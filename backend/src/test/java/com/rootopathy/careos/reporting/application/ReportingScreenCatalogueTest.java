package com.rootopathy.careos.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ReportingScreenCatalogueTest {
    @Test
    void exposesTheExactTenScreenRegister() {
        assertThat(ReportingScreenCatalogue.titles())
                .containsExactly(
                        "Reporting dashboard",
                        "Operational reports",
                        "Clinical safety reports",
                        "Outcome reports",
                        "Workforce governance",
                        "Access and security reports",
                        "AI governance",
                        "Financial reports",
                        "Scheduled exports",
                        "Report audit and history");
        assertThat(ReportingScreenCatalogue.screen("P12-10").title())
                .isEqualTo("Report audit and history");
    }

    @Test
    void filtersSensitiveActionsByExactPermission() {
        var screen = ReportingScreenCatalogue.screen("P12-09");
        assertThat(ReportingScreenCatalogue.projectedActions(
                        screen, Set.of("reporting.export.create")))
                .extracting(action -> action.key())
                .containsExactly("request-report-export");
    }

    @Test
    void rejectsUnknownScreensAndActions() {
        assertThatThrownBy(() -> ReportingScreenCatalogue.screen("P12-11"))
                .isInstanceOf(ReportingException.class);
        assertThatThrownBy(() -> ReportingScreenCatalogue.mutation("P12-09", "raw-query"))
                .isInstanceOf(ReportingException.class);
    }
}
