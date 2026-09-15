# Authorization registry approval boundary

## Current status

The authorization mechanism and a bounded Phase 0 reference policy now exist; an owner-approved production policy still does not. Flyway V14 adds `careos-phase0-reference-v1` entries for four interactive roles, seven human permissions, operation policy, delegation ceilings, and final-owner protection. V15 adds governed invitation acceptance, V16 adds five disjoint non-interactive roles and operations, and V17 adds a database-enforced approval workflow for administrative MFA reset.

Every supplied entry has status `reference`. It is ignored unless `careos.authorization.reference-policy-enabled=true`, which is enabled only by the local profile and explicit integration tests. The production preflight rejects that flag plus governed-invitation, service-identity, and MFA-administration activation. The runtime role can read canonical catalogs but cannot change them; unknown, retired, or non-enabled reference entries grant nothing.

This reference policy is an executable least-privilege proposal, not owner approval inferred from the prototype. `IMPLEMENTATION_GAPS.md` retains final registry acceptance as an external input.

## Enforced invariants

- Organization discovery is read-only and exposes only the authenticated actor's live, effective memberships.
- A selected organization is a server-side navigation preference, not proof of authorization.
- A governed use case must name a registry permission and execute through `TenantAuthorizationOperations`.
- Membership status/effective dates, organization status, role status, permission status, and the role-permission grant are checked inside the tenant transaction.
- The active membership rows are locked while authorized work runs, preventing a membership-revocation race.
- No membership returns a hidden-resource result; a live membership without the required permission returns an explicit denial.
- Raw organization headers are not consumed by selection or authorization code.
- Invitations may assign only a reference role explicitly marked `invitation_assignable`, and only when the caller's role has an exact delegation edge. Final-owner roles are never invitation-assignable.
- The final effective owner of an organization cannot be revoked, downgraded, or scheduled to expire until another indefinite effective owner exists.
- Human memberships accept only interactive roles. Service identities accept only non-interactive roles, use a bounded purpose allow-list and expiring HMAC-digested credentials, and authorize only an exact tenant, purpose, role permission, and operation.
- Administrative MFA reset requires a recent-authenticated reasoned request, a decision by a different authorized user who is not the target, and execution by the original requester using the exact subject, reason, approval, and idempotency key. PostgreSQL owns and validates every lifecycle transition; evidence cannot be deleted.

## Reference registry scope

The interactive proposal contains `organization_owner`, `organization_administrator`, `organization_member`, and local-only `local_bootstrap`. Owners receive the bounded Phase 0 organization, invitation, audit, membership-management, and administrative-MFA permissions. Administrators receive profile/membership/audit read plus invitation issue/revoke; members receive profile read. Delegation edges prevent administrators from assigning owners or peer administrators.

The non-interactive proposal contains one role each for outbox publication, integration consumption, notification delivery, job execution, and schedule dispatch. These identities are not browser users, cannot hold organization memberships, cannot use operations with reason/recent-authentication/maker-checker requirements, and currently start no worker or scheduler.

## Owner input still required

The approved registry must define, at minimum:

1. A versioned list of stable permission keys, names, descriptions, scopes, risk classes, and retirement rules.
2. A versioned list of role keys and exact permission grants.
3. Which roles are assignable by invitation and each role's delegation ceiling.
4. Final-owner protections, maker-checker separation, reason/recent-authentication requirements, and hidden-versus-denied behavior per operation.
5. Matching audit-event and outbox-event names and schemas for every governed mutation.

After approval, add a new reviewed Flyway migration that promotes or replaces the exact accepted entries and records the approval/version reference; do not edit historical migrations in a deployed environment. Keep the machine-readable contract, route bindings, allow/deny/delegation/final-owner/maker-checker tests, and production preflight aligned. Do not add runtime endpoints that mutate canonical catalogs.
