# CareOS repository stabilization report

## 1. Baseline commit reviewed

`0c53e5ca766f0e52ead8127a59718dcf8e1cce94` (`main` and `origin/main` when this UAT-preparation pass started on 29 September 2026).

The executable repository—not prior QA prose—was used as the baseline. The application stack was started locally and the reported inability to create administration data was reproduced as missing server-projected actions for the synthetic `local_bootstrap` membership.

## 2. Final commit

No final commit was created. The deliverable is an uncommitted working tree based on `0c53e5ca766f0e52ead8127a59718dcf8e1cce94`. This is stated explicitly so local evidence cannot be mistaken for hosted, commit-bound release evidence.

## 3–5. Problems, root causes and exact fixes

| Problem | Root cause | Exact fix |
| --- | --- | --- |
| The local administrator could view the application but had no create actions across Administration and Modules 2–13. | Mutation authorization recognized the V114/V115 local bridge, while screen/action projections still selected only `active` roles. The deliberately `reference`-status `local_bootstrap` role was therefore hidden. | V116 adds one `SECURITY INVOKER` action-projection function. It preserves active production roles and admits only the exact reference role when the V115 database-owned capability and transaction flag are both present and the requested actor/tenant exactly match transaction context. All relevant stores use this central function. |
| No production-capable role composed every approved human workflow or could govern the complete approved human-role catalogue. | Existing roles were module-specific; `organization_owner` intentionally did not inherit later-module grants, and the local bootstrap identity is reference-only. | V117 creates a checksum-bound `PLATFORM_ACCESS` release and the MFA-required, organization-scoped `platform_super_administrator`. It contains the exact 277-permission active human union, excludes all 10 service/worker-only permissions, and uses the governed existing-membership maker/checker path for assignment, change and revocation. |
| The first V117 peer-flow test showed that setting `mfa_required=true` alone did not enforce enrollment for a later-registry role. | V24's private login MFA index synchronizer and the readiness query were intentionally hardcoded to the then-only `m1-candidate-1` release. | V117 replaces the synchronizer with an active-role/active-release derivation, preserving the private index and `SECURITY DEFINER` boundary. Readiness uses the same active immutable-release rule. Login now requires platform administrators to enroll/use MFA, and the database test asserts the derived result. |
| Directly “opening everything” would have bypassed safety controls. | A global bypass or database-superuser interpretation would defeat RLS, assurance, audit, independent approval and provider readiness. | Super administration is role-based and tenant-scoped. Direct invitation, self-target changes, service-role assignment, ordinary final-owner mutation and cross-tenant authority remain denied. The only same-role delegation exception is platform administrator to a different platform administrator; every other reflexive role delegation remains database-rejected. |
| A normal admin create flow could fail during successive workforce onboarding. | Member numbers used only the first eight characters of UUIDv7. IDs created in the same time window share that prefix and collided with the unique member-number constraint. | Member numbers now use the complete uppercase UUID (`WF-<UUID>`), within the existing 48-character field and validation contract. A unit test and two-successive-creation UAT smoke cover the regression. |
| The browser emitted an invalid-pattern console error on service creation. | The HTML pattern used a character class that is invalid under modern UnicodeSets (`v`) semantics. | Replaced it with the backend-aligned, bounded pattern `[A-Z0-9](?:[A-Z0-9_]|-){1,31}`. |
| UAT had no safe, repeatable creation/upload path or evidence record. | The live audit was read-only, base Compose intentionally disabled S3, and documentation did not distinguish automated readiness, formal UAT and production acceptance. | Added an explicit localhost-only mutation smoke, a synthetic UAT Compose overlay enabling private quarantine only, real document upload, synthetic record markers, screenshots/trace/report, CI execution and artifact upload, a twelve-scenario UAT guide and a separate accountable acceptance record. |
| A successful quarantine upload was rejected by the frontend and UUID fields emitted Chromium pattern errors. | The runtime row validator rejected the governed `$kind` discriminator, while abbreviated UUID character-class patterns are invalid under modern UnicodeSets (`v`) semantics. | The document contract now admits only the exact `$kind` extension and retains strict checks for all other fields. UUID inputs use the exact version/variant-aware 36-character pattern. Contract tests and the authenticated mutation smoke cover the response end to end. |
| The urgent temporary-identity screen appeared unusable. | The reconciliation worker is intentionally absent; creating a temporary clinical identity without it would violate the fail-closed design. | Kept the path denied. The UAT smoke requires the precise 409/reconciliation dependency and treats any silent success or different error as a failure. The UAT guide explains how to classify this expected boundary. |
| QA wording could outlive executable evidence. | Earlier Markdown referred to historical candidates and hosted states rather than this working tree. | Updated the machine-readable evidence model with UAT state and rewrote current QA/UAT status to identify the baseline, dirty tree, local results and absent hosted/target acceptance explicitly. |

## 6. Files changed

- Database/security: `V116__capability_gated_local_action_projection.sql`, `V117__governed_platform_super_administrator.sql`, the authorization stores/bootstrap and every administration/business-module store that projects interactive permissions.
- Workforce: `JdbcWorkforceStore.java` and `JdbcWorkforceStoreTest.java`.
- Database attacks: `TenantRlsIntegrationTest.java`.
- Frontend/UAT: administration/identity role catalogues, strict UUID input patterns, document response contracts/tests, `frontend/package.json`, `frontend/scripts/uat-smoke.mjs` and `compose.uat.yaml`.
- Approved access evidence: `approved-inputs/platform-access/01-implementation-direction.md`, `02-authorization-policy.json` and `03-approval-evidence.md`.
- CI/evidence contracts: `.github/workflows/quality.yml`, `scripts/generate-qa-evidence.mjs`, `scripts/verify-ci-security.mjs` and its tests.
- Guidance/evidence: `README.md`, `IMPLEMENTATION_GAPS.md`, `docs/AUTHORIZATION_REGISTRY.md`, `docs/SECURITY.md`, `docs/PRODUCTION_SECURITY.md`, `docs/BUILD_STATUS.md`, `docs/CONSOLIDATED_QA_REPORT.md`, `docs/LOCAL_REFERENCE_AUTHORIZATION_THREAT_MODEL.md`, `docs/UAT_GUIDE.md`, `docs/UAT_ACCEPTANCE_RECORD.md` and this report.

No new product module or screen was added.

## 7. Migration impact

V116 is forward-only and adds one permission-projection function; it creates no business table and grants no production role or permission. Public function execution is revoked. The restricted runtime role receives execute permission only, and the function runs with invoker rights.

V117 is forward-only. It adds one active cross-cutting authorization release and one interactive role; no product module or business table is added. The role is migration-composed from active, release-backed human authority, is not invitation-assignable or a final owner, and is MFA-required. A centralized `SECURITY INVOKER` delegation predicate binds actor, tenant, active membership, target and all registry releases. The runtime role cannot mutate the catalogues. An existing deployment must follow its normal migration and governed membership-change process; V117 does not silently assign a production person.

Fresh local/test databases still require the explicit V115 bootstrap capability. Production provisioning must not create/grant that capability, and the production Spring profile continues to reject reference-policy activation. An older local volume without the capability remains denied until an accountable local administrator provisions it or a disposable synthetic volume is recreated.

## 8. Security impact

- Strengthened: local action visibility and mutation authorization now share the same deployment-owned capability boundary.
- Strengthened: the function binds requested organization and actor to transaction-local context and a current active membership.
- Verified: missing/false flag, capability removal, GUC manipulation, cross-tenant, wrong-actor and unknown-permission paths deny access.
- Verified: the platform role has 277 human permissions and none of the 10 machine permissions; direct invitation, self-target, non-platform reflexive delegation, service targets and cross-tenant delegation deny access.
- Verified: platform-role assignment immediately enters the private mandatory-MFA index; login and readiness cannot ignore a later active registry release.
- Preserved: active production-role behavior, RLS, MFA/recent authentication, idempotency, ETag concurrency, audit/outbox, CSP, authorization-reason privacy and fail-closed providers.
- Preserved: urgent temporary identity remains unavailable without its reconciliation worker.

The residual trust and attack model are documented in `LOCAL_REFERENCE_AUTHORIZATION_THREAT_MODEL.md`.

## 9. Tests added or strengthened

- PostgreSQL V116 projection attacks plus V117 composition, evidence, service-exclusion, mandatory-MFA derivation, exact peer-delegation and cross-tenant attacks.
- Authenticated HTTP promotion of another user from a platform administrator to a peer platform administrator through independent approval, followed by governed revocation.
- One workforce member-number collision unit test.
- Document response-contract coverage for the exact governed discriminator and invalid extensions.
- One guarded authenticated UAT smoke covering 16 action projections, the complete synthetic registration path, private quarantine upload and one required fail-closed denial.
- CI-security contract checks for V116/V117, checksum-bound platform-access evidence, human/service separation, centralized action projections, the mutation opt-in and retained UAT evidence.
- The full existing backend, frontend, browser/Axe/overflow, API/negative-contract, S3/Object Lock/ClamAV and authorization-reason privacy suites remain enabled.

## 10–14. Final local counts

| Measure | Count/state |
| --- | --- |
| Backend | 283/283 PASS; zero failures/errors/skips (261 core, 22 compatibility) |
| Frontend | 144/144 PASS |
| Browser | 185/185 PASS across 1440/1024/768/390/320 |
| Repository/API/input/security contracts | 141/141 PASS |
| API operations | 135 |
| Registered screens | 195 |
| Flyway | V1–V117 from empty PostgreSQL 18 |
| Authenticated product audit | PASS: 193 protected routes, four expected 428 boundaries, zero unexpected API/console failures |
| Governed UAT smoke | PASS: 16 actions, six recorded mutation stages including private document quarantine, one expected denial, zero unexpected API/console failures |

The repository-owned S3 fixture reproduced manifest `sha256:9b225075e9847bde86fa19f8274353ae7c5c4018af813ebb5bb9ce12d329b744` before compatibility execution.

## 15. Hosted CI status

Hosted quality: **NOT RUN for the exact working tree**.

Hosted security: **NOT RUN for the exact working tree**.

The quality workflow is configured to run frontend, browser, backend-core, compatibility, contracts and product-smoke independently, aggregate all required results, run both authenticated audits and upload commit-named evidence. The security workflow and its immutable-action/image contracts are retained. Local results are not substituted for hosted CodeQL, Trivy, CycloneDX or GitHub-runner evidence.

## 16. Remaining external/non-code blockers

- Commit the candidate and obtain green hosted quality and security workflows for that exact SHA.
- Execute the manual UAT scenarios with separate maker/checker identities in an isolated target environment and obtain accountable sign-off.
- Protected COS source reconciliation and facility-scoped authorization approval/implementation.
- Dedicated patient clinical workspace and care-plan/follow-up-specific UX.
- Production workers/schedulers and real notification, payment, calendar, laboratory and FHIR providers with approved credentials/configuration.
- Production infrastructure, backup/restore, monitoring/on-call, load, penetration and target tenant-isolation testing.
- Clinical, privacy, legal, finance, security and operations acceptance.

Unavailable capabilities remain fail closed; no clinical policy, credential, partner contract, protected source, terminology package, implementation guide or provider configuration was fabricated.

## 17. Gate decision

**Repository release gate: FAIL.** Local engineering and UAT-readiness gates pass, but the changes are not an exact committed SHA with successful hosted quality and security evidence.

**Production acceptance: NOT GRANTED unless separately evidenced.**
