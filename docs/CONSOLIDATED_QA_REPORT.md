# CareOS consolidated repository QA report

**Run date:** 28 September 2026

**Repository scope:** Phase 0 and Modules 1-13 through Flyway V114

**Result:** PASS

**Target-environment/production acceptance:** not granted

## Results

| Gate | Result | Evidence |
| --- | --- | --- |
| Backend clean verification | PASS | Java 25/Maven 3.9.11 compiled 474 production and 57 test source files, validated/applied V1-V114 to disposable PostgreSQL 18, passed 274 tests in 57 suites with zero failures, errors or skips, enforced all 11 architecture rules and packaged the bootable JAR. |
| Frontend contract/static/unit/build | PASS | Generated-client drift, formatting, strict TypeScript, ESLint, 70-source/14-feature/209-import architecture checks, four negative dependency fixtures, 144 tests in 15 files and the Vite production build all pass. |
| Browser/accessibility/responsive | PASS | The complete Playwright run passes 185/185 cases across the exact 1440/1024/768/390/320 projects, including Axe and overflow assertions. |
| API contract | PASS | OpenAPI 3.1 version 0.52.0 contains exactly 135 registered operations and all 38 positive/negative contract cases pass. |
| Screen registry | PASS | The independent verifier and backend registry agree on exactly 195 screens: M1 23, M2 29, M3 16, M4 15, M5 12, COS 27, P7 11, P8 10, P9 12, P10 9, P11 11, P12 10 and P13 10. |
| Repository/input/security contracts | PASS | All 131 API, approved-input, retained-candidate and CI-security contract cases pass, including the whole-project runner parity check. |
| Live product audit | PASS | A real authenticated browser visited 193 protected business routes plus login and organization selection. It found zero unexpected API failures, zero unexpected console errors, zero exposed internal screen-code routes and zero module routes without useful content. |
| Compose and deployment smoke | PASS | Both Compose models validate. The rebuilt six-service local stack upgraded through V114, remains healthy, serves frontend HTTP 200 and exposes the exact 195-screen registry. The clean backend suite separately applies V1-V114 from an empty PostgreSQL 18 schema. |
| Application images and source/image scans | PASS | Both digest-pinned non-root application images rebuild. Trivy 0.74 reports zero fixed HIGH/CRITICAL Maven/npm, Debian 13.6 backend OS/JAR or Alpine 3.24.1 frontend findings, zero Dockerfile misconfigurations and no source/image secret finding. |

## Live product-readiness remediation

The repository gates were green before this pass, but the running local product was not useful enough for hands-on QA. A first authenticated route sweep found widespread internal M1/M2/P-style labels, 31 pages presenting alerts or load failures, 143 content-empty pages, only one route with a real data row and no single administrator view spanning the product. It also reproduced an M1 activation-query HTTP 500, broad Module 2 HTTP 500 responses and two purpose-bound Module 2 reads that could only return HTTP 400.

The remediated live stack now reports:

| Live check | Result |
| --- | --- |
| Authenticated experiences covered | 195: login, organization selection and 193 protected business routes |
| Unexpected API failures | 0 |
| Unexpected browser-console errors | 0 |
| Routes exposing internal M1/M2/P/COS labels anywhere in visible page text | 0 |
| Module routes without live rows, a clearly labeled demo preview or an explicit access-purpose prompt | 0 |
| Routes with local read-only demo previews | 168 |
| Routes with live server rows | 2 |
| Purpose-bound workforce routes | 2, both gated and successfully loaded with an explicit audited QA reason |
| Administrator coverage | One administrator overview with links to all 13 workspaces |

The remediation includes these product changes:

- Repaired the M1 activation query to use the actual facility name column.
- Added a V114 local-bootstrap bridge for Module 2's database resource checks. It works only when the existing transaction-scoped reference-policy flag is explicitly enabled; the same calls remain denied when that flag is false.
- Ensured Module 2 domain failures use their specific problem handler instead of becoming generic HTTP 500 responses.
- Added an optional checked `X-Authorization-Reason` contract and a mandatory purpose prompt for the two purpose-bound workforce reads.
- Replaced visible implementation codes with meaningful workspace names, page titles and Previous/Next controls.
- Added a clearly identified `CareOS Demo Administrator` and a 13-workspace command centre.
- Added module-relevant local demo records for empty Module 2-13 projections. They are UI-only, read-only, limited to the fixed local demo organization, never written or submitted to an API, and explicitly excluded from clinical or operational use.
- Added guided identity-verification calls to action for high-assurance pages instead of presenting an unexplained failure.

Four HTTP 428 responses remain intentional: two audit/export requests on Audit log and one request each on Credential review detail and Workforce audit log. They preserve recent-authentication/MFA enforcement and now render a guided verification state. They are not counted as unexpected failures.

The live audit is repeatable while the Compose stack is running:

```powershell
cd frontend
npm run test:live:audit
```

Its detailed JSON and four visual captures are written to `frontend/test-results/live-product-audit/`.

One cross-module test-fixture cleanup issue was exposed by the full run: the document-evidence integration test truncated a parent table without including Module 8's later foreign-key child. The disposable-test cleanup now uses PostgreSQL `CASCADE`; the affected 12-test suite and the subsequent clean 274-test backend verification both pass. Production code and migration history were not weakened or rewritten.

A post-completion audit exposed one QA-tooling false-green risk: the original shell whole-project runner omitted several enforced gates. The shell runner now includes approved-input enforcement, negative contract/security suites, generated-client drift, frontend architecture, the complete browser matrix and clean Maven verification. A matching PowerShell runner was added, and the CI-security suite now rejects parity regressions between both runners. No additional application defect was reproduced during this audit.

The only non-blocking build observation is Vite's approximately 775 KB JavaScript chunk warning. It does not affect correctness, accessibility or the passing production build, but route-level code splitting remains a sensible performance optimization before production rollout. The runners also fail early outside the required Node 24.15-or-newer Node 24 line.

## Meaning of PASS

This result closes the deferred repository-wide regression and the local live-product remediation for the completed 13-module source tree. It verifies the checked local code, migrations, contracts, generated client, architecture boundaries, unit/integration behavior, registered routes, browser accessibility, responsive containment and authenticated local runtime behavior.

It is not production acceptance. Protected COS source reconciliation, the separately unapproved M1 facility-scope extension, real secrets/keys and service identities, partner/provider/profile/policy activation, hosted CI and registry enforcement, retained exact-artifact SBOM/provenance evidence, target infrastructure, backup/restore, monitoring/on-call, performance/load/penetration exercises and accountable clinical/privacy/legal/finance/operations approvals remain explicit external acceptance work.
