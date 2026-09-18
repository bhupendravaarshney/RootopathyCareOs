package com.rootopathy.careos.administration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationProfileTest {
    @Test
    void recognizesTheExactApprovedProfileAsReadinessComplete() {
        var profile = profile(
                "Approved Care Network Private Limited",
                "Approved Care Network",
                "Approved Care",
                "care_network",
                "IN",
                "Asia/Kolkata",
                "en-IN",
                "under_review");

        assertThat(profile.readinessComplete()).isTrue();
        assertThat(profile.readinessGaps()).isEmpty();
    }

    @Test
    void keepsLegacyRowsBlockedUntilTypeAndLocaleAreGoverned() {
        var profile = profile(
                "Legacy Care Network",
                "Legacy Care",
                null,
                null,
                "IN",
                "Asia/Kolkata",
                null,
                "draft");

        assertThat(profile.readinessComplete()).isFalse();
        assertThat(profile.readinessGaps()).containsExactly("organizationType", "locale");
    }

    @Test
    void rejectsClosedOrMalformedProfileEvidence() {
        var profile = profile(
                "<Invalid Care>",
                "X",
                "Y",
                "hospital",
                "ZZ",
                "+05:30",
                "not_a_locale",
                "closed");

        assertThat(profile.readinessGaps())
                .containsExactly(
                        "legalName",
                        "displayName",
                        "tradingName",
                        "organizationType",
                        "countryCode",
                        "timezone",
                        "locale",
                        "lifecycleStatus");
    }

    @Test
    void permitsAnAbsentOptionalTradingName() {
        var profile = profile(
                "Approved Administrative Organization",
                "Approved Administration",
                null,
                "administrative",
                "GB",
                "Europe/London",
                "en-GB",
                "active");

        assertThat(profile.readinessComplete()).isTrue();
    }

    private static OrganizationProfile profile(
            String legalName,
            String displayName,
            String tradingName,
            String organizationType,
            String countryCode,
            String timezone,
            String locale,
            String lifecycleStatus) {
        return new OrganizationProfile(
                UUID.fromString("01900000-0000-7000-8000-000000000001"),
                legalName,
                displayName,
                tradingName,
                organizationType,
                countryCode,
                timezone,
                locale,
                lifecycleStatus,
                true,
                7,
                Instant.parse("2026-09-18T08:00:00Z"));
    }
}
