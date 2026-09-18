CREATE TABLE authorization_registry_releases (
    registry_version varchar(80) PRIMARY KEY,
    approval_record_id varchar(120) NOT NULL UNIQUE,
    approval_package_sha256 char(64) NOT NULL,
    authorization_artifact_sha256 char(64) NOT NULL,
    approval_evidence_sha256 char(64) NOT NULL,
    approved_by varchar(200) NOT NULL,
    approved_at timestamptz NOT NULL,
    status varchar(24) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (registry_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$'),
    CHECK (approval_record_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$'),
    CHECK (approval_package_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (authorization_artifact_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (approval_evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (char_length(btrim(approved_by)) BETWEEN 3 AND 200),
    CHECK (status IN ('active', 'retired')),
    CHECK (isfinite(approved_at) AND isfinite(created_at))
);

CREATE UNIQUE INDEX authorization_registry_one_active_uq
    ON authorization_registry_releases ((status))
    WHERE status = 'active';

INSERT INTO authorization_registry_releases
    (registry_version, approval_record_id, approval_package_sha256,
     authorization_artifact_sha256, approval_evidence_sha256,
     approved_by, approved_at, status)
VALUES
    ('m1-candidate-1', 'M1-APPROVAL-20260916-01',
     '19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946',
     '3d65f85fc39d2ddcca0bcdce4fb43c1c8f4ff55902fbfc1a455669671e2c308b',
     '8a9e69692adebaccc738d51df225d2cf4ed98579b5451fca2c12a03131122735',
     'bhupendra, developer', '2026-09-16T15:52:55.639Z', 'active');

CREATE FUNCTION careos_reject_authorization_registry_release_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'authorization registry approval releases are migration-owned and immutable';
END;
$$;

CREATE TRIGGER authorization_registry_releases_no_update
    BEFORE UPDATE OR DELETE ON authorization_registry_releases
    FOR EACH ROW EXECUTE FUNCTION careos_reject_authorization_registry_release_change();

ALTER TABLE authorization_operations
    ADD COLUMN mfa_required boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT authorization_operations_mfa_check
        CHECK (NOT mfa_required OR recent_authentication_required);

ALTER TABLE authorization_permissions
    DROP CONSTRAINT authorization_permissions_permission_key_check,
    ADD CONSTRAINT authorization_permissions_permission_key_check
        CHECK (permission_key ~ '^[a-z][a-z0-9_]*([.:-][a-z0-9_]+)*$');

UPDATE authorization_permissions
SET display_name = 'Read organization profile',
    description = 'Read the approved active organization profile projection.',
    status = 'active',
    registry_version = 'm1-candidate-1',
    scope = 'organization',
    risk_class = 'low'
WHERE permission_key = 'organization.profile.read';

UPDATE authorization_permissions
SET display_name = 'Manage organization profile',
    description = 'Manage the approved organization profile fields with governed evidence.',
    status = 'active',
    registry_version = 'm1-candidate-1',
    scope = 'organization',
    risk_class = 'moderate'
WHERE permission_key = 'organization.profile.manage';

INSERT INTO authorization_permissions
    (permission_key, display_name, description, status, registry_version, scope, risk_class)
VALUES
    ('organization.identifier.read', 'Read organization identifiers',
     'Read approved organization identifier projections.', 'active', 'm1-candidate-1', 'organization', 'moderate'),
    ('organization.identifier.manage', 'Manage organization identifiers',
     'Create and update governed organization identifiers.', 'active', 'm1-candidate-1', 'organization', 'high'),
    ('organization.identifier.verify', 'Verify organization identifiers',
     'Record an assured organization identifier verification.', 'active', 'm1-candidate-1', 'organization', 'high'),
    ('organization.contact.read', 'Read organization contacts',
     'Read approved organization address and contact projections.', 'active', 'm1-candidate-1', 'organization', 'moderate'),
    ('organization.contact.manage', 'Manage organization contacts',
     'Manage effective organization addresses and contacts.', 'active', 'm1-candidate-1', 'organization', 'moderate'),
    ('organization.settings.read', 'Read international settings',
     'Read active organization locale, timezone, and regional settings.', 'active', 'm1-candidate-1', 'organization', 'low'),
    ('organization.settings.manage', 'Manage international settings',
     'Manage effective organization locale, timezone, and regional settings.', 'active', 'm1-candidate-1', 'organization', 'high'),
    ('organization.governance.read', 'Read governance responsibilities',
     'Read minimum-necessary organization governance responsibilities.', 'active', 'm1-candidate-1', 'organization', 'moderate'),
    ('organization.governance.manage', 'Manage governance responsibilities',
     'Manage effective organization governance responsibilities.', 'active', 'm1-candidate-1', 'organization', 'high'),
    ('network.facility.read', 'Read facilities',
     'Read active and authorized facility projections.', 'active', 'm1-candidate-1', 'organization', 'low'),
    ('network.facility.manage', 'Manage facility drafts',
     'Create and update facility draft configuration.', 'active', 'm1-candidate-1', 'organization', 'moderate'),
    ('network.facility.lifecycle', 'Manage facility lifecycle',
     'Submit and govern facility lifecycle transitions.', 'active', 'm1-candidate-1', 'organization', 'high'),
    ('network.structure.read', 'Read organization structure',
     'Read authorized department, unit, and location projections.', 'active', 'm1-candidate-1', 'organization', 'low'),
    ('network.structure.manage', 'Manage organization structure',
     'Create and update department, unit, and location drafts.', 'active', 'm1-candidate-1', 'organization', 'moderate'),
    ('network.structure.lifecycle', 'Manage structure lifecycle',
     'Govern department, unit, and location lifecycle transitions.', 'active', 'm1-candidate-1', 'organization', 'high'),
    ('network.hours.read', 'Read operating hours',
     'Read active and scheduled operating-hours projections.', 'active', 'm1-candidate-1', 'organization', 'low'),
    ('network.hours.manage', 'Manage operating hours',
     'Manage atomic operating-hours batches and exceptions.', 'active', 'm1-candidate-1', 'organization', 'moderate'),
    ('service.catalog.read', 'Read service catalogue',
     'Read active and authorized service definitions.', 'active', 'm1-candidate-1', 'organization', 'low'),
    ('service.catalog.manage', 'Manage service catalogue',
     'Create and update service definition drafts.', 'active', 'm1-candidate-1', 'organization', 'moderate'),
    ('service.catalog.lifecycle', 'Manage service lifecycle',
     'Govern service activation and retirement.', 'active', 'm1-candidate-1', 'organization', 'high'),
    ('service.assignment.read', 'Read service assignments',
     'Read active and scheduled facility service assignments.', 'active', 'm1-candidate-1', 'organization', 'low'),
    ('service.assignment.manage', 'Manage service assignments',
     'Create and update service assignment drafts.', 'active', 'm1-candidate-1', 'organization', 'moderate'),
    ('service.assignment.lifecycle', 'Manage assignment lifecycle',
     'Govern service assignment lifecycle transitions.', 'active', 'm1-candidate-1', 'organization', 'high'),
    ('identifier.scheme.read', 'Read identifier schemes',
     'Read authorized identifier scheme projections.', 'active', 'm1-candidate-1', 'organization', 'moderate'),
    ('identifier.scheme.manage', 'Manage identifier scheme drafts',
     'Create immutable candidate identifier scheme versions.', 'active', 'm1-candidate-1', 'organization', 'high'),
    ('identifier.scheme.activate', 'Activate identifier schemes',
     'Independently activate an approved identifier scheme version.', 'active', 'm1-candidate-1', 'organization', 'critical'),
    ('identifier.scheme.retire', 'Retire identifier schemes',
     'Independently retire an active identifier scheme.', 'active', 'm1-candidate-1', 'organization', 'critical'),
    ('access.membership.read', 'Read organization access',
     'Read minimum-necessary organization membership summaries.', 'active', 'm1-candidate-1', 'organization', 'moderate'),
    ('access.membership.manage', 'Manage organization access',
     'Request governed organization membership role, scope, or revocation changes.', 'active', 'm1-candidate-1', 'organization', 'critical'),
    ('access.membership.approve', 'Approve organization access',
     'Independently approve a governed membership change.', 'active', 'm1-candidate-1', 'organization', 'critical'),
    ('access.invitation.issue', 'Issue organization invitations',
     'Issue an invitation within the actor delegation ceiling.', 'active', 'm1-candidate-1', 'organization', 'high'),
    ('access.invitation.revoke', 'Revoke organization invitations',
     'Revoke a pending organization invitation.', 'active', 'm1-candidate-1', 'organization', 'high'),
    ('access.mfa_reset.request', 'Request administrative MFA reset',
     'Request a separated administrative MFA reset.', 'active', 'm1-candidate-1', 'organization', 'critical'),
    ('access.mfa_reset.approve', 'Approve administrative MFA reset',
     'Independently approve an administrative MFA reset.', 'active', 'm1-candidate-1', 'organization', 'critical'),
    ('access.mfa_reset.execute', 'Execute administrative MFA reset',
     'Execute the exact independently approved MFA reset.', 'active', 'm1-candidate-1', 'organization', 'critical'),
    ('access.owner_transfer.request', 'Request owner transfer',
     'Request a final-owner-safe organization ownership transfer.', 'active', 'm1-candidate-1', 'organization', 'critical'),
    ('access.owner_transfer.approve', 'Approve owner transfer',
     'Independently approve an organization ownership transfer.', 'active', 'm1-candidate-1', 'organization', 'critical'),
    ('access.owner_transfer.execute', 'Execute owner transfer',
     'Execute the exact independently approved ownership transfer.', 'active', 'm1-candidate-1', 'organization', 'critical'),
    ('configuration.readiness.read', 'Read configuration readiness',
     'Read server-calculated configuration readiness.', 'active', 'm1-candidate-1', 'organization', 'low'),
    ('configuration.validation.run', 'Run configuration validation',
     'Run deterministic validation for an exact configuration revision.', 'active', 'm1-candidate-1', 'organization', 'moderate'),
    ('configuration.submit', 'Submit configuration',
     'Submit an exact fresh configuration result for independent decision.', 'active', 'm1-candidate-1', 'organization', 'high'),
    ('configuration.approve', 'Approve configuration',
     'Independently approve or reject submitted configuration.', 'active', 'm1-candidate-1', 'organization', 'critical'),
    ('configuration.activate', 'Activate configuration',
     'Activate an approved exact configuration revision.', 'active', 'm1-candidate-1', 'organization', 'critical'),
    ('evidence.history.read', 'Read configuration history',
     'Read minimum-necessary configuration history.', 'active', 'm1-candidate-1', 'organization', 'high'),
    ('evidence.audit.read', 'Read audit evidence',
     'Read purpose-bound minimum-necessary audit evidence.', 'active', 'm1-candidate-1', 'organization', 'high'),
    ('evidence.export.request', 'Request evidence export',
     'Request a bounded purpose-bound evidence export.', 'active', 'm1-candidate-1', 'organization', 'critical'),
    ('evidence.export.approve', 'Approve evidence export',
     'Independently authorize a restricted evidence export.', 'active', 'm1-candidate-1', 'organization', 'critical'),
    ('evidence.export.access', 'Access evidence export',
     'Access an authorized non-expired evidence export.', 'active', 'm1-candidate-1', 'organization', 'critical'),
    ('identity.invitation.accept', 'Accept organization invitation',
     'Public one-use invitation acceptance marker; never granted through a membership role.',
     'active', 'm1-candidate-1', 'self', 'moderate');

UPDATE authorization_roles
SET display_name = 'Organization owner',
    description = 'Final-owner role governed by separation, assurance, and owner-integrity rules.',
    status = 'active', registry_version = 'm1-candidate-1',
    interactive = true, invitation_assignable = false, final_owner = true
WHERE role_key = 'organization_owner';

UPDATE authorization_roles
SET display_name = 'Organization administrator',
    description = 'Day-to-day organization administrator without owner-transfer authority.',
    status = 'active', registry_version = 'm1-candidate-1',
    interactive = true, invitation_assignable = true, final_owner = false
WHERE role_key = 'organization_administrator';

UPDATE authorization_roles
SET description = 'Synthetic local-development owner; reference-only and never production eligible.',
    status = 'reference', registry_version = 'm1-candidate-1',
    interactive = true, invitation_assignable = false, final_owner = true
WHERE role_key = 'local_bootstrap';

INSERT INTO authorization_roles
    (role_key, display_name, description, status, registry_version,
     interactive, invitation_assignable, final_owner)
VALUES
    ('configuration_editor', 'Configuration editor',
     'Creates and submits draft organization configuration without approval authority.',
     'active', 'm1-candidate-1', true, true, false),
    ('configuration_approver', 'Configuration approver',
     'Independently decides and activates submitted configuration.',
     'active', 'm1-candidate-1', true, true, false),
    ('security_administrator', 'Security administrator',
     'Administers bounded invitations and initiates governed access-security requests.',
     'active', 'm1-candidate-1', true, true, false),
    ('auditor', 'Auditor',
     'Reads minimum-necessary history and audit evidence after required assurance.',
     'active', 'm1-candidate-1', true, true, false),
    ('export_approver', 'Export approver',
     'Independently authorizes restricted evidence exports.',
     'active', 'm1-candidate-1', true, true, false),
    ('organization_viewer', 'Organization viewer',
     'Reads active non-restricted organization configuration and readiness.',
     'active', 'm1-candidate-1', true, true, false);

DELETE FROM authorization_role_permissions
WHERE role_key IN ('organization_owner', 'organization_administrator', 'local_bootstrap');

INSERT INTO authorization_role_permissions (role_key, permission_key)
SELECT roles.role_key, permissions.permission_key
FROM (VALUES ('organization_owner'), ('local_bootstrap')) AS roles(role_key)
CROSS JOIN authorization_permissions permissions
WHERE permissions.registry_version = 'm1-candidate-1'
  AND permissions.permission_key <> 'identity.invitation.accept';

INSERT INTO authorization_role_permissions (role_key, permission_key)
VALUES
    ('organization_administrator', 'organization.profile.read'),
    ('organization_administrator', 'organization.profile.manage'),
    ('organization_administrator', 'organization.identifier.read'),
    ('organization_administrator', 'organization.identifier.manage'),
    ('organization_administrator', 'organization.identifier.verify'),
    ('organization_administrator', 'organization.contact.read'),
    ('organization_administrator', 'organization.contact.manage'),
    ('organization_administrator', 'organization.settings.read'),
    ('organization_administrator', 'organization.settings.manage'),
    ('organization_administrator', 'organization.governance.read'),
    ('organization_administrator', 'organization.governance.manage'),
    ('organization_administrator', 'network.facility.read'),
    ('organization_administrator', 'network.facility.manage'),
    ('organization_administrator', 'network.structure.read'),
    ('organization_administrator', 'network.structure.manage'),
    ('organization_administrator', 'network.hours.read'),
    ('organization_administrator', 'network.hours.manage'),
    ('organization_administrator', 'service.catalog.read'),
    ('organization_administrator', 'service.catalog.manage'),
    ('organization_administrator', 'service.assignment.read'),
    ('organization_administrator', 'service.assignment.manage'),
    ('organization_administrator', 'identifier.scheme.read'),
    ('organization_administrator', 'identifier.scheme.manage'),
    ('organization_administrator', 'access.membership.read'),
    ('organization_administrator', 'access.invitation.issue'),
    ('organization_administrator', 'access.invitation.revoke'),
    ('organization_administrator', 'access.mfa_reset.request'),
    ('organization_administrator', 'configuration.readiness.read'),
    ('organization_administrator', 'configuration.validation.run'),
    ('organization_administrator', 'configuration.submit'),
    ('organization_administrator', 'evidence.history.read'),
    ('configuration_editor', 'organization.profile.read'),
    ('configuration_editor', 'organization.profile.manage'),
    ('configuration_editor', 'organization.identifier.read'),
    ('configuration_editor', 'organization.identifier.manage'),
    ('configuration_editor', 'organization.contact.read'),
    ('configuration_editor', 'organization.contact.manage'),
    ('configuration_editor', 'organization.settings.read'),
    ('configuration_editor', 'organization.settings.manage'),
    ('configuration_editor', 'organization.governance.read'),
    ('configuration_editor', 'organization.governance.manage'),
    ('configuration_editor', 'network.facility.read'),
    ('configuration_editor', 'network.facility.manage'),
    ('configuration_editor', 'network.structure.read'),
    ('configuration_editor', 'network.structure.manage'),
    ('configuration_editor', 'network.hours.read'),
    ('configuration_editor', 'network.hours.manage'),
    ('configuration_editor', 'service.catalog.read'),
    ('configuration_editor', 'service.catalog.manage'),
    ('configuration_editor', 'service.assignment.read'),
    ('configuration_editor', 'service.assignment.manage'),
    ('configuration_editor', 'identifier.scheme.read'),
    ('configuration_editor', 'identifier.scheme.manage'),
    ('configuration_editor', 'configuration.readiness.read'),
    ('configuration_editor', 'configuration.validation.run'),
    ('configuration_editor', 'configuration.submit'),
    ('configuration_approver', 'organization.profile.read'),
    ('configuration_approver', 'organization.identifier.read'),
    ('configuration_approver', 'organization.contact.read'),
    ('configuration_approver', 'organization.settings.read'),
    ('configuration_approver', 'organization.governance.read'),
    ('configuration_approver', 'network.facility.read'),
    ('configuration_approver', 'network.facility.lifecycle'),
    ('configuration_approver', 'network.structure.read'),
    ('configuration_approver', 'network.structure.lifecycle'),
    ('configuration_approver', 'network.hours.read'),
    ('configuration_approver', 'service.catalog.read'),
    ('configuration_approver', 'service.catalog.lifecycle'),
    ('configuration_approver', 'service.assignment.read'),
    ('configuration_approver', 'service.assignment.lifecycle'),
    ('configuration_approver', 'identifier.scheme.read'),
    ('configuration_approver', 'identifier.scheme.activate'),
    ('configuration_approver', 'identifier.scheme.retire'),
    ('configuration_approver', 'configuration.readiness.read'),
    ('configuration_approver', 'configuration.approve'),
    ('configuration_approver', 'configuration.activate'),
    ('security_administrator', 'access.membership.read'),
    ('security_administrator', 'access.invitation.issue'),
    ('security_administrator', 'access.invitation.revoke'),
    ('security_administrator', 'access.mfa_reset.request'),
    ('auditor', 'evidence.history.read'),
    ('auditor', 'evidence.audit.read'),
    ('auditor', 'evidence.export.request'),
    ('export_approver', 'evidence.export.approve'),
    ('export_approver', 'evidence.export.access'),
    ('organization_viewer', 'organization.profile.read'),
    ('organization_viewer', 'organization.identifier.read'),
    ('organization_viewer', 'organization.contact.read'),
    ('organization_viewer', 'organization.settings.read'),
    ('organization_viewer', 'organization.governance.read'),
    ('organization_viewer', 'network.facility.read'),
    ('organization_viewer', 'network.structure.read'),
    ('organization_viewer', 'network.hours.read'),
    ('organization_viewer', 'service.catalog.read'),
    ('organization_viewer', 'service.assignment.read'),
    ('organization_viewer', 'identifier.scheme.read'),
    ('organization_viewer', 'configuration.readiness.read');

DELETE FROM authorization_role_delegations
WHERE delegator_role_key IN
    ('organization_owner', 'organization_administrator', 'security_administrator', 'local_bootstrap');

INSERT INTO authorization_role_delegations
    (delegator_role_key, target_role_key, registry_version)
VALUES
    ('organization_owner', 'organization_administrator', 'm1-candidate-1'),
    ('organization_owner', 'configuration_editor', 'm1-candidate-1'),
    ('organization_owner', 'configuration_approver', 'm1-candidate-1'),
    ('organization_owner', 'security_administrator', 'm1-candidate-1'),
    ('organization_owner', 'auditor', 'm1-candidate-1'),
    ('organization_owner', 'export_approver', 'm1-candidate-1'),
    ('organization_owner', 'organization_viewer', 'm1-candidate-1'),
    ('organization_administrator', 'configuration_editor', 'm1-candidate-1'),
    ('organization_administrator', 'organization_viewer', 'm1-candidate-1'),
    ('security_administrator', 'organization_viewer', 'm1-candidate-1'),
    ('local_bootstrap', 'organization_administrator', 'm1-candidate-1'),
    ('local_bootstrap', 'configuration_editor', 'm1-candidate-1'),
    ('local_bootstrap', 'configuration_approver', 'm1-candidate-1'),
    ('local_bootstrap', 'security_administrator', 'm1-candidate-1'),
    ('local_bootstrap', 'auditor', 'm1-candidate-1'),
    ('local_bootstrap', 'export_approver', 'm1-candidate-1'),
    ('local_bootstrap', 'organization_viewer', 'm1-candidate-1');

UPDATE authorization_operations
SET permission_key = 'organization.profile.read',
    status = 'active', registry_version = 'm1-candidate-1'
WHERE operation_key = 'organization.profile.read';

UPDATE authorization_operations
SET permission_key = 'configuration.readiness.read',
    status = 'active', registry_version = 'm1-candidate-1'
WHERE operation_key = 'organization.readiness.read';

UPDATE authorization_operations
SET permission_key = 'organization.profile.manage',
    status = 'active', registry_version = 'm1-candidate-1'
WHERE operation_key = 'organization.profile.update';

UPDATE audit_event_definitions
SET status = 'active', registry_version = 'm1-candidate-1'
WHERE event_name = 'organization.profile.updated' AND schema_version = 1;

UPDATE outbox_event_definitions
SET status = 'active', registry_version = 'm1-candidate-1'
WHERE event_name = 'organization.profile.updated' AND schema_version = 1;

UPDATE authorization_operation_events
SET status = 'active', registry_version = 'm1-candidate-1'
WHERE operation_key = 'organization.profile.update'
  AND event_name = 'organization.profile.updated' AND schema_version = 1;

CREATE OR REPLACE FUNCTION careos_validate_runtime_organization_update()
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
    configured_reason text :=
        nullif(current_setting('app.current_authorization_reason', true), '');
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF configured_operation IS DISTINCT FROM 'organization.profile.update'
        OR configured_organization IS DISTINCT FROM OLD.id
        OR configured_actor IS NULL
        OR configured_reason IS NULL THEN
        RAISE EXCEPTION 'organization profile updates require the authorized approved operation'
            USING ERRCODE = '42501';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.status IS DISTINCT FROM OLD.status
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'organization profile update attempted to change protected lifecycle data'
            USING ERRCODE = '23514';
    END IF;

    IF ROW(NEW.legal_name, NEW.display_name, NEW.country_code, NEW.timezone)
        IS NOT DISTINCT FROM
       ROW(OLD.legal_name, OLD.display_name, OLD.country_code, OLD.timezone) THEN
        RAISE EXCEPTION 'organization profile update must change at least one field'
            USING ERRCODE = '23514';
    END IF;

    NEW.updated_at := greatest(clock_timestamp(), OLD.updated_at + interval '1 microsecond');

    IF NEW.lock_version <> OLD.lock_version + 1
        OR NEW.updated_by IS DISTINCT FROM configured_actor THEN
        RAISE EXCEPTION 'organization profile revision evidence is invalid'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = NEW.timezone) THEN
        RAISE EXCEPTION 'organization timezone is not an IANA timezone identifier'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION careos_validate_runtime_organization_update() IS
    'Restricts runtime organization updates to the approved profile operation, exact tenant/actor/reason context, and monotonic revision evidence.';

INSERT INTO authorization_operations
    (operation_key, permission_key, display_name, description, mutation, denial_mode,
     reason_required, recent_authentication_required,
     recent_authentication_max_age_seconds, maximum_future_skew_seconds,
     maker_checker_required, status, registry_version, mfa_required)
VALUES
    ('identity.invitation.issue', 'access.invitation.issue', 'Issue invitation',
     'Issue a one-use invitation within the actor delegation ceiling.',
     true, 'explicit', true, true, 600, 5, false, 'active', 'm1-candidate-1', true),
    ('identity.invitation.revoke', 'access.invitation.revoke', 'Revoke invitation',
     'Revoke a pending organization invitation with durable evidence.',
     true, 'explicit', true, true, 600, 5, false, 'active', 'm1-candidate-1', true),
    ('identity.invitation.accept', 'identity.invitation.accept', 'Accept invitation',
     'Consume a valid one-use invitation for the exact invited account.',
     true, 'explicit', false, false, NULL, NULL, false, 'active', 'm1-candidate-1', false),
    ('identity.mfa.admin-reset.execute', 'access.mfa_reset.execute',
     'Execute administrative MFA reset',
     'Execute the exact separately approved MFA reset as its original maker.',
     true, 'hidden', true, true, 300, 5, true, 'active', 'm1-candidate-1', true)
ON CONFLICT (operation_key) DO NOTHING;

UPDATE authorization_operations
SET permission_key = 'access.mfa_reset.request',
    display_name = 'Request administrative MFA reset',
    description = 'Request an independently approved MFA reset for another active member.',
    mutation = true, denial_mode = 'hidden', reason_required = true,
    recent_authentication_required = true,
    recent_authentication_max_age_seconds = 300,
    maximum_future_skew_seconds = 5,
    maker_checker_required = false,
    status = 'active', registry_version = 'm1-candidate-1', mfa_required = true
WHERE operation_key = 'identity.mfa.admin-reset.request';

UPDATE authorization_operations
SET permission_key = 'access.mfa_reset.approve',
    display_name = 'Approve administrative MFA reset',
    description = 'Independently approve another account reset request without executing it.',
    mutation = true, denial_mode = 'hidden', reason_required = true,
    recent_authentication_required = true,
    recent_authentication_max_age_seconds = 300,
    maximum_future_skew_seconds = 5,
    maker_checker_required = false,
    status = 'active', registry_version = 'm1-candidate-1', mfa_required = true
WHERE operation_key = 'identity.mfa.admin-reset.approve';

ALTER TABLE authorization_approval_workflows
    DROP CONSTRAINT authorization_approval_workflows_request_operation_key_key,
    DROP CONSTRAINT authorization_approval_workflows_decision_operation_key_key;

UPDATE authorization_approval_workflows
SET status = 'retired'
WHERE target_operation_key = 'identity.mfa.admin-reset';

CREATE UNIQUE INDEX authorization_approval_active_request_operation_uq
    ON authorization_approval_workflows (request_operation_key)
    WHERE status <> 'retired';

CREATE UNIQUE INDEX authorization_approval_active_decision_operation_uq
    ON authorization_approval_workflows (decision_operation_key)
    WHERE status <> 'retired';

INSERT INTO authorization_approval_workflows
    (target_operation_key, request_operation_key, decision_operation_key,
     subject_type, maximum_approval_ttl_seconds, status, registry_version)
VALUES
    ('identity.mfa.admin-reset.execute', 'identity.mfa.admin-reset.request',
     'identity.mfa.admin-reset.approve', 'user', 1800, 'active', 'm1-candidate-1');

UPDATE audit_event_definitions
SET status = 'active', registry_version = 'm1-candidate-1'
WHERE event_name IN
    ('identity.mfa-admin-reset.requested', 'identity.mfa-admin-reset.approved',
     'identity.mfa-admin-reset.completed')
  AND schema_version = 1;

UPDATE outbox_event_definitions
SET status = 'active', registry_version = 'm1-candidate-1'
WHERE event_name IN
    ('identity.mfa-admin-reset.requested', 'identity.mfa-admin-reset.approved',
     'identity.mfa-admin-reset.completed')
  AND schema_version = 1;

INSERT INTO audit_event_definitions
    (event_name, schema_version, display_name, description, subject_type,
     reason_required, required_payload_keys, allowed_payload_keys,
     payload_schema, status, registry_version)
VALUES
    ('identity.invitation.issued', 1, 'Invitation issued',
     'A bounded organization invitation was issued.', 'invitation', true,
     ARRAY['emailHash', 'expiresAt', 'roleKey'], ARRAY['emailHash', 'expiresAt', 'roleKey'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('identity.invitation.revoked', 1, 'Invitation revoked',
     'A pending organization invitation was revoked.', 'invitation', true,
     ARRAY['emailHash', 'roleKey'], ARRAY['emailHash', 'roleKey'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('identity.invitation.accepted', 1, 'Invitation accepted',
     'A one-use organization invitation was accepted.', 'invitation', false,
     ARRAY['accountLink', 'emailHash', 'roleKey', 'userId'],
     ARRAY['accountLink', 'emailHash', 'roleKey', 'userId'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1');

INSERT INTO outbox_event_definitions
    (event_name, schema_version, description, aggregate_type,
     required_payload_keys, allowed_payload_keys, payload_schema, status, registry_version)
VALUES
    ('identity.invitation.issued', 1,
     'A bounded organization invitation was issued.', 'invitation',
     ARRAY['emailHash', 'expiresAt', 'roleKey'], ARRAY['emailHash', 'expiresAt', 'roleKey'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('identity.invitation.revoked', 1,
     'A pending organization invitation was revoked.', 'invitation',
     ARRAY['emailHash', 'roleKey'], ARRAY['emailHash', 'roleKey'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1'),
    ('identity.invitation.accepted', 1,
     'A one-use organization invitation was accepted.', 'invitation',
     ARRAY['accountLink', 'emailHash', 'roleKey', 'userId'],
     ARRAY['accountLink', 'emailHash', 'roleKey', 'userId'],
     '{"type":"object"}'::jsonb, 'active', 'm1-candidate-1');

INSERT INTO authorization_operation_events
    (operation_key, event_kind, event_name, schema_version, status, registry_version)
VALUES
    ('identity.invitation.issue', 'audit', 'identity.invitation.issued', 1,
     'active', 'm1-candidate-1'),
    ('identity.invitation.issue', 'outbox', 'identity.invitation.issued', 1,
     'active', 'm1-candidate-1'),
    ('identity.invitation.revoke', 'audit', 'identity.invitation.revoked', 1,
     'active', 'm1-candidate-1'),
    ('identity.invitation.revoke', 'outbox', 'identity.invitation.revoked', 1,
     'active', 'm1-candidate-1'),
    ('identity.invitation.accept', 'audit', 'identity.invitation.accepted', 1,
     'active', 'm1-candidate-1'),
    ('identity.invitation.accept', 'outbox', 'identity.invitation.accepted', 1,
     'active', 'm1-candidate-1'),
    ('identity.mfa.admin-reset.request', 'audit',
     'identity.mfa-admin-reset.requested', 1, 'active', 'm1-candidate-1'),
    ('identity.mfa.admin-reset.request', 'outbox',
     'identity.mfa-admin-reset.requested', 1, 'active', 'm1-candidate-1'),
    ('identity.mfa.admin-reset.approve', 'audit',
     'identity.mfa-admin-reset.approved', 1, 'active', 'm1-candidate-1'),
    ('identity.mfa.admin-reset.approve', 'outbox',
     'identity.mfa-admin-reset.approved', 1, 'active', 'm1-candidate-1'),
    ('identity.mfa.admin-reset.execute', 'audit',
     'identity.mfa-admin-reset.completed', 1, 'active', 'm1-candidate-1'),
    ('identity.mfa.admin-reset.execute', 'outbox',
     'identity.mfa-admin-reset.completed', 1, 'active', 'm1-candidate-1')
ON CONFLICT (operation_key, event_kind, event_name, schema_version) DO UPDATE
SET status = EXCLUDED.status, registry_version = EXCLUDED.registry_version;

CREATE OR REPLACE FUNCTION careos_validate_invitation_lifecycle()
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
    configured_reason text :=
        nullif(current_setting('app.current_authorization_reason', true), '');
    reference_enabled boolean :=
        coalesce(nullif(current_setting('app.reference_authorization_policy_enabled', true), '')::boolean, false);
BEGIN
    IF current_user <> '${applicationRole}' THEN
        RETURN NEW;
    END IF;

    IF NEW.organization_id IS DISTINCT FROM configured_organization THEN
        RAISE EXCEPTION 'invitation tenant does not match the authorized transaction'
            USING ERRCODE = '42501';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF configured_operation NOT IN ('identity.invitation.issue', 'organization.invitation.issue')
            OR NEW.invited_by IS DISTINCT FROM configured_actor
            OR NEW.status <> 'pending'
            OR NEW.issued_reason IS DISTINCT FROM configured_reason
            OR NEW.expires_at <= clock_timestamp()
            OR NEW.expires_at > clock_timestamp() + interval '7 days'
            OR NEW.accepted_by IS NOT NULL OR NEW.accepted_at IS NOT NULL
            OR NEW.revoked_by IS NOT NULL OR NEW.revoked_at IS NOT NULL THEN
            RAISE EXCEPTION 'invalid invitation issuance context or state'
                USING ERRCODE = '42501';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM authorization_roles target_role
            WHERE target_role.role_key = NEW.role_key
              AND target_role.invitation_assignable
              AND (target_role.status = 'active'
                   OR (reference_enabled AND target_role.status = 'reference'))
        ) OR NOT EXISTS (
            SELECT 1
            FROM organization_memberships membership
            JOIN authorization_roles delegator_role
              ON delegator_role.role_key = membership.role_key
            JOIN authorization_role_delegations delegation
              ON delegation.delegator_role_key = delegator_role.role_key
             AND delegation.target_role_key = NEW.role_key
             AND delegation.registry_version = delegator_role.registry_version
            JOIN authorization_roles target_role
              ON target_role.role_key = delegation.target_role_key
             AND target_role.registry_version = delegation.registry_version
            WHERE membership.organization_id = NEW.organization_id
              AND membership.user_id = configured_actor
              AND membership.status = 'active'
              AND membership.effective_from <= clock_timestamp()
              AND (membership.effective_to IS NULL
                   OR membership.effective_to > clock_timestamp())
              AND (delegator_role.status = 'active'
                   OR (reference_enabled AND delegator_role.status = 'reference'))
              AND (target_role.status = 'active'
                   OR (reference_enabled AND target_role.status = 'reference'))
        ) THEN
            RAISE EXCEPTION 'the invitation role exceeds the actor delegation ceiling'
                USING ERRCODE = '42501';
        END IF;

        NEW.created_at := clock_timestamp();
        NEW.updated_at := NEW.created_at;
        NEW.lock_version := 0;
        RETURN NEW;
    END IF;

    IF ROW(NEW.id, NEW.organization_id, NEW.email, NEW.display_name, NEW.role_key,
           NEW.token_hash, NEW.expires_at, NEW.invited_by, NEW.issued_reason, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.organization_id, OLD.email, OLD.display_name, OLD.role_key,
           OLD.token_hash, OLD.expires_at, OLD.invited_by, OLD.issued_reason, OLD.created_at)
        OR OLD.status <> 'pending'
        OR NEW.lock_version <> OLD.lock_version + 1 THEN
        RAISE EXCEPTION 'immutable invitation content or terminal state cannot be changed'
            USING ERRCODE = '55000';
    END IF;

    IF configured_operation IN ('identity.invitation.revoke', 'organization.invitation.revoke') THEN
        IF NEW.status <> 'revoked'
            OR NEW.revoked_by IS DISTINCT FROM configured_actor
            OR NEW.revocation_reason IS DISTINCT FROM configured_reason
            OR NEW.revocation_correlation_id IS DISTINCT FROM
                nullif(current_setting('app.current_correlation_id', true), '')
            OR NEW.revoked_at IS NOT NULL
            OR NEW.accepted_by IS NOT NULL OR NEW.accepted_at IS NOT NULL THEN
            RAISE EXCEPTION 'invalid invitation revocation state'
                USING ERRCODE = '42501';
        END IF;
        NEW.revoked_at := clock_timestamp();
    ELSIF configured_operation IN ('identity.invitation.accept', 'organization.invitation.accept') THEN
        IF NEW.status <> 'accepted'
            OR NEW.accepted_by IS DISTINCT FROM configured_actor
            OR NEW.acceptance_correlation_id IS DISTINCT FROM
                nullif(current_setting('app.current_correlation_id', true), '')
            OR NEW.accepted_existing_account IS NULL
            OR NEW.accepted_at IS NOT NULL
            OR NEW.revoked_by IS NOT NULL OR NEW.revoked_at IS NOT NULL
            OR OLD.expires_at <= clock_timestamp() THEN
            RAISE EXCEPTION 'invalid invitation acceptance state'
                USING ERRCODE = '42501';
        END IF;
        NEW.accepted_at := clock_timestamp();
    ELSE
        RAISE EXCEPTION 'invitation transition is not authorized for this operation'
            USING ERRCODE = '42501';
    END IF;

    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION careos_validate_authorization_approval_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    workflow authorization_approval_workflows%ROWTYPE;
    configured_actor uuid :=
        nullif(current_setting('app.current_actor_id', true), '')::uuid;
    configured_operation text :=
        nullif(current_setting('app.current_operation_key', true), '');
    configured_correlation text :=
        nullif(current_setting('app.current_correlation_id', true), '');
    configured_reason text :=
        nullif(current_setting('app.current_authorization_reason', true), '');
    configured_approval uuid :=
        nullif(current_setting('app.current_approval_id', true), '')::uuid;
    reference_enabled boolean := coalesce(
        nullif(current_setting('app.reference_authorization_policy_enabled', true), '')::boolean,
        false);
BEGIN
    SELECT * INTO workflow
    FROM authorization_approval_workflows registered
    WHERE registered.target_operation_key = NEW.operation_key
      AND (registered.status = 'active'
           OR (reference_enabled AND registered.status = 'reference'));
    IF NOT FOUND THEN
        RAISE EXCEPTION 'approval workflow is unknown, inactive, or retired'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF configured_operation IS DISTINCT FROM workflow.request_operation_key
            OR NEW.subject_type IS DISTINCT FROM workflow.subject_type
            OR NEW.requested_by_user_id IS DISTINCT FROM configured_actor
            OR NEW.request_reason IS DISTINCT FROM configured_reason
            OR NEW.request_correlation_id IS DISTINCT FROM configured_correlation
            OR NEW.status <> 'pending'
            OR NEW.expires_at > NEW.created_at
                + make_interval(secs => workflow.maximum_approval_ttl_seconds)
            OR (NEW.operation_key IN
                    ('identity.mfa.admin-reset', 'identity.mfa.admin-reset.execute')
                AND NEW.subject_id = NEW.requested_by_user_id) THEN
            RAISE EXCEPTION 'invalid authorization approval request'
                USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;

    IF ROW(NEW.id, NEW.organization_id, NEW.operation_key, NEW.subject_type,
           NEW.subject_id, NEW.requested_by_user_id, NEW.request_reason,
           NEW.request_correlation_id, NEW.expires_at, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.organization_id, OLD.operation_key, OLD.subject_type,
           OLD.subject_id, OLD.requested_by_user_id, OLD.request_reason,
           OLD.request_correlation_id, OLD.expires_at, OLD.created_at) THEN
        RAISE EXCEPTION 'authorization approval request identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.lock_version <> OLD.lock_version + 1
        OR NEW.updated_at < OLD.updated_at
        OR NEW.updated_at > clock_timestamp() + interval '5 seconds' THEN
        RAISE EXCEPTION 'invalid authorization approval revision'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.status = 'pending' AND NEW.status IN ('approved', 'rejected') THEN
        IF configured_operation IS DISTINCT FROM workflow.decision_operation_key
            OR NEW.decided_by_user_id IS DISTINCT FROM configured_actor
            OR NEW.decided_by_user_id = OLD.requested_by_user_id
            OR NEW.decided_by_user_id = OLD.subject_id
            OR NEW.decision_reason IS DISTINCT FROM configured_reason
            OR NEW.decision_correlation_id IS DISTINCT FROM configured_correlation
            OR NEW.decided_at < OLD.created_at
            OR NEW.decided_at > clock_timestamp() + interval '5 seconds'
            OR NEW.expires_at <= clock_timestamp()
            OR NEW.consumed_by_user_id IS NOT NULL
            OR NEW.consumed_at IS NOT NULL
            OR NEW.consumption_correlation_id IS NOT NULL
            OR NEW.consumed_idempotency_key IS NOT NULL THEN
            RAISE EXCEPTION 'invalid authorization approval decision'
                USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status IN ('pending', 'approved') AND NEW.status = 'expired' THEN
        IF configured_operation IS DISTINCT FROM workflow.request_operation_key
            OR configured_actor IS NULL
            OR OLD.expires_at > clock_timestamp()
            OR ROW(NEW.decided_by_user_id, NEW.decision_reason,
                   NEW.decision_correlation_id, NEW.decided_at)
               IS DISTINCT FROM
               ROW(OLD.decided_by_user_id, OLD.decision_reason,
                   OLD.decision_correlation_id, OLD.decided_at)
            OR NEW.consumed_by_user_id IS NOT NULL
            OR NEW.consumed_at IS NOT NULL
            OR NEW.consumption_correlation_id IS NOT NULL
            OR NEW.consumed_idempotency_key IS NOT NULL THEN
            RAISE EXCEPTION 'invalid authorization approval expiration'
                USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'approved' AND NEW.status = 'consumed' THEN
        IF configured_operation IS DISTINCT FROM workflow.target_operation_key
            OR configured_approval IS DISTINCT FROM OLD.id
            OR NEW.consumed_by_user_id IS DISTINCT FROM configured_actor
            OR NEW.consumed_by_user_id IS DISTINCT FROM OLD.requested_by_user_id
            OR NEW.consumption_correlation_id IS DISTINCT FROM configured_correlation
            OR NEW.consumed_idempotency_key IS NULL
            OR NEW.consumed_at < OLD.decided_at
            OR NEW.consumed_at > clock_timestamp() + interval '5 seconds'
            OR NEW.expires_at <= clock_timestamp()
            OR ROW(NEW.decided_by_user_id, NEW.decision_reason,
                   NEW.decision_correlation_id, NEW.decided_at)
               IS DISTINCT FROM
               ROW(OLD.decided_by_user_id, OLD.decision_reason,
                   OLD.decision_correlation_id, OLD.decided_at) THEN
            RAISE EXCEPTION 'invalid authorization approval consumption'
                USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'invalid authorization approval lifecycle transition'
        USING ERRCODE = '23514';
END;
$$;

REVOKE ALL ON authorization_registry_releases FROM PUBLIC;
REVOKE ALL ON FUNCTION careos_reject_authorization_registry_release_change() FROM PUBLIC;
GRANT SELECT ON authorization_registry_releases TO "${applicationRole}";

COMMENT ON TABLE authorization_registry_releases IS
    'Checksum-bound approval evidence for migration-owned active authorization registries.';
COMMENT ON COLUMN authorization_operations.mfa_required IS
    'Requires a separately recorded recent MFA assertion in addition to recent authentication.';
