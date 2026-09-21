CREATE OR REPLACE FUNCTION careos_validate_service_location_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
 configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
 configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
 configured_reason text:=nullif(current_setting('app.current_authorization_reason',true),'');
 referenced_facility uuid; parent_status text; facility_status text; hierarchy_depth integer;
BEGIN
 IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
 IF NEW.organization_id IS DISTINCT FROM configured_organization OR configured_actor IS NULL
  OR configured_operation NOT IN ('network.structure.manage','network.structure.lifecycle','configuration.activate')
  OR char_length(coalesce(configured_reason,'')) NOT BETWEEN 10 AND 500 OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
   RAISE EXCEPTION 'invalid service location governance context' USING ERRCODE='42501';
 END IF;
 IF TG_OP='INSERT' AND (configured_operation<>'network.structure.manage' OR NEW.status<>'draft' OR NEW.created_by IS DISTINCT FROM configured_actor) THEN
  RAISE EXCEPTION 'invalid service location draft evidence' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.organization_id<>OLD.organization_id OR NEW.facility_id<>OLD.facility_id
  OR NEW.created_at<>OLD.created_at OR NEW.created_by<>OLD.created_by OR NEW.lock_version<>OLD.lock_version+1) THEN
  RAISE EXCEPTION 'invalid service location revision' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND configured_operation='network.structure.manage' AND (OLD.status<>'draft' OR NEW.status<>'draft') THEN
  RAISE EXCEPTION 'draft management cannot change service location lifecycle' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND configured_operation IN ('network.structure.lifecycle','configuration.activate') THEN
  IF NOT ((OLD.status='draft' AND NEW.status='active') OR (OLD.status='active' AND NEW.status='suspended')
       OR (OLD.status='suspended' AND NEW.status='active') OR (OLD.status IN ('active','suspended') AND NEW.status='closed'))
   OR NEW.parent_id IS DISTINCT FROM OLD.parent_id OR NEW.unit_id IS DISTINCT FROM OLD.unit_id
   OR NEW.address_id IS DISTINCT FROM OLD.address_id OR NEW.location_code<>OLD.location_code OR NEW.location_type<>OLD.location_type
   OR NEW.name<>OLD.name OR NEW.virtual_service_type IS DISTINCT FROM OLD.virtual_service_type OR NEW.capacity IS DISTINCT FROM OLD.capacity
   OR NEW.accessibility_notes IS DISTINCT FROM OLD.accessibility_notes OR NEW.effective_from<>OLD.effective_from
   OR (NEW.status<>'closed' AND NEW.effective_to IS DISTINCT FROM OLD.effective_to) THEN
   RAISE EXCEPTION 'invalid service location lifecycle transition' USING ERRCODE='23514'; END IF;
  IF NEW.status='active' THEN
   SELECT status INTO facility_status FROM facilities WHERE organization_id=NEW.organization_id AND id=NEW.facility_id;
   IF facility_status NOT IN ('under_review','active') THEN RAISE EXCEPTION 'service location activation requires an eligible facility' USING ERRCODE='23514'; END IF;
   IF NEW.effective_from>clock_timestamp() OR (NEW.effective_to IS NOT NULL AND NEW.effective_to<=clock_timestamp()) THEN RAISE EXCEPTION 'service location activation requires a current effective range' USING ERRCODE='23514'; END IF;
  END IF;
  IF OLD.status='active' AND NEW.status='suspended' AND EXISTS(WITH RECURSIVE descendants(id,status) AS (SELECT id,status FROM service_locations WHERE organization_id=NEW.organization_id AND parent_id=NEW.id UNION ALL SELECT l.id,l.status FROM service_locations l JOIN descendants d ON l.parent_id=d.id WHERE l.organization_id=NEW.organization_id) SELECT 1 FROM descendants WHERE status='active') THEN RAISE EXCEPTION 'service location suspension requires all descendants to be non-active' USING ERRCODE='23514'; END IF;
  IF NEW.status='closed' THEN
   IF NEW.effective_to IS NULL OR NEW.effective_to<=NEW.effective_from OR abs(extract(epoch FROM (NEW.effective_to-clock_timestamp())))>300 THEN RAISE EXCEPTION 'service location closure requires a current effective end' USING ERRCODE='23514'; END IF;
   IF EXISTS(WITH RECURSIVE descendants(id,status) AS (SELECT id,status FROM service_locations WHERE organization_id=NEW.organization_id AND parent_id=NEW.id UNION ALL SELECT l.id,l.status FROM service_locations l JOIN descendants d ON l.parent_id=d.id WHERE l.organization_id=NEW.organization_id) SELECT 1 FROM descendants WHERE status<>'closed') THEN RAISE EXCEPTION 'service location closure requires all descendants to be closed' USING ERRCODE='23514'; END IF;
  END IF;
 END IF;
 IF NEW.unit_id IS NOT NULL THEN SELECT facility_id INTO referenced_facility FROM organization_units WHERE organization_id=NEW.organization_id AND id=NEW.unit_id; IF referenced_facility IS NULL OR referenced_facility<>NEW.facility_id THEN RAISE EXCEPTION 'service location unit must belong to the same facility' USING ERRCODE='23514'; END IF; END IF;
 IF NEW.parent_id IS NOT NULL THEN
  SELECT facility_id,status INTO referenced_facility,parent_status FROM service_locations WHERE organization_id=NEW.organization_id AND id=NEW.parent_id;
  IF referenced_facility IS NULL OR referenced_facility<>NEW.facility_id THEN RAISE EXCEPTION 'service location parent must belong to the same facility' USING ERRCODE='23514'; END IF;
  IF TG_OP='UPDATE' AND configured_operation IN ('network.structure.lifecycle','configuration.activate') AND NEW.status='active' AND parent_status<>'active' THEN RAISE EXCEPTION 'service location activation requires an active parent' USING ERRCODE='23514'; END IF;
  IF EXISTS(WITH RECURSIVE ancestors(id,parent_id) AS (SELECT id,parent_id FROM service_locations WHERE organization_id=NEW.organization_id AND id=NEW.parent_id UNION ALL SELECT p.id,p.parent_id FROM service_locations p JOIN ancestors a ON p.id=a.parent_id WHERE p.organization_id=NEW.organization_id) SELECT 1 FROM ancestors WHERE id=NEW.id) THEN RAISE EXCEPTION 'service location hierarchy cycle' USING ERRCODE='23514'; END IF;
  WITH RECURSIVE ancestors(id,parent_id,depth) AS (SELECT id,parent_id,1 FROM service_locations WHERE organization_id=NEW.organization_id AND id=NEW.parent_id UNION ALL SELECT p.id,p.parent_id,a.depth+1 FROM service_locations p JOIN ancestors a ON p.id=a.parent_id WHERE p.organization_id=NEW.organization_id AND a.depth<9) SELECT coalesce(max(depth),0) INTO hierarchy_depth FROM ancestors;
  IF hierarchy_depth>=8 THEN RAISE EXCEPTION 'service location hierarchy depth exceeds 8' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;

INSERT INTO authorization_operation_events(operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES ('network.structure.lifecycle','audit','network.location.changed',1,'active','m1-candidate-1'),
       ('network.structure.lifecycle','outbox','network.location.changed',1,'active','m1-candidate-1');
