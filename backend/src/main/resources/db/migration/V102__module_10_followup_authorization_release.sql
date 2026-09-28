-- Module 10 repository authorization release under the standing implementation direction.
-- Outcome catalogues, thresholds, escalation policy and production activation remain separate.
INSERT INTO authorization_registry_releases
    (registry_version,approval_record_id,approval_package_sha256,
     authorization_artifact_sha256,approval_evidence_sha256,
     approved_by,approved_at,status,module_key)
VALUES
    ('m10-standing-direction-v1','PROJECT-STANDING-DIRECTION-20260926-M10',
     '735f471293abbb50b26ac55a9abc9c50fa7923f5c858575c1d07c7ffef2e8960',
     '02b30d581dd3d4862c64985222322046ca8dbb7dcc5d8700e610aa38b35ed9b2',
     'd64637bb17c658d4f8ee627cf798688f22ab0956b30aaf81ca6114c5baec7921',
     'bhupendra, developer','2026-09-28T11:00:00Z','active','M10');

INSERT INTO authorization_permissions
    (permission_key,display_name,description,status,registry_version,scope,risk_class)
VALUES
    ('followup.read','Read follow-up and outcomes','Read minimum-necessary follow-up, measurement, escalation and interpretation projections.','active','m10-standing-direction-v1','organization','critical'),
    ('followup.plan.create','Create follow-up plan','Create a draft follow-up plan bound to an exact active care-plan version.','active','m10-standing-direction-v1','organization','critical'),
    ('followup.domain.add','Add outcome domain','Append a version-bound outcome definition and baseline requirement.','active','m10-standing-direction-v1','organization','critical'),
    ('followup.rule.add','Add escalation rule','Append an exact outcome threshold with severity, owner and response target.','active','m10-standing-direction-v1','organization','critical'),
    ('followup.schedule.manage','Manage follow-up schedule','Append or complete an owned follow-up event.','active','m10-standing-direction-v1','organization','critical'),
    ('followup.measure.record','Record outcome measure','Append an attributed outcome measurement and evaluate thresholds atomically.','active','m10-standing-direction-v1','organization','critical'),
    ('followup.escalation.acknowledge','Acknowledge outcome escalation','Acknowledge an exact open threshold-breach event and its owned task.','active','m10-standing-direction-v1','organization','critical'),
    ('followup.escalation.resolve','Resolve outcome escalation','Resolve an acknowledged threshold-breach event and its owned task.','active','m10-standing-direction-v1','organization','critical'),
    ('followup.interpretation.record','Record outcome interpretation','Append an attributed interpretation linked to exact measurement evidence.','active','m10-standing-direction-v1','organization','critical'),
    ('followup.submit','Submit follow-up plan','Freeze and submit an exact complete follow-up plan for review.','active','m10-standing-direction-v1','organization','critical'),
    ('followup.confirm','Confirm follow-up plan','Confirm an exact submitted follow-up plan as the responsible clinician.','active','m10-standing-direction-v1','organization','critical'),
    ('followup.close','Complete or cancel follow-up plan','Close an active or draft follow-up plan without deleting evidence.','active','m10-standing-direction-v1','organization','critical');

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT 'local_bootstrap',permission_key
FROM authorization_permissions WHERE registry_version='m10-standing-direction-v1';

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT 'practitioner',permission_key
FROM authorization_permissions WHERE registry_version='m10-standing-direction-v1';

INSERT INTO authorization_operations
    (operation_key,permission_key,display_name,description,mutation,denial_mode,
     reason_required,recent_authentication_required,
     recent_authentication_max_age_seconds,maximum_future_skew_seconds,
     maker_checker_required,status,registry_version,mfa_required)
SELECT permission_key,permission_key,display_name,description,
       permission_key<>'followup.read','explicit',permission_key<>'followup.read',
       permission_key='followup.confirm',
       CASE WHEN permission_key='followup.confirm' THEN 600 ELSE NULL END,
       CASE WHEN permission_key='followup.confirm' THEN 5 ELSE NULL END,
       false,'active','m10-standing-direction-v1',permission_key='followup.confirm'
FROM authorization_permissions
WHERE registry_version='m10-standing-direction-v1';
