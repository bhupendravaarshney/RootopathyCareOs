# Module 1 administration implementation plan

**Review date:** 21 September 2026
**Status:** Exact `m1-candidate-1` inputs approved unchanged; production input gate and organization-wide M1-01 through M1-23 repository implementation/verification complete through Flyway V50 and OpenAPI 0.40.0; additive facility scope, target operations, and M1G owner acceptance remain open

## Authority and implementation condition

The authoritative build specification requires approved M1-01 through M1-23 high-fidelity mockups, CareOS Design System 1.0 tokens/assets, and approved policy decisions before Module 1 implementation. Those inputs are now present under `approved-inputs/module-1/`. Approval record `M1-APPROVAL-20260916-01` records that **bhupendra, developer** approved `m1-candidate-1` unchanged on 16 September 2026 and binds all 23 screens, the exact eight-artifact package digest `19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946`, and separate checksum-matched approval evidence.

The machine-readable gate reports `APPROVED`, eight of eight artifacts, and `implementationAuthorized: true`; both normal and required-approval verification pass. Flyway V20 records the checksum-bound authorization release, V21-V40 implement the organization-wide identity/access, organization, facility, hierarchy, and location boundaries, and V41-V50 add atomic hours, services, assignments, identifier schemes, maker-checker configuration activation, history/audit/export projections, facility lifecycle, authoritative configuration invalidation, and the complete export lifecycle. The missing facility-scope details have additive candidate `m1-facility-scope-candidate-1` at digest `76a3f7a2c63cef0cab02b8a1d38a66220eee0abb99c9be0a82b64eaa8941fddb`; its verifier explicitly reports `implementationAuthorized: false` until separately approved. Existing approval covers only the organization-wide decisions and does not provide target-environment or owner acceptance.

## Source review

Available and reviewed:

- the complete 46-page Java/React build specification, especially pages 2, 6, 8-14, 28-38, and 40;
- all 23 M1 screen registrations and the generic dashboard/list/form prototypes;
- the Phase 0 identity, tenancy, authorization, governance, OpenAPI, generated-client, frontend-session, RLS, idempotency, audit, outbox, and test boundaries;
- the provisional `careos-phase0-reference-v1` registry and its production activation guard;
- the current implementation and gap ledgers.
- the eight exact approved `m1-candidate-1` artifacts and approval record `M1-APPROVAL-20260916-01`;
- the V20-V50 authorization, identity/access, organization, network, service, identifier, activation, evidence, and export implementation plus its checked M1-01 through M1-23 browser surface.

Still external or not supplied:

- target-environment security, provider, deployment, monitoring, backup/restore, and operational acceptance evidence;
- Module 2 and later-module approved designs/policies;
- original protected COS source assets and required clinical-governance approval.

## Executable input-package contract

`contracts/module-1-input-gate.schema.json` publishes the package shape, and `contracts/module-1-input-gate.json` records the current repository state. It is `APPROVED`, lists all eight checksum-matched files below `approved-inputs/module-1/`, and binds the approval record and evidence.

Each supplied bundle must be a regular file below `approved-inputs/module-1/` and have one canonical category, bounded version, repository-relative path, and lowercase SHA-256 digest:

1. screen mockups;
2. Design System;
3. data dictionary and validation;
4. lifecycle and transition matrix;
5. authorization policy;
6. audit and event registry;
7. readiness and activation policy;
8. history and export policy.

Run `node scripts/verify-module-1-inputs.mjs --require-approved` before Module 1 implementation and release work. It validates all eight bytes, the deterministic package digest, accountable identity and decision time, ordered M1-01 through M1-23 scope, and separate approval evidence. Any artifact drift invalidates the current approval and must remain blocked until a new approval record binds the replacement package.

The verifier accepts only regular non-symbolic evidence files. Fourteen focused tests cover partial delivery, path escape, missing input, checksum mismatch, duplicate categories, incomplete false approval, scope drift, package/evidence-digest drift, reused evidence, placeholder/invalid/future approval, full approval, and schema alignment. The quality workflow runs both the verifier and tests, while the repository security contract rejects removal of either command.

## Owner-review draft packet

`docs/module-1-review-drafts/` provides a concrete, source-grounded starting point for the eight decisions above. Its index explains the handoff, and `contracts/module-1-review-drafts.json` lists the exact review files. The packet includes:

1. screen/state and responsive mockup decisions for M1-01 through M1-23;
2. Design System tokens, components, content, accessibility, and breakpoint decisions;
3. entity, field, invariant, and validation decisions;
4. lifecycle, transition, reason, approval, concurrency, and effective-date decisions;
5. role, permission, scope, denial, recent-authentication, MFA, delegation, and maker-checker decisions;
6. audit/outbox event naming, versioning, payload, redaction, retention, and consumer decisions;
7. readiness/activation gates, evidence, freshness, override, and independent-approval decisions; and
8. configuration history, audit access, export purpose, authorization, redaction, safety, and retention decisions.

Every brief is marked `DRAFT_NOT_APPROVED`, separates a proposed review baseline from owner decisions and acceptance criteria, and remains outside the production evidence directory. `node scripts/verify-module-1-review-drafts.mjs` checks all eight files and every M1 screen while always reporting `implementationAuthorized: false`; its seven tests cover completeness, status misuse, ordering, path isolation, missing files, document structure, and screen coverage. The quality and repository-security gates require both commands.

The draft packet remains non-authorizing historical review material. The approved package supersedes it for implementation; changes to a draft do not alter the approved inputs.

## Concrete candidate input package

`candidate-inputs/module-1/` converts the review baseline into one consistent versioned proposal:

- `01-screen-mockups.html` is a self-contained interactive review application for all 23 screens and every required width, with representative loading/empty/no-result/denied/conflict/error/success/session-expired states;
- `02-design-system.md` freezes proposed tokens, component contracts, accessibility, content, browser, and responsive rules;
- `03-data-dictionary-and-validation.md` defines proposed fields, bounds, classifications, tenant/revision/effective-time conventions, and stable validation families;
- `04-lifecycle-and-transition-matrix.md` fixes candidate states, allowed transitions, assurance, concurrency, evidence, scheduling, and compensation;
- `05-authorization-policy.md` defines proposed roles, exact permission families, grant/delegation, denial, MFA/recent-authentication, maker-checker, final-owner, worker, and emergency boundaries;
- `06-audit-and-event-registry.md` fixes candidate event names, versions, payload keys, retention, and internal consumer subscriptions;
- `07-readiness-and-activation-policy.md` defines deterministic gates, outcomes, freshness/invalidation, decision sequence, and no-override baseline; and
- `08-history-and-export-policy.md` defines history/audit projections, filters/cursors, purposes, formats, limits, content safety, retention, and worker behavior.

`contracts/module-1-candidate-inputs.json` and `node scripts/verify-module-1-candidate-inputs.mjs` bind those files. The verifier reports digest `c2087548aacd35eb4927532d63b844851c6fe9c56cccb56e46596d707d7a1a53` and always returns `implementationAuthorized: false`. Eight contract tests plus five exact-viewport Playwright/Axe/overflow cases protect the package and its offline/non-persistent visual artifact.

The candidate directory remains non-authorizing provenance. Its exact bytes were accepted unchanged and copied to `approved-inputs/module-1/`; the production manifest and `M1-APPROVAL-20260916-01`, not the candidate label or verifier, carry implementation authority.

## Existing implementation boundary

M1-01 through M1-04 exercise persisted login/recovery, governed invitation acceptance and administration, MFA self-service/administrative reset, and organization selection. V20 promotes the interactive registry and implemented invitation/MFA event bindings to the approved release. Invitation issue/revoke and all administrative-reset stages now require both recent primary authentication and a separately recorded recent MFA assertion; production activation is allowed only with the exact approved registry/package digest and remains disabled by default. Their shared identity and workspace frames use the approved font stack, hash-safe keyboard skip navigation, and route-heading focus with problem-summary precedence. Local identity validation now focuses a durable summary, associates and styles the exact invalid field, and offers hash-safe field navigation; approved registry metadata and primary action content, including `Set up authenticator`, are aligned for M1-01 through M1-04.

M1-05 through M1-11 use a bounded `administration` feature backed by protected tenant APIs. M1-05/M1-06 consume all 15 exact ordered `m1-readiness-v1` live gates, approved outcomes, database-owned freshness, bounded evidence references, and permission-safe deep links. M1-07 through M1-11 implement the approved profile, registration identifiers, effective addresses/masked contacts, international settings, and governance responsibilities. V48 invalidates configuration evidence when their authoritative state changes.

M1-12 through M1-19 now use live governed facilities, hierarchy, locations, atomic operating hours, services, assignments, and identifier-scheme drafts/versions. Database and application constraints enforce hierarchy/lifecycle ordering, current eligibility, overlap/range rules, timezone gaps/ambiguities, immutable scheme versions, and readiness integration. Identifier version activation/retirement is performed only by the M1-21 maker-checker configuration workflow.

M1-20 now uses the same bounded `administration` feature for authorized membership list/read, governed organization-wide non-owner role changes/revocation, and final-owner-safe owner promotion/demotion. The endpoints and UI provide normalized server search, allow-listed state/role filters, signed cursor paging, runtime response validation, strong revision preconditions, request/independent-approval/execution states, and live server-projected invitation/MFA-reset/change/owner-transfer actions. Its approved responsive result contract is implemented as a keyboard-focusable table at 768px and wider and equivalent semantic record cards at 390/320px, with one shared action component. Additive candidate `m1-facility-scope-candidate-1` supplies an exact facility-grant contract, but facility-scoped grants remain deliberately unavailable until that candidate is separately approved.

M1-21 through M1-23 now provide exact-digest validation/submission/independent decision/activation, configuration history, purpose-bound audited detail, and request/decision/access export flows. Export jobs use request-time repeatable snapshots and policy digests; the browser polls only active states with bounded backoff. Artifact processing/retention services are implemented, while production worker identity, deployment, and scheduling remain an operational acceptance item.

## Screen traceability ledger

| Screen                         | Required behavior from specification                           | Current evidence                                                                                                                                                                                                                                                         | Implementation prerequisite                                                                                |
| ------------------------------ | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| M1-01 Login                    | Authentication, throttling, recovery, safe redirect            | Persisted server flow, approved shared frame/content, focused malformed-token/local-validation summaries, and exact invalid-field association exist                                                                                                                      | Complete target identity and owner acceptance                                                              |
| M1-02 Invitation               | One-use token, identity confirmation, password/account linkage | Approved-registry issue/revoke/accept flow, approved shared frame/content, and focused malformed-token/password-validation states exist                                                                                                                                  | Add inspection/resend/delivery behavior only under an exact authorized contract; complete owner acceptance |
| M1-03 MFA                      | TOTP enrollment/challenge, recovery codes, verified state      | Self-service, mandatory-role enrollment/use, approved-registry maker-checker reset, focused local-preparation failures, and exact `Set up authenticator` content exist                                                                                                   | Complete visual/owner acceptance; separately approve optional viewer/editor organization policy            |
| M1-04 Organization selector    | Select only an authorized organization                         | Live/effective membership selection plus approved frame, heading focus, metadata, `Open workspace`, loading, error, and no-access states exist                                                                                                                           | Complete target-environment and owner acceptance                                                           |
| M1-05 Admin dashboard          | Readiness, counts, exceptions, lifecycle status                | Exact-catalogue live server metrics, freshness, lifecycle/facility counts, configuration/service evaluators, and five permission-safe priority exceptions                                                                                                                | Complete target-environment and owner acceptance                                                           |
| M1-06 Setup checklist          | Server-calculated gates and deep links                         | All 15 ordered approved live gates, outcomes, reason/remediation codes, bounded evidence, freshness, permission-safe links, and M1-21 validation/invalidation integration                                                                                                | Complete target dependency evidence and owner acceptance                                                   |
| M1-07 Organization profile     | Legal/display identity and governance status                   | Exact approved identity/type/country/timezone/locale projection and governed update; permission-projected read-only mode; strong revision/idempotency/reason/evidence; live profile-complete evaluator and configuration invalidation                                      | Complete final owner/target acceptance                                                                      |
| M1-08 Registration/identifiers | Registration numbers and schemes                               | Governed type registry/list/draft edit/verification/revocation/supersession; exact normalization, jurisdiction/range/uniqueness/primary/history constraints; strong dual revisions, evidence, responsive UI, live readiness, and configuration invalidation                  | Complete final owner/target acceptance and separately govern scheduled expiry automation                   |
| M1-09 Addresses/contacts       | Effective contact and address records                          | Governed list/create/verify/end/supersede; exact address types, channels, purpose, validation/verification, effective ranges, primary/preferred overlap, confidential masked projection, immutable history, strong revisions, evidence, responsive UI, and live coverage readiness | Complete final owner/target acceptance and deliver separately governed scheduled transition automation     |
| M1-10 International settings   | Country, timezone, locale, language, currency, and week start | V28 immutable effective history, organization-derived baseline, one future version, locale-library previews, impact rules, governed API/UI/evidence, and configuration invalidation                                                                                     | Complete final owner/target acceptance                                                                      |
| M1-11 Governance contacts      | Clinical, privacy, security, billing contacts                  | V29 immutable effective responsibilities, eligible membership/contact linkage, masked escalation projection, gap-free supersession, governed API/UI/evidence, live four-type readiness, and configuration invalidation                                                  | Complete final owner/target acceptance                                                                      |
| M1-12 Facilities               | Server list/filter and one add action                          | Approved draft persistence/evidence, filtered directory/create/edit/submit, live eligibility readiness, checked client, responsive cards, and permission-projected controls                                                                                            | Complete final owner/target acceptance                                                                      |
| M1-13 Facility wizard          | Draft facility, hierarchy, address, activation                 | Live high-assurance activation/suspension/reactivation/closure with address, hierarchy, hours, assignment-impact, assurance, revision, idempotency, and evidence checks                                                                                                  | Complete final owner/target acceptance                                                                      |
| M1-14 Departments/units        | Tenant/facility hierarchy and effective lifecycle              | Live forced-RLS hierarchy create/edit/reparent and parent-first/descendant-first lifecycle with immutable parent history, depth/cycle controls, readiness, checked UI, and attack coverage                                                                               | Complete final owner/target acceptance                                                                      |
| M1-15 Locations                | Facility-bound physical/virtual locations                      | Live physical/virtual location create/edit/reparent/lifecycle with governed address/unit selectors, hierarchy rules, capacity, revisions, evidence, readiness, and checked UI                                                                                           | Complete final owner/target acceptance                                                                      |
| M1-16 Operating hours          | Weekly/holiday/overnight intervals in one batch                | Live atomic weekly/exception batches with timezone, DST gap/ambiguity, cyclic overlap, range, lifecycle, readiness, revision, evidence, and checked UI contracts                                                                                                         | Complete final owner/target acceptance                                                                      |
| M1-17 Service catalogue        | Governed service definitions                                   | Live definition draft/update/lifecycle with code/owner/coding constraints, readiness, permission projection, evidence, and checked UI                                                                                                                                   | Complete final owner/target acceptance                                                                      |
| M1-18 Facility services        | Effective service assignment by facility/location              | Live effective assignments with parent eligibility, overlap, range, capacity/availability/prerequisite, lifecycle, readiness, impact, evidence, and checked UI                                                                                                          | Complete final owner/target acceptance                                                                      |
| M1-19 Identifier schemes       | Versioned prefix/pattern/sequence rules                        | Live stable schemes and immutable draft versions with safe pattern/previews, scope, sequence rules, single-active enforcement, readiness, and M1-21-only maker-checker lifecycle                                                                                        | Complete final owner/target acceptance                                                                      |
| M1-20 Administrator access     | Invite, scope, lifecycle, final-owner guard                    | Authorized list/read, filters/cursors, live action projection, governed organization-wide non-owner role-change/revocation, final-owner-safe owner-transfer workflows, approved wide-table/drawer-card projection, and a checked non-authorizing facility-scope contract | Approve and implement the facility-scope candidate, then obtain explicit owner acceptance                  |
| M1-21 Review/activate          | Typed validation run and independent activation                | Live immutable 15-minute results, exact baseline/result digests, submit, independent 30-minute decision, atomic activation, invalidation, prior-version history, and permission-projected controls                                                                       | Complete final owner/target acceptance                                                                      |
| M1-22 Configuration history    | Filter, compare, cursor page, purpose export                   | Live allow-listed signed-cursor history, semantic compare, request-time snapshot exports, restricted maker-checker decision, bounded polling, access, expiry, and disposal mechanics                                                                                    | Activate accepted production worker operations and complete owner/target acceptance                        |
| M1-23 Audit log                | Server filters, detail, purpose-bound export                   | Live minimum-necessary audit filters, risk/redaction, MFA/recent-auth purpose-bound detail, request-time snapshot exports, restricted decision, short-lived access, retention, and checked UI                                                                            | Activate accepted production worker operations and complete owner/target acceptance                        |

## Proposed Module 1 architecture

This architecture applies the already-enforced modular-monolith rules and the approved Module 1 contracts. A catalogue entry is still only a specification until its persisted vertical slice and tests exist.

### Backend boundary

The existing `com.rootopathy.careos.administration` module establishes `domain`, `application`, `infrastructure`, and `api` packages for bounded profile/readiness and membership-read behavior. It will grow to own the approved organization configuration, network hierarchy, service configuration, identifier schemes, readiness, activation, and configuration-history behavior. It reuses identity, tenancy, and governance application contracts rather than directly writing identity, membership, role, permission, audit, or outbox tables.

Every protected use case must:

1. enter through `/api/v1/organizations/{organizationId}/...`;
2. name one approved operation and purpose;
3. execute inside `TenantAuthorizationOperations`;
4. use forced-RLS persistence and composite tenant foreign keys;
5. apply revision/ETag and idempotency rules appropriate to the operation;
6. commit business state, audit evidence, outbox evidence, and idempotency outcome atomically where a governed mutation occurs;
7. return only checked OpenAPI responses and RFC 9457 failures.

### Data boundary

The approved schema must extend the existing `organizations` aggregate rather than create a parallel tenant record. Candidate entity families from the specification are:

- organization profiles, identifiers, addresses, contacts, international settings, and governance contacts;
- facilities, departments, locations, operating hours, and holiday exceptions;
- services plus effective facility/location service assignments;
- identifier schemes, immutable scheme versions, sequences, and issued identifiers;
- configuration versions, change requests/items, activation runs/results, and export jobs.

Every tenant-owned table requires a non-null `organization_id`, UUIDv7 identifier, composite tenant links, explicit grants and forced RLS, actor/timestamp/revision columns appropriate to mutability, constrained lifecycle state, and indexes derived from approved access paths. Historical/effective evidence must not be cascade-deleted. Effective ranges, hierarchy containment, final-owner rules, activation approval, and other invariants must be defended in PostgreSQL as well as application code.

One logical configuration change creates one parent configuration version. In particular, an operating-hours weekly batch cannot create one parent version per interval or weekday.

### Frontend boundary

The bounded `administration` feature is composed by the root without cross-feature imports. Its organization-keyed state drives M1-05 through M1-23 and is discarded on organization change. Runtime validators reject malformed or wrong-tenant profile, identifier, contact, facility, hierarchy, location, hours, service, assignment, scheme, activation, history, audit, and export projections before they enter UI state. Only server-projected actions are exposed; all mutations retain checked CSRF, idempotency, strong revision, reason, and assurance behavior. The shared identity/workspace frame for M1-01 through M1-04 retains hash-safe skip navigation, route-heading focus, and problem-summary precedence. Shared components contain no administration rules.

The production UI must replace generic templates with screen-specific loading, empty, no-result, validation, denied/hidden, stale/conflict, dependency-failure, and success behavior. The router supplies a focused, non-reflective not-found boundary for unknown authenticated hashes. The current shell and every registered route are checked for containment and serious Axe findings at exact 1440, 1024, 768, 390, and 320 pixel widths. Each delivered screen must now be traced to the approved table/card alternative, server pagination/filtering, strong ETag conflicts, reason/recent-authentication/MFA/approval interactions, accessible error focus, complete keyboard/dialog behavior, and responsive layout.

### Verification boundary

Each vertical slice requires:

- domain and application unit tests;
- fresh Flyway and catalog verification on PostgreSQL 18;
- forced-RLS, missing-context, direct-SQL, cross-tenant ID/link, constraint, trigger, optimistic-lock, and idempotency attacks;
- HTTP authorization, validation, correlation, Problem, ETag, retry, and event-evidence tests;
- checked OpenAPI and generated-client drift tests;
- frontend unit tests for every state and action;
- Playwright keyboard, responsive, and Axe coverage;
- careos_test isolation and full Phase 0 regression gates.

## Delivery slices

| Slice | Scope                                                              | Exit condition                                                                                          |
| ----- | ------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------- |
| M1A   | Architecture, source/mockup audit, traceability, input gate        | This document and roadmap/gap reconciliation are complete                                               |
| M1B   | Production identity/access acceptance for M1-01 to M1-04 and M1-20 | Approved identity/RBAC registry and final designs are implemented and attack-tested                     |
| M1C   | Organization core for M1-05 to M1-11                               | Profile/configuration persistence, readiness projections, APIs, UI states, and governance evidence pass |
| M1D   | Network configuration for M1-12 to M1-16                           | Facility hierarchy, locations, atomic operating hours, lifecycle, APIs, UI, and attacks pass            |
| M1E   | Services and identifiers for M1-17 to M1-19                        | Versioned service/assignment/identifier behavior and concurrency tests pass                             |
| M1F   | Activation and evidence for M1-21 to M1-23                         | Fresh typed validation, maker-checker activation, history/audit/export, and purpose controls pass       |
| M1G   | Module acceptance                                                  | All 23 screens trace to approved API/permission/event/tests; full gates and owner acceptance pass       |

Base input approval and organization-wide M1C-M1F implementation are complete through V50. The final local verification passes 205 backend tests across 28 suites, 72 frontend unit tests, 110 five-viewport browser/Axe/overflow cases, the 100-operation OpenAPI and generated-client drift gates, static/build/package gates, and every repository input/security verifier. M1B remains open for the separately approved facility-scope extension, separately authorized invitation inspection/resend/delivery behavior, explicit owner acceptance, and target-environment evidence. M1G remains open for production worker/provider operations and final owner/module acceptance.

## Complete organization-wide verification checkpoint

The 21 September 2026 Phase 1BQ pass validates all 50 migrations on disposable PostgreSQL 18 with Redis/object-store dependencies, packaging, strict frontend response contracts, five exact viewports, accessibility/overflow, and the full Phase 0 regression suite. It also closes DST gap/ambiguity handling, configuration invalidation/readiness, request-time export snapshot consistency, policy digest binding, artifact rollback, requester-only access, bounded polling, and OpenAPI 3.1 nullability drift. This is repository evidence, not hosted target-environment or owner acceptance.

## Provisional organization-core reference checkpoint

The reference checkpoint supplies:

- tenant-authorized profile/readiness reads and a governed profile update under `/api/v1/organizations/{organizationId}/...`;
- a V18 database trigger that limits runtime organization updates to the exact reference operation, actor, tenant, reason, mutable field set, and monotonic revision;
- strong ETag/`If-Match`, explicit idempotency and reason, exact replay, stale-write rejection, and one atomic audit/outbox pair;
- server-calculated readiness gates and bounded counts rather than browser-authored completion state;
- real M1-05, M1-06, and M1-07 loading, failure/retry, stale-conflict, validation, and success states through the checked client;
- direct PostgreSQL boundary attacks, HTTP integration coverage, frontend unit coverage, and desktop/mobile Playwright/Axe coverage.

Verification is clean at 165 backend tests, 45 frontend unit tests, and 65 five-width browser/Axe tests. Flyway reaches V19 on fresh PostgreSQL 18; local/test retains the isolated tenant fixture, while a dedicated default/production-mode test proves changed-data rollback and zero-tenant cleanup. The checked OpenAPI 3.1 contract contains 24 operations, generated client drift and both frontend-feature architecture checks pass, and both Compose models resolve. The frontend total includes strict screen-registry, unknown-route, navigation-mode, honest synthetic action/filter/pagination behavior, keyboard-focusable table scrolling, and all-route horizontal-overflow coverage; backend behavior remains at the Phase 1U baseline.

This checkpoint was the implementation baseline before approval. V20 promotes the implemented profile/readiness bindings to the checksum-approved release. Phase 1AK replaces the provisional gate shape with the exact approved live catalogue, but the underlying unimplemented M1C evaluators and fields remain work rather than approval blockers.

## Exact live-readiness catalogue checkpoint

The first M1C increment supplies:

- one canonical 15-key `m1-readiness-v1` order with the approved four-outcome vocabulary;
- database-owned evaluation and 15-minute expiry instants, organization revision, exact outcome counts, stable reason/remediation codes, and bounded aggregate evidence references;
- truthful fail-closed projection: only final-owner and mandatory-role-MFA can complete until the other authoritative entity families/evaluators exist;
- caller-permission-filtered deep links, with M1-06 as the safe fallback rather than disclosure of an unreadable target;
- distinct M1-05 dashboard metrics/priority exceptions and M1-06 full-catalogue/freshness presentation; and
- OpenAPI/generated-client/runtime-validator/domain/HTTP/unit/five-viewport contract enforcement.

This checkpoint is not a completed `configuration_validation`. It intentionally has no configuration ID/revision/result digest, immutable result row, invalidation consumer, submit decision, approval, or activation action. Those fields must come from the approved configuration-version/validation/activation model rather than being synthesized from the organization-profile revision.

## Exact organization-profile checkpoint

The second M1C increment supplies:

- exact approved legal/display/trading-name fields, organization type, ISO country, IANA timezone, BCP 47 locale, lifecycle projection, revision, and updated time;
- NFC/trim/length/markup/control validation, stable field-specific RFC 9457 errors, strong ETag concurrency, caller idempotency, and explicit reasons;
- V25 persistence constraints and runtime triggers that restrict updates to the approved mutable fields and preserve revision/evidence integrity;
- caller-permission-projected editability, including a usable read-only M1-07 state;
- exact sorted `changedFields` plus `lockVersion` audit/outbox evidence; and
- a live `organization.profile.complete` evaluator that remains blocked for invalid, closed, or legacy-incomplete profiles and completes after a valid governed update.

This closes the exact M1-07 vertical slice, not M1C. The checkpoints below resolve registration identifiers and effective addresses/contacts; international change impact, governance responsibilities, configuration-version invalidation, immutable validation, activation, history, and export remain separate approved slices.

## Exact registration-identifier checkpoint

The third M1C increment supplies:

- Flyway V26 migration-owned identifier-type metadata, forced-RLS tenant records, UUIDv7 defaults, exact status/range/normalization constraints, non-revoked uniqueness, primary-range exclusion, immutable history, and database-guarded transitions;
- exact `organization.identifier.read/manage/verify` bindings and `created`, `updated`, `verified`, `revoked`, and `superseded` audit/outbox event pairs, with MFA and recent authentication for verification;
- six checked operations for list, draft create/edit, verification, revocation, and atomic supersession, including exact idempotent replay, strong predecessor/replacement revisions, safe Problems, and minimum-necessary evidence projection;
- an atomic same-type verified replacement flow that promotes a replacement to primary when necessary, persists `supersedesId`, and cannot strand a required current primary;
- responsive server-backed M1-08 cards and forms with strict runtime validation and only live server-projected actions; and
- the live `organization.identifier.primary_verified` evaluator with bounded required/current counts and approved blocked/complete behavior.

This closes the bounded M1-08 screen slice, not configuration activation or M1C. Identifier `verified -> active` remains coupled to the approved configuration workflow, and scheduled expiry must be delivered with its allow-listed worker/evidence boundary. The M1-09 checkpoint below resolves effective addresses/contacts; M1-10/M1-11, configuration invalidation, final owner/visual acceptance, and target-environment evidence remain.

## Exact effective-address and masked-contact checkpoint

The fourth M1C increment supplies:

- Flyway V27 migration-owned `operational` purpose metadata plus forced-RLS address/contact records with UUIDv7 defaults, exact type/channel/value/range constraints, current-value uniqueness, primary/preferred overlap exclusion, immutable history, and one-replacement lineage;
- exact `organization.contact.read/manage` bindings and safe `organization.address.changed`/`organization.contact.changed` audit/outbox pairs containing only record ID, change type, effective start, and lock version;
- eight checked operations for directory read, address create/supersede/end, and contact create/verify/supersede/end with scoped idempotency, strong predecessor revisions, safe Problems, masking, and current-permission action projection;
- responsive server-backed M1-09 cards/forms that never project or prefill a raw contact value and fail closed if one appears in a response;
- atomic same-type or same-channel/purpose replacement flows that preserve designation and predecessor history; and
- the live `organization.contact.coverage` evaluator with current registered-address and verified-primary-operational-contact evidence, including draft warning versus activation blocking behavior.

This closes the bounded M1-09 screen and live evaluator, not M1C. Scheduled activation/expiry requires separately governed worker/evidence delivery; M1-10/M1-11, configuration invalidation, final owner/visual acceptance, and target-environment evidence remain.

The owner-review packet remains 8/8 non-authorizing drafts. The independent production package is now 8/8 approved artifacts; these remain separate provenance and authority gates.

## Approval gate

Module 1 production coding can start only when all of the following are versioned and supplied and `node scripts/verify-module-1-inputs.mjs --require-approved` passes:

- [x] Approved M1-01 through M1-23 desktop and responsive mockups, including every state and interaction.
- [x] CareOS Design System 1.0 tokens, assets, components, content rules, and breakpoint behavior.
- [x] Approved organization/network/service/identifier data dictionary and validation catalogue.
- [x] Approved lifecycle and transition matrix for organization, facility, hierarchy, service assignment, identifier scheme, and configuration activation.
- [x] Approved role, permission, operation-risk, scope/delegation, denial, reason, recent-authentication, MFA, and maker-checker registry.
- [x] Approved audit and outbox event names, versions, required/allowed payload keys, and retention/redaction rules.
- [x] Approved readiness/activation gate catalogue, freshness window, override rules, and independent-approval policy.
- [x] Approved purpose-bound configuration-history/audit export policy and artifact retention controls.
- [x] Approval record naming the exact version/checksum of every supplied artifact.

This gate passes, and the organization-wide repository implementation/verification is complete. The unapproved facility-scope extension, production worker/provider and target-environment acceptance, and final owner/module acceptance remain independently required.
