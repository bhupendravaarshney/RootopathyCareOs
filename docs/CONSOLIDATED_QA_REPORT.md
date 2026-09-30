# CareOS consolidated QA report

**Evidence date:** 30 September 2026

**Baseline commit reviewed:** `0c53e5ca766f0e52ead8127a59718dcf8e1cce94` (`main` and `origin/main` at the start of this pass)

**Evidence subject:** executable candidate `fb5388fa9de73e1f5dc3e7c98c0037648d67e055`

**Schema/API boundary:** Flyway V117, OpenAPI 3.1.0, 195 screens, 135 operations

**Local UAT readiness: PASS.**

**Repository release gate: PASS** for the evidence-subject commit.

**Target-environment UAT: NOT EXECUTED.**

**Production acceptance: NOT GRANTED.**

## Local consolidated QA

| Gate | Result | Executable evidence |
| --- | --- | --- |
| Backend clean verification | PASS | 283/283 tests, zero failures, errors or skips: 261 core and 22 required compatibility tests. PostgreSQL 18 applied and validated V1–V117 from an empty database. |
| PostgreSQL authorization and RLS | PASS | V114–V117 attacks cover missing/false reference flag denial, deployment-capability removal, runtime-role GUC manipulation, exact actor/tenant binding, cross-tenant denial, unknown permissions, unchanged active production roles, production-profile rejection, exact platform-role composition and machine-authority exclusion. |
| Platform super administrator | PASS | The active MFA-required organization role has exactly 277 approved human-interactive permissions and none of the 10 worker/provider-only permissions. Assignment requires an existing member and governed maker/checker change; direct invitation, acting-user changes, service targets, ordinary final-owner mutation, non-platform reflexive delegation and cross-tenant use remain denied. |
| Object storage/scanner compatibility | PASS | The repository-owned S3 fixture reproduced manifest `sha256:9b225075e9847bde86fa19f8274353ae7c5c4018af813ebb5bb9ce12d329b744`; S3, Object Lock and ClamAV tests ran without skips. |
| Frontend static/unit/build | PASS | `npm ci`, API drift check, architecture check, strict typecheck, lint, formatting, 144/144 Vitest tests and production build pass. The exact Node 24.15.0 Docker build also passes. |
| Browser/accessibility/responsive | PASS | 185/185 Playwright cases pass at 1440, 1024, 768, 390 and 320 pixels with Axe and document/body overflow checks. |
| Repository/API/security contracts | PASS | 141/141 positive and negative contract tests pass; the registry remains 195 screens and OpenAPI remains 135 operations. Default, UAT-overlay and scanner-overlay Compose configurations validate. |
| Authenticated product audit | PASS | Login, organization selection and 193 protected routes pass with zero unexpected API failures, console errors, exposed screen codes or contentless routes. Four HTTP 428 results remain intentional high-assurance boundaries. |
| Governed UAT mutation smoke | PASS | 16 representative server-projected actions are visible; one service draft, two successive workforce pathways, the normal registration path and a real synthetic document quarantine upload complete. The urgent temporary-identity path returns the required fail-closed worker dependency. No unexpected API or console failure occurred. |
| Authorization-reason privacy | PASS | The backend/security suites retain the tests proving raw `X-Authorization-Reason` values are absent from logs, exception text, trace attributes, metrics, proxy evidence and browser analytics; only the normalized bounded value may enter governed audit evidence. |

The local host used Node 24.13.0 and emitted an engine warning against the repository's 24.15.0 floor. All frontend commands passed, and the digest-pinned frontend Docker build used the exact 24.15.0 toolchain. Hosted CI must still run with `frontend/.nvmrc` for commit-bound evidence.

## UAT evidence and usage

The runnable instructions are in [UAT_GUIDE.md](UAT_GUIDE.md); the accountable sign-off template is [UAT_ACCEPTANCE_RECORD.md](UAT_ACCEPTANCE_RECORD.md). Automated local evidence is written to:

- `frontend/test-results/live-product-audit/`
- `frontend/test-results/uat-smoke/report.json`
- `frontend/test-results/uat-smoke/*.png`
- `frontend/test-results/uat-smoke/trace.zip`
- `build/qa-evidence.json`

The mutation smoke is guarded by `CAREOS_UAT_ALLOW_MUTATION=true`, permits localhost by default, creates uniquely marked synthetic data only, and requires a second explicit override for a non-local target. The UAT Compose overlay enables private quarantine only; scan, promotion, signed access and retention remain separately fail closed. `local_bootstrap` is displayed as Local/UAT super administrator but remains a capability-gated reference role. Formal maker/checker and platform-role promotion UAT requires separate invited identities and completion of the manual scenarios in the guide.

## Hosted GitHub quality

**PASS for `fb5388fa9de73e1f5dc3e7c98c0037648d67e055`.** Frontend, browser, backend core, compatibility, contracts and authenticated product-smoke all passed independently, and the aggregate quality gate passed. Commit-bound QA evidence and authenticated product-audit artifacts were uploaded.

The quality workflow is configured with independent frontend, browser, backend-core, compatibility, contracts and product-smoke lanes. Its product-smoke lane runs both authenticated audits and uploads screenshots, reports and traces under the exact commit SHA; the aggregate gate requires every lane.

No earlier hosted result is carried forward to these changed bytes.

## Hosted GitHub security

**PASS for `fb5388fa9de73e1f5dc3e7c98c0037648d67e055`.** Source controls, Java and JavaScript/TypeScript CodeQL, pinned hardened image builds, Trivy scans, dependency review/SBOM behavior and the immutable-action/image contracts passed for the complete candidate.

## Target-environment acceptance

**NOT EXECUTED.** The local Compose result does not cover managed PostgreSQL/Redis/storage, deployment identities, secret management, TLS/edge controls, provider credentials, backup/restore, monitoring/on-call, load, penetration or target tenant-isolation testing. Complete the UAT acceptance record against one exact candidate and isolated target environment.

## Production acceptance

**NOT GRANTED.** Protected COS reconciliation, facility-scoped authorization approval, dedicated clinical/care-plan/follow-up UX, production workers, real notification/payment/calendar/lab/FHIR providers and accountable clinical, privacy, legal, finance, security and operations approval remain open and fail closed where applicable.

## Test classification

- Core required gate: architecture, domain behavior, PostgreSQL tenancy/RLS, authorization, governance, identity, migrations, API contracts, frontend unit/build and browser accessibility/responsiveness.
- Compatibility/infrastructure required gate: S3-compatible mechanics, Object Lock, ClamAV and provider-specific behavior.

Both classes remain required for release. The classification improves diagnosis only; it does not permit a skip on `main`.

## Evidence interpretation

Local engineering and UAT-readiness gates plus hosted quality/security are green for the evidence-subject commit. Formal target UAT and production acceptance remain separate accountable decisions.
