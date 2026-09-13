package com.rootopathy.careos.governance.application;

import com.rootopathy.careos.governance.domain.OutboxEnvelope;
import com.rootopathy.careos.governance.domain.OutboxPublicationPolicy;
import com.rootopathy.careos.governance.domain.OutboxPublicationSummary;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * At-least-once publisher coordinator. It is intentionally not auto-wired until an approved
 * non-interactive identity and destination adapter are supplied.
 */
public final class OutboxPublisherService {
    private static final Pattern WORKER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final String UNEXPECTED_ERROR = "transport.unexpected";

    private final TenantAuthorizationOperations authorization;
    private final OutboxDeliveryOperations delivery;
    private final OutboxTransportPort transport;

    public OutboxPublisherService(
            TenantAuthorizationOperations authorization,
            OutboxDeliveryOperations delivery,
            OutboxTransportPort transport) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public OutboxPublicationSummary publishBatch(
            TenantAuthorizationRequest authorizationRequest,
            String workerId,
            OutboxPublicationPolicy policy) {
        Objects.requireNonNull(authorizationRequest, "authorizationRequest");
        Objects.requireNonNull(policy, "policy");
        requireWorkerId(workerId);

        var claimed = authorization.execute(
                authorizationRequest,
                context -> delivery.claimDue(context, workerId, policy.batchSize(), policy.claimLease()));
        var published = 0;
        var rescheduled = 0;
        var deadLettered = 0;
        var leaseLost = 0;

        for (var event : claimed) {
            try {
                transport.publish(event);
            } catch (OutboxTransportException exception) {
                var disposition = recordFailure(
                        authorizationRequest,
                        workerId,
                        policy,
                        event,
                        exception.errorCode(),
                        exception.retryable());
                if (disposition == FailureDisposition.DEAD_LETTERED) {
                    deadLettered++;
                } else if (disposition == FailureDisposition.RESCHEDULED) {
                    rescheduled++;
                } else {
                    leaseLost++;
                }
                continue;
            } catch (RuntimeException exception) {
                var disposition = recordFailure(
                        authorizationRequest,
                        workerId,
                        policy,
                        event,
                        UNEXPECTED_ERROR,
                        true);
                if (disposition == FailureDisposition.DEAD_LETTERED) {
                    deadLettered++;
                } else if (disposition == FailureDisposition.RESCHEDULED) {
                    rescheduled++;
                } else {
                    leaseLost++;
                }
                continue;
            }
            if (authorization.execute(
                    authorizationRequest,
                    context -> delivery.markPublished(
                            context, event.eventId(), event.claimToken(), workerId))) {
                published++;
            } else {
                leaseLost++;
            }
        }
        return new OutboxPublicationSummary(
                claimed.size(), published, rescheduled, deadLettered, leaseLost);
    }

    private FailureDisposition recordFailure(
            TenantAuthorizationRequest request,
            String workerId,
            OutboxPublicationPolicy policy,
            OutboxEnvelope event,
            String errorCode,
            boolean retryable) {
        if (!retryable || event.attemptCount() >= policy.maxAttempts()) {
            return authorization.execute(
                            request,
                            context -> delivery.deadLetter(
                                    context,
                                    event.eventId(),
                                    event.claimToken(),
                                    workerId,
                                    errorCode))
                    ? FailureDisposition.DEAD_LETTERED
                    : FailureDisposition.LEASE_LOST;
        }
        return authorization.execute(
                        request,
                        context -> delivery.reschedule(
                                context,
                                event.eventId(),
                                event.claimToken(),
                                workerId,
                                errorCode,
                                policy.retryDelayForAttempt(event.attemptCount())))
                ? FailureDisposition.RESCHEDULED
                : FailureDisposition.LEASE_LOST;
    }

    private static void requireWorkerId(String workerId) {
        if (workerId == null || !WORKER_ID.matcher(workerId).matches()) {
            throw new IllegalArgumentException("workerId has an invalid format");
        }
    }

    private enum FailureDisposition {
        RESCHEDULED,
        DEAD_LETTERED,
        LEASE_LOST
    }
}
