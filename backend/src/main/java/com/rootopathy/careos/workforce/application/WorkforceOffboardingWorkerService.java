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
import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class WorkforceOffboardingWorkerService {
    private static final String SERVICE_IDENTITY = "m2-offboarding-worker-v1";
    private static final String CONSUMER = "m2-offboarding-worker-v1";
    private final ServiceIdentityAuthorizationOperations authorization;
    private final WorkforceOffboardingStore store;
    private final GovernanceEvidenceOperations evidence;
    private final ConsumerInboxOperations inbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkforceOffboardingWorkerService(
            ServiceIdentityAuthorizationOperations authorization,
            WorkforceOffboardingStore store,
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

    public Result consume(OutboxEnvelope envelope, String presentedCredential) {
        if (!"workforce.offboarding.approved".equals(envelope.eventName())
                || envelope.schemaVersion() != 1
                || !"workforce_offboarding_request".equals(envelope.aggregateType())) {
            throw new IllegalArgumentException("unsupported offboarding-worker event");
        }
        var command = new Command(
                envelope.organizationId(),
                envelope.aggregateId(),
                presentedCredential,
                envelope.correlationId());
        try {
            return authorization.execute(request(command), context -> {
                var result = new Result[1];
                inbox.execute(
                        context,
                        InboundOutboxEvent.from(CONSUMER, envelope),
                        () -> result[0] = execute(context, command));
                return result[0] == null
                        ? new Result(command.requestId(), "duplicate")
                        : result[0];
            });
        } catch (RuntimeException exception) {
            return recordFailure(command, envelope, failureCode(exception));
        }
    }

    public Result execute(Command command) {
        try {
            return authorization.execute(request(command), context -> execute(context, command));
        } catch (RuntimeException exception) {
            return recordFailure(command, null, failureCode(exception));
        }
    }

    private Result execute(
            com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
            Command command) {
        if (!store.isDue(context, command.requestId(), clock.instant())) {
            return new Result(command.requestId(), "scheduled");
        }
        var completed = store.executeApproved(context, command.requestId(), clock.instant());
        var started = new LinkedHashMap<String,Object>(completed.auditPayload());
        started.put("state","started");
        started.put("failureCode",null);
        var startedPayload = json(started);
        evidence.record(
                context,
                GovernanceEvidence.auditOnly(new AuditRecord(
                        "workforce.offboarding.started",
                        1,
                        "workforce_offboarding_request",
                        completed.subjectId(),
                        "Execute the independently approved offboarding plan.",
                        startedPayload)));
        var completedPayload = json(completed.auditPayload());
        evidence.record(
                context,
                new GovernanceEvidence(
                        new AuditRecord(
                                completed.auditEvent(),
                                1,
                                completed.subjectType(),
                                completed.subjectId(),
                                "Execute the independently approved offboarding plan.",
                                completedPayload),
                        new OutboxRecord(
                                completed.outboxEvent(),
                                1,
                                completed.aggregateType(),
                                completed.subjectId(),
                                json(completed.outboxPayload()))));
        return new Result(completed.subjectId(), "completed");
    }

    private Result recordFailure(
            Command command,OutboxEnvelope envelope,String failureCode) {
        return authorization.execute(request(command),context -> {
            var result = new Result[1];
            var work = (Runnable) () -> {
                var failed = store.recordFailure(
                        context,command.requestId(),failureCode,clock.instant());
                evidence.record(
                        context,
                        new GovernanceEvidence(
                                new AuditRecord(
                                        failed.auditEvent(),1,failed.subjectType(),failed.subjectId(),
                                        "Reconcile a failed approved offboarding plan.",
                                        json(failed.auditPayload())),
                                new OutboxRecord(
                                        failed.outboxEvent(),1,failed.aggregateType(),failed.subjectId(),
                                        json(failed.outboxPayload()))));
                result[0]=new Result(command.requestId(),"failed");
            };
            if (envelope==null) {
                work.run();
            } else {
                inbox.execute(context,InboundOutboxEvent.from(CONSUMER,envelope),work);
            }
            return result[0]==null
                    ? new Result(command.requestId(),"duplicate")
                    : result[0];
        });
    }

    private static String failureCode(RuntimeException exception) {
        if (exception instanceof WorkforceException workforce
                && workforce.reason()==WorkforceException.Reason.CONFLICT) {
            return "plan_or_dependency_conflict";
        }
        if (exception instanceof org.springframework.dao.DataAccessException) {
            return "persistence_dependency_failed";
        }
        return "execution_failed";
    }

    private static ServiceIdentityAuthorizationRequest request(Command command) {
        return new ServiceIdentityAuthorizationRequest(
                command.organizationId(),
                command.presentedCredential(),
                SERVICE_IDENTITY,
                command.correlationId(),
                new OperationKey("m2.offboarding.execute"));
    }

    public List<UUID> dueRequests(SweepCommand command) {
        if (command.maximumItems() < 1 || command.maximumItems() > 100) {
            throw new IllegalArgumentException("maximumItems must be between 1 and 100");
        }
        return authorization.execute(
                new ServiceIdentityAuthorizationRequest(
                        command.organizationId(),
                        command.presentedCredential(),
                        SERVICE_IDENTITY,
                        command.correlationId(),
                        new OperationKey("m2.offboarding.execute")),
                context -> store.dueRequestIds(
                        context, clock.instant(), command.maximumItems()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize offboarding evidence.", exception);
        }
    }

    public record Command(
            UUID organizationId,
            UUID requestId,
            String presentedCredential,
            String correlationId) {}

    public record SweepCommand(
            UUID organizationId,
            int maximumItems,
            String presentedCredential,
            String correlationId) {}

    public record Result(UUID requestId, String status) {}
}
