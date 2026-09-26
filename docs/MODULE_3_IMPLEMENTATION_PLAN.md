# CareOS Module 3 implementation plan

**Module:** M3 Patient Registry (`P3-01` through `P3-16`)
**Current phase:** M3H repository closeout complete; QA/target activation deferred
**Accepted product definition:** `m3-product-definition-draft-2`
**Accepted predecessor:** `M2-COMPLETION-ACCEPTANCE-20260926-01`
**Product acceptance:** `M3-PRODUCT-ACCEPTANCE-20260926-01`
**Approved input:** `m3-candidate-1` under `M3-APPROVAL-20260926-01`
**Implementation authorization:** `true`

## Authority and current boundary

The user accepted the completed Module 2 repository evidence and directed work to move ahead on 26 September 2026. That instruction authorizes Module 3 entry planning under the build specification's Modules 3-13 product-definition template.

The reviewer subsequently returned `ACCEPT_WITH_CHANGES`, requested recommended changes, and then accepted the exact `m3-product-definition-draft-2` bytes for mockup preparation under `M3-PRODUCT-ACCEPTANCE-20260926-01`. That decision authorizes only synthetic review assets; it does not authorize production M3 code.

The resulting eight-artifact `m3-candidate-1` package covers all 16 screens, 14 core entity families, and fifteen accepted decision families. The reviewer accepted its exact candidate digest `c383cd7dbe927d2443958f71350c8cd59ac1a8eb725dc5b2a70e40ced115e21f`. `M3-APPROVAL-20260926-01` records that decision separately, and byte-identical approved artifacts produce promoted-package digest `3e7ced79ecc01f59e9d4d3bb15a48bd30e579a32f194b23e9f3ee0a9aed09c00`.

`contracts/module-3-input-gate.json` reports `APPROVED` and `implementationAuthorized: true`. M3B through M3H have now been implemented and repository-verified from that frozen input boundary. This does not approve a target deployment, production release, missing local catalogue or partner integration.

## Entry conditions

| Condition                                   | State          | Evidence / required action                                                                    |
| ------------------------------------------- | -------------- | --------------------------------------------------------------------------------------------- |
| M1 identity/tenant/governance baseline      | AVAILABLE      | Organization-wide M1 repository boundary through V50                                          |
| M2 predecessor repository acceptance        | PASS           | `M2-COMPLETION-ACCEPTANCE-20260926-01`                                                        |
| Authoritative M3 screen/entity baseline     | AVAILABLE      | Build specification section 16: P3-01 through P3-16 and 14 core entities                      |
| M3 architecture/product definition          | ACCEPTED       | `M3-PRODUCT-ACCEPTANCE-20260926-01` binds exact `m3-product-definition-draft-2` bytes         |
| Product/privacy/clinical/security decisions | ACCEPTED INPUT | Conservative defaults accepted; local catalogues remain fail-closed activation gates          |
| High-fidelity clickable mockups             | APPROVED INPUT | Byte-identical approved P3-01 through P3-16 prototype; synthetic, responsive and non-mutating |
| Exact M3 input manifest/verifier            | PASS           | `APPROVED`; eight artifacts; 16 screens; 14 entities; 15 decisions; digest `3e7ced79…ed09c00` |
| Accountable M3 input approval               | PASS           | `M3-APPROVAL-20260926-01` binds exact candidate and promoted-package digests                  |
| Repository implementation                   | PASS           | M3B-M3H implemented through Flyway V78 and the 112-operation checked browser/API boundary     |

## Phase 3A candidate artifacts

The accepted product definition has been expanded into a review package with these exact artifact families:

1. P3-01 through P3-16 self-contained clickable screen mockups and all state/viewport variants.
2. Patient-registry Design System and sensitive-data presentation extension.
3. Data dictionary, classifications, validation, relationships, normalization and database invariants.
4. Registration, patient, identifier, proxy, consent/privacy, safety, duplicate and merge lifecycle matrix.
5. Authorization, field projection, assurance, maker-checker and service-identity policy.
6. Canonical audit/outbox event registry, jobs, notifications and consumer contract.
7. Duplicate/readiness/safety/registration validation and invalidation policy.
8. History, timeline, reporting, FHIR, export, retention and legal-hold policy.

The original package remains under `candidate-inputs/module-3/` as non-authorizing provenance. Its eight accepted artifacts are promoted byte-identically under `approved-inputs/module-3/`; authority comes from the approved input gate and separate approval record, not from altered internal labels.

## Dependency-ordered implementation slices

These slices were implemented in dependency order from the exact approved input package. Repository completion does not activate missing local policy catalogues or external providers.

| Slice | Screens     | Primary scope                                                                                                                | Exit before next slice                                                                                              |
| ----- | ----------- | ---------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| M3A   | All         | Product decisions, architecture, mockups, exact input manifest/verifier and approval                                         | Exact approved package; implementation gate reports authorized                                                      |
| M3B   | P3-01–P3-04 | Dashboard/directory, registration run, organization-scoped search-before-create and duplicate candidate foundation           | Forced-RLS persistence and minimum-necessary search/disposition pass database/HTTP attacks                          |
| M3C   | P3-05–P3-08 | Identity/demographics, effective contacts/addresses, communication preferences and identifiers                               | Provenance/verification, confidentiality, uniqueness, effective history and immutable correction pass               |
| M3D   | P3-09–P3-11 | Caregiver/proxy authority, consent/privacy restrictions and governed safety flags                                            | Authority/purpose/effective-time checks, independent decisions and future-use withdrawal consequences pass          |
| M3E   | P3-12–P3-13 | Cross-section validation, atomic registration and canonical patient summary/action projection                                | Duplicate disposition plus exact revision/digest produce one auditable patient result without optimistic UI success |
| M3F   | P3-14–P3-16 | Duplicate queue/leases, merge request/decision/execution and allow-listed identity/audit timeline                            | Independent serialized merge preserves lineage/attribution; purpose-bound timeline/detail passes                    |
| M3G   | All         | Jobs/notifications, configuration/version invalidation, FHIR mapping, reporting/export/retention and portal-link integration | External gaps fail closed; safe projections/artifacts/evidence and previous-module regressions pass                 |
| M3H   | All         | Full migration, security, contract, unit, browser, accessibility, responsive, worker, supply-chain and regression closeout   | Repository evidence PASS; QA, target activation and production acceptance remain separately recorded                |

## Implementation result

- M3B-M3G are complete for the approved repository scope across P3-01 through P3-16.
- Flyway V69-V78 adds the approved authorization/event release, 17 patient-registry tables, tenant/RLS integrity, one-use merge decisions, immutable identity history, effective lineage, exact validation binding and direct lifecycle/merge guards.
- The backend implements minimum-necessary dashboard/directory projections, search-first registration, identity/contact/address/preference/relationship history, exact validation/submission, duplicate leases/dispositions, independent three-party merge and a payload-free correlated timeline.
- All 16 P3 routes use the checked API client and runtime response validation; server-projected permissions, strong revisions, scoped idempotency, reasons and impact previews remain authoritative.
- Identifier issuance/display, proxy authority, consent/privacy enforcement, safety catalogues, urgent reconciliation, deceased verification, export/retention, portal linkage and FHIR remain visibly unavailable when their required local catalogue, worker or partner contract is absent. This is the approved fail-closed result, not a fabricated implementation.
- Detailed evidence and the deferred QA/activation boundary are recorded in `MODULE_3_COMPLETION_REPORT.md`.

## Frozen implementation rules

- Reuse canonical M1 identity/RBAC/governance/platform capabilities; do not create parallel users, memberships, permissions, audit/outbox, documents, notifications or exports.
- Keep patient identity separate from portal user identity and caregiver/proxy authority.
- Never auto-merge, cross-tenant match, infer proxy authority, treat preferences as consent, or treat consent as universal legal basis.
- Use organization-bound transactions, forced RLS, composite tenant references, exact operation context, strong revision/idempotency and database lifecycle guards.
- Preserve provenance, effective history, merge lineage, clinical attribution and lawful evidence; do not physically delete effective/referenced records.
- Generated OpenAPI types are the browser contract. UI visibility and client state never supply authority or lifecycle truth.
- Sensitive values stay out of routes, cursors, logs, audit/outbox, notification metadata and exports unless an approved field policy explicitly permits them.
- No real personal or clinical information is permitted in candidate assets, fixtures, tests or screenshots.

## Implemented traceability rule

Every approved action must trace:

`mockup -> route -> permission/field policy -> controller -> application use case -> authorized transaction -> database invariant -> audit/outbox event -> tests`

Every state transition must trace:

`source state -> validation/policy version -> purpose and assurance -> maker/checker/executor -> target state -> immutable evidence -> invalidation/downstream effect`

Every sensitive view/export must trace:

`purpose -> scope -> tenant/RLS -> field classification/redaction -> reason/recent-auth -> access evidence -> expiry/disposal`

## End-of-module verification

- Apply every migration from V1 on disposable PostgreSQL 18; prove schema inventory, UUIDv7 defaults, forced RLS, composite FKs, restricted grants, triggers, lifecycles, immutable evidence and direct-SQL/cross-tenant attacks.
- Backend domain/application/API/security tests for every permission, field projection, transition, duplicate/merge case, proxy/consent/privacy/safety consequence, revision/idempotency and worker retry/dead-letter path.
- Generated OpenAPI drift and strict browser runtime validation for every P3 response/action.
- Frontend unit coverage for forms, accessible server errors, filters/cursors, responsive tables/cards, comparison/approval dialogs, timeline/evidence and no optimistic governed success.
- Playwright/Axe/overflow at exact 1440, 1024, 768, 390 and 320 widths for all 16 screens and critical registration, duplicate, proxy, consent/privacy, safety, merge and export paths.
- Full M1/M2 regression, architecture/static/build, dependency/secret/image, approved-input, screen/API/event/permission registry, test-isolation and synthetic-data gates.
- Owner QA and explicit production acceptance remain required before target release. Under the user's standing direction, later repository modules may continue before that deferred QA pass.

## Immediate next work

Preserve this M3 repository checkpoint for the later QA pass and proceed to the next incomplete product module. Target deployment must continue to fail closed until local policy catalogues, worker/provider identities, hosted controls and production acceptance exist.
