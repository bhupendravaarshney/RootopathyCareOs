package com.rootopathy.careos.administration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdministrationReadinessTest {
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant EVALUATED_AT = Instant.parse("2026-09-17T08:00:00Z");

    @Test
    void acceptsOnlyTheExactOrderedApprovedCatalogue() {
        var readiness = readiness(gates(), EVALUATED_AT.plusSeconds(15 * 60L), 15);

        assertThat(readiness.catalogueVersion()).isEqualTo("m1-readiness-v1");
        assertThat(readiness.gates()).extracting(ReadinessGate::key)
                .containsExactlyElementsOf(AdministrationReadiness.CATALOGUE_KEYS);

        var reordered = new ArrayList<>(gates());
        var first = reordered.getFirst();
        reordered.set(0, reordered.get(1));
        reordered.set(1, first);
        assertThatThrownBy(() -> readiness(reordered, EVALUATED_AT.plusSeconds(15 * 60L), 15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("readiness gates do not match the approved catalogue");
    }

    @Test
    void rejectsAnyFreshnessWindowOtherThanFifteenMinutes() {
        assertThatThrownBy(() -> readiness(gates(), EVALUATED_AT.plusSeconds(14 * 60L), 15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("readiness evaluation freshness is invalid");
    }

    @Test
    void rejectsOutcomeCountsThatDoNotMatchTheServerGates() {
        assertThatThrownBy(() -> readiness(gates(), EVALUATED_AT.plusSeconds(15 * 60L), 14))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("readiness outcome counts are inconsistent");
    }

    private static AdministrationReadiness readiness(
            List<ReadinessGate> gates, Instant expiresAt, int blockedGates) {
        return new AdministrationReadiness(
                ORGANIZATION_ID,
                "draft",
                ReadinessGate.CATALOGUE_VERSION,
                4,
                EVALUATED_AT,
                expiresAt,
                0,
                blockedGates,
                0,
                0,
                15,
                2,
                1,
                1,
                gates);
    }

    private static List<ReadinessGate> gates() {
        return AdministrationReadiness.CATALOGUE_KEYS.stream()
                .map(key -> new ReadinessGate(
                        key,
                        ReadinessGate.CATALOGUE_VERSION,
                        key,
                        "blocked",
                        "m1.readiness.test",
                        "m1.remediation.test",
                        "Synthetic domain-test gate.",
                        List.of(),
                        "#/M1-05"))
                .toList();
    }
}
