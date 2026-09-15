# Frontend session boundary

## Scope

The React application now keeps protected route content behind the existing server-side browser identity and organization-selection APIs. This is a Phase 0 navigation/session boundary, not permission to perform a tenant business operation.

The `features/session` state machine calls the checked `CareOsApiClient` for:

- `GET /api/v1/auth/session` during bootstrap;
- `POST /api/v1/auth/login` for primary credentials;
- `POST /api/v1/auth/mfa/challenges` when the server reports `mfa_required`;
- `GET /api/v1/organizations` after full authentication;
- `POST /api/v1/auth/organization-selections` for explicit selection or switching; and
- `POST /api/v1/auth/logout` for acknowledged session invalidation.

Every POST still goes through the client's per-mutation CSRF bootstrap, correlation validation, checked response handling, and no-automatic-retry rule.

## State and route behavior

| Server/client state | Browser behavior |
| ------------------- | ---------------- |
| Session request pending | Show a non-workspace loading state. |
| `anonymous` | Show M1-01 with empty credential fields; preserve the requested protected hash as the post-authentication destination. |
| `mfa_required` | Show the pending M1-03 challenge; accept an authenticator or recovery code only through the checked API. |
| `authenticated`, organization request pending | Keep the workspace hidden while membership-backed choices load. |
| Authenticated with no selected organization | Require explicit M1-04 selection; do not silently send a selection mutation. |
| Authenticated with exactly one server-selected organization | Render the protected shell using the returned user and organization names. |
| Authenticated with no organizations | Show a no-access state and sign-out action; render no protected screen. |
| Malformed/ambiguous response or dependency failure | Keep the workspace locked, focus a safe error summary, and expose only the checked detail, bounded retry guidance, and correlation reference. |
| Organization switch | Keep the old controlled selection until the server confirms the requested organization. |
| Logout failure | Retain the authenticated local view and show an error; never claim the session ended without server acknowledgement. A server `401` is treated as an already-invalid session. |

M1-02 is deliberately unavailable. It displays no token field and sends no request because governed invitation issuance, expiry, acceptance, and account-linkage behavior has not been approved or implemented.

## Security invariants

- Credentials, MFA/recovery codes, session IDs, CSRF values, and organization selections are never written to `localStorage`, `sessionStorage`, IndexedDB, or a URL.
- Password and MFA code React state is cleared when submitted. The browser client does not log bodies and does not retry mutations.
- Generated TypeScript types are not treated as runtime proof. The session feature validates the state/user shape, UUID identities, organization shape and uniqueness, and at-most-one selected organization before rendering the shell.
- Organization selection is only navigation state. Every future tenant operation must still carry its path organization into backend `TenantAuthorizationOperations`, where membership, purpose, and permission are revalidated transactionally.
- API, data, shared component, feature, and page dependency direction is enforced by `npm run architecture:check`. Features cannot directly import another feature, and shared layers cannot depend on a feature.
- Errors use the sanitized RFC 9457 client result. Raw exceptions, response bodies, credentials, and tokens are not rendered.

## Verification

The frontend suite covers anonymous lockout, primary login, pending MFA, explicit selection and later switching, no-membership denial, ambiguous-selection denial, session retry, successful and failed logout, and unavailable invitations. Playwright exercises the CSRF/login/selection/logout sequence and Axe-checks all 75 protected prototype entries plus the four identity states on desktop and 320px viewports.

## Still required

This boundary does not implement invitation/account-linking policy, password-recovery screens, MFA enrollment or recovery-code administration screens, recent-authentication dialogs, permission-driven navigation/actions, automatic session-expiry refresh, an approved production router/design system, or business-record caching. The remaining 75 protected entries still contain synthetic prototype content and must not be treated as completed M1, M2, or clinical workflows.
