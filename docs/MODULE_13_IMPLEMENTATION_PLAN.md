# CareOS Module 13 implementation plan

**Module:** M13 Integrations and FHIR (`P13-01` through `P13-10`)  
**Current phase:** M13A-M13E repository construction complete; module-focused and consolidated repository QA passed  
**Predecessor:** M12 repository PASS through Flyway V110  
**Implementation direction:** user's standing approval applied through repository construction and consolidated QA  
**Production acceptance:** not granted

## Authoritative scope

The build specification defines ten screens: integration dashboard, FHIR endpoints, terminology mappings, WhatsApp messaging, payment configuration, calendar integration, laboratory/imaging interfaces, webhook management, mobile/API clients and integration audit/replay.

Inbound webhooks require verified signatures, timestamp/nonce replay protection and idempotency. Outbound messages originate from versioned transactional-outbox events, and failures retain bounded retry/dead-letter evidence with separately authorized safe replay. FHIR is an external representation rather than the internal schema: mappings and terminology are versioned, resources are profile-validated, import/export provenance is recorded and no broad generic FHIR server is exposed initially.

## Conservative implementation decisions

- Connection records describe bounded provider families and versioned contracts using opaque endpoint/credential-reference aliases and digests. They never persist URLs containing credentials, access tokens, signing secrets, API keys, messaging destinations or provider payloads.
- Repository validation proves configuration completeness only. Every connection retains `operational_enabled=false`; no successful WhatsApp, payment, calendar, laboratory, imaging or mobile transport is fabricated without an approved adapter, partner contract and secret-manager integration.
- FHIR/terminology mappings pin an exact mapping kind, source/target version, base release where applicable, implementation-guide package/version, terminology version and content digest. Activated versions are immutable and successor-based.
- Inbound receipt evidence contains signature scheme/key version, signature/nonce/idempotency/payload digests and timestamps only. Accepted receipts require verified-signature evidence within a five-minute skew window and unique nonce/idempotency digests; raw headers and bodies are not retained.
- Outbound delivery records bind one immutable transactional-outbox event, exact destination connection, event version and payload digest. Attempts are append-only and retain only bounded outcome/error classifications, not response bodies.
- Dead-letter replay never rewrites or reopens the original delivery. A recent-authenticated, MFA-backed browser decision creates an immutable replay authorization; an authorized service may later create one successor delivery with exact lineage after revalidating current connection/policy state.
- FHIR exchange evidence stores direction, resource type, exact mapping/profile versions, resource/identifier digests, validation outcome and provenance reference only. A syntactically or profile-valid resource is never authority to mutate a CareOS aggregate.
- Mobile/API-client records contain public-identifier, scope and credential-reference digests only. Secret material and bearer tokens are prohibited.
- Audit/outbox payloads contain identifiers, artifact types, states, revisions and bounded digests only. They exclude clinical, financial, credential, message and FHIR resource content.

## Repository model

Module 13 adds `integration_connections`, `integration_mapping_versions`, `inbound_webhook_receipts`, `integration_outbound_deliveries`, `integration_delivery_attempts`, `integration_replay_requests`, `fhir_exchange_records` and `integration_api_clients`. All relations are tenant-owned, forced-RLS, UUIDv7 keyed and operation-bound. Receipts, attempts, exchange records and replay decisions are append-only; validated connections, activated mappings and revoked clients cannot be rewritten into a different identity.

## Dependency-ordered slices

| Slice | Screens | Scope | Exit |
| --- | --- | --- | --- |
| M13A | All | Fixed provider/FHIR catalogues, permissions, secret-free contracts and activation boundary | Versioned plan and migration-owned authorization catalogue |
| M13B | P13-01-P13-09 | Governed connection, mapping and API-client registries plus signature/replay/idempotency and exact outbox-delivery evidence | Tenant isolation, immutable versions and payload exclusion fail closed |
| M13C | P13-02-P13-03 | Version-pinned FHIR mapping/profile validation and provenance evidence | No generic FHIR server or internal-schema coupling; unapproved profiles remain unavailable |
| M13D | P13-08-P13-10 | Delivery attempt/dead-letter evidence and separately authorized successor replay | Original evidence remains terminal and safe replay is exact-lineage only |
| M13E | All | OpenAPI/client, ten live routes, backend/frontend/browser gates and closeout | Repository and consolidated QA PASS; provider/profile/secret/worker activation remains separate |

## Completion checkpoint

All five slices are complete at the repository boundary through V113. The focused backend gate passes the Module 13 catalogue, lifecycle and public-registry tests against disposable PostgreSQL 18. OpenAPI 0.52.0 verifies 135 operations and all 38 API-contract cases; generated-client drift is clean; all 141 frontend unit tests and static/build gates pass; and every P13 route passes the five required browser viewport/Axe/overflow projects.

The subsequent clean consolidated run passes 274 backend tests in 57 suites, all 185 browser cases, all 131 repository/API/security contract cases and the exact 195-screen registry. See `MODULE_13_COMPLETION_REPORT.md` and `CONSOLIDATED_QA_REPORT.md` for the complete evidence and retained activation boundary.

## Activation boundary

This plan does not select partner endpoints, credentials, signature algorithms/keys, WhatsApp templates or consent, merchant contracts, calendar scopes, laboratory/imaging standards, FHIR base releases or implementation guides, terminology packages, mobile scopes, destination routing, data residency, retry budgets, monitoring owners or production replay authority. The repository supplies fail-closed registries and evidence mechanics; target interoperability, clinical safety, finance, privacy, security, legal, operations and deployment acceptance remains separate.
