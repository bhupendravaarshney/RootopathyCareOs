# Build status

## Verified in the delivery environment

- Frontend dependency installation
- Strict TypeScript
- ESLint with zero warnings
- Vitest component/registry tests
- Vite production build
- npm dependency audit
- Static 79-screen register contract: M1 23, M2 29 and COS 27
- Git secret/path checks

## Verified during Phase 0 start (13 September 2026)

- Maven Wrapper 3.3.4 using Maven 3.9.11 on Java 25
- Spring Boot compilation, unit test and bootable JAR packaging
- Testcontainers 2.0.5 dependency model resolution
- Playwright/Axe traversal of all 79 routes at desktop and 320px: 8 tests passed
- Frontend formatting, typecheck, lint, unit tests and production build
- Docker Compose configuration resolution
- Backend and frontend Docker image builds

## Verified during Phase 0B (13 September 2026)

- Five ArchUnit modular-boundary rules
- Correlation-ID propagation and four RFC 9457 HTTP contract tests
- Checked OpenAPI 3.1 contract for the two implemented public endpoints
- Spring Boot Flyway startup against a disposable PostgreSQL 18 database
- Separate migration and restricted application roles
- Transaction-local organization, actor, purpose, and correlation settings
- Six RLS isolation/attack tests, including no-context denial and cross-tenant insert/update attempts
- Fresh backend/PostgreSQL deployment smoke test at Flyway schema version 2
- 16 backend tests passing on Java 25 and Maven 3.9.11

## Verified during Phase 0C (13 September 2026)

- Persisted password authentication with HTTP Basic and default form login disabled
- Redis 8 indexed sessions, secure cookie contract, session rotation, idle/absolute expiry, logout, and security-version revocation
- Browser Origin/Referer and CSRF enforcement with RFC 9457 security failures
- Generic, one-use password reset and session revocation behavior
- Encrypted TOTP enrollment, one-use recovery codes, MFA/recent-authentication controls, and Redis throttling
- Append-only hashed authentication evidence and invitation RLS
- Checked OpenAPI 3.1 coverage for all 13 implemented operations
- 24 backend tests passing on Java 25 against disposable PostgreSQL 18 and Redis 8 containers
- Backend runtime image rebuilt successfully with the Phase 0C implementation and non-root `careos` user

## Verified during Phase 0D foundation (13 September 2026)

- Actor-bound, read-only RLS discovery of only the authenticated user's live memberships and selectable organizations
- Authenticated organization listing and CSRF-protected server-side selection APIs; forged `X-Organization-Id` input is ignored
- Immediate removal of suspended memberships from discovery and selection
- Transaction-bound tenant authorization that locks/revalidates membership and checks a migration-owned role/permission mapping before issuing an `AuthorizedTenantContext`
- Empty-by-default, runtime-read-only authorization catalogs; unknown permissions, roles, mappings, and non-members fail closed
- PostgreSQL rejection of actor-discovery writes and runtime authorization-policy mutation
- Flyway validation through schema version 5 and checked OpenAPI coverage for all 15 implemented operations
- 29 backend tests passing in a clean Java 25 build against disposable PostgreSQL 18 and Redis 8 containers
- Fresh isolated backend-image smoke test reached health `UP`, Flyway version 5, seeded exactly one local bootstrap membership, and ran as non-root user `careos`

## Verified during Phase 0E foundation (13 September 2026)

- Migration-owned, runtime-read-only audit/outbox event-version registries that remain empty and fail closed pending owner approval
- Database enforcement of event/version/type/payload-key contracts and transaction-bound actor, organization, purpose, and correlation metadata
- General audit evidence append-only even for the migration owner; immutable/non-deletable outbox content
- Atomic authorization, idempotency, business callback, audit, outbox, and response-replay completion through `GovernedMutationExecutor`
- Actor/tenant/operation-scoped request serialization, hash-conflict rejection, completed-response replay, rollback recovery, concurrent retry handling, and expired-key reuse
- Tenant-authorized outbox claim tokens, leases, `SKIP LOCKED` batching, publication acknowledgement, retry/backoff ceilings, and dead-letter transitions
- Flyway validation through schema version 6
- 36 backend tests passing in a clean Java 25 build; the tenant/governance suite passes all 19 attack and failure scenarios
- Bootable backend JAR packaged from 85 production sources and 5 test sources
- Rebuilt backend image reached health `UP` against fresh PostgreSQL 18/Redis 8/Mailpit, applied Flyway v6, retained empty event registries, seeded one local membership, and ran as non-root user `careos`

## Verified during Phase 0F foundation (13 September 2026)

- Nine framework-independent application ports covering private quarantine, scanning, promotion, signed access, retention, durable notifications, Redis jobs, workers, and schedulers
- Validated opaque document references, hashes, content metadata, notification templates, deduplication keys, and reference-only job envelopes
- Application-context rejection of missing or duplicate capability probes
- Explicit default adapters that report `UNAVAILABLE` and throw before consuming document bytes or performing I/O
- Non-secret capability state/reason reporting through `/actuator/info`
- Five focused platform contract scenarios plus all prior architecture, identity, PostgreSQL/tenancy, and governance suites
- 41 backend tests passing in a clean Java 25 build against disposable PostgreSQL 18 and Redis 8 containers
- Bootable backend JAR packaged from 111 production sources and 6 test sources; checked API (15 operations), prototype (79 routes), and Compose contracts pass
- Rebuilt backend image reached health `UP`, applied Flyway v6, reported all nine capabilities unavailable, retained empty production event registries, and ran as non-root user `careos`

## Verified during Phase 0G foundation (13 September 2026)

- MinIO Java client 9.0.1 plus the explicit OkHttp JVM transport compile and package on Java 25
- Opt-in S3-compatible private quarantine with fail-fast HTTPS/credential/bucket-policy configuration
- Tenant-derived opaque object keys, conditional creation, configurable upload ceiling, exact byte count and SHA-256 verification, mismatch cleanup, and byte-verified retry/conflict handling
- Six isolated object-storage scenarios covering anonymous denial, short/long/digest mismatch cleanup, oversize non-consumption, tenant separation, replay, conflict, public bucket-policy rejection, HTTPS validation, and Spring adapter replacement
- Pinned multi-architecture Quay object-store digest for synthetic local Compose/Testcontainers compatibility; production provider approval remains open
- 47 backend tests passing in a clean Java 25 build against disposable PostgreSQL 18, Redis 8, and object-storage containers
- Bootable backend JAR packaged from 115 production sources and 7 test sources; checked API (15 operations), prototype (79 routes), and Compose contracts pass
- Enabled backend image reached health `UP`, applied Flyway v6, reported only private quarantine available with eight capabilities unavailable, exposed no storage configuration in Actuator info, retained empty production event registries, and ran as non-root user `careos`

## Verified during Phase 0H foundation (13 September 2026)

- Scanner-only quarantine content boundary with tenant, quarantine-state, size, SHA-256, and ETag checks and an architecture rule preventing API-layer access
- Opt-in ClamD adapter with startup `PING`/`VERSIONCOMMANDS` checks, ClamAV 1.5.3 engine floor, configured signature-age/future-skew enforcement, and per-scan freshness revalidation
- Bounded NUL-framed `INSTREAM` transport with network-order chunks, connection/read timeouts, total scan ceiling, and independent exact-length/SHA-256 verification
- Seven deterministic scanner scenarios covering clean/infected/error mapping, protocol chunks, integrity drift, oversize non-consumption, stale signatures, old engines, missing `INSTREAM`, outage, timeout, tenant mismatch, and Spring adapter replacement
- Real pinned official ClamAV 1.5.3 compatibility check returned `stream: OK` for synthetic clean content and `Eicar-Test-Signature FOUND` for the harmless EICAR test pattern
- Optional Compose overlay pins the official multi-architecture ClamAV 1.5.3 base image by digest, persists signatures, does not publish port 3310, and leaves the default stack disabled
- 55 backend tests passing in a clean Java 25 build against disposable PostgreSQL 18, Redis 8, object storage, and deterministic ClamD protocol servers
- Bootable backend JAR packaged from 120 production sources and 8 test sources; frontend formatting/typecheck/lint/unit/build, checked API (15 operations), prototype (79 routes), and both Compose contracts pass
- Rebuilt enabled backend image reached health `UP`, applied Flyway v6, reported exactly quarantine and malware scanning available with seven capabilities unavailable, exposed no dependency configuration in Actuator info, retained empty production event registries, and ran as non-root user `careos`

## Verified during Phase 0I foundation (13 September 2026)

- Opt-in tenant-derived Redis job transport with an explicit bounded `jobType@schemaVersion` allow-list and fail-fast `PING`/scripting/server-time readiness
- Atomic Lua lifecycle for deduplicated enqueue, due-job claim, opaque leases, idempotent acknowledgement, exponential retry, expired-lease recovery, maximum-attempt dead-lettering, and incremental terminal cleanup
- Per-record attempt/retry/retention policy snapshots and SHA-256 payload digests; cross-tenant/stale leases, unapproved versions, distant schedules, retained-record drift, and Redis dependency failures are rejected without delivery
- Payload-free tenant depth snapshots, bounded-cardinality Micrometer transition counters, and an architecture rule preventing API-layer claim/completion access
- Nine isolated Redis scenarios cover sequential and concurrent deduplication/claiming, tenant isolation, acknowledgement, retry, terminal failure, lease loss/recovery, malformed retained state, restart recovery, real dependency timeout/recovery, strict configuration, metrics, and Spring capability replacement
- Compose and all Redis-backed integration suites use the same pinned official multi-architecture Redis 8 Alpine digest
- 65 backend tests passing in a clean Java 25 build against disposable PostgreSQL 18, Redis 8, object storage, and deterministic ClamD protocol servers
- Bootable backend JAR packaged from 127 production sources and 9 test sources; frontend formatting/typecheck/lint/unit/build and eight browser/Axe checks, checked API (15 operations), prototype (79 routes), and both Compose contracts pass
- Rebuilt enabled backend image reached health `UP`, applied Flyway v6, reported only the Redis job queue available with eight capabilities unavailable, exposed no dependency configuration or unauthenticated metrics, retained empty production event registries and zero pre-work queue keys, and ran as non-root user `careos`

## Verified during Phase 0J foundation (13 September 2026)

- Flyway V7 tenant-keyed durable-notification schema with forced RLS, a runtime tenant policy, restricted grants, immutable encrypted content/policy, database-validated lifecycle transitions, retained terminal evidence, and retention-only deletion
- Opt-in PostgreSQL notification store with a bounded template-version allow-list, AES-256-GCM canonical parameters, fresh nonces and tenant/request/template AAD, named-key rotation, SHA-256 deduplication/content/lease evidence, and fail-closed activation checks
- Authorized-transaction-only enqueue, deterministic `FOR UPDATE SKIP LOCKED` claim, opaque leases, idempotent acknowledgement, bounded exponential retry, lease recovery, dead-lettering, incremental cleanup, payload-free snapshots, and safe transition metrics
- Eleven isolated notification scenarios cover restricted activation, RLS and direct database attacks, encrypted-at-rest records with no destination/raw tokens, concurrent deduplication/conflicts, scheduling, acknowledgement, retry/exhaustion, lease loss/recovery, ciphertext corruption, historical-key rotation/missing-key denial, and Spring capability replacement
- Eight ArchUnit rules include API-layer exclusion of notification claim/completion mechanics; worker/scheduler execution and consent, destination resolution, provider delivery, and approved notification governance remain unavailable
- 77 backend tests passing in a clean Java 25 build against disposable PostgreSQL 18, Redis 8, object storage, and deterministic ClamD protocol servers
- Bootable backend JAR packaged from 135 production sources and 10 test sources; frontend formatting/typecheck/lint/unit/build and eight browser/Axe checks, checked API (15 operations), prototype (79 routes), and both Compose contracts pass
- Rebuilt enabled backend image reached health `UP`, applied Flyway v7, reported only durable-notification persistence mechanics available with eight capabilities unavailable, verified the restricted runtime role and forced-RLS policy/triggers, exposed no key configuration or unauthenticated metrics, retained zero notification/event rows, Redis keys, and outbound messages, and ran as non-root user `careos`

## Configured but not yet fully integration-verified

- Complete six-service Docker Compose startup on the default host ports
- Remote GitHub Actions execution of the updated workflow
- Production object-store/IAM/KMS, scanner/network/signature operations, Redis ACL/TLS/HA/persistence/restore acceptance, and notification key-management/backup/restore acceptance; promotion, signed access, retention, notification consent/destination/provider delivery, worker, and scheduler behavior

The current Phase 0 work has closed the Java/frontend build-verification gap and now verifies PostgreSQL migration/RLS, Redis-backed browser identity, membership-backed organization selection, fail-closed tenant authorization, policy-neutral audit/outbox/idempotency mechanics, explicit external-capability boundaries, private-quarantine storage, fail-closed scanner transport/integrity, durable Redis job-transport mechanics, and encrypted PostgreSQL notification persistence/leasing mechanics. A fresh Phase 0J image-level deployment also passed in isolated containers without binding the default PostgreSQL or Redis host ports. Owner-approved authorization/event/job/template content, governed invitations, non-interactive worker identity, production storage/scanner/Redis/key-management acceptance, durable scan evidence, consent/destination/provider delivery, remaining document adapters, and all production workflows remain incomplete.

## Production status

This checkpoint is intentionally named **foundation**. It is runnable and suitable for starting development. It is not a production release, clinical-device claim, security certification or completed implementation of Modules 1-13.
