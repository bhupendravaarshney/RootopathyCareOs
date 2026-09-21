package com.rootopathy.careos.administration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rootopathy.careos.governance.application.ConsumerInboxOperations;
import com.rootopathy.careos.governance.application.GovernanceEvidenceOperations;
import com.rootopathy.careos.governance.domain.GovernanceEvidenceIds;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.ServiceIdentityAuthorizationRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class EvidenceExportWorkerServiceTest {
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000101");
    private static final UUID ACTOR_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000102");
    private static final UUID EXPORT_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000103");
    private static final UUID REQUESTER_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000104");
    private static final String ARTIFACT_REFERENCE =
            "01990000-0000-7000-8000-000000000105";
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
    private static final AuthorizedTenantContext CONTEXT = new AuthorizedTenantContext(
            ORGANIZATION_ID, ACTOR_ID, "m1-export-worker-v1", "export-worker-test-001");

    private EvidenceExportStore store;
    private EvidenceExportArtifactStore artifacts;
    private GovernanceEvidenceOperations evidence;
    private EvidenceExportWorkerService service;

    @BeforeEach
    void setUp() {
        store = mock(EvidenceExportStore.class);
        artifacts = mock(EvidenceExportArtifactStore.class);
        evidence = mock(GovernanceEvidenceOperations.class);
        service = new EvidenceExportWorkerService(
                immediateAuthorization(),
                store,
                artifacts,
                evidence,
                mock(ConsumerInboxOperations.class),
                new ObjectMapper());
        when(store.claim(any(), eq(EXPORT_ID), eq("worker-01"))).thenReturn(runningWork());
        when(evidence.record(any(), any()))
                .thenReturn(new GovernanceEvidenceIds(UUID.randomUUID(), null));
    }

    @Test
    void neutralizesSpreadsheetFormulaCellsAndCompletesTheDigestBoundArtifact() {
        when(store.rows(any(), any(), anyInt())).thenReturn(List.of(Map.of(
                "displayName", "  =SUM(1,1)\nInjected",
                "status", "active")));
        var bytes = ArgumentCaptor.forClass(byte[].class);
        when(artifacts.store(
                        any(), eq(EXPORT_ID), eq("text/csv"), anyString(), bytes.capture(), anyString()))
                .thenAnswer(invocation -> new EvidenceExportArtifactStore.StoredArtifact(
                        ARTIFACT_REFERENCE,
                        invocation.getArgument(5, String.class),
                        (long) invocation.getArgument(4, byte[].class).length));
        when(store.ready(
                        any(), any(), eq(ARTIFACT_REFERENCE), anyString(), eq("text/csv"),
                        anyString(), eq(1), anyLong()))
                .thenReturn(readyWork());

        var result = service.generate(command());

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(new String(bytes.getValue(), StandardCharsets.UTF_8))
                .contains("\"'  =SUM(1,1) Injected\"");
    }

    @Test
    void removesTheArtifactAndRollsBackInsteadOfApplyingAStaleFailureAfterReadyEvidenceFails() {
        when(store.rows(any(), any(), anyInt())).thenReturn(List.of(Map.of("status", "active")));
        when(artifacts.store(any(), eq(EXPORT_ID), anyString(), anyString(), any(), anyString()))
                .thenReturn(new EvidenceExportArtifactStore.StoredArtifact(
                        ARTIFACT_REFERENCE, "a".repeat(64), 24));
        when(store.ready(any(), any(), eq(ARTIFACT_REFERENCE), anyString(), anyString(),
                        anyString(), eq(1), anyLong()))
                .thenReturn(readyWork());
        when(evidence.record(any(), any())).thenThrow(new IllegalStateException("audit unavailable"));

        assertThatThrownBy(() -> service.generate(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");

        verify(artifacts).delete(any(), eq(EXPORT_ID), eq(ARTIFACT_REFERENCE), anyString());
        verify(store, never()).generationFailed(any(), any(), anyString(), any(Boolean.class));
    }

    private static ServiceIdentityAuthorizationOperations immediateAuthorization() {
        return new ServiceIdentityAuthorizationOperations() {
            @Override
            public <T> T execute(
                    ServiceIdentityAuthorizationRequest request,
                    Function<AuthorizedTenantContext, T> authorizedWork) {
                return authorizedWork.apply(CONTEXT);
            }
        };
    }

    private static EvidenceExportWorkerService.Command command() {
        return new EvidenceExportWorkerService.Command(
                ORGANIZATION_ID,
                EXPORT_ID,
                "worker-01",
                "Export_Worker_Test_Credential_123456789",
                "export-worker-test-001");
    }

    private static EvidenceExportStore.Work runningWork() {
        return new EvidenceExportStore.Work(
                EXPORT_ID,
                REQUESTER_ID,
                "audit-summary-v1",
                "csv",
                "{}",
                "b".repeat(64),
                "security_investigation",
                "running",
                NOW,
                1,
                2,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false);
    }

    private static EvidenceExportStore.Work readyWork() {
        return new EvidenceExportStore.Work(
                EXPORT_ID,
                REQUESTER_ID,
                "audit-summary-v1",
                "csv",
                "{}",
                "b".repeat(64),
                "security_investigation",
                "ready",
                NOW,
                1,
                3,
                ARTIFACT_REFERENCE,
                "a".repeat(64),
                "text/csv",
                "careos-audit-" + EXPORT_ID + ".csv",
                1,
                24L,
                NOW.plusSeconds(86400),
                false);
    }
}
