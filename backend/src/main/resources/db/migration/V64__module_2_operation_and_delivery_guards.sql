-- Bind every Module 2 write to the exact operation that owns the target table.
-- RLS establishes the tenant boundary; this allowlist prevents a valid operation
-- from being reused to mutate an unrelated aggregate through direct SQL.
CREATE OR REPLACE FUNCTION careos_validate_m2_tenant_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_organization uuid :=
        nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid :=
        nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text :=
        nullif(current_setting('app.current_operation_key',true),'');
    operation_allowed boolean := false;
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;
    IF NEW.organization_id IS DISTINCT FROM configured_organization
       OR configured_actor IS NULL
       OR configured_operation IS NULL
       OR NOT EXISTS (
           SELECT 1
           FROM authorization_operations operation
           JOIN authorization_registry_releases release
             ON release.registry_version=operation.registry_version
            AND release.status='active'
           WHERE operation.operation_key=configured_operation
             AND operation.registry_version='m2-candidate-1'
             AND operation.status='active') THEN
        RAISE EXCEPTION 'invalid Module 2 tenant write context'
            USING ERRCODE='42501';
    END IF;

    operation_allowed := CASE TG_TABLE_NAME
        WHEN 'organization_person_links' THEN configured_operation IN (
            'workforce.member.create','workforce.person.match',
            'workforce.person.merge.execute')
        WHEN 'person_match_keys' THEN configured_operation IN (
            'workforce.member.create','workforce.person.match','workforce.person.correct',
            'workforce.person.merge.execute')
        WHEN 'person_merge_requests' THEN configured_operation IN (
            'workforce.person.merge.request','workforce.person.merge.approve',
            'workforce.person.merge.execute')
        WHEN 'workforce_members' THEN configured_operation IN (
            'workforce.member.create','workforce.member.manage','workforce.person.match',
            'workforce.person.merge.execute','workforce.account_link.request',
            'workforce.validation.run','workforce.activation.submit',
            'workforce.activation.execute','workforce.lifecycle.suspend',
            'workforce.lifecycle.reactivate','workforce.offboarding.execute',
            'm2.offboarding.execute')
        WHEN 'workforce_identifiers' THEN configured_operation IN (
            'workforce.member.create','workforce.member.manage','workforce.person.match')
        WHEN 'employment_engagements' THEN configured_operation IN (
            'workforce.engagement.manage','workforce.engagement.lifecycle',
            'workforce.offboarding.execute','m2.offboarding.execute')
        WHEN 'practitioner_profiles' THEN configured_operation IN (
            'workforce.practitioner.manage','workforce.practitioner.lifecycle',
            'workforce.lifecycle.suspend','workforce.lifecycle.reactivate',
            'workforce.offboarding.execute','m2.offboarding.execute')
        WHEN 'professional_registrations' THEN configured_operation IN (
            'credential.registration.manage','credential.registration.lifecycle',
            'm2.expiry.process')
        WHEN 'qualifications' THEN
            configured_operation='credential.qualification.manage'
        WHEN 'practitioner_specialties' THEN
            configured_operation='practitioner.specialty.manage'
        WHEN 'practitioner_credentials' THEN configured_operation IN (
            'credential.record.manage','credential.document.upload',
            'credential.review.claim','credential.review.decide',
            'credential.lifecycle','m2.expiry.process')
        WHEN 'credential_documents' THEN configured_operation IN (
            'credential.document.upload','m2.credential.scan.bind')
        WHEN 'credential_scan_attempts' THEN
            configured_operation='m2.credential.scan.bind'
        WHEN 'credential_verifications' THEN
            configured_operation='credential.review.decide'
        WHEN 'scope_definitions' THEN
            configured_operation='practitioner.scope.manage'
        WHEN 'scope_requirements' THEN
            configured_operation='practitioner.scope.manage'
        WHEN 'scopes_of_practice' THEN configured_operation IN (
            'practitioner.scope.manage','practitioner.scope.submit',
            'practitioner.scope.approve','practitioner.scope.lifecycle')
        WHEN 'scope_activities' THEN
            configured_operation='practitioner.scope.manage'
        WHEN 'scope_restrictions' THEN
            configured_operation='practitioner.scope.manage'
        WHEN 'workforce_assignments' THEN configured_operation IN (
            'workforce.assignment.manage','workforce.assignment.lifecycle',
            'workforce.offboarding.execute','m2.offboarding.execute')
        WHEN 'practitioner_service_assignments' THEN configured_operation IN (
            'practitioner.service_assignment.manage',
            'practitioner.service_assignment.lifecycle','m2.eligibility.evaluate',
            'credential.registration.lifecycle','credential.lifecycle',
            'practitioner.scope.lifecycle','workforce.lifecycle.suspend',
            'workforce.lifecycle.reactivate','workforce.offboarding.execute',
            'm2.offboarding.execute')
        WHEN 'availability_profiles' THEN configured_operation IN (
            'workforce.availability.manage','workforce.offboarding.execute',
            'm2.offboarding.execute')
        WHEN 'availability_periods' THEN
            configured_operation='workforce.availability.manage'
        WHEN 'availability_exceptions' THEN
            configured_operation='workforce.availability.manage'
        WHEN 'access_assignment_scopes' THEN configured_operation IN (
            'workforce.account_link.request','workforce.offboarding.execute',
            'm2.offboarding.execute')
        WHEN 'workforce_readiness_runs' THEN
            configured_operation='workforce.validation.run'
        WHEN 'workforce_readiness_results' THEN
            configured_operation='workforce.validation.run'
        WHEN 'workforce_activation_requests' THEN configured_operation IN (
            'workforce.activation.submit','workforce.activation.approve',
            'workforce.activation.execute')
        WHEN 'workforce_offboarding_requests' THEN configured_operation IN (
            'workforce.offboarding.request','workforce.offboarding.approve',
            'workforce.offboarding.execute','m2.offboarding.execute')
        WHEN 'workforce_lifecycle_transitions' THEN configured_operation IN (
            'workforce.activation.submit','workforce.activation.execute',
            'workforce.lifecycle.suspend','workforce.lifecycle.reactivate',
            'workforce.offboarding.execute','m2.offboarding.execute')
        WHEN 'workforce_configuration_snapshots' THEN
            configured_operation='workforce.registry.activate'
        WHEN 'workforce_configuration_change_requests' THEN configured_operation IN (
            'workforce.registry.manage','workforce.registry.approve',
            'workforce.registry.activate')
        WHEN 'workforce_configuration_change_items' THEN configured_operation IN (
            'workforce.registry.manage','workforce.registry.approve',
            'workforce.registry.activate')
        WHEN 'workforce_export_jobs' THEN configured_operation IN (
            'workforce.export.request','workforce.export.approve',
            'workforce.export.access','m2.export.generate','m2.retention.dispose')
        WHEN 'credential_legal_holds' THEN
            configured_operation='credential.lifecycle'
        WHEN 'practitioner_eligibility_evidence' THEN
            configured_operation='m2.eligibility.evaluate'
        WHEN 'workforce_notification_deliveries' THEN configured_operation IN (
            'workforce.expiry.escalate','m2.expiry.process','m2.notification.deliver')
        WHEN 'workforce_registry_definitions' THEN configured_operation IN (
            'workforce.registry.manage','workforce.registry.activate')
        WHEN 'workforce_registry_entries' THEN configured_operation IN (
            'workforce.registry.manage','workforce.registry.activate')
        WHEN 'workforce_registry_versions' THEN configured_operation IN (
            'workforce.registry.manage','workforce.registry.approve',
            'workforce.registry.activate')
        ELSE false
    END;
    IF NOT operation_allowed THEN
        RAISE EXCEPTION 'Module 2 operation does not own target table %',TG_TABLE_NAME
            USING ERRCODE='42501';
    END IF;

    IF TG_OP='INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version<>0 THEN
            RAISE EXCEPTION 'invalid Module 2 creation evidence'
                USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at
           OR NEW.created_by IS DISTINCT FROM OLD.created_by
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version<>OLD.lock_version+1
           OR NEW.updated_at<OLD.updated_at
           OR NEW.updated_at>clock_timestamp()+interval '5 seconds' THEN
            RAISE EXCEPTION 'invalid Module 2 revision evidence'
                USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- The only physical deletion in Module 2 is removal of a newly-created provisional
-- onboarding case after an explicit use-existing match decision. Keep DELETE off the
-- application role and expose one validated, tenant-bound atomic routine instead.
CREATE FUNCTION careos_discard_provisional_onboarding_case(
    requested_organization_id uuid,
    requested_member_id uuid,
    requested_link_id uuid,
    requested_person_id uuid,
    expected_member_revision bigint)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path=pg_catalog,public
SET row_security=off
AS $$
DECLARE
    configured_organization uuid :=
        nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid :=
        nullif(current_setting('app.current_actor_id',true),'')::uuid;
    deleted_links integer;
BEGIN
    IF configured_organization IS DISTINCT FROM requested_organization_id
       OR configured_actor IS NULL
       OR nullif(current_setting('app.current_operation_key',true),'')
            <>'workforce.person.match'
       OR expected_member_revision<0 THEN
        RAISE EXCEPTION 'invalid provisional onboarding disposal context'
            USING ERRCODE='42501';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(
        requested_organization_id::text||':provisional:'||requested_member_id::text,0));
    IF NOT EXISTS (
        SELECT 1
        FROM workforce_members member
        JOIN organization_person_links link
          ON link.organization_id=member.organization_id
         AND link.id=member.organization_person_link_id
        WHERE member.organization_id=requested_organization_id
          AND member.id=requested_member_id
          AND member.lock_version=expected_member_revision
          AND member.lifecycle_state='draft'
          AND link.id=requested_link_id
          AND link.person_id=requested_person_id
          AND link.relationship_status='candidate'
          AND link.source_workforce_member_id=requested_member_id
          AND NOT EXISTS (
              SELECT 1 FROM person_merge_requests merge_request
              WHERE merge_request.organization_id=requested_organization_id
                AND merge_request.decision_state IN ('draft','submitted','approved')
                AND requested_link_id IN (
                    merge_request.retained_link_id,merge_request.discarded_link_id))) THEN
        RAISE EXCEPTION 'provisional onboarding case is stale'
            USING ERRCODE='40001';
    END IF;

    UPDATE organization_person_links
       SET source_workforce_member_id=NULL,lock_version=lock_version+1,
           updated_at=clock_timestamp(),updated_by=configured_actor
     WHERE organization_id=requested_organization_id AND id=requested_link_id;
    DELETE FROM workforce_identifiers
     WHERE organization_id=requested_organization_id
       AND workforce_member_id=requested_member_id;
    DELETE FROM workforce_members
     WHERE organization_id=requested_organization_id AND id=requested_member_id;
    DELETE FROM person_match_keys
     WHERE organization_id=requested_organization_id
       AND organization_person_link_id=requested_link_id;
    DELETE FROM person_profile_aliases WHERE person_id=requested_person_id;
    DELETE FROM person_contacts WHERE person_id=requested_person_id;
    DELETE FROM person_addresses WHERE person_id=requested_person_id;
    DELETE FROM organization_person_links
     WHERE organization_id=requested_organization_id AND id=requested_link_id
       AND person_id=requested_person_id AND relationship_status='candidate'
       AND source_workforce_member_id IS NULL;
    GET DIAGNOSTICS deleted_links=ROW_COUNT;
    IF deleted_links=1 AND NOT EXISTS (
        SELECT 1 FROM organization_person_links WHERE person_id=requested_person_id) THEN
        DELETE FROM person_profiles WHERE id=requested_person_id;
        IF FOUND THEN RETURN true; END IF;
    END IF;
    RAISE EXCEPTION 'provisional person record is no longer isolated'
        USING ERRCODE='40001';
END;
$$;

REVOKE ALL ON FUNCTION careos_discard_provisional_onboarding_case(
    uuid,uuid,uuid,uuid,bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION careos_discard_provisional_onboarding_case(
    uuid,uuid,uuid,uuid,bigint) TO "${applicationRole}";

-- Notification failures are immutable attempts. Attempts two through five are linked
-- rows with server-owned due times; the fifth failure is an explicitly owned dead letter.
ALTER TABLE workforce_notification_deliveries
    ADD COLUMN retry_of_notification_id uuid,
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN dead_lettered_at timestamptz,
    ADD COLUMN dead_letter_owner varchar(80),
    ADD CONSTRAINT workforce_notification_retry_source_fk
        FOREIGN KEY (organization_id,retry_of_notification_id)
        REFERENCES workforce_notification_deliveries(organization_id,id),
    ADD CONSTRAINT workforce_notification_retry_shape_check CHECK (
        retry_of_notification_id IS NULL OR retry_of_notification_id<>id),
    ADD CONSTRAINT workforce_notification_attempt_shape_check CHECK (
        (attempt_number=1 AND retry_of_notification_id IS NULL
                          AND next_attempt_at IS NULL)
        OR (attempt_number BETWEEN 2 AND 5
            AND retry_of_notification_id IS NOT NULL
            AND next_attempt_at IS NOT NULL)),
    ADD CONSTRAINT workforce_notification_dead_letter_shape_check CHECK (
        (dead_lettered_at IS NULL AND dead_letter_owner IS NULL)
        OR (status='failed' AND attempt_number=5
            AND dead_lettered_at IS NOT NULL
            AND dead_letter_owner='workforce_operations')),
    ADD CONSTRAINT workforce_notification_retry_time_check CHECK (
        (next_attempt_at IS NULL OR isfinite(next_attempt_at))
        AND (dead_lettered_at IS NULL OR isfinite(dead_lettered_at)));

CREATE UNIQUE INDEX workforce_notification_retry_source_uq
    ON workforce_notification_deliveries(organization_id,retry_of_notification_id)
    WHERE retry_of_notification_id IS NOT NULL;

CREATE INDEX workforce_notification_due_idx
    ON workforce_notification_deliveries(
        organization_id,next_attempt_at,created_at,id)
    WHERE status='planned';

CREATE FUNCTION careos_guard_workforce_notification_attempt()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
    parent workforce_notification_deliveries%ROWTYPE;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.attempt_number=1 THEN
            IF operation_key NOT IN ('workforce.expiry.escalate','m2.expiry.process')
               OR NEW.retry_of_notification_id IS NOT NULL
               OR NEW.next_attempt_at IS NOT NULL
               OR NEW.status NOT IN ('planned','suppressed') THEN
                RAISE EXCEPTION 'invalid initial workforce notification attempt'
                    USING ERRCODE='23514';
            END IF;
            RETURN NEW;
        END IF;
        SELECT * INTO parent
        FROM workforce_notification_deliveries source
        WHERE source.organization_id=NEW.organization_id
          AND source.id=NEW.retry_of_notification_id
        FOR SHARE;
        IF NOT FOUND OR operation_key<>'m2.notification.deliver'
           OR parent.status<>'failed' OR parent.dead_lettered_at IS NOT NULL
           OR NEW.attempt_number<>parent.attempt_number+1
           OR NEW.status<>'planned'
           OR NEW.next_attempt_at IS DISTINCT FROM
                parent.sent_at+(interval '1 minute'*(1 << (parent.attempt_number-1)))
           OR ROW(NEW.workforce_member_id,NEW.practitioner_credential_id,
                  NEW.professional_registration_id,NEW.source_request_id,
                  NEW.template_entry_id,NEW.template_version_id,NEW.milestone,
                  NEW.channel,NEW.recipient_opaque_reference,NEW.purpose_key)
              IS DISTINCT FROM
              ROW(parent.workforce_member_id,parent.practitioner_credential_id,
                  parent.professional_registration_id,parent.source_request_id,
                  parent.template_entry_id,parent.template_version_id,parent.milestone,
                  parent.channel,parent.recipient_opaque_reference,parent.purpose_key) THEN
            RAISE EXCEPTION 'invalid workforce notification retry attempt'
                USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;

    IF ROW(NEW.organization_id,NEW.workforce_member_id,
           NEW.practitioner_credential_id,NEW.professional_registration_id,
           NEW.source_request_id,NEW.template_entry_id,NEW.template_version_id,
           NEW.milestone,NEW.channel,NEW.recipient_opaque_reference,
           NEW.purpose_key,NEW.attempt_number,NEW.retry_of_notification_id,
           NEW.next_attempt_at,NEW.created_at,NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.organization_id,OLD.workforce_member_id,
           OLD.practitioner_credential_id,OLD.professional_registration_id,
           OLD.source_request_id,OLD.template_entry_id,OLD.template_version_id,
           OLD.milestone,OLD.channel,OLD.recipient_opaque_reference,
           OLD.purpose_key,OLD.attempt_number,OLD.retry_of_notification_id,
           OLD.next_attempt_at,OLD.created_at,OLD.created_by) THEN
        RAISE EXCEPTION 'workforce notification attempt identity is immutable'
            USING ERRCODE='55000';
    END IF;
    IF OLD.status IN ('delivered','failed','suppressed','cancelled') THEN
        RAISE EXCEPTION 'terminal workforce notification attempt is immutable'
            USING ERRCODE='55000';
    END IF;
    IF NOT (
      (operation_key='m2.notification.deliver' AND (
        (OLD.status='planned' AND NEW.status='queued'
         AND OLD.queued_at IS NULL AND NEW.queued_at IS NOT NULL
         AND NEW.provider_opaque_id IS NULL AND NEW.sent_at IS NULL
         AND NEW.delivered_at IS NULL AND NEW.failure_code IS NULL
         AND NEW.dead_lettered_at IS NULL AND NEW.dead_letter_owner IS NULL) OR
        (OLD.status='queued' AND NEW.status='sending'
         AND NEW.queued_at=OLD.queued_at
         AND NEW.delivered_at IS NULL AND NEW.failure_code IS NULL
         AND NEW.dead_lettered_at IS NULL AND NEW.dead_letter_owner IS NULL) OR
        (OLD.status IN ('queued','sending') AND NEW.status='delivered'
         AND NEW.queued_at=OLD.queued_at
         AND NEW.provider_opaque_id IS NOT NULL
         AND NEW.sent_at IS NOT NULL AND NEW.delivered_at IS NOT NULL
         AND NEW.failure_code IS NULL
         AND NEW.dead_lettered_at IS NULL AND NEW.dead_letter_owner IS NULL) OR
        (OLD.status IN ('queued','sending') AND NEW.status='failed'
         AND NEW.queued_at=OLD.queued_at
         AND NEW.sent_at IS NOT NULL AND NEW.delivered_at IS NULL
         AND NEW.failure_code IS NOT NULL
         AND ((NEW.attempt_number<5 AND NEW.dead_lettered_at IS NULL
                                      AND NEW.dead_letter_owner IS NULL)
           OR (NEW.attempt_number=5 AND NEW.dead_lettered_at IS NOT NULL
                                      AND NEW.dead_letter_owner='workforce_operations')))
      )) OR
      (operation_key='m2.expiry.process'
       AND OLD.status='planned' AND NEW.status='cancelled'
       AND NEW.queued_at IS NULL AND NEW.provider_opaque_id IS NULL
       AND NEW.sent_at IS NULL AND NEW.delivered_at IS NULL
       AND NEW.failure_code='source_no_longer_current'
       AND NEW.dead_lettered_at IS NULL AND NEW.dead_letter_owner IS NULL)
    ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workforce_notification_deliveries_guard_attempt
    BEFORE INSERT OR UPDATE ON workforce_notification_deliveries
    FOR EACH ROW EXECUTE FUNCTION careos_guard_workforce_notification_attempt();

CREATE FUNCTION careos_require_notification_retry_after_failure()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status='failed' AND NEW.attempt_number<5
       AND NOT EXISTS (
           SELECT 1 FROM workforce_notification_deliveries retry
           WHERE retry.organization_id=NEW.organization_id
             AND retry.retry_of_notification_id=NEW.id
             AND retry.attempt_number=NEW.attempt_number+1) THEN
        RAISE EXCEPTION 'failed workforce notification attempt requires linked retry'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER workforce_notification_deliveries_require_retry
    AFTER INSERT OR UPDATE ON workforce_notification_deliveries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION careos_require_notification_retry_after_failure();

CREATE OR REPLACE FUNCTION careos_validate_m2_person_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    configured_actor uuid:=
        nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text:=
        nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF configured_actor IS NULL OR NOT (
        (TG_TABLE_NAME='person_profiles'
         AND configured_operation IN ('workforce.member.create','workforce.person.correct'))
        OR (TG_TABLE_NAME IN ('person_profile_aliases','person_contacts','person_addresses')
            AND configured_operation IN ('workforce.member.create','workforce.person.correct'))
    ) THEN
        RAISE EXCEPTION 'invalid Module 2 person write context'
            USING ERRCODE='42501';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version<>0 THEN
            RAISE EXCEPTION 'invalid person creation evidence'
                USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at
           OR NEW.created_by IS DISTINCT FROM OLD.created_by
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version<>OLD.lock_version+1
           OR NEW.updated_at<OLD.updated_at
           OR NEW.updated_at>clock_timestamp()+interval '5 seconds' THEN
            RAISE EXCEPTION 'invalid person revision evidence'
                USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION careos_guard_organization_person_link_lifecycle()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.relationship_status=NEW.relationship_status THEN RETURN NEW; END IF;
    IF OLD.relationship_status IN ('ended','merged') THEN
        RAISE EXCEPTION 'terminal organization person link is immutable'
            USING ERRCODE='55000';
    END IF;
    IF NEW.status<>NEW.relationship_status OR NOT (
        (OLD.relationship_status='candidate'
         AND NEW.relationship_status IN ('active','ended')
         AND operation_key='workforce.person.match') OR
        (OLD.relationship_status='active' AND NEW.relationship_status='merged'
         AND operation_key='workforce.person.merge.execute')
    ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER organization_person_links_guard_lifecycle
    BEFORE UPDATE OF relationship_status,status ON organization_person_links
    FOR EACH ROW EXECUTE FUNCTION careos_guard_organization_person_link_lifecycle();

CREATE FUNCTION careos_guard_workforce_member_lifecycle()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.lifecycle_state='offboarded' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'offboarded workforce member is immutable'
            USING ERRCODE='55000';
    END IF;
    IF OLD.lifecycle_state=NEW.lifecycle_state THEN RETURN NEW; END IF;
    IF NEW.status<>NEW.lifecycle_state OR NOT (
        (OLD.lifecycle_state='draft' AND NEW.lifecycle_state='submitted'
         AND operation_key='workforce.activation.submit') OR
        (OLD.lifecycle_state='submitted' AND NEW.lifecycle_state='active'
         AND operation_key='workforce.activation.execute') OR
        (OLD.lifecycle_state='active' AND NEW.lifecycle_state='suspended'
         AND operation_key='workforce.lifecycle.suspend') OR
        (OLD.lifecycle_state='suspended' AND NEW.lifecycle_state='active'
         AND operation_key='workforce.lifecycle.reactivate') OR
        (OLD.lifecycle_state IN ('active','suspended')
         AND NEW.lifecycle_state='offboarding'
         AND operation_key IN ('workforce.offboarding.execute','m2.offboarding.execute')) OR
        (OLD.lifecycle_state='offboarding' AND NEW.lifecycle_state='offboarded'
         AND operation_key IN ('workforce.offboarding.execute','m2.offboarding.execute'))
    ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workforce_members_guard_lifecycle
    BEFORE UPDATE ON workforce_members
    FOR EACH ROW EXECUTE FUNCTION careos_guard_workforce_member_lifecycle();

CREATE FUNCTION careos_guard_employment_engagement_lifecycle()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.status IN ('ended','cancelled') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'terminal engagement evidence is immutable'
            USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.workforce_member_id,NEW.engagement_type,NEW.employment_category_key,
           NEW.manager_membership_id,NEW.work_email_contact_id,NEW.work_phone_contact_id,
           NEW.encrypted_external_reference,NEW.effective_from)
       IS DISTINCT FROM
       ROW(OLD.workforce_member_id,OLD.engagement_type,OLD.employment_category_key,
           OLD.manager_membership_id,OLD.work_email_contact_id,OLD.work_phone_contact_id,
           OLD.encrypted_external_reference,OLD.effective_from) THEN
        RAISE EXCEPTION 'engagement content is immutable' USING ERRCODE='55000';
    END IF;
    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
       AND NOT (operation_key IN (
                    'workforce.engagement.lifecycle','workforce.offboarding.execute',
                    'm2.offboarding.execute')
                AND NEW.effective_to>OLD.effective_from
                AND (OLD.effective_to IS NULL OR NEW.effective_to<=OLD.effective_to)) THEN
        RAISE EXCEPTION 'engagement range may only be shortened by lifecycle operation'
            USING ERRCODE='55000';
    END IF;
    IF OLD.status<>NEW.status AND NOT (
        (OLD.status IN ('draft','scheduled') AND NEW.status='active'
         AND operation_key='workforce.engagement.lifecycle') OR
        (OLD.status='active' AND NEW.status IN ('suspended','ended')
         AND operation_key='workforce.engagement.lifecycle') OR
        (OLD.status='suspended' AND NEW.status IN ('active','ended')
         AND operation_key='workforce.engagement.lifecycle') OR
        (OLD.status IN ('draft','scheduled') AND NEW.status='cancelled'
         AND operation_key='workforce.engagement.lifecycle') OR
        (OLD.status IN ('draft','scheduled','active','suspended')
         AND NEW.status IN ('ended','cancelled')
         AND operation_key IN ('workforce.offboarding.execute','m2.offboarding.execute'))
    ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER employment_engagements_guard_lifecycle
    BEFORE UPDATE ON employment_engagements
    FOR EACH ROW EXECUTE FUNCTION careos_guard_employment_engagement_lifecycle();

CREATE FUNCTION careos_guard_practitioner_profile_lifecycle()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.lifecycle_state='ended' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'ended practitioner profile is immutable'
            USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.workforce_member_id,NEW.profession_entry_id,NEW.profession_version_id,
           NEW.regulated,NEW.clinical_title,NEW.effective_from)
       IS DISTINCT FROM
       ROW(OLD.workforce_member_id,OLD.profession_entry_id,OLD.profession_version_id,
           OLD.regulated,OLD.clinical_title,OLD.effective_from) THEN
        RAISE EXCEPTION 'practitioner profile content is immutable'
            USING ERRCODE='55000';
    END IF;
    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
       AND NOT (operation_key IN ('workforce.practitioner.lifecycle',
                                  'workforce.offboarding.execute','m2.offboarding.execute')
                AND NEW.effective_to>OLD.effective_from
                AND (OLD.effective_to IS NULL OR NEW.effective_to<=OLD.effective_to)) THEN
        RAISE EXCEPTION 'practitioner profile range may only be shortened by lifecycle operation'
            USING ERRCODE='55000';
    END IF;
    IF OLD.lifecycle_state<>NEW.lifecycle_state AND NOT (
        (OLD.lifecycle_state='draft' AND NEW.lifecycle_state='active'
         AND operation_key='workforce.practitioner.lifecycle') OR
        (OLD.lifecycle_state='active' AND NEW.lifecycle_state IN ('suspended','ended')
         AND operation_key IN ('workforce.practitioner.lifecycle',
                               'workforce.lifecycle.suspend')) OR
        (OLD.lifecycle_state='suspended' AND NEW.lifecycle_state IN ('active','ended')
         AND operation_key IN ('workforce.practitioner.lifecycle',
                               'workforce.lifecycle.reactivate')) OR
        (OLD.lifecycle_state IN ('active','suspended') AND NEW.lifecycle_state='ended'
         AND operation_key IN ('workforce.offboarding.execute','m2.offboarding.execute'))
    ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    IF NEW.status<>NEW.lifecycle_state THEN
        RAISE EXCEPTION 'practitioner lifecycle/status mismatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER practitioner_profiles_guard_lifecycle
    BEFORE UPDATE ON practitioner_profiles
    FOR EACH ROW EXECUTE FUNCTION careos_guard_practitioner_profile_lifecycle();

CREATE FUNCTION careos_guard_availability_profile_lifecycle()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.lifecycle_state IN ('superseded','cancelled') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'terminal availability profile is immutable'
            USING ERRCODE='55000';
    END IF;
    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
       AND NOT (operation_key IN ('workforce.availability.manage',
                                  'workforce.offboarding.execute','m2.offboarding.execute')
                AND NEW.effective_to>OLD.effective_from
                AND (OLD.effective_to IS NULL OR NEW.effective_to<=OLD.effective_to)) THEN
        RAISE EXCEPTION 'availability range may only be shortened by lifecycle operation'
            USING ERRCODE='55000';
    END IF;
    IF OLD.lifecycle_state<>NEW.lifecycle_state AND NOT (
        (OLD.lifecycle_state='scheduled' AND NEW.lifecycle_state='active'
         AND operation_key='workforce.availability.manage') OR
        (OLD.lifecycle_state='active' AND NEW.lifecycle_state='superseded'
         AND operation_key='workforce.availability.manage') OR
        (OLD.lifecycle_state IN ('draft','scheduled') AND NEW.lifecycle_state='cancelled'
         AND operation_key='workforce.availability.manage') OR
        (OLD.lifecycle_state IN ('draft','scheduled','active')
         AND NEW.lifecycle_state='cancelled'
         AND operation_key IN ('workforce.offboarding.execute','m2.offboarding.execute'))
    ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    IF NEW.status<>NEW.lifecycle_state THEN
        RAISE EXCEPTION 'availability lifecycle/status mismatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER availability_profiles_guard_lifecycle
    BEFORE UPDATE ON availability_profiles
    FOR EACH ROW EXECUTE FUNCTION careos_guard_availability_profile_lifecycle();

CREATE FUNCTION careos_guard_access_assignment_scope_lifecycle()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF OLD.status IN ('ended','cancelled') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'terminal access-assignment link is immutable'
            USING ERRCODE='55000';
    END IF;
    IF OLD.status<>NEW.status AND NOT (
        (OLD.status IN ('requested','approved') AND NEW.status='active'
         AND operation_key='workforce.account_link.request') OR
        (OLD.status IN ('requested','approved','active')
         AND NEW.status IN ('ended','cancelled')
         AND operation_key IN ('workforce.offboarding.execute','m2.offboarding.execute'))
    ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
       AND NOT (operation_key IN ('workforce.account_link.request',
                                  'workforce.offboarding.execute','m2.offboarding.execute')
                AND NEW.effective_to>OLD.effective_from
                AND (OLD.effective_to IS NULL OR NEW.effective_to<=OLD.effective_to)) THEN
        RAISE EXCEPTION 'access-assignment range may only be shortened by its owning operation'
            USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER access_assignment_scopes_guard_lifecycle
    BEFORE UPDATE ON access_assignment_scopes
    FOR EACH ROW EXECUTE FUNCTION careos_guard_access_assignment_scope_lifecycle();

CREATE FUNCTION careos_guard_activation_request_lifecycle()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    -- Readiness invalidation is executed by a SECURITY DEFINER trigger and deliberately
    -- bypasses the application-role operation path after rechecking the tenant itself.
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF operation_key<>'workforce.activation.submit' OR NEW.status<>'submitted'
           OR NEW.checker_id IS NOT NULL OR NEW.decision_code IS NOT NULL
           OR NEW.decided_at IS NOT NULL OR NEW.activator_id IS NOT NULL
           OR NEW.activated_at IS NOT NULL THEN
            RAISE EXCEPTION 'invalid activation request creation'
                USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status IN ('rejected','expired','invalidated','activated') THEN
        RAISE EXCEPTION 'terminal activation request is immutable'
            USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.workforce_member_id,NEW.member_revision,NEW.readiness_run_id,
           NEW.result_digest,NEW.maker_id,NEW.submitted_reason_code,
           NEW.warning_acknowledgements,NEW.policy_version,NEW.requested_at,
           NEW.expires_at)
       IS DISTINCT FROM
       ROW(OLD.workforce_member_id,OLD.member_revision,OLD.readiness_run_id,
           OLD.result_digest,OLD.maker_id,OLD.submitted_reason_code,
           OLD.warning_acknowledgements,OLD.policy_version,OLD.requested_at,
           OLD.expires_at) THEN
        RAISE EXCEPTION 'activation request submission evidence is immutable'
            USING ERRCODE='55000';
    END IF;
    IF NOT (
        (OLD.status='submitted' AND NEW.status IN ('approved','rejected')
         AND operation_key='workforce.activation.approve'
         AND OLD.checker_id IS NULL AND NEW.checker_id IS NOT NULL
         AND NEW.checker_id<>OLD.maker_id AND NEW.decision_code=NEW.status
         AND OLD.decided_at IS NULL AND NEW.decided_at IS NOT NULL
         AND NEW.activator_id IS NULL AND NEW.activated_at IS NULL) OR
        (OLD.status='approved' AND NEW.status='activated'
         AND operation_key='workforce.activation.execute'
         AND NEW.checker_id=OLD.checker_id AND NEW.decision_code=OLD.decision_code
         AND NEW.decided_at=OLD.decided_at AND OLD.activator_id IS NULL
         AND NEW.activator_id IS NOT NULL AND NEW.activator_id<>OLD.maker_id
         AND OLD.activated_at IS NULL AND NEW.activated_at IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workforce_activation_requests_guard_lifecycle
    BEFORE INSERT OR UPDATE ON workforce_activation_requests
    FOR EACH ROW EXECUTE FUNCTION careos_guard_activation_request_lifecycle();

ALTER TABLE workforce_offboarding_requests
    ADD COLUMN dead_letter_owner varchar(80),
    ADD CONSTRAINT workforce_offboarding_dead_letter_owner_check CHECK (
        (dead_lettered_at IS NULL AND dead_letter_owner IS NULL)
        OR (status='failed' AND attempt_count=5
            AND dead_lettered_at IS NOT NULL
            AND dead_letter_owner='workforce_operations'));

ALTER TABLE workforce_export_jobs
    ADD COLUMN dead_letter_owner varchar(80),
    ADD CONSTRAINT workforce_export_dead_letter_owner_check CHECK (
        (dead_lettered_at IS NULL AND dead_letter_owner IS NULL)
        OR (status='failed' AND dead_lettered_at IS NOT NULL
            AND dead_letter_owner='workforce_operations'));

CREATE FUNCTION careos_guard_offboarding_request_lifecycle()
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
        (OLD.status='submitted' AND NEW.status='approved'
         AND operation_key='workforce.offboarding.approve'
         AND OLD.checker_id IS NULL AND NEW.checker_id IS NOT NULL
         AND NEW.checker_id<>OLD.maker_id
         AND NEW.failure_code IS NULL AND NEW.attempt_count=OLD.attempt_count) OR
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

CREATE TRIGGER workforce_offboarding_requests_guard_lifecycle
    BEFORE INSERT OR UPDATE ON workforce_offboarding_requests
    FOR EACH ROW EXECUTE FUNCTION careos_guard_offboarding_request_lifecycle();

CREATE FUNCTION careos_guard_workforce_export_lifecycle()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation_key text:=nullif(current_setting('app.current_operation_key',true),'');
    actor_id uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
BEGIN
    IF TG_OP='INSERT' THEN
        IF operation_key<>'workforce.export.request'
           OR NEW.requester_id IS DISTINCT FROM actor_id
           OR NEW.status NOT IN ('requested','authorized')
           OR NEW.attempt_count<>0 OR NEW.worker_id IS NOT NULL
           OR NEW.lease_expires_at IS NOT NULL
           OR (NEW.retry_of_export_id IS NULL AND NEW.next_attempt_at IS NOT NULL)
           OR NEW.dead_lettered_at IS NOT NULL OR NEW.dead_letter_owner IS NOT NULL
           OR NEW.failure_code IS NOT NULL
           OR NEW.artifact_opaque_id IS NOT NULL OR NEW.artifact_digest IS NOT NULL
           OR NEW.artifact_content_type IS NOT NULL OR NEW.artifact_filename IS NOT NULL
           OR NEW.artifact_byte_count IS NOT NULL OR NEW.row_count IS NOT NULL
           OR NEW.ready_at IS NOT NULL OR NEW.expires_at IS NOT NULL
           OR NEW.disposed_at IS NOT NULL OR NEW.access_granted_to IS NOT NULL
           OR NEW.access_grant_digest IS NOT NULL OR NEW.access_granted_at IS NOT NULL
           OR NEW.access_grant_expires_at IS NOT NULL THEN
            RAISE EXCEPTION 'invalid workforce export creation evidence'
                USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status IN ('failed','disposed') THEN
        RAISE EXCEPTION 'terminal workforce export is immutable'
            USING ERRCODE='55000';
    END IF;
    IF ROW(NEW.requester_id,NEW.purpose_key,NEW.legal_basis_key,NEW.projection,
           NEW.filters_digest,NEW.sort_digest,NEW.format,NEW.row_limit,
           NEW.size_limit_bytes,NEW.policy_version,NEW.filters_json,
           NEW.retry_of_export_id,NEW.created_at,NEW.created_by,NEW.legal_hold)
       IS DISTINCT FROM
       ROW(OLD.requester_id,OLD.purpose_key,OLD.legal_basis_key,OLD.projection,
           OLD.filters_digest,OLD.sort_digest,OLD.format,OLD.row_limit,
           OLD.size_limit_bytes,OLD.policy_version,OLD.filters_json,
           OLD.retry_of_export_id,OLD.created_at,OLD.created_by,OLD.legal_hold) THEN
        RAISE EXCEPTION 'workforce export request evidence is immutable'
            USING ERRCODE='55000';
    END IF;
    IF NOT (
        (OLD.status='requested' AND NEW.status IN ('authorized','failed')
         AND operation_key='workforce.export.approve'
         AND NEW.approval_reference_id IS NOT NULL
         AND ((NEW.status='authorized' AND NEW.failure_code IS NULL)
           OR (NEW.status='failed' AND NEW.failure_code='authorization_denied')))
        OR
        (OLD.status='authorized' AND NEW.status='running'
         AND operation_key='m2.export.generate'
         AND OLD.attempt_count=0 AND NEW.attempt_count=1
         AND NEW.snapshot_at IS NOT NULL AND NEW.worker_id IS NOT NULL
         AND NEW.lease_expires_at>clock_timestamp()
         AND NEW.next_attempt_at IS NULL AND NEW.failure_code IS NULL
         AND NEW.dead_lettered_at IS NULL AND NEW.dead_letter_owner IS NULL)
        OR
        (OLD.status='running' AND NEW.status IN ('ready','failed')
         AND operation_key='m2.export.generate'
         AND NEW.attempt_count=OLD.attempt_count
         AND NEW.snapshot_at=OLD.snapshot_at
         AND NEW.worker_id IS NULL AND NEW.lease_expires_at IS NULL
         AND ((NEW.status='ready' AND NEW.failure_code IS NULL
               AND NEW.artifact_opaque_id IS NOT NULL
               AND NEW.artifact_digest IS NOT NULL AND NEW.ready_at IS NOT NULL
               AND NEW.expires_at IS NOT NULL)
           OR (NEW.status='failed' AND NEW.failure_code IS NOT NULL
               AND NEW.dead_lettered_at IS NOT NULL
               AND NEW.dead_letter_owner='workforce_operations')))
        OR
        (OLD.status='ready' AND NEW.status='ready'
         AND operation_key='workforce.export.access'
         AND NEW.access_granted_to=OLD.requester_id
         AND NEW.access_grant_digest=OLD.artifact_digest
         AND NEW.access_granted_at IS NOT NULL
         AND NEW.access_grant_expires_at IS NOT NULL
         AND NEW.access_granted_at>=clock_timestamp()-interval '5 seconds'
         AND NEW.access_granted_at<=clock_timestamp()+interval '5 seconds'
         AND NEW.access_grant_expires_at>clock_timestamp()
         AND NEW.access_grant_expires_at<=NEW.access_granted_at+interval '10 minutes'
         AND ROW(NEW.approval_reference_id,NEW.snapshot_at,NEW.attempt_count,
                 NEW.worker_id,NEW.lease_expires_at,NEW.next_attempt_at,
                 NEW.dead_lettered_at,NEW.dead_letter_owner,
                 NEW.artifact_opaque_id,NEW.artifact_digest,
                 NEW.artifact_content_type,NEW.artifact_filename,
                 NEW.artifact_byte_count,NEW.row_count,NEW.ready_at,NEW.expires_at,
                 NEW.disposed_at,NEW.failure_code)
             IS NOT DISTINCT FROM
             ROW(OLD.approval_reference_id,OLD.snapshot_at,OLD.attempt_count,
                 OLD.worker_id,OLD.lease_expires_at,OLD.next_attempt_at,
                 OLD.dead_lettered_at,OLD.dead_letter_owner,
                 OLD.artifact_opaque_id,OLD.artifact_digest,
                 OLD.artifact_content_type,OLD.artifact_filename,
                 OLD.artifact_byte_count,OLD.row_count,OLD.ready_at,OLD.expires_at,
                 OLD.disposed_at,OLD.failure_code))
        OR
        (OLD.status='ready' AND NEW.status='expired'
         AND operation_key='m2.retention.dispose'
         AND OLD.expires_at<=clock_timestamp())
        OR
        (OLD.status='expired' AND NEW.status='disposed'
         AND operation_key='m2.retention.dispose'
         AND NOT OLD.legal_hold AND NEW.disposed_at IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'm2.lifecycle.invalid_transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workforce_export_jobs_guard_lifecycle
    BEFORE INSERT OR UPDATE ON workforce_export_jobs
    FOR EACH ROW EXECUTE FUNCTION careos_guard_workforce_export_lifecycle();

-- Invalidating a previously complete readiness result is itself governed evidence.
-- Keep this synchronous so stale readiness/activation can never be used while an
-- outbox consumer is delayed, and emit the approved minimal audit/outbox records
-- from the same transaction. The trigger-depth check prevents the application role
-- from calling this SECURITY DEFINER routine as an arbitrary invalidation primitive.
CREATE OR REPLACE FUNCTION careos_invalidate_member_readiness(
    affected_organization_id uuid,
    affected_member_id uuid,
    invalidation_code text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path=pg_catalog,public
AS $$
DECLARE
    configured_organization uuid :=
        nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid :=
        nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_purpose text :=
        nullif(current_setting('app.current_purpose',true),'');
    configured_correlation text :=
        nullif(current_setting('app.current_correlation_id',true),'');
    original_operation text :=
        nullif(current_setting('app.current_operation_key',true),'');
    readiness_record record;
    activation_record record;
    event_payload jsonb;
    occurred_time timestamptz;
BEGIN
    IF affected_organization_id IS NULL OR affected_member_id IS NULL THEN
        RETURN;
    END IF;
    IF configured_organization IS NULL THEN
        IF session_user='${applicationRole}' THEN
            RAISE EXCEPTION 'missing Module 2 readiness invalidation tenant'
                USING ERRCODE='42501';
        END IF;
        RETURN;
    END IF;
    IF configured_organization IS DISTINCT FROM affected_organization_id
       OR configured_actor IS NULL
       OR configured_purpose IS NULL
       OR configured_correlation IS NULL
       OR original_operation IS NULL
       OR NOT (
           pg_trigger_depth()>0
           OR (
               original_operation='workforce.validation.run'
               AND nullif(current_setting('app.current_actor_kind',true),'')='service'
               AND EXISTS (
                   SELECT 1
                   FROM service_identities identity
                   WHERE identity.organization_id=affected_organization_id
                     AND identity.id=configured_actor
                     AND identity.service_key='m2-readiness-invalidator-v1'
                     AND identity.status='active'
                     AND identity.active_from<=clock_timestamp()
                     AND (identity.expires_at IS NULL
                          OR identity.expires_at>clock_timestamp()))
           )
       )
       OR invalidation_code IS NULL
       OR invalidation_code !~ '^[a-z][a-z0-9_]{1,119}$' THEN
        RAISE EXCEPTION 'invalid Module 2 readiness invalidation context'
            USING ERRCODE='42501';
    END IF;

    FOR readiness_record IN
        UPDATE workforce_readiness_runs
           SET status='invalidated',failure_code=invalidation_code,
               lock_version=lock_version+1,updated_at=clock_timestamp(),
               updated_by=configured_actor
         WHERE organization_id=affected_organization_id
           AND workforce_member_id=affected_member_id
           AND status='complete'
         RETURNING id,workforce_member_id,pathway,result_digest,
                   blocker_count,warning_count,expires_at
    LOOP
        occurred_time:=clock_timestamp();
        event_payload:=jsonb_build_object(
            'memberId',readiness_record.workforce_member_id,
            'runId',readiness_record.id,
            'pathway',readiness_record.pathway,
            'resultDigest',readiness_record.result_digest,
            'blockerCount',readiness_record.blocker_count,
            'warningCount',readiness_record.warning_count,
            'expiresAt',readiness_record.expires_at,
            'failureCode',invalidation_code);
        PERFORM set_config('app.current_operation_key','workforce.validation.run',true);
        INSERT INTO audit_events(
            organization_id,actor_user_id,event_name,schema_version,
            subject_type,subject_id,reason,payload,purpose,correlation_id,occurred_at)
        VALUES (
            affected_organization_id,configured_actor,'workforce.readiness.invalidated',1,
            'workforce_readiness_run',readiness_record.id,NULL,event_payload,
            configured_purpose,configured_correlation,occurred_time);
        INSERT INTO outbox_events(
            organization_id,actor_user_id,event_name,schema_version,
            aggregate_type,aggregate_id,payload,purpose,correlation_id,
            occurred_at,available_at)
        VALUES (
            affected_organization_id,configured_actor,'workforce.readiness.invalidated',1,
            'workforce_readiness_run',readiness_record.id,
            jsonb_build_object(
                'memberId',readiness_record.workforce_member_id,
                'runId',readiness_record.id,
                'invalidationCode',invalidation_code),
            configured_purpose,configured_correlation,occurred_time,occurred_time);
    END LOOP;

    FOR activation_record IN
        UPDATE workforce_activation_requests
           SET status='invalidated',lock_version=lock_version+1,
               updated_at=clock_timestamp(),updated_by=configured_actor
         WHERE organization_id=affected_organization_id
           AND workforce_member_id=affected_member_id
           AND status IN ('draft','submitted','approved')
         RETURNING id,workforce_member_id,readiness_run_id,result_digest
    LOOP
        occurred_time:=clock_timestamp();
        event_payload:=jsonb_build_object(
            'memberId',activation_record.workforce_member_id,
            'activationRequestId',activation_record.id,
            'runId',activation_record.readiness_run_id,
            'resultDigest',activation_record.result_digest,
            'decisionCode',invalidation_code,
            'state','invalidated');
        PERFORM set_config('app.current_operation_key','workforce.activation.submit',true);
        INSERT INTO audit_events(
            organization_id,actor_user_id,event_name,schema_version,
            subject_type,subject_id,reason,payload,purpose,correlation_id,occurred_at)
        VALUES (
            affected_organization_id,configured_actor,'workforce.activation.invalidated',1,
            'workforce_activation_request',activation_record.id,
            'Authoritative readiness dependency changed.',event_payload,
            configured_purpose,configured_correlation,occurred_time);
        INSERT INTO outbox_events(
            organization_id,actor_user_id,event_name,schema_version,
            aggregate_type,aggregate_id,payload,purpose,correlation_id,
            occurred_at,available_at)
        VALUES (
            affected_organization_id,configured_actor,'workforce.activation.invalidated',1,
            'workforce_activation_request',activation_record.id,event_payload,
            configured_purpose,configured_correlation,occurred_time,occurred_time);
    END LOOP;
    PERFORM set_config('app.current_operation_key',original_operation,true);
END;
$$;
