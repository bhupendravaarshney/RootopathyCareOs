-- Module 11 repository authorization release under the standing implementation direction.
-- Finance/tax policy, payment providers, payer formats and production activation remain separate.
INSERT INTO authorization_registry_releases
    (registry_version,approval_record_id,approval_package_sha256,
     authorization_artifact_sha256,approval_evidence_sha256,
     approved_by,approved_at,status,module_key)
VALUES
    ('m11-standing-direction-v1','PROJECT-STANDING-DIRECTION-20260926-M11',
     'b5df6e6a62ef61ec7cb74ab9f2b7d0c4e86ac2e1f533a69a60f0c4aabf7196c2',
     '3b0a37aedb1921c6eb53ca9cb53a7dc2936f6f447843ed41e105ed1ac3982c74',
     '697bc0794fd92924e820958850d6f63140131533347ec0b1def35eb92c31ed27',
     'bhupendra, developer','2026-09-28T12:00:00Z','active','M11');

INSERT INTO authorization_permissions
    (permission_key,display_name,description,status,registry_version,scope,risk_class)
VALUES
    ('billing.read','Read billing','Read minimum-necessary catalog, invoice, settlement, claim and reconciliation projections.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.pricebook.create','Create price book','Create a versioned draft price book.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.pricebook.item.add','Add price item','Append a server-priced item to a draft price book.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.pricebook.activate','Activate price book','Freeze and activate an exact complete price-book version.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.package.create','Create package','Create a draft package bound to an active price book.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.package.entitlement.add','Add package entitlement','Append an exact service entitlement to a draft package.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.package.activate','Activate package','Freeze and activate an exact complete package version.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.estimate.create','Create estimate','Create a patient-bound estimate using authoritative active pricing.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.estimate.finalize','Finalize estimate','Freeze the exact amount, tax, validity and source snapshot.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.invoice.issue','Issue invoice','Issue an invoice and immutable line snapshot from a finalized estimate.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.invoice.void','Void invoice','Void an unpaid invoice without deleting financial evidence.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.payment.intent.create','Create payment intent','Create a card-data-free expected amount and currency intent.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.payment.record','Record manual payment','Append authorized non-card settlement evidence to an invoice.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.payment.provider.record','Record verified provider payment','Append a signature-verified idempotent provider settlement event.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.refund.record','Record refund','Append an authorized reason-bound refund without exceeding settlement.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.adjustment.record','Record adjustment','Append an authorized reason-bound debit or credit adjustment.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.claim.create','Create claim','Create an invoice-bound payer claim.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.claim.submit','Submit claim','Freeze and submit an exact claim amount and currency.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.remittance.record','Record remittance','Append exact payer remittance evidence and allocation.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.reconciliation.create','Create reconciliation','Snapshot expected, observed and variance amounts for a bounded period.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.reconciliation.complete','Resolve reconciliation','Resolve an exact variance with attributable reason.','active','m11-standing-direction-v1','organization','critical'),
    ('billing.export.create','Request financial export','Create a purpose-bound bounded financial export request.','active','m11-standing-direction-v1','organization','critical');

INSERT INTO authorization_roles
    (role_key,display_name,description,status,registry_version,
     interactive,invitation_assignable,final_owner)
VALUES
    ('billing_administrator','Billing administrator','Manages versioned pricing, invoices, settlement evidence and reconciliation.','active','m11-standing-direction-v1',true,true,false),
    ('claims_officer','Claims officer','Manages invoice-bound claims and remittance evidence.','active','m11-standing-direction-v1',true,true,false),
    ('financial_auditor','Financial auditor','Reads minimum-necessary finance evidence and requests governed exports.','active','m11-standing-direction-v1',true,true,false),
    ('service_m11_payment','Module 11 payment callback','Records only verified provider settlement events.','active','m11-standing-direction-v1',false,false,false);

INSERT INTO authorization_role_permissions (role_key,permission_key)
SELECT role_key,permission_key
FROM (VALUES ('organization_owner'),('local_bootstrap'),('billing_administrator')) roles(role_key)
CROSS JOIN authorization_permissions permission
WHERE permission.registry_version='m11-standing-direction-v1'
  AND permission.permission_key<>'billing.payment.provider.record';

INSERT INTO authorization_role_permissions (role_key,permission_key)
VALUES
    ('claims_officer','billing.read'),
    ('claims_officer','billing.claim.create'),
    ('claims_officer','billing.claim.submit'),
    ('claims_officer','billing.remittance.record'),
    ('financial_auditor','billing.read'),
    ('financial_auditor','billing.reconciliation.create'),
    ('financial_auditor','billing.reconciliation.complete'),
    ('financial_auditor','billing.export.create'),
    ('service_m11_payment','billing.payment.provider.record');

INSERT INTO authorization_operations
    (operation_key,permission_key,display_name,description,mutation,denial_mode,
     reason_required,recent_authentication_required,
     recent_authentication_max_age_seconds,maximum_future_skew_seconds,
     maker_checker_required,status,registry_version,mfa_required)
SELECT permission_key,permission_key,display_name,description,
       permission_key<>'billing.read','explicit',permission_key<>'billing.read',
       permission_key IN (
           'billing.pricebook.activate','billing.package.activate','billing.invoice.void',
           'billing.refund.record','billing.adjustment.record','billing.export.create'),
       CASE WHEN permission_key IN (
           'billing.pricebook.activate','billing.package.activate','billing.invoice.void',
           'billing.refund.record','billing.adjustment.record','billing.export.create') THEN 600 ELSE NULL END,
       CASE WHEN permission_key IN (
           'billing.pricebook.activate','billing.package.activate','billing.invoice.void',
           'billing.refund.record','billing.adjustment.record','billing.export.create') THEN 5 ELSE NULL END,
       false,'active','m11-standing-direction-v1',
       permission_key IN (
           'billing.pricebook.activate','billing.package.activate','billing.invoice.void',
           'billing.refund.record','billing.adjustment.record','billing.export.create')
FROM authorization_permissions
WHERE registry_version='m11-standing-direction-v1';
