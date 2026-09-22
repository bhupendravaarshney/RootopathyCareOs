package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.governance.application.ConsumerInboxOperations;
import com.rootopathy.careos.governance.domain.InboundOutboxEvent;
import com.rootopathy.careos.governance.domain.OutboxEnvelope;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.ServiceIdentityAuthorizationRequest;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Deduplicates the approved history feed. The minimum-necessary history projection is
 * intentionally read from immutable governed audit evidence, so the exact 44-table M2
 * baseline does not need a second mutable copy of the same facts.
 */
@Service
public final class WorkforceHistoryProjectorService {
    private static final String IDENTITY = "m2-history-projector-v1";
    private static final Set<String> HISTORY_EVENTS = Set.of(
            "workforce.member.draft_created",
            "workforce.member.changed",
            "workforce.person_merge.executed",
            "workforce.identifier.created",
            "workforce.identifier.updated",
            "workforce.identifier.verified",
            "workforce.identifier.revoked",
            "workforce.identifier.superseded",
            "workforce.engagement.created",
            "workforce.engagement.scheduled",
            "workforce.engagement.activated",
            "workforce.engagement.suspended",
            "workforce.engagement.ended",
            "workforce.engagement.cancelled",
            "practitioner.profile.created",
            "practitioner.profile.activated",
            "practitioner.profile.suspended",
            "practitioner.profile.ended",
            "credential.qualification.changed",
            "credential.registration.created",
            "credential.registration.submitted",
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
            "credential.document.quarantined",
            "credential.document.clean",
            "credential.document.rejected",
            "credential.legal_hold.changed",
            "practitioner.specialty.changed",
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
            "workforce.account_link.requested",
            "practitioner.service_assignment.created",
            "practitioner.service_assignment.scheduled",
            "practitioner.service_assignment.activated",
            "practitioner.service_assignment.suspended",
            "practitioner.service_assignment.ended",
            "practitioner.service_assignment.cancelled",
            "practitioner.eligibility.evaluated",
            "practitioner.eligibility.invalidated",
            "workforce.availability.scheduled",
            "workforce.availability.activated",
            "workforce.availability.superseded",
            "workforce.availability.cancelled",
            "workforce.readiness.invalidated",
            "workforce.activation.submitted",
            "workforce.activation.approved",
            "workforce.activation.rejected",
            "workforce.activation.invalidated",
            "workforce.member.activated",
            "workforce.member.suspended",
            "workforce.member.reactivated",
            "workforce.offboarding.approved",
            "workforce.offboarding.completed",
            "workforce.offboarding.failed",
            "credential.expiry.milestone_reached",
            "workforce.registry.activated",
            "workforce.configuration.activated",
            "workforce.configuration.superseded");

    private final ServiceIdentityAuthorizationOperations authorization;
    private final ConsumerInboxOperations inbox;

    public WorkforceHistoryProjectorService(
            ServiceIdentityAuthorizationOperations authorization,
            ConsumerInboxOperations inbox) {
        this.authorization = authorization;
        this.inbox = inbox;
    }

    public Result consume(OutboxEnvelope envelope, String presentedCredential) {
        if (envelope.schemaVersion() != 1 || !HISTORY_EVENTS.contains(envelope.eventName())) {
            throw new IllegalArgumentException("unsupported history-projector event");
        }
        return authorization.execute(
                new ServiceIdentityAuthorizationRequest(
                        envelope.organizationId(),
                        presentedCredential,
                        IDENTITY,
                        envelope.correlationId(),
                        new OperationKey("workforce.history.read")),
                context -> {
                    var processed = new boolean[1];
                    inbox.execute(
                            context,
                            InboundOutboxEvent.from(IDENTITY, envelope),
                            () -> processed[0] = true);
                    return new Result(
                            envelope.eventId(), processed[0] ? "processed" : "duplicate");
                });
    }

    public record Result(UUID sourceEventId, String status) {}
}
