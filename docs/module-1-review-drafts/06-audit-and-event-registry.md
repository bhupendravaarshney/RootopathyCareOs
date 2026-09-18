# Module 1 audit and event registry review brief

**Artifact kind:** `audit-and-event-registry`  
**Status:** `DRAFT_NOT_APPROVED`  
**Draft version:** `review-draft-1`  
**Approval authority:** Owner decision required

## Proposed review baseline

The implemented governance mechanism atomically commits business state, idempotency outcome, one append-only audit record, and one immutable outbox record for mapped governed mutations. Migration-owned registries reject unknown/retired versions, wrong subject or aggregate type, missing required payload keys, unapproved extra keys, and transaction-context drift. This mechanism is reusable; its reference event content is not production approval.

The current provisional organization update maps operation `organization.profile.update` to audit and outbox event `organization.profile.updated` version 1 with required payload keys `changedFields` and `lockVersion`. Owners must accept, replace, or split that reference definition.

### Registry record required for every event version

| Attribute     | Audit decision                                                                                              | Outbox decision                                                                                    |
| ------------- | ----------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| Identity      | Stable event name, schema version, display name, registry version, status, owner.                           | Stable event name, schema version, registry version, status, producer owner.                       |
| Subject       | Subject type/ID, organization, actor, purpose, reason requirement, correlation, occurred/recorded time.     | Aggregate type/ID, organization, producer operation, correlation, occurred/recorded time.          |
| Payload       | Required payload keys, allowed payload keys, complete nested schema, types, bounds, sensitivity, redaction. | Required payload keys, allowed payload keys, complete nested schema, classification, minimization. |
| Compatibility | Version increment rules, reader behavior, retirement, historical readability, correction/supersession.      | Backward/forward compatibility, destination contract, consumer migration, replay behavior.         |
| Operations    | Retention, access, search/filter projection, legal hold, support use.                                       | Destination, tenant dispatch, delivery SLA, retry/dead-letter, replay authorization, retention.    |

### Candidate M1 governed event families for review

Exact names, versions, payloads, and whether both audit and outbox are required remain owner decisions:

- organization profile, identifier, address, contact, international-setting, and governance-contact created/updated/superseded/ended;
- facility, department/unit, and location drafted/updated/activated/suspended/reactivated/closed/reparented;
- operating-hours and holiday batch scheduled/activated/superseded/cancelled;
- service definition activated/updated/retired and facility/location service assignment started/suspended/ended;
- identifier scheme/version activated/superseded/retired and sequence-allocation policy changed;
- invitation issued/revoked/accepted, membership role/scope changed, membership revoked, final-owner transferred, and administrative MFA reset lifecycle events;
- configuration validation completed, submission created, approval granted/rejected/expired, activation completed/failed, and version superseded;
- history/audit export requested/authorized/completed/failed/accessed/expired/disposed.

### Payload minimization baseline

- Prefer stable opaque IDs, changed-field names, revisions, state transitions, effective times, bounded decision codes, and evidence references.
- Do not copy passwords, tokens, session/CSRF values, MFA secrets/recovery codes, raw document locations, signed URLs, recipient destinations, free-text clinical data, or full before/after records.
- Reason text requires an approved bound, sensitivity classification, retention/redaction rule, and minimum-necessary projection.
- Outbox consumers receive only fields needed for the approved purpose. Audit visibility may be narrower than record visibility.
- Nested schema enforcement must be selected and tested before the repository claims more than its current top-level key enforcement.

### Consumer and delivery decisions

Every outbox mapping needs an approved destination and consumer owner. Each consumer definition must bind one consumer key to exact event/version, aggregate type, permission, purpose, compatibility policy, retry/dead-letter ownership, and transaction-bound inbox behavior. A persisted outbox event is not permission to publish, and a stored consumer receipt is not transport authenticity.

## Owner decisions required

1. Approve exact audit and outbox event names, versions, subject/aggregate types, required payload and allowed payload keys, full schemas, classifications, and owners.
2. Approve which mutations emit audit only versus audit plus outbox; justify any governed mutation without both.
3. Approve reason handling, sensitive-field omission/redaction, event access, search projections, retention, legal hold, correction, and disposal.
4. Approve destination, consumer, non-interactive identity, purpose, compatibility, retry, dead-letter, replay, monitoring, and incident ownership.
5. Select nested JSON Schema enforcement and migration/compatibility rules before complex payloads are activated.

## Acceptance checklist

- [ ] Every approved governed operation maps to exact migration-owned audit/outbox definitions.
- [ ] Required/allowed keys and full schemas reject drift without exposing sensitive source records.
- [ ] Atomic commit/rollback, exact replay, changed replay, concurrency, unknown event, wrong context, and direct-SQL tampering tests are defined.
- [ ] Consumer inbox, transport authentication, retry/dead-letter, governed replay, retention, and monitoring ownership are complete before activation.
- [ ] Product, security, privacy, governance, integration, operations, and data owners record review outcomes.
- [ ] A named authority records approval evidence for the exact checksum-bound bundle.
