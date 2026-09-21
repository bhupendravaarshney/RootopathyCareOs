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

Do not implement random screens. Each phase starts with architecture, mockup review and a gap ledger, then backend contracts, UI integration, security tests, browser tests and a verified delivery checkpoint.

## Current build status

**Phase 0 repository mechanics are complete; target-environment production acceptance remains open.** `m1-candidate-1` was approved unchanged by **bhupendra, developer** on 16 September 2026. The production input gate is `APPROVED` at eight of eight artifacts and package digest `19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946`. Flyway V20 records the immutable approval release and activates the approved Module 1 interactive authorization catalogue; V21-V24 implement bounded membership, owner-transfer, and mandatory-MFA behavior; V25-V29 implement M1-07 through M1-11 organization core and live readiness, including confidential, gap-free governance responsibility coverage. Persisted configuration validation/activation is not claimed. The facility-scope candidate remains non-authorizing, and M1B, final M1C acceptance, and Module 1 remain in progress.

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

Production event entries are deliberately not populated. The outbox coordinator is deliberately not scheduled or connected to a placeholder destination: activation requires the approved event and consumer registries, non-interactive service identity, tenant job-dispatch path, destination/subscription adapters, and operational procedures described in `GOVERNANCE_EVIDENCE.md`. Phase 0S later supplies the reusable consumer deduplication transaction boundary without activating it.

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

This is scanner transport and integrity mechanics, not an approved production document workflow. ClamD TCP is unauthenticated and unencrypted and must remain on a trusted segmented network. Later slices add durable scan attestations, promotion, signed-read, and immutable-retention mechanics; rescan/version policy, provider/IAM/KMS/Object-Lock acceptance, governed hold release/disposal, monitoring, recovery, and operational ownership remain open.

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

### Phase 0K - CI supply-chain and container-runtime hardening (completed 13 September 2026)

- [x] Pin every third-party GitHub Action to a full commit SHA with a readable release comment; use explicit Ubuntu runner versions, top-level read-only permissions, checkout without persisted credentials, concurrency cancellation, and finite job timeouts.
- [x] Add pull-request dependency review, CodeQL `security-extended` analysis for Java/Kotlin and JavaScript/TypeScript, and Trivy filesystem scanning for dependencies, secrets, and configuration.
- [x] Build both application images in the security workflow, reject fixed HIGH/CRITICAL image findings, generate CycloneDX SBOMs, and retain both SBOMs as a bounded CI artifact.
- [x] Add weekly Dependabot coverage for Maven, npm, Docker, Compose, and GitHub Actions dependencies with bounded open-update limits.
- [x] Pin every Dockerfile base, Compose service, scanner overlay, and PostgreSQL/Redis Testcontainers image by SHA-256 digest; explicitly declare the digest-pinned PostgreSQL image as a compatible Testcontainers substitute.
- [x] Override the managed Tomcat runtime to the fixed 11.0.25 security floor and replace the backend runtime with the Java 25 Debian 13 distroless non-root image.
- [x] Run the frontend as unprivileged Nginx on port 8080 and provide an unprivileged backend health probe without adding a package manager to the distroless runtime.
- [x] Add a dependency-free repository security contract plus eight negative tests covering mutable actions/images, excessive workflow permissions, floating runners, missing timeouts/concurrency, checkout credentials, root resets, and incomplete dependency-update coverage.

Local evidence: a clean Java 25 build compiled 135 production sources and 10 test sources, passed all 77 backend tests, and packaged the bootable JAR. Exact Node 24.15 frontend installation, formatting, typecheck, lint, two unit tests, production build, all eight desktop/mobile browser/Axe checks, the 79-screen register, 15-operation OpenAPI contract, both Compose models, and all eight CI-security contract tests pass. Trivy 0.74 reports zero fixed HIGH/CRITICAL findings in the Maven/npm dependency sources and final backend/frontend images, zero Dockerfile misconfigurations, and no emitted secret findings; both final images generate valid CycloneDX SBOMs. The final backend image reached health `UP` as UID 65532 with a read-only filesystem, all capabilities dropped, no-new-privileges, Flyway v7, protected metrics, correct capability readiness, and empty notification/event/Redis/mail state. The final frontend reached healthy as UID 101 under the same runtime restrictions. Temporary smoke resources were removed.

This closes the locally verifiable CI/supply-chain foundation, not production deployment acceptance. The hosted security workflow, dependency-review graph access, CodeQL result upload, repository rules/required checks, signed provenance, artifact retention policy, and production registry admission still require execution and approval in the target GitHub/registry environment.

### Phase 0L - checked API conventions and browser client foundation (completed 14 September 2026)

- [x] Extend the OpenAPI 3.1 contract with a machine-checked protected tenant path, UUID organization parameter, opaque cursor/bounded limit metadata, operation-specific filter allowlists, strong `ETag`/required `If-Match`, scoped idempotency, and bounded caller-controlled retry policy.
- [x] Add reusable 412, 428, 429, and 503 Problem/retry components while retaining correlation metadata on every implemented operation.
- [x] Refactor the dependency-free API verifier into an importable contract check and add six tests that reject tenant-prefix ambiguity, permissive filters, mutation auto-retry, weakened concurrency, and dangling references.
- [x] Pin a TypeScript 6-compatible OpenAPI generator, override its vulnerable YAML transitive range to fixed `js-yaml` 4.3.2, check in TypeScript-only artifacts, and fail CI when the generated file set or content drifts.
- [x] Add a generated-type-backed browser client for all 15 implemented operations with credential inclusion, validated request/response correlation, per-mutation CSRF bootstrap, documented-status/body enforcement, safe RFC 9457 parsing, abort/network outcomes, strong ETag exposure, bounded `Retry-After`, and no automatic retry.
- [x] Align backend CORS with `If-Match`, `Idempotency-Key`, `ETag`, and retry/correlation metadata while removing the unused `X-Organization-Id` alternative; cover the allow/expose lists with a focused backend test.
- [x] Enable TypeScript `noUncheckedIndexedAccess`, repair prototype registry/navigation fallbacks, and add the generation/negative-contract gates to the quality workflow.

Local evidence: a clean Java 25 build compiled 135 production sources and 11 test sources, passed all 78 backend tests against disposable dependencies, and packaged the bootable JAR. Exact Node 24.15 installation audited 290 packages with zero vulnerabilities; generated-type drift, formatting, strict typecheck, lint, 10 frontend unit tests, the Vite production build, and all eight desktop/mobile route-wide Playwright/Axe checks pass. The OpenAPI verifier accepts all 15 implemented operations plus six conventions, and all six API-contract negative tests and eight CI-security negative tests pass. Both current application images build from their clean Docker contexts.

This completes the shared HTTP/client convention, not a production frontend or tenant business API. The React screens still render synthetic prototype data, and protected business operations remain fail closed until their permissions, purposes, event schemas, persistence, state transitions, and UI behavior are approved and implemented. See `API_CONVENTIONS.md`.

### Phase 0M - authenticated frontend session boundary (completed 14 September 2026)

- [x] Add a memory-only session state machine that bootstraps the server session before exposing any protected route content.
- [x] Validate session/user UUID state and membership-backed organization shape, uniqueness, and selection cardinality at runtime instead of treating generated TypeScript types as response validation.
- [x] Connect M1-01 primary login and pending M1-03 MFA challenge to the checked browser client with empty secret fields, cleared submitted secrets, disabled duplicate actions, safe Problem details, correlation references, and bounded retry guidance.
- [x] Require explicit M1-04 organization selection when the server has no current preference; use returned organization/user state in the shell and support acknowledged organization switching from desktop and mobile navigation.
- [x] Connect sign-out to the checked CSRF-aware mutation and retain local authenticated state if logout is not acknowledged; treat a server `401` as an already-invalid session.
- [x] Add loading, no-membership, malformed/ambiguous-response, dependency-failure, and focused error-summary states while keeping the workspace hidden.
- [x] Replace the synthetic invitation form with an explicitly unavailable M1-02 state until governed issuance, account linkage, expiry, and acceptance exist.
- [x] Add a TypeScript-AST frontend dependency verifier and four negative fixtures; run it in CI before typecheck to enforce inward API/data/component/feature/page direction and feature isolation.
- [x] Expand unit coverage to 19 tests and the desktop/mobile Playwright/Axe suite to 12 tests, including all 79 registered route states and a mocked CSRF/login/selection/logout browser flow.

Local evidence: all 17 frontend source files and 30 relative imports pass the new boundary verifier; its four negative fixtures reject shared-to-feature reversal, cross-feature coupling, API-to-page reversal, and source escape. Generated API drift, strict typecheck, lint, formatting, all 19 Vitest tests, the Vite production build, and all 12 desktop/mobile browser tests pass. Browser coverage visits the 75 protected prototype entries plus four identity states and exercises the checked CSRF/login/organization-selection/logout sequence. The 15-operation API, backend behavior, and database schema remain unchanged.

This closes the session-aware browser shell, not the product frontend. Organization preference is never authorization evidence; the 75 protected entries still render synthetic content. Invitations/account linkage, password-recovery and MFA-administration UI, recent-auth dialogs, expiry revalidation, permission-driven actions, approved routing/design assets, tenant business APIs/cache invalidation, and all production workflows remain open. See `FRONTEND_SESSION.md`.

### Phase 0N - operational signal foundation (completed 14 September 2026)

- [x] Enable Spring Boot ECS JSON console output and add one safe completion event per HTTP request with validated correlation, trace/span context, allow-listed method, framework route template, status, duration, and outcome.
- [x] Prove request logging excludes raw URI/query values, path values, request bodies/headers, users, tenants, recipients, and payloads and restores any outer MDC correlation context.
- [x] Add the OpenTelemetry tracing foundation with W3C propagation, baggage disabled, 10% default sampling, bounded span attributes/events/links, and trace export disabled by default.
- [x] Disable OTLP metric/log export and ambient `OTEL_*` mapping so inherited environment settings cannot silently start telemetry export.
- [x] Add Prometheus registry output with a bounded application tag, expose it behind full CareOS authentication, and retain non-sensitive bounded-cardinality custom adapter metrics.
- [x] Separate public status-only liveness from readiness; keep liveness internal-only and require application readiness, PostgreSQL, and Redis for traffic readiness.
- [x] Point the Compose backend health check at `/readyz` while retaining `/livez`, `/readyz`, and the standard Actuator probe aliases on the main application port.
- [x] Add an integration outage drill that pauses Redis, proves `503/DOWN` readiness with continued `200/UP` liveness, resumes Redis, and proves readiness recovery.
- [x] Add `OPERATIONS.md` with the signal contract, safe telemetry rules, first response, local drill, production monitoring/alert acceptance, and deployment/restore checklist.

Local evidence: a clean pinned Java 25 build compiled 136 production sources and 11 test sources, passed all 80 backend tests, and packaged the bootable JAR. Both Compose models, all 15 checked operations, six API-contract negative tests, the 79-screen register, the CI-security contract, and its eight negative tests pass. The rebuilt distroless backend reached `UP` on both probes against fresh PostgreSQL 18/Redis 8, rejected anonymous Prometheus access, started no OTLP metrics publisher, and retained UID 65532, read-only root, dropped capabilities, and no-new-privileges in an isolated smoke. Trivy 0.74 finds zero fixed HIGH/CRITICAL vulnerabilities in its Debian 13.6 layer and packaged JAR.

This completes only the repository-local signal foundation. Production still needs an approved non-interactive scrape boundary, telemetry collector and region/retention/access policy, measured service objectives, dashboards, tested alert delivery/on-call escalation, managed deployment assets, encrypted backup ownership, timed restore evidence, dead-letter procedures, and provider-specific incident/rollback runbooks. See `OPERATIONS.md`.

### Phase 0O - HTTP and production-configuration hardening (completed 14 September 2026)

- [x] Make the backend API response policy explicit: no-content CSP, deny framing, no referrer, no MIME sniffing, restricted browser capabilities, and one-year non-preloaded/non-subdomain HSTS on secure requests.
- [x] Apply an always-on strict same-origin CSP and complementary isolation, permissions, referrer, HSTS, MIME, framing, cross-domain, and legacy-XSS headers at the unprivileged Nginx frontend.
- [x] Retain the production `/api` same-origin proxy and bound its connect/send/read timeouts while removing the hop-by-hop `Connection` header and Nginx version tokens.
- [x] Add a `production` Spring profile that requires externally injected PostgreSQL, Redis, SMTP, origin/base-URL, and identity-secret settings instead of inheriting successful local credential fallbacks.
- [x] Move every successful synthetic database, Redis, SMTP, browser, and bootstrap fallback into profile-gated `application-local.yml`; require explicit values in the base configuration and lock that separation in the repository security contract.
- [x] Require verified PostgreSQL TLS, authenticated Redis TLS, authenticated mandatory SMTP STARTTLS with server identity verification, secure cookies, canonical HTTPS browser origins, and an HTTPS OTLP destination whenever export is enabled.
- [x] Reject mixed local/test production profiles, documented non-production secret material, trivial MFA key material, embedded database credentials, duplicate origins, the S3 HTTP override, and production runtime bucket creation without including secret values in errors.
- [x] Extend backend and dependency-free repository tests to lock the header/startup policy and reject missing always-on headers, weak/wildcard CSP, server tokens, or unbounded proxy behavior.
- [x] Add `PRODUCTION_SECURITY.md` with the configuration contract, TLS/proxy ownership, secret-manager boundary, and explicit rate-limit/WAF/deployment acceptance gaps.

Local evidence: a clean pinned Java 25 build compiles 137 production sources and 11 test sources, applies all seven migrations to disposable PostgreSQL 18, passes all 84 backend tests, and packages the bootable JAR. The focused startup-guard tests cover a secure configuration plus mixed-profile, local-secret, insecure-cookie, plaintext dependency, invalid-origin, S3 override, and insecure OTLP failures. The backend integration suite proves the exact headers and secure-request-only HSTS behavior. The frontend gates remain green at 19 unit and 12 desktop/mobile browser tests; both Compose models, the 15-operation API, six API negative tests, 79-screen register, and the expanded 10-test CI-security contract pass. Rebuilt final images have zero fixed HIGH/CRITICAL OS-package findings. The backend image refuses to start without an explicit required runtime value, while the hardened frontend serves the expected strict headers as UID 101.

This is a repository preflight boundary, not production TLS, secrets, or edge acceptance. The real environment still needs an approved secret manager and rotation drills; public TLS termination/redirect and trusted proxy topology; private backend/management routing; frontend-to-backend transport acceptance; operation-specific/application and coarse edge rate policy; WAF/DDoS ownership; certificate/header/CSP scans; and measured load, abuse, and false-positive tests. See `PRODUCTION_SECURITY.md`.

### Phase 0P - browser identity lifecycle self-service (completed 15 September 2026)

- [x] Extend the checked session schema with authoritative `mfaEnabled` state and have every authenticated session response read the persisted account state, including immediately after enrollment.
- [x] Add public password-reset request and completion routes using the checked CSRF/correlation-aware client, the backend's generic request response, matching password and UTF-8 byte checks, and explicit all-session-revocation confirmation.
- [x] Preserve case-sensitive email tokens while parsing the reset fragment, immediately replace the browser-history entry with a token-free hash, retain the token only in React memory, and forget it after successful consumption.
- [x] Turn authenticated M1-03 into identity-level MFA self-service, available even without a selected organization, with current-password and conditional second-factor recent authentication.
- [x] Connect TOTP enrollment/verification and recovery-code regeneration; require explicit destructive acknowledgement before replacement and show plaintext setup/recovery material only in the active memory-only view.
- [x] Runtime-validate the session/MFA status, `otpauth:` enrollment material, and unique recovery-code format; fail closed on invalid responses or `401`, and return to recent authentication on server `428`.
- [x] Expand unit and desktop/mobile browser coverage for generic recovery, missing/mismatched reset input, token case preservation/scrubbing, recent authentication, MFA enrollment, one-time codes, and accessibility.

Local evidence: a clean host-side Java 25 build compiled 137 production sources and 11 test sources, passed all 84 tests against disposable PostgreSQL 18, Redis 8, object storage, and deterministic ClamD fixtures, applied Flyway through V7, and packaged the bootable JAR. The checked OpenAPI 3.1 contract is version 0.5.0, retains 15 operations, and passes all six conventions; all six API negative tests, the 79-screen register, the CI-security contract, and its 10 negative tests pass. Frontend generated drift, its 18-source/35-import architecture boundary and four negative tests, formatting, strict typecheck, lint, all 24 unit tests, production build, and all 16 desktop/mobile Playwright/Axe tests pass. Browser flows prove CSRF-backed generic reset request/completion with token removal and recent-authenticated MFA enrollment with one-time recovery-code handling.

This completes policy-neutral browser password recovery and MFA enrollment/recovery-code self-service, not the governed identity program. Invitation issuance/acceptance and existing-account linkage, MFA disable and administrator reset/support policy, non-interactive service accounts, session-expiry revalidation, permission-driven identity administration, and all tenant business workflows remain open. See `FRONTEND_SESSION.md`.

### Phase 0Q - browser session-expiry convergence (completed 15 September 2026)

- [x] Publish `X-CareOS-Session-Expires-In` on valid authenticated responses as the bounded effective seconds to the earlier Redis idle or application absolute deadline; omit it for anonymous sessions and expose it through CORS.
- [x] Advance OpenAPI 3.1 to version 0.6.0 with a seventh checked convention requiring authenticated deadline responses, refresh-on-real-request semantics, local deadline lock, and no background polling.
- [x] Extend the checked client with a session-lifecycle publisher that derives a conservative deadline from request start, ignores malformed optional expiry metadata, and broadcasts every `401` as invalidation without changing transport results.
- [x] Require authenticated bootstrap/login/MFA responses to contain valid expiry metadata before exposing protected content, and fail closed when it is absent or elapsed.
- [x] Arm a bounded memory-only browser timer, cancel in-flight session/identity generations at expiry, and return to M1-01 with a safe notice without contacting the server.
- [x] Revalidate the full session/organization boundary on focus, visible-tab return, online return, and persisted-page restoration only while the local deadline is still valid; lock without a request after expiry.
- [x] Add focused server calculation/CORS/integration coverage, checked-client deadline/invalidation tests, application missing-deadline/timeout/resume tests, and desktop/mobile no-polling browser coverage.

Local evidence: a clean Java 25 build compiles 138 production sources and 12 test sources, applies all seven migrations to disposable PostgreSQL 18, passes all 87 backend tests, and packages the bootable JAR. Frontend generated drift, its 18-source/35-import architecture boundary and four negative tests, formatting, strict typecheck, lint, all 29 unit tests, production build, and all 18 desktop/mobile Playwright/Axe tests pass. The checked 15-operation OpenAPI 3.1 version 0.6.0 contract passes seven conventions and all seven negative tests; the 79-screen register and CI-security verifier/10 negative tests remain green.

This completes automatic expiry convergence for calls made through the checked browser client without weakening server idle expiry. It does not approve invitation/account-linking or administrative MFA policy, service identities, permission-driven business administration, tenant record caching, or any production business workflow. See `FRONTEND_SESSION.md`.

### Phase 0R - durable document-security evidence mechanics (completed 15 September 2026)

- [x] Add Flyway V8 with separate append-only quarantine-evidence and scan-attestation tables keyed by organization, document, and object version.
- [x] Apply forced RLS to both tables, grant the runtime role only `SELECT`/`INSERT`, bind actor/purpose/correlation and server record time in insert triggers, and reject update/delete even for ordinary migration-owner operations.
- [x] Link scan attestations to the exact quarantine row through a composite tenant/document/object-version foreign key and verify the role, ownership, RLS, policies, triggers, grants, foreign key, and missing-context visibility at startup.
- [x] Make exact quarantine and scan observation replays idempotent while rejecting changed metadata and contradictory observations at the same scanner/time identity.
- [x] Require authoritative digest equality for `CLEAN` and `INFECTED`; retain `ERROR` evidence with an explicit unknown digest without allowing it to become promotion evidence.
- [x] Add `DocumentSecurityOperations` to check the authorized evidence transaction before storage I/O, verify returned opaque references, require quarantine evidence before scanning, and persist each accepted result.
- [x] Prevent API packages from calling storage, scanner, or low-level evidence ports directly with a ninth ArchUnit rule.
- [x] Cover orchestration order, rollback, exact replay, conflicts, latest observation, missing metadata, digest drift, forced RLS, cross-tenant writes, restricted grants, and append-only owner attacks.

Local evidence: a clean Java 25 build compiled 146 production sources and 14 test sources, applied all eight migrations to disposable PostgreSQL 18, passed all 99 backend tests across PostgreSQL, Redis, pinned object storage, and deterministic ClamD fixtures, and packaged the bootable JAR. The new evidence slice contributes five application tests, six PostgreSQL integration tests, and one architecture rule. The API/frontend surface is intentionally unchanged at 15 operations, seven conventions/seven negative tests, 29 frontend unit tests, and 18 desktop/mobile Playwright/Axe tests.

This completes durable quarantine/scan evidence mechanics, not the M7 document workflow. Storage and scanning remain opt-in and disabled by default; Phases 0T/0U later add separately opt-in promotion and signed-read mechanics, and Phase 0V later adds immutable retention/legal-hold-enablement mechanics. Approved permissions/events, a business document/provenance model, governed upload/finalization/read/disposal behavior, provider IAM/KMS/operations, and protected API/browser coverage remain required. See `PLATFORM_CAPABILITIES.md`.

### Phase 0S - durable consumer inbox and deduplication mechanics (completed 15 September 2026)

- [x] Add Flyway V9 with a migration-owned, runtime-read-only consumer/event-version allow-list that remains empty until reviewed production entries are approved.
- [x] Add tenant-scoped consumer receipts keyed by organization, consumer, and stable source event ID, with forced RLS, `SELECT`/`INSERT`-only runtime grants, transaction-context/server-time insertion, and owner-level append-only enforcement.
- [x] Link every consumer definition to an existing outbox event definition and every receipt to the exact consumer/event version through checked composite foreign keys.
- [x] Validate active definitions, aggregate type, and required/allowed top-level payload keys before processing; hash PostgreSQL-canonical JSON and retain only the digest rather than copying payload content into inbox evidence.
- [x] Add `ConsumerInboxExecutor` so tenant authorization, unique receipt acquisition, and the first consumer effect commit or roll back in one transaction.
- [x] Treat exact canonical redelivery as replay without invoking the effect, reject changed envelope content under the same source event ID, and serialize simultaneous delivery through PostgreSQL uniqueness.
- [x] Fail startup for an unsafe runtime role, ownership/grant/RLS/policy/trigger/key-shape drift, unavailable database time, or receipt visibility without tenant context.
- [x] Prevent API packages from bypassing the transactional executor through the low-level inbox port with a tenth ArchUnit rule.
- [x] Cover exact replay, changed content, unknown consumers, payload drift, callback rollback/retry, concurrent delivery, missing transaction, tenant mismatch, cross-tenant SQL, restricted grants, and migration-owner mutation attacks.

Local evidence: a clean Java 25 build compiled 153 production sources and 14 test sources, applied all nine migrations to disposable PostgreSQL 18, passed all 106 backend tests across PostgreSQL, Redis, pinned object storage, and deterministic ClamD fixtures, and packaged the bootable JAR. The focused tenant/governance and architecture run passed all 35 tests. The unchanged 15-operation/seven-convention API passes its verifier and all seven negative tests; the 79-screen and CI-security contracts/all ten negative tests also pass. Frontend source is unchanged at its latest verified 29 unit tests and 18 desktop/mobile Playwright/Axe tests.

This completes the reusable consumer inbox/deduplication transaction boundary, not a running integration consumer. Production consumer definitions, non-interactive service identity, tenant dispatch, authenticated destination/subscription adapters, broker acknowledgement, real governed effects, monitoring, replay, retention, and dead-letter procedures remain unavailable or unapproved. See `GOVERNANCE_EVIDENCE.md`.

### Phase 0T - evidence-gated clean document promotion mechanics (completed 15 September 2026)

- [x] Add Flyway V10 with one append-only promotion-evidence row per tenant/document/object version, linked to the exact scan attestation through organization/document/version keys.
- [x] Snapshot the policy key, canonical accepted-scanner allow-list, maximum scan age, maximum future skew, actor, purpose, correlation, and PostgreSQL promotion time.
- [x] Apply forced RLS, `SELECT`/`INSERT`-only runtime grants, transaction-context/server-time insertion, and owner-level update/delete rejection; fail startup on role, ownership, grant, policy, trigger, composite-link, or missing-context drift.
- [x] Independently require the latest scan attestation, `CLEAN`, an accepted scanner, and database-time freshness in the insert trigger.
- [x] Add `DocumentPromotionOperations` so callers provide only an opaque reference and cannot supply raw scan evidence; verify quarantine/latest-scan reference, digest, scanner, freshness, storage response, and persisted policy snapshot.
- [x] Add an opt-in S3-compatible adapter that requires distinct private quarantine/clean buckets, tenant-derived keys, current matching policy authorization, conditional creation, streaming SHA-256 verification, bad-copy cleanup, and full-content verification for exact replay without deleting quarantine.
- [x] Keep promotion disabled unless `careos.documents.promotion.enabled=true` and an explicit bounded policy is configured; extend production preflight and the API architecture boundary accordingly.
- [x] Cover ordering, replay, reference/policy/evidence drift, missing/non-clean/unapproved/stale/future scans, latest-scan enforcement, forced RLS, direct SQL, owner mutation, private buckets, corrupt source/copy cleanup, tenant isolation, and conflicting destinations.

Local evidence: a clean Java 25 build compiled 161 production sources and 15 test sources, applied all ten migrations to disposable PostgreSQL 18, passed all 119 backend tests across PostgreSQL, Redis, pinned object storage, and deterministic ClamD fixtures, and packaged the bootable JAR. Both Compose models resolve. The unchanged 15-operation/seven-convention API and all seven negative tests, 79-screen register, and CI-security verifier/all ten negative tests were rerun and pass. Frontend source is unchanged at its latest verified 29 unit tests and 18 desktop/mobile Playwright/Axe tests.

This completes only disabled-by-default clean-promotion mechanics. It does not approve a promotion policy or production provider, expose an upload/read route, add an M7 document/provenance state machine or governed permission/event transition, or complete IAM/KMS/versioning/Object-Lock/backup/monitoring acceptance. Phase 0U later adds internal signed-read mechanics and Phase 0V later adds immutable retention/legal-hold-enablement mechanics, but a clean private copy without committed V10 evidence remains non-deliverable. See `PLATFORM_CAPABILITIES.md`.

### Phase 0U - promotion-evidence-gated signed document access mechanics (completed 15 September 2026)

- [x] Replace the raw-reference signer call with a bounded in-memory authorization derived by `DocumentAccessOperations` from committed V10 promotion evidence and the current authorized tenant/purpose context.
- [x] Add an explicit signed-access policy covering policy key, one-to-sixteen accepted purposes, one-second-to-one-hour maximum URL TTL, one-second-to-five-minute authorization age, and zero-to-one-minute future skew.
- [x] Add Flyway V11 URL-free access-grant evidence linked to promotion by composite tenant/document/version keys, with snapshotted policy/lifetime and request context.
- [x] Apply forced RLS, `SELECT`/`INSERT`-only runtime grants, PostgreSQL-time context/purpose/authorization/expiry validation, owner-level immutability, composite-link readiness checks, and missing-context invisibility.
- [x] Add an opt-in S3-compatible signer that requires the private clean bucket, rechecks current policy/authorization, verifies state/digest/type/size/ETag, downloads and hashes the complete clean object, signs only bounded `GET`, and validates the returned URL origin.
- [x] Keep signed access disabled unless `careos.documents.signed-access.enabled=true` and the explicit policy is valid; extend production preflight and the API architecture boundary.
- [x] Cover ordering, missing promotion, tenant/purpose/policy/authorization drift, signer/evidence failure, RLS/direct SQL/immutability, missing/corrupt clean objects, real signed retrieval, write denial, and capability activation.

Local evidence: a clean Java 25 build compiled 170 production sources and 16 test sources, applied all eleven migrations to disposable PostgreSQL 18, passed all 131 backend tests across PostgreSQL, Redis, pinned object storage, and deterministic ClamD fixtures, and packaged the bootable JAR. The focused signed-access/security run passed all 49 tests. Both Compose models resolve. The unchanged 15-operation/seven-convention API and all seven negative tests, 79-screen register, and CI-security verifier/all ten negative tests were rerun and pass. Frontend source is unchanged at its latest verified 29 unit tests and 18 desktop/mobile Playwright/Axe tests.

This completes only disabled-by-default internal signed-access mechanics. It does not approve a document-read permission or purpose, expose a download route, add an M7 document/provenance state machine, complete a governed audit/outbox read workflow, or approve production object storage/IAM/KMS/versioning/Object-Lock/backup/monitoring. Phase 0V later adds immutable retention/legal-hold-enablement mechanics, while governed release, disposal, and revocation remain open. Bearer URLs are never persisted and the coordinator cannot return one without committed V10 promotion evidence and matching V11 grant evidence. See `PLATFORM_CAPABILITIES.md`.

### Phase 0V - immutable document retention and legal-hold mechanics (completed 15 September 2026)

- [x] Replace the raw retention-port call with `DocumentRetentionOperations`, which requires committed V10 promotion evidence, the authorized tenant/purpose context, a matching explicit policy, and the latest durable retention state before constructing a bounded authorization.
- [x] Make directive replay durable and exact while rejecting changed replay content, retention shortening, legal-hold release, and no-op directives before storage I/O.
- [x] Add Flyway V12 append-only retention evidence linked to the exact promotion and exact predecessor directive, with snapshotted policy/context/deadline/hold data and only a SHA-256 provider-version identifier.
- [x] Apply forced RLS, `SELECT`/`INSERT`-only runtime grants, transaction-context/server-time insertion, per-document advisory serialization, monotonic predecessor checks, and owner-level update/delete rejection; verify all controls and missing-context invisibility at startup.
- [x] Add an opt-in S3-compatible adapter that requires an existing private versioned/Object-Lock-enabled clean bucket, verifies the exact current version and full ETag-bound SHA-256 content, applies only Object Lock `COMPLIANCE` retention, optionally enables legal hold, and verifies provider state afterward.
- [x] Never create the retention bucket at runtime, bypass governance mode, shorten retention, disable a hold, delete an object, or persist a raw bucket, object key, or provider version.
- [x] Keep retention disabled unless `careos.documents.retention.enabled=true`, private S3 storage is enabled with runtime bucket creation disabled, and an explicit bounded policy is configured; extend production preflight and the API architecture boundary.
- [x] Cover promotion/order/replay/failure/drift, monotonicity, policy bounds, RLS/direct-SQL/immutability, exact-version provider locking/deletion denial, content corruption, Object Lock readiness, activation, and production preflight.

Local evidence: a clean Java 25 build compiled 179 production sources and 17 test sources, applied all twelve migrations to disposable PostgreSQL 18, passed all 144 backend tests with zero failures, errors, or skips across PostgreSQL, Redis, pinned Object-Lock-capable storage, and deterministic ClamD fixtures, and packaged the bootable JAR. The focused retention/security run passed all 56 tests. Both Compose models resolve. The unchanged 15-operation/seven-convention API and all seven negative tests, 79-screen register, and CI-security verifier/all ten negative tests were rerun and pass. Frontend source is unchanged at its latest verified 29 unit tests and 18 desktop/mobile Playwright/Axe tests.

This completes only disabled-by-default immutable retention application and legal-hold enablement mechanics. It does not approve a jurisdictional schedule, permission, or purpose; expose an HTTP route; implement the M7 document/provenance lifecycle; release a hold; shorten retention; dispose of content; or approve production provider IAM/KMS/Object-Lock/backup/monitoring. Those policy-sensitive transitions remain fail-closed. See `PLATFORM_CAPABILITIES.md`.

### Phase 0W - UUIDv7 identifier strategy (completed 15 September 2026)

- [x] Add one shared RFC 9562 UUIDv7 generator for application-created identifiers, using a secure random source and synchronized process-local monotonic advancement for equal-millisecond generation and clock rollback.
- [x] Replace every production `UUID.randomUUID()` call with the shared generator, including correlation IDs, local identity bootstrap, idempotency, governance evidence, document scan attestations, and document-access grants.
- [x] Add Flyway V13 to require PostgreSQL 18 and switch all 12 active database-generated identifier defaults from `gen_random_uuid()` to native `uuidv7()`.
- [x] Preserve pre-V13 and caller-supplied UUID compatibility: do not rewrite historical rows or add version checks that would reject valid existing/reference identifiers.
- [x] Add an ArchUnit rule that rejects future direct production UUIDv4 generation and database catalog coverage that locks the exact expected UUIDv7 default set.
- [x] Document that UUIDv7 ordering and embedded timestamps are implementation properties, not authorization, secrecy, or trusted chronology evidence.

Local evidence: a clean Java 25 build compiled 180 production sources and 18 test sources, applied all thirteen migrations to disposable PostgreSQL 18, passed all 150 backend tests with zero failures, errors, or skips across PostgreSQL, Redis, pinned Object-Lock-capable storage, and deterministic ClamD fixtures, and packaged the bootable JAR. Four generator tests, one new architecture rule, and one PostgreSQL default test cover the slice; the focused generator/architecture and fresh-database runs also pass. Both Compose models resolve. The unchanged 15-operation/seven-convention API and all seven negative tests, 79-screen register, and CI-security verifier/all ten negative tests were rerun and pass. Frontend source is unchanged at its latest verified 29 unit tests and 18 desktop/mobile Playwright/Axe tests.

This completes current identifier generation strategy only. UUIDs remain opaque references, existing identifiers remain valid, and every future table/module must retain UUIDv7 generation while using server timestamps, tenant authorization, and database integrity for security and chronology.

### Phase 0X - reference authorization and governed identity administration (completed 15 September 2026)

- [x] Add Flyway V14 with a bounded `careos-phase0-reference-v1` permission, interactive-role, operation-risk, delegation-ceiling, and final-owner policy that remains ignored unless explicitly enabled outside production.
- [x] Add Flyway V15 and a governed invitation service for recent-authenticated, reason-bound, idempotent issue/revoke operations plus one-use expiry-checked acceptance and existing-account linkage, with atomic audit/outbox evidence and no raw token returned to an administrator.
- [x] Add Flyway V16 and a separate non-interactive service-identity authorization boundary with disjoint roles, exact tenant/purpose/operation checks, bounded expiring credential digests, row locking, and no browser-session or human-membership fallback.
- [x] Add Flyway V17 and an append-only maker-checker approval lifecycle for administrative MFA reset: the target cannot request or approve, maker and checker must differ, only the original maker may execute, approved evidence is consumed exactly once, and expired open evidence can be retired without deletion.
- [x] Revoke target MFA/recovery material and every session atomically with completed reset evidence; deliver post-commit notification/session-cache cleanup only on first execution, not idempotent replay.
- [x] Extend the checked OpenAPI/client/session boundary and M1-02/M1-03 browser states to invitation and administrative-reset transitions, including explicit reason, recent-authentication, retry-key retention, runtime response validation, safe token handling, accessibility, and desktop/mobile coverage.
- [x] Reject activation of the provisional reference policy, invitations, service identities, and MFA administration in the production preflight; keep canonical catalogs migration-owned/runtime-read-only and preserve deny-by-default behavior.
- [x] Preserve cycle-free modules by exposing only a shared authenticated-actor contract to tenancy rather than coupling tenant delivery code to the concrete identity principal.

Local evidence: a clean Java 25 build compiles 210 production sources and 18 test sources, applies all 17 migrations to disposable PostgreSQL 18, passes all 162 backend tests with zero failures, errors, or skips across PostgreSQL, Redis, pinned Object-Lock-capable storage, and deterministic ClamD fixtures, and packages the bootable JAR. The database suite covers the reference catalogs, delegation/final-owner attacks, invitation issuance/acceptance/linkage, disjoint service identities, and the complete MFA maker-checker lifecycle. The OpenAPI 3.1 version 0.8.0 contract covers 21 operations and passes seven conventions plus all seven negative tests. Frontend generation, its 18-source/35-import architecture boundary and four negative tests, strict typecheck, lint, formatting, all 35 unit tests, production build, and all 20 desktop/mobile Playwright/Axe tests pass. The 79-screen and CI-security contracts/all ten negative tests, both Compose models, and both current application container builds also pass. A fresh isolated hardened smoke reaches backend readiness at Flyway V17 and serves/proxies the frontend successfully under the declared non-root/read-only/capability restrictions; all temporary resources were removed.

This closes the repository work that can be safely implemented without inventing production policy. It does **not** approve the reference registry, permit its production activation, provision or rotate a production service credential, activate a worker, decide whether self-service MFA disable is allowed, or provide deployment-owned security/operations evidence. Those are explicit production-acceptance inputs below.

### Remaining Phase 0 production acceptance

- [x] Replace the provisional interactive registry with the checksum-approved Module 1 catalogue and enforce its exact mandatory-role MFA/self-disable rule.
- [ ] If optional viewer/editor organization-level MFA enforcement or self-disable is desired, approve its exact lifecycle/readiness contract first; bind every future protected business route to an approved operation as it is implemented.
- [x] Provide disabled-in-production reference implementations for governed invitation issuance/revocation/acceptance, existing-account linkage, and a separate non-interactive service-identity authorization path.
- [x] Enforce delegation ceilings, final-owner protection, recent authentication/reasons, and database-backed maker-checker administrative MFA reset for the reference policy.
- [ ] Extend V8's composite tenant-link pattern and add operation-specific database invariants as the first production business vertical slice is introduced.
- [x] Define fail-closed document, notification, Redis, worker, and scheduler ports.
- [x] Implement and integration-test policy-neutral private-quarantine storage mechanics behind the Phase 0F boundary, disabled by default.
- [x] Implement and integration-test policy-neutral quarantined malware-scanning mechanics behind the Phase 0F boundary, disabled by default.
- [x] Implement and integration-test policy-neutral Redis durable-job transport mechanics behind the Phase 0F boundary, disabled by default.
- [x] Implement and integration-test policy-neutral encrypted notification persistence/lease mechanics behind the Phase 0F boundary, disabled by default.
- [x] Persist transaction-bound, append-only quarantine metadata and scan attestations under forced RLS without activating promotion or document delivery.
- [x] Add a migration-owned consumer allow-list and transaction-bound, forced-RLS append-only inbox receipts with concurrent exact-delivery deduplication, without activating a transport or worker.
- [x] Add disabled-by-default, evidence-gated clean-promotion mechanics with an explicit policy snapshot, a distinct private clean bucket, and append-only forced-RLS proof without exposing document delivery.
- [x] Add disabled-by-default, promotion-evidence-gated signed-read mechanics with a purpose/TTL policy, full clean-object re-verification, and URL-free append-only forced-RLS grant evidence without exposing an HTTP route.
- [x] Add disabled-by-default, promotion-evidence-gated immutable retention and legal-hold enablement with S3 Object Lock `COMPLIANCE`, monotonic policy enforcement, exact provider-version verification, and append-only forced-RLS evidence without exposing release or disposal.
- [x] Standardize new application and database-generated identifiers on UUIDv7 without rewriting or rejecting historical/reference UUIDs.
- [x] Add immutable CI dependencies, dependency/SAST/secret/configuration/container gates, update automation, SBOM generation, digest-pinned images, and unprivileged application runtimes.
- [x] Establish protected tenant-route, pagination/filter, concurrency, idempotency/retry conventions plus generated frontend API types and a checked browser client.
- [x] Connect the checked identity/organization client to a fail-closed frontend session gate, real login/pending-MFA/selection/switch/logout states, and an enforced session feature boundary.
- [x] Connect public password recovery plus recent-authenticated M1-03 MFA enrollment/recovery-code self-service without persisting secrets.
- [x] Add server-derived idle/absolute deadline locking and event-driven browser session revalidation without a background heartbeat.
- [x] Add structured safe request telemetry, bounded disabled-by-default trace export, authenticated Prometheus format, dependency-correct probes, an outage drill, and the repository operational runbook.
- [x] Add explicit backend/frontend response headers, strict same-origin CSP, bounded proxy behavior, and a fail-closed production configuration preflight profile.
- [ ] Approve a production object-store/IAM/KMS design and complete its deployment, recovery, monitoring, and acceptance controls.
- [ ] Bind clean promotion, signed access, and retention application to an approved permission/event/document-state/read-audit workflow and production provider controls; design separately governed legal-hold release and disposal, and implement notification consent/destination/provider plus authorized worker and scheduler adapters, keeping each unavailable until its checklist passes.

The repository-level Phase 0 implementation is complete at the provisional reference-policy checkpoint. Production Phase 0 acceptance remains incomplete until the owner/environment items in `IMPLEMENTATION_GAPS.md` are approved, deployed, and evidenced; none may be converted to a code-only PASS.

## Phase 1 - M1 administration

### Phase 1A - architecture, source/mockup review, and gap ledger (completed 16 September 2026)

- [x] Re-read the authoritative Module 1 screen, entity, route, state-machine, API, security, UI, test, and acceptance requirements from the complete 46-page build specification.
- [x] Inspect all 23 registered administration routes and distinguish the server-backed Phase 0 reference identity states from the generic synthetic templates that existed at this checkpoint.
- [x] Confirm that the workspace does not contain the specification-mandated approved M1 mockups, CareOS Design System 1.0 assets, approved Module 1 policy/registry decisions, or an approval record.
- [x] Define the administration module boundary, tenant/governance/data/API/frontend/test invariants, dependency-ordered vertical slices, and a screen-by-screen traceability ledger in `MODULE_1_IMPLEMENTATION_PLAN.md`.
- [x] Preserve the stop condition: do not create production business migrations, endpoints, or screen behavior from the generic prototype or unapproved assumptions.

This is the required preimplementation checkpoint, not completion of an M1 product workflow. The remaining synthetic prototype routes are available only for navigation and discussion.

### Phase 1R - provisional organization-core reference slice (completed 16 September 2026)

- [x] Extend the existing organization aggregate in Flyway V18 with bounded legal/display name, country, IANA timezone, actor, timestamp, and monotonic revision enforcement; do not create a parallel tenant model.
- [x] Add reference-only readiness-read and profile-update operations, a least-privilege profile-management permission, and one versioned audit/outbox event pair; retain migration ownership and production activation rejection.
- [x] Implement tenant-authorized M1-05/M1-06 server readiness projections and bounded counts, plus M1-07 profile read/update through the administration domain/application/infrastructure/API boundary.
- [x] Require a strong profile ETag, exact `If-Match`, caller-owned `Idempotency-Key`, explicit reason, exact replay, stale-write rejection, and atomic business/audit/outbox/idempotency commit.
- [x] Replace the three generic routes with a selected-organization-scoped administration feature that has runtime response validation and accessible loading, error/retry, validation, stale-conflict, and success states.
- [x] Expand OpenAPI to version 0.9.0 and the generated checked client from 21 to 24 operations; retain CSRF, correlation, RFC 9457, session-deadline, and no-automatic-mutation-retry guarantees.
- [x] Add HTTP integration and direct PostgreSQL authorization/RLS/trigger attack coverage, frontend unit/client coverage, and desktop/mobile Playwright/Axe coverage.

Local evidence: a clean Maven 3.9.11/Java 25 build compiles 219 production sources and 18 test sources, applies all 18 migrations to disposable PostgreSQL 18, passes all 164 backend tests with zero failures, errors, or skips across PostgreSQL, Redis, pinned Object-Lock-capable storage, and deterministic ClamD fixtures, and packages the bootable JAR. OpenAPI 3.1 version 0.9.0 verifies all 24 operations/seven conventions and all seven negative tests. Frontend API drift, its 20-source/45-import two-feature architecture boundary and four negative tests, strict typecheck, lint, formatting, all 40 unit tests, production build, and all 22 desktop/mobile Playwright/Axe tests pass. The 79-screen register, CI-security verifier/all ten negative tests, both Compose models, and both current application image builds also pass.

This is an executable engineering reference, not production completion of M1C. The profile field set, readiness gates/counts, permissions, operations, event names, content, and visuals remain provisional. Production preflight still rejects the reference registry, and M1-08 through M1-23 remain synthetic until approved inputs support their delivery slices.

### Phase 1S - current-image deployment and supply-chain assurance (completed 16 September 2026)

- [x] Correct the official PostgreSQL 18 Compose data-volume target from the rejected legacy `/var/lib/postgresql/data` mount to the supported major-version parent `/var/lib/postgresql`.
- [x] Extend the dependency-free security contract so a PostgreSQL 18 service using the legacy mount fails verification; retain the existing ten positive/negative security tests.
- [x] Start all six services from fresh isolated volumes and prove Flyway V18, one synthetic bootstrap organization, backend liveness/readiness, the frontend root, strict response headers, and its 79-screen same-origin API proxy.
- [x] Prove the current backend and frontend run as non-root, reject root-filesystem writes, retain writable bounded `/tmp` mounts, drop all capabilities, enable no-new-privileges, and reject anonymous metrics with `401`.
- [x] Run Trivy 0.74's repository and final-image HIGH/CRITICAL gates with `ignore-unfixed`, then generate and parse CycloneDX SBOMs for both current images.
- [x] Remove every isolated smoke container, network, volume, temporary image, and scanner-cache volume after verification.

Local evidence: both Compose models and all ten CI-security contract tests pass with the new PostgreSQL 18 mount assertion. A fresh isolated six-service deployment reaches healthy backend/frontend state at Flyway V18; the backend runs as `65532:65532`, the frontend as `nginx`, root writes fail, `/tmp` writes succeed, all capabilities are dropped, no-new-privileges is set, anonymous metrics return `401`, the security-header-bearing frontend serves successfully, and its proxy returns all 79 registered screens. Trivy 0.74 reports zero fixed HIGH/CRITICAL findings in the Maven/npm source manifests, Debian 13.6 runtime, packaged application JAR, and Alpine 3.24.1 runtime; both Dockerfiles report zero misconfigurations and no secret finding is emitted. Parsed CycloneDX 1.7 outputs contain 199 backend and 22 frontend components. All temporary resources were removed.

This closes the repository-local current-image assurance gap only. Hosted workflow execution, retained SBOM artifacts, signing/provenance, registry admission, deployment verification, production infrastructure, and product-owner acceptance remain external requirements.

### Phase 1T - executable Module 1 input-approval gate (completed 16 September 2026)

- [x] Add a checked JSON Schema and repository state manifest for the eight required Module 1 input bundles while retaining the truthful `BLOCKED_INPUT` state.
- [x] Add a dependency-free verifier for canonical artifact categories, bounded versions, repository-contained regular-file paths, exact SHA-256 content, duplicate categories/paths, and package completeness.
- [x] Bind an approved claim to a distinct checksum-verified approval-evidence file, one non-placeholder/non-future approval record, the ordered M1-01 through M1-23 scope, and a deterministic digest of the exact eight-artifact package.
- [x] Separate consistency verification from authorization: the normal command accepts an honestly blocked or fully approved manifest, while `--require-approved` fails closed until the complete package and approval validate.
- [x] Add fourteen focused tests for blocked, partial, complete, path-escape, missing-file, checksum, duplicate, false-approval, screen-scope, package-digest, placeholder, and future-evidence behavior plus schema/executable alignment.
- [x] Run the gate and its tests in the maintained quality workflow and project verifier; extend the security contract so removal of either workflow command is rejected.

Local evidence: `node scripts/verify-module-1-inputs.mjs` verifies the checked manifest and reports `BLOCKED_INPUT`, zero of eight artifacts, all eight exact missing categories, no approval, and `implementationAuthorized: false`. The same command with `--require-approved` exits nonzero. All fourteen input-gate tests pass, including a checksum-bound complete synthetic approval fixture. The repository security verifier passes with all eleven attack tests and proves that the quality workflow cannot silently omit the gate or its test suite.

This implements approval evidence mechanics, not approval itself. No artifact bundle or owner record was invented, all production checklist items below remain unchecked, and the manifest must remain `BLOCKED_INPUT` until the real files and exact approval are supplied.

### Phase 1U - environment-scoped foundation seed strategy (completed 16 September 2026)

- [x] Preserve the immutable historical V1 migration while adding Flyway V19 to remove its reserved synthetic organization and facility from default and production migration paths.
- [x] Retain that fixture only through explicit hard-coded `local` and `test` Flyway placeholders; hard-disable it in the base and `production` configurations.
- [x] Make upgrades fail closed when the reserved organization/profile or facility has changed, contains additional facilities, or has tenant data referenced by any foreign key; never cascade-delete or silently rewrite tenant data.
- [x] Add a disposable PostgreSQL 18 migration test that proves changed fixture rollback, absence of a V19 success record after failure, successful retry after explicit remediation, and a zero-tenant final production/default state.
- [x] Extend the dependency-free repository contract and its mutation tests so profile scoping, V19 cleanup, and foreign-key failure handling cannot be removed silently.

Local evidence: the focused production/default migration test and the existing local/test tenant-RLS suite both pass against PostgreSQL 18. A clean Maven 3.9.11/Java 25 build compiles 219 production and 19 test sources, validates and applies all 19 migrations, passes all 165 backend tests with zero failures, errors, or skips, and packages the bootable JAR. The repository security verifier and all twelve positive/negative tests pass.

V19 deliberately refuses to guess when the historical reserved fixture has become real tenant data. Such an upgrade must migrate or remove that tenant explicitly before retrying; this preserves data and keeps production bootstrap fail-closed.

### Phase 1V - fail-closed protected route resolution (completed 16 September 2026)

- [x] Remove the screen registry's silent M1-05 fallback so an unknown identifier cannot become a real or synthetic business page.
- [x] Admit protected content only for an ID in the checked 79-screen registry after authentication and organization selection; preserve the anonymous, MFA, organization-selection, and no-organization gates ahead of route disclosure.
- [x] Let the authenticated shell represent no active screen without falsely highlighting a dashboard link.
- [x] Add a focused, non-reflective not-found state that confirms no business page loaded, moves keyboard focus to its heading, and provides one explicit recovery link to M1-05.
- [x] Cover strict registry lookup, authenticated rejection, anonymous-session precedence, desktop/mobile recovery, and Axe accessibility.

Local evidence: generated API drift, the 21-source/48-import two-feature architecture boundary and all four negative fixtures, strict typecheck, lint, full formatting, all 42 unit tests, and the production frontend build pass. All 24 desktop/mobile Playwright cases pass, including the complete 79-route Axe sweep and the new unknown-route focus/recovery case on both desktop and the 320px viewport. Backend, migration, and API behavior did not change; the Phase 1U 165-test/Flyway V19 evidence remains the backend baseline.

This closes the unsafe default-route behavior only. The hash router, generic prototype pages, approved route hierarchy, Design System, and screen-specific business states remain part of the input-blocked production work below.

### Phase 1W - exact responsive-breakpoint assurance (completed 16 September 2026)

- [x] Replace the two implicit browser viewport projects with exact 1440, 1024, 768, 390, and 320 pixel projects required by the specification.
- [x] Preserve mobile/touch semantics for 390/320 and prove that the drawer boundary applies only at 760 pixels and below, while 768 retains usable persistent navigation.
- [x] Extend every registered-route sweep with body/document horizontal-overflow rejection and bounded offending-element diagnostics alongside the existing route-specific Axe checks.
- [x] Fix the detected M1/M2 list-table overflow by making the table scroller a positioned, shrinkable, 100%-bounded containing block for its screen-reader-only header content.
- [x] Exercise the complete identity, recovery, invitation, MFA, administration, session-expiry, unknown-route, and workspace-navigation browser suite in all five projects.
- [x] Extend the dependency-free repository contract to require every exact viewport/device project and the document/body, registered-route, identity-route, exact-width, and drawer-boundary assertions; prove removal and weakening fail its mutation suite.

Local evidence: generated API drift, the 21-source/48-import architecture boundary and all four negative fixtures, strict typecheck, lint, full formatting, all 42 unit tests, and the production build pass. The initial expanded run correctly failed M1-12 and M2-02 at 768/390/320 with document widths reaching the table's intrinsic content; all six focused failing module/viewport cases pass after the containment fix. The final run passes all 60 Playwright cases in 2.4 minutes, covering 395 registered-route viewport renders plus every checked browser workflow with no serious Axe finding or page-level overflow. The repository verifier and all thirteen CI-security contract tests pass with exact responsive-matrix enforcement. Backend behavior is unchanged from the Phase 1U 165-test/Flyway V19 baseline.

This is exact responsive assurance for the current reference UI, not acceptance of production mockups or Design System behavior. Approved screen-specific table/card alternatives, interaction layouts, complete keyboard/dialog focus behavior, and formal WCAG 2.2 AA acceptance remain in the gate below.

### Phase 1X - honest synthetic-interaction containment (completed 16 September 2026)

- [x] Put a named synthetic-only notice before every generic page, explicitly prohibit real personal/clinical data entry, replace plausible sample people and records with unambiguous synthetic labels, and make generic form/clinical fields read-only previews.
- [x] Remove browser-local save/confirmation success simulation and expose unimplemented `Review`, `Open`, save, and clinical confirmation actions as natively disabled controls with accessible reasons.
- [x] Remove generic row links that falsely implied record routing; retain prototype pagination only for valid destinations and render endpoints as non-links rather than keyboard-active `aria-disabled` anchors.
- [x] Make search/status/scope filtering and `Clear filters` truthful controlled interactions over the visible synthetic records, including a polite result count and correct initial disabled state.
- [x] Preserve keyboard access to horizontally scrollable tables by naming and focusing the scroll region with a visible focus ring after the expanded Axe sweep detected the missing focus target.
- [x] Add focused component coverage and a complete interaction-boundary browser case to all five exact viewport projects without creating a production schema, endpoint, permission, event, or workflow.

Local evidence: generated API drift, the 21-source/48-import architecture boundary and all four negative fixtures, strict typecheck, lint, full formatting, all 45 unit tests, and the production build pass. The first complete run correctly detected the newly non-focusable table scroller at the four widths where it overflowed; all fifteen focused M1/M2/interaction cases then passed after the named focusable-region fix. The final run passes all 65 Playwright cases across 1440/1024/768/390/320, covering 395 registered-route viewport renders, the new honest-interaction scenario in every project, serious/critical Axe rejection, and document/body overflow checks. Backend behavior remains at the Phase 1U 165-test/Flyway V19 baseline.

This closes deceptive or inert behavior in the generic reference pages only. Those pages still contain no approved product data model or workflow; their disabled actions may be enabled only by an approved, authorized, persisted, audited, tenant-safe vertical slice after the input gate below passes.

### Phase 1Y - Module 1 owner-review draft packet (completed 16 September 2026)

- [x] Create one source-grounded review brief for each of the eight canonical Module 1 input categories, covering all M1-01 through M1-23 screen states and the current reference architecture without treating proposals as settled product behavior.
- [x] Mark every file and the manifest `DRAFT_NOT_APPROVED`, keep the packet outside `approved-inputs/module-1/`, and document the explicit owner-review-to-production-evidence handoff.
- [x] Separate proposed review baselines from unresolved owner decisions and acceptance criteria for designs, data/validation, transitions, authorization, events, readiness/activation, and history/export.
- [x] Add a dependency-free verifier that requires all eight exact canonical files, draft-only metadata, required review sections, and all 23 M1 screen IDs while always reporting `implementationAuthorized: false`.
- [x] Add seven focused contract tests and require both the verifier and its tests in normal local/CI quality checks and the repository security contract.

Local evidence: `node scripts/verify-module-1-review-drafts.mjs` verifies all eight briefs and reports `DRAFT_NOT_APPROVED`, package digest `bee95ca0ddb74d14256d2dd80fe9565021e64a9f6feb6124b7a1110335a3fe63`, and `implementationAuthorized: false`. All seven review-draft tests and all thirteen CI-security contract tests pass. The production approval verifier remains intentionally fail-closed at `BLOCKED_INPUT`, zero of eight approved artifacts, and no approval.

This phase removes the blank-page review problem; it does not make product decisions on an owner's behalf. The checklist below remains open until authorized owners revise/accept final artifacts and the checksum-bound production gate passes.

### Phase 1Z - concrete Module 1 candidate input package (completed 16 September 2026)

- [x] Convert all eight review categories into one versioned `m1-candidate-1` package with explicit working decisions instead of unresolved questions.
- [x] Provide a self-contained interactive M1-01 through M1-23 review mockup with responsive navigation, table/card behavior, representative states, focus/recovery behavior, and operation/permission/assurance annotations.
- [x] Freeze proposed Design System tokens/components; data types/bounds/classifications; lifecycles/transitions; role/grant/assurance policy; event schemas/consumers; readiness catalogue/freshness; and history/export limits, safety, retention, and worker contracts.
- [x] Keep every artifact `CANDIDATE_FOR_APPROVAL` under `candidate-inputs/module-1/`, separate from both drafts and production evidence, with no network, persistence, or implementation authority.
- [x] Add an exact manifest/verifier, eight positive/negative tests, quality/security integration, and five browser/Axe/overflow cases at 1440/1024/768/390/320.

Local evidence: all eight candidate artifacts validate at package digest `c2087548aacd35eb4927532d63b844851c6fe9c56cccb56e46596d707d7a1a53` with `implementationAuthorized: false`. All eight candidate tests and all thirteen repository-security tests pass. Generated API drift, architecture, typecheck, lint, formatting, all 45 frontend unit tests, and the production build pass. The complete Playwright run passes 70 cases in 3.8 minutes. The production gate remains `BLOCKED_INPUT`, zero of eight approved artifacts, and no approval.

This was the final preapproval checkpoint. The exact candidate was subsequently approved unchanged and promoted in Phase 1AA below; the candidate directory itself remains non-authorizing provenance.

### Phase 1AA - checksum-bound Module 1 input approval (completed 16 September 2026)

- [x] Promote the exact eight `m1-candidate-1` artifacts byte-for-byte into `approved-inputs/module-1/` without silently revising the accepted proposal.
- [x] Record approval by **bhupendra, developer**, decision time, all M1-01 through M1-23 screen IDs, the package digest, candidate digest, and distinct approval-evidence checksum in `M1-APPROVAL-20260916-01`.
- [x] Change the production manifest to `APPROVED`, eight of eight supplied artifacts, and `implementationAuthorized: true` only after the independent verifier calculated and accepted every checksum.
- [x] Make the checked approved repository state a positive test while retaining synthetic blocked/partial/tampered/future/placeholder negative fixtures.
- [x] Run both normal and `--require-approved` modes plus all fourteen input-gate tests.

Local evidence: both production verifier modes report `APPROVED`, 8/8, no missing artifacts, and package SHA-256 `19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946`; all fourteen input-gate tests pass. The retained candidate digest is `c2087548aacd35eb4927532d63b844851c6fe9c56cccb56e46596d707d7a1a53`, and authority comes only from the production record.

This completes the input gate, not Module 1 delivery or target-environment acceptance. Any approved-file drift must fail required-approval verification until a new accountable record binds the replacement package.

### Phase 1AB - approved authorization release and first M1B identity/access increment (completed 16 September 2026)

- [x] Add Flyway V20 with immutable checksum-bound `authorization_registry_releases` evidence for the exact approved package and authorization artifact.
- [x] Activate the approved interactive roles, permission families, exact grants, and delegation edges while retaining `local_bootstrap`, old member policy, and non-interactive service roles as reference-only.
- [x] Promote existing organization profile/readiness bindings and replace invitation/MFA operation and event bindings with approved `identity.*`/`access.*` keys.
- [x] Require a separately recorded recent MFA assertion, in addition to recent primary authentication, for invitation issue/revoke and every administrative MFA-reset stage; expose a fail-closed `428 mfa-required` response.
- [x] Preserve database-enforced maker/checker/target separation, final-owner safety, exact approval consumption, append-only evidence, and approved-operation organization-profile trigger checks.
- [x] Permit production invitation/MFA activation only for exact registry `m1-candidate-1` and package digest `19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946`; retain disabled defaults and reject the reference-policy flag.
- [x] Replace the obsolete invitation role choice with approved non-owner roles and default to `organization_viewer`; align the checked API descriptions and generated client.

Local evidence: Flyway validates and applies V20 on fresh PostgreSQL 18; the 14-case identity HTTP suite passes, including missing-MFA denial without invitation or notification side effects; all 33 tenant/RLS/authorization cases pass, including approved-release immutability. A clean Maven 3.9.11/Java 25 build passes all 166 backend tests and packages the bootable JAR. Frontend API drift, architecture, formatting, strict typecheck, lint, all 45 unit tests, the production build, and all 70 five-viewport Playwright/Axe cases pass. This verifies the increment; full M1B and Module 1 acceptance remain governed by the open delivery checkpoints below.

At this checkpoint M1-20 membership listing, mutations, owner transfer, action projection, and acceptance remained open. Phase 1AC below resolves the list/read and action-projection portion only.

### Phase 1AC - authorized M1-20 membership read (completed 17 September 2026)

- [x] Add Flyway V21 with active approved `access.membership.read`, the existing approved permission binding, hidden denial, and no unapproved mutation/event capability.
- [x] Implement `GET /api/v1/organizations/{organizationId}/memberships` inside an actor-bound tenant transaction with forced RLS and an approved minimum-field projection.
- [x] Enforce NFC-normalized literal search, allow-listed access-state/role filters, unknown/duplicate query rejection, deterministic descending keyset order, and limit-plus-one paging.
- [x] Sign opaque cursors with HMAC-SHA256; bind operation, tenant, normalized filters, limit, snapshot, and keyset position; enforce a 15-minute expiry and bounded future skew.
- [x] Compute actions from current live approved permissions: expose only page-level invitation and eligible-row MFA-reset navigation, never inferred role/scope/revoke/owner actions.
- [x] Replace the synthetic M1-20 route with runtime-validated loading, failure/retry, empty/no-result, filter, cursor-history, responsive-table, and permission-projected navigation states.
- [x] Extend OpenAPI to version 0.10.0 and 25 operations, regenerate the checked client, and add backend authorization/cursor attacks, client/unit tests, and all-five-viewport browser/Axe coverage.

Local evidence: a clean Maven 3.9.11/Java 25 build compiles 223 production sources and 19 test sources, applies all 21 migrations to disposable PostgreSQL 18, passes all 168 backend tests, enforces all 11 architecture rules, and packages the bootable JAR. OpenAPI 0.10.0 verifies all 25 operations and seven conventions; all seven negative tests pass. Frontend API drift, the 21-source/48-import architecture boundary and four negative fixtures, strict typecheck, lint, formatting, all 47 unit tests, the production build, and all 75 browser tests across 1440/1024/768/390/320 pass. The 79-screen, approved-input, review, candidate, and repository-security contracts also pass.

This completed M1-20 list/read, server filtering/cursor paging, and safe action projection. At that checkpoint M1B remained open for maker-checker role/scope change and revocation, final-owner-safe owner transfer, approved final visual acceptance, complete mutation attack/browser regression, target-environment evidence, and owner acceptance. Phase 1AD below resolves organization-wide non-owner role changes and revocation.

### Phase 1AD - governed non-owner membership role change and revocation (completed 17 September 2026)

- [x] Add Flyway V22 with immutable forced-RLS `membership_change_requests`, optimistic membership revisions, exact approved request/approve/execute operations, 30-minute approvals, and approved `identity.membership.changed`/`identity.membership.revoked` audit and outbox bindings.
- [x] Enforce organization-wide non-owner targets, self/target separation, maker/checker separation, delegation ceilings, final-owner safety, NFC/control-free 10-500-code-point reasons, recent primary authentication plus MFA, and exact revision/approval/reason binding in both application and PostgreSQL boundaries.
- [x] Implement idempotent request, independent approval, and execution endpoints with strong membership ETags, `428` missing-precondition behavior, `412` stale revisions, hidden target denial, no-store responses, exact replay, and conflict-safe execution.
- [x] Extend M1-20 live action projection with authorized role-change/revocation row actions and approve/execute page actions without advertising owner transfer or facility-scoped grants.
- [x] Add the runtime-validated governed access-change panel for request, approval, and execution; preserve caller-owned idempotency keys and refresh the membership projection after execution.
- [x] Advance OpenAPI to version 0.11.0 and 28 operations, regenerate the checked TypeScript client, and add HTTP, direct-SQL, transport, UI, accessibility, overflow, and five-viewport regression coverage.
- [x] Keep production membership administration disabled by default and bind any activation to the exact approved registry/package digest through the production configuration guard.

Local evidence: a Maven 3.9.11/Java 25 verification build compiles 232 production sources and 19 test sources, validates and applies all 22 migrations to disposable PostgreSQL 18, passes all 170 backend tests with zero failures, errors, or skips, enforces all 11 architecture rules, and packages the bootable JAR. OpenAPI 0.11.0 verifies all 28 operations and seven conventions. Frontend API drift, the 21-source/48-import architecture boundary and four negative fixtures, strict typecheck, lint, formatting, all 49 unit tests, the production build, and all 80 browser tests across 1440/1024/768/390/320 pass.

This completes organization-wide non-owner membership role change and revocation only. M1B remains open for facility-scoped grants/scope changes, final-owner-safe owner transfer, final visual/owner acceptance, target-environment evidence, and full slice acceptance.

### Phase 1AE - governed final-owner-safe owner transfer (completed 17 September 2026)

- [x] Add Flyway V23 with immutable forced-RLS `owner_transfer_requests`, exact approved permission bindings, a 30-minute approval workflow, and approved final `identity.owner.transferred` audit/outbox evidence.
- [x] Govern both non-owner-to-owner promotion and owner-to-approved-non-owner demotion with strong membership revisions, exact reason binding, recent primary authentication plus MFA, caller-owned idempotency, and maker/checker/target separation.
- [x] Enforce the exact request, approval, executor, membership, role, revision, reason, and expiry in PostgreSQL; require an indefinite promotion target, enforce the demotion delegation ceiling, and preserve at least one indefinite active owner.
- [x] Add request, independent-approval, and maker-execution endpoints with hidden target denial, `428`/`412` precondition behavior, exact replay, no-store responses, and conflict-safe execution.
- [x] Project owner-transfer row/page actions from current live permissions and extend the M1-20 governed access panel with owner promotion/demotion states without advertising facility scope.
- [x] Advance OpenAPI to version 0.12.0 and 31 operations, regenerate the checked TypeScript client, and add HTTP, direct-SQL, transport, UI, accessibility, overflow, and five-viewport promotion coverage.
- [x] Keep membership administration disabled by default and retain exact approved registry/package-digest production activation guards.

Local evidence: a Maven 3.9.11/Java 25 clean verification compiles 232 production sources and 19 test sources, validates and applies all 23 migrations to disposable PostgreSQL 18, passes all 171 backend tests with zero failures, errors, or skips, enforces all 11 architecture rules, and packages the bootable JAR. OpenAPI 0.12.0 verifies all 31 operations and seven conventions. Frontend API drift, architecture, strict typecheck, lint, formatting, all 51 unit tests, the production build, and all 85 browser tests across 1440/1024/768/390/320 pass.

This completes final-owner-safe owner transfer. M1B remains open for facility-scoped membership grants/scope changes, final visual/owner acceptance, target-environment evidence, and full slice acceptance. The approved package states the facility-scope narrowing rule but does not define the grant record, lifecycle, or exact enforcement contract; that contract must be approved before implementation.

### Phase 1AF - approval-ready facility-scope contract (completed 17 September 2026)

- [x] Trace the approved Module 1 package and complete build specification to confirm that neither defines the facility-grant record, set-replacement lifecycle, role-transition interaction, facility derivation, API projection, or database enforcement semantics.
- [x] Add one exact additive contract for explicit `organization`/`facilities` modes, eligible editor/viewer roles, temporal `access_assignments`, immutable scope requests, canonical set digests, atomic full-set replacement, role/revoke/owner interactions, facility-aware permission intersection, HTTP/UI projection, and approved six-key final evidence.
- [x] Bind the candidate to `m1-candidate-1`, approval record `M1-APPROVAL-20260916-01`, and approved package digest `19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946` without changing the approved artifacts.
- [x] Add a path-safe, exact-shape verifier and seven mutation tests that reject false approval, base-package drift, descriptor drift, missing security decisions, unresolved markers, and any implementation-authorized claim.
- [x] Run the additive candidate verifier and tests in the maintained quality workflow, and make the CI-security contract reject removal of either command.
- [x] Preserve the authorization boundary: no migration, API, server action, or UI control implements facility scope before a separate accountable approval binds the exact candidate digest.

Local evidence: `m1-facility-scope-candidate-1` verifies at SHA-256 `76a3f7a2c63cef0cab02b8a1d38a66220eee0abb99c9be0a82b64eaa8941fddb`; all seven candidate tests and all thirteen CI-security tests pass. The verifier reports `CANDIDATE_FOR_APPROVAL` and `implementationAuthorized: false`.

This resolves the contract-authoring gap only. Facility-scope implementation remains blocked until an approval record names that exact candidate version and digest plus an accountable approver identity/role and approval time.

### Phase 1AG - approved mandatory-role MFA enforcement (completed 17 September 2026)

- [x] Add Flyway V24, bound to the exact approved release, with an interactive-role `mfa_required` flag set only for owner, administrator, configuration approver, security administrator, auditor, and export approver.
- [x] Maintain a non-tenant-disclosing, membership-derived MFA requirement projection and expose only the boolean `careos_user_requires_mfa(uuid)` function to the runtime role.
- [x] Split enabled MFA from required MFA in persisted account lookup and principal authorities; return `mfa_enrollment_required` with HTTP `202` when a mandatory-role account has no enabled factor.
- [x] Restrict that session to logout/session and MFA enrollment/verification, keep organization data and workspace content locked, rotate the session after verification, record primary/MFA evidence, and require browser acknowledgement of one-time recovery codes before workspace bootstrap.
- [x] Re-evaluate the requirement on protected requests so an already authenticated password-only session is invalidated immediately after a mandatory role becomes effective.
- [x] Reject enabled-factor removal at the PostgreSQL boundary unless it is the exact consumed `identity.mfa.admin-reset.execute` operation with matching organization, actor, approval, target, correlation, and executor evidence; expose no self-disable endpoint.
- [x] Replace the approximate administrator-access readiness gate with exact non-overrideable `access.final_owner` and `access.mfa_enforced` calculations from approved policy.
- [x] Advance OpenAPI to version 0.13.0 without inventing a new operation, regenerate the checked client, and add login/enrollment, promotion/session-invalidation, readiness, direct-SQL, unit, responsive, accessibility, and overflow coverage.

Local evidence: a Maven 3.9.11/Java 25 clean verification compiles 232 production sources and 19 test sources, validates and applies all 24 migrations to disposable PostgreSQL 18, passes all 174 backend tests with zero failures, errors, or skips, enforces all 11 architecture rules, and packages the bootable JAR. OpenAPI 0.13.0 verifies all 31 operations and seven conventions; all seven negative tests pass. Frontend API drift, the 21-source/48-import architecture boundary and four negative fixtures, strict typecheck, lint, formatting, all 52 unit tests, the production build, and all 90 browser/Axe/overflow tests across 1440/1024/768/390/320 pass.

This completes the approved mandatory-role MFA and active-membership self-disable boundary. It does not approve the facility-scope candidate or define optional viewer/editor organization-level enforcement. M1B remains open for facility-scope approval/implementation, final visual/owner acceptance, target-environment evidence, and full slice acceptance.

### Phase 1AH - approved M1-20 responsive record-card projection (completed 17 September 2026)

- [x] Reconcile the production M1-20 result layout with the approved design-system rule that action-heavy tables become record cards at drawer widths.
- [x] Keep the named keyboard-focusable result region and the wide table at 768px, 1024px, and 1440px.
- [x] Render semantic membership record cards at 390px and 320px with administrator, role, access, MFA, effective-period, and action content equivalent to the table.
- [x] Reuse one server-authorized `MembershipActions` component for table rows and cards so viewport changes cannot create or remove authority.
- [x] Retain the explicit facility-scope unavailable boundary and make no API, database, permission, or mutation change.
- [x] Add compact-projection unit coverage and explicit card-versus-table assertions to the full five-viewport browser/Axe/overflow matrix.

Local evidence: a fresh Maven 3.9.11/Java 25 containerized clean verification compiles 232 production and 19 test sources, validates/applies all 24 migrations, passes all 174 backend tests with zero failures, errors, or skips, enforces all 11 architecture rules, and packages the JAR. Generated API drift, the 21-source/48-import frontend architecture boundary and four negative fixtures, formatting, strict typecheck, lint, all 53 unit tests, the production build, and all 90 Playwright/Axe/overflow cases pass. The updated M1-20 test proves cards with no table at 390/320 and a table with no card list at 768/1024/1440; the 79-screen, 31-operation, CI-security, approved-input, draft, candidate, and facility-scope-candidate repository verifiers also pass.

This resolves the approved responsive table/card projection for M1-20, not final M1B acceptance. Facility-scope approval/implementation, remaining M1-01 through M1-04 visual reconciliation, explicit owner acceptance, and target-environment evidence remain open.

### Phase 1AI - approved identity/access frame and content conformance (completed 17 September 2026)

- [x] Reconcile the shared M1-01 through M1-04 identity frame and authenticated workspace frame with the approved font stack and primary content.
- [x] Add a keyboard-visible `Skip to main content` link to both frames without allowing the fragment-router URL to change.
- [x] Focus each newly rendered identity or workspace route heading while preserving higher-priority focus already placed on a problem summary.
- [x] Align the M1-01 through M1-04 registry titles/purposes and the approved `Continue securely`, `Invite administrator`, and `Open workspace` primary actions.
- [x] Add unit coverage for identity/workspace heading focus and hash-safe skip navigation, and exercise the behavior across the exact five-viewport browser matrix.

Local evidence: frontend formatting, lint, strict typecheck/build, generated-client drift, the 21-source/48-import architecture boundary and four negative fixtures, all 54 unit tests, and all 90 Playwright/Axe/overflow cases pass. The browser suite explicitly checks identity and workspace heading focus plus hash-safe skip navigation at 1440, 1024, 768, 390, and 320 pixels. The 79-screen, 31-operation, CI-security, approved-input, draft, candidate, and facility-scope-candidate repository verifiers also pass. No backend, OpenAPI, or database source changed in this increment; the immediately preceding fresh Maven 3.9.11/Java 25 baseline remains 174 passing backend tests through Flyway V24.

This closes the shared frame, route-focus, font, metadata, and primary-action content portion of M1-01 through M1-04, not final M1B acceptance. Facility-scope approval/implementation, remaining screen-specific identity states, any additional invitation inspection/resend/delivery contract work, explicit owner acceptance, and target-environment evidence remain open.

### Phase 1AJ - approved identity validation and M1-03 action conformance (completed 17 September 2026)

- [x] Replace passive local validation messages with a reusable focused error summary for identity forms and local governed-action preparation failures.
- [x] Associate password-reset and invitation-acceptance validation with the exact invalid input through `aria-invalid` and `aria-describedby`, using the approved two-pixel danger treatment.
- [x] Provide a keyboard-operable field link that focuses the invalid control without allowing the fragment-router URL to change.
- [x] Give malformed reset/invitation token states and local invitation/MFA preparation failures the same deterministic error-summary focus behavior.
- [x] Align the remaining M1-03 primary enrollment action with the approved `Set up authenticator` content.
- [x] Prove password-reset and invitation-validation focus, association, containment, Axe, and hash preservation in unit tests and all five exact viewport projects.

Local evidence: generated API drift, formatting, lint, strict typecheck, production build, the 22-source/49-import frontend architecture boundary and four negative fixtures, all 55 unit tests, and all 90 Playwright/Axe/overflow cases pass. All 79-screen, 31-operation, CI-security, approved-input, draft, candidate, and facility-scope-candidate repository verifiers plus their 56 combined negative tests pass. No backend, OpenAPI, or database source changed in this increment; the current clean Maven 3.9.11/Java 25 baseline remains 174 passing backend tests through Flyway V24.

This closes the approved local identity-validation focus/association contract and final M1-03 primary-action copy gap, not final M1B acceptance. Facility-scope approval/implementation, invitation inspection/resend/delivery behavior requiring separately authorized contracts, explicit owner acceptance, target-environment evidence, and full slice acceptance remain open.

### Phase 1AK - exact approved readiness catalogue projection (completed 17 September 2026)

- [x] Replace all nine provisional readiness entries with the 15 exact ordered `m1-readiness-v1` gate keys and the approved `complete`, `warning`, `blocked`, and `not_applicable` outcomes.
- [x] Add domain invariants and checked OpenAPI 0.14.0 fields for catalogue/gate version, organization revision, database evaluation/expiry time, exact outcome counts, stable reason/remediation codes, bounded evidence references, and a 900-second freshness window.
- [x] Keep missing authoritative entity/evaluator families fail-closed: only exact final-owner and mandatory-MFA checks can complete; absent organization/network/service/configuration/registry/dependency evaluators do not receive synthetic success.
- [x] Derive every gate destination from the caller's live permission projection and fall back to M1-06 rather than exposing an unreadable M1-20/M1-21 target.
- [x] Split M1-05 into its approved metric/priority-exception dashboard and M1-06 into the complete ordered checklist with explicit live-projection versus persisted-validation scope.
- [x] Fail client rendering on catalogue version/order/count/outcome/freshness/evidence/link drift and lock the contract with a new API negative case, backend domain/HTTP tests, frontend unit coverage, and the five exact browser widths.

Local evidence: a clean Maven 3.9.11/Java 25 verification compiles 232 production and 20 test sources, validates and applies all 24 migrations, passes all 177 backend tests with zero failures, errors, or skips, enforces all 11 architecture rules, and packages the bootable JAR. OpenAPI 0.14.0 retains 31 operations and seven conventions; all 8 negative tests pass. Generated-client drift, the 22-source/49-import frontend architecture boundary and four negative fixtures, formatting, strict typecheck, lint, all 56 unit tests, the production build, and all 95 Playwright/Axe/overflow cases across 1440/1024/768/390/320 pass.

This starts M1C but does not complete it. At this checkpoint exact profile/type/locale, identifiers, address/contact, international settings, governance, network/service/scheme persistence and evaluators remained; Phase 1AL below resolves the profile/type/locale portion. Immutable configuration validation/result digests, invalidation, submission, approval, activation, history, and export remain M1F work.

### Phase 1AL - exact approved M1-07 organization profile (completed 18 September 2026)

- [x] Extend the organization aggregate through Flyway V25 with optional trading name, required approved organization type, required BCP 47 locale, exact name bounds, exact lifecycle vocabulary, and legacy-safe readiness blocking rather than invented backfill values.
- [x] Validate and normalize required/optional names, approved type, ISO 3166-1 alpha-2 country, IANA timezone, BCP 47 locale, and the NFC/control-free 10-500-code-point reason; return stable field-specific RFC 9457 violations.
- [x] Restrict runtime persistence to the exact approved mutable fields, required actor/tenant/reason context, monotonic revision, IANA timezone, and non-empty change; preserve strong ETag, caller idempotency, stale-write rejection, and atomic audit/outbox behavior.
- [x] Emit the exact `organization.profile.updated` payload with alphabetically sorted changed fields and lock version, including exact replay with no duplicate evidence.
- [x] Derive `editable` from the caller's live `organization.profile.manage` permission and render either the exact editable M1-07 form or a usable read-only projection without client-side role inference.
- [x] Replace the provisional profile readiness blocker with the approved evaluator over legal/display/trading identity, organization type, ISO country, IANA timezone, BCP 47 locale, eligible lifecycle, and current revision.
- [x] Advance OpenAPI to 0.15.0, regenerate the TypeScript contract, add profile-drift rejection, and cover legacy blocking, exact field errors, permission projection, governed update/replay/conflict, evidence, post-update readiness, responsive UI, and direct database attacks.

Local evidence: a clean Maven 3.9.11/Java 25 verification compiles 232 production and 21 test sources, validates and applies all 25 migrations, passes all 181 backend tests with zero failures, errors, or skips, enforces all 11 architecture rules, and packages the bootable JAR. OpenAPI 0.15.0 retains 31 operations and seven conventions; all 9 contract tests pass, comprising one acceptance case and eight negative drift fixtures. Generated-client drift, the 22-source/49-import frontend boundary and four negative fixtures, formatting, strict typecheck, lint, all 57 unit tests, the production build, and all 95 Playwright/Axe/overflow cases across 1440/1024/768/390/320 pass. The 79-screen, approved-input, review/candidate, facility-candidate, and 13-test CI-security gates also pass.

This closes the exact M1-07 profile vertical slice, not M1C. Phase 1AM below resolves the bounded M1-08 registration-identifier screen; M1-09 effective addresses/contacts, M1-10 international-settings change behavior, M1-11 governance responsibilities, their readiness evaluators, configuration-version invalidation, and final M1C owner/target acceptance remain.

### Phase 1AM - governed M1-08 registration identifiers (completed 18 September 2026)

- [x] Add Flyway V26 with one migration-owned base `registration` type, forced-RLS tenant identifier records, UUIDv7 defaults, exact approved normalization/bounds/status/range checks, non-revoked uniqueness, primary-range exclusion, one-replacement lineage, immutable history, and direct-write lifecycle guards.
- [x] Activate the exact `organization.identifier.read/manage/verify` operation bindings and all five approved identifier audit/outbox event pairs; retain current permission projection and require MFA plus authentication no older than ten minutes for verification.
- [x] Implement server-backed list, draft create/edit, verification, revocation, and atomic supersession with stable Problems, exact idempotent replay, predecessor and replacement strong revisions, verified same-type replacement checks, primary continuity, and minimum-necessary evidence projection.
- [x] Replace the M1-08 synthetic template with responsive governed cards/forms, strict response/lifecycle/action/lineage validation, exact client headers, and server-projected create/edit/verify/revoke/supersede actions.
- [x] Replace the identifier readiness placeholder with a live current verified-primary evaluation over applicable required type metadata, approved severity behavior, and bounded count evidence.
- [x] Advance OpenAPI to 0.16.0 and 37 operations, regenerate the TypeScript contract, and lock identifier fields, lifecycle, actions, supersession revision evidence, HTTP/database behavior, responsive interaction, Axe, overflow, and replay behavior in tests.

Local evidence: a clean Maven 3.9.11/Java 25 verification compiles 241 production and 22 test sources, validates and applies all 26 migrations, passes all 185 backend tests with zero failures, errors, or skips, enforces all 11 architecture rules, and packages the bootable JAR. OpenAPI 0.16.0 verifies 37 operations and seven conventions; all 11 contract tests pass. Generated-client drift, the 22-source/49-import frontend boundary and four negative fixtures, formatting, strict typecheck, lint, all 60 unit tests, the production build, and all 100 Playwright/Axe/overflow cases across 1440/1024/768/390/320 pass. The 79-screen, approved-input, review/candidate, facility-candidate, and CI-security gates also pass.

This closes the bounded M1-08 screen and live evaluator, not configuration activation or M1C. The approved `verified -> active` transition remains coupled to later configuration approval, and allow-listed expiry scheduling/evidence remains future worker work. Phase 1AN below resolves M1-09; M1-10/M1-11, configuration invalidation, final owner/visual acceptance, and target-environment evidence remain.

### Phase 1AN - governed M1-09 effective addresses and masked contacts (completed 18 September 2026)

- [x] Add Flyway V27 with migration-owned `operational` purpose metadata, forced-RLS tenant address/contact records, UUIDv7 defaults, exact types/channels/normalization/ranges, primary/preferred overlap exclusion, current contact uniqueness, immutable history, and one-replacement lineage.
- [x] Activate exact `organization.contact.read/manage` bindings and the safe `organization.address.changed`/`organization.contact.changed` audit/outbox pairs with only `recordId`, `changeType`, `effectiveFrom`, and `lockVersion`.
- [x] Implement directory read, address create/supersede/end, and contact create/verify/supersede/end with masked responses, exact permission projection, reason, idempotency, strong predecessor revisions, safe Problems, and atomic supersession.
- [x] Replace the M1-09 synthetic template with responsive governed address/contact history cards and forms that never display or prefill a raw contact value and fail closed if a response contains one.
- [x] Replace the address/contact readiness placeholder with live current registered-address and verified-primary-operational-contact evaluation, including draft warning and activation blocking behavior.
- [x] Advance OpenAPI to 0.17.0 and 45 operations, regenerate the TypeScript contract, and lock confidentiality, lifecycle, overlap, verification, immutable lineage, concurrency, replay, evidence, responsive interaction, Axe, and overflow behavior in tests.

Local evidence: a clean Maven 3.9.11/Java 25 verification compiles 251 production and 23 test sources, validates and applies all 27 migrations, passes all 189 backend tests with zero failures, errors, or skips, and enforces all 11 architecture rules. OpenAPI 0.17.0 verifies 45 operations and seven conventions; all 12 contract tests pass. Generated-client drift, the 22-source/49-import frontend boundary and four negative fixtures, formatting, strict typecheck, lint, all 64 unit tests, the production build, and all 105 Playwright/Axe/overflow cases across 1440/1024/768/390/320 pass. The 79-screen, approved-input, review/candidate, facility-candidate, and CI-security gates also pass.

This closes the bounded M1-09 screen and live evaluator, not M1C. Future scheduled activation/expiry requires an allow-listed worker and governed evidence. M1-10/M1-11, configuration invalidation, final owner/visual acceptance, and target-environment evidence remain.

### Phase 1AO - governed M1-10 international settings (completed 18 September 2026)

- [x] Add Flyway V28 with a forced-RLS, UUIDv7, immutable effective-version aggregate, exact read/manage bindings, one-pending-version protection, strong revision enforcement, and the exact `organization.settings.changed` audit/outbox event.
- [x] Derive the legacy baseline once from the approved organization country/timezone/locale values, then persist it atomically with the first scheduled replacement so later profile changes cannot rewrite settings history.
- [x] Implement tenant-authorized GET and idempotent PUT scheduling with ISO country/currency values, IANA timezones, BCP 47 locale/language tags, approved week starts, future effective time, server-owned impact rules, and locale-library format previews rather than arbitrary format strings.
- [x] Expose default/active/scheduled/superseded lifecycle history, live edit/schedule authority, one-future-version conflict behavior, exact replay, stale revision handling, and safe evidence containing only `changedFields`, `effectiveFrom`, and `lockVersion`.
- [x] Advance OpenAPI to 0.18.0 and 47 operations, regenerate the TypeScript contract, and route M1-10 to a responsive server-backed history/scheduling screen with strict projection and entity-tag validation.

Local evidence: Maven 3.9.11/Java 25 compiles 261 production and 24 test sources, validates and applies all 28 migrations, and all 193 backend tests pass with zero failures, errors, or skips. OpenAPI 0.18.0 verifies 47 operations and seven conventions; all 13 contract tests pass. Generated-client drift, strict typecheck, lint, all 64 unit tests, and all 105 Playwright/Axe/overflow cases across 1440/1024/768/390/320 pass.

This closes the bounded M1-10 settings slice, not M1C. The effective lifecycle is derived from immutable ranges; no unapproved worker or configuration invalidation behavior is claimed. M1-11 governance responsibilities, scheduled identifier/address/contact automation, configuration invalidation, final owner/visual acceptance, and target-environment evidence remain.

### Phase 1AP - governed M1-11 organization responsibilities (completed 18 September 2026)

- [x] Add Flyway V29 with forced-RLS, UUIDv7 governance responsibility history for clinical, privacy, security, and billing coverage; require exactly one linked active membership or verified active external contact, at least one escalation channel, and non-overlapping primary effective ranges.
- [x] Activate exact `organization.governance.read/manage` bindings, require MFA plus authentication no older than ten minutes for mutation, and emit only the five approved `organization.governance.changed` evidence fields.
- [x] Implement confidential directory read, create, scheduled ending, and atomic supersession with reason, idempotency, strong revisions, immutable lineage, deferred replacement enforcement, and an active-role no-gap boundary.
- [x] Replace M1-11 synthetic content with a responsive assignment/history screen using eligible assignees and masked escalation projections, while deriving available actions from server authority.
- [x] Replace the governance readiness placeholder with a live requirement for four current primary responsibilities, each with an escalation channel.
- [x] Advance OpenAPI to 0.19.0 and 51 operations, regenerate the checked TypeScript client, and lock confidential projections, evidence shape, migration/RLS behavior, HTTP behavior, accessibility, and responsive overflow in regression coverage.

Local evidence: a clean Maven 3.9.11/Java 25 run compiled and packaged the application, validated/applied all 29 migrations, and produced 194 passing backend tests with zero failures, errors, or skips, including 35 RLS and 11 architecture tests. OpenAPI 0.19.0 verifies 51 operations and seven conventions; all 14 contract tests pass. Generated-client drift, formatting, strict typecheck, lint, all 64 frontend unit tests, the production build, and all 105 Playwright/Axe/overflow cases across 1440/1024/768/390/320 pass.

This closes the bounded M1-11 screen and live governance coverage evaluator, not M1C acceptance. Scheduled identifier/address/contact automation, configuration invalidation, final owner/visual acceptance, and target-environment evidence remain.

### Phase 1AQ - approved M1-12 facility persistence foundation (completed 18 September 2026)

- [x] Add Flyway V30 to extend the legacy forced-RLS facility aggregate with approved legal/display identity, uppercase organization-unique code, migration-owned facility type, optional same-tenant address/contact references, IANA timezone override, lifecycle vocabulary, closure reason, actor evidence, revision constraints, and a directory index.
- [x] Activate exact `network.facility.read` and draft-only `network.facility.manage` operations and bind draft creation to the approved `facility.created` audit/outbox shape.
- [x] Enforce tenant, actor, operation, reason, draft lifecycle, required-field, immutable identity, and monotonic revision rules at the database boundary while retaining an explicit test-registry-only path for generic transaction probes.
- [x] Update transaction fixtures to use approved uppercase facility codes and prove V30 against a fresh PostgreSQL 18 database and the complete tenant/RLS suite.
- [x] Add tenant-authorized facility directory reads with server-side query/status filtering and live create authority, plus governed idempotent draft creation using the exact V30 operation and evidence binding.
- [x] Replace the placeholder minimum-facility result with a live count of submitted/active facilities that have a validated address and an explicit or inherited timezone.
- [x] Advance the checked contract to OpenAPI 0.20.0 with 53 operations, add exact facility directory/create schemas, regenerate the TypeScript types, and add checked client methods with allow-listed filters and caller-owned idempotency.
- [x] Route M1-12 to a responsive live facility directory with strict response validation, server-backed search, explicit empty/error/retry states, permission-gated draft creation, migration-owned type options, and caller-owned retry keys.
- [x] Cover authorized read/filter/create, exact idempotent replay, preserved legacy projection, draft readiness blocking, and exact four-key audit/outbox evidence through the real HTTP/security stack.
- [x] Remove M1-12 from the synthetic-screen assertions and pass all 105 Playwright/Axe/overflow cases across 1440/1024/768/390/320 against the live route.

Local evidence: Flyway validates and applies all 30 migrations; all 35 tenant/RLS cases and the focused facility HTTP/evidence integration case pass. OpenAPI 0.20.0 verifies 53 operations and seven conventions; all 14 contract tests pass. Generated-client drift, formatting, strict typecheck, lint, all 64 frontend unit tests, the production build, and all 105 Playwright/Axe/overflow cases pass.

This closes the bounded M1-12 directory and draft-creation slice. Facility submission, activation, suspension, closure, complete address capture, impact checks, and maker-checker lifecycle remain M1-13; final M1D and target-environment acceptance are not claimed.

### Phase 1AR - governed M1-13 facility draft editing foundation (completed 18 September 2026)

- Flyway V31 registers the exact `facility.updated` audit/outbox event under the approved `network.facility.manage` operation.
- The tenant-authorized facility aggregate now supports draft-only checked updates with strong entity tags, revision increments, same-tenant address/contact references, reason capture, idempotent replay, and stale-write rejection.
- OpenAPI 0.21.0 checks 54 operations, including `updateFacilityDraft`; the generated TypeScript boundary and handwritten client expose the checked mutation.
- The focused browser-security integration proof covers create, update, exact replay, stale rejection, and one exact four-key audit/outbox pair. The 35-test RLS suite and all 64 frontend unit tests remain green.

This completes only the prerequisite draft-edit boundary for M1-13. Submission, impact review, approval/activation, suspension, closure, complete address orchestration, and final M1D/target acceptance remain open.

### Phase 1AS - checked M1-13 facility draft editing UI (completed 18 September 2026)

- M1-12 now projects an edit control only when the authorized directory grants facility management and the record remains in `draft`.
- Selecting a draft hydrates the checked write form; saving sends the exact current `facility:{id}:{lockVersion}` strong entity tag, a fresh idempotency key, and the required reason through `updateFacilityDraft`.
- Successful writes replace the directory from the validated server response; failures remain visible without discarding the in-progress correction, and cancel returns to draft creation.
- The focused React interaction proof verifies hydration and exact mutation arguments. All 64 frontend tests, strict typecheck, lint, formatting, production build, and 105 five-viewport Playwright/Axe/overflow cases pass.

This closes the browser control omitted by Phase 1AR, not the M1-13 lifecycle. Submission, impact review, approval/activation, suspension, closure, complete address orchestration, and final M1D/target acceptance remain open.

### Phase 1AT - governed M1-13 facility submission foundation (completed 18 September 2026)

- Flyway V32 registers exact `facility.submitted` audit/outbox evidence and advances the database guard only for governed `draft -> under_review` transitions.
- Submission requires the exact facility-bound strong entity tag, a fresh idempotency key, a bounded reason, an unchanged draft, a validated currently effective address, and an explicit or inherited timezone.
- OpenAPI 0.22.0 checks 55 operations and the generated/handwritten clients expose `submitFacilityDraft`; cross-facility entity tags are rejected before persistence.
- The focused live PostgreSQL/Redis proof covers prerequisite completion, submission, exact replay, revision 3, readiness completion, and exactly one four-key audit/outbox pair. All 64 frontend tests, generated drift, and strict typecheck pass.

This completes the submission foundation, not independent activation. Hierarchy, operating-hours/service-policy readiness, maker-checker activation, suspension/reactivation, closure impact, browser submission controls, and final M1D/target acceptance remain open.

### Phase 1AU - checked M1-13 facility submission UI (completed 18 September 2026)

- Authorized draft cards now expose `Submit for review`; non-draft records and read-only users receive no submission control.
- The inline confirmation captures a bounded reason, explains the validated-address/timezone prerequisites, and sends the current facility-bound strong entity tag plus a fresh idempotency key.
- Conflict and other governed failures remain visible without discarding the reason; success replaces the directory from the validated server response and removes draft-only controls.
- The focused React proof verifies exact submission arguments and the `under_review` projection. All 64 frontend tests, strict typecheck, lint, formatting, production build, and 105 five-viewport Playwright/Axe/overflow cases pass.

This closes the browser submission control omitted by Phase 1AT. Hierarchy, operating-hours/service-policy readiness, maker-checker activation, suspension/reactivation, closure impact, and final M1D/target acceptance remain open.

### Phase 1AV - approved M1-14 hierarchy persistence foundation (completed 20 September 2026)

- Activation remains fail-closed because its approved prerequisites require real hierarchy, hours, and service-policy evidence; work therefore proceeds through those dependencies rather than bypassing them.
- Flyway V33 adds forced-RLS organization units with facility-scoped unique codes, department/unit types, effective ranges, immutable tenant/facility identity, UUIDv7 IDs, revisions, same-facility parents, cycle rejection, and maximum depth eight.
- Tenant-authorized read and governed idempotent draft-create endpoints emit the exact six-key `network.unit.changed` audit/outbox evidence under the approved facility-management permission.
- OpenAPI 0.23.0 checks 57 operations and generated-client drift/typecheck pass. Backend compilation, fresh migration, the 35-test tenant suite except its expected UUIDv7 catalogue update, and the corrected focused UUIDv7 gate pass; all other RLS tests were green.

This establishes only M1-14 read/create persistence. Checked editing/reparenting, lifecycle and active-parent rules, live UI, direct hierarchy attack coverage, readiness integration, M1-15 locations, M1-16 hours, M1-18 assignments, and facility activation remain open.

### Phase 1AW - governed M1-14 unit draft editing (completed 20 September 2026)

- Unit drafts now support checked edits with unit-bound strong entity tags, revision increments, reason capture, idempotent replay, and stale/cross-unit rejection.
- The edit path cannot change the parent, facility, tenant, lifecycle, creation evidence, or identity; reparenting remains a separate future event and endpoint.
- V33's database guard now confines ordinary updates to `draft -> draft`, preventing the draft edit operation from activating or otherwise changing lifecycle state.
- OpenAPI 0.24.0 checks 58 operations. Backend compilation, all 14 contract tests, generated drift/typecheck, and the focused live PostgreSQL/Redis create-update-replay/stale/cross-unit/exact-evidence proof pass.

M1-14 reparenting, lifecycle/active-parent enforcement, live UI, additional direct hierarchy attacks, readiness integration, M1-15/M1-16/M1-18, and facility activation remain open.

### Phase 1AX - governed M1-14 unit reparenting foundation (completed 20 September 2026)

- Flyway V34 adds forced-RLS, append-only unit-parent history with exact revision/effective-time evidence and the approved `network.unit.reparented` audit/outbox contract.
- Immediate reparenting requires a unit-bound strong entity tag, idempotency, reason, unchanged draft revision, and an eligible parent in the same facility.
- Server and database checks reject self/cyclic ancestry and combined ancestor/subtree depth beyond eight; successful changes atomically persist history, increment the unit revision, and emit one exact five-key evidence pair.
- OpenAPI 0.25.0 checks 59 operations. Backend compilation, 14 contract tests, generated drift/typecheck, and the focused live create/edit/reparent/replay/cycle/history/evidence proof pass.

This completes the immediate draft reparenting foundation, not scheduled reparenting or M1-14. Lifecycle/active-parent enforcement, live UI, broader depth/cross-tenant attacks, readiness integration, and the remaining facility-activation dependencies remain open.

### Phase 1AY - governed M1-14 unit activation foundation (completed 20 September 2026)

- Flyway V35 promotes the approved high-risk `network.structure.lifecycle` operation with MFA and ten-minute recent-authentication assurance and binds it to exact `network.unit.changed` audit/outbox evidence.
- The first bounded lifecycle transition is `draft -> active`; it requires a strong unit-bound revision, idempotency, reason, a current effective range, an under-review or active facility, and an active parent when one exists.
- PostgreSQL independently restricts lifecycle writes, immutable fields, eligible facility state, effective range, and active-parent ordering. Draft management remains unable to alter lifecycle state.
- OpenAPI 0.26.0 checks 60 operations. Backend compilation, contract verification, generated-client drift/typecheck, fresh V35 migration, and the focused live parent-first/replay/stale/exact-evidence proof pass.

This completes activation only, not M1-14 lifecycle. Suspension, reactivation, closure/descendant impact, live UI, hierarchy readiness integration, and remaining facility-activation dependencies remain open.

### Phase 1AZ - governed M1-14 unit suspension and reactivation (completed 20 September 2026)

- Flyway V36 extends the checked lifecycle matrix with `active -> suspended` and `suspended -> active` while continuing to reject all unapproved transitions and content changes.
- Suspension is descendant-first: an active unit with any active descendant is blocked. Reactivation is parent-first and repeats the eligible-facility and current-effective-range checks.
- The high-assurance lifecycle endpoints retain unit-bound strong revisions, idempotency, reason, MFA, recent authentication, and exact six-key `network.unit.changed` audit/outbox evidence.
- OpenAPI 0.27.0 checks 62 operations. Backend compilation, API contract tests, generated-client drift/typecheck, fresh V36 migration, and the focused live ordering/replay/stale/evidence proof pass.

This completes activation, suspension, and reactivation, not M1-14 lifecycle. Closure and descendant/assignment impact, live UI, readiness integration, and remaining facility-activation dependencies remain open.

### Phase 1BA - governed M1-14 immediate unit closure (completed 20 September 2026)

- Flyway V37 adds terminal `active|suspended -> closed` transitions with a server-checked current effective end and immutable content except for that end instant.
- Closure is leaf-first across every non-closed descendant. Closed units cannot re-enter activation, suspension, reactivation, editing, or reparenting workflows.
- The high-assurance closure endpoint retains unit-bound strong revisions, idempotency, reason, MFA, recent authentication, and exact six-key `network.unit.changed` audit/outbox evidence using the persisted source state.
- OpenAPI 0.28.0 checks 63 operations. Backend compilation, API contract tests, generated-client drift/typecheck, fresh V37 migration, and the focused live ordering/replay/terminal/evidence proof pass.

This completes immediate hierarchy-only lifecycle closure, not full M1-14 impact enforcement. Service-assignment blocking depends on M1-18 persistence; live UI, hierarchy readiness integration, and remaining facility-activation dependencies remain open.

### Phase 1BB - live M1-14 hierarchy read/create UI (completed 20 September 2026)

- M1-14 now routes to a live authenticated hierarchy screen instead of the generic readiness projection, with facility selection, validated unit-directory loading, nested depth presentation, lifecycle badges, revisions, and explicit loading/failure/empty states.
- The handwritten checked browser client now implements typed unit-directory reads and idempotent draft creation. Runtime validation rejects wrong-tenant/facility projections, invalid parent references, malformed states, ranges, codes, or revisions.
- Permission-projected draft creation supports department/unit type, eligible draft parent, effective start, normalized code/name, reason, and server-authoritative response replacement; unavailable lifecycle authority is not inferred from `canManage`.
- All 65 frontend unit tests, strict typecheck, lint, formatting, generated-client drift, production build, and 105 five-viewport Playwright/Axe/overflow cases pass.

This completes the first live M1-14 browser slice. Draft edit/reparent, separately projected lifecycle controls, hierarchy readiness integration, and scheduled/assignment impact behavior remain open.

### Phase 1BC - live M1-14 hierarchy readiness evaluation (completed 20 September 2026)

- The `network.hierarchy.valid` gate now evaluates persisted organization units instead of returning the evaluator-unavailable placeholder.
- Readiness requires every eligible facility to have a current active root-backed hierarchy and every active unit to resolve through current active, same-facility ancestors without an orphan, cycle, or depth beyond eight.
- The gate remains fail-closed for zero eligible facilities, incomplete facility coverage, ineffective active units, or invalid ancestor chains and returns deterministic eligible-facility, covered-facility, active-unit, and valid-active-unit evidence counts.
- Backend compilation and the focused live PostgreSQL/Redis lifecycle proof pass, including complete readiness after parent-first activation and blocked readiness after leaf-first closure.

This completes M1-14 hierarchy readiness integration. Draft edit/reparent UI, separately projected lifecycle controls, M1-18 assignment-impact enforcement, and the remaining facility-activation dependencies remain open.

### Phase 1BD - live M1-14 draft edit and reparent UI (completed 20 September 2026)

- The checked browser client now implements organization-unit draft update and reparent mutations through their distinct approved routes with strong unit/revision entity tags and caller-owned idempotency keys.
- Manageable draft cards expose separate edit and parent-change workflows; ordinary edits preserve the existing parent, while reparenting excludes the selected unit and its descendants from eligible parent choices.
- Both workflows normalize governed inputs, retain explicit busy/failure/cancel states, and replace the hierarchy only with a server-authoritative response that passes tenant, facility, reference, lifecycle, range, and revision validation.
- All 66 frontend tests, strict typecheck, lint, formatting, generated-client drift, production build, and 105 five-viewport Playwright/Axe/overflow cases pass. Client coverage verifies exact methods, routes, entity tags, and idempotency headers; the React proof verifies normalized edit and reparent arguments.

This completes M1-14 draft create/edit/reparent browser controls. Separately projected high-assurance lifecycle controls, M1-18 assignment-impact enforcement, and the remaining facility-activation dependencies remain open.

### Phase 1BE - permission-projected M1-14 lifecycle UI (completed 20 September 2026)

- OpenAPI 0.29.0 adds `canManageLifecycle` to the unit directory, derived independently from the effective actor's `network.structure.lifecycle` permission rather than inferred from draft-management authority.
- The checked browser client implements activation, suspension, reactivation, and immediate closure through their distinct approved endpoints with strong unit/revision entity tags, caller-owned idempotency keys, and explicit reasons.
- Unit cards expose only state-valid lifecycle actions. A confirmation panel explains current MFA/recent-authentication requirements; PostgreSQL and the application service remain authoritative for parent-first activation/reactivation, descendant-first suspension/closure, facility eligibility, and terminal closure.
- Backend compilation, the focused live PostgreSQL/Redis lifecycle proof, all 67 frontend tests, contract verification, generated-client drift, strict typecheck, lint, formatting, production build, and 105 five-viewport Playwright/Axe/overflow cases pass. Negative UI coverage proves draft management alone does not expose lifecycle controls.

This completes immediate M1-14 lifecycle browser controls. M1-18 assignment-impact enforcement, operating-hours/service-policy readiness, facility activation, and final acceptance remain open.

### Phase 1BF - approved M1-15 service-location persistence foundation (completed 20 September 2026)

- Review of the approved lifecycle matrix confirmed that future-effective closure is a facility rule, not an approved department/unit transition; no unsupported scheduled unit closure was introduced.
- Flyway V38 adds forced-RLS service locations with UUIDv7 identity, facility-scoped unique codes, physical/virtual exclusivity, required physical address or bounded virtual-service type, optional unit and location parents, capacity, internal accessibility notes, effective range, lifecycle, and monotonic revisions.
- PostgreSQL independently enforces same-facility unit/location references, cycle rejection, maximum hierarchy depth eight, draft-first creation, governed actor/tenant/reason context, and draft-only ordinary updates.
- The approved `network.structure.manage` operation is promoted for governed structure drafts and bound to exact `network.location.changed` audit/outbox definitions. A clean PostgreSQL 18 migration and focused UUIDv7 catalogue test pass across all 38 migrations.

This establishes M1-15 persistence constraints only. Tenant-authorized directory/create/edit/reparent endpoints, lifecycle transitions, exact evidence execution, hierarchy-readiness integration, live UI, and direct RLS/hierarchy attack coverage remain open.

### Phase 1BG - governed M1-15 location directory and draft creation (completed 20 September 2026)

- OpenAPI 0.30.0 checks 65 operations and adds tenant-authorized facility location-directory reads plus governed idempotent draft creation.
- The application validates normalized codes/names, physical-address versus virtual-service-type exclusivity, capacity, accessibility notes, effective ranges, and bounded confidential reasons before persistence; PostgreSQL retains independent reference/hierarchy enforcement.
- Successful creation returns the server-authoritative directory and atomically emits one exact six-key `network.location.changed` audit/outbox pair under the approved `network.structure.manage` operation.
- Backend compilation, contract verification, generated-client drift/typecheck, and the focused live PostgreSQL/Redis empty-read/create/exact-replay/projection/evidence proof pass against all 38 migrations.

M1-15 checked browser client/UI, draft editing/reparenting, lifecycle, location-aware readiness, and broader direct RLS/hierarchy attacks remain open.

### Phase 1BH - live M1-15 location directory and draft-create UI (completed 20 September 2026)

- The checked browser client now reads the facility-scoped location directory and creates idempotent location drafts through the generated OpenAPI types.
- M1-15 is a live facility-selected screen with explicit loading, failure, empty, read-only, and manageable states; server responses are rejected unless organization/facility identity, hierarchy references, physical/virtual exclusivity, lifecycle, effective dates, capacity, timestamps, and revisions satisfy the runtime contract.
- The permission-gated form switches between required physical-address and virtual-service-type inputs, supports optional unit/parent/capacity/accessibility data, and replaces local state with the server-authoritative directory after creation.
- Frontend unit, type, lint, formatting, generated-contract drift, production-build, responsive route, and accessibility coverage pass with the live M1-15 route.

M1-15 draft editing/reparenting, lifecycle transitions, location-aware readiness, address/unit pickers, and broader direct RLS/hierarchy attacks remain open.

### Phase 1BI - governed M1-15 draft editing and reparenting (completed 20 September 2026)

- OpenAPI 0.31.0 checks 67 operations and adds strong-ETag, idempotent service-location draft editing plus immediate reparenting with bounded effective time and explicit reasons.
- V39 adds forced-RLS append-only service-location parent history and exact five-key `network.location.reparented` audit/outbox definitions under the approved structure-management operation.
- The store independently enforces draft state, revision equality, same-facility eligible parents, cycle rejection, and combined ancestor/subtree depth no greater than eight; ordinary edits cannot change the parent.
- The live M1-15 UI exposes state-valid edit and parent-change controls, filters self/descendant parent candidates, uses strong revisions, and accepts only server-authoritative runtime-valid directory responses.
- Backend compilation and focused clean PostgreSQL/Redis edit/reparent/history/evidence integration pass across all 39 migrations; frontend unit and contract checks pass.

M1-15 lifecycle transitions, location-aware readiness, address/unit pickers, and broader direct RLS/hierarchy attacks remain open.

### Phase 1BJ - governed M1-15 service-location lifecycle API (completed 20 September 2026)

- V40 permits only approved high-assurance location transitions under `network.structure.lifecycle` and binds exact `network.location.changed` evidence to that operation.
- Activation/reactivation require an eligible facility, current effective range, and active parent; suspension is descendant-first and immediate terminal closure is leaf-first with a current effective end.
- OpenAPI 0.32.0 checks 71 operations and separately projects exact location lifecycle authority through `canManageLifecycle`.
- Strong revisions, caller-owned idempotency, recent authentication, MFA, explicit reasons, state-valid ordering, and exact six-key audit/outbox evidence are enforced across the controller, service, store, and PostgreSQL trigger.
- Backend compilation and the focused clean PostgreSQL/Redis create/edit/reparent/activate/suspend/reactivate/close/evidence proof pass across all 40 migrations; generated-client drift and all frontend static/unit/build gates pass.

M1-15 checked lifecycle browser methods/UI, location-aware readiness, address/unit pickers, and broader direct RLS/hierarchy attacks remain open.

### Phase 1BK - permission-projected M1-15 lifecycle UI (completed 20 September 2026)

- The checked browser client implements service-location activation, suspension, reactivation, and closure through the four approved endpoints with strong location/revision entity tags, caller-owned idempotency keys, and explicit reasons.
- Location cards expose only state-valid lifecycle actions when the server separately projects `canManageLifecycle`; draft-management authority alone cannot reveal these controls.
- A high-assurance confirmation panel states the MFA/recent-authentication requirement while the service and PostgreSQL remain authoritative for eligibility, effective ranges, ancestor/descendant ordering, concurrency, and terminal closure.
- All 70 frontend tests, generated-client drift, strict typecheck, lint, formatting, production build, and 105 five-viewport Playwright/Axe/overflow cases pass. Coverage proves exact lifecycle arguments and the negative authority boundary.

This completes immediate M1-15 lifecycle browser controls. Location-aware readiness, address/unit pickers, broader direct RLS/hierarchy attacks, and the remaining facility-activation dependencies remain open.

### Phase 1BL - location-aware network hierarchy readiness (completed 20 September 2026)

- The approved `network.hierarchy.valid` evaluator now includes active service locations alongside organization units and still derives completion only from authoritative tenant state.
- Every active location must be currently effective, reach a root through active, current, same-facility location ancestors within depth eight, and reference a valid active unit hierarchy when a unit is assigned.
- Eligible-facility coverage may be established by a valid active unit or location hierarchy; bounded evidence separately reports active/valid unit and location counts, and invalid location results deep-link to M1-15.
- Backend compilation and the focused clean PostgreSQL/Redis lifecycle/readiness proof pass across all 40 migrations, including complete, suspended-blocked, and reactivated-complete outcomes.

This completes M1-15 integration into the live hierarchy-readiness gate. Address/unit pickers, broader direct RLS/hierarchy attacks, operating-hours/service-policy readiness, and the remaining facility-activation dependencies remain open.

### Phase 1BM - governed M1-15 address and unit selectors (implemented 20 September 2026; verified in Phase 1BQ)

- The live location screen now loads the tenant-authorized address collection and selected-facility organization-unit directory instead of accepting raw address or unit UUIDs.
- Physical create/edit forms select only current active organization addresses; virtual forms continue to exclude address references. Optional unit selectors expose non-closed units from the selected facility with code and lifecycle context.
- Facility changes clear stale unit and location-parent selections, while dependency failures, empty unit directories, and missing physical-address prerequisites remain explicit and fail closed.
- In accordance with the delivery rule in force at implementation time, executable verification was held until all M1 slices were present and then completed in Phase 1BQ.

This completed the planned M1-15 browser input controls; the retained final verification and the remaining M1 implementation work are resolved by Phases 1BN-1BQ, with owner/target acceptance still open.

### Phase 1 approval gate and implementation slices

### Phase 1BN - M1-16 through M1-19 governed backend foundation (implemented 21 September 2026; verified in Phase 1BQ)

- M1-16 now has forced-RLS atomic operating-hours batches, weekly/exception intervals, governed lifecycle evidence, tenant-authorized directory/mutation APIs, and live readiness coverage evaluation.
- M1-17 now has forced-RLS service definitions, governed draft and lifecycle APIs, exact catalogue evidence, and organization-type-aware active-service readiness evaluation.
- M1-18 now has forced-RLS effective assignments, service/facility/location eligibility, overlap rejection, immutable lifecycle content, governed APIs, and active-service assignment coverage evaluation. Suspended assignments cannot be reactivated in place; recovery creates a new effective assignment.
- M1-19 now has stable schemes, immutable scheme versions, single-active-version enforcement, sequence ownership/issued-identifier uniqueness, governed directory/version APIs, M1-21-only maker-checker lifecycle activation, and declared-scope readiness evaluation.
- The deferred executable compilation, migration, contract, integration, frontend, browser, accessibility, and regression verification completed in Phase 1BQ.

### Phase 1BO - M1-21 configuration activation persistence (implemented 21 September 2026; verified in Phase 1BQ)

- Versioned configuration records now bind parent/baseline digests, logical change references, maker identity, requested effective time, correlation, and monotonic lifecycle revisions.
- Immutable typed validation results expire after 15 minutes; independent maker/checker decisions bind the exact digest and expire after 30 minutes; activation runs preserve the previous active version and fail without replacing it.
- All activation tables use forced tenant RLS. PostgreSQL restricts lifecycle transitions by the exact governed operation, and the approved validation/submission/decision/activation audit and outbox contracts are registered.
- Application orchestration, OpenAPI/browser surfaces, configuration history, audit/export, and final verification are completed by Phases 1BP-1BQ; target-environment and owner acceptance remain open.

### Phase 1BP - M1-13 and M1-21 through M1-23 application foundations (implemented 21 September 2026; verified in Phase 1BQ)

- M1-13 now has high-assurance facility activation, suspension, reactivation, and closure boundaries. PostgreSQL independently requires eligible address, hierarchy, and active hours for activation/reactivation and blocks closure while current service-assignment impact remains unresolved.
- M1-21 now exposes governed validation, submission, independent approve/reject, and activation APIs over immutable 15-minute validation results and 30-minute approvals, with exact digest, revision, maker/checker, assurance, and prior-active-version checks.
- M1-22 and M1-23 now expose allowlisted configuration-history and audit projections with tenant/filter-bound signed cursors, bounded time ranges and page sizes, minimum-necessary fields, risk/redaction markers, and audit MFA/recent-authentication enforcement.
- Purpose-bound export request and independent decision persistence now implements approved projection/format/purpose allowlists, one-active-job limits, forced RLS, requester/approver separation, and audit-only versus authorized-worker outbox evidence.
- The M1-13 and M1-16 through M1-23 browser routes now load live tenant projections rather than the synthetic prototype shell. Permission-specific mutation controls, generated OpenAPI alignment, export worker/access lifecycle mechanics, and final repository verification are completed in Phase 1BQ.

### Phase 1BQ - complete organization-wide M1 verification and hardening (completed 21 September 2026)

- Closed activation/readiness invariants: initial configuration integrity is represented, authoritative changes invalidate validation/approval evidence, Redis is checked as the required runtime dependency, and optional unused export storage does not block activation.
- Closed evidence/export invariants: approved projections and risk classification are enforced, request-time repeatable snapshot rows bind the exact policy digest, worker failure removes partial artifacts, requester-only access and fresh idempotent grants are enforced, and database lifecycle triggers defend authorization, lease, expiry, and disposal transitions.
- Added deterministic DST gap/overlap rejection for weekly and dated operating-hours boundaries; cyclic weekly overlap remains checked in application and PostgreSQL.
- Reconciled OpenAPI 3.1 nullable/required fields, regenerated TypeScript, added strict checked response validators for every M1-16 through M1-23 projection, and removed the unreachable direct identifier lifecycle route so activation remains solely inside M1-21 maker-checker configuration activation.
- Completed actor-specific action projections, stable RFC 9457 workflow errors, bounded export-status backoff with terminal stop/manual refresh, and live five-viewport fixtures for all M1-16 through M1-23 routes.
- Verification passes 205 backend tests across 28 suites and all 50 migrations; backend packaging; 72 frontend unit tests plus generated drift/type/lint/format/architecture/build gates; 110 five-viewport Playwright/Axe/overflow cases; the 79-screen and 100-operation contracts; all input/candidate/facility-scope verifiers; and all CI-security checks.
- This completes the organization-wide repository implementation for M1C-M1F. It does not approve facility-scoped grants, activate a production export-worker identity/deployment/schedule, accept target providers/infrastructure, or provide M1G owner acceptance.

- [x] Supply and approve versioned M1-01 through M1-23 desktop/responsive mockups and all interaction states.
- [x] Supply and approve CareOS Design System 1.0 tokens/assets/components/content rules.
- [x] Approve the Module 1 data dictionary, validation catalogue, lifecycle/state-transition rules, and readiness/activation gates.
- [x] Promote or replace the provisional permission/role/operation registry and approve matching Module 1 audit/outbox events and schemas.
- [x] Record artifact versions/checksums and owner approval for the exact implementation input package.
- [ ] Approve the additive `m1-facility-scope-candidate-1` package at digest `76a3f7a2c63cef0cab02b8a1d38a66220eee0abb99c9be0a82b64eaa8941fddb` before implementing facility scope.
- [ ] Deliver M1B production identity/access acceptance for M1-01 to M1-04 and M1-20.
- [x] Deliver M1C organization core and readiness for M1-05 to M1-11.
- [x] Deliver M1D facilities, hierarchy, locations, and atomic hours for M1-12 to M1-16.
- [x] Deliver M1E services, assignments, and identifier schemes for M1-17 to M1-19.
- [x] Deliver M1F maker-checker activation, history, audit, and purpose-bound export mechanics for M1-21 to M1-23.
- [x] Run the full M1 security/accessibility/browser/regression suite and obtain owner acceptance before Module 2. The user accepted commit `2ba6c9b` as the predecessor baseline on 21 September 2026; this does not constitute target-environment production acceptance.

The exact input checklist, per-screen status, architecture, and exit conditions are authoritative in `MODULE_1_IMPLEMENTATION_PLAN.md`. The approval gate, organization-wide repository implementation/verification, and accepted Module 2 predecessor baseline now pass. Facility-scope approval, production worker/provider operations, target-environment evidence, and production owner acceptance remain separate exit conditions; repository acceptance is not production acceptance.

### Phase 2A - checksum-bound Module 2 input candidate (completed 21 September 2026)

- [x] Bind Module 2 planning to the user-accepted M1 commit `2ba6c9b3b567d0371c8523e6f18945ec33138ae4` and build-specification digest `2c8f9f020c7c1a21678c795df57fd8be1b659f2141ab7c8747b5dec973db0bfe`.
- [x] Create one self-contained responsive review mockup covering M2-01 through M2-29, ten explicit UI-state families, the 1440/1024/768/390/320 matrix, mobile record cards, dialog focus behavior, and conspicuous synthetic/no-production-action boundaries.
- [x] Define the exact 44-table data/validation baseline; credential/document, scope, assignment, availability, readiness, activation, lifecycle, configuration, registry, legal-hold, notification, eligibility, and export boundaries; and reuse of the canonical M1 identity/RBAC/platform sources.
- [x] Define additive deny-by-default roles/permissions/scopes/assurance, exact audit/outbox event families and consumers, clinical/non-clinical readiness, point-in-time eligibility, no-override behavior, expiry milestones, minimum-necessary history/audit/timeline projections, retention, and safe exports.
- [x] Add a path-safe verifier binding the exact eight artifacts, all 29 screen IDs, all 44 table names, accepted baseline, build specification bytes, required decision topics, self-contained mockup boundary, and non-authorizing status.
- [x] Run the end-of-phase integrity gate: the verifier reports eight artifacts, 29 screens, 44 tables, package digest `2e64bd4e1ac5192a9a4783abf58f4578b8bceda60c21c2e835a910e6760d0f8f`, and `implementationAuthorized: false`; all ten positive/negative verifier tests, five exact-viewport Playwright/Axe/overflow cases, frontend formatting, and the updated 13-test CI-security contract pass.
- [ ] Obtain accountable review/approval of the exact candidate digest and promote byte-identical artifacts plus distinct approval evidence before writing Module 2 production migrations or runtime behavior.

The dependency-ordered M2A-M2H implementation, screen traceability, architecture boundaries, deferred end-of-module testing rule, and exit criteria are authoritative in `MODULE_2_IMPLEMENTATION_PLAN.md`. Current M2 runtime pages remain synthetic and disabled; the completed candidate supplies decisions for review but grants no implementation authority.
