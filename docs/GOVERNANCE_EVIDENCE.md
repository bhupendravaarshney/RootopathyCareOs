# Governance evidence, outbox, and idempotency boundary

## Implemented mechanics

Flyway `V6__governance_evidence_and_delivery.sql` and the `governance` backend module establish the policy-neutral mechanics for retryable governed mutations:

1. `TenantAuthorizationOperations` revalidates membership and permission and opens the tenant-bound transaction.
2. `JdbcIdempotencyOperations` atomically acquires an actor/organization/operation/key tuple or returns its completed response.
3. The business callback runs only for the first request.
4. `JdbcGovernanceEvidenceOperations` writes one audit record and one outbox record in that same transaction.
5. The idempotency response is completed in that transaction. Any exception rolls back the business change, audit, outbox, and replay record together.

`GovernedMutationExecutor` is the application entry point for this transaction shape. Calling the evidence, idempotency, or delivery adapters without a live writable `AuthorizedTenantContext` transaction fails closed.

## Database invariants

- `audit_event_definitions` and `outbox_event_definitions` are migration-owned and runtime-read-only. They are intentionally empty in production migrations until the owner approves canonical entries.
- Unknown or retired event/version pairs, incorrect subject/aggregate types, missing required payload keys, unapproved extra payload keys, and mismatched actor/organization/purpose/correlation metadata are rejected by PostgreSQL.
- The `payload_schema` document is stored with every event definition. V6 enforces object size and top-level required/allowed keys; a full JSON Schema validator must be selected before nested schema rules are claimed as enforced.
- General audit records are append-only, including for the table owner.
- Outbox event content is immutable and rows cannot be deleted. Only lease acquisition, publication acknowledgement, retry scheduling, and dead-letter transitions are accepted.
- Idempotency records are scoped to organization, actor, operation, and key. Completed records and unexpired replay evidence cannot be rewritten or deleted.
- V6 refuses to invent metadata for pre-existing rows in the three unused foundation tables. An installation with such rows needs an explicit reviewed forward migration before V6 is applied.

## Delivery semantics

`OutboxPublisherService` provides bounded batch claims, `FOR UPDATE SKIP LOCKED`, unique claim tokens, leases, exponential retry delays, retry ceilings, safe error codes, publication acknowledgement, and dead-lettering. Publication occurs after the claim transaction commits, so delivery is intentionally at least once. Destination adapters must transmit the stable outbox event ID and consumers must deduplicate on that ID.

The publisher is intentionally not registered as a scheduled Spring component. Activating it requires all of the following:

- an approved non-interactive service identity and permission;
- an approved tenant-discovery/job-dispatch mechanism;
- a destination-specific `OutboxTransportPort` adapter with security and contract tests;
- consumer-side inbox/deduplication persistence;
- monitoring, alerting, replay, retention, and dead-letter operating procedures.

This prevents a local placeholder transport or a human browser session from silently becoming production worker authorization.

## Required registry approval

Each audit event version needs an approved event name, display name, description, subject type, reason requirement, required and allowed top-level payload keys, complete payload schema, status, and registry version. Each outbox event version needs the corresponding aggregate type and schema fields plus an approved destination, classification, retention, compatibility, and consumer ownership decision.

Registry content must be added only through a reviewed Flyway migration. Application runtime credentials have `SELECT` only and cannot create or relax policy.

## Verified failure paths

The disposable PostgreSQL 18 suite covers atomic commit and rollback, replay, conflicting request hashes, concurrent identical requests, expired-key reuse, unknown events, payload drift, calls outside an authorized transaction, runtime registry mutation, owner-level audit/outbox/idempotency tampering, successful publication, retry, permanent failure, retry exhaustion, and dead-lettering.
