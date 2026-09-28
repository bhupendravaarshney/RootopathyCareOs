-- Module 7 repository authorization release under the standing implementation direction.
-- Production providers, result policy, sharing and retention acceptance remain separate.
INSERT INTO authorization_registry_releases
    (registry_version,approval_record_id,approval_package_sha256,
     authorization_artifact_sha256,approval_evidence_sha256,
     approved_by,approved_at,status,module_key)
VALUES
    ('m7-standing-direction-v1','PROJECT-STANDING-DIRECTION-20260926-M7',
     '9ef737d397227959d17bc7309366aa1028c6f42bed2c8225961ef24c9f7e3182',
     '4e37d9b711c640751753344d455f233b0c659d4f392350043141511412972558',
     '2ce0504622c6dd734677b03b59f17fa2449105232a514c24b8fcc5935ad2fc3f',
     'bhupendra, developer','2026-09-28T08:00:00Z','active','M7');

INSERT INTO authorization_permissions
    (permission_key,display_name,description,status,registry_version,scope,risk_class)
VALUES
    ('document.read','Read documents and results','Read minimum-necessary document, scan, result and provenance projections.','active','m7-standing-direction-v1','organization','critical'),
    ('document.upload','Upload document version','Quarantine and bind an immutable patient-document version.','active','m7-standing-direction-v1','organization','critical'),
    ('document.classify','Classify document','Append attributed document classification and metadata evidence.','active','m7-standing-direction-v1','organization','critical'),
    ('document.scan.bind','Scan document','Bind platform scan and clean-promotion evidence to an exact document version.','active','m7-standing-direction-v1','organization','critical'),
    ('document.result.write','Record diagnostic result','Append a provenance-preserving diagnostic report and result values.','active','m7-standing-direction-v1','organization','critical'),
    ('document.result.review','Review diagnostic result','Acknowledge or resolve an exact result flag revision.','active','m7-standing-direction-v1','organization','critical'),
    ('document.result.escalate','Escalate diagnostic result','Append owned escalation evidence for a critical or abnormal result.','active','m7-standing-direction-v1','organization','critical'),
    ('document.access','Access clean document','Create purpose-bound short-lived access to one clean promoted version.','active','m7-standing-direction-v1','organization','critical'),
    ('document.intent.create','Create export or share intent','Record an export/share intent without claiming delivery.','active','m7-standing-direction-v1','organization','high');

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT 'local_bootstrap',permission_key
FROM authorization_permissions WHERE registry_version='m7-standing-direction-v1';

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT 'practitioner',permission_key
FROM authorization_permissions WHERE registry_version='m7-standing-direction-v1';

INSERT INTO authorization_operations
    (operation_key,permission_key,display_name,description,mutation,denial_mode,
     reason_required,recent_authentication_required,
     recent_authentication_max_age_seconds,maximum_future_skew_seconds,
     maker_checker_required,status,registry_version,mfa_required)
SELECT permission_key,permission_key,display_name,description,
       permission_key<>'document.read','explicit',
       permission_key=ANY(ARRAY[
           'document.upload','document.classify','document.result.write',
           'document.result.review','document.result.escalate','document.access',
           'document.intent.create'
       ]),
       permission_key=ANY(ARRAY['document.access','document.intent.create']),
       CASE WHEN permission_key=ANY(ARRAY['document.access','document.intent.create']) THEN 600 ELSE NULL END,
       CASE WHEN permission_key=ANY(ARRAY['document.access','document.intent.create']) THEN 5 ELSE NULL END,
       false,'active','m7-standing-direction-v1',
       permission_key='document.intent.create'
FROM authorization_permissions
WHERE registry_version='m7-standing-direction-v1';
