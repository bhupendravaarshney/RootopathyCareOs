package com.rootopathy.careos.billing.infrastructure;

import com.rootopathy.careos.billing.application.BillingException;
import com.rootopathy.careos.billing.application.BillingStore;
import com.rootopathy.careos.billing.domain.BillingScreen;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed Module 11 pricing, invoicing, settlement and reconciliation workflow. */
@Repository
public class JdbcBillingStore implements BillingStore {
    private static final String OUTBOX_EVENT = "m11.financial-artifact-changed.v1";

    private final JdbcTemplate jdbc;

    public JdbcBillingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Projection projection(AuthorizedTenantContext context, ScreenQuery query) {
        requireOperationScope(context);
        var projected = switch (query.screenId()) {
            case "P11-01" -> invoiceRows(context, "P11-01");
            case "P11-02" -> priceBookRows(context);
            case "P11-03" -> packageRows(context);
            case "P11-04" -> estimateRows(context, "P11-04");
            case "P11-05" -> invoiceScreenRows(context);
            case "P11-06" -> paymentScreenRows(context);
            case "P11-07" -> paymentIntentScreenRows(context);
            case "P11-08" -> refundAdjustmentRows(context);
            case "P11-09" -> claimScreenRows(context);
            case "P11-10" -> reconciliationRows(context);
            case "P11-11" -> exportRows(context);
            default -> throw notFound("The requested billing screen does not exist.");
        };
        var rows = projected.stream()
                .filter(row -> matches(query, row))
                .limit(query.limit())
                .toList();
        return new Projection(
                metrics(context), columns(query.screenId()), rows,
                notices(query.screenId()), databaseNow());
    }

    @Override
    public Set<String> permissions(AuthorizedTenantContext context) {
        requireOperationScope(context);
        return Set.copyOf(jdbc.queryForList(
                "SELECT permission_key FROM careos_projected_interactive_permissions(?, ?)",
                String.class, context.organizationId(), context.actorId()));
    }

    @Override
    public MutationResult mutate(AuthorizedTenantContext context, MutationCommand command) {
        requireOperationScope(context);
        return switch (command.actionKey()) {
            case "create-price-book" -> createPriceBook(context, command);
            case "add-price-item" -> addPriceItem(context, command);
            case "activate-price-book" -> activatePriceBook(context, command);
            case "create-package" -> createPackage(context, command);
            case "add-package-entitlement" -> addPackageEntitlement(context, command);
            case "activate-package" -> activatePackage(context, command);
            case "create-estimate" -> createEstimate(context, command);
            case "finalize-estimate" -> finalizeEstimate(context, command);
            case "issue-invoice" -> issueInvoice(context, command);
            case "void-invoice" -> voidInvoice(context, command);
            case "record-payment" -> recordPayment(context, command);
            case "create-payment-intent" -> createPaymentIntent(context, command);
            case "record-refund" -> recordRefund(context, command);
            case "record-adjustment" -> recordAdjustment(context, command);
            case "create-claim" -> createClaim(context, command);
            case "submit-claim" -> submitClaim(context, command);
            case "record-remittance" -> recordRemittance(context, command);
            case "create-reconciliation" -> createReconciliation(context, command);
            case "complete-reconciliation" -> completeReconciliation(context, command);
            case "request-financial-export" -> requestExport(context, command);
            default -> throw notFound("The requested billing action does not exist.");
        };
    }

    private MutationResult createPriceBook(
            AuthorizedTenantContext context, MutationCommand command) {
        var id = UuidV7Generator.randomUuid();
        var code = upperCode(field(command, "bookCode"), "bookCode", 2, 40);
        var display = bounded(field(command, "displayName"), 2, 160, "displayName");
        var currency = currency(field(command, "currency"));
        var version = integer(field(command, "versionNumber"), "versionNumber", 1, 1_000_000);
        var from = date(field(command, "effectiveFrom"), "effectiveFrom");
        var to = optionalDate(command.fields().get("effectiveTo"), "effectiveTo");
        requireDateOrder(from, to, "effectiveTo");
        jdbc.update(
                """
                INSERT INTO price_books(
                    id,organization_id,book_code,display_name,currency,version_number,
                    effective_from,effective_to,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,'draft',?,?)
                """,
                id, context.organizationId(), code, display, currency, version,
                Date.valueOf(from), to == null ? null : Date.valueOf(to),
                context.actorId(), context.actorId());
        return result(id, null, "price_book", "billing.price_book.created", "none", "draft", 0, 201);
    }

    private MutationResult addPriceItem(
            AuthorizedTenantContext context, MutationCommand command) {
        var book = lockPriceBook(context, command.targetId());
        requireRevision(book.revision(), command.expectedRevision(), "price book");
        if (!book.status().equals("draft")) throw conflict("Only draft price books accept items.");
        var id = UuidV7Generator.randomUuid();
        var serviceId = fieldUuid(command, "serviceId");
        var itemCode = upperCode(field(command, "itemCode"), "itemCode", 2, 64);
        var display = bounded(field(command, "displayName"), 2, 200, "displayName");
        var amount = money(field(command, "unitAmountMinor"), "unitAmountMinor", true);
        var tax = integer(field(command, "taxBasisPoints"), "taxBasisPoints", 0, 10_000);
        var from = date(field(command, "effectiveFrom"), "effectiveFrom");
        var to = optionalDate(command.fields().get("effectiveTo"), "effectiveTo");
        requireDateOrder(from, to, "effectiveTo");
        var sequence = nextSequence(
                "price_items", "item_sequence", "price_book_id", context.organizationId(), book.id());
        jdbc.update(
                """
                INSERT INTO price_items(
                    id,organization_id,price_book_id,service_id,item_sequence,item_code,
                    display_name,currency,unit_amount_minor,tax_basis_points,
                    effective_from,effective_to,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'active',?,?)
                """,
                id, context.organizationId(), book.id(), serviceId, sequence, itemCode,
                display, book.currency(), amount, tax, Date.valueOf(from),
                to == null ? null : Date.valueOf(to), context.actorId(), context.actorId());
        bumpPriceBook(context, book);
        return result(id, null, "price_item", "billing.price_item.added", "none", "active", 0, 201);
    }

    private MutationResult activatePriceBook(
            AuthorizedTenantContext context, MutationCommand command) {
        var book = lockPriceBook(context, command.targetId());
        requireRevision(book.revision(), command.expectedRevision(), "price book");
        if (!book.status().equals("draft")) throw conflict("Only a draft price book can be activated.");
        var digest = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT careos_m11_price_book_digest(?,?)",
                String.class, context.organizationId(), book.id()));
        var changed = jdbc.update(
                """
                UPDATE price_books SET status='active',configuration_digest=?,
                    activated_at=clock_timestamp(),activated_by=?,activation_reason=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='draft'
                """,
                digest, context.actorId(), command.reason(), context.actorId(),
                context.organizationId(), book.id(), book.revision());
        requireChanged(changed, "The price book changed before activation.");
        return result(book.id(), null, "price_book", "billing.price_book.activated",
                "draft", "active", book.revision() + 1, 200);
    }

    private MutationResult createPackage(
            AuthorizedTenantContext context, MutationCommand command) {
        var book = lockPriceBook(context, fieldUuid(command, "priceBookId"));
        if (!book.status().equals("active")) throw conflict("Package pricing must use an active price book.");
        var id = UuidV7Generator.randomUuid();
        var packageCode = upperCode(field(command, "packageCode"), "packageCode", 2, 64);
        var display = bounded(field(command, "displayName"), 2, 200, "displayName");
        var amount = money(field(command, "packageAmountMinor"), "packageAmountMinor", true);
        var version = integer(field(command, "versionNumber"), "versionNumber", 1, 1_000_000);
        var from = date(field(command, "effectiveFrom"), "effectiveFrom");
        var to = optionalDate(command.fields().get("effectiveTo"), "effectiveTo");
        requireDateOrder(from, to, "effectiveTo");
        jdbc.update(
                """
                INSERT INTO packages(
                    id,organization_id,price_book_id,package_code,display_name,currency,
                    package_amount_minor,version_number,effective_from,effective_to,
                    status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,'draft',?,?)
                """,
                id, context.organizationId(), book.id(), packageCode, display, book.currency(),
                amount, version, Date.valueOf(from), to == null ? null : Date.valueOf(to),
                context.actorId(), context.actorId());
        return result(id, null, "package", "billing.package.created", "none", "draft", 0, 201);
    }

    private MutationResult addPackageEntitlement(
            AuthorizedTenantContext context, MutationCommand command) {
        var packageRecord = lockPackage(context, command.targetId());
        requireRevision(packageRecord.revision(), command.expectedRevision(), "package");
        if (!packageRecord.status().equals("draft")) throw conflict("Only draft packages accept entitlements.");
        var id = UuidV7Generator.randomUuid();
        var sequence = nextSequence(
                "package_entitlements", "entitlement_sequence", "package_id",
                context.organizationId(), packageRecord.id());
        var serviceId = fieldUuid(command, "serviceId");
        var quantity = integer(field(command, "quantity"), "quantity", 1, 10_000);
        var expiry = optionalInteger(command.fields().get("expiresAfterDays"),
                "expiresAfterDays", 1, 3650);
        jdbc.update(
                """
                INSERT INTO package_entitlements(
                    id,organization_id,package_id,service_id,entitlement_sequence,quantity,
                    expires_after_days,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,'active',?,?)
                """,
                id, context.organizationId(), packageRecord.id(), serviceId, sequence,
                quantity, expiry, context.actorId(), context.actorId());
        bumpPackage(context, packageRecord);
        return result(id, null, "package_entitlement", "billing.package_entitlement.added",
                "none", "active", 0, 201);
    }

    private MutationResult activatePackage(
            AuthorizedTenantContext context, MutationCommand command) {
        var packageRecord = lockPackage(context, command.targetId());
        requireRevision(packageRecord.revision(), command.expectedRevision(), "package");
        if (!packageRecord.status().equals("draft")) throw conflict("Only a draft package can be activated.");
        var digest = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT careos_m11_package_digest(?,?)",
                String.class, context.organizationId(), packageRecord.id()));
        var changed = jdbc.update(
                """
                UPDATE packages SET status='active',configuration_digest=?,
                    activated_at=clock_timestamp(),activated_by=?,activation_reason=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='draft'
                """,
                digest, context.actorId(), command.reason(), context.actorId(),
                context.organizationId(), packageRecord.id(), packageRecord.revision());
        requireChanged(changed, "The package changed before activation.");
        return result(packageRecord.id(), null, "package", "billing.package.activated",
                "draft", "active", packageRecord.revision() + 1, 200);
    }

    private MutationResult createEstimate(
            AuthorizedTenantContext context, MutationCommand command) {
        var patientId = fieldUuid(command, "patientId");
        var appointmentId = optionalUuid(command.fields().get("appointmentId"), "appointmentId");
        var bookId = fieldUuid(command, "priceBookId");
        var priceItemId = optionalUuid(command.fields().get("priceItemId"), "priceItemId");
        var packageId = optionalUuid(command.fields().get("packageId"), "packageId");
        if ((priceItemId == null) == (packageId == null)) {
            throw invalid("Exactly one of priceItemId or packageId is required.");
        }
        var estimateNumber = upperCode(field(command, "estimateNumber"), "estimateNumber", 4, 64);
        var quantity = integer(field(command, "quantity"), "quantity", 1, 10_000);
        var validUntil = date(field(command, "validUntil"), "validUntil");
        var source = priceSource(context, bookId, priceItemId, packageId);
        if (source.packageId() != null && quantity != 1) {
            throw invalid("Package estimates must use quantity 1.");
        }
        var subtotal = multiply(source.unitAmountMinor(), quantity, "estimate subtotal");
        var tax = source.packageId() == null
                ? roundedBasisPoints(subtotal, source.taxBasisPoints())
                : 0L;
        var total = add(subtotal, tax, "estimate total");
        var id = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO estimates(
                    id,organization_id,patient_id,appointment_id,price_book_id,
                    source_price_item_id,source_package_id,estimate_number,currency,
                    quantity,unit_amount_minor,subtotal_minor,tax_minor,total_minor,
                    valid_until,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'draft',?,?)
                """,
                id, context.organizationId(), patientId, appointmentId, bookId,
                priceItemId, packageId, estimateNumber, source.currency(), quantity,
                source.unitAmountMinor(), subtotal, tax, total, Date.valueOf(validUntil),
                context.actorId(), context.actorId());
        return result(id, null, "estimate", "billing.estimate.created", "none", "draft", 0, 201);
    }

    private MutationResult finalizeEstimate(
            AuthorizedTenantContext context, MutationCommand command) {
        var estimate = lockEstimate(context, command.targetId());
        requireRevision(estimate.revision(), command.expectedRevision(), "estimate");
        if (!estimate.status().equals("draft")) throw conflict("Only a draft estimate can be finalized.");
        var digest = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT careos_m11_estimate_digest(?,?)",
                String.class, context.organizationId(), estimate.id()));
        var changed = jdbc.update(
                """
                UPDATE estimates SET status='finalized',content_digest=?,
                    finalized_at=clock_timestamp(),finalized_by=?,finalization_reason=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='draft'
                """,
                digest, context.actorId(), command.reason(), context.actorId(),
                context.organizationId(), estimate.id(), estimate.revision());
        requireChanged(changed, "The estimate changed before finalization.");
        return result(estimate.id(), null, "estimate", "billing.estimate.finalized",
                "draft", "finalized", estimate.revision() + 1, 200);
    }

    private MutationResult issueInvoice(
            AuthorizedTenantContext context, MutationCommand command) {
        var estimate = lockEstimate(context, command.targetId());
        requireRevision(estimate.revision(), command.expectedRevision(), "estimate");
        if (!estimate.status().equals("finalized")) throw conflict("Only a finalized estimate can be invoiced.");
        var invoiceNumber = upperCode(field(command, "invoiceNumber"), "invoiceNumber", 4, 64);
        var encounterId = optionalUuid(command.fields().get("encounterId"), "encounterId");
        var dueOn = date(field(command, "dueOn"), "dueOn");
        if (dueOn.isBefore(LocalDate.now(ZoneOffset.UTC))) {
            throw invalid("dueOn must not be in the past.");
        }
        var invoiceId = UuidV7Generator.randomUuid();
        var digest = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT careos_m11_invoice_digest(?,?,?,?,?,?,?,?)",
                String.class, context.organizationId(), estimate.id(), invoiceNumber,
                estimate.currency(), estimate.subtotalMinor(), estimate.taxMinor(),
                estimate.totalMinor(), Date.valueOf(dueOn)));
        jdbc.update(
                """
                INSERT INTO invoices(
                    id,organization_id,patient_id,appointment_id,encounter_id,estimate_id,
                    invoice_number,currency,subtotal_minor,tax_minor,original_total_minor,
                    adjustment_minor,paid_minor,refunded_minor,balance_minor,issued_at,due_on,
                    snapshot_digest,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,0,0,0,?,clock_timestamp(),?,?,'issued',?,?)
                """,
                invoiceId, context.organizationId(), estimate.patientId(), estimate.appointmentId(),
                encounterId, estimate.id(), invoiceNumber, estimate.currency(),
                estimate.subtotalMinor(), estimate.taxMinor(), estimate.totalMinor(),
                estimate.totalMinor(), Date.valueOf(dueOn), digest,
                context.actorId(), context.actorId());
        var source = invoiceSource(context, estimate);
        var itemId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO invoice_items(
                    id,organization_id,invoice_id,service_id,price_item_id,package_id,
                    line_sequence,description,quantity,unit_amount_minor,subtotal_minor,
                    tax_minor,total_minor,currency,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,1,?,?,?,?,?,?,?,'issued',?,?)
                """,
                itemId, context.organizationId(), invoiceId, source.serviceId(),
                estimate.priceItemId(), estimate.packageId(), source.description(), estimate.quantity(),
                estimate.unitAmountMinor(), estimate.subtotalMinor(), estimate.taxMinor(),
                estimate.totalMinor(), estimate.currency(), context.actorId(), context.actorId());
        return result(invoiceId, invoiceId, "invoice", "billing.invoice.issued",
                "none", "issued", 0, 201);
    }

    private MutationResult voidInvoice(
            AuthorizedTenantContext context, MutationCommand command) {
        var invoice = lockInvoice(context, command.targetId());
        requireRevision(invoice.revision(), command.expectedRevision(), "invoice");
        var changed = jdbc.update(
                """
                UPDATE invoices SET status='void',voided_at=clock_timestamp(),voided_by=?,void_reason=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='issued'
                """,
                context.actorId(), command.reason(), context.actorId(), context.organizationId(),
                invoice.id(), invoice.revision());
        requireChanged(changed, "Only an unchanged unsettled invoice can be voided.");
        return result(invoice.id(), invoice.id(), "invoice", "billing.invoice.voided",
                invoice.status(), "void", invoice.revision() + 1, 200);
    }

    private MutationResult recordPayment(
            AuthorizedTenantContext context, MutationCommand command) {
        var invoice = lockInvoice(context, command.targetId());
        requireRevision(invoice.revision(), command.expectedRevision(), "invoice");
        requireOpenInvoice(invoice);
        var source = oneOf(field(command, "source"), "source",
                "manual_cash", "manual_bank", "manual_other");
        var reference = bounded(field(command, "paymentReference"), 4, 120, "paymentReference");
        var amount = money(field(command, "amountMinor"), "amountMinor", false);
        var occurredAt = instant(field(command, "occurredAt"), "occurredAt");
        var id = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO payments(
                    id,organization_id,invoice_id,payment_reference,source_key,
                    amount_minor,currency,occurred_at,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,'settled',?,?)
                """,
                id, context.organizationId(), invoice.id(), reference, source,
                amount, invoice.currency(), Timestamp.from(occurredAt),
                context.actorId(), context.actorId());
        recalculateInvoice(context, invoice);
        return result(id, invoice.id(), "payment", "billing.payment.recorded",
                "none", "settled", 0, 201);
    }

    private MutationResult createPaymentIntent(
            AuthorizedTenantContext context, MutationCommand command) {
        var invoice = lockInvoice(context, command.targetId());
        requireRevision(invoice.revision(), command.expectedRevision(), "invoice");
        requireOpenInvoice(invoice);
        var provider = code(field(command, "providerKey"), "providerKey", 2, 80);
        var amount = money(field(command, "amountMinor"), "amountMinor", false);
        var expiresAt = instant(field(command, "expiresAt"), "expiresAt");
        var id = UuidV7Generator.randomUuid();
        var reference = "pi_" + id.toString().replace("-", "");
        jdbc.update(
                """
                INSERT INTO payment_intents(
                    id,organization_id,invoice_id,intent_reference,provider_key,
                    amount_minor,currency,expires_at,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,'awaiting_provider',?,?)
                """,
                id, context.organizationId(), invoice.id(), reference, provider,
                amount, invoice.currency(), Timestamp.from(expiresAt),
                context.actorId(), context.actorId());
        return result(id, invoice.id(), "payment_intent", "billing.payment_intent.created",
                "none", "awaiting_provider", 0, 201);
    }

    private MutationResult recordRefund(
            AuthorizedTenantContext context, MutationCommand command) {
        var payment = readPayment(context, command.targetId());
        var invoice = lockInvoice(context, payment.invoiceId());
        var reference = bounded(field(command, "refundReference"), 4, 120, "refundReference");
        var amount = money(field(command, "amountMinor"), "amountMinor", false);
        var id = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO refunds(
                    id,organization_id,invoice_id,payment_id,refund_reference,
                    amount_minor,currency,reason,authorized_at,authorized_by,
                    status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,clock_timestamp(),?,'settled',?,?)
                """,
                id, context.organizationId(), invoice.id(), payment.id(), reference,
                amount, payment.currency(), command.reason(), context.actorId(),
                context.actorId(), context.actorId());
        recalculateInvoice(context, invoice);
        return result(id, invoice.id(), "refund", "billing.refund.recorded",
                "none", "settled", 0, 201);
    }

    private MutationResult recordAdjustment(
            AuthorizedTenantContext context, MutationCommand command) {
        var invoice = lockInvoice(context, command.targetId());
        requireRevision(invoice.revision(), command.expectedRevision(), "invoice");
        if (invoice.status().equals("void")) throw conflict("A void invoice cannot be adjusted.");
        var reference = bounded(field(command, "adjustmentReference"), 4, 120, "adjustmentReference");
        var direction = oneOf(field(command, "direction"), "direction", "debit", "credit");
        var amount = money(field(command, "amountMinor"), "amountMinor", false);
        var id = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO adjustments(
                    id,organization_id,invoice_id,adjustment_reference,direction_key,
                    amount_minor,currency,reason,authorized_at,authorized_by,
                    status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,clock_timestamp(),?,'posted',?,?)
                """,
                id, context.organizationId(), invoice.id(), reference, direction,
                amount, invoice.currency(), command.reason(), context.actorId(),
                context.actorId(), context.actorId());
        recalculateInvoice(context, invoice);
        return result(id, invoice.id(), "adjustment", "billing.adjustment.recorded",
                "none", "posted", 0, 201);
    }

    private MutationResult createClaim(
            AuthorizedTenantContext context, MutationCommand command) {
        var invoice = lockInvoice(context, command.targetId());
        requireRevision(invoice.revision(), command.expectedRevision(), "invoice");
        requireOpenInvoice(invoice);
        var id = UuidV7Generator.randomUuid();
        var claimNumber = bounded(field(command, "claimNumber"), 4, 100, "claimNumber");
        var payer = code(field(command, "payerKey"), "payerKey", 2, 80);
        var amount = money(field(command, "amountMinor"), "amountMinor", false);
        jdbc.update(
                """
                INSERT INTO claims(
                    id,organization_id,invoice_id,claim_number,payer_key,amount_minor,
                    remitted_minor,currency,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,0,?,'draft',?,?)
                """,
                id, context.organizationId(), invoice.id(), claimNumber, payer,
                amount, invoice.currency(), context.actorId(), context.actorId());
        return result(id, invoice.id(), "claim", "billing.claim.created", "none", "draft", 0, 201);
    }

    private MutationResult submitClaim(
            AuthorizedTenantContext context, MutationCommand command) {
        var claim = lockClaim(context, command.targetId());
        requireRevision(claim.revision(), command.expectedRevision(), "claim");
        var changed = jdbc.update(
                """
                UPDATE claims SET status='submitted',submitted_at=clock_timestamp(),submitted_by=?,
                    submission_reason=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='draft'
                """,
                context.actorId(), command.reason(), context.actorId(),
                context.organizationId(), claim.id(), claim.revision());
        requireChanged(changed, "Only an unchanged draft claim can be submitted.");
        return result(claim.id(), claim.invoiceId(), "claim", "billing.claim.submitted",
                "draft", "submitted", claim.revision() + 1, 200);
    }

    private MutationResult recordRemittance(
            AuthorizedTenantContext context, MutationCommand command) {
        var claim = lockClaim(context, command.targetId());
        requireRevision(claim.revision(), command.expectedRevision(), "claim");
        if (!Set.of("submitted", "partially_paid").contains(claim.status())) {
            throw conflict("Only a submitted claim can receive remittance.");
        }
        var invoice = lockInvoice(context, claim.invoiceId());
        requireOpenInvoice(invoice);
        var id = UuidV7Generator.randomUuid();
        var reference = bounded(field(command, "remittanceReference"), 4, 120, "remittanceReference");
        var amount = money(field(command, "amountMinor"), "amountMinor", false);
        var receivedAt = instant(field(command, "receivedAt"), "receivedAt");
        var digest = digest(field(command, "evidenceDigest"), "evidenceDigest");
        jdbc.update(
                """
                INSERT INTO remittances(
                    id,organization_id,claim_id,invoice_id,remittance_reference,
                    amount_minor,currency,received_at,evidence_digest,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,'posted',?,?)
                """,
                id, context.organizationId(), claim.id(), invoice.id(), reference,
                amount, claim.currency(), Timestamp.from(receivedAt), digest,
                context.actorId(), context.actorId());
        var remitted = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT coalesce(sum(amount_minor),0) FROM remittances WHERE organization_id=? AND claim_id=?",
                Long.class, context.organizationId(), claim.id()));
        var claimStatus = remitted == claim.amountMinor() ? "paid" : "partially_paid";
        var claimChanged = jdbc.update(
                """
                UPDATE claims SET remitted_minor=?,status=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                remitted, claimStatus, context.actorId(), context.organizationId(),
                claim.id(), claim.revision());
        requireChanged(claimChanged, "The claim changed during remittance recording.");
        recalculateInvoice(context, invoice);
        return result(id, invoice.id(), "remittance", "billing.remittance.recorded",
                "none", "posted", 0, 201);
    }

    private MutationResult createReconciliation(
            AuthorizedTenantContext context, MutationCommand command) {
        var id = UuidV7Generator.randomUuid();
        var type = oneOf(field(command, "type"), "type", "payment", "claim", "refund");
        var reference = bounded(field(command, "reconciliationReference"), 4, 120,
                "reconciliationReference");
        var start = date(field(command, "periodStart"), "periodStart");
        var end = date(field(command, "periodEnd"), "periodEnd");
        requireDateOrder(start, end, "periodEnd");
        var currency = currency(field(command, "currency"));
        var expected = money(field(command, "expectedMinor"), "expectedMinor", true);
        var observed = money(field(command, "observedMinor"), "observedMinor", true);
        var variance = subtract(observed, expected, "reconciliation variance");
        var digest = digest(field(command, "evidenceDigest"), "evidenceDigest");
        var status = variance == 0 ? "matched" : "exception";
        jdbc.update(
                """
                INSERT INTO reconciliations(
                    id,organization_id,reconciliation_type,reconciliation_reference,
                    period_start,period_end,currency,expected_minor,observed_minor,
                    variance_minor,evidence_digest,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id, context.organizationId(), type, reference, Date.valueOf(start),
                Date.valueOf(end), currency, expected, observed, variance, digest, status,
                context.actorId(), context.actorId());
        return result(id, null, "reconciliation", "billing.reconciliation.created",
                "none", status, 0, 201);
    }

    private MutationResult completeReconciliation(
            AuthorizedTenantContext context, MutationCommand command) {
        var reconciliation = lockReconciliation(context, command.targetId());
        requireRevision(reconciliation.revision(), command.expectedRevision(), "reconciliation");
        var changed = jdbc.update(
                """
                UPDATE reconciliations SET status='resolved',resolved_at=clock_timestamp(),
                    resolved_by=?,resolution_reason=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='exception'
                """,
                context.actorId(), command.reason(), context.actorId(), context.organizationId(),
                reconciliation.id(), reconciliation.revision());
        requireChanged(changed, "Only an unchanged reconciliation exception can be resolved.");
        return result(reconciliation.id(), null, "reconciliation",
                "billing.reconciliation.completed", "exception", "resolved",
                reconciliation.revision() + 1, 200);
    }

    private MutationResult requestExport(
            AuthorizedTenantContext context, MutationCommand command) {
        var id = UuidV7Generator.randomUuid();
        var type = oneOf(field(command, "exportType"), "exportType",
                "invoice_register", "payments", "refunds", "claims", "reconciliation", "audit");
        var format = oneOf(field(command, "format"), "format", "csv", "json");
        var start = date(field(command, "periodStart"), "periodStart");
        var end = date(field(command, "periodEnd"), "periodEnd");
        requireDateOrder(start, end, "periodEnd");
        var now = databaseNow();
        var filtersDigest = sha256(type, format, start, end, context.purpose());
        jdbc.update(
                """
                INSERT INTO financial_exports(
                    id,organization_id,export_type,format_key,period_start,period_end,
                    filters_digest,purpose,requested_at,requested_by,expires_at,
                    status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'requested',?,?)
                """,
                id, context.organizationId(), type, format, Date.valueOf(start), Date.valueOf(end),
                filtersDigest, context.purpose(), Timestamp.from(now), context.actorId(),
                Timestamp.from(now.plusSeconds(3600)), context.actorId(), context.actorId());
        return result(id, null, "financial_export", "billing.export.requested",
                "none", "requested", 0, 202);
    }

    private void bumpPriceBook(AuthorizedTenantContext context, PriceBookRecord book) {
        requireChanged(jdbc.update(
                """
                UPDATE price_books SET lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='draft'
                """,
                context.actorId(), context.organizationId(), book.id(), book.revision()),
                "The price book changed while its item was added.");
    }

    private void bumpPackage(AuthorizedTenantContext context, PackageRecord packageRecord) {
        requireChanged(jdbc.update(
                """
                UPDATE packages SET lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='draft'
                """,
                context.actorId(), context.organizationId(), packageRecord.id(), packageRecord.revision()),
                "The package changed while its entitlement was added.");
    }

    private long recalculateInvoice(AuthorizedTenantContext context, InvoiceRecord invoice) {
        var totals = jdbc.queryForMap(
                """
                SELECT
                  coalesce((SELECT sum(amount_minor) FROM payments
                            WHERE organization_id=? AND invoice_id=?),0)
                  +coalesce((SELECT sum(amount_minor) FROM remittances
                            WHERE organization_id=? AND invoice_id=?),0) AS paid,
                  coalesce((SELECT sum(amount_minor) FROM refunds
                            WHERE organization_id=? AND invoice_id=?),0) AS refunded,
                  coalesce((SELECT sum(CASE direction_key WHEN 'debit' THEN amount_minor ELSE -amount_minor END)
                            FROM adjustments WHERE organization_id=? AND invoice_id=?),0) AS adjusted
                """,
                context.organizationId(), invoice.id(), context.organizationId(), invoice.id(),
                context.organizationId(), invoice.id(), context.organizationId(), invoice.id());
        var paid = number(totals.get("paid"));
        var refunded = number(totals.get("refunded"));
        var adjusted = number(totals.get("adjusted"));
        var balance = add(add(invoice.originalTotalMinor(), adjusted, "invoice balance"),
                subtract(refunded, paid, "invoice balance"), "invoice balance");
        if (balance < 0) throw conflict("The financial evidence would overpay the invoice.");
        var status = balance == 0 ? "paid" : paid > refunded ? "partially_paid" : "issued";
        var changed = jdbc.update(
                """
                UPDATE invoices SET adjustment_minor=?,paid_minor=?,refunded_minor=?,balance_minor=?,
                    status=?,lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status<>'void'
                """,
                adjusted, paid, refunded, balance, status, context.actorId(),
                context.organizationId(), invoice.id(), invoice.revision());
        requireChanged(changed, "The invoice changed during monetary allocation.");
        return invoice.revision() + 1;
    }

    private List<BillingScreen.Row> invoiceScreenRows(AuthorizedTenantContext context) {
        var rows = new ArrayList<BillingScreen.Row>();
        rows.addAll(estimateRows(context, "P11-05").stream()
                .filter(row -> row.status().equals("finalized"))
                .toList());
        rows.addAll(invoiceRows(context, "P11-05"));
        return rows;
    }

    private List<BillingScreen.Row> paymentScreenRows(AuthorizedTenantContext context) {
        var rows = new ArrayList<BillingScreen.Row>(invoiceRows(context, "P11-06"));
        rows.addAll(paymentRows(context));
        return rows;
    }

    private List<BillingScreen.Row> paymentIntentScreenRows(AuthorizedTenantContext context) {
        var rows = new ArrayList<BillingScreen.Row>(invoiceRows(context, "P11-07"));
        rows.addAll(paymentIntentRows(context));
        return rows;
    }

    private List<BillingScreen.Row> refundAdjustmentRows(AuthorizedTenantContext context) {
        var rows = new ArrayList<BillingScreen.Row>(invoiceRows(context, "P11-08"));
        rows.addAll(paymentRows(context).stream()
                .map(row -> copyWithScreen("P11-08", row, List.of("record-refund")))
                .toList());
        rows.addAll(jdbc.query(
                """
                SELECT id,invoice_id,payment_id,NULL::uuid AS adjustment_id,
                       refund_reference AS reference,amount_minor,currency,status,created_at,'refund' AS artifact
                FROM refunds WHERE organization_id=?
                UNION ALL
                SELECT id,invoice_id,NULL::uuid,id,adjustment_reference,amount_minor,currency,status,created_at,'adjustment'
                FROM adjustments WHERE organization_id=?
                ORDER BY created_at DESC LIMIT 200
                """,
                (rs, index) -> row("P11-08", rs.getObject("id", UUID.class), rs.getString("status"), 0,
                        refs("invoice", rs.getObject("invoice_id", UUID.class),
                                "payment", rs.getObject("payment_id", UUID.class),
                                "adjustment", rs.getObject("adjustment_id", UUID.class),
                                "refund", rs.getString("artifact").equals("refund")
                                        ? rs.getObject("id", UUID.class) : null),
                        values("artifact", rs.getString("artifact"), "reference", rs.getString("reference"),
                                "amountMinor", Long.toString(rs.getLong("amount_minor")),
                                "currency", rs.getString("currency"), "createdAt", string(rs.getObject("created_at"))),
                        List.of()),
                context.organizationId(), context.organizationId()));
        return rows;
    }

    private List<BillingScreen.Row> claimScreenRows(AuthorizedTenantContext context) {
        var rows = new ArrayList<BillingScreen.Row>(invoiceRows(context, "P11-09"));
        rows.addAll(claimRows(context));
        return rows;
    }

    private List<BillingScreen.Row> priceBookRows(AuthorizedTenantContext context) {
        return jdbc.query(
                """
                SELECT id,book_code,display_name,currency,version_number,effective_from,effective_to,
                       status,lock_version,configuration_digest,updated_at
                FROM price_books WHERE organization_id=? ORDER BY updated_at DESC,id LIMIT 200
                """,
                (rs, index) -> {
                    var status = rs.getString("status");
                    return row("P11-02", rs.getObject("id", UUID.class), status,
                            rs.getLong("lock_version"), refs("priceBook", rs.getObject("id", UUID.class)),
                            values("code", rs.getString("book_code"), "name", rs.getString("display_name"),
                                    "currency", rs.getString("currency"), "version", rs.getString("version_number"),
                                    "effectiveFrom", rs.getString("effective_from"),
                                    "effectiveTo", rs.getString("effective_to"),
                                    "digest", rs.getString("configuration_digest")),
                            status.equals("draft")
                                    ? List.of("add-price-item", "activate-price-book") : List.of());
                },
                context.organizationId());
    }

    private List<BillingScreen.Row> packageRows(AuthorizedTenantContext context) {
        return jdbc.query(
                """
                SELECT id,price_book_id,package_code,display_name,currency,package_amount_minor,
                       version_number,effective_from,effective_to,status,lock_version,configuration_digest,updated_at
                FROM packages WHERE organization_id=? ORDER BY updated_at DESC,id LIMIT 200
                """,
                (rs, index) -> {
                    var status = rs.getString("status");
                    return row("P11-03", rs.getObject("id", UUID.class), status,
                            rs.getLong("lock_version"),
                            refs("priceBook", rs.getObject("price_book_id", UUID.class),
                                    "package", rs.getObject("id", UUID.class)),
                            values("code", rs.getString("package_code"), "name", rs.getString("display_name"),
                                    "currency", rs.getString("currency"),
                                    "amountMinor", rs.getString("package_amount_minor"),
                                    "version", rs.getString("version_number"),
                                    "effectiveFrom", rs.getString("effective_from"),
                                    "effectiveTo", rs.getString("effective_to"),
                                    "digest", rs.getString("configuration_digest")),
                            status.equals("draft")
                                    ? List.of("add-package-entitlement", "activate-package") : List.of());
                },
                context.organizationId());
    }

    private List<BillingScreen.Row> estimateRows(AuthorizedTenantContext context, String screenId) {
        return jdbc.query(
                """
                SELECT id,patient_id,appointment_id,price_book_id,source_package_id,estimate_number,
                       currency,quantity,subtotal_minor,tax_minor,total_minor,valid_until,
                       status,lock_version,content_digest,updated_at
                FROM estimates WHERE organization_id=? ORDER BY updated_at DESC,id LIMIT 200
                """,
                (rs, index) -> {
                    var status = rs.getString("status");
                    var allowed = screenId.equals("P11-04") && status.equals("draft")
                            ? List.of("finalize-estimate")
                            : screenId.equals("P11-05") && status.equals("finalized")
                                    ? List.of("issue-invoice") : List.<String>of();
                    return row(screenId, rs.getObject("id", UUID.class), status,
                            rs.getLong("lock_version"),
                            refs("patient", rs.getObject("patient_id", UUID.class),
                                    "appointment", rs.getObject("appointment_id", UUID.class),
                                    "priceBook", rs.getObject("price_book_id", UUID.class),
                                    "package", rs.getObject("source_package_id", UUID.class),
                                    "estimate", rs.getObject("id", UUID.class)),
                            values("artifact", "estimate", "number", rs.getString("estimate_number"),
                                    "currency", rs.getString("currency"), "quantity", rs.getString("quantity"),
                                    "subtotalMinor", rs.getString("subtotal_minor"),
                                    "taxMinor", rs.getString("tax_minor"), "totalMinor", rs.getString("total_minor"),
                                    "validUntil", rs.getString("valid_until"), "digest", rs.getString("content_digest")),
                            allowed);
                },
                context.organizationId());
    }

    private List<BillingScreen.Row> invoiceRows(AuthorizedTenantContext context, String screenId) {
        return jdbc.query(
                """
                SELECT id,patient_id,appointment_id,encounter_id,estimate_id,invoice_number,currency,
                       original_total_minor,adjustment_minor,paid_minor,refunded_minor,balance_minor,
                       issued_at,due_on,status,lock_version
                FROM invoices WHERE organization_id=? ORDER BY issued_at DESC,id LIMIT 200
                """,
                (rs, index) -> {
                    var status = rs.getString("status");
                    var allowed = new ArrayList<String>();
                    if (screenId.equals("P11-05") && status.equals("issued")
                            && rs.getLong("paid_minor") == rs.getLong("refunded_minor")) {
                        allowed.add("void-invoice");
                    }
                    if (screenId.equals("P11-06") && Set.of("issued", "partially_paid").contains(status)) {
                        allowed.add("record-payment");
                    }
                    if (screenId.equals("P11-07") && Set.of("issued", "partially_paid").contains(status)) {
                        allowed.add("create-payment-intent");
                    }
                    if (screenId.equals("P11-08") && !status.equals("void")) {
                        allowed.add("record-adjustment");
                    }
                    if (screenId.equals("P11-09") && Set.of("issued", "partially_paid").contains(status)) {
                        allowed.add("create-claim");
                    }
                    return row(screenId, rs.getObject("id", UUID.class), status,
                            rs.getLong("lock_version"),
                            refs("patient", rs.getObject("patient_id", UUID.class),
                                    "appointment", rs.getObject("appointment_id", UUID.class),
                                    "encounter", rs.getObject("encounter_id", UUID.class),
                                    "estimate", rs.getObject("estimate_id", UUID.class),
                                    "invoice", rs.getObject("id", UUID.class)),
                            values("artifact", "invoice", "number", rs.getString("invoice_number"),
                                    "currency", rs.getString("currency"),
                                    "originalTotalMinor", rs.getString("original_total_minor"),
                                    "adjustmentMinor", rs.getString("adjustment_minor"),
                                    "paidMinor", rs.getString("paid_minor"),
                                    "refundedMinor", rs.getString("refunded_minor"),
                                    "balanceMinor", rs.getString("balance_minor"),
                                    "issuedAt", string(rs.getObject("issued_at")), "dueOn", rs.getString("due_on")),
                            List.copyOf(allowed));
                },
                context.organizationId());
    }

    private List<BillingScreen.Row> paymentRows(AuthorizedTenantContext context) {
        return jdbc.query(
                """
                SELECT id,invoice_id,payment_intent_id,payment_reference,source_key,
                       amount_minor,currency,occurred_at,status,created_at
                FROM payments WHERE organization_id=? ORDER BY occurred_at DESC,id LIMIT 200
                """,
                (rs, index) -> row("P11-06", rs.getObject("id", UUID.class), rs.getString("status"), 0,
                        refs("invoice", rs.getObject("invoice_id", UUID.class),
                                "paymentIntent", rs.getObject("payment_intent_id", UUID.class),
                                "payment", rs.getObject("id", UUID.class)),
                        values("artifact", "payment", "reference", rs.getString("payment_reference"),
                                "source", rs.getString("source_key"), "amountMinor", rs.getString("amount_minor"),
                                "currency", rs.getString("currency"),
                                "occurredAt", string(rs.getObject("occurred_at"))),
                        List.of()),
                context.organizationId());
    }

    private List<BillingScreen.Row> paymentIntentRows(AuthorizedTenantContext context) {
        return jdbc.query(
                """
                SELECT id,invoice_id,intent_reference,provider_key,amount_minor,currency,
                       expires_at,status,lock_version,created_at
                FROM payment_intents WHERE organization_id=? ORDER BY created_at DESC,id LIMIT 200
                """,
                (rs, index) -> row("P11-07", rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getLong("lock_version"),
                        refs("invoice", rs.getObject("invoice_id", UUID.class),
                                "paymentIntent", rs.getObject("id", UUID.class)),
                        values("artifact", "payment_intent", "reference", rs.getString("intent_reference"),
                                "provider", rs.getString("provider_key"), "amountMinor", rs.getString("amount_minor"),
                                "currency", rs.getString("currency"), "expiresAt", string(rs.getObject("expires_at"))),
                        List.of()),
                context.organizationId());
    }

    private List<BillingScreen.Row> claimRows(AuthorizedTenantContext context) {
        return jdbc.query(
                """
                SELECT id,invoice_id,claim_number,payer_key,amount_minor,remitted_minor,
                       currency,status,lock_version,submitted_at,updated_at
                FROM claims WHERE organization_id=? ORDER BY updated_at DESC,id LIMIT 200
                """,
                (rs, index) -> {
                    var status = rs.getString("status");
                    var allowed = status.equals("draft") ? List.of("submit-claim")
                            : Set.of("submitted", "partially_paid").contains(status)
                                    ? List.of("record-remittance") : List.<String>of();
                    return row("P11-09", rs.getObject("id", UUID.class), status,
                            rs.getLong("lock_version"),
                            refs("invoice", rs.getObject("invoice_id", UUID.class),
                                    "claim", rs.getObject("id", UUID.class)),
                            values("artifact", "claim", "number", rs.getString("claim_number"),
                                    "payer", rs.getString("payer_key"), "amountMinor", rs.getString("amount_minor"),
                                    "remittedMinor", rs.getString("remitted_minor"),
                                    "currency", rs.getString("currency"),
                                    "submittedAt", string(rs.getObject("submitted_at"))),
                            allowed);
                },
                context.organizationId());
    }

    private List<BillingScreen.Row> reconciliationRows(AuthorizedTenantContext context) {
        return jdbc.query(
                """
                SELECT id,reconciliation_type,reconciliation_reference,period_start,period_end,
                       currency,expected_minor,observed_minor,variance_minor,status,lock_version,updated_at
                FROM reconciliations WHERE organization_id=? ORDER BY updated_at DESC,id LIMIT 200
                """,
                (rs, index) -> {
                    var status = rs.getString("status");
                    return row("P11-10", rs.getObject("id", UUID.class), status,
                            rs.getLong("lock_version"),
                            refs("reconciliation", rs.getObject("id", UUID.class)),
                            values("type", rs.getString("reconciliation_type"),
                                    "reference", rs.getString("reconciliation_reference"),
                                    "period", rs.getString("period_start") + " to " + rs.getString("period_end"),
                                    "currency", rs.getString("currency"),
                                    "expectedMinor", rs.getString("expected_minor"),
                                    "observedMinor", rs.getString("observed_minor"),
                                    "varianceMinor", rs.getString("variance_minor")),
                            status.equals("exception")
                                    ? List.of("complete-reconciliation") : List.of());
                },
                context.organizationId());
    }

    private List<BillingScreen.Row> exportRows(AuthorizedTenantContext context) {
        return jdbc.query(
                """
                SELECT id,export_type,format_key,period_start,period_end,purpose,
                       requested_at,expires_at,status,lock_version
                FROM financial_exports WHERE organization_id=? ORDER BY requested_at DESC,id LIMIT 200
                """,
                (rs, index) -> row("P11-11", rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getLong("lock_version"),
                        refs("financialExport", rs.getObject("id", UUID.class)),
                        values("type", rs.getString("export_type"), "format", rs.getString("format_key"),
                                "period", rs.getString("period_start") + " to " + rs.getString("period_end"),
                                "purpose", rs.getString("purpose"),
                                "requestedAt", string(rs.getObject("requested_at")),
                                "expiresAt", string(rs.getObject("expires_at"))),
                        List.of()),
                context.organizationId());
    }

    private List<BillingScreen.Metric> metrics(AuthorizedTenantContext context) {
        var values = jdbc.queryForMap(
                """
                SELECT
                  count(*) FILTER (WHERE status IN ('issued','partially_paid')) AS open_invoices,
                  coalesce(sum(balance_minor) FILTER (WHERE status IN ('issued','partially_paid')),0) AS outstanding,
                  count(*) FILTER (WHERE status IN ('issued','partially_paid') AND due_on<current_date) AS overdue
                FROM invoices WHERE organization_id=?
                """,
                context.organizationId());
        var exceptions = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM reconciliations WHERE organization_id=? AND status='exception'",
                Long.class, context.organizationId()));
        return List.of(
                metric("openInvoices", "Open invoices", number(values.get("open_invoices")), "info"),
                metric("outstandingMinor", "Outstanding (minor units)", number(values.get("outstanding")), "warning"),
                metric("overdue", "Overdue", number(values.get("overdue")), "danger"),
                metric("reconciliationExceptions", "Reconciliation exceptions", exceptions, "warning"));
    }

    private static List<BillingScreen.Column> columns(String screenId) {
        return switch (screenId) {
            case "P11-02" -> List.of(column("code", "Code"), column("name", "Name"),
                    column("currency", "Currency"), column("version", "Version"));
            case "P11-03" -> List.of(column("code", "Code"), column("name", "Package"),
                    column("amountMinor", "Amount"), column("currency", "Currency"));
            case "P11-04" -> List.of(column("number", "Estimate"), column("totalMinor", "Total"),
                    column("currency", "Currency"), column("validUntil", "Valid until"));
            case "P11-05", "P11-01" -> List.of(column("number", "Invoice or estimate"),
                    column("originalTotalMinor", "Total"), column("balanceMinor", "Balance"),
                    column("currency", "Currency"));
            case "P11-06", "P11-07", "P11-08" -> List.of(column("artifact", "Artifact"),
                    column("reference", "Reference"), column("amountMinor", "Amount"),
                    column("currency", "Currency"));
            case "P11-09" -> List.of(column("artifact", "Artifact"), column("number", "Claim or invoice"),
                    column("amountMinor", "Amount"), column("remittedMinor", "Remitted"));
            case "P11-10" -> List.of(column("type", "Type"), column("reference", "Reference"),
                    column("varianceMinor", "Variance"), column("currency", "Currency"));
            case "P11-11" -> List.of(column("type", "Export"), column("format", "Format"),
                    column("period", "Period"), column("purpose", "Purpose"));
            default -> List.of();
        };
    }

    private static List<BillingScreen.Notice> notices(String screenId) {
        var notices = new ArrayList<BillingScreen.Notice>();
        notices.add(notice("info", "Clinical independence",
                "Financial state never completes, blocks, reopens or rewrites a clinical record."));
        if (screenId.equals("P11-07")) {
            notices.add(notice("warning", "Provider link unavailable",
                    "The repository records card-data-free intents only; no hosted payment URL is fabricated."));
        }
        if (screenId.equals("P11-11")) {
            notices.add(notice("warning", "Export worker unavailable",
                    "Requests are retained, but no artifact is produced until an authorized worker and policy are active."));
        }
        return List.copyOf(notices);
    }

    private static boolean matches(ScreenQuery query, BillingScreen.Row row) {
        if (query.patientId() != null && !query.patientId().equals(row.patientId())) return false;
        if (query.invoiceId() != null && !query.invoiceId().equals(row.invoiceId())) return false;
        if (query.status() != null && !query.status().equalsIgnoreCase(row.status())) return false;
        if (query.search() == null) return true;
        var needle = query.search().toLowerCase(Locale.ROOT);
        return row.values().values().stream()
                .filter(Objects::nonNull)
                .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(needle));
    }

    private static BillingScreen.Row copyWithScreen(
            String screenId, BillingScreen.Row source, List<String> actions) {
        return new BillingScreen.Row(
                source.id(), source.patientId(), source.appointmentId(), source.encounterId(),
                source.priceBookId(), source.packageId(), source.estimateId(), source.invoiceId(),
                source.paymentIntentId(), source.paymentId(), source.refundId(), source.adjustmentId(),
                source.claimId(), source.remittanceId(), source.reconciliationId(),
                source.financialExportId(), source.status(), source.revision(),
                etag(screenId, source.id(), source.revision()), source.values(), actions);
    }

    private static BillingScreen.Row row(
            String screenId,
            UUID id,
            String status,
            long revision,
            Map<String, UUID> refs,
            Map<String, String> values,
            List<String> actions) {
        return new BillingScreen.Row(
                id, refs.get("patient"), refs.get("appointment"), refs.get("encounter"),
                refs.get("priceBook"), refs.get("package"), refs.get("estimate"),
                refs.get("invoice"), refs.get("paymentIntent"), refs.get("payment"),
                refs.get("refund"), refs.get("adjustment"), refs.get("claim"),
                refs.get("remittance"), refs.get("reconciliation"), refs.get("financialExport"),
                status, revision, etag(screenId, id, revision), values, actions);
    }

    private static String etag(String screenId, UUID id, long revision) {
        return "\"m11:" + screenId + ":" + id + ":" + revision + "\"";
    }

    private PriceBookRecord lockPriceBook(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,book_code,display_name,currency,effective_from,effective_to,status,lock_version
                FROM price_books WHERE organization_id=? AND id=? FOR UPDATE
                """,
                (rs, index) -> new PriceBookRecord(
                        rs.getObject("id", UUID.class), rs.getString("book_code"),
                        rs.getString("display_name"), rs.getString("currency"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class), rs.getString("status"),
                        rs.getLong("lock_version")),
                context.organizationId(), id);
        if (rows.isEmpty()) throw notFound("The price book is unavailable.");
        return rows.getFirst();
    }

    private PackageRecord lockPackage(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,price_book_id,package_code,display_name,currency,package_amount_minor,
                       effective_from,effective_to,status,lock_version
                FROM packages WHERE organization_id=? AND id=? FOR UPDATE
                """,
                (rs, index) -> new PackageRecord(
                        rs.getObject("id", UUID.class), rs.getObject("price_book_id", UUID.class),
                        rs.getString("package_code"), rs.getString("display_name"),
                        rs.getString("currency"), rs.getLong("package_amount_minor"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class), rs.getString("status"),
                        rs.getLong("lock_version")),
                context.organizationId(), id);
        if (rows.isEmpty()) throw notFound("The package is unavailable.");
        return rows.getFirst();
    }

    private EstimateRecord lockEstimate(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,patient_id,appointment_id,price_book_id,source_price_item_id,
                       source_package_id,estimate_number,currency,quantity,unit_amount_minor,
                       subtotal_minor,tax_minor,total_minor,valid_until,status,lock_version
                FROM estimates WHERE organization_id=? AND id=? FOR UPDATE
                """,
                (rs, index) -> new EstimateRecord(
                        rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("appointment_id", UUID.class),
                        rs.getObject("price_book_id", UUID.class),
                        rs.getObject("source_price_item_id", UUID.class),
                        rs.getObject("source_package_id", UUID.class),
                        rs.getString("estimate_number"), rs.getString("currency"),
                        rs.getInt("quantity"), rs.getLong("unit_amount_minor"),
                        rs.getLong("subtotal_minor"), rs.getLong("tax_minor"),
                        rs.getLong("total_minor"), rs.getObject("valid_until", LocalDate.class),
                        rs.getString("status"), rs.getLong("lock_version")),
                context.organizationId(), id);
        if (rows.isEmpty()) throw notFound("The estimate is unavailable.");
        return rows.getFirst();
    }

    private InvoiceRecord lockInvoice(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,patient_id,currency,original_total_minor,adjustment_minor,paid_minor,
                       refunded_minor,balance_minor,status,lock_version
                FROM invoices WHERE organization_id=? AND id=? FOR UPDATE
                """,
                (rs, index) -> new InvoiceRecord(
                        rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getString("currency"), rs.getLong("original_total_minor"),
                        rs.getLong("adjustment_minor"), rs.getLong("paid_minor"),
                        rs.getLong("refunded_minor"), rs.getLong("balance_minor"),
                        rs.getString("status"), rs.getLong("lock_version")),
                context.organizationId(), id);
        if (rows.isEmpty()) throw notFound("The invoice is unavailable.");
        return rows.getFirst();
    }

    private PaymentRecord readPayment(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,invoice_id,amount_minor,currency,status
                FROM payments WHERE organization_id=? AND id=?
                """,
                (rs, index) -> new PaymentRecord(
                        rs.getObject("id", UUID.class), rs.getObject("invoice_id", UUID.class),
                        rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("status")),
                context.organizationId(), id);
        if (rows.isEmpty()) throw notFound("The payment is unavailable.");
        return rows.getFirst();
    }

    private ClaimRecord lockClaim(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,invoice_id,amount_minor,remitted_minor,currency,status,lock_version
                FROM claims WHERE organization_id=? AND id=? FOR UPDATE
                """,
                (rs, index) -> new ClaimRecord(
                        rs.getObject("id", UUID.class), rs.getObject("invoice_id", UUID.class),
                        rs.getLong("amount_minor"), rs.getLong("remitted_minor"),
                        rs.getString("currency"), rs.getString("status"), rs.getLong("lock_version")),
                context.organizationId(), id);
        if (rows.isEmpty()) throw notFound("The claim is unavailable.");
        return rows.getFirst();
    }

    private ReconciliationRecord lockReconciliation(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                "SELECT id,status,lock_version FROM reconciliations WHERE organization_id=? AND id=? FOR UPDATE",
                (rs, index) -> new ReconciliationRecord(
                        rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getLong("lock_version")),
                context.organizationId(), id);
        if (rows.isEmpty()) throw notFound("The reconciliation is unavailable.");
        return rows.getFirst();
    }

    private PriceSource priceSource(
            AuthorizedTenantContext context, UUID bookId, UUID priceItemId, UUID packageId) {
        if (priceItemId != null) {
            var rows = jdbc.query(
                    """
                    SELECT item.id,item.service_id,item.display_name,item.currency,
                           item.unit_amount_minor,item.tax_basis_points
                    FROM price_items item JOIN price_books book
                      ON book.organization_id=item.organization_id AND book.id=item.price_book_id
                    WHERE item.organization_id=? AND item.id=? AND item.price_book_id=?
                      AND book.status='active' AND current_date>=book.effective_from
                      AND (book.effective_to IS NULL OR current_date<=book.effective_to)
                      AND current_date>=item.effective_from
                      AND (item.effective_to IS NULL OR current_date<=item.effective_to)
                    """,
                    (rs, index) -> new PriceSource(
                            rs.getObject("id", UUID.class), null,
                            rs.getObject("service_id", UUID.class), rs.getString("display_name"),
                            rs.getString("currency"), rs.getLong("unit_amount_minor"),
                            rs.getInt("tax_basis_points")),
                    context.organizationId(), priceItemId, bookId);
            if (rows.isEmpty()) throw notFound("The active price item is unavailable.");
            return rows.getFirst();
        }
        var rows = jdbc.query(
                """
                SELECT package.id,package.display_name,package.currency,package.package_amount_minor
                FROM packages package JOIN price_books book
                  ON book.organization_id=package.organization_id AND book.id=package.price_book_id
                WHERE package.organization_id=? AND package.id=? AND package.price_book_id=?
                  AND package.status='active' AND book.status='active'
                  AND current_date>=package.effective_from
                  AND (package.effective_to IS NULL OR current_date<=package.effective_to)
                """,
                (rs, index) -> new PriceSource(
                        null, rs.getObject("id", UUID.class), null, rs.getString("display_name"),
                        rs.getString("currency"), rs.getLong("package_amount_minor"), 0),
                context.organizationId(), packageId, bookId);
        if (rows.isEmpty()) throw notFound("The active package is unavailable.");
        return rows.getFirst();
    }

    private InvoiceSource invoiceSource(AuthorizedTenantContext context, EstimateRecord estimate) {
        if (estimate.priceItemId() != null) {
            return jdbc.queryForObject(
                    "SELECT service_id,display_name FROM price_items WHERE organization_id=? AND id=?",
                    (rs, index) -> new InvoiceSource(
                            rs.getObject("service_id", UUID.class), rs.getString("display_name")),
                    context.organizationId(), estimate.priceItemId());
        }
        return jdbc.queryForObject(
                "SELECT display_name FROM packages WHERE organization_id=? AND id=?",
                (rs, index) -> new InvoiceSource(null, rs.getString("display_name")),
                context.organizationId(), estimate.packageId());
    }

    private int nextSequence(
            String table, String column, String parentColumn, UUID organizationId, UUID parentId) {
        var supported = Set.of(
                "price_items:item_sequence:price_book_id",
                "package_entitlements:entitlement_sequence:package_id");
        if (!supported.contains(table + ":" + column + ":" + parentColumn)) {
            throw new IllegalArgumentException("Unsupported finance sequence.");
        }
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT coalesce(max(" + column + "),0)+1 FROM " + table
                        + " WHERE organization_id=? AND " + parentColumn + "=?",
                Integer.class, organizationId, parentId));
    }

    private void requireOperationScope(AuthorizedTenantContext context) {
        var bound = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT nullif(current_setting('app.current_organization_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_actor_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_operation_key',true),'') IS NOT NULL
                """,
                Boolean.class, context.organizationId(), context.actorId()));
        if (!bound) {
            throw notFound("The billing resource is unavailable or is not assigned to this account.");
        }
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .toInstant();
    }

    private static void requireOpenInvoice(InvoiceRecord invoice) {
        if (!Set.of("issued", "partially_paid").contains(invoice.status()) || invoice.balanceMinor() <= 0) {
            throw conflict("The invoice is not open for this financial action.");
        }
    }

    private static String field(MutationCommand command, String key) {
        var value = command.fields().get(key);
        if (value == null || value.isBlank()) throw invalid(key + " is required.");
        return value.strip();
    }

    private static UUID fieldUuid(MutationCommand command, String key) {
        return uuid(field(command, key), key);
    }

    private static UUID optionalUuid(String value, String field) {
        return value == null || value.isBlank() ? null : uuid(value.strip(), field);
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(field + " must be a UUID.");
        }
    }

    private static String bounded(String value, int minimum, int maximum, String field) {
        if (value == null) throw invalid(field + " is required.");
        var normalized = value.strip();
        var length = normalized.codePointCount(0, normalized.length());
        if (length < minimum || length > maximum) {
            throw invalid(field + " must contain " + minimum + " to " + maximum + " characters.");
        }
        return normalized;
    }

    private static String upperCode(String value, String field, int minimum, int maximum) {
        var normalized = bounded(value, minimum, maximum, field).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_.-]*")) {
            throw invalid(field + " has an invalid format.");
        }
        return normalized;
    }

    private static String code(String value, String field, int minimum, int maximum) {
        var normalized = bounded(value, minimum, maximum, field).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9_.:-]*")) {
            throw invalid(field + " has an invalid format.");
        }
        return normalized;
    }

    private static String currency(String value) {
        var normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) throw invalid("currency must contain three uppercase letters.");
        return normalized;
    }

    private static String digest(String value, String field) {
        var normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) throw invalid(field + " must be a lowercase SHA-256 digest.");
        return normalized;
    }

    private static int integer(String value, String field, int minimum, int maximum) {
        try {
            var parsed = Integer.parseInt(value.strip());
            if (parsed < minimum || parsed > maximum) throw invalid(field + " is outside its range.");
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid(field + " must be a whole number.");
        }
    }

    private static Integer optionalInteger(
            String value, String field, int minimum, int maximum) {
        return value == null || value.isBlank() ? null : integer(value, field, minimum, maximum);
    }

    private static long money(String value, String field, boolean zeroAllowed) {
        try {
            var parsed = Long.parseLong(value.strip());
            if (parsed < (zeroAllowed ? 0 : 1)) throw invalid(field + " is outside its range.");
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid(field + " must be a whole minor-unit amount.");
        }
    }

    private static LocalDate date(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw invalid(field + " must be an ISO local date.");
        }
    }

    private static LocalDate optionalDate(String value, String field) {
        return value == null || value.isBlank() ? null : date(value.strip(), field);
    }

    private static Instant instant(String value, String field) {
        try {
            if (value.endsWith("Z") || value.matches(".*[+-][0-9]{2}:[0-9]{2}$")) {
                return OffsetDateTime.parse(value).toInstant();
            }
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException exception) {
            throw invalid(field + " must be an ISO date-time.");
        }
    }

    private static void requireDateOrder(LocalDate from, LocalDate to, String field) {
        if (to != null && to.isBefore(from)) throw invalid(field + " must not precede the start date.");
    }

    private static String oneOf(String value, String field, String... options) {
        for (var option : options) if (option.equals(value)) return value;
        throw invalid(field + " contains an unsupported value.");
    }

    private static long multiply(long value, int quantity, String field) {
        try {
            return BigInteger.valueOf(value).multiply(BigInteger.valueOf(quantity)).longValueExact();
        } catch (ArithmeticException exception) {
            throw invalid(field + " exceeds the supported minor-unit range.");
        }
    }

    private static long roundedBasisPoints(long value, int basisPoints) {
        try {
            return BigInteger.valueOf(value)
                    .multiply(BigInteger.valueOf(basisPoints))
                    .add(BigInteger.valueOf(5000))
                    .divide(BigInteger.valueOf(10000))
                    .longValueExact();
        } catch (ArithmeticException exception) {
            throw invalid("Calculated tax exceeds the supported minor-unit range.");
        }
    }

    private static long add(long left, long right, String field) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw invalid(field + " exceeds the supported minor-unit range.");
        }
    }

    private static long subtract(long left, long right, String field) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException exception) {
            throw invalid(field + " exceeds the supported minor-unit range.");
        }
    }

    private static String sha256(Object... values) {
        try {
            var canonical = new StringBuilder("m11-finance-v1|");
            for (var value : values) {
                var text = Objects.toString(value, "<null>");
                canonical.append(text.length()).append(':').append(text).append(';');
            }
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate financial evidence digest.", exception);
        }
    }

    private static void requireRevision(long actual, Long expected, String target) {
        if (expected == null) {
            throw new BillingException(
                    BillingException.Reason.PRECONDITION_REQUIRED,
                    "A strong " + target + " revision is required.");
        }
        if (actual != expected) {
            throw new BillingException(
                    BillingException.Reason.STALE,
                    "The " + target + " changed; reload before continuing.");
        }
    }

    private static void requireChanged(int changed, String message) {
        if (changed != 1) throw conflict(message);
    }

    private static Map<String, UUID> refs(Object... entries) {
        var values = new LinkedHashMap<String, UUID>();
        for (var index = 0; index < entries.length; index += 2) {
            if (entries[index + 1] != null) values.put((String) entries[index], (UUID) entries[index + 1]);
        }
        return Map.copyOf(values);
    }

    private static Map<String, String> values(String... entries) {
        var values = new LinkedHashMap<String, String>();
        for (var index = 0; index < entries.length; index += 2) {
            values.put(entries[index], safe(entries[index + 1], "Not recorded"));
        }
        return Map.copyOf(values);
    }

    private static MutationResult result(
            UUID subjectId,
            UUID invoiceId,
            String subjectType,
            String auditEvent,
            String fromState,
            String toState,
            long revision,
            int statusCode) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("artifactId", subjectId);
        payload.put("artifactType", subjectType);
        payload.put("fromState", fromState);
        payload.put("toState", toState);
        payload.put("revision", revision);
        var immutable = Map.<String, Object>copyOf(payload);
        return new MutationResult(
                subjectId, invoiceId, subjectType, auditEvent, OUTBOX_EVENT,
                "financial_artifact", subjectId, immutable, immutable, statusCode, revision);
    }

    private static BillingScreen.Column column(String key, String label) {
        return new BillingScreen.Column(key, label);
    }

    private static BillingScreen.Metric metric(String key, String label, long value, String tone) {
        return new BillingScreen.Metric(key, label, value, tone);
    }

    private static BillingScreen.Notice notice(String tone, String title, String detail) {
        return new BillingScreen.Notice(tone, title, detail);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String string(Object value) {
        return value == null ? "Not recorded" : value.toString();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static BillingException invalid(String message) {
        return new BillingException(BillingException.Reason.INVALID, message);
    }

    private static BillingException notFound(String message) {
        return new BillingException(BillingException.Reason.NOT_FOUND, message);
    }

    private static BillingException conflict(String message) {
        return new BillingException(BillingException.Reason.CONFLICT, message);
    }

    private record PriceBookRecord(
            UUID id,
            String code,
            String display,
            String currency,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status,
            long revision) {}

    private record PackageRecord(
            UUID id,
            UUID priceBookId,
            String code,
            String display,
            String currency,
            long amountMinor,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status,
            long revision) {}

    private record EstimateRecord(
            UUID id,
            UUID patientId,
            UUID appointmentId,
            UUID priceBookId,
            UUID priceItemId,
            UUID packageId,
            String number,
            String currency,
            int quantity,
            long unitAmountMinor,
            long subtotalMinor,
            long taxMinor,
            long totalMinor,
            LocalDate validUntil,
            String status,
            long revision) {}

    private record InvoiceRecord(
            UUID id,
            UUID patientId,
            String currency,
            long originalTotalMinor,
            long adjustmentMinor,
            long paidMinor,
            long refundedMinor,
            long balanceMinor,
            String status,
            long revision) {}

    private record PaymentRecord(
            UUID id, UUID invoiceId, long amountMinor, String currency, String status) {}

    private record ClaimRecord(
            UUID id,
            UUID invoiceId,
            long amountMinor,
            long remittedMinor,
            String currency,
            String status,
            long revision) {}

    private record ReconciliationRecord(UUID id, String status, long revision) {}

    private record PriceSource(
            UUID priceItemId,
            UUID packageId,
            UUID serviceId,
            String description,
            String currency,
            long unitAmountMinor,
            int taxBasisPoints) {}

    private record InvoiceSource(UUID serviceId, String description) {}
}
