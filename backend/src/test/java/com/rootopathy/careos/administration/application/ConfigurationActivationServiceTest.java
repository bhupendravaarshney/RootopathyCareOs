package com.rootopathy.careos.administration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rootopathy.careos.administration.domain.AdministrationReadiness;
import com.rootopathy.careos.administration.domain.ConfigurationActivationDirectory;
import com.rootopathy.careos.administration.domain.ReadinessGate;
import com.rootopathy.careos.governance.application.GovernanceEvidenceOperations;
import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.application.IdempotencyOperations;
import com.rootopathy.careos.governance.domain.GovernanceEvidenceIds;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class ConfigurationActivationServiceTest {
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000001");
    private static final UUID ACTOR_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000002");
    private static final UUID CONFIGURATION_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000003");
    private static final UUID RESULT_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000004");
    private static final UUID FACILITY_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000005");
    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");
    private static final AuthorizedTenantContext CONTEXT = new AuthorizedTenantContext(
            ORGANIZATION_ID, ACTOR_ID, "configuration-activation", "configuration-test-001");

    @Test
    void initialCandidateSuppliesItsOwnConfigurationIntegrityAnchor() {
        var authorization = immediateAuthorization();
        IdempotencyOperations idempotency = (context, command, firstExecution) ->
                new IdempotencyOutcome(firstExecution.get(), false);
        GovernanceEvidenceOperations evidence = (context, value) ->
                new GovernanceEvidenceIds(UUID.randomUUID(), UUID.randomUUID());
        var mutations = new GovernedMutationExecutor(authorization, idempotency, evidence);
        var store = mock(ConfigurationActivationStore.class);
        var administration = mock(OrganizationAdministrationStore.class);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var service = new ConfigurationActivationService(
                authorization, mutations, store, administration, new ObjectMapper(), clock);

        when(store.baseline(any(), any(), anyLong()))
                .thenReturn(new ConfigurationActivationStore.Baseline(
                        CONFIGURATION_ID, true, null, null, 7, "canonical-state"));
        when(administration.readiness(any())).thenReturn(readinessWithIntegrityBlocker());
        when(store.candidateSatisfiedGates(any(), any()))
                .thenReturn(Set.of("configuration.integrity"));

        var projected = ArgumentCaptor.forClass(AdministrationReadiness.class);
        when(store.validate(any(), any(), projected.capture(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var digest = invocation.getArgument(4, String.class);
                    return new ConfigurationActivationStore.ValidationResult(
                            CONFIGURATION_ID,
                            RESULT_ID,
                            digest,
                            0,
                            0,
                            NOW.plusSeconds(900),
                            1,
                            directory());
                });

        var outcome = service.validate(new ConfigurationActivationService.Validate(
                ORGANIZATION_ID,
                ACTOR_ID,
                "configuration-test-001",
                "configuration-test-key-0001",
                null,
                null,
                "Initial configuration activation",
                "Validate the exact initial configuration candidate.",
                NOW.plusSeconds(60),
                List.of(new ConfigurationActivationService.ChangeItem(
                        "facility", FACILITY_ID, 0, "activated"))));

        assertThat(outcome.response().statusCode()).isEqualTo(201);
        assertThat(projected.getValue().blockedGates()).isZero();
        assertThat(projected.getValue().gates())
                .filteredOn(gate -> gate.key().equals("configuration.integrity"))
                .singleElement()
                .satisfies(gate -> {
                    assertThat(gate.outcome()).isEqualTo("complete");
                    assertThat(gate.reasonCode()).isEqualTo("m1.readiness.candidate_satisfied");
                });
        verify(store).validate(any(), any(), any(), anyString(), anyString());
    }

    private static TenantAuthorizationOperations immediateAuthorization() {
        return new TenantAuthorizationOperations() {
            @Override
            public <T> T execute(
                    TenantAuthorizationRequest request,
                    Function<AuthorizedTenantContext, T> authorizedWork) {
                return authorizedWork.apply(CONTEXT);
            }
        };
    }

    private static AdministrationReadiness readinessWithIntegrityBlocker() {
        var gates = new ArrayList<ReadinessGate>();
        for (var key : AdministrationReadiness.CATALOGUE_KEYS) {
            var blocked = key.equals("configuration.integrity");
            gates.add(new ReadinessGate(
                    key,
                    ReadinessGate.CATALOGUE_VERSION,
                    key,
                    blocked ? "blocked" : "complete",
                    blocked ? "m1.test.blocked" : "m1.test.complete",
                    "m1.remediation.none",
                    "Deterministic readiness fixture.",
                    List.of("fixture:" + key),
                    "#/M1-21"));
        }
        return new AdministrationReadiness(
                ORGANIZATION_ID,
                "draft",
                ReadinessGate.CATALOGUE_VERSION,
                7,
                NOW,
                NOW.plusSeconds(900),
                14,
                1,
                0,
                0,
                15,
                1,
                1,
                0,
                gates);
    }

    private static ConfigurationActivationDirectory directory() {
        return new ConfigurationActivationDirectory(
                ORGANIZATION_ID, true, true, false, false, List.of(), List.of(), NOW);
    }
}
