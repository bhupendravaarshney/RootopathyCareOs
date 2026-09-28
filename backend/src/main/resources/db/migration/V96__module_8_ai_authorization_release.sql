-- Module 8 repository authorization release under the standing implementation direction.
-- AI provider, model/prompt content, consent policy and production clinical use remain separate.
INSERT INTO authorization_registry_releases
    (registry_version,approval_record_id,approval_package_sha256,
     authorization_artifact_sha256,approval_evidence_sha256,
     approved_by,approved_at,status,module_key)
VALUES
    ('m8-standing-direction-v1','PROJECT-STANDING-DIRECTION-20260926-M8',
     'b5061b6f89edc87bbfed2184669d8e0889eed147f9e5a434c521be9ae3ef8db1',
     '571abcbb30bdd7e1963ceea227219130f901655101f1d16b61ca61a5ea584671',
     '18c7d7ac615578072cff9a01ccfb8fd5061c8df66019282dab956cf9e012de61',
     'bhupendra, developer','2026-09-28T09:30:00Z','active','M8');

INSERT INTO authorization_permissions
    (permission_key,display_name,description,status,registry_version,scope,risk_class)
VALUES
    ('ai.read','Read AI sessions','Read minimum-necessary AI session, provenance, safety and review projections.','active','m8-standing-direction-v1','organization','critical'),
    ('ai.session.launch','Launch AI session','Create a purpose-bound draft AI session without invoking a provider.','active','m8-standing-direction-v1','organization','critical'),
    ('ai.consent.record','Record AI purpose and consent','Append explicit purpose, consent/legal-basis and minimization evidence.','active','m8-standing-direction-v1','organization','critical'),
    ('ai.input.select','Select AI input','Create an immutable minimum-necessary input manifest.','active','m8-standing-direction-v1','organization','critical'),
    ('ai.process.request','Request AI processing','Create a versioned AI processing contract and retain provider outcome evidence.','active','m8-standing-direction-v1','organization','critical'),
    ('ai.output.edit','Edit AI draft','Append a clinician-authored version of a draft AI output.','active','m8-standing-direction-v1','organization','critical'),
    ('ai.safety.review','Review AI safety flag','Acknowledge or resolve visible AI safety and uncertainty evidence.','active','m8-standing-direction-v1','organization','critical'),
    ('ai.review.decide','Decide AI output','Explicitly accept or reject one exact draft output version.','active','m8-standing-direction-v1','organization','critical'),
    ('ai.session.cancel','Cancel AI session','Cancel a non-terminal AI session without deleting its evidence.','active','m8-standing-direction-v1','organization','high');

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT 'local_bootstrap',permission_key
FROM authorization_permissions WHERE registry_version='m8-standing-direction-v1';

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT 'practitioner',permission_key
FROM authorization_permissions WHERE registry_version='m8-standing-direction-v1';

INSERT INTO authorization_operations
    (operation_key,permission_key,display_name,description,mutation,denial_mode,
     reason_required,recent_authentication_required,
     recent_authentication_max_age_seconds,maximum_future_skew_seconds,
     maker_checker_required,status,registry_version,mfa_required)
SELECT permission_key,permission_key,display_name,description,
       permission_key<>'ai.read','explicit',permission_key<>'ai.read',
       permission_key='ai.review.decide',
       CASE WHEN permission_key='ai.review.decide' THEN 600 ELSE NULL END,
       CASE WHEN permission_key='ai.review.decide' THEN 5 ELSE NULL END,
       false,'active','m8-standing-direction-v1',permission_key='ai.review.decide'
FROM authorization_permissions
WHERE registry_version='m8-standing-direction-v1';
