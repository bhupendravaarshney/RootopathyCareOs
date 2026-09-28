# CareOS consolidated repository QA report

**Run date:** 28 September 2026  
**Repository scope:** Phase 0 and Modules 1-13 through Flyway V113  
**Result:** PASS  
**Target-environment/production acceptance:** not granted

## Results

| Gate | Result | Evidence |
| --- | --- | --- |
| Backend clean verification | PASS | Java 25/Maven 3.9.11 compiled 474 production and 57 test source files, validated/applied V1-V113 to disposable PostgreSQL 18, passed 274 tests in 57 suites with zero failures, errors or skips, enforced all 11 architecture rules and packaged the bootable JAR. |
| Frontend contract/static/unit/build | PASS | Generated-client drift, formatting, strict TypeScript, ESLint, 66-source/14-feature/187-import architecture checks, four negative dependency fixtures, 141 tests in 14 files and the Vite production build all pass. |
| Browser/accessibility/responsive | PASS | The complete Playwright run passes 185/185 cases across the exact 1440/1024/768/390/320 projects, including Axe and overflow assertions. |
| API contract | PASS | OpenAPI 3.1 version 0.52.0 contains exactly 135 registered operations and all 38 positive/negative contract cases pass. |
| Screen registry | PASS | The independent verifier and backend registry agree on exactly 195 screens: M1 23, M2 29, M3 16, M4 15, M5 12, COS 27, P7 11, P8 10, P9 12, P10 9, P11 11, P12 10 and P13 10. |
| Repository/input/security contracts | PASS | All 131 API, approved-input, retained-candidate and CI-security contract cases pass, including the whole-project runner parity check. |
| Compose and deployment smoke | PASS | Both Compose models validate. An isolated six-service stack applies V1-V113, reaches backend liveness/readiness, serves frontend HTTP 200 and reports the exact 195-screen registry before clean teardown. |
| Application images and source/image scans | PASS | Both digest-pinned non-root application images rebuild. Trivy 0.74 reports zero fixed HIGH/CRITICAL Maven/npm, Debian 13.6 backend OS/JAR or Alpine 3.24.1 frontend findings, zero Dockerfile misconfigurations and no source/image secret finding. |

One cross-module test-fixture cleanup issue was exposed by the full run: the document-evidence integration test truncated a parent table without including Module 8's later foreign-key child. The disposable-test cleanup now uses PostgreSQL `CASCADE`; the affected 12-test suite and the subsequent clean 274-test backend verification both pass. Production code and migration history were not weakened or rewritten.

A post-completion audit exposed one QA-tooling false-green risk: the original shell whole-project runner omitted several enforced gates. The shell runner now includes approved-input enforcement, negative contract/security suites, generated-client drift, frontend architecture, the complete browser matrix and clean Maven verification. A matching PowerShell runner was added, and the CI-security suite now rejects parity regressions between both runners. No additional application defect was reproduced during this audit.

The only non-blocking build observation is Vite's approximately 765 KB JavaScript chunk warning. It does not affect correctness, accessibility or the passing production build, but route-level code splitting remains a sensible performance optimization before production rollout. The runners also fail early outside the required Node 24.15-or-newer Node 24 line.

## Meaning of PASS

This result closes the deferred repository-wide regression for the completed 13-module source tree. It verifies the checked local code, migrations, contracts, generated client, architecture boundaries, unit/integration behavior, registered routes, browser accessibility and responsive containment.

It is not production acceptance. Protected COS source reconciliation, the separately unapproved M1 facility-scope extension, real secrets/keys and service identities, partner/provider/profile/policy activation, hosted CI and registry enforcement, retained exact-artifact SBOM/provenance evidence, target infrastructure, backup/restore, monitoring/on-call, performance/load/penetration exercises and accountable clinical/privacy/legal/finance/operations approvals remain explicit external acceptance work.
