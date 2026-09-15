ALTER TABLE document_scan_attestations
    ADD CONSTRAINT document_scan_attestations_promotion_link_unique
    UNIQUE (organization_id, attestation_id, document_id, object_version_id);

CREATE TABLE document_promotion_evidence (
    organization_id uuid NOT NULL,
    document_id uuid NOT NULL,
    object_version_id uuid NOT NULL,
    scan_attestation_id uuid NOT NULL,
    promotion_policy_key varchar(120) NOT NULL,
    accepted_scanner_keys varchar(2047) NOT NULL,
    maximum_scan_age_seconds integer NOT NULL,
    maximum_future_skew_seconds integer NOT NULL,
    promoted_by_actor_id uuid NOT NULL,
    purpose varchar(128) NOT NULL,
    correlation_id varchar(128) NOT NULL,
    promoted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, document_id, object_version_id),
    CONSTRAINT document_promotion_evidence_scan_fk
        FOREIGN KEY (organization_id, scan_attestation_id, document_id, object_version_id)
        REFERENCES document_scan_attestations
            (organization_id, attestation_id, document_id, object_version_id),
    CHECK (promotion_policy_key ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (accepted_scanner_keys ~
        '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*(,[a-z][a-z0-9]*([.:-][a-z0-9]+)*){0,15}$'),
    CHECK (maximum_scan_age_seconds BETWEEN 1 AND 2592000),
    CHECK (maximum_future_skew_seconds BETWEEN 0 AND 300),
    CHECK (purpose ~ '^[a-z0-9][a-z0-9._:-]{0,127}$'),
    CHECK (correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CHECK (isfinite(promoted_at))
);

ALTER TABLE document_promotion_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_promotion_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY document_promotion_evidence_tenant_policy ON document_promotion_evidence
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

CREATE FUNCTION careos_validate_document_promotion_evidence_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    attested_verdict text;
    attested_scanner_key text;
    attested_scanned_at timestamptz;
    latest_attestation_id uuid;
BEGIN
    IF NEW.organization_id IS DISTINCT FROM
            nullif(current_setting('app.current_organization_id', true), '')::uuid
        OR NEW.promoted_by_actor_id IS DISTINCT FROM
            nullif(current_setting('app.current_actor_id', true), '')::uuid
        OR NEW.purpose IS DISTINCT FROM
            nullif(current_setting('app.current_purpose', true), '')
        OR NEW.correlation_id IS DISTINCT FROM
            nullif(current_setting('app.current_correlation_id', true), '') THEN
        RAISE EXCEPTION 'document promotion evidence does not match the authorized transaction'
            USING ERRCODE = '42501';
    END IF;

    SELECT scans.attestation_id, scans.verdict, scans.scanner_key, scans.scanned_at
    INTO latest_attestation_id, attested_verdict, attested_scanner_key, attested_scanned_at
    FROM document_scan_attestations scans
    WHERE scans.organization_id = NEW.organization_id
      AND scans.document_id = NEW.document_id
      AND scans.object_version_id = NEW.object_version_id
    ORDER BY scans.scanned_at DESC, scans.recorded_at DESC, scans.attestation_id DESC
    LIMIT 1;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'document scan attestation does not exist'
            USING ERRCODE = '23503';
    END IF;
    IF latest_attestation_id IS DISTINCT FROM NEW.scan_attestation_id THEN
        RAISE EXCEPTION 'document promotion requires the latest scan attestation'
            USING ERRCODE = '23514';
    END IF;
    IF attested_verdict <> 'clean' THEN
        RAISE EXCEPTION 'document promotion requires a clean scan attestation'
            USING ERRCODE = '23514';
    END IF;
    IF attested_scanner_key <> ALL(string_to_array(NEW.accepted_scanner_keys, ',')) THEN
        RAISE EXCEPTION 'document promotion scanner is not accepted by the policy snapshot'
            USING ERRCODE = '23514';
    END IF;

    NEW.promoted_at := clock_timestamp();
    IF attested_scanned_at <
            NEW.promoted_at - make_interval(secs => NEW.maximum_scan_age_seconds)
        OR attested_scanned_at >
            NEW.promoted_at + make_interval(secs => NEW.maximum_future_skew_seconds) THEN
        RAISE EXCEPTION 'document scan attestation is outside the promotion policy window'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER document_promotion_evidence_validate_insert
    BEFORE INSERT ON document_promotion_evidence
    FOR EACH ROW EXECUTE FUNCTION careos_validate_document_promotion_evidence_insert();

CREATE TRIGGER document_promotion_evidence_reject_mutation
    BEFORE UPDATE OR DELETE ON document_promotion_evidence
    FOR EACH ROW EXECUTE FUNCTION careos_reject_document_security_evidence_mutation();

REVOKE ALL ON document_promotion_evidence FROM PUBLIC;
GRANT SELECT, INSERT ON document_promotion_evidence TO "${applicationRole}";

COMMENT ON TABLE document_promotion_evidence IS
    'Append-only tenant-scoped proof that an exact clean scan satisfied a snapshotted promotion policy.';
