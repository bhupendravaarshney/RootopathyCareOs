CREATE OR REPLACE FUNCTION careos_validate_organization_unit_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
 configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
 configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
 configured_reason text:=nullif(current_setting('app.current_authorization_reason',true),'');
 parent_facility uuid; parent_status text; facility_status text; hierarchy_depth integer;
BEGIN
 IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
 IF NEW.organization_id IS DISTINCT FROM configured_organization OR configured_actor IS NULL
  OR configured_operation NOT IN ('network.facility.manage','network.structure.lifecycle')
  OR char_length(coalesce(configured_reason,'')) NOT BETWEEN 10 AND 500
  OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
   RAISE EXCEPTION 'invalid organization unit governance context' USING ERRCODE='42501';
 END IF;
 IF TG_OP='INSERT' AND (configured_operation<>'network.facility.manage' OR NEW.status<>'draft' OR NEW.created_by IS DISTINCT FROM configured_actor) THEN
   RAISE EXCEPTION 'invalid organization unit draft evidence' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.organization_id<>OLD.organization_id OR NEW.facility_id<>OLD.facility_id
  OR NEW.created_at<>OLD.created_at OR NEW.created_by<>OLD.created_by OR NEW.lock_version<>OLD.lock_version+1) THEN
   RAISE EXCEPTION 'invalid organization unit revision' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND configured_operation='network.facility.manage' AND (OLD.status<>'draft' OR NEW.status<>'draft') THEN
   RAISE EXCEPTION 'draft management cannot change organization unit lifecycle' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND configured_operation='network.structure.lifecycle' THEN
  IF OLD.status<>'draft' OR NEW.status<>'active' OR NEW.parent_id IS DISTINCT FROM OLD.parent_id OR NEW.unit_code<>OLD.unit_code
   OR NEW.unit_type<>OLD.unit_type OR NEW.name<>OLD.name OR NEW.effective_from<>OLD.effective_from OR NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
    RAISE EXCEPTION 'invalid organization unit activation transition' USING ERRCODE='23514';
  END IF;
  SELECT status INTO facility_status FROM facilities WHERE organization_id=NEW.organization_id AND id=NEW.facility_id;
  IF facility_status NOT IN ('under_review','active') THEN RAISE EXCEPTION 'organization unit activation requires an eligible facility' USING ERRCODE='23514'; END IF;
  IF NEW.effective_from>clock_timestamp() OR (NEW.effective_to IS NOT NULL AND NEW.effective_to<=clock_timestamp()) THEN
    RAISE EXCEPTION 'organization unit activation requires a current effective range' USING ERRCODE='23514';
  END IF;
 END IF;
 IF NEW.parent_id IS NOT NULL THEN
  SELECT facility_id,status INTO parent_facility,parent_status FROM organization_units WHERE organization_id=NEW.organization_id AND id=NEW.parent_id;
  IF parent_facility IS NULL OR parent_facility<>NEW.facility_id THEN RAISE EXCEPTION 'organization unit parent must belong to the same facility' USING ERRCODE='23514'; END IF;
  IF TG_OP='UPDATE' AND configured_operation='network.structure.lifecycle' AND parent_status<>'active' THEN
    RAISE EXCEPTION 'organization unit activation requires an active parent' USING ERRCODE='23514';
  END IF;
  WITH RECURSIVE ancestors(id,parent_id,depth) AS (
   SELECT id,parent_id,1 FROM organization_units WHERE organization_id=NEW.organization_id AND id=NEW.parent_id
   UNION ALL SELECT p.id,p.parent_id,a.depth+1 FROM organization_units p JOIN ancestors a ON p.id=a.parent_id WHERE p.organization_id=NEW.organization_id AND a.depth<9)
  SELECT coalesce(max(depth),0) INTO hierarchy_depth FROM ancestors;
  IF EXISTS(WITH RECURSIVE ancestors(id,parent_id) AS (SELECT id,parent_id FROM organization_units WHERE organization_id=NEW.organization_id AND id=NEW.parent_id UNION ALL SELECT p.id,p.parent_id FROM organization_units p JOIN ancestors a ON p.id=a.parent_id WHERE p.organization_id=NEW.organization_id) SELECT 1 FROM ancestors WHERE id=NEW.id)
    THEN RAISE EXCEPTION 'organization unit hierarchy cycle' USING ERRCODE='23514'; END IF;
  IF hierarchy_depth>=8 THEN RAISE EXCEPTION 'organization unit hierarchy depth exceeds 8' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;

INSERT INTO authorization_operations
 (operation_key,permission_key,display_name,description,mutation,denial_mode,reason_required,
  recent_authentication_required,recent_authentication_max_age_seconds,maximum_future_skew_seconds,
  maker_checker_required,status,registry_version,mfa_required)
VALUES ('network.structure.lifecycle','network.structure.lifecycle','Manage structure lifecycle',
 'Govern department, unit, and location lifecycle transitions.',true,'explicit',true,true,600,5,false,
 'active','m1-candidate-1',true);

INSERT INTO authorization_operation_events(operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES ('network.structure.lifecycle','audit','network.unit.changed',1,'active','m1-candidate-1'),
       ('network.structure.lifecycle','outbox','network.unit.changed',1,'active','m1-candidate-1');
