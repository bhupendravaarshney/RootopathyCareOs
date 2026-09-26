DROP TRIGGER presenting_concerns_authorship_guard ON presenting_concerns;
DROP TRIGGER clinical_problems_authorship_guard ON clinical_problems;
DROP TRIGGER diagnoses_authorship_guard ON diagnoses;
DROP TRIGGER note_versions_authorship_guard ON note_versions;
DROP TRIGGER orders_authorship_guard ON orders;
DROP FUNCTION careos_guard_m5_clinical_insert();

CREATE FUNCTION careos_guard_m5_clinical_insert()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    row_data jsonb:=to_jsonb(NEW);
    author_id uuid:=coalesce(
        nullif(row_data->>'author_practitioner_id','')::uuid,
        nullif(row_data->>'requester_practitioner_id','')::uuid);
    target_encounter uuid;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_TABLE_NAME='note_versions' THEN
        SELECT note.encounter_id INTO target_encounter
          FROM encounter_notes note
         WHERE note.organization_id=NEW.organization_id
           AND note.id=nullif(row_data->>'encounter_note_id','')::uuid;
    ELSE
        target_encounter:=nullif(row_data->>'encounter_id','')::uuid;
    END IF;
    IF TG_OP<>'INSERT' OR author_id IS NULL OR target_encounter IS NULL
       OR NOT careos_m5_actor_is_practitioner(
            NEW.organization_id,actor,author_id,clock_timestamp())
       OR NOT EXISTS (SELECT 1 FROM encounters encounter
            WHERE encounter.organization_id=NEW.organization_id
              AND encounter.id=target_encounter
              AND encounter.status IN ('in_progress','on_hold')) THEN
        RAISE EXCEPTION 'invalid Module 5 clinical authorship context' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'presenting_concerns','clinical_problems','diagnoses','note_versions','orders'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT ON %I FOR EACH ROW EXECUTE FUNCTION careos_guard_m5_clinical_insert()',
            table_name||'_authorship_guard',table_name);
    END LOOP;
END $$;

COMMENT ON FUNCTION careos_guard_m5_clinical_insert() IS
    'Runtime-safe cross-table clinical authorship guard using common JSON field extraction.';
