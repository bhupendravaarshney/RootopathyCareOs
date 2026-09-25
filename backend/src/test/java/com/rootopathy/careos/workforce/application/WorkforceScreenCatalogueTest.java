package com.rootopathy.careos.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WorkforceScreenCatalogueTest {
    private static final Set<String> SPECIAL_ENDPOINT_ACTIONS = Set.of(
            "upload-document",
            "access-credential-document",
            "access-evidence",
            "access-export");

    @Test
    void registersEveryApprovedScreenWithCoherentGovernedActions() {
        var screens = IntStream.rangeClosed(1, 29)
                .mapToObj(number -> WorkforceScreenCatalogue.screen("M2-%02d".formatted(number)))
                .toList();

        assertThat(screens)
                .hasSize(29)
                .extracting(WorkforceScreenCatalogue.ScreenSpec::id)
                .doesNotHaveDuplicates();
        assertThat(screens.stream().mapToInt(screen -> screen.actions().size()).sum()).isEqualTo(89);

        for (var screen : screens) {
            assertThat(screen.id()).matches("M2-(0[1-9]|1[0-9]|2[0-9])");
            assertThat(screen.title()).isNotBlank();
            assertThat(screen.purpose()).isNotBlank();
            assertThat(screen.readOperation()).matches("[a-z][a-z0-9_]*(?:[.:-][a-z0-9_]+)*");
            assertThat(screen.actions())
                    .extracting(WorkforceScreenCatalogue.ActionSpec::key)
                    .doesNotHaveDuplicates();

            for (var action : screen.actions()) {
                assertThat(action.key()).matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
                assertThat(action.label()).isNotBlank();
                assertThat(action.fields())
                        .extracting(com.rootopathy.careos.workforce.domain.WorkforceScreen.Field::key)
                        .doesNotHaveDuplicates();
                assertThat(!action.ifMatchRequired() || action.targetRequired()).isTrue();

                if (action.href() != null) {
                    assertThat(action.style()).isEqualTo("link");
                    assertThat(action.href()).matches("#/(?:M1-(?:0[1-9]|1[0-9]|2[0-3])|M2-(?:0[1-9]|1[0-9]|2[0-9]))");
                    assertThat(action.operation()).isEmpty();
                    assertThat(action.permission()).isEmpty();
                    assertThat(action.fields()).isEmpty();
                    assertThat(action.targetRequired()).isFalse();
                    assertThat(action.ifMatchRequired()).isFalse();
                    assertThat(action.reasonRequired()).isFalse();
                } else {
                    assertThat(action.style()).isEqualTo("primary");
                    assertThat(action.operation()).isNotBlank();
                    assertThat(action.permission()).isNotBlank();
                    for (var field : action.fields()) {
                        assertThat(field.key()).matches("[A-Za-z][A-Za-z0-9]{0,79}");
                        assertThat(field.inputType())
                                .isIn("date", "datetime-local", "hidden", "select", "text", "uuid");
                        assertThat(field.options())
                                .extracting(com.rootopathy.careos.workforce.domain.WorkforceScreen.Option::value)
                                .doesNotHaveDuplicates();
                        if (field.inputType().equals("select")) {
                            assertThat(field.options()).isNotEmpty();
                        } else {
                            assertThat(field.options()).isEmpty();
                        }
                    }
                }
            }
        }
    }

    @Test
    void resolvesOnlyGenericMutationsAndProjectsOnlyExactPermissions() {
        var allPermissions = new HashSet<String>();
        for (var number = 1; number <= 29; number++) {
            var screen = WorkforceScreenCatalogue.screen("M2-%02d".formatted(number));
            allPermissions.addAll(screen.actions().stream()
                    .filter(action -> action.href() == null)
                    .map(WorkforceScreenCatalogue.ActionSpec::permission)
                    .toList());

            var links = WorkforceScreenCatalogue.projectedActions(screen, Set.of());
            assertThat(links).allMatch(action -> action.style().equals("link"));

            for (var action : screen.actions()) {
                if (action.href() != null || SPECIAL_ENDPOINT_ACTIONS.contains(action.key())) {
                    assertThatThrownBy(() ->
                                    WorkforceScreenCatalogue.mutation(screen.id(), action.key()))
                            .isInstanceOf(WorkforceException.class);
                } else {
                    assertThat(WorkforceScreenCatalogue.mutation(screen.id(), action.key()))
                            .isSameAs(action);
                }
            }
        }

        for (var number = 1; number <= 29; number++) {
            var screen = WorkforceScreenCatalogue.screen("M2-%02d".formatted(number));
            assertThat(WorkforceScreenCatalogue.projectedActions(screen, allPermissions))
                    .hasSameSizeAs(screen.actions());
        }
    }

    @Test
    void rejectsUnregisteredScreensAndActions() {
        assertThatThrownBy(() -> WorkforceScreenCatalogue.screen("M2-30"))
                .isInstanceOf(WorkforceException.class);
        assertThatThrownBy(() -> WorkforceScreenCatalogue.mutation("M2-01", "invented-action"))
                .isInstanceOf(WorkforceException.class);
    }
}
