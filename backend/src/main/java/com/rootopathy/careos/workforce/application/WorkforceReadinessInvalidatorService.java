package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.governance.application.ConsumerInboxOperations;
import com.rootopathy.careos.governance.domain.InboundOutboxEvent;
import com.rootopathy.careos.governance.domain.OutboxEnvelope;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.ServiceIdentityAuthorizationRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Inbox-deduplicated reconciliation for the approved readiness invalidator contract.
 * Database source triggers invalidate synchronously; this consumer closes delivery gaps
 * without reopening or duplicating already-invalidated evidence.
 */
@Service
public final class WorkforceReadinessInvalidatorService {
    private static final String IDENTITY = "m2-readiness-invalidator-v1";
    private static final Map<String, String> MATERIAL_EVENTS = Map.ofEntries(
            event("organization.profile.updated", "organization"),
            event("identity.membership.changed", "membership"),
            event("identity.membership.revoked", "membership"),
            event("identity.owner.transferred", "membership"),
            event("facility.created", "facility"),
            event("facility.updated", "facility"),
            event("facility.submitted", "facility"),
            event("facility.activated", "facility"),
            event("facility.suspended", "facility"),
            event("facility.closed", "facility"),
            event("network.unit.changed", "organization_unit"),
            event("network.unit.reparented", "organization_unit"),
            event("network.location.changed", "service_location"),
            event("network.location.reparented", "service_location"),
            event("service.definition.created", "service_definition"),
            event("service.definition.updated", "service_definition"),
            event("service.definition.activated", "service_definition"),
            event("service.definition.retired", "service_definition"),
            event("service.assignment.scheduled", "service_assignment"),
            event("service.assignment.activated", "service_assignment"),
            event("service.assignment.suspended", "service_assignment"),
            event("service.assignment.ended", "service_assignment"),
            event("service.assignment.cancelled", "service_assignment"),
            event("workforce.member.draft_created", "workforce_member"),
            event("workforce.member.changed", "workforce_member"),
            event("workforce.person_merge.executed", "person_merge_request"),
            event("workforce.identifier.created", "workforce_identifier"),
            event("workforce.identifier.updated", "workforce_identifier"),
            event("workforce.identifier.verified", "workforce_identifier"),
            event("workforce.identifier.revoked", "workforce_identifier"),
            event("workforce.identifier.superseded", "workforce_identifier"),
            event("workforce.engagement.created", "employment_engagement"),
            event("workforce.engagement.scheduled", "employment_engagement"),
            event("workforce.engagement.activated", "employment_engagement"),
            event("workforce.engagement.suspended", "employment_engagement"),
            event("workforce.engagement.ended", "employment_engagement"),
            event("workforce.engagement.cancelled", "employment_engagement"),
            event("practitioner.profile.created", "practitioner_profile"),
            event("practitioner.profile.activated", "practitioner_profile"),
            event("practitioner.profile.suspended", "practitioner_profile"),
            event("practitioner.profile.ended", "practitioner_profile"),
            event("credential.qualification.changed", "qualification"),
            event("credential.registration.created", "professional_registration"),
            event("credential.registration.submitted", "professional_registration"),
            event("credential.registration.verified", "professional_registration"),
            event("credential.registration.suspended", "professional_registration"),
            event("credential.registration.revoked", "professional_registration"),
            event("credential.registration.expired", "professional_registration"),
            event("credential.registration.superseded", "professional_registration"),
            event("credential.record.submitted", "practitioner_credential"),
            event("credential.record.returned", "practitioner_credential"),
            event("credential.review.decided", "practitioner_credential"),
            event("credential.record.suspended", "practitioner_credential"),
            event("credential.record.revoked", "practitioner_credential"),
            event("credential.record.expired", "practitioner_credential"),
            event("credential.record.superseded", "practitioner_credential"),
            event("credential.document.quarantined", "credential_document"),
            event("credential.document.clean", "credential_document"),
            event("credential.document.rejected", "credential_document"),
            event("credential.legal_hold.changed", "credential_legal_hold"),
            event("practitioner.specialty.changed", "practitioner_specialty"),
            event("practitioner.scope.submitted", "scope_of_practice"),
            event("practitioner.scope.decided", "scope_of_practice"),
            event("practitioner.scope.suspended", "scope_of_practice"),
            event("practitioner.scope.ended", "scope_of_practice"),
            event("practitioner.scope.superseded", "scope_of_practice"),
            event("workforce.assignment.created", "workforce_assignment"),
            event("workforce.assignment.scheduled", "workforce_assignment"),
            event("workforce.assignment.activated", "workforce_assignment"),
            event("workforce.assignment.suspended", "workforce_assignment"),
            event("workforce.assignment.reactivated", "workforce_assignment"),
            event("workforce.assignment.ended", "workforce_assignment"),
            event("workforce.assignment.cancelled", "workforce_assignment"),
            event("workforce.assignment.transferred", "workforce_assignment"),
            event("practitioner.service_assignment.created", "practitioner_service_assignment"),
            event("practitioner.service_assignment.scheduled", "practitioner_service_assignment"),
            event("practitioner.service_assignment.activated", "practitioner_service_assignment"),
            event("practitioner.service_assignment.suspended", "practitioner_service_assignment"),
            event("practitioner.service_assignment.ended", "practitioner_service_assignment"),
            event("practitioner.service_assignment.cancelled", "practitioner_service_assignment"),
            event("workforce.availability.scheduled", "availability_profile"),
            event("workforce.availability.activated", "availability_profile"),
            event("workforce.availability.superseded", "availability_profile"),
            event("workforce.availability.cancelled", "availability_profile"),
            event("workforce.account_link.requested", "workforce_member"),
            event("workforce.member.suspended", "workforce_member"),
            event("workforce.member.reactivated", "workforce_member"),
            event("workforce.offboarding.completed", "workforce_offboarding_request"),
            event("workforce.registry.activated", "workforce_registry_version"),
            event("workforce.configuration.activated", "workforce_configuration_snapshot"),
            event("workforce.configuration.superseded", "workforce_configuration_snapshot"));

    private final ServiceIdentityAuthorizationOperations authorization;
    private final WorkforceReadinessInvalidationStore store;
    private final ConsumerInboxOperations inbox;

    public WorkforceReadinessInvalidatorService(
            ServiceIdentityAuthorizationOperations authorization,
            WorkforceReadinessInvalidationStore store,
            ConsumerInboxOperations inbox) {
        this.authorization = authorization;
        this.store = store;
        this.inbox = inbox;
    }

    public Result consume(OutboxEnvelope envelope, String presentedCredential) {
        var expectedAggregate = MATERIAL_EVENTS.get(envelope.eventName());
        if (envelope.schemaVersion() != 1
                || expectedAggregate == null
                || !expectedAggregate.equals(envelope.aggregateType())) {
            throw new IllegalArgumentException("unsupported readiness-invalidator event");
        }
        return authorization.execute(
                new ServiceIdentityAuthorizationRequest(
                        envelope.organizationId(),
                        presentedCredential,
                        IDENTITY,
                        envelope.correlationId(),
                        new OperationKey("workforce.validation.run")),
                context -> {
                    var result = new Result[1];
                    inbox.execute(
                            context,
                            InboundOutboxEvent.from(IDENTITY, envelope),
                            () -> result[0] = new Result(
                                    envelope.eventId(),
                                    "processed",
                                    store.reconcile(
                                            context,
                                            envelope.aggregateType(),
                                            envelope.aggregateId(),
                                            invalidationCode(envelope.eventName()))));
                    return result[0] == null
                            ? new Result(envelope.eventId(), "duplicate", 0)
                            : result[0];
                });
    }

    private static Map.Entry<String, String> event(String name, String aggregateType) {
        return Map.entry(name, aggregateType);
    }

    private static String invalidationCode(String eventName) {
        if (eventName.startsWith("credential.")) return "credential_evidence_changed";
        if (eventName.startsWith("workforce.availability.")) return "availability_state_changed";
        if (eventName.startsWith("workforce.member.")) return "member_state_changed";
        if (eventName.startsWith("organization.")
                || eventName.startsWith("identity.")
                || eventName.startsWith("facility.")
                || eventName.startsWith("network.")
                || eventName.startsWith("service.")
                || eventName.startsWith("workforce.registry.")
                || eventName.startsWith("workforce.configuration.")) {
            return "organization_dependency_changed";
        }
        return "evaluated_state_changed";
    }

    public record Result(UUID sourceEventId, String status, int affectedMembers) {}
}
