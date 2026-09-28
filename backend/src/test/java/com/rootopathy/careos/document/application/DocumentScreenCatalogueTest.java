package com.rootopathy.careos.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class DocumentScreenCatalogueTest {
    @Test
    void exposesTheExactElevenScreenSequence() {
        assertThat(DocumentScreenCatalogue.titles())
                .containsExactly(
                        "Document dashboard",
                        "Patient document list",
                        "Upload document",
                        "Classification and metadata",
                        "Scan status",
                        "Document viewer",
                        "Result inbox",
                        "Result detail",
                        "Acknowledge or escalate",
                        "Version history",
                        "Export or share intent");
        assertThat(DocumentScreenCatalogue.screen("P7-01").title())
                .isEqualTo("Document dashboard");
        assertThat(DocumentScreenCatalogue.screen("P7-11").title())
                .isEqualTo("Export or share intent");
    }

    @Test
    void projectsOnlyPermissionBackedMutations() {
        var projected = DocumentScreenCatalogue.projectedActions(
                DocumentScreenCatalogue.screen("P7-09"),
                Set.of("document.result.review"));
        assertThat(projected).extracting(action -> action.key())
                .containsExactly("acknowledge-result", "resolve-result");
    }

    @Test
    void rejectsUnknownScreensAndActions() {
        assertThatThrownBy(() -> DocumentScreenCatalogue.screen("P7-12"))
                .isInstanceOf(DocumentException.class);
        assertThatThrownBy(() -> DocumentScreenCatalogue.mutation("P7-09", "dismiss-result"))
                .isInstanceOf(DocumentException.class);
    }
}
