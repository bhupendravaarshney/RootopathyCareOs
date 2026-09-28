package com.rootopathy.careos.document.application;

import com.rootopathy.careos.document.domain.DocumentScreen;
import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.GovernedMutation;
import com.rootopathy.careos.governance.domain.IdempotencyCommand;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.governance.domain.OutboxRecord;
import com.rootopathy.careos.platform.application.DocumentAccessException;
import com.rootopathy.careos.platform.application.DocumentAccessOperations;
import com.rootopathy.careos.platform.application.DocumentPromotionOperations;
import com.rootopathy.careos.platform.application.DocumentSecurityOperations;
import com.rootopathy.careos.platform.application.DocumentStorageException;
import com.rootopathy.careos.platform.application.PlatformCapabilityUnavailableException;
import com.rootopathy.careos.platform.domain.DocumentPromotionEvidence;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.platform.domain.MalwareScanVerdict;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class DocumentService {
    private static final String JSON = "application/json";
    private static final String DEFAULT_PURPOSE = "documents";
    private static final long MAXIMUM_BYTES = 20_971_520L;
    private static final Duration ACCESS_TTL = Duration.ofSeconds(60);
    private static final Set<String> MEDIA_TYPES = Set.of(
            "application/pdf", "image/png", "image/jpeg", "text/plain");
    private static final Set<String> ACCESS_PURPOSES = Set.of(
            "clinical_care", "result_review", "patient_request", "security_investigation");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ETAG = Pattern.compile(
            "\\\"m7:(P7-(?:0[1-9]|1[01])):([0-9a-fA-F-]{36}):([0-9]{1,19})\\\"");

    private final TenantAuthorizationOperations authorization;
    private final GovernedMutationExecutor governedMutations;
    private final DocumentStore store;
    private final DocumentSecurityOperations documentSecurity;
    private final ObjectProvider<DocumentPromotionOperations> promotion;
    private final ObjectProvider<DocumentAccessOperations> access;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DocumentService(
            TenantAuthorizationOperations authorization,
            GovernedMutationExecutor governedMutations,
            DocumentStore store,
            DocumentSecurityOperations documentSecurity,
            ObjectProvider<DocumentPromotionOperations> promotion,
            ObjectProvider<DocumentAccessOperations> access,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorization = authorization;
        this.governedMutations = governedMutations;
        this.store = store;
        this.documentSecurity = documentSecurity;
        this.promotion = promotion;
        this.access = access;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public DocumentScreen screen(ReadCommand command) {
        Objects.requireNonNull(command, "command");
        var spec = DocumentScreenCatalogue.screen(command.screenId());
        if (command.cursor() != null && !command.cursor().isBlank()) {
            throw invalid("The document cursor is invalid or expired; restart from the first page.");
        }
        var limit = command.limit() == null ? 50 : command.limit();
        if (limit < 1 || limit > 100) throw invalid("limit must be between 1 and 100.");
        var query = new DocumentStore.ScreenQuery(
                command.screenId(),
                command.patientId(),
                command.documentId(),
                command.diagnosticReportId(),
                optional(command.search(), 100, "q"),
                optional(command.status(), 120, "status"),
                limit);
        return authorization.execute(
                authorization(
                        command.organizationId(),
                        command.actorId(),
                        command.correlationId(),
                        DEFAULT_PURPOSE,
                        spec.readOperation(),
                        null,
                        command.recentAuthenticationAt(),
                        command.mfaAuthenticatedAt()),
                context -> project(context, spec, query));
    }

    public IdempotencyOutcome act(ActionCommand command) {
        Objects.requireNonNull(command, "command");
        var spec = DocumentScreenCatalogue.mutation(command.screenId(), command.actionKey());
        if (Set.of("upload-document", "access-document").contains(command.actionKey())) {
            throw invalid("This action requires its dedicated file or access endpoint.");
        }
        var key = requireIdempotencyKey(command.idempotencyKey());
        var reason = spec.reasonRequired()
                ? requireReason(command.reason())
                : optional(command.reason(), 500, "reason");
        if (spec.targetRequired() && command.targetId() == null) {
            throw invalid("targetId is required for this action.");
        }
        var revision = spec.ifMatchRequired()
                ? Long.valueOf(requireRevision(command.ifMatch(), command.screenId(), command.targetId()))
                : optionalRevision(command.ifMatch(), command.screenId(), command.targetId());
        var fields = normalizeFields(command.fields());
        requireFields(spec, fields);
        var now = clock.instant();
        var mutation = new DocumentStore.MutationCommand(
                command.screenId(),
                command.actionKey(),
                command.targetId(),
                command.patientId(),
                command.documentId(),
                command.documentVersionId(),
                command.diagnosticReportId(),
                revision,
                reason,
                fields,
                now,
                command.correlationId());
        var idempotency = idempotency(
                spec.operation(),
                key,
                spec.operation(),
                command.screenId(),
                command.actionKey(),
                command.targetId(),
                command.patientId(),
                command.documentId(),
                command.documentVersionId(),
                command.diagnosticReportId(),
                revision,
                reason,
                canonicalFields(fields));
        return governedMutations.execute(
                authorization(
                        command.organizationId(),
                        command.actorId(),
                        command.correlationId(),
                        DEFAULT_PURPOSE,
                        spec.operation(),
                        reason,
                        command.recentAuthenticationAt(),
                        command.mfaAuthenticatedAt()),
                idempotency,
                context -> {
                    var result = command.actionKey().equals("scan-document")
                            ? scan(context, mutation)
                            : store.mutate(context, mutation);
                    var response = projectResult(context, command.screenId(), result);
                    return governed(result, reason, response);
                });
    }

    public IdempotencyOutcome upload(UploadRequest command) {
        Objects.requireNonNull(command, "command");
        var key = requireIdempotencyKey(command.idempotencyKey());
        var reason = requireReason(command.reason());
        var patientId = Objects.requireNonNull(command.patientId(), "patientId");
        var replacementId = command.replacementDocumentId();
        var expectedRevision = replacementId == null
                ? null
                : requireRevision(command.ifMatch(), "P7-03", replacementId);
        var title = required(command.title(), 2, 240, "title");
        var documentType = token(command.documentTypeKey(), "documentTypeKey", 2, 80);
        var source = source(command.sourceKey(), "sourceKey", 2, 120);
        var fileName = safeFileName(command.fileName());
        var mediaType = mediaType(command.mediaType());
        var content = Objects.requireNonNull(command.content(), "content").clone();
        if (content.length < 1 || content.length > MAXIMUM_BYTES) {
            throw invalid("The document must contain 1 byte to 20 MiB.");
        }
        validateSignature(content, mediaType, fileName);
        var digest = sha256(content);
        var suppliedDigest = command.sha256() == null ? "" : command.sha256().strip().toLowerCase();
        if (!SHA256.matcher(suppliedDigest).matches() || !digest.equals(suppliedDigest)) {
            throw invalid("sha256 must match the exact uploaded bytes.");
        }
        var now = clock.instant();
        var documentId = replacementId == null ? UuidV7Generator.randomUuid() : replacementId;
        var versionId = UuidV7Generator.randomUuid();
        var upload = new DocumentStore.UploadCommand(
                documentId,
                versionId,
                patientId,
                command.encounterId(),
                command.assessmentSessionId(),
                expectedRevision,
                title,
                documentType,
                source,
                fileName,
                mediaType,
                content.length,
                digest,
                replacementId == null ? null : reason,
                now);
        var idempotency = idempotency(
                "document.upload",
                key,
                "document.upload",
                documentId,
                patientId,
                command.encounterId(),
                command.assessmentSessionId(),
                expectedRevision,
                title,
                documentType,
                source,
                fileName,
                mediaType,
                content.length,
                digest,
                reason);
        return governedMutations.execute(
                authorization(
                        command.organizationId(),
                        command.actorId(),
                        command.correlationId(),
                        DEFAULT_PURPOSE,
                        "document.upload",
                        reason,
                        command.recentAuthenticationAt(),
                        command.mfaAuthenticatedAt()),
                idempotency,
                context -> {
                    var evidence = documentSecurity.quarantine(
                            context,
                            new DocumentQuarantineRequest(
                                    documentId, versionId, content.length, mediaType, digest),
                            new ByteArrayInputStream(content));
                    var result = store.bindUpload(context, upload, evidence);
                    var response = projectResult(context, "P7-03", result);
                    return governed(result, reason, response);
                });
    }

    public IdempotencyOutcome createAccess(AccessCommand command) {
        Objects.requireNonNull(command, "command");
        var key = requireIdempotencyKey(command.idempotencyKey());
        var reason = requireReason(command.reason());
        var purpose = token(command.purposeKey(), "purposeKey", 2, 80);
        if (!ACCESS_PURPOSES.contains(purpose)) {
            throw invalid("The document access purpose is not supported.");
        }
        var now = clock.instant();
        var idempotency = idempotency(
                "document.access",
                key,
                "document.access",
                command.documentId(),
                command.documentVersionId(),
                purpose,
                reason);
        return governedMutations.execute(
                authorization(
                        command.organizationId(),
                        command.actorId(),
                        command.correlationId(),
                        purpose,
                        "document.access",
                        reason,
                        command.recentAuthenticationAt(),
                        command.mfaAuthenticatedAt()),
                idempotency,
                context -> {
                    var version = store.version(
                            context,
                            Objects.requireNonNull(command.documentId(), "documentId"),
                            command.documentVersionId());
                    var operations = access.getIfAvailable();
                    if (operations == null) throw unavailable("Clean document access is not configured.");
                    try {
                        var signed = operations.createReadAccess(context, version.object(), ACCESS_TTL);
                        var result = store.bindAccess(context, version, purpose, reason, signed);
                        var response = new AccessResponse(
                                result.subjectId(),
                                version.documentId(),
                                version.documentVersionId(),
                                accessPath(
                                        command.organizationId(),
                                        version.documentId(),
                                        version.documentVersionId(),
                                        result.subjectId(),
                                        purpose),
                                signed.expiresAt(),
                                version.mediaType(),
                                version.byteCount(),
                                version.sha256(),
                                purpose);
                        return governed(result, reason, response);
                    } catch (DocumentAccessException
                            | DocumentStorageException
                            | PlatformCapabilityUnavailableException exception) {
                        throw unavailable("Clean document access is not currently available.");
                    }
                });
    }

    public Redirect openAccess(OpenAccessCommand command) {
        Objects.requireNonNull(command, "command");
        var purpose = token(command.purposeKey(), "purposeKey", 2, 80);
        if (!ACCESS_PURPOSES.contains(purpose)) {
            throw invalid("The document access purpose is not supported.");
        }
        return authorization.execute(
                authorization(
                        command.organizationId(),
                        command.actorId(),
                        command.correlationId(),
                        purpose,
                        "document.read",
                        null,
                        command.recentAuthenticationAt(),
                        command.mfaAuthenticatedAt()),
                context -> {
                    var reference = store.access(
                            context,
                            command.documentId(),
                            command.documentVersionId(),
                            command.accessIntentId(),
                            purpose);
                    var operations = access.getIfAvailable();
                    if (operations == null) throw unavailable("Clean document access is not configured.");
                    try {
                        var signed = operations.reopenReadAccess(
                                context, reference.object(), reference.platformAccessGrantId());
                        return new Redirect(signed.readUrl().toASCIIString(), signed.expiresAt());
                    } catch (DocumentAccessException exception) {
                        throw new DocumentException(
                                DocumentException.Reason.NOT_FOUND,
                                "The document access grant is unavailable or expired.");
                    } catch (DocumentStorageException
                            | PlatformCapabilityUnavailableException exception) {
                        throw unavailable("Clean document access is not currently available.");
                    }
                });
    }

    private DocumentStore.MutationResult scan(
            com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
            DocumentStore.MutationCommand command) {
        var version = store.version(context, command.documentId(), command.targetId());
        if (command.expectedRevision() == null) {
            throw new DocumentException(
                    DocumentException.Reason.PRECONDITION_REQUIRED,
                    "A strong document-version If-Match revision is required.");
        }
        if (version.revision() != command.expectedRevision()) {
            throw new DocumentException(
                    DocumentException.Reason.STALE,
                    "The document version changed; refresh before scanning again.");
        }
        try {
            var scan = documentSecurity.scan(context, version.object());
            DocumentPromotionEvidence promoted = null;
            if (scan.result().verdict() == MalwareScanVerdict.CLEAN) {
                var operations = promotion.getIfAvailable();
                if (operations == null) {
                    throw unavailable("Clean document promotion is not configured.");
                }
                promoted = operations.promote(context, version.object());
            }
            return store.bindScan(context, version, scan, promoted);
        } catch (PlatformCapabilityUnavailableException | DocumentStorageException exception) {
            throw unavailable("Document scanning or clean promotion is not currently available.");
        }
    }

    private DocumentScreen projectResult(
            com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
            String screenId,
            DocumentStore.MutationResult result) {
        return project(
                context,
                DocumentScreenCatalogue.screen(screenId),
                new DocumentStore.ScreenQuery(
                        screenId,
                        result.patientId(),
                        result.documentId(),
                        result.diagnosticReportId(),
                        null,
                        null,
                        50));
    }

    private DocumentScreen project(
            com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext context,
            DocumentScreenCatalogue.ScreenSpec spec,
            DocumentStore.ScreenQuery query) {
        var projection = store.projection(context, query);
        var actions = DocumentScreenCatalogue.projectedActions(spec, store.permissions(context));
        var permittedTargets = actions.stream()
                .filter(DocumentScreen.Action::targetRequired)
                .map(DocumentScreen.Action::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var rows = projection.rows().stream()
                .map(row -> new DocumentScreen.Row(
                        row.id(),
                        row.patientId(),
                        row.documentId(),
                        row.documentVersionId(),
                        row.diagnosticReportId(),
                        row.resultFlagId(),
                        row.status(),
                        row.revision(),
                        row.etag(),
                        row.values(),
                        row.allowedActionKeys().stream()
                                .filter(permittedTargets::contains)
                                .toList()))
                .toList();
        return new DocumentScreen(
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
                null,
                query.limit());
    }

    private GovernedMutation governed(
            DocumentStore.MutationResult result, String reason, Object response) {
        var audit = new AuditRecord(
                result.auditEvent(),
                1,
                result.subjectType(),
                result.subjectId(),
                reason,
                json(result.auditPayload()));
        var evidence = result.outboxEvent() == null
                ? GovernanceEvidence.auditOnly(audit)
                : new GovernanceEvidence(
                        audit,
                        new OutboxRecord(
                                result.outboxEvent(),
                                1,
                                result.aggregateType(),
                                result.outboxAggregateId() == null
                                        ? result.subjectId()
                                        : result.outboxAggregateId(),
                                json(result.outboxPayload())));
        return new GovernedMutation(
                new IdempotentResponse(result.statusCode(), JSON, json(response)), evidence);
    }

    private static void requireFields(
            DocumentScreenCatalogue.ActionSpec spec, Map<String, String> fields) {
        var supported = spec.fields().stream()
                .map(DocumentScreen.Field::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        fields.keySet().forEach(key -> {
            if (!supported.contains(key)) throw invalid("The action field '" + key + "' is not supported.");
        });
        spec.fields().forEach(field -> {
            if (field.required()
                    && (fields.get(field.key()) == null || fields.get(field.key()).isBlank())) {
                throw invalid(field.label() + " is required.");
            }
        });
    }

    private static Map<String, String> normalizeFields(Map<String, String> supplied) {
        if (supplied == null || supplied.size() > 64) {
            throw invalid("fields must contain at most 64 values.");
        }
        var normalized = new LinkedHashMap<String, String>();
        supplied.forEach((key, value) -> {
            if (key == null || value == null || !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")) {
                throw invalid("Action field names and values must be non-null and well formed.");
            }
            var clean = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
            if (clean.length() > 20000) throw invalid("Action field values are too long.");
            normalized.put(key, clean);
        });
        return Map.copyOf(normalized);
    }

    private static long requireRevision(String value, String screenId, UUID targetId) {
        var revision = optionalRevision(value, screenId, targetId);
        if (revision == null) {
            throw new DocumentException(
                    DocumentException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match revision is required.");
        }
        return revision;
    }

    private static Long optionalRevision(String value, String screenId, UUID targetId) {
        if (value == null || value.isBlank()) return null;
        var matcher = ETAG.matcher(value.strip());
        if (!matcher.matches()
                || !matcher.group(1).equals(screenId)
                || targetId == null
                || !matcher.group(2).equalsIgnoreCase(targetId.toString())) {
            throw invalid("If-Match does not identify the requested document resource.");
        }
        try {
            return Long.parseLong(matcher.group(3));
        } catch (NumberFormatException exception) {
            throw invalid("If-Match revision is invalid.");
        }
    }

    private IdempotencyCommand idempotency(
            String operation, String key, Object... values) {
        return new IdempotencyCommand(
                operation, key, hash(values), clock.instant().plus(24, ChronoUnit.HOURS));
    }

    private static String requireReason(String reason) {
        var value = optional(reason, 500, "reason");
        if (value == null || value.codePointCount(0, value.length()) < 10) {
            throw invalid("reason must contain 10 to 500 characters.");
        }
        return value;
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw invalid("Idempotency-Key must contain 16 to 128 safe characters.");
        }
        return value;
    }

    private static String optional(String value, int maximum, String field) {
        if (value == null || value.isBlank()) return null;
        var normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        if (normalized.codePointCount(0, normalized.length()) > maximum
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(field + " is too long or contains unsafe characters.");
        }
        return normalized;
    }

    private static String required(
            String value, int minimum, int maximum, String field) {
        var normalized = optional(value, maximum, field);
        if (normalized == null || normalized.codePointCount(0, normalized.length()) < minimum) {
            throw invalid(field + " is required.");
        }
        return normalized;
    }

    private static String token(String value, String field, int minimum, int maximum) {
        var normalized = required(value, minimum, maximum, field);
        if (!normalized.matches("[a-z][a-z0-9_]*")) throw invalid(field + " has an invalid format.");
        return normalized;
    }

    private static String source(String value, String field, int minimum, int maximum) {
        var normalized = required(value, minimum, maximum, field);
        if (!normalized.matches("[a-z][a-z0-9_.:-]*")) throw invalid(field + " has an invalid format.");
        return normalized;
    }

    private static String safeFileName(String value) {
        var normalized = required(value, 1, 240, "fileName");
        if (normalized.contains("/")
                || normalized.contains("\\")
                || normalized.equals(".")
                || normalized.equals("..")) {
            throw invalid("fileName must contain only a safe base name.");
        }
        return normalized;
    }

    private static String mediaType(String value) {
        var normalized = value == null ? "" : value.strip().toLowerCase();
        if (!MEDIA_TYPES.contains(normalized)) {
            throw invalid("Only PDF, PNG, JPEG and plain-text documents are supported.");
        }
        return normalized;
    }

    private static void validateSignature(byte[] content, String mediaType, String fileName) {
        var lower = fileName.toLowerCase();
        var valid = switch (mediaType) {
            case "application/pdf" -> starts(content, new byte[] {'%', 'P', 'D', 'F', '-'})
                    && lower.endsWith(".pdf");
            case "image/png" -> starts(content, new byte[] {
                        (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a
                    })
                    && lower.endsWith(".png");
            case "image/jpeg" -> starts(content, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})
                    && (lower.endsWith(".jpg") || lower.endsWith(".jpeg"));
            case "text/plain" -> validUtf8Text(content) && lower.endsWith(".txt");
            default -> false;
        };
        if (!valid) throw invalid("The filename, declared media type and file signature do not agree.");
    }

    private static boolean starts(byte[] content, byte[] signature) {
        if (content.length < signature.length) return false;
        for (var index = 0; index < signature.length; index++) {
            if (content[index] != signature[index]) return false;
        }
        return true;
    }

    private static boolean validUtf8Text(byte[] content) {
        for (byte value : content) if (value == 0) return false;
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static String canonicalFields(Map<String, String> fields) {
        return fields.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate the document digest.", exception);
        }
    }

    private static String hash(Object... values) {
        var canonical = new StringBuilder("m7-document-result-v1|");
        for (var value : values) {
            var text = Objects.toString(value, "<null>");
            canonical.append(text.length()).append(':').append(text).append(';');
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private TenantAuthorizationRequest authorization(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String purpose,
            String operation,
            String reason,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {
        return new TenantAuthorizationRequest(
                organizationId,
                new AuthenticatedActorContext(actorId, purpose, correlationId),
                new OperationKey(operation),
                reason,
                recentAuthenticationAt,
                mfaAuthenticatedAt,
                null);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize the document response.", exception);
        }
    }

    private static String accessPath(
            UUID organizationId,
            UUID documentId,
            UUID versionId,
            UUID intentId,
            String purpose) {
        return "/api/v1/organizations/" + organizationId
                + "/documents/" + documentId
                + "/versions/" + versionId
                + "/accesses/" + intentId
                + "?purposeKey=" + purpose;
    }

    private static DocumentException invalid(String message) {
        return new DocumentException(DocumentException.Reason.INVALID, message);
    }

    private static DocumentException unavailable(String message) {
        return new DocumentException(DocumentException.Reason.DEPENDENCY_UNAVAILABLE, message);
    }

    public record ReadCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String screenId,
            UUID patientId,
            UUID documentId,
            UUID diagnosticReportId,
            String search,
            String status,
            Integer limit,
            String cursor,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    public record ActionCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            String screenId,
            String actionKey,
            UUID targetId,
            UUID patientId,
            UUID documentId,
            UUID documentVersionId,
            UUID diagnosticReportId,
            String reason,
            Map<String, String> fields,
            String ifMatch,
            String idempotencyKey,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    public record UploadRequest(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID patientId,
            UUID encounterId,
            UUID assessmentSessionId,
            UUID replacementDocumentId,
            String title,
            String documentTypeKey,
            String sourceKey,
            String reason,
            String fileName,
            String mediaType,
            String sha256,
            byte[] content,
            String ifMatch,
            String idempotencyKey,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {
        public UploadRequest {
            content = content == null ? null : content.clone();
        }
    }

    public record AccessCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID documentId,
            UUID documentVersionId,
            String purposeKey,
            String reason,
            String idempotencyKey,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    public record OpenAccessCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID documentId,
            UUID documentVersionId,
            UUID accessIntentId,
            String purposeKey,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    public record AccessResponse(
            UUID accessIntentId,
            UUID documentId,
            UUID documentVersionId,
            String accessPath,
            Instant expiresAt,
            String mediaType,
            long byteCount,
            String sha256,
            String purposeKey) {}

    public record Redirect(String location, Instant expiresAt) {}
}
