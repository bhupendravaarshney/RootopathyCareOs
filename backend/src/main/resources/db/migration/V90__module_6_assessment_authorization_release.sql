-- Module 6 repository authorization release under the project standing
-- implementation direction. This is not protected-source or clinical-policy approval.
INSERT INTO authorization_registry_releases
    (registry_version,approval_record_id,approval_package_sha256,
     authorization_artifact_sha256,approval_evidence_sha256,
     approved_by,approved_at,status,module_key)
VALUES
    ('m6-standing-direction-v1','PROJECT-STANDING-DIRECTION-20260926-M6',
     '24df40ace5e1a3c00bc721bdcfea3d884c893511e205a79d940456ff8fc21dbb',
     '24df40ace5e1a3c00bc721bdcfea3d884c893511e205a79d940456ff8fc21dbb',
     'bc93a6d126d054c62bd1279b6695d7ae62c692e9a0b87facf477037f39814e54',
     'bhupendra, developer','2026-09-26T17:30:00Z','active','M6');

INSERT INTO authorization_permissions
    (permission_key,display_name,description,status,registry_version,scope,risk_class)
VALUES
    ('assessment.read','Read clinical assessment','Read minimum-necessary assessment, provenance and completion context.','active','m6-standing-direction-v1','organization','critical'),
    ('assessment.start','Start clinical assessment','Start a governed COS assessment for an active encounter.','active','m6-standing-direction-v1','organization','critical'),
    ('assessment.response.write','Version assessment response','Append a sourced, attributed assessment response version.','active','m6-standing-direction-v1','organization','critical'),
    ('assessment.measurement.write','Record assessment measurement','Append a purpose-bound measurement with source, method, unit, cadence, owner and threshold.','active','m6-standing-direction-v1','organization','critical'),
    ('assessment.red_flag.write','Manage assessment red flag','Raise, acknowledge or resolve a visible assessment red flag.','active','m6-standing-direction-v1','organization','critical'),
    ('assessment.review','Review clinical assessment','Bind completeness, source and uncertainty review to an exact revision.','active','m6-standing-direction-v1','organization','critical'),
    ('assessment.sign','Sign clinical assessment','Sign an exact reviewed assessment version as its eligible responsible clinician.','active','m6-standing-direction-v1','organization','critical'),
    ('assessment.amend','Amend signed assessment','Append an attributed correction linked to the signed assessment.','active','m6-standing-direction-v1','organization','critical'),
    ('assessment.lifecycle.manage','Manage assessment lifecycle','Return a review to draft or explicitly close, cancel or mark an assessment in error.','active','m6-standing-direction-v1','organization','critical');

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT 'local_bootstrap',permission_key
FROM authorization_permissions WHERE registry_version='m6-standing-direction-v1';

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT 'practitioner',permission_key
FROM authorization_permissions WHERE registry_version='m6-standing-direction-v1';

INSERT INTO authorization_operations
    (operation_key,permission_key,display_name,description,mutation,denial_mode,
     reason_required,recent_authentication_required,
     recent_authentication_max_age_seconds,maximum_future_skew_seconds,
     maker_checker_required,status,registry_version,mfa_required)
SELECT permission_key,permission_key,display_name,description,
       permission_key<>'assessment.read','explicit',
       permission_key=ANY(ARRAY[
           'assessment.start','assessment.red_flag.write','assessment.review',
           'assessment.sign','assessment.amend','assessment.lifecycle.manage'
       ]),
       permission_key=ANY(ARRAY['assessment.sign','assessment.amend']),
       CASE WHEN permission_key=ANY(ARRAY['assessment.sign','assessment.amend']) THEN 600 ELSE NULL END,
       CASE WHEN permission_key=ANY(ARRAY['assessment.sign','assessment.amend']) THEN 5 ELSE NULL END,
       false,'active','m6-standing-direction-v1',
       permission_key=ANY(ARRAY['assessment.sign','assessment.amend'])
FROM authorization_permissions
WHERE registry_version='m6-standing-direction-v1';
