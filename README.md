# ROOTOPATHY CareOS — Java + React/Node Edition

This repository is the **from-scratch engineering foundation** for CareOS. It combines a Java Spring Boot modular monolith, a session-aware React/TypeScript frontend built with Node, a checked OpenAPI/generated-client boundary, PostgreSQL, Redis, opt-in private-quarantine, malware-scanner, clean-promotion, signed-access, immutable-retention, durable-job, and encrypted durable-notification adapters, Mailpit and 79 CareOS route states. The organization-wide Module 1 baseline is repository-verified; the approved Module 2 implementation is present through Flyway V65 but its end-of-module verification is still pending.

> Phase 0 mechanics and the approved organization-wide Module 1 repository implementation are mechanically complete through Flyway V50. V20 carries the checksum-bound authorization release; V21-V40 build identity/access, organization, facility, hierarchy, and location slices; V41-V50 add atomic hours, services, assignments, identifier schemes, maker-checker configuration activation, history/audit projections, facility lifecycle, configuration invalidation, and the purpose-bound export lifecycle. This is not target-environment production acceptance or a claim that clinical modules are complete; infrastructure controls, operational evidence, and product workflows still require slice/module owner acceptance.
>
> `m1-candidate-1` was approved unchanged by **bhupendra, developer** on 16 September 2026. The exact eight-artifact package, M1-01 through M1-23 scope, and separate approval evidence are recorded as `M1-APPROVAL-20260916-01`. The checked organization-wide implementation now includes live M1-01 through M1-23 flows and has completed its repository verification pass. M1G owner/target-environment acceptance remains open, as do separately governed automation/worker deployment and the unapproved facility-scoped grant extension.
>
> The Module 1 input boundary is machine-verifiable: `contracts/module-1-input-gate.json` reports `APPROVED`, eight of eight required bundles, package digest `19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946`, and `implementationAuthorized: true`. Both normal and `--require-approved` verification pass.
>
> A separate eight-part owner-review packet is available under `docs/module-1-review-drafts/`. It is source-grounded and machine-checked, but every file is `DRAFT_NOT_APPROVED`; it gives decision owners a starting point and never authorizes implementation.
>
> The original proposal remains under `candidate-inputs/module-1/` as non-authorizing provenance. Its exact accepted bytes were promoted to `approved-inputs/module-1/`; authority comes only from the production manifest and approval record, never from the candidate verifier.
>
> The approved base package did not define exact facility-scope storage or enforcement. The additive `m1-facility-scope-candidate-1` contract now supplies an approval-ready proposal at digest `76a3f7a2c63cef0cab02b8a1d38a66220eee0abb99c9be0a82b64eaa8941fddb`. It remains `CANDIDATE_FOR_APPROVAL`, reports `implementationAuthorized: false`, and does not change runtime behavior until separately approved.
>
> The user accepted commit `2ba6c9b` as the Module 1 predecessor baseline and approved `m2-candidate-1` unchanged on 21 September 2026. `contracts/module-2-input-gate.json` reports `APPROVED` and `implementationAuthorized: true`; approval record `M2-APPROVAL-20260921-01` binds all 29 screens, the exact 44-table baseline, candidate digest `2e64bd4e1ac5192a9a4783abf58f4578b8bceda60c21c2e835a910e6760d0f8f`, and promoted-package digest `624df2edc0024526040271911d43a1b33a12e723fefb3beb3e985264cef89521`. The approved bytes are under `approved-inputs/module-2/`; the original candidate remains unchanged as provenance.
>
> Module 2 implementation is in progress. Migrations V51-V65, the workforce module, 29 live route projections/actions, worker coordinators, and the 109-operation OpenAPI/browser boundary are implemented. Credential-document quarantine/scanning/promotion/access, readiness and eligibility invalidation, expiry/notification processing, export/retention, and lifecycle/offboarding controls are represented. Testing has deliberately not yet been run under the user's end-only testing instruction; these additions are implementation evidence, not a verified completion claim.

## Runtime stack

- Java 25 LTS
- Spring Boot 4.1.x
- Maven 3.9
- React 19 + strict TypeScript
- Node.js 24 LTS + Vite
- PostgreSQL 18 + Flyway, including native UUIDv7 defaults
- Redis 8; one official multi-architecture Alpine digest is pinned across Compose and tests
- Opt-in tenant-isolated Redis durable-job transport; worker execution remains disabled
- Opt-in tenant-isolated PostgreSQL encrypted notification store; recipient routing and outbound delivery remain disabled
- S3-compatible private quarantine adapter; pinned MinIO only as a synthetic local compatibility target
- Opt-in ClamAV 1.5.3 scanner adapter; pinned official image only as a synthetic local compatibility target
- Opt-in S3 Object Lock `COMPLIANCE` retention and legal-hold-enablement adapter; release and disposal remain disabled
- ECS JSON logs, correlation/trace context, Prometheus-format metrics, and separate dependency-aware liveness/readiness probes
- Digest-pinned Java 25 distroless backend and unprivileged Nginx frontend runtime images
- Docker Compose with digest-pinned service images

Node.js is the frontend toolchain; the CareOS business backend is Java/Spring Boot.

## What is runnable

- Backend liveness: `http://localhost:8080/livez`
- Backend readiness (PostgreSQL and Redis): `http://localhost:8080/readyz`
- Aggregate backend health: `http://localhost:8080/actuator/health`
- Public prototype registry API: `http://localhost:8080/api/public/prototype-screens`
- Browser identity API: `http://localhost:8080/api/v1/auth/session`
- Membership-backed organization API: `http://localhost:8080/api/v1/organizations`
- Session-gated frontend: `http://localhost:4173/#/M1-05`
- Password recovery request: `http://localhost:4173/#/forgot-password` (the local reset email appears in Mailpit)
- Invitation acceptance: `http://localhost:4173/#/accept-invitation?token=...` (the one-use local link appears in Mailpit)
- Authenticated MFA self-service: `http://localhost:4173/#/M1-03`
- Module 1: `#/M1-01` through `#/M1-23`
- Module 2: `#/M2-01` through `#/M2-29`
- Clinical prototype: `#/COS-01` through `#/COS-27`
- MinIO console: `http://localhost:9001`
- Mailpit: `http://localhost:8025`

Module 1 routes and the implemented Module 2 routes use checked tenant APIs and server-projected permissions. Module 2 screens validate live projections and send governed actions through the checked browser boundary; their current implementation remains unverified until the final M2H pass. Clinical prototype records remain synthetic: those pages say so explicitly, warn against entering real personal or clinical information, and keep clinical previews and unimplemented workflow actions disabled. Once signed in, the shell's actor and organization labels come from the server session and membership APIs.

## Quick start — recommended

Requirements: Docker Desktop with Compose v2.

### Windows PowerShell

```powershell
Copy-Item .env.example .env
docker compose up --build -d
docker compose ps
```

### macOS/Linux

```bash
cp .env.example .env
docker compose up --build -d
docker compose ps
```

`.env.example` is synthetic local-development material only. The `production` profile deliberately rejects its documented credentials and insecure transport settings; see [docs/PRODUCTION_SECURITY.md](docs/PRODUCTION_SECURITY.md).

Open `http://localhost:4173/#/M1-05` and sign in with the synthetic local bootstrap values copied from `.env.example`. The form intentionally does not prefill credentials.

Stop without deleting data:

```bash
docker compose down
```

The migration/runtime database-role split is created when PostgreSQL initializes a new local volume. The official PostgreSQL 18 image requires the named volume to be mounted at `/var/lib/postgresql`, allowing its major-version-specific cluster directory below that parent; the repository security contract protects this layout. If this repository was previously started before the role split or with an older PostgreSQL volume layout, back up retained data and perform an explicit supported database upgrade. Reset only disposable synthetic data with the command below, and never reset a volume containing data you need to retain.

Private quarantine, malware scanning, clean promotion, signed access, and immutable retention remain disabled by default. Set `CAREOS_STORAGE_S3_ENABLED=true` only to exercise quarantine with the synthetic `.env.example` settings. This activates quarantine storage only; scanning, promotion, signed access, and retention have separate switches.

To exercise both quarantine and scanner mechanics locally, use the optional overlay. It starts the pinned official ClamAV base image, persists downloaded synthetic signature data in a separate volume, keeps port 3310 inside the Compose network, and enables both adapters:

```bash
docker compose -f compose.yaml -f compose.scanner.yaml up --build -d
```

ClamAV may need several minutes and substantial memory for its first signature download/engine load. The backend waits for the daemon health check and then fails startup if the engine, protocol, or signature freshness contract is not satisfied. This overlay is a compatibility environment, not production scanner approval.

Clean-promotion mechanics additionally require `CAREOS_DOCUMENT_PROMOTION_ENABLED=true`, a policy key, an accepted-scanner list, an explicit maximum scan age, and a separate clean bucket. Promotion consumes only the persisted latest `CLEAN` attestation inside an authorized writable tenant transaction, rechecks the policy and digest, copies into the private clean bucket, and commits append-only V10 evidence afterward.

Signed-read mechanics require `CAREOS_DOCUMENT_SIGNED_ACCESS_ENABLED=true`, a policy key, an accepted-purpose list, a maximum URL TTL, and bounded authorization-age/skew settings. The internal coordinator requires committed V10 promotion evidence, the signer fully rehashes the private clean object before creating a bounded read-only URL, and V11 records URL-free append-only grant evidence before the transaction returns it.

Immutable-retention mechanics require `CAREOS_DOCUMENT_RETENTION_ENABLED=true`, an explicit policy key/purpose allow-list/minimum and maximum duration, enabled private S3 storage, `CAREOS_STORAGE_S3_CREATE_BUCKET_IF_MISSING=false`, and a separately pre-provisioned clean bucket with versioning and Object Lock enabled. The coordinator requires committed V10 promotion, the adapter fully rehashes the exact current provider version, applies only Object Lock `COMPLIANCE` retention and optional one-way legal hold, and V12 records append-only evidence without raw storage identifiers. It cannot shorten retention, release a hold, or dispose of content. All checked settings are synthetic: there is no document HTTP route, approved read/retention permission or schedule, governed release/disposal workflow, or production provider acceptance. See [docs/PLATFORM_CAPABILITIES.md](docs/PLATFORM_CAPABILITIES.md).

The Redis job transport is also disabled by default. To exercise its mechanics against the local Redis service, set `CAREOS_JOBS_REDIS_ENABLED=true` and supply an explicit comma-separated `CAREOS_JOBS_REDIS_ALLOWED_JOB_DEFINITIONS` list such as the synthetic `foundation.synthetic@1` entry in `.env.example`. This activates queue storage only: it does not start a worker or scheduler, authorize a job effect, or populate a production job registry.

The durable notification store is disabled by default. Its synthetic mechanics require `CAREOS_NOTIFICATIONS_POSTGRES_ENABLED=true`, an explicit `template@version` allow-list, an active key ID, and one or more `key-id:base64-encoded-32-byte-key` entries. Keep production keys in an approved secret manager rather than `.env` or Compose. Enabling this adapter persists and leases encrypted requests only; it does not resolve consent, store a destination, invoke SMTP/SMS/push, or start a worker.

The backend writes ECS-compatible JSON logs and creates bounded W3C trace context. Prometheus metrics require a fully authenticated CareOS session, and OTLP trace export is disabled by default. Keep `CAREOS_OTLP_TRACING_ENABLED=false` until an approved collector, TLS/authentication, region, retention, access policy, and non-interactive observability identity or private management boundary exist. See [docs/OPERATIONS.md](docs/OPERATIONS.md).

The frontend image serves a strict same-origin CSP and explicit security headers, and the backend declares a no-content API policy. The repository Nginx listener is still plain HTTP for local/container compatibility; production TLS termination, trusted forwarding, secret injection, edge/WAF/rate policy, and provider acceptance remain external requirements. See [docs/PRODUCTION_SECURITY.md](docs/PRODUCTION_SECURITY.md).

Delete local containers and volumes only when intentionally resetting synthetic data:

```bash
docker compose down --volumes
```

## Run without Docker

Start PostgreSQL 18, Redis 8, and an SMTP sink locally. Create `careos_dev` with a migration owner named `careos_migrator`, create a login role named `careos_app` with `NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS`, and grant it database connect access. The database defaults use the synthetic local-only passwords from `.env.example`; use environment variables for different credentials.

Spring does not automatically import `.env`. Before starting outside Compose, explicitly set `SPRING_PROFILES_ACTIVE=local`, `CAREOS_TOKEN_PEPPER`, and `CAREOS_MFA_ENCRYPTION_KEY` in the shell. The latter two intentionally have no runtime fallback; the synthetic development values are documented in `.env.example`. Then:

```bash
cd backend
./mvnw spring-boot:run
```

On Windows PowerShell, use `.\mvnw.cmd spring-boot:run`.

In a second terminal:

```bash
cd frontend
npm ci
npm run dev
```

Frontend development URL: `http://localhost:5173/#/M1-05`.

## Test and build

```bash
cd frontend
npm ci
npm run api:check
npm run architecture:check
npm run format:check
npm run typecheck
npm run lint
npm test
npm run test:e2e
npm run build
```

```bash
cd backend
./mvnw test
./mvnw package
```

On Windows PowerShell, use `.\mvnw.cmd test` and `.\mvnw.cmd package`.

Repository and supply-chain contracts can be run without installing extra Node packages:

```bash
node scripts/verify-prototype-register.mjs
node scripts/verify-api-contract.mjs
node --test scripts/tests/verify-api-contract.test.mjs
node scripts/verify-module-1-inputs.mjs --require-approved
node --test scripts/tests/verify-module-1-inputs.test.mjs
node scripts/verify-module-1-review-drafts.mjs
node --test scripts/tests/verify-module-1-review-drafts.test.mjs
node scripts/verify-module-1-candidate-inputs.mjs
node --test scripts/tests/verify-module-1-candidate-inputs.test.mjs
node scripts/verify-module-1-facility-scope-candidate.mjs
node --test scripts/tests/verify-module-1-facility-scope-candidate.test.mjs
node scripts/verify-module-2-candidate-inputs.mjs
node --test scripts/tests/verify-module-2-candidate-inputs.test.mjs
node scripts/verify-module-2-inputs.mjs --require-approved
node --test scripts/tests/verify-module-2-inputs.test.mjs
node scripts/verify-ci-security.mjs
node --test scripts/tests/verify-ci-security.test.mjs
```

The checked Module 1 and Module 2 production commands verify their eight approved artifacts and distinct approval records; use `--require-approved` for implementation or release work. Review-draft and retained candidate commands remain non-authorizing, as does the additive facility-scope candidate. Any change to an approved artifact changes its checksum and must fail its production gate until a new accountable approval binds the replacement package, full screen scope, and distinct approval evidence.

Backend tests require Docker because Testcontainers creates and removes isolated PostgreSQL 18 `careos_test`, Redis 8, and pinned object-storage instances. UUID tests verify RFC 9562 layout and process-local monotonic behavior, while PostgreSQL catalog tests verify native `uuidv7()` defaults. The suite attacks approved/reference authorization, delegation/final-owner controls, checksum-bound release evidence, identity/MFA workflows, organization and network boundaries, operating-hours/service/assignment/scheme constraints, configuration activation/invalidation, history/audit projection, export snapshot/lifecycle/access, cross-tenant isolation, concurrency, idempotency, evidence, and readiness behavior. Redis queue, PostgreSQL notification, document evidence, Object Lock, consumer inbox, and deterministic ClamD tests cover their respective integrity, isolation, replay, retry, and fail-closed contracts. Tests do not use development infrastructure. Never point automated tests at development or production services.

The last completed full local verification, on 21 September 2026 before the Module 2 runtime implementation, passed 205 backend tests across 28 suites, 72 frontend unit tests, 110 runtime Playwright/Axe cases across five exact viewport profiles, the packaged backend JAR, the frontend production build and static gates, the 79-screen registry, the then-current 100-operation OpenAPI contract, every Module 1 input/candidate/facility-scope verifier, and the CI-security contract. The Module 2 candidate closeout separately passed its ten integrity/negative tests, five exact-viewport Playwright/Axe/overflow cases, frontend formatting, and the updated 13-test CI-security contract. No current V51-V65/109-operation Module 2 runtime verification result is claimed yet.

## Security baseline already represented

- Persisted users and password credentials with a synthetic local-profile bootstrap administrator; the paired organization/facility fixture is retained only in local/test and removed by V19 from base/production state
- JSON login with generic credential failures; HTTP Basic and default form login are disabled
- Redis-backed indexed sessions with an HttpOnly `SameSite=Strict` cookie, idle and absolute expiry, ID rotation, and security-version revocation
- Origin/Referer validation plus double-submit CSRF protection for browser mutations
- One-use password-reset tokens, encrypted TOTP seeds, one-use recovery codes, recent authentication, and Redis-backed throttling
- Hashed authentication metadata in a database-enforced append-only evidence table
- Organization-scoped memberships
- Actor-bound organization discovery and server-side organization selection with live-membership revalidation
- Separate Flyway migration and restricted application database roles
- Transaction-bound membership/permission authorization with organization, actor, purpose, and correlation context
- Migration-owned, runtime-read-only authorization catalogs with an immutable checksum-bound `m1-candidate-1` release; unknown, retired, reference-only, cross-version, or ungranted entries deny access
- PostgreSQL forced RLS on all current tenant-owned foundation tables
- Disposable PostgreSQL 18 migration and cross-tenant attack tests
- RFC 9457 problem responses and a checked OpenAPI 3.1 source containing 109 implemented operations; final Module 2 drift and behavior verification is pending
- Checked protected tenant-route, opaque cursor/filter, strong ETag/If-Match, scoped idempotency, and bounded caller-controlled retry conventions
- Exact TypeScript-only OpenAPI generation with CI drift detection and a credentialed, correlation/CSRF-aware native browser client for the current API surface; final Module 2 regeneration/drift verification is pending
- Memory-only frontend session gating with runtime response validation, server-derived idle/absolute deadline locking, event-driven resume revalidation without background polling, real login/password-recovery/MFA challenge and self-service/organization selection and switching/logout states, accessible fail-closed errors, strict rejection and focused recovery for unregistered protected hashes, exact 1440/1024/768/390/320 route containment and Axe coverage, and a CI-enforced feature dependency direction
- Approved-registry M1-05 through M1-09 organization APIs and states with tenant authorization, the exact ordered 15-gate live projection, exact profile fields, governed identifier metadata, effective registered/postal/service/billing addresses, confidential masked email/phone/web contacts, verification and primary/preferred rules, immutable supersession history, permission-projected actions, runtime response validation, strong revisions, caller-owned idempotency, explicit reasons, raw-value-free atomic audit/outbox evidence, and live profile/primary-identifier/address-contact/network-hierarchy evaluators
- Live M1-15 facility-selected physical/virtual service-location directory and governed draft create/edit/reparent plus separately permission-projected activation/suspension/reactivation/closure with governed current-address/non-closed-unit selectors, conditional inputs, strong revisions, immutable parent history, runtime response validation, caller-owned idempotency, and server-authoritative state replacement
- Live M1-16 through M1-19 atomic operating-hours, service catalogue, effective assignment, and immutable identifier-scheme version flows with timezone-transition checks, database-enforced eligibility/overlap/lifecycle rules, checked response contracts, and readiness integration; scheme activation/retirement occurs only through M1-21 maker-checker configuration activation
- Live M1-21 through M1-23 exact-digest configuration validation/submission/decision/activation, signed-cursor history/audit projections, purpose-bound detail access, request-time export snapshots, independent restricted-export decisions, authorized artifact processing/access/retention mechanics, and bounded browser status backoff
- Approved-registry M1-20 membership read with tenant authorization, hidden denial, literal server filters, HMAC-signed tenant/filter/limit-bound cursor paging, runtime response validation, and current-permission invitation/MFA-reset action projection
- Governed M1-20 organization-wide non-owner role-change and membership-revocation request/approve/execute transitions with strong revision preconditions, scoped idempotency, recent authentication plus MFA, exact reasons, maker/checker/target separation, delegation ceilings, database-bound approval consumption, audit/outbox evidence, and runtime-validated UI states
- Governed M1-20 owner promotion/demotion request/approve/execute transitions with strong revisions, recent authentication plus MFA, maker/checker/target separation, exact approved-maker execution, indefinite-promotion/delegation checks, final-owner protection, `identity.owner.transferred` evidence, and runtime-validated UI states
- Checksum-bound Module 1 input-package verification in `APPROVED` state with eight exact bundles, one all-screen approval record, and a required-approval mode used by implementation gates
- A separate checked eight-part Module 1 owner-review packet that is structurally complete but explicitly non-authorizing
- A retained checksum-identified candidate provenance package plus its byte-identical approved eight-artifact promotion, offline responsive 23-screen review application, eight contract tests, and five viewport/Axe/overflow tests
- Shared RFC 9562 UUIDv7 generation for new application identifiers, process-local monotonic behavior, native PostgreSQL 18 defaults, and compatibility with historical/reference UUIDs
- Optimistic-lock columns
- Migration-owned, fail-closed audit/outbox event-version registries
- Atomic governed-mutation orchestration with database-enforced append-only audit evidence and immutable transactional outbox content
- Actor/tenant/operation-scoped request idempotency with concurrent serialization, response replay, conflict rejection, and expiry
- Leased outbox claim/ack/retry/dead-letter mechanics with stable event IDs for downstream deduplication
- Migration-owned consumer/event-version allow-list plus forced-RLS, append-only inbox receipts keyed by tenant/consumer/source event
- Transactional consumer execution with canonical-payload digesting, exact-redelivery suppression, changed-content conflict, callback rollback/retry, and concurrent serialization
- Explicit fail-closed ports, adapter-completeness checks, and safe status reporting for document security, durable notifications, Redis jobs, workers, and schedulers
- Opt-in private-quarantine storage with tenant-derived keys, exact length/digest verification, mismatch cleanup, verified retries, and anonymous-access denial
- Opt-in ClamD scanning with startup/runtime signature-freshness checks, an engine security floor, bounded `INSTREAM` framing, independent length/digest verification, and fail-closed verdicts
- Transaction-bound PostgreSQL quarantine metadata and scan attestations with forced RLS, context/server-time binding, composite tenant/object linkage, exact-replay handling, and database-enforced append-only evidence
- Disabled-by-default clean promotion with an explicit scanner/freshness policy, distinct private clean bucket, streaming digest verification, exact replay, and forced-RLS append-only policy evidence
- Disabled-by-default signed clean-document access with an explicit purpose/TTL policy, full ETag-bound digest verification, GET-only private URLs, and URL-free forced-RLS append-only grant evidence
- Disabled-by-default immutable document retention with an explicit purpose/duration policy, exact-version full-content verification, S3 Object Lock `COMPLIANCE`, one-way legal-hold enablement, and provider-version-hashed forced-RLS append-only evidence
- Opt-in tenant-derived Redis job queues with allow-listed schema versions, atomic Lua lifecycle transitions, opaque leases, bounded retry/dead-letter retention, integrity checks, and safe metrics
- Opt-in PostgreSQL durable notifications with allow-listed template versions, AES-256-GCM parameters, hashed deduplication/leases, forced RLS, database-checked transitions, bounded retry/dead-letter retention, and safe metrics
- ECS JSON request telemetry with validated correlation IDs, templated routes, bounded OpenTelemetry context, baggage disabled, and every OTLP exporter disabled by default
- Public status-only `/livez` and PostgreSQL/Redis-aware `/readyz` probes; authenticated Prometheus-format metrics with bounded non-sensitive dimensions
- Explicit Spring API headers plus an always-on strict same-origin Nginx CSP/header contract, hidden Nginx version tokens, and bounded same-origin API proxy timeouts
- A production-only startup guard requiring secure cookies, canonical HTTPS browser origins, verified PostgreSQL TLS, authenticated Redis TLS, mandatory authenticated SMTP STARTTLS/identity verification, fresh non-production secrets, safe S3 flags, complete promotion/signed-access/retention policies, and HTTPS when OTLP tracing is enabled
- Full-SHA GitHub Actions, explicit least-privilege/time/concurrency bounds, dependency review, Java/JavaScript CodeQL, Trivy dependency/secret/configuration/image gates, and weekly dependency updates
- CycloneDX SBOM artifacts plus fixed HIGH/CRITICAL image rejection in CI
- Digest-pinned Dockerfile, Compose, scanner, and PostgreSQL/Redis test images; final application stages declare non-root users
- PostgreSQL 18 parent-volume layout plus fresh six-service Flyway V18 deployment evidence and a separately verified Flyway V19 environment-scoped seed boundary
- Pinned object-storage service in the local topology for synthetic compatibility only
- Synthetic credentials only

The `local` profile retains the synthetic organization/facility fixture and seeds a synthetic account and membership into the persisted identity model; the test profile retains the same isolated tenant fixture. Base and production configuration hard-disable that Flyway placeholder, so V19 removes an untouched legacy fixture and refuses to proceed if it was changed or acquired referenced tenant data.

The frontend calls the checked client for session/identity flows and the full organization-wide M1 administration surface: readiness, organization records, facility/hierarchy/location lifecycle, atomic hours, services, assignments, identifier drafts/versions, membership/owner workflows, exact-digest configuration activation, history/audit evidence, and purpose-bound exports. M1-16 through M1-23 success responses are additionally checked at runtime against strict structural contracts. The browser consumes the server's effective session deadline, locks without polling when it passes, performs checked revalidation only when an unexpired view returns to use, and polls only active export jobs with bounded backoff and a terminal stop. Sensitive reset/invitation tokens, raw contacts, export filters, and signed download URLs are not durably stored in browser state.

V14-V18 retain the original reference mechanics; V20 records the approved M1 package; V21-V50 implement the governed organization-wide M1 boundary; and V51-V65 establish the approved M2 authorization release, exact workforce persistence, event registries, direct-database/lifecycle controls, invalidation coverage, leases, export access, subscriptions, and internal-consumer authorization. Canonical registries remain migration-owned and runtime-read-only, and production activation requires the exact approved digest. Facility-scoped M1 membership grants remain unavailable pending separate approval; scheduled worker deployment/credentials, target provider controls, hosted operational evidence, final M2 verification, and owner/module acceptance remain open.

Platform capabilities are explicitly unavailable by default until tested adapters are intentionally configured; quarantine is not clean content, a raw scanner return must pass through V8 evidence and the Phase 0T coordinator/V10 policy-evidence boundary before a private clean copy, Phase 0U then requires committed promotion plus URL-free V11 grant evidence before its internal coordinator returns an opt-in signed read, and Phase 0V applies only monotonic COMPLIANCE retention/hold enablement with V12 evidence. A queued job is not authority to execute its effect, and a persisted notification is not permission or ability to contact its recipient. V9 supplies only the transaction-bound inbox/deduplication mechanic; no production subscription, authenticated transport, broker acknowledgement, or consumer is active. Production tenant provisioning, service credential provisioning/rotation and worker wiring, storage/scanner/Redis/key-management/Object-Lock acceptance, governed document hold-release/disposal workflows, consent/destination/provider delivery, and real producer/consumer operations remain incomplete.

## Repository map

```text
backend/                 Spring Boot modular-monolith foundation
frontend/                React/TypeScript session boundary and 79 route states
candidate-inputs/        Retained M1/M2 provenance plus the non-authorizing M1 facility-scope candidate
approved-inputs/         Checksum-bound approved Module 1 and Module 2 artifacts and approval evidence
contracts/               Checked API/input contracts plus M1 and M2 input manifests
docs/                    architecture, operations, screen register, module plans, review drafts and delivery status
scripts/                 verification helpers
compose.yaml             local PostgreSQL, Redis, MinIO, Mailpit and apps
compose.scanner.yaml     optional pinned ClamAV/quarantine compatibility overlay
.github/                 hardened quality/security workflows and dependency updates
```

Read the included [complete build specification](docs/CareOS_Complete_Build_Specification_Java_Spring_Boot_React_Node_Edition.pdf), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/API_CONVENTIONS.md](docs/API_CONVENTIONS.md), [docs/FRONTEND_SESSION.md](docs/FRONTEND_SESSION.md), [docs/GOVERNANCE_EVIDENCE.md](docs/GOVERNANCE_EVIDENCE.md), [docs/OPERATIONS.md](docs/OPERATIONS.md), [docs/PLATFORM_CAPABILITIES.md](docs/PLATFORM_CAPABILITIES.md), [docs/PROTOTYPE_REGISTER.md](docs/PROTOTYPE_REGISTER.md), [docs/IMPLEMENTATION_ROADMAP.md](docs/IMPLEMENTATION_ROADMAP.md) and [GIT_POSITION.md](GIT_POSITION.md) before implementation.
