package com.rootopathy.careos.administration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rootopathy.careos.governance.application.GovernanceEvidenceOperations;
import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.application.IdempotencyOperations;
import com.rootopathy.careos.governance.domain.GovernanceEvidenceIds;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EvidenceExportServiceTest {
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000201");
    private static final UUID ACTOR_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000202");
    private static final UUID EXPORT_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000203");
    private static final String ARTIFACT_REFERENCE =
            "01990000-0000-7000-8000-000000000204";
    private static final String ARTIFACT_DIGEST = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
    private static final AuthorizedTenantContext CONTEXT = new AuthorizedTenantContext(
            ORGANIZATION_ID, ACTOR_ID, "evidence-export", "export-access-test-001");

    @Test
    void storesOnlyOpaqueAccessMaterialAndMintsAFreshUrlForEveryReplay() {
        var storedResponse = new AtomicReference<IdempotentResponse>();
        IdempotencyOperations idempotency = (context, command, firstExecution) -> {
            var existing = storedResponse.get();
            if (existing != null) return new IdempotencyOutcome(existing, true);
            var response = firstExecution.get();
            storedResponse.set(response);
            return new IdempotencyOutcome(response, false);
        };
        var evidence = mock(GovernanceEvidenceOperations.class);
        when(evidence.record(any(), any()))
                .thenReturn(new GovernanceEvidenceIds(UUID.randomUUID(), null));
        var authorization = immediateAuthorization();
        var mutations = new GovernedMutationExecutor(authorization, idempotency, evidence);
        var store = mock(EvidenceExportStore.class);
        when(store.access(any(), eq(EXPORT_ID), eq(4L), eq("security_investigation")))
                .thenReturn(new EvidenceExportStore.Access(
                        EXPORT_ID,
                        ARTIFACT_REFERENCE,
                        ARTIFACT_DIGEST,
                        "text/csv",
                        "careos-audit.csv",
                        NOW.plus(Duration.ofHours(1)),
                        4));
        var artifacts = mock(EvidenceExportArtifactStore.class);
        when(artifacts.createReadGrant(
                        any(),
                        eq(EXPORT_ID),
                        eq(ARTIFACT_REFERENCE),
                        eq(ARTIFACT_DIGEST),
                        eq("careos-audit.csv"),
                        eq(Duration.ofMinutes(10))))
                .thenReturn(
                        new EvidenceExportArtifactStore.AccessGrant(
                                URI.create("https://download.example/first"),
                                NOW.plus(Duration.ofMinutes(10))),
                        new EvidenceExportArtifactStore.AccessGrant(
                                URI.create("https://download.example/replay"),
                                NOW.plus(Duration.ofMinutes(10))));
        var service = new EvidenceExportService(
                authorization,
                mutations,
                store,
                artifacts,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var command = new EvidenceExportService.Access(
                ORGANIZATION_ID,
                ACTOR_ID,
                "export-access-test-001",
                "export-access-key-0001",
                "\"evidence-export:" + EXPORT_ID + ":4\"",
                EXPORT_ID,
                "security_investigation",
                "Investigate the approved security incident.",
                NOW,
                NOW);

        var first = service.access(command);
        var replay = service.access(command);

        assertThat(storedResponse.get().bodyJson())
                .contains(ARTIFACT_REFERENCE)
                .doesNotContain("downloadUrl", "https://");
        assertThat(first.response().bodyJson()).contains("https://download.example/first");
        assertThat(first.replayed()).isFalse();
        assertThat(replay.response().bodyJson()).contains("https://download.example/replay");
        assertThat(replay.replayed()).isTrue();
        verify(store, times(1)).access(any(), eq(EXPORT_ID), eq(4L), eq("security_investigation"));
        verify(artifacts, times(2)).createReadGrant(
                any(),
                eq(EXPORT_ID),
                eq(ARTIFACT_REFERENCE),
                eq(ARTIFACT_DIGEST),
                eq("careos-audit.csv"),
                eq(Duration.ofMinutes(10)));
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
}
