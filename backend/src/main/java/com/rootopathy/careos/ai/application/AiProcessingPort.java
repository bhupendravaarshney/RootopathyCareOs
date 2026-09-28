package com.rootopathy.careos.ai.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Versioned, minimum-necessary boundary for a separately deployed Python AI processor. */
public interface AiProcessingPort {
    Result process(Request request);

    record Request(
            UUID organizationId,
            UUID aiSessionId,
            UUID jobContractId,
            UUID patientId,
            UUID encounterId,
            String purposeKey,
            String providerKey,
            String modelKey,
            String modelVersion,
            String promptKey,
            String promptVersion,
            String outputSchemaKey,
            int outputSchemaVersion,
            Map<String, String> parameters,
            List<InputReference> inputs) {
        public Request {
            parameters = Map.copyOf(parameters);
            inputs = List.copyOf(inputs);
        }
    }

    record InputReference(
            UUID manifestItemId,
            String sourceType,
            UUID sourceId,
            long sourceRevision,
            String sourceDigest,
            List<String> dataCategories) {
        public InputReference {
            dataCategories = List.copyOf(dataCategories);
        }
    }

    record Result(
            String providerRequestReference,
            String content,
            String uncertaintyLabel,
            List<Citation> citations,
            List<SafetyFlag> safetyFlags,
            long inputTokens,
            long outputTokens,
            long costMinorUnits,
            String currency,
            long latencyMillis,
            Instant providerDeletionDueAt) {
        public Result {
            citations = List.copyOf(citations == null ? List.of() : citations);
            safetyFlags = List.copyOf(safetyFlags == null ? List.of() : safetyFlags);
        }
    }

    record Citation(UUID manifestItemId, String sourceLocator, String claim) {}

    record SafetyFlag(
            String flagKey,
            String severity,
            String summary,
            UUID ownerPractitionerId,
            Instant escalationDueAt) {}
}
