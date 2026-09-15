# ROOTOPATHY CareOS — Java + React/Node Edition

This repository is the verified **from-scratch engineering foundation** for CareOS. It combines a Java Spring Boot modular monolith, a session-aware React/TypeScript frontend built with Node, a checked OpenAPI/generated-client boundary, PostgreSQL, Redis, opt-in private-quarantine, malware-scanner, durable-job, and encrypted durable-notification adapters, Mailpit and 79 CareOS route states.

> This is not a claim that all clinical production modules are complete. The runnable foundation and prototypes are complete; production workflows must be implemented module-by-module against the accompanying build specification.

## Runtime stack

- Java 25 LTS
- Spring Boot 4.1.x
- Maven 3.9
- React 19 + strict TypeScript
- Node.js 24 LTS + Vite
- PostgreSQL 18 + Flyway
- Redis 8; one official multi-architecture Alpine digest is pinned across Compose and tests
- Opt-in tenant-isolated Redis durable-job transport; worker execution remains disabled
- Opt-in tenant-isolated PostgreSQL encrypted notification store; recipient routing and outbound delivery remain disabled
- S3-compatible private quarantine adapter; pinned MinIO only as a synthetic local compatibility target
- Opt-in ClamAV 1.5.3 scanner adapter; pinned official image only as a synthetic local compatibility target
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
- Module 1: `#/M1-01` through `#/M1-23`
- Module 2: `#/M2-01` through `#/M2-29`
- Clinical prototype: `#/COS-01` through `#/COS-27`
- MinIO console: `http://localhost:9001`
- Mailpit: `http://localhost:8025`

Protected prototype records remain synthetic. Once signed in, the shell's actor and organization labels come from the server session and membership APIs.

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

The migration/runtime database-role split is created when PostgreSQL initializes a new local volume. If this repository was previously started before that split was introduced, either provision the two roles manually or intentionally reset the synthetic local volume with the command below. Never reset a volume containing data you need to retain.

Private quarantine and malware scanning remain disabled by default. Set `CAREOS_STORAGE_S3_ENABLED=true` only to exercise quarantine with the synthetic `.env.example` settings. This activates storage only; scanning, promotion, signed access, and retention remain unavailable.

To exercise both quarantine and scanner mechanics locally, use the optional overlay. It starts the pinned official ClamAV base image, persists downloaded synthetic signature data in a separate volume, keeps port 3310 inside the Compose network, and enables both adapters:

```bash
docker compose -f compose.yaml -f compose.scanner.yaml up --build -d
```

ClamAV may need several minutes and substantial memory for its first signature download/engine load. The backend waits for the daemon health check and then fails startup if the engine, protocol, or signature freshness contract is not satisfied. This overlay is a compatibility environment, not production scanner approval.

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
node scripts/verify-ci-security.mjs
node --test scripts/tests/verify-ci-security.test.mjs
```

Backend tests require Docker because Testcontainers creates and removes isolated PostgreSQL 18 `careos_test`, Redis 8, and pinned object-storage instances. Redis queue tests exercise atomic scripts, retry/dead-letter state, lease recovery, application restart, and a real paused-dependency timeout/recovery. PostgreSQL notification tests exercise encrypted storage, tenant isolation, concurrent deduplication, lease/retry/dead-letter transitions, ciphertext corruption, and key rotation. Scanner protocol tests use an in-process deterministic ClamD server rather than downloading live definitions. Tests do not use development infrastructure. Never point automated tests at development or production services.

## Security baseline already represented

- Persisted users and password credentials with a synthetic local-profile bootstrap administrator
- JSON login with generic credential failures; HTTP Basic and default form login are disabled
- Redis-backed indexed sessions with an HttpOnly `SameSite=Strict` cookie, idle and absolute expiry, ID rotation, and security-version revocation
- Origin/Referer validation plus double-submit CSRF protection for browser mutations
- One-use password-reset tokens, encrypted TOTP seeds, one-use recovery codes, recent authentication, and Redis-backed throttling
- Hashed authentication metadata in a database-enforced append-only evidence table
- Organization-scoped memberships
- Actor-bound organization discovery and server-side organization selection with live-membership revalidation
- Separate Flyway migration and restricted application database roles
- Transaction-bound membership/permission authorization with organization, actor, purpose, and correlation context
- Migration-owned, runtime-read-only authorization catalogs that deny every unapproved role/permission mapping
- PostgreSQL forced RLS on all current tenant-owned foundation tables
- Disposable PostgreSQL 18 migration and cross-tenant attack tests
- RFC 9457 problem responses and a checked OpenAPI 3.1 contract for all 15 implemented operations
- Checked protected tenant-route, opaque cursor/filter, strong ETag/If-Match, scoped idempotency, and bounded caller-controlled retry conventions
- Exact TypeScript-only OpenAPI generation with CI drift detection and a credentialed, correlation/CSRF-aware native browser client for all 15 current operations
- Memory-only frontend session gating with runtime response validation, real login/pending-MFA/organization selection and switching/logout states, accessible fail-closed errors, and a CI-enforced feature dependency direction
- UUID identifiers
- Optimistic-lock columns
- Migration-owned, fail-closed audit/outbox event-version registries
- Atomic governed-mutation orchestration with database-enforced append-only audit evidence and immutable transactional outbox content
- Actor/tenant/operation-scoped request idempotency with concurrent serialization, response replay, conflict rejection, and expiry
- Leased outbox claim/ack/retry/dead-letter mechanics with stable event IDs for downstream deduplication
- Explicit fail-closed ports, adapter-completeness checks, and safe status reporting for document security, durable notifications, Redis jobs, workers, and schedulers
- Opt-in private-quarantine storage with tenant-derived keys, exact length/digest verification, mismatch cleanup, verified retries, and anonymous-access denial
- Opt-in ClamD scanning with startup/runtime signature-freshness checks, an engine security floor, bounded `INSTREAM` framing, independent length/digest verification, and fail-closed verdicts
- Opt-in tenant-derived Redis job queues with allow-listed schema versions, atomic Lua lifecycle transitions, opaque leases, bounded retry/dead-letter retention, integrity checks, and safe metrics
- Opt-in PostgreSQL durable notifications with allow-listed template versions, AES-256-GCM parameters, hashed deduplication/leases, forced RLS, database-checked transitions, bounded retry/dead-letter retention, and safe metrics
- ECS JSON request telemetry with validated correlation IDs, templated routes, bounded OpenTelemetry context, baggage disabled, and every OTLP exporter disabled by default
- Public status-only `/livez` and PostgreSQL/Redis-aware `/readyz` probes; authenticated Prometheus-format metrics with bounded non-sensitive dimensions
- Explicit Spring API headers plus an always-on strict same-origin Nginx CSP/header contract, hidden Nginx version tokens, and bounded same-origin API proxy timeouts
- A production-only startup guard requiring secure cookies, canonical HTTPS browser origins, verified PostgreSQL TLS, authenticated Redis TLS, mandatory authenticated SMTP STARTTLS/identity verification, fresh non-production secrets, safe S3 flags, and HTTPS when OTLP tracing is enabled
- Full-SHA GitHub Actions, explicit least-privilege/time/concurrency bounds, dependency review, Java/JavaScript CodeQL, Trivy dependency/secret/configuration/image gates, and weekly dependency updates
- CycloneDX SBOM artifacts plus fixed HIGH/CRITICAL image rejection in CI
- Digest-pinned Dockerfile, Compose, scanner, and PostgreSQL/Redis test images; final application stages declare non-root users
- Pinned object-storage service in the local topology for synthetic compatibility only
- Synthetic credentials only

The `local` profile seeds a synthetic account and membership into the persisted identity model. The frontend calls the checked client for session bootstrap, login, pending MFA, organization selection/switching, and logout, but organization selection is only a server-side navigation preference and never authorization evidence. Authorization and event registries are fail-closed until owner-approved content is supplied. There is still no protected tenant business API or production business-record UI/cache. Platform capabilities are explicitly unavailable by default until tested adapters are intentionally configured; quarantine is not clean content, an in-memory scanner result is not durable promotion evidence, a queued job is not authority to execute its effect, and a persisted notification is not permission or ability to contact its recipient. Governed invitation issuance/acceptance and account linking, identity-administration UI, approved scoped RBAC/event/job/template policy, maker-checker rules, a non-interactive service-account path, production storage/scanner/Redis/key-management acceptance, remaining document adapters, consent/destination/provider wiring, and consumer deduplication still need to be completed before production.

## Repository map

```text
backend/                 Spring Boot modular-monolith foundation
frontend/                React/TypeScript session boundary and 79 route states
contracts/               Checked API contract and shared HTTP conventions
docs/                    architecture, operations, screen register and delivery status
scripts/                 verification helpers
compose.yaml             local PostgreSQL, Redis, MinIO, Mailpit and apps
compose.scanner.yaml     optional pinned ClamAV/quarantine compatibility overlay
.github/                 hardened quality/security workflows and dependency updates
```

Read the included [complete build specification](docs/CareOS_Complete_Build_Specification_Java_Spring_Boot_React_Node_Edition.pdf), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/API_CONVENTIONS.md](docs/API_CONVENTIONS.md), [docs/FRONTEND_SESSION.md](docs/FRONTEND_SESSION.md), [docs/GOVERNANCE_EVIDENCE.md](docs/GOVERNANCE_EVIDENCE.md), [docs/OPERATIONS.md](docs/OPERATIONS.md), [docs/PLATFORM_CAPABILITIES.md](docs/PLATFORM_CAPABILITIES.md), [docs/PROTOTYPE_REGISTER.md](docs/PROTOTYPE_REGISTER.md), [docs/IMPLEMENTATION_ROADMAP.md](docs/IMPLEMENTATION_ROADMAP.md) and [GIT_POSITION.md](GIT_POSITION.md) before implementation.
