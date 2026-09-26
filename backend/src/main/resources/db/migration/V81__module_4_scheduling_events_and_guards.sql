CREATE TEMP TABLE m4_audit_seed (
    event_name varchar(180) PRIMARY KEY,
    subject_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL,
    reason_required boolean NOT NULL
) ON COMMIT DROP;

INSERT INTO m4_audit_seed VALUES
 ('appointment.schedule.created','appointment_schedule','appointment.schedule.manage',
  ARRAY['scheduleId','serviceId','facilityId','locationId','practitionerId','state','revision'],
  ARRAY['scheduleId','serviceId','facilityId','locationId','practitionerId','state','revision'],true),
 ('appointment.schedule.activated','appointment_schedule','appointment.schedule.manage',
  ARRAY['scheduleId','state','revision'],ARRAY['scheduleId','state','revision'],true),
 ('appointment.slot.created','appointment_slot','appointment.schedule.manage',
  ARRAY['slotId','scheduleId','startsAt','endsAt','revision'],
  ARRAY['slotId','scheduleId','startsAt','endsAt','revision'],true),
 ('appointment.request.started','appointment_request','appointment.request.manage',
  ARRAY['requestId','patientId','source','status','revision'],
  ARRAY['requestId','patientId','source','status','revision'],false),
 ('appointment.request.context_changed','appointment_request','appointment.request.manage',
  ARRAY['requestId','patientId','changeStep','revision'],
  ARRAY['requestId','patientId','changeStep','revision'],false),
 ('appointment.slot.held','appointment_request','appointment.slot.hold',
  ARRAY['requestId','slotId','holdExpiresAt','revision'],
  ARRAY['requestId','slotId','holdExpiresAt','revision'],false),
 ('appointment.confirmed','appointment','appointment.book',
  ARRAY['appointmentId','requestId','patientId','slotId','startsAt','revision'],
  ARRAY['appointmentId','requestId','patientId','slotId','startsAt','eligibilityEvidenceId','revision'],true),
 ('appointment.rescheduled','appointment','appointment.reschedule',
  ARRAY['appointmentId','priorSlotId','newSlotId','startsAt','revision'],
  ARRAY['appointmentId','priorSlotId','newSlotId','startsAt','eligibilityEvidenceId','revision'],true),
 ('appointment.cancelled','appointment','appointment.cancel',
  ARRAY['appointmentId','reasonCode','policyVersion','revision'],
  ARRAY['appointmentId','reasonCode','policyVersion','revision'],true),
 ('appointment.no_show_recorded','appointment','appointment.no_show',
  ARRAY['appointmentId','reasonCode','policyVersion','revision'],
  ARRAY['appointmentId','reasonCode','evidenceCode','policyVersion','revision'],true),
 ('appointment.waitlist.joined','waitlist_entry','appointment.waitlist.manage',
  ARRAY['waitlistEntryId','patientId','serviceId','status','revision'],
  ARRAY['waitlistEntryId','patientId','serviceId','facilityId','locationId','status','revision'],true),
 ('appointment.waitlist.withdrawn','waitlist_entry','appointment.waitlist.manage',
  ARRAY['waitlistEntryId','patientId','status','revision'],
  ARRAY['waitlistEntryId','patientId','status','revision'],true);

INSERT INTO audit_event_definitions
    (event_name,schema_version,display_name,description,subject_type,reason_required,
     required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,replace(initcap(replace(event_name,'.',' ')),'_',' '),
       'CareOS Module 4 governed scheduling evidence.',subject_type,reason_required,
       required_keys,allowed_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m4-standing-direction-v1'
FROM m4_audit_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'audit',event_name,1,'active','m4-standing-direction-v1'
FROM m4_audit_seed;

CREATE TEMP TABLE m4_outbox_seed (
    event_name varchar(180) PRIMARY KEY,
    aggregate_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL
) ON COMMIT DROP;

INSERT INTO m4_outbox_seed VALUES
 ('m4.appointment.confirmed.v1','appointment','appointment.book',
  ARRAY['appointmentId','patientId','slotId','startsAt'],
  ARRAY['appointmentId','patientId','slotId','startsAt']),
 ('m4.appointment.rescheduled.v1','appointment','appointment.reschedule',
  ARRAY['appointmentId','priorSlotId','newSlotId','startsAt'],
  ARRAY['appointmentId','priorSlotId','newSlotId','startsAt']),
 ('m4.appointment.cancelled.v1','appointment','appointment.cancel',
  ARRAY['appointmentId','reasonCode','policyVersion'],
  ARRAY['appointmentId','reasonCode','policyVersion']),
 ('m4.appointment.no-show.v1','appointment','appointment.no_show',
  ARRAY['appointmentId','reasonCode','policyVersion'],
  ARRAY['appointmentId','reasonCode','policyVersion']),
 ('m4.waitlist.changed.v1','waitlist_entry','appointment.waitlist.manage',
  ARRAY['waitlistEntryId','patientId','serviceId','status'],
  ARRAY['waitlistEntryId','patientId','serviceId','status']);

INSERT INTO outbox_event_definitions
    (event_name,schema_version,description,aggregate_type,required_payload_keys,
     allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,'CareOS Module 4 transactional scheduling event.',aggregate_type,
       required_keys,allowed_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m4-standing-direction-v1'
FROM m4_outbox_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'outbox',event_name,1,'active','m4-standing-direction-v1'
FROM m4_outbox_seed;

CREATE FUNCTION careos_guard_m4_schedule_transition()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op<>'appointment.schedule.manage' OR NEW.lifecycle_state<>'draft' THEN
            RAISE EXCEPTION 'invalid Module 4 schedule creation' USING ERRCODE='23514';
        END IF;
    ELSIF op<>'appointment.schedule.manage'
       OR NOT ((OLD.lifecycle_state='draft' AND NEW.lifecycle_state IN ('draft','active'))
            OR (OLD.lifecycle_state='active' AND NEW.lifecycle_state='retired')) THEN
        RAISE EXCEPTION 'invalid Module 4 schedule transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER appointment_schedules_lifecycle_guard
    BEFORE INSERT OR UPDATE ON appointment_schedules
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m4_schedule_transition();

CREATE FUNCTION careos_guard_m4_slot_transition()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    op text:=nullif(current_setting('app.current_operation_key',true),'');
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op<>'appointment.schedule.manage' OR NEW.status<>'available'
           OR NOT EXISTS (SELECT 1 FROM appointment_schedules schedule
               WHERE schedule.organization_id=NEW.organization_id
                 AND schedule.id=NEW.schedule_id AND schedule.lifecycle_state='active'
                 AND schedule.service_id=NEW.service_id
                 AND schedule.facility_id=NEW.facility_id
                 AND schedule.location_id=NEW.location_id
                 AND schedule.practitioner_profile_id=NEW.practitioner_profile_id
                 AND NEW.starts_at>=schedule.effective_from
                 AND NEW.ends_at<=schedule.effective_to) THEN
            RAISE EXCEPTION 'invalid Module 4 slot creation' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF op='appointment.slot.hold' THEN
        IF NEW.status<>'held'
           OR OLD.status NOT IN ('available','held')
           OR (OLD.status='held' AND OLD.hold_expires_at>clock_timestamp())
           OR NEW.hold_expires_at<=clock_timestamp()
           OR NEW.hold_expires_at>clock_timestamp()+interval '5 minutes 5 seconds'
           OR NEW.booked_appointment_id IS NOT NULL THEN
            RAISE EXCEPTION 'invalid Module 4 slot hold transition' USING ERRCODE='23514';
        END IF;
    ELSIF op IN ('appointment.book','appointment.reschedule') AND NEW.status='booked' THEN
        IF OLD.status NOT IN ('available','held') OR NEW.booked_appointment_id IS NULL THEN
            RAISE EXCEPTION 'invalid Module 4 slot booking transition' USING ERRCODE='23514';
        END IF;
    ELSIF op IN ('appointment.reschedule','appointment.cancel') AND NEW.status='available' THEN
        IF OLD.status<>'booked' OR NEW.booked_appointment_id IS NOT NULL THEN
            RAISE EXCEPTION 'invalid Module 4 slot release transition' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'operation cannot change Module 4 slot state' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER appointment_slots_lifecycle_guard
    BEFORE INSERT OR UPDATE ON appointment_slots
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m4_slot_transition();

CREATE FUNCTION careos_guard_m4_request_transition()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op<>'appointment.request.manage' OR NEW.status<>'collecting'
           OR NEW.selected_slot_id IS NOT NULL OR NEW.completed_appointment_id IS NOT NULL THEN
            RAISE EXCEPTION 'invalid Module 4 request creation' USING ERRCODE='23514';
        END IF;
    ELSIF op='appointment.request.manage' THEN
        IF OLD.status<>'collecting' OR NEW.status<>'collecting'
           OR ROW(NEW.selected_slot_id,NEW.completed_appointment_id)
              IS DISTINCT FROM ROW(OLD.selected_slot_id,OLD.completed_appointment_id) THEN
            RAISE EXCEPTION 'invalid Module 4 request context transition' USING ERRCODE='23514';
        END IF;
    ELSIF op='appointment.slot.hold' THEN
        IF OLD.status<>'collecting' OR NEW.status<>'held'
           OR NEW.selected_slot_id IS NULL OR NEW.hold_expires_at<=clock_timestamp() THEN
            RAISE EXCEPTION 'invalid Module 4 request hold transition' USING ERRCODE='23514';
        END IF;
    ELSIF op='appointment.book' THEN
        IF OLD.status<>'held' OR OLD.hold_expires_at<=clock_timestamp()
           OR NEW.status<>'confirmed' OR NEW.completed_appointment_id IS NULL THEN
            RAISE EXCEPTION 'invalid Module 4 request confirmation transition' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'operation cannot change Module 4 request state' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER appointment_requests_lifecycle_guard
    BEFORE INSERT OR UPDATE ON appointment_requests
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m4_request_transition();

CREATE FUNCTION careos_guard_m4_appointment_transition()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op<>'appointment.book' OR NEW.status<>'confirmed'
           OR NEW.cancelled_at IS NOT NULL OR NEW.no_show_at IS NOT NULL THEN
            RAISE EXCEPTION 'invalid Module 4 appointment creation' USING ERRCODE='23514';
        END IF;
    ELSIF op='appointment.reschedule' THEN
        IF OLD.status<>'confirmed' OR NEW.status<>'confirmed'
           OR NEW.slot_id=OLD.slot_id OR NEW.reschedule_count<>OLD.reschedule_count+1
           OR ROW(NEW.patient_id,NEW.service_id,NEW.source_request_id)
              IS DISTINCT FROM ROW(OLD.patient_id,OLD.service_id,OLD.source_request_id) THEN
            RAISE EXCEPTION 'invalid Module 4 reschedule transition' USING ERRCODE='23514';
        END IF;
    ELSIF op='appointment.cancel' THEN
        IF OLD.status<>'confirmed' OR NEW.status<>'cancelled' OR NEW.cancelled_at IS NULL THEN
            RAISE EXCEPTION 'invalid Module 4 cancellation transition' USING ERRCODE='23514';
        END IF;
    ELSIF op='appointment.no_show' THEN
        IF OLD.status<>'confirmed' OR OLD.starts_at>clock_timestamp()
           OR NEW.status<>'no_show' OR NEW.no_show_at IS NULL THEN
            RAISE EXCEPTION 'invalid Module 4 no-show transition' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'operation cannot change Module 4 appointment state' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER appointments_lifecycle_guard
    BEFORE INSERT OR UPDATE ON appointments
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m4_appointment_transition();

CREATE FUNCTION careos_guard_m4_waitlist_transition()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF op<>'appointment.waitlist.manage' OR NEW.status<>'waiting' THEN
            RAISE EXCEPTION 'invalid Module 4 waitlist creation' USING ERRCODE='23514';
        END IF;
    ELSIF op<>'appointment.waitlist.manage' OR OLD.status<>'waiting'
       OR NEW.status<>'withdrawn' OR NEW.patient_id<>OLD.patient_id
       OR NEW.service_id<>OLD.service_id THEN
        RAISE EXCEPTION 'invalid Module 4 waitlist transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER waitlist_entries_lifecycle_guard
    BEFORE INSERT OR UPDATE ON waitlist_entries
    FOR EACH ROW EXECUTE FUNCTION careos_guard_m4_waitlist_transition();
