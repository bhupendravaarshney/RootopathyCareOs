# CareOS consolidated QA report

**Evidence date:** 29 September 2026

**Baseline commit:** `be6cfa8e4f947a2e42ed1cba29d29ad1f65f4dc5`

**Attempted stabilization commit:** `97ea69d9e2fd43d2fd887f7ad5410ba6e103ff60` (`main`/`origin/main`)

**Evidence subject:** uncommitted corrective working tree based on the attempted stabilization commit

**Schema/API boundary:** Flyway V115, OpenAPI 3.1.0, 195 screens, 135 operations

**Repository release gate: FAIL** — the hosted quality and security workflows failed for `97ea69d9…`; the corrective tree is locally verified but has no final commit or hosted evidence for its exact bytes.

**Production acceptance: NOT GRANTED.**

## Local consolidated QA

The local evidence is authoritative for the tested working tree, but it is not commit-bound hosted evidence. `build/qa-evidence.json` records the baseline SHA together with `source.workingTree: DIRTY`, `source.commitBound: false`, and `repositoryReleaseGate: NOT_EVALUATED`.

| Gate | Result | Executable evidence |
| --- | --- | --- |
| Backend clean verification | PASS | 279/279 tests, zero failures/errors/skips; 257 core tests plus 22 required compatibility tests. PostgreSQL 18 applies V1-V115 from empty state, architecture rules pass, and the JAR packages. |
| PostgreSQL authorization and RLS | PASS | Tenant/RLS attacks, active production-role behavior, missing/false local reference flag denial, capability-less GUC manipulation denial, cross-tenant denial, unknown-permission denial, and production reference-policy rejection pass. |
| Object storage and scanner compatibility | PASS | 15 S3/Object Lock tests and 7 ClamAV tests pass without skips. The corrected reproducible S3 fixture preflight pins its builder/frontend, rewrites layer timestamps, and emits a portable Docker V2 manifest; it is rejected unless its manifest is exactly `sha256:9b225075e9847bde86fa19f8274353ae7c5c4018af813ebb5bb9ce12d329b744`. Hosted verification of this follow-up correction remains required. |
| Frontend static/unit/build | PASS | `npm ci`, generated API drift, architecture, strict typecheck, lint, formatting, 144/144 Vitest tests, and the production build pass. |
| Browser/accessibility/responsive | PASS | 185/185 Playwright cases pass at 1440, 1024, 768, 390, and 320 pixels with Axe plus document/body overflow assertions. The M1-05 administration header and readiness rows remain contained at exactly 768 pixels. |
| Repository/API/security contracts | PASS | 140/140 contract and negative tests pass; the registry remains exactly 195 screens and OpenAPI remains exactly 135 operations. Both Compose models validate. |
| Authenticated live product audit | PASS | Login, organization selection, and all 193 protected business routes pass. The audit records zero unexpected API failures, console errors, exposed internal screen codes, or contentless routes; four HTTP 428 responses remain expected high-assurance boundaries. |
| Authorization-reason privacy | PASS | Tests prove a sentinel reason is absent from application/exception logs, request telemetry, OpenTelemetry attributes, Prometheus output, generic responses, Nginx access-log format, and frontend analytics/logging paths. |
| Dependency and runtime-image security | PASS | Exact Trivy 0.74.0 filesystem plus rebuilt backend/frontend image scans report zero fixed HIGH/CRITICAL vulnerabilities after Jackson 2.21.6/3.1.6 patch floors; both runtime OS layers and the backend application JAR are clean. |
| Test shutdown signal | PASS | The final backend run contains no PostgreSQL connection-refused, Lettuce `ConnectionWatchdog`, or Spring Session cleanup retry noise after dependencies are torn down. |

The local Node executable was 24.13.0, below the repository's declared 24.15.0 floor, although every individually required frontend command passed. Hosted jobs use `frontend/.nvmrc`; that hosted result is still required.

## Hosted GitHub quality

| Revision | State | Evidence |
| --- | --- | --- |
| Baseline `be6cfa8…` | FAIL | Frontend M1-14 async race; backend could not fetch the removed Quay MinIO digest; contracts passed; browser was skipped because it depended on frontend. |
| Attempt `97ea69d9…` | FAIL | Independent frontend, backend-core, contracts, and authenticated product-smoke passed. Compatibility failed before Maven because the first source-built fixture produced `sha256:d007…` on the hosted runner instead of expected `sha256:bb6f…`. Browser ran independently and reported two assertions for the same M1-05 header badge extending to 780px at the 768px viewport. |
| Corrective working tree | NOT RUN | No final corrective commit or hosted run exists. The fixture now pins BuildKit/frontend inputs and rewrites layer timestamps; the administration header has a component-specific tablet layout. |

A frontend failure can no longer suppress browser evidence. The compatibility lane builds and digest-verifies the repository-owned S3 fixture before Maven starts, so fixture failure is early and explicit. The quality artifact is named `qa-evidence-<commit>` and the live audit artifact is named `authenticated-product-audit-<commit>`.

## Hosted GitHub security

| Revision | State | Evidence |
| --- | --- | --- |
| Baseline `be6cfa8…` | PASS | The existing hosted `CareOS security` workflow passed for the baseline commit. |
| Attempt `97ea69d9…` | FAIL | Java and JavaScript CodeQL passed. Trivy source and backend-image scans each reported the two fixed HIGH findings for CVE-2026-68497 in Jackson 2.21.5 and 3.1.5; the later frontend-image scan was skipped after the backend-image failure. |
| Corrective working tree | NOT RUN | Local CI-security contracts and exact Trivy filesystem plus rebuilt backend/frontend image scans pass with Jackson 2.21.6 and 3.1.6. Hosted CodeQL, image scans, and CycloneDX evidence remain required for the final corrective commit. |

No hosted security result from the baseline is carried forward to changed bytes.

## Target-environment acceptance

**NOT EVALUATED.** No evidence in this pass covers managed production PostgreSQL/Redis/storage, deployment identities, secret management, TLS/edge controls, provider credentials, backups/restores, monitoring/on-call, load testing, penetration testing, or target tenant-isolation smoke tests.

## Production acceptance

**NOT GRANTED.** Protected COS reconciliation, facility-scoped authorization approval, dedicated clinical/care-plan/follow-up UX, production workers, real notification/payment/calendar/lab/FHIR providers, accountable clinical/privacy/legal/finance/operations approval, and the target-environment controls above remain open and fail closed where applicable.

## Test classification

- Core required gate: architecture, domain behavior, PostgreSQL tenancy/RLS, authorization, governance, identity, migrations, API contracts, frontend unit/build, and browser accessibility/responsiveness.
- Compatibility/infrastructure required gate: S3-compatible provider mechanics, Object Lock, ClamAV, and provider-specific behavior.

Both classes are required for release. Classification changes diagnosis only; it does not permit a skip on `main`.

## Evidence interpretation

Local quality is green. Repository release remains failed until the exact final commit passes both hosted quality and hosted security and retains its commit-bound artifacts. Production acceptance remains a separate accountable decision and cannot be inferred from repository test success.
