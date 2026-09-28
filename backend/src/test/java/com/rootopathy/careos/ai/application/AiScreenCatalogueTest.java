package com.rootopathy.careos.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AiScreenCatalogueTest {
    @Test
    void exposesTheExactTenScreenSequence() {
        assertThat(AiScreenCatalogue.titles())
                .containsExactly(
                        "AI session launcher",
                        "Purpose and consent check",
                        "Input selection",
                        "Transcription and extraction",
                        "Draft summary",
                        "Clinical suggestion panel",
                        "Safety and uncertainty flags",
                        "Source and provenance viewer",
                        "Clinician review and approval",
                        "AI session history");
        assertThat(AiScreenCatalogue.screen("P8-01").title())
                .isEqualTo("AI session launcher");
        assertThat(AiScreenCatalogue.screen("P8-10").title())
                .isEqualTo("AI session history");
    }

    @Test
    void projectsOnlyPermissionBackedMutations() {
        var projected = AiScreenCatalogue.projectedActions(
                AiScreenCatalogue.screen("P8-07"), Set.of("ai.safety.review"));
        assertThat(projected).extracting(action -> action.key())
                .containsExactly("acknowledge-safety-flag", "resolve-safety-flag");
    }

    @Test
    void rejectsUnknownScreensAndActions() {
        assertThatThrownBy(() -> AiScreenCatalogue.screen("P8-11"))
                .isInstanceOf(AiException.class);
        assertThatThrownBy(() -> AiScreenCatalogue.mutation("P8-09", "auto-approve"))
                .isInstanceOf(AiException.class);
    }
}
