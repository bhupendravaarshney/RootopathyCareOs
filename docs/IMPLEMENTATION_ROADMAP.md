# Production implementation roadmap

| Phase | Deliverable                                                      | Exit condition                                             |
| ----- | ---------------------------------------------------------------- | ---------------------------------------------------------- |
| 0     | Security, tenancy, audit, outbox, idempotency and test isolation | Cross-tenant tests and CI pass                             |
| 1     | M1-01–M1-23 administration                                       | Organization can be independently activated                |
| 2     | M2-01–M2-29 workforce                                            | Credentialed practitioner and non-clinical workflows pass  |
| 3     | Patient registry                                                 | Duplicate-safe patient identity and consent complete       |
| 4     | Scheduling                                                       | Appointment and clinician/resource assignment complete     |
| 5     | Encounter                                                        | Visit lifecycle and clinical participants complete         |
| 6     | COS-01–COS-27 assessment                                         | Protected design implemented with clinician sign-off       |
| 7     | Documents and results                                            | Private upload/review/provenance pipeline complete         |
| 8     | AI governance                                                    | Purpose-bound AI sessions and human approval complete      |
| 9     | Care plan and follow-up                                          | Versioned coordinated plan and tasks complete              |
| 10    | Outcomes                                                         | Comparable baselines, measures and escalation complete     |
| 11    | Billing                                                          | Invoice, payment, refund and reconciliation complete       |
| 12    | Reporting                                                        | Authorized operational and governance projections complete |
| 13    | FHIR/integrations                                                | Versioned adapters and contract tests complete             |

Do not implement random screens. Each phase starts with architecture, mockup review and a gap ledger, then backend contracts, UI integration, security tests, browser tests and a verified Git checkpoint.

## Current build status

**Phase 0 is in progress.** Module 1 has not started.

### Phase 0A - build verification and reproducibility (completed 13 September 2026)

- [x] Pin the supported Node 24 patch contract in `.nvmrc`, package metadata, Docker, and CI.
- [x] Use deterministic `npm ci` in the frontend container build.
- [x] Fix the serious active-navigation WCAG contrast failure.
- [x] Correct the 320px Playwright device setup and split the 79-route Axe sweep into bounded tests.
- [x] Run Playwright/Axe in CI and retain failure evidence.
- [x] Verify typecheck, lint, format, unit, production build, and all 8 browser tests locally.
- [x] Add Maven Wrapper 3.3.4, pin Maven 3.9.11, and use it in local scripts, Docker, and CI.
- [x] Repair Testcontainers 2 dependency management so the backend Maven model builds.
- [x] Verify the backend on Java 25 and build both application container images.

### Phase 0B - architecture, API, and tenant/RLS foundation (completed 13 September 2026)

- [x] Establish domain/application/infrastructure/API package boundaries and enforce them with five ArchUnit rules.
- [x] Add validated correlation IDs, RFC 9457 validation/error responses, and a checked OpenAPI 3.1 foundation contract.
- [x] Separate the Flyway migration owner from the non-superuser/non-`BYPASSRLS` runtime role.
- [x] Bind organization, actor, purpose, and correlation settings transaction-locally from an `AuthorizedTenantContext`.
- [x] Apply forced RLS to all current tenant-owned tables and test missing-context, read, insert, and update attacks in disposable PostgreSQL 18.
- [x] Repair Spring Boot 4 startup migration wiring by adopting `spring-boot-starter-flyway`.

Local evidence: 16 backend tests pass, both static contract scripts pass, both application images build, and a fresh deployment smoke test reached Flyway version 2 while the runtime role saw zero tenant rows without context.

### Phase 0C - browser identity and session security core (completed 13 September 2026)

- [x] Replace in-memory HTTP Basic with persisted password credentials and a JSON login flow that returns generic failures.
- [x] Store indexed sessions in Redis with an HttpOnly `SameSite=Strict` cookie, ID rotation, idle/absolute expiry, logout evidence, and security-version revocation.
- [x] Require an approved browser origin plus a double-submit CSRF token for every unsafe identity request and return RFC 9457 failures from the security filter chain.
- [x] Implement one-use, peppered password-reset tokens with generic request responses and all-session revocation.
- [x] Implement encrypted TOTP enrollment, one-use hashed recovery codes, MFA challenges, recent authentication, and Redis-backed login/MFA throttling.
- [x] Persist hashed authentication metadata in a database-enforced append-only ledger.
- [x] Expand the checked OpenAPI 3.1 contract from 2 to all 13 implemented operations.

Local evidence: 24 backend tests pass on Java 25 against disposable PostgreSQL 18 and Redis 8 containers. The suite covers session rotation/revocation, CSRF/origin attacks, disabled Basic authentication, generic credential/reset behavior, throttling, password-reset replay, encrypted MFA material, recovery-code replay, recent-authentication evidence, invitation RLS, and append-only authentication evidence.

This slice deliberately does not claim the entire identity roadmap item: governed invitation issuance/acceptance, existing-account linkage, organization selection, scoped authorization, and non-interactive service identities depend on the next policy slice.

### Phase 0D - membership selection and fail-closed authorization mechanics (completed 13 September 2026)

- [x] Add actor-bound, read-only PostgreSQL transactions for organization discovery without creating a tenant authorization context.
- [x] Expose only live/effective memberships and draft/active organizations through forced RLS.
- [x] Add authenticated organization listing and CSRF/origin-protected server-side selection APIs; treat selection only as a navigation preference.
- [x] Replace the directly callable tenant-context transaction hook with `TenantAuthorizationOperations`, which locks/revalidates membership and checks permission in the same tenant transaction as authorized work.
- [x] Add migration-owned, runtime-read-only permission, role, and role-permission catalogs that are empty by default and therefore deny unapproved policy.
- [x] Test actor-discovery read/write boundaries, forged organization headers, suspended membership removal, hidden non-members, missing permissions, and runtime policy-mutation attacks.
- [x] Expand the checked OpenAPI 3.1 contract from 13 to all 15 implemented operations.

The production authorization registry is deliberately not populated. The audit identified that registry as an owner decision, and the prototype is not an acceptable source for inventing clinical or administrative privileges. The required approval payload and enforced boundary are recorded in `AUTHORIZATION_REGISTRY.md`.

Local evidence: a clean Java 25 build compiled 61 production sources, applied all five migrations to fresh PostgreSQL 18 databases, and passed all 29 backend tests against PostgreSQL 18 and Redis 8. The checked contract verifier passes for all 15 implemented operations. A fresh isolated backend-image deployment reached health `UP`, Flyway version 5, created exactly one synthetic local membership, and ran as non-root user `careos`.

### Phase 0E - governed mutation evidence and delivery mechanics (completed 13 September 2026)

- [x] Add migration-owned, runtime-read-only audit and outbox event/version registries that remain empty until owner approval.
- [x] Enforce transaction actor, organization, purpose, correlation, event type, top-level payload keys, and size at the database boundary.
- [x] Make general audit evidence append-only even for the migration owner and make outbox content immutable/non-deletable.
- [x] Add `GovernedMutationExecutor` so authorization, first execution, audit, outbox, and idempotency completion commit or roll back together.
- [x] Scope idempotency to actor, tenant, operation, and key; serialize concurrent retries, reject hash conflicts, replay completed responses, and permit reuse only after expiry.
- [x] Implement tenant-authorized outbox leasing, claim tokens, `SKIP LOCKED` batching, acknowledgement, bounded retry/backoff, retry ceilings, and dead-letter state transitions.
- [x] Attack-test runtime registry mutation, schema drift, missing transaction context, rollback recovery, concurrent replay, owner-level evidence mutation/deletion, retry, and dead-letter behavior.

Local evidence: a clean Java 25 build compiled 85 production sources and 5 test sources, applied all six migrations to fresh PostgreSQL 18 databases, passed all 36 backend tests against PostgreSQL 18 and Redis 8, and packaged the bootable JAR. The focused tenant/governance suite passes all 19 scenarios. A rebuilt backend image reached health `UP` against isolated PostgreSQL 18/Redis 8/Mailpit, applied Flyway v6, kept both event registries empty, seeded one synthetic local membership, and ran as non-root user `careos`.

Production event entries are deliberately not populated. The outbox coordinator is deliberately not scheduled or connected to a placeholder destination: activation requires the approved event registries, non-interactive service identity, tenant job-dispatch path, destination adapter, and consumer deduplication described in `GOVERNANCE_EVIDENCE.md`.

### Phase 0F - fail-closed platform capability boundaries (completed 13 September 2026)

- [x] Define framework-independent ports and validated metadata for private document quarantine, malware scanning, clean promotion, signed access, and retention.
- [x] Define separate durable-notification, Redis-job, worker-execution, and scheduler-execution boundaries without reclassifying immediate SMTP as durable delivery.
- [x] Require exactly one status probe for every capability; reject missing or duplicate adapter registrations during application-context construction.
- [x] Register explicit unavailable adapters that throw before reading content or performing I/O instead of falling back to disk, unscanned access, synchronous mail, memory queues, or web-node scheduling.
- [x] Expose only stable capability state/reason codes through Actuator info and verify all nine defaults through the real Spring configuration.

Local evidence: a clean Java 25 build compiled 111 production sources and 6 test sources, passed all 41 backend tests against disposable PostgreSQL 18 and Redis 8, and packaged the bootable JAR. API coverage remains 15 operations, the 79-screen registry and Compose contract pass, and a rebuilt backend image reached health `UP`, Flyway v6, nine explicitly unavailable platform capabilities, empty production event registries, and non-root user `careos`.

This slice completes the safe activation boundary only. It does not implement or enable MinIO/S3 storage, malware scanning, promotion persistence, signing, retention execution, encrypted notification delivery, a Redis job state machine, non-interactive workers, or schedulers. Their activation checklist is recorded in `PLATFORM_CAPABILITIES.md`.

### Phase 0G - private document quarantine mechanics (completed 13 September 2026)

- [x] Pin the MinIO Java 9.0.1 client and its JVM HTTP transport explicitly rather than relying on incomplete multiplatform Maven metadata.
- [x] Add an opt-in S3-compatible quarantine adapter that fails application startup on incomplete configuration, an unreachable/missing bucket, or any bucket policy.
- [x] Require HTTPS unless a local HTTP override is explicit; keep credentials required on activation and redacted from configuration/status representations.
- [x] Derive object keys exclusively from the authorized organization, document, and version UUIDs; never accept a filename or storage key from a request.
- [x] Enforce a configured upload ceiling, exact stream length, lowercase SHA-256 verification, conditional creation, mismatch cleanup, byte-verified idempotent retries, and conflict rejection.
- [x] Keep scanning, promotion, signed access, and retention unavailable; successful quarantine storage is not clean-document evidence.
- [x] Replace the inaccessible mutable Docker Hub image with a pinned multi-architecture Quay digest for synthetic local compatibility and isolated Testcontainers checks.

Local evidence: a clean Java 25 build compiled 115 production sources and 7 test sources, passed all 47 backend tests against disposable PostgreSQL 18, Redis 8, and the pinned object store, and packaged the bootable JAR. Six storage scenarios verify private anonymous access, exact content/digest enforcement and cleanup, upload ceilings, tenant key separation, retry/conflict behavior, public-policy rejection, HTTPS configuration, and Spring capability replacement. API coverage remains 15 operations, the 79-screen registry and Compose contract pass, and an enabled image smoke reached health `UP`, Flyway v6, exactly one available capability (`private-document-quarantine`), eight unavailable capabilities, no Actuator storage-configuration disclosure, empty production event registries, and non-root user `careos`.

This is storage mechanics, not production document completion. Provider approval, least-privilege IAM, KMS-backed encryption, network controls, durable document metadata/provenance, scanning, promotion, access, retention, backup/restore, monitoring, and operational acceptance remain open.

### Phase 0H - quarantined malware-scanning mechanics (completed 13 September 2026)

- [x] Add a scanner-only, closeable quarantine-content boundary that revalidates tenant ownership, quarantine metadata, size, digest, and object ETag without adding an HTTP download path.
- [x] Add an opt-in ClamD adapter that fails startup unless `PING` succeeds, `VERSIONCOMMANDS` advertises `INSTREAM`, the engine is at least 1.5.3, and signature definitions are current within a configured bounded age.
- [x] Stream content using NUL-framed `INSTREAM`, bounded chunks, network-byte-order lengths, configured connect/read timeouts, and a configured total scan ceiling.
- [x] Independently recompute exact byte length and SHA-256 while scanning; accept `CLEAN` only for an exact `stream: OK` response after both integrity checks pass.
- [x] Map detections to `INFECTED` and every dependency outage, timeout, stale definition, malformed response, size excess, storage failure, or integrity mismatch to `ERROR`, never `CLEAN`.
- [x] Keep scanner TCP private in an optional Compose overlay, pin the official multi-architecture ClamAV 1.5.3 image by digest, and keep both storage and scanning disabled in the default stack.
- [x] Keep promotion, signed access, retention, durable scan evidence, document metadata/provenance, and every public document route unavailable.

Local evidence: a clean Java 25 build compiled 120 production sources and 8 test sources, passed all 55 backend tests, and packaged the bootable JAR. Seven deterministic scanner scenarios cover wire framing/chunk bounds, clean/infected/error mapping, content-integrity drift, size ceilings, stale definitions, old engines, missing protocol support, outage, timeout, tenant mismatch, and Spring adapter replacement; six S3 scenarios also exercise the scanner-only read boundary against the pinned object store. The checked 15-operation API and 79-screen registry, frontend formatting/typecheck/lint/unit/build gates, and both default and scanner-overlay Compose contracts pass. A pinned official ClamAV 1.5.3 daemon returned `stream: OK` for synthetic clean bytes and `FOUND` for the standard EICAR test pattern. A rebuilt isolated backend image reached health `UP`, Flyway v6, exactly two available capabilities (private quarantine and malware scanning), seven unavailable capabilities, empty production event registries, safe Actuator output, and non-root user `careos`; all temporary resources were removed.

This is scanner transport and integrity mechanics, not an approved production document workflow. ClamD TCP is unauthenticated and unencrypted and must remain on a trusted segmented network. Durable scan attestations, rescan/version policy, provider/IAM/KMS acceptance, promotion authorization and state transitions, signed reads, retention/legal hold, monitoring, recovery, and operational ownership remain open.

### Phase 0I - durable Redis job-transport mechanics (completed 13 September 2026)

- [x] Extend the queue boundary with tenant-scoped claim, acknowledgement, failure disposition, and payload-free state snapshots without adding a worker or HTTP execution route.
- [x] Add an opt-in Redis adapter that fails startup without a bounded `jobType@schemaVersion` allow-list, valid lease/retry/retention limits, exact `PONG`, and scripting/server-time support.
- [x] Derive every key from the authorized organization, use one Redis Cluster hash tag per tenant, and SHA-256-index deduplication tokens instead of placing raw tokens in key names.
- [x] Use bounded atomic Lua transitions for enqueue, scheduled claim, acknowledgement, exponential retry, expired-lease recovery, maximum-attempt dead-lettering, and incremental terminal cleanup.
- [x] Persist each job's retry/attempt/retention policy and SHA-256 payload digest; reject stale or cross-tenant leases, unapproved versions, malformed retained records, distant schedules, and Redis failures without delivering work.
- [x] Add safe Micrometer transition counters and payload-free tenant depth snapshots; cover concurrency, deduplication, retry/dead-letter, lease loss, restart, record drift, timeout/recovery, configuration, and capability replacement against disposable Redis 8.
- [x] Pin the same official multi-architecture Redis 8 Alpine digest in Compose and every Redis-backed integration suite.
- [x] Keep the adapter disabled by default and prevent API packages from accessing job claim/completion mechanics; worker and scheduler execution remain unavailable.

Local evidence: a clean Java 25 build compiled 127 production sources and 9 test sources, passed all 65 backend tests, and packaged the bootable JAR. Nine Redis adapter scenarios exercise concurrent producers/claimers, exact deduplication, tenant separation, scheduled retries, bounded attempts, idempotent acknowledgement, lease expiry/recovery, retained-record drift, application restart, a real paused-dependency timeout/recovery, strict activation, metrics, and Spring capability replacement. The checked 15-operation API and 79-screen registry, frontend formatting/typecheck/lint/unit/build plus eight browser/Axe checks, and both Compose contracts pass. A rebuilt isolated backend image reached health `UP`, Flyway v6, exactly one available capability (`redis-job-queue`), eight unavailable capabilities, empty production event registries, no pre-work queue keys or configuration disclosure, and non-root user `careos`; temporary smoke resources were removed.

This is durable queue transport, not permission to perform background work. The checked default remains unavailable, and the synthetic allow-list is not production registry approval. Production Redis ACL/TLS/HA/persistence/restore acceptance, capacity/alerting, dead-letter operations, approved job definitions, authenticated non-interactive identities, tenant reauthorization, governed effects/evidence, worker deployment, and scheduling remain open.

### Phase 0J - encrypted durable notification-store mechanics (completed 13 September 2026)

- [x] Extend the notification boundary with tenant-scoped claim, acknowledgement, failure disposition, and payload-free state snapshots without resolving a destination, invoking a provider, or starting a worker.
- [x] Add Flyway V7 with a tenant-keyed notification table, forced RLS, restricted runtime grants, origin-context validation, immutable ciphertext/policy, database-checked lease/finalization transitions, terminal evidence, and retention-only deletion.
- [x] Add an opt-in PostgreSQL adapter that fails startup without a bounded `templateKey@templateVersion` allow-list, one to eight named 256-bit keys, an active key, bounded lifecycle settings, a restricted non-owner/non-`BYPASSRLS` runtime role, forced RLS, required privileges, server time, and missing-context denial.
- [x] Encrypt canonical JSON parameters with AES-256-GCM, a fresh 96-bit nonce, and tenant/request/template AAD; store no destination or raw deduplication/lease token; support historical keys during rotation and fail closed when one is missing.
- [x] Require the existing authorized writable tenant transaction for every operation; use PostgreSQL server time, deterministic `FOR UPDATE SKIP LOCKED` claims, opaque 256-bit leases, copied retry/retention policy, bounded exponential retry, lease recovery, dead letters, and incremental cleanup.
- [x] Add payload/content integrity checks, safe Micrometer counters, payload-free snapshots, idempotent exact enqueue/acknowledgement, conflict rejection, and an eighth ArchUnit rule preventing API access to notification claim/completion mechanics.
- [x] Keep notification persistence disabled by default and keep worker/scheduler plus consent, destination resolution, provider delivery, and governance-event policy unavailable.

Local evidence: a clean Java 25 build compiled 135 production sources and 10 test sources, applied all seven migrations to disposable PostgreSQL 18 databases, passed all 77 backend tests, and packaged the bootable JAR. Eleven notification scenarios cover activation, transaction enforcement, RLS, direct database mutation guards, encrypted storage, concurrent deduplication, conflicts, scheduling, acknowledgement, retry/dead-letter, lease recovery, ciphertext tampering, key rotation/missing keys, and safe configuration; the prior 19 tenant/governance attack scenarios and all 13 architecture/platform default-contract checks remain green. The checked 15-operation API and 79-screen registry, frontend formatting/typecheck/lint/unit/build plus eight browser/Axe checks, and both Compose contracts pass. A rebuilt isolated backend image reached health `UP`, Flyway v7, exactly one available capability (`durable-notification-delivery` persistence mechanics), eight unavailable capabilities, forced tenant RLS with the expected policy/triggers, empty notification and production event state, zero Redis keys and outbound messages, protected metrics, safe Actuator/log output, and non-root user `careos`; temporary smoke resources were removed.

This is durable notification persistence and leasing, not outbound delivery. The checked default remains unavailable, and the synthetic allow-list/key material used by tests is not production template or key-management approval. Production completion still requires approved templates/payload schemas, consent and destination policy, non-interactive worker identity, provider and regional acceptance, provider idempotency, approved audit/outbox/delivery evidence, key rotation/re-encryption operations, monitoring, dead-letter replay, backup/restore, retention procedures, and operational ownership.

### Next Phase 0 slice

- [ ] Approve and add the canonical permission/role/operation registry, then bind protected routes, delegation ceilings, final-owner safeguards, and maker-checker rules to the implemented authorization boundary.
- [ ] Complete governed invitation issuance/acceptance, existing-account linkage, and a separate non-interactive service-account path using that approved policy.
- [ ] Add composite tenant foreign keys and database-enforced invariants as the first production vertical slice is introduced.
- [x] Define fail-closed document, notification, Redis, worker, and scheduler ports.
- [x] Implement and integration-test policy-neutral private-quarantine storage mechanics behind the Phase 0F boundary, disabled by default.
- [x] Implement and integration-test policy-neutral quarantined malware-scanning mechanics behind the Phase 0F boundary, disabled by default.
- [x] Implement and integration-test policy-neutral Redis durable-job transport mechanics behind the Phase 0F boundary, disabled by default.
- [x] Implement and integration-test policy-neutral encrypted notification persistence/lease mechanics behind the Phase 0F boundary, disabled by default.
- [ ] Approve a production object-store/IAM/KMS design and complete its deployment, recovery, monitoring, and acceptance controls.
- [ ] Implement and integration-test the remaining approved promotion, signed-access, retention, notification consent/destination/provider, worker, and scheduler adapters; keep each unavailable until its checklist passes.

Phase 0 remains incomplete until every foundation exit condition in `IMPLEMENTATION_GAPS.md` is satisfied and CI passes.
