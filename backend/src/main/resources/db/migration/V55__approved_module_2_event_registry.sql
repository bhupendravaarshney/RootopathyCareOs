INSERT INTO authorization_operations
    (operation_key,permission_key,display_name,description,mutation,denial_mode,
     reason_required,recent_authentication_required,recent_authentication_max_age_seconds,
     maximum_future_skew_seconds,maker_checker_required,status,registry_version,mfa_required)
VALUES
    ('credential.review.claim','credential.review.queue','Claim credential review','Claim or release an independently reviewable credential.',true,'hidden',false,false,NULL,NULL,false,'active','m2-candidate-1',false),
    ('m2.credential.scan.bind','credential.document.upload','Bind credential scan evidence','Bind accepted platform scan evidence to one credential document.',true,'hidden',false,false,NULL,NULL,false,'active','m2-candidate-1',false),
    ('m2.eligibility.evaluate','practitioner.eligibility.read','Evaluate practitioner eligibility','Calculate one immutable point-in-time eligibility result.',true,'hidden',false,false,NULL,NULL,false,'active','m2-candidate-1',false),
    ('m2.expiry.process','workforce.expiry.escalate','Process workforce expiry','Project deterministic credential expiry milestones.',true,'hidden',false,false,NULL,NULL,false,'active','m2-candidate-1',false),
    ('m2.notification.deliver','workforce.expiry.escalate','Deliver workforce notification','Deliver one authorized minimum-necessary notification.',true,'hidden',false,false,NULL,NULL,false,'active','m2-candidate-1',false),
    ('m2.offboarding.execute','workforce.offboarding.execute','Execute approved offboarding','Execute one approved due tenant-bound offboarding plan.',true,'hidden',false,false,NULL,NULL,false,'active','m2-candidate-1',false),
    ('m2.export.generate','workforce.export.request','Generate workforce export','Generate one approved bounded workforce export snapshot.',true,'hidden',false,false,NULL,NULL,false,'active','m2-candidate-1',false),
    ('m2.retention.dispose','workforce.export.access','Dispose workforce artifact','Dispose one expired artifact after retention and hold checks.',true,'hidden',false,false,NULL,NULL,false,'active','m2-candidate-1',false),
    ('m2.outbox.publish','workforce.audit.read','Publish Module 2 outbox','Publish only allow-listed Module 2 event versions.',true,'hidden',false,false,NULL,NULL,false,'active','m2-candidate-1',false);

CREATE TEMP TABLE m2_audit_seed (
    event_name varchar(180) PRIMARY KEY,
    subject_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    payload_keys text[] NOT NULL,
    reason_required boolean NOT NULL
) ON COMMIT DROP;

INSERT INTO m2_audit_seed VALUES
 ('workforce.person.match_decided','workforce_member','workforce.person.match',ARRAY['onboardingId','matchRunId','decisionCode','candidateReference','lockVersion'],true),
 ('workforce.person.linked','workforce_member','workforce.member.create',ARRAY['memberId','organizationPersonLinkId','linkType','lockVersion'],true),
 ('workforce.person.corrected','workforce_member','workforce.person.correct',ARRAY['memberId','changedFields','provenanceCode','lockVersion'],true),
 ('workforce.member.updated','workforce_member','workforce.member.manage',ARRAY['memberId','changedFields','lockVersion'],true),
 ('workforce.assignment.transferred','workforce_member','workforce.assignment.lifecycle',ARRAY['memberId','predecessorId','successorId','impactDigest','effectiveTime'],true),
 ('workforce.account_link.requested','workforce_member','workforce.account_link.request',ARRAY['memberId','requestId','linkMode','state','membershipId'],true),
 ('workforce.account_link.cancelled','workforce_member','workforce.account_link.request',ARRAY['memberId','requestId','linkMode','state','membershipId'],true),
 ('workforce.account_link.completed','workforce_member','workforce.account_link.request',ARRAY['memberId','requestId','linkMode','state','membershipId'],true),
 ('workforce.member.activated','workforce_member','workforce.activation.execute',ARRAY['memberId','activationRequestId','resultDigest','effectiveTime','revision'],true),
 ('workforce.member.suspended','workforce_member','workforce.lifecycle.suspend',ARRAY['memberId','transitionId','categoryCode','impactDigest','effectiveTime','revision'],true),
 ('workforce.member.reactivated','workforce_member','workforce.lifecycle.reactivate',ARRAY['memberId','transitionId','categoryCode','impactDigest','effectiveTime','revision'],true),
 ('workforce.evidence.accessed','workforce_evidence','workforce.audit.read',ARRAY['evidenceFamily','evidenceId','projection','purposeCode','redactionPolicyVersion'],false);

INSERT INTO m2_audit_seed
SELECT event_name,'person_merge_request',operation_key,
       ARRAY['mergeRequestId','retainedLinkId','discardedLinkId','impactDigest','state'],true
FROM (VALUES
 ('workforce.person_merge.requested','workforce.person.merge.request'),
 ('workforce.person_merge.approved','workforce.person.merge.approve'),
 ('workforce.person_merge.rejected','workforce.person.merge.approve'),
 ('workforce.person_merge.executed','workforce.person.merge.execute'),
 ('workforce.person_merge.cancelled','workforce.person.merge.request')
) seed(event_name,operation_key);

INSERT INTO m2_audit_seed
SELECT event_name,'workforce_identifier',
       CASE WHEN event_name IN ('workforce.identifier.created','workforce.identifier.updated') THEN 'workforce.member.manage' ELSE 'workforce.member.manage' END,
       ARRAY['memberId','identifierId','fromState','toState','lockVersion'],true
FROM unnest(ARRAY['workforce.identifier.created','workforce.identifier.updated','workforce.identifier.verified','workforce.identifier.revoked','workforce.identifier.superseded']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'employment_engagement',
       CASE WHEN event_name='workforce.engagement.created' THEN 'workforce.engagement.manage' ELSE 'workforce.engagement.lifecycle' END,
       ARRAY['memberId','engagementId','fromState','toState','effectiveFrom','effectiveTo','lockVersion'],true
FROM unnest(ARRAY['workforce.engagement.created','workforce.engagement.scheduled','workforce.engagement.activated','workforce.engagement.suspended','workforce.engagement.ended','workforce.engagement.cancelled']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'practitioner_profile',
       CASE WHEN event_name='practitioner.profile.created' THEN 'workforce.practitioner.manage' ELSE 'workforce.practitioner.lifecycle' END,
       ARRAY['memberId','practitionerId','professionVersionId','fromState','toState','lockVersion'],true
FROM unnest(ARRAY['practitioner.profile.created','practitioner.profile.activated','practitioner.profile.suspended','practitioner.profile.ended']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'qualification','credential.qualification.manage',
       ARRAY['memberId','qualificationId','fromState','toState','revision'],true
FROM unnest(ARRAY['credential.qualification.created','credential.qualification.submitted','credential.qualification.verified','credential.qualification.rejected','credential.qualification.returned','credential.qualification.superseded']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'professional_registration',
       CASE WHEN event_name IN ('credential.registration.created','credential.registration.submitted') THEN 'credential.registration.manage' ELSE 'credential.registration.lifecycle' END,
       ARRAY['practitionerId','registrationId','fromState','toState','expiryDate','revision'],true
FROM unnest(ARRAY['credential.registration.created','credential.registration.submitted','credential.registration.verified','credential.registration.suspended','credential.registration.revoked','credential.registration.expired','credential.registration.superseded']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'practitioner_credential','credential.record.manage',
       ARRAY['practitionerId','credentialId','fromState','toState','evidenceCount','revision'],true
FROM unnest(ARRAY['credential.record.created','credential.record.updated','credential.record.submitted','credential.record.returned']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'credential_document','credential.document.upload',
       ARRAY['credentialId','documentId','declaredType','declaredSize','digest','state'],false
FROM unnest(ARRAY['credential.document.intent_created','credential.document.uploaded','credential.document.quarantined']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'credential_document','m2.credential.scan.bind',
       ARRAY['credentialId','documentId','scanAttemptId','scannerPolicyVersion','outcome','failureCode'],false
FROM unnest(ARRAY['credential.document.scan_started','credential.document.clean','credential.document.infected','credential.document.invalid','credential.document.failed']) event_name;

INSERT INTO m2_audit_seed VALUES
 ('credential.document.accessed','credential_document','credential.document.read',ARRAY['credentialId','documentId','accessIntentId','purposeCode','evidenceDigest'],false);

INSERT INTO m2_audit_seed
SELECT event_name,'practitioner_credential','credential.review.claim',
       ARRAY['credentialId','queueItemId','reviewerId','leaseExpiry','state'],false
FROM unnest(ARRAY['credential.review.claimed','credential.review.released','credential.review.lease_expired']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'practitioner_credential','credential.review.decide',
       ARRAY['practitionerId','credentialId','verificationId','decisionCode','evidenceDigest','policyVersion','revision'],true
FROM unnest(ARRAY['credential.review.verified','credential.review.rejected','credential.review.more_information_required','credential.review.returned_for_correction']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'practitioner_credential','credential.lifecycle',
       ARRAY['practitionerId','credentialId','fromState','toState','effectiveTime','revision'],true
FROM unnest(ARRAY['credential.record.suspended','credential.record.revoked','credential.record.expired','credential.record.superseded']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'credential_legal_hold','credential.lifecycle',
       ARRAY['credentialId','legalHoldId','authorityCode','state','evidenceDigest'],true
FROM unnest(ARRAY['credential.legal_hold.imposed','credential.legal_hold.released']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'practitioner_specialty','practitioner.specialty.manage',
       ARRAY['practitionerId','specialtyId','specialtyVersionId','designation','effectiveFrom','effectiveTo'],true
FROM unnest(ARRAY['practitioner.specialty.scheduled','practitioner.specialty.activated','practitioner.specialty.ended','practitioner.specialty.superseded']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'scope_of_practice',
       CASE WHEN event_name='practitioner.scope.submitted' THEN 'practitioner.scope.submit' ELSE 'practitioner.scope.manage' END,
       ARRAY['practitionerId','scopeId','definitionVersionId','fromState','toState','resultDigest','revision'],true
FROM unnest(ARRAY['practitioner.scope.created','practitioner.scope.updated','practitioner.scope.submitted']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'scope_of_practice','practitioner.scope.approve',
       ARRAY['practitionerId','scopeId','decisionId','decisionCode','resultDigest','policyVersion','revision'],true
FROM unnest(ARRAY['practitioner.scope.approved','practitioner.scope.rejected','practitioner.scope.changes_requested']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'scope_of_practice','practitioner.scope.lifecycle',
       ARRAY['practitionerId','scopeId','fromState','toState','effectiveTime','revision'],true
FROM unnest(ARRAY['practitioner.scope.suspended','practitioner.scope.ended','practitioner.scope.superseded']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'workforce_assignment',
       CASE WHEN event_name='workforce.assignment.created' THEN 'workforce.assignment.manage' ELSE 'workforce.assignment.lifecycle' END,
       ARRAY['memberId','assignmentId','contextType','contextId','fromState','toState','effectiveFrom','effectiveTo','revision'],true
FROM unnest(ARRAY['workforce.assignment.created','workforce.assignment.scheduled','workforce.assignment.activated','workforce.assignment.suspended','workforce.assignment.reactivated','workforce.assignment.ended','workforce.assignment.cancelled']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'practitioner_service_assignment',
       CASE WHEN event_name='practitioner.service_assignment.created' THEN 'practitioner.service_assignment.manage' ELSE 'practitioner.service_assignment.lifecycle' END,
       ARRAY['practitionerId','serviceAssignmentId','serviceId','contextId','eligibilityEvidenceId','fromState','toState','revision'],true
FROM unnest(ARRAY['practitioner.service_assignment.created','practitioner.service_assignment.scheduled','practitioner.service_assignment.activated','practitioner.service_assignment.suspended','practitioner.service_assignment.ended','practitioner.service_assignment.cancelled']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'practitioner_eligibility','m2.eligibility.evaluate',
       ARRAY['practitionerId','contextId','resultId','outcome','resultDigest','expiryTime'],false
FROM unnest(ARRAY['practitioner.eligibility.evaluated','practitioner.eligibility.invalidated']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'availability_profile','workforce.availability.manage',
       ARRAY['memberId','profileId','timezone','effectiveFrom','intervalCount','exceptionCount','revision'],true
FROM unnest(ARRAY['workforce.availability.scheduled','workforce.availability.activated','workforce.availability.superseded','workforce.availability.cancelled']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'workforce_readiness_run','workforce.validation.run',
       ARRAY['memberId','runId','pathway','resultDigest','blockerCount','warningCount','expiresAt','failureCode'],false
FROM unnest(ARRAY['workforce.readiness.completed','workforce.readiness.failed','workforce.readiness.invalidated']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'workforce_activation_request',operation_key,
       ARRAY['memberId','activationRequestId','runId','resultDigest','decisionCode','state'],true
FROM (VALUES
 ('workforce.activation.submitted','workforce.activation.submit'),
 ('workforce.activation.approved','workforce.activation.approve'),
 ('workforce.activation.rejected','workforce.activation.approve'),
 ('workforce.activation.expired','workforce.activation.submit'),
 ('workforce.activation.invalidated','workforce.activation.submit')
) seed(event_name,operation_key);

INSERT INTO m2_audit_seed
SELECT event_name,'workforce_offboarding_request',operation_key,
       ARRAY['memberId','offboardingRequestId','impactDigest','effectiveTime','state','failureCode'],true
FROM (VALUES
 ('workforce.offboarding.requested','workforce.offboarding.request'),
 ('workforce.offboarding.approved','workforce.offboarding.approve'),
 ('workforce.offboarding.scheduled','workforce.offboarding.approve'),
 ('workforce.offboarding.started','m2.offboarding.execute'),
 ('workforce.offboarding.completed','m2.offboarding.execute'),
 ('workforce.offboarding.failed','m2.offboarding.execute'),
 ('workforce.offboarding.cancelled','workforce.offboarding.request')
) seed(event_name,operation_key);

INSERT INTO m2_audit_seed
SELECT event_name,'practitioner_credential',
       CASE WHEN event_name='credential.expiry.escalated' THEN 'workforce.expiry.escalate' ELSE 'm2.expiry.process' END,
       ARRAY['credentialId','milestone','expiryDate','notificationId','outcomeCode'],false
FROM unnest(ARRAY['credential.expiry.milestone_reached','credential.expiry.reminder_suppressed','credential.expiry.escalated']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'workforce_notification','m2.notification.deliver',
       ARRAY['notificationId','templateVersion','channel','milestone','attempt','state','failureCode'],false
FROM unnest(ARRAY['workforce.notification.queued','workforce.notification.delivered','workforce.notification.failed','workforce.notification.suppressed']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'workforce_registry_version',operation_key,
       ARRAY['definitionId','entryId','versionId','changeRequestId','fromState','toState','resultDigest'],true
FROM (VALUES
 ('workforce.registry.created','workforce.registry.manage'),
 ('workforce.registry.submitted','workforce.registry.manage'),
 ('workforce.registry.approved','workforce.registry.approve'),
 ('workforce.registry.rejected','workforce.registry.approve'),
 ('workforce.registry.activated','workforce.registry.activate'),
 ('workforce.registry.superseded','workforce.registry.activate')
) seed(event_name,operation_key);

INSERT INTO m2_audit_seed
SELECT event_name,'workforce_configuration_snapshot','workforce.registry.activate',
       ARRAY['snapshotId','previousSnapshotId','changeRequestId','resultDigest','effectiveTime'],true
FROM unnest(ARRAY['workforce.configuration.activated','workforce.configuration.superseded']) event_name;

INSERT INTO m2_audit_seed
SELECT event_name,'workforce_export',operation_key,
       ARRAY['exportId','projection','format','filterDigest','purposeCode','approvalId'],true
FROM (VALUES
 ('workforce.export.requested','workforce.export.request'),
 ('workforce.export.authorized','workforce.export.approve'),
 ('workforce.export.denied','workforce.export.approve')
) seed(event_name,operation_key);

INSERT INTO m2_audit_seed
SELECT event_name,'workforce_export',operation_key,
       ARRAY['exportId','state','artifactDigest','rowCount','expiryTime','failureCode'],false
FROM (VALUES
 ('workforce.export.completed','m2.export.generate'),
 ('workforce.export.failed','m2.export.generate'),
 ('workforce.export.accessed','workforce.export.access'),
 ('workforce.export.expired','m2.retention.dispose'),
 ('workforce.export.disposed','m2.retention.dispose')
) seed(event_name,operation_key);

INSERT INTO audit_event_definitions
    (event_name,schema_version,display_name,description,subject_type,reason_required,
     required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,replace(initcap(replace(event_name,'.',' ')),'_',' '),
       'Approved CareOS Module 2 governed audit evidence.',subject_type,reason_required,
       payload_keys,payload_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m2-candidate-1'
FROM m2_audit_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'audit',event_name,1,'active','m2-candidate-1'
FROM m2_audit_seed;

CREATE TEMP TABLE m2_outbox_seed (
    event_name varchar(180) PRIMARY KEY,
    aggregate_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    payload_keys text[] NOT NULL
) ON COMMIT DROP;

INSERT INTO m2_outbox_seed VALUES
 ('workforce.member.draft_created','workforce_member','workforce.member.create',ARRAY['memberId','pathway','lockVersion']),
 ('workforce.member.changed','workforce_member','workforce.member.manage',ARRAY['memberId','changeFamily','lockVersion']),
 ('workforce.person_merge.executed','person_merge_request','workforce.person.merge.execute',ARRAY['mergeRequestId','retainedMemberId','affectedReferenceCount']),
 ('credential.qualification.changed','qualification','credential.qualification.manage',ARRAY['memberId','qualificationId','toState','revision']),
 ('credential.record.submitted','practitioner_credential','credential.record.manage',ARRAY['practitionerId','credentialId','toState','revision']),
 ('credential.record.returned','practitioner_credential','credential.record.manage',ARRAY['practitionerId','credentialId','toState','revision']),
 ('credential.document.quarantined','credential_document','credential.document.upload',ARRAY['credentialId','documentId','digest','state']),
 ('credential.document.clean','credential_document','m2.credential.scan.bind',ARRAY['credentialId','documentId','digest','outcome']),
 ('credential.document.rejected','credential_document','m2.credential.scan.bind',ARRAY['credentialId','documentId','digest','outcome']),
 ('credential.review.decided','practitioner_credential','credential.review.decide',ARRAY['practitionerId','credentialId','verificationId','decisionCode','revision']),
 ('credential.legal_hold.changed','credential_legal_hold','credential.lifecycle',ARRAY['credentialId','legalHoldId','state']),
 ('practitioner.specialty.changed','practitioner_specialty','practitioner.specialty.manage',ARRAY['practitionerId','specialtyId','specialtyVersionId','effectiveFrom','effectiveTo']),
 ('practitioner.scope.submitted','scope_of_practice','practitioner.scope.submit',ARRAY['practitionerId','scopeId','definitionVersionId','resultDigest','revision']),
 ('practitioner.scope.decided','scope_of_practice','practitioner.scope.approve',ARRAY['practitionerId','scopeId','decisionId','decisionCode','revision']),
 ('workforce.assignment.transferred','workforce_assignment','workforce.assignment.lifecycle',ARRAY['memberId','predecessorId','successorId','impactDigest','effectiveTime']),
 ('workforce.account_link.requested','workforce_member','workforce.account_link.request',ARRAY['memberId','requestId','linkMode']),
 ('workforce.readiness.invalidated','workforce_readiness_run','workforce.validation.run',ARRAY['memberId','runId','invalidationCode']),
 ('workforce.member.activated','workforce_member','workforce.activation.execute',ARRAY['memberId','activationRequestId','resultDigest','effectiveTime','revision']),
 ('workforce.member.suspended','workforce_member','workforce.lifecycle.suspend',ARRAY['memberId','transitionId','categoryCode','impactDigest','effectiveTime','revision']),
 ('workforce.member.reactivated','workforce_member','workforce.lifecycle.reactivate',ARRAY['memberId','transitionId','categoryCode','impactDigest','effectiveTime','revision']),
 ('credential.expiry.milestone_reached','practitioner_credential','m2.expiry.process',ARRAY['credentialId','milestone','expiryDate','notificationId']),
 ('workforce.registry.activated','workforce_registry_version','workforce.registry.activate',ARRAY['definitionId','entryId','versionId','previousVersionId','resultDigest']),
 ('workforce.configuration.activated','workforce_configuration_snapshot','workforce.registry.activate',ARRAY['snapshotId','previousSnapshotId','changeRequestId','resultDigest','effectiveTime']),
 ('workforce.configuration.superseded','workforce_configuration_snapshot','workforce.registry.activate',ARRAY['snapshotId','previousSnapshotId','changeRequestId','resultDigest','effectiveTime']),
 ('workforce.export.authorized','workforce_export','workforce.export.approve',ARRAY['exportId','projection','format','filterDigest','purposeCode']),
 ('workforce.export.expired','workforce_export','m2.retention.dispose',ARRAY['exportId','artifactDigest','expiryTime']),
 ('workforce.export.disposal_requested','workforce_export','m2.retention.dispose',ARRAY['exportId','artifactDigest','expiryTime']);

INSERT INTO m2_outbox_seed
SELECT event_name,'workforce_identifier','workforce.member.manage',
       ARRAY['memberId','identifierId','fromState','toState','lockVersion']
FROM unnest(ARRAY['workforce.identifier.created','workforce.identifier.updated','workforce.identifier.verified','workforce.identifier.revoked','workforce.identifier.superseded']) event_name;

INSERT INTO m2_outbox_seed
SELECT event_name,'employment_engagement',
       CASE WHEN event_name='workforce.engagement.created' THEN 'workforce.engagement.manage' ELSE 'workforce.engagement.lifecycle' END,
       ARRAY['memberId','engagementId','fromState','toState','effectiveFrom','effectiveTo','lockVersion']
FROM unnest(ARRAY['workforce.engagement.created','workforce.engagement.scheduled','workforce.engagement.activated','workforce.engagement.suspended','workforce.engagement.ended','workforce.engagement.cancelled']) event_name;

INSERT INTO m2_outbox_seed
SELECT event_name,'practitioner_profile',
       CASE WHEN event_name='practitioner.profile.created' THEN 'workforce.practitioner.manage' ELSE 'workforce.practitioner.lifecycle' END,
       ARRAY['memberId','practitionerId','professionVersionId','fromState','toState','lockVersion']
FROM unnest(ARRAY['practitioner.profile.created','practitioner.profile.activated','practitioner.profile.suspended','practitioner.profile.ended']) event_name;

INSERT INTO m2_outbox_seed
SELECT event_name,'professional_registration',
       CASE WHEN event_name IN ('credential.registration.created','credential.registration.submitted') THEN 'credential.registration.manage' ELSE 'credential.registration.lifecycle' END,
       ARRAY['practitionerId','registrationId','fromState','toState','expiryDate','revision']
FROM unnest(ARRAY['credential.registration.created','credential.registration.submitted','credential.registration.verified','credential.registration.suspended','credential.registration.revoked','credential.registration.expired','credential.registration.superseded']) event_name;

INSERT INTO m2_outbox_seed
SELECT event_name,'practitioner_credential','credential.lifecycle',
       ARRAY['practitionerId','credentialId','fromState','toState','effectiveTime','revision']
FROM unnest(ARRAY['credential.record.suspended','credential.record.revoked','credential.record.expired','credential.record.superseded']) event_name;

INSERT INTO m2_outbox_seed
SELECT event_name,'scope_of_practice','practitioner.scope.lifecycle',
       ARRAY['practitionerId','scopeId','fromState','toState','effectiveTime','revision']
FROM unnest(ARRAY['practitioner.scope.suspended','practitioner.scope.ended','practitioner.scope.superseded']) event_name;

INSERT INTO m2_outbox_seed
SELECT event_name,'workforce_assignment',
       CASE WHEN event_name='workforce.assignment.created' THEN 'workforce.assignment.manage' ELSE 'workforce.assignment.lifecycle' END,
       ARRAY['memberId','assignmentId','contextType','contextId','fromState','toState','effectiveFrom','effectiveTo','revision']
FROM unnest(ARRAY['workforce.assignment.created','workforce.assignment.scheduled','workforce.assignment.activated','workforce.assignment.suspended','workforce.assignment.reactivated','workforce.assignment.ended','workforce.assignment.cancelled']) event_name;

INSERT INTO m2_outbox_seed
SELECT event_name,'practitioner_service_assignment',
       CASE WHEN event_name='practitioner.service_assignment.created' THEN 'practitioner.service_assignment.manage' ELSE 'practitioner.service_assignment.lifecycle' END,
       ARRAY['practitionerId','serviceAssignmentId','serviceId','contextId','eligibilityEvidenceId','fromState','toState','revision']
FROM unnest(ARRAY['practitioner.service_assignment.created','practitioner.service_assignment.scheduled','practitioner.service_assignment.activated','practitioner.service_assignment.suspended','practitioner.service_assignment.ended','practitioner.service_assignment.cancelled']) event_name;

INSERT INTO m2_outbox_seed
SELECT event_name,'practitioner_eligibility','m2.eligibility.evaluate',
       ARRAY['practitionerId','contextId','resultId','outcome','resultDigest','expiryTime']
FROM unnest(ARRAY['practitioner.eligibility.evaluated','practitioner.eligibility.invalidated']) event_name;

INSERT INTO m2_outbox_seed
SELECT event_name,'availability_profile','workforce.availability.manage',
       ARRAY['memberId','profileId','timezone','effectiveFrom','intervalCount','exceptionCount','revision']
FROM unnest(ARRAY['workforce.availability.scheduled','workforce.availability.activated','workforce.availability.superseded','workforce.availability.cancelled']) event_name;

INSERT INTO m2_outbox_seed
SELECT event_name,'workforce_activation_request',operation_key,
       ARRAY['memberId','activationRequestId','runId','resultDigest','decisionCode','state']
FROM (VALUES
 ('workforce.activation.submitted','workforce.activation.submit'),
 ('workforce.activation.approved','workforce.activation.approve'),
 ('workforce.activation.rejected','workforce.activation.approve'),
 ('workforce.activation.invalidated','workforce.activation.submit')
) seed(event_name,operation_key);

INSERT INTO m2_outbox_seed
SELECT event_name,'workforce_offboarding_request',operation_key,
       ARRAY['memberId','offboardingRequestId','impactDigest','effectiveTime','state']
FROM (VALUES
 ('workforce.offboarding.approved','workforce.offboarding.approve'),
 ('workforce.offboarding.completed','m2.offboarding.execute'),
 ('workforce.offboarding.failed','m2.offboarding.execute')
) seed(event_name,operation_key);

INSERT INTO outbox_event_definitions
    (event_name,schema_version,description,aggregate_type,required_payload_keys,
     allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,'Approved CareOS Module 2 transactional event.',aggregate_type,
       payload_keys,payload_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m2-candidate-1'
FROM m2_outbox_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'outbox',event_name,1,'active','m2-candidate-1'
FROM m2_outbox_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT service_operation,'audit',event_name,1,'active','m2-candidate-1'
FROM (VALUES
 ('m2.offboarding.execute','workforce.offboarding.started'),
 ('m2.offboarding.execute','workforce.offboarding.completed'),
 ('m2.offboarding.execute','workforce.offboarding.failed'),
 ('m2.export.generate','workforce.export.completed'),
 ('m2.export.generate','workforce.export.failed'),
 ('m2.retention.dispose','workforce.export.expired'),
 ('m2.retention.dispose','workforce.export.disposed')
) extra(service_operation,event_name)
ON CONFLICT DO NOTHING;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
VALUES
 ('workforce.person.correct','outbox','workforce.member.changed',1,'active','m2-candidate-1'),
 ('workforce.offboarding.execute','audit','workforce.offboarding.completed',1,'active','m2-candidate-1'),
 ('workforce.offboarding.execute','audit','workforce.offboarding.failed',1,'active','m2-candidate-1'),
 ('workforce.offboarding.execute','outbox','workforce.offboarding.completed',1,'active','m2-candidate-1'),
 ('workforce.offboarding.execute','outbox','workforce.offboarding.failed',1,'active','m2-candidate-1')
ON CONFLICT DO NOTHING;

COMMENT ON TABLE audit_event_definitions IS
    'Migration-owned approved audit registry, including the checksum-bound Module 2 version 1 catalogue.';
