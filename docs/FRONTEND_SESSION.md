# Frontend session boundary

## Scope

The React application now keeps protected route content behind the existing server-side browser identity and organization-selection APIs. This is a Phase 0 navigation/session boundary, not permission to perform a tenant business operation.

The `features/session` state machine calls the checked `CareOsApiClient` for:

- `GET /api/v1/auth/session` during bootstrap;
- `POST /api/v1/auth/login` for primary credentials;
- `POST /api/v1/auth/password-reset-requests` and `POST /api/v1/auth/password-resets` for public recovery;
- `POST /api/v1/auth/invitation-acceptances` for one-time anonymous account creation or exact authenticated existing-account linkage;
- `POST /api/v1/auth/mfa/challenges` when the server reports `mfa_required`;
- `POST /api/v1/auth/recent-authentications` before protected identity changes;
- `POST /api/v1/auth/mfa/enrollments`, its verification operation, and recovery-code regeneration for self-service MFA;
- the three organization-scoped administrative-MFA request, independent-approval, and execution operations;
- `GET /api/v1/organizations` after full authentication;
- `POST /api/v1/auth/organization-selections` for explicit selection or switching;
- governed organization invitation issue and revocation operations; and
- `POST /api/v1/auth/logout` for acknowledged session invalidation.

Every POST still goes through the client's per-mutation CSRF bootstrap, correlation validation, checked response handling, and no-automatic-retry rule.

## State and route behavior

| Server/client state | Browser behavior |
| ------------------- | ---------------- |
| Session request pending | Show a non-workspace loading state. |
| `anonymous` | Show M1-01 with empty credential fields; preserve the requested protected hash as the post-authentication destination. |
| `#/forgot-password` | Accept an email through the checked public recovery operation and always show the same generic accepted state. |
| `#/reset-password?token=...` | Capture the case-sensitive one-use token, replace the current history entry with a token-free fragment, validate matching password fields, and revoke local authenticated state only after server completion. |
| `#/accept-invitation?token=...` | Capture and immediately scrub the case-sensitive one-use token. An anonymous invitee supplies a new password; an existing account must authenticate as the exact invited email. No token is displayed or persisted. |
| `mfa_required` | Show the pending M1-03 challenge; accept an authenticator or recovery code only through the checked API. |
| `authenticated`, organization request pending | Keep the workspace hidden while membership-backed choices load. |
| Authenticated M1-03 | Use the server's persisted MFA status; require recent primary and, when enabled, second-factor authentication before revealing or replacing self-service MFA material. With a selected organization, expose the governed administrative reset request/approve/execute panel; the server remains authoritative for permission and actor separation. The identity-level self-service route remains available without a tenant selection. |
| Authenticated response | Require a valid server-issued effective session deadline before exposing identity or workspace content; update that deadline from subsequent checked API traffic. |
| Effective deadline reached | Immediately discard authenticated UI state and show M1-01 with a session-ended notice without sending a request that could refresh the idle session. |
| Browser focus/visible/online/BFCache return | If the local deadline remains valid, rerun checked session and organization bootstrap before restoring protected content. If it has passed, lock locally without contacting the server. |
| Authenticated with no selected organization | Require explicit M1-04 selection; do not silently send a selection mutation. |
| Authenticated with exactly one server-selected organization | Render the protected shell using the returned user and organization names. |
| Authenticated with no organizations | Show a no-access state and sign-out action; render no protected screen. |
| Malformed/ambiguous response or dependency failure | Keep the workspace locked, focus a safe error summary, and expose only the checked detail, bounded retry guidance, and correlation reference. |
| Organization switch | Keep the old controlled selection until the server confirms the requested organization. |
| Logout failure | Retain the authenticated local view and show an error; never claim the session ended without server acknowledgement. A server `401` is treated as an already-invalid session. |

M1-02 now issues only the two reference-policy delegable roles, retains a caller-owned idempotency key for an uncertain retry, and can revoke the returned pending invitation. The server enforces role delegation, recent authentication, reason, expiry, one-time token use, account-linking rules, audit/outbox evidence, and production disablement. The browser never receives an issued raw token; delivery goes through the configured notification channel.

## Security invariants

- Credentials, MFA/recovery codes, invitation/reset tokens, session IDs, CSRF values, and organization selections are never written to `localStorage`, `sessionStorage`, or IndexedDB. Email-delivered one-time tokens necessarily arrive in the hash URL; the application captures them without case conversion, immediately removes them from the current history entry, holds them only in React memory, and forgets them after successful use. The server-issued `otpauth:` provisioning link is likewise transient and never placed in CareOS navigation or storage.
- Password, recent-authentication, MFA challenge, and enrollment-code React state is cleared when submitted. Plaintext setup material and recovery codes live only in a recently-authenticated child view and disappear when that view is closed, recent authentication is lost, or the route unmounts. The browser client does not log bodies and does not retry mutations.
- Generated TypeScript types are not treated as runtime proof. The session feature validates state/user/MFA shape, UUID identities, organization shape and uniqueness, at-most-one selected organization, invitation/account-link results, administrative-MFA transition results, one-time enrollment material, and unique recovery-code format before rendering or revealing it.
- The session response reads authoritative persisted MFA status rather than trusting the potentially stale principal captured before an enrollment. An authenticated-operation `401` returns the browser to the anonymous lock; `428` clears local recent-authentication status and requires credential verification again.
- The server emits the effective time remaining to the earlier Redis idle or absolute deadline on authenticated responses. The checked client derives a conservative local deadline from request start and publishes deadline updates and all `401` invalidations. The session feature uses a bounded timer, clears in-flight generations when the session ends, and keeps no deadline in browser persistence.
- There is deliberately no interval heartbeat. Idle pages therefore expire, including when background timer throttling delays JavaScript: visibility, focus, online, and BFCache-return handlers compare the deadline before making a checked revalidation request.
- Organization selection is only navigation state. Every future tenant operation must still carry its path organization into backend `TenantAuthorizationOperations`, where membership, purpose, and permission are revalidated transactionally.
- API, data, shared component, feature, and page dependency direction is enforced by `npm run architecture:check`. Features cannot directly import another feature, and shared layers cannot depend on a feature.
- Errors use the sanitized RFC 9457 client result. Raw exceptions, response bodies, credentials, and tokens are not rendered.

## Verification

The frontend suite has 35 unit tests covering the checked transport/session boundary, invitation issuance/revocation/acceptance, token scrubbing, account linkage, recent authentication, MFA enrollment/recovery codes, and all three administrative-MFA transitions with caller-owned retry keys. Twenty Playwright tests exercise those browser flows plus login/selection/logout, password recovery, and no-polling deadline locking, and Axe-check all 75 protected prototype entries plus identity/recovery/invitation states on desktop and 320px viewports.

## Still required

This boundary implements the disabled-in-production Phase 0 reference invitation and governed administrator-reset workflows. It does not implement self-service MFA disable, an owner-approved production policy, permission projections for action hiding, an approved production router/design system, or business-record caching. The remaining 75 protected entries still contain synthetic prototype content and must not be treated as completed M1, M2, or clinical workflows.
