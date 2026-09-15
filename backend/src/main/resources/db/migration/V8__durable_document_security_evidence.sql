CREATE TABLE document_quarantine_evidence (
    organization_id uuid NOT NULL REFERENCES organizations(id),
    document_id uuid NOT NULL,
    object_version_id uuid NOT NULL,
    declared_bytes bigint NOT NULL,
    media_type varchar(255) NOT NULL,
    sha256 varchar(64) NOT NULL,
    recorded_by_actor_id uuid NOT NULL,
    purpose varchar(128) NOT NULL,
    correlation_id varchar(128) NOT NULL,
    quarantined_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, document_id, object_version_id),
    CHECK (declared_bytes > 0),
    CHECK (media_type ~ '^[^[:cntrl:][:space:]/]+/[^[:cntrl:][:space:]/]+$'),
    CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (purpose ~ '^[a-z0-9][a-z0-9._:-]{0,127}$'),
    CHECK (correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CHECK (isfinite(quarantined_at))
);

CREATE TABLE document_scan_attestations (
    organization_id uuid NOT NULL,
    attestation_id uuid NOT NULL,
    document_id uuid NOT NULL,
    object_version_id uuid NOT NULL,
    verdict varchar(16) NOT NULL,
    scanner_key varchar(120) NOT NULL,
    definitions_version varchar(120) NOT NULL,
    sha256 varchar(64) NOT NULL,
    scanned_at timestamptz NOT NULL,
    recorded_by_actor_id uuid NOT NULL,
    purpose varchar(128) NOT NULL,
    correlation_id varchar(128) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, attestation_id),
    CONSTRAINT document_scan_attestations_object_fk
        FOREIGN KEY (organization_id, document_id, object_version_id)
        REFERENCES document_quarantine_evidence (organization_id, document_id, object_version_id),
    CONSTRAINT document_scan_attestations_observation_unique
        UNIQUE (organization_id, document_id, object_version_id, scanner_key, scanned_at),
    CHECK (verdict IN ('clean', 'infected', 'error')),
    CHECK (scanner_key ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (definitions_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]*$'),
    CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (purpose ~ '^[a-z0-9][a-z0-9._:-]{0,127}$'),
    CHECK (correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CHECK (isfinite(scanned_at)),
    CHECK (isfinite(recorded_at))
);

CREATE INDEX document_scan_attestations_latest_idx
    ON document_scan_attestations
        (organization_id, document_id, object_version_id,
         scanned_at DESC, recorded_at DESC, attestation_id DESC);

ALTER TABLE document_quarantine_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_quarantine_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY document_quarantine_evidence_tenant_policy ON document_quarantine_evidence
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

ALTER TABLE document_scan_attestations ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_scan_attestations FORCE ROW LEVEL SECURITY;
CREATE POLICY document_scan_attestations_tenant_policy ON document_scan_attestations
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

CREATE FUNCTION careos_validate_document_object_evidence_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.organization_id IS DISTINCT FROM
            nullif(current_setting('app.current_organization_id', true), '')::uuid
        OR NEW.recorded_by_actor_id IS DISTINCT FROM
            nullif(current_setting('app.current_actor_id', true), '')::uuid
        OR NEW.purpose IS DISTINCT FROM
            nullif(current_setting('app.current_purpose', true), '')
        OR NEW.correlation_id IS DISTINCT FROM
            nullif(current_setting('app.current_correlation_id', true), '') THEN
        RAISE EXCEPTION 'document quarantine evidence does not match the authorized transaction'
            USING ERRCODE = '42501';
    END IF;
    NEW.quarantined_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_validate_document_scan_attestation_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    expected_sha256 text;
BEGIN
    IF NEW.organization_id IS DISTINCT FROM
            nullif(current_setting('app.current_organization_id', true), '')::uuid
        OR NEW.recorded_by_actor_id IS DISTINCT FROM
            nullif(current_setting('app.current_actor_id', true), '')::uuid
        OR NEW.purpose IS DISTINCT FROM
            nullif(current_setting('app.current_purpose', true), '')
        OR NEW.correlation_id IS DISTINCT FROM
            nullif(current_setting('app.current_correlation_id', true), '') THEN
        RAISE EXCEPTION 'document scan attestation does not match the authorized transaction'
            USING ERRCODE = '42501';
    END IF;

    SELECT objects.sha256
    INTO expected_sha256
    FROM document_quarantine_evidence objects
    WHERE objects.organization_id = NEW.organization_id
      AND objects.document_id = NEW.document_id
      AND objects.object_version_id = NEW.object_version_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'document quarantine evidence does not exist'
            USING ERRCODE = '23503';
    END IF;
    IF NEW.verdict <> 'error' AND NEW.sha256 IS DISTINCT FROM expected_sha256 THEN
        RAISE EXCEPTION 'successful document scan digest does not match quarantine evidence'
            USING ERRCODE = '23514';
    END IF;
    NEW.recorded_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_reject_document_security_evidence_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'document security evidence is append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER document_quarantine_evidence_validate_insert
    BEFORE INSERT ON document_quarantine_evidence
    FOR EACH ROW EXECUTE FUNCTION careos_validate_document_object_evidence_insert();

CREATE TRIGGER document_quarantine_evidence_reject_mutation
    BEFORE UPDATE OR DELETE ON document_quarantine_evidence
    FOR EACH ROW EXECUTE FUNCTION careos_reject_document_security_evidence_mutation();

CREATE TRIGGER document_scan_attestations_validate_insert
    BEFORE INSERT ON document_scan_attestations
    FOR EACH ROW EXECUTE FUNCTION careos_validate_document_scan_attestation_insert();

CREATE TRIGGER document_scan_attestations_reject_mutation
    BEFORE UPDATE OR DELETE ON document_scan_attestations
    FOR EACH ROW EXECUTE FUNCTION careos_reject_document_security_evidence_mutation();

REVOKE ALL ON document_quarantine_evidence FROM PUBLIC;
REVOKE ALL ON document_scan_attestations FROM PUBLIC;
GRANT SELECT, INSERT ON document_quarantine_evidence TO "${applicationRole}";
GRANT SELECT, INSERT ON document_scan_attestations TO "${applicationRole}";

COMMENT ON TABLE document_quarantine_evidence IS
    'Append-only tenant-scoped metadata proving the exact object version accepted into private quarantine.';
COMMENT ON TABLE document_scan_attestations IS
    'Append-only tenant-scoped scanner observations; error verdicts may carry an unknown digest and can never authorize promotion.';
