package com.rootopathy.careos.administration.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AdministrationReadiness(
        UUID organizationId,
        String lifecycleStatus,
        String catalogueVersion,
        long organizationRevision,
        Instant evaluatedAt,
        Instant expiresAt,
        int completedGates,
        int blockedGates,
        int warningGates,
        int notApplicableGates,
        int totalGates,
        int activeMemberships,
        int facilityCount,
        int draftFacilityCount,
        List<ReadinessGate> gates) {
    public static final List<String> CATALOGUE_KEYS = List.of(
            "organization.profile.complete",
            "organization.identifier.primary_verified",
            "organization.contact.coverage",
            "organization.governance.coverage",
            "access.final_owner",
            "access.mfa_enforced",
            "network.facility.minimum",
            "network.hierarchy.valid",
            "network.hours.valid",
            "service.catalogue.active",
            "service.assignment.valid",
            "identifier.scheme.active",
            "configuration.integrity",
            "governance.registry.active",
            "platform.dependencies.ready");

    public AdministrationReadiness {
        Objects.requireNonNull(organizationId, "organizationId");
        lifecycleStatus = Objects.requireNonNull(lifecycleStatus, "lifecycleStatus");
        var approvedCatalogueVersion =
                Objects.requireNonNull(catalogueVersion, "catalogueVersion");
        catalogueVersion = approvedCatalogueVersion;
        evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!ReadinessGate.CATALOGUE_VERSION.equals(catalogueVersion)) {
            throw new IllegalArgumentException("readiness catalogue version is not approved");
        }
        if (organizationRevision < 0 || !expiresAt.equals(evaluatedAt.plusSeconds(15 * 60L))) {
            throw new IllegalArgumentException("readiness evaluation freshness is invalid");
        }
        if (completedGates < 0
                || blockedGates < 0
                || warningGates < 0
                || notApplicableGates < 0
                || totalGates < 1
                || completedGates > totalGates
                || activeMemberships < 0
                || facilityCount < 0
                || draftFacilityCount < 0
                || draftFacilityCount > facilityCount) {
            throw new IllegalArgumentException("readiness counts are inconsistent");
        }
        gates = List.copyOf(Objects.requireNonNull(gates, "gates"));
        if (gates.size() != totalGates) {
            throw new IllegalArgumentException("gate count does not match totalGates");
        }
        if (!gates.stream().map(ReadinessGate::key).toList().equals(CATALOGUE_KEYS)
                || gates.stream().anyMatch(
                        gate -> !approvedCatalogueVersion.equals(gate.version()))) {
            throw new IllegalArgumentException("readiness gates do not match the approved catalogue");
        }
        var actualCompleted = count(gates, "complete");
        var actualBlocked = count(gates, "blocked");
        var actualWarnings = count(gates, "warning");
        var actualNotApplicable = count(gates, "not_applicable");
        if (completedGates != actualCompleted
                || blockedGates != actualBlocked
                || warningGates != actualWarnings
                || notApplicableGates != actualNotApplicable
                || completedGates + blockedGates + warningGates + notApplicableGates
                        != totalGates) {
            throw new IllegalArgumentException("readiness outcome counts are inconsistent");
        }
    }

    private static int count(List<ReadinessGate> gates, String outcome) {
        return (int) gates.stream().filter(gate -> outcome.equals(gate.outcome())).count();
    }
}
