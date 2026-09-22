package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.governance.application.GovernanceEvidenceOperations;
import com.rootopathy.careos.governance.application.ConsumerInboxOperations;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.InboundOutboxEvent;
import com.rootopathy.careos.governance.domain.OutboxEnvelope;
import com.rootopathy.careos.platform.application.DurableNotificationPort;
import com.rootopathy.careos.platform.domain.DurableNotification;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.ServiceIdentityAuthorizationRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class WorkforceNotificationWorkerService {
    private static final String SERVICE_IDENTITY = "m2-notification-worker-v1";
    private static final String CONSUMER = "m2-notification-worker-v1";
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._:-]{1,160}");
    private final ServiceIdentityAuthorizationOperations authorization;
    private final WorkforceNotificationStore store;
    private final DurableNotificationPort notifications;
    private final GovernanceEvidenceOperations evidence;
    private final ConsumerInboxOperations inbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkforceNotificationWorkerService(
            ServiceIdentityAuthorizationOperations authorization,
            WorkforceNotificationStore store,
            DurableNotificationPort notifications,
            GovernanceEvidenceOperations evidence,
            ConsumerInboxOperations inbox,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorization = authorization;
        this.store = store;
        this.notifications = notifications;
        this.evidence = evidence;
        this.inbox = inbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public QueueResult queuePlanned(QueueCommand command) {
        if (command.maximumItems() < 1 || command.maximumItems() > 100) {
            throw new IllegalArgumentException("maximumItems must be between 1 and 100");
        }
        return authorization.execute(request(
                command.organizationId(),command.presentedCredential(),command.correlationId()),
                context -> {
                    var plans = store.lockPlanned(context,command.maximumItems());
                    for (var plan : plans) {
                        queue(context, plan);
                    }
                    return new QueueResult(plans.size());
                });
    }

    public ConsumeResult consume(OutboxEnvelope envelope, String presentedCredential) {
        if (!"credential.expiry.milestone_reached".equals(envelope.eventName())
                || envelope.schemaVersion() != 1
                || !"practitioner_credential".equals(envelope.aggregateType())) {
            throw new IllegalArgumentException("unsupported notification-worker event");
        }
        var notificationId = notificationId(envelope.payloadJson());
        return authorization.execute(
                request(
                        envelope.organizationId(),presentedCredential,envelope.correlationId()),
                context -> {
                    var result = new ConsumeResult[1];
                    inbox.execute(
                            context,
                            InboundOutboxEvent.from(CONSUMER,envelope),
                            () -> {
                                var plan = store.lockPlanned(
                                        context,notificationId,envelope.aggregateId());
                                if (plan.isPresent()) {
                                    queue(context,plan.orElseThrow());
                                    result[0] = new ConsumeResult(
                                            envelope.eventId(),notificationId,"processed",true);
                                } else if (store.belongsToSource(
                                        context,notificationId,envelope.aggregateId())) {
                                    result[0] = new ConsumeResult(
                                            envelope.eventId(),notificationId,"processed",false);
                                } else {
                                    throw new IllegalArgumentException(
                                            "notification event does not match its expiry source");
                                }
                            });
                    return result[0] == null
                            ? new ConsumeResult(
                                    envelope.eventId(),notificationId,"duplicate",false)
                            : result[0];
                });
    }

    public WorkforceNotificationStore.Delivery delivered(OutcomeCommand command) {
        var providerReference = safe(command.outcomeCode(),"providerOpaqueId");
        return authorization.execute(request(
                command.organizationId(),command.presentedCredential(),command.correlationId()),
                context -> {
                    var delivery = store.delivered(
                            context,command.notificationId(),command.expectedRevision(),
                            command.attempt(),providerReference,clock.instant());
                    record(context,"workforce.notification.delivered",delivery);
                    return delivery;
                });
    }

    public WorkforceNotificationStore.Delivery failed(OutcomeCommand command) {
        var failureCode = safe(command.outcomeCode(),"failureCode");
        return authorization.execute(request(
                command.organizationId(),command.presentedCredential(),command.correlationId()),
                context -> {
                    var delivery = store.failed(
                            context,command.notificationId(),command.expectedRevision(),
                            command.attempt(),failureCode,clock.instant());
                    record(context,"workforce.notification.failed",delivery);
                    return delivery;
                });
    }

    private void queue(
            com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
            WorkforceNotificationStore.Plan plan) {
        var now = clock.instant();
        var message = ordered(
                "notificationId",plan.notificationId(),
                "memberReference",plan.memberId(),
                "milestone",plan.milestone(),
                "expiryDate",plan.expiryDate(),
                "attempt",plan.attempt());
        notifications.enqueue(
                context,
                new DurableNotification(
                        plan.notificationId(),plan.recipientUserId(),plan.templateKey(),
                        plan.templateVersion(),json(message),
                        "m2-expiry:"+plan.notificationId(),now));
        var queued = store.queued(
                context,plan.notificationId(),plan.revision(),now);
        record(context,"workforce.notification.queued",queued);
    }

    private static ServiceIdentityAuthorizationRequest request(
            UUID organizationId,String credential,String correlationId) {
        return new ServiceIdentityAuthorizationRequest(
                organizationId,credential,SERVICE_IDENTITY,correlationId,
                new OperationKey("m2.notification.deliver"));
    }

    private void record(
            com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
            String event,
            WorkforceNotificationStore.Delivery delivery) {
        var payload = ordered(
                "notificationId",delivery.notificationId(),
                "templateVersion",delivery.templateVersionId(),
                "channel",delivery.channel(),
                "milestone",delivery.milestone(),
                "attempt",delivery.attempt(),
                "state",delivery.state(),
                "failureCode",delivery.failureCode());
        evidence.record(
                context,
                GovernanceEvidence.auditOnly(new AuditRecord(
                        event,1,"workforce_notification",delivery.notificationId(),null,json(payload))));
    }

    private static String safe(String value,String name) {
        if (value==null || !SAFE_TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(name+" has an invalid format");
        }
        return value;
    }

    private UUID notificationId(String payloadJson) {
        try {
            var payload = objectMapper.readTree(payloadJson);
            var value = payload.get("notificationId");
            if (value == null || !value.isString()) {
                throw new IllegalArgumentException(
                        "notification event omitted notificationId");
            }
            return UUID.fromString(value.stringValue());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "notification event payload is invalid",exception);
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
            throw new IllegalStateException("Unable to serialize workforce notification evidence.",exception);
        }
    }

    public record QueueCommand(
            UUID organizationId,
            int maximumItems,
            String presentedCredential,
            String correlationId) {}

    public record OutcomeCommand(
            UUID organizationId,
            UUID notificationId,
            long expectedRevision,
            int attempt,
            String outcomeCode,
            String presentedCredential,
            String correlationId) {
        public OutcomeCommand {
            if (expectedRevision<0) throw new IllegalArgumentException("expectedRevision must be non-negative");
            if (attempt<1 || attempt>5) throw new IllegalArgumentException("attempt must be between 1 and 5");
        }
    }

    public record QueueResult(int queued) {}

    public record ConsumeResult(
            UUID sourceEventId,
            UUID notificationId,
            String status,
            boolean queued) {}
}
