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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class WorkforceExpiryProjectorService {
    private static final String SCHEDULER_IDENTITY = "m2-expiry-scheduler-v1";
    private static final String PROJECTOR_IDENTITY = "m2-expiry-projector-v1";
    private static final String CONSUMER = "m2-expiry-projector-v1";
    private static final Set<String> SOURCE_EVENTS = Set.of(
            "credential.registration.verified",
            "credential.registration.superseded",
            "credential.registration.expired",
            "credential.review.decided",
            "credential.record.superseded",
            "credential.record.expired");
    private final ServiceIdentityAuthorizationOperations authorization;
    private final WorkforceExpiryStore store;
    private final GovernanceEvidenceOperations evidence;
    private final ConsumerInboxOperations inbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkforceExpiryProjectorService(
            ServiceIdentityAuthorizationOperations authorization,
            WorkforceExpiryStore store,
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

    public Result process(Command command) {
        if (command.maximumItems() < 1 || command.maximumItems() > 500) {
            throw new IllegalArgumentException("maximumItems must be between 1 and 500");
        }
        return authorization.execute(
                new ServiceIdentityAuthorizationRequest(
                        command.organizationId(),
                        command.presentedCredential(),
                        SCHEDULER_IDENTITY,
                        command.correlationId(),
                        new OperationKey("m2.expiry.process")),
                context -> {
                    var now = clock.instant();
                    var evaluationDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
                    var milestones = store.projectDue(
                            context,evaluationDate,now,command.maximumItems());
                    for (var milestone : milestones) record(context, milestone);
                    var planned = milestones.stream()
                            .filter(milestone -> milestone.state().equals("planned"))
                            .count();
                    return new Result(evaluationDate,milestones.size(),planned,milestones.size()-planned);
                });
    }

    public ConsumeResult consume(OutboxEnvelope envelope, String presentedCredential) {
        if (envelope.schemaVersion()!=1 || !SOURCE_EVENTS.contains(envelope.eventName())
                || !("professional_registration".equals(envelope.aggregateType())
                     || "practitioner_credential".equals(envelope.aggregateType()))) {
            throw new IllegalArgumentException("unsupported expiry-projector event");
        }
        return authorization.execute(
                new ServiceIdentityAuthorizationRequest(
                        envelope.organizationId(),presentedCredential,PROJECTOR_IDENTITY,
                        envelope.correlationId(),new OperationKey("m2.expiry.process")),
                context -> {
                    var result = new ConsumeResult[1];
                    inbox.execute(
                            context,
                            InboundOutboxEvent.from(CONSUMER,envelope),
                            () -> {
                                var now=clock.instant();
                                var evaluationDate=LocalDate.ofInstant(now,ZoneOffset.UTC);
                                var milestones=store.projectDue(context,evaluationDate,now,500);
                                for (var milestone:milestones) record(context,milestone);
                                result[0]=new ConsumeResult(
                                        envelope.eventId(),"processed",milestones.size());
                            });
                    return result[0]==null
                            ? new ConsumeResult(envelope.eventId(),"duplicate",0)
                            : result[0];
                });
    }

    private void record(
            com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
            WorkforceExpiryStore.Milestone milestone) {
        var payload = ordered(
                "credentialId",milestone.sourceId(),
                "milestone",milestone.milestone(),
                "expiryDate",milestone.expiryDate(),
                "notificationId",milestone.notificationId(),
                "outcomeCode",milestone.outcomeCode());
        if (milestone.state().equals("planned")) {
            var outbox = ordered(
                    "credentialId",milestone.sourceId(),
                    "milestone",milestone.milestone(),
                    "expiryDate",milestone.expiryDate(),
                    "notificationId",milestone.notificationId());
            evidence.record(
                    context,
                    new GovernanceEvidence(
                            new AuditRecord(
                                    "credential.expiry.milestone_reached",1,
                                    "practitioner_credential",milestone.sourceId(),null,json(payload)),
                            new OutboxRecord(
                                    "credential.expiry.milestone_reached",1,
                                    "practitioner_credential",milestone.sourceId(),json(outbox))));
        } else {
            evidence.record(
                    context,
                    GovernanceEvidence.auditOnly(new AuditRecord(
                            "credential.expiry.reminder_suppressed",1,
                            "practitioner_credential",milestone.sourceId(),null,json(payload))));
        }
    }

    private static LinkedHashMap<String,Object> ordered(Object... values) {
        var result = new LinkedHashMap<String,Object>();
        for (var index=0;index<values.length;index+=2) {
            result.put((String) values[index],values[index+1]);
        }
        return result;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize workforce expiry evidence.",exception);
        }
    }

    public record Command(
            UUID organizationId,
            int maximumItems,
            String presentedCredential,
            String correlationId) {}

    public record Result(
            LocalDate evaluationDate,
            long projected,
            long planned,
            long suppressed) {}

    public record ConsumeResult(UUID sourceEventId,String status,int projected) {}
}
