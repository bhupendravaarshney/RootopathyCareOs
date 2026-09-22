-- Module 2 workers consume only their exact, migration-owned outbox contracts.
-- The inbox foreign key and insert trigger reject any other event/version/aggregate tuple.
INSERT INTO outbox_consumer_definitions(
    consumer_key,event_name,schema_version,description,status,registry_version)
VALUES
    ('m2-offboarding-worker-v1','workforce.offboarding.approved',1,
     'Execute one independently approved, due workforce offboarding plan.',
     'active','m2-candidate-1'),
    ('m2-export-worker-v1','workforce.export.authorized',1,
     'Generate one bounded and authorized workforce export snapshot.',
     'active','m2-candidate-1'),
    ('m2-retention-worker-v1','workforce.export.disposal_requested',1,
     'Recheck holds and dispose one expired digest-bound workforce export artifact.',
     'active','m2-candidate-1'),
    ('m2-notification-worker-v1','credential.expiry.milestone_reached',1,
     'Deliver one authorized minimum-necessary expiry milestone notification.',
     'active','m2-candidate-1')
ON CONFLICT (consumer_key,event_name,schema_version) DO NOTHING;

INSERT INTO outbox_consumer_definitions(
    consumer_key,event_name,schema_version,description,status,registry_version)
SELECT
    'm2-expiry-projector-v1',event_name,1,
    'Reconcile deterministic expiry work after an authoritative source lifecycle event.',
    'active','m2-candidate-1'
FROM unnest(ARRAY[
    'credential.registration.verified',
    'credential.registration.superseded',
    'credential.registration.expired',
    'credential.review.decided',
    'credential.record.superseded',
    'credential.record.expired'
]::varchar[]) AS expiry_source(event_name)
ON CONFLICT (consumer_key,event_name,schema_version) DO NOTHING;

INSERT INTO outbox_consumer_definitions(
    consumer_key,event_name,schema_version,description,status,registry_version)
SELECT
    'm2-eligibility-evaluator-v1',event_name,1,
    'Re-evaluate prospective service eligibility after one authoritative material event.',
    'active','m2-candidate-1'
FROM unnest(ARRAY[
    'credential.qualification.changed',
    'credential.registration.verified',
    'credential.registration.suspended',
    'credential.registration.revoked',
    'credential.registration.expired',
    'credential.registration.superseded',
    'credential.record.submitted',
    'credential.record.returned',
    'credential.review.decided',
    'credential.record.suspended',
    'credential.record.revoked',
    'credential.record.expired',
    'credential.record.superseded',
    'credential.expiry.milestone_reached',
    'practitioner.scope.submitted',
    'practitioner.scope.decided',
    'practitioner.scope.suspended',
    'practitioner.scope.ended',
    'practitioner.scope.superseded',
    'workforce.assignment.created',
    'workforce.assignment.scheduled',
    'workforce.assignment.activated',
    'workforce.assignment.suspended',
    'workforce.assignment.reactivated',
    'workforce.assignment.ended',
    'workforce.assignment.cancelled',
    'workforce.assignment.transferred',
    'practitioner.service_assignment.created',
    'practitioner.service_assignment.scheduled',
    'practitioner.service_assignment.activated',
    'practitioner.service_assignment.suspended',
    'practitioner.service_assignment.ended',
    'practitioner.service_assignment.cancelled',
    'workforce.member.suspended',
    'workforce.member.reactivated',
    'workforce.offboarding.completed',
    'workforce.registry.activated',
    'workforce.configuration.activated',
    'workforce.configuration.superseded'
]::varchar[]) AS material(event_name)
ON CONFLICT (consumer_key,event_name,schema_version) DO NOTHING;

INSERT INTO outbox_consumer_definitions(
    consumer_key,event_name,schema_version,description,status,registry_version)
SELECT
    'm2-readiness-invalidator-v1',event_name,1,
    'Reconcile stale readiness and pending activation after one authoritative material event.',
    'active','m2-candidate-1'
FROM unnest(ARRAY[
    'organization.profile.updated',
    'identity.membership.changed','identity.membership.revoked','identity.owner.transferred',
    'facility.created','facility.updated','facility.submitted','facility.activated',
    'facility.suspended','facility.closed',
    'network.unit.changed','network.unit.reparented',
    'network.location.changed','network.location.reparented',
    'service.definition.created','service.definition.updated',
    'service.definition.activated','service.definition.retired',
    'service.assignment.scheduled','service.assignment.activated',
    'service.assignment.suspended','service.assignment.ended',
    'service.assignment.cancelled',
    'workforce.member.draft_created','workforce.member.changed',
    'workforce.person_merge.executed',
    'workforce.identifier.created','workforce.identifier.updated',
    'workforce.identifier.verified','workforce.identifier.revoked',
    'workforce.identifier.superseded',
    'workforce.engagement.created','workforce.engagement.scheduled',
    'workforce.engagement.activated','workforce.engagement.suspended',
    'workforce.engagement.ended','workforce.engagement.cancelled',
    'practitioner.profile.created','practitioner.profile.activated',
    'practitioner.profile.suspended','practitioner.profile.ended',
    'credential.qualification.changed',
    'credential.registration.created','credential.registration.submitted',
    'credential.registration.verified','credential.registration.suspended',
    'credential.registration.revoked','credential.registration.expired',
    'credential.registration.superseded',
    'credential.record.submitted','credential.record.returned',
    'credential.review.decided','credential.record.suspended',
    'credential.record.revoked','credential.record.expired',
    'credential.record.superseded',
    'credential.document.quarantined','credential.document.clean',
    'credential.document.rejected','credential.legal_hold.changed',
    'practitioner.specialty.changed','practitioner.scope.submitted',
    'practitioner.scope.decided','practitioner.scope.suspended',
    'practitioner.scope.ended','practitioner.scope.superseded',
    'workforce.assignment.created','workforce.assignment.scheduled',
    'workforce.assignment.activated','workforce.assignment.suspended',
    'workforce.assignment.reactivated','workforce.assignment.ended',
    'workforce.assignment.cancelled','workforce.assignment.transferred',
    'practitioner.service_assignment.created',
    'practitioner.service_assignment.scheduled',
    'practitioner.service_assignment.activated',
    'practitioner.service_assignment.suspended',
    'practitioner.service_assignment.ended',
    'practitioner.service_assignment.cancelled',
    'workforce.availability.scheduled','workforce.availability.activated',
    'workforce.availability.superseded','workforce.availability.cancelled',
    'workforce.account_link.requested','workforce.member.suspended',
    'workforce.member.reactivated','workforce.offboarding.completed',
    'workforce.registry.activated','workforce.configuration.activated',
    'workforce.configuration.superseded'
]::varchar[]) AS material(event_name)
ON CONFLICT (consumer_key,event_name,schema_version) DO NOTHING;

INSERT INTO outbox_consumer_definitions(
    consumer_key,event_name,schema_version,description,status,registry_version)
SELECT
    'm2-history-projector-v1',event_name,1,
    'Deduplicate one approved lifecycle, decision, configuration, or evidence history event.',
    'active','m2-candidate-1'
FROM unnest(ARRAY[
    'workforce.member.draft_created','workforce.member.changed',
    'workforce.person_merge.executed',
    'workforce.identifier.created','workforce.identifier.updated',
    'workforce.identifier.verified','workforce.identifier.revoked',
    'workforce.identifier.superseded',
    'workforce.engagement.created','workforce.engagement.scheduled',
    'workforce.engagement.activated','workforce.engagement.suspended',
    'workforce.engagement.ended','workforce.engagement.cancelled',
    'practitioner.profile.created','practitioner.profile.activated',
    'practitioner.profile.suspended','practitioner.profile.ended',
    'credential.qualification.changed',
    'credential.registration.created','credential.registration.submitted',
    'credential.registration.verified','credential.registration.suspended',
    'credential.registration.revoked','credential.registration.expired',
    'credential.registration.superseded',
    'credential.record.submitted','credential.record.returned',
    'credential.review.decided','credential.record.suspended',
    'credential.record.revoked','credential.record.expired',
    'credential.record.superseded',
    'credential.document.quarantined','credential.document.clean',
    'credential.document.rejected','credential.legal_hold.changed',
    'practitioner.specialty.changed','practitioner.scope.submitted',
    'practitioner.scope.decided','practitioner.scope.suspended',
    'practitioner.scope.ended','practitioner.scope.superseded',
    'workforce.assignment.created','workforce.assignment.scheduled',
    'workforce.assignment.activated','workforce.assignment.suspended',
    'workforce.assignment.reactivated','workforce.assignment.ended',
    'workforce.assignment.cancelled','workforce.assignment.transferred',
    'workforce.account_link.requested',
    'practitioner.service_assignment.created',
    'practitioner.service_assignment.scheduled',
    'practitioner.service_assignment.activated',
    'practitioner.service_assignment.suspended',
    'practitioner.service_assignment.ended',
    'practitioner.service_assignment.cancelled',
    'practitioner.eligibility.evaluated','practitioner.eligibility.invalidated',
    'workforce.availability.scheduled','workforce.availability.activated',
    'workforce.availability.superseded','workforce.availability.cancelled',
    'workforce.readiness.invalidated',
    'workforce.activation.submitted','workforce.activation.approved',
    'workforce.activation.rejected','workforce.activation.invalidated',
    'workforce.member.activated','workforce.member.suspended',
    'workforce.member.reactivated','workforce.offboarding.approved',
    'workforce.offboarding.completed','workforce.offboarding.failed',
    'credential.expiry.milestone_reached','workforce.registry.activated',
    'workforce.configuration.activated','workforce.configuration.superseded'
]::varchar[]) AS history(event_name)
ON CONFLICT (consumer_key,event_name,schema_version) DO NOTHING;
