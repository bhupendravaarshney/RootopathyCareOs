package com.rootopathy.careos.workforce.application;

import com.rootopathy.careos.administration.application.EvidenceExportArtifactStore;
import com.rootopathy.careos.governance.application.GovernedMutationExecutor;
import com.rootopathy.careos.governance.domain.AuditRecord;
import com.rootopathy.careos.governance.domain.GovernanceEvidence;
import com.rootopathy.careos.governance.domain.GovernedMutation;
import com.rootopathy.careos.governance.domain.IdempotencyCommand;
import com.rootopathy.careos.governance.domain.IdempotencyOutcome;
import com.rootopathy.careos.governance.domain.IdempotentResponse;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import com.rootopathy.careos.tenancy.domain.OperationKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class WorkforceExportAccessService {
    private static final Set<String> PURPOSES = Set.of(
            "workforce_operations",
            "credentialing_review",
            "regulatory_evidence",
            "security_investigation",
            "employment_record_request",
            "data_correction");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");
    private static final Pattern ETAG = Pattern.compile(
            "\"m2:(?:M2-(?:26|27|29)|export):([0-9a-fA-F-]{36}):([0-9]{1,19})\"");
    private final TenantAuthorizationOperations authorization;
    private final GovernedMutationExecutor mutations;
    private final WorkforceExportStore store;
    private final EvidenceExportArtifactStore artifacts;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkforceExportAccessService(
            TenantAuthorizationOperations authorization,
            GovernedMutationExecutor mutations,
            WorkforceExportStore store,
            EvidenceExportArtifactStore artifacts,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorization = authorization;
        this.mutations = mutations;
        this.store = store;
        this.artifacts = artifacts;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public IdempotencyOutcome access(Command command) {
        Objects.requireNonNull(command, "command");
        var reason = reason(command.reason());
        if (command.idempotencyKey() == null
                || !IDEMPOTENCY_KEY.matcher(command.idempotencyKey()).matches()) {
            throw new IllegalArgumentException("invalid Idempotency-Key");
        }
        if (!PURPOSES.contains(command.purposeKey())) {
            throw new IllegalArgumentException("invalid workforce export purpose");
        }
        var revision = revision(command.ifMatch(), command.exportId());
        var authorizationRequest = authorization(command, reason);
        var persisted = mutations.execute(
                authorizationRequest,
                new IdempotencyCommand(
                        "workforce.export.access",
                        command.idempotencyKey(),
                        hash(command.exportId(),revision,command.purposeKey(),reason),
                        clock.instant().plusSeconds(600)),
                context -> {
                    var access = store.access(
                            context,command.exportId(),revision,command.purposeKey(),
                            maximumGrantExpiry(command));
                    var response = new LinkedHashMap<String,Object>();
                    response.put("exportId",access.exportId());
                    response.put("downloadUrl",downloadUrl(command.organizationId(),access.exportId()));
                    response.put("expiresAt",access.grantExpiresAt());
                    response.put("artifactDigest",access.artifactDigest());
                    response.put("contentType",access.contentType());
                    response.put("filename",access.filename());
                    var audit = new LinkedHashMap<String,Object>();
                    audit.put("exportId",access.exportId());
                    audit.put("state","accessed");
                    audit.put("artifactDigest",access.artifactDigest());
                    audit.put("rowCount",access.rowCount());
                    audit.put("expiryTime",access.grantExpiresAt());
                    audit.put("failureCode",null);
                    return new GovernedMutation(
                            new IdempotentResponse(200,"application/json",json(response)),
                            GovernanceEvidence.auditOnly(new AuditRecord(
                                    "workforce.export.accessed",1,"workforce_export",
                                    access.exportId(),reason,json(audit))));
                });
        return persisted;
    }

    public Download download(DownloadCommand command) {
        Objects.requireNonNull(command,"command");
        var request = new TenantAuthorizationRequest(
                command.organizationId(),
                new AuthenticatedActorContext(
                        command.actorId(),"workforce-export-download",command.correlationId()),
                new OperationKey("workforce.export.access"),
                null,
                command.recentAuthenticationAt(),
                command.mfaAuthenticatedAt(),
                null);
        return authorization.execute(request,context -> {
            var access=store.download(context,command.exportId());
            var content=artifacts.open(
                    context,access.exportId(),access.artifactReference(),
                    access.artifactDigest(),access.byteCount());
            return new Download(
                    access.exportId(),access.artifactDigest(),access.contentType(),
                    access.filename(),access.byteCount(),access.grantExpiresAt(),content);
        });
    }

    private TenantAuthorizationRequest authorization(Command command, String reason) {
        return new TenantAuthorizationRequest(
                command.organizationId(),
                new AuthenticatedActorContext(
                        command.actorId(),"workforce-export",command.correlationId()),
                new OperationKey("workforce.export.access"),
                reason,
                command.recentAuthenticationAt(),
                command.mfaAuthenticatedAt(),
                null);
    }

    private Instant maximumGrantExpiry(Command command) {
        var now=clock.instant();
        var deadline=now.plus(Duration.ofMinutes(10));
        if (command.recentAuthenticationAt()!=null) {
            deadline=earlier(deadline,command.recentAuthenticationAt().plus(Duration.ofMinutes(5)));
        }
        if (command.mfaAuthenticatedAt()!=null) {
            deadline=earlier(deadline,command.mfaAuthenticatedAt().plus(Duration.ofMinutes(5)));
        }
        if (!deadline.isAfter(now)) {
            throw new IllegalArgumentException("recent authentication is required for export access");
        }
        return deadline;
    }

    private static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right)?left:right;
    }

    private static String downloadUrl(UUID organizationId, UUID exportId) {
        return "/api/v1/organizations/"+organizationId
                +"/workforce/exports/"+exportId+"/download";
    }

    private static long revision(String value, UUID exportId) {
        var matcher=value==null?null:ETAG.matcher(value);
        if (matcher==null || !matcher.matches()
                || !UUID.fromString(matcher.group(1)).equals(exportId)) {
            throw new IllegalArgumentException("strong workforce export If-Match required");
        }
        return Long.parseLong(matcher.group(2));
    }

    private static String reason(String value) {
        var normalized=value==null?"":value.strip();
        if (normalized.length()<10 || normalized.length()>500
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid workforce export access reason");
        }
        return normalized;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize workforce export access.",exception);
        }
    }

    private static String hash(Object... values) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(java.util.Arrays.deepToString(values).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.",exception);
        }
    }

    public record Command(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID exportId,
            String purposeKey,
            String reason,
            String ifMatch,
            String idempotencyKey,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    public record DownloadCommand(
            UUID organizationId,
            UUID actorId,
            String correlationId,
            UUID exportId,
            Instant recentAuthenticationAt,
            Instant mfaAuthenticatedAt) {}

    public record Download(
            UUID exportId,
            String artifactDigest,
            String contentType,
            String filename,
            long byteCount,
            Instant expiresAt,
            EvidenceExportArtifactStore.ArtifactContent content) {}
}
