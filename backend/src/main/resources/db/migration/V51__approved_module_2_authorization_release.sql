-- Module 2 is an additive authorization release. Module 1 remains active because its
-- canonical roles and operations continue to protect the shared identity and network sources.
DROP TRIGGER authorization_registry_releases_no_update ON authorization_registry_releases;

ALTER TABLE authorization_registry_releases
    ADD COLUMN module_key varchar(16) NOT NULL DEFAULT 'M1',
    ADD CONSTRAINT authorization_registry_releases_module_key_check
        CHECK (module_key ~ '^M[1-9][0-9]*$');

DROP INDEX authorization_registry_one_active_uq;
CREATE UNIQUE INDEX authorization_registry_one_active_per_module_uq
    ON authorization_registry_releases (module_key)
    WHERE status = 'active';

INSERT INTO authorization_registry_releases
    (registry_version, approval_record_id, approval_package_sha256,
     authorization_artifact_sha256, approval_evidence_sha256,
     approved_by, approved_at, status, module_key)
VALUES
    ('m2-candidate-1', 'M2-APPROVAL-20260921-01',
     '624df2edc0024526040271911d43a1b33a12e723fefb3beb3e985264cef89521',
     '143f01aafc2a8f2a6f2bfade2365006ac8b781472696f1db233a1307b122ca32',
     '57676a269fee327d5a1f8c7458c901343c8b222af398025a98ca41c6b9aadbbe',
     'bhupendra, developer', '2026-09-21T13:43:43.843Z', 'active', 'M2');

CREATE TRIGGER authorization_registry_releases_no_update
    BEFORE UPDATE OR DELETE ON authorization_registry_releases
    FOR EACH ROW EXECUTE FUNCTION careos_reject_authorization_registry_release_change();

ALTER TABLE authorization_operations
    DROP CONSTRAINT authorization_operations_operation_key_check,
    ADD CONSTRAINT authorization_operations_operation_key_check
        CHECK (operation_key ~ '^[a-z][a-z0-9_]*([.:-][a-z0-9_]+)*$');

INSERT INTO authorization_permissions
    (permission_key, display_name, description, status, registry_version, scope, risk_class)
VALUES
    ('workforce.dashboard.read', 'Read workforce dashboard', 'Read permission-scoped workforce metrics and queues.', 'active', 'm2-candidate-1', 'organization', 'low'),
    ('workforce.directory.read', 'Read workforce directory', 'Search the minimum-necessary workforce directory.', 'active', 'm2-candidate-1', 'organization', 'low'),
    ('workforce.member.read', 'Read workforce member', 'Read an authorized workforce member projection.', 'active', 'm2-candidate-1', 'organization', 'moderate'),
    ('workforce.member.create', 'Create workforce member', 'Start a governed clinical or non-clinical workforce pathway.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.member.manage', 'Manage workforce member', 'Manage an authorized workforce member draft.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.person.match', 'Match organization person', 'Run and decide organization-scoped person matching.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.person.restricted_read', 'Read restricted person fields', 'Read purpose-bound restricted person details.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.person.correct', 'Correct person profile', 'Append a governed person-profile correction.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.person.merge.request', 'Request person-link merge', 'Request organization-local person-link consolidation.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.person.merge.approve', 'Approve person-link merge', 'Independently approve person-link consolidation.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.person.merge.execute', 'Execute person-link merge', 'Execute an approved organization-local person-link consolidation.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.engagement.read', 'Read engagement', 'Read minimum-necessary engagement evidence.', 'active', 'm2-candidate-1', 'organization', 'moderate'),
    ('workforce.engagement.manage', 'Manage engagement', 'Create and update effective-dated engagements.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.engagement.lifecycle', 'Manage engagement lifecycle', 'Schedule, activate, suspend, or end an engagement.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.practitioner.read', 'Read practitioner profile', 'Read an authorized practitioner projection.', 'active', 'm2-candidate-1', 'organization', 'moderate'),
    ('workforce.practitioner.manage', 'Manage practitioner profile', 'Manage a clinical practitioner draft.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.practitioner.lifecycle', 'Manage practitioner lifecycle', 'Govern practitioner profile lifecycle changes.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('credential.qualification.read', 'Read qualifications', 'Read qualification evidence projections.', 'active', 'm2-candidate-1', 'organization', 'moderate'),
    ('credential.qualification.manage', 'Manage qualifications', 'Create and submit qualification versions.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('credential.registration.read', 'Read registrations', 'Read masked professional registration projections.', 'active', 'm2-candidate-1', 'organization', 'moderate'),
    ('credential.registration.manage', 'Manage registrations', 'Create and renew registration versions.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('credential.registration.lifecycle', 'Manage registration lifecycle', 'Govern registration suspension, revocation, expiry, and supersession.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('credential.record.read', 'Read credential records', 'Read minimum-necessary credential records.', 'active', 'm2-candidate-1', 'organization', 'moderate'),
    ('credential.record.manage', 'Manage credential records', 'Create, update, and submit credential versions.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('credential.document.upload', 'Upload credential evidence', 'Create and complete a private quarantined credential upload.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('credential.document.read', 'Read clean credential evidence', 'Request purpose-bound access to clean promoted credential evidence.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('credential.review.queue', 'Read credential review queue', 'Read independently reviewable credential work.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('credential.review.decide', 'Decide credential review', 'Record an independent credential verification decision.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('credential.lifecycle', 'Manage credential lifecycle', 'Suspend, revoke, expire, or supersede a credential.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('practitioner.specialty.read', 'Read specialties', 'Read practitioner specialty versions.', 'active', 'm2-candidate-1', 'organization', 'moderate'),
    ('practitioner.specialty.manage', 'Manage specialties', 'Manage effective primary and secondary specialties.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('practitioner.scope.read', 'Read scope of practice', 'Read authorized clinical scope projections.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('practitioner.scope.manage', 'Manage scope draft', 'Create and update clinical scope versions.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('practitioner.scope.submit', 'Submit scope of practice', 'Submit an exact scope result for independent decision.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('practitioner.scope.approve', 'Approve scope of practice', 'Independently decide a submitted clinical scope.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('practitioner.scope.lifecycle', 'Manage scope lifecycle', 'Suspend, end, or supersede approved clinical scope.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.assignment.read', 'Read workforce assignments', 'Read hierarchy-valid workforce assignments.', 'active', 'm2-candidate-1', 'organization', 'moderate'),
    ('workforce.assignment.manage', 'Manage workforce assignments', 'Create and update assignment drafts.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.assignment.lifecycle', 'Manage assignment lifecycle', 'Activate, transfer, suspend, reactivate, end, or cancel assignments.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('practitioner.service_assignment.read', 'Read practitioner service assignments', 'Read service/context assignment projections.', 'active', 'm2-candidate-1', 'organization', 'moderate'),
    ('practitioner.service_assignment.manage', 'Manage practitioner service assignments', 'Create and update service/context assignments.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('practitioner.service_assignment.lifecycle', 'Manage practitioner service assignment lifecycle', 'Govern service assignment lifecycle.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('practitioner.eligibility.read', 'Read practitioner eligibility', 'Read immutable point-in-time eligibility evidence.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.availability.read', 'Read workforce availability', 'Read authorized working-pattern projections.', 'active', 'm2-candidate-1', 'organization', 'moderate'),
    ('workforce.availability.manage', 'Manage workforce availability', 'Replace a weekly availability profile atomically.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.account_link.read', 'Read workforce account-link state', 'Read the minimum account-link projection.', 'active', 'm2-candidate-1', 'organization', 'moderate'),
    ('workforce.account_link.request', 'Request workforce account link', 'Request an existing-user link or canonical M1 invitation flow.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.readiness.read', 'Read workforce readiness', 'Read server-calculated readiness results.', 'active', 'm2-candidate-1', 'organization', 'moderate'),
    ('workforce.validation.run', 'Run workforce validation', 'Evaluate the exact pathway readiness catalogue.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.activation.submit', 'Submit workforce activation', 'Submit a fresh zero-blocker readiness result.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.activation.approve', 'Approve workforce activation', 'Independently decide an activation request.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.activation.execute', 'Execute workforce activation', 'Activate an approved exact workforce revision.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.lifecycle.suspend', 'Suspend workforce member', 'Govern a prospective workforce suspension.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.lifecycle.reactivate', 'Reactivate workforce member', 'Govern workforce reactivation from fresh readiness.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.offboarding.request', 'Request workforce offboarding', 'Create an exact impact-bound offboarding request.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.offboarding.approve', 'Approve workforce offboarding', 'Independently approve an offboarding plan.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.offboarding.execute', 'Execute workforce offboarding', 'Execute an approved due offboarding plan.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.expiry.read', 'Read credential expiry queues', 'Read mutually exclusive credential expiry buckets.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.expiry.escalate', 'Escalate credential expiry', 'Record a governed expiry escalation.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.history.read', 'Read workforce configuration history', 'Read immutable configuration history.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.audit.read', 'Read workforce audit evidence', 'Read purpose-bound workforce audit evidence.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.export.request', 'Request workforce export', 'Request a bounded purpose-bound workforce export.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.export.approve', 'Approve workforce export', 'Independently authorize a restricted workforce export.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.export.access', 'Access workforce export', 'Access an authorized non-expired workforce export.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.registry.read', 'Read workforce registries', 'Read active controlled workforce catalogue versions.', 'active', 'm2-candidate-1', 'organization', 'moderate'),
    ('workforce.registry.manage', 'Manage workforce registries', 'Create controlled workforce registry change versions.', 'active', 'm2-candidate-1', 'organization', 'high'),
    ('workforce.registry.approve', 'Approve workforce registry changes', 'Independently approve a workforce registry change.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.registry.activate', 'Activate workforce registry changes', 'Activate an approved registry/configuration snapshot.', 'active', 'm2-candidate-1', 'organization', 'critical'),
    ('workforce.timeline.read', 'Read workforce evidence timeline', 'Read a minimum-necessary member evidence timeline.', 'active', 'm2-candidate-1', 'organization', 'high');

-- Interactive roles remain entries in the canonical M1 role registry. Their M2 grants below are
-- independently versioned permissions and are usable only while both releases are active.
INSERT INTO authorization_roles
    (role_key, display_name, description, status, registry_version,
     interactive, invitation_assignable, final_owner)
VALUES
    ('workforce_administrator', 'Workforce administrator', 'Orchestrates governed workforce onboarding, assignments, readiness, and lifecycle.', 'active', 'm1-candidate-1', true, true, false),
    ('hr_administrator', 'HR administrator', 'Manages person, engagement, non-clinical assignment, and offboarding records.', 'active', 'm1-candidate-1', true, true, false),
    ('facility_administrator', 'Facility administrator', 'Manages facility-scoped workforce assignments and availability.', 'active', 'm1-candidate-1', true, true, false),
    ('credentialing_officer', 'Credentialing officer', 'Manages and independently verifies credential evidence.', 'active', 'm1-candidate-1', true, true, false),
    ('clinical_governance_approver', 'Clinical governance approver', 'Independently governs clinical scope and related lifecycle decisions.', 'active', 'm1-candidate-1', true, true, false),
    ('practitioner', 'Practitioner', 'Receives minimum-necessary self-service workforce access.', 'active', 'm1-candidate-1', true, true, false),
    ('clinical_support_staff', 'Clinical support staff', 'Receives minimum operational self-service workforce access.', 'active', 'm1-candidate-1', true, true, false),
    ('service_m2_credential_scan', 'Module 2 credential scan coordinator', 'Binds approved platform scan evidence to credential documents.', 'active', 'm2-candidate-1', false, false, false),
    ('service_m2_eligibility', 'Module 2 eligibility evaluator', 'Calculates immutable practitioner eligibility evidence.', 'active', 'm2-candidate-1', false, false, false),
    ('service_m2_expiry', 'Module 2 expiry scheduler', 'Creates deterministic expiry milestones.', 'active', 'm2-candidate-1', false, false, false),
    ('service_m2_notification', 'Module 2 notification worker', 'Delivers approved minimum-necessary workforce notifications.', 'active', 'm2-candidate-1', false, false, false),
    ('service_m2_offboarding', 'Module 2 offboarding worker', 'Executes only approved due offboarding plans.', 'active', 'm2-candidate-1', false, false, false),
    ('service_m2_export', 'Module 2 export worker', 'Generates approved bounded workforce export artifacts.', 'active', 'm2-candidate-1', false, false, false),
    ('service_m2_retention', 'Module 2 retention worker', 'Disposes expired workforce artifacts after hold checks.', 'active', 'm2-candidate-1', false, false, false),
    ('service_m2_outbox', 'Module 2 outbox publisher', 'Publishes only approved Module 2 event versions.', 'active', 'm2-candidate-1', false, false, false);

INSERT INTO authorization_role_permissions (role_key, permission_key)
SELECT role_key, permission_key
FROM (VALUES ('organization_owner'), ('local_bootstrap')) AS roles(role_key)
CROSS JOIN authorization_permissions permissions
WHERE permissions.registry_version = 'm2-candidate-1';

INSERT INTO authorization_role_permissions (role_key, permission_key)
SELECT 'organization_administrator', permission_key
FROM authorization_permissions
WHERE registry_version = 'm2-candidate-1'
  AND permission_key = ANY (ARRAY[
    'workforce.dashboard.read','workforce.directory.read','workforce.member.read',
    'workforce.member.create','workforce.member.manage','workforce.person.match',
    'workforce.engagement.read','workforce.engagement.manage','workforce.assignment.read',
    'workforce.assignment.manage','workforce.availability.read','workforce.availability.manage',
    'workforce.account_link.read','workforce.account_link.request','workforce.readiness.read',
    'workforce.validation.run','workforce.activation.submit','workforce.history.read',
    'workforce.registry.read','workforce.timeline.read'
  ]);

INSERT INTO authorization_role_permissions (role_key, permission_key)
SELECT grants.role_key, grants.permission_key
FROM (VALUES
    ('workforce_administrator','workforce.dashboard.read'),('workforce_administrator','workforce.directory.read'),
    ('workforce_administrator','workforce.member.read'),('workforce_administrator','workforce.member.create'),
    ('workforce_administrator','workforce.member.manage'),('workforce_administrator','workforce.person.match'),
    ('workforce_administrator','workforce.engagement.read'),('workforce_administrator','workforce.engagement.manage'),
    ('workforce_administrator','workforce.practitioner.read'),('workforce_administrator','workforce.practitioner.manage'),
    ('workforce_administrator','workforce.assignment.read'),('workforce_administrator','workforce.assignment.manage'),
    ('workforce_administrator','practitioner.service_assignment.read'),('workforce_administrator','practitioner.service_assignment.manage'),
    ('workforce_administrator','workforce.availability.read'),('workforce_administrator','workforce.availability.manage'),
    ('workforce_administrator','workforce.account_link.read'),('workforce_administrator','workforce.account_link.request'),
    ('workforce_administrator','workforce.readiness.read'),('workforce_administrator','workforce.validation.run'),
    ('workforce_administrator','workforce.activation.submit'),('workforce_administrator','workforce.lifecycle.suspend'),
    ('workforce_administrator','workforce.lifecycle.reactivate'),('workforce_administrator','workforce.offboarding.request'),
    ('workforce_administrator','workforce.timeline.read'),
    ('hr_administrator','workforce.dashboard.read'),('hr_administrator','workforce.directory.read'),
    ('hr_administrator','workforce.member.read'),('hr_administrator','workforce.member.create'),
    ('hr_administrator','workforce.member.manage'),('hr_administrator','workforce.person.match'),
    ('hr_administrator','workforce.person.restricted_read'),('hr_administrator','workforce.person.correct'),
    ('hr_administrator','workforce.engagement.read'),('hr_administrator','workforce.engagement.manage'),
    ('hr_administrator','workforce.engagement.lifecycle'),('hr_administrator','workforce.assignment.read'),
    ('hr_administrator','workforce.assignment.manage'),('hr_administrator','workforce.assignment.lifecycle'),
    ('hr_administrator','workforce.availability.read'),('hr_administrator','workforce.availability.manage'),
    ('hr_administrator','workforce.lifecycle.suspend'),('hr_administrator','workforce.lifecycle.reactivate'),
    ('hr_administrator','workforce.offboarding.request'),('hr_administrator','workforce.timeline.read'),
    ('facility_administrator','workforce.dashboard.read'),('facility_administrator','workforce.directory.read'),
    ('facility_administrator','workforce.member.read'),('facility_administrator','workforce.assignment.read'),
    ('facility_administrator','workforce.assignment.manage'),('facility_administrator','workforce.availability.read'),
    ('facility_administrator','workforce.availability.manage'),('facility_administrator','workforce.readiness.read'),
    ('credentialing_officer','workforce.member.read'),('credentialing_officer','workforce.practitioner.read'),
    ('credentialing_officer','credential.qualification.read'),('credentialing_officer','credential.qualification.manage'),
    ('credentialing_officer','credential.registration.read'),('credentialing_officer','credential.registration.manage'),
    ('credentialing_officer','credential.registration.lifecycle'),('credentialing_officer','credential.record.read'),
    ('credentialing_officer','credential.record.manage'),('credentialing_officer','credential.document.upload'),
    ('credentialing_officer','credential.document.read'),('credentialing_officer','credential.review.queue'),
    ('credentialing_officer','credential.review.decide'),('credentialing_officer','credential.lifecycle'),
    ('credentialing_officer','workforce.assignment.read'),
    ('credentialing_officer','workforce.expiry.read'),('credentialing_officer','workforce.expiry.escalate'),
    ('clinical_governance_approver','workforce.member.read'),('clinical_governance_approver','workforce.practitioner.read'),
    ('clinical_governance_approver','practitioner.specialty.read'),('clinical_governance_approver','practitioner.scope.read'),
    ('clinical_governance_approver','practitioner.scope.approve'),('clinical_governance_approver','practitioner.scope.lifecycle'),
    ('clinical_governance_approver','practitioner.eligibility.read'),
    ('clinical_governance_approver','credential.record.read'),
    ('clinical_governance_approver','workforce.lifecycle.suspend'),('clinical_governance_approver','workforce.lifecycle.reactivate'),
    ('practitioner','workforce.member.read'),('practitioner','workforce.practitioner.read'),
    ('practitioner','credential.qualification.read'),('practitioner','credential.qualification.manage'),
    ('practitioner','credential.registration.read'),('practitioner','credential.registration.manage'),
    ('practitioner','credential.record.read'),('practitioner','credential.record.manage'),
    ('practitioner','credential.document.upload'),('practitioner','practitioner.specialty.read'),
    ('practitioner','practitioner.scope.read'),('practitioner','workforce.assignment.read'),
    ('practitioner','practitioner.service_assignment.read'),('practitioner','workforce.availability.read'),
    ('practitioner','workforce.availability.manage'),('practitioner','workforce.timeline.read'),
    ('clinical_support_staff','workforce.member.read'),('clinical_support_staff','workforce.assignment.read'),
    ('clinical_support_staff','workforce.availability.read'),('clinical_support_staff','workforce.availability.manage'),
    ('clinical_support_staff','workforce.timeline.read'),
    ('security_administrator','workforce.account_link.read'),
    ('security_administrator','workforce.account_link.request'),
    ('auditor','workforce.history.read'),('auditor','workforce.audit.read'),('auditor','workforce.timeline.read'),
    ('export_approver','workforce.export.approve'),
    ('organization_viewer','workforce.dashboard.read'),('organization_viewer','workforce.directory.read'),
    ('organization_viewer','workforce.member.read'),('organization_viewer','workforce.readiness.read'),
    ('service_m2_credential_scan','credential.document.upload'),
    ('service_m2_eligibility','practitioner.eligibility.read'),
    ('service_m2_expiry','workforce.expiry.escalate'),
    ('service_m2_notification','workforce.expiry.escalate'),
    ('service_m2_offboarding','workforce.offboarding.execute'),
    ('service_m2_export','workforce.export.request'),
    ('service_m2_retention','workforce.export.access'),
    ('service_m2_outbox','workforce.audit.read')
) AS grants(role_key, permission_key);

INSERT INTO authorization_role_delegations
    (delegator_role_key, target_role_key, registry_version)
VALUES
    ('organization_owner','workforce_administrator','m1-candidate-1'),
    ('organization_owner','hr_administrator','m1-candidate-1'),
    ('organization_owner','facility_administrator','m1-candidate-1'),
    ('organization_owner','credentialing_officer','m1-candidate-1'),
    ('organization_owner','clinical_governance_approver','m1-candidate-1'),
    ('organization_owner','practitioner','m1-candidate-1'),
    ('organization_owner','clinical_support_staff','m1-candidate-1'),
    ('organization_administrator','workforce_administrator','m1-candidate-1'),
    ('organization_administrator','hr_administrator','m1-candidate-1'),
    ('organization_administrator','facility_administrator','m1-candidate-1'),
    ('organization_administrator','practitioner','m1-candidate-1'),
    ('organization_administrator','clinical_support_staff','m1-candidate-1');

INSERT INTO authorization_operations
    (operation_key, permission_key, display_name, description, mutation, denial_mode,
     reason_required, recent_authentication_required,
     recent_authentication_max_age_seconds, maximum_future_skew_seconds,
     maker_checker_required, status, registry_version, mfa_required)
SELECT permission_key,
       permission_key,
       display_name,
       description,
       NOT (permission_key LIKE '%.read' OR permission_key = 'credential.review.queue'),
       CASE WHEN risk_class = 'critical' AND permission_key NOT LIKE '%.read' THEN 'explicit' ELSE 'hidden' END,
       NOT (permission_key LIKE '%.read' OR permission_key = 'credential.review.queue'
            OR permission_key IN ('workforce.validation.run','workforce.export.access')),
       permission_key = ANY (ARRAY[
           'workforce.person.restricted_read','workforce.person.merge.request',
           'workforce.person.merge.approve','workforce.person.merge.execute',
           'credential.document.read','credential.review.decide',
           'credential.registration.lifecycle','credential.lifecycle','practitioner.scope.submit',
           'practitioner.scope.approve','practitioner.scope.lifecycle','workforce.assignment.lifecycle',
           'practitioner.service_assignment.lifecycle','workforce.activation.submit',
           'workforce.activation.approve','workforce.activation.execute','workforce.lifecycle.suspend',
           'workforce.lifecycle.reactivate','workforce.offboarding.request','workforce.offboarding.approve',
           'workforce.offboarding.execute','workforce.audit.read','workforce.export.request',
           'workforce.export.approve','workforce.export.access','workforce.registry.approve',
           'workforce.registry.activate'
       ]),
       CASE WHEN permission_key = ANY (ARRAY[
           'workforce.person.restricted_read','credential.document.read','workforce.audit.read'
       ]) THEN 600
       WHEN permission_key = ANY (ARRAY[
           'workforce.person.merge.request','workforce.person.merge.approve',
           'workforce.person.merge.execute','credential.review.decide',
           'credential.registration.lifecycle','credential.lifecycle',
           'practitioner.scope.submit','practitioner.scope.approve','practitioner.scope.lifecycle',
           'workforce.assignment.lifecycle','practitioner.service_assignment.lifecycle',
           'workforce.activation.submit','workforce.activation.approve','workforce.activation.execute',
           'workforce.lifecycle.suspend','workforce.lifecycle.reactivate','workforce.offboarding.request',
           'workforce.offboarding.approve','workforce.offboarding.execute','workforce.export.request',
           'workforce.export.approve','workforce.export.access','workforce.registry.approve',
           'workforce.registry.activate'
       ]) THEN 300
       ELSE NULL END,
       CASE WHEN permission_key = ANY (ARRAY[
           'workforce.person.restricted_read','workforce.person.merge.request',
           'workforce.person.merge.approve','workforce.person.merge.execute',
           'credential.document.read','credential.review.decide',
           'credential.registration.lifecycle','credential.lifecycle','practitioner.scope.submit',
           'practitioner.scope.approve','practitioner.scope.lifecycle','workforce.assignment.lifecycle',
           'practitioner.service_assignment.lifecycle','workforce.activation.submit',
           'workforce.activation.approve','workforce.activation.execute','workforce.lifecycle.suspend',
           'workforce.lifecycle.reactivate','workforce.offboarding.request','workforce.offboarding.approve',
           'workforce.offboarding.execute','workforce.audit.read','workforce.export.request',
           'workforce.export.approve','workforce.export.access','workforce.registry.approve',
           'workforce.registry.activate'
       ]) THEN 5 ELSE NULL END,
       false,
       'active',
       'm2-candidate-1',
       permission_key = ANY (ARRAY[
           'workforce.audit.read','workforce.person.merge.request',
           'workforce.person.merge.approve','workforce.person.merge.execute',
           'credential.document.read','credential.review.decide',
           'credential.registration.lifecycle','credential.lifecycle','practitioner.scope.submit',
           'practitioner.scope.approve','practitioner.scope.lifecycle','workforce.activation.submit',
           'workforce.activation.approve','workforce.activation.execute','workforce.lifecycle.suspend',
           'workforce.lifecycle.reactivate','workforce.offboarding.request',
           'workforce.offboarding.approve','workforce.offboarding.execute','workforce.export.request',
           'workforce.export.approve','workforce.export.access','workforce.registry.approve',
           'workforce.registry.activate'
       ])
FROM authorization_permissions
WHERE registry_version = 'm2-candidate-1';

REVOKE ALL ON authorization_registry_releases FROM PUBLIC;
GRANT SELECT ON authorization_registry_releases TO "${applicationRole}";

COMMENT ON COLUMN authorization_registry_releases.module_key IS
    'Approved module owning this additive registry release; at most one active release per module.';
