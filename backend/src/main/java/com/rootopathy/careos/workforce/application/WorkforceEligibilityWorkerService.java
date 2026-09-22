package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.governance.application.ConsumerInboxOperations;
import com.rootopathy.careos.governance.application.GovernanceEvidenceOperations;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.InboundOutboxEvent;
import com.rootopathy.careos.governance.domain.OutboxEnvelope;
import com.rootopathy.careos.governance.domain.OutboxRecord;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.ServiceIdentityAuthorizationRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class WorkforceEligibilityWorkerService {
    private static final String SERVICE_IDENTITY = "m2-eligibility-evaluator-v1";
    private static final String CONSUMER = "m2-eligibility-evaluator-v1";
    private static final Set<String> MATERIAL_EVENTS = Set.of(
            "credential.qualification.changed",
            "credential.registration.verified",
            "credential.registration.suspended",
            "credential.registration.revoked",
            "credential.registration.expired",
            "credential.registration.superseded",
            "credential.record.submitted",
            "credential.record.returned",
            "credential.review.decided",
            "credential.record.suspended",
            "credential.record.revoked",
            "credential.record.expired",
            "credential.record.superseded",
            "credential.expiry.milestone_reached",
            "practitioner.scope.submitted",
            "practitioner.scope.decided",
            "practitioner.scope.suspended",
            "practitioner.scope.ended",
            "practitioner.scope.superseded",
            "workforce.assignment.created",
            "workforce.assignment.scheduled",
            "workforce.assignment.activated",
            "workforce.assignment.suspended",
            "workforce.assignment.reactivated",
            "workforce.assignment.ended",
            "workforce.assignment.cancelled",
            "workforce.assignment.transferred",
            "practitioner.service_assignment.created",
            "practitioner.service_assignment.scheduled",
            "practitioner.service_assignment.activated",
            "practitioner.service_assignment.suspended",
            "practitioner.service_assignment.ended",
            "practitioner.service_assignment.cancelled",
            "workforce.member.suspended",
            "workforce.member.reactivated",
            "workforce.offboarding.completed",
            "workforce.registry.activated",
            "workforce.configuration.activated",
            "workforce.configuration.superseded");
    private final ServiceIdentityAuthorizationOperations authorization;
    private final WorkforceEligibilityStore store;
    private final GovernanceEvidenceOperations evidence;
    private final ConsumerInboxOperations inbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkforceEligibilityWorkerService(
            ServiceIdentityAuthorizationOperations authorization,
            WorkforceEligibilityStore store,
            GovernanceEvidenceOperations evidence,
            ConsumerInboxOperations inbox,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorization = authorization;
        this.store = store;
        this.evidence = evidence;
        this.inbox = inbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public WorkforceEligibilityStore.Evaluation evaluate(Command command) {
        return authorization.execute(
                new ServiceIdentityAuthorizationRequest(
                        command.organizationId(),
                        command.presentedCredential(),
                        SERVICE_IDENTITY,
                        command.correlationId(),
                        new OperationKey("m2.eligibility.evaluate")),
                context -> {
                    var evaluated = store.evaluate(
                            context,
                            new WorkforceEligibilityStore.Command(
                                    command.practitionerId(),
                                    command.serviceId(),
                                    command.facilityId(),
                                    command.locationId(),
                                    command.activityEntryId(),
                                    command.supervisorPractitionerId(),
                                    command.evaluatedFrom(),
                                    command.evaluatedTo(),
                                    clock.instant()));
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("practitionerId", evaluated.practitionerId());
                    payload.put("contextId", evaluated.contextId());
                    payload.put("resultId", evaluated.resultId());
                    payload.put("outcome", evaluated.outcome());
                    payload.put("resultDigest", evaluated.resultDigest());
                    payload.put("expiryTime", evaluated.expiresAt());
                    var json = json(payload);
                    evidence.record(
                            context,
                            new GovernanceEvidence(
                                    new AuditRecord(
                                            "practitioner.eligibility.evaluated",
                                            1,
                                            "practitioner_eligibility",
                                            evaluated.resultId(),
                                            null,
                                            json),
                                    new OutboxRecord(
                                            "practitioner.eligibility.evaluated",
                                            1,
                                            "practitioner_eligibility",
                                            evaluated.resultId(),
                                            json)));
                    return evaluated;
                });
    }

    public WorkforceEligibilityStore.Reconciliation reconcile(ReconcileCommand command) {
        return authorization.execute(
                new ServiceIdentityAuthorizationRequest(
                        command.organizationId(),
                        command.presentedCredential(),
                        SERVICE_IDENTITY,
                        command.correlationId(),
                        new OperationKey("m2.eligibility.evaluate")),
                context -> {
                    return reconcile(context, command.serviceAssignmentId());
                });
    }

    public ConsumeResult consume(OutboxEnvelope envelope, String presentedCredential) {
        if (envelope.schemaVersion() != 1 || !MATERIAL_EVENTS.contains(envelope.eventName())) {
            throw new IllegalArgumentException("unsupported eligibility-evaluator event");
        }
        return authorization.execute(
                new ServiceIdentityAuthorizationRequest(
                        envelope.organizationId(),
                        presentedCredential,
                        SERVICE_IDENTITY,
                        envelope.correlationId(),
                        new OperationKey("m2.eligibility.evaluate")),
                context -> {
                    var result = new ConsumeResult[1];
                    inbox.execute(
                            context,
                            InboundOutboxEvent.from(CONSUMER, envelope),
                            () -> {
                                var assignmentIds = store.affectedServiceAssignmentIds(
                                        context, envelope.eventName(), envelope.aggregateId());
                                var suspended = 0;
                                for (var assignmentId : assignmentIds) {
                                    var reconciled = reconcile(context, assignmentId);
                                    if (!reconciled.fromState().equals(reconciled.toState())) {
                                        suspended++;
                                    }
                                }
                                result[0] = new ConsumeResult(
                                        envelope.eventId(), "processed", assignmentIds.size(), suspended);
                            });
                    return result[0] == null
                            ? new ConsumeResult(envelope.eventId(), "duplicate", 0, 0)
                            : result[0];
                });
    }

    private WorkforceEligibilityStore.Reconciliation reconcile(
            com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
            UUID serviceAssignmentId) {
        var reconciled = store.reconcile(context, serviceAssignmentId, clock.instant());
        var evaluated = reconciled.evaluation();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("practitionerId", evaluated.practitionerId());
        payload.put("contextId", evaluated.contextId());
        payload.put("resultId", evaluated.resultId());
        payload.put("outcome", evaluated.outcome());
        payload.put("resultDigest", evaluated.resultDigest());
        payload.put("expiryTime", evaluated.expiresAt());
        var event = evaluated.outcome().equals("eligible")
                ? "practitioner.eligibility.evaluated"
                : "practitioner.eligibility.invalidated";
        var json = json(payload);
        evidence.record(
                context,
                new GovernanceEvidence(
                        new AuditRecord(
                                event,
                                1,
                                "practitioner_eligibility",
                                evaluated.resultId(),
                                null,
                                json),
                        new OutboxRecord(
                                event,
                                1,
                                "practitioner_eligibility",
                                evaluated.resultId(),
                                json)));
        return reconciled;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize eligibility evidence.", exception);
        }
    }

    public record Command(
            UUID organizationId,
            UUID practitionerId,
            UUID serviceId,
            UUID facilityId,
            UUID locationId,
            UUID activityEntryId,
            UUID supervisorPractitionerId,
            Instant evaluatedFrom,
            Instant evaluatedTo,
            String presentedCredential,
            String correlationId) {
        public Command(
                UUID organizationId,
                UUID practitionerId,
                UUID serviceId,
                UUID facilityId,
                UUID locationId,
                UUID activityEntryId,
                Instant evaluatedFrom,
                Instant evaluatedTo,
                String presentedCredential,
                String correlationId) {
            this(organizationId,practitionerId,serviceId,facilityId,locationId,activityEntryId,
                    null,evaluatedFrom,evaluatedTo,presentedCredential,correlationId);
        }
    }

    public record ReconcileCommand(
            UUID organizationId,
            UUID serviceAssignmentId,
            String presentedCredential,
            String correlationId) {}

    public record ConsumeResult(
            UUID sourceEventId,
            String status,
            int reconciledAssignments,
            int newlySuspendedAssignments) {}
}
