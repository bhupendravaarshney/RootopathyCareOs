# Security and data-handling rules

- Never commit `.env`, database dumps, access tokens, private keys or real clinical data.
- Use synthetic seed data in local development and automated tests.
- Automated database tests must use the disposable PostgreSQL 18 `careos_test` container; they must never point at `careos_dev` or production.
- Only authenticated membership/policy code may construct an `AuthorizedTenantContext`; never trust an organization header directly.
- Every tenant-owned transaction must set and verify transaction-local organization, actor, purpose, and correlation context.
- Run Flyway with the migration owner and all application queries with the restricted non-`BYPASSRLS` role.
- Enforce authorization in application policy and forced RLS.
- Treat the stored organization selection only as a navigation preference; revalidate membership and permission inside every governed tenant transaction.
- Keep authorization registry tables migration-owned and runtime-read-only; an unknown or retired role, permission, or mapping must grant nothing.
- Keep audit/outbox event-version registries migration-owned and runtime-read-only; reject unknown/retired events and payload-key drift.
- Use private object storage, quarantine and fail-closed malware scanning for credential and clinical documents.
- Keep document quarantine, scanning, promotion, signed access, retention, durable notifications, Redis jobs, workers, and schedulers explicitly unavailable by default until a tested adapter is intentionally configured for each capability; infrastructure presence alone is not capability readiness.
- Treat quarantine as untrusted: storage success never authorizes download or promotion, and every downstream operation remains denied until matching, durable, current `CLEAN` evidence and its own approved adapter exist.
- A scanner may read quarantine only through the internal tenant-checking content boundary. Never expose that stream through an API, trust a daemon verdict without local size/digest verification, or convert timeout, stale definitions, protocol drift, oversize input, or dependency failure into `CLEAN`.
- ClamD TCP is unauthenticated and unencrypted. Keep it on a trusted private network, never publish port 3310 from the local overlay, and require production network policy, signature-update monitoring, resource limits, and operational ownership.
- Redis queue keys must be derived from an authorized tenant, share a tenant hash slot, contain no raw deduplication token, and transition only through bounded atomic scripts. Never log job references or lease tokens, accept an unapproved job schema, acknowledge a stale/cross-tenant lease, or treat queue presence as authorization to execute work.
- Keep Redis job transport separate from worker activation. Production requires authenticated non-interactive identities, tenant reauthorization, ACL/TLS/HA/persistence and restore acceptance, metrics/alerts, dead-letter review/replay, and governed evidence before a worker may claim jobs.
- Store durable notification parameters only as authenticated ciphertext under a named 256-bit key; store only hashes of deduplication and lease tokens. Never persist or log a recipient destination in the queue, return parameters through an API, deliver under a stale/cross-tenant lease, or discard retained terminal evidence early.
- Keep notification persistence separate from delivery activation. At delivery time, an authorized non-interactive worker must re-resolve current consent and destination, use an approved provider/idempotency contract, and record approved governance evidence. The Phase 0J adapter performs none of those delivery actions.
- Require HTTPS, dedicated private buckets, provider-level public-access controls, least-privilege IAM, KMS-backed encryption, and governed metadata before accepting a quarantine adapter for production; the pinned local compatibility server is not production approval.
- Log metadata and governance evidence, never passwords, MFA seeds, recovery codes, raw invitation tokens or unnecessary clinical text.
- High-risk operations require MFA, recent authentication, mandatory reason and maker-checker separation where defined.
- General audit records are database-enforced append-only evidence. Outbox event content is immutable and non-deletable; only validated lease, acknowledgement, retry, and dead-letter metadata transitions are allowed.
- Retryable governed mutations must use `GovernedMutationExecutor`; never commit business data separately from its audit, outbox, and idempotency evidence.
- Scope request replay records by organization, authenticated actor, operation, and key; never log keys, request bodies, response bodies, or transport exception text.
- Browser authentication uses persisted credentials and Redis-backed server-side sessions; HTTP Basic and default form login must remain disabled.
- Production session cookies must set `Secure`; local HTTP development is the only permitted exception.
- Invitation acceptance/account linking, owner-approved scoped RBAC/event/template registries, maker-checker rules, non-interactive service authentication, production platform acceptance, consent/destination transports, and consumer deduplication remain fail-closed until their governed implementations exist.

Report security problems privately to the designated CareOS security owner. Do not include patient information in issue trackers.
