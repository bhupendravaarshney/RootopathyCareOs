-- Merge decisions are immutable except for their single, evidence-preserving
-- approved -> consumed transition. The original V70 append-only trigger made
-- the modelled consumption fields unreachable and left an approved decision
-- appearing reusable after execution.
ALTER TABLE patient_merge_decisions
    DROP CONSTRAINT patient_merge_decisions_lock_version_check;

ALTER TABLE patient_merge_decisions
    ADD CONSTRAINT patient_merge_decisions_lock_version_check
    CHECK (lock_version >= 0);

DROP TRIGGER patient_merge_decisions_append_only ON patient_merge_decisions;

CREATE OR REPLACE FUNCTION careos_guard_m3_merge_decision_evidence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_organization uuid :=
        nullif(current_setting('app.current_organization_id', true), '')::uuid;
    configured_actor uuid :=
        nullif(current_setting('app.current_actor_id', true), '')::uuid;
    configured_operation text :=
        nullif(current_setting('app.current_operation_key', true), '');
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Module 3 merge decision evidence is append-only'
            USING ERRCODE = '55000';
    END IF;

    IF current_user <> '${applicationRole}'
       OR configured_operation IS DISTINCT FROM 'patient.merge.execute'
       OR configured_organization IS DISTINCT FROM OLD.organization_id
       OR configured_actor IS NULL
       OR OLD.status IS DISTINCT FROM 'approved'
       OR NEW.status IS DISTINCT FROM 'consumed'
       OR NEW.consumed_by IS DISTINCT FROM configured_actor
       OR NEW.consumed_at IS NULL
       OR NEW.consumed_at < OLD.created_at
       OR NEW.consumed_at > OLD.decision_expires_at
       OR NEW.consumed_at > clock_timestamp() + interval '5 seconds'
       OR configured_actor = OLD.checker_id
       OR ROW(
            NEW.id,NEW.organization_id,NEW.merge_request_id,
            NEW.request_revision,NEW.impact_digest,NEW.decision,
            NEW.reason_code,NEW.checker_id,NEW.assurance_reference,
            NEW.decision_expires_at,NEW.correction_reference,
            NEW.provenance_source,NEW.classification,NEW.policy_version,
            NEW.created_at,NEW.created_by
          ) IS DISTINCT FROM ROW(
            OLD.id,OLD.organization_id,OLD.merge_request_id,
            OLD.request_revision,OLD.impact_digest,OLD.decision,
            OLD.reason_code,OLD.checker_id,OLD.assurance_reference,
            OLD.decision_expires_at,OLD.correction_reference,
            OLD.provenance_source,OLD.classification,OLD.policy_version,
            OLD.created_at,OLD.created_by
          )
       OR NOT EXISTS (
            SELECT 1
            FROM patient_merge_requests request
            WHERE request.organization_id = OLD.organization_id
              AND request.id = OLD.merge_request_id
              AND request.requested_by <> configured_actor
              AND request.status = 'approved'
          ) THEN
        RAISE EXCEPTION 'invalid Module 3 merge decision consumption'
            USING ERRCODE = '42501';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER patient_merge_decisions_evidence_guard
    BEFORE UPDATE OR DELETE ON patient_merge_decisions
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m3_merge_decision_evidence();

GRANT UPDATE ON patient_merge_decisions TO "${applicationRole}";

COMMENT ON FUNCTION careos_guard_m3_merge_decision_evidence() IS
    'Preserves immutable merge-decision evidence while allowing one exact, independently executed consumption transition.';
