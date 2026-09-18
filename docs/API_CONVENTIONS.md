# CareOS HTTP and frontend API conventions

Status: checked foundation convention with approved-registry identity administration, authorized membership read/change/owner-transfer administration, and bounded organization profile, registration-identifier, effective-address/masked-contact, and international-settings slices. This document governs every protected business endpoint; it does not turn an approved catalogue entry into an implemented operation or production acceptance.

The machine-readable source is `contracts/openapi/careos-foundation.json`, currently version `0.18.0`. Its `x-careos-conventions`, reusable components, exact readiness catalogue, and exact approved organization-profile, identifier, address, masked-contact, and international-settings boundaries are enforced by `scripts/verify-api-contract.mjs` and negative tests. If this document and the checked contract disagree, stop and reconcile them before adding an endpoint.

## Route and tenant boundary

- Public foundation routes remain under `/api/public/...`; browser identity and organization discovery/selection remain under `/api/v1/...` as declared in the contract.
- Every protected business resource must be nested under `/api/v1/organizations/{organizationId}/...` and declare the shared required UUID `OrganizationId` path parameter.
- The path organization is only an authorization input. The backend must revalidate the authenticated actor's live membership and operation permission inside `TenantAuthorizationOperations` before starting business work.
- The stored organization selection is navigation state only. `X-Organization-Id` is not an accepted tenant selector and is deliberately absent from the CORS allowlist.
- Hidden-resource policy remains operation-specific. The seventeen organization-core operations and M1-20 membership read/change/owner-transfer operations bind to active approved operations. Reference entries still require explicit local/test opt-in and remain rejected in production. Every unimplemented approved business operation remains fail closed until its exact handler, persistence, evidence, and tests exist.

## Readiness projection boundary

- `GET /api/v1/organizations/{organizationId}/setup-readiness` returns the canonical ordered 15-key `m1-readiness-v1` catalogue. The only outcomes are `complete`, `warning`, `blocked`, and `not_applicable`; clients reject unknown, missing, duplicate, or reordered gates.
- The live projection carries the organization revision, database-owned `evaluatedAt` and `expiresAt` exactly 900 seconds apart, exact outcome counts, per-gate version, safe reason/remediation codes, and at most four bounded aggregate evidence references.
- The server computes outcomes from authoritative state. The browser cannot mark a gate complete. Missing persistence or an unavailable evaluator cannot be converted into completion; current unimplemented evaluators are visibly blocked or not applicable.
- Deep links are selected from current effective caller permissions. When the actor may read readiness but not the target screen, the response links back to M1-06 rather than exposing or inviting a denied resource.
- This response is a live M1-05/M1-06 projection, not an immutable activation result. A future `configuration_validation` operation must introduce its approved configuration ID/revision/digests, persistence, invalidation, evidence, and lifecycle rather than treating the organization revision as an activation digest.

## Organization profile boundary

- `GET` and `PUT /api/v1/organizations/{organizationId}/profile` expose the exact approved legal/display/trading identity, organization type, ISO country, IANA timezone, BCP 47 locale, lifecycle status, lock version, update time, and current caller editability.
- Legacy rows with null organization type or locale remain readable and readiness-blocked. A governed update requires both fields; the API never invents a backfill value.
- Profile names and the required 10-500-code-point change reason are NFC-normalized and trimmed; names reject markup/control characters and reasons reject control characters. Type, country, timezone, locale, and lifecycle vocabularies fail closed. Validation Problems use stable `m1.field.*` codes and exact JSON-pointer field paths.
- `editable` is derived from the caller's current `organization.profile.manage` permission. It is presentation evidence only; every mutation still reauthorizes inside the tenant transaction.
- Successful changes preserve strong revision/idempotency/reason rules and emit only sorted changed field names plus lock version in `organization.profile.updated` evidence. Profile values are not copied into audit/outbox payloads.

## Organization identifier boundary

- `GET` and `POST /api/v1/organizations/{organizationId}/identifiers`, draft `PUT`, and the verification, revocation, and supersession subresources implement M1-08 against the migration-owned `m1-candidate-1` type registry. The base registry contains only the approved generic `registration` key; jurisdiction-specific types require a versioned migration rather than invented browser data.
- Authorities and values are trimmed/NFC-normalized with exact approved bounds; jurisdiction, issue/expiry dates, inclusive-start/exclusive-end effective ranges, verification state/evidence, primary designation, predecessor linkage, and lifecycle vocabulary fail closed. The database owns non-revoked identity uniqueness, non-overlapping verified primary ranges, immutable terminal history, and forced tenant RLS.
- Draft create/edit uses live `organization.identifier.manage`; verification uses `organization.identifier.verify`, MFA, recent authentication no older than ten minutes, and bounded evidence. Evidence is projected only with current verification permission. List actions are derived by the server from current permission and lifecycle rather than role names in the browser.
- Supersession binds the predecessor `If-Match` and the replacement's exact strong ETag, requires a separately verified same-type unused replacement, atomically promotes it to primary when necessary, records its immutable `supersedesId`, and emits only the approved safe transition payload. Revocation cannot remove a required current primary without another current verified primary.
- `organization.identifier.primary_verified` is a live readiness evaluation over applicable required type metadata and a current verified primary. Identifier activation remains part of the future configuration-approval workflow; this API does not manufacture an activation result.

## Organization address and contact boundary

- `GET /api/v1/organizations/{organizationId}/contacts` returns the ordered M1-09 directory; address creation plus supersession/ending subresources and contact creation plus verification/supersession/ending subresources are governed mutations. The migration-owned base purpose registry contains only `operational`; new purposes require a versioned migration.
- Addresses use the exact registered/postal/service/billing types, one to four bounded lines, locality, region, postcode, ISO country, validation state/source, primary designation, inclusive-start/exclusive-end effective range, immutable predecessor link, lifecycle, actions, and revision. No geocode is accepted or projected.
- Contacts use email/phone/web channels, a registered purpose, normalized confidential value, verification state, primary/preferred designations, effective range, immutable predecessor link, lifecycle, actions, and revision. Every response uses `maskedValue`; the raw normalized value is absent even for managers and must never enter audit or outbox payloads.
- `organization.contact.read/manage` authorize inside the tenant transaction. Mutations require a 10-500-code-point NFC reason, scoped idempotency, and strong `If-Match` for existing records. PostgreSQL owns active/scheduled lifecycle derivation, current-value uniqueness, primary/preferred effective-range overlap, one-replacement lineage, terminal history, and forced RLS.
- Address/contact supersession atomically makes the predecessor immutable and inserts one current same-type or same-channel/purpose replacement while preserving its primary/preferred designations. Ending is allowed only for a current active record; future scheduled activation/expiry remains separate worker work.
- `organization.contact.coverage` is complete only with a current registered address and a verified current primary operational contact. A missing registered address is blocked; an unverified/missing operational contact is warning in draft and blocked at activation. Evidence contains only bounded counts and links to M1-09 when the caller has read permission.

## Lists, filters, and cursors

- Lists use cursor pagination, not offset pagination. `limit` defaults to 25 and is bounded from 1 through 100.
- A cursor is opaque to clients. A production server must integrity-protect it and bind it to the organization, operation, effective filters, sort, and unique stable tie-breaker. Clients must only return a cursor issued by the preceding compatible request.
- List responses must expose `items` and `page`, where `page` follows `CursorPageMetadata`. `nextCursor` is `null` exactly when `hasMore` is false.
- Each operation must enumerate its accepted filter and sort query parameters in OpenAPI. Unknown parameters, unknown values, unsupported combinations, and malformed/replayed cursors are validation failures; there is no free-form query language.
- Pagination must preserve a deterministic ordering when records are inserted or changed. The endpoint contract must name its stable sort and tie-breaker before implementation.

## Concurrency

- Readable mutable representations return a strong `ETag` in the checked quoted format.
- `PUT`, `PATCH`, and `DELETE` of protected mutable resources require the corresponding strong `If-Match`; wildcard and weak tags are not accepted.
- A missing precondition returns RFC 9457 status 428. A stale/mismatched tag returns status 412 without mutating business data or governance evidence.
- A successful mutation returns the new representation and strong ETag when the operation has a response body. Backend CORS allows `If-Match` and exposes `ETag`.
- A two-record transition such as identifier supersession must additionally bind the exact replacement revision in its checked request and lock both records deterministically in one tenant transaction. Address/contact supersession binds the predecessor revision and creates its immutable replacement in that same transaction.
- Entity tags identify versions and must not embed clinical data, secrets, actor identifiers, or reversible content digests.

## Idempotency and retry

- Retry behavior is caller-controlled. The checked browser client performs no automatic retries.
- Only `GET` and `HEAD` are eligible for a caller retry, and only when policy permits it. A numeric `Retry-After` is accepted for 429 or 503 only when it is between 1 and 86,400 seconds.
- Browser mutations are never automatically retried. A protected mutation designed for explicit caller retry must declare the shared `Idempotency-Key` header and execute through `GovernedMutationExecutor`.
- Idempotency remains scoped to organization, authenticated actor, and operation. Reusing a key with a different canonical request is a conflict; concurrent equivalent requests serialize and a completed response may be replayed according to the governed retention policy.
- Authentication, authorization, validation, concurrency, and business-conflict failures are not made successful by blind retries.

## Correlation and problem responses

- Every request sends a validated `X-Correlation-Id`; every response must return one. The response identifier is canonical for UI support and diagnostics.
- Errors use `application/problem+json` and the checked `Problem` shape. Transport exception text and response bodies are never logged or copied into synthetic client errors.
- The client rejects an undocumented status, missing response correlation, invalid media type/JSON, or malformed Problem response as a contract failure. Invalid successful upstream responses normalize to client status 502 while preserving their observed `responseStatus`.
- Strong ETags and bounded retry delays are exposed as metadata on `ApiResult`; they do not trigger client behavior by themselves.

## Browser session and CSRF handling

- The API base URL is `/api` by default and must end in `/api`. Absolute non-local URLs require HTTPS and may not contain credentials, a query, or a fragment.
- Every request uses `credentials: include`. JavaScript never sets `Origin`; the browser owns that header and the backend validates it.
- Before each unsafe browser operation, the client obtains `/api/v1/auth/csrf`, validates the exact `X-XSRF-TOKEN` header name and `_csrf` parameter metadata, and then sends the returned token. If bootstrap or validation fails, the mutation is not sent.
- Every response made under a valid authenticated session carries `X-CareOS-Session-Expires-In`, the bounded effective seconds remaining to the earlier Redis idle or absolute session deadline. CORS exposes this signal. The checked client converts it from the request start into a local deadline and publishes later deadline or `401` invalidation events to the session feature.
- The browser locks locally when that deadline passes and performs no background polling, because polling would itself keep the Redis idle session alive. Focus, visible-tab return, online return, and back/forward-cache restoration trigger a full checked session revalidation only if the locally held deadline has not passed.
- Cookies, CSRF values, passwords, MFA material, recovery codes, idempotency keys, and full response bodies must not enter logs, caches, client persistence, or CareOS navigation URLs. The only token-bearing browser URL is the email-delivered password-reset fragment; the application must capture it case-safely, immediately replace that history entry with the token-free reset route, keep it only in memory, and retire it after successful use.

## Generated types and checked client

`@hey-api/openapi-ts` is pinned exactly and configured to generate TypeScript-only artifacts. The vulnerable YAML transitive range is overridden to fixed `js-yaml` 4.3.2 and `npm audit --audit-level=high` must stay clean.

```bash
cd frontend
npm run api:generate
npm run api:check
```

Generated files live in `frontend/src/api/generated/` and must not be hand-edited. `api:check` regenerates into an isolated directory and compares both the file set and normalized contents. CI runs this before typecheck.

`frontend/src/api/client.ts` wraps all 45 currently implemented operations with generated request/response types, credential inclusion, per-mutation CSRF bootstrap, correlation handling, safe Problem parsing, abort/network outcomes, strict status/body checks, session-lifecycle publication, ETag exposure, and bounded `Retry-After` parsing. The session feature calls the identity/session/organization subset for login, public password recovery, pending MFA, restricted mandatory-role enrollment, recent authentication, MFA enrollment/recovery-code replacement, governed invitation issue/revoke/acceptance, maker-checker administrative MFA reset, selection/switching, and logout. The administration feature calls the bounded M1-05/M1-06 readiness projection, M1-07 organization-profile read/update, M1-08 identifier operations, all eight M1-09 address/contact operations, and M1-20 membership read plus governed non-owner membership-change and owner-transfer request/approval/execution operations. The remaining protected business integration must happen as approved screen-specific vertical slices replace placeholder behavior. See `FRONTEND_SESSION.md` and `MODULE_1_IMPLEMENTATION_PLAN.md`.

## Endpoint review checklist

Before a protected endpoint is added, its review must show:

1. approved operation permission, purpose, hidden/denied behavior, and event/audit definitions;
2. tenant-prefixed route and transaction-local authorization/RLS path;
3. explicit request, response, validation, filter, ordering, and cursor contracts;
4. ETag/`If-Match` behavior for mutable resources and idempotency behavior for retryable mutations;
5. RFC 9457 failures and correlation on every response;
6. generated-type drift, backend contract/integration/attack tests, frontend client/UI tests, and browser accessibility coverage.

Until all six exist, the endpoint remains an implementation gap.
