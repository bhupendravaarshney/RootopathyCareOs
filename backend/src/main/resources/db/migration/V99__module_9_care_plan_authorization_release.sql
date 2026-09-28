-- Module 9 repository authorization release under the standing implementation direction.
-- Clinical catalogues, interaction policy and production care-plan activation remain separate.
INSERT INTO authorization_registry_releases
    (registry_version,approval_record_id,approval_package_sha256,
     authorization_artifact_sha256,approval_evidence_sha256,
     approved_by,approved_at,status,module_key)
VALUES
    ('m9-standing-direction-v1','PROJECT-STANDING-DIRECTION-20260926-M9',
     '3c0943dc96bf80ef93efead10b6942cc441a905347a3197fd9b458158891ab28',
     'f5f5bd163ab16986f001a881ff68e6cff2fc812f26068e4be7e9399778fb33be',
     'c70685db26aca6830ecf773bf64e8f19f70c318046ad021dc13c04efce4d7735',
     'bhupendra, developer','2026-09-28T10:30:00Z','active','M9');

INSERT INTO authorization_permissions
    (permission_key,display_name,description,status,registry_version,scope,risk_class)
VALUES
    ('care-plan.read','Read care plans','Read minimum-necessary care-plan, goal, intervention, consent, safety and version projections.','active','m9-standing-direction-v1','organization','critical'),
    ('care-plan.create','Create care plan','Create a patient and encounter-bound draft coordinated care plan.','active','m9-standing-direction-v1','organization','critical'),
    ('care-plan.priority.add','Add care-plan priority','Append a sourced problem or priority to a draft plan version.','active','m9-standing-direction-v1','organization','critical'),
    ('care-plan.goal.add','Add care-plan goal','Append a measurable clinical or patient-stated goal to a draft plan version.','active','m9-standing-direction-v1','organization','critical'),
    ('care-plan.intervention.add','Add care-plan intervention','Append an intervention with rationale, timing, stop criteria and monitoring.','active','m9-standing-direction-v1','organization','critical'),
    ('care-plan.assignment.manage','Assign care-plan owner and task','Append accountable intervention ownership and a clinical task.','active','m9-standing-direction-v1','organization','critical'),
    ('care-plan.consent.record','Record plan consent and preferences','Append patient consent and preference evidence to a draft plan version.','active','m9-standing-direction-v1','organization','critical'),
    ('care-plan.safety.review','Review plan interactions','Append an exact-version cross-modality interaction and safety review.','active','m9-standing-direction-v1','organization','critical'),
    ('care-plan.submit','Submit care plan','Freeze and submit an exact complete plan version for clinician review.','active','m9-standing-direction-v1','organization','critical'),
    ('care-plan.approve','Approve care plan','Approve an exact submitted plan version as the responsible clinician.','active','m9-standing-direction-v1','organization','critical'),
    ('care-plan.activate','Activate care plan','Activate an approved care plan without changing its clinical content.','active','m9-standing-direction-v1','organization','critical'),
    ('care-plan.amend','Amend care plan','Create a draft successor and immutable amendment lineage from an active plan.','active','m9-standing-direction-v1','organization','critical'),
    ('care-plan.close','Complete or cancel care plan','Close an active or draft care plan without deleting evidence.','active','m9-standing-direction-v1','organization','critical');

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT 'local_bootstrap',permission_key
FROM authorization_permissions WHERE registry_version='m9-standing-direction-v1';

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT 'practitioner',permission_key
FROM authorization_permissions WHERE registry_version='m9-standing-direction-v1';

INSERT INTO authorization_operations
    (operation_key,permission_key,display_name,description,mutation,denial_mode,
     reason_required,recent_authentication_required,
     recent_authentication_max_age_seconds,maximum_future_skew_seconds,
     maker_checker_required,status,registry_version,mfa_required)
SELECT permission_key,permission_key,display_name,description,
       permission_key<>'care-plan.read','explicit',permission_key<>'care-plan.read',
       permission_key='care-plan.approve',
       CASE WHEN permission_key='care-plan.approve' THEN 600 ELSE NULL END,
       CASE WHEN permission_key='care-plan.approve' THEN 5 ELSE NULL END,
       false,'active','m9-standing-direction-v1',permission_key='care-plan.approve'
FROM authorization_permissions
WHERE registry_version='m9-standing-direction-v1';
