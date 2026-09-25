package com.rootopathy.careos.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rootopathy.careos.governance.application.ConsumerInboxOperations;
import com.rootopathy.careos.governance.application.GovernanceEvidenceOperations;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.GovernanceEvidenceIds;
import com.rootopathy.careos.governance.domain.OutboxEnvelope;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.ServiceIdentityAuthorizationRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class WorkforceOffboardingWorkerServiceTest {
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000301");
    private static final UUID SERVICE_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000302");
    private static final UUID REQUEST_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000303");
    private static final UUID MEMBER_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000304");
    private static final UUID MEMBERSHIP_ID =
            UUID.fromString("01990000-0000-7000-8000-000000000305");
    private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");
    private static final String CORRELATION_ID = "offboarding-worker-test-001";
    private static final String SERVICE_CREDENTIAL =
            "M2_Offboarding-Worker-Unit-Credential-2026";
    private static final AuthorizedTenantContext CONTEXT = new AuthorizedTenantContext(
            ORGANIZATION_ID, SERVICE_ID, "m2-offboarding-worker-v1", CORRELATION_ID);

    private WorkforceOffboardingStore store;
    private GovernanceEvidenceOperations evidence;
    private List<ServiceIdentityAuthorizationRequest> authorizationRequests;
    private WorkforceOffboardingWorkerService service;

    @BeforeEach
    void setUp() {
        store = mock(WorkforceOffboardingStore.class);
        evidence = mock(GovernanceEvidenceOperations.class);
        authorizationRequests = new ArrayList<>();
        when(evidence.record(any(), any()))
                .thenReturn(new GovernanceEvidenceIds(UUID.randomUUID(), null));
        service = new WorkforceOffboardingWorkerService(
                immediateAuthorization(),
                store,
                evidence,
                mock(ConsumerInboxOperations.class),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recordsStartedChildAndCompletedEvidenceInOneAuthorizedExecution() {
        when(store.isDue(CONTEXT, REQUEST_ID, NOW)).thenReturn(true);
        when(store.executeApproved(CONTEXT, REQUEST_ID, NOW)).thenReturn(completedResult());

        var result = service.execute(command());

        assertThat(result).isEqualTo(new WorkforceOffboardingWorkerService.Result(REQUEST_ID, "completed"));
        assertThat(authorizationRequests).singleElement().satisfies(request -> {
            assertThat(request.organizationId()).isEqualTo(ORGANIZATION_ID);
            assertThat(request.purpose()).isEqualTo("m2-offboarding-worker-v1");
            assertThat(request.requiredOperation().value()).isEqualTo("m2.offboarding.execute");
            assertThat(request.correlationId()).isEqualTo(CORRELATION_ID);
        });

        var captured = ArgumentCaptor.forClass(GovernanceEvidence.class);
        verify(evidence, times(3)).record(eq(CONTEXT), captured.capture());
        assertThat(captured.getAllValues())
                .extracting(item -> item.audit().eventName())
                .containsExactly(
                        "workforce.offboarding.started",
                        "identity.membership.revoked",
                        "workforce.offboarding.completed");
        assertThat(captured.getAllValues().get(0).outbox()).isNull();
        assertThat(captured.getAllValues().get(1).outbox().eventName())
                .isEqualTo("identity.membership.revoked");
        assertThat(captured.getAllValues().get(2).outbox().eventName())
                .isEqualTo("workforce.offboarding.completed");
    }

    @Test
    void leavesAFutureApprovedPlanScheduledWithoutInventingEvidence() {
        when(store.isDue(CONTEXT, REQUEST_ID, NOW)).thenReturn(false);

        var result = service.execute(command());

        assertThat(result.status()).isEqualTo("scheduled");
        verify(store, never()).executeApproved(any(), any(), any());
        verify(store, never()).recordFailure(any(), any(), any(), any());
        verify(evidence, never()).record(any(), any());
    }

    @Test
    void rollsBackTheAttemptThenRecordsBoundedFailureEvidenceInANewAuthorization() {
        when(store.isDue(CONTEXT, REQUEST_ID, NOW)).thenReturn(true);
        when(store.executeApproved(CONTEXT, REQUEST_ID, NOW))
                .thenThrow(new WorkforceException(
                        WorkforceException.Reason.CONFLICT,
                        "The approved impact changed."));
        when(store.recordFailure(
                        CONTEXT, REQUEST_ID, "plan_or_dependency_conflict", NOW))
                .thenReturn(failedResult());

        var result = service.execute(command());

        assertThat(result.status()).isEqualTo("failed");
        assertThat(authorizationRequests).hasSize(2).allSatisfy(request -> {
            assertThat(request.purpose()).isEqualTo("m2-offboarding-worker-v1");
            assertThat(request.requiredOperation().value()).isEqualTo("m2.offboarding.execute");
        });
        verify(store).recordFailure(CONTEXT, REQUEST_ID, "plan_or_dependency_conflict", NOW);
        var captured = ArgumentCaptor.forClass(GovernanceEvidence.class);
        verify(evidence).record(eq(CONTEXT), captured.capture());
        assertThat(captured.getValue().audit().eventName()).isEqualTo("workforce.offboarding.failed");
        assertThat(captured.getValue().outbox().eventName()).isEqualTo("workforce.offboarding.failed");
    }

    @Test
    void rejectsAnyUnsubscribedEnvelopeBeforeAuthorization() {
        var envelope = new OutboxEnvelope(
                UUID.fromString("01990000-0000-7000-8000-000000000306"),
                ORGANIZATION_ID,
                "workforce.offboarding.completed",
                1,
                "workforce_offboarding_request",
                REQUEST_ID,
                "{}",
                CORRELATION_ID,
                NOW,
                1,
                UUID.fromString("01990000-0000-7000-8000-000000000307"),
                "worker-01",
                NOW.plusSeconds(30));

        assertThatThrownBy(() -> service.consume(envelope, "credential-value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported offboarding-worker event");
        assertThat(authorizationRequests).isEmpty();
    }

    private ServiceIdentityAuthorizationOperations immediateAuthorization() {
        return new ServiceIdentityAuthorizationOperations() {
            @Override
            public <T> T execute(
                    ServiceIdentityAuthorizationRequest request,
                    Function<AuthorizedTenantContext, T> authorizedWork) {
                authorizationRequests.add(request);
                return authorizedWork.apply(CONTEXT);
            }
        };
    }

    private static WorkforceOffboardingWorkerService.Command command() {
        return new WorkforceOffboardingWorkerService.Command(
                ORGANIZATION_ID, REQUEST_ID, SERVICE_CREDENTIAL, CORRELATION_ID);
    }

    private static WorkforceStore.MutationResult completedResult() {
        var membershipPayload = Map.<String, Object>of(
                "approvalId", REQUEST_ID,
                "changeType", "revoke",
                "fromRole", "practitioner",
                "lockVersion", 3L,
                "membershipId", MEMBERSHIP_ID,
                "toRole", "revoked");
        var completedPayload = Map.<String, Object>of(
                "memberId", MEMBER_ID,
                "offboardingRequestId", REQUEST_ID,
                "impactDigest", "a".repeat(64),
                "effectiveTime", NOW,
                "state", "completed",
                "failureCode", "none");
        return new WorkforceStore.MutationResult(
                REQUEST_ID,
                "workforce_offboarding_request",
                "workforce.offboarding.completed",
                "workforce.offboarding.completed",
                "workforce_offboarding_request",
                completedPayload,
                Map.of(
                        "memberId", MEMBER_ID,
                        "offboardingRequestId", REQUEST_ID,
                        "impactDigest", "a".repeat(64),
                        "effectiveTime", NOW,
                        "state", "completed"),
                200,
                9,
                List.of(new WorkforceStore.MutationEvidence(
                        MEMBERSHIP_ID,
                        "membership",
                        "identity.membership.revoked",
                        "identity.membership.revoked",
                        "membership",
                        membershipPayload,
                        membershipPayload)));
    }

    private static WorkforceStore.MutationResult failedResult() {
        var audit = Map.<String, Object>of(
                "memberId", MEMBER_ID,
                "offboardingRequestId", REQUEST_ID,
                "impactDigest", "a".repeat(64),
                "effectiveTime", NOW,
                "state", "failed",
                "failureCode", "plan_or_dependency_conflict");
        return new WorkforceStore.MutationResult(
                REQUEST_ID,
                "workforce_offboarding_request",
                "workforce.offboarding.failed",
                "workforce.offboarding.failed",
                "workforce_offboarding_request",
                audit,
                Map.of(
                        "memberId", MEMBER_ID,
                        "offboardingRequestId", REQUEST_ID,
                        "impactDigest", "a".repeat(64),
                        "effectiveTime", NOW,
                        "state", "failed"),
                409,
                8);
    }
}
