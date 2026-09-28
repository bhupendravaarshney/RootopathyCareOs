# CareOS Module 8 implementation plan

**Module:** M8 AI assistance and governance (`P8-01` through `P8-10`)  
**Current phase:** M8A-M8E repository construction complete; module-focused verification passed  
**Predecessor:** M7 repository PASS through Flyway V95  
**Implementation direction:** user's standing approval to complete repository construction before consolidated QA  
**Production acceptance:** not granted

## Authoritative scope

The build specification defines ten screens: AI session launcher, purpose and consent check, input selection, transcription/extraction, draft summary, clinical suggestion panel, safety/uncertainty flags, source/provenance viewer, clinician review/approval, and AI session history.

The required AI record retains purpose, requester, tenant, patient, encounter and an approved input manifest; provider/model/model-version/prompt-template-version and parameters; output schema, citations/provenance, uncertainty and safety flags; reviewer edits, accept/reject decision, reason and final approved version; and latency, token/cost, retry, failure and retention metadata.

Spring Boot remains the authorization, purpose, data-minimization, workflow and final-record authority. Model-specific processing sits behind a versioned Python-service job port. Raw patient context is never placed on the existing generic job queue or an uncontrolled message bus.

## Conservative implementation decisions

- An AI session belongs to one organization, verified patient and active encounter, and records the requesting actor plus a bounded clinical purpose. No session can process input until explicit consent/legal-basis evidence and minimum-necessary review are recorded.
- Input manifests contain allow-listed typed references, exact source revisions/digests and selection justifications. They do not copy unrestricted clinical narratives into queue, audit or outbox payloads.
- A processing request binds an exact active model release, prompt release, output schema and parameter digest. A model/prompt release is eligible only after an immutable evaluation dataset result and independent safety sign-off.
- The production AI processing port is unavailable by default and throws before network I/O. A deterministic synthetic adapter may exist only inside isolated tests. Provider configuration or a successful test does not constitute clinical or production approval.
- Every provider return is stored as a visibly labeled draft. Output revisions are append-only; clinician edits create a new version. Acceptance is never preselected and requires an eligible clinician, explicit decision, reason, exact revision and recent authenticated MFA.
- Citations bind exact input-manifest items and bounded source locators. Unsupported citations, missing provenance, unknown output schema, or provider/model/prompt drift reject the result.
- Safety and uncertainty flags remain visible. Emergency or critical flags create/require a clinical escalation reference and block approval; generative reassurance can never close them.
- Provider latency, token counts, bounded cost/currency, retries, failures and retention metadata are recorded separately from clinical output. Audit/outbox payloads carry identifiers, state and digests only.
- AI output never mutates an encounter, assessment, diagnosis, care plan or document directly. A separately authorized clinician action must explicitly adopt approved content into the relevant source-of-truth module.

## Repository model

The Module 8 boundary uses `ai_sessions`, `ai_purpose_consents`, `ai_input_manifests`, `ai_input_manifest_items`, `ai_model_releases`, `ai_prompt_releases`, `ai_evaluation_signoffs`, `ai_job_contracts`, `ai_job_attempts`, `ai_outputs`, `ai_output_versions`, `ai_output_citations`, `ai_safety_flags`, `ai_safety_escalations`, `ai_reviews`, `ai_usage_records` and `ai_retention_metadata`.

All tenant-owned rows use composite organization foreign keys, forced RLS, UUIDv7 identifiers and server-owned timestamps. Model/prompt/evaluation records are runtime read-only once activated; manifests, job attempts, outputs, versions, citations, safety flags, reviews, usage and retention evidence are append-only.

## Lifecycles

AI sessions follow `draft -> authorized -> input_ready -> processing -> draft_ready -> under_review -> accepted | rejected | cancelled`. A failed processing attempt moves the session to `failed`; an explicit resubmission from the same approved manifest may start a new immutable job contract/attempt, while prior failure evidence remains unchanged. `accepted`, `rejected` and `cancelled` are terminal.

AI outputs remain `draft` until an attributed clinician decision. Editing appends an output version. Reviews follow `accepted | rejected`; no default decision exists. Safety flags follow `open -> acknowledged -> resolved`, while emergency/critical flags require a linked clinical escalation before acknowledgement and resolution.

## Dependency-ordered slices

| Slice | Screens | Scope | Exit |
| --- | --- | --- | --- |
| M8A | All | Screen/action contract, lifecycle, authorization, minimization, model/prompt/evaluation and provider boundaries | Versioned plan and migration-owned catalogues |
| M8B | P8-01-P8-03 | Session, purpose/consent and immutable minimum-necessary input manifest | Wrong-patient, missing-consent and manifest-drift attacks pass |
| M8C | P8-04-P8-08 | Versioned job contract, fail-closed processing port, draft outputs, citations, provenance, uncertainty, safety, usage/failure/retention evidence | No raw context on generic queues; provider/result drift fails closed |
| M8D | P8-09-P8-10 | Clinician edits, explicit accept/reject, emergency escalation blocking and immutable session history | No preselected acceptance or unresolved critical approval |
| M8E | All | OpenAPI/client, all ten live routes, backend/frontend/browser/security gates and closeout | Repository PASS; consolidated QA/target acceptance remain separate |

## Required controls

- Organization-bound authorization transactions, forced RLS and composite tenant foreign keys for every tenant relation.
- UUIDv7 identifiers, strong revisions, scoped idempotency, exact digests, bounded fields and RFC 9457 failures.
- Explicit purpose/consent/legal-basis and minimum-necessary evidence before any processing request.
- Exact model, prompt, evaluation, schema and parameter versions with immutable rollout/sign-off evidence.
- Draft-only provider results, append-only reviewer edits, no preselected acceptance, eligible clinician/recent-MFA approval and visible uncertainty/safety state.
- Payload-minimized audit/outbox events containing identifiers, state and digests, never raw patient context, prompts, output narratives, provider credentials or unrestricted cost data.
- Five-viewport keyboard, Axe and overflow verification for every P8 route.

## Activation boundary

This plan does not approve an AI provider, region, model, prompt, evaluation dataset, safety threshold, consent/legal-basis rule, data-residency/retention/training policy, token/cost budget, clinical-use case, escalation pathway or Python-service deployment. Repository construction uses a fail-closed provider port and synthetic-only tests. Production activation requires accountable clinical, privacy, security, financial and operational acceptance plus target-environment evidence.

## Completion checklist

- [x] V96 releases the exact migration-owned Module 8 permission, operation and event catalogues.
- [x] V97 creates all 17 governed AI relations with composite tenant integrity, UUIDv7 identifiers, forced RLS and immutable evidence.
- [x] V98 adds payload-minimized audit/outbox events plus database lifecycle, model/prompt/evaluation, manifest, processing, provenance, safety, review and retention guards.
- [x] `P8-01` through `P8-10` use runtime-validated server projections and expose only server-projected actions.
- [x] The processing port is unavailable by default; failures are explicit and may be manually resubmitted without rewriting prior attempts.
- [x] Clinician edits append a new version and retain citation lineage; acceptance requires a reason, recent MFA and resolved critical/emergency safety flags.
- [x] OpenAPI 3.1 version 0.47.0 verifies exactly 125 operations, and all 28 contract-verifier cases pass.
- [x] The generated client has no drift; formatting, strict typecheck, lint, the 51-source/9-feature/146-import boundary, all 116 frontend unit tests and the production build pass.
- [x] The focused backend gate compiles 433 production and 46 test sources, applies V1-V98 to PostgreSQL 18 and passes 5/5 catalogue, registry and lifecycle tests.
- [x] Every Module 8 route passes the focused Playwright/Axe/overflow gate at 1440, 1024, 768, 390 and 320 pixels.

See `MODULE_8_COMPLETION_REPORT.md` for the verified repository boundary and the activation work that deliberately remains open.
