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

## Residual trust

The migration/deployment owner remains trusted. An actor able to alter migrations, grant roles as the database owner, or change production startup policy already controls the authorization system. V115 specifically prevents an ordinary application connection or injectable request context from converting GUC manipulation into workforce authority. Any target environment that provisions the local capability is non-production until that provisioning is removed and independently verified.

## Authorization-reason privacy

`X-Authorization-Reason` is untrusted sensitive input. CareOS captures it before request telemetry, normalizes it to NFC, trims it, rejects control characters, and bounds it to 10–500 Unicode code points. The original header is then hidden from downstream request/header enumeration. Only the normalized value may enter the explicitly governed workforce audit/evidence path; logs, exception messages, trace attributes, metrics labels, proxy access logs, browser analytics, and support-style generic evidence must not contain the raw value.
