package com.rootopathy.careos.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class BillingScreenCatalogueTest {
    @Test
    void exposesTheExactElevenScreenRegister() {
        assertThat(BillingScreenCatalogue.titles())
                .containsExactly(
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
        assertThat(BillingScreenCatalogue.screen("P11-11").title())
                .isEqualTo("Financial audit or export");
    }

    @Test
    void filtersActionsByExactPermission() {
        var screen = BillingScreenCatalogue.screen("P11-08");
        assertThat(BillingScreenCatalogue.projectedActions(
                        screen, Set.of("billing.refund.record")))
                .extracting(action -> action.key())
                .containsExactly("record-refund");
    }

    @Test
    void rejectsUnknownScreensAndActions() {
        assertThatThrownBy(() -> BillingScreenCatalogue.screen("P11-12"))
                .isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> BillingScreenCatalogue.mutation("P11-02", "store-card"))
                .isInstanceOf(BillingException.class);
    }
}
