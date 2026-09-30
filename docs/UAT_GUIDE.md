# CareOS UAT guide

## What UAT means for this repository

User acceptance testing (UAT) answers: “Can an authorized user complete the agreed business workflow safely?” It is different from unit, API, browser, and security testing, which answer whether the implementation behaves according to its technical contracts.

This repository can support local or isolated-environment UAT with synthetic data. It does **not** grant production acceptance. Never enter real patient, workforce, payment, credential, or other sensitive data into the local Compose environment.

The synthetic `local_bootstrap` user is displayed as **CareOS UAT Super Administrator** and its registry role as **Local/UAT super administrator**. In a database explicitly provisioned with the local-only capability it receives the same 277 approved human-interactive permissions as the production-capable platform role. It never receives the 10 worker/provider-only permissions, is never production eligible, and cannot bypass tenant isolation, MFA, maker/checker, audit, concurrency, idempotency, or provider readiness. Formal role and maker/checker UAT must use separately invited test identities.

## 1. Start a disposable UAT stack

Requirements: Docker Desktop with Compose v2. Node.js 24 is also required for the automated browser audits.

From the repository root in PowerShell:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose --project-name careos-uat -f compose.yaml -f compose.uat.yaml up --build --detach --wait
docker compose --project-name careos-uat -f compose.yaml -f compose.uat.yaml ps
```

The fixed `careos-uat` project name gives acceptance testing its own named PostgreSQL, Redis and object-storage volumes. It avoids reusing or deleting an existing development stack and must also be supplied to the matching stop/reset commands below.

If `.env` already exists, review it instead of overwriting it. The values in `.env.example` are synthetic local-only credentials and are rejected by the production profile.

Confirm:

- Backend liveness: <http://localhost:8080/livez>
- Backend readiness: <http://localhost:8080/readyz>
- Frontend: <http://localhost:4173/#/M1-05>
- Local email inbox: <http://localhost:8025>

Sign in with the synthetic bootstrap email and password from `.env`. Select the Rootopathy organization and open the workspace.

If an older local volume was created before the local reference-authority capability existed, it remains fail-closed. For a disposable synthetic environment only, recreate its volumes. Do not run this against data that must be retained:

```powershell
docker compose --project-name careos-uat -f compose.yaml -f compose.uat.yaml down --volumes --remove-orphans
docker compose --project-name careos-uat -f compose.yaml -f compose.uat.yaml up --build --detach --wait
```

## 2. Run the automated UAT readiness checks

These commands inspect all protected routes first, then create a small set of uniquely named synthetic records.

```powershell
Push-Location frontend
npm ci
npx playwright install chromium
npm run test:live:audit
$env:CAREOS_UAT_ALLOW_MUTATION = "true"
npm run test:uat:smoke
Remove-Item Env:CAREOS_UAT_ALLOW_MUTATION
Pop-Location
```

The mutation smoke deliberately refuses to run without `CAREOS_UAT_ALLOW_MUTATION=true`. It also refuses a non-local URL unless `CAREOS_UAT_ALLOW_NONLOCAL=true` is separately supplied. Never enable the non-local override for production.

A passing mutation smoke verifies that:

- 16 representative administration and Module 2–13 actions are server-projected;
- a governed service draft can be created;
- two non-clinical workforce onboarding pathways can be started successively without a member-number collision;
- a normal synthetic patient registration can be started;
- its duplicate-search, disposition, validation and submission path completes;
- a small synthetic text document is uploaded into the private quarantine bucket and returned as `quarantined`;
- the urgent temporary identity path remains denied while its reconciliation worker is unavailable;
- no unexpected API failure or browser-console error occurred.

Evidence is written to:

- `frontend/test-results/live-product-audit/`
- `frontend/test-results/uat-smoke/report.json`
- `frontend/test-results/uat-smoke/*.png`
- `frontend/test-results/uat-smoke/trace.zip`

The quality workflow runs both audits against an isolated stack and uploads the evidence against the exact commit.

## 3. Create separate UAT identities

Do not perform formal approval scenarios using one shared account.

1. As the local bootstrap user, open `M1-03` and enroll an authenticator. Retain the one-use recovery codes securely for this disposable test only.
2. Open `M1-02` and issue unique invitations, such as:
   - configuration maker: `configuration_editor`
   - independent checker: `configuration_approver`
   - read-only tester: `organization_viewer`
3. Open Mailpit at <http://localhost:8025>, find each invitation, and use its one-time link in a separate browser profile.
4. Let each tester choose their own synthetic password. Roles that require MFA stay locked to MFA setup until enrollment is complete.
5. Use different people or at least different browser profiles for maker and checker. A maker must not approve their own request.

The `platform_super_administrator` role is deliberately absent from direct invitations. To test it, first invite an existing member into a lower approved human role, then use `M1-20` to request a role change to **Platform super administrator**. A different authorized checker must approve the request before execution. The acting administrator cannot change their own membership, assign a service/worker role, alter the protected final owner through the ordinary role-change path, or cross an organization boundary.

## 4. Super-administrator scope

CareOS provides two deliberately distinct identities:

- `platform_super_administrator` is an active, production-capable, organization-scoped role. It composes every approved human-interactive permission and can govern other users through the existing audited invitation and membership-change workflows. It requires MFA and cannot be assigned directly by invitation.
- `local_bootstrap` is a fixed Local/UAT super administrator. It has the same human workflow surface only when both the application flag and deployment-owned PostgreSQL capability are present. Production startup rejects this bridge and production provisioning must not create the capability.

“Super administrator” does not mean database superuser or unconditional success. A genuine validation error, stale ETag, missing independent approval, missing MFA/recent authentication, absent worker/provider, tenant mismatch, or unavailable clinical policy must still fail closed. Access for another user is enabled or disabled by assigning, changing, suspending, or revoking governed roles—not by mutating individual permission rows at runtime.

An HTTP `428` on a high-assurance action means recent authentication or MFA evidence is missing. Complete the displayed assurance step; do not classify the response as a product failure or bypass it.

## 5. Manual acceptance scenarios

Use a unique marker such as `UAT-YYYYMMDD-INITIALS-NNN` in every synthetic record and reason. Record the outcome and evidence for each case.

| ID | Route | Tester action | Required outcome |
| --- | --- | --- | --- |
| UAT-01 | M1-01, M1-04, M1-05 | Sign in, select the organization, switch workspace, sign out, then sign in again. | Session and organization state remain server-authoritative; no protected page is visible after sign-out. |
| UAT-02 | M1-17 | Add a uniquely coded synthetic service. | A draft is created and displayed with its governed reason; duplicate or stale requests do not create a second conflicting record. |
| UAT-03 | M1-12, M1-14, M1-15 | Create facility, hierarchy, and location drafts using synthetic names. Edit and reparent only within the documented draft flow. | Strong revision/ETag and idempotency behavior is preserved; stale changes are rejected. Activation remains subject to readiness and assurance checks. |
| UAT-04 | M2-03 | Start two non-clinical onboarding pathways in quick succession. | Both receive distinct `WF-<UUID>` member numbers and neither conflicts with the other. |
| UAT-05 | P3-03 | Start a normal, non-urgent synthetic registration. | The registration starts and produces governed evidence. No invented clinical identity data is needed. |
| UAT-06 | P3-03 | Attempt the urgent temporary identity pathway. | It fails closed with the reconciliation-worker dependency; no temporary identity is created. |
| UAT-07 | M2-04 and M2-05 | Open a purpose-bound read and provide a 10–500 character synthetic reason. | Access is audited. The raw reason does not appear in access logs, traces, metrics, browser analytics, or generic error text. |
| UAT-08 | M1-02, M1-03 and high-assurance lifecycle actions | Attempt once without current MFA/re-authentication, then complete the required assurance and retry. | The first request returns the documented boundary; only an assured request proceeds. |
| UAT-09 | Governed configuration flow | Maker creates/submits; a different checker reviews/approves; maker performs only an allowed subsequent step. | Self-approval and stale-revision attempts are denied. Audit/outbox evidence identifies distinct actors. |
| UAT-10 | M1, M2, P3–P13 and COS workspaces | Traverse all protected routes at 1440, 1024, 768, 390 and 320 pixels. | No horizontal document/body overflow, serious Axe violation, exposed internal screen code, empty route, unexpected API failure, or console error. |
| UAT-11 | M1-02, M1-20 | Invite a synthetic member into a lower role; as a different assured actor approve and execute promotion to Platform super administrator; then exercise a governed demotion or revocation. | Direct invitation to super administrator is unavailable; governed promotion succeeds only after independent approval, self-target/service-role/final-owner/cross-tenant attempts remain denied, and audit/outbox evidence is retained. |
| UAT-12 | P7-03 | Upload a small synthetic text document. | The response reports `quarantined`, tenant-derived storage identity and exact length/SHA-256 checks; no public access is created. Scan, clean promotion, signed access and retention remain unavailable unless their separate dependencies are configured. |

Capabilities requiring protected COS material, approved clinical policy, real providers, partner contracts, worker deployment, payment/lab/FHIR credentials, or production infrastructure must remain visibly unavailable. Their fail-closed state is not permission to invent configuration.

## 6. Record UAT evidence

For every failure record:

- exact commit SHA;
- date/time and environment;
- scenario ID and route;
- test role, but never a password, MFA secret, recovery code, or invitation token;
- synthetic record marker;
- expected and actual result;
- correlation/reference ID shown by CareOS;
- screenshot or Playwright trace;
- whether the failure is a core product failure or an external compatibility/dependency failure.

Do not paste sensitive authorization reasons, credentials, tokens, or real personal/clinical information into tickets or support bundles.

## 7. UAT exit decision

UAT can be signed off only when:

- the exact candidate commit has green required quality and security workflows;
- both automated product audits pass and their artifacts are retained;
- all agreed manual scenarios pass with synthetic data;
- maker/checker cases use independent identities;
- all unexpected 4xx/5xx responses, console errors, accessibility defects, and responsive overflows are resolved;
- each accepted fail-closed result is linked to a documented activation dependency;
- the accountable product, clinical, privacy, security, and operations owners sign the target-environment record.

Repository UAT readiness is not production acceptance. Production still requires separately evidenced infrastructure, backup/restore, monitoring/on-call, load, penetration, clinical, privacy, legal, finance, and operational acceptance.

## Stop the environment

Retain the disposable UAT data:

```powershell
docker compose --project-name careos-uat -f compose.yaml -f compose.uat.yaml down
```

Delete only disposable synthetic UAT data:

```powershell
docker compose --project-name careos-uat -f compose.yaml -f compose.uat.yaml down --volumes --remove-orphans
```
