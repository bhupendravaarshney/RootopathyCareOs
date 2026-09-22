-- The approved event registry defines two internal consumers whose identities are
-- deliberately narrower than a browser role. Reuse the related approved service
-- roles and their exact operations so production service authorization does not
-- depend on the disabled Phase 0 reference-consumer catalogue.
INSERT INTO authorization_role_permissions (role_key,permission_key)
VALUES
    ('service_m2_eligibility','workforce.validation.run'),
    ('service_m2_outbox','workforce.history.read')
ON CONFLICT (role_key,permission_key) DO NOTHING;

-- These predecessor events are committed by the same approved decision action
-- as their successor event. Keep the event definitions unchanged and authorize
-- only the two additional action/event pairs required for atomic supersession.
INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES
    ('credential.review.decide','audit','credential.record.superseded',1,'active','m2-candidate-1'),
    ('credential.review.decide','outbox','credential.record.superseded',1,'active','m2-candidate-1'),
    ('credential.document.upload','audit','credential.record.updated',1,'active','m2-candidate-1'),
    ('practitioner.scope.approve','audit','practitioner.scope.superseded',1,'active','m2-candidate-1'),
    ('practitioner.scope.approve','outbox','practitioner.scope.superseded',1,'active','m2-candidate-1')
ON CONFLICT DO NOTHING;

-- The approved upload mockup fixes the document limit at 20 MiB. Narrow the
-- original table bound without changing the checksum-bound approved inputs.
ALTER TABLE credential_documents
    DROP CONSTRAINT credential_documents_declared_size_check,
    ADD CONSTRAINT credential_documents_declared_size_check
        CHECK (declared_size BETWEEN 1 AND 20971520);

-- A future-dated approved offboarding plan is a scheduled plan. Keep the
-- decision and its due state in one statement so the worker can later claim it
-- without relying on an in-memory timer.
CREATE OR REPLACE FUNCTION careos_guard_offboarding_request_lifecycle()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF TG_OP='INSERT' THEN
        IF operation_key<>'workforce.offboarding.request' OR NEW.status<>'submitted'
           OR NEW.checker_id IS NOT NULL OR NEW.failure_code IS NOT NULL
           OR NEW.attempt_count<>0 OR NEW.next_attempt_at IS NOT NULL
           OR NEW.dead_lettered_at IS NOT NULL OR NEW.dead_letter_owner IS NOT NULL THEN
            RAISE EXCEPTION 'invalid offboarding request creation'
                USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status IN ('completed','cancelled') OR OLD.dead_lettered_at IS NOT NULL THEN
        RAISE EXCEPTION 'terminal offboarding request is immutable'
            USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.workforce_member_id,NEW.engagement_end_at,NEW.effective_at,
           NEW.reason_entry_id,NEW.reason_version_id,NEW.impact_digest,
           NEW.maker_id,NEW.access_action,NEW.assignment_action,
           NEW.service_action,NEW.handover_reference)
       IS DISTINCT FROM
       ROW(OLD.workforce_member_id,OLD.engagement_end_at,OLD.effective_at,
           OLD.reason_entry_id,OLD.reason_version_id,OLD.impact_digest,
           OLD.maker_id,OLD.access_action,OLD.assignment_action,
           OLD.service_action,OLD.handover_reference) THEN
        RAISE EXCEPTION 'offboarding plan evidence is immutable'
            USING ERRCODE='55000';
    END IF;
    IF NOT (
        (OLD.status='submitted' AND NEW.status IN ('approved','scheduled')
         AND operation_key='workforce.offboarding.approve'
         AND OLD.checker_id IS NULL AND NEW.checker_id IS NOT NULL
         AND NEW.checker_id<>OLD.maker_id
         AND NEW.failure_code IS NULL AND NEW.attempt_count=OLD.attempt_count
         AND ((NEW.status='scheduled' AND NEW.effective_at>statement_timestamp())
           OR (NEW.status='approved' AND NEW.effective_at<=statement_timestamp()))) OR
        (OLD.status IN ('approved','scheduled','failed') AND NEW.status='failed'
         AND operation_key IN ('workforce.offboarding.execute','m2.offboarding.execute')
         AND NEW.checker_id=OLD.checker_id
         AND NEW.attempt_count=LEAST(OLD.attempt_count+1,5)
         AND NEW.failure_code IS NOT NULL
         AND ((NEW.attempt_count<5 AND NEW.next_attempt_at IS NOT NULL
                                    AND NEW.dead_lettered_at IS NULL
                                    AND NEW.dead_letter_owner IS NULL)
           OR (NEW.attempt_count=5 AND NEW.next_attempt_at IS NULL
                                    AND NEW.dead_lettered_at IS NOT NULL
                                    AND NEW.dead_letter_owner='workforce_operations'))) OR
        (OLD.status IN ('approved','scheduled','failed') AND NEW.status='completed'
         AND operation_key IN ('workforce.offboarding.execute','m2.offboarding.execute')
         AND NEW.checker_id=OLD.checker_id
         AND NEW.attempt_count=OLD.attempt_count
         AND NEW.failure_code IS NULL AND NEW.next_attempt_at IS NULL
         AND NEW.dead_lettered_at IS NULL AND NEW.dead_letter_owner IS NULL)
    ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
