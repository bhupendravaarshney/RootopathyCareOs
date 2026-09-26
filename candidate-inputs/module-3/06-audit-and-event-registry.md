# Module 3 audit and event registry candidate

**Artifact kind:** `audit-and-event-registry`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m3-candidate-1`  
**Approval:** Not granted

## Decision baseline

M3 reuses the canonical append-only audit evidence and transactional outbox. Every governed mutation writes domain state, idempotency result, audit record and any allow-listed outbox event in one tenant transaction. An audit record is evidence, not a general clinical history; no screen or export exposes a raw audit payload. An outbox event is integration intent, not authorization to execute its effect.

All events below are **version 1** candidate registrations. Runtime code cannot publish an unregistered event/version, and consumers accept only an exact migration-owned producer/event/version contract. Payloads use opaque IDs, safe states, bounded codes, revisions/digests and correlation—not raw patient data.

### Required audit envelope

Every mutation records event name/version, organization, actor/service identity, authenticated user where applicable, delegated/proxy context reference where applicable, purpose, exact operation, subject/resource opaque IDs, source/target state, effective time, expected/result revision, idempotency reference, reason code/reference, assurance class/time, maker/checker/executor references where applicable, policy/configuration/detector versions, outcome, correlation/trace IDs and server recorded time.

Protected reason/evidence content is referenced, not copied. Failed authorization/search attempts use safe event families and bounded dimensions without storing queried raw names, identifiers, contacts or match inputs.

### Canonical patient and registration events

| Event (version 1)                           | Trigger                                  | Required payload beyond envelope                                                           | Outbox                                                      |
| ------------------------------------------- | ---------------------------------------- | ------------------------------------------------------------------------------------------ | ----------------------------------------------------------- |
| `patient.registration.started`              | New registration run                     | registration ID, source code, expiry, initial step                                         | No                                                          |
| `patient.registration.search_completed`     | Bounded duplicate search                 | registration ID, detector version, factor categories, candidate count/bands, result digest | No                                                          |
| `patient.registration.disposition_recorded` | Existing/new/escalate/urgent choice      | registration ID, disposition, candidate ID if any, reason code, source revisions           | No                                                          |
| `patient.registration.completed`            | Atomic new/existing link result          | registration ID, patient ID, result kind, revision, validation digest                      | Yes: `m3.patient.registered.v1` only for new active patient |
| `patient.identity.corrected`                | Governed identity/demographic correction | patient ID, changed field codes, predecessor/successor references, provenance class        | Yes: `m3.patient.identity-changed.v1`                       |
| `patient.lifecycle.changed`                 | inactive/reactive/deceased/error outcome | patient ID, prior/new state, effective time, reason/evidence reference                     | Yes when downstream invalidation is required                |

### Identifier, contact and preference events

| Event (version 1)            | Safe content                                                                                                                | Explicitly prohibited                                                            |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| `patient.identifier.changed` | patient/identifier IDs, scheme/version key, masked display suffix if approved, prior/new state, verification class, lineage | Raw/encrypted value, HMAC digest, national identifier, issuer provider response. |
| `patient.contact.changed`    | patient/contact ID, channel/use, state, verification class, primary/preferred change                                        | Raw phone/email/address, message destination.                                    |
| `patient.address.changed`    | patient/address ID, type/use, state, validation class, primary/preferred change                                             | Address lines, postcode or geolocation.                                          |
| `patient.preference.changed` | patient/preference ID, purpose/channel/language/format policy keys, state                                                   | Contact destination, consent claim, sensitive free text.                         |

### Authority, consent and privacy events

| Event (version 1)           | Trigger / safe content                                                                                      | Outbox consumer intent                                                                |
| --------------------------- | ----------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| `patient.proxy.requested`   | patient/authority IDs, relationship ref, authority type, scope digest, effective range, maker               | None until decision.                                                                  |
| `patient.proxy.decided`     | exact request/revision/digest, checker, decision/reason code, state                                         | `m3.patient.authority-changed.v1` invalidates portal/proxy projections.               |
| `patient.proxy.revoked`     | authority ID, revoker, reason, effective time                                                               | Authority invalidation plus exact portal-session revocation request.                  |
| `patient.consent.activated` | consent ID, purpose/action/data/actor policy keys, directive digest/reference, grantor authority ref, range | `m3.patient.consent-changed.v1` invalidates dependent policy results.                 |
| `patient.consent.withdrawn` | consent ID, prior revision, withdrawal effective time/reason                                                | Same; future dependent jobs/access re-evaluate.                                       |
| `patient.privacy.decided`   | request/restriction ID, policy/version, scoped consequence codes, checker/decision                          | `m3.patient.privacy-changed.v1` invalidates projections/exports/messages/integration. |

No event states that consent is the universal legal basis. No privacy event carries restricted field values or free-text evidence.

### Safety events

| Event (version 1)                  | Trigger                           | Safe payload                                                                                       |
| ---------------------------------- | --------------------------------- | -------------------------------------------------------------------------------------------------- |
| `patient.safety_flag.proposed`     | New candidate flag                | patient/flag IDs, category/severity codes, source reference type/ID, proposed visibility, deadline |
| `patient.safety_flag.provisional`  | Urgent provisional display        | flag revision, policy/version, verification deadline, author class                                 |
| `patient.safety_flag.verified`     | Eligible independent verification | flag/source revisions, verifier class/ID, active interval, review deadline                         |
| `patient.safety_flag.acknowledged` | Relevant workflow acknowledgement | flag revision, workflow/encounter reference if permitted, actor, time                              |
| `patient.safety_flag.resolved`     | Resolve/supersede/error           | prior/new state, effective time, reason code, successor ref                                        |

The concise flag code may be represented only where the audit projection policy permits. Detailed clinical narrative remains in the source record. Flags are not communication events.

### Duplicate and merge events

| Event (version 1)                 | Required evidence                                                                                                                    |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `patient.duplicate.detected`      | Candidate ID, ordered patient IDs, detector/version, band, factor category set, source revisions, digest, SLA—not raw factor values. |
| `patient.duplicate.claimed`       | Candidate/revision, reviewer, opaque lease reference/expiry.                                                                         |
| `patient.duplicate.dispositioned` | Candidate/revision/detector, disposition/reason, field/factor digest and reopen conditions.                                          |
| `patient.merge.requested`         | Request, survivor/duplicate, patient revisions, field-disposition digest, affected-reference digest, maker/expiry.                   |
| `patient.merge.decided`           | Exact request/revision/digests, independent checker, approve/reject, decision expiry.                                                |
| `patient.merge.executed`          | One-use decision, survivor/duplicate, pre/post revisions, reference-family counts, executor, lineage link and invalidation digest.   |
| `patient.merge.correction_opened` | Merge evidence ref, correction case, affected clinical-review flag, accountable owner; no routine unmerge claim.                     |

Merge execution emits `m3.patient.merged.v1` only after atomic commit. Consumers use survivor/duplicate opaque IDs and reauthorize their own affected references; they never rewrite clinical attribution from an unauthenticated message.

### Portal, communication, export and FHIR events

- `patient.portal_link.requested|activated|revoked|recovered` version 1: link/user/patient or authority opaque references, proofing policy/version/result digest, assurance and lifecycle. No token, evidence image or identity-provider assertion.
- `patient.communication.authorized|suppressed|delivery_recorded|dead_lettered` version 1: patient, purpose, channel, template/version, authority/preference/provider-policy digests, safe outcome and attempt count. No destination, message content or provider response body.
- `patient.export.requested|decided|ready|accessed|expired|disposed|failed` version 1: projection/purpose, normalized-filter digest, snapshot, row/byte counts, artifact digest/reference, actor/checker/access-grant reference. No signed URL, storage key or exported cells.
- `patient.fhir.exchange_requested|validated|applied|delivered|rejected|failed` version 1: partner/profile/package/base-version, purpose, resource type/count, bundle/content digest, provenance ref and safe validation code. No generic payload copy.

### Prohibited sensitive content

Audit/outbox/inbox, logs and metrics must not contain:

- names or aliases, birth date, gender/sex values, phone/email/address;
- raw/masked-more-than-approved patient or national/provider identifiers, match inputs/keys/scores, search strings;
- authority/consent/privacy evidence content, signatures, document names/content/keys/URLs;
- safety narrative, diagnosis, allergy, condition, safeguarding allegation or clinical free text;
- passwords, invitation/reset/recovery/session/CSRF/MFA/token/assertion material;
- notification destination/body/provider response, FHIR resource/bundle content or export rows;
- unrestricted request/response bodies, stack traces, SQL or before/after JSON.

### Consumers and invalidation

| Consumer                         | Accepted events                                                            | Exact effect / denied effect                                                                                      |
| -------------------------------- | -------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `m3-projection-invalidator-v1`   | Identity/contact/identifier/authority/consent/privacy/safety/merge changes | Invalidate named patient projections/policy results. Cannot mutate source facts.                                  |
| `m3-session-revoker-v1`          | Authority/portal-link revoke, compromise, merge policy signal              | Invoke canonical identity session revocation for exact linked account/context. Cannot create/restore access.      |
| `m3-match-refresh-v1`            | Approved identity/contact/identifier material change                       | Recompute allowed organization match factors and candidates. Cannot merge or cross tenants.                       |
| `m3-notification-coordinator-v1` | Exact authorized communication intent                                      | Reauthorize then persist one durable notification through platform port. Cannot select a new purpose/destination. |
| `m3-export-worker-v1`            | Authorized export job                                                      | Generate exact snapshot/projection to private artifact. Cannot approve or grant access.                           |
| `m3-retention-worker-v1`         | Exact policy-eligible disposal intent                                      | Recheck schedule/hold then dispose and attest. Cannot release hold.                                               |
| `m3-fhir-adapter-v1`             | Approved exact exchange request                                            | Validate/re-authorize/map selected profile. Cannot generic search or cross-org match.                             |

Consumers use transaction-bound inbox receipts keyed by tenant/consumer/source event. Exact redelivery is suppressed; changed content under the same event ID conflicts; callback failure rolls back receipt and retries. Unknown producer/event/version, missing authorization context or unavailable required provider fails closed and eventually dead-letters with safe evidence.

## Verification and acceptance

- Registry tests require every event/version and reject unknown/retired/duplicate entries, payload drift and runtime mutation.
- Atomicity tests prove domain/idempotency/audit/outbox commit or roll back together, including concurrent replay and failure injection.
- Payload allow-list tests seed sentinel sensitive values and prove their absence from database evidence, messages, logs, metrics and errors.
- Consumer tests cover source authorization, exact redelivery, changed-content conflict, transaction rollback, lease/retry/dead-letter/replay and tenant crossing.
- Correlation/timeline tests prove allow-listed projection and explicit evidence links without exposing raw payloads or asserting unsupported causality.

## Approval boundary

These event names, payload keys and consumers are candidate vocabulary. They do not register an event, activate a subscription or provision a service identity. Exact-package approval and later migration/runtime review are required.
