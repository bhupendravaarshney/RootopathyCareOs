# Authorization registry approval boundary

## Current status

The authorization mechanism exists, but the production policy does not. Flyway version 5 creates migration-owned `authorization_permissions`, `authorization_roles`, and `authorization_role_permissions` catalogs with no production grants. The runtime role can read these catalogs but cannot change them. An unknown membership role therefore grants no permission.

This empty state is intentional. `IMPLEMENTATION_GAPS.md` identifies the permission and authorization registries as owner inputs that must not be guessed from the prototype.

## Enforced invariants

- Organization discovery is read-only and exposes only the authenticated actor's live, effective memberships.
- A selected organization is a server-side navigation preference, not proof of authorization.
- A governed use case must name a registry permission and execute through `TenantAuthorizationOperations`.
- Membership status/effective dates, organization status, role status, permission status, and the role-permission grant are checked inside the tenant transaction.
- The active membership rows are locked while authorized work runs, preventing a membership-revocation race.
- No membership returns a hidden-resource result; a live membership without the required permission returns an explicit denial.
- Raw organization headers are not consumed by selection or authorization code.

## Owner input still required

The approved registry must define, at minimum:

1. A versioned list of stable permission keys, names, descriptions, scopes, risk classes, and retirement rules.
2. A versioned list of role keys and exact permission grants.
3. Which roles are assignable by invitation and each role's delegation ceiling.
4. Final-owner protections, maker-checker separation, reason/recent-authentication requirements, and hidden-versus-denied behavior per operation.
5. Matching audit-event and outbox-event names and schemas for every governed mutation.

After approval, add the registry through a reviewed Flyway migration, add a checked machine-readable contract, bind each protected operation to an approved permission, and add allow/deny/delegation/final-owner tests. Do not add runtime endpoints that mutate the canonical catalogs.
