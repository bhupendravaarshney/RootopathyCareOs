CREATE TABLE price_books (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    book_code varchar(40) NOT NULL,
    display_name varchar(160) NOT NULL,
    currency char(3) NOT NULL,
    version_number integer NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    configuration_digest char(64),
    activated_at timestamptz,
    activated_by uuid,
    activation_reason varchar(500),
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,book_code,version_number),
    CHECK (book_code~'^[A-Z0-9][A-Z0-9_-]{1,39}$'),
    CHECK (char_length(btrim(display_name)) BETWEEN 2 AND 160),
    CHECK (currency~'^[A-Z]{3}$'),
    CHECK (version_number BETWEEN 1 AND 1000000),
    CHECK (effective_to IS NULL OR effective_to>=effective_from),
    CHECK (configuration_digest IS NULL OR configuration_digest~'^[0-9a-f]{64}$'),
    CHECK ((activated_at IS NULL)=(activated_by IS NULL)),
    CHECK ((activated_at IS NULL)=(activation_reason IS NULL)),
    CHECK (activation_reason IS NULL OR char_length(btrim(activation_reason)) BETWEEN 10 AND 500),
    CHECK (status IN ('draft','active','retired')),
    CHECK (status='draft' OR (configuration_digest IS NOT NULL AND activated_at IS NOT NULL)),
    CHECK (lock_version>=0)
);

CREATE UNIQUE INDEX price_books_one_active_code_uq
    ON price_books(organization_id,book_code) WHERE status='active';

CREATE TABLE price_items (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    price_book_id uuid NOT NULL,
    service_id uuid NOT NULL,
    item_sequence integer NOT NULL,
    item_code varchar(64) NOT NULL,
    display_name varchar(200) NOT NULL,
    currency char(3) NOT NULL,
    unit_amount_minor bigint NOT NULL,
    tax_basis_points integer NOT NULL DEFAULT 0,
    effective_from date NOT NULL,
    effective_to date,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,price_book_id,item_sequence),
    UNIQUE (organization_id,price_book_id,item_code),
    UNIQUE (organization_id,price_book_id,service_id),
    FOREIGN KEY (organization_id,price_book_id) REFERENCES price_books(organization_id,id),
    FOREIGN KEY (organization_id,service_id) REFERENCES service_definitions(organization_id,id),
    CHECK (item_sequence BETWEEN 1 AND 10000),
    CHECK (item_code~'^[A-Z0-9][A-Z0-9_.-]{1,63}$'),
    CHECK (char_length(btrim(display_name)) BETWEEN 2 AND 200),
    CHECK (currency~'^[A-Z]{3}$'),
    CHECK (unit_amount_minor>=0),
    CHECK (tax_basis_points BETWEEN 0 AND 10000),
    CHECK (effective_to IS NULL OR effective_to>=effective_from),
    CHECK (status='active'),
    CHECK (lock_version=0)
);

CREATE TABLE packages (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    price_book_id uuid NOT NULL,
    package_code varchar(64) NOT NULL,
    display_name varchar(200) NOT NULL,
    currency char(3) NOT NULL,
    package_amount_minor bigint NOT NULL,
    version_number integer NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    configuration_digest char(64),
    activated_at timestamptz,
    activated_by uuid,
    activation_reason varchar(500),
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,package_code,version_number),
    FOREIGN KEY (organization_id,price_book_id) REFERENCES price_books(organization_id,id),
    CHECK (package_code~'^[A-Z0-9][A-Z0-9_.-]{1,63}$'),
    CHECK (char_length(btrim(display_name)) BETWEEN 2 AND 200),
    CHECK (currency~'^[A-Z]{3}$'),
    CHECK (package_amount_minor>=0),
    CHECK (version_number BETWEEN 1 AND 1000000),
    CHECK (effective_to IS NULL OR effective_to>=effective_from),
    CHECK (configuration_digest IS NULL OR configuration_digest~'^[0-9a-f]{64}$'),
    CHECK ((activated_at IS NULL)=(activated_by IS NULL)),
    CHECK ((activated_at IS NULL)=(activation_reason IS NULL)),
    CHECK (activation_reason IS NULL OR char_length(btrim(activation_reason)) BETWEEN 10 AND 500),
    CHECK (status IN ('draft','active','retired')),
    CHECK (status='draft' OR (configuration_digest IS NOT NULL AND activated_at IS NOT NULL)),
    CHECK (lock_version>=0)
);

CREATE UNIQUE INDEX packages_one_active_code_uq
    ON packages(organization_id,package_code) WHERE status='active';

CREATE TABLE package_entitlements (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    package_id uuid NOT NULL,
    service_id uuid NOT NULL,
    entitlement_sequence integer NOT NULL,
    quantity integer NOT NULL,
    expires_after_days integer,
    status varchar(24) NOT NULL DEFAULT 'active',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,package_id,entitlement_sequence),
    UNIQUE (organization_id,package_id,service_id),
    FOREIGN KEY (organization_id,package_id) REFERENCES packages(organization_id,id),
    FOREIGN KEY (organization_id,service_id) REFERENCES service_definitions(organization_id,id),
    CHECK (entitlement_sequence BETWEEN 1 AND 1000),
    CHECK (quantity BETWEEN 1 AND 10000),
    CHECK (expires_after_days IS NULL OR expires_after_days BETWEEN 1 AND 3650),
    CHECK (status='active'),
    CHECK (lock_version=0)
);

CREATE TABLE estimates (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    appointment_id uuid,
    price_book_id uuid NOT NULL,
    source_price_item_id uuid,
    source_package_id uuid,
    estimate_number varchar(64) NOT NULL,
    currency char(3) NOT NULL,
    quantity integer NOT NULL,
    unit_amount_minor bigint NOT NULL,
    subtotal_minor bigint NOT NULL,
    tax_minor bigint NOT NULL,
    total_minor bigint NOT NULL,
    valid_until date NOT NULL,
    content_digest char(64),
    finalized_at timestamptz,
    finalized_by uuid,
    finalization_reason varchar(500),
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,estimate_number),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,appointment_id) REFERENCES appointments(organization_id,id),
    FOREIGN KEY (organization_id,price_book_id) REFERENCES price_books(organization_id,id),
    FOREIGN KEY (organization_id,source_price_item_id) REFERENCES price_items(organization_id,id),
    FOREIGN KEY (organization_id,source_package_id) REFERENCES packages(organization_id,id),
    CHECK ((source_price_item_id IS NOT NULL)::integer+(source_package_id IS NOT NULL)::integer=1),
    CHECK (estimate_number~'^[A-Z0-9][A-Z0-9_.-]{3,63}$'),
    CHECK (currency~'^[A-Z]{3}$'),
    CHECK (quantity BETWEEN 1 AND 10000),
    CHECK (unit_amount_minor>=0 AND subtotal_minor>=0 AND tax_minor>=0 AND total_minor>=0),
    CHECK (subtotal_minor=unit_amount_minor*quantity),
    CHECK (total_minor=subtotal_minor+tax_minor),
    CHECK (content_digest IS NULL OR content_digest~'^[0-9a-f]{64}$'),
    CHECK ((finalized_at IS NULL)=(finalized_by IS NULL)),
    CHECK ((finalized_at IS NULL)=(finalization_reason IS NULL)),
    CHECK (finalization_reason IS NULL OR char_length(btrim(finalization_reason)) BETWEEN 10 AND 500),
    CHECK (status IN ('draft','finalized','expired','cancelled')),
    CHECK (status='draft' OR content_digest IS NOT NULL),
    CHECK (lock_version>=0)
);

CREATE INDEX estimates_patient_created_idx
    ON estimates(organization_id,patient_id,created_at DESC,id);

CREATE TABLE invoices (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    patient_id uuid NOT NULL,
    appointment_id uuid,
    encounter_id uuid,
    estimate_id uuid NOT NULL,
    invoice_number varchar(64) NOT NULL,
    currency char(3) NOT NULL,
    subtotal_minor bigint NOT NULL,
    tax_minor bigint NOT NULL,
    original_total_minor bigint NOT NULL,
    adjustment_minor bigint NOT NULL DEFAULT 0,
    paid_minor bigint NOT NULL DEFAULT 0,
    refunded_minor bigint NOT NULL DEFAULT 0,
    balance_minor bigint NOT NULL,
    issued_at timestamptz NOT NULL,
    due_on date NOT NULL,
    voided_at timestamptz,
    voided_by uuid,
    void_reason varchar(500),
    snapshot_digest char(64) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'issued',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,invoice_number),
    UNIQUE (organization_id,estimate_id),
    FOREIGN KEY (organization_id,patient_id) REFERENCES patient_profiles(organization_id,id),
    FOREIGN KEY (organization_id,appointment_id) REFERENCES appointments(organization_id,id),
    FOREIGN KEY (organization_id,encounter_id) REFERENCES encounters(organization_id,id),
    FOREIGN KEY (organization_id,estimate_id) REFERENCES estimates(organization_id,id),
    CHECK (invoice_number~'^[A-Z0-9][A-Z0-9_.-]{3,63}$'),
    CHECK (currency~'^[A-Z]{3}$'),
    CHECK (subtotal_minor>=0 AND tax_minor>=0 AND original_total_minor>=0),
    CHECK (original_total_minor=subtotal_minor+tax_minor),
    CHECK (paid_minor>=0 AND refunded_minor>=0),
    CHECK (balance_minor=original_total_minor+adjustment_minor-paid_minor+refunded_minor),
    CHECK (balance_minor>=0),
    CHECK (snapshot_digest~'^[0-9a-f]{64}$'),
    CHECK ((voided_at IS NULL)=(voided_by IS NULL)),
    CHECK ((voided_at IS NULL)=(void_reason IS NULL)),
    CHECK (void_reason IS NULL OR char_length(btrim(void_reason)) BETWEEN 10 AND 500),
    CHECK (status IN ('issued','partially_paid','paid','void')),
    CHECK (status<>'paid' OR balance_minor=0),
    CHECK (status<>'void' OR paid_minor=refunded_minor),
    CHECK (lock_version>=0)
);

CREATE INDEX invoices_patient_status_idx
    ON invoices(organization_id,patient_id,status,issued_at DESC,id);

CREATE TABLE invoice_items (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    invoice_id uuid NOT NULL,
    service_id uuid,
    price_item_id uuid,
    package_id uuid,
    line_sequence integer NOT NULL,
    description varchar(240) NOT NULL,
    quantity integer NOT NULL,
    unit_amount_minor bigint NOT NULL,
    subtotal_minor bigint NOT NULL,
    tax_minor bigint NOT NULL,
    total_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'issued',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,invoice_id,line_sequence),
    FOREIGN KEY (organization_id,invoice_id) REFERENCES invoices(organization_id,id),
    FOREIGN KEY (organization_id,service_id) REFERENCES service_definitions(organization_id,id),
    FOREIGN KEY (organization_id,price_item_id) REFERENCES price_items(organization_id,id),
    FOREIGN KEY (organization_id,package_id) REFERENCES packages(organization_id,id),
    CHECK ((price_item_id IS NOT NULL)::integer+(package_id IS NOT NULL)::integer=1),
    CHECK (line_sequence BETWEEN 1 AND 1000),
    CHECK (char_length(btrim(description)) BETWEEN 2 AND 240),
    CHECK (quantity BETWEEN 1 AND 10000),
    CHECK (unit_amount_minor>=0 AND subtotal_minor>=0 AND tax_minor>=0 AND total_minor>=0),
    CHECK (subtotal_minor=unit_amount_minor*quantity),
    CHECK (total_minor=subtotal_minor+tax_minor),
    CHECK (currency~'^[A-Z]{3}$'),
    CHECK (status='issued'),
    CHECK (lock_version=0)
);

CREATE TABLE payment_intents (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    invoice_id uuid NOT NULL,
    intent_reference varchar(80) NOT NULL,
    provider_key varchar(80) NOT NULL,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    expires_at timestamptz NOT NULL,
    provider_reference_digest char(64),
    status varchar(24) NOT NULL DEFAULT 'awaiting_provider',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,intent_reference),
    FOREIGN KEY (organization_id,invoice_id) REFERENCES invoices(organization_id,id),
    CHECK (intent_reference~'^[A-Za-z0-9][A-Za-z0-9._:-]{15,79}$'),
    CHECK (provider_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (amount_minor>0),
    CHECK (currency~'^[A-Z]{3}$'),
    CHECK (provider_reference_digest IS NULL OR provider_reference_digest~'^[0-9a-f]{64}$'),
    CHECK (status IN ('awaiting_provider','authorized','succeeded','failed','cancelled','expired')),
    CHECK (lock_version>=0)
);

CREATE TABLE payments (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    invoice_id uuid NOT NULL,
    payment_intent_id uuid,
    payment_reference varchar(120) NOT NULL,
    source_key varchar(24) NOT NULL,
    provider_key varchar(80),
    provider_event_id varchar(160),
    provider_event_digest char(64),
    signature_key_id varchar(80),
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    occurred_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'settled',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,payment_reference),
    UNIQUE (organization_id,provider_key,provider_event_id),
    FOREIGN KEY (organization_id,invoice_id) REFERENCES invoices(organization_id,id),
    FOREIGN KEY (organization_id,payment_intent_id) REFERENCES payment_intents(organization_id,id),
    CHECK (char_length(btrim(payment_reference)) BETWEEN 4 AND 120),
    CHECK (source_key IN ('manual_cash','manual_bank','manual_other','provider')),
    CHECK ((source_key='provider')=(provider_key IS NOT NULL)),
    CHECK ((source_key='provider')=(provider_event_id IS NOT NULL)),
    CHECK ((source_key='provider')=(provider_event_digest IS NOT NULL)),
    CHECK ((source_key='provider')=(signature_key_id IS NOT NULL)),
    CHECK (provider_key IS NULL OR provider_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (provider_event_id IS NULL OR char_length(provider_event_id) BETWEEN 4 AND 160),
    CHECK (provider_event_digest IS NULL OR provider_event_digest~'^[0-9a-f]{64}$'),
    CHECK (signature_key_id IS NULL OR signature_key_id~'^[A-Za-z0-9][A-Za-z0-9_.:-]{1,79}$'),
    CHECK (amount_minor>0),
    CHECK (currency~'^[A-Z]{3}$'),
    CHECK (status='settled'),
    CHECK (lock_version=0)
);

CREATE TABLE refunds (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    invoice_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    refund_reference varchar(120) NOT NULL,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    reason varchar(500) NOT NULL,
    authorized_at timestamptz NOT NULL,
    authorized_by uuid NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'settled',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,refund_reference),
    FOREIGN KEY (organization_id,invoice_id) REFERENCES invoices(organization_id,id),
    FOREIGN KEY (organization_id,payment_id) REFERENCES payments(organization_id,id),
    CHECK (char_length(btrim(refund_reference)) BETWEEN 4 AND 120),
    CHECK (amount_minor>0),
    CHECK (currency~'^[A-Z]{3}$'),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 500),
    CHECK (status='settled'),
    CHECK (lock_version=0)
);

CREATE TABLE adjustments (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    invoice_id uuid NOT NULL,
    adjustment_reference varchar(120) NOT NULL,
    direction_key varchar(16) NOT NULL,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    reason varchar(500) NOT NULL,
    authorized_at timestamptz NOT NULL,
    authorized_by uuid NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'posted',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,adjustment_reference),
    FOREIGN KEY (organization_id,invoice_id) REFERENCES invoices(organization_id,id),
    CHECK (char_length(btrim(adjustment_reference)) BETWEEN 4 AND 120),
    CHECK (direction_key IN ('debit','credit')),
    CHECK (amount_minor>0),
    CHECK (currency~'^[A-Z]{3}$'),
    CHECK (char_length(btrim(reason)) BETWEEN 10 AND 500),
    CHECK (status='posted'),
    CHECK (lock_version=0)
);

CREATE TABLE claims (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    invoice_id uuid NOT NULL,
    claim_number varchar(100) NOT NULL,
    payer_key varchar(80) NOT NULL,
    amount_minor bigint NOT NULL,
    remitted_minor bigint NOT NULL DEFAULT 0,
    currency char(3) NOT NULL,
    submitted_at timestamptz,
    submitted_by uuid,
    submission_reason varchar(500),
    status varchar(24) NOT NULL DEFAULT 'draft',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,payer_key,claim_number),
    FOREIGN KEY (organization_id,invoice_id) REFERENCES invoices(organization_id,id),
    CHECK (char_length(btrim(claim_number)) BETWEEN 4 AND 100),
    CHECK (payer_key~'^[a-z][a-z0-9_.:-]{1,79}$'),
    CHECK (amount_minor>0 AND remitted_minor>=0 AND remitted_minor<=amount_minor),
    CHECK (currency~'^[A-Z]{3}$'),
    CHECK ((submitted_at IS NULL)=(submitted_by IS NULL)),
    CHECK ((submitted_at IS NULL)=(submission_reason IS NULL)),
    CHECK (submission_reason IS NULL OR char_length(btrim(submission_reason)) BETWEEN 10 AND 500),
    CHECK (status IN ('draft','submitted','partially_paid','paid','denied','cancelled')),
    CHECK (status='draft' OR submitted_at IS NOT NULL),
    CHECK (status<>'paid' OR remitted_minor=amount_minor),
    CHECK (lock_version>=0)
);

CREATE TABLE remittances (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    claim_id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    remittance_reference varchar(120) NOT NULL,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    received_at timestamptz NOT NULL,
    evidence_digest char(64) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'posted',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,remittance_reference),
    FOREIGN KEY (organization_id,claim_id) REFERENCES claims(organization_id,id),
    FOREIGN KEY (organization_id,invoice_id) REFERENCES invoices(organization_id,id),
    CHECK (char_length(btrim(remittance_reference)) BETWEEN 4 AND 120),
    CHECK (amount_minor>0),
    CHECK (currency~'^[A-Z]{3}$'),
    CHECK (evidence_digest~'^[0-9a-f]{64}$'),
    CHECK (status='posted'),
    CHECK (lock_version=0)
);

CREATE TABLE reconciliations (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    reconciliation_type varchar(24) NOT NULL,
    reconciliation_reference varchar(120) NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    currency char(3) NOT NULL,
    expected_minor bigint NOT NULL,
    observed_minor bigint NOT NULL,
    variance_minor bigint NOT NULL,
    evidence_digest char(64) NOT NULL,
    resolved_at timestamptz,
    resolved_by uuid,
    resolution_reason varchar(500),
    status varchar(24) NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    UNIQUE (organization_id,reconciliation_reference),
    CHECK (reconciliation_type IN ('payment','claim','refund')),
    CHECK (char_length(btrim(reconciliation_reference)) BETWEEN 4 AND 120),
    CHECK (period_end>=period_start),
    CHECK (currency~'^[A-Z]{3}$'),
    CHECK (expected_minor>=0 AND observed_minor>=0),
    CHECK (variance_minor=observed_minor-expected_minor),
    CHECK (evidence_digest~'^[0-9a-f]{64}$'),
    CHECK ((resolved_at IS NULL)=(resolved_by IS NULL)),
    CHECK ((resolved_at IS NULL)=(resolution_reason IS NULL)),
    CHECK (resolution_reason IS NULL OR char_length(btrim(resolution_reason)) BETWEEN 10 AND 500),
    CHECK (status IN ('matched','exception','resolved')),
    CHECK ((variance_minor=0 AND status='matched') OR (variance_minor<>0 AND status IN ('exception','resolved'))),
    CHECK (status<>'resolved' OR resolved_at IS NOT NULL),
    CHECK (lock_version>=0)
);

CREATE TABLE financial_exports (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    export_type varchar(40) NOT NULL,
    format_key varchar(16) NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    filters_digest char(64) NOT NULL,
    purpose varchar(128) NOT NULL,
    requested_at timestamptz NOT NULL,
    requested_by uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    artifact_reference varchar(240),
    artifact_digest char(64),
    row_count integer,
    status varchar(24) NOT NULL DEFAULT 'requested',
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    CHECK (export_type IN ('invoice_register','payments','refunds','claims','reconciliation','audit')),
    CHECK (format_key IN ('csv','json')),
    CHECK (period_end>=period_start),
    CHECK (filters_digest~'^[0-9a-f]{64}$'),
    CHECK (purpose~'^[a-z0-9][a-z0-9._:-]{0,127}$'),
    CHECK (expires_at>requested_at),
    CHECK ((artifact_reference IS NULL)=(artifact_digest IS NULL)),
    CHECK ((artifact_reference IS NULL)=(row_count IS NULL)),
    CHECK (artifact_reference IS NULL OR char_length(artifact_reference) BETWEEN 1 AND 240),
    CHECK (artifact_digest IS NULL OR artifact_digest~'^[0-9a-f]{64}$'),
    CHECK (row_count IS NULL OR row_count>=0),
    CHECK (status IN ('requested','ready','failed','expired')),
    CHECK (status<>'ready' OR artifact_reference IS NOT NULL),
    CHECK (lock_version=0)
);

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'price_books','price_items','packages','package_entitlements','estimates',
        'invoices','invoice_items','payment_intents','payments','refunds','adjustments',
        'claims','remittances','reconciliations','financial_exports'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid) WITH CHECK (organization_id=nullif(current_setting(''app.current_organization_id'',true),'''')::uuid)',
            table_name||'_tenant_policy',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_validate_m11_tenant_write()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    configured_organization uuid:=nullif(current_setting('app.current_organization_id',true),'')::uuid;
    configured_actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    configured_operation text:=nullif(current_setting('app.current_operation_key',true),'');
    allowed_operations text[];
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    allowed_operations:=CASE TG_TABLE_NAME
        WHEN 'price_books' THEN ARRAY['billing.pricebook.create','billing.pricebook.item.add','billing.pricebook.activate']
        WHEN 'price_items' THEN ARRAY['billing.pricebook.item.add']
        WHEN 'packages' THEN ARRAY['billing.package.create','billing.package.entitlement.add','billing.package.activate']
        WHEN 'package_entitlements' THEN ARRAY['billing.package.entitlement.add']
        WHEN 'estimates' THEN ARRAY['billing.estimate.create','billing.estimate.finalize']
        WHEN 'invoices' THEN ARRAY[
            'billing.invoice.issue','billing.invoice.void','billing.payment.record',
            'billing.payment.provider.record','billing.refund.record','billing.adjustment.record',
            'billing.remittance.record']
        WHEN 'invoice_items' THEN ARRAY['billing.invoice.issue']
        WHEN 'payment_intents' THEN ARRAY['billing.payment.intent.create','billing.payment.provider.record']
        WHEN 'payments' THEN ARRAY['billing.payment.record','billing.payment.provider.record']
        WHEN 'refunds' THEN ARRAY['billing.refund.record']
        WHEN 'adjustments' THEN ARRAY['billing.adjustment.record']
        WHEN 'claims' THEN ARRAY['billing.claim.create','billing.claim.submit','billing.remittance.record']
        WHEN 'remittances' THEN ARRAY['billing.remittance.record']
        WHEN 'reconciliations' THEN ARRAY['billing.reconciliation.create','billing.reconciliation.complete']
        WHEN 'financial_exports' THEN ARRAY['billing.export.create']
        ELSE ARRAY[]::text[]
    END;
    IF NEW.organization_id IS DISTINCT FROM configured_organization
       OR configured_actor IS NULL OR configured_operation IS NULL
       OR NOT configured_operation=ANY(allowed_operations)
       OR NOT EXISTS (
            SELECT 1 FROM authorization_operations operation
            JOIN authorization_registry_releases release
              ON release.registry_version=operation.registry_version AND release.status='active'
            WHERE operation.operation_key=configured_operation
              AND operation.registry_version='m11-standing-direction-v1'
              AND operation.status='active') THEN
        RAISE EXCEPTION 'invalid Module 11 operation-bound tenant write context' USING ERRCODE='42501';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.created_by IS DISTINCT FROM configured_actor
           OR NEW.updated_by IS DISTINCT FROM configured_actor OR NEW.lock_version<>0 THEN
            RAISE EXCEPTION 'invalid Module 11 creation evidence' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.created_by IS DISTINCT FROM OLD.created_by
           OR NEW.updated_by IS DISTINCT FROM configured_actor OR NEW.lock_version<>OLD.lock_version+1
           OR NEW.updated_at<OLD.updated_at OR NEW.updated_at>clock_timestamp()+interval '5 seconds' THEN
            RAISE EXCEPTION 'invalid Module 11 revision evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'price_books','price_items','packages','package_entitlements','estimates',
        'invoices','invoice_items','payment_intents','payments','refunds','adjustments',
        'claims','remittances','reconciliations','financial_exports'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION careos_validate_m11_tenant_write()',
            table_name||'_write_guard',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_reject_m11_evidence_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 11 financial evidence is append-only' USING ERRCODE='42501';
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'price_items','package_entitlements','invoice_items','payment_intents','payments',
        'refunds','adjustments','remittances','financial_exports'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION careos_reject_m11_evidence_mutation()',
            table_name||'_immutable',table_name);
    END LOOP;
END $$;

CREATE FUNCTION careos_reject_m11_delete()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Module 11 records cannot be deleted' USING ERRCODE='42501';
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'price_books','packages','estimates','invoices','claims','reconciliations'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION careos_reject_m11_delete()',
            table_name||'_no_delete',table_name);
    END LOOP;
END $$;

REVOKE ALL ON
    price_books,price_items,packages,package_entitlements,estimates,invoices,invoice_items,
    payment_intents,payments,refunds,adjustments,claims,remittances,reconciliations,financial_exports
FROM PUBLIC;

GRANT SELECT,INSERT,UPDATE ON price_books,packages,estimates,invoices,claims,reconciliations
    TO "${applicationRole}";
GRANT SELECT,INSERT ON price_items,package_entitlements,invoice_items,payment_intents,payments,
    refunds,adjustments,remittances,financial_exports TO "${applicationRole}";

COMMENT ON TABLE payment_intents IS
    'Expected amount/currency and opaque provider references only; raw card data, provider tokens and bearer links are prohibited.';
COMMENT ON TABLE payments IS
    'Append-only settlement evidence with exact invoice amount/currency and optional verified-provider event provenance.';
COMMENT ON TABLE refunds IS
    'Append-only reason-bound authorized refund evidence capped by the original settlement.';
