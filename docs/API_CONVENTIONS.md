# CareOS HTTP and frontend API conventions

Status: checked Phase 0 foundation convention. This document governs every future protected business endpoint but does not authorize or implement one.

The machine-readable source is `contracts/openapi/careos-foundation.json`, currently version `0.6.0`. Its `x-careos-conventions` object and reusable components are enforced by `scripts/verify-api-contract.mjs` and negative tests. If this document and the checked contract disagree, stop and reconcile them before adding an endpoint.

## Route and tenant boundary

- Public foundation routes remain under `/api/public/...`; browser identity and organization discovery/selection remain under `/api/v1/...` as declared in the contract.
- Every protected business resource must be nested under `/api/v1/organizations/{organizationId}/...` and declare the shared required UUID `OrganizationId` path parameter.
- The path organization is only an authorization input. The backend must revalidate the authenticated actor's live membership and operation permission inside `TenantAuthorizationOperations` before starting business work.
- The stored organization selection is navigation state only. `X-Organization-Id` is not an accepted tenant selector and is deliberately absent from the CORS allowlist.
- Hidden-resource policy remains operation-specific. Until owner-approved policy says otherwise, authorization stays fail closed and no protected business route may be published.

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

`frontend/src/api/client.ts` wraps all 15 currently implemented operations with generated request/response types, credential inclusion, per-mutation CSRF bootstrap, correlation handling, safe Problem parsing, abort/network outcomes, strict status/body checks, session-lifecycle publication, ETag exposure, and bounded `Retry-After` parsing. The session feature calls the identity/session/organization subset for login, public password recovery, pending MFA, recent authentication, MFA enrollment/recovery-code replacement, selection/switching, and logout. Protected business integration must still happen as approved screen-specific vertical slices replace placeholder behavior. See `FRONTEND_SESSION.md`.

## Endpoint review checklist

Before a protected endpoint is added, its review must show:

1. approved operation permission, purpose, hidden/denied behavior, and event/audit definitions;
2. tenant-prefixed route and transaction-local authorization/RLS path;
3. explicit request, response, validation, filter, ordering, and cursor contracts;
4. ETag/`If-Match` behavior for mutable resources and idempotency behavior for retryable mutations;
5. RFC 9457 failures and correlation on every response;
6. generated-type drift, backend contract/integration/attack tests, frontend client/UI tests, and browser accessibility coverage.

Until all six exist, the endpoint remains an implementation gap.
