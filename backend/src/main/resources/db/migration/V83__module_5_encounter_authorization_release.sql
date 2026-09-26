-- Module 5 repository authorization release under the project standing
-- implementation direction. This is not production clinical-policy approval.
INSERT INTO authorization_registry_releases
    (registry_version,approval_record_id,approval_package_sha256,
     authorization_artifact_sha256,approval_evidence_sha256,
     approved_by,approved_at,status,module_key)
VALUES
    ('m5-standing-direction-v1','PROJECT-STANDING-DIRECTION-20260926-01',
     '24df40ace5e1a3c00bc721bdcfea3d884c893511e205a79d940456ff8fc21dbb',
     '24df40ace5e1a3c00bc721bdcfea3d884c893511e205a79d940456ff8fc21dbb',
     'bc93a6d126d054c62bd1279b6695d7ae62c692e9a0b87facf477037f39814e54',
     'bhupendra, developer','2026-09-26T16:30:00Z','active','M5');

INSERT INTO authorization_permissions
    (permission_key,display_name,description,status,registry_version,scope,risk_class)
VALUES
    ('encounter.dashboard.read','Read encounter dashboard','Read minimum-necessary encounter work metrics.','active','m5-standing-direction-v1','organization','moderate'),
    ('encounter.context.read','Read encounter context','Read patient, appointment and participant context.','active','m5-standing-direction-v1','organization','high'),
    ('encounter.clinical.read','Read encounter clinical record','Read concerns, problems, diagnoses, orders, tasks and note metadata.','active','m5-standing-direction-v1','organization','critical'),
    ('encounter.history.read','Read encounter history','Read allow-listed encounter lifecycle evidence.','active','m5-standing-direction-v1','organization','critical'),
    ('encounter.open','Open encounter','Create an episode/encounter from exact patient and care context.','active','m5-standing-direction-v1','organization','high'),
    ('encounter.lifecycle.manage','Manage encounter lifecycle','Apply governed encounter lifecycle transitions.','active','m5-standing-direction-v1','organization','critical'),
    ('encounter.participant.manage','Manage encounter participants','Add or remove snapshotted encounter participants.','active','m5-standing-direction-v1','organization','critical'),
    ('encounter.concern.write','Record presenting concerns','Append governed presenting-concern evidence.','active','m5-standing-direction-v1','organization','critical'),
    ('encounter.problem.write','Record problems and diagnoses','Append or lifecycle clinical problem/diagnosis evidence.','active','m5-standing-direction-v1','organization','critical'),
    ('encounter.order.manage','Manage encounter orders','Create and lifecycle internal clinical orders.','active','m5-standing-direction-v1','organization','critical'),
    ('encounter.task.manage','Manage clinical tasks','Create and progress attributed clinical tasks.','active','m5-standing-direction-v1','organization','critical'),
    ('encounter.note.write','Write encounter notes','Create append-only draft note versions.','active','m5-standing-direction-v1','organization','critical'),
    ('encounter.note.sign','Sign encounter notes','Sign an exact note version as an eligible practitioner participant.','active','m5-standing-direction-v1','organization','critical'),
    ('encounter.amend','Amend signed encounter notes','Append a signed correction linked to an exact signed version.','active','m5-standing-direction-v1','organization','critical'),
    ('encounter.red_flag.acknowledge','Acknowledge red-flag escalation','Acknowledge or resolve a visible red-flag escalation with reason.','active','m5-standing-direction-v1','organization','critical');

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT 'local_bootstrap',permission_key
FROM authorization_permissions WHERE registry_version='m5-standing-direction-v1';

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT 'practitioner',permission_key
FROM authorization_permissions WHERE registry_version='m5-standing-direction-v1';

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT roles.role_key,permissions.permission_key
FROM (VALUES ('organization_owner'),('organization_administrator'),
             ('facility_administrator'),('clinical_support_staff')) roles(role_key)
CROSS JOIN authorization_permissions permissions
WHERE permissions.registry_version='m5-standing-direction-v1'
  AND permissions.permission_key=ANY(ARRAY[
      'encounter.dashboard.read','encounter.context.read','encounter.open'
  ]);

INSERT INTO authorization_operations
    (operation_key,permission_key,display_name,description,mutation,denial_mode,
     reason_required,recent_authentication_required,
     recent_authentication_max_age_seconds,maximum_future_skew_seconds,
     maker_checker_required,status,registry_version,mfa_required)
SELECT permission_key,permission_key,display_name,description,
       permission_key NOT LIKE '%.read',
       CASE WHEN risk_class='critical' THEN 'explicit' ELSE 'hidden' END,
       permission_key=ANY(ARRAY[
           'encounter.open','encounter.lifecycle.manage','encounter.participant.manage',
           'encounter.problem.write','encounter.order.manage','encounter.task.manage',
           'encounter.note.sign','encounter.amend','encounter.red_flag.acknowledge'
       ]),
       permission_key=ANY(ARRAY['encounter.note.sign','encounter.amend']),
       CASE WHEN permission_key=ANY(ARRAY['encounter.note.sign','encounter.amend']) THEN 600 ELSE NULL END,
       CASE WHEN permission_key=ANY(ARRAY['encounter.note.sign','encounter.amend']) THEN 5 ELSE NULL END,
       false,'active','m5-standing-direction-v1',
       permission_key=ANY(ARRAY['encounter.note.sign','encounter.amend'])
FROM authorization_permissions
WHERE registry_version='m5-standing-direction-v1';
