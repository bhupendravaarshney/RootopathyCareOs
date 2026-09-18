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
- At this checkpoint M1-02 remained unavailable; Phase 0X later replaces it with governed reference issue/revoke/acceptance/linkage
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
- Recovery-code regeneration requires explicit acknowledgement that previous codes are immediately revoked; at this checkpoint MFA disable and administrator reset remained unavailable, with reference administrator reset added later in Phase 0X
- The frontend architecture gate accepts 18 source files and 35 inward relative imports; generated drift, format, strict typecheck, lint, 24 unit tests, and the Vite production build pass
- All 16 Playwright tests pass on desktop and mobile-320, including the 75 protected-route Axe sweep plus checked-client password recovery and recent-authenticated MFA enrollment flows
- A clean host-side Java 25 build compiles 137 production sources and 11 test sources, passes all 84 tests against disposable dependencies, applies Flyway through V7, and packages the bootable JAR
- The 15-operation OpenAPI 3.1 version 0.5.0 contract/six conventions, six API negative tests, 79-screen register, and CI-security verifier/10 negative tests remain green

This closed password-recovery and MFA enrollment/recovery-code browser self-service at that checkpoint. Phase 0Q later adds expiry convergence, and Phase 0X adds reference invitations/linkage, service authorization, and maker-checker administrator reset. Self-disable policy, production approval, and every tenant business workflow remain incomplete.

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

This closes automatic browser session-expiry convergence for the checked client. Direct credentialed fetches remain prohibited because they would bypass lifecycle publication. Phase 0X later adds reference invitations/linkage, service authorization, and maker-checker administrator reset; self-disable policy, production approval, and every tenant business workflow remain incomplete.

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

This closes the policy-neutral durable evidence gap only. It adds no upload/download route, business document state machine, permission/event entry, approved freshness policy, clean promotion, signed access, immutable retention, or production object-store/scanner acceptance. Later slices supply the three document mechanics; approved policies, governed hold release/disposal, and provider acceptance remain open. The external storage/scanner adapters remain disabled by default.

## Verified during Phase 0S foundation (15 September 2026)

- Flyway V9 adds a migration-owned, runtime-read-only `outbox_consumer_definitions` allow-list linked to the existing versioned outbox registry; it remains intentionally empty until production consumer mappings are approved
- Tenant-scoped `consumer_inbox_records` use a composite organization/consumer/source-event primary key, forced RLS, `SELECT`/`INSERT`-only runtime grants, transaction-context/server-time insertion, and append-only enforcement even for the migration owner
- Startup rejects unsafe runtime-role flags, table ownership, missing/excess grants, policy/trigger/key/FK drift, unavailable database time, or inbox visibility without tenant context
- `ConsumerInboxExecutor` makes authorization, unique receipt acquisition, and the first consumer callback one transaction; callback failure rolls back the receipt and effect for safe later retry
- Active consumer/event mapping, aggregate type, and top-level payload keys are validated before work; PostgreSQL-canonical JSON is SHA-256 hashed and only its digest plus bounded envelope metadata is retained
- Exact canonical redelivery returns the original receipt without executing work, changed content conflicts, and simultaneous deliveries serialize to one callback
- A tenth ArchUnit rule prevents API packages from bypassing transactional inbox execution through the low-level port
- Six added PostgreSQL scenarios cover canonical replay, conflict/unknown/payload rejection, rollback/retry, concurrency, missing transaction, tenant isolation, cross-tenant SQL, restricted grants, and owner-level immutability
- A clean Java 25 build compiles 153 production sources and 14 test sources, applies Flyway through V9, passes all 106 tests against disposable dependencies, and packages the bootable JAR
- The unchanged 15-operation/seven-convention API and all seven negative tests, 79-screen register, and CI-security verifier/all ten negative tests were rerun and pass

This closes only reusable consumer inbox/deduplication mechanics. No production consumer definition, non-interactive identity, tenant dispatch, authenticated transport/subscription, broker acknowledgement, governed business effect, replay operation, monitoring, retention, or dead-letter procedure is active.

## Verified during Phase 0T foundation (15 September 2026)

- Flyway V10 adds one tenant/document/object-version promotion record linked to the exact scan attestation and snapshots policy key, canonical accepted scanners, scan-age/future-skew bounds, request context, and PostgreSQL promotion time
- Forced RLS, tenant policy, `SELECT`/`INSERT`-only runtime grants, context/server-time insert validation, and append-only owner enforcement protect the promotion table; startup validates role, ownership, grants, policy, triggers, link shape, and missing-context invisibility
- The database independently accepts only the latest `CLEAN` attestation from a snapshotted accepted scanner inside the database-time freshness window
- `DocumentPromotionOperations` reloads authoritative quarantine/latest-scan evidence, verifies reference/digest/scanner/freshness, copies before recording evidence, rejects adapter/evidence drift, and returns exact existing evidence on replay
- The disabled-by-default S3-compatible promotion adapter requires an exact current configured policy authorization and distinct policy-free quarantine/clean buckets; it uses tenant-derived keys, conditional creation, ETag-bound reads, exact byte/digest streaming, mismatch cleanup, verified replay, and never deletes quarantine
- Configuration requires an explicit bounded policy key, one-to-sixteen scanner allow-list, one-second-to-30-day scan age, and zero-to-five-minute future skew; production preflight rejects incomplete promotion activation
- The existing document architecture rule now also prevents API packages from calling the low-level promotion port
- Thirteen added backend tests cover coordinator order and rejection, storage privacy/integrity/replay/conflicts, PostgreSQL RLS/latest-clean-scanner-freshness/immutability attacks, and production preflight
- A clean Java 25 build compiles 161 production sources and 15 test sources, applies Flyway through V10, passes all 119 tests against disposable dependencies, and packages the bootable JAR
- Both Compose models and the unchanged 15-operation/seven-convention API with seven negative tests, 79-screen register, and CI-security verifier with ten negative tests pass

This closes only clean-promotion mechanics. Promotion remains disabled by default, the checked values are synthetic, and no route can deliver the object. Phase 0U later adds internal signed-read mechanics and Phase 0V later adds immutable retention/legal-hold-enablement mechanics; approved M7 document state/provenance, permission/event/outbox transitions, production storage/IAM/KMS/versioning/Object-Lock/backup/monitoring acceptance, governed hold release/disposal, and protected HTTP/browser coverage remain open.

## Verified during Phase 0U foundation (15 September 2026)

- Flyway V11 adds URL-free append-only document-access grant evidence linked by tenant/document/version keys to committed V10 promotion evidence
- The row snapshots policy key, canonical accepted purposes, requested/maximum TTL, authorization age/future skew, actor, purpose, correlation, authorization/grant time, and expiry without storing a bearer URL, signature, credential, bucket, or object key
- Forced RLS, `SELECT`/`INSERT`-only runtime grants, context-bound insertion, PostgreSQL-time purpose/authorization/expiry validation, composite promotion linkage, and owner-level mutation rejection protect the table; startup checks every control and missing-context invisibility
- `DocumentAccessOperations` requires committed promotion evidence, tenant/purpose policy, exact signer response, and matching persisted evidence before returning a URL from the authorized transaction
- The disabled-by-default S3 signer rechecks its configured policy and authorization age, verifies clean metadata/size/type/ETag, performs an ETag-bound full SHA-256 download, signs only bounded `GET`, and rejects a returned URL outside the configured origin
- Configuration permits one-to-sixteen explicit purposes, one-second-to-one-hour URL TTL, one-second-to-five-minute authorization age, and zero-to-one-minute future skew; production preflight rejects incomplete activation
- The document architecture rule prevents API packages from invoking the low-level signer
- Twelve added backend tests cover orchestration and failure ordering, promotion/purpose/TTL constraints, database RLS/direct-SQL/immutability attacks, private clean-object integrity, real signed retrieval and write denial, activation, and production preflight
- A focused 49-test security run and a clean Java 25 build compile 170 production sources and 16 test sources, apply Flyway through V11, pass all 131 tests against disposable dependencies, and package the bootable JAR
- Both Compose models and the unchanged 15-operation/seven-convention API with seven negative tests, 79-screen register, and CI-security verifier with ten negative tests pass

This closes only internal signed-access mechanics. Signed access remains disabled by default, the checked policy is synthetic, bearer URLs are never persisted, and no HTTP route exists. Phase 0V later adds immutable retention/legal-hold-enablement mechanics; approved document-read/retention permissions, purposes and state, governed audit/event behavior, production storage/IAM/KMS/versioning/Object-Lock/backup/monitoring, hold release/disposal/revocation, and protected HTTP/browser coverage remain open.

## Verified during Phase 0V foundation (15 September 2026)

- Flyway V12 adds append-only tenant/document/object-version retention evidence linked to exact V10 promotion evidence and exact predecessor directives
- The row snapshots policy and request context, requested deadline, hold state, authorization/application time, and only a SHA-256 provider-version identifier; it has no bucket, object key, or raw provider version
- Forced RLS, `SELECT`/`INSERT`-only runtime grants, context/server-time binding, per-document advisory serialization, exact predecessor lineage, and owner-level immutability protect the table; the database rejects unpromoted, stale, unapproved, shortened, hold-releasing, no-op, and conflicting evidence
- `DocumentRetentionOperations` requires committed promotion and current durable state, enforces tenant/purpose/policy and monotonic rules, returns exact durable replay without storage I/O, and records evidence only after validating the provider receipt
- The disabled-by-default S3 adapter requires an existing private versioned/Object-Lock-enabled clean bucket, rechecks authorization and current clean-object metadata, performs a version- and ETag-bound full SHA-256 read, applies only Object Lock `COMPLIANCE` retention, optionally enables legal hold, and verifies the exact provider state afterward
- The adapter never creates a bucket, bypasses governance, shortens retention, disables a hold, deletes content, or returns a raw storage identifier; configuration and production preflight require an explicit bounded policy and private S3 storage with runtime bucket creation disabled
- Thirteen added backend tests cover coordinator order/replay/failure/drift, monotonic policy enforcement, PostgreSQL RLS/direct-SQL/immutability attacks, real exact-version Object Lock and delete rejection, content/provider drift, activation, and production preflight
- A focused 56-test retention/security run and a clean Java 25 build compile 179 production sources and 17 test sources, apply Flyway through V12, pass all 144 tests with zero failures, errors, or skips against disposable dependencies, and package the bootable JAR
- Both Compose models and the unchanged 15-operation/seven-convention API with seven negative tests, 79-screen register, and CI-security verifier with ten negative tests pass

This closes only immutable retention application and legal-hold enablement mechanics. Retention remains disabled by default and the checked policy is synthetic. No route, M7 lifecycle, approved schedule/permission/event, hold release, retention shortening, disposal, or production provider IAM/KMS/Object-Lock/backup/monitoring acceptance exists.

## Verified during Phase 0W foundation (15 September 2026)

- A shared Java `UuidV7Generator` emits RFC 9562 UUIDv7 identifiers from a cryptographically secure random source and preserves process-local monotonic ordering during same-millisecond generation or wall-clock rollback
- Every production Java UUID-generation call site uses the shared strategy; an eleventh ArchUnit test rejects future direct `UUID.randomUUID()` use in production code
- Flyway V13 requires PostgreSQL 18 and changes all 12 active database-generated identifier defaults to native `uuidv7()` without rewriting historical rows or rejecting caller-supplied/reference UUIDs
- Four generator tests prove timestamp/version/variant encoding, 10,001 unique strictly ordered same-millisecond identifiers, rollback handling, and invalid input rejection; a database integration test verifies the exact default set and a generated version-7 identifier
- A focused 15-test generator/architecture run, a fresh 26-test PostgreSQL migration/RLS run, and a clean Java 25 build compile 180 production sources and 18 test sources, apply Flyway through V13, pass all 150 tests with zero failures, errors, or skips, and package the bootable JAR
- Both Compose models and the unchanged 15-operation/seven-convention API with seven negative tests, 79-screen register, and CI-security verifier with ten negative tests pass

This closes current identifier-generation consistency, not identifier-based trust. UUID timestamps/order are observable implementation details and never replace tenant authorization, server-owned event time, or database integrity; existing identifiers remain valid.

## Verified during Phase 0X foundation (15 September 2026)

- Flyway V14 adds an explicitly opt-in, migration-owned/runtime-read-only reference authorization policy with operation risk, denial, reason, recent-authentication, delegation-ceiling, and final-effective-owner controls; production preflight rejects its activation
- V15 and the identity service implement governed, idempotent invitation issuance/revocation plus one-use expiry-checked acceptance and existing-account linkage with atomic audit/outbox evidence and no administrator-visible raw token
- V16 supplies disjoint tenant-scoped non-interactive identities and expiring credential digests plus exact credential/tenant/purpose/role/operation authorization; human memberships cannot use service roles and no browser session can substitute for a service credential
- V17 supplies append-only MFA-reset approval evidence: target, maker, and checker are separated; only the original maker consumes an unexpired approval with exact reason/target/idempotency; execution revokes MFA, recovery codes, and sessions and emits evidence exactly once
- M1-02 and M1-03 drive invitation and all three MFA administrator transitions through the generated-type-backed checked client with runtime response validation, recent authentication, explicit reasons, stable retry keys, accessible states, and no persistent token/secret material
- A shared authenticated-actor contract removes tenancy's dependency on the concrete identity principal, and Playwright uses a dedicated strict port instead of reusing an arbitrary application
- A clean Java 25 build compiles 210 production sources and 18 test sources, applies Flyway through V17, passes all 162 tests with zero failures/errors/skips, and packages the JAR; all 11 ArchUnit rules pass
- OpenAPI 3.1 version 0.8.0 checks all 21 operations/seven conventions and all seven negative tests; frontend generation/architecture/type/lint/format/build, all 35 unit tests, all 20 desktop/mobile Playwright/Axe tests, the 79-screen and CI-security contracts/all ten negative tests, both Compose models, and both current container-image builds pass
- A fresh isolated hardened smoke reaches backend readiness at Flyway V17 as UID 65532 and serves/proxies the frontend as `nginx`; both retain read-only roots, dropped capabilities, and no-new-privileges, and all temporary smoke resources were removed

This is the repository-complete Phase 0 reference checkpoint. Production still requires owner approval/replacement of the provisional policy, a decision on MFA enforcement/self-disable, service-credential provisioning/rotation and worker activation, target-environment security/operations evidence, and business-module implementation.

## Reviewed during Phase 1A preimplementation (16 September 2026)

- The complete specification's Module 1 screens, entity families, API routes, lifecycle rules, frontend requirements, security rules, test architecture, and acceptance gate were reconciled with the current repository.
- All 23 M1 routes were reviewed: M1-01 through M1-04 reuse Phase 0 reference identity/selection mechanics, while M1-05 through M1-23 remain generic synthetic templates without production business persistence or APIs.
- The required approved M1 high-fidelity mockups, CareOS Design System 1.0 assets, final policy/registry decisions, and versioned owner approval are absent from the workspace.
- `MODULE_1_IMPLEMENTATION_PLAN.md` now records the screen-by-screen traceability ledger, administration module/data/API/frontend/test boundaries, dependency-ordered M1B-M1G slices, and exact approval checklist.
- Stale architecture/API documentation was reconciled to the actual OpenAPI 3.1 version 0.8.0 and all 21 implemented foundation operations.

Phase 1A changed documentation and delivery control only; no executable source, migration, contract, or dependency changed, so the previously recorded Phase 0X evidence was that checkpoint's executable baseline. Module 1 production implementation is `BLOCKED_INPUT` until the approval checklist is supplied; this status prevents generic prototype behavior from being misrepresented as a product workflow.

## Verified during Phase 1R provisional organization core (16 September 2026)

- Flyway V18 extends the existing organization aggregate with bounded profile validation, updater evidence, monotonic lock revision, and a runtime trigger that permits only the exact tenant/actor/reason-bound reference update operation and mutable field set
- The reference registry adds profile management plus readiness-read/profile-update operations and one versioned profile-updated audit/outbox pair; every entry remains migration-owned, explicitly opt-in outside production, and rejected by production preflight
- The new administration module supplies protected tenant readiness/profile reads and a governed profile update with strong ETag/`If-Match`, caller-owned idempotency, explicit reason, exact replay, stale/conflict handling, and atomic business/audit/outbox/idempotency commit
- M1-05 and M1-06 now render the same server-calculated provisional gates and bounded counts; M1-07 reads and updates the real organization aggregate through the checked client with runtime validation and accessible loading, failure/retry, validation, success, and stale-edit states
- Direct PostgreSQL attacks prove read operations cannot write, profile updates cannot alter lifecycle fields, and exact authorized revisions succeed; HTTP coverage proves missing precondition, success, exact replay, changed-payload conflict, stale ETag rejection, final persisted state, and exactly one audit/outbox pair
- A clean Maven 3.9.11/Java 25 build compiles 219 production sources and 18 test sources, applies Flyway through V18, passes all 164 tests with zero failures/errors/skips, enforces all 11 ArchUnit rules, packages the bootable JAR, and both current application images build
- OpenAPI 3.1 version 0.9.0 checks all 24 operations/seven conventions and all seven negative tests; frontend generation, its 20-source/45-import two-feature architecture boundary/four negative tests, strict typecheck, lint, formatting, all 40 unit tests, production build, and all 22 desktop/mobile Playwright/Axe tests pass
- The 79-screen registry, both Compose models, and the CI-security verifier/all ten negative tests pass

This checkpoint completes a provisional engineering slice only. It does not approve the organization profile dictionary, readiness/activation catalogue, reference permissions/events, final content/designs, or production activation. M1-08 through M1-23 remain synthetic, M1C is not accepted, and the Module 1 production slices remain `BLOCKED_INPUT` until the documented approval package is supplied.

## Verified during Phase 1S current-image assurance (16 September 2026)

- A fresh six-service deployment exposed and then verified the fix for the official PostgreSQL 18 image's required major-version parent mount: Compose now mounts the named volume at `/var/lib/postgresql`, not the rejected legacy `/var/lib/postgresql/data` target
- The dependency-free Compose security verifier now rejects that legacy PostgreSQL 18 target; all ten existing positive/negative CI-security tests and both default/scanner-overlay Compose models pass
- Fresh isolated volumes reach Flyway V18 and seed exactly one synthetic organization; backend liveness/readiness and frontend health pass, the same-origin proxy returns all 79 screens, required frontend security headers are present, and anonymous metrics return `401`
- The backend runs as `65532:65532` and the frontend as `nginx`; both reject root writes, accept writes only through their declared `/tmp` mounts, drop every capability, and retain no-new-privileges
- Trivy 0.74 reports zero fixed HIGH/CRITICAL findings across the Maven/npm manifests, Debian 13.6 backend OS, packaged JAR, and Alpine 3.24.1 frontend OS; both Dockerfiles have zero detected misconfigurations and no secret finding is emitted
- Both current images generate parseable CycloneDX 1.7 SBOMs, containing 199 backend and 22 frontend components in this local run; all temporary containers, networks, volumes, images, and scanner caches were removed

This is repository-local assurance, not hosted or production acceptance. The target environment must still retain its own workflow logs/SBOMs, sign and admit exact artifacts, verify them at deployment, and satisfy the outstanding production control and product-approval gates.

## Verified during Phase 1T executable Module 1 input gate (16 September 2026)

- A checked JSON Schema and state manifest define eight canonical input bundles and retain the honest `BLOCKED_INPUT` state with zero supplied artifacts and no approval
- The dependency-free verifier accepts partial evidence only while blocked, hashes each repository-contained regular file, rejects path escape, missing/non-file/symbolic inputs, checksum drift, duplicate categories or paths, schema/catalogue drift, and incomplete approved claims
- Approved state requires every category, a deterministic exact-package SHA-256, ordered M1-01 through M1-23 scope, a bounded record identifier, a real non-placeholder approver, a valid non-future UTC timestamp, and a distinct checksum-matched approval-evidence file
- Normal consistency verification reports all eight missing categories and `implementationAuthorized: false`; `--require-approved` exits nonzero for the checked state
- All fourteen focused gate tests pass, including a fully checksum-bound synthetic approval fixture, and the quality workflow runs both the verifier and its tests
- The CI-security contract now requires those quality-workflow commands; its eleven positive/negative tests pass

This checkpoint automates the approval boundary but does not satisfy it. No mockup, Design System, policy, data, lifecycle, event, export, or owner-approval artifact has been supplied or inferred.

## Verified during Phase 1U environment-scoped foundation seed strategy (16 September 2026)

- Flyway V19 compensates for the immutable historical V1 fixture by removing the exact reserved organization and facility in base/production mode while retaining them only under hard-coded local/test configuration
- Cleanup first verifies the untouched organization profile/revision and exact sole facility; changed or additional facility state aborts transactionally
- Any dependent tenant row raises an explicit foreign-key remediation failure rather than cascading, deleting history, or guessing that data is disposable
- A dedicated PostgreSQL 18 test proves changed-fixture rollback with no successful V19 history record, row preservation, explicit remediation, successful retry, and a zero-tenant final production/default state
- The existing tenant-RLS integration suite reaches V19 with the test fixture retained, proving the non-production path remains usable
- The dependency-free security verifier now protects all four profile values plus the V19 opt-in, cleanup, and foreign-key safeguards; all twelve positive/negative contract tests pass
- A clean Maven 3.9.11/Java 25 build compiles 219 production and 19 test sources, validates/applies all 19 migrations, passes all 165 backend tests without failures, errors, or skips, and packages the bootable JAR

This resolves environment scoping for the legacy fixture only. Production tenant provisioning, initial-owner ceremony, migration/import procedures, and approval of organization activation behavior remain open.

## Verified during Phase 1V fail-closed protected route resolution (16 September 2026)

- The checked 79-screen registry now rejects unknown identifiers instead of silently returning M1-05
- The root route boundary renders protected content only for a registered ID after the existing authentication and organization gates have succeeded
- An authenticated unknown route retains safe organization and sign-out controls but has no active screen link, loads no business page, and never reflects the untrusted route text
- The dedicated not-found state focuses its heading and offers one explicit recovery link to the administration dashboard
- Generated API drift, the 21-source/48-import frontend architecture boundary and four negative fixtures, strict typecheck, lint, formatting, all 42 unit tests, and the production build pass
- All 24 desktop/mobile Playwright cases pass, including the complete 79-route Axe matrix and unknown-route accessibility/focus/recovery on desktop and the 320px viewport

This is a frontend routing safety checkpoint, not approval of the hash router or any product screen. The backend is unchanged from the Phase 1U 165-test/Flyway V19 baseline, and production Module 1 remains `BLOCKED_INPUT`.

## Verified during Phase 1W exact responsive-breakpoint assurance (16 September 2026)

- Playwright now declares exact 1440, 1024, 768, 390, and 320 pixel projects instead of relying on one implicit desktop width and one 320px project
- Every registered route rejects body or document horizontal overflow and retains its route-specific serious/critical Axe check at every required width
- The expanded matrix exposed list-table page overflow at 768, 390, and 320 pixels because an absolutely positioned screen-reader-only header escaped the horizontal scroll wrapper
- The table wrapper now establishes a positioned, zero-minimum-width, 100%-bounded containing block; all six originally failing M1/M2 module/viewport cases pass
- Responsive navigation coverage proves persistent navigation at 1440/1024/768 and drawer opening, workspace navigation, and automatic closing at 390/320
- The dependency-free repository verifier requires all five exact projects and the document/body, registered-route, identity-route, exact-width, and drawer-boundary assertions; its new mutation test rejects missing 768 coverage and weakened overflow checks, bringing that suite to thirteen tests
- Generated API drift, the 21-source/48-import architecture boundary and four negative fixtures, strict typecheck, lint, formatting, all 42 unit tests, and the production build pass
- All 60 Playwright cases pass across the five projects, including 395 registered-route viewport renders and the complete identity, recovery, invitation, MFA, profile, session, navigation, and unknown-route workflow set

This verifies the current reference layout only. Production responsive designs, table/card alternatives, keyboard/dialog focus acceptance, and formal WCAG 2.2 AA evidence remain approval-dependent. Backend behavior remains at the Phase 1U 165-test/Flyway V19 baseline.

## Verified during Phase 1X honest synthetic-interaction containment (16 September 2026)

- Every generic dashboard/list/form/clinical route now shows a named synthetic-only boundary, tells users not to enter real personal or clinical information, uses unmistakably synthetic sample identities and records, and keeps generic form/clinical fields read-only
- Unapproved `Review`, `Open`, save, and clinical confirmation actions are natively disabled with accessible explanations; the former browser-local save and confirmation success simulations no longer exist
- Generic row links no longer route synthetic records to unrelated screens, while list search/status/scope and `Clear filters` now perform their stated local behavior and expose a polite bounded result count
- A terminal pagination destination renders as disabled text instead of an activatable `aria-disabled` anchor
- The first expanded Axe run detected that removing fake row links left overflowing tables without a keyboard target; table scrollers are now named focusable regions with a visible focus ring
- Generated API drift, the 21-source/48-import architecture boundary and four negative fixtures, strict typecheck, lint, formatting, all 45 unit tests, and the production build pass
- All 65 Playwright cases pass across the five exact projects, including 395 registered-route renders and five new action/filter/pagination boundary cases with Axe and overflow checks

This makes the generic reference UI honest and keyboard-reachable; it does not implement the disabled workflows or approve any product content. Backend and migration behavior remain unchanged from the Phase 1U baseline.

## Verified during Phase 1Y Module 1 owner-review draft preparation (16 September 2026)

- Eight review briefs now cover the exact Module 1 input categories: screen mockups, Design System, data dictionary/validation, lifecycle/transitions, authorization, audit/events, readiness/activation, and history/export
- The packet is isolated under `docs/module-1-review-drafts/`; every brief and its manifest are explicitly `DRAFT_NOT_APPROVED`, list unresolved owner decisions, and provide acceptance criteria
- The draft verifier requires all eight canonical entries, safe repository-contained regular files, exact draft metadata/sections, and every M1-01 through M1-23 identifier while always returning `implementationAuthorized: false`
- The verifier reports all 8 drafts present with package digest `bee95ca0ddb74d14256d2dd80fe9565021e64a9f6feb6124b7a1110335a3fe63`; all seven focused tests pass
- Both draft checks are required by local/CI verification, and all thirteen repository security tests pass with removal coverage
- The independent production gate remains unchanged and correctly fails approval mode with `BLOCKED_INPUT`, zero of eight approved artifacts, no approval, and no implementation authority

This is a checked decision-preparation package, not approved product evidence. It gives owners concrete material to revise and accept while preserving the production stop condition.

## Verified during Phase 1Z Module 1 candidate input preparation (16 September 2026)

- `candidate-inputs/module-1/` contains all eight concrete `m1-candidate-1` artifacts and is isolated from the production approval directory
- The visual artifact is a self-contained, keyboard-operable M1-01 through M1-23 review application with state switching, responsive table/card behavior, dialogs, focus recovery, and operation/permission/assurance annotations
- Seven companion artifacts freeze proposed Design System, data/validation, lifecycle, authorization, event, readiness/activation, and history/export contracts
- The candidate verifier reports 8/8 artifacts, digest `c2087548aacd35eb4927532d63b844851c6fe9c56cccb56e46596d707d7a1a53`, `CANDIDATE_FOR_APPROVAL`, and `implementationAuthorized: false`; all eight tests pass
- All thirteen repository-security tests require the production, draft, and candidate checks in quality CI
- Generated API drift, the 21-source/48-import architecture boundary and four negative fixtures, typecheck, lint, formatting, 45 unit tests, and production build pass
- All 70 browser tests pass across 1440/1024/768/390/320, including five new candidate navigation/state/focus/Axe/overflow cases and the unchanged 65 application cases
- The production verifier still reports `BLOCKED_INPUT`, zero of eight approved artifacts, no approval, and no implementation authority

This was the final candidate checkpoint. The exact digest was subsequently accepted unchanged in Phase 1AA; the candidate package itself remains non-authorizing provenance.

## Verified during Phase 1AA Module 1 input approval (16 September 2026)

- All eight accepted `m1-candidate-1` artifacts were copied byte-for-byte to `approved-inputs/module-1/`
- Approval record `M1-APPROVAL-20260916-01` binds approver **bhupendra, developer**, the exact package, all M1-01 through M1-23 screens, decision time, candidate digest, and distinct approval-evidence checksum
- The production manifest reports `APPROVED`, eight of eight artifacts, no missing categories, and `implementationAuthorized: true`
- Normal and `--require-approved` verification both pass at package digest `19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946`
- All fourteen production input-gate tests pass, including the checked approved state and isolated blocked/partial/tampered/placeholder/future fixtures

This authorizes Module 1 implementation against the exact approved bytes. It does not mark any implementation slice or target environment complete, and any artifact drift requires new approval evidence.

## Verified during Phase 1AB approved authorization and first M1B increment (16 September 2026)

- Flyway V20 creates immutable checksum-bound `authorization_registry_releases` evidence and activates the approved interactive permission, role, grant, and delegation catalogue
- `local_bootstrap`, obsolete member policy, and non-interactive service identities remain reference-only; production continues to reject the reference-policy flag
- Existing organization profile/readiness operations are promoted, invitation operation/event names are rebound to approved `identity.*`/`access.*` keys, and the administrative MFA execution workflow uses its exact approved target operation
- Tenant authorization now enforces a separate recent MFA timestamp for invitation issue/revoke and MFA reset request/approve/execute and maps missing evidence to `428 mfa-required`
- PostgreSQL retains final-owner, delegation, invitation, maker/checker/target, approval-consumption, append-only evidence, and profile-update boundary checks
- Production invitation/MFA activation remains disabled by default and is permitted only for exact registry `m1-candidate-1` and the approved package digest; digest drift fails the startup guard
- The invitation UI defaults to `organization_viewer`, offers the approved non-owner roles, and retains server-authoritative delegation enforcement
- Checked OpenAPI descriptions and generated TypeScript now expose the approved-registry and recent-MFA contract
- Verification passes: fresh V20 migration; 14 identity HTTP cases; 33 tenant/RLS cases; 10 production-guard cases; a clean 166-test Maven/Java 25 build and packaged JAR; both production input-verifier modes/all 14 tests; frontend API drift/architecture/format/typecheck/lint; all 45 unit tests; the production build; and all 70 five-viewport Playwright/Axe cases

This was the first M1B increment, not M1B completion. At that checkpoint membership listing, maker-checker role/scope change and revocation, owner transfer, permission-projected actions, final visual/browser acceptance, and target-environment evidence remained open.

## Verified during Phase 1AC authorized M1-20 membership read (17 September 2026)

- Flyway V21 activates exact approved operation `access.membership.read` with hidden denial and no unimplemented membership-mutation capability
- `GET /api/v1/organizations/{organizationId}/memberships` authorizes inside the actor-bound tenant transaction, applies forced RLS, and returns the approved minimum membership projection
- Search is NFC-normalized and literal; access-state and role filters are allow-listed; unknown and duplicate parameters fail closed
- Stable descending keyset pagination uses HMAC-SHA256 cursors bound to operation, tenant, normalized filters, limit, snapshot, and position, with a 15-minute expiry
- Server action projection reads current live approved permissions and exposes only authorized invitation navigation and eligible-target MFA-reset navigation; role/scope/revoke/owner mutations remain unavailable
- OpenAPI 3.1 version 0.10.0 contains 25 operations, generated TypeScript/client drift checks pass, and M1-20 is a real administration page with loading, failure/retry, empty/no-result, filter, cursor-history, responsive-table, and action states
- Verification passes: a clean Java 25 build compiles 223 production and 19 test sources, applies all 21 migrations, passes all 168 backend tests, enforces all 11 architecture rules, and packages the JAR; all 47 frontend unit tests and all 75 five-viewport Playwright/Axe cases pass
- The seven API contract tests, four frontend architecture negative fixtures, 79-screen register, both approved-input modes/all 14 tests, seven review tests, eight candidate tests, and all 13 repository-security tests pass

This completed the M1-20 list/read increment only. At that checkpoint maker-checker membership role/scope change and revocation, final-owner-safe owner transfer, final visual/owner acceptance, and target-environment evidence remained open. Phase 1AD below resolves organization-wide non-owner role changes and revocation.

## Verified during Phase 1AD governed membership changes (17 September 2026)

- Flyway V22 creates immutable forced-RLS `membership_change_requests`, adds optimistic revisions to memberships, and activates exact approved request/approve/execute operation bindings
- Organization-wide non-owner role changes and membership revocation require a strong current membership ETag, caller-owned idempotency, an exact NFC/control-free reason, recent primary authentication plus MFA, and a live independent approval
- PostgreSQL independently enforces non-owner/self/target restrictions, maker/checker separation, delegation ceilings, exact revision and approval consumption, reason binding, final-owner safety, and the only permitted role/revocation row transitions
- Approved final `identity.membership.changed` and `identity.membership.revoked` evidence is written atomically to append-only audit and transactional outbox stores
- Three checked endpoints implement request, approval, and execution with hidden target denial, `428` missing precondition, `412` stale revision, replay-safe responses, no-store caching, and conflict-safe execution
- M1-20 now projects authorized role-change/revocation row actions and approve/execute page actions and provides a runtime-validated governed access-change panel; owner transfer and facility-scoped grants remain honestly unavailable
- Production activation remains disabled by default and is guarded by the exact approved registry and package digest
- OpenAPI 3.1 version 0.11.0 contains 28 operations; generated client drift, frontend architecture/format/typecheck/lint, all 49 unit tests, the production build, and all 80 five-viewport Playwright/Axe/overflow cases pass
- A Java 25 verification build compiles 232 production and 19 test sources, validates and applies all 22 migrations, passes all 170 backend tests, enforces all 11 architecture rules, and packages the JAR

This completes organization-wide non-owner role change and membership revocation only. Facility-scoped grants/scope changes, final-owner-safe owner transfer, final visual/owner acceptance, target-environment evidence, and full M1B acceptance remain open.

## Verified during Phase 1AE governed owner transfer (17 September 2026)

- Flyway V23 creates immutable forced-RLS `owner_transfer_requests` and binds governed request/approve/execute operations to the approved `access.owner_transfer.*` permissions and final `identity.owner.transferred` event
- Owner promotion/demotion requires a strong current membership ETag, caller-owned idempotency, an exact NFC/control-free reason, recent primary authentication plus MFA, and a live 30-minute independent approval
- PostgreSQL independently binds the exact maker, checker, target, organization, membership, from/to role, revision, reason, expiry, and executor; it requires an indefinite promotion target, enforces demotion delegation, and preserves the final indefinite active owner
- The approved maker executes the transition, exact replays are stable, direct unapproved owner-role SQL is rejected, and final audit/outbox evidence is atomic with the membership update
- Three checked endpoints and the M1-20 governed access panel implement request, approval, and execution; live action projection exposes eligible owner promotion/demotion plus approval/execution actions without advertising facility scope
- OpenAPI 3.1 version 0.12.0 contains 31 operations; generated client drift, frontend architecture/format/typecheck/lint, all 51 unit tests, the production build, and all 85 five-viewport Playwright/Axe/overflow cases pass
- A Maven 3.9.11/Java 25 clean verification compiles 232 production and 19 test sources, validates and applies all 23 migrations, passes all 171 backend tests, enforces all 11 architecture rules, and packages the JAR

This completes final-owner-safe owner transfer. Facility-scoped grants/scope changes, final visual/owner acceptance, target-environment evidence, and full M1B acceptance remain open. The approved package defines only the facility-scope narrowing rule, not the grant record, lifecycle, or exact enforcement contract.

## Verified during Phase 1AF approval-ready facility-scope contract (17 September 2026)

- Additive candidate `m1-facility-scope-candidate-1` defines the previously missing grant record, lifecycle, role-transition interaction, facility derivation, authorization intersection, evidence, API projection, UI states, and database invariants without changing the approved base package
- The exact candidate is bound to `m1-candidate-1`, approval record `M1-APPROVAL-20260916-01`, and package digest `19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946`
- Its strict verifier reports digest `76a3f7a2c63cef0cab02b8a1d38a66220eee0abb99c9be0a82b64eaa8941fddb`, `CANDIDATE_FOR_APPROVAL`, and `implementationAuthorized: false`; all seven candidate tests and all thirteen CI-security tests pass
- No migration, endpoint, permission action, or UI control implements facility scope before a separate accountable approval binds that exact candidate digest

This resolves contract authoring only. Facility-scope runtime work remains blocked on explicit approval.

## Verified during Phase 1AG approved mandatory-role MFA enforcement (17 September 2026)

- Flyway V24 requires the exact approved release, adds an interactive-role MFA requirement, and marks only `organization_owner`, `organization_administrator`, `configuration_approver`, `security_administrator`, `auditor`, and `export_approver`
- A membership-derived, non-tenant-disclosing projection gives the runtime role execute-only access to a boolean MFA-requirement function; the projection follows membership insert, role change, suspension/revocation, expiry, and deletion
- Mandatory-role accounts without enabled MFA receive HTTP `202` and `mfa_enrollment_required`; their restricted sessions cannot load organizations or any workspace operation and can reach only checked enrollment/verification plus session/logout
- Verified enrollment rotates the session and records primary/MFA evidence; the browser keeps the workspace locked until one-time recovery codes are acknowledged
- Protected requests reload authoritative MFA requirements, so an existing password-only session is invalidated immediately when a mandatory membership becomes effective
- PostgreSQL rejects enabled-factor removal unless the exact consumed `identity.mfa.admin-reset.execute` organization, actor, approval, target, correlation, and executor context is present; the governed reset remains successful and no self-disable endpoint is exposed
- Readiness now implements exact non-overrideable `access.final_owner` and `access.mfa_enforced` gates
- OpenAPI 3.1 version 0.13.0 retains 31 operations and adds required `mfaRequired` plus `mfa_enrollment_required`; generated client drift, frontend architecture/format/typecheck/lint, all 52 unit tests, the production build, and all 90 five-viewport Playwright/Axe/overflow cases pass
- A Maven 3.9.11/Java 25 clean verification compiles 232 production and 19 test sources, validates and applies all 24 migrations, passes all 174 backend tests, enforces all 11 architecture rules, and packages the JAR

This completes the approved mandatory-role MFA enrollment/use and active-membership self-disable boundary. Optional viewer/editor organization enforcement remains undefined, facility scope remains unapproved, and final M1B/target-environment acceptance remains open.

## Verified during Phase 1AH approved M1-20 responsive record-card projection (17 September 2026)

- M1-20 now follows the approved responsive component contract: the named, keyboard-focusable membership region contains the full action-heavy table at 768/1024/1440 and semantic record cards at the 390/320 drawer widths
- Each card carries the same administrator, role, protected-owner, access/account, MFA, effective-period, and action information as its table row
- Table rows and cards reuse one `MembershipActions` implementation driven only by the runtime-validated server action projection; resizing does not infer authority and facility-scope controls remain absent
- One new unit test locks the compact projection; the existing five-project M1-20 browser case now asserts cards/no table at 390/320 and table/no cards at 768/1024/1440
- Frontend generated-client drift, architecture/negative fixtures, formatting, strict typecheck, lint, all 53 unit tests, production build, and all 90 five-viewport Playwright/Axe/overflow cases pass
- A fresh Maven 3.9.11/Java 25 containerized clean verification compiles 232 production and 19 test sources, validates/applies all 24 migrations, passes all 174 backend tests without failure/error/skip, enforces all 11 architecture rules, and packages the JAR
- The 79-screen, 31-operation, CI-security, approved-input, review-draft, retained-candidate, and facility-scope-candidate repository contracts pass; no backend/API/database source changed in this increment

This closes M1-20's approved responsive table/card projection gap only. Facility-scope approval and implementation, remaining M1-01 through M1-04 visual reconciliation, explicit M1B owner acceptance, target-environment evidence, and full slice acceptance remain open.

## Verified during Phase 1AI approved identity/access frame and content conformance (17 September 2026)

- The shared identity frame and authenticated workspace shell now expose the approved keyboard-visible `Skip to main content` link; activating it focuses `<main>` without mutating the hash-router URL
- Each identity state and registered workspace route focuses its rendered `h1`, while a focused problem summary retains priority when a request or validation failure is present
- The frontend now uses the approved `Inter`, `Segoe UI`, `Roboto`, system-font stack rather than the unapproved Manrope face
- M1-01 through M1-04 registry titles/purposes and the approved `Continue securely`, `Invite administrator`, and `Open workspace` primary actions are aligned with the approved artifacts
- Frontend generated-client drift, architecture/negative fixtures, formatting, strict typecheck, lint, all 54 unit tests, production build, and all 90 five-viewport Playwright/Axe/overflow cases pass
- The 79-screen, 31-operation, CI-security, approved-input, review-draft, retained-candidate, and facility-scope-candidate repository contracts pass
- No backend, OpenAPI, or database source changed in this increment; the immediately preceding fresh Maven 3.9.11/Java 25 verification remains the current 174-test, Flyway-V24 backend baseline

This closes the shared frame/content portion of M1-01 through M1-04 only. Facility-scope approval and implementation, remaining screen-specific identity states and invitation operations, explicit M1B owner acceptance, target-environment evidence, and full slice acceptance remain open.

## Verified during Phase 1AJ approved identity validation and M1-03 action conformance (17 September 2026)

- A reusable identity-form issue summary now receives focus for local validation and governed-action preparation failures, matching the approved problem-summary behavior
- Password-reset and invitation-acceptance mismatches/size failures mark and describe the exact invalid field with `aria-invalid`, `aria-describedby`, and the approved two-pixel danger border
- Each summary includes a keyboard-operable invalid-field link that focuses the control without changing the fragment-router URL
- Malformed reset/invitation token states, invitation preparation failures, and MFA-reset preparation failures use the same focused summary instead of passive alert text
- The remaining M1-03 enrollment action now uses the approved `Set up authenticator` label
- Frontend generated-client drift, formatting, lint, strict typecheck, production build, the 22-source/49-import architecture boundary and four negative fixtures, all 55 unit tests, and all 90 five-viewport Playwright/Axe/overflow cases pass
- The 79-screen, 31-operation, CI-security, approved-input, review-draft, retained-candidate, and facility-scope-candidate repository contracts plus their 56 combined negative tests pass
- No backend, OpenAPI, or database source changed; the current clean Maven 3.9.11/Java 25 baseline remains 174 backend tests through Flyway V24

This closes the local identity-validation focus/association and M1-03 action-content gap only. Facility-scope approval and implementation, separately authorized invitation inspection/resend/delivery behavior, explicit M1B owner acceptance, target-environment evidence, and full slice acceptance remain open.

## Verified during Phase 1AK exact approved readiness catalogue projection (17 September 2026)

- The readiness endpoint now emits the exact ordered 15-key `m1-readiness-v1` catalogue and only the approved `complete`, `warning`, `blocked`, and `not_applicable` outcomes
- Domain construction rejects wrong catalogue/gate versions, order, counts, outcome totals, or any freshness window other than 15 minutes
- Responses carry organization revision, database-owned evaluation/expiry instants, blocker/warning/not-applicable counts, stable reason/remediation codes, and at most four aggregate evidence references per gate
- Missing approved entity families/evaluators stay visibly blocked or not applicable; only the existing final-owner and mandatory-role-MFA evaluators can report complete
- Deep links are selected against current caller permissions; a readiness-only viewer retains readable M1-07 links but receives M1-06 instead of unauthorized M1-20/M1-21 destinations
- M1-05 now renders dashboard metrics plus five priority exceptions, while M1-06 renders all 15 ordered gates and identifies the response as a live projection rather than persisted validation/activation evidence
- OpenAPI 3.1 version 0.14.0 retains 31 operations and fixes exact readiness keys/outcomes/metadata/evidence/freshness; all 8 contract tests and generated-client drift pass
- Frontend architecture/negative fixtures, formatting, strict typecheck, lint, all 56 unit tests, production build, and all 95 five-viewport Playwright/Axe/overflow cases pass
- A clean Maven 3.9.11/Java 25 verification compiles 232 production and 20 test sources, validates and applies all 24 migrations, passes all 177 backend tests with zero failures, errors, or skips, enforces all 11 architecture rules, and packages the bootable JAR

This is a bounded M1C live-projection increment. It does not create configuration versions or immutable validation results/digests, implement transaction-bound invalidation, complete the missing organization/network/service evaluators, or authorize submission/approval/activation/history/export.

## Verified during Phase 1AL exact approved M1-07 organization profile (18 September 2026)

- Flyway V25 adds the approved optional trading name, required organization type, and required BCP 47 locale to the existing organization aggregate, narrows approved name bounds, fixes the lifecycle vocabulary, and leaves legacy missing values visible but readiness-blocked
- Application validation NFC-normalizes names and the required 10-500-code-point reason, enforces exact name bounds, markup/control rejection for names, control rejection for reasons, approved organization types, ISO country codes, IANA timezones, and BCP 47 locales, and emits stable field-specific RFC 9457 violations
- The database trigger restricts runtime writes to exact profile fields under the authorized tenant/actor/reason context with monotonic revision, non-empty change, required type/locale, and IANA timezone enforcement
- Strong ETag/`If-Match`, caller-owned idempotency, exact replay, stale conflict behavior, and atomic `organization.profile.updated` audit/outbox evidence remain intact; changed fields are sorted and contain only the approved payload plus lock version
- Profile reads project editability from current `organization.profile.manage`; the React screen renders exact editable fields for authorized callers and a usable read-only state for readers without inferring roles
- The exact `organization.profile.complete` readiness evaluator now blocks legacy/malformed/closed profiles and becomes complete after a valid governed update
- OpenAPI 3.1 version 0.15.0 retains 31 operations, locks the exact profile response/update/lifecycle contract, and passes all 9 contract tests (one acceptance case and eight negative drift fixtures) plus generated-client drift
- Frontend architecture, formatting, strict typecheck, lint, all 57 unit tests, the production build, and all 95 five-viewport Playwright/Axe/overflow cases pass
- A clean Maven 3.9.11/Java 25 verification compiles 232 production and 21 test sources, validates and applies all 25 migrations, passes all 181 backend tests without failure/error/skip, enforces all 11 architecture rules, and packages the bootable JAR
- The 79-screen, approved-input, review-draft, retained-candidate, facility-scope-candidate, API, and CI-security repository gates pass

This closes the exact M1-07 profile vertical slice. Phase 1AM below resolves M1-08 registration identifiers and Phase 1AN resolves M1-09 addresses/contacts; M1C still lacks M1-10/M1-11, configuration-version invalidation, final visual/owner acceptance, and target-environment evidence, and it is not a completed activation workflow.

## Verified during Phase 1AM governed M1-08 registration identifiers (18 September 2026)

- Flyway V26 adds a migration-owned approved base identifier-type registry and forced-RLS tenant identifier records with UUIDv7 IDs, exact normalization/bounds/status/range constraints, non-revoked identity uniqueness, primary-range exclusion, one-replacement lineage, immutable terminal history, and guarded transitions
- Exact `organization.identifier.read/manage/verify` operations and all five approved identifier audit/outbox event pairs are active; verification requires owner/admin authority, MFA, authentication no older than ten minutes, reason, and bounded evidence
- Six checked operations implement list, draft create/edit, verification, revocation, and atomic supersession with scoped idempotency, exact replay, strong predecessor/replacement revisions, safe Problems, current permission projection, and minimum-necessary evidence
- Supersession locks both rows deterministically, requires a separately verified unused same-type replacement, preserves predecessor history, writes `supersedesId`, promotes a replacement to primary where required, and cannot strand a required current primary
- The live `organization.identifier.primary_verified` evaluator uses applicable migration-owned type metadata and current verified primary state, returns bounded required/current count evidence, and never claims persisted configuration validation
- M1-08 now renders responsive server-backed identifier cards and governed create/edit/verify/revoke/supersede forms, rejects response/lifecycle/action/lineage drift, and uses only server-projected actions
- OpenAPI 3.1 version 0.16.0 verifies 37 operations and seven conventions; all 11 contract tests and generated-client drift pass
- Frontend architecture, formatting, strict typecheck, lint, all 60 unit tests, the production build, and all 100 five-viewport Playwright/Axe/overflow cases pass
- A clean Maven 3.9.11/Java 25 verification compiles 241 production and 22 test sources, validates and applies all 26 migrations, passes all 185 backend tests without failure/error/skip, enforces all 11 architecture rules, and packages the bootable JAR
- The 79-screen, approved-input, review-draft, retained-candidate, facility-scope-candidate, API, and CI-security repository gates pass

This closes the bounded M1-08 screen and live evaluator. Identifier activation remains part of the later configuration-approval slice, allow-listed expiry automation remains future worker/evidence work, and Phase 1AN below resolves M1-09 while M1C remains incomplete.

## Verified during Phase 1AN governed M1-09 effective addresses and masked contacts (18 September 2026)

- Flyway V27 adds migration-owned `operational` purpose metadata and forced-RLS address/contact history with UUIDv7 IDs, exact type/channel/value/range constraints, primary/preferred overlap exclusion, current contact uniqueness, immutable terminal history, and one-replacement lineage
- Exact `organization.contact.read/manage` bindings and `organization.address.changed`/`organization.contact.changed` audit/outbox pairs are active; event payloads contain only record ID, change type, effective start, and lock version
- Eight checked operations implement directory read, address create/supersede/end, and contact create/verify/supersede/end with reason, scoped idempotency, strong predecessor revisions, safe Problems, atomic replacement, and current permission projection
- Contact responses are masked even for managers; raw normalized values are absent from response, audit, and outbox projections, replacement forms start blank, and strict React validation rejects any raw `value` field
- The live `organization.contact.coverage` evaluator requires a current registered address and verified primary operational contact, reports bounded counts, warns for an unverified contact in draft, and blocks it at activation
- M1-09 renders responsive server-backed history cards/forms with only server-projected actions and verified create/verify/end/supersede refresh behavior
- OpenAPI 3.1 version 0.17.0 verifies 45 operations and seven conventions; all 12 contract tests and generated-client drift pass
- Frontend architecture, formatting, strict typecheck, lint, all 64 unit tests, the production build, and all 105 five-viewport Playwright/Axe/overflow cases pass
- A clean Maven 3.9.11/Java 25 verification compiles 251 production and 23 test sources, validates and applies all 27 migrations, passes all 189 backend tests without failure/error/skip, and enforces all 11 architecture rules
- The 79-screen, approved-input, review-draft, retained-candidate, facility-scope-candidate, API, and CI-security repository gates pass

This closes the bounded M1-09 screen and live evaluator. Scheduled address/contact activation and expiry remain future allow-listed worker/evidence work, and M1C still requires M1-10/M1-11, configuration invalidation, final owner/visual acceptance, and target-environment evidence.

## Verified during Phase 1AO governed M1-10 international settings (18 September 2026)

- Flyway V28 adds forced-RLS immutable international-settings versions, UUIDv7 identity, one pending future version, effective-range/lineage/revision guards, and exact read/manage plus `organization.settings.changed` bindings.
- GET projects a virtual organization-derived baseline until the first change; PUT atomically persists that baseline and its future replacement with strong If-Match, caller-owned idempotency, reason, safe evidence, and no profile mutation.
- Server-owned impact rules cover country, timezone, locale, language, currency, and week-start changes; all display samples come from locale libraries and arbitrary format strings are excluded.
- OpenAPI 3.1 version 0.18.0 verifies 47 operations and seven conventions; all 13 contract tests and generated-client drift pass.
- Strict typecheck, lint, all 64 frontend unit tests, and all 105 five-viewport Playwright/Axe/overflow cases pass.
- Maven 3.9.11/Java 25 compiles 261 production and 24 test sources, applies all 28 migrations, and all 193 backend tests pass without failure, error, or skip.

This closes the bounded M1-10 slice. M1-11, configuration invalidation, scheduled identifier/address/contact automation, final acceptance, and target-environment evidence remain.

## Verified during Phase 1AP governed M1-11 responsibilities (18 September 2026)

- Flyway V29 adds forced-RLS, UUIDv7, immutable effective governance responsibility history with eligible membership/contact links, confidential escalation data, non-overlapping primary coverage, and deferred gap-free replacement.
- Exact read/manage policy, MFA plus ten-minute recent authentication, reason, idempotency, strong revision, and five-field `organization.governance.changed` evidence govern mutations.
- The server-backed M1-11 screen assigns clinical, privacy, security, and billing responsibility, displays masked escalation data/history, and derives actions from the live permission projection.
- The live readiness evaluator blocks activation until all four current primary responsibilities have an escalation channel.
- OpenAPI 0.19.0 verifies 51 operations and seven conventions; all 14 contract tests and generated-client drift pass.
- Maven 3.9.11/Java 25 applies all 29 migrations and reports 194 passing backend tests with zero failures/errors/skips, including 35 RLS and 11 architecture tests.
- Formatting, strict typecheck, lint, all 64 frontend unit tests, the production build, and all 105 five-viewport Playwright/Axe/overflow cases pass.

This closes the bounded M1-11 screen and live evaluator. M1C still requires scheduled identifier/address/contact automation, configuration invalidation, final acceptance, and target-environment evidence.

## Verified during Phase 1AQ M1-12 facility persistence foundation (18 September 2026)

- Flyway V30 extends the forced-RLS facility aggregate with the approved identity, type, same-tenant references, timezone, lifecycle, actor, revision, and closure constraints.
- Exact facility read/draft-manage operations and `facility.created` audit/outbox definitions are active; runtime writes require tenant, actor, operation, reason, and draft evidence.
- Tenant-authorized GET/POST backend operations now provide filtered facility directory reads and governed idempotent draft creation; readiness counts only submitted/active facilities with a validated address and effective timezone.
- OpenAPI 0.20.0 verifies 53 operations and seven conventions; all 14 contract tests, generated-client drift, strict typecheck, and lint pass with checked facility read/create client methods.
- M1-12 now renders a strict live facility card directory and permission-gated draft form; all 64 frontend unit tests, formatting, typecheck, lint, and the production build pass.
- The focused HTTP/security test proves filtering, creation, replay, legacy projection, readiness blocking, and one exact audit/outbox pair; all 105 five-viewport Playwright/Axe/overflow cases pass with M1-12 removed from synthetic assertions.
- All 30 migrations and all 35 tenant/RLS integration cases pass on fresh PostgreSQL 18.

The bounded M1-12 directory/draft slice is complete. Facility lifecycle submission/activation and complete profile/address orchestration remain M1-13 work.

## Verified during Phase 1AR M1-13 facility draft editing foundation (18 September 2026)

- Flyway V31 adds the exact `facility.updated` governance definitions without broadening the authorized facility operation.
- Draft-only PUT uses a strong `facility:{id}:{revision}` entity tag, increments the persisted revision, retains forced-RLS and same-tenant references, captures a bounded reason, and returns 412 for a stale write.
- OpenAPI 0.21.0 verifies 54 operations; generated-client drift, strict typecheck, lint, and all 64 frontend unit tests pass with a checked `updateFacilityDraft` client method.
- The 35-test RLS suite passes. A focused live PostgreSQL/Redis browser-security test proves create, checked update, idempotent replay, stale rejection, and exactly one four-key `facility.updated` audit/outbox pair.

This is a bounded M1-13 prerequisite, not lifecycle completion. Submission, impact checks, maker-checker approval/activation, suspension, closure, complete address orchestration, browser edit controls, and final M1D/target acceptance remain open.

## Verified during Phase 1AS M1-13 facility draft editing UI (18 September 2026)

- The live facility directory exposes editing only for authorized draft rows and hydrates the existing checked facility form without inferring authority for non-draft records.
- Save supplies the current strong facility entity tag, a fresh idempotency key, normalized values, and a required reason to the checked client; cancel restores creation mode.
- A focused React interaction assertion verifies the exact facility ID, revision tag, corrected payload, and idempotency-key namespace.
- All 64 frontend tests, strict typecheck, lint, formatting, production build, and all 105 five-viewport Playwright/Axe/overflow cases pass.

Facility submission, impact review, maker-checker approval/activation, suspension, closure, complete address orchestration, and final M1D/target acceptance remain open.

## Verified during Phase 1AT M1-13 facility submission foundation (18 September 2026)

- V32 supplies the exact `facility.submitted` definitions and a narrow database-enforced `draft -> under_review` transition with immutable identity/creation fields and a +1 revision.
- Submission fails closed unless the facility has a validated effective address and effective timezone, and it requires tenant authorization, reason, idempotency, and a strong entity tag bound to that facility ID.
- OpenAPI 0.22.0 verifies 55 operations; generated-client drift, strict typecheck, and all 64 frontend tests pass with the checked submission client.
- The focused HTTP/security test proves completion, submission, replay, readiness, and exactly one four-key audit/outbox pair against fresh PostgreSQL and Redis.

Independent activation, hierarchy/hours/service-policy readiness, suspension/reactivation, closure impact, browser submission controls, and final M1D/target acceptance remain open.

## Verified during Phase 1AU M1-13 facility submission UI (18 September 2026)

- Authorized draft cards expose an inline submission confirmation with the server prerequisite explanation and a required reason; non-draft/read-only projections remain action-free.
- Submission uses the exact current facility revision, a facility-bound strong entity tag, and a fresh idempotency key, retaining governed failure context and accepting only a validated directory response.
- The React interaction proof verifies exact mutation arguments and the returned `under_review` state.
- All 64 frontend tests, strict typecheck, lint, formatting, production build, and 105 five-viewport Playwright/Axe/overflow cases pass.

Independent maker-checker activation, hierarchy/hours/service-policy readiness, suspension/reactivation, closure impact, and final M1D/target acceptance remain open.

## Configured but not yet fully integration-verified

- Remote GitHub Actions execution of both updated workflows, including dependency-graph access, CodeQL result upload, required-check/repository-rule enforcement, and SBOM artifact retention
- Signed image provenance, production-registry admission policy, and deployment-time SBOM/signature verification
- Production object-store/IAM/KMS/versioning/Object-Lock, scanner/network/signature operations, Redis ACL/TLS/HA/persistence/restore acceptance, and notification key-management/backup/restore acceptance; governed promotion/signed-access/retention activation, hold release/disposal, notification consent/destination/provider delivery, worker, and scheduler behavior
- Production service-credential provisioning/rotation, metric/log/trace collection, scraper/worker activation or private management boundary, service objectives, dashboards, alert delivery/on-call escalation, encrypted backups, and timed restore evidence

The current Phase 0 repository work is complete mechanically. Phases 1R through 1Z established the organization-core boundary and exact candidate; Phase 1AA records unchanged approval, Phase 1AB promotes the checksum-bound interactive registry, Phase 1AC delivers the bounded M1-20 membership read, Phase 1AD delivers governed organization-wide non-owner role changes and revocation, Phase 1AE delivers final-owner-safe owner transfer, Phase 1AF freezes the non-authorizing facility-scope candidate, Phase 1AG enforces approved mandatory-role MFA, Phase 1AH supplies the approved M1-20 wide-table/drawer-card projection, Phase 1AI aligns the shared approved identity/access frame, Phase 1AJ supplies focused field-associated identity validation plus the exact M1-03 action, Phase 1AK starts M1C with exact readiness, Phase 1AL completes M1-07, Phase 1AM completes M1-08 identifiers, and Phase 1AN completes M1-09 effective addresses/masked contacts and live coverage readiness. Production remains fail-closed for unimplemented operations and unaccepted target infrastructure. Teams must define tenant provisioning, activate accepted providers/workers and target controls, capture hosted operational evidence, approve and implement the facility-scoped grant contract, finish separately authorized M1B work and the remaining M1C-M1G slices, and obtain slice/module acceptance before production release.

## Production status

This checkpoint is intentionally a **reference foundation**. It is runnable, locally verified, and suitable for continuing approved Module 1 vertical slices. It is not production acceptance, a production release, a clinical-device claim, a security certification, or completion of Modules 1-13.
