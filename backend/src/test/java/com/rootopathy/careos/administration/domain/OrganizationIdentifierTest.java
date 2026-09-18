package com.rootopathy.careos.administration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationIdentifierTest {
    private static final UUID IDENTIFIER_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000801");

    @Test
    void acceptsTheExactDraftProjectionAndDefensivelyCopiesActions() {
        var actions = new ArrayList<>(List.of("edit", "verify"));

        var identifier = identifier("draft", "unverified", actions, null, null);
        actions.clear();

        assertThat(identifier.availableActions()).containsExactly("edit", "verify");
        assertThat(identifier.issueDate()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(identifier.effectiveTo()).isNull();
    }

    @Test
    void rejectsContradictoryLifecycleAndProjectedActions() {
        assertThatThrownBy(() -> identifier(
                        "verified",
                        "unverified",
                        List.of("edit"),
                        "NPR-CASE-42",
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent");

        assertThatThrownBy(() -> identifier(
                        "verified",
                        "verified",
                        List.of("verify"),
                        "NPR-CASE-42",
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lifecycle");

        assertThatThrownBy(() -> identifier(
                        "superseded",
                        "verified",
                        List.of("supersede"),
                        "NPR-CASE-42",
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lifecycle");

        assertThat(identifier(
                                "verified",
                                "verified",
                                List.of("revoke", "supersede"),
                                "NPR-CASE-42",
                                null)
                        .availableActions())
                .containsExactly("revoke", "supersede");
    }

    @Test
    void rejectsInvalidRangesAndCollectionTypeDrift() {
        assertThatThrownBy(() -> identifier(
                        "draft",
                        "unverified",
                        List.of(),
                        null,
                        Instant.parse("2026-09-18T07:59:59Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveTo");

        var identifier = identifier("draft", "unverified", List.of(), null, null);
        assertThatThrownBy(() -> new OrganizationIdentifierCollection(
                        UUID.fromString("01900000-0000-7000-8000-000000000001"),
                        true,
                        List.of(new OrganizationIdentifierType(
                                "tax", "Tax registration", null, false)),
                        List.of(identifier)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("applicable type");
    }

    private static OrganizationIdentifier identifier(
            String status,
            String verificationStatus,
            List<String> actions,
            String evidenceReference,
            Instant effectiveTo) {
        return new OrganizationIdentifier(
                IDENTIFIER_ID,
                "registration",
                "Registration identifier",
                "National Provider Registry",
                "REG-IN-0042",
                "IN",
                verificationStatus,
                evidenceReference,
                true,
                LocalDate.parse("2026-09-01"),
                null,
                Instant.parse("2026-09-18T08:00:00Z"),
                effectiveTo,
                null,
                status,
                actions,
                0,
                Instant.parse("2026-09-18T07:00:00Z"),
                Instant.parse("2026-09-18T07:00:00Z"));
    }
}
