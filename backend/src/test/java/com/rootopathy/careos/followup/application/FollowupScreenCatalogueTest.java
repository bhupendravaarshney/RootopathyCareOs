package com.rootopathy.careos.followup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class FollowupScreenCatalogueTest {
    @Test
    void exposesTheExactNineScreenSequence() {
        assertThat(FollowupScreenCatalogue.titles())
                .containsExactly(
                        "Monitoring dashboard",
                        "Rules",
                        "Domains",
                        "Measures",
                        "Escalation",
                        "Follow-up schedule",
                        "Interpretation",
                        "Confirm plan",
                        "Outcome timeline");
        assertThat(FollowupScreenCatalogue.screen("P10-01").title())
                .isEqualTo("Monitoring dashboard");
        assertThat(FollowupScreenCatalogue.screen("P10-09").title())
                .isEqualTo("Outcome timeline");
    }

    @Test
    void projectsOnlyPermissionBackedMutations() {
        var projected = FollowupScreenCatalogue.projectedActions(
                FollowupScreenCatalogue.screen("P10-05"),
                Set.of("followup.escalation.acknowledge"));
        assertThat(projected).extracting(action -> action.key())
                .containsExactly("acknowledge-escalation");
    }

    @Test
    void rejectsUnknownScreensAndActions() {
        assertThatThrownBy(() -> FollowupScreenCatalogue.screen("P10-10"))
                .isInstanceOf(FollowupException.class);
        assertThatThrownBy(() -> FollowupScreenCatalogue.mutation("P10-08", "auto-confirm"))
                .isInstanceOf(FollowupException.class);
    }
}
