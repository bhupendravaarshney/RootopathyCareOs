# Fail-closed platform capabilities

## Scope

Phase 0F establishes the activation boundary for infrastructure that must never gain an unsafe local or best-effort fallback. It defines framework-independent application ports, validated metadata records, one status probe per capability, explicit unavailable adapters, and non-secret status reporting.

This is a completed **fail-closed boundary**, not a claim that the production integrations exist. The Compose presence of object storage, Redis, Mailpit, or ClamAV does not make a corresponding application capability available. Phase 0G adds an opt-in quarantine adapter, Phase 0H adds an opt-in scanner adapter, Phase 0I adds an opt-in durable Redis job transport, Phase 0J adds an opt-in encrypted PostgreSQL notification store, and Phase 0R adds mandatory PostgreSQL quarantine/scan evidence mechanics. The four external adapters remain disabled by default; the evidence store does not activate document promotion/access/retention, outbound notification delivery, or worker execution.

## Capability registry

| Capability key                    | Application boundary             | Foundation state | Default reason code                              |
| --------------------------------- | -------------------------------- | ---------------- | ------------------------------------------------ |
| `private-document-quarantine`     | `PrivateDocumentStoragePort`     | Unavailable by default; opt-in adapter tested | `object-storage-adapter-not-configured` |
| `malware-scanning`                | `MalwareScannerPort`             | Unavailable by default; opt-in adapter tested | `malware-scanner-adapter-not-configured`         |
| `document-promotion`              | `DocumentPromotionPort`          | Unavailable      | `document-promotion-not-configured`               |
| `signed-document-access`          | `SignedDocumentAccessPort`       | Unavailable      | `signed-access-adapter-not-configured`            |
| `document-retention`              | `DocumentRetentionPort`          | Unavailable      | `retention-adapter-not-configured`                |
| `durable-notification-delivery`   | `DurableNotificationPort`        | Unavailable by default; opt-in persistence mechanics tested | `durable-notification-adapter-not-configured`     |
| `redis-job-queue`                 | `JobQueuePort`                   | Unavailable by default; opt-in adapter tested | `redis-job-adapter-not-configured` |
| `worker-execution`                | `WorkerExecutionPort`            | Unavailable      | `worker-execution-not-configured`                 |
| `scheduler-execution`             | `SchedulerExecutionPort`         | Unavailable      | `scheduler-execution-not-configured`              |

`PlatformCapabilityRegistry` requires exactly one `CapabilityProbe` for every known capability. A missing or duplicate registration prevents application-context construction. An unavailable adapter throws `PlatformCapabilityUnavailableException` before reading content or performing I/O; it never writes to local disk, skips scanning, sends best-effort mail, queues work in memory, or starts work on a web node. The S3 adapter reports private quarantine as `AVAILABLE` only after its storage checks. The ClamAV adapter independently reports malware scanning as `AVAILABLE` only after its protocol, engine-version, and definition-freshness checks. The Redis queue reports `AVAILABLE` only after property/allow-list validation, `PING`, and a scripting/server-time readiness check. The PostgreSQL notification adapter reports its storage/lease mechanics available only after strict property, database-role, table, forced-RLS, privilege, missing-context visibility, and server-time checks. Enabling any one capability never enables another.

`/actuator/info` publishes only the capability key, `available`/`unavailable` state, and stable reason code. It never publishes endpoints, buckets, destinations, credentials, payloads, or exception text. Foundation health can remain `UP` while optional capabilities are unavailable; a deployment must add a real readiness dependency before enabling a workflow that requires one.

## Document contract

The document boundary separates five decisions that must be independently implemented and tested:

1. An already-authorized tenant uploads a declared size, media type, and lowercase SHA-256 digest into private quarantine using opaque document/version identifiers.
2. A scanner reads the quarantined object. Timeout, stale definitions, scanner failure, or an unreachable scanner must result in `ERROR`, never `CLEAN`.
3. Promotion accepts only matching, current `CLEAN` evidence. A storage copy by itself is not promotion evidence.
4. Signed access is read-only, short-lived, tenant-authorized, and limited to a promoted clean object. Bucket and storage keys stay inside the adapter.
5. Retention applies an approved policy key and must preserve legal holds. Disposal is a governed mutation with audit evidence, not a raw storage delete.

Phase 0G implements step 1 and Phase 0H implements the transport/integrity mechanics of step 2, each behind an explicit configuration switch. Phase 0R durably records the exact quarantine metadata and every scanner observation through a transaction-bound coordinator. No business document/provenance state machine, scan-freshness policy, promotion state transition, URL signer, or retention executor is wired.

## Private quarantine adapter checkpoint

The MinIO Java client is used as an S3-compatible transport. Activation requires all storage credentials and settings and fails startup unless:

- the endpoint is an absolute HTTPS origin, or HTTP has been explicitly allowed for local/test use;
- the dedicated quarantine bucket already exists, unless local/test runtime creation is explicitly enabled;
- the runtime identity can inspect the bucket and the bucket has no bucket policy;
- the configured upload ceiling is positive and the bucket name and credentials pass bounded validation.

Object keys are derived inside the adapter as `organizations/{organizationId}/documents/{documentId}/versions/{objectVersionId}/quarantine`. Callers cannot supply keys or filenames. Writes use conditional creation, stream through an exact-length SHA-256 verifier, remove content that fails post-upload verification, and accept an existing object only after downloading and matching its byte length, media type, and digest. This supports a retry after a surrounding transaction failure without allowing different content to overwrite the same version.

The adapter is disabled by default. The pinned MinIO server digest in Compose and tests is a synthetic compatibility target only: it is not an approved production provider or deployment. Production activation still requires provider selection, separate provisioning credentials, least-privilege runtime IAM, account-level public-access controls, TLS/network policy, KMS-backed encryption, versioning/object-lock decisions, monitoring, backup/restore, and security/operational acceptance. The storage adapter itself has no HTTP upload route and creates no database record; `DocumentSecurityOperations` is the separate application boundary that records V8 evidence after storage acceptance.

## Malware scanner adapter checkpoint

`ClamAvMalwareScannerAdapter` reads only through `QuarantinedDocumentContentSourcePort`; no API package may depend on that stream boundary. The S3 implementation rechecks organization ownership, quarantine state, bounded size, expected SHA-256, and ETag before opening a conditional object read. The scanner then independently enforces the exact byte count and recomputes SHA-256 while streaming, so a daemon `OK` is insufficient by itself.

Activation requires an explicit host/port, bounded connect and read timeouts, a total scan ceiling, a signature-age ceiling, and an explicit time zone for ClamD's zone-less database timestamp. Startup fails unless `PING` returns `PONG`, `VERSIONCOMMANDS` has the expected bounded format and advertises `INSTREAM`, the engine is at least the current security floor of 1.5.3, and definitions are not stale or implausibly in the future. The identity check is repeated for every scan so a long-running instance cannot keep producing clean verdicts from definitions that have aged beyond policy.

Content uses recommended NUL command framing and four-byte network-order chunk lengths. Only exact `stream: OK` plus matching local size/digest produces `CLEAN`; a non-empty `... FOUND` response produces `INFECTED`; every other response, timeout, socket/storage failure, oversize object, or integrity mismatch produces `ERROR`. The result carries a bounded scanner key, database version, observed-or-explicitly-unknown digest, and timestamp. A raw return value alone is not evidence; `DocumentSecurityOperations` persists it before returning a `DocumentScanAttestation`.

Both adapters remain disabled in the default stack. `compose.scanner.yaml` is an optional local overlay that enables quarantine and scanning together and pins the official multi-architecture ClamAV 1.5.3 base image by digest. It does not publish port 3310. ClamD TCP has no authentication or encryption, so production requires trusted network segmentation, egress/ingress policy, current signature operations, resource sizing, metrics/alerts, and owner acceptance. Promotion must require an authoritative matching `CLEAN` attestation and a still-unapproved freshness rule; V8 persistence alone is not permission to promote.

## PostgreSQL document-security evidence checkpoint

Flyway V8 adds `document_quarantine_evidence` and `document_scan_attestations`. Both tables use forced RLS and the canonical transaction-local organization setting. The runtime role receives only `SELECT` and `INSERT`; insert triggers bind organization, actor, purpose, and correlation to the authorized transaction and replace record timestamps with PostgreSQL server time. Update and delete triggers reject mutation even when normal table privileges would otherwise allow it. Scan rows have a composite `(organization_id, document_id, object_version_id)` foreign key to the exact quarantine evidence row.

The evidence adapter initializes only after migrations and refuses an unsafe superuser/`BYPASSRLS`/owner runtime, missing forced RLS, policies, insert/immutability triggers, the composite foreign key, excess update/delete privileges, or any evidence visibility without tenant context. Every operation also verifies the active writable transaction against its `AuthorizedTenantContext` before reading or writing.

Exact quarantine retries return the original evidence; the same object identity with changed size, media type, or digest conflicts. Scan observations are append-only and deduplicate on tenant, object, scanner, and scanner timestamp. A contradictory replay at that identity conflicts. `CLEAN` and `INFECTED` must carry the quarantine digest. `ERROR` remains evidence even when the scanner could not observe content and returned the explicit unknown digest, but it can never qualify for promotion. Latest-observation reads are deterministically ordered and bounded to one row.

`DefaultDocumentSecurityOperations` checks the authorized evidence boundary before invoking storage, accepts only the expected tenant/document/object-version reference, and records evidence after the external adapter returns. A transaction rollback removes database evidence; the S3 adapter's verified exact-object replay makes a later retry safe if the object already exists. Scanning is refused without quarantine evidence, and API packages are prohibited from depending directly on storage, scanner, or evidence-store ports.

This is infrastructure evidence, not the M7 document model. It creates no upload or download endpoint, document owner/subject/provenance model, approved permission/event, freshness policy, promotion record, signed URL, retention/legal hold, audit/outbox workflow, or provider acceptance. Those remain required before content can leave quarantine.

## Notification and job contract

`DurableNotificationPort` accepts an internal recipient identifier and a versioned template reference rather than a raw destination. Phase 0J implements atomic encrypted persistence, deduplication, leasing, bounded retries, dead letters, and retention mechanics. Recipient consent/destination resolution, provider invocation, and delivery governance remain deliberately absent. The existing password-reset SMTP path remains immediate and is not represented as durable delivery.

`JobQueuePort` accepts versioned, deduplicated work containing opaque references rather than clinical text or credentials. The Phase 0I adapter implements the policy-neutral Redis lifecycle described below. It is transport mechanics, not authorization to execute a job.

## PostgreSQL durable notification adapter checkpoint

Activation requires an explicit bounded `templateKey@templateVersion` allow-list; one to eight named 256-bit AES keys with an active key ID; and bounded lease, attempt, retry, schedule, retention, claim-batch, and cleanup settings. Startup fails unless `durable_notifications` exists, forced RLS is enabled, the runtime role is neither superuser nor `BYPASSRLS` nor the table owner, the required table privileges exist, PostgreSQL server time is available, and the runtime role sees no rows without tenant context.

Flyway V7 stores the internal recipient UUID, encrypted parameters, SHA-256 payload/content/deduplication digests, request context, copied retry/retention policy, and lifecycle evidence. It has no email, phone, endpoint, or other destination column. Parameters use AES-256-GCM with a fresh 96-bit nonce and tenant/notification/recipient/template metadata as additional authenticated data. A claim decrypts only in application memory. Historical named keys may remain configured while a new active key encrypts new rows; a missing historical key fails closed and leaves work unclaimed.

Every enqueue, claim, acknowledgement, failure, snapshot, recovery, and cleanup call requires the same active writable `AuthorizedTenantContext` transaction used by governed tenant work. Forced RLS scopes every query. The database trigger binds enqueue origin metadata to that transaction, makes content and copied policy immutable, restricts claim/finalization transitions, preserves terminal evidence, and permits deletion only after the row's retention deadline.

Due selection uses deterministic ordering with `FOR UPDATE SKIP LOCKED`. The adapter issues a new opaque 256-bit lease for every attempt and stores only its SHA-256 hash. PostgreSQL server time controls due dates, lease expiry, exponential retry, exhaustion, and retention. Exact enqueue and acknowledgement replays are idempotent; changed identifiers/deduplication content, stale or cross-tenant claims, unapproved template versions, distant schedules, ciphertext/AAD/digest drift, and missing decryption keys cannot return parameters for delivery. Retry and retention policy is copied into every row so a later configuration change cannot silently rewrite active work. Payload-free tenant snapshots and bounded-cardinality Micrometer transition counters expose mechanics only.

The adapter is disabled by default, and the architecture rules prevent API packages from accessing notification claim/completion mechanics. It does not send a message and starts no loop. A future authorized non-interactive worker must claim a request, resolve current recipient consent and destination at delivery time, invoke an approved provider with its own idempotency contract, and record approved audit/outbox/delivery evidence. Production still requires approved templates and payload schemas, a key-management/rotation/re-encryption design, destination and consent policy, provider security and regional acceptance, monitoring/alerts, dead-letter inspection/replay, backup/restore, retention operations, and owner acceptance.

## Redis durable job adapter checkpoint

Activation requires an explicit bounded `jobType@schemaVersion` allow-list, key prefix, lease, attempt/retry, schedule, retention, claim-batch, cleanup-batch, and Redis connection/command timeouts. Startup fails unless Redis returns exact `PONG` and can execute the server-time readiness script. No job definition is accepted merely because an infrastructure service is reachable.

All keys are derived from the authorized organization and share one Redis Cluster hash tag. Deduplication keys are SHA-256-derived before indexing. Bounded Lua scripts use Redis server time and atomically perform enqueue, due-job claim, acknowledgement, retry, expired-lease recovery, maximum-attempt dead-lettering, and terminal cleanup. Exact duplicate enqueue and acknowledgement are idempotent; conflicting identifiers/deduplication keys, stale or cross-tenant leases, distant schedules, unapproved versions, corrupt retained records, and dependency failures are rejected without delivery. A SHA-256 payload digest detects retained-record drift before a claim is returned; it is not a trust boundary against an operator able to rewrite Redis data.

Retry/attempt/retention policy is copied into each enqueued record so a later configuration change cannot silently alter active work. Payload-free per-tenant depth snapshots and bounded-cardinality Micrometer transition counters provide mechanics-level telemetry. Completed and dead-letter records remain deduplicated for the configured retention window and are purged incrementally; no global Redis key scan is used.

The adapter is disabled by default and no API package may claim or complete a background job. `WorkerExecutionPort` and `SchedulerExecutionPort` remain unavailable. Production activation still requires approved job definitions, authenticated non-interactive worker identity, operation-specific tenant authorization, governed business evidence, Redis ACL/TLS/HA/persistence and restore acceptance, capacity limits, alerts, and dead-letter inspection/replay procedures.

Compose and all Redis-backed integration suites use one pinned official multi-architecture Redis 8 Alpine digest. This makes the synthetic compatibility target reproducible; it is not approval of a production Redis topology or operating model.

Worker and scheduler ports require an `AuthorizedTenantContext`. They cannot be activated until a separately authenticated non-interactive identity can enter the same tenant authorization boundary as browser work. No `@Scheduled` task or placeholder worker is started by the web application, so making notification persistence or a queue available cannot cause delivery or job execution.

## Adapter activation checklist

The foundation status probe reports whether an intentionally enabled external adapter's checked mechanics are ready; it is not a production-acceptance or workflow-readiness signal. The mandatory document-evidence store instead fails application startup if its PostgreSQL security contract is absent. Before a production workflow may rely on a capability, all applicable items below must also be complete:

- The concrete adapter implements both its application port and `CapabilityProbe`; replacing a port without a probe remains a startup error.
- Owner-approved retention, notification, job, service-identity, and event policies have stable versioned registry entries where required.
- Tenant authorization and governed mutation evidence wrap every state-changing operation.
- Secrets come from the approved secret/key-management system; payloads, destinations, tokens, and clinical text are absent from logs and status endpoints.
- Integration tests cover tenant isolation, malformed metadata, dependency outage, timeout, retry, duplicate delivery, lease loss, restart recovery, and terminal failure.
- Deployment readiness, metrics, alerting, replay/dead-letter procedures, backup/restore, and operational ownership are defined.

Until then, the checked default configuration remains `UNAVAILABLE`, and an opt-in mechanics result must not be treated as authorization to enable a production workflow.
