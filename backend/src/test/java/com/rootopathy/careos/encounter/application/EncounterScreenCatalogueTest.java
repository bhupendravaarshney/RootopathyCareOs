package com.rootopathy.careos.encounter.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class EncounterScreenCatalogueTest {
    @Test
    void registersEveryApprovedEncounterScreenWithCoherentGovernedActions() {
        var screens = IntStream.rangeClosed(1, 12)
                .mapToObj(number -> EncounterScreenCatalogue.screen("P5-%02d".formatted(number)))
                .toList();

        assertThat(screens)
                .hasSize(12)
                .extracting(EncounterScreenCatalogue.ScreenSpec::id)
                .doesNotHaveDuplicates();

        for (var screen : screens) {
            assertThat(screen.id()).matches("P5-(0[1-9]|1[0-2])");
            assertThat(screen.title()).isNotBlank();
            assertThat(screen.purpose()).isNotBlank();
            assertThat(screen.readOperation()).startsWith("encounter.");
            assertThat(screen.actions())
                    .extracting(EncounterScreenCatalogue.ActionSpec::key)
                    .doesNotHaveDuplicates();

            for (var action : screen.actions()) {
                assertThat(action.key()).matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
                assertThat(action.label()).isNotBlank();
                assertThat(action.fields())
                        .extracting(com.rootopathy.careos.encounter.domain.EncounterScreen.Field::key)
                        .doesNotHaveDuplicates();
                assertThat(!action.ifMatchRequired() || action.targetRequired()).isTrue();
                if (action.href() != null) {
                    assertThat(action.style()).isEqualTo("link");
                    assertThat(action.href()).matches("#/P5-(0[1-9]|1[0-2])");
                    assertThat(action.operation()).isEmpty();
                    assertThat(action.permission()).isEmpty();
                    assertThat(action.fields()).isEmpty();
                } else {
                    assertThat(action.style()).isEqualTo("primary");
                    assertThat(action.operation()).isNotBlank();
                    assertThat(action.permission()).isNotBlank();
                    assertThat(action.fields())
                            .allMatch(field -> Set.of(
                                            "checkbox",
                                            "datetime-local",
                                            "select",
                                            "text",
                                            "textarea",
                                            "uuid")
                                    .contains(field.inputType()));
                }
            }
        }
    }

    @Test
    void resolvesOnlyMutationsAndProjectsOnlyPermittedCapabilities() {
        var permissions = new HashSet<String>();
        for (var number = 1; number <= 12; number++) {
            var screen = EncounterScreenCatalogue.screen("P5-%02d".formatted(number));
            permissions.addAll(screen.actions().stream()
                    .filter(action -> action.href() == null)
                    .map(EncounterScreenCatalogue.ActionSpec::permission)
                    .toList());
            for (var action : screen.actions()) {
                if (action.href() == null) {
                    assertThat(EncounterScreenCatalogue.mutation(screen.id(), action.key()))
                            .isSameAs(action);
                } else {
                    assertThatThrownBy(() -> EncounterScreenCatalogue.mutation(screen.id(), action.key()))
                            .isInstanceOf(EncounterException.class);
                }
            }
            assertThat(EncounterScreenCatalogue.projectedActions(screen, permissions))
                    .hasSameSizeAs(screen.actions());
        }
    }

    @Test
    void rejectsUnregisteredScreensAndActions() {
        assertThatThrownBy(() -> EncounterScreenCatalogue.screen("P5-13"))
                .isInstanceOf(EncounterException.class);
        assertThatThrownBy(() -> EncounterScreenCatalogue.mutation("P5-01", "invented-action"))
                .isInstanceOf(EncounterException.class);
    }
}
