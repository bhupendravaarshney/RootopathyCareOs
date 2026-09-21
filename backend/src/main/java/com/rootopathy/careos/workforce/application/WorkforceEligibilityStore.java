package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.time.Instant;
import java.util.UUID;

public interface WorkforceEligibilityStore {
    Evaluation evaluate(AuthorizedTenantContext context, Command command);

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
}
