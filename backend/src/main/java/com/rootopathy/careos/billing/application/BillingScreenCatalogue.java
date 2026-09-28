package com.rootopathy.careos.billing.application;

import com.rootopathy.careos.billing.domain.BillingScreen;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Repository-owned contract for the eleven Module 11 billing and payments screens. */
final class BillingScreenCatalogue {
    private static final List<String> TITLES = List.of(
            "Billing dashboard",
            "Price books",
            "Packages",
            "Estimate",
            "Invoice",
            "Payment",
            "Payment link",
            "Refund or adjustment",
            "Claims",
            "Reconciliation",
            "Financial audit or export");
    private static final Map<String, ScreenSpec> SCREENS = screens();

    private BillingScreenCatalogue() {}

    static ScreenSpec screen(String id) {
        var screen = SCREENS.get(id);
        if (screen == null) {
            throw new BillingException(
                    BillingException.Reason.NOT_FOUND,
                    "The requested billing screen does not exist.");
        }
        return screen;
    }

    static ActionSpec mutation(String screenId, String actionKey) {
        return screen(screenId).actions().stream()
                .filter(action -> action.key().equals(actionKey))
                .findFirst()
                .orElseThrow(() -> new BillingException(
                        BillingException.Reason.NOT_FOUND,
                        "The requested billing action does not exist."));
    }

    static List<BillingScreen.Action> projectedActions(
            ScreenSpec screen, Set<String> permissions) {
        return screen.actions().stream()
                .filter(action -> permissions.contains(action.permission()))
                .map(ActionSpec::projection)
                .toList();
    }

    static List<String> titles() {
        return TITLES;
    }

    record ScreenSpec(
            String id,
            String title,
            String purpose,
            String readOperation,
            List<ActionSpec> actions) {}

    record ActionSpec(
            String key,
            String label,
            String operation,
            String permission,
            String style,
            boolean targetRequired,
            boolean ifMatchRequired,
            boolean reasonRequired,
            List<BillingScreen.Field> fields) {
        BillingScreen.Action projection() {
            return new BillingScreen.Action(
                    key, label, style, targetRequired, ifMatchRequired, reasonRequired, fields);
        }
    }

    private static Map<String, ScreenSpec> screens() {
        var screens = new LinkedHashMap<String, ScreenSpec>();
        add(screens, 1, "Review open invoices, settlement state, claims and reconciliation exceptions.");
        add(screens, 2, "Version, populate and activate currency-bound price books.",
                action("create-price-book", "Create price book", "billing.pricebook.create", false, false,
                        code("bookCode", "Book code", true), text("displayName", "Display name", true),
                        currency(), number("versionNumber", "Version", true), date("effectiveFrom", "Effective from", true),
                        date("effectiveTo", "Effective to", false)),
                action("add-price-item", "Add price item", "billing.pricebook.item.add", true, true,
                        uuid("serviceId", "Active service", true), code("itemCode", "Item code", true),
                        text("displayName", "Display name", true), money("unitAmountMinor", "Unit amount (minor units)", true),
                        number("taxBasisPoints", "Tax (basis points)", true), date("effectiveFrom", "Effective from", true),
                        date("effectiveTo", "Effective to", false)),
                action("activate-price-book", "Activate price book", "billing.pricebook.activate", true, true));
        add(screens, 3, "Version packages and exact service entitlements against active pricing.",
                action("create-package", "Create package", "billing.package.create", false, false,
                        uuid("priceBookId", "Active price book", true), code("packageCode", "Package code", true),
                        text("displayName", "Display name", true), money("packageAmountMinor", "Package amount (minor units)", true),
                        number("versionNumber", "Version", true), date("effectiveFrom", "Effective from", true),
                        date("effectiveTo", "Effective to", false)),
                action("add-package-entitlement", "Add entitlement", "billing.package.entitlement.add", true, true,
                        uuid("serviceId", "Active service", true), number("quantity", "Quantity", true),
                        number("expiresAfterDays", "Expiry after days", false)),
                action("activate-package", "Activate package", "billing.package.activate", true, true));
        add(screens, 4, "Create and freeze a patient-bound estimate from authoritative active pricing.",
                action("create-estimate", "Create estimate", "billing.estimate.create", false, false,
                        uuid("patientId", "Patient", true), uuid("appointmentId", "Appointment", false),
                        uuid("priceBookId", "Price book", true), uuid("priceItemId", "Price item", false),
                        uuid("packageId", "Package", false), text("estimateNumber", "Estimate number", true),
                        number("quantity", "Quantity", true), date("validUntil", "Valid until", true)),
                action("finalize-estimate", "Finalize estimate", "billing.estimate.finalize", true, true));
        add(screens, 5, "Issue immutable invoice lines from a valid finalized estimate without changing clinical state.",
                action("issue-invoice", "Issue invoice", "billing.invoice.issue", true, true,
                        text("invoiceNumber", "Invoice number", true), uuid("encounterId", "Encounter", false),
                        date("dueOn", "Due on", true)),
                action("void-invoice", "Void invoice", "billing.invoice.void", true, true));
        add(screens, 6, "Record non-card manual settlement evidence using exact invoice amount and currency.",
                action("record-payment", "Record payment", "billing.payment.record", true, true,
                        select("source", "Source", true, "manual_cash", "Cash", "manual_bank", "Bank transfer", "manual_other", "Other approved manual source"),
                        text("paymentReference", "Payment reference", true), money("amountMinor", "Amount (minor units)", true),
                        dateTime("occurredAt", "Occurred at", true)));
        add(screens, 7, "Create a card-data-free payment intent; provider-hosted link delivery remains fail closed.",
                action("create-payment-intent", "Create payment intent", "billing.payment.intent.create", true, true,
                        code("providerKey", "Provider key", true), money("amountMinor", "Amount (minor units)", true),
                        dateTime("expiresAt", "Expires at", true)));
        add(screens, 8, "Append authorized refunds or debit/credit adjustments without rewriting settlement history.",
                action("record-refund", "Record refund", "billing.refund.record", true, false,
                        text("refundReference", "Refund reference", true), money("amountMinor", "Amount (minor units)", true)),
                action("record-adjustment", "Record adjustment", "billing.adjustment.record", true, true,
                        text("adjustmentReference", "Adjustment reference", true),
                        select("direction", "Direction", true, "debit", "Debit", "credit", "Credit"),
                        money("amountMinor", "Amount (minor units)", true)));
        add(screens, 9, "Create invoice-bound claims, submit exact amounts and append remittance evidence.",
                action("create-claim", "Create claim", "billing.claim.create", true, true,
                        text("claimNumber", "Claim number", true), code("payerKey", "Payer key", true),
                        money("amountMinor", "Claim amount (minor units)", true)),
                action("submit-claim", "Submit claim", "billing.claim.submit", true, true),
                action("record-remittance", "Record remittance", "billing.remittance.record", true, true,
                        text("remittanceReference", "Remittance reference", true),
                        money("amountMinor", "Amount (minor units)", true),
                        dateTime("receivedAt", "Received at", true), digest("evidenceDigest", "Evidence SHA-256", true)));
        add(screens, 10, "Snapshot expected and observed totals and explicitly resolve every variance.",
                action("create-reconciliation", "Create reconciliation", "billing.reconciliation.create", false, false,
                        select("type", "Type", true, "payment", "Payment", "claim", "Claim", "refund", "Refund"),
                        text("reconciliationReference", "Reference", true), date("periodStart", "Period start", true),
                        date("periodEnd", "Period end", true), currency(),
                        money("expectedMinor", "Expected (minor units)", true), money("observedMinor", "Observed (minor units)", true),
                        digest("evidenceDigest", "Evidence SHA-256", true)),
                action("complete-reconciliation", "Resolve variance", "billing.reconciliation.complete", true, true));
        add(screens, 11, "Review financial evidence and request bounded purpose-specific exports.",
                action("request-financial-export", "Request export", "billing.export.create", false, false,
                        select("exportType", "Export type", true,
                                "invoice_register", "Invoice register", "payments", "Payments", "refunds", "Refunds",
                                "claims", "Claims", "reconciliation", "Reconciliation", "audit", "Audit"),
                        select("format", "Format", true, "csv", "CSV", "json", "JSON"),
                        date("periodStart", "Period start", true), date("periodEnd", "Period end", true)));
        return Map.copyOf(screens);
    }

    private static void add(
            Map<String, ScreenSpec> target, int sequence, String purpose, ActionSpec... actions) {
        var id = "P11-%02d".formatted(sequence);
        target.put(id, new ScreenSpec(
                id, TITLES.get(sequence - 1), purpose, "billing.read", List.of(actions)));
    }

    private static ActionSpec action(
            String key,
            String label,
            String operation,
            boolean targetRequired,
            boolean ifMatchRequired,
            BillingScreen.Field... fields) {
        return new ActionSpec(
                key, label, operation, operation, "primary", targetRequired, ifMatchRequired, true,
                List.of(fields));
    }

    private static BillingScreen.Field text(String key, String label, boolean required) {
        return new BillingScreen.Field(key, label, "text", required, null, List.of());
    }

    private static BillingScreen.Field code(String key, String label, boolean required) {
        return text(key, label, required);
    }

    private static BillingScreen.Field uuid(String key, String label, boolean required) {
        return new BillingScreen.Field(key, label, "uuid", required, null, List.of());
    }

    private static BillingScreen.Field date(String key, String label, boolean required) {
        return new BillingScreen.Field(key, label, "date", required, null, List.of());
    }

    private static BillingScreen.Field dateTime(String key, String label, boolean required) {
        return new BillingScreen.Field(key, label, "datetime-local", required, null, List.of());
    }

    private static BillingScreen.Field number(String key, String label, boolean required) {
        return new BillingScreen.Field(key, label, "number", required, null, List.of());
    }

    private static BillingScreen.Field money(String key, String label, boolean required) {
        return number(key, label, required);
    }

    private static BillingScreen.Field digest(String key, String label, boolean required) {
        return text(key, label, required);
    }

    private static BillingScreen.Field currency() {
        return text("currency", "Currency", true);
    }

    private static BillingScreen.Field select(
            String key, String label, boolean required, String... entries) {
        var options = new ArrayList<BillingScreen.Option>();
        for (var index = 0; index < entries.length; index += 2) {
            options.add(new BillingScreen.Option(entries[index], entries[index + 1]));
        }
        return new BillingScreen.Field(key, label, "select", required, null, options);
    }
}
