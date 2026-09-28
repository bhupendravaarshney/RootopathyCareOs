# CareOS Module 11 implementation plan

**Module:** M11 Billing and Payments (`P11-01` through `P11-11`)  
**Current phase:** M11A-M11E repository construction complete; module-focused verification passed and consolidated QA deferred
**Predecessor:** M10 repository PASS through Flyway V104  
**Implementation direction:** user's standing approval to complete repository construction before consolidated QA  
**Production acceptance:** not granted

The completed repository evidence is recorded in `MODULE_11_COMPLETION_REPORT.md`.

## Authoritative scope

The build specification defines eleven screens: billing dashboard, price books, packages, estimate, invoice, payment, payment link, refund/adjustment, claims, reconciliation and financial audit/export.

The required model contains price books/items, packages/entitlements, estimates, invoices/items, payment intents/payments, refunds, adjustments, claims, remittances, reconciliations and financial exports. Clinical completion must never depend on payment state; CareOS must never store raw card data; provider callbacks must be signed, idempotent and amount/currency matched; refunds and adjustments require reason, authorization and immutable evidence.

## Conservative implementation decisions

- Every monetary artifact uses integer minor units and one uppercase ISO-style three-letter currency snapshot. Cross-currency allocation, implicit rounding and client-calculated totals are rejected.
- Price books and packages begin as drafts. Activation freezes an exact digest and their items/entitlements become immutable; later changes require a separately versioned successor rather than in-place edits.
- An estimate binds one verified patient and an optional exact appointment. Finalization freezes the selected active price/package source, quantity, tax, validity and totals. An invoice is issued only from that exact finalized estimate and retains immutable line snapshots.
- Invoice issue, manual payment, refund, adjustment and claim remittance create append-only monetary evidence. The invoice balance is a guarded derived accumulator; direct balance edits or evidence deletion are rejected.
- A payment intent contains only CareOS/provider opaque references, expected amount/currency and lifecycle metadata. Raw card number, CVV, track data, expiry, bank secret, provider token and hosted-payment bearer URL are neither accepted nor persisted.
- Browser actions may record only approved non-card manual settlement evidence. Provider-originated settlement enters through a separate service-identity and signature-verification boundary; its transport adapter remains unavailable until Module 13 supplies an accepted provider integration.
- Refunds cannot exceed settled payment less prior refunds. Adjustments are signed, reason-bound, recent-MFA decisions and cannot make the adjusted invoice total negative.
- Claims bind one exact invoice/currency. Submission freezes the claim amount; remittances are append-only, cannot exceed the claim and update invoice settlement without rewriting clinical records.
- Reconciliations snapshot expected, observed and variance amounts for an exact period/reference digest. A non-zero variance remains an exception until explicitly resolved with reason and authorization.
- Financial export requests retain purpose, bounded period/filter digest, format, actor and expiry only. Export generation and artifact delivery remain unavailable until an authorized worker and retention/access policy are activated.
- Audit/outbox payloads carry identifiers, artifact types, states and revisions only; they exclude card data, provider secrets, patient names, clinical narratives and unrestricted financial content.

## Repository model

Module 11 adds `price_books`, `price_items`, `packages`, `package_entitlements`, `estimates`, `invoices`, `invoice_items`, `payment_intents`, `payments`, `refunds`, `adjustments`, `claims`, `remittances`, `reconciliations` and `financial_exports`.

All tenant-owned rows use composite organization foreign keys, forced RLS, UUIDv7 identifiers and server-owned timestamps. Activated catalogues, finalized estimates, issued line items, settlement/refund/adjustment/remittance evidence and export requests are immutable or one-way state guarded.

## Dependency-ordered slices

| Slice | Screens | Scope | Exit |
| --- | --- | --- | --- |
| M11A | All | Exact screen/action contract, authorization, minor-unit/currency rules, PCI minimization and provider boundary | Versioned plan and migration-owned catalogues |
| M11B | P11-02-P11-05 | Price books/items, packages/entitlements, finalized estimates and issued invoices | Stale price, client-total, cross-currency and post-activation mutation attacks fail |
| M11C | P11-06-P11-08 | Payment intents, manual settlement, refund and adjustment evidence | Raw-card fields fail; overpayment/refund and unauthorized balance edits fail |
| M11D | P11-09-P11-11 | Claims/remittances, reconciliation and purpose-bound export requests | Over-remittance, unexplained variance and unsafe export behavior fail |
| M11E | All | OpenAPI/client, all eleven live routes, backend/frontend/browser/security gates and closeout | Repository PASS; provider, consolidated QA and target acceptance remain separate |

## Activation boundary

This plan does not approve currencies, tax rules, price/package catalogues, discounts, invoice numbering, payment methods, merchant/provider credentials, callback algorithms/secrets, refund/adjustment authority, insurer/payer formats, claim/remittance standards, accounting treatment, export contents, retention or patient financial communication. The repository supplies fail-closed versioned mechanics; target finance, tax, PCI, privacy, legal, operational and deployment acceptance remains separate.
