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

This completes durable quarantine/scan evidence mechanics, not the M7 document workflow. Storage and scanning remain opt-in and disabled by default; promotion, signed access, and retention stay explicitly unavailable. Approved permissions/events, a business document/provenance model, governed upload/finalization, scan freshness, provider IAM/KMS/operations, and protected API/browser coverage remain required. See `PLATFORM_CAPABILITIES.md`.

### Next Phase 0 slice

- [ ] Approve and add the canonical permission/role/operation registry, then bind protected routes, delegation ceilings, final-owner safeguards, and maker-checker rules to the implemented authorization boundary.
- [ ] Complete governed invitation issuance/acceptance, existing-account linkage, and a separate non-interactive service-account path using that approved policy.
- [ ] Extend V8's composite tenant-link pattern and add operation-specific database invariants as the first production business vertical slice is introduced.
- [x] Define fail-closed document, notification, Redis, worker, and scheduler ports.
- [x] Implement and integration-test policy-neutral private-quarantine storage mechanics behind the Phase 0F boundary, disabled by default.
- [x] Implement and integration-test policy-neutral quarantined malware-scanning mechanics behind the Phase 0F boundary, disabled by default.
- [x] Implement and integration-test policy-neutral Redis durable-job transport mechanics behind the Phase 0F boundary, disabled by default.
- [x] Implement and integration-test policy-neutral encrypted notification persistence/lease mechanics behind the Phase 0F boundary, disabled by default.
- [x] Persist transaction-bound, append-only quarantine metadata and scan attestations under forced RLS without activating promotion or document delivery.
- [x] Add immutable CI dependencies, dependency/SAST/secret/configuration/container gates, update automation, SBOM generation, digest-pinned images, and unprivileged application runtimes.
- [x] Establish protected tenant-route, pagination/filter, concurrency, idempotency/retry conventions plus generated frontend API types and a checked browser client.
- [x] Connect the checked identity/organization client to a fail-closed frontend session gate, real login/pending-MFA/selection/switch/logout states, and an enforced session feature boundary.
- [x] Connect public password recovery plus recent-authenticated M1-03 MFA enrollment/recovery-code self-service without persisting secrets.
- [x] Add server-derived idle/absolute deadline locking and event-driven browser session revalidation without a background heartbeat.
- [x] Add structured safe request telemetry, bounded disabled-by-default trace export, authenticated Prometheus format, dependency-correct probes, an outage drill, and the repository operational runbook.
- [x] Add explicit backend/frontend response headers, strict same-origin CSP, bounded proxy behavior, and a fail-closed production configuration preflight profile.
- [ ] Approve a production object-store/IAM/KMS design and complete its deployment, recovery, monitoring, and acceptance controls.
- [ ] Implement and integration-test the remaining approved promotion, signed-access, retention, notification consent/destination/provider, worker, and scheduler adapters; keep each unavailable until its checklist passes.

Phase 0 remains incomplete until every foundation exit condition in `IMPLEMENTATION_GAPS.md` is satisfied and CI passes.
