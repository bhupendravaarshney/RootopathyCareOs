# CareOS Module 2 implementation plan

**Module:** M2 Workforce (`M2-01` through `M2-29`)  
**Accepted predecessor baseline:** commit `2ba6c9b3b567d0371c8523e6f18945ec33138ae4` (`M1`)  
**Input candidate:** `m2-candidate-1`  
**Candidate package SHA-256:** `2e64bd4e1ac5192a9a4783abf58f4578b8bceda60c21c2e835a910e6760d0f8f`  
**Approved package SHA-256:** `624df2edc0024526040271911d43a1b33a12e723fefb3beb3e985264cef89521`  
**Approval record:** `M2-APPROVAL-20260921-01`  
**Implementation authorization:** `true`; final implementation verification and owner acceptance remain pending

## Authority and implementation condition

On 21 September 2026 the user explicitly accepted commit `2ba6c9b` as the Module 1 baseline and later approved `m2-candidate-1` unchanged at exact digest `2e64bd4e1ac5192a9a4783abf58f4578b8bceda60c21c2e835a910e6760d0f8f`. That acceptance authorizes the exact approved Module 2 package against the named predecessor; it is not target-environment production acceptance.

The original proposal remains unchanged under `candidate-inputs/module-2/` as provenance. Its eight byte-identical approved artifacts are under `approved-inputs/module-2/`. `contracts/module-2-input-gate.json` binds all 29 screens, the exact 44-table build-specification baseline, accepted M1 commit, candidate digest, promoted-package digest, and approval record; the production verifier reports `APPROVED` and `implementationAuthorized: true`.

The approved package resolves the high-impact choices that cannot be guessed during coding: organization-scoped person matching, age boundary, credential/document lifecycle, independent verification, clinical-scope approval, supervision/eligibility, hierarchy/access scope, activation, suspension/reactivation, offboarding, expiry notifications, controlled registries, evidence projections, retention, and exports. Any replacement requires a new checksum-bound approval; implementation must not silently alter those decisions.

## Current implementation checkpoint — 22 September 2026

The implementation is not yet frozen and no current Module 2 runtime tests have been executed. The following records source present in the repository, not verified completion:

- M2A is implemented: the exact approved input package, production input gate, authorization/operation/readiness/event registries, module boundary, and migration release are present.
- V51-V65 provide the exact 44-table workforce baseline plus forced RLS, composite tenant references, operation/lifecycle guards, immutable evidence, invalidation coverage, review leases, internal export access, worker subscriptions, and internal-consumer authorization.
- M2B-M2G application code is broadly present across the workforce domain/application/infrastructure/API layers. All M2-01 through M2-29 routes now use live server projections and governed actions rather than the former generic synthetic records.
- The credential-document path includes bounded upload validation, private quarantine, fail-closed scan evidence, clean promotion, independent review, and actor/purpose-bound access. Provider-signed URLs are not persisted or placed in durable frontend state; the internal access route remints them for a no-store redirect.
- Readiness/eligibility/history invalidation, expiry and notification processing, export/retention/access lifecycle, retry/dead-letter handling, and offboarding orchestration are implemented or being finalized. Offboarding still requires final database enforcement for its canonical M1 membership/session child effects before the implementation freeze.
- The OpenAPI source and browser code now cover the live M2 boundary. Generated-client drift, compilation, frontend validation, migrations, security attacks, workers, accessibility/responsiveness, and regression behavior remain unverified until M2H.

Remaining implementation order: finish the offboarding database guards and deterministic evidence, perform a static source/contract audit, freeze M2B-M2G, regenerate any derived client artifact, then run the complete M2H suite once and repair failures before handoff.

## Frozen architecture boundaries

- Reuse the accepted M1 users, sessions, invitations, memberships, canonical RBAC, MFA/recent-authentication, audit/outbox, idempotency, configuration evidence, export, private document, scanner, promotion, signed-access, retention, and notification ports.
- Add one `workforce` backend module with `domain`, `application`, `infrastructure`, and `api` packages. It may read M1 capabilities through published application interfaces; it cannot write another module's repositories.
- Keep Person, User, Workforce Member, Engagement, Practitioner, Credential, Scope, Assignment, Service Assignment, Availability, Access, and Eligibility separate.
- No parallel user, role, permission, facility, service, document, audit, outbox, export, or notification source of truth.
- Every tenant-owned workforce table uses forced RLS and composite tenant foreign keys. Human/global person access occurs only through authorized organization links and non-browsable operations.
- Generated OpenAPI types are the browser contract. UI state never supplies authority, lifecycle truth, eligibility, readiness, or successful mutation evidence.
- Document upload remains private quarantine → validation → fail-closed scanning → clean promotion → purpose-bound access. Credential tables never store provider keys or signed URLs.

## Dependency-ordered implementation slices

| Slice | Screens | Primary scope | Exit before next slice |
| --- | --- | --- | --- |
| M2A | All | Promote exact approved input package; migration-owned permission/operation/audit/readiness/registry release; module architecture and OpenAPI skeleton | Required-approval gate passes; no candidate status remains in runtime authority. |
| M2B | M2-01–M2-06, M2-21 | Person links/match keys/merge requests, workforce member, identifier, engagement, dashboard/directory/profile foundations | Duplicate-safe clinical/non-clinical draft can be persisted/read with forced RLS and minimum projections. |
| M2C | M2-07–M2-12, M2-25 | Practitioner, qualification, registration, credential, private evidence pipeline, independent verification, expiry projections | Clean-evidence-only decision and renewal/suspension/revocation history pass direct database and HTTP attacks. |
| M2D | M2-13–M2-14 | Specialty, scope definitions/requirements, scope activities/restrictions, maker/checker decisions, eligibility invalidation | Scope approval is independent, immutable, context/effective-date aware, and cannot grant access. |
| M2E | M2-15–M2-19, M2-22 | Hierarchy assignments/transfers, practitioner service assignments, point-in-time eligibility, canonical access scopes, weekly availability, account link/invitation | Transfer and weekly save are atomic; M1 RBAC remains canonical; assignment/scope/access independence is proven. |
| M2F | M2-20–M2-24 | Pathway-specific readiness, activation, canonical profile action projection, suspension/reactivation, coordinated offboarding | Fresh result/digest plus MC activation; lifecycle impacts and offboarding preserve attribution and fail closed. |
| M2G | M2-26–M2-29 | Workforce configuration snapshots/change requests/items, controlled registries, history/audit/timeline, purpose export, legal holds/retention | Allow-listed minimum projections, immutable registry versions, safe artifacts and lifecycle evidence pass. |
| M2H | All | Full regression, security, migration, contract, unit, browser, accessibility, responsive, worker and build closeout | Evidence-based PASS/FAIL and complete screen/table/permission/event ledger. No Module 3 work. |

Tests may be authored beside each slice, but under the user's delivery instruction they are not executed incrementally. Executable testing begins only after M2B through M2G implementation is complete, then M2H runs the complete suite and fixes any failures before handoff.

## Screen traceability ledger

| Screen | Production capability | Primary tables / reused source | Permission family |
| --- | --- | --- | --- |
| M2-01 | Metrics, exceptions, permission-scoped queues | Read models over M2 sources | `workforce.dashboard.read` |
| M2-02 | Search/filter/sort/cursor directory | `workforce_members`, organization person/assignment summaries | `workforce.directory.read` |
| M2-03 | Persist pathway and access intent | `workforce_members`, onboarding evidence | `workforce.member.create` |
| M2-04 | Organization-scoped match and explicit decision | `organization_person_links`, `person_match_keys`, `person_merge_requests` | `workforce.person.match` |
| M2-05 | Identity/contact proposal and correction | person profile/alias/contact/address plus organization link | `workforce.member.manage`, restricted person permissions |
| M2-06 | Effective engagement and manager | `employment_engagements` | `workforce.engagement.*` |
| M2-07 | Practitioner profession/lifecycle | `practitioner_profiles` | `workforce.practitioner.*` |
| M2-08 | Qualification versions/decisions | `qualifications`, credential documents/verification where applicable | `credential.qualification.*` |
| M2-09 | Registration/licence renewal and lifecycle | `professional_registrations` | `credential.registration.*` |
| M2-10 | Private credential evidence upload/scan | `practitioner_credentials`, `credential_documents`, `credential_scan_attempts`, platform document evidence | `credential.document.upload` |
| M2-11 | Risk/SLA verification queue | Credential/scan/verification read projection | `credential.review.queue` |
| M2-12 | Clean evidence and independent decision | `credential_verifications`, credential/document evidence | `credential.document.read`, `credential.review.decide` |
| M2-13 | Effective primary/secondary specialty | `practitioner_specialties` | `practitioner.specialty.*` |
| M2-14 | Versioned activities/restrictions and approval | scope definitions/requirements/practice/activity/restriction tables | `practitioner.scope.*` |
| M2-15 | Organization hierarchy assignment | `workforce_assignments`, M1 facility/unit/location | `workforce.assignment.*` |
| M2-16 | Service/context assignment and eligibility | `practitioner_service_assignments`, `practitioner_eligibility_evidence`, M1 service/context | service-assignment and eligibility permissions |
| M2-17 | Canonical role/scope request | M1 access assignments plus `access_assignment_scopes` | existing M1 access permissions |
| M2-18 | Atomic weekly availability and exceptions | availability profile/period/exception tables | `workforce.availability.*` |
| M2-19 | Existing-user link or invitation | M1 user/membership/invitation plus account-link evidence | M1 invitation/access plus account-link request |
| M2-20 | Readiness, submit, independent activation | readiness run/result and activation request tables | validation/activation permissions |
| M2-21 | Canonical selected-member profile/actions | Minimum projections across member domains | `workforce.member.read` plus action-specific permissions |
| M2-22 | Atomic assignment transfer/end | `workforce_assignments`, eligibility/impact evidence | `workforce.assignment.lifecycle` |
| M2-23 | Governed suspension/reactivation | lifecycle transition plus affected domain evidence | `workforce.lifecycle.*`, clinical authority where applicable |
| M2-24 | Coordinated historical offboarding | offboarding requests, lifecycle transitions, M1 access child operations | `workforce.offboarding.*` |
| M2-25 | Mutually exclusive expiry queues/reminders | credentials/registrations, notification deliveries | `workforce.expiry.*` |
| M2-26 | Version list/detail/compare/export | configuration snapshot/change request/item tables | `workforce.history.read`, export permissions |
| M2-27 | Purpose-bound audit detail/export | M1 audit plus M2 registry/projection/export tables | `workforce.audit.read`, export permissions |
| M2-28 | Versioned allowed workforce catalogues | workforce registry definition/entry/version tables | `workforce.registry.*` |
| M2-29 | Correlated member evidence timeline | allow-listed audit/decision/lifecycle projection | `workforce.timeline.read` |

## Required end-of-module verification

- All Flyway migrations from V1 onward on disposable PostgreSQL 18, including clean install, forced RLS, composite FKs, direct-SQL guards, exclusions, immutable evidence, and schema count/traceability.
- Backend unit/integration/API/security tests for every transition, permission/scope/field projection, cross-tenant guessed ID, self-decision, freshness, concurrency, idempotency, scanner/storage failure, worker retry/dead letter, retention/hold, export, and historical point-in-time eligibility.
- Checked OpenAPI 3.1 generation/drift and strict runtime validation before server data enters UI state.
- Frontend unit tests for forms, server errors, permission actions, tables/cards, cursor recovery, dialogs/focus, evidence/readiness/timeline projections, and no optimistic governed success.
- Playwright/Axe at 1440, 1024, 768, 390, and 320 for every registered M2 route plus all critical onboarding, credential, scope, assignment, activation, lifecycle, audit/export paths; no document/body overflow.
- ArchUnit/frontend boundary, formatting, strict typecheck, lint, production build, dependency/security, secret/path, candidate/approved input, screen register, API-operation, audit/permission registry, and complete M1 regression checks.
- Test isolation must use only disposable `careos_test`; development state must be proven unchanged. No real personal, employment, credential, document, or clinical information is permitted.

## Approval and exit boundary

M2 is complete only when the exact input package is approved, every visible production action is server-authorized/persisted/audited, all 44 tables and 29 screens are traceable, all M2H gates pass without skipped critical behavior, and the owner accepts the evidence. Target provider/worker/deployment/monitoring/backup acceptance remains separate. Do not commit/tag, freeze, or begin Module 3 without a distinct instruction.
