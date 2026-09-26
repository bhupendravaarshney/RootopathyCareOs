CREATE OR REPLACE FUNCTION careos_guard_m5_note_version()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected_version integer; expected_prior uuid;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT note.current_version_number+1,note.current_version_id
      INTO expected_version,expected_prior
      FROM encounter_notes note
     WHERE note.organization_id=NEW.organization_id AND note.id=NEW.encounter_note_id
       AND note.status='draft' FOR UPDATE;
    IF expected_version IS NULL OR NEW.version_number<>expected_version
       OR NEW.prior_version_id IS DISTINCT FROM expected_prior
       OR NEW.content_digest<>encode(digest(NEW.content_text::text,'sha256'::text),'hex')
       OR NEW.status<>'recorded' THEN
        RAISE EXCEPTION 'invalid Module 5 note version' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION careos_guard_m5_amendment()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    encounter_record encounters%ROWTYPE;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT encounter.* INTO encounter_record FROM encounters encounter
     WHERE encounter.organization_id=NEW.organization_id AND encounter.id=NEW.encounter_id;
    IF nullif(current_setting('app.current_operation_key',true),'')<>'encounter.amend'
       OR encounter_record.id IS NULL
       OR encounter_record.status NOT IN ('in_progress','on_hold','completed')
       OR NEW.amendment_digest<>
          encode(digest(NEW.amendment_text::text,'sha256'::text),'hex')
       OR NEW.reason_text IS DISTINCT FROM
          nullif(current_setting('app.current_authorization_reason',true),'')
       OR NOT careos_m5_actor_is_practitioner(
            NEW.organization_id,actor,NEW.author_practitioner_id,NEW.amended_at)
       OR NOT careos_m5_practitioner_eligible(
            NEW.organization_id,NEW.author_practitioner_id,encounter_record.service_id,
            encounter_record.facility_id,encounter_record.location_id,NEW.amended_at)
       OR NOT EXISTS (SELECT 1 FROM practitioner_eligibility_evidence evidence
            WHERE evidence.organization_id=NEW.organization_id
              AND evidence.id=NEW.eligibility_evidence_id
              AND evidence.result_digest=NEW.eligibility_digest
              AND evidence.practitioner_profile_id=NEW.author_practitioner_id
              AND evidence.service_id=encounter_record.service_id
              AND evidence.facility_id=encounter_record.facility_id
              AND (evidence.location_id IS NULL
                   OR evidence.location_id=encounter_record.location_id)
              AND evidence.outcome='eligible' AND evidence.status='eligible'
              AND evidence.evaluated_from<=NEW.amended_at
              AND (evidence.evaluated_to IS NULL OR evidence.evaluated_to>NEW.amended_at)
              AND evidence.expires_at>NEW.amended_at)
       OR NOT EXISTS (SELECT 1 FROM encounter_signatures signature
            JOIN note_versions version ON version.organization_id=signature.organization_id
             AND version.id=signature.note_version_id
            WHERE signature.organization_id=NEW.organization_id
              AND signature.id=NEW.prior_signature_id
              AND signature.encounter_id=NEW.encounter_id
              AND signature.encounter_note_id=NEW.encounter_note_id
              AND signature.note_version_id=NEW.amended_note_version_id
              AND signature.status='signed'
              AND version.content_digest=signature.signed_content_digest) THEN
        RAISE EXCEPTION 'invalid Module 5 signed amendment' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END $$;

COMMENT ON FUNCTION careos_guard_m5_note_version() IS
    'Validates append-only note lineage and SHA-256 digest using explicit pgcrypto text types.';
