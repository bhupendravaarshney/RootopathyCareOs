# Module 2 audit and event registry candidate

**Artifact kind:** `audit-and-event-registry`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m2-candidate-1`  
**Approval:** Not granted

## Decision baseline

Every governed Module 2 mutation commits an append-only audit event and, only where this registry names one, an immutable transactional outbox event in the same transaction as business state, idempotency, and decision evidence. Names are lowercase dot-separated past-tense events at `version 1`. Migration-owned schemas reject unknown versions, missing or extra keys, wrong types/bounds, wrong aggregate/subject, nested payload drift, and tenant/actor/operation context mismatch.

### Common envelope and payload rules

Audit envelope fields are registry/version, event UUIDv7, organization ID, actor type/ID, operation, purpose, subject type/ID, correlation ID, occurred/recorded UTC instants, outcome, risk class, reason code or protected-reason reference, and exact payload. Outbox fields replace subject with aggregate type/ID and producer operation, add payload digest, and never include session, authentication, personal contact, address, birth date, registration/credential number, filename, object key, signed URL, reviewer free text, provider destination, or document content.

Payload keys are exact per event. UUIDs, integer revisions/counts, UTC instants, stable enum/reason codes, SHA-256 digests, and bounded sorted field-name arrays are permitted. Free text is prohibited. Nullable keys are defined only in the exact variant requiring them; a pipe-separated name below denotes separate registry entries, never a wildcard.

### Identity, member, and engagement events

| Operation family | Audit event v1 / required payload | Outbox event v1 / required payload |
| --- | --- | --- |
| Match decision | `workforce.person.match_decided` / `onboardingId, matchRunId, decisionCode, candidateReference, lockVersion` | None; matching is internal evidence. |
| Person/link create | `workforce.person.linked` / `memberId, organizationPersonLinkId, linkType, lockVersion` | `workforce.member.draft_created` / `memberId, pathway, lockVersion` |
| Person correction | `workforce.person.corrected` / `memberId, changedFields, provenanceCode, lockVersion` | `workforce.member.changed` / `memberId, changeFamily, lockVersion` |
| Merge request/decision/execution | `workforce.person_merge.requested|approved|rejected|executed|cancelled` / `mergeRequestId, retainedLinkId, discardedLinkId, impactDigest, state` | `workforce.person_merge.executed` only / `mergeRequestId, retainedMemberId, affectedReferenceCount` |
| Member update | `workforce.member.updated` / `memberId, changedFields, lockVersion` | `workforce.member.changed` / `memberId, changeFamily, lockVersion` |
| Workforce identifier | `workforce.identifier.created|updated|verified|revoked|superseded` / `memberId, identifierId, fromState, toState, lockVersion` | Same names/keys; normalized value excluded. |
| Engagement | `workforce.engagement.created|scheduled|activated|suspended|ended|cancelled` / `memberId, engagementId, fromState, toState, effectiveFrom, effectiveTo, lockVersion` | Same names/keys. |
| Practitioner profile | `practitioner.profile.created|activated|suspended|ended` / `memberId, practitionerId, professionVersionId, fromState, toState, lockVersion` | Same names/keys. |

### Qualification, registration, credential, and document events

| Operation family | Audit event v1 / required payload | Outbox event v1 / required payload |
| --- | --- | --- |
| Qualification | `credential.qualification.created|submitted|verified|rejected|returned|superseded` / `memberId, qualificationId, fromState, toState, revision` | `credential.qualification.changed` / `memberId, qualificationId, toState, revision` |
| Registration | `credential.registration.created|submitted|verified|suspended|revoked|expired|superseded` / `practitionerId, registrationId, fromState, toState, expiryDate, revision` | Same names/keys; number/authority free text excluded, registry IDs used. |
| Credential draft/submission | `credential.record.created|updated|submitted|returned` / `practitionerId, credentialId, fromState, toState, evidenceCount, revision` | `credential.record.submitted|returned` / `practitionerId, credentialId, toState, revision` |
| Upload/quarantine | `credential.document.intent_created|uploaded|quarantined` / `credentialId, documentId, declaredType, declaredSize, digest, state` | `credential.document.quarantined` / `credentialId, documentId, digest, state` |
| Scan | `credential.document.scan_started|clean|infected|invalid|failed` / `credentialId, documentId, scanAttemptId, scannerPolicyVersion, outcome, failureCode` | `credential.document.clean|rejected` / `credentialId, documentId, digest, outcome` |
| Evidence preview | `credential.document.accessed` / `credentialId, documentId, accessIntentId, purposeCode, evidenceDigest` | None. Signed access data is excluded. |
| Review claim/release | `credential.review.claimed|released|lease_expired` / `credentialId, queueItemId, reviewerId, leaseExpiry, state` | None; a claim is not a business decision. |
| Verification decision | `credential.review.verified|rejected|more_information_required|returned_for_correction` / `practitionerId, credentialId, verificationId, decisionCode, evidenceDigest, policyVersion, revision` | `credential.review.decided` / `practitionerId, credentialId, verificationId, decisionCode, revision` |
| Credential lifecycle | `credential.record.suspended|revoked|expired|superseded` / `practitionerId, credentialId, fromState, toState, effectiveTime, revision` | Same names/keys. |
| Legal hold | `credential.legal_hold.imposed|released` / `credentialId, legalHoldId, authorityCode, state, evidenceDigest` | `credential.legal_hold.changed` / `credentialId, legalHoldId, state` |

### Specialty, scope, assignment, availability, and access events

| Operation family | Audit event v1 / required payload | Outbox event v1 / required payload |
| --- | --- | --- |
| Specialty | `practitioner.specialty.scheduled|activated|ended|superseded` / `practitionerId, specialtyId, specialtyVersionId, designation, effectiveFrom, effectiveTo` | `practitioner.specialty.changed` / same keys excluding designation only when unnecessary to consumers. |
| Scope draft/submission | `practitioner.scope.created|updated|submitted` / `practitionerId, scopeId, definitionVersionId, fromState, toState, resultDigest, revision` | `practitioner.scope.submitted` / `practitionerId, scopeId, definitionVersionId, resultDigest, revision` |
| Scope decision | `practitioner.scope.approved|rejected|changes_requested` / `practitionerId, scopeId, decisionId, decisionCode, resultDigest, policyVersion, revision` | `practitioner.scope.decided` / `practitionerId, scopeId, decisionId, decisionCode, revision` |
| Scope lifecycle | `practitioner.scope.suspended|ended|superseded` / `practitionerId, scopeId, fromState, toState, effectiveTime, revision` | Same names/keys. |
| Workforce assignment | `workforce.assignment.created|scheduled|activated|suspended|reactivated|ended|cancelled` / `memberId, assignmentId, contextType, contextId, fromState, toState, effectiveFrom, effectiveTo, revision` | Same names/keys. |
| Assignment transfer | `workforce.assignment.transferred` / `memberId, predecessorId, successorId, impactDigest, effectiveTime` | Same name/keys. |
| Service assignment | `practitioner.service_assignment.created|scheduled|activated|suspended|ended|cancelled` / `practitionerId, serviceAssignmentId, serviceId, contextId, eligibilityEvidenceId, fromState, toState, revision` | Same names/keys. |
| Eligibility | `practitioner.eligibility.evaluated|invalidated` / `practitionerId, contextId, resultId, outcome, resultDigest, expiryTime` | Same names/keys. No underlying sensitive reason values. |
| Availability | `workforce.availability.scheduled|activated|superseded|cancelled` / `memberId, profileId, timezone, effectiveFrom, intervalCount, exceptionCount, revision` | Same names/keys. Intervals are excluded. |
| Account-link request | `workforce.account_link.requested|cancelled|completed` / `memberId, requestId, linkMode, state, membershipId` | `workforce.account_link.requested` only / `memberId, requestId, linkMode` |
| Access mutation | Existing approved M1 identity membership/invitation/MFA/owner event names and schemas | Existing M1 mappings; Module 2 does not duplicate them. |

### Readiness, lifecycle, configuration, notification, and export events

| Operation family | Audit event v1 / required payload | Outbox event v1 / required payload |
| --- | --- | --- |
| Readiness | `workforce.readiness.completed|failed|invalidated` / `memberId, runId, pathway, resultDigest, blockerCount, warningCount, expiresAt, failureCode` | `workforce.readiness.invalidated` only / `memberId, runId, invalidationCode` |
| Activation submit/decision | `workforce.activation.submitted|approved|rejected|expired|invalidated` / `memberId, activationRequestId, runId, resultDigest, decisionCode, state` | Same names/keys except `expired` may be internal-only. |
| Activation | `workforce.member.activated` / `memberId, activationRequestId, resultDigest, effectiveTime, revision` | Same name/keys. |
| Suspension/reactivation | `workforce.member.suspended|reactivated` / `memberId, transitionId, categoryCode, impactDigest, effectiveTime, revision` | Same names/keys. Protected reason excluded. |
| Offboarding | `workforce.offboarding.requested|approved|scheduled|started|completed|failed|cancelled` / `memberId, offboardingRequestId, impactDigest, effectiveTime, state, failureCode` | `workforce.offboarding.approved|completed|failed` / `memberId, offboardingRequestId, impactDigest, effectiveTime, state` |
| Expiry milestone | `credential.expiry.milestone_reached|reminder_suppressed|escalated` / `credentialId, milestone, expiryDate, notificationId, outcomeCode` | `credential.expiry.milestone_reached` / `credentialId, milestone, expiryDate, notificationId` |
| Notification | `workforce.notification.queued|delivered|failed|suppressed` / `notificationId, templateVersion, channel, milestone, attempt, state, failureCode` | No further outbox; provider adapter evidence only. |
| Registry change | `workforce.registry.created|submitted|approved|rejected|activated|superseded` / `definitionId, entryId, versionId, changeRequestId, fromState, toState, resultDigest` | `workforce.registry.activated` / `definitionId, entryId, versionId, previousVersionId, resultDigest` |
| Configuration snapshot | `workforce.configuration.activated|superseded` / `snapshotId, previousSnapshotId, changeRequestId, resultDigest, effectiveTime` | Same names/keys. |
| History/audit restricted access | `workforce.evidence.accessed` / `evidenceFamily, evidenceId, projection, purposeCode, redactionPolicyVersion` | None. |
| Export request/decision | `workforce.export.requested|authorized|denied` / `exportId, projection, format, filterDigest, purposeCode, approvalId` | `workforce.export.authorized` only / `exportId, projection, format, filterDigest, purposeCode` |
| Export lifecycle | `workforce.export.completed|failed|accessed|expired|disposed` / `exportId, state, artifactDigest, rowCount, expiryTime, failureCode` | `workforce.export.expired|disposal_requested` / `exportId, artifactDigest, expiryTime` |

### Consumers and delivery

| Consumer | Exact subscriptions and purpose |
| --- | --- |
| `m2-readiness-invalidator-v1` | All member/person/engagement/practitioner/credential/scope/assignment/access/availability/registry material-change events; invalidates affected readiness/activation inside tenant context. No external transport. |
| `m2-eligibility-evaluator-v1` | Registration, credential, scope, assignment, service, supervision, and registry material-change/expiry events; writes immutable point-in-time eligibility evidence. |
| `m2-expiry-projector-v1` | Verified registration/credential activated/superseded/expired events; maintains mutually exclusive expiry work and deduplication. |
| `m2-history-projector-v1` | Approved lifecycle, decision, configuration, and evidence events; builds minimum-necessary member/configuration history with inbox deduplication. |
| `m2-offboarding-worker-v1` | `workforce.offboarding.approved` only; fetches and revalidates the exact plan before due execution. |
| `m2-notification-worker-v1` | Authorized expiry milestone notifications only; resolves opaque destination under separate purpose/consent policy. |
| `m2-export-worker-v1` | `workforce.export.authorized` only; generates one bounded tenant snapshot artifact. |
| `m2-retention-worker-v1` | Credential/document or export disposal requests only; rechecks holds, policy, object digest, and access expiry. |

An outbox record is not permission to publish. Each configured destination requires exact consumer identity, event/version allowlist, purpose, transport authentication, data classification, retention, retry/dead-letter owner, and replay procedure. Candidate 1 permits no analytics, warehouse, messaging, regulator, cross-organization, or external clinical destination. Email notification is a separate adapter and contains only approved minimal template data.

Audit evidence, decisions, and lifecycle attribution are retained seven years after member offboarding or source supersession by candidate default, subject to a longer jurisdiction schedule/legal hold. Document content follows its separately approved retention class. Delivered outbox evidence is retained one year; dead-letter/replay decisions seven years. Retention never authorizes broader visibility.

## Verification and acceptance

- Schema tests reject missing/extra keys, wrong type/bounds, nested payload drift, unknown/retired versions, prohibited sensitive fragments, wrong subject/aggregate, and transaction-context mismatch.
- Transaction tests prove state/audit/outbox/idempotency atomicity, exact replay, changed replay rejection, rollback, and direct-SQL append-only protection.
- Consumer tests prove event/version/identity allowlists, payload digest, forced RLS, inbox exact-redelivery suppression, changed-content conflict, retries/dead letter, and governed replay without double state changes.
- Privacy/security tests inspect logs, audit, outbox, dead letters, traces, and notification requests for raw identity/contact/registration/document/provider data.

## Approval boundary

This registry fixes candidate names, payloads, consumers, and retention for review. It activates no producer, publisher, worker, notification, or destination. Each requires the checksum-bound package plus owner and operational approval.
