package com.rootopathy.careos.careplan.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CarePlanScreenCatalogueTest {
    @Test
    void exposesTheExactTwelveScreenSequence() {
        assertThat(CarePlanScreenCatalogue.titles())
                .containsExactly(
                        "Care plan dashboard",
                        "Create coordinated plan",
                        "Problems and priorities",
                        "Goals",
                        "Interventions",
                        "Modality coordination",
                        "Owners and tasks",
                        "Consent and preferences",
                        "Safety and interaction review",
                        "Clinician approval",
                        "Patient summary",
                        "Plan versions and amendments");
        assertThat(CarePlanScreenCatalogue.screen("P9-01").title())
                .isEqualTo("Care plan dashboard");
        assertThat(CarePlanScreenCatalogue.screen("P9-12").title())
                .isEqualTo("Plan versions and amendments");
    }

    @Test
    void projectsOnlyPermissionBackedMutations() {
        var projected = CarePlanScreenCatalogue.projectedActions(
                CarePlanScreenCatalogue.screen("P9-10"),
                Set.of("care-plan.submit", "care-plan.approve"));
        assertThat(projected).extracting(action -> action.key())
                .containsExactly("submit-plan", "approve-plan");
    }

    @Test
    void rejectsUnknownScreensAndActions() {
        assertThatThrownBy(() -> CarePlanScreenCatalogue.screen("P9-13"))
                .isInstanceOf(CarePlanException.class);
        assertThatThrownBy(() -> CarePlanScreenCatalogue.mutation("P9-10", "auto-approve"))
                .isInstanceOf(CarePlanException.class);
    }
}
