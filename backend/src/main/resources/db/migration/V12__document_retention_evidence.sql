CREATE TABLE document_retention_evidence (
    organization_id uuid NOT NULL,
    retention_directive_id uuid NOT NULL,
    document_id uuid NOT NULL,
    object_version_id uuid NOT NULL,
    previous_retention_directive_id uuid,
    retention_policy_key varchar(160) NOT NULL,
    accepted_purposes varchar(2063) NOT NULL,
    minimum_retention_seconds bigint NOT NULL,
    maximum_retention_seconds bigint NOT NULL,
    maximum_authorization_age_seconds integer NOT NULL,
    maximum_future_skew_seconds integer NOT NULL,
    retain_until timestamptz NOT NULL,
    legal_hold boolean NOT NULL,
    retention_mode varchar(16) NOT NULL,
    storage_version_sha256 varchar(64) NOT NULL,
    authorized_at timestamptz NOT NULL,
    applied_by_actor_id uuid NOT NULL,
    purpose varchar(128) NOT NULL,
    correlation_id varchar(128) NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, retention_directive_id),
    CONSTRAINT document_retention_evidence_document_unique
        UNIQUE (organization_id, retention_directive_id, document_id, object_version_id),
    CONSTRAINT document_retention_evidence_promotion_fk
        FOREIGN KEY (organization_id, document_id, object_version_id)
        REFERENCES document_promotion_evidence
            (organization_id, document_id, object_version_id),
    CONSTRAINT document_retention_evidence_previous_fk
        FOREIGN KEY (
            organization_id, previous_retention_directive_id,
            document_id, object_version_id)
        REFERENCES document_retention_evidence
            (organization_id, retention_directive_id, document_id, object_version_id),
    CHECK (previous_retention_directive_id IS DISTINCT FROM retention_directive_id),
    CHECK (retention_policy_key ~ '^[a-z][a-z0-9]*([.:-][a-z0-9]+)*$'),
    CHECK (accepted_purposes ~
        '^[a-z0-9][a-z0-9._:-]{0,127}(,[a-z0-9][a-z0-9._:-]{0,127}){0,15}$'),
    CHECK (minimum_retention_seconds BETWEEN 1 AND 3155760000),
    CHECK (maximum_retention_seconds BETWEEN minimum_retention_seconds AND 3155760000),
    CHECK (maximum_authorization_age_seconds BETWEEN 1 AND 300),
    CHECK (maximum_future_skew_seconds BETWEEN 0 AND 60),
    CHECK (retention_mode = 'compliance'),
    CHECK (storage_version_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (purpose ~ '^[a-z0-9][a-z0-9._:-]{0,127}$'),
    CHECK (correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CHECK (date_trunc('second', retain_until) = retain_until),
    CHECK (isfinite(retain_until)),
    CHECK (isfinite(authorized_at)),
    CHECK (isfinite(applied_at))
);

CREATE INDEX document_retention_evidence_latest_idx
    ON document_retention_evidence
        (organization_id, document_id, object_version_id,
         applied_at DESC, retention_directive_id DESC);

ALTER TABLE document_retention_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_retention_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY document_retention_evidence_tenant_policy
    ON document_retention_evidence
    USING (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.current_organization_id', true), '')::uuid);

CREATE FUNCTION careos_validate_document_retention_evidence_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    promotion_time timestamptz;
    current_directive_id uuid;
    current_retain_until timestamptz;
    current_legal_hold boolean;
    canonical_purposes text;
BEGIN
    IF NEW.organization_id IS DISTINCT FROM
            nullif(current_setting('app.current_organization_id', true), '')::uuid
        OR NEW.applied_by_actor_id IS DISTINCT FROM
            nullif(current_setting('app.current_actor_id', true), '')::uuid
        OR NEW.purpose IS DISTINCT FROM
            nullif(current_setting('app.current_purpose', true), '')
        OR NEW.correlation_id IS DISTINCT FROM
            nullif(current_setting('app.current_correlation_id', true), '') THEN
        RAISE EXCEPTION 'document retention evidence does not match the authorized transaction'
            USING ERRCODE = '42501';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
        NEW.organization_id::text || ':' || NEW.document_id::text || ':' ||
            NEW.object_version_id::text,
        0));

    IF EXISTS (
        SELECT 1
        FROM document_retention_evidence existing
        WHERE existing.organization_id = NEW.organization_id
          AND existing.retention_directive_id = NEW.retention_directive_id
    ) THEN
        RETURN NEW;
    END IF;

    SELECT string_agg(values.purpose, ',' ORDER BY values.purpose)
    INTO canonical_purposes
    FROM unnest(string_to_array(NEW.accepted_purposes, ',')) AS values(purpose);
    IF NEW.accepted_purposes IS DISTINCT FROM canonical_purposes
        OR NEW.purpose <> ALL(string_to_array(NEW.accepted_purposes, ',')) THEN
        RAISE EXCEPTION 'document retention purpose policy snapshot is invalid'
            USING ERRCODE = '23514';
    END IF;

    SELECT promotions.promoted_at
    INTO promotion_time
    FROM document_promotion_evidence promotions
    WHERE promotions.organization_id = NEW.organization_id
      AND promotions.document_id = NEW.document_id
      AND promotions.object_version_id = NEW.object_version_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'document retention requires promotion evidence'
            USING ERRCODE = '23503';
    END IF;

    NEW.applied_at := clock_timestamp();
    IF NEW.authorized_at <
            NEW.applied_at - make_interval(secs => NEW.maximum_authorization_age_seconds)
        OR NEW.authorized_at >
            NEW.applied_at + make_interval(secs => NEW.maximum_future_skew_seconds) THEN
        RAISE EXCEPTION 'document retention authorization is outside the policy window'
            USING ERRCODE = '23514';
    END IF;
    IF promotion_time >
            NEW.applied_at + make_interval(secs => NEW.maximum_future_skew_seconds)
        OR NEW.authorized_at <
            promotion_time - make_interval(secs => NEW.maximum_future_skew_seconds) THEN
        RAISE EXCEPTION 'document retention promotion evidence is temporally invalid'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.retain_until <
            NEW.authorized_at + make_interval(secs => NEW.minimum_retention_seconds)
        OR NEW.retain_until >
            NEW.authorized_at + make_interval(secs => NEW.maximum_retention_seconds)
        OR NEW.retain_until <= NEW.applied_at THEN
        RAISE EXCEPTION 'document retention deadline is outside the policy window'
            USING ERRCODE = '23514';
    END IF;

    SELECT evidence.retention_directive_id, evidence.retain_until, evidence.legal_hold
    INTO current_directive_id, current_retain_until, current_legal_hold
    FROM document_retention_evidence evidence
    WHERE evidence.organization_id = NEW.organization_id
      AND evidence.document_id = NEW.document_id
      AND evidence.object_version_id = NEW.object_version_id
    ORDER BY evidence.applied_at DESC, evidence.retention_directive_id DESC
    LIMIT 1;

    IF FOUND THEN
        IF NEW.previous_retention_directive_id IS DISTINCT FROM current_directive_id THEN
            RAISE EXCEPTION 'document retention predecessor is stale'
                USING ERRCODE = '40001';
        END IF;
        IF NEW.retain_until < current_retain_until THEN
            RAISE EXCEPTION 'document retention cannot be shortened'
                USING ERRCODE = '23514';
        END IF;
        IF current_legal_hold AND NOT NEW.legal_hold THEN
            RAISE EXCEPTION 'document legal hold cannot be released by this boundary'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.retain_until = current_retain_until
            AND NEW.legal_hold = current_legal_hold THEN
            RAISE EXCEPTION 'document retention directive makes no change'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.previous_retention_directive_id IS NOT NULL THEN
        RAISE EXCEPTION 'first document retention directive cannot name a predecessor'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER document_retention_evidence_validate_insert
    BEFORE INSERT ON document_retention_evidence
    FOR EACH ROW EXECUTE FUNCTION careos_validate_document_retention_evidence_insert();

CREATE TRIGGER document_retention_evidence_reject_mutation
    BEFORE UPDATE OR DELETE ON document_retention_evidence
    FOR EACH ROW EXECUTE FUNCTION careos_reject_document_security_evidence_mutation();

REVOKE ALL ON document_retention_evidence FROM PUBLIC;
GRANT SELECT, INSERT ON document_retention_evidence TO "${applicationRole}";

COMMENT ON TABLE document_retention_evidence IS
    'Append-only tenant-scoped evidence that COMPLIANCE retention or legal hold was applied to an exact promoted storage version; release and disposal are out of scope.';
