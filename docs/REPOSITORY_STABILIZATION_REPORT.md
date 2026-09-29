# CareOS repository stabilization report

## 1. Baseline commit reviewed

`be6cfa8e4f947a2e42ed1cba29d29ad1f65f4dc5` (`main`, also matching `origin/main` when inspected on 29 September 2026).

Baseline hosted state:

- `CareOS security`: PASS.
- `CareOS quality`: FAIL.
- Frontend failed at the M1-14 hierarchy assertion; backend failed after 260 tests while fetching the removed Quay MinIO digest; contracts passed; browser was skipped because it depended on frontend.
- Pulling `quay.io/minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e` independently returned HTTP 401. A stale local image had masked this reproducibility failure.

## 2. Final commit

`97ea69d9e2fd43d2fd887f7ad5410ba6e103ff60` was the first stabilization attempt. `bb4ad13715f59a4dbde26fcb92907671b8b57215` is the second attempt and currently matches `main`/`origin/main`; hosted security and every independent quality lane except frontend passed, but it is not an accepted final commit because the aggregate quality gate failed. The follow-up result is an uncommitted tree based on `bb4ad137…`. No follow-up commit or push was authorized, so there is no final SHA or hosted result for these exact bytes.

## 3–5. Problems, root causes, and fixes

| Problem | Root cause | Exact fix |
| --- | --- | --- |
| Hosted M1-14 unit failure | The test asserted hierarchy rows immediately after the page heading, before the asynchronous organization-unit projection resolved. | Awaited all `Clinical Services` rows with `findAllByText`; retained Cardiology, depth/revision, create/edit/reparent/activate, ETag and idempotency assertions. |
| S3 integration was not reproducible | The legacy digest could no longer be fetched; local cache made the first failure appear environment-specific. The first replacement fixed image/config timestamps but not layer file timestamps, so local and hosted BuildKit produced different manifests. | Added a repository-owned MinIO source fixture with pinned BuildKit, Dockerfile frontend, Go builder, source commit/archive checksum, platform/source epoch, disabled environment-derived metadata, `rewrite-timestamp=true`, and a single portable Docker V2 output. Two cache-independent builds reproduce manifest `sha256:9b225075e9847bde86fa19f8274353ae7c5c4018af813ebb5bb9ce12d329b744`. CI builds, verifies and starts it before Maven; the Java test requires matching metadata and never pulls. |
| Browser evidence was hidden | `browser.needs = frontend`. | Made frontend, browser, backend-core, compatibility, contracts and product-smoke independent; added an `always()` aggregate that requires every lane. |
| 768px readiness overflow | The first local fix correctly collapsed readiness rows but the downloaded hosted trace showed the remaining offender was the administration page-header badge. Its `fit-content`/nowrap width extended from x=526 to x=780 while the shell deliberately remained in tablet mode at 768px. | Added an administration-specific page-head class and 761–900px stacked layout with bounded children and safe badge wrapping. The global 760px mobile-shell breakpoint is unchanged; the 768 project retains Axe and document/body overflow assertions. |
| Hosted source/image security failed | Spring Boot 4.1.1 managed Jackson 3.1.5 and the MinIO dependency path used Jackson 2.21.5; Trivy 0.74.0 reported fixed HIGH CVE-2026-68497 for both. | Added patch-only BOM floors 3.1.6 and 2.21.6. Maven resolves both fixed versions; exact Trivy filesystem and rebuilt backend/frontend image scans now report zero fixed HIGH/CRITICAL findings without an ignore rule. |
| Focus resume revalidation was racy | The resume event handler read authentication and pending-action refs synchronized in a passive effect. Hosted scheduling allowed the authenticated shell to become queryable before that effect ran, so focus still observed the prior `loading` phase and returned without contacting the server. | Synchronize both event-handler refs in `useLayoutEffect`, closing the post-commit/pre-passive-effect window. The test now awaits the second server session request, requires the anonymous sign-in boundary, and proves the authenticated dashboard was removed. |
| V114 GUC could be mistaken for authority | Any connected PostgreSQL role can attempt to set a custom GUC. | V115 requires direct `session_user` membership in a deployment-owned non-login capability role with `ADMIN`, `INHERIT` and `SET` all false. Local/test bootstrap opts in explicitly; production config still rejects the reference policy. |
| Authorization reason could leak | The useful purpose header entered the servlet request before generic telemetry and logging boundaries. | Added an earliest-order capture/normalize/bounds filter, hid the raw header from downstream access/enumeration, retained only the governed normalized value, and added log/trace/metric/proxy/browser leak tests. |
| Live audit was advisory | It was only a manual command. | Added a required isolated Compose `product-smoke` job with readiness/frontend checks, authenticated audit artifacts/traces/screenshots and unconditional teardown. |
| Compatibility failures obscured core health | S3/ClamAV provider behavior ran inside one backend result. | Tagged S3 and ClamAV as `compatibility`; CI now runs 257 core and 22 compatibility tests in separate required lanes. Full local Maven verification still runs all 279. |
| Integration shutdown logs obscured failures | Spring contexts/pools survived dependency containers, and scheduled session cleanup retried during teardown. | Closed compatible Spring contexts after each integration class and disabled only scheduled test-session cleanup. Isolation remains per class/state; final logs contain no connection-refused/reconnect cleanup noise. |
| QA prose was not commit-bound | Markdown could retain PASS while hosted quality failed. | Added machine-readable QA evidence with SHA, dirty/clean state, schema/API/screens/operations/counts, quality/security/product states and explicit release/production fields. Hosted artifacts are SHA-named; dirty local evidence cannot pass the repository release gate. |

## 6. Files changed

- CI/supply chain: `.github/workflows/quality.yml`, `.github/dependabot.yml`, `.gitignore`, `compose.yaml`, `backend/pom.xml`, `test-fixtures/s3/*`.
- QA tooling: `scripts/build-s3-test-fixture.mjs`, `scripts/generate-qa-evidence.mjs`, both whole-project runners, CI-security verifier, and their tests.
- Authorization/security: V115, PostgreSQL bootstrap/test roles, `PostgresTenantAuthorizationOperations`, `AuthorizationReasonFilter`, `WorkforceController`, CORS configuration, and security/API/tenant attack tests.
- Test lifecycle: `application-test.yml` and the 16 Spring integration classes marked for post-class context closure.
- Frontend/browser/audit: `App.test.tsx`, `SessionProvider.tsx`, `AdministrationScreens.tsx`, `styles.css`, `prototypes.spec.ts`, and `live-product-audit.mjs`.
- Evidence/docs: `README.md`, `IMPLEMENTATION_GAPS.md`, `docs/BUILD_STATUS.md`, `docs/CONSOLIDATED_QA_REPORT.md`, `docs/IMPLEMENTATION_ROADMAP.md`, `docs/PLATFORM_CAPABILITIES.md`, this report, and `docs/LOCAL_REFERENCE_AUTHORIZATION_THREAT_MODEL.md`.

## 7. Migration impact

V114 is unchanged. New forward-only V115 replaces the local-bootstrap permission helper with the two-factor database boundary and adds the capability-check function. No business table or production role grant is added. Fresh local/demo environments provision the capability through the explicit bootstrap marker, while production must omit it. A pre-V115 local cluster remains fail-closed until an accountable administrator provisions the local-only capability or recreates a disposable synthetic volume; initialization hooks are not assumed to rerun.

## 8. Security impact

- Strengthened: custom-GUC manipulation is no longer sufficient for reference workforce authority.
- Strengthened: cross-tenant, unknown permission, production-role and production-profile behavior remains fail closed and has direct database attacks.
- Strengthened: raw authorization purposes are excluded from logs, exceptions, traces, metrics, proxy logs and browser analytics; normalized values remain available only to governed evidence paths.
- Strengthened: the S3 fixture is repository-controlled and digest-verified, with no mutable or unavailable server image reference.
- Strengthened: both transitive Jackson generations are held at fixed patch floors for CVE-2026-68497; no vulnerability suppression was added.
- Preserved: RLS, MFA/recent authentication, idempotency, ETag concurrency, audit/outbox, CSP, authorization and provider fail-closed behavior.

The detailed authorization boundary and residual trust are documented in `LOCAL_REFERENCE_AUTHORIZATION_THREAT_MODEL.md`.

## 9. Tests added or strengthened

- Five net new backend cases, including V115 capability/GUC attacks and authorization-reason leakage/telemetry assertions.
- M1-14 asynchronous hierarchy regression fix without reduced assertions.
- Explicit 768px administration page-header and readiness-row layout assertions while retaining Axe and both overflow checks.
- S3 fixture digest/preflight tests and expanded CI-security mutation coverage.
- Existing hosted browser evidence was downloaded and reproduced as two assertions for the same 768px page-header offender; both focused cases now pass after the component-specific fix.
- Session resume regression now waits for the second server revalidation call and verifies both the anonymous sign-in boundary and removal of the authenticated dashboard; the complete 144-test frontend suite passed six consecutive runs after the fix.
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

Local-only image scan identifiers are backend `sha256:54a883f705f8c67b4da58afb99b9181d97e467b05122f1ab15dd0728220d2530` and frontend `sha256:59be1264f0a8131e7f6ca19662da78be682a3e513b6bd9c7c8dff21ab3dafaf0`. Local CycloneDX 1.7 evidence hashes are backend `sha256:d9eef109ab0270da735c4830da2f35bfc56987d74fd169646dcb1102b162a713` and frontend `sha256:a3bee639ee2618ccc237424221e65450fff0f565e21c3558c982379eb4c23790`. These identifiers are recorded as local, dirty-tree evidence in `build/qa-evidence.json`; they are not hosted release evidence.

## 15. Hosted CI status

- Baseline quality: **FAIL**.
- Baseline security: **PASS**.
- `97ea69d9…` quality run `36553327188`: **FAIL** — frontend, backend, contracts, and product-smoke passed; compatibility failed on fixture digest drift and browser failed twice on the same 768px overflow.
- `97ea69d9…` security run `36553327089`: **FAIL** — both CodeQL jobs passed; source and backend-image Trivy failed on the two Jackson CVE-2026-68497 findings; frontend-image scan was not reached.
- `bb4ad137…` quality run `36561861499`: **FAIL** — backend, compatibility, contracts, browser, and product-smoke passed; frontend alone failed on the session focus-revalidation race.
- `bb4ad137…` security run `36561861463`: **PASS** — both CodeQL languages, source scan, and backend/frontend image scans passed.
- Follow-up-tree quality: **NOT RUN**.
- Follow-up-tree security: **NOT RUN**.

The corrected fixture, focused 768px cases, local security contracts, exact Trivy source scan, and rebuilt backend/frontend image scans pass, but they are not substitutes for GitHub-hosted CodeQL, Trivy, image/SBOM or quality evidence on a final SHA.

## 16. Remaining external/non-code blockers

- Commit/push and successful required hosted quality/security checks for the exact commit.
- Retained image scan, SBOM and provenance identifiers for that commit.
- Protected COS source reconciliation and M1 facility-scope approval.
- Target providers/profiles/credentials, workers/schedulers and managed infrastructure.
- Backup/restore, monitoring/on-call, load and penetration testing.
- Target tenant-isolation/deployment smoke and accountable clinical/privacy/legal/finance/operations acceptance.

No Module 14, unrelated screen, speculative AI feature, production credential, partner contract, protected source content, terminology package, implementation guide or provider configuration was added.

## 17. Gate decision

**Repository release gate: FAIL.** The current `main` attempt passed hosted security but failed hosted quality. Local executable evidence for the follow-up tree passes, but its exact bytes are uncommitted and have no hosted quality/security evidence.

**Production acceptance: NOT GRANTED unless separately evidenced.**
