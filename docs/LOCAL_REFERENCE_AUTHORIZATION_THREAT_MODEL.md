# Local reference authorization threat model

## Scope

The V114 workforce bridge exists only so the fixed synthetic `local_bootstrap` membership can exercise local/demo projections. It is not a production role, a production policy release, or a substitute for approved workforce authorization.

## Threat

PostgreSQL custom GUC names are request context, not secrets or authority. A connected role can attempt to set `app.reference_authorization_policy_enabled`; therefore a truthy GUC alone would let an ordinary runtime connection manufacture the local reference-policy condition.

## V115 boundary

Reference authorization now requires both:

1. the application transaction sets the bounded reference flag; and
2. `session_user` has a direct membership in `careos_local_reference_authority`.

The capability role is deployment-owned, non-login, non-superuser, non-`CREATEDB`, non-`CREATEROLE`, and non-`BYPASSRLS`. Its membership is granted with `ADMIN FALSE`, `INHERIT FALSE`, and `SET FALSE`. The normal runtime role therefore cannot create or grant the capability, inherit privileges from it, or assume it with `SET ROLE`. The capability has no object privileges; it is only an immutable fact checked by the migration-owned function.

The database bootstrap accepts only exact `true` or `false` for `CAREOS_DB_LOCAL_REFERENCE_AUTHORITY` and defaults to false. The local Compose topology and disposable database tests opt in explicitly. Production provisioning must leave it false/absent and must not create or grant the capability role. Independently, the production application profile still rejects `careos.authorization.reference-policy-enabled=true` during startup.

PostgreSQL initialization hooks run only for a new cluster. An older local volume therefore remains denied after V115 until an accountable database administrator explicitly provisions the local-only capability or the operator recreates a disposable synthetic volume. This operational friction is intentional; the migration never guesses that an existing environment is safe to classify as local.

## Verified attacks

Database tests prove denial when the reference flag is missing or false, denial after capability membership is revoked even when the GUC is true, inability of the runtime role to `SET ROLE` or self-grant the capability, cross-tenant denial, unknown/non-approved permission denial, retention of `local_bootstrap` as a reference-only role, unchanged active production-role behavior, and production-profile rejection of reference-policy activation.

## V116 action-projection boundary

Authorization and user-interface action projection now share the same capability boundary. `careos_projected_interactive_permissions(organization, actor)` is a migration-owned, `SECURITY INVOKER` function. It returns active-role permissions normally, but returns permissions for the exact reference-only `local_bootstrap` role only when `careos_reference_authorization_enabled()` confirms both V115 factors. It additionally requires its arguments to match the transaction-local organization and actor and a current active membership. Public execution is revoked and only the restricted application role can call it.

Application stores no longer inspect the raw reference GUC when projecting actions. Database attacks prove missing/false flag denial, exact actor and tenant binding, unchanged active-role behavior, and immediate fail-closed behavior after the deployment capability is removed. The function projects candidate actions only; every mutation still passes through the existing authorization, RLS, validation, concurrency, idempotency, audit and outbox boundaries.

## V117 platform super-administrator boundary

V117 adds an active, organization-scoped `platform_super_administrator` backed by the separately checksum-bound `platform-access-v1` registry release. Its grants are the exact migration-time union of active permissions on active interactive roles backed by active releases (277 at V117). Ten non-interactive worker/provider permissions are explicitly absent. It is MFA-required, not invitation-assignable, and not the protected final-owner role.

The role can govern another existing member through the approved membership-change workflow, including promotion of another member to platform administrator. This is the sole permitted same-role delegation tuple; the runtime role has read-only access to the migration-owned delegation catalogue. Targeting the acting user remains denied, and role change still requires a bounded reason, recent authentication/MFA where required, independent approval, exact consumed approval context, strong revision, idempotency, audit and outbox evidence. Direct invitation to the role, service-role assignment, ordinary final-owner mutation and cross-tenant delegation remain denied.

`local_bootstrap` receives the same delegation catalogue only as a reference role behind both V115 factors. This makes local/UAT access usable without making the custom GUC authoritative or converting the synthetic identity into a production role. Production provisioning must not grant the capability, and the production application profile continues to reject reference-policy activation.

## Residual trust

The migration/deployment owner remains trusted. An actor able to alter migrations, grant roles as the database owner, or change production startup policy already controls the authorization system. V115 specifically prevents an ordinary application connection or injectable request context from converting GUC manipulation into workforce authority. Any target environment that provisions the local capability is non-production until that provisioning is removed and independently verified.

## Authorization-reason privacy

`X-Authorization-Reason` is untrusted sensitive input. CareOS captures it before request telemetry, normalizes it to NFC, trims it, rejects control characters, and bounds it to 10–500 Unicode code points. The original header is then hidden from downstream request/header enumeration. Only the normalized value may enter the explicitly governed workforce audit/evidence path; logs, exception messages, trace attributes, metrics labels, proxy access logs, browser analytics, and support-style generic evidence must not contain the raw value.
