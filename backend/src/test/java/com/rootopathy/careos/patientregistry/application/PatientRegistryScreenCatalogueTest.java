package com.rootopathy.careos.patientregistry.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PatientRegistryScreenCatalogueTest {
    @Test
    void exposesEveryApprovedScreenWithTheExactRuntimeActionBoundary() {
        var expectedActions = Map.ofEntries(
                Map.entry("P3-01", Set.of("start-registration", "review-duplicates")),
                Map.entry("P3-02", Set.of("start-registration")),
                Map.entry("P3-03", Set.of("start-registration")),
                Map.entry(
                        "P3-04",
                        Set.of("search-duplicates", "record-registration-disposition")),
                Map.entry("P3-05", Set.of("correct-identity")),
                Map.entry("P3-06", Set.of("add-contact", "add-address")),
                Map.entry("P3-07", Set.of("set-communication-preference")),
                Map.entry("P3-08", Set.of()),
                Map.entry("P3-09", Set.of("add-caregiver-relationship")),
                Map.entry("P3-10", Set.of()),
                Map.entry("P3-11", Set.of()),
                Map.entry(
                        "P3-12", Set.of("validate-registration", "submit-registration")),
                Map.entry("P3-13", Set.of("change-patient-lifecycle")),
                Map.entry("P3-14", Set.of("claim-duplicate", "disposition-duplicate")),
                Map.entry(
                        "P3-15",
                        Set.of(
                                "request-patient-merge",
                                "decide-patient-merge",
                                "execute-patient-merge")),
                Map.entry("P3-16", Set.of()));

        var screenIds = IntStream.rangeClosed(1, 16)
                .mapToObj(index -> "P3-%02d".formatted(index))
                .toList();

        assertThat(screenIds).allSatisfy(screenId -> {
            var screen = PatientRegistryScreenCatalogue.screen(screenId);
            assertThat(screen.id()).isEqualTo(screenId);
            assertThat(screen.title()).isNotBlank();
            assertThat(screen.purpose()).isNotBlank();
            assertThat(screen.readOperation()).startsWith("patient.");
            assertThat(screen.actions().stream()
                            .map(PatientRegistryScreenCatalogue.ActionSpec::key)
                            .collect(Collectors.toSet()))
                    .isEqualTo(expectedActions.get(screenId));
        });
    }

    @Test
    void keepsPolicyDependentCapabilitiesFailClosedAndCapturesRegistrationProvenance() {
        assertThat(PatientRegistryScreenCatalogue.screen("P3-08").actions()).isEmpty();
        assertThat(PatientRegistryScreenCatalogue.screen("P3-10").actions()).isEmpty();
        assertThat(PatientRegistryScreenCatalogue.screen("P3-11").actions()).isEmpty();

        var proxyActions = PatientRegistryScreenCatalogue.screen("P3-09").actions();
        assertThat(proxyActions)
                .extracting(PatientRegistryScreenCatalogue.ActionSpec::key)
                .containsExactly("add-caregiver-relationship");
        assertThat(proxyActions)
                .extracting(PatientRegistryScreenCatalogue.ActionSpec::operation)
                .doesNotContain(
                        "patient.proxy.request",
                        "patient.proxy.decide",
                        "patient.portal_link.request");

        var start = PatientRegistryScreenCatalogue.mutation("P3-03", "start-registration");
        assertThat(start.fields())
                .extracting(field -> field.key())
                .contains(
                        "registrationSource",
                        "supplierRelationshipKey",
                        "purposeKey",
                        "facilityId",
                        "urgent",
                        "urgentReasonCode");
    }
}
