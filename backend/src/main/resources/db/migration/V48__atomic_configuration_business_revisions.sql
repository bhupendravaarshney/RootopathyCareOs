CREATE OR REPLACE FUNCTION careos_validate_identifier_scheme() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),''); actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid; tenant uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
BEGIN
 IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
 IF NEW.organization_id IS DISTINCT FROM tenant OR actor IS NULL OR op NOT IN ('identifier.scheme.manage','identifier.scheme.activate','identifier.scheme.retire','configuration.activate') OR NEW.updated_by IS DISTINCT FROM actor OR char_length(coalesce(nullif(current_setting('app.current_authorization_reason',true),''),'')) NOT BETWEEN 10 AND 500 THEN RAISE EXCEPTION 'invalid scheme governance context' USING ERRCODE='42501'; END IF;
 IF TG_OP='INSERT' AND (op<>'identifier.scheme.manage' OR NEW.status<>'draft' OR NEW.created_by<>actor) THEN RAISE EXCEPTION 'schemes begin draft' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.organization_id<>OLD.organization_id OR NEW.scheme_key<>OLD.scheme_key OR NEW.scope_type<>OLD.scope_type OR NEW.scope_id IS DISTINCT FROM OLD.scope_id OR NEW.created_at<>OLD.created_at OR NEW.created_by<>OLD.created_by OR NEW.lock_version<>OLD.lock_version+1) THEN RAISE EXCEPTION 'invalid scheme revision' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND NOT ((op='identifier.scheme.manage' AND OLD.status='draft' AND NEW.status='draft') OR (op IN ('identifier.scheme.activate','configuration.activate') AND OLD.status='draft' AND NEW.status='active') OR (op IN ('identifier.scheme.retire','configuration.activate') AND OLD.status='active' AND NEW.status='retired')) THEN RAISE EXCEPTION 'invalid scheme transition' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION careos_validate_identifier_scheme_version() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op text:=nullif(current_setting('app.current_operation_key',true),''); actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid; tenant uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
BEGIN
 IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
 IF NEW.organization_id IS DISTINCT FROM tenant OR actor IS NULL OR op NOT IN ('identifier.scheme.version.create','identifier.scheme.activate','identifier.scheme.retire','configuration.activate') THEN RAISE EXCEPTION 'invalid scheme-version governance context' USING ERRCODE='42501'; END IF;
 IF TG_OP='INSERT' AND (op<>'identifier.scheme.version.create' OR NEW.status<>'draft' OR NEW.created_by<>actor OR NEW.next_sequence<>NEW.sequence_start) THEN RAISE EXCEPTION 'invalid scheme version creation' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND op NOT IN ('identifier.scheme.activate','identifier.scheme.retire','configuration.activate') THEN RAISE EXCEPTION 'scheme versions are immutable' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.organization_id<>OLD.organization_id OR NEW.scheme_id<>OLD.scheme_id OR NEW.version_number<>OLD.version_number OR NEW.prefix<>OLD.prefix OR NEW.pattern<>OLD.pattern OR NEW.alphabet<>OLD.alphabet OR NEW.check_digit_algorithm IS DISTINCT FROM OLD.check_digit_algorithm OR NEW.sequence_start<>OLD.sequence_start OR NEW.sequence_increment<>OLD.sequence_increment OR NEW.padding<>OLD.padding OR NEW.preview_samples<>OLD.preview_samples OR NEW.effective_from<>OLD.effective_from OR NEW.next_sequence<>OLD.next_sequence OR NEW.created_at<>OLD.created_at OR NEW.created_by<>OLD.created_by OR NEW.lock_version<>OLD.lock_version+1) THEN RAISE EXCEPTION 'scheme version content is immutable' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND NOT ((op IN ('identifier.scheme.activate','configuration.activate') AND OLD.status='draft' AND NEW.status='active' AND NEW.activated_by=actor AND NEW.activated_at IS NOT NULL) OR (op IN ('identifier.scheme.retire','configuration.activate') AND OLD.status='active' AND NEW.status='retired' AND NEW.retired_by=actor AND NEW.retired_at IS NOT NULL)) THEN RAISE EXCEPTION 'invalid scheme-version transition' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION careos_invalidate_configuration_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE affected_organization_id uuid;
BEGIN
 IF TG_TABLE_NAME='organizations' THEN
  affected_organization_id:=CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END;
 ELSE
  affected_organization_id:=CASE WHEN TG_OP='DELETE' THEN OLD.organization_id ELSE NEW.organization_id END;
 END IF;
 UPDATE configuration_validation_results SET invalidated_at=clock_timestamp(),invalidation_code='evaluated_state_changed'
 WHERE organization_id=affected_organization_id AND invalidated_at IS NULL;
 UPDATE configuration_approvals SET invalidated_at=clock_timestamp()
 WHERE organization_id=affected_organization_id AND invalidated_at IS NULL;
 RETURN NULL;
END $$;

CREATE FUNCTION careos_invalidate_configuration_evidence_for_mfa_user() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE affected_user_id uuid:=CASE WHEN TG_OP='DELETE' THEN OLD.user_id ELSE NEW.user_id END;
BEGIN
 UPDATE configuration_validation_results results
 SET invalidated_at=clock_timestamp(),invalidation_code='mfa_state_changed'
 WHERE results.invalidated_at IS NULL AND EXISTS(
  SELECT 1 FROM organization_memberships memberships
  WHERE memberships.organization_id=results.organization_id
    AND memberships.user_id=affected_user_id);
 UPDATE configuration_approvals approvals SET invalidated_at=clock_timestamp()
 WHERE approvals.invalidated_at IS NULL AND EXISTS(
  SELECT 1 FROM organization_memberships memberships
  WHERE memberships.organization_id=approvals.organization_id
    AND memberships.user_id=affected_user_id);
 RETURN NULL;
END $$;

CREATE TRIGGER organizations_invalidate_configuration AFTER UPDATE ON organizations FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER identifiers_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON organization_identifiers FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER addresses_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON organization_addresses FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER contacts_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON organization_contacts FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER international_settings_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON organization_international_settings FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER governance_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON organization_governance_responsibilities FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER facilities_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON facilities FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER organization_units_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON organization_units FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER service_locations_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON service_locations FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER operating_hours_batches_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON operating_hours_batches FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER operating_hours_intervals_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON operating_hours_intervals FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER operating_hours_exceptions_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON operating_hours_exceptions FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER operating_hours_exception_intervals_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON operating_hours_exception_intervals FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER service_definitions_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON service_definitions FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER service_assignments_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON service_assignments FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER identifier_schemes_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON identifier_schemes FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER identifier_scheme_versions_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON identifier_scheme_versions FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER memberships_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON organization_memberships FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER authorization_approvals_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON authorization_approval_requests FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence();
CREATE TRIGGER mfa_methods_invalidate_configuration AFTER INSERT OR UPDATE OR DELETE ON mfa_methods FOR EACH ROW WHEN (current_setting('app.current_operation_key',true) IS DISTINCT FROM 'configuration.activate') EXECUTE FUNCTION careos_invalidate_configuration_evidence_for_mfa_user();

GRANT UPDATE ON configuration_validation_results,configuration_approvals TO "${applicationRole}";
