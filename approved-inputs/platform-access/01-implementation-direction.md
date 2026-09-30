# Platform super-administrator implementation direction

**Record ID:** `PLATFORM-ACCESS-DIRECTION-20260929-01`  
**Decision maker:** Bhupendra, developer  
**Recorded:** 29 September 2026  
**Scope:** repository implementation of an organization-scoped super-administrator role in local, QA, UAT, and production-capable application profiles

The decision maker explicitly directed CareOS to provide a super-administrator with access to every implemented user-facing workflow and the ability to govern access for other users.

This direction authorizes the repository role and its governed assignment path. It does not authorize a database superuser, tenant/RLS bypass, service or worker identity, provider-readiness bypass, audit suppression, maker-checker bypass, MFA bypass, concurrency bypass, idempotency bypass, or use of unavailable clinical/provider configuration.

The role may be deployed in a production-capable build, but this record does not assign it to a production person, approve production secrets or providers, or grant target-environment production acceptance.
