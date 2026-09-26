-- Module 4 repository authorization release under the user's standing
-- implementation direction. This release does not represent production policy
-- or target-environment acceptance.
INSERT INTO authorization_registry_releases
    (registry_version,approval_record_id,approval_package_sha256,
     authorization_artifact_sha256,approval_evidence_sha256,
     approved_by,approved_at,status,module_key)
VALUES
    ('m4-standing-direction-v1','M4-STANDING-DIRECTION-20260926-01',
     'dc5dd9b8b645b02313a41daf688ec581c13568f3a09b71f144e451171e0ffa01',
     'dc5dd9b8b645b02313a41daf688ec581c13568f3a09b71f144e451171e0ffa01',
     '98a88d9a50be46cb290d31283e0ab3007689ae7f18748b30205a1c67168c1552',
     'bhupendra, developer','2026-09-26T14:00:00Z','active','M4');

INSERT INTO authorization_permissions
    (permission_key,display_name,description,status,registry_version,scope,risk_class)
VALUES
    ('appointment.dashboard.read','Read scheduling dashboard','Read minimum-necessary scheduling metrics and work queues.','active','m4-standing-direction-v1','organization','low'),
    ('appointment.directory.read','Read appointment directory','Read bounded organization appointment projections.','active','m4-standing-direction-v1','organization','moderate'),
    ('appointment.schedule.read','Read schedules and slots','Read organization schedules, slots and availability projections.','active','m4-standing-direction-v1','organization','moderate'),
    ('appointment.schedule.manage','Manage schedules and slots','Create and lifecycle internal schedules and slots.','active','m4-standing-direction-v1','organization','high'),
    ('appointment.request.manage','Manage appointment requests','Create and update a staff-authorized appointment request.','active','m4-standing-direction-v1','organization','high'),
    ('appointment.slot.hold','Hold appointment slot','Atomically acquire a short-lived appointment slot hold.','active','m4-standing-direction-v1','organization','high'),
    ('appointment.book','Confirm appointment','Confirm an eligible request and consume one held slot atomically.','active','m4-standing-direction-v1','organization','critical'),
    ('appointment.reschedule','Reschedule appointment','Atomically move an appointment while preserving timeline evidence.','active','m4-standing-direction-v1','organization','critical'),
    ('appointment.cancel','Cancel appointment','Cancel an appointment with versioned policy evidence.','active','m4-standing-direction-v1','organization','high'),
    ('appointment.no_show','Record appointment no-show','Record a post-start no-show decision with policy evidence.','active','m4-standing-direction-v1','organization','high'),
    ('appointment.waitlist.read','Read appointment waitlist','Read bounded waitlist projections.','active','m4-standing-direction-v1','organization','moderate'),
    ('appointment.waitlist.manage','Manage appointment waitlist','Create or withdraw an internal waitlist request.','active','m4-standing-direction-v1','organization','high'),
    ('appointment.payment.read','Read payment requirement','Read non-financial appointment payment-requirement state.','active','m4-standing-direction-v1','organization','high'),
    ('appointment.timeline.read','Read appointment timeline','Read allow-listed appointment lifecycle evidence.','active','m4-standing-direction-v1','organization','high');

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT roles.role_key,permissions.permission_key
FROM (VALUES ('organization_owner'),('local_bootstrap')) roles(role_key)
CROSS JOIN authorization_permissions permissions
WHERE permissions.registry_version='m4-standing-direction-v1';

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT roles.role_key,permissions.permission_key
FROM (VALUES ('organization_administrator'),('facility_administrator'),
             ('clinical_support_staff')) roles(role_key)
CROSS JOIN authorization_permissions permissions
WHERE permissions.registry_version='m4-standing-direction-v1'
  AND permissions.permission_key <> 'appointment.schedule.manage';

INSERT INTO authorization_role_permissions (role_key,permission_key)
VALUES
    ('facility_administrator','appointment.schedule.manage'),
    ('organization_administrator','appointment.schedule.manage'),
    ('practitioner','appointment.schedule.read'),
    ('practitioner','appointment.directory.read'),
    ('practitioner','appointment.timeline.read');

INSERT INTO authorization_operations
    (operation_key,permission_key,display_name,description,mutation,denial_mode,
     reason_required,recent_authentication_required,
     recent_authentication_max_age_seconds,maximum_future_skew_seconds,
     maker_checker_required,status,registry_version,mfa_required)
SELECT permission_key,permission_key,display_name,description,
       permission_key NOT LIKE '%.read',
       CASE WHEN risk_class='critical' THEN 'explicit' ELSE 'hidden' END,
       permission_key = ANY(ARRAY[
           'appointment.schedule.manage','appointment.book','appointment.reschedule',
           'appointment.cancel','appointment.no_show','appointment.waitlist.manage'
       ]),
       permission_key = ANY(ARRAY['appointment.book','appointment.reschedule']),
       CASE WHEN permission_key = ANY(ARRAY['appointment.book','appointment.reschedule'])
            THEN 600 ELSE NULL END,
       CASE WHEN permission_key = ANY(ARRAY['appointment.book','appointment.reschedule'])
            THEN 5 ELSE NULL END,
       false,'active','m4-standing-direction-v1',false
FROM authorization_permissions
WHERE registry_version='m4-standing-direction-v1';

COMMENT ON TABLE authorization_registry_releases IS
    'Immutable checksum-bound authorization releases, including the Module 4 standing-direction repository release.';
