# CareOS Module 11 completion report

**Module:** M11 Billing and Payments (`P11-01` through `P11-11`)  
**Repository construction status:** complete and module-focused verification passed  
**Verification date:** 28 September 2026  
**Consolidated QA:** deferred under the project's continuous execution direction  
**Production status:** not approved; finance/tax/accounting policy, provider credentials and callback verification, payer formats, export workers and target-environment acceptance remain separate

## Completed scope

- V105 releases the migration-owned Module 11 roles, permissions and operations. V106 creates all fifteen required forced-RLS billing relations with append-only monetary evidence and least-privilege runtime grants. V107 adds payload-minimized audit/outbox contracts, deterministic catalogue/estimate/invoice digests, exact lifecycle guards and derived invoice-balance enforcement.
- `P11-01` through `P11-11` use checked tenant-authorized projections and governed actions for dashboards, price books, packages, estimates, invoices, manual payments, card-data-free payment intents, refunds/adjustments, claims/remittances, reconciliation and financial export requests.
- Monetary values are integer minor units with an immutable currency snapshot. Activated pricing/package content, finalized estimates, issued invoice lines and settlement/refund/adjustment/remittance evidence cannot be rewritten or deleted.
- Invoice balances are derived from immutable evidence. Overpayment, over-refund, over-remittance, cross-currency drift, client-computed total drift, stale revisions and unauthorized direct balance changes fail closed.
- Financial status is independent of clinical completion. Raw card data, CVV/PAN fields, provider tokens and bearer payment links are rejected and have no persistence columns. The provider callback transport remains unavailable until an approved provider/signature policy is activated; no successful callback or hosted link is fabricated.
- Refunds, adjustments, invoice voids, catalogue activation and financial exports require explicit reasons and current authorization; the high-risk actions also require recent authentication and MFA. Audit/outbox payloads contain only bounded identifiers, state and revision evidence.

## Repository evidence

| Gate | Result | Evidence |
| --- | --- | --- |
| Backend compilation and billing verification | PASS | Java 25/Maven 3.9.11 compiles 457 production and 52 test sources. The focused catalogue/registry/lifecycle run validates and applies V1-V107 to disposable PostgreSQL 18 and passes 5/5 tests. |
| Financial lifecycle and governance evidence | PASS | The integration path activates pricing and a package, finalizes an exact estimate, issues immutable invoice lines, creates a card-data-free intent, records payment/refund/credit, submits a claim and remittance, resolves a reconciliation variance, requests an export, verifies the derived balance and rejects raw-card input and evidence mutation. |
| API and generated client | PASS | OpenAPI 3.1 version 0.50.0 verifies exactly 131 operations; generated TypeScript has no drift and all 34 positive/negative API-contract cases pass, including exact Module 11 screen/action/query bounds. |
| Frontend static/unit/build | PASS | Formatting, strict typecheck, lint, the 60-source/12-feature/171-import boundary plus four negative fixtures, all 131 unit tests and the production build pass. |
| Module 11 browser/accessibility/responsive | PASS | Every P11 route passes in 5/5 Playwright projects across exact 1440, 1024, 768, 390 and 320 widths, including Axe and overflow checks. |
| Public catalogue | PASS | The public registry reports 175 screens: M1 23, M2 29, M3 16, M4 15, M5 12, COS 27, P7 11, P8 10, P9 12, P10 9 and P11 11. |

## Deferred QA and activation

The project-wide cross-module regression, supply-chain/image/deployment reruns and target-environment acceptance remain deferred until consolidated QA. This report is repository-construction evidence, not authority to bill, collect payment, submit claims or export financial data in production.

Production activation still requires approved currencies, price/tax/discount/accounting rules, numbering policy, merchant/provider credentials, callback signature and replay policy, refund/adjustment authority, payer/claim/remittance standards, export contents and retention, patient financial communication, monitoring and accountable finance, privacy, security, legal and deployment sign-off. These inputs remain explicit fail-closed boundaries.

## Next dependency

Module 12 Reporting and Analytics must build purpose-bound operational/clinical/financial projections without treating derived analytics as source clinical or financial evidence.
