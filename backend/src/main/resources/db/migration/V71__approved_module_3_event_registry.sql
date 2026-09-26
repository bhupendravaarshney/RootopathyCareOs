CREATE TEMP TABLE m3_audit_seed (
    event_name varchar(180) PRIMARY KEY,
    subject_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL,
    reason_required boolean NOT NULL
) ON COMMIT DROP;

INSERT INTO m3_audit_seed VALUES
 ('patient.registration.started','patient_registration','patient.registration.start',
  ARRAY['registrationId','sourceCode','expiresAt','currentStep'],
  ARRAY['registrationId','sourceCode','expiresAt','currentStep'],false),
 ('patient.registration.search_completed','patient_registration','patient.duplicate.search',
  ARRAY['registrationId','detectorVersion','candidateCount','resultDigest'],
  ARRAY['registrationId','detectorVersion','candidateCount','candidateBands','resultDigest'],false),
 ('patient.registration.disposition_recorded','patient_registration','patient.registration.manage',
  ARRAY['registrationId','dispositionCode','resultDigest'],
  ARRAY['registrationId','dispositionCode','patientId','sourceRevision','resultDigest'],true),
 ('patient.registration.validated','patient_registration','patient.registration.manage',
  ARRAY['registrationId','patientId','revision','validationDigest'],
  ARRAY['registrationId','patientId','revision','validationDigest','blockerCount','warningCount'],true),
 ('patient.registration.completed','patient_registration','patient.registration.submit',
  ARRAY['registrationId','patientId','resultKind','revision','validationDigest'],
  ARRAY['registrationId','patientId','resultKind','revision','validationDigest'],true),
 ('patient.identity.corrected','patient_profile','patient.profile.manage',
  ARRAY['patientId','changedFields','provenanceCode','revision'],
  ARRAY['patientId','changedFields','predecessorReference','successorReference','provenanceCode','revision'],true),
 ('patient.lifecycle.changed','patient_profile','patient.profile.manage',
  ARRAY['patientId','priorState','newState','effectiveTime','revision'],
  ARRAY['patientId','priorState','newState','effectiveTime','reasonCode','evidenceReference','revision'],true),
 ('patient.contact.changed','patient_contact','patient.contact.manage',
  ARRAY['patientId','contactId','channel','state','verificationClass','revision'],
  ARRAY['patientId','contactId','channel','contactUse','state','verificationClass','primary','preferred','revision'],true),
 ('patient.address.changed','patient_address','patient.contact.manage',
  ARRAY['patientId','addressId','addressUse','state','validationClass','revision'],
  ARRAY['patientId','addressId','addressUse','state','validationClass','primary','preferred','revision'],true),
 ('patient.preference.changed','communication_preference','patient.preference.manage',
  ARRAY['patientId','preferenceId','purposeKey','channel','state','revision'],
  ARRAY['patientId','preferenceId','purposeKey','channel','languageTag','formatKey','state','revision'],true),
 ('patient.relationship.changed','caregiver_relationship','patient.profile.manage',
  ARRAY['patientId','relationshipId','relationshipTypeKey','state','revision'],
  ARRAY['patientId','relationshipId','relationshipTypeKey','state','revision'],true),
 ('patient.duplicate.claimed','patient_duplicate_candidate','patient.duplicate.review',
  ARRAY['candidateId','reviewerId','leaseExpiry','revision'],
  ARRAY['candidateId','reviewerId','leaseReference','leaseExpiry','revision'],false),
 ('patient.duplicate.dispositioned','patient_duplicate_candidate','patient.duplicate.review',
  ARRAY['candidateId','dispositionCode','reasonCode','revision'],
  ARRAY['candidateId','detectorVersion','dispositionCode','reasonCode','resultDigest','revision'],true),
 ('patient.merge.requested','patient_merge_request','patient.merge.request',
  ARRAY['mergeRequestId','survivorPatientId','duplicatePatientId','impactDigest','expiry'],
  ARRAY['mergeRequestId','survivorPatientId','duplicatePatientId','sourceRevisionDigest','fieldDispositionDigest','affectedReferenceDigest','impactDigest','expiry'],true),
 ('patient.merge.decided','patient_merge_request','patient.merge.decide',
  ARRAY['mergeRequestId','decisionId','decisionCode','impactDigest','decisionExpiry'],
  ARRAY['mergeRequestId','decisionId','requestRevision','decisionCode','reasonCode','impactDigest','decisionExpiry'],true),
 ('patient.merge.executed','patient_merge_request','patient.merge.execute',
  ARRAY['mergeRequestId','decisionId','survivorPatientId','duplicatePatientId','affectedReferenceCount','invalidationDigest'],
  ARRAY['mergeRequestId','decisionId','survivorPatientId','duplicatePatientId','survivorRevision','duplicateRevision','affectedReferenceCount','invalidationDigest'],true);

INSERT INTO audit_event_definitions
    (event_name,schema_version,display_name,description,subject_type,reason_required,
     required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,replace(initcap(replace(event_name,'.',' ')),'_',' '),
       'Approved CareOS Module 3 governed patient-registry audit evidence.',
       subject_type,reason_required,required_keys,allowed_keys,
       '{"type":"object","additionalProperties":false}'::jsonb,
       'active','m3-candidate-1'
FROM m3_audit_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'audit',event_name,1,'active','m3-candidate-1'
FROM m3_audit_seed;

CREATE TEMP TABLE m3_outbox_seed (
    event_name varchar(180) PRIMARY KEY,
    aggregate_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL,
    required_keys text[] NOT NULL,
    allowed_keys text[] NOT NULL
) ON COMMIT DROP;

INSERT INTO m3_outbox_seed VALUES
 ('m3.patient.registered.v1','patient','patient.registration.submit',
  ARRAY['patientId','registrationId','revision','validationDigest'],
  ARRAY['patientId','registrationId','revision','validationDigest']),
 ('m3.patient.identity-changed.v1','patient','patient.profile.manage',
  ARRAY['patientId','changeFamily','revision'],
  ARRAY['patientId','changeFamily','revision','invalidationDigest']),
 ('m3.patient.lifecycle-changed.v1','patient','patient.profile.manage',
  ARRAY['patientId','priorState','newState','revision'],
  ARRAY['patientId','priorState','newState','revision','invalidationDigest']),
 ('m3.patient.merged.v1','patient_merge_request','patient.merge.execute',
  ARRAY['mergeRequestId','survivorPatientId','duplicatePatientId','affectedReferenceCount'],
  ARRAY['mergeRequestId','survivorPatientId','duplicatePatientId','affectedReferenceCount','invalidationDigest']);

INSERT INTO outbox_event_definitions
    (event_name,schema_version,description,aggregate_type,required_payload_keys,
     allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,'Approved CareOS Module 3 transactional patient event.',aggregate_type,
       required_keys,allowed_keys,'{"type":"object","additionalProperties":false}'::jsonb,
       'active','m3-candidate-1'
FROM m3_outbox_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'outbox',event_name,1,'active','m3-candidate-1'
FROM m3_outbox_seed;

COMMENT ON TABLE audit_event_definitions IS
    'Migration-owned approved audit registry, including the checksum-bound Module 3 version 1 catalogue.';
