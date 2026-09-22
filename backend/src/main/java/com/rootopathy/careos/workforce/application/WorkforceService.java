package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.GovernedMutation;
import com.rootopathy.careos.governance.domain.IdempotencyCommand;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.governance.domain.OutboxRecord;
import com.rootopathy.careos.platform.application.DocumentSecurityOperations;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import com.rootopathy.careos.workforce.domain.WorkforceScreen;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public final class WorkforceService {
    private static final String JSON = "application/json";
    private static final String PURPOSE = "workforce-administration";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");
    private static final Pattern ETAG = Pattern.compile(
            "\\\"m2:(M2-[0-9]{2}):([0-9a-fA-F-]{36}):([0-9]{1,19})\\\"");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> EVIDENCE_PURPOSES = Set.of(
            "workforce_operations",
            "credentialing_review",
            "regulatory_evidence",
            "security_investigation",
            "employment_record_request",
            "data_correction");
    private static final Set<String> IMPACT_ACTIONS = Set.of(
            "request-person-merge",
            "suspend-registration",
            "revoke-registration",
            "suspend-credential",
            "revoke-credential",
            "suspend-scope",
            "end-scope",
            "transfer-assignment",
            "suspend-assignment",
            "reactivate-assignment",
            "end-assignment",
            "suspend-member",
            "reactivate-member",
            "request-offboarding");

    private final TenantAuthorizationOperations authorization;
    private final GovernedMutationExecutor governedMutations;
    private final WorkforceStore store;
    private final WorkforceImpactTokenCodec impactTokens;
    private final WorkforceScreenCursorCodec screenCursors;
    private final DocumentSecurityOperations documents;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkforceService(
            TenantAuthorizationOperations authorization,
            GovernedMutationExecutor governedMutations,
            WorkforceStore store,
            WorkforceImpactTokenCodec impactTokens,
            WorkforceScreenCursorCodec screenCursors,
            DocumentSecurityOperations documents,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorization = authorization;
        this.governedMutations = governedMutations;
        this.store = store;
        this.impactTokens = impactTokens;
        this.screenCursors = screenCursors;
        this.documents = documents;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public WorkforceScreen screen(ReadCommand command) {
        Objects.requireNonNull(command, "command");
        var spec = WorkforceScreenCatalogue.screen(command.screenId());
        var query = pagedQuery(command);
        return authorization.execute(
                authorization(
                        command.organizationId(),
                        command.actorId(),
                        command.correlationId(),
                        spec.readOperation(),
                        null,
                        command.recentAuthenticationAt(),
                        command.mfaAuthenticatedAt()),
                context -> project(context, spec, query));
    }

    public IdempotencyOutcome act(ActionCommand command) {
        Objects.requireNonNull(command, "command");
        var spec = WorkforceScreenCatalogue.mutation(command.screenId(), command.actionKey());
        var key = requireIdempotencyKey(command.idempotencyKey());
        var reason = spec.reasonRequired() ? requireReason(command.reason()) : optionalReason(command.reason());
        var expectedRevision = spec.ifMatchRequired()
                ? requireRevision(command.ifMatch(), command.screenId(), command.targetId())
                : optionalRevision(command.ifMatch(), command.screenId(), command.targetId());
        if (spec.targetRequired() && command.targetId() == null) {
            throw invalid("targetId is required for this action.");
        }
        var fields = Map.copyOf(command.fields() == null ? Map.of() : command.fields());
        requireFields(spec, fields);
        var now = clock.instant();
        if (!IMPACT_ACTIONS.contains(command.actionKey())
                && command.impactToken()!=null && !command.impactToken().isBlank()) {
            throw invalid("This action does not accept an impact preview token.");
        }
        if (IMPACT_ACTIONS.contains(command.actionKey())) {
            if (command.targetId()==null || expectedRevision==null
                    || command.impactToken()==null || command.impactToken().isBlank()) {
                throw invalid("A fresh impact preview is required for this action.");
            }
            WorkforceImpactTokenCodec.Decoded preview;
            try {
                preview=impactTokens.decode(
                        command.impactToken(),
                        impactBinding(command,expectedRevision,reason,fields));
            } catch (IllegalArgumentException exception) {
                throw invalid(exception.getMessage());
            }
            var boundFields=new LinkedHashMap<>(fields);
            boundFields.put("_impactDigest",preview.impactDigest());
            boundFields.put("_impactExpiresAt",preview.expiresAt().toString());
            fields=Map.copyOf(boundFields);
        }
        var mutation = new WorkforceStore.MutationCommand(
                command.screenId(),
                command.actionKey(),
                command.targetId(),
                command.memberId(),
                expectedRevision,
                command.decision(),
                reason,
                fields,
                command.evidenceIds(),
                now);
        var idempotency = new IdempotencyCommand(
                spec.operation(),
                key,
                hash(
                        spec.operation(),
                        command.screenId(),
                        command.actionKey(),
                        command.targetId(),
                        command.memberId(),
                        expectedRevision,
                        command.decision(),
                        reason,
                        canonicalFields(fields),
                        command.evidenceIds()),
                now.plus(24, ChronoUnit.HOURS));

        return governedMutations.execute(
                authorization(
                        command.organizationId(),
                        command.actorId(),
                        command.correlationId(),
                        spec.operation(),
                        reason,
                        command.recentAuthenticationAt(),
                        command.mfaAuthenticatedAt()),
                idempotency,
                context -> {
                    var result = store.mutate(context, mutation);
                    var memberId = command.memberId();
                    if ((memberId == null || command.actionKey().equals("record-match-decision"))
                            && "workforce_member".equals(result.subjectType())) {
                        memberId = result.subjectId();
                    }
                    var response = project(
                            context,
                            WorkforceScreenCatalogue.screen(command.screenId()),
                            new WorkforceStore.ScreenQuery(
                                    command.screenId(), memberId, null, null, 50));
                    return governed(result, reason, response);
                });
    }

    public ImpactPreviewResponse previewImpact(ImpactPreviewCommand command) {
        Objects.requireNonNull(command,"command");
        if (!IMPACT_ACTIONS.contains(command.actionKey())) {
            throw invalid("This action does not define an impact preview.");
        }
        var spec=WorkforceScreenCatalogue.mutation(command.screenId(),command.actionKey());
        if (command.targetId()==null) {
            throw invalid("targetId is required for an impact preview.");
        }
        var revision=requireRevision(command.ifMatch(),command.screenId(),command.targetId());
        var fields=Map.copyOf(command.fields()==null?Map.of():command.fields());
        requireFields(spec,fields);
        var reason=spec.reasonRequired()?requireReason(command.reason()):optionalReason(command.reason());
        var now=clock.instant();
        return authorization.execute(
                authorization(
                        command.organizationId(),command.actorId(),command.correlationId(),
                        spec.operation(),reason,command.recentAuthenticationAt(),
                        command.mfaAuthenticatedAt()),
                context -> {
                    var analysis=store.previewImpact(
                            context,
                            new WorkforceStore.MutationCommand(
                                    command.screenId(),command.actionKey(),command.targetId(),
                                    command.memberId(),revision,command.decision(),reason,fields,
                                    command.evidenceIds(),now));
                    var expiresAt=now.plusSeconds(600);
                    var token=impactTokens.encode(
                            impactBinding(command,revision,reason,fields),analysis.digest(),expiresAt);
                    return new ImpactPreviewResponse(
                            command.screenId(),command.actionKey(),command.targetId(),revision,
                            analysis.digest(),token,expiresAt,analysis.blocked(),analysis.items());
                });
    }

    public IdempotencyOutcome uploadCredentialDocument(DocumentUploadCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.content(), "content");
        var key = requireIdempotencyKey(command.idempotencyKey());
        var reason = requireReason(command.reason());
        var fileName = requireSafeFileName(command.fileName());
        var mediaType = requireMediaType(command.mediaType());
        var digest = command.sha256() == null ? "" : command.sha256().strip().toLowerCase();
        if (!SHA256.matcher(digest).matches()) {
            throw invalid("sha256 must contain a lowercase SHA-256 digest.");
        }
        if (command.declaredSize() < 1 || command.declaredSize() > 26_214_400L) {
            throw invalid("declaredSize must be between 1 byte and 25 MiB.");
        }
        var now = clock.instant();
        var documentId = UuidV7Generator.randomUuid();
        var objectVersionId = UuidV7Generator.randomUuid();
        var storeCommand = new WorkforceStore.DocumentCommand(
                Objects.requireNonNull(command.credentialId(), "credentialId"),
                command.memberId(),
                fileName,
                mediaType,
                command.declaredSize(),
                digest,
                requireToken(command.retentionClass(), "retentionClass", 2, 80),
                reason,
                command.content(),
                now);
        var idempotency = new IdempotencyCommand(
                "credential.document.upload",
                key,
                hash(
                        "credential.document.upload",
                        command.credentialId(),
                        command.memberId(),
                        fileName,
                        mediaType,
                        command.declaredSize(),
                        digest,
                        command.retentionClass(),
                        reason),
                now.plus(24, ChronoUnit.HOURS));

        return governedMutations.execute(
                authorization(
                        command.organizationId(),
                        command.actorId(),
                        command.correlationId(),
                        "credential.document.upload",
                        reason,
                        command.recentAuthenticationAt(),
                        command.mfaAuthenticatedAt()),
                idempotency,
                context -> {
                    var evidence = documents.quarantine(
                            context,
                            new DocumentQuarantineRequest(
                                    documentId,
                                    objectVersionId,
                                    command.declaredSize(),
                                    mediaType,
                                    digest),
                            command.content());
                    var result = store.bindCredentialDocument(
                            context,
                            storeCommand,
                            evidence.document().documentId(),
                            evidence.document().objectVersionId(),
                            evidence.quarantinedAt());
                    var response = project(
                            context,
                            WorkforceScreenCatalogue.screen("M2-10"),
                            new WorkforceStore.ScreenQuery(
                                    "M2-10", command.memberId(), null, null, 50));
                    return governed(result, reason, response);
                });
    }

    public IdempotencyOutcome accessEvidence(EvidenceAccessCommand command) {
        Objects.requireNonNull(command, "command");
        var key = requireIdempotencyKey(command.idempotencyKey());
        var reason = requireReason(command.reason());
        var projection = requireToken(command.projection(), "projection", 3, 80);
        if (!Set.of("workforce-audit-detail-v1", "member-evidence-detail-v1")
                .contains(projection)) {
            throw invalid("The restricted evidence projection is not supported.");
        }
        var purposeCode = requireToken(command.purposeCode(), "purposeCode", 2, 80);
        if (!EVIDENCE_PURPOSES.contains(purposeCode)) {
            throw invalid("The restricted evidence purpose is not supported.");
        }
        if (projection.equals("member-evidence-detail-v1") && command.memberId() == null) {
            throw invalid("memberId is required for member evidence detail.");
        }
        var now = clock.instant();
        var idempotency = new IdempotencyCommand(
                "workforce.audit.read",
                key,
                hash(
                        "workforce.audit.read",
                        command.evidenceId(),
                        command.memberId(),
                        projection,
                        purposeCode,
                        reason),
                now.plus(24, ChronoUnit.HOURS));
        return governedMutations.execute(
                authorization(
                        command.organizationId(),
                        command.actorId(),
                        command.correlationId(),
                        purposeCode.replace('_', '-'),
                        "workforce.audit.read",
                        reason,
                        command.recentAuthenticationAt(),
                        command.mfaAuthenticatedAt()),
                idempotency,
                context -> {
                    var detail = store.accessEvidence(
                            context,
                            new WorkforceStore.EvidenceAccessCommand(
                                    Objects.requireNonNull(command.evidenceId(), "evidenceId"),
                                    command.memberId(),
                                    projection,
                                    purposeCode));
                    Map<String, Object> payload;
                    try {
                        payload = objectMapper.readValue(
                                detail.payloadJson(), new TypeReference<LinkedHashMap<String, Object>>() {});
                    } catch (Exception exception) {
                        throw new IllegalStateException(
                                "Unable to project the restricted workforce evidence payload.", exception);
                    }
                    var response = new EvidenceAccessResponse(
                            detail.evidenceId(),
                            detail.memberId(),
                            detail.occurredAt(),
                            detail.actorId(),
                            detail.actorKind(),
                            detail.operation(),
                            detail.eventName(),
                            detail.schemaVersion(),
                            detail.subjectType(),
                            detail.subjectId(),
                            detail.correlationId(),
                            projection,
                            purposeCode,
                            detail.redactionPolicyVersion(),
                            payload);
                    var accessPayload = Map.<String, Object>of(
                            "evidenceFamily", eventFamily(detail.eventName()),
                            "evidenceId", detail.evidenceId(),
                            "projection", projection,
                            "purposeCode", purposeCode,
                            "redactionPolicyVersion", detail.redactionPolicyVersion());
                    var audit = new AuditRecord(
                            "workforce.evidence.accessed",
                            1,
                            "workforce_evidence",
                            detail.evidenceId(),
                            reason,
                            json(accessPayload));
                    return new GovernedMutation(
                            new IdempotentResponse(200, JSON, json(response)),
                            GovernanceEvidence.auditOnly(audit));
                });
    }

    private WorkforceScreen project(
            com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
            WorkforceScreenCatalogue.ScreenSpec spec,
            WorkforceStore.ScreenQuery query) {
        var pageSize = query.limit();
        var binding = new WorkforceScreenCursorCodec.Binding(
                context.organizationId(),
                context.actorId(),
                query.screenId(),
                query.memberId(),
                query.search(),
                query.status(),
                pageSize);
        return project(
                context,
                spec,
                new PagedQuery(
                        new WorkforceStore.ScreenQuery(
                                query.screenId(),
                                query.memberId(),
                                query.search(),
                                query.status(),
                                10_001),
                        binding,
                        new WorkforceScreenCursorCodec.Position(clock.instant(), 0, null),
                        pageSize));
    }

    private WorkforceScreen project(
            com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
            WorkforceScreenCatalogue.ScreenSpec spec,
            PagedQuery page) {
        var projection = store.projection(context, page.query());
        var actions = WorkforceScreenCatalogue.projectedActions(spec, store.permissions(context));
        var permittedActionKeys = actions.stream()
                .filter(WorkforceScreen.Action::targetRequired)
                .map(WorkforceScreen.Action::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var authorizedRows = projection.rows().stream()
                .map(row -> new WorkforceScreen.Row(
                        row.id(),
                        row.memberId(),
                        row.status(),
                        row.revision(),
                        row.etag(),
                        row.values(),
                        row.allowedActionKeys().stream()
                                .filter(permittedActionKeys::contains)
                                .toList()))
                .toList();
        var snapshotDigest = screenSnapshotDigest(authorizedRows, actions);
        if (page.position().snapshotDigest() != null
                && !MessageDigest.isEqual(
                        page.position().snapshotDigest().getBytes(StandardCharsets.UTF_8),
                        snapshotDigest.getBytes(StandardCharsets.UTF_8))) {
            throw conflict(
                    "The workforce result set changed while paging; restart from the first page.");
        }
        var boundedSize = Math.min(authorizedRows.size(), 10_000);
        var from = Math.min(page.position().offset(), boundedSize);
        var to = Math.min(from + page.pageSize(), boundedSize);
        var rows = List.copyOf(authorizedRows.subList(from, to));
        var nextCursor = boundedSize > to
                ? screenCursors.encode(
                        page.binding(),
                        new WorkforceScreenCursorCodec.Position(
                                page.position().asOf(), to, snapshotDigest))
                : null;
        return new WorkforceScreen(
                context.organizationId(),
                spec.id(),
                spec.title(),
                spec.purpose(),
                projection.generatedAt(),
                projection.metrics(),
                projection.columns(),
                rows,
                actions,
                projection.notices(),
                nextCursor,
                page.pageSize());
    }

    private GovernedMutation governed(
            WorkforceStore.MutationResult result, String reason, WorkforceScreen response) {
        var auditPayload = json(result.auditPayload());
        var audit = new AuditRecord(
                result.auditEvent(),
                1,
                result.subjectType(),
                result.subjectId(),
                reason,
                auditPayload);
        GovernanceEvidence evidence;
        if (result.outboxEvent() == null) {
            evidence = GovernanceEvidence.auditOnly(audit);
        } else {
            evidence = new GovernanceEvidence(
                    audit,
                    new OutboxRecord(
                            result.outboxEvent(),
                            1,
                            result.aggregateType(),
                            result.subjectId(),
                            json(result.outboxPayload())));
        }
        return new GovernedMutation(
                new IdempotentResponse(result.statusCode(), JSON, json(response)), evidence);
    }

    private PagedQuery pagedQuery(ReadCommand command) {
        var search = normalizeSearch(command.search());
        var status = normalizeStatus(command.status());
        var limit = command.limit() == null ? 25 : command.limit();
        if (limit < 1 || limit > 100) {
            throw invalid("limit must be between 1 and 100.");
        }
        var binding = new WorkforceScreenCursorCodec.Binding(
                command.organizationId(),
                command.actorId(),
                command.screenId(),
                command.memberId(),
                search,
                status,
                limit);
        WorkforceScreenCursorCodec.Position position;
        if (command.cursor() == null || command.cursor().isBlank()) {
            position = new WorkforceScreenCursorCodec.Position(clock.instant(), 0, null);
        } else {
            try {
                position = screenCursors.decode(command.cursor().strip(), binding);
            } catch (IllegalArgumentException exception) {
                throw invalid(exception.getMessage());
            }
        }
        return new PagedQuery(
                new WorkforceStore.ScreenQuery(
                        command.screenId(), command.memberId(), search, status, 10_001),
                binding,
                position,
                limit);
    }

    private static TenantAuthorizationRequest authorization(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String operation,
            String reason,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {
        return new TenantAuthorizationRequest(
                Objects.requireNonNull(organizationId, "organizationId"),
                new AuthenticatedActorContext(
                        Objects.requireNonNull(actorId, "actorId"), PURPOSE, correlationId),
                new OperationKey(operation),
                reason,
                recentAuthenticationAt,
                mfaAuthenticatedAt,
                null);
    }

    private static TenantAuthorizationRequest authorization(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String purpose,
            String operation,
            String reason,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {
        return new TenantAuthorizationRequest(
                Objects.requireNonNull(organizationId, "organizationId"),
                new AuthenticatedActorContext(
                        Objects.requireNonNull(actorId, "actorId"), purpose, correlationId),
                new OperationKey(operation),
                reason,
                recentAuthenticationAt,
                mfaAuthenticatedAt,
                null);
    }

    private static String eventFamily(String eventName) {
        var separator = eventName.lastIndexOf('.');
        return separator > 0 ? eventName.substring(0, separator) : eventName;
    }

    private static void requireFields(
            WorkforceScreenCatalogue.ActionSpec spec, Map<String, String> fields) {
        var allowed = spec.fields().stream().map(WorkforceScreen.Field::key).collect(java.util.stream.Collectors.toSet());
        if (!allowed.containsAll(fields.keySet())) {
            throw invalid("The action contains an unsupported field.");
        }
        for (var field : spec.fields()) {
            var value = fields.get(field.key());
            if (field.required() && (value == null || value.isBlank())) {
                throw invalid(field.label() + " is required.");
            }
            if (value != null && value.length() > 2000) {
                throw invalid(field.label() + " is too long.");
            }
            if (value != null
                    && !field.options().isEmpty()
                    && field.options().stream().noneMatch(option -> option.value().equals(value))) {
                throw invalid(field.label() + " has an unsupported value.");
            }
        }
    }

    private static long requireRevision(String ifMatch, String screenId, UUID targetId) {
        var revision = optionalRevision(ifMatch, screenId, targetId);
        if (revision == null) {
            throw new WorkforceException(
                    WorkforceException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match value for the selected record is required.");
        }
        return revision;
    }

    private static Long optionalRevision(String ifMatch, String screenId, UUID targetId) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        var matcher = ETAG.matcher(ifMatch.strip());
        if (!matcher.matches()
                || !matcher.group(1).equals(screenId)
                || targetId == null
                || !UUID.fromString(matcher.group(2)).equals(targetId)) {
            throw new WorkforceException(
                    WorkforceException.Reason.STALE,
                    "If-Match does not identify the selected workforce record.");
        }
        return Long.parseLong(matcher.group(3));
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw invalid("Idempotency-Key must contain 16 to 128 safe characters.");
        }
        return value;
    }

    private static String requireReason(String value) {
        var normalized = Normalizer.normalize(value == null ? "" : value.strip(), Normalizer.Form.NFC);
        var length = normalized.codePointCount(0, normalized.length());
        if (length < 10 || length > 500 || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("reason must contain between 10 and 500 safe characters.");
        }
        return normalized;
    }

    private static String optionalReason(String value) {
        return value == null || value.isBlank() ? null : requireReason(value);
    }

    private static String requireSafeFileName(String value) {
        var normalized = requireToken(value, "fileName", 1, 255);
        if (normalized.indexOf('/') >= 0
                || normalized.indexOf('\\') >= 0
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("fileName contains unsupported characters.");
        }
        return normalized;
    }

    private static String requireMediaType(String value) {
        var normalized = requireToken(value, "mediaType", 3, 120).toLowerCase();
        if (!normalized.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")) {
            throw invalid("mediaType has an unsupported format.");
        }
        return normalized;
    }

    private static String requireToken(String value, String name, int min, int max) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.length() < min || normalized.length() > max) {
            throw invalid(name + " has an invalid length.");
        }
        return normalized;
    }

    private static String normalizeSearch(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        var length = normalized.codePointCount(0, normalized.length());
        if (length < 2 || length > 100
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Search filters must contain 2 to 100 safe characters.");
        }
        return normalized;
    }

    private static String normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.strip();
        if (normalized.length() > 120
                || !normalized.matches("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*")) {
            throw invalid("Status filters must be stable lower-case state or event keys.");
        }
        return normalized;
    }

    private static String canonicalFields(Map<String, String> fields) {
        var values = new ArrayList<>(fields.entrySet());
        values.sort(Comparator.comparing(Map.Entry::getKey));
        var canonical = new StringBuilder();
        for (var entry : values) {
            canonical.append(entry.getKey().length())
                    .append(':')
                    .append(entry.getKey())
                    .append('=')
                    .append(entry.getValue().length())
                    .append(':')
                    .append(entry.getValue())
                    .append(';');
        }
        return canonical.toString();
    }

    private static WorkforceImpactTokenCodec.Binding impactBinding(
            ActionCommand command, long revision, String reason, Map<String,String> fields) {
        return impactBinding(
                command.organizationId(),command.actorId(),command.screenId(),
                command.actionKey(),command.targetId(),command.memberId(),command.decision(),reason,
                command.evidenceIds(),revision,fields);
    }

    private static WorkforceImpactTokenCodec.Binding impactBinding(
            ImpactPreviewCommand command, long revision, String reason, Map<String,String> fields) {
        return impactBinding(
                command.organizationId(),command.actorId(),command.screenId(),
                command.actionKey(),command.targetId(),command.memberId(),command.decision(),reason,
                command.evidenceIds(),revision,fields);
    }

    private static WorkforceImpactTokenCodec.Binding impactBinding(
            UUID organizationId,
            UUID actorId,
            String screenId,
            String actionKey,
            UUID targetId,
            UUID memberId,
            String decision,
            String reason,
            List<UUID> evidenceIds,
            long revision,
            Map<String,String> fields) {
        return new WorkforceImpactTokenCodec.Binding(
                organizationId,actorId,screenId,actionKey,targetId,revision,
                hash(memberId,decision,reason,canonicalFields(fields),evidenceIds));
    }

    private static String hash(Object... values) {
        try {
            var canonical = new StringBuilder();
            for (var value : values) {
                var string = Objects.toString(value, "<null>");
                canonical.append(string.length()).append(':').append(string).append(';');
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash the workforce command.", exception);
        }
    }

    private static String screenSnapshotDigest(
            List<WorkforceScreen.Row> rows, List<WorkforceScreen.Action> actions) {
        var canonical = new StringBuilder("m2-screen-snapshot-v1|");
        for (var action : actions) {
            canonical.append(action.key().length()).append(':').append(action.key()).append(';');
        }
        canonical.append('|');
        for (var row : rows) {
            canonical.append(row.id()).append('|')
                    .append(Objects.toString(row.memberId(), "-")).append('|')
                    .append(row.status()).append('|')
                    .append(row.revision()).append('|')
                    .append(row.etag()).append('|')
                    .append(canonicalFields(row.values())).append('|');
            row.allowedActionKeys().stream().sorted().forEach(action ->
                    canonical.append(action.length()).append(':').append(action).append(';'));
            canonical.append('\n');
        }
        return hash(canonical);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize the workforce response.", exception);
        }
    }

    private static WorkforceException invalid(String message) {
        return new WorkforceException(WorkforceException.Reason.INVALID, message);
    }

    public record ReadCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String screenId,
            UUID memberId,
            String search,
            String status,
            Integer limit,
            String cursor,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    private record PagedQuery(
            WorkforceStore.ScreenQuery query,
            WorkforceScreenCursorCodec.Binding binding,
            WorkforceScreenCursorCodec.Position position,
            int pageSize) {}

    public record ActionCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String screenId,
            String actionKey,
            UUID targetId,
            UUID memberId,
            String decision,
            String reason,
            Map<String, String> fields,
            List<UUID> evidenceIds,
            String impactToken,
            String ifMatch,
            String idempotencyKey,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {
        public ActionCommand {
            evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
        }
    }

    public record ImpactPreviewCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String screenId,
            String actionKey,
            UUID targetId,
            UUID memberId,
            String decision,
            String reason,
            Map<String,String> fields,
            List<UUID> evidenceIds,
            String ifMatch,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {
        public ImpactPreviewCommand {
            fields=Map.copyOf(fields==null?Map.of():fields);
            evidenceIds=List.copyOf(evidenceIds==null?List.of():evidenceIds);
        }
    }

    public record ImpactPreviewResponse(
            String screenId,
            String actionKey,
            UUID targetId,
            long revision,
            String digest,
            String token,
            Instant expiresAt,
            boolean blocked,
            List<WorkforceStore.ImpactItem> items) {
        public ImpactPreviewResponse {
            items=List.copyOf(items==null?List.of():items);
        }
    }

    public record DocumentUploadCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID credentialId,
            UUID memberId,
            String fileName,
            String mediaType,
            long declaredSize,
            String sha256,
            String retentionClass,
            String reason,
            InputStream content,
            String idempotencyKey,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    public record EvidenceAccessCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID evidenceId,
            UUID memberId,
            String projection,
            String purposeCode,
            String reason,
            String idempotencyKey,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    public record EvidenceAccessResponse(
            UUID evidenceId,
            UUID memberId,
            Instant occurredAt,
            UUID actorId,
            String actorKind,
            String operation,
            String eventName,
            int schemaVersion,
            String subjectType,
            UUID subjectId,
            String correlationId,
            String projection,
            String purposeCode,
            String redactionPolicyVersion,
            Map<String, Object> payload) {
        public EvidenceAccessResponse {
            payload = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(payload == null ? Map.of() : payload));
        }
    }
}
