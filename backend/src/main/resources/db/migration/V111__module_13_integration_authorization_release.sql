-- Module 13 repository authorization release under the standing implementation direction.
-- Partner contracts, credentials, profiles, transports and production activation remain separate.
INSERT INTO authorization_registry_releases
    (registry_version,approval_record_id,approval_package_sha256,
     authorization_artifact_sha256,approval_evidence_sha256,
     approved_by,approved_at,status,module_key)
VALUES
    ('m13-standing-direction-v1','PROJECT-STANDING-DIRECTION-20260928-M13',
     '3d5e45abf486b188270ff342b377920c8064210546bcdb7d1966e4f59d237b84',
     'aeb73181b0e85bbaee32e81ae544c411d47f94cead50b6d39f5b2dd633380cee',
     '7b17841c6b89567c6d635178a19f39ef964160b8c333b5589cb492b43821f8d2',
     'bhupendra, developer','2026-09-28T16:00:00Z','active','M13');

INSERT INTO authorization_permissions
    (permission_key,display_name,description,status,registry_version,scope,risk_class)
VALUES
    ('integration.read','Read integrations','Read tenant-scoped integration configuration and payload-free delivery evidence.','active','m13-standing-direction-v1','organization','high'),
    ('integration.connection.create','Create integration connection','Create a secret-free versioned integration connection definition.','active','m13-standing-direction-v1','organization','high'),
    ('integration.connection.validate','Validate integration connection','Mark an exact connection revision configuration-complete without enabling transport.','active','m13-standing-direction-v1','organization','critical'),
    ('integration.connection.suspend','Suspend integration connection','Suspend an exact validated connection revision.','active','m13-standing-direction-v1','organization','critical'),
    ('integration.connection.retire','Retire integration connection','Permanently retire an exact connection revision.','active','m13-standing-direction-v1','organization','critical'),
    ('integration.mapping.create','Create integration mapping','Create an exact versioned FHIR, terminology or partner mapping.','active','m13-standing-direction-v1','organization','high'),
    ('integration.mapping.activate','Activate integration mapping','Activate an exact immutable mapping/profile version.','active','m13-standing-direction-v1','organization','critical'),
    ('integration.mapping.retire','Retire integration mapping','Retire an exact active mapping/profile version.','active','m13-standing-direction-v1','organization','critical'),
    ('integration.api_client.create','Register API client','Register secret-free mobile or API client metadata.','active','m13-standing-direction-v1','organization','critical'),
    ('integration.api_client.revoke','Revoke API client','Permanently revoke an exact API client registration.','active','m13-standing-direction-v1','organization','critical'),
    ('integration.replay.authorize','Authorize integration replay','Authorize one bounded successor delivery for exact dead-letter evidence.','active','m13-standing-direction-v1','organization','critical'),
    ('integration.webhook.receive','Receive verified webhook','Record digest-only evidence for a signature-verified replay-safe inbound webhook.','active','m13-standing-direction-v1','organization','critical'),
    ('integration.delivery.create','Create outbound delivery','Bind one versioned outbox event to an exact validated destination.','active','m13-standing-direction-v1','organization','critical'),
    ('integration.delivery.attempt','Record delivery attempt','Record one payload-free outbound delivery attempt and bounded retry outcome.','active','m13-standing-direction-v1','organization','critical'),
    ('integration.delivery.replay','Execute authorized replay','Create one exact-lineage successor delivery from an unexpired replay authorization.','active','m13-standing-direction-v1','organization','critical'),
    ('integration.fhir.exchange.record','Record FHIR exchange','Record profile-validation and provenance evidence for an exact FHIR exchange.','active','m13-standing-direction-v1','organization','critical');

INSERT INTO authorization_roles
    (role_key,display_name,description,status,registry_version,
     interactive,invitation_assignable,final_owner)
VALUES
    ('integration_administrator','Integration administrator','Manages secret-free connection, mapping, client and replay definitions.','active','m13-standing-direction-v1',true,true,false),
    ('integration_auditor','Integration auditor','Reads payload-free integration delivery, validation and replay evidence.','active','m13-standing-direction-v1',true,true,false),
    ('service_m13_inbound','Module 13 inbound service','Records only verified inbound webhook and FHIR exchange evidence.','active','m13-standing-direction-v1',false,false,false),
    ('service_m13_delivery','Module 13 delivery service','Creates, attempts and safely replays exact outbound deliveries.','active','m13-standing-direction-v1',false,false,false);

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT role_key,permission_key
FROM (VALUES ('organization_owner'),('local_bootstrap'),('integration_administrator')) roles(role_key)
CROSS JOIN authorization_permissions permission
WHERE permission.registry_version='m13-standing-direction-v1'
  AND permission.permission_key NOT IN (
      'integration.webhook.receive','integration.delivery.create',
      'integration.delivery.attempt','integration.delivery.replay',
      'integration.fhir.exchange.record');

INSERT INTO authorization_role_permissions (role_key,permission_key)
VALUES
    ('integration_auditor','integration.read'),
    ('service_m13_inbound','integration.webhook.receive'),
    ('service_m13_inbound','integration.fhir.exchange.record'),
    ('service_m13_delivery','integration.delivery.create'),
    ('service_m13_delivery','integration.delivery.attempt'),
    ('service_m13_delivery','integration.delivery.replay'),
    ('service_m13_delivery','integration.fhir.exchange.record');

INSERT INTO authorization_operations
    (operation_key,permission_key,display_name,description,mutation,denial_mode,
     reason_required,recent_authentication_required,
     recent_authentication_max_age_seconds,maximum_future_skew_seconds,
     maker_checker_required,status,registry_version,mfa_required)
SELECT permission_key,permission_key,display_name,description,
       permission_key<>'integration.read','explicit',permission_key<>'integration.read',
       permission_key IN (
           'integration.connection.validate','integration.connection.suspend',
           'integration.connection.retire','integration.mapping.activate',
           'integration.mapping.retire','integration.api_client.create',
           'integration.api_client.revoke','integration.replay.authorize'),
       CASE WHEN permission_key IN (
           'integration.connection.validate','integration.connection.suspend',
           'integration.connection.retire','integration.mapping.activate',
           'integration.mapping.retire','integration.api_client.create',
           'integration.api_client.revoke','integration.replay.authorize') THEN 600 ELSE NULL END,
       CASE WHEN permission_key IN (
           'integration.connection.validate','integration.connection.suspend',
           'integration.connection.retire','integration.mapping.activate',
           'integration.mapping.retire','integration.api_client.create',
           'integration.api_client.revoke','integration.replay.authorize') THEN 5 ELSE NULL END,
       false,'active','m13-standing-direction-v1',
       permission_key IN (
           'integration.connection.validate','integration.connection.suspend',
           'integration.connection.retire','integration.mapping.activate',
           'integration.mapping.retire','integration.api_client.create',
           'integration.api_client.revoke','integration.replay.authorize')
FROM authorization_permissions
WHERE registry_version='m13-standing-direction-v1';
