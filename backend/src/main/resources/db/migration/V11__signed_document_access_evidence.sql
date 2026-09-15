CREATE TABLE document_access_grant_evidence (
    organization_id uuid NOT NULL,
    access_grant_id uuid NOT NULL,
    document_id uuid NOT NULL,
    object_version_id uuid NOT NULL,
    access_policy_key varchar(120) NOT NULL,
    accepted_purposes varchar(2063) NOT NULL,
    requested_ttl_seconds integer NOT NULL,
    maximum_ttl_seconds integer NOT NULL,
    maximum_authorization_age_seconds integer NOT NULL,
    maximum_future_skew_seconds integer NOT NULL,
    authorized_at timestamptz NOT NULL,
    granted_to_actor_id uuid NOT NULL,
    purpose varchar(128) NOT NULL,
    correlation_id varchar(128) NOT NULL,
    granted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (organization_id, access_grant_id),
    CONSTRAINT document_access_grant_evidence_promotion_fk
        FOREIGN KEY (organization_id, document_id, object_version_id)
        REFERENCES document_promotion_evidence
            (organization_id, document_id, object_version_id),
    CHECK (access_policy_key ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (accepted_purposes ~
        '^[a-z0-9][a-z0-9._:-]{0,127}(,[a-z0-9][a-z0-9._:-]{0,127}){0,15}$'),
    CHECK (requested_ttl_seconds BETWEEN 1 AND 3600),
    CHECK (maximum_ttl_seconds BETWEEN 1 AND 3600),
    CHECK (requested_ttl_seconds <= maximum_ttl_seconds),
    CHECK (maximum_authorization_age_seconds BETWEEN 1 AND 300),
    CHECK (maximum_future_skew_seconds BETWEEN 0 AND 60),
    CHECK (purpose ~ '^[a-z0-9][a-z0-9._:-]{0,127}$'),
    CHECK (correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CHECK (isfinite(authorized_at)),
    CHECK (isfinite(granted_at)),
    CHECK (isfinite(expires_at))
);

ALTER TABLE document_access_grant_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_access_grant_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY document_access_grant_evidence_tenant_policy
    ON document_access_grant_evidence
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

CREATE FUNCTION careos_validate_document_access_grant_evidence_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    promotion_time timestamptz;
BEGIN
    IF NEW.organization_id IS DISTINCT FROM
            nullif(current_setting('app.current_organization_id', true), '')::uuid
        OR NEW.granted_to_actor_id IS DISTINCT FROM
            nullif(current_setting('app.current_actor_id', true), '')::uuid
        OR NEW.purpose IS DISTINCT FROM
            nullif(current_setting('app.current_purpose', true), '')
        OR NEW.correlation_id IS DISTINCT FROM
            nullif(current_setting('app.current_correlation_id', true), '') THEN
        RAISE EXCEPTION 'document access evidence does not match the authorized transaction'
            USING ERRCODE = '42501';
    END IF;

    IF NEW.purpose <> ALL(string_to_array(NEW.accepted_purposes, ',')) THEN
        RAISE EXCEPTION 'document access purpose is not accepted by the policy snapshot'
            USING ERRCODE = '23514';
    END IF;

    SELECT promotions.promoted_at
    INTO promotion_time
    FROM document_promotion_evidence promotions
    WHERE promotions.organization_id = NEW.organization_id
      AND promotions.document_id = NEW.document_id
      AND promotions.object_version_id = NEW.object_version_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'document access requires promotion evidence'
            USING ERRCODE = '23503';
    END IF;

    NEW.granted_at := clock_timestamp();
    IF NEW.authorized_at <
            NEW.granted_at - make_interval(secs => NEW.maximum_authorization_age_seconds)
        OR NEW.authorized_at >
            NEW.granted_at + make_interval(secs => NEW.maximum_future_skew_seconds) THEN
        RAISE EXCEPTION 'document access authorization is outside the policy window'
            USING ERRCODE = '23514';
    END IF;
    IF promotion_time >
            NEW.granted_at + make_interval(secs => NEW.maximum_future_skew_seconds) THEN
        RAISE EXCEPTION 'document promotion evidence is implausibly in the future'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.expires_at IS DISTINCT FROM
            NEW.authorized_at + make_interval(secs => NEW.requested_ttl_seconds)
        OR NEW.expires_at <= NEW.granted_at THEN
        RAISE EXCEPTION 'document access expiry does not match the bounded authorization'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER document_access_grant_evidence_validate_insert
    BEFORE INSERT ON document_access_grant_evidence
    FOR EACH ROW EXECUTE FUNCTION careos_validate_document_access_grant_evidence_insert();

CREATE TRIGGER document_access_grant_evidence_reject_mutation
    BEFORE UPDATE OR DELETE ON document_access_grant_evidence
    FOR EACH ROW EXECUTE FUNCTION careos_reject_document_security_evidence_mutation();

REVOKE ALL ON document_access_grant_evidence FROM PUBLIC;
GRANT SELECT, INSERT ON document_access_grant_evidence TO "${applicationRole}";

COMMENT ON TABLE document_access_grant_evidence IS
    'Append-only tenant-scoped evidence for bounded signed access to promoted clean content; bearer URLs are never stored.';
