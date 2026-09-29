package com.rootopathy.careos.ai.infrastructure;

import com.rootopathy.careos.ai.application.AiException;
import com.rootopathy.careos.ai.application.AiProcessingPort;
import com.rootopathy.careos.ai.application.AiStore;
import com.rootopathy.careos.ai.domain.AiScreen;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed Module 8 AI session, provenance, safety and review workflow. */
@Repository
public class JdbcAiStore implements AiStore {
    private static final Set<String> SESSION_TYPES = Set.of(
            "transcription", "extraction", "summary", "clinical_suggestion");
    private static final Set<String> SOURCE_TYPES = Set.of(
            "encounter", "assessment", "document", "diagnostic_report");
    private static final Set<String> TERMINAL = Set.of(
            "accepted", "rejected", "failed", "cancelled");

    private final JdbcTemplate jdbc;

    public JdbcAiStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Projection projection(AuthorizedTenantContext context, ScreenQuery query) {
        requireOperationScope(context);
        var rows = sessionRows(context, query).stream()
                .map(row -> withAllowedActions(query.screenId(), row))
                .limit(query.limit())
                .toList();
        return new Projection(
                metrics(context), columns(query.screenId()), rows, notices(query.screenId()), databaseNow());
    }

    @Override
    public Set<String> permissions(AuthorizedTenantContext context) {
        requireOperationScope(context);
        return Set.copyOf(jdbc.queryForList(
                "SELECT permission_key FROM careos_projected_interactive_permissions(?, ?)",
                String.class,
                context.organizationId(),
                context.actorId()));
    }

    @Override
    public MutationResult mutate(AuthorizedTenantContext context, MutationCommand command) {
        requireOperationScope(context);
        return switch (command.actionKey()) {
            case "launch-session" -> launch(context, command);
            case "record-purpose-consent" -> recordPurpose(context, command);
            case "select-input" -> selectInput(context, command);
            case "edit-output" -> editOutput(context, command);
            case "acknowledge-safety-flag", "resolve-safety-flag" -> reviewSafety(context, command);
            case "decide-output" -> decide(context, command);
            case "cancel-session" -> cancel(context, command);
            default -> throw notFound("The requested AI action does not exist.");
        };
    }

    @Override
    public ProcessingContext prepareProcessing(
            AuthorizedTenantContext context,
            MutationCommand command,
            Map<String, String> parameters) {
        requireOperationScope(context);
        var session = lockSession(context, command.targetId());
        requireRevision(session.revision(), command.expectedRevision());
        if (!Set.of("input_ready", "failed").contains(session.status())
                || session.manifestId() == null) {
            throw conflict("The AI session does not have an approved input manifest.");
        }
        var releases = jdbc.query(
                """
                SELECT model.id model_id,model.provider_key,model.model_key,model.model_version,
                       model.retention_days,prompt.id prompt_id,prompt.prompt_key,
                       prompt.prompt_version,prompt.output_schema_key,prompt.output_schema_version,
                       evaluation.id evaluation_id
                FROM ai_model_releases model
                JOIN ai_prompt_releases prompt ON prompt.organization_id=model.organization_id
                JOIN ai_evaluation_signoffs evaluation
                  ON evaluation.organization_id=model.organization_id
                 AND evaluation.model_release_id=model.id
                 AND evaluation.prompt_release_id=prompt.id
                WHERE model.organization_id=? AND model.status='active'
                  AND model.training_use_prohibited AND model.effective_from<=clock_timestamp()
                  AND prompt.status='active' AND prompt.effective_from<=clock_timestamp()
                  AND prompt.purpose_key=? AND evaluation.outcome='approved'
                  AND evaluation.expires_at>clock_timestamp()
                ORDER BY model.effective_from DESC,prompt.effective_from DESC,evaluation.safety_signoff_at DESC
                LIMIT 1
                """,
                (resultSet, rowNumber) -> new Release(
                        resultSet.getObject("model_id", UUID.class),
                        resultSet.getString("provider_key"),
                        resultSet.getString("model_key"),
                        resultSet.getString("model_version"),
                        resultSet.getInt("retention_days"),
                        resultSet.getObject("prompt_id", UUID.class),
                        resultSet.getString("prompt_key"),
                        resultSet.getString("prompt_version"),
                        resultSet.getString("output_schema_key"),
                        resultSet.getInt("output_schema_version"),
                        resultSet.getObject("evaluation_id", UUID.class)),
                context.organizationId(),
                session.purposeKey());
        if (releases.isEmpty()) {
            throw new AiException(
                    AiException.Reason.POLICY_UNAVAILABLE,
                    "No active model, prompt and evaluation release is approved for this purpose.");
        }
        var release = releases.getFirst();
        var inputs = jdbc.query(
                """
                SELECT id,source_type,source_id,source_revision,source_digest,data_categories
                FROM ai_input_manifest_items
                WHERE organization_id=? AND input_manifest_id=? ORDER BY item_sequence
                """,
                (resultSet, rowNumber) -> new AiProcessingPort.InputReference(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("source_type"),
                        resultSet.getObject("source_id", UUID.class),
                        resultSet.getLong("source_revision"),
                        resultSet.getString("source_digest"),
                        List.of((String[]) resultSet.getArray("data_categories").getArray())),
                context.organizationId(),
                session.manifestId());
        if (inputs.isEmpty()) throw conflict("The approved AI input manifest is empty.");
        var contractId = UuidV7Generator.randomUuid();
        var contractVersion = jdbc.queryForObject(
                "SELECT coalesce(max(contract_version),0)+1 FROM ai_job_contracts WHERE organization_id=? AND ai_session_id=?",
                Integer.class,
                context.organizationId(),
                session.id());
        var parameterDigest = digest(canonical(parameters));
        var contractDigest = digest(String.join(
                "|",
                session.id().toString(),
                session.manifestId().toString(),
                release.modelId().toString(),
                release.promptId().toString(),
                release.evaluationId().toString(),
                release.outputSchemaKey(),
                Integer.toString(release.outputSchemaVersion()),
                parameterDigest));
        jdbc.update(
                """
                INSERT INTO ai_job_contracts(
                    id,organization_id,ai_session_id,input_manifest_id,model_release_id,
                    prompt_release_id,evaluation_signoff_id,contract_version,output_schema_key,
                    output_schema_version,parameter_digest,contract_digest,status,requested_at,
                    reason,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'requested',clock_timestamp(),?,?,?)
                """,
                contractId,
                context.organizationId(),
                session.id(),
                session.manifestId(),
                release.modelId(),
                release.promptId(),
                release.evaluationId(),
                contractVersion,
                release.outputSchemaKey(),
                release.outputSchemaVersion(),
                parameterDigest,
                contractDigest,
                command.reason(),
                context.actorId(),
                context.actorId());
        var changed = jdbc.update(
                """
                UPDATE ai_sessions SET status='processing',failure_code=NULL,
                    lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                  AND status IN ('input_ready','failed')
                """,
                context.actorId(), context.organizationId(), session.id(), session.revision());
        requireChanged(changed, "The AI session changed before processing began.");
        var attempt = jdbc.queryForObject(
                "SELECT coalesce(max(attempt_number),0)+1 FROM ai_job_attempts WHERE organization_id=? AND job_contract_id=?",
                Integer.class,
                context.organizationId(),
                contractId);
        return new ProcessingContext(
                session.id(), session.patientId(), session.encounterId(), session.revision() + 1,
                contractId, attempt, session.purposeKey(), session.sessionType(),
                release.providerKey(), release.modelKey(), release.modelVersion(),
                release.promptKey(), release.promptVersion(), release.outputSchemaKey(),
                release.outputSchemaVersion(), release.retentionDays(), command.now(), parameters, inputs);
    }

    @Override
    public MutationResult completeProcessing(
            AuthorizedTenantContext context,
            ProcessingContext processing,
            AiProcessingPort.Result supplied,
            Instant completedAt) {
        requireOperationScope(context);
        var result = validateResult(processing, supplied, completedAt);
        var outputDigest = digest(String.join(
                "|", result.content(), result.uncertaintyLabel(), processing.outputSchemaKey(),
                Integer.toString(processing.outputSchemaVersion())));
        var attemptId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO ai_job_attempts(
                    id,organization_id,job_contract_id,attempt_number,provider_request_reference,
                    status,started_at,completed_at,latency_millis,result_digest,retryable,
                    created_by,updated_by)
                VALUES (?,?,?,?,?,'succeeded',?,?,?,?,false,?,?)
                """,
                attemptId,
                context.organizationId(),
                processing.jobContractId(),
                processing.attemptNumber(),
                result.providerRequestReference(),
                Timestamp.from(processing.startedAt()),
                Timestamp.from(completedAt),
                result.latencyMillis(),
                outputDigest,
                context.actorId(),
                context.actorId());
        var outputId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO ai_outputs(
                    id,organization_id,ai_session_id,job_attempt_id,output_schema_key,
                    output_schema_version,provider_output_digest,uncertainty_label,status,
                    created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,'draft',?,?)
                """,
                outputId,
                context.organizationId(),
                processing.aiSessionId(),
                attemptId,
                processing.outputSchemaKey(),
                processing.outputSchemaVersion(),
                outputDigest,
                result.uncertaintyLabel(),
                context.actorId(),
                context.actorId());
        var versionId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO ai_output_versions(
                    id,organization_id,ai_output_id,version_number,content_type,content,
                    content_digest,authored_by_type,created_by,updated_by)
                VALUES (?,?,?,1,?,?,?,'provider',?,?)
                """,
                versionId,
                context.organizationId(),
                outputId,
                processing.sessionType(),
                result.content(),
                digest(result.content()),
                context.actorId(),
                context.actorId());
        var allowedItems = processing.inputs().stream()
                .map(AiProcessingPort.InputReference::manifestItemId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var citationSequence = 0;
        for (var citation : result.citations()) {
            if (!allowedItems.contains(citation.manifestItemId())) {
                throw invalid("The AI provider returned a citation outside the approved input manifest.");
            }
            jdbc.update(
                    """
                    INSERT INTO ai_output_citations(
                        id,organization_id,ai_output_version_id,input_manifest_item_id,
                        citation_sequence,source_locator,claim_digest,created_by,updated_by)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,
                    UuidV7Generator.randomUuid(),
                    context.organizationId(),
                    versionId,
                    citation.manifestItemId(),
                    ++citationSequence,
                    bounded(citation.sourceLocator(), 1, 240, "citation source locator"),
                    digest(bounded(citation.claim(), 1, 2000, "citation claim")),
                    context.actorId(),
                    context.actorId());
        }
        for (var flag : result.safetyFlags()) {
            var flagId = UuidV7Generator.randomUuid();
            var severity = oneOf(flag.severity(), "flag severity",
                    "information", "warning", "critical", "emergency");
            jdbc.update(
                    """
                    INSERT INTO ai_safety_flags(
                        id,organization_id,ai_output_id,flag_key,severity,summary,state,
                        created_by,updated_by)
                    VALUES (?,?,?,?,?,?,'open',?,?)
                    """,
                    flagId,
                    context.organizationId(),
                    outputId,
                    code(flag.flagKey(), "flag key", 2, 80),
                    severity,
                    bounded(flag.summary(), 2, 1000, "flag summary"),
                    context.actorId(),
                    context.actorId());
            if (Set.of("critical", "emergency").contains(severity)) {
                if (flag.ownerPractitionerId() == null
                        || flag.escalationDueAt() == null
                        || flag.escalationDueAt().isBefore(completedAt)
                        || flag.escalationDueAt().isAfter(completedAt.plus(24, ChronoUnit.HOURS))) {
                    throw invalid("Critical AI safety flags require an owner and escalation due within 24 hours.");
                }
                jdbc.update(
                        """
                        INSERT INTO ai_safety_escalations(
                            id,organization_id,ai_safety_flag_id,owner_practitioner_id,
                            escalation_level,due_at,delivery_status,reason,created_by,updated_by)
                        VALUES (?,?,?,?,? ,?,'not_dispatched',?,?,?)
                        """,
                        UuidV7Generator.randomUuid(),
                        context.organizationId(),
                        flagId,
                        flag.ownerPractitionerId(),
                        severity.equals("emergency") ? "emergency_pathway" : "clinical_owner",
                        Timestamp.from(flag.escalationDueAt()),
                        "Route the provider safety flag to accountable clinical review.",
                        context.actorId(),
                        context.actorId());
            }
        }
        jdbc.update(
                """
                INSERT INTO ai_usage_records(
                    id,organization_id,job_attempt_id,input_tokens,output_tokens,
                    cost_minor_units,currency,measured_at,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                UuidV7Generator.randomUuid(), context.organizationId(), attemptId,
                result.inputTokens(), result.outputTokens(), result.costMinorUnits(),
                result.currency(), Timestamp.from(completedAt), context.actorId(), context.actorId());
        var retainUntil = completedAt.plus(processing.retentionDays(), ChronoUnit.DAYS);
        jdbc.update(
                """
                INSERT INTO ai_retention_metadata(
                    id,organization_id,ai_session_id,policy_key,retain_until,
                    provider_deletion_due_at,training_use_prohibited,recorded_at,
                    created_by,updated_by)
                VALUES (?,?,?,'approved_model_release',?,?,true,?,?,?)
                """,
                UuidV7Generator.randomUuid(),
                context.organizationId(),
                processing.aiSessionId(),
                Timestamp.from(retainUntil),
                result.providerDeletionDueAt() == null
                        ? null
                        : Timestamp.from(result.providerDeletionDueAt()),
                Timestamp.from(completedAt),
                context.actorId(),
                context.actorId());
        var changed = jdbc.update(
                """
                UPDATE ai_sessions SET status='draft_ready',current_output_id=?,failure_code=NULL,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='processing'
                """,
                outputId,
                context.actorId(),
                context.organizationId(),
                processing.aiSessionId(),
                processing.sessionRevision());
        requireChanged(changed, "The AI session changed before the provider result was recorded.");
        var revision = processing.sessionRevision() + 1;
        var audit = map(
                "aiSessionId", processing.aiSessionId(), "jobContractId", processing.jobContractId(),
                "jobAttemptId", attemptId, "aiOutputId", outputId,
                "attemptNumber", processing.attemptNumber(), "status", "draft_ready", "revision", revision);
        var outbox = map(
                "aiSessionId", processing.aiSessionId(), "jobContractId", processing.jobContractId(),
                "aiOutputId", outputId, "status", "draft_ready");
        return result(
                processing.jobContractId(), processing.aiSessionId(), "ai_job_contract",
                "ai.processing.recorded", "m8.ai-processing-state-changed.v1", "ai_session",
                processing.aiSessionId(), audit, outbox, 200, revision);
    }

    @Override
    public MutationResult failProcessing(
            AuthorizedTenantContext context,
            ProcessingContext processing,
            String failureCode,
            Instant completedAt) {
        requireOperationScope(context);
        var code = code(failureCode, "failure code", 2, 80);
        var attemptId = UuidV7Generator.randomUuid();
        var latency = Math.max(0, completedAt.toEpochMilli() - processing.startedAt().toEpochMilli());
        jdbc.update(
                """
                INSERT INTO ai_job_attempts(
                    id,organization_id,job_contract_id,attempt_number,status,started_at,
                    completed_at,latency_millis,failure_code,retryable,created_by,updated_by)
                VALUES (?,?,?,?,'failed',?,?,?,?,true,?,?)
                """,
                attemptId, context.organizationId(), processing.jobContractId(),
                processing.attemptNumber(), Timestamp.from(processing.startedAt()),
                Timestamp.from(completedAt), latency, code, context.actorId(), context.actorId());
        var changed = jdbc.update(
                """
                UPDATE ai_sessions SET status='failed',failure_code=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='processing'
                """,
                code, context.actorId(), context.organizationId(), processing.aiSessionId(),
                processing.sessionRevision());
        requireChanged(changed, "The AI session changed before the provider failure was recorded.");
        var revision = processing.sessionRevision() + 1;
        var audit = map(
                "aiSessionId", processing.aiSessionId(), "jobContractId", processing.jobContractId(),
                "jobAttemptId", attemptId, "attemptNumber", processing.attemptNumber(),
                "status", "failed", "failureCode", code, "revision", revision);
        var outbox = map(
                "aiSessionId", processing.aiSessionId(), "jobContractId", processing.jobContractId(),
                "status", "failed", "failureCode", code);
        return result(
                processing.jobContractId(), processing.aiSessionId(), "ai_job_contract",
                "ai.processing.recorded", "m8.ai-processing-state-changed.v1", "ai_session",
                processing.aiSessionId(), audit, outbox, 200, revision);
    }

    private MutationResult launch(AuthorizedTenantContext context, MutationCommand command) {
        var patientId = fieldUuid(command, "patientId");
        var encounterId = fieldUuid(command, "encounterId");
        var sessionType = oneOf(field(command, "sessionType"), "sessionType",
                SESSION_TYPES.toArray(String[]::new));
        var sessionId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO ai_sessions(
                    id,organization_id,patient_id,encounter_id,requester_id,session_type,
                    status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,'draft',?,?)
                """,
                sessionId, context.organizationId(), patientId, encounterId, context.actorId(),
                sessionType, context.actorId(), context.actorId());
        var payload = map(
                "aiSessionId", sessionId, "patientId", patientId, "encounterId", encounterId,
                "sessionType", sessionType, "status", "draft");
        return result(
                sessionId, sessionId, "ai_session", "ai.session.launched",
                "m8.ai-session-launched.v1", "ai_session", sessionId,
                payload, payload, 201, 0);
    }

    private MutationResult recordPurpose(AuthorizedTenantContext context, MutationCommand command) {
        var session = lockSession(context, command.targetId());
        requireRevision(session.revision(), command.expectedRevision());
        if (!session.status().equals("draft")) throw conflict("The AI session purpose is already fixed.");
        var purpose = oneOf(field(command, "purposeKey"), "purposeKey",
                "clinical_documentation", "clinical_review", "care_coordination");
        var legalBasis = source(field(command, "legalBasisKey"), "legalBasisKey", 2, 80);
        var consentStatus = oneOf(field(command, "consentStatus"), "consentStatus",
                "granted", "not_required");
        var consentReference = optional(command.fields().get("consentReference"), 240, "consentReference");
        if (consentStatus.equals("granted") && consentReference == null) {
            throw invalid("consentReference is required when consent is granted.");
        }
        if (!"true".equals(field(command, "minimumNecessaryConfirmed"))) {
            throw invalid("Minimum-necessary confirmation is required.");
        }
        var consentId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO ai_purpose_consents(
                    id,organization_id,ai_session_id,purpose_key,legal_basis_key,
                    consent_status,consent_reference,minimum_necessary_confirmed,
                    recorded_at,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,true,clock_timestamp(),?,?,?)
                """,
                consentId, context.organizationId(), session.id(), purpose, legalBasis,
                consentStatus, consentReference, command.reason(), context.actorId(), context.actorId());
        var changed = jdbc.update(
                """
                UPDATE ai_sessions SET purpose_key=?,status='authorized',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='draft'
                """,
                purpose, context.actorId(), context.organizationId(), session.id(), session.revision());
        requireChanged(changed, "The AI session changed before purpose evidence was bound.");
        var revision = session.revision() + 1;
        var audit = map(
                "aiSessionId", session.id(), "purposeConsentId", consentId,
                "purposeKey", purpose, "legalBasisKey", legalBasis,
                "consentStatus", consentStatus, "status", "authorized", "revision", revision);
        return result(
                consentId, session.id(), "ai_purpose_consent", "ai.purpose_consent.recorded",
                null, null, null, audit, Map.of(), 200, revision);
    }

    private MutationResult selectInput(AuthorizedTenantContext context, MutationCommand command) {
        var session = lockSession(context, command.targetId());
        requireRevision(session.revision(), command.expectedRevision());
        if (!session.status().equals("authorized")) {
            throw conflict("Purpose and consent must be authorized before selecting AI inputs.");
        }
        var sourceType = oneOf(field(command, "sourceType"), "sourceType",
                SOURCE_TYPES.toArray(String[]::new));
        var sourceId = fieldUuid(command, "sourceId");
        var sourceRevision = longValue(field(command, "sourceRevision"), "sourceRevision", 0, Long.MAX_VALUE);
        var sourceDigest = sha256(field(command, "sourceDigest"), "sourceDigest");
        var categories = categories(field(command, "dataCategories"));
        var selectionReason = bounded(field(command, "selectionReason"), 10, 500, "selectionReason");
        var manifestId = UuidV7Generator.randomUuid();
        var itemId = UuidV7Generator.randomUuid();
        var manifestDigest = digest(String.join(
                "|", sourceType, sourceId.toString(), Long.toString(sourceRevision),
                sourceDigest, String.join(",", categories), selectionReason));
        jdbc.update(
                """
                INSERT INTO ai_input_manifests(
                    id,organization_id,ai_session_id,manifest_version,manifest_digest,item_count,
                    approved_at,reason,created_by,updated_by)
                VALUES (?,?,?,1,?,1,clock_timestamp(),?,?,?)
                """,
                manifestId, context.organizationId(), session.id(), manifestDigest,
                command.reason(), context.actorId(), context.actorId());
        jdbc.update(
                """
                INSERT INTO ai_input_manifest_items(
                    id,organization_id,input_manifest_id,item_sequence,source_type,source_id,
                    source_revision,source_digest,selection_reason,data_categories,
                    created_by,updated_by)
                VALUES (?,?,?,1,?,?,?,?,?,string_to_array(?,','),?,?)
                """,
                itemId, context.organizationId(), manifestId, sourceType, sourceId,
                sourceRevision, sourceDigest, selectionReason, String.join(",", categories),
                context.actorId(), context.actorId());
        var changed = jdbc.update(
                """
                UPDATE ai_sessions SET current_manifest_id=?,status='input_ready',
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='authorized'
                """,
                manifestId, context.actorId(), context.organizationId(), session.id(), session.revision());
        requireChanged(changed, "The AI session changed before the input manifest was approved.");
        var revision = session.revision() + 1;
        var audit = map(
                "aiSessionId", session.id(), "inputManifestId", manifestId,
                "manifestVersion", 1, "itemCount", 1, "sourceType", sourceType,
                "digest", manifestDigest, "status", "input_ready", "revision", revision);
        var outbox = map(
                "aiSessionId", session.id(), "inputManifestId", manifestId,
                "itemCount", 1, "status", "input_ready");
        return result(
                manifestId, session.id(), "ai_input_manifest", "ai.input_manifest.approved",
                "m8.ai-input-ready.v1", "ai_session", session.id(),
                audit, outbox, 201, revision);
    }

    private MutationResult editOutput(AuthorizedTenantContext context, MutationCommand command) {
        var session = lockSession(context, command.targetId());
        requireRevision(session.revision(), command.expectedRevision());
        if (!Set.of("draft_ready", "under_review").contains(session.status())
                || session.outputId() == null) {
            throw conflict("The AI session has no editable draft output.");
        }
        var content = bounded(field(command, "content"), 1, 20000, "content");
        var editSummary = bounded(field(command, "editSummary"), 10, 500, "editSummary");
        var latest = latestVersion(context, session.outputId());
        var versionId = UuidV7Generator.randomUuid();
        var version = latest.versionNumber() + 1;
        var contentDigest = digest(content);
        jdbc.update(
                """
                INSERT INTO ai_output_versions(
                    id,organization_id,ai_output_id,version_number,prior_version_id,
                    content_type,content,content_digest,authored_by_type,edit_summary,
                    created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?, 'clinician',?,?,?)
                """,
                versionId, context.organizationId(), session.outputId(), version, latest.id(),
                session.sessionType(), content, contentDigest, editSummary,
                context.actorId(), context.actorId());
        jdbc.update(
                """
                INSERT INTO ai_output_citations(
                    id,organization_id,ai_output_version_id,input_manifest_item_id,
                    citation_sequence,source_locator,claim_digest,created_by,updated_by)
                SELECT uuidv7(),organization_id,?,input_manifest_item_id,citation_sequence,
                       source_locator,claim_digest,?,?
                FROM ai_output_citations
                WHERE organization_id=? AND ai_output_version_id=?
                ORDER BY citation_sequence
                """,
                versionId,
                context.actorId(),
                context.actorId(),
                context.organizationId(),
                latest.id());
        var changed = jdbc.update(
                """
                UPDATE ai_sessions SET status='under_review',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                  AND status IN ('draft_ready','under_review')
                """,
                context.actorId(), context.organizationId(), session.id(), session.revision());
        requireChanged(changed, "The AI session changed before the draft edit was appended.");
        var revision = session.revision() + 1;
        var audit = map(
                "aiSessionId", session.id(), "aiOutputId", session.outputId(),
                "aiOutputVersionId", versionId, "versionNumber", version,
                "digest", contentDigest, "status", "under_review", "revision", revision);
        return result(
                versionId, session.id(), "ai_output_version", "ai.output.edited",
                null, null, null, audit, Map.of(), 201, revision);
    }

    private MutationResult reviewSafety(AuthorizedTenantContext context, MutationCommand command) {
        var session = lockSession(context, command.targetId());
        requireRevision(session.revision(), command.expectedRevision());
        if (session.outputId() == null) throw conflict("The AI session has no safety evidence.");
        var flagId = fieldUuid(command, "safetyFlagId");
        var flags = jdbc.query(
                """
                SELECT id,state,lock_version FROM ai_safety_flags
                WHERE organization_id=? AND id=? AND ai_output_id=? FOR UPDATE
                """,
                (resultSet, rowNumber) -> new FlagRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("state"),
                        resultSet.getLong("lock_version")),
                context.organizationId(), flagId, session.outputId());
        if (flags.isEmpty()) throw notFound("The AI safety flag is unavailable.");
        var flag = flags.getFirst();
        var acknowledge = command.actionKey().equals("acknowledge-safety-flag");
        var expected = acknowledge ? "open" : "acknowledged";
        var next = acknowledge ? "acknowledged" : "resolved";
        if (!flag.state().equals(expected)) throw conflict("The AI safety flag is not in the required state.");
        var changed = acknowledge
                ? jdbc.update(
                        """
                        UPDATE ai_safety_flags SET state='acknowledged',acknowledged_at=clock_timestamp(),
                            lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                        WHERE organization_id=? AND id=? AND lock_version=? AND state='open'
                        """,
                        context.actorId(), context.organizationId(), flag.id(), flag.revision())
                : jdbc.update(
                        """
                        UPDATE ai_safety_flags SET state='resolved',resolved_at=clock_timestamp(),
                            lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                        WHERE organization_id=? AND id=? AND lock_version=? AND state='acknowledged'
                        """,
                        context.actorId(), context.organizationId(), flag.id(), flag.revision());
        requireChanged(changed, "The AI safety flag changed before review completed.");
        var revision = flag.revision() + 1;
        var audit = map(
                "aiSessionId", session.id(), "aiOutputId", session.outputId(),
                "safetyFlagId", flag.id(), "fromState", flag.state(), "toState", next,
                "revision", revision);
        var outbox = map("aiSessionId", session.id(), "safetyFlagId", flag.id(), "state", next);
        return result(
                flag.id(), session.id(), "ai_safety_flag", "ai.safety_flag.reviewed",
                "m8.ai-safety-state-changed.v1", "ai_safety_flag", flag.id(),
                audit, outbox, 200, session.revision());
    }

    private MutationResult decide(AuthorizedTenantContext context, MutationCommand command) {
        var session = lockSession(context, command.targetId());
        requireRevision(session.revision(), command.expectedRevision());
        if (!Set.of("draft_ready", "under_review").contains(session.status())
                || session.outputId() == null) {
            throw conflict("The AI session has no reviewable draft output.");
        }
        if (command.recentAuthenticationAt() == null || command.mfaAuthenticatedAt() == null) {
            throw conflict("Recent authenticated MFA is required for an AI output decision.");
        }
        var decision = oneOf(field(command, "decision"), "decision", "accepted", "rejected");
        var reviewer = fieldUuid(command, "reviewerPractitionerId");
        var latest = latestVersion(context, session.outputId());
        var reviewId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO ai_reviews(
                    id,organization_id,ai_session_id,ai_output_id,ai_output_version_id,
                    reviewer_practitioner_id,decision,reason,reviewed_at,recent_authentication_at,
                    mfa_authenticated_at,final_version_digest,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,clock_timestamp(),?,?,?,?,?)
                """,
                reviewId, context.organizationId(), session.id(), session.outputId(), latest.id(),
                reviewer, decision, command.reason(), Timestamp.from(command.recentAuthenticationAt()),
                Timestamp.from(command.mfaAuthenticatedAt()), latest.digest(),
                context.actorId(), context.actorId());
        var changed = jdbc.update(
                """
                UPDATE ai_sessions SET status=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                  AND status IN ('draft_ready','under_review')
                """,
                decision, context.actorId(), context.organizationId(), session.id(), session.revision());
        requireChanged(changed, "The AI session changed before the clinician decision was recorded.");
        var revision = session.revision() + 1;
        var audit = map(
                "aiSessionId", session.id(), "aiOutputId", session.outputId(),
                "aiOutputVersionId", latest.id(), "aiReviewId", reviewId,
                "decision", decision, "digest", latest.digest(), "status", decision,
                "revision", revision);
        var outbox = map(
                "aiSessionId", session.id(), "aiOutputId", session.outputId(),
                "decision", decision, "status", decision);
        return result(
                reviewId, session.id(), "ai_review", "ai.output.decided",
                "m8.ai-output-decided.v1", "ai_session", session.id(),
                audit, outbox, 200, revision);
    }

    private MutationResult cancel(AuthorizedTenantContext context, MutationCommand command) {
        var session = lockSession(context, command.targetId());
        requireRevision(session.revision(), command.expectedRevision());
        if (TERMINAL.contains(session.status())) throw conflict("The AI session is already terminal.");
        var changed = jdbc.update(
                """
                UPDATE ai_sessions SET status='cancelled',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                context.actorId(), context.organizationId(), session.id(), session.revision());
        requireChanged(changed, "The AI session changed before cancellation.");
        var revision = session.revision() + 1;
        var audit = map(
                "aiSessionId", session.id(), "fromStatus", session.status(),
                "status", "cancelled", "revision", revision);
        return result(
                session.id(), session.id(), "ai_session", "ai.session.cancelled",
                null, null, null, audit, Map.of(), 200, revision);
    }

    private List<AiScreen.Row> sessionRows(AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT session.id,session.patient_id,session.encounter_id,
                       session.current_manifest_id,session.current_output_id,session.session_type,
                       session.purpose_key,session.status,session.failure_code,session.lock_version,
                       patient.patient_number,
                       latest_version.id output_version_id,latest_version.version_number,
                       latest_version.content,latest_version.authored_by_type,
                       output.uncertainty_label,
                       flag.id safety_flag_id,flag.severity safety_severity,flag.state safety_state,
                       review.decision,
                       model.provider_key,model.model_key,model.model_version,
                       prompt.prompt_key,prompt.prompt_version,
                       coalesce((SELECT count(*) FROM ai_output_citations citation
                           WHERE citation.organization_id=session.organization_id
                             AND citation.ai_output_version_id=latest_version.id),0) citation_count,
                       usage.input_tokens,usage.output_tokens,usage.cost_minor_units,usage.currency
                FROM ai_sessions session
                JOIN patient_profiles patient ON patient.organization_id=session.organization_id
                                             AND patient.id=session.patient_id
                LEFT JOIN ai_outputs output ON output.organization_id=session.organization_id
                                           AND output.id=session.current_output_id
                LEFT JOIN LATERAL (
                    SELECT version.id,version.version_number,version.content,version.authored_by_type
                    FROM ai_output_versions version
                    WHERE version.organization_id=session.organization_id
                      AND version.ai_output_id=output.id
                    ORDER BY version.version_number DESC LIMIT 1
                ) latest_version ON true
                LEFT JOIN LATERAL (
                    SELECT candidate.id,candidate.severity,candidate.state
                    FROM ai_safety_flags candidate
                    WHERE candidate.organization_id=session.organization_id
                      AND candidate.ai_output_id=output.id
                    ORDER BY CASE candidate.severity WHEN 'emergency' THEN 1 WHEN 'critical' THEN 2
                              WHEN 'warning' THEN 3 ELSE 4 END,candidate.created_at
                    LIMIT 1
                ) flag ON true
                LEFT JOIN ai_reviews review ON review.organization_id=session.organization_id
                                           AND review.ai_session_id=session.id
                LEFT JOIN ai_job_attempts attempt ON attempt.organization_id=output.organization_id
                                                 AND attempt.id=output.job_attempt_id
                LEFT JOIN ai_job_contracts contract ON contract.organization_id=attempt.organization_id
                                                   AND contract.id=attempt.job_contract_id
                LEFT JOIN ai_model_releases model ON model.organization_id=contract.organization_id
                                                 AND model.id=contract.model_release_id
                LEFT JOIN ai_prompt_releases prompt ON prompt.organization_id=contract.organization_id
                                                   AND prompt.id=contract.prompt_release_id
                LEFT JOIN ai_usage_records usage ON usage.organization_id=attempt.organization_id
                                                AND usage.job_attempt_id=attempt.id
                WHERE session.organization_id=?
                  AND (?::uuid IS NULL OR session.patient_id=?::uuid)
                  AND (?::uuid IS NULL OR session.encounter_id=?::uuid)
                  AND (?::uuid IS NULL OR session.id=?::uuid)
                  AND (?::text IS NULL OR session.status=?::text)
                  AND (?::text IS NULL OR patient.patient_number ILIKE '%'||?::text||'%'
                       OR session.id::text ILIKE '%'||?::text||'%')
                ORDER BY session.updated_at DESC,session.id DESC
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("current_manifest_id", UUID.class),
                        resultSet.getObject("current_output_id", UUID.class),
                        resultSet.getObject("output_version_id", UUID.class),
                        resultSet.getObject("safety_flag_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", "ai_session",
                                "patient", maskPatientNumber(resultSet.getString("patient_number")),
                                "sessionType", resultSet.getString("session_type"),
                                "purpose", safe(resultSet.getString("purpose_key"), "Purpose not authorized"),
                                "draftLabel", resultSet.getObject("current_output_id") == null
                                        ? "No provider draft" : "AI-generated draft — clinician review required",
                                "draft", safe(resultSet.getString("content"), "No draft output recorded"),
                                "author", safe(resultSet.getString("authored_by_type"), "Not recorded"),
                                "version", string(resultSet.getObject("version_number")),
                                "uncertainty", safe(resultSet.getString("uncertainty_label"), "Not recorded"),
                                "safety", safe(resultSet.getString("safety_severity"), "No provider flag"),
                                "safetyState", safe(resultSet.getString("safety_state"), "Not applicable"),
                                "provider", safe(resultSet.getString("provider_key"), "Provider not invoked"),
                                "model", joinVersion(resultSet.getString("model_key"), resultSet.getString("model_version")),
                                "prompt", joinVersion(resultSet.getString("prompt_key"), resultSet.getString("prompt_version")),
                                "citations", Long.toString(resultSet.getLong("citation_count")),
                                "decision", safe(resultSet.getString("decision"), "No decision"),
                                "failure", safe(resultSet.getString("failure_code"), "None"),
                                "usage", usage(resultSet.getObject("input_tokens"), resultSet.getObject("output_tokens"),
                                        resultSet.getObject("cost_minor_units"), resultSet.getString("currency")))),
                context.organizationId(),
                query.patientId(), query.patientId(),
                query.encounterId(), query.encounterId(),
                query.aiSessionId(), query.aiSessionId(),
                query.status(), query.status(),
                query.search(), query.search(), query.search());
    }

    private List<AiScreen.Metric> metrics(AuthorizedTenantContext context) {
        var values = jdbc.queryForMap(
                """
                SELECT (SELECT count(*) FROM ai_sessions WHERE organization_id=?) sessions,
                       (SELECT count(*) FROM ai_sessions WHERE organization_id=? AND status='draft_ready') draft_ready,
                       (SELECT count(*) FROM ai_sessions WHERE organization_id=? AND status='accepted') accepted,
                       (SELECT count(*) FROM ai_safety_flags WHERE organization_id=? AND state<>'resolved') open_flags,
                       (SELECT count(*) FROM ai_sessions WHERE organization_id=? AND status='failed') failed
                """,
                context.organizationId(), context.organizationId(), context.organizationId(),
                context.organizationId(), context.organizationId());
        return List.of(
                metric("sessions", "AI sessions", number(values.get("sessions")), "neutral"),
                metric("draftReady", "Drafts awaiting review", number(values.get("draft_ready")), "warning"),
                metric("accepted", "Clinician accepted", number(values.get("accepted")), "success"),
                metric("openFlags", "Open safety flags", number(values.get("open_flags")), "danger"),
                metric("failed", "Failed attempts", number(values.get("failed")), "warning"));
    }

    private static List<AiScreen.Column> columns(String screenId) {
        return switch (screenId) {
            case "P8-04" -> List.of(column("patient", "Patient"), column("sessionType", "Task"),
                    column("provider", "Provider"), column("failure", "Failure"));
            case "P8-05", "P8-06" -> List.of(column("patient", "Patient"),
                    column("draftLabel", "Boundary"), column("draft", "Draft"),
                    column("uncertainty", "Uncertainty"));
            case "P8-07" -> List.of(column("patient", "Patient"), column("safety", "Severity"),
                    column("safetyState", "State"), column("uncertainty", "Uncertainty"));
            case "P8-08" -> List.of(column("patient", "Patient"), column("model", "Model release"),
                    column("prompt", "Prompt release"), column("citations", "Citations"));
            case "P8-09" -> List.of(column("patient", "Patient"), column("draftLabel", "Boundary"),
                    column("version", "Version"), column("decision", "Decision"));
            default -> List.of(column("patient", "Patient"), column("sessionType", "Task"),
                    column("purpose", "Purpose"), column("decision", "Decision"));
        };
    }

    private static List<AiScreen.Notice> notices(String screenId) {
        var notices = new ArrayList<AiScreen.Notice>();
        notices.add(notice(
                "warning", "AI output is always a draft",
                "No provider output changes a clinical source of truth until an eligible clinician explicitly reviews and adopts it."));
        notices.add(notice(
                "neutral", "Provider activation is separate",
                "The Python processing adapter, model, prompt, consent, retention and safety policies remain fail closed until approved."));
        if (Set.of("P8-06", "P8-07", "P8-09").contains(screenId)) {
            notices.add(notice(
                    "danger", "Emergency handling",
                    "Critical or emergency flags require accountable escalation and resolution; generative reassurance cannot close them."));
        }
        return List.copyOf(notices);
    }

    private static AiScreen.Row withAllowedActions(String screenId, AiScreen.Row row) {
        var actions = new ArrayList<String>();
        switch (screenId) {
            case "P8-02" -> { if (row.status().equals("draft")) actions.add("record-purpose-consent"); }
            case "P8-03" -> { if (row.status().equals("authorized")) actions.add("select-input"); }
            case "P8-04" -> {
                if (Set.of("input_ready", "failed").contains(row.status())) {
                    actions.add("request-processing");
                }
            }
            case "P8-05" -> {
                if (Set.of("draft_ready", "under_review").contains(row.status()) && row.outputId() != null) {
                    actions.add("edit-output");
                }
            }
            case "P8-07" -> {
                if (row.safetyFlagId() != null && row.values().get("safetyState").equals("open")) {
                    actions.add("acknowledge-safety-flag");
                } else if (row.safetyFlagId() != null
                        && row.values().get("safetyState").equals("acknowledged")) {
                    actions.add("resolve-safety-flag");
                }
            }
            case "P8-09" -> {
                if (Set.of("draft_ready", "under_review").contains(row.status()) && row.outputId() != null) {
                    actions.add("decide-output");
                }
            }
            case "P8-10" -> { if (!TERMINAL.contains(row.status())) actions.add("cancel-session"); }
            default -> { /* Read-only screen. */ }
        }
        return new AiScreen.Row(
                row.id(), row.patientId(), row.encounterId(), row.inputManifestId(),
                row.outputId(), row.outputVersionId(), row.safetyFlagId(), row.status(),
                row.revision(), row.etag(), row.values(), List.copyOf(actions));
    }

    private static AiScreen.Row row(
            String screenId,
            UUID id,
            UUID patientId,
            UUID encounterId,
            UUID manifestId,
            UUID outputId,
            UUID outputVersionId,
            UUID safetyFlagId,
            String status,
            long revision,
            Map<String, String> values) {
        return new AiScreen.Row(
                id, patientId, encounterId, manifestId, outputId, outputVersionId,
                safetyFlagId, status, revision,
                "\"m8:" + screenId + ":" + id + ":" + revision + "\"",
                values, List.of());
    }

    private SessionRecord lockSession(AuthorizedTenantContext context, UUID sessionId) {
        if (sessionId == null) throw invalid("An AI session target is required.");
        var rows = jdbc.query(
                """
                SELECT id,patient_id,encounter_id,session_type,purpose_key,status,
                       current_manifest_id,current_output_id,lock_version
                FROM ai_sessions WHERE organization_id=? AND id=? FOR UPDATE
                """,
                (resultSet, rowNumber) -> new SessionRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getString("session_type"),
                        resultSet.getString("purpose_key"),
                        resultSet.getString("status"),
                        resultSet.getObject("current_manifest_id", UUID.class),
                        resultSet.getObject("current_output_id", UUID.class),
                        resultSet.getLong("lock_version")),
                context.organizationId(), sessionId);
        if (rows.isEmpty()) throw notFound("The AI session is unavailable.");
        return rows.getFirst();
    }

    private OutputVersionRecord latestVersion(AuthorizedTenantContext context, UUID outputId) {
        var rows = jdbc.query(
                """
                SELECT id,version_number,content_digest FROM ai_output_versions
                WHERE organization_id=? AND ai_output_id=?
                ORDER BY version_number DESC LIMIT 1
                """,
                (resultSet, rowNumber) -> new OutputVersionRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getInt("version_number"),
                        resultSet.getString("content_digest")),
                context.organizationId(), outputId);
        if (rows.isEmpty()) throw conflict("The AI output has no immutable version.");
        return rows.getFirst();
    }

    private static AiProcessingPort.Result validateResult(
            ProcessingContext processing, AiProcessingPort.Result result, Instant completedAt) {
        if (result == null) throw invalid("The AI provider returned no result.");
        bounded(result.providerRequestReference(), 2, 200, "provider request reference");
        bounded(result.content(), 1, 20000, "provider output");
        oneOf(result.uncertaintyLabel(), "uncertainty", "low", "moderate", "high", "unknown");
        if (result.citations().isEmpty() || result.citations().size() > 128) {
            throw invalid("The AI provider must return 1 to 128 provenance citations.");
        }
        if (result.safetyFlags().size() > 16
                || result.inputTokens() < 0
                || result.outputTokens() < 0
                || result.costMinorUnits() < 0
                || result.latencyMillis() < 0
                || result.currency() == null
                || !result.currency().matches("[A-Z]{3}")) {
            throw invalid("The AI provider returned invalid safety or usage metadata.");
        }
        var retentionDeadline = completedAt.plus(processing.retentionDays(), ChronoUnit.DAYS);
        if (result.providerDeletionDueAt() == null
                || result.providerDeletionDueAt().isBefore(completedAt)
                || result.providerDeletionDueAt().isAfter(retentionDeadline)) {
            throw invalid("Provider deletion must be scheduled within the approved retention window.");
        }
        return result;
    }

    private void requireOperationScope(AuthorizedTenantContext context) {
        var bound = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT nullif(current_setting('app.current_organization_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_actor_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_operation_key',true),'') IS NOT NULL
                """,
                Boolean.class,
                context.organizationId(), context.actorId()));
        if (!bound) throw notFound("The AI resource is unavailable or is not assigned to this account.");
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .toInstant();
    }

    private static String field(MutationCommand command, String key) {
        var value = command.fields().get(key);
        if (value == null || value.isBlank()) throw invalid(key + " is required.");
        return value.strip();
    }

    private static UUID fieldUuid(MutationCommand command, String key) {
        try {
            return UUID.fromString(field(command, key));
        } catch (IllegalArgumentException exception) {
            throw invalid(key + " must be a UUID.");
        }
    }

    private static String bounded(String value, int minimum, int maximum, String field) {
        if (value == null) throw invalid(field + " is required.");
        var normalized = value.strip();
        var length = normalized.codePointCount(0, normalized.length());
        if (length < minimum || length > maximum) {
            throw invalid(field + " must contain " + minimum + " to " + maximum + " characters.");
        }
        return normalized;
    }

    private static String optional(String value, int maximum, String field) {
        if (value == null || value.isBlank()) return null;
        return bounded(value, 1, maximum, field);
    }

    private static String source(String value, String field, int minimum, int maximum) {
        var normalized = bounded(value, minimum, maximum, field);
        if (!normalized.matches("[a-z][a-z0-9_.:-]*")) throw invalid(field + " has an invalid format.");
        return normalized;
    }

    private static String code(String value, String field, int minimum, int maximum) {
        var normalized = bounded(value, minimum, maximum, field);
        if (!normalized.matches("[a-z][a-z0-9_]*")) throw invalid(field + " has an invalid format.");
        return normalized;
    }

    private static String sha256(String value, String field) {
        var normalized = value == null ? "" : value.strip().toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) throw invalid(field + " must be a lowercase SHA-256 digest.");
        return normalized;
    }

    private static long longValue(String value, String field, long minimum, long maximum) {
        try {
            var parsed = Long.parseLong(value);
            if (parsed < minimum || parsed > maximum) throw invalid(field + " is outside the allowed range.");
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid(field + " must be an integer.");
        }
    }

    private static List<String> categories(String value) {
        var categories = java.util.Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .peek(entry -> {
                    if (!entry.matches("[a-z][a-z0-9_]{1,79}")) {
                        throw invalid("dataCategories contains an invalid key.");
                    }
                })
                .distinct()
                .sorted()
                .toList();
        if (categories.isEmpty() || categories.size() > 16) {
            throw invalid("dataCategories must contain 1 to 16 comma-separated keys.");
        }
        return categories;
    }

    private static String oneOf(String value, String field, String... options) {
        for (var option : options) if (option.equals(value)) return value;
        throw invalid(field + " contains an unsupported value.");
    }

    private static void requireRevision(long actual, Long expected) {
        if (expected == null) throw new AiException(
                AiException.Reason.PRECONDITION_REQUIRED, "A strong AI session revision is required.");
        if (actual != expected) throw new AiException(
                AiException.Reason.STALE, "The AI session changed; reload before continuing.");
    }

    private static void requireChanged(int changed, String message) {
        if (changed != 1) throw conflict(message);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate AI evidence digest.", exception);
        }
    }

    private static String canonical(Map<String, String> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static Map<String, Object> map(Object... entries) {
        var values = new LinkedHashMap<String, Object>();
        for (var index = 0; index < entries.length; index += 2) {
            if (entries[index + 1] != null) values.put((String) entries[index], entries[index + 1]);
        }
        return Map.copyOf(values);
    }

    private static Map<String, String> values(String... entries) {
        var values = new LinkedHashMap<String, String>();
        for (var index = 0; index < entries.length; index += 2) {
            values.put(entries[index], safe(entries[index + 1], "Not recorded"));
        }
        return Map.copyOf(values);
    }

    private static MutationResult result(
            UUID subjectId,
            UUID sessionId,
            String subjectType,
            String auditEvent,
            String outboxEvent,
            String aggregateType,
            UUID outboxAggregateId,
            Map<String, Object> audit,
            Map<String, Object> outbox,
            int statusCode,
            long revision) {
        return new MutationResult(
                subjectId, sessionId, subjectType, auditEvent, outboxEvent, aggregateType,
                outboxAggregateId, audit, outbox, statusCode, revision);
    }

    private static AiScreen.Column column(String key, String label) {
        return new AiScreen.Column(key, label);
    }

    private static AiScreen.Metric metric(String key, String label, long value, String tone) {
        return new AiScreen.Metric(key, label, value, tone);
    }

    private static AiScreen.Notice notice(String tone, String title, String detail) {
        return new AiScreen.Notice(tone, title, detail);
    }

    private static String maskPatientNumber(String value) {
        if (value == null || value.length() <= 4) return "••••";
        return "••••" + value.substring(value.length() - 4);
    }

    private static String joinVersion(String key, String version) {
        return key == null || version == null ? "Not recorded" : key + " @ " + version;
    }

    private static String usage(Object input, Object output, Object cost, String currency) {
        if (!(input instanceof Number in) || !(output instanceof Number out)
                || !(cost instanceof Number amount) || currency == null) return "Not recorded";
        return in.longValue() + " in / " + out.longValue() + " out; "
                + amount.longValue() + " minor " + currency;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String string(Object value) {
        return value == null ? "Not recorded" : value.toString();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static AiException invalid(String message) {
        return new AiException(AiException.Reason.INVALID, message);
    }

    private static AiException notFound(String message) {
        return new AiException(AiException.Reason.NOT_FOUND, message);
    }

    private static AiException conflict(String message) {
        return new AiException(AiException.Reason.CONFLICT, message);
    }

    private record SessionRecord(
            UUID id,
            UUID patientId,
            UUID encounterId,
            String sessionType,
            String purposeKey,
            String status,
            UUID manifestId,
            UUID outputId,
            long revision) {}

    private record Release(
            UUID modelId,
            String providerKey,
            String modelKey,
            String modelVersion,
            int retentionDays,
            UUID promptId,
            String promptKey,
            String promptVersion,
            String outputSchemaKey,
            int outputSchemaVersion,
            UUID evaluationId) {}

    private record OutputVersionRecord(UUID id, int versionNumber, String digest) {}

    private record FlagRecord(UUID id, String state, long revision) {}
}
