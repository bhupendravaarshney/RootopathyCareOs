-- Approved additive Module 3 patient-registry authorization release.
-- Module 1 and Module 2 remain active because their identity, organization and
-- workforce sources continue to be authoritative dependencies of this module.
INSERT INTO authorization_registry_releases
    (registry_version, approval_record_id, approval_package_sha256,
     authorization_artifact_sha256, approval_evidence_sha256,
     approved_by, approved_at, status, module_key)
VALUES
    ('m3-candidate-1', 'M3-APPROVAL-20260926-01',
     '3e7ced79ecc01f59e9d4d3bb15a48bd30e579a32f194b23e9f3ee0a9aed09c00',
     '2f72d355094802dfb740e82d4a82113034f4600c905263b8c51ece81b943e7ec',
     'fc05236d7cddcf23a098b9b49601c438c8979f38c74286f86ad4f29bae285586',
     'bhupendra, developer', '2026-09-26T10:51:24.596Z', 'active', 'M3');

INSERT INTO authorization_permissions
    (permission_key, display_name, description, status, registry_version, scope, risk_class)
VALUES
    ('patient.dashboard.read', 'Read patient dashboard', 'Read minimum-necessary patient registry metrics and queues.', 'active', 'm3-candidate-1', 'organization', 'low'),
    ('patient.directory.read', 'Read patient directory', 'Search the bounded organization-local patient directory.', 'active', 'm3-candidate-1', 'organization', 'moderate'),
    ('patient.profile.read', 'Read patient profile', 'Read an authorized minimum-necessary patient profile projection.', 'active', 'm3-candidate-1', 'organization', 'moderate'),
    ('patient.profile.manage', 'Manage patient profile', 'Append governed patient identity and lifecycle changes.', 'active', 'm3-candidate-1', 'organization', 'high'),
    ('patient.registration.start', 'Start patient registration', 'Start a governed patient registration run.', 'active', 'm3-candidate-1', 'organization', 'high'),
    ('patient.registration.manage', 'Manage patient registration', 'Manage an authorized unexpired patient registration run.', 'active', 'm3-candidate-1', 'organization', 'high'),
    ('patient.registration.submit', 'Submit patient registration', 'Complete a validated duplicate-dispositioned registration.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.duplicate.search', 'Search patient duplicates', 'Run a bounded organization-scoped patient duplicate search.', 'active', 'm3-candidate-1', 'organization', 'high'),
    ('patient.duplicate.review', 'Review patient duplicates', 'Claim and disposition an authorized duplicate candidate.', 'active', 'm3-candidate-1', 'organization', 'high'),
    ('patient.merge.request', 'Request patient merge', 'Request an exact revision-bound patient merge.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.merge.decide', 'Decide patient merge', 'Independently decide an exact patient merge request.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.merge.execute', 'Execute patient merge', 'Execute an approved, unexpired, exact patient merge decision.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.contact.read', 'Read patient contact', 'Read masked patient contact and address projections.', 'active', 'm3-candidate-1', 'organization', 'moderate'),
    ('patient.contact.manage', 'Manage patient contact', 'Manage patient contacts and addresses without inferring consent.', 'active', 'm3-candidate-1', 'organization', 'high'),
    ('patient.preference.manage', 'Manage patient preferences', 'Manage communication preferences independently from consent.', 'active', 'm3-candidate-1', 'organization', 'high'),
    ('patient.identifier.manage', 'Manage patient identifiers', 'Manage governed versioned patient identifiers.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.proxy.read', 'Read patient proxy summary', 'Read relationship and authority state without inferring authority.', 'active', 'm3-candidate-1', 'organization', 'high'),
    ('patient.proxy.request', 'Request patient proxy authority', 'Request a scoped patient authority grant.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.proxy.decide', 'Decide patient proxy authority', 'Independently decide a policy-supported authority request.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.proxy.revoke', 'Revoke patient proxy authority', 'Prospectively revoke an active patient authority grant.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.consent.read', 'Read patient consent', 'Read purpose-bound patient consent summaries.', 'active', 'm3-candidate-1', 'organization', 'high'),
    ('patient.consent.manage', 'Manage patient consent', 'Activate, withdraw or supersede a policy-supported directive.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.privacy.read', 'Read patient privacy restrictions', 'Read policy-safe patient privacy restriction summaries.', 'active', 'm3-candidate-1', 'organization', 'high'),
    ('patient.privacy.manage', 'Manage patient privacy restrictions', 'Decide and lifecycle a scoped privacy restriction.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.safety_flag.read', 'Read patient safety flags', 'Read approved minimum-necessary safety flag projections.', 'active', 'm3-candidate-1', 'organization', 'high'),
    ('patient.safety_flag.propose', 'Propose patient safety flag', 'Propose a concise catalogue-backed patient safety flag.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.safety_flag.verify', 'Verify patient safety flag', 'Independently verify a patient safety flag.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.safety_flag.acknowledge', 'Acknowledge patient safety flag', 'Record workflow-specific acknowledgement evidence.', 'active', 'm3-candidate-1', 'organization', 'high'),
    ('patient.safety_flag.resolve', 'Resolve patient safety flag', 'Resolve, supersede or enter a safety flag in error.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.timeline.read', 'Read patient timeline', 'Read the allow-listed patient identity evidence timeline.', 'active', 'm3-candidate-1', 'organization', 'high'),
    ('patient.timeline.detail', 'Read patient timeline detail', 'Read purpose-bound restricted patient evidence detail.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.export.request', 'Request patient export', 'Request a bounded purpose-bound patient export.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.export.decide', 'Decide patient export', 'Independently authorize a restricted patient export.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.export.access', 'Access patient export', 'Access an authorized non-expired patient export artifact.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.portal_link.request', 'Request patient portal link', 'Request proofed self or proxy portal linkage.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.portal_link.decide', 'Decide patient portal link', 'Independently decide a proofed portal linkage request.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.portal_link.revoke', 'Revoke patient portal link', 'Revoke a patient portal link and invalidate dependent access.', 'active', 'm3-candidate-1', 'organization', 'critical'),
    ('patient.portal_link.recover', 'Recover patient portal link', 'Recover or relink a patient portal account after proofing.', 'active', 'm3-candidate-1', 'organization', 'critical');

-- Owners can exercise the accepted catalogue. More restrictive role templates are
-- intentionally additive and may be granted later without weakening this release.
INSERT INTO authorization_role_permissions (role_key, permission_key)
SELECT roles.role_key, permissions.permission_key
FROM (VALUES ('organization_owner'), ('local_bootstrap')) AS roles(role_key)
CROSS JOIN authorization_permissions permissions
WHERE permissions.registry_version = 'm3-candidate-1';

INSERT INTO authorization_role_permissions (role_key, permission_key)
SELECT 'organization_administrator', permission_key
FROM authorization_permissions
WHERE registry_version = 'm3-candidate-1'
  AND permission_key = ANY (ARRAY[
      'patient.dashboard.read','patient.directory.read','patient.profile.read',
      'patient.profile.manage','patient.registration.start','patient.registration.manage',
      'patient.registration.submit','patient.duplicate.search','patient.duplicate.review',
      'patient.merge.request','patient.contact.read','patient.contact.manage',
      'patient.preference.manage','patient.identifier.manage','patient.proxy.read',
      'patient.proxy.request','patient.consent.read','patient.privacy.read',
      'patient.safety_flag.read','patient.safety_flag.propose',
      'patient.safety_flag.acknowledge','patient.timeline.read'
  ]);

INSERT INTO authorization_role_permissions (role_key, permission_key)
VALUES
    ('auditor', 'patient.timeline.read'),
    ('auditor', 'patient.timeline.detail'),
    ('auditor', 'patient.export.request'),
    ('auditor', 'patient.export.access'),
    ('export_approver', 'patient.export.decide'),
    ('practitioner', 'patient.profile.read'),
    ('practitioner', 'patient.contact.read'),
    ('practitioner', 'patient.safety_flag.read'),
    ('practitioner', 'patient.safety_flag.propose'),
    ('practitioner', 'patient.safety_flag.verify'),
    ('practitioner', 'patient.safety_flag.acknowledge'),
    ('practitioner', 'patient.safety_flag.resolve');

INSERT INTO authorization_operations
    (operation_key, permission_key, display_name, description, mutation, denial_mode,
     reason_required, recent_authentication_required,
     recent_authentication_max_age_seconds, maximum_future_skew_seconds,
     maker_checker_required, status, registry_version, mfa_required)
SELECT permission_key,
       permission_key,
       display_name,
       description,
       NOT (permission_key LIKE '%.read'
            OR permission_key IN ('patient.duplicate.search','patient.timeline.detail','patient.export.access')),
       CASE WHEN risk_class = 'critical' AND permission_key NOT LIKE '%.read'
            THEN 'explicit' ELSE 'hidden' END,
       NOT (permission_key LIKE '%.read'
            OR permission_key IN ('patient.duplicate.search','patient.safety_flag.acknowledge'))
            OR permission_key IN ('patient.timeline.detail','patient.export.access'),
       permission_key = ANY (ARRAY[
           'patient.timeline.detail','patient.identifier.manage','patient.proxy.request',
           'patient.proxy.decide','patient.proxy.revoke','patient.consent.manage',
           'patient.privacy.manage','patient.safety_flag.verify',
           'patient.merge.decide','patient.merge.execute','patient.export.request',
           'patient.export.decide','patient.export.access','patient.portal_link.request',
           'patient.portal_link.decide','patient.portal_link.revoke','patient.portal_link.recover'
       ]),
       CASE
           WHEN permission_key = ANY (ARRAY[
               'patient.merge.decide','patient.merge.execute','patient.export.request',
               'patient.export.decide','patient.export.access','patient.portal_link.recover'
           ]) THEN 300
           WHEN permission_key = ANY (ARRAY[
               'patient.timeline.detail','patient.identifier.manage','patient.proxy.request',
               'patient.proxy.decide','patient.proxy.revoke','patient.consent.manage',
               'patient.privacy.manage','patient.safety_flag.verify',
               'patient.portal_link.request','patient.portal_link.decide','patient.portal_link.revoke'
           ]) THEN 600
           ELSE NULL
       END,
       CASE WHEN permission_key = ANY (ARRAY[
           'patient.timeline.detail','patient.identifier.manage','patient.proxy.request',
           'patient.proxy.decide','patient.proxy.revoke','patient.consent.manage',
           'patient.privacy.manage','patient.safety_flag.verify','patient.merge.decide',
           'patient.merge.execute','patient.export.request','patient.export.decide',
           'patient.export.access','patient.portal_link.request','patient.portal_link.decide',
           'patient.portal_link.revoke','patient.portal_link.recover'
       ]) THEN 5 ELSE NULL END,
       false,
       'active',
       'm3-candidate-1',
       permission_key = ANY (ARRAY[
           'patient.timeline.detail','patient.identifier.manage','patient.proxy.request',
           'patient.proxy.decide','patient.proxy.revoke','patient.consent.manage',
           'patient.privacy.manage','patient.safety_flag.verify','patient.merge.decide',
           'patient.merge.execute','patient.export.request','patient.export.decide',
           'patient.export.access','patient.portal_link.request','patient.portal_link.decide',
           'patient.portal_link.revoke','patient.portal_link.recover'
       ])
FROM authorization_permissions
WHERE registry_version = 'm3-candidate-1';

REVOKE ALL ON authorization_registry_releases FROM PUBLIC;
GRANT SELECT ON authorization_registry_releases TO "${applicationRole}";
