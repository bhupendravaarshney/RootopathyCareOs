-- Module 12 repository authorization release under the standing implementation direction.
-- Report policy, private artifact workers and production activation remain separate.
INSERT INTO authorization_registry_releases
    (registry_version,approval_record_id,approval_package_sha256,
     authorization_artifact_sha256,approval_evidence_sha256,
     approved_by,approved_at,status,module_key)
VALUES
    ('m12-standing-direction-v1','PROJECT-STANDING-DIRECTION-20260928-M12',
     '8c903b22734729243aa96906bd547947876758a7a906d5f9a75b9a15d41387da',
     'ed38a003aa234af6e3bd102c89ff9a71aa44423835cf067468785d5ec65d98d3',
     'c48376794891dd5f4551bdf619782230929d735dc289250bba0ba1cc612b6aac',
     'bhupendra, developer','2026-09-28T14:00:00Z','active','M12');

INSERT INTO authorization_permissions
    (permission_key,display_name,description,status,registry_version,scope,risk_class)
VALUES
    ('reporting.read','Read reporting','Read tenant-scoped minimum-necessary aggregate reporting projections.','active','m12-standing-direction-v1','organization','high'),
    ('reporting.run.create','Run report','Create one bounded immutable aggregate report snapshot.','active','m12-standing-direction-v1','organization','high'),
    ('reporting.schedule.create','Create report schedule','Create a purpose-bound report schedule definition.','active','m12-standing-direction-v1','organization','critical'),
    ('reporting.schedule.pause','Pause report schedule','Pause an active report schedule using an exact revision.','active','m12-standing-direction-v1','organization','critical'),
    ('reporting.schedule.resume','Resume report schedule','Resume a paused report schedule using an exact revision.','active','m12-standing-direction-v1','organization','critical'),
    ('reporting.schedule.cancel','Cancel report schedule','Permanently cancel a report schedule using an exact revision.','active','m12-standing-direction-v1','organization','critical'),
    ('reporting.export.create','Request report export','Request a bounded export of one exact completed report snapshot.','active','m12-standing-direction-v1','organization','critical'),
    ('reporting.export.complete','Complete report export','Record private artifact metadata from the authorized report export worker.','active','m12-standing-direction-v1','organization','critical');

INSERT INTO authorization_roles
    (role_key,display_name,description,status,registry_version,
     interactive,invitation_assignable,final_owner)
VALUES
    ('reporting_analyst','Reporting analyst','Runs fixed aggregate reports and manages governed schedules and exports.','active','m12-standing-direction-v1',true,true,false),
    ('governance_report_auditor','Governance report auditor','Reads aggregate governance reports and requests exact-run exports.','active','m12-standing-direction-v1',true,true,false),
    ('service_m12_export','Module 12 export worker','Completes only authorized private report-export artifacts.','active','m12-standing-direction-v1',false,false,false);

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT role_key,permission_key
FROM (VALUES ('organization_owner'),('local_bootstrap'),('reporting_analyst')) roles(role_key)
CROSS JOIN authorization_permissions permission
WHERE permission.registry_version='m12-standing-direction-v1'
  AND permission.permission_key<>'reporting.export.complete';

INSERT INTO authorization_role_permissions (role_key,permission_key)
VALUES
    ('governance_report_auditor','reporting.read'),
    ('governance_report_auditor','reporting.run.create'),
    ('governance_report_auditor','reporting.export.create'),
    ('service_m12_export','reporting.export.complete');

INSERT INTO authorization_operations
    (operation_key,permission_key,display_name,description,mutation,denial_mode,
     reason_required,recent_authentication_required,
     recent_authentication_max_age_seconds,maximum_future_skew_seconds,
     maker_checker_required,status,registry_version,mfa_required)
SELECT permission_key,permission_key,display_name,description,
       permission_key<>'reporting.read','explicit',permission_key<>'reporting.read',
       permission_key IN (
           'reporting.schedule.create','reporting.schedule.pause','reporting.schedule.resume',
           'reporting.schedule.cancel','reporting.export.create'),
       CASE WHEN permission_key IN (
           'reporting.schedule.create','reporting.schedule.pause','reporting.schedule.resume',
           'reporting.schedule.cancel','reporting.export.create') THEN 600 ELSE NULL END,
       CASE WHEN permission_key IN (
           'reporting.schedule.create','reporting.schedule.pause','reporting.schedule.resume',
           'reporting.schedule.cancel','reporting.export.create') THEN 5 ELSE NULL END,
       false,'active','m12-standing-direction-v1',
       permission_key IN (
           'reporting.schedule.create','reporting.schedule.pause','reporting.schedule.resume',
           'reporting.schedule.cancel','reporting.export.create')
FROM authorization_permissions
WHERE registry_version='m12-standing-direction-v1';
