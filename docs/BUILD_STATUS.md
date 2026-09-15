# Build status

## Verified in the delivery environment

- Frontend dependency installation
- OpenAPI TypeScript generation and isolated drift checking
- TypeScript-AST frontend feature dependency verification and negative fixtures
- Strict TypeScript with indexed-access checks
- ESLint with zero warnings
- Vitest API-client/component/registry tests
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

## Verified during Phase 0K foundation (13 September 2026)

- All third-party Actions use full commit SHAs with release comments; both workflows use explicit runners, read-only default permissions, non-persisted checkout credentials, concurrency cancellation, and finite job timeouts
- Pull-request dependency review, Java/JavaScript CodeQL `security-extended`, Trivy source/image gates, CI image builds, CycloneDX image SBOM artifacts, and weekly Maven/npm/Docker/Actions Dependabot updates are configured
- A dependency-free repository contract and eight negative tests enforce immutable action/image references, least workflow authority, bounded execution, non-root final images, complete update coverage, and pinned Testcontainers dependencies
- Every Dockerfile base, Compose/overlay service, and PostgreSQL/Redis Testcontainers image is digest-pinned; the PostgreSQL fixtures explicitly declare compatibility for the pinned official image
- Tomcat is held at the fixed 11.0.25 security floor; the backend uses Java 25 Debian 13 distroless as UID 65532 and the frontend uses unprivileged Nginx as UID 101 on port 8080
- Trivy 0.74 reports zero fixed HIGH/CRITICAL dependency or final-image findings, zero Dockerfile misconfigurations, and no emitted secret findings; both final images generate valid CycloneDX SBOMs
- The final backend and frontend images both pass read-only, all-capabilities-dropped, no-new-privileges runtime smokes; backend health/Flyway/capability/metrics/clean-state assertions and frontend health/root-HTML assertions pass
- Clean Java 25 verification passes all 77 backend tests; exact Node 24.15 installation/format/typecheck/lint/unit/build, eight browser/Axe tests, 79-screen/15-operation contracts, both Compose models, and all eight CI-security tests pass

## Verified during Phase 0L foundation (14 September 2026)

- OpenAPI 3.1 version 0.4 retains all 15 implemented operations and adds machine-checked protected tenant-route, cursor/filter, strong ETag/If-Match, scoped idempotency, bounded retry, and reusable 412/503 conventions
- The importable dependency-free contract verifier passes, and six mutation-based tests prove tenant-prefix, filter, mutation-retry, precondition, and local-reference weakening is rejected
- Exact `@hey-api/openapi-ts` 0.99.0 generates two TypeScript-only files; isolated drift checking passes, `js-yaml` is forced to fixed 4.3.2, and exact Node 24.15 `npm ci` audits all 290 packages with zero vulnerabilities
- A native generated-type-backed client wraps all 15 operations with credentialed requests, validated correlation, per-mutation CSRF bootstrap, documented response enforcement, safe Problem/network handling, ETag/retry metadata, HTTPS configuration checks, cancellation, and no automatic retry
- Eight client tests plus two prototype tests pass under strict TypeScript with indexed-access checking; format, lint, generated drift, Vite production build, and the existing browser/Axe suite pass
- Backend CORS now allows `If-Match`/`Idempotency-Key`, exposes correlation/retry/ETag metadata, and does not allow `X-Organization-Id`; a clean Java 25 build compiles 135 production and 11 test sources, passes all 78 tests, and packages the bootable JAR
- Both backend and frontend application images build successfully from the updated clean Docker contexts
- Quality CI runs generated-type drift and API negative tests before accepting type/contract changes; the repository security contract still passes all eight negative tests

This is an API/client foundation checkpoint. No protected tenant business endpoint, server-state cache, or production screen integration has been added, and no policy-blocked authorization or event content has been invented.

## Verified during Phase 0M foundation (14 September 2026)

- A memory-only React session state machine now bootstraps and runtime-validates the server session and membership-backed organization list before exposing the protected shell
- M1-01 login, pending M1-03 MFA, M1-04 organization selection, desktop/mobile organization switching, and sign-out call the checked CSRF/correlation-aware client; submitted secret fields are cleared and mutations are not automatically retried
- Loading, no-membership, malformed/ambiguous response, dependency failure, safe focused error, and retry states remain outside the protected workspace; failed logout does not falsely discard authenticated local state
- M1-02 explicitly remains unavailable and makes no invitation request until governed issuance/account-linkage/acceptance is implemented
- The shell now uses server-returned actor and organization labels instead of a hard-coded user; the other 75 protected entries retain their clearly synthetic prototype records and actions
- A TypeScript-AST architecture gate accepts 17 source files/30 inward relative imports; four negative tests reject reverse shared dependencies, cross-feature coupling, API-to-page imports, and source escape
- Eight API-client tests and eleven application/session tests pass; all 12 Playwright tests pass on desktop and mobile-320 while Axe-checking 75 protected entries plus four identity states and exercising CSRF login, explicit organization selection, and logout
- Generated API drift, architecture, format, strict typecheck, lint, production build, the 79-screen register, API contract tests, and CI-security contract tests remain green

This is an authenticated frontend foundation, not production M1 delivery. Invitation/account linking, password recovery and MFA lifecycle administration UI, recent-authentication dialogs, permission-driven actions, continuous expiry revalidation, approved designs/router, tenant business APIs/caching, and every persisted business screen remain incomplete.

## Verified during Phase 0N foundation (14 September 2026)

- Spring Boot emits ECS-compatible JSON and a request completion event containing correlation/trace context, allow-listed methods, framework route templates, status, duration, and outcome without raw path/query values or request content
- OpenTelemetry W3C context is bounded, baggage is disabled, trace export is off by default, OTLP metric/log exporters are disabled, and ambient `OTEL_*` environment mapping cannot silently enable export
- Prometheus-format metrics carry the bounded `careos-backend` application tag and require full CareOS authentication; anonymous access returns the checked RFC 9457 `401`
- Public `/livez` checks only recoverable in-process state; `/readyz` requires application readiness, PostgreSQL, and Redis; all public health responses suppress components/details
- Pausing disposable Redis makes readiness return `503/DOWN` while liveness remains `200/UP`; unpausing Redis restores readiness
- Compose now waits on `/readyz`; both default and scanner-overlay models resolve
- `OPERATIONS.md` records probe/telemetry semantics, first response, a local outage drill, and explicit monitoring, alert, deployment, backup, and timed-restore acceptance gaps
- A clean pinned Java 25 build compiled 136 production sources and 11 test sources, passed all 80 tests, and packaged the bootable JAR
- The rebuilt Java 25 distroless image reached both probes against fresh PostgreSQL 18/Redis 8, rejected anonymous metrics, started no OTLP metrics publisher, and passed non-root/read-only/capability/no-new-privileges checks
- Trivy 0.74 reports zero fixed HIGH/CRITICAL vulnerabilities in the rebuilt Debian 13.6 runtime layer and packaged application JAR
- The 15-operation API and six negative contract tests, 79-screen register, CI-security repository contract, and all eight CI-security negative tests remain green

This is a repository signal baseline, not production operational acceptance. No external collector, non-interactive scraper identity, dashboard, alert route/on-call escalation, managed deployment, encrypted backup, timed restore, or dead-letter replay procedure has been approved or implemented.

## Verified during Phase 0O foundation (14 September 2026)

- Spring Security explicitly emits no-content CSP, deny-framing, no-referrer, no-sniff, restrictive permissions, and secure-request-only one-year HSTS for the API
- Unprivileged Nginx emits an always-on strict same-origin CSP and complementary isolation, permissions, referrer, HSTS, MIME, framing, cross-domain, and legacy-XSS headers while hiding version tokens
- The same-origin `/api` proxy now has bounded connect/send/read behavior and removes the hop-by-hop `Connection` header
- `application-production.yml` requires external PostgreSQL, Redis, SMTP, browser-origin/base-URL, mail, token-pepper, and MFA-key values while enabling secure cookie, Redis TLS, and authenticated mandatory SMTP STARTTLS with certificate identity checks
- Synthetic successful dependency/browser/bootstrap fallbacks are isolated in `application-local.yml`; the base configuration requires explicit values, and the dependency-free repository contract prevents those fallbacks moving back into the default path
- A production-only startup guard rejects mixed non-production profiles, documented local/test material, trivial MFA keys, plaintext/unverified dependency transports, embedded database credentials, non-canonical/duplicate origins, unsafe S3 switches, and insecure explicitly enabled OTLP export without disclosing secret values
- Four focused configuration tests and one HTTP integration test cover the positive configuration, negative guard matrix, exact response headers, and secure-request-only HSTS semantics
- The dependency-free CI-security contract now includes 10 tests and rejects missing always-on Nginx headers, unsafe/wildcard CSP, version tokens, or incomplete proxy bounds
- A clean Java 25 build compiles 137 production sources and 11 test sources, passes all 84 tests against disposable dependencies, applies Flyway through V7, and packages the bootable JAR
- Frontend formatting, strict typecheck, lint, all 19 unit tests, production build, and all 12 desktop/mobile Playwright/Axe tests remain green; both Compose models and all repository/API/prototype contracts pass
- The rebuilt Nginx image passes syntax and response-header inspection as UID 101 under the existing read-only, dropped-capability, and no-new-privileges restrictions
- Both rebuilt images report zero fixed HIGH/CRITICAL OS-package findings under Trivy 0.74; the backend image also proves a no-profile/no-configuration launch fails on a required placeholder rather than using local defaults

This is a production configuration and HTTP preflight baseline, not a deployed security boundary. Secret-manager/rotation exercises, public and service-to-service TLS, trusted forwarding, private backend/management routing, operation/edge rate limits, WAF/DDoS controls, CSP reporting, real-host scans, and measured load/abuse tests remain unapproved and incomplete.

## Verified during Phase 0P foundation (15 September 2026)

- OpenAPI 3.1 advances to version 0.5.0; its checked session response includes persisted `mfaEnabled` state and stays authoritative for a session created before MFA enrollment
- M1-01 links to public reset request/completion routes with a generic accepted state, password confirmation/UTF-8 bounds, submitted-field clearing, and server-confirmed all-session revocation
- Reset tokens retain case during parsing, are removed from the active browser-history entry immediately after capture, remain only in React memory, and are forgotten after successful use
- Authenticated M1-03 now requires recent password plus conditional second-factor verification before TOTP enrollment or recovery-code replacement, including for users without tenant membership
- TOTP setup and unique recovery-code responses are runtime-validated; one-time plaintext material is removed when acknowledged or when the recent-authenticated child view unmounts
- Recovery-code regeneration requires explicit acknowledgement that previous codes are immediately revoked; MFA disable and administrator reset remain deliberately unavailable
- The frontend architecture gate accepts 18 source files and 35 inward relative imports; generated drift, format, strict typecheck, lint, 24 unit tests, and the Vite production build pass
- All 16 Playwright tests pass on desktop and mobile-320, including the 75 protected-route Axe sweep plus checked-client password recovery and recent-authenticated MFA enrollment flows
- A clean host-side Java 25 build compiles 137 production sources and 11 test sources, passes all 84 tests against disposable dependencies, applies Flyway through V7, and packages the bootable JAR
- The 15-operation OpenAPI 3.1 version 0.5.0 contract/six conventions, six API negative tests, 79-screen register, and CI-security verifier/10 negative tests remain green

This closes password-recovery and MFA enrollment/recovery-code browser self-service only. Governed invitations/account linkage, MFA disable/administrator reset, automatic session-expiry revalidation, permission-driven identity administration, service identities, and every tenant business workflow remain incomplete.

## Verified during Phase 0Q foundation (15 September 2026)

- Valid authenticated responses expose `X-CareOS-Session-Expires-In`, calculated as the effective time to the earlier Redis idle or absolute deadline; anonymous session responses omit it
- CORS exposes the bounded header, and focused calculation tests prove idle-first, absolute-first, and already-elapsed behavior
- OpenAPI 3.1 advances to version 0.6.0 with a seventh checked no-polling session-lifecycle convention and a seventh negative contract test
- The checked browser client derives a conservative local deadline from request start and publishes deadline updates or global `401` invalidation without persisting the value
- Authenticated bootstrap fails closed without valid expiry metadata; a bounded timer removes protected state at expiry without issuing a refresh request
- Focus, visible-tab, online, and BFCache return events perform full checked session/organization revalidation only while the local deadline remains valid
- The frontend architecture/generation/format/type/lint/build gates pass with 29 unit tests, and all 18 desktop/mobile Playwright/Axe tests pass, including a no-polling deadline assertion
- A clean Java 25 build compiles 138 production sources and 12 test sources, applies Flyway through V7, passes all 87 tests against disposable dependencies, and packages the bootable JAR
- The 15-operation API/seven conventions, seven API negative tests, 79-screen register, and CI-security verifier/10 negative tests remain green

This closes automatic browser session-expiry convergence for the checked client. Direct credentialed fetches remain prohibited because they would bypass lifecycle publication. Governed invitations/account linkage, MFA disable/administrator reset, permission-driven administration, service identities, and every tenant business workflow remain incomplete.

## Verified during Phase 0R foundation (15 September 2026)

- Flyway V8 creates distinct `document_quarantine_evidence` and `document_scan_attestations` tables with forced RLS, canonical tenant policies, composite tenant/object linkage, restricted `SELECT`/`INSERT` runtime grants, transaction-context/server-time insert triggers, and update/delete rejection
- The mandatory PostgreSQL adapter refuses unsafe role/table ownership, missing RLS/policies/triggers/composite linkage, excess privileges, or evidence visibility without tenant context before serving operations
- Exact quarantine metadata replays converge while changed byte count, media type, or digest conflicts; database rollback removes evidence and the existing S3 exact-object replay supports a safe subsequent retry
- Scan observations are immutable and deduplicated by tenant/object/scanner/time; contradictory observations conflict, latest lookup is deterministic and bounded, and `CLEAN`/`INFECTED` require the quarantine digest
- Scanner `ERROR` observations remain durable when the digest could not be observed, but cannot authorize promotion
- `DocumentSecurityOperations` verifies the authorized transaction before storage I/O, rejects returned-reference drift, requires durable quarantine metadata before scanning, and persists the accepted result
- A ninth ArchUnit rule prevents API packages from bypassing the coordinator through storage, scanner, or evidence-store ports
- Five focused application tests and six disposable PostgreSQL 18 tests pass for ordering, idempotency, rollback, missing/mismatched/ambiguous evidence, forced RLS, cross-tenant denial, request-context binding, restricted grants, and append-only migration-owner attacks
- A clean Java 25 build compiles 146 production sources and 14 test sources, applies Flyway through V8, passes all 99 tests against disposable dependencies, and packages the bootable JAR

This closes the policy-neutral durable evidence gap only. It adds no upload/download route, business document state machine, permission/event entry, approved freshness policy, clean promotion, signed access, retention/legal hold, or production object-store/scanner acceptance. The external storage/scanner adapters remain disabled by default.

## Configured but not yet fully integration-verified

- Complete six-service Docker Compose startup on the default host ports
- Remote GitHub Actions execution of both updated workflows, including dependency-graph access, CodeQL result upload, required-check/repository-rule enforcement, and SBOM artifact retention
- Signed image provenance, production-registry admission policy, and deployment-time SBOM/signature verification
- Production object-store/IAM/KMS, scanner/network/signature operations, Redis ACL/TLS/HA/persistence/restore acceptance, and notification key-management/backup/restore acceptance; promotion, signed access, retention, notification consent/destination/provider delivery, worker, and scheduler behavior
- Production metric/log/trace collection, non-interactive scraper identity or private management boundary, service objectives, dashboards, alert delivery/on-call escalation, encrypted backups, and timed restore evidence

The current Phase 0 work has closed the local Java/frontend build-verification, shared API/client convention, session-aware frontend shell, automatic no-polling session-expiry convergence, browser password-recovery and MFA enrollment/recovery-code self-service, CI supply-chain, and repository operational-signal baseline gaps and now verifies PostgreSQL migration/RLS, Redis-backed browser identity, membership-backed organization selection, fail-closed frontend and backend tenant gates, policy-neutral audit/outbox/idempotency mechanics, explicit external-capability boundaries, private-quarantine storage, fail-closed scanner transport/integrity, append-only quarantine/scan evidence, durable Redis job-transport mechanics, encrypted PostgreSQL notification persistence/leasing mechanics, generated frontend contract drift, credentialed CSRF-aware HTTP handling, frontend dependency direction, ECS JSON request telemetry, bounded trace context, authenticated Prometheus format, dependency-aware probes, digest-pinned image builds, vulnerability/SAST/secret/configuration gates, SBOM generation, and unprivileged container execution. Owner-approved authorization/event/job/template content, governed invitations/account linkage and administrative MFA reset, protected business endpoints and screen integration, non-interactive worker/scraper/service identity, hosted-CI/registry enforcement, production storage/scanner/Redis/key-management acceptance, consent/destination/provider delivery, external telemetry/alerting, tested backup/restore, promotion/signed-access/retention adapters, and all production workflows remain incomplete.

## Production status

This checkpoint is intentionally named **foundation**. It is runnable and suitable for starting development. It is not a production release, clinical-device claim, security certification or completed implementation of Modules 1-13.
