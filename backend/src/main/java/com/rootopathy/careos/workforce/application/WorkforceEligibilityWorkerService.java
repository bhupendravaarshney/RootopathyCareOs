package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.governance.application.GovernanceEvidenceOperations;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.OutboxRecord;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.ServiceIdentityAuthorizationRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class WorkforceEligibilityWorkerService {
    private static final String SERVICE_IDENTITY = "m2-eligibility-evaluator-v1";
    private final ServiceIdentityAuthorizationOperations authorization;
    private final WorkforceEligibilityStore store;
    private final GovernanceEvidenceOperations evidence;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkforceEligibilityWorkerService(
            ServiceIdentityAuthorizationOperations authorization,
            WorkforceEligibilityStore store,
            GovernanceEvidenceOperations evidence,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorization = authorization;
        this.store = store;
        this.evidence = evidence;
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
}
