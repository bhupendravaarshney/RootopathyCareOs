# Production HTTP and configuration boundary

## Scope

Phase 0O adds repository-enforceable HTTP response policy and a fail-closed `production` Spring profile. It is a deployment preflight boundary, not approval of a hosting provider, network, certificate, secret manager, WAF, or production release.

The local Compose stack continues to use the `local` profile and synthetic credentials. Never copy `.env.example` into a production secret store.

## Production startup contract

Activate `production` without `local` or `test`:

```text
SPRING_PROFILES_ACTIVE=production
```

The base configuration now requires explicit database, Redis, SMTP, browser-origin/base-URL, mail, token-pepper, and MFA-key values. All synthetic successful fallbacks and the bootstrap identity live only in profile-gated `application-local.yml`; test values live only in test resources. The Flyway synthetic-foundation-data placeholder is hard-disabled in base/production and enabled only in local/test, so V19 removes the historical untouched fixture from production/default state and aborts if that reserved tenant has changed or acquired dependencies. `application-production.yml` additionally fixes secure production transport defaults and requires the deployment system to supply:

| Boundary | Required external values | Enforced transport behavior |
| --- | --- | --- |
| Runtime PostgreSQL | `DB_URL`, `DB_APP_USERNAME`, `DB_APP_PASSWORD` | JDBC URL must explicitly contain `sslmode=verify-full`; credentials must remain in separate properties. |
| Flyway PostgreSQL | `DB_MIGRATION_URL`, `DB_MIGRATION_USERNAME`, `DB_MIGRATION_PASSWORD`, optionally distinct `DB_APP_ROLE` | Separate migration credentials and `sslmode=verify-full` are required. Run migrations as a controlled deployment job in the final platform design. |
| Redis sessions and throttles | `REDIS_HOST`, optional `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD` | TLS is enabled and the startup guard rejects an override that disables it. |
| Security email | `SMTP_HOST`, optional `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD` | Authentication, STARTTLS, mandatory upgrade, certificate identity checking, and bounded socket timeouts are enabled. |
| Browser identity | `CAREOS_ALLOWED_ORIGINS`, `CAREOS_BASE_URL`, `CAREOS_SECURITY_MAIL_FROM`, `CAREOS_TOKEN_PEPPER`, `CAREOS_MFA_ENCRYPTION_KEY` | Origins and the application base URL must be canonical HTTPS origins; the base URL must be on the allow-list. |
| Approved Module 1 identity administration | Optional `CAREOS_INVITATIONS_ENABLED`, `CAREOS_MFA_ADMINISTRATION_ENABLED`; registry/digest overrides only for an explicitly replaced approved release | Both capabilities default to disabled. Enabling either requires active registry `m1-candidate-1` and package digest `19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946`; mismatches abort startup. |

The startup guard also rejects:

- a mixed `production,local` or `production,test` profile;
- activation of the provisional reference authorization policy or reference service identities, and invitation/MFA-administration activation without the exact approved registry/package digest;
- an insecure session cookie override;
- missing dependency identities or passwords;
- the documented local/test database, token, MFA, service-credential, or object-store material;
- a trivial repeated-byte MFA key;
- duplicate or non-canonical browser origins;
- an S3 HTTP override or runtime bucket creation;
- local S3 credentials when document storage is enabled;
- document promotion without enabled private S3 storage, an explicit policy key, scanner allow-list, and maximum scan age;
- signed document access without enabled private S3 storage, an explicit policy key, purpose allow-list, maximum URL TTL, and maximum authorization age;
- document retention without enabled private S3 storage, runtime bucket creation disabled, an explicit policy key, purpose allow-list, minimum and maximum duration, and maximum authorization age; and
- a non-HTTPS or credential-bearing OTLP endpoint when trace export is explicitly enabled.

Errors identify the failed control but never include the supplied secret. Environment-variable names are an injection interface only: production values must come from an approved secret manager with access audit, rotation, revocation, separation of duties, and recovery procedures. They must not be committed to an env file, image, deployment manifest, log, support bundle, or CI artifact.

## HTTP response policy

Spring Security explicitly writes an API policy on public, authenticated, and error responses:

- `Content-Security-Policy: default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`
- `Permissions-Policy` disables camera, geolocation, microphone, payment, and USB
- `Referrer-Policy: no-referrer`
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- one-year HSTS on requests Spring identifies as secure, without preload or subdomain scope

The Nginx frontend applies an `always` policy to successful and error responses. Its CSP permits only same-origin scripts, styles, fonts, connections, forms, manifests, and workers; same-origin or data images; and no objects or framing. It also emits HSTS, no-referrer, no-sniff, deny-framing, cross-origin opener/resource isolation, a restrictive permissions policy, and legacy cross-domain/XSS hardening. The repository verifier rejects a missing `always`, wildcard CSP source, `unsafe-inline`, `unsafe-eval`, missing header, enabled server token, or unbounded API proxy timeout.

The production frontend build uses `/api`, and Nginx proxies that path to the backend. This keeps browser API traffic same-origin and allows `connect-src 'self'`. Local Vite development uses its own `/api` proxy.

## TLS and proxy ownership still required

The repository Nginx container listens on unprivileged HTTP port 8080; it does not contain certificates or select a public hostname. HSTS is honored by browsers only when received over HTTPS. A production deployment must therefore:

1. terminate approved TLS at an ingress/load balancer or replace the listener with an approved end-to-end TLS topology;
2. redirect plaintext public traffic before it reaches the application;
3. keep the frontend-to-backend hop on an accepted private encrypted or otherwise explicitly approved network boundary;
4. strip and set forwarding headers at the trusted edge rather than trusting client-supplied values;
5. prevent direct public access to the backend and management routes; and
6. test certificate issuance, renewal, revocation, protocol/cipher policy, HSTS behavior, and failure recovery against the real hostnames.

Do not enable HSTS preload or `includeSubDomains` until the domain owner has verified every affected hostname and accepted the recovery consequences.

## Rate limiting and edge controls still required

Redis-backed login and MFA attempt limits already protect the implemented credential flows. General API quotas, upload/body limits, connection limits, bot/abuse policy, DDoS protection, IP reputation, geographic policy, managed WAF rules, and emergency blocking are deployment and owner decisions that this slice does not invent.

Before production acceptance, define per-operation and per-identity limits, trusted proxy/client-IP semantics, accessibility-safe failure behavior, false-positive ownership, bypass/change audit, metrics, alert thresholds, and a load/security test. Enforce coarse network controls at the approved edge and business-aware limits at the application boundary; do not treat either layer as tenant authorization.

## Acceptance evidence still required

- A negative startup test in the chosen deployment platform using intentionally incomplete/insecure configuration.
- TLS and header scans against every public hostname, including redirects and error responses.
- Browser CSP violation monitoring with an approved collection destination and privacy/retention policy before adding reporting directives.
- Secret injection, rotation, revocation, and break-glass exercises without exposing values.
- PostgreSQL, Redis, SMTP, object-storage, and telemetry certificate failure drills.
- Approved WAF/rate-limit configuration plus measured load, abuse, and false-positive tests.
- Confirmation that management endpoints and direct backend access are private and independently authenticated.

Until those checks pass, the new profile and headers are secure repository defaults only—not production infrastructure acceptance.
