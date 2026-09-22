package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WorkforceEligibilityStore {
    Evaluation evaluate(AuthorizedTenantContext context, Command command);

    Reconciliation reconcile(
            AuthorizedTenantContext context, UUID serviceAssignmentId, Instant now);

    List<UUID> affectedServiceAssignmentIds(
            AuthorizedTenantContext context,
            String eventName,
            UUID aggregateId);

    record Command(
            UUID practitionerId,
            UUID serviceId,
            UUID facilityId,
            UUID locationId,
            UUID activityEntryId,
            UUID supervisorPractitionerId,
            Instant evaluatedFrom,
            Instant evaluatedTo,
            Instant now) {
        public Command(
                UUID practitionerId,
                UUID serviceId,
                UUID facilityId,
                UUID locationId,
                UUID activityEntryId,
                Instant evaluatedFrom,
                Instant evaluatedTo,
                Instant now) {
            this(practitionerId,serviceId,facilityId,locationId,activityEntryId,null,
                    evaluatedFrom,evaluatedTo,now);
        }
    }

    record Evaluation(
            UUID resultId,
            UUID practitionerId,
            UUID contextId,
            String outcome,
            String resultDigest,
            Instant expiresAt) {}

    record Reconciliation(
            UUID serviceAssignmentId,
            String fromState,
            String toState,
            long revision,
            Evaluation evaluation) {}
}
