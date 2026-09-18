# ROOTOPATHY CareOS — Java + React/Node Edition

This repository is the verified **from-scratch engineering foundation** for CareOS. It combines a Java Spring Boot modular monolith, a session-aware React/TypeScript frontend built with Node, a checked OpenAPI/generated-client boundary, PostgreSQL, Redis, opt-in private-quarantine, malware-scanner, clean-promotion, signed-access, immutable-retention, durable-job, and encrypted durable-notification adapters, Mailpit and 79 CareOS route states.

> Phase 0 repository mechanics are complete. Flyway V20 carries the checksum-bound approved Module 1 authorization release, V21 activates the exact approved M1-20 membership-read operation, V22 enforces governed organization-wide non-owner role changes and membership revocation, V23 adds final-owner-safe owner promotion/demotion, V24 enforces approved mandatory-role MFA enrollment/use plus governed factor removal, and V25 implements the exact approved M1-07 organization-profile fields and persistence boundary. This is not target-environment production acceptance or a claim that clinical modules are complete; infrastructure controls, operational evidence, and product workflows still must be delivered module-by-module against the accompanying specification.
>
> `m1-candidate-1` was approved unchanged by **bhupendra, developer** on 16 September 2026. The exact eight-artifact package, M1-01 through M1-23 scope, and separate approval evidence are recorded as `M1-APPROVAL-20260916-01`. Five bounded M1B runtime increments cover approved registry promotion, M1-20 read/change/owner-transfer, and mandatory-role MFA. M1C now has two bounded increments: the exact 15-gate live-readiness projection and the exact M1-07 profile fields, validation, persistence, UI, evidence, editability, and profile-complete evaluator. M1B, M1C, and Module 1 as a whole remain incomplete.
>
> The Module 1 input boundary is machine-verifiable: `contracts/module-1-input-gate.json` reports `APPROVED`, eight of eight required bundles, package digest `19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946`, and `implementationAuthorized: true`. Both normal and `--require-approved` verification pass.
>
> A separate eight-part owner-review packet is available under `docs/module-1-review-drafts/`. It is source-grounded and machine-checked, but every file is `DRAFT_NOT_APPROVED`; it gives decision owners a starting point and never authorizes implementation.
>
> The original proposal remains under `candidate-inputs/module-1/` as non-authorizing provenance. Its exact accepted bytes were promoted to `approved-inputs/module-1/`; authority comes only from the production manifest and approval record, never from the candidate verifier.
>
> The approved base package did not define exact facility-scope storage or enforcement. The additive `m1-facility-scope-candidate-1` contract now supplies an approval-ready proposal at digest `76a3f7a2c63cef0cab02b8a1d38a66220eee0abb99c9be0a82b64eaa8941fddb`. It remains `CANDIDATE_FOR_APPROVAL`, reports `implementationAuthorized: false`, and does not change runtime behavior until separately approved.

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

Protected prototype records remain synthetic. Generic pages say so explicitly, warn against entering real personal or clinical information, keep form/clinical previews read-only, and keep unimplemented workflow actions disabled; only local filtering and valid prototype navigation are interactive. Once signed in, the shell's actor and organization labels come from the server session and membership APIs.

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
node scripts/verify-ci-security.mjs
node --test scripts/tests/verify-ci-security.test.mjs
```

The checked Module 1 production command verifies all eight approved artifacts plus approval record `M1-APPROVAL-20260916-01`; add `--require-approved` when authorizing implementation or release work. The review-draft, retained input-candidate, and additive facility-scope candidate commands remain non-authorizing and report `implementationAuthorized: false`. Any change to an approved artifact changes its checksum and must fail the production gate until a new accountable approval binds the replacement package, all 23 screen IDs, and distinct approval evidence. Facility-scope implementation separately requires approval bound to its exact additive candidate digest.

Backend tests require Docker because Testcontainers creates and removes isolated PostgreSQL 18 `careos_test`, Redis 8, and pinned object-storage instances. UUID tests verify RFC 9562 layout and process-local monotonic behavior, while PostgreSQL catalog tests verify all 17 native `uuidv7()` defaults. The suite attacks active approved and retained reference authorization, delegation/final-owner controls, immutable checksum-bound release evidence, governed invitation/linkage, non-owner membership-change and owner-transfer transitions, separate recent-MFA enforcement, mandatory-role enrollment and stale-session invalidation, database-protected factor removal, service-identity separation, the complete MFA maker-checker lifecycle, and exact organization-profile validation/readiness/authorization/trigger/concurrency/idempotency/evidence boundaries. Redis queue, PostgreSQL notification, document evidence, Object Lock, consumer inbox, and deterministic ClamD tests cover their respective integrity, isolation, replay, retry, and fail-closed contracts. Tests do not use development infrastructure. Never point automated tests at development or production services.

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
- RFC 9457 problem responses and a checked OpenAPI 3.1 contract for all 31 implemented operations
- Checked protected tenant-route, opaque cursor/filter, strong ETag/If-Match, scoped idempotency, and bounded caller-controlled retry conventions
- Exact TypeScript-only OpenAPI generation with CI drift detection and a credentialed, correlation/CSRF-aware native browser client for all 31 current operations
- Memory-only frontend session gating with runtime response validation, server-derived idle/absolute deadline locking, event-driven resume revalidation without background polling, real login/password-recovery/MFA challenge and self-service/organization selection and switching/logout states, accessible fail-closed errors, strict rejection and focused recovery for unregistered protected hashes, exact 1440/1024/768/390/320 route containment and Axe coverage, and a CI-enforced feature dependency direction
- Approved-registry organization readiness/profile APIs and M1-05 through M1-07 states with tenant authorization, the exact ordered 15-gate live projection, exact legal/display/trading identity, approved organization type, ISO country, IANA timezone, BCP 47 locale, lifecycle/readiness evaluation, permission-projected editability, runtime response validation, strong ETag/If-Match, caller-owned idempotency, explicit reason, and atomic audit/outbox evidence
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

The frontend calls the checked client for session bootstrap, login, generic password recovery, governed invitation issue/revocation/acceptance and account linking, pending MFA, mandatory-role MFA enrollment, recent-authenticated MFA enrollment/recovery-code replacement, organization-scoped maker-checker administrative MFA reset, organization selection/switching, logout, exact-catalogue organization readiness, exact approved profile read/update/read-only projection, M1-20 membership read, governed non-owner membership-change request/approval/execution, and final-owner-safe owner-transfer request/approval/execution. It consumes the server's effective session deadline, locks without polling when it passes, and performs checked revalidation only when an unexpired browser view returns to use. Mandatory enrollment keeps organization loading and workspace content locked through one-time recovery-code acknowledgement. Reset and invitation tokens are removed from browser history after capture, and credential/MFA material is held only in the active React view. Organization selection is only a server-side navigation preference and never authorization evidence.

V14-V18 retain the original reference mechanics; V20 records the approved package and activates the approved Module 1 interactive roles, grants, delegations, implemented organization-core operations, invitation events, and MFA reset workflow; V21 activates exact `access.membership.read`; V22 adds governed non-owner role-change/revocation; V23 adds governed owner promotion/demotion; V24 enforces mandatory-role MFA and governed factor removal; V25 completes the approved M1-07 profile shape and live profile evaluator. Invitations, MFA administration, and membership administration require separately recorded recent MFA assertions and can be enabled in production only with the exact approved registry/package digest; reference service identities remain production-disabled. Canonical registries remain migration-owned and runtime-read-only. M1-05/M1-06 use the exact approved readiness catalogue as a live projection, and M1-07/M1-20 implement approved bounded slices, but M1B and later Module 1 slices are not complete: facility-scoped membership grants, M1-08 through M1-11 organization-core records/evaluators, persisted configuration validation/activation, and final slice acceptance remain unavailable, and the other 71 protected route entries still use synthetic local content under an explicit no-real-data boundary, keep generic form/clinical previews read-only, disable unimplemented workflow actions, and expose only honest local filters and valid prototype pagination.

Platform capabilities are explicitly unavailable by default until tested adapters are intentionally configured; quarantine is not clean content, a raw scanner return must pass through V8 evidence and the Phase 0T coordinator/V10 policy-evidence boundary before a private clean copy, Phase 0U then requires committed promotion plus URL-free V11 grant evidence before its internal coordinator returns an opt-in signed read, and Phase 0V applies only monotonic COMPLIANCE retention/hold enablement with V12 evidence. A queued job is not authority to execute its effect, and a persisted notification is not permission or ability to contact its recipient. V9 supplies only the transaction-bound inbox/deduplication mechanic; no worker, subscription, authenticated transport, broker acknowledgement, or production consumer is active. Approved business permissions/events must still be bound to implemented operations slice by slice; production tenant provisioning, service credential provisioning/rotation and worker wiring, production storage/scanner/Redis/key-management/Object-Lock acceptance, governed document state/read/hold-release/disposal workflows, consent/destination/provider, and real producer/consumer wiring remain incomplete.

## Repository map

```text
backend/                 Spring Boot modular-monolith foundation
frontend/                React/TypeScript session boundary and 79 route states
candidate-inputs/        Retained Module 1 provenance plus the non-authorizing facility-scope candidate
approved-inputs/         Checksum-bound approved Module 1 artifacts and approval evidence
contracts/               Checked API/input contracts plus M1 production/draft/candidate manifests
docs/                    architecture, operations, screen register, M1 review drafts and delivery status
scripts/                 verification helpers
compose.yaml             local PostgreSQL, Redis, MinIO, Mailpit and apps
compose.scanner.yaml     optional pinned ClamAV/quarantine compatibility overlay
.github/                 hardened quality/security workflows and dependency updates
```

Read the included [complete build specification](docs/CareOS_Complete_Build_Specification_Java_Spring_Boot_React_Node_Edition.pdf), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/API_CONVENTIONS.md](docs/API_CONVENTIONS.md), [docs/FRONTEND_SESSION.md](docs/FRONTEND_SESSION.md), [docs/GOVERNANCE_EVIDENCE.md](docs/GOVERNANCE_EVIDENCE.md), [docs/OPERATIONS.md](docs/OPERATIONS.md), [docs/PLATFORM_CAPABILITIES.md](docs/PLATFORM_CAPABILITIES.md), [docs/PROTOTYPE_REGISTER.md](docs/PROTOTYPE_REGISTER.md), [docs/IMPLEMENTATION_ROADMAP.md](docs/IMPLEMENTATION_ROADMAP.md) and [GIT_POSITION.md](GIT_POSITION.md) before implementation.
