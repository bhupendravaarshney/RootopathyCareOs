# Governance evidence, outbox, idempotency, and consumer inbox boundary

## Implemented mechanics

Flyway `V6__governance_evidence_and_delivery.sql` and the `governance` backend module establish the policy-neutral mechanics for retryable governed mutations:

1. `TenantAuthorizationOperations` revalidates membership and permission and opens the tenant-bound transaction.
2. `JdbcIdempotencyOperations` atomically acquires an actor/organization/operation/key tuple or returns its completed response.
3. The business callback runs only for the first request.
4. `JdbcGovernanceEvidenceOperations` writes one audit record and one outbox record in that same transaction.
5. The idempotency response is completed in that transaction. Any exception rolls back the business change, audit, outbox, and replay record together.

`GovernedMutationExecutor` is the application entry point for this transaction shape. Calling the evidence, idempotency, or delivery adapters without a live writable `AuthorizedTenantContext` transaction fails closed.

Flyway V15, V17, and V18 establish the first HTTP workflow mechanics. V20 promotes the bounded organization-profile event and rebinds invitation issue/revocation/acceptance plus MFA reset request/approval/execution to active, approved `m1-candidate-1` operation/event mappings. V22 adds governed non-owner membership changed/revoked evidence, and V23 adds final `identity.owner.transferred` evidence. Each governed mutation commits its state change, exact replay response, audit row, and outbox row atomically. Retained reference entries remain ignored unless non-production reference policy is explicitly enabled.

Flyway `V9__consumer_inbox_deduplication.sql` adds the complementary policy-neutral transaction shape for at-least-once consumption:

1. `ConsumerInboxExecutor` asks `TenantAuthorizationOperations` to revalidate the worker principal's membership and approved permission and open the tenant-bound transaction.
2. `PostgresConsumerInboxOperations` validates an active migration-owned consumer/event-version mapping, the event aggregate type, and the existing event definition's required/allowed top-level payload keys.
3. PostgreSQL-canonical JSON is hashed with SHA-256; the receipt stores that digest and bounded envelope metadata, not a second payload copy.
4. A unique `(organization_id, consumer_key, source_event_id)` receipt is acquired before the callback. PostgreSQL serializes simultaneous attempts.
5. The first callback runs in that transaction. Failure rolls back the effect and receipt; exact redelivery returns the original receipt without rerunning the callback; changed content conflicts.

This executor is a reusable boundary only. No message subscription, worker, transport authentication, broker acknowledgement, production consumer definition, or business effect is registered.

## Database invariants

- `audit_event_definitions` and `outbox_event_definitions` are migration-owned and runtime-read-only. V20 activates the approved invitation, MFA-administration, and organization-profile mappings while retaining obsolete reference history; V22/V23 add exact membership-change/revocation/owner-transfer mappings. Future approved catalogue entries grant no event authority until an exact operation mapping is implemented.
- Unknown or retired event/version pairs, incorrect subject/aggregate types, missing required payload keys, unapproved extra payload keys, and mismatched actor/organization/purpose/correlation metadata are rejected by PostgreSQL.
- The `payload_schema` document is stored with every event definition. V6 enforces object size and top-level required/allowed keys; a full JSON Schema validator must be selected before nested schema rules are claimed as enforced.
- General audit records are append-only, including for the table owner.
- Outbox event content is immutable and rows cannot be deleted. Only lease acquisition, publication acknowledgement, retry scheduling, and dead-letter transitions are accepted.
- Idempotency records are scoped to organization, actor, operation, and key. Completed records and unexpired replay evidence cannot be rewritten or deleted.
- `outbox_consumer_definitions` is migration-owned and runtime-read-only. It is intentionally empty in production migrations; unknown or retired consumer/event-version mappings fail before a callback executes.
- `consumer_inbox_records` uses forced RLS and a composite primary key scoped to tenant, consumer, and stable source event ID. Runtime receives only `SELECT` and `INSERT`; context/server-time triggers bind the original processor evidence, while update/delete is rejected even for the table owner.
- Consumer receipts retain event/version/aggregate/source-correlation/time metadata and a SHA-256 digest of PostgreSQL-canonical JSON. They deliberately do not copy payload content. The digest detects replay drift within this application boundary; transport authenticity and administrator compromise require separate controls.
- V9 startup checks reject an unsafe runtime role, table ownership, excess/missing grants, RLS/policy/trigger drift, incorrect key or registry foreign-key shapes, unavailable database time, or any inbox visibility without tenant context.
- V6 refuses to invent metadata for pre-existing rows in the three unused foundation tables. An installation with such rows needs an explicit reviewed forward migration before V6 is applied.

## Delivery semantics

`OutboxPublisherService` provides bounded batch claims, `FOR UPDATE SKIP LOCKED`, unique claim tokens, leases, exponential retry delays, retry ceilings, safe error codes, publication acknowledgement, and dead-lettering. Publication occurs after the claim transaction commits, so delivery is intentionally at least once. Destination adapters must transmit the stable outbox event ID. A CareOS consumer must enter `ConsumerInboxExecutor` with that ID before performing a governed effect; a remote consumer needs an equivalent reviewed inbox guarantee.

The publisher is intentionally not registered as a scheduled Spring component. Activating it requires all of the following:

- an approved and provisioned non-interactive service identity and permission using the V16 authorization boundary;
- an approved tenant-discovery/job-dispatch mechanism;
- a destination-specific `OutboxTransportPort` adapter with security and contract tests;
- approved consumer definitions and destination/subscription wiring that use the inbox boundary;
- monitoring, alerting, replay, retention, and dead-letter operating procedures.

This prevents a local placeholder transport or a human browser session from silently becoming production worker authorization.

## Required registry approval

Each audit event version needs an approved event name, display name, description, subject type, reason requirement, required and allowed top-level payload keys, complete payload schema, status, and registry version. Each outbox event version needs the corresponding aggregate type and schema fields plus an approved destination, classification, retention, compatibility, and consumer ownership decision. Each consumer entry needs an approved consumer key, exact event/version mapping, description, owner, permission/purpose, compatibility behavior, and registry version; V9 stores the mechanical mapping but does not invent the owner decisions.

Registry content must be added only through a reviewed Flyway migration. Application runtime credentials have `SELECT` only and cannot create or relax policy.

## Verified failure paths

The disposable PostgreSQL 18 suite covers atomic commit and rollback, replay, conflicting request hashes, concurrent identical requests, expired-key reuse, unknown events, payload drift, calls outside an authorized transaction, runtime registry mutation, owner-level audit/outbox/idempotency tampering, successful publication, retry, permanent failure, retry exhaustion, and dead-lettering. V9 coverage additionally proves consumer replay/deduplication and tenant isolation. V15/V17 coverage proves exact mapped evidence for invitation and MFA maker-checker transitions, including rollback, idempotent replay, expiry, wrong actor/subject/reason, cross-tenant access, and evidence-tampering attacks. V18 coverage proves one profile revision produces exactly one audit/outbox pair, exact replay produces no duplicate evidence, conflicting replay and stale ETags fail, and direct SQL cannot bypass the authorized operation context. V22/V23 coverage proves exact non-owner change/revocation and final owner-transfer payloads commit only with their approved membership transition, replay creates no duplicate evidence, and direct SQL cannot bypass approval or final-owner guards.
