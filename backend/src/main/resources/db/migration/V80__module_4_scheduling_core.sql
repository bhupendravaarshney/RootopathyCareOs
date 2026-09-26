CREATE TABLE appointment_schedules (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    service_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    location_id uuid NOT NULL,
    practitioner_profile_id uuid NOT NULL,
    timezone varchar(80) NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz NOT NULL,
    slot_duration_minutes smallint NOT NULL,
    lifecycle_state varchar(24) NOT NULL DEFAULT 'draft',
    policy_version varchar(80) NOT NULL DEFAULT 'm4-standing-direction-v1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,service_id) REFERENCES service_definitions(organization_id,id),
    FOREIGN KEY (organization_id,facility_id) REFERENCES facilities(organization_id,id),
    FOREIGN KEY (organization_id,location_id) REFERENCES service_locations(organization_id,id),
    FOREIGN KEY (organization_id,practitioner_profile_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (timezone ~ '^[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)+$'),
    CHECK (effective_to>effective_from),
    CHECK (slot_duration_minutes BETWEEN 5 AND 480),
    CHECK (lifecycle_state IN ('draft','active','retired')),
    CHECK (lock_version>=0)
);

CREATE TABLE appointment_slots (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    schedule_id uuid NOT NULL,
    service_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    location_id uuid NOT NULL,
    practitioner_profile_id uuid NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    timezone_snapshot varchar(80) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'available',
    held_by_request_id uuid,
    hold_token_digest char(64),
    hold_expires_at timestamptz,
    booked_appointment_id uuid,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,schedule_id,starts_at),
    FOREIGN KEY (organization_id,schedule_id) REFERENCES appointment_schedules(organization_id,id),
    FOREIGN KEY (organization_id,service_id) REFERENCES service_definitions(organization_id,id),
    FOREIGN KEY (organization_id,facility_id) REFERENCES facilities(organization_id,id),
    FOREIGN KEY (organization_id,location_id) REFERENCES service_locations(organization_id,id),
    FOREIGN KEY (organization_id,practitioner_profile_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (ends_at>starts_at),
    CHECK (timezone_snapshot ~ '^[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)+$'),
    CHECK (status IN ('available','held','booked','blocked')),
    CHECK ((status='held')=(held_by_request_id IS NOT NULL AND hold_token_digest IS NOT NULL AND hold_expires_at IS NOT NULL)),
    CHECK ((status='booked')=(booked_appointment_id IS NOT NULL)),
    CHECK (status='held' OR (hold_token_digest IS NULL AND hold_expires_at IS NULL)),
    CHECK (lock_version>=0)
);

ALTER TABLE appointment_slots ADD CONSTRAINT appointment_slots_practitioner_overlap_excl
    EXCLUDE USING gist (
        organization_id WITH =,
        practitioner_profile_id WITH =,
        tstzrange(starts_at,ends_at,'[)') WITH &&
    ) WHERE (status<>'blocked');

CREATE TABLE appointment_requests (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    service_id uuid,
    facility_id uuid,
    location_id uuid,
    practitioner_profile_id uuid,
    selected_slot_id uuid,
    completed_appointment_id uuid,
    request_source varchar(40) NOT NULL,
    purpose_key varchar(80) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'collecting',
    hold_token_digest char(64),
    hold_expires_at timestamptz,
    expires_at timestamptz NOT NULL DEFAULT (clock_timestamp()+interval '24 hours'),
    policy_version varchar(80) NOT NULL DEFAULT 'm4-standing-direction-v1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,service_id) REFERENCES service_definitions(organization_id,id),
    FOREIGN KEY (organization_id,facility_id) REFERENCES facilities(organization_id,id),
    FOREIGN KEY (organization_id,location_id) REFERENCES service_locations(organization_id,id),
    FOREIGN KEY (organization_id,practitioner_profile_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,selected_slot_id) REFERENCES appointment_slots(organization_id,id),
    CHECK (request_source IN ('staff','referral','portal','import')),
    CHECK (char_length(btrim(purpose_key)) BETWEEN 2 AND 80),
    CHECK (status IN ('collecting','held','confirmed','abandoned','expired')),
    CHECK ((status='held')=(selected_slot_id IS NOT NULL AND hold_token_digest IS NOT NULL AND hold_expires_at IS NOT NULL)),
    CHECK ((status='confirmed')=(completed_appointment_id IS NOT NULL)),
    CHECK (status='held' OR (hold_token_digest IS NULL AND hold_expires_at IS NULL)),
    CHECK (expires_at>created_at),
    CHECK (lock_version>=0)
);

CREATE TABLE appointments (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    source_request_id uuid NOT NULL,
    patient_id uuid NOT NULL,
    service_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    location_id uuid NOT NULL,
    schedule_id uuid NOT NULL,
    slot_id uuid NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    timezone_snapshot varchar(80) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'confirmed',
    reschedule_count integer NOT NULL DEFAULT 0,
    policy_version varchar(80) NOT NULL DEFAULT 'm4-standing-direction-v1',
    confirmed_at timestamptz NOT NULL,
    cancelled_at timestamptz,
    no_show_at timestamptz,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,source_request_id),
    FOREIGN KEY (organization_id,source_request_id) REFERENCES appointment_requests(organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,service_id) REFERENCES service_definitions(organization_id,id),
    FOREIGN KEY (organization_id,facility_id) REFERENCES facilities(organization_id,id),
    FOREIGN KEY (organization_id,location_id) REFERENCES service_locations(organization_id,id),
    FOREIGN KEY (organization_id,schedule_id) REFERENCES appointment_schedules(organization_id,id),
    FOREIGN KEY (organization_id,slot_id) REFERENCES appointment_slots(organization_id,id),
    CHECK (ends_at>starts_at),
    CHECK (timezone_snapshot ~ '^[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)+$'),
    CHECK (status IN ('confirmed','cancelled','no_show')),
    CHECK ((status='cancelled')=(cancelled_at IS NOT NULL)),
    CHECK ((status='no_show')=(no_show_at IS NOT NULL)),
    CHECK (reschedule_count>=0 AND lock_version>=0)
);

CREATE UNIQUE INDEX appointments_one_active_slot_uq
    ON appointments(organization_id,slot_id) WHERE status='confirmed';

ALTER TABLE appointment_slots
    ADD CONSTRAINT appointment_slots_request_fk
        FOREIGN KEY (organization_id,held_by_request_id) REFERENCES appointment_requests(organization_id,id),
    ADD CONSTRAINT appointment_slots_booking_fk
        FOREIGN KEY (organization_id,booked_appointment_id) REFERENCES appointments(organization_id,id);

ALTER TABLE appointment_requests
    ADD CONSTRAINT appointment_requests_completion_fk
        FOREIGN KEY (organization_id,completed_appointment_id) REFERENCES appointments(organization_id,id);

CREATE TABLE appointment_participants (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    appointment_id uuid NOT NULL,
    participant_type varchar(24) NOT NULL,
    patient_id uuid,
    practitioner_profile_id uuid,
    role_key varchar(80) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,appointment_id) REFERENCES appointments(organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,practitioner_profile_id) REFERENCES practitioner_profiles(organization_id,id),
    CHECK (participant_type IN ('patient','practitioner','support')),
    CHECK ((patient_id IS NOT NULL)::integer+(practitioner_profile_id IS NOT NULL)::integer=1),
    CHECK (status IN ('active','removed')),
    CHECK (lock_version>=0)
);

CREATE TABLE appointment_status_history (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    appointment_id uuid NOT NULL,
    from_status varchar(24),
    to_status varchar(24) NOT NULL,
    prior_slot_id uuid,
    new_slot_id uuid,
    reason_code varchar(80) NOT NULL,
    policy_version varchar(80) NOT NULL,
    effective_at timestamptz NOT NULL,
    actor_id uuid NOT NULL,
    correlation_reference uuid NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,appointment_id) REFERENCES appointments(organization_id,id),
    FOREIGN KEY (organization_id,prior_slot_id) REFERENCES appointment_slots(organization_id,id),
    FOREIGN KEY (organization_id,new_slot_id) REFERENCES appointment_slots(organization_id,id),
    CHECK (to_status IN ('confirmed','rescheduled','cancelled','no_show')),
    CHECK (lock_version=0)
);

CREATE TABLE appointment_assignments (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    appointment_id uuid NOT NULL,
    practitioner_profile_id uuid NOT NULL,
    practitioner_service_assignment_id uuid NOT NULL,
    eligibility_evidence_id uuid NOT NULL,
    eligibility_digest char(64) NOT NULL,
    evaluated_for timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,appointment_id) REFERENCES appointments(organization_id,id),
    FOREIGN KEY (organization_id,practitioner_profile_id) REFERENCES practitioner_profiles(organization_id,id),
    FOREIGN KEY (organization_id,practitioner_service_assignment_id) REFERENCES practitioner_service_assignments(organization_id,id),
    FOREIGN KEY (organization_id,eligibility_evidence_id) REFERENCES practitioner_eligibility_evidence(organization_id,id),
    CHECK (eligibility_digest ~ '^[0-9a-f]{64}$'),
    CHECK (status IN ('active','superseded')),
    CHECK (lock_version>=0)
);

CREATE UNIQUE INDEX appointment_assignments_one_active_uq
    ON appointment_assignments(organization_id,appointment_id) WHERE status='active';

CREATE TABLE waitlist_entries (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    service_id uuid NOT NULL,
    facility_id uuid,
    location_id uuid,
    earliest_at timestamptz NOT NULL,
    latest_at timestamptz NOT NULL,
    priority_key varchar(40) NOT NULL DEFAULT 'standard',
    status varchar(24) NOT NULL DEFAULT 'waiting',
    offered_slot_id uuid,
    offer_expires_at timestamptz,
    policy_version varchar(80) NOT NULL DEFAULT 'm4-standing-direction-v1',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,service_id) REFERENCES service_definitions(organization_id,id),
    FOREIGN KEY (organization_id,facility_id) REFERENCES facilities(organization_id,id),
    FOREIGN KEY (organization_id,location_id) REFERENCES service_locations(organization_id,id),
    FOREIGN KEY (organization_id,offered_slot_id) REFERENCES appointment_slots(organization_id,id),
    CHECK (latest_at>earliest_at),
    CHECK (priority_key IN ('standard','urgent_review')),
    CHECK (status IN ('waiting','offered','accepted','expired','withdrawn')),
    CHECK ((status='offered')=(offered_slot_id IS NOT NULL AND offer_expires_at IS NOT NULL)),
    CHECK (lock_version>=0)
);

CREATE TABLE appointment_reminders (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    appointment_id uuid NOT NULL,
    template_version varchar(80) NOT NULL,
    channel varchar(24) NOT NULL,
    recipient_reference uuid NOT NULL,
    scheduled_for timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'planned',
    attempt_count integer NOT NULL DEFAULT 0,
    provider_opaque_reference varchar(160),
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,appointment_id) REFERENCES appointments(organization_id,id),
    CHECK (channel IN ('email','sms','whatsapp','portal')),
    CHECK (status IN ('planned','queued','sent','failed','cancelled')),
    CHECK (attempt_count>=0 AND lock_version>=0)
);

CREATE TABLE appointment_cancellations (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    appointment_id uuid NOT NULL,
    reason_code varchar(80) NOT NULL,
    policy_version varchar(80) NOT NULL,
    financial_outcome varchar(32) NOT NULL DEFAULT 'pending_policy',
    cancelled_at timestamptz NOT NULL,
    cancelled_by uuid NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,appointment_id),
    FOREIGN KEY (organization_id,appointment_id) REFERENCES appointments(organization_id,id),
    CHECK (financial_outcome='pending_policy'),
    CHECK (lock_version=0)
);

CREATE TABLE no_show_decisions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    appointment_id uuid NOT NULL,
    reason_code varchar(80) NOT NULL,
    evidence_code varchar(80) NOT NULL,
    policy_version varchar(80) NOT NULL,
    decided_at timestamptz NOT NULL,
    decided_by uuid NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,appointment_id),
    FOREIGN KEY (organization_id,appointment_id) REFERENCES appointments(organization_id,id),
    CHECK (lock_version=0)
);

CREATE TABLE appointment_payment_requirements (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    appointment_id uuid NOT NULL,
    requirement_state varchar(32) NOT NULL DEFAULT 'deferred_to_billing',
    policy_version varchar(80) NOT NULL DEFAULT 'm4-standing-direction-v1',
    amount_minor bigint,
    currency_code char(3),
    status varchar(24) NOT NULL DEFAULT 'deferred',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,appointment_id),
    FOREIGN KEY (organization_id,appointment_id) REFERENCES appointments(organization_id,id),
    CHECK (requirement_state='deferred_to_billing'),
    CHECK (amount_minor IS NULL AND currency_code IS NULL),
    CHECK (status='deferred'),
    CHECK (lock_version=0)
);

CREATE TABLE external_calendar_links (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    appointment_id uuid NOT NULL,
    provider_key varchar(80) NOT NULL,
    provider_opaque_reference varchar(160) NOT NULL,
    direction varchar(16) NOT NULL,
    last_internal_revision bigint NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'disabled',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,appointment_id,provider_key),
    FOREIGN KEY (organization_id,appointment_id) REFERENCES appointments(organization_id,id),
    CHECK (direction IN ('outbound','bidirectional')),
    CHECK (status IN ('disabled','pending','active','failed','revoked')),
    CHECK (last_internal_revision>=0 AND lock_version>=0)
);

CREATE INDEX appointment_slots_search_idx
    ON appointment_slots(organization_id,status,starts_at,service_id,facility_id,location_id);
CREATE INDEX appointments_directory_idx
    ON appointments(organization_id,status,starts_at,id);
CREATE INDEX appointment_requests_work_idx
    ON appointment_requests(organization_id,status,expires_at,id);
CREATE INDEX waitlist_entries_work_idx
    ON waitlist_entries(organization_id,status,earliest_at,id);
CREATE INDEX appointment_status_history_timeline_idx
    ON appointment_status_history(organization_id,appointment_id,effective_at DESC,id DESC);

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'appointment_schedules','appointment_slots','appointment_requests','appointments',
        'appointment_participants','appointment_status_history','appointment_assignments',
        'waitlist_entries','appointment_reminders','appointment_cancellations',
        'no_show_decisions','appointment_payment_requirements','external_calendar_links'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid) WITH CHECK (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid)',
            table_name||'_tenant_policy',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_validate_m4_tenant_write()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
    allowed_operations text[];
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    allowed_operations:=CASE TG_TABLE_NAME
        WHEN 'appointment_schedules' THEN ARRAY['appointment.schedule.manage']
        WHEN 'appointment_slots' THEN ARRAY['appointment.schedule.manage','appointment.slot.hold','appointment.book','appointment.reschedule','appointment.cancel']
        WHEN 'appointment_requests' THEN ARRAY['appointment.request.manage','appointment.slot.hold','appointment.book']
        WHEN 'appointments' THEN ARRAY['appointment.book','appointment.reschedule','appointment.cancel','appointment.no_show']
        WHEN 'appointment_participants' THEN ARRAY['appointment.book']
        WHEN 'appointment_status_history' THEN ARRAY['appointment.book','appointment.reschedule','appointment.cancel','appointment.no_show']
        WHEN 'appointment_assignments' THEN ARRAY['appointment.book','appointment.reschedule']
        WHEN 'waitlist_entries' THEN ARRAY['appointment.waitlist.manage']
        WHEN 'appointment_cancellations' THEN ARRAY['appointment.cancel']
        WHEN 'no_show_decisions' THEN ARRAY['appointment.no_show']
        WHEN 'appointment_payment_requirements' THEN ARRAY['appointment.book']
        ELSE ARRAY[]::text[]
    END;
    IF NEW.organization_id IS DISTINCT FROM configured_organization
       OR configured_actor IS NULL
       OR configured_operation IS NULL
       OR NOT configured_operation=ANY(allowed_operations)
       OR NOT EXISTS (
            SELECT 1 FROM authorization_operations operation
            JOIN authorization_registry_releases release
              ON release.registry_version=operation.registry_version AND release.status='active'
            WHERE operation.operation_key=configured_operation
              AND operation.registry_version='m4-standing-direction-v1'
              AND operation.status='active') THEN
        RAISE EXCEPTION 'invalid Module 4 operation-bound tenant write context' USING ERRCODE='42501';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor
           OR NEW.lock_version<>0 THEN
            RAISE EXCEPTION 'invalid Module 4 creation evidence' USING ERRCODE='23514';
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
            RAISE EXCEPTION 'invalid Module 4 revision evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'appointment_schedules','appointment_slots','appointment_requests','appointments',
        'appointment_participants','appointment_status_history','appointment_assignments',
        'waitlist_entries','appointment_cancellations','no_show_decisions',
        'appointment_payment_requirements'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION careos_validate_m4_tenant_write()',
            table_name||'_write_guard',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_reject_m4_evidence_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 4 evidence is append-only' USING ERRCODE='42501';
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'appointment_status_history','appointment_cancellations','no_show_decisions',
        'appointment_payment_requirements'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION careos_reject_m4_evidence_mutation()',
            table_name||'_immutable',table_name);
    END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE ON
    appointment_schedules,appointment_slots,appointment_requests,appointments,
    appointment_participants,appointment_assignments,waitlist_entries
TO "${applicationRole}";
GRANT SELECT,INSERT ON
    appointment_status_history,appointment_cancellations,no_show_decisions,
    appointment_payment_requirements
TO "${applicationRole}";
GRANT SELECT ON appointment_reminders,external_calendar_links TO "${applicationRole}";

COMMENT ON TABLE appointment_slots IS
    'Internal source-of-truth appointment slots with database-time short-lived holds.';
COMMENT ON TABLE appointment_payment_requirements IS
    'Non-financial M4 evidence; charging/refund behavior remains deferred to approved billing policy.';
COMMENT ON TABLE external_calendar_links IS
    'Opaque external calendar evidence only; an external calendar is never appointment lifecycle truth.';
