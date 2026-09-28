package com.rootopathy.careos.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class IntegrationScreenCatalogueTest {
    @Test
    void exposesTheExactTenScreenRegister() {
        assertThat(IntegrationScreenCatalogue.titles())
                .containsExactly(
                        "Integration dashboard",
                        "FHIR endpoints",
                        "Terminology mappings",
                        "WhatsApp messaging",
                        "Payment configuration",
                        "Calendar integration",
                        "Lab/imaging interfaces",
                        "Webhook management",
                        "Mobile/API clients",
                        "Integration audit and replay");
        assertThat(IntegrationScreenCatalogue.screen("P13-10").title())
                .isEqualTo("Integration audit and replay");
    }

    @Test
    void filtersCriticalActionsByExactPermission() {
        var screen = IntegrationScreenCatalogue.screen("P13-10");
        assertThat(IntegrationScreenCatalogue.projectedActions(
                        screen, Set.of("integration.replay.authorize")))
                .extracting(action -> action.key())
                .containsExactly("authorize-replay");
    }

    @Test
    void keepsProviderFamilyFixedByScreenAndRejectsUnknownActions() {
        assertThat(IntegrationScreenCatalogue.screen("P13-02").providerKind()).isEqualTo("fhir");
        assertThat(IntegrationScreenCatalogue.screen("P13-08").providerKind()).isEqualTo("webhook");
        assertThatThrownBy(() -> IntegrationScreenCatalogue.screen("P13-11"))
                .isInstanceOf(IntegrationException.class);
        assertThatThrownBy(() -> IntegrationScreenCatalogue.mutation("P13-08", "submit-payload"))
                .isInstanceOf(IntegrationException.class);
    }
}
