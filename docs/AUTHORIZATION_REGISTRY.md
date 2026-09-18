# Authorization registry approval boundary

## Current status

`m1-candidate-1` was approved unchanged by **bhupendra, developer** on 16 September 2026. The production input manifest is `APPROVED`, contains all eight required artifacts, and passes `node scripts/verify-module-1-inputs.mjs --require-approved` at package digest `19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946`. Approval record `M1-APPROVAL-20260916-01` binds that package, all M1-01 through M1-23 screens, and the separate approval evidence.

Flyway V20 records the approval in immutable `authorization_registry_releases` evidence and activates the exact approved Module 1 permission, interactive-role, grant, and delegation catalogue under registry version `m1-candidate-1`. It also promotes the already implemented organization profile/readiness operations and replaces the invitation and administrative-MFA operation/event bindings with approved names and assurance rules. Flyway V21 activates exact read operation `access.membership.read`; V22 activates exact request/approve/execute bindings for governed organization-wide non-owner role changes and membership revocation; V23 binds the approved `access.owner_transfer.*` permissions to governed `access.owner-transfer.*` operations and the final `identity.owner.transferred` event; V24 marks the exact six mandatory-MFA roles and enforces enrollment/use plus the approved factor-removal boundary.

The older `careos-phase0-reference-v1` entries remain historical reference mechanics. Production still rejects `careos.authorization.reference-policy-enabled=true`; active approved entries do not require that flag. Governed invitations, administrative MFA reset, and membership administration remain disabled by default, but production may enable them only while the configured active registry and approval-package digest exactly match the approved release. Reference service identities and actual workers remain unavailable in production.

An active permission entry is not an implemented endpoint. V20 authorizes incremental delivery against the approved catalogue; current runtime bindings cover organization profile/readiness, invitation issue/revoke/acceptance, administrative MFA reset, V21 membership list/read, V22 organization-wide non-owner membership role-change/revocation, V23 final-owner-safe owner promotion/demotion request/approval/execution, and V24 mandatory-role MFA enrollment/use through the existing identity operations.

## Enforced invariants

- Canonical permissions, roles, grants, delegations, operations, event bindings, approval workflows, and release evidence are migration-owned and runtime-read-only.
- Authorization requires one exact active operation, permission, role, and registry version. Unknown, retired, cross-version, or ungranted entries grant nothing.
- A live membership is locked and revalidated in the same transaction as authorized work. Organization selection is only a navigation preference.
- Missing membership and hidden operations return a non-disclosing result; explicit denials remain distinguishable only where the approved operation policy permits it.
- Invitations can assign only an approved invitation-assignable role within an exact delegation edge. Owners cannot be invited, and the final effective owner remains protected by PostgreSQL.
- Invitation issue/revoke requires a reason, recent primary authentication, and a separately recorded recent MFA assertion. The assertion is checked against the operation window and future-skew bound.
- Administrative MFA reset requires MFA and recent authentication for request, independent decision, and execution. The maker, checker, and target separation rules, exact approval, subject, reason, expiry, correlation, and idempotency evidence remain database-enforced and append-only.
- Runtime organization-profile updates require the approved operation, exact organization, actor, and reason. PostgreSQL limits mutable fields and requires actor-bound one-step revision evidence even if application code is bypassed.
- Membership read authorizes inside the tenant transaction, returns hidden denial, applies forced RLS, and projects only actions backed by current live approved permissions. Signed cursors cannot be rebound across tenant, filters, operation, or page limit.
- Membership role change/revocation requires a strong current revision, exact reason, recent primary authentication plus MFA, independent live approval, maker/checker/target separation, and an allowed delegation edge. PostgreSQL binds the approval to the exact non-owner membership, from/to role, change type, revision, reason, and executor context before permitting one update and atomic changed/revoked evidence.
- Owner promotion/demotion requires the same strong revision, reason, recent primary authentication plus MFA, 30-minute approval, and maker/checker/target separation. The approved maker executes the exact transition; PostgreSQL requires an indefinite promotion target, an allowed demotion delegation edge, and preserves the final effective owner while writing atomic `identity.owner.transferred` evidence.
- `organization_owner`, `organization_administrator`, `configuration_approver`, `security_administrator`, `auditor`, and `export_approver` require enabled MFA while any membership is effective. Login without a factor receives restricted enrollment authority, protected requests re-evaluate the live requirement after role changes, and PostgreSQL permits enabled-factor removal only for the exact consumed governed administrative reset. `local_bootstrap`, editor, and viewer are not marked required.
- Readiness uses exact non-overrideable `access.final_owner` and `access.mfa_enforced` gates. Optional viewer/editor organization-level enforcement is not runtime authority until a separate approved contract defines its policy and lifecycle.
- Human memberships accept only interactive roles. Reference non-interactive roles remain isolated to service identities and cannot be used through browser membership.
- The approval release row contains the exact package, authorization-artifact, and approval-evidence digests and cannot be updated or deleted by either the runtime role or ordinary migration-owner SQL.

## Approved interactive scope

The approved interactive roles are `organization_owner`, `organization_administrator`, `configuration_editor`, `configuration_approver`, `security_administrator`, `auditor`, `export_approver`, and `organization_viewer`. `local_bootstrap` remains reference-only for synthetic local development and is never production eligible.

The exact permission families, grants, assurance windows, denial behavior, delegation ceilings, final-owner rules, and MFA enforcement are authoritative in `approved-inputs/module-1/05-authorization-policy.md`. Owners can delegate every non-owner role; administrators can delegate editor/viewer; security administrators can delegate viewer. No role can grant itself authority outside those edges.

## Remaining implementation work

The approved registry removes the input blocker; it does not complete M1B or the later Module 1 slices. Remaining authorization work includes:

1. M1-20 facility-scoped membership grants/scope changes. List/read, organization-wide non-owner role change/revocation, final-owner-safe owner transfer, and mandatory-role MFA are complete. Additive candidate `m1-facility-scope-candidate-1` defines the missing exact contract, but its verifier reports `implementationAuthorized: false`; runtime work requires approval bound to its exact digest.
2. Operation and event bindings for the approved organization, network, service, identifier, activation, history, audit, and export use cases as each persisted vertical slice is delivered.
3. Extend the implemented M1-20 server action-projection pattern to each later screen so unavailable actions are hidden or denied according to approved operation policy without trusting browser state.
4. Production provisioning/rotation and approved activation for non-interactive identities, workers, consumers, notification delivery, and schedulers.
5. Full allow/deny/delegation/final-owner/maker-checker/MFA attack coverage for each newly implemented operation and target-environment acceptance before production rollout.

Do not expose runtime endpoints that mutate canonical authorization catalogs or infer implementation completeness merely because V20-V24 contain a permission, role attribute, or operation key.
