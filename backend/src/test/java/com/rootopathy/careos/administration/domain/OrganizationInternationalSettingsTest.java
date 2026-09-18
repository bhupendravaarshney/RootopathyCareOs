package com.rootopathy.careos.administration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationInternationalSettingsTest {
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID CURRENT_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000a01");
    private static final UUID SCHEDULED_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000a02");
    private static final Instant NOW = Instant.parse("2026-09-18T08:00:00Z");

    @Test
    void acceptsOneCurrentAndOneScheduledVersionWithLocaleLibraryPreviews() {
        var current = version(CURRENT_ID, "active", null, 4, "en-IN", "INR");
        var scheduled = version(SCHEDULED_ID, "scheduled", CURRENT_ID, 4, "en-GB", "GBP");

        var settings = new OrganizationInternationalSettings(
                ORGANIZATION_ID,
                true,
                false,
                4,
                NOW,
                OrganizationInternationalSettings.APPROVED_WEEK_STARTS,
                OrganizationInternationalSettings.APPROVED_IMPACT_RULES,
                List.of(scheduled, current));

        assertThat(settings.versions()).hasSize(2);
        assertThat(settings.impactRules())
                .extracting(InternationalSettingsImpact::field)
                .containsExactly(
                        "countryCode",
                        "timezone",
                        "locale",
                        "language",
                        "currencyCode",
                        "weekStart");
        assertThat(scheduled.formatPreview().localeLibraryDerived()).isTrue();
        assertThat(scheduled.formatPreview().sampleCurrency()).contains("£");
    }

    @Test
    void rejectsSchedulingAuthorityWithAnExistingScheduledVersion() {
        var current = version(CURRENT_ID, "active", null, 4, "en-IN", "INR");
        var scheduled = version(SCHEDULED_ID, "scheduled", CURRENT_ID, 4, "en-GB", "GBP");

        assertThatThrownBy(() -> new OrganizationInternationalSettings(
                        ORGANIZATION_ID,
                        true,
                        true,
                        4,
                        NOW,
                        OrganizationInternationalSettings.APPROVED_WEEK_STARTS,
                        OrganizationInternationalSettings.APPROVED_IMPACT_RULES,
                        List.of(scheduled, current)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocks another schedule");
    }

    @Test
    void rejectsRevisionAndLifecycleDrift() {
        var current = version(CURRENT_ID, "active", null, 4, "en-IN", "INR");

        assertThatThrownBy(() -> new OrganizationInternationalSettings(
                        ORGANIZATION_ID,
                        true,
                        true,
                        3,
                        NOW,
                        OrganizationInternationalSettings.APPROVED_WEEK_STARTS,
                        OrganizationInternationalSettings.APPROVED_IMPACT_RULES,
                        List.of(current)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lockVersion");

        assertThatThrownBy(() -> new InternationalSettingsVersion(
                        null,
                        "configured",
                        "IN",
                        "Asia/Kolkata",
                        "en-IN",
                        "en",
                        "INR",
                        "MONDAY",
                        NOW,
                        null,
                        "active",
                        null,
                        0,
                        NOW,
                        InternationalSettingsFormatPreview.create(
                                "en-IN", "Asia/Kolkata", "INR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("require an ID");
    }

    private static InternationalSettingsVersion version(
            UUID id,
            String lifecycle,
            UUID supersedesId,
            long lockVersion,
            String locale,
            String currencyCode) {
        var scheduled = "scheduled".equals(lifecycle);
        return new InternationalSettingsVersion(
                id,
                "configured",
                "IN",
                "Asia/Kolkata",
                locale,
                locale.substring(0, 2),
                currencyCode,
                "MONDAY",
                scheduled ? NOW.plusSeconds(86_400) : NOW.minusSeconds(86_400),
                scheduled ? null : NOW.plusSeconds(86_400),
                lifecycle,
                supersedesId,
                lockVersion,
                NOW,
                InternationalSettingsFormatPreview.create(
                        locale, "Asia/Kolkata", currencyCode));
    }
}
