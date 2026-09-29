# CareOS repository stabilization report

## 1. Baseline commit reviewed

`be6cfa8e4f947a2e42ed1cba29d29ad1f65f4dc5` (`main`, also matching `origin/main` when inspected on 29 September 2026).

Baseline hosted state:

- `CareOS security`: PASS.
- `CareOS quality`: FAIL.
- Frontend failed at the M1-14 hierarchy assertion; backend failed after 260 tests while fetching the removed Quay MinIO digest; contracts passed; browser was skipped because it depended on frontend.
- Pulling `quay.io/minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e` independently returned HTTP 401. A stale local image had masked this reproducibility failure.

## 2. Final commit

**NOT CREATED.** The result is an uncommitted working tree based on the baseline. No commit or push was authorized, so there is no final SHA and no hosted result for these exact bytes.

## 3–5. Problems, root causes, and fixes

| Problem | Root cause | Exact fix |
| --- | --- | --- |
| Hosted M1-14 unit failure | The test asserted hierarchy rows immediately after the page heading, before the asynchronous organization-unit projection resolved. | Awaited all `Clinical Services` rows with `findAllByText`; retained Cardiology, depth/revision, create/edit/reparent/activate, ETag and idempotency assertions. |
| S3 integration was not reproducible | The legacy digest could no longer be fetched; local cache made the failure appear environment-specific. | Added a repository-owned MinIO source fixture with pinned builder digest, source commit/archive checksum, fixed platform/source epoch and expected manifest `sha256:bb6f358423eec8c666f70d24dbab12a0b9467b5071f2bb30ee64767d3dce82d1`. CI builds, verifies and starts it before Maven; the Java test requires matching metadata and never pulls. |
| Browser evidence was hidden | `browser.needs = frontend`. | Made frontend, browser, backend-core, compatibility, contracts and product-smoke independent; added an `always()` aggregate that requires every lane. |
| 768px readiness overflow | A content-sized final grid column combined with nowrap/fit-content badges, while the shell breakpoint remained 760px. | Added readiness-specific 761–900px containment using `minmax(0, 1fr)`, a collapsed badge/action row, bounded widths and safe wrapping. The 768 test asserts layout plus document/body containment and Axe. |
| V114 GUC could be mistaken for authority | Any connected PostgreSQL role can attempt to set a custom GUC. | V115 requires direct `session_user` membership in a deployment-owned non-login capability role with `ADMIN`, `INHERIT` and `SET` all false. Local/test bootstrap opts in explicitly; production config still rejects the reference policy. |
| Authorization reason could leak | The useful purpose header entered the servlet request before generic telemetry and logging boundaries. | Added an earliest-order capture/normalize/bounds filter, hid the raw header from downstream access/enumeration, retained only the governed normalized value, and added log/trace/metric/proxy/browser leak tests. |
| Live audit was advisory | It was only a manual command. | Added a required isolated Compose `product-smoke` job with readiness/frontend checks, authenticated audit artifacts/traces/screenshots and unconditional teardown. |
| Compatibility failures obscured core health | S3/ClamAV provider behavior ran inside one backend result. | Tagged S3 and ClamAV as `compatibility`; CI now runs 257 core and 22 compatibility tests in separate required lanes. Full local Maven verification still runs all 279. |
| Integration shutdown logs obscured failures | Spring contexts/pools survived dependency containers, and scheduled session cleanup retried during teardown. | Closed compatible Spring contexts after each integration class and disabled only scheduled test-session cleanup. Isolation remains per class/state; final logs contain no connection-refused/reconnect cleanup noise. |
| QA prose was not commit-bound | Markdown could retain PASS while hosted quality failed. | Added machine-readable QA evidence with SHA, dirty/clean state, schema/API/screens/operations/counts, quality/security/product states and explicit release/production fields. Hosted artifacts are SHA-named; dirty local evidence cannot pass the repository release gate. |

## 6. Files changed

- CI/supply chain: `.github/workflows/quality.yml`, `.github/dependabot.yml`, `.gitignore`, `compose.yaml`, `test-fixtures/s3/*`.
- QA tooling: `scripts/build-s3-test-fixture.mjs`, `scripts/generate-qa-evidence.mjs`, both whole-project runners, CI-security verifier, and their tests.
- Authorization/security: V115, PostgreSQL bootstrap/test roles, `PostgresTenantAuthorizationOperations`, `AuthorizationReasonFilter`, `WorkforceController`, CORS configuration, and security/API/tenant attack tests.
- Test lifecycle: `application-test.yml` and the 16 Spring integration classes marked for post-class context closure.
- Frontend/browser/audit: `App.test.tsx`, `AdministrationScreens.tsx`, `styles.css`, `prototypes.spec.ts`, and `live-product-audit.mjs`.
- Evidence/docs: `README.md`, `IMPLEMENTATION_GAPS.md`, `docs/BUILD_STATUS.md`, `docs/CONSOLIDATED_QA_REPORT.md`, `docs/IMPLEMENTATION_ROADMAP.md`, `docs/PLATFORM_CAPABILITIES.md`, this report, and `docs/LOCAL_REFERENCE_AUTHORIZATION_THREAT_MODEL.md`.

## 7. Migration impact

V114 is unchanged. New forward-only V115 replaces the local-bootstrap permission helper with the two-factor database boundary and adds the capability-check function. No business table or production role grant is added. Fresh local/demo environments provision the capability through the explicit bootstrap marker, while production must omit it. A pre-V115 local cluster remains fail-closed until an accountable administrator provisions the local-only capability or recreates a disposable synthetic volume; initialization hooks are not assumed to rerun.

## 8. Security impact

- Strengthened: custom-GUC manipulation is no longer sufficient for reference workforce authority.
- Strengthened: cross-tenant, unknown permission, production-role and production-profile behavior remains fail closed and has direct database attacks.
- Strengthened: raw authorization purposes are excluded from logs, exceptions, traces, metrics, proxy logs and browser analytics; normalized values remain available only to governed evidence paths.
- Strengthened: the S3 fixture is repository-controlled and digest-verified, with no mutable or unavailable server image reference.
- Preserved: RLS, MFA/recent authentication, idempotency, ETag concurrency, audit/outbox, CSP, authorization and provider fail-closed behavior.

The detailed authorization boundary and residual trust are documented in `LOCAL_REFERENCE_AUTHORIZATION_THREAT_MODEL.md`.

## 9. Tests added or strengthened

- Five net new backend cases, including V115 capability/GUC attacks and authorization-reason leakage/telemetry assertions.
- M1-14 asynchronous hierarchy regression fix without reduced assertions.
- Explicit 768px readiness layout assertions while retaining Axe and both overflow checks.
- S3 fixture digest/preflight tests and expanded CI-security mutation coverage.
- QA-evidence count/parsing and release-state negative tests.
- Live audit commit/origin/status output plus trace and failure evidence.

## 10–14. Final local counts

| Measure | Count/state |
| --- | --- |
| Backend | 279/279 PASS; zero failures/errors/skips (257 core, 22 compatibility) |
| Frontend | 144/144 PASS |
| Browser | 185/185 PASS across 1440/1024/768/390/320 |
| Repository/API/input/security contracts | 140/140 PASS |
| API operations | 135 |
| Registered screens | 195 |
| Flyway | V1–V115 from empty PostgreSQL 18 |
| Authenticated live audit | PASS: login, selection, 193 protected routes, 4 expected 428 boundaries, 0 unexpected API/console failures |

## 15. Hosted CI status

- Baseline quality: **FAIL**.
- Baseline security: **PASS**.
- Stabilization-tree quality: **NOT RUN**.
- Stabilization-tree security: **NOT RUN**.

The workflow changes and local security contracts pass, but they are not substitutes for GitHub-hosted CodeQL, Trivy, image/SBOM or quality evidence on a final SHA.

## 16. Remaining external/non-code blockers

- Commit/push and successful required hosted quality/security checks for the exact commit.
- Retained image scan, SBOM and provenance identifiers for that commit.
- Protected COS source reconciliation and M1 facility-scope approval.
- Target providers/profiles/credentials, workers/schedulers and managed infrastructure.
- Backup/restore, monitoring/on-call, load and penetration testing.
- Target tenant-isolation/deployment smoke and accountable clinical/privacy/legal/finance/operations acceptance.

No Module 14, unrelated screen, speculative AI feature, production credential, partner contract, protected source content, terminology package, implementation guide or provider configuration was added.

## 17. Gate decision

**Repository release gate: FAIL.** Local executable evidence passes, but the exact changed tree is uncommitted and has no hosted quality/security evidence.

**Production acceptance: NOT GRANTED unless separately evidenced.**
