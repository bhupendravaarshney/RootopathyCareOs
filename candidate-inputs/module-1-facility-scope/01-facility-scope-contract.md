# Module 1 facility-scoped membership contract

**Artifact kind:** `facility-scope-contract`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m1-facility-scope-candidate-1`  
**Approval:** Not granted

## Decision baseline

This is an additive decision package for M1B and does not replace or mutate the approved `m1-candidate-1` package. It is based on approval record `M1-APPROVAL-20260916-01` and approved package SHA-256 `19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946`.

The approved base says that facility-scoped grants may narrow `configuration_editor` and `organization_viewer` access, cannot broaden a permission, and do not apply to owners, approval, audit, exports, schemes, or organization-wide settings. This candidate makes the missing record, lifecycle, mutation, enforcement, evidence, API, and verification choices exact. The choices below form one indivisible approval unit.

Every membership has exactly one explicit scope mode:

- `organization` means the role's existing organization-wide grants are evaluated normally and the membership has no current facility assignments.
- `facilities` means the role's existing grants are intersected with a non-empty set of 1 to 100 current facility assignments. It never adds a role grant or permission.
- Only active `configuration_editor` and `organization_viewer` memberships may use `facilities`. Every other role must use `organization`.
- Existing memberships migrate to `organization`; absence of assignment rows is never interpreted as facility scope.
- Scope changes are immediate atomic full-set replacements. Scheduled or incremental add/remove scope mutations are not part of this candidate.

Implementation is not authorized by this candidate. A separate approval record must bind the exact verifier-produced candidate package digest before any production migration, endpoint, action, or enforcement behavior is added.

## Exact data contract

### Membership scope

`organization_memberships` gains `scope_mode varchar(20) NOT NULL DEFAULT 'organization'`, constrained to `organization` or `facilities`. A scope execution updates the membership once, increments `lock_version` by exactly one, and records the existing `updated_at` and `updated_by` evidence even when the old and new modes are both `facilities`.

The database enforces, at transaction end, all of these current-state invariants:

1. `organization` has zero open `access_assignments`.
2. `facilities` belongs only to an active, currently effective `configuration_editor` or `organization_viewer` membership and has 1 to 100 distinct open assignments.
3. Every open assignment references a non-`closed` facility in the same organization.
4. A facility cannot enter `closed` while an open assignment references it. Suspension does not delete or widen a scope; the underlying resource operation still enforces its approved lifecycle rule.

### Access assignments

`access_assignments` is the tenant-owned temporal scope ledger with these exact fields:

| Field | Contract |
| --- | --- |
| `id` | UUIDv7 primary key. |
| `organization_id` | Required tenant UUID. |
| `membership_id` | Required membership UUID; composite same-tenant foreign key. |
| `facility_id` | Required facility UUID; composite same-tenant foreign key. |
| `effective_from` | Required finite server timestamp shared by the whole execution. |
| `effective_to` | Nullable finite timestamp; when present it is strictly after `effective_from`. |
| `granted_by_user_id` | Required maker UUID. |
| `grant_approval_id` | Required consumed approval UUID that opened the row. |
| `ended_by_user_id` | Nullable actor UUID, present exactly when `effective_to` is present. |
| `end_approval_id` | Nullable consumed approval UUID, present exactly when `effective_to` is present. |
| `created_at` | Required finite server timestamp. |

There is at most one open assignment for `(organization_id, membership_id, facility_id)`. Rows are never deleted. An open row may change only once to set its three end fields; every other field is immutable. Full-set replacement first ends all open rows at one server timestamp and then creates a fresh row for every requested facility at that same timestamp. Forced RLS, composite tenant foreign keys, trigger-bound operation context, revoked public access, and least-privilege runtime grants are mandatory.

### Immutable scope request

The existing `membership_change_requests` aggregate adds `scope_change` to `change_type` plus nullable `from_scope_mode`, `to_scope_mode`, `from_scope_sha256`, and `to_scope_sha256`. Those four fields are required only for `scope_change`; `from_role_key` and `to_role_key` are both the unchanged eligible role. `membership_scope_change_facilities` is an immutable, forced-RLS child keyed by request and facility with `organization_id`, `request_id`, `membership_id`, `facility_id`, and a unique 1-based `ordinal` from 1 through 100. It contains exactly the requested target set when `to_scope_mode=facilities` and zero rows when `to_scope_mode=organization`.

The canonical scope bytes are UTF-8 `scope_mode`, line feed, then each lowercase canonical facility UUID in ascending bytewise order followed by a line feed. The `organization` form is therefore exactly `organization\n`. Both digests are lowercase SHA-256 hex. The request trigger recomputes the current digest and target digest; execution recomputes both again and fails closed on any difference.

Request rows and child rows are immutable and retained. Scope history is reconstructed from those rows plus ended `access_assignments`; raw facility identifiers are not copied into audit or outbox payloads.

## Authorization and transition contract

### Scope intersection

Authorization remains deny-by-default. Role grants, operation policy, current membership, tenant, assurance, lifecycle, and facility scope must all allow an operation. Scope is derived from persisted target relationships inside the tenant transaction, never from a browser-selected facility or unverified request field.

For a `facilities` membership, the only scope-eligible permission families are:

- `network.facility.read/manage` for an assigned facility;
- `network.structure.read/manage` and `network.hours.read/manage` for records whose persisted `facility_id` is assigned; and
- `service.assignment.read/manage` for an assignment whose persisted facility is assigned.

Every organization profile/identifier/contact/settings/governance operation, `service.catalog.*`, `identifier.scheme.*`, access administration, configuration readiness/validation/submission/approval/activation, evidence history/audit/export, and every approval or lifecycle operation is denied to a `facilities` membership. A minimum organization label already projected by the authenticated shell is not authority to call an organization-wide operation. A resource without one authoritative persisted facility relationship is out of scope. Lists and counts apply the assignment intersection in SQL; a caller never receives rows and then relies on client filtering.

The underlying approved role still controls read versus manage. A scope cannot make a viewer an editor, add lifecycle authority, or affect `organization_owner`, `organization_administrator`, `configuration_approver`, `security_administrator`, `auditor`, or `export_approver`.

### Governed scope change

Scope changes reuse the existing operations and permissions without adding grants:

- request: `access.membership.change.request` / `access.membership.manage`;
- independent decision: `access.membership.change.approve` / `access.membership.approve`; and
- maker execution: `access.membership.change` / `access.membership.manage`.

The current approved grant catalogue therefore keeps the workflow owner-governed. Request, approval, and execution require current authorization, recent primary authentication and MFA no older than five minutes, a normalized 10-to-500-character reason, caller-owned idempotency, target separation, maker/checker separation, and the existing 30-minute approval expiry. Only the original maker may execute. `If-Match` binds the request to the exact membership `lock_version`. Request rejects a maker who is the target; approval rejects a checker who is the maker or target; execution revalidates all three identities. Request and execution also validate the eligible role, exact current scope digest, exact desired set, and same-tenant non-closed facilities. Approval does not change access.

An executed scope change atomically consumes the exact approval, locks the membership and involved facilities, ends the prior assignment set, creates the desired set, updates `scope_mode` and the membership revision, and commits business state plus audit/outbox/idempotency evidence. Any stale revision, changed facility state, changed current set, missing target, cross-tenant target, expired approval, wrong executor, or partial write rolls back the whole transaction.

### Interaction with other membership transitions

- A role change between `configuration_editor` and `organization_viewer` preserves the mode and exact facility set.
- A role change from an eligible scoped role to any other non-owner role atomically ends all assignments and sets `organization`.
- A role change from an ineligible role to `configuration_editor` or `organization_viewer` starts in `organization`; narrowing is a separate scope-change request.
- Revocation atomically ends every open assignment and sets `organization` before the membership becomes revoked.
- Owner promotion atomically ends every open assignment and sets `organization`.
- Owner demotion to an eligible role starts in `organization`; later narrowing requires a separate scope-change request.
- Facility closure is rejected until every open access assignment has been removed through an approved scope or membership transition.

The same database transaction and consumed approval govern these cleanup effects. Direct SQL cannot create, end, or retain an assignment contrary to the resulting membership role/status/scope.

## HTTP and projection contract

The existing membership-change routes are extended; no parallel scope mutation route is introduced. `MembershipChangeRequest` accepts `changeType=scope_change`, requires `scopeMode`, `facilityIds`, and `reason`, and requires `toRoleKey` to be absent or null. `scopeMode=organization` requires an empty array. `scopeMode=facilities` requires 1 to 100 unique UUIDs. For `role_change` and `revoke`, both scope fields are absent. The server rejects duplicates before sorting the validated IDs for canonical comparison; different input order produces the same canonical idempotency digest.

The request response exposes the authorized review projection: unchanged role, from/to scope mode, sorted target facility IDs, target scope digest, approval ID, membership ID, target user ID, resulting membership revision expectation, status, and expiry. Approval and execution continue through the existing approval and execution routes. Responses remain runtime-validated, `Cache-Control: no-store`, correlation-bearing, and free of secrets.

Each M1-20 membership row adds `scopeMode` and `facilities`, where each facility summary is `{id, code, displayName}` sorted by code then ID and bounded to 100. The server may project `requestScopeChange` only for an eligible target when the current actor can actually request it. The page continues to project approval/execution actions from current server authorization. Hidden targets return `404`; known but unauthorized actions return the approved explicit `403`; missing/stale preconditions return `428`/`412`; stale approvals and conflicting execution return `409` with no side effects.

The M1-20 UI displays `Organization-wide` or facility chips, provides a full-replacement multi-select only when `requestScopeChange` is projected, shows the reason/MFA/recent-authentication/independent-approval consequences before submission, preserves the caller's idempotency key across retry, and refreshes from the server after execution. It never infers authority from role names or cached facility choices. Loading, empty, hidden/denied, validation, stale revision, expired approval, dependency failure, success, keyboard/focus, reduced-motion, card/table, and 1440/1024/768/390/320 containment states are required.

## Evidence contract

Scope change reuses the approved final `identity.membership.changed` audit and outbox event with exactly these six payload keys: `membershipId`, `changeType`, `fromRole`, `toRole`, `approvalId`, and `lockVersion`. For this transition, `changeType=scope_change` and `fromRole=toRole`. Facility IDs and scope digests are deliberately absent from the event; the immutable tenant-RLS request/child rows and temporal assignment ledger are the detailed evidence source.

The existing `identity.membership-change.requested` and `identity.membership-change.approved` events remain the intermediate evidence with their already registered exact payloads. No new event schema or consumer entitlement is implied. Final audit, outbox, membership revision, assignment replacement, approval consumption, and idempotent response commit atomically. Replay returns the original response and produces no second transition or evidence row.

An implementation migration must record a separate immutable extension release containing candidate version, candidate package SHA-256, approval record ID, approver identity/role, approval time, and the approved base package digest. Startup activation must fail closed unless both the existing `m1-candidate-1` release and this exact extension release are active and configuration-bound.

## Verification and acceptance

- Migration tests prove upgrade defaults, 1-to-100 bounds, exact canonical digests, same-tenant composite links, forced RLS, current-state deferred constraints, immutable request/history, one-time assignment ending, facility-close conflict, UUIDv7 defaults, and rollback of partial replacements.
- Direct PostgreSQL attacks cover missing tenant/actor/operation/reason/approval context, cross-tenant facility IDs, wrong role, empty/oversized/duplicate sets, closed facilities, forged digests, stale revisions, unconsumed/wrong approvals, self-target, wrong maker/checker/executor, delete/update bypass, retained scope after role/revoke/owner transitions, and final-owner invariants.
- HTTP tests cover request/approve/execute success and exact replay plus `400`, `401`, `403`, hidden `404`, `409`, `412`, `428`, and `503` with zero unauthorized business, approval, audit, outbox, or idempotency side effects.
- Authorization tests prove assigned-facility read/manage intersection, descendant and service-assignment derivation, organization/scheme/catalog/activation/evidence denial, list/count SQL filtering, suspended lifecycle behavior, non-assigned and ambiguous-resource denial, and that organization-scoped roles are unchanged.
- Contract/client/UI tests cover conditional request schemas, runtime response rejection, generated-client drift, action projection, full-set selection, facility chips, stale/expired recovery, no client-side authority, Axe, keyboard/focus behavior, reduced motion, and overflow at 1440, 1024, 768, 390, and 320 pixels.
- A clean backend verification, frontend type/lint/format/unit/build run, complete five-viewport browser suite, API/convention verifier, candidate verifier, CI-security verifier, Compose resolution, and relevant image/security checks must pass before the implementation increment is described as complete.

## Approval boundary

This file is a complete proposal, not an approval record. Its verifier must continue to report `CANDIDATE_FOR_APPROVAL` and `implementationAuthorized: false`. Approval must name `m1-facility-scope-candidate-1`, the exact candidate package SHA-256 emitted by the verifier, the approver identity and role, and an approval timestamp. Any artifact byte or manifest value change creates a new digest or fails verification and requires a new approval. Until that evidence exists and verifies, runtime authorization, persistence, API, and UI behavior must remain unchanged and facility-scope actions must remain unavailable.
