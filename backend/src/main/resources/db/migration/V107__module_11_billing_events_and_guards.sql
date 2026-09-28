CREATE TEMP TABLE m11_event_seed (
    event_name varchar(160) PRIMARY KEY,
    subject_type varchar(120) NOT NULL,
    operation_key varchar(160) NOT NULL
) ON COMMIT DROP;

INSERT INTO m11_event_seed VALUES
 ('billing.price_book.created','price_book','billing.pricebook.create'),
 ('billing.price_item.added','price_item','billing.pricebook.item.add'),
 ('billing.price_book.activated','price_book','billing.pricebook.activate'),
 ('billing.package.created','package','billing.package.create'),
 ('billing.package_entitlement.added','package_entitlement','billing.package.entitlement.add'),
 ('billing.package.activated','package','billing.package.activate'),
 ('billing.estimate.created','estimate','billing.estimate.create'),
 ('billing.estimate.finalized','estimate','billing.estimate.finalize'),
 ('billing.invoice.issued','invoice','billing.invoice.issue'),
 ('billing.invoice.voided','invoice','billing.invoice.void'),
 ('billing.payment_intent.created','payment_intent','billing.payment.intent.create'),
 ('billing.payment.recorded','payment','billing.payment.record'),
 ('billing.provider_payment.recorded','payment','billing.payment.provider.record'),
 ('billing.refund.recorded','refund','billing.refund.record'),
 ('billing.adjustment.recorded','adjustment','billing.adjustment.record'),
 ('billing.claim.created','claim','billing.claim.create'),
 ('billing.claim.submitted','claim','billing.claim.submit'),
 ('billing.remittance.recorded','remittance','billing.remittance.record'),
 ('billing.reconciliation.created','reconciliation','billing.reconciliation.create'),
 ('billing.reconciliation.completed','reconciliation','billing.reconciliation.complete'),
 ('billing.export.requested','financial_export','billing.export.create');

INSERT INTO audit_event_definitions
    (event_name,schema_version,display_name,description,subject_type,reason_required,
     required_payload_keys,allowed_payload_keys,payload_schema,status,registry_version)
SELECT event_name,1,replace(initcap(replace(event_name,'.',' ')),'_',' '),
       'CareOS Module 11 governed financial evidence.',subject_type,true,
       ARRAY['artifactId','artifactType','fromState','toState','revision'],
       ARRAY['artifactId','artifactType','fromState','toState','revision'],
       '{"type":"object","additionalProperties":false}'::jsonb,
       'active','m11-standing-direction-v1'
FROM m11_event_seed;

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT operation_key,'audit',event_name,1,'active','m11-standing-direction-v1'
FROM m11_event_seed;

INSERT INTO outbox_event_definitions
    (event_name,schema_version,description,aggregate_type,required_payload_keys,
     allowed_payload_keys,payload_schema,status,registry_version)
VALUES
    ('m11.financial-artifact-changed.v1',1,
     'A governed financial artifact changed.','financial_artifact',
     ARRAY['artifactId','artifactType','fromState','toState','revision'],
     ARRAY['artifactId','artifactType','fromState','toState','revision'],
     '{"type":"object","additionalProperties":false}'::jsonb,
     'active','m11-standing-direction-v1');

INSERT INTO authorization_operation_events
    (operation_key,event_kind,event_name,schema_version,status,registry_version)
SELECT permission_key,'outbox','m11.financial-artifact-changed.v1',1,
       'active','m11-standing-direction-v1'
FROM authorization_permissions
WHERE registry_version='m11-standing-direction-v1'
  AND permission_key<>'billing.read';

CREATE FUNCTION careos_m11_price_book_digest(requested_organization uuid, requested_book uuid)
RETURNS char(64) LANGUAGE sql STABLE STRICT AS $$
    SELECT encode(sha256(convert_to(concat_ws('|',
        book.id::text,book.book_code,book.display_name,book.currency,
        book.version_number::text,book.effective_from::text,coalesce(book.effective_to::text,''),
        coalesce((SELECT string_agg(concat_ws(':',item.item_sequence::text,item.id::text,
            item.service_id::text,item.item_code,item.display_name,item.currency,
            item.unit_amount_minor::text,item.tax_basis_points::text,item.effective_from::text,
            coalesce(item.effective_to::text,'')),',' ORDER BY item.item_sequence,item.id)
            FROM price_items item WHERE item.organization_id=book.organization_id
              AND item.price_book_id=book.id),'')
    ),'UTF8')),'hex')::char(64)
    FROM price_books book
    WHERE book.organization_id=requested_organization AND book.id=requested_book
$$;

CREATE FUNCTION careos_m11_package_digest(requested_organization uuid, requested_package uuid)
RETURNS char(64) LANGUAGE sql STABLE STRICT AS $$
    SELECT encode(sha256(convert_to(concat_ws('|',
        package.id::text,package.price_book_id::text,package.package_code,package.display_name,
        package.currency,package.package_amount_minor::text,package.version_number::text,
        package.effective_from::text,coalesce(package.effective_to::text,''),
        coalesce((SELECT string_agg(concat_ws(':',entitlement.entitlement_sequence::text,
            entitlement.id::text,entitlement.service_id::text,entitlement.quantity::text,
            coalesce(entitlement.expires_after_days::text,'')),','
            ORDER BY entitlement.entitlement_sequence,entitlement.id)
            FROM package_entitlements entitlement
            WHERE entitlement.organization_id=package.organization_id
              AND entitlement.package_id=package.id),'')
    ),'UTF8')),'hex')::char(64)
    FROM packages package
    WHERE package.organization_id=requested_organization AND package.id=requested_package
$$;

CREATE FUNCTION careos_m11_estimate_digest(requested_organization uuid, requested_estimate uuid)
RETURNS char(64) LANGUAGE sql STABLE STRICT AS $$
    SELECT encode(sha256(convert_to(concat_ws('|',
        estimate.id::text,estimate.patient_id::text,coalesce(estimate.appointment_id::text,''),
        estimate.price_book_id::text,coalesce(estimate.source_price_item_id::text,''),
        coalesce(estimate.source_package_id::text,''),estimate.estimate_number,
        estimate.currency,estimate.quantity::text,estimate.unit_amount_minor::text,
        estimate.subtotal_minor::text,estimate.tax_minor::text,estimate.total_minor::text,
        estimate.valid_until::text
    ),'UTF8')),'hex')::char(64)
    FROM estimates estimate
    WHERE estimate.organization_id=requested_organization AND estimate.id=requested_estimate
$$;

CREATE FUNCTION careos_m11_invoice_digest(
    requested_organization uuid, requested_estimate uuid, requested_invoice_number text,
    requested_currency text, requested_subtotal bigint, requested_tax bigint,
    requested_total bigint, requested_due_on date)
RETURNS char(64) LANGUAGE sql STABLE STRICT AS $$
    SELECT encode(sha256(convert_to(concat_ws('|',
        estimate.id::text,estimate.content_digest,requested_invoice_number,
        requested_currency,requested_subtotal::text,requested_tax::text,
        requested_total::text,requested_due_on::text
    ),'UTF8')),'hex')::char(64)
    FROM estimates estimate
    WHERE estimate.organization_id=requested_organization AND estimate.id=requested_estimate
$$;

CREATE FUNCTION careos_guard_m11_price_book()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation text:=nullif(current_setting('app.current_operation_key',true),'');
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    reason text:=nullif(current_setting('app.current_authorization_reason',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF operation<>'billing.pricebook.create' OR NEW.status<>'draft'
           OR NEW.configuration_digest IS NOT NULL OR NEW.activated_at IS NOT NULL THEN
            RAISE EXCEPTION 'price books begin as unfrozen drafts' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF operation='billing.pricebook.item.add' THEN
        IF OLD.status<>'draft' OR NEW.status<>'draft'
           OR NEW.book_code<>OLD.book_code OR NEW.display_name<>OLD.display_name
           OR NEW.currency<>OLD.currency OR NEW.version_number<>OLD.version_number
           OR NEW.effective_from<>OLD.effective_from OR NEW.effective_to IS DISTINCT FROM OLD.effective_to
           OR NEW.configuration_digest IS NOT NULL OR NEW.activated_at IS NOT NULL THEN
            RAISE EXCEPTION 'price items may only bump an unchanged draft price book' USING ERRCODE='23514';
        END IF;
    ELSIF operation='billing.pricebook.activate' THEN
        IF OLD.status<>'draft' OR NEW.status<>'active'
           OR NEW.book_code<>OLD.book_code OR NEW.display_name<>OLD.display_name
           OR NEW.currency<>OLD.currency OR NEW.version_number<>OLD.version_number
           OR NEW.effective_from<>OLD.effective_from OR NEW.effective_to IS DISTINCT FROM OLD.effective_to
           OR NEW.configuration_digest IS DISTINCT FROM careos_m11_price_book_digest(NEW.organization_id,NEW.id)
           OR NEW.activated_by IS DISTINCT FROM actor OR NEW.activation_reason IS DISTINCT FROM reason
           OR NEW.activated_at IS NULL OR NEW.activated_at>clock_timestamp()+interval '5 seconds'
           OR NOT EXISTS (SELECT 1 FROM price_items item
                          WHERE item.organization_id=NEW.organization_id AND item.price_book_id=NEW.id) THEN
            RAISE EXCEPTION 'price-book activation requires exact complete frozen evidence' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid price-book lifecycle operation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER price_books_lifecycle_guard
BEFORE INSERT OR UPDATE ON price_books FOR EACH ROW EXECUTE FUNCTION careos_guard_m11_price_book();

CREATE FUNCTION careos_guard_m11_price_item()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE book price_books%ROWTYPE; service_status text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT * INTO book FROM price_books
     WHERE organization_id=NEW.organization_id AND id=NEW.price_book_id FOR UPDATE;
    SELECT status INTO service_status FROM service_definitions
     WHERE organization_id=NEW.organization_id AND id=NEW.service_id;
    IF book.id IS NULL OR book.status<>'draft' OR service_status IS DISTINCT FROM 'active'
       OR NEW.currency<>book.currency OR NEW.effective_from<book.effective_from
       OR (book.effective_to IS NOT NULL AND (NEW.effective_to IS NULL OR NEW.effective_to>book.effective_to)) THEN
        RAISE EXCEPTION 'price item must match a draft price book and active service' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER price_items_parent_guard
BEFORE INSERT ON price_items FOR EACH ROW EXECUTE FUNCTION careos_guard_m11_price_item();

CREATE FUNCTION careos_guard_m11_package()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation text:=nullif(current_setting('app.current_operation_key',true),'');
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    reason text:=nullif(current_setting('app.current_authorization_reason',true),'');
    book price_books%ROWTYPE;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        SELECT * INTO book FROM price_books
         WHERE organization_id=NEW.organization_id AND id=NEW.price_book_id;
        IF operation<>'billing.package.create' OR NEW.status<>'draft'
           OR book.id IS NULL OR book.status<>'active' OR NEW.currency<>book.currency
           OR NEW.effective_from<book.effective_from
           OR (book.effective_to IS NOT NULL AND (NEW.effective_to IS NULL OR NEW.effective_to>book.effective_to)) THEN
            RAISE EXCEPTION 'package must begin as a draft bound to active matching pricing' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF operation='billing.package.entitlement.add' THEN
        IF OLD.status<>'draft' OR NEW.status<>'draft'
           OR NEW.price_book_id<>OLD.price_book_id OR NEW.package_code<>OLD.package_code
           OR NEW.display_name<>OLD.display_name OR NEW.currency<>OLD.currency
           OR NEW.package_amount_minor<>OLD.package_amount_minor
           OR NEW.version_number<>OLD.version_number OR NEW.effective_from<>OLD.effective_from
           OR NEW.effective_to IS DISTINCT FROM OLD.effective_to
           OR NEW.configuration_digest IS NOT NULL OR NEW.activated_at IS NOT NULL THEN
            RAISE EXCEPTION 'entitlements may only bump an unchanged draft package' USING ERRCODE='23514';
        END IF;
    ELSIF operation='billing.package.activate' THEN
        IF OLD.status<>'draft' OR NEW.status<>'active'
           OR NEW.price_book_id<>OLD.price_book_id OR NEW.package_code<>OLD.package_code
           OR NEW.display_name<>OLD.display_name OR NEW.currency<>OLD.currency
           OR NEW.package_amount_minor<>OLD.package_amount_minor
           OR NEW.version_number<>OLD.version_number OR NEW.effective_from<>OLD.effective_from
           OR NEW.effective_to IS DISTINCT FROM OLD.effective_to
           OR NEW.configuration_digest IS DISTINCT FROM careos_m11_package_digest(NEW.organization_id,NEW.id)
           OR NEW.activated_by IS DISTINCT FROM actor OR NEW.activation_reason IS DISTINCT FROM reason
           OR NEW.activated_at IS NULL OR NEW.activated_at>clock_timestamp()+interval '5 seconds'
           OR NOT EXISTS (SELECT 1 FROM package_entitlements entitlement
                          WHERE entitlement.organization_id=NEW.organization_id
                            AND entitlement.package_id=NEW.id) THEN
            RAISE EXCEPTION 'package activation requires exact complete frozen evidence' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid package lifecycle operation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER packages_lifecycle_guard
BEFORE INSERT OR UPDATE ON packages FOR EACH ROW EXECUTE FUNCTION careos_guard_m11_package();

CREATE FUNCTION careos_guard_m11_entitlement()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE package_status text; service_status text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT status INTO package_status FROM packages
     WHERE organization_id=NEW.organization_id AND id=NEW.package_id FOR UPDATE;
    SELECT status INTO service_status FROM service_definitions
     WHERE organization_id=NEW.organization_id AND id=NEW.service_id;
    IF package_status IS DISTINCT FROM 'draft' OR service_status IS DISTINCT FROM 'active' THEN
        RAISE EXCEPTION 'entitlement requires a draft package and active service' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER package_entitlements_parent_guard
BEFORE INSERT ON package_entitlements FOR EACH ROW EXECUTE FUNCTION careos_guard_m11_entitlement();

CREATE FUNCTION careos_guard_m11_estimate()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation text:=nullif(current_setting('app.current_operation_key',true),'');
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    reason text:=nullif(current_setting('app.current_authorization_reason',true),'');
    item price_items%ROWTYPE;
    package packages%ROWTYPE;
    book price_books%ROWTYPE;
    calculated_tax bigint;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        IF operation<>'billing.estimate.create' OR NEW.status<>'draft'
           OR NEW.content_digest IS NOT NULL OR NEW.finalized_at IS NOT NULL
           OR NEW.valid_until<current_date OR NEW.valid_until>current_date+365 THEN
            RAISE EXCEPTION 'estimate creation state is invalid' USING ERRCODE='23514';
        END IF;
        SELECT * INTO book FROM price_books
         WHERE organization_id=NEW.organization_id AND id=NEW.price_book_id;
        IF book.id IS NULL OR book.status<>'active' OR NEW.currency<>book.currency
           OR current_date<book.effective_from
           OR (book.effective_to IS NOT NULL AND current_date>book.effective_to) THEN
            RAISE EXCEPTION 'estimate requires current active pricing' USING ERRCODE='23514';
        END IF;
        IF NEW.source_price_item_id IS NOT NULL THEN
            SELECT * INTO item FROM price_items
             WHERE organization_id=NEW.organization_id AND id=NEW.source_price_item_id;
            IF item.id IS NULL OR item.price_book_id<>NEW.price_book_id
               OR NEW.unit_amount_minor<>item.unit_amount_minor
               OR NEW.subtotal_minor<>item.unit_amount_minor*NEW.quantity
               OR NEW.currency<>item.currency OR current_date<item.effective_from
               OR (item.effective_to IS NOT NULL AND current_date>item.effective_to) THEN
                RAISE EXCEPTION 'estimate price-item snapshot is invalid' USING ERRCODE='23514';
            END IF;
            calculated_tax:=(NEW.subtotal_minor*item.tax_basis_points+5000)/10000;
            IF NEW.tax_minor<>calculated_tax THEN
                RAISE EXCEPTION 'estimate tax must be server calculated' USING ERRCODE='23514';
            END IF;
        ELSE
            SELECT * INTO package FROM packages
             WHERE organization_id=NEW.organization_id AND id=NEW.source_package_id;
            IF package.id IS NULL OR package.price_book_id<>NEW.price_book_id
               OR package.status<>'active' OR NEW.quantity<>1
               OR NEW.unit_amount_minor<>package.package_amount_minor
               OR NEW.subtotal_minor<>package.package_amount_minor
               OR NEW.tax_minor<>0 OR NEW.currency<>package.currency
               OR current_date<package.effective_from
               OR (package.effective_to IS NOT NULL AND current_date>package.effective_to) THEN
                RAISE EXCEPTION 'estimate package snapshot is invalid' USING ERRCODE='23514';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF operation<>'billing.estimate.finalize' OR OLD.status<>'draft' OR NEW.status<>'finalized'
       OR NEW.patient_id<>OLD.patient_id OR NEW.appointment_id IS DISTINCT FROM OLD.appointment_id
       OR NEW.price_book_id<>OLD.price_book_id
       OR NEW.source_price_item_id IS DISTINCT FROM OLD.source_price_item_id
       OR NEW.source_package_id IS DISTINCT FROM OLD.source_package_id
       OR NEW.estimate_number<>OLD.estimate_number OR NEW.currency<>OLD.currency
       OR NEW.quantity<>OLD.quantity OR NEW.unit_amount_minor<>OLD.unit_amount_minor
       OR NEW.subtotal_minor<>OLD.subtotal_minor OR NEW.tax_minor<>OLD.tax_minor
       OR NEW.total_minor<>OLD.total_minor OR NEW.valid_until<>OLD.valid_until
       OR NEW.content_digest IS DISTINCT FROM careos_m11_estimate_digest(NEW.organization_id,NEW.id)
       OR NEW.finalized_by IS DISTINCT FROM actor OR NEW.finalization_reason IS DISTINCT FROM reason
       OR NEW.finalized_at IS NULL OR NEW.finalized_at>clock_timestamp()+interval '5 seconds' THEN
        RAISE EXCEPTION 'estimate finalization requires an exact immutable snapshot' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER estimates_lifecycle_guard
BEFORE INSERT OR UPDATE ON estimates FOR EACH ROW EXECUTE FUNCTION careos_guard_m11_estimate();

CREATE FUNCTION careos_guard_m11_invoice()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation text:=nullif(current_setting('app.current_operation_key',true),'');
    actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
    reason text:=nullif(current_setting('app.current_authorization_reason',true),'');
    estimate estimates%ROWTYPE;
    calculated_paid bigint;
    calculated_refunded bigint;
    calculated_adjustment bigint;
    calculated_balance bigint;
    expected_status text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        SELECT * INTO estimate FROM estimates
         WHERE organization_id=NEW.organization_id AND id=NEW.estimate_id FOR UPDATE;
        IF operation<>'billing.invoice.issue' OR estimate.id IS NULL OR estimate.status<>'finalized'
           OR estimate.valid_until<current_date OR NEW.patient_id<>estimate.patient_id
           OR NEW.appointment_id IS DISTINCT FROM estimate.appointment_id
           OR NEW.currency<>estimate.currency OR NEW.subtotal_minor<>estimate.subtotal_minor
           OR NEW.tax_minor<>estimate.tax_minor OR NEW.original_total_minor<>estimate.total_minor
           OR NEW.adjustment_minor<>0 OR NEW.paid_minor<>0 OR NEW.refunded_minor<>0
           OR NEW.balance_minor<>estimate.total_minor OR NEW.status<>'issued'
           OR NEW.snapshot_digest IS DISTINCT FROM careos_m11_invoice_digest(
                NEW.organization_id,NEW.estimate_id,NEW.invoice_number,NEW.currency,
                NEW.subtotal_minor,NEW.tax_minor,NEW.original_total_minor,NEW.due_on) THEN
            RAISE EXCEPTION 'invoice must be issued from an exact valid finalized estimate' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.patient_id<>OLD.patient_id OR NEW.appointment_id IS DISTINCT FROM OLD.appointment_id
       OR NEW.encounter_id IS DISTINCT FROM OLD.encounter_id OR NEW.estimate_id<>OLD.estimate_id
       OR NEW.invoice_number<>OLD.invoice_number OR NEW.currency<>OLD.currency
       OR NEW.subtotal_minor<>OLD.subtotal_minor OR NEW.tax_minor<>OLD.tax_minor
       OR NEW.original_total_minor<>OLD.original_total_minor OR NEW.issued_at<>OLD.issued_at
       OR NEW.due_on<>OLD.due_on OR NEW.snapshot_digest<>OLD.snapshot_digest THEN
        RAISE EXCEPTION 'issued invoice identity and source totals are immutable' USING ERRCODE='23514';
    END IF;
    IF operation='billing.invoice.void' THEN
        IF OLD.status<>'issued' OR OLD.paid_minor<>OLD.refunded_minor OR NEW.status<>'void'
           OR NEW.adjustment_minor<>OLD.adjustment_minor OR NEW.paid_minor<>OLD.paid_minor
           OR NEW.refunded_minor<>OLD.refunded_minor OR NEW.balance_minor<>OLD.balance_minor
           OR NEW.voided_by IS DISTINCT FROM actor OR NEW.void_reason IS DISTINCT FROM reason
           OR NEW.voided_at IS NULL OR NEW.voided_at>clock_timestamp()+interval '5 seconds' THEN
            RAISE EXCEPTION 'only an unsettled issued invoice may be voided' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF operation NOT IN ('billing.payment.record','billing.payment.provider.record',
                         'billing.refund.record','billing.adjustment.record','billing.remittance.record')
       OR OLD.status='void' OR NEW.voided_at IS DISTINCT FROM OLD.voided_at
       OR NEW.voided_by IS DISTINCT FROM OLD.voided_by OR NEW.void_reason IS DISTINCT FROM OLD.void_reason THEN
        RAISE EXCEPTION 'invalid invoice monetary transition' USING ERRCODE='23514';
    END IF;
    SELECT coalesce(sum(amount_minor),0) INTO calculated_paid FROM payments
     WHERE organization_id=NEW.organization_id AND invoice_id=NEW.id;
    SELECT calculated_paid+coalesce(sum(amount_minor),0) INTO calculated_paid FROM remittances
     WHERE organization_id=NEW.organization_id AND invoice_id=NEW.id;
    SELECT coalesce(sum(amount_minor),0) INTO calculated_refunded FROM refunds
     WHERE organization_id=NEW.organization_id AND invoice_id=NEW.id;
    SELECT coalesce(sum(CASE direction_key WHEN 'debit' THEN amount_minor ELSE -amount_minor END),0)
      INTO calculated_adjustment FROM adjustments
     WHERE organization_id=NEW.organization_id AND invoice_id=NEW.id;
    calculated_balance:=NEW.original_total_minor+calculated_adjustment-calculated_paid+calculated_refunded;
    expected_status:=CASE WHEN calculated_balance=0 THEN 'paid'
                          WHEN calculated_paid>calculated_refunded THEN 'partially_paid'
                          ELSE 'issued' END;
    IF calculated_balance<0 OR NEW.paid_minor<>calculated_paid
       OR NEW.refunded_minor<>calculated_refunded OR NEW.adjustment_minor<>calculated_adjustment
       OR NEW.balance_minor<>calculated_balance OR NEW.status<>expected_status THEN
        RAISE EXCEPTION 'invoice totals must equal immutable monetary evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER invoices_lifecycle_guard
BEFORE INSERT OR UPDATE ON invoices FOR EACH ROW EXECUTE FUNCTION careos_guard_m11_invoice();

CREATE FUNCTION careos_guard_m11_invoice_item()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE invoice invoices%ROWTYPE; estimate estimates%ROWTYPE; item price_items%ROWTYPE; package packages%ROWTYPE;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT * INTO invoice FROM invoices
     WHERE organization_id=NEW.organization_id AND id=NEW.invoice_id FOR UPDATE;
    SELECT * INTO estimate FROM estimates
     WHERE organization_id=NEW.organization_id AND id=invoice.estimate_id;
    IF invoice.id IS NULL OR invoice.status<>'issued' OR NEW.currency<>invoice.currency
       OR NEW.quantity<>estimate.quantity OR NEW.unit_amount_minor<>estimate.unit_amount_minor
       OR NEW.subtotal_minor<>estimate.subtotal_minor OR NEW.tax_minor<>estimate.tax_minor
       OR NEW.total_minor<>estimate.total_minor
       OR NEW.price_item_id IS DISTINCT FROM estimate.source_price_item_id
       OR NEW.package_id IS DISTINCT FROM estimate.source_package_id THEN
        RAISE EXCEPTION 'invoice line must exactly snapshot its finalized estimate' USING ERRCODE='23514';
    END IF;
    IF NEW.price_item_id IS NOT NULL THEN
        SELECT * INTO item FROM price_items WHERE organization_id=NEW.organization_id AND id=NEW.price_item_id;
        IF NEW.service_id IS DISTINCT FROM item.service_id OR NEW.description<>item.display_name THEN
            RAISE EXCEPTION 'invoice price-item description or service drifted' USING ERRCODE='23514';
        END IF;
    ELSE
        SELECT * INTO package FROM packages WHERE organization_id=NEW.organization_id AND id=NEW.package_id;
        IF NEW.service_id IS NOT NULL OR NEW.description<>package.display_name THEN
            RAISE EXCEPTION 'invoice package description drifted' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER invoice_items_snapshot_guard
BEFORE INSERT ON invoice_items FOR EACH ROW EXECUTE FUNCTION careos_guard_m11_invoice_item();

CREATE FUNCTION careos_check_m11_invoice_complete()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE requested_invoice uuid;
        invoice invoices%ROWTYPE; item_count integer; subtotal bigint; tax bigint; total bigint;
BEGIN
    IF TG_TABLE_NAME='invoices' THEN
        requested_invoice:=NEW.id;
    ELSE
        requested_invoice:=NEW.invoice_id;
    END IF;
    SELECT * INTO invoice FROM invoices WHERE organization_id=NEW.organization_id AND id=requested_invoice;
    IF invoice.id IS NULL THEN RETURN NULL; END IF;
    SELECT count(*),coalesce(sum(subtotal_minor),0),coalesce(sum(tax_minor),0),coalesce(sum(total_minor),0)
      INTO item_count,subtotal,tax,total FROM invoice_items
     WHERE organization_id=invoice.organization_id AND invoice_id=invoice.id;
    IF item_count<1 OR subtotal<>invoice.subtotal_minor OR tax<>invoice.tax_minor
       OR total<>invoice.original_total_minor THEN
        RAISE EXCEPTION 'issued invoice requires complete matching line evidence' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER invoices_completeness_guard
AFTER INSERT OR UPDATE ON invoices DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION careos_check_m11_invoice_complete();
CREATE CONSTRAINT TRIGGER invoice_items_completeness_guard
AFTER INSERT ON invoice_items DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION careos_check_m11_invoice_complete();

CREATE FUNCTION careos_guard_m11_payment_intent()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE invoice invoices%ROWTYPE;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT * INTO invoice FROM invoices
     WHERE organization_id=NEW.organization_id AND id=NEW.invoice_id FOR UPDATE;
    IF invoice.id IS NULL OR invoice.status IN ('paid','void') OR NEW.currency<>invoice.currency
       OR NEW.amount_minor>invoice.balance_minor OR NEW.expires_at<=clock_timestamp()
       OR NEW.expires_at>clock_timestamp()+interval '7 days'
       OR NEW.status<>'awaiting_provider' OR NEW.provider_reference_digest IS NOT NULL THEN
        RAISE EXCEPTION 'payment intent must match the open invoice and expose no provider secret' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER payment_intents_invoice_guard
BEFORE INSERT ON payment_intents FOR EACH ROW EXECUTE FUNCTION careos_guard_m11_payment_intent();

CREATE FUNCTION careos_guard_m11_payment()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation text:=nullif(current_setting('app.current_operation_key',true),'');
        invoice invoices%ROWTYPE; intent payment_intents%ROWTYPE;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT * INTO invoice FROM invoices
     WHERE organization_id=NEW.organization_id AND id=NEW.invoice_id FOR UPDATE;
    IF invoice.id IS NULL OR invoice.status IN ('paid','void') OR NEW.currency<>invoice.currency
       OR NEW.amount_minor>invoice.balance_minor OR NEW.occurred_at>clock_timestamp()+interval '5 minutes' THEN
        RAISE EXCEPTION 'payment must match an open invoice amount and currency' USING ERRCODE='23514';
    END IF;
    IF operation='billing.payment.record' THEN
        IF NEW.source_key NOT IN ('manual_cash','manual_bank','manual_other')
           OR NEW.payment_intent_id IS NOT NULL OR NEW.provider_key IS NOT NULL THEN
            RAISE EXCEPTION 'browser payment records cannot represent provider card events' USING ERRCODE='23514';
        END IF;
    ELSIF operation='billing.payment.provider.record' THEN
        SELECT * INTO intent FROM payment_intents
         WHERE organization_id=NEW.organization_id AND id=NEW.payment_intent_id;
        IF NEW.source_key<>'provider' OR intent.id IS NULL OR intent.invoice_id<>NEW.invoice_id
           OR intent.provider_key<>NEW.provider_key OR intent.amount_minor<>NEW.amount_minor
           OR intent.currency<>NEW.currency OR intent.expires_at<NEW.occurred_at THEN
            RAISE EXCEPTION 'provider event does not match its exact payment intent' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid payment source operation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER payments_settlement_guard
BEFORE INSERT ON payments FOR EACH ROW EXECUTE FUNCTION careos_guard_m11_payment();

CREATE FUNCTION careos_guard_m11_refund()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
        payment payments%ROWTYPE; prior_refunds bigint;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT * INTO payment FROM payments
     WHERE organization_id=NEW.organization_id AND id=NEW.payment_id;
    SELECT coalesce(sum(amount_minor),0) INTO prior_refunds FROM refunds
     WHERE organization_id=NEW.organization_id AND payment_id=NEW.payment_id;
    IF payment.id IS NULL OR NEW.invoice_id<>payment.invoice_id OR NEW.currency<>payment.currency
       OR prior_refunds+NEW.amount_minor>payment.amount_minor
       OR NEW.authorized_by IS DISTINCT FROM actor
       OR NEW.authorized_at>clock_timestamp()+interval '5 seconds' THEN
        RAISE EXCEPTION 'refund must be authorized and capped by exact settlement evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER refunds_settlement_guard
BEFORE INSERT ON refunds FOR EACH ROW EXECUTE FUNCTION careos_guard_m11_refund();

CREATE FUNCTION careos_guard_m11_adjustment()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
        invoice invoices%ROWTYPE; delta bigint;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT * INTO invoice FROM invoices
     WHERE organization_id=NEW.organization_id AND id=NEW.invoice_id FOR UPDATE;
    delta:=CASE NEW.direction_key WHEN 'debit' THEN NEW.amount_minor ELSE -NEW.amount_minor END;
    IF invoice.id IS NULL OR invoice.status='void' OR NEW.currency<>invoice.currency
       OR invoice.original_total_minor+invoice.adjustment_minor+delta-invoice.paid_minor+invoice.refunded_minor<0
       OR NEW.authorized_by IS DISTINCT FROM actor
       OR NEW.authorized_at>clock_timestamp()+interval '5 seconds' THEN
        RAISE EXCEPTION 'adjustment must be authorized and preserve a non-negative balance' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER adjustments_invoice_guard
BEFORE INSERT ON adjustments FOR EACH ROW EXECUTE FUNCTION careos_guard_m11_adjustment();

CREATE FUNCTION careos_guard_m11_claim()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation text:=nullif(current_setting('app.current_operation_key',true),'');
        actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
        reason text:=nullif(current_setting('app.current_authorization_reason',true),'');
        invoice invoices%ROWTYPE; remitted bigint; expected_status text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        SELECT * INTO invoice FROM invoices
         WHERE organization_id=NEW.organization_id AND id=NEW.invoice_id FOR UPDATE;
        IF operation<>'billing.claim.create' OR invoice.id IS NULL OR invoice.status='void'
           OR NEW.status<>'draft' OR NEW.currency<>invoice.currency
           OR NEW.amount_minor>invoice.balance_minor OR NEW.remitted_minor<>0 THEN
            RAISE EXCEPTION 'claim must begin as an exact invoice-bound draft' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.invoice_id<>OLD.invoice_id OR NEW.claim_number<>OLD.claim_number
       OR NEW.payer_key<>OLD.payer_key OR NEW.amount_minor<>OLD.amount_minor
       OR NEW.currency<>OLD.currency THEN
        RAISE EXCEPTION 'claim identity and amount are immutable' USING ERRCODE='23514';
    END IF;
    IF operation='billing.claim.submit' THEN
        IF OLD.status<>'draft' OR NEW.status<>'submitted' OR NEW.remitted_minor<>0
           OR NEW.submitted_by IS DISTINCT FROM actor OR NEW.submission_reason IS DISTINCT FROM reason
           OR NEW.submitted_at IS NULL OR NEW.submitted_at>clock_timestamp()+interval '5 seconds' THEN
            RAISE EXCEPTION 'claim submission evidence is invalid' USING ERRCODE='23514';
        END IF;
    ELSIF operation='billing.remittance.record' THEN
        SELECT coalesce(sum(amount_minor),0) INTO remitted FROM remittances
         WHERE organization_id=NEW.organization_id AND claim_id=NEW.id;
        expected_status:=CASE WHEN remitted=NEW.amount_minor THEN 'paid' ELSE 'partially_paid' END;
        IF OLD.status NOT IN ('submitted','partially_paid') OR NEW.remitted_minor<>remitted
           OR NEW.status<>expected_status OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
           OR NEW.submitted_by IS DISTINCT FROM OLD.submitted_by
           OR NEW.submission_reason IS DISTINCT FROM OLD.submission_reason THEN
            RAISE EXCEPTION 'claim remittance totals must equal immutable evidence' USING ERRCODE='23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid claim lifecycle operation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER claims_lifecycle_guard
BEFORE INSERT OR UPDATE ON claims FOR EACH ROW EXECUTE FUNCTION careos_guard_m11_claim();

CREATE FUNCTION careos_guard_m11_remittance()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE claim claims%ROWTYPE; invoice invoices%ROWTYPE; prior bigint;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    SELECT * INTO claim FROM claims
     WHERE organization_id=NEW.organization_id AND id=NEW.claim_id FOR UPDATE;
    SELECT * INTO invoice FROM invoices
     WHERE organization_id=NEW.organization_id AND id=NEW.invoice_id FOR UPDATE;
    SELECT coalesce(sum(amount_minor),0) INTO prior FROM remittances
     WHERE organization_id=NEW.organization_id AND claim_id=NEW.claim_id;
    IF claim.id IS NULL OR claim.status NOT IN ('submitted','partially_paid')
       OR NEW.invoice_id<>claim.invoice_id OR invoice.status IN ('paid','void')
       OR NEW.currency<>claim.currency OR NEW.currency<>invoice.currency
       OR prior+NEW.amount_minor>claim.amount_minor OR NEW.amount_minor>invoice.balance_minor THEN
        RAISE EXCEPTION 'remittance must match open claim and invoice bounds' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER remittances_claim_guard
BEFORE INSERT ON remittances FOR EACH ROW EXECUTE FUNCTION careos_guard_m11_remittance();

CREATE FUNCTION careos_guard_m11_reconciliation()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation text:=nullif(current_setting('app.current_operation_key',true),'');
        actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
        reason text:=nullif(current_setting('app.current_authorization_reason',true),'');
        expected_status text;
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        expected_status:=CASE WHEN NEW.variance_minor=0 THEN 'matched' ELSE 'exception' END;
        IF operation<>'billing.reconciliation.create' OR NEW.status<>expected_status
           OR NEW.resolved_at IS NOT NULL THEN
            RAISE EXCEPTION 'reconciliation state must match its exact variance' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF operation<>'billing.reconciliation.complete' OR OLD.status<>'exception'
       OR NEW.status<>'resolved' OR NEW.reconciliation_type<>OLD.reconciliation_type
       OR NEW.reconciliation_reference<>OLD.reconciliation_reference
       OR NEW.period_start<>OLD.period_start OR NEW.period_end<>OLD.period_end
       OR NEW.currency<>OLD.currency OR NEW.expected_minor<>OLD.expected_minor
       OR NEW.observed_minor<>OLD.observed_minor OR NEW.variance_minor<>OLD.variance_minor
       OR NEW.evidence_digest<>OLD.evidence_digest OR NEW.resolved_by IS DISTINCT FROM actor
       OR NEW.resolution_reason IS DISTINCT FROM reason OR NEW.resolved_at IS NULL
       OR NEW.resolved_at>clock_timestamp()+interval '5 seconds' THEN
        RAISE EXCEPTION 'reconciliation resolution evidence is invalid' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER reconciliations_lifecycle_guard
BEFORE INSERT OR UPDATE ON reconciliations FOR EACH ROW EXECUTE FUNCTION careos_guard_m11_reconciliation();

CREATE FUNCTION careos_guard_m11_export()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor uuid:=nullif(current_setting('app.current_actor_id',true),'')::uuid;
        purpose text:=nullif(current_setting('app.current_purpose',true),'');
BEGIN
    IF current_user<>'${applicationRole}' THEN RETURN NEW; END IF;
    IF NEW.requested_by IS DISTINCT FROM actor OR NEW.purpose IS DISTINCT FROM purpose
       OR NEW.requested_at>clock_timestamp()+interval '5 seconds'
       OR NEW.expires_at>NEW.requested_at+interval '24 hours'
       OR NEW.status<>'requested' OR NEW.artifact_reference IS NOT NULL THEN
        RAISE EXCEPTION 'financial export request must be purpose-bound and artifact-free' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER financial_exports_request_guard
BEFORE INSERT ON financial_exports FOR EACH ROW EXECUTE FUNCTION careos_guard_m11_export();
