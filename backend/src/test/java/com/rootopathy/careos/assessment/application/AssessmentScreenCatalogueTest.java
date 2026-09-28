package com.rootopathy.careos.assessment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AssessmentScreenCatalogueTest {
    @Test
    void preservesAllTwentySevenCosStepsAndCoherentGovernedActions() {
        var screens = IntStream.rangeClosed(1, 27)
                .mapToObj(number -> AssessmentScreenCatalogue.screen("COS-%02d".formatted(number)))
                .toList();

        assertThat(screens)
                .hasSize(27)
                .extracting(AssessmentScreenCatalogue.ScreenSpec::id)
                .doesNotHaveDuplicates();
        assertThat(screens)
                .extracting(AssessmentScreenCatalogue.ScreenSpec::title)
                .containsExactlyElementsOf(AssessmentScreenCatalogue.titles());

        for (var screen : screens) {
            assertThat(screen.id()).matches("COS-(0[1-9]|1[0-9]|2[0-7])");
            assertThat(screen.title()).isNotBlank();
            assertThat(screen.purpose()).isNotBlank();
            assertThat(screen.readOperation()).isEqualTo("assessment.read");
            assertThat(screen.actions())
                    .extracting(AssessmentScreenCatalogue.ActionSpec::key)
                    .doesNotHaveDuplicates();
            for (var action : screen.actions()) {
                assertThat(action.key()).matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
                assertThat(action.label()).isNotBlank();
                assertThat(action.style()).isEqualTo("primary");
                assertThat(action.operation()).startsWith("assessment.");
                assertThat(action.permission()).isEqualTo(action.operation());
                assertThat(!action.ifMatchRequired() || action.targetRequired()).isTrue();
                assertThat(action.fields())
                        .extracting(com.rootopathy.careos.assessment.domain.AssessmentScreen.Field::key)
                        .doesNotHaveDuplicates();
                assertThat(action.fields())
                        .allMatch(field -> Set.of("checkbox", "select", "text", "textarea", "uuid")
                                .contains(field.inputType()));
            }
        }
    }

    @Test
    void resolvesOnlyRegisteredMutationsAndProjectsOnlyPermittedCapabilities() {
        var permissions = new HashSet<String>();
        for (var number = 1; number <= 27; number++) {
            var screen = AssessmentScreenCatalogue.screen("COS-%02d".formatted(number));
            permissions.addAll(screen.actions().stream()
                    .map(AssessmentScreenCatalogue.ActionSpec::permission)
                    .toList());
            for (var action : screen.actions()) {
                assertThat(AssessmentScreenCatalogue.mutation(screen.id(), action.key()))
                        .isSameAs(action);
            }
            assertThat(AssessmentScreenCatalogue.projectedActions(screen, permissions))
                    .hasSameSizeAs(screen.actions());
        }
    }

    @Test
    void keepsAiSynthesisUnavailableAndRejectsInventedContracts() {
        assertThat(AssessmentScreenCatalogue.screen("COS-24").actions()).isEmpty();
        assertThatThrownBy(() -> AssessmentScreenCatalogue.screen("COS-28"))
                .isInstanceOf(AssessmentException.class);
        assertThatThrownBy(() -> AssessmentScreenCatalogue.mutation("COS-01", "invented-action"))
                .isInstanceOf(AssessmentException.class);
    }
}
