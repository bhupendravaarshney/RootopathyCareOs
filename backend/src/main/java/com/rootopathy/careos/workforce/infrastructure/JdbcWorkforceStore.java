package com.rootopathy.careos.workforce.infrastructure;

import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.platform.application.PlatformCapabilityRegistry;
import com.rootopathy.careos.platform.domain.CapabilityAvailability;
import com.rootopathy.careos.platform.domain.DocumentPromotionEvidence;
import com.rootopathy.careos.platform.domain.DocumentScanAttestation;
import com.rootopathy.careos.platform.domain.PlatformCapability;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.workforce.application.WorkforceException;
import com.rootopathy.careos.workforce.application.WorkforceEligibilityStore;
import com.rootopathy.careos.workforce.application.WorkforceMatchKeyCodec;
import com.rootopathy.careos.workforce.application.WorkforceOffboardingStore;
import com.rootopathy.careos.workforce.application.WorkforceSensitiveValueCodec;
import com.rootopathy.careos.workforce.application.WorkforceStore;
import com.rootopathy.careos.workforce.application.WorkforceStore.MutationEvidence;
import com.rootopathy.careos.workforce.application.WorkforceWorkerStore;
import com.rootopathy.careos.workforce.domain.WorkforceScreen;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcWorkforceStore
        implements WorkforceStore, WorkforceWorkerStore, WorkforceEligibilityStore,
                WorkforceOffboardingStore {
    private static final String EMPTY_DIGEST =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private final JdbcTemplate jdbc;
    private final PlatformCapabilityRegistry capabilities;
    private final WorkforceMatchKeyCodec matchKeys;
    private final WorkforceSensitiveValueCodec sensitiveValues;

    public JdbcWorkforceStore(
            JdbcTemplate jdbc,
            PlatformCapabilityRegistry capabilities,
            WorkforceMatchKeyCodec matchKeys,
            WorkforceSensitiveValueCodec sensitiveValues) {
        this.jdbc = jdbc;
        this.capabilities = capabilities;
        this.matchKeys = matchKeys;
        this.sensitiveValues = sensitiveValues;
    }

    @Override
    public Projection projection(AuthorizedTenantContext context, ScreenQuery query) {
        requireOperationScope();
        List<WorkforceScreen.Row> rows = switch (query.screenId()) {
            case "M2-01", "M2-02", "M2-03", "M2-21", "M2-23" ->
                memberRows(context, query);
            case "M2-24" -> offboardingRows(context, query);
            case "M2-04", "M2-05" -> personRows(context, query);
            case "M2-06" -> engagementRows(context, query);
            case "M2-07" -> practitionerRows(context, query);
            case "M2-08" -> qualificationRows(context, query);
            case "M2-09" -> registrationRows(context, query);
            case "M2-10", "M2-11", "M2-12" -> credentialRows(context, query);
            case "M2-13" -> specialtyRows(context, query);
            case "M2-14" -> scopeRows(context, query);
            case "M2-15", "M2-22" -> assignmentRows(context, query);
            case "M2-16" -> serviceAssignmentRows(context, query);
            case "M2-17", "M2-19" -> accessRows(context, query);
            case "M2-18" -> availabilityRows(context, query);
            case "M2-20" -> readinessRows(context, query);
            case "M2-25" -> expiryRows(context, query);
            case "M2-26" -> historyRows(context, query);
            case "M2-27" -> auditRows(context, query, false);
            case "M2-28" -> registryRows(context, query);
            case "M2-29" -> auditRows(context, query, true);
            default -> throw notFound("The requested workforce projection does not exist.");
        };
        if (Set.of("M2-26", "M2-27", "M2-29").contains(query.screenId())) {
            var combined = new ArrayList<WorkforceScreen.Row>(exportRows(context, query));
            combined.addAll(rows);
            rows = List.copyOf(combined.subList(0, Math.min(combined.size(), query.limit())));
        }
        rows = rows.stream()
                .filter(row -> row.memberId() == null || canAccessMember(context, row.memberId()))
                .toList();
        if (query.screenId().equals("M2-12")) {
            rows = rows.stream().map(row -> credentialEvidenceRow(context, row)).toList();
        }
        rows = rows.stream()
                .map(row -> withAllowedActions(query.screenId(), row))
                .toList();
        var columns = columns(query.screenId());
        var metrics = metrics(context, query.screenId(), rows);
        var notices = notices(query.screenId());
        var generatedAt = Objects.requireNonNull(
                        jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .toInstant();
        return new Projection(metrics, columns, rows, notices, generatedAt);
    }

    private WorkforceScreen.Row withAllowedActions(
            String screenId, WorkforceScreen.Row row) {
        var status = row.status();
        var actionContext = row.values().getOrDefault("$actionContext", "");
        var actions = new ArrayList<String>();
        switch (screenId) {
            case "M2-04" -> {
                if (Set.of("clear", "possible_match").contains(status)) {
                    actions.add("record-match-decision");
                } else if (status.equals("active")) {
                    actions.add("request-person-merge");
                } else if (status.equals("submitted")) {
                    if (actionContext.equals("maker")) {
                        actions.add("cancel-person-merge");
                    } else if (actionContext.equals("independent")) {
                        actions.addAll(List.of("approve-person-merge", "reject-person-merge"));
                    }
                } else if (status.equals("approved")) {
                    if (actionContext.equals("independent")) {
                        actions.add("execute-person-merge");
                    }
                }
            }
            case "M2-05" -> actions.add("save-identity");
            case "M2-06" -> {
                actions.add("save-engagement");
                if (Set.of("draft", "scheduled").contains(status)) {
                    actions.add("activate-engagement");
                }
            }
            case "M2-07" -> {
                actions.add("save-practitioner");
                if (status.equals("draft")) actions.add("activate-practitioner");
            }
            case "M2-08" -> {
                actions.add("add-qualification");
                if (status.equals("draft")) actions.add("submit-qualification");
                if (status.equals("submitted") && actionContext.equals("independent")) {
                    actions.add("decide-qualification");
                }
            }
            case "M2-09" -> {
                actions.add("add-registration");
                if (status.equals("draft")) actions.add("submit-registration");
                if (status.equals("submitted") && actionContext.equals("independent")) {
                    actions.add("verify-registration");
                }
                if (status.equals("verified")) actions.add("suspend-registration");
                if (Set.of("verified", "suspended").contains(status)) {
                    actions.add("revoke-registration");
                }
            }
            case "M2-10" -> {
                actions.add("create-credential");
                if (Set.of("draft", "evidence_pending").contains(status)) {
                    actions.addAll(List.of("upload-document", "submit-credential"));
                }
            }
            case "M2-11" -> {
                if (Set.of("submitted", "in_review").contains(status)
                        && actionContext.equals("claimable")) {
                    actions.add("claim-review");
                }
            }
            case "M2-12" -> {
                if (!"0".equals(row.values().getOrDefault("evidenceCount", "0"))) {
                    actions.add("access-credential-document");
                }
                if (status.equals("in_review") && actionContext.equals("reviewer")) {
                    actions.add("decide-credential");
                }
                if (status.equals("verified")) actions.add("suspend-credential");
                if (Set.of("verified", "suspended").contains(status)) {
                    actions.add("revoke-credential");
                }
            }
            case "M2-13" -> {
                actions.add("add-specialty");
                if (Set.of("draft", "scheduled").contains(status)) {
                    actions.add("activate-specialty");
                }
                if (Set.of("scheduled", "active").contains(status)) {
                    actions.add("end-specialty");
                }
            }
            case "M2-14" -> {
                actions.add("save-scope");
                if (status.equals("draft")) actions.add("submit-scope");
                if (status.equals("submitted") && actionContext.equals("independent")) {
                    actions.add("decide-scope");
                }
                if (Set.of("approved", "active").contains(status)) actions.add("suspend-scope");
                if (Set.of("approved", "active", "suspended").contains(status)) {
                    actions.add("end-scope");
                }
            }
            case "M2-15" -> {
                actions.add("save-assignment");
                if (Set.of("draft", "scheduled").contains(status)) {
                    actions.add("activate-assignment");
                }
            }
            case "M2-16" -> {
                actions.add("create-service-assignment");
                if (status.equals("scheduled")) {
                    actions.addAll(List.of("activate-service-assignment", "cancel-service-assignment"));
                }
                if (status.equals("active")) actions.add("suspend-service-assignment");
                if (status.equals("suspended")) actions.add("reactivate-service-assignment");
                if (Set.of("scheduled", "active", "suspended").contains(status)) {
                    actions.add("end-service-assignment");
                }
            }
            case "M2-18" -> {
                actions.add("save-availability");
                if (Set.of("draft", "scheduled").contains(status)) {
                    actions.add("activate-availability");
                }
            }
            case "M2-19" -> {
                if (!status.equals("offboarded")) actions.add("link-existing-account");
            }
            case "M2-20" -> {
                var context = row.values().getOrDefault("context", "");
                var primary = row.values().getOrDefault("primary", "");
                if (context.equals("Workforce member")) {
                    if (Set.of("draft", "submitted", "suspended").contains(status)) {
                        actions.add("run-readiness");
                    }
                } else if (primary.startsWith("Readiness ") && status.equals("complete")) {
                    actions.add("submit-activation");
                } else if (primary.startsWith("Activation ")) {
                    if (status.equals("submitted") && actionContext.equals("independent")) {
                        actions.add("approve-activation");
                    }
                    if (status.equals("approved") && actionContext.equals("independent")) {
                        actions.add("execute-activation");
                    }
                }
            }
            case "M2-22" -> {
                if (Set.of("scheduled", "active").contains(status)) {
                    actions.addAll(List.of("transfer-assignment", "end-assignment"));
                }
                if (status.equals("active")) actions.add("suspend-assignment");
                if (status.equals("suspended")) {
                    actions.addAll(List.of("reactivate-assignment", "end-assignment"));
                }
                if (status.equals("scheduled")) actions.add("cancel-assignment");
            }
            case "M2-23" -> {
                if (status.equals("active")) actions.add("suspend-member");
                if (status.equals("suspended")) actions.add("reactivate-member");
            }
            case "M2-24" -> {
                var primary = row.values().getOrDefault("primary", "");
                if (!primary.endsWith(" · offboarding")
                        && Set.of("active", "suspended").contains(status)) {
                    actions.add("request-offboarding");
                } else if (status.equals("submitted") && actionContext.equals("independent")) {
                    actions.add("approve-offboarding");
                }
            }
            case "M2-25" -> actions.add("escalate-expiry");
            case "M2-26" -> exportActions(status, actionContext, actions, false);
            case "M2-27" -> {
                if (status.equals("recorded")) actions.add("access-evidence");
                exportActions(status, actionContext, actions, true);
            }
            case "M2-28" -> {
                actions.add("create-registry-version");
                if (Set.of("draft", "validating", "ready").contains(status)
                        && actionContext.equals("maker")) {
                    actions.add("submit-registry-change");
                }
                if (status.equals("submitted") && actionContext.equals("independent")) {
                    actions.addAll(List.of("approve-registry-change", "reject-registry-change"));
                }
                if (status.equals("approved") && actionContext.equals("independent")) {
                    actions.add("activate-registry-change");
                }
            }
            case "M2-29" -> {
                if (status.equals("recorded")) actions.add("access-evidence");
                exportActions(status, actionContext, actions, false);
            }
            default -> {
                // Screens with link-only or non-target actions need no row action projection.
            }
        }
        var publicValues = new LinkedHashMap<>(row.values());
        publicValues.remove("$actionContext");
        return new WorkforceScreen.Row(
                row.id(),
                row.memberId(),
                row.status(),
                row.revision(),
                row.etag(),
                publicValues,
                actions);
    }

    private static void exportActions(
            String status, String actionContext, List<String> actions, boolean approvalSupported) {
        if (status.equals("failed") && actionContext.equals("owner_retryable")) {
            actions.add("retry-export");
        }
        if (status.equals("ready") && actionContext.equals("owner")) {
            actions.add("access-export");
        }
        if (approvalSupported && status.equals("requested")
                && actionContext.equals("independent")) {
            actions.addAll(List.of("approve-export", "deny-export"));
        }
    }

    private WorkforceScreen.Row credentialEvidenceRow(
            AuthorizedTenantContext context, WorkforceScreen.Row row) {
        var evidence = jdbc.query(
                """
                SELECT document.id,document.declared_media_type,document.declared_size,
                       document.declared_sha256,document.promoted_evidence_digest
                FROM credential_documents document
                JOIN document_promotion_evidence promotion
                  ON promotion.organization_id=document.organization_id
                 AND promotion.document_id=document.platform_document_id
                 AND promotion.object_version_id=document.platform_object_version_id
                WHERE document.organization_id=? AND document.practitioner_credential_id=?
                  AND document.status='clean' AND document.promoted_evidence_digest IS NOT NULL
                ORDER BY document.uploaded_at,document.id
                LIMIT 32
                """,
                (resultSet, rowNumber) -> new CredentialEvidenceSummary(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("declared_media_type"),
                        resultSet.getLong("declared_size"),
                        resultSet.getString("declared_sha256"),
                        resultSet.getString("promoted_evidence_digest")),
                context.organizationId(),
                row.id());
        var values = new LinkedHashMap<>(row.values());
        values.put("evidenceCount", Integer.toString(evidence.size()));
        for (var index = 0; index < evidence.size(); index++) {
            var item = evidence.get(index);
            values.put("evidence." + index + ".id", item.documentId().toString());
            values.put(
                    "evidence." + index + ".label",
                    item.mediaType() + " · " + item.byteCount() + " bytes · sha256 "
                            + item.declaredDigest().substring(0, 12) + "…");
            values.put("evidence." + index + ".digest", item.promotionDigest());
        }
        return new WorkforceScreen.Row(
                row.id(), row.memberId(), row.status(), row.revision(), row.etag(), values);
    }

    @Override
    public Set<String> permissions(AuthorizedTenantContext context) {
        return Set.copyOf(jdbc.queryForList(
                """
                SELECT DISTINCT grants.permission_key
                FROM organization_memberships memberships
                JOIN authorization_roles roles
                  ON roles.role_key=memberships.role_key
                JOIN authorization_role_permissions grants
                  ON grants.role_key=roles.role_key
                JOIN authorization_permissions permissions
                  ON permissions.permission_key=grants.permission_key
                WHERE memberships.organization_id = ?
                  AND memberships.user_id = ?
                  AND memberships.status = 'active'
                  AND memberships.effective_from <= clock_timestamp()
                  AND (memberships.effective_to IS NULL OR memberships.effective_to > clock_timestamp())
                  AND roles.status='active' AND roles.interactive
                  AND permissions.status='active'
                  AND EXISTS(SELECT 1 FROM authorization_registry_releases release
                      WHERE release.registry_version=roles.registry_version
                        AND release.status='active')
                  AND EXISTS(SELECT 1 FROM authorization_registry_releases release
                      WHERE release.registry_version=permissions.registry_version
                        AND release.status='active')
                  AND careos_m2_user_has_resource_scope(
                      memberships.organization_id,memberships.user_id,grants.permission_key)
                """,
                String.class,
                context.organizationId(),
                context.actorId()));
    }

    @Override
    public EvidenceDetail accessEvidence(
            AuthorizedTenantContext context, EvidenceAccessCommand command) {
        requireOperationScope();
        var memberProjection = command.projection().equals("member-evidence-detail-v1");
        if (!memberProjection && !command.projection().equals("workforce-audit-detail-v1")) {
            throw invalid("The restricted evidence projection is not supported.");
        }
        if (memberProjection) {
            if (command.memberId() == null) {
                throw invalid("memberId is required for member evidence detail.");
            }
            requireMember(context, command.memberId());
        }
        var detail = jdbc.query(
                """
                SELECT event.id,correlation.member_id,event.occurred_at,event.actor_user_id,
                       event.actor_kind,event.operation_key,event.event_name,event.schema_version,
                       event.subject_type,event.subject_id,event.correlation_id,event.payload::text
                FROM audit_events event
                JOIN audit_event_definitions definition
                  ON definition.event_name=event.event_name
                 AND definition.schema_version=event.schema_version
                 AND definition.status='active'
                 AND definition.registry_version='m2-candidate-1'
                CROSS JOIN LATERAL (
                    SELECT careos_m2_audit_event_member_id(
                        event.organization_id,event.subject_type,event.subject_id,event.payload)
                        AS member_id
                ) correlation
                WHERE event.organization_id=? AND event.id=?
                  AND (NOT ?::boolean OR correlation.member_id=?::uuid)
                """,
                resultSet -> resultSet.next()
                        ? new EvidenceDetail(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("member_id", UUID.class),
                                resultSet.getTimestamp("occurred_at").toInstant(),
                                resultSet.getObject("actor_user_id", UUID.class),
                                resultSet.getString("actor_kind"),
                                resultSet.getString("operation_key"),
                                resultSet.getString("event_name"),
                                resultSet.getInt("schema_version"),
                                resultSet.getString("subject_type"),
                                resultSet.getObject("subject_id", UUID.class),
                                resultSet.getString("correlation_id"),
                                resultSet.getString("payload"),
                                "m2-minimum-necessary-v1")
                        : null,
                context.organizationId(),
                command.evidenceId(),
                memberProjection,
                command.memberId());
        if (detail == null
                || (detail.memberId() != null && !canAccessMember(context, detail.memberId()))) {
            throw notFound("The workforce evidence record was not found.");
        }
        var sourcePermission = evidenceSourcePermission(detail.eventName());
        if (sourcePermission == null || !permissions(context).contains(sourcePermission)) {
            throw notFound("The workforce evidence record was not found.");
        }
        return detail;
    }

    private static String evidenceSourcePermission(String eventName) {
        if (eventName.startsWith("workforce.person_merge.")
                || eventName.startsWith("workforce.person.")) {
            return "workforce.person.restricted_read";
        }
        if (eventName.startsWith("workforce.member.")
                || eventName.startsWith("workforce.identifier.")) {
            return "workforce.member.read";
        }
        if (eventName.startsWith("workforce.engagement.")) {
            return "workforce.engagement.read";
        }
        if (eventName.startsWith("practitioner.profile.")) {
            return "workforce.practitioner.read";
        }
        if (eventName.startsWith("credential.qualification.")) {
            return "credential.qualification.read";
        }
        if (eventName.startsWith("credential.registration.")) {
            return "credential.registration.read";
        }
        if (eventName.startsWith("credential.document.")) {
            return "credential.document.read";
        }
        if (eventName.startsWith("credential.review.")
                || eventName.startsWith("credential.record.")
                || eventName.startsWith("credential.legal_hold.")) {
            return "credential.record.read";
        }
        if (eventName.startsWith("credential.expiry.")
                || eventName.startsWith("workforce.notification.")) {
            return "workforce.expiry.read";
        }
        if (eventName.startsWith("practitioner.specialty.")) {
            return "practitioner.specialty.read";
        }
        if (eventName.startsWith("practitioner.scope.")) {
            return "practitioner.scope.read";
        }
        if (eventName.startsWith("workforce.assignment.")) {
            return "workforce.assignment.read";
        }
        if (eventName.startsWith("practitioner.service_assignment.")) {
            return "practitioner.service_assignment.read";
        }
        if (eventName.startsWith("practitioner.eligibility.")) {
            return "practitioner.eligibility.read";
        }
        if (eventName.startsWith("workforce.availability.")) {
            return "workforce.availability.read";
        }
        if (eventName.startsWith("workforce.account_link.")) {
            return "workforce.account_link.read";
        }
        if (eventName.startsWith("workforce.readiness.")
                || eventName.startsWith("workforce.activation.")) {
            return "workforce.readiness.read";
        }
        if (eventName.startsWith("workforce.offboarding.")) {
            return "workforce.member.read";
        }
        if (eventName.startsWith("workforce.registry.")) {
            return "workforce.registry.read";
        }
        if (eventName.startsWith("workforce.configuration.")) {
            return "workforce.history.read";
        }
        if (eventName.startsWith("workforce.export.")
                || eventName.startsWith("workforce.evidence.")) {
            return "workforce.audit.read";
        }
        return null;
    }

    @Override
    public CredentialDocument credentialDocument(
            AuthorizedTenantContext context, UUID credentialId, UUID documentId) {
        requireOperationScope();
        var document = jdbc.query(
                """
                SELECT document.id,document.practitioner_credential_id,
                       credential.workforce_member_id,document.platform_document_id,
                       document.platform_object_version_id,document.declared_media_type,
                       document.declared_size,document.promoted_evidence_digest
                FROM credential_documents document
                JOIN practitioner_credentials credential
                  ON credential.organization_id=document.organization_id
                 AND credential.id=document.practitioner_credential_id
                JOIN document_promotion_evidence promotion
                  ON promotion.organization_id=document.organization_id
                 AND promotion.document_id=document.platform_document_id
                 AND promotion.object_version_id=document.platform_object_version_id
                WHERE document.organization_id=?
                  AND document.practitioner_credential_id=? AND document.id=?
                  AND document.status='clean'
                  AND document.promoted_evidence_digest IS NOT NULL
                """,
                resultSet -> resultSet.next()
                        ? new CredentialDocument(
                                resultSet.getObject("practitioner_credential_id", UUID.class),
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("workforce_member_id", UUID.class),
                                new com.rootopathy.careos.platform.domain.DocumentObjectReference(
                                        context.organizationId(),
                                        resultSet.getObject("platform_document_id", UUID.class),
                                        resultSet.getObject("platform_object_version_id", UUID.class)),
                                resultSet.getString("declared_media_type"),
                                resultSet.getLong("declared_size"),
                                resultSet.getString("promoted_evidence_digest"))
                        : null,
                context.organizationId(),
                credentialId,
                documentId);
        if (document == null || !canAccessMember(context, document.memberId())) {
            throw notFound("The clean credential document was not found.");
        }
        return document;
    }

    @Override
    public ImpactAnalysis previewImpact(
            AuthorizedTenantContext context, MutationCommand command) {
        requireOperationScope();
        return switch (command.actionKey()) {
            case "request-person-merge" -> personMergeRequestImpact(context,command);
            case "suspend-registration" -> registrationLifecycleImpact(context,command,"suspended");
            case "revoke-registration" -> registrationLifecycleImpact(context,command,"revoked");
            case "suspend-credential" -> credentialLifecycleImpact(context,command,"suspended");
            case "revoke-credential" -> credentialLifecycleImpact(context,command,"revoked");
            case "suspend-scope" -> scopeLifecycleImpact(context,command,"suspended");
            case "end-scope" -> scopeLifecycleImpact(context,command,"ended");
            case "transfer-assignment" -> assignmentTransferImpact(context,command);
            case "suspend-assignment" -> assignmentLifecycleImpact(context,command,"suspended");
            case "reactivate-assignment" -> assignmentLifecycleImpact(context,command,"active");
            case "end-assignment" -> assignmentLifecycleImpact(context,command,"ended");
            case "suspend-member" -> memberLifecycleImpact(context,command,"active","suspended");
            case "reactivate-member" -> memberLifecycleImpact(context,command,"suspended","active");
            case "request-offboarding" -> offboardingRequestImpact(context,command);
            default -> throw invalid("The requested action does not define an impact preview.");
        };
    }

    @Override
    public MutationResult mutate(AuthorizedTenantContext context, MutationCommand command) {
        requireOperationScope();
        return switch (command.actionKey()) {
            case "start-onboarding" -> startOnboarding(context, command);
            case "record-match-decision" -> recordMatchDecision(context, command);
            case "request-person-merge" -> requestPersonMerge(context, command);
            case "approve-person-merge" -> decidePersonMerge(context, command, true);
            case "reject-person-merge" -> decidePersonMerge(context, command, false);
            case "cancel-person-merge" -> cancelPersonMerge(context, command);
            case "execute-person-merge" -> executePersonMerge(context, command);
            case "save-identity" -> saveIdentity(context, command);
            case "save-engagement" -> saveEngagement(context, command);
            case "activate-engagement" -> activateEngagement(context, command);
            case "save-practitioner" -> savePractitioner(context, command);
            case "activate-practitioner" -> activatePractitioner(context, command);
            case "add-qualification" -> addQualification(context, command);
            case "submit-qualification" -> transitionQualification(context, command, "draft", "submitted");
            case "decide-qualification" -> decideQualification(context, command);
            case "add-registration" -> addRegistration(context, command);
            case "submit-registration" -> transitionRegistration(context, command, "draft", "submitted");
            case "verify-registration" -> transitionRegistration(context, command, "submitted", "verified");
            case "suspend-registration" -> transitionRegistrationLifecycle(context, command, "suspended");
            case "revoke-registration" -> transitionRegistrationLifecycle(context, command, "revoked");
            case "create-credential" -> createCredential(context, command);
            case "submit-credential" -> submitCredential(context, command);
            case "claim-review" -> claimReview(context, command);
            case "decide-credential" -> decideCredential(context, command);
            case "suspend-credential" -> transitionCredentialLifecycle(context, command, "suspended");
            case "revoke-credential" -> transitionCredentialLifecycle(context, command, "revoked");
            case "add-specialty" -> addSpecialty(context, command);
            case "activate-specialty" -> activateSpecialty(context, command);
            case "end-specialty" -> endSpecialty(context, command);
            case "save-scope" -> saveScope(context, command);
            case "submit-scope" -> submitScope(context, command);
            case "decide-scope" -> decideScope(context, command);
            case "suspend-scope" -> transitionScopeLifecycle(context, command, "suspended");
            case "end-scope" -> transitionScopeLifecycle(context, command, "ended");
            case "save-assignment" -> saveAssignment(context, command);
            case "activate-assignment" -> activateAssignment(context, command);
            case "create-service-assignment" -> createServiceAssignment(context, command);
            case "activate-service-assignment" -> activateServiceAssignment(context, command);
            case "reactivate-service-assignment" -> activateServiceAssignment(context, command);
            case "suspend-service-assignment" -> transitionServiceAssignment(context, command, "suspended");
            case "end-service-assignment" -> transitionServiceAssignment(context, command, "ended");
            case "cancel-service-assignment" -> transitionServiceAssignment(context, command, "cancelled");
            case "link-existing-account" -> linkExistingAccount(context, command);
            case "save-availability" -> saveAvailability(context, command);
            case "activate-availability" -> activateAvailability(context, command);
            case "run-readiness" -> runReadiness(context, command);
            case "submit-activation" -> submitActivation(context, command);
            case "approve-activation" -> approveActivation(context, command);
            case "execute-activation" -> executeActivation(context, command);
            case "transfer-assignment" -> transferAssignment(context, command);
            case "suspend-assignment" -> transitionAssignmentLifecycle(context, command, "suspended");
            case "reactivate-assignment" -> transitionAssignmentLifecycle(context, command, "active");
            case "end-assignment" -> transitionAssignmentLifecycle(context, command, "ended");
            case "cancel-assignment" -> transitionAssignmentLifecycle(context, command, "cancelled");
            case "suspend-member" -> lifecycle(context, command, "active", "suspended");
            case "reactivate-member" -> lifecycle(context, command, "suspended", "active");
            case "request-offboarding" -> requestOffboarding(context, command);
            case "approve-offboarding" -> approveOffboarding(context, command);
            case "execute-offboarding" -> executeOffboarding(context, command);
            case "escalate-expiry" -> escalateExpiry(context, command);
            case "request-export" -> requestExport(context, command);
            case "retry-export" -> retryExport(context, command);
            case "approve-export" -> decideExport(context, command, true);
            case "deny-export" -> decideExport(context, command, false);
            case "create-registry-change" -> createRegistryChange(context, command);
            case "create-registry-version" -> createRegistryVersionChange(context, command);
            case "submit-registry-change" -> submitRegistryChange(context, command);
            case "approve-registry-change" -> decideRegistryChange(context, command, true);
            case "reject-registry-change" -> decideRegistryChange(context, command, false);
            case "activate-registry-change" -> activateRegistryChange(context, command);
            default -> throw notFound("The requested workforce action does not exist.");
        };
    }

    @Override
    public boolean isDue(AuthorizedTenantContext context, UUID requestId, Instant now) {
        return exists(
                """
                SELECT EXISTS(SELECT 1 FROM workforce_offboarding_requests
                WHERE organization_id=? AND id=? AND effective_at<=?
                  AND (status IN ('approved','scheduled')
                    OR (status='failed' AND dead_lettered_at IS NULL AND next_attempt_at<=?)))
                """,
                context.organizationId(), requestId, Timestamp.from(now), Timestamp.from(now));
    }

    @Override
    public List<UUID> dueRequestIds(
            AuthorizedTenantContext context, Instant now, int maximumItems) {
        if (maximumItems < 1 || maximumItems > 100) {
            throw new IllegalArgumentException("maximumItems must be between 1 and 100");
        }
        return jdbc.queryForList(
                """
                SELECT id FROM workforce_offboarding_requests
                WHERE organization_id=? AND effective_at<=?
                  AND (status IN ('approved','scheduled')
                    OR (status='failed' AND dead_lettered_at IS NULL AND next_attempt_at<=?))
                ORDER BY effective_at,id LIMIT ?
                """,
                UUID.class, context.organizationId(), Timestamp.from(now), Timestamp.from(now),
                maximumItems);
    }

    @Override
    public MutationResult executeApproved(
            AuthorizedTenantContext context, UUID requestId, Instant now) {
        var request = requireOffboarding(context, requestId);
        return executeOffboarding(
                context,
                new MutationCommand(
                        "M2-24",
                        "execute-offboarding",
                        requestId,
                        request.memberId(),
                        request.revision(),
                        null,
                        "Execute the independently approved offboarding plan.",
                        Map.of(),
                        List.of(),
                        now));
    }

    @Override
    public MutationResult recordFailure(
            AuthorizedTenantContext context,
            UUID requestId,
            String failureCode,
            Instant failedAt) {
        if (failureCode == null || !failureCode.matches("[a-z][a-z0-9._:-]{1,119}")) {
            throw new IllegalArgumentException("invalid offboarding failure code");
        }
        var request = requireOffboarding(context, requestId);
        var revision = jdbc.query(
                """
                UPDATE workforce_offboarding_requests
                   SET status='failed',attempt_count=LEAST(attempt_count+1,5),failure_code=?,
                       next_attempt_at=CASE WHEN attempt_count+1<5
                           THEN ?+(power(2,attempt_count)::text||' minutes')::interval ELSE NULL END,
                       dead_lettered_at=CASE WHEN attempt_count+1>=5 THEN ? ELSE NULL END,
                       dead_letter_owner=CASE WHEN attempt_count+1>=5
                           THEN 'workforce_operations' ELSE NULL END,
                       lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                 WHERE organization_id=? AND id=? AND status IN ('approved','scheduled','failed')
                 RETURNING lock_version
                """,
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null,
                failureCode,
                Timestamp.from(failedAt),
                Timestamp.from(failedAt),
                context.actorId(),
                context.organizationId(),
                requestId);
        if (revision == null) {
            throw conflict("The offboarding request cannot accept failure evidence.");
        }
        var audit = ordered(
                "memberId",request.memberId(),
                "offboardingRequestId",requestId,
                "impactDigest",request.impactDigest(),
                "effectiveTime",request.effectiveAt(),
                "state","failed",
                "failureCode",failureCode);
        var outbox = ordered(
                "memberId",request.memberId(),
                "offboardingRequestId",requestId,
                "impactDigest",request.impactDigest(),
                "effectiveTime",request.effectiveAt(),
                "state","failed");
        return result(
                requestId,"workforce_offboarding_request","workforce.offboarding.failed",
                "workforce.offboarding.failed","workforce_offboarding_request",
                audit,outbox,200,revision);
    }

    @Override
    public MutationResult bindCredentialDocument(
            AuthorizedTenantContext context,
            DocumentCommand command,
            UUID platformDocumentId,
            UUID platformObjectVersionId,
            Instant quarantinedAt) {
        var credential=requireCredential(context,command.credentialId());
        if (!Set.of("draft","evidence_pending").contains(credential.status())) {
            throw conflict("Evidence must be uploaded to a new draft credential revision.");
        }
        var documentId = UuidV7Generator.randomUuid();
        var updated = jdbc.update(
                """
                INSERT INTO credential_documents(
                    id,organization_id,practitioner_credential_id,platform_document_id,
                    platform_object_version_id,declared_media_type,declared_file_name,
                    declared_size,declared_sha256,retention_class,uploaded_at,status,
                    created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'quarantined',?,?)
                """,
                documentId,
                context.organizationId(),
                command.credentialId(),
                platformDocumentId,
                platformObjectVersionId,
                command.mediaType(),
                command.fileName(),
                command.declaredSize(),
                command.sha256(),
                command.retentionClass(),
                Timestamp.from(quarantinedAt),
                context.actorId(),
                context.actorId());
        if (updated != 1) {
            throw conflict("Credential document evidence could not be bound.");
        }
        var credentialChanged = jdbc.update(
                """
                UPDATE practitioner_credentials
                SET status = CASE WHEN status='draft' THEN 'evidence_pending' ELSE status END,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                  AND status IN ('draft','evidence_pending')
                """,
                context.actorId(),
                context.organizationId(),
                command.credentialId(),
                credential.revision());
        requireChanged(credentialChanged, "The credential changed before document evidence was bound.");
        var evidenceCount = Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT count(*) FROM credential_documents
                WHERE organization_id=? AND practitioner_credential_id=?
                """,
                Integer.class,
                context.organizationId(),
                command.credentialId()));
        var audit = ordered(
                "credentialId", command.credentialId(),
                "documentId", documentId,
                "declaredType", command.mediaType(),
                "declaredSize", command.declaredSize(),
                "digest", command.sha256(),
                "state", "quarantined");
        var outbox = ordered(
                "credentialId", command.credentialId(),
                "documentId", documentId,
                "digest", command.sha256(),
                "state", "quarantined");
        var intentPayload = ordered(
                "credentialId", command.credentialId(),
                "documentId", documentId,
                "declaredType", command.mediaType(),
                "declaredSize", command.declaredSize(),
                "digest", command.sha256(),
                "state", "intent_created");
        var uploadedPayload = ordered(
                "credentialId", command.credentialId(),
                "documentId", documentId,
                "declaredType", command.mediaType(),
                "declaredSize", command.declaredSize(),
                "digest", command.sha256(),
                "state", "uploaded");
        var credentialPayload = ordered(
                "practitionerId", credential.practitionerId(),
                "credentialId", credential.id(),
                "fromState", credential.status(),
                "toState", credential.status().equals("draft") ? "evidence_pending" : credential.status(),
                "evidenceCount", evidenceCount,
                "revision", credential.revision() + 1);
        return result(
                documentId,
                "credential_document",
                "credential.document.quarantined",
                "credential.document.quarantined",
                "credential_document",
                audit,
                outbox,
                201,
                0,
                List.of(
                        new MutationEvidence(
                                documentId,
                                "credential_document",
                                "credential.document.intent_created",
                                null,
                                null,
                                intentPayload,
                                Map.of()),
                        new MutationEvidence(
                                documentId,
                                "credential_document",
                                "credential.document.uploaded",
                                null,
                                null,
                                uploadedPayload,
                                Map.of()),
                        new MutationEvidence(
                                credential.id(),
                                "practitioner_credential",
                                "credential.record.updated",
                                null,
                                null,
                                credentialPayload,
                                Map.of())));
    }

    @Override
    public DocumentWork document(AuthorizedTenantContext context, UUID documentId) {
        var work = jdbc.query(
                """
                SELECT document.id,document.practitioner_credential_id,
                       credential.workforce_member_id,document.platform_document_id,
                       document.platform_object_version_id,document.declared_sha256,
                       document.lock_version,
                       COALESCE((SELECT max(attempt_number) FROM credential_scan_attempts attempt
                                 WHERE attempt.organization_id=document.organization_id
                                   AND attempt.credential_document_id=document.id),0)+1 AS next_attempt
                FROM credential_documents document
                JOIN practitioner_credentials credential
                  ON credential.organization_id=document.organization_id
                 AND credential.id=document.practitioner_credential_id
                WHERE document.organization_id=? AND document.id=?
                  AND document.status IN ('quarantined','failed')
                FOR UPDATE OF document
                """,
                resultSet -> resultSet.next()
                        ? new DocumentWork(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("practitioner_credential_id", UUID.class),
                                resultSet.getObject("workforce_member_id", UUID.class),
                                new com.rootopathy.careos.platform.domain.DocumentObjectReference(
                                        context.organizationId(),
                                        resultSet.getObject("platform_document_id", UUID.class),
                                        resultSet.getObject("platform_object_version_id", UUID.class)),
                                resultSet.getString("declared_sha256"),
                                resultSet.getInt("next_attempt"),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),
                documentId);
        if (work == null) {
            throw notFound("The credential document is not available for scanning.");
        }
        return work;
    }

    @Override
    public DocumentBinding bindScan(
            AuthorizedTenantContext context,
            DocumentWork document,
            DocumentScanAttestation scan,
            DocumentPromotionEvidence promotion) {
        var outcome = switch (scan.result().verdict()) {
            case CLEAN -> "clean";
            case INFECTED -> "infected";
            case ERROR -> "failed";
        };
        if (outcome.equals("clean") && promotion == null) {
            throw conflict("Clean scan evidence must be promoted before it can be bound.");
        }
        if (!scan.result().document().equals(document.object())
                || (scan.result().verdict()
                                != com.rootopathy.careos.platform.domain.MalwareScanVerdict.ERROR
                        && !scan.result().sha256().equals(document.declaredDigest()))) {
            throw conflict("The scan evidence does not match the declared document object.");
        }
        var failureCode = outcome.equals("failed") ? "scanner_error" : null;
        var attemptId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO credential_scan_attempts(
                    id,organization_id,credential_document_id,platform_scan_attestation_id,
                    attempt_number,scanner_identity,scanner_version,signature_version,
                    started_at,completed_at,outcome,failure_code,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                attemptId,
                context.organizationId(),
                document.documentId(),
                scan.attestationId(),
                document.nextAttempt(),
                scan.result().scannerKey(),
                scan.result().definitionsVersion(),
                "platform-attestation-v1",
                Timestamp.from(scan.result().scannedAt()),
                Timestamp.from(scan.recordedAt()),
                outcome,
                failureCode,
                outcome,
                context.actorId(),
                context.actorId());
        var promotionDigest = promotion == null
                ? null
                : digest(promotion.document()
                        + "|"
                        + promotion.scanAttestation().attestationId()
                        + "|"
                        + promotion.policyKey()
                        + "|"
                        + promotion.promotedAt());
        var changed = jdbc.update(
                """
                UPDATE credential_documents
                SET status=?,promoted_evidence_digest=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                  AND status IN ('quarantined','failed')
                """,
                outcome,
                promotionDigest,
                context.actorId(),
                context.organizationId(),
                document.documentId(),
                document.revision());
        requireChanged(changed, "The credential document changed before scan evidence was bound.");
        return new DocumentBinding(
                document.documentId(),
                document.credentialId(),
                attemptId,
                document.declaredDigest(),
                outcome,
                failureCode,
                document.revision() + 1);
    }

    @Override
    public WorkforceEligibilityStore.Evaluation evaluate(
            AuthorizedTenantContext context, WorkforceEligibilityStore.Command command) {
        var contextId = command.locationId() == null ? command.facilityId() : command.locationId();
        var memberId=lockPractitionerForEligibility(context,command.practitionerId());
        Objects.requireNonNull(command.activityEntryId(),"activityEntryId");
        if (command.evaluatedTo()!=null && !command.evaluatedTo().isAfter(command.evaluatedFrom())) {
            throw invalid("The eligibility evaluation end must follow its start.");
        }
        var coverageEnd=command.evaluatedTo()==null?command.evaluatedFrom():command.evaluatedTo();
        var activeContext = exists(
                """
                SELECT EXISTS(SELECT 1 FROM practitioner_profiles practitioner
                JOIN workforce_members member ON member.organization_id=practitioner.organization_id
                  AND member.id=practitioner.workforce_member_id
                JOIN organizations organization ON organization.id=practitioner.organization_id
                JOIN service_definitions service ON service.organization_id=practitioner.organization_id
                  AND service.id=?
                JOIN facilities facility ON facility.organization_id=practitioner.organization_id
                  AND facility.id=?
                WHERE practitioner.organization_id=? AND practitioner.id=?
                  AND practitioner.status='active' AND member.lifecycle_state='active'
                  AND organization.status='active' AND service.status='active' AND facility.status='active'
                  AND practitioner.effective_from<=?
                  AND (practitioner.effective_to IS NULL OR practitioner.effective_to>=?)
                  AND (?::uuid IS NULL OR EXISTS(
                      SELECT 1 FROM service_locations location
                      WHERE location.organization_id=practitioner.organization_id
                        AND location.id=? AND location.facility_id=facility.id
                        AND location.status='active' AND location.effective_from<=?
                        AND (location.effective_to IS NULL OR location.effective_to>?)))
                """,
                command.serviceId(), command.facilityId(), context.organizationId(), command.practitionerId(),
                Timestamp.from(command.evaluatedFrom()), Timestamp.from(coverageEnd),
                command.locationId(),command.locationId(),Timestamp.from(command.evaluatedFrom()),
                Timestamp.from(coverageEnd));
        var engagementId = jdbc.query(
                """
                SELECT id FROM employment_engagements
                WHERE organization_id=? AND workforce_member_id=? AND status='active'
                  AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?)
                ORDER BY effective_from DESC,id DESC LIMIT 1
                """,
                resultSet -> resultSet.next()?resultSet.getObject(1,UUID.class):null,
                context.organizationId(),memberId,Timestamp.from(command.evaluatedFrom()),
                Timestamp.from(coverageEnd));
        var registrationId = jdbc.query(
                """
                SELECT registration.id FROM professional_registrations registration
                JOIN workforce_registry_versions regulator_version
                  ON regulator_version.organization_id=registration.organization_id
                 AND regulator_version.id=registration.regulator_version_id
                 AND regulator_version.registry_entry_id=registration.regulator_entry_id
                JOIN workforce_registry_versions type_version
                  ON type_version.organization_id=registration.organization_id
                 AND type_version.id=registration.registration_type_version_id
                 AND type_version.registry_entry_id=registration.registration_type_entry_id
                WHERE registration.organization_id=? AND registration.practitioner_profile_id=?
                  AND registration.status='verified' AND registration.valid_from<=?::date
                  AND (registration.expires_on IS NULL OR registration.expires_on>=?::date)
                  AND regulator_version.status='active' AND regulator_version.effective_from<=?
                  AND (regulator_version.effective_to IS NULL OR regulator_version.effective_to>=?)
                  AND type_version.status='active' AND type_version.effective_from<=?
                  AND (type_version.effective_to IS NULL OR type_version.effective_to>=?)
                ORDER BY registration.expires_on NULLS LAST,registration.id LIMIT 1
                """,
                resultSet -> resultSet.next()?resultSet.getObject(1,UUID.class):null,
                context.organizationId(), command.practitionerId(),
                Timestamp.from(command.evaluatedFrom()), Timestamp.from(coverageEnd),
                Timestamp.from(command.evaluatedFrom()), Timestamp.from(coverageEnd),
                Timestamp.from(command.evaluatedFrom()), Timestamp.from(coverageEnd));
        var qualificationId = jdbc.query(
                """
                SELECT qualification.id FROM qualifications qualification
                JOIN workforce_registry_versions version
                  ON version.organization_id=qualification.organization_id
                 AND version.id=qualification.qualification_version_id
                 AND version.registry_entry_id=qualification.qualification_entry_id
                WHERE qualification.organization_id=? AND qualification.workforce_member_id=?
                  AND qualification.status='verified'
                  AND (qualification.expires_on IS NULL OR qualification.expires_on>=?::date)
                  AND version.status='active' AND version.effective_from<=?
                  AND (version.effective_to IS NULL OR version.effective_to>=?)
                ORDER BY qualification.expires_on NULLS LAST,qualification.id LIMIT 1
                """,
                resultSet -> resultSet.next()?resultSet.getObject(1,UUID.class):null,
                context.organizationId(),memberId,Timestamp.from(coverageEnd),
                Timestamp.from(command.evaluatedFrom()),Timestamp.from(coverageEnd));
        var credentialId = jdbc.query(
                """
                SELECT credential.id FROM practitioner_credentials credential
                JOIN workforce_registry_versions version
                  ON version.organization_id=credential.organization_id
                 AND version.id=credential.credential_type_version_id
                 AND version.registry_entry_id=credential.credential_type_entry_id
                JOIN credential_verifications verification
                  ON verification.organization_id=credential.organization_id
                 AND verification.id=credential.current_verification_id
                WHERE credential.organization_id=? AND credential.practitioner_profile_id=?
                  AND credential.status='verified'
                  AND (credential.expires_on IS NULL OR credential.expires_on>=?::date)
                  AND version.status='active' AND version.effective_from<=?
                  AND (version.effective_to IS NULL OR version.effective_to>=?)
                  AND verification.status='verified' AND verification.decision='verified'
                  AND cardinality(verification.evidence_ids)>0
                  AND NOT EXISTS(
                      SELECT 1 FROM unnest(verification.evidence_ids) evidence_id
                      LEFT JOIN credential_documents document
                        ON document.organization_id=credential.organization_id
                       AND document.id=evidence_id
                       AND document.practitioner_credential_id=credential.id
                      LEFT JOIN document_promotion_evidence promotion
                        ON promotion.organization_id=document.organization_id
                       AND promotion.document_id=document.platform_document_id
                       AND promotion.object_version_id=document.platform_object_version_id
                      WHERE document.id IS NULL OR document.status<>'clean'
                         OR document.promoted_evidence_digest IS NULL OR promotion.id IS NULL)
                ORDER BY credential.expires_on NULLS LAST,credential.id LIMIT 1
                """,
                resultSet -> resultSet.next()?resultSet.getObject(1,UUID.class):null,
                context.organizationId(),command.practitionerId(),Timestamp.from(coverageEnd),
                Timestamp.from(command.evaluatedFrom()),Timestamp.from(coverageEnd));
        var scope = jdbc.query(
                """
                SELECT scope.id,activity.id,
                       activity.supervision_mode_entry_id IS NOT NULL AS supervision_required
                FROM scopes_of_practice scope
                JOIN scope_activities activity
                  ON activity.organization_id=scope.organization_id
                 AND activity.scope_of_practice_id=scope.id
                JOIN scope_definitions definition
                  ON definition.organization_id=scope.organization_id
                 AND definition.id=scope.scope_definition_id
                JOIN workforce_registry_versions activity_version
                  ON activity_version.organization_id=activity.organization_id
                 AND activity_version.id=activity.activity_version_id
                 AND activity_version.registry_entry_id=activity.activity_entry_id
                WHERE scope.organization_id=? AND scope.practitioner_profile_id=?
                  AND scope.lifecycle_state='approved' AND definition.status='active'
                  AND activity.status='active' AND activity_version.status='active'
                  AND activity.activity_entry_id=?
                  AND (activity.service_id IS NULL OR activity.service_id=?)
                  AND (activity.facility_id IS NULL OR activity.facility_id=?)
                  AND (activity.location_id IS NULL OR activity.location_id IS NOT DISTINCT FROM ?)
                  AND scope.effective_from<=? AND (scope.effective_to IS NULL OR scope.effective_to>=?)
                  AND activity.effective_from<=?
                  AND (activity.effective_to IS NULL OR activity.effective_to>=?)
                  AND activity_version.effective_from<=?
                  AND (activity_version.effective_to IS NULL OR activity_version.effective_to>=?)
                ORDER BY scope.effective_from DESC,scope.id DESC,activity.id LIMIT 1
                """,
                resultSet -> resultSet.next()
                        ? new EligibilityScope(
                                resultSet.getObject(1,UUID.class),resultSet.getObject(2,UUID.class),
                                resultSet.getBoolean(3))
                        : null,
                context.organizationId(),command.practitionerId(),command.activityEntryId(),
                command.serviceId(),command.facilityId(),command.locationId(),
                Timestamp.from(command.evaluatedFrom()),Timestamp.from(coverageEnd),
                Timestamp.from(command.evaluatedFrom()),Timestamp.from(coverageEnd),
                Timestamp.from(command.evaluatedFrom()),Timestamp.from(coverageEnd));
        var requirementsComplete = scope != null
                && scopeRequirementsMet(
                        context, requireScope(context, scope.id()),
                        command.evaluatedFrom(), coverageEnd);
        var restrictionsClear=scope!=null && !exists(
                """
                SELECT EXISTS(SELECT 1 FROM scope_restrictions
                WHERE organization_id=? AND scope_of_practice_id=? AND status='active'
                  AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?))
                """,
                context.organizationId(),scope.id(),Timestamp.from(command.evaluatedFrom()),
                Timestamp.from(coverageEnd));
        var assignmentId = jdbc.query(
                """
                SELECT id FROM workforce_assignments
                WHERE organization_id=? AND workforce_member_id=? AND facility_id=?
                  AND (location_id IS NULL OR location_id IS NOT DISTINCT FROM ?)
                  AND lifecycle_state='active' AND effective_from<=?
                  AND (effective_to IS NULL OR effective_to>=?)
                ORDER BY primary_assignment DESC,effective_from DESC,id LIMIT 1
                """,
                resultSet -> resultSet.next()?resultSet.getObject(1,UUID.class):null,
                context.organizationId(), memberId, command.facilityId(), command.locationId(),
                Timestamp.from(command.evaluatedFrom()), Timestamp.from(coverageEnd));
        var serviceAssignmentId = jdbc.query(
                """
                SELECT id FROM practitioner_service_assignments
                WHERE organization_id=? AND practitioner_profile_id=? AND service_id=?
                  AND facility_id=? AND location_id IS NOT DISTINCT FROM ?
                  AND scope_of_practice_id=?
                  AND supervisor_practitioner_id IS NOT DISTINCT FROM ?
                  AND lifecycle_state IN ('draft','scheduled','active','suspended')
                  AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?)
                ORDER BY CASE lifecycle_state WHEN 'active' THEN 0 WHEN 'scheduled' THEN 1
                         WHEN 'suspended' THEN 2 ELSE 3 END,
                         effective_from DESC,id LIMIT 1
                """,
                resultSet -> resultSet.next()?resultSet.getObject(1,UUID.class):null,
                context.organizationId(),command.practitionerId(),command.serviceId(),
                command.facilityId(),command.locationId(),scope==null?null:scope.id(),
                command.supervisorPractitionerId(),Timestamp.from(command.evaluatedFrom()),
                Timestamp.from(coverageEnd));
        var supervisionComplete=scope==null || !scope.supervisionRequired() || (
                command.supervisorPractitionerId()!=null
                && !command.supervisorPractitionerId().equals(command.practitionerId())
                && exists(
                        """
                        SELECT EXISTS(SELECT 1 FROM practitioner_profiles supervisor
                        JOIN workforce_members member
                          ON member.organization_id=supervisor.organization_id
                         AND member.id=supervisor.workforce_member_id
                        JOIN workforce_assignments assignment
                          ON assignment.organization_id=member.organization_id
                         AND assignment.workforce_member_id=member.id
                        WHERE supervisor.organization_id=? AND supervisor.id=?
                          AND supervisor.status='active' AND member.lifecycle_state='active'
                          AND assignment.facility_id=? AND assignment.lifecycle_state='active'
                          AND assignment.effective_from<=?
                          AND (assignment.effective_to IS NULL OR assignment.effective_to>=?)
                          AND EXISTS(SELECT 1 FROM employment_engagements engagement
                              WHERE engagement.organization_id=supervisor.organization_id
                                AND engagement.workforce_member_id=member.id
                                AND engagement.status='active' AND engagement.effective_from<=?
                                AND (engagement.effective_to IS NULL OR engagement.effective_to>=?))
                          AND EXISTS(SELECT 1 FROM professional_registrations registration
                              WHERE registration.organization_id=supervisor.organization_id
                                AND registration.practitioner_profile_id=supervisor.id
                                AND registration.status='verified'
                                AND registration.valid_from<=?::date
                                AND (registration.expires_on IS NULL OR registration.expires_on>=?::date))
                          AND EXISTS(SELECT 1 FROM practitioner_credentials credential
                              JOIN credential_verifications verification
                                ON verification.organization_id=credential.organization_id
                               AND verification.id=credential.current_verification_id
                              WHERE credential.organization_id=supervisor.organization_id
                                AND credential.practitioner_profile_id=supervisor.id
                                AND credential.status='verified'
                                AND (credential.expires_on IS NULL OR credential.expires_on>=?::date)
                                AND verification.status='verified' AND verification.decision='verified')
                          AND EXISTS(SELECT 1 FROM scopes_of_practice supervisor_scope
                              JOIN scope_activities supervisor_activity
                                ON supervisor_activity.organization_id=supervisor_scope.organization_id
                               AND supervisor_activity.scope_of_practice_id=supervisor_scope.id
                              WHERE supervisor_scope.organization_id=supervisor.organization_id
                                AND supervisor_scope.practitioner_profile_id=supervisor.id
                                AND supervisor_scope.lifecycle_state='approved'
                                AND supervisor_activity.status='active'
                                AND supervisor_activity.activity_entry_id=?
                                AND (supervisor_activity.service_id IS NULL
                                  OR supervisor_activity.service_id=?)
                                AND (supervisor_activity.facility_id IS NULL
                                  OR supervisor_activity.facility_id=?)
                                AND (supervisor_activity.location_id IS NULL
                                  OR supervisor_activity.location_id IS NOT DISTINCT FROM ?)
                                AND supervisor_scope.effective_from<=?
                                AND (supervisor_scope.effective_to IS NULL OR supervisor_scope.effective_to>=?)
                                AND supervisor_activity.effective_from<=?
                                AND (supervisor_activity.effective_to IS NULL
                                  OR supervisor_activity.effective_to>=?)
                                AND NOT EXISTS(SELECT 1 FROM scope_restrictions restriction
                                    WHERE restriction.organization_id=supervisor_scope.organization_id
                                      AND restriction.scope_of_practice_id=supervisor_scope.id
                                      AND restriction.status='active'
                                      AND restriction.effective_from<=?
                                      AND (restriction.effective_to IS NULL
                                        OR restriction.effective_to>=?))))
                        """,
                        context.organizationId(),command.supervisorPractitionerId(),command.facilityId(),
                        Timestamp.from(command.evaluatedFrom()),Timestamp.from(coverageEnd),
                        Timestamp.from(command.evaluatedFrom()),Timestamp.from(coverageEnd),
                        Timestamp.from(command.evaluatedFrom()),Timestamp.from(coverageEnd),
                        Timestamp.from(coverageEnd),command.activityEntryId(),command.serviceId(),
                        command.facilityId(),command.locationId(),
                        Timestamp.from(command.evaluatedFrom()),Timestamp.from(coverageEnd),
                        Timestamp.from(command.evaluatedFrom()),Timestamp.from(coverageEnd),
                        Timestamp.from(command.evaluatedFrom()),Timestamp.from(coverageEnd)));
        var evidenceSnapshot = eligibilityEvidenceSnapshot(
                context, registrationId, qualificationId, credentialId,
                scope == null ? null : scope.id(), scope == null ? null : scope.activityId(),
                assignmentId, engagementId);
        var hardComplete=activeContext && engagementId!=null && registrationId!=null
                && qualificationId!=null && credentialId!=null && scope!=null
                && requirementsComplete && assignmentId!=null && serviceAssignmentId!=null
                && supervisionComplete;
        var outcome=!hardComplete?"ineligible":restrictionsClear?"eligible":"indeterminate";
        var reasons = new ArrayList<String>();
        if (!activeContext) reasons.add("inactive_context");
        if (engagementId==null) reasons.add("engagement_not_current");
        if (registrationId==null) reasons.add("registration_not_current");
        if (qualificationId==null) reasons.add("qualification_not_current");
        if (credentialId==null) reasons.add("credential_not_current");
        if (scope==null) reasons.add("activity_scope_not_approved");
        if (scope!=null && !requirementsComplete) reasons.add("scope_requirements_not_current");
        if (!restrictionsClear && scope!=null) reasons.add("restriction_context_indeterminate");
        if (assignmentId==null) reasons.add("hierarchy_assignment_missing");
        if (serviceAssignmentId==null) reasons.add("service_assignment_missing");
        if (!supervisionComplete) reasons.add("supervision_not_resolved");
        var expiresAt = command.now().plusSeconds(900);
        if (command.evaluatedTo() != null
                && command.evaluatedTo().isAfter(command.now())
                && command.evaluatedTo().isBefore(expiresAt)) {
            expiresAt = command.evaluatedTo();
        }
        if (evidenceSnapshot.dependencyExpiresAt() != null
                && evidenceSnapshot.dependencyExpiresAt().isAfter(command.now())
                && evidenceSnapshot.dependencyExpiresAt().isBefore(expiresAt)) {
            expiresAt = evidenceSnapshot.dependencyExpiresAt();
        }
        var canonical = command.practitionerId()
                + "|" + command.serviceId()
                + "|" + command.facilityId()
                + "|" + Objects.toString(command.locationId(), "")
                + "|" + Objects.toString(command.activityEntryId(), "")
                + "|" + Objects.toString(command.supervisorPractitionerId(), "")
                + "|" + command.evaluatedFrom()
                + "|" + Objects.toString(command.evaluatedTo(), "")
                + "|" + Objects.toString(registrationId, "")
                + "|" + Objects.toString(qualificationId, "")
                + "|" + Objects.toString(credentialId, "")
                + "|" + Objects.toString(scope==null?null:scope.id(), "")
                + "|" + Objects.toString(assignmentId, "")
                + "|" + Objects.toString(serviceAssignmentId, "")
                + "|" + evidenceSnapshot.digest()
                + "|" + String.join(",", reasons)
                + "|" + outcome;
        var resultDigest = digest(canonical);
        var resultId = UuidV7Generator.randomUuid();
        var registrationJson = "{\"complete\":"+(registrationId!=null)
                +",\"registrationId\":"+uuidJson(registrationId)
                +",\"revision\":"+evidenceSnapshot.registrationRevision()
                +",\"sourceDigest\":\""+evidenceSnapshot.digest()+"\"}";
        var credentialJson = "{\"complete\":"+(credentialId!=null && qualificationId!=null)
                +",\"credentialId\":"+uuidJson(credentialId)
                +",\"credentialRevision\":"+evidenceSnapshot.credentialRevision()
                +",\"qualificationId\":"+uuidJson(qualificationId)
                +",\"qualificationRevision\":"+evidenceSnapshot.qualificationRevision()
                +",\"sourceDigest\":\""+evidenceSnapshot.digest()+"\"}";
        var scopeJson = "{\"complete\":"+(scope!=null && restrictionsClear)
                +",\"scopeId\":"+uuidJson(scope==null?null:scope.id())
                +",\"activityId\":"+uuidJson(scope==null?null:scope.activityId())
                +",\"scopeRevision\":"+evidenceSnapshot.scopeRevision()
                +",\"activityRevision\":"+evidenceSnapshot.activityRevision()
                +",\"restrictionsClear\":"+restrictionsClear+"}";
        var assignmentJson = "{\"complete\":"+(assignmentId!=null && engagementId!=null
                && serviceAssignmentId!=null)
                +",\"assignmentId\":"+uuidJson(assignmentId)
                +",\"assignmentRevision\":"+evidenceSnapshot.assignmentRevision()
                +",\"engagementId\":"+uuidJson(engagementId)
                +",\"engagementRevision\":"+evidenceSnapshot.engagementRevision()
                +",\"serviceAssignmentId\":"+uuidJson(serviceAssignmentId)+"}";
        var supervisionJson = "{\"complete\":"+supervisionComplete
                +",\"required\":"+(scope!=null && scope.supervisionRequired())
                +",\"supervisorId\":"+uuidJson(command.supervisorPractitionerId())+"}";
        jdbc.update(
                """
                INSERT INTO practitioner_eligibility_evidence(
                    id,organization_id,practitioner_profile_id,service_id,facility_id,location_id,
                    activity_entry_id,evaluated_from,evaluated_to,catalogue_version,
                    registration_evidence,credential_evidence,scope_evidence,assignment_evidence,
                    supervision_evidence,outcome,reason_codes,result_digest,evaluated_at,expires_at,
                    status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,'m2-candidate-1',?::jsonb,?::jsonb,?::jsonb,?::jsonb,
                        ?::jsonb,?,?::varchar[],?,?,?,?,?,?)
                """,
                resultId,
                context.organizationId(),
                command.practitionerId(),
                command.serviceId(),
                command.facilityId(),
                command.locationId(),
                command.activityEntryId(),
                Timestamp.from(command.evaluatedFrom()),
                timestamp(command.evaluatedTo()),
                registrationJson,
                credentialJson,
                scopeJson,
                assignmentJson,
                supervisionJson,
                outcome,
                reasons.toArray(String[]::new),
                resultDigest,
                Timestamp.from(command.now()),
                Timestamp.from(expiresAt),
                outcome,
                context.actorId(),
                context.actorId());
        return new WorkforceEligibilityStore.Evaluation(
                resultId,
                command.practitionerId(),
                contextId,
                outcome,
                resultDigest,
                expiresAt);
    }

    @Override
    public WorkforceEligibilityStore.Reconciliation reconcile(
            AuthorizedTenantContext context, UUID serviceAssignmentId, Instant now) {
        var practitionerId=jdbc.query(
                """
                SELECT practitioner_profile_id FROM practitioner_service_assignments
                WHERE organization_id=? AND id=?
                """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject("practitioner_profile_id",UUID.class)
                        : null,
                context.organizationId(),serviceAssignmentId);
        if (practitionerId==null) {
            throw notFound("The service assignment was not found for eligibility reconciliation.");
        }
        lockPractitionerForEligibility(context,practitionerId);
        var assignment = jdbc.query(
                """
                SELECT assignment.practitioner_profile_id,assignment.service_id,
                       assignment.facility_id,assignment.location_id,
                       assignment.supervisor_practitioner_id,assignment.effective_from,
                       assignment.effective_to,assignment.lifecycle_state,assignment.lock_version,
                       evidence.activity_entry_id
                FROM practitioner_service_assignments assignment
                LEFT JOIN practitioner_eligibility_evidence evidence
                  ON evidence.organization_id=assignment.organization_id
                 AND evidence.id=assignment.eligibility_evidence_id
                WHERE assignment.organization_id=? AND assignment.id=?
                FOR UPDATE OF assignment
                """,
                resultSet -> resultSet.next()
                        ? new ServiceAssignmentReconciliationSource(
                                resultSet.getObject("practitioner_profile_id",UUID.class),
                                resultSet.getObject("service_id",UUID.class),
                                resultSet.getObject("facility_id",UUID.class),
                                resultSet.getObject("location_id",UUID.class),
                                resultSet.getObject("supervisor_practitioner_id",UUID.class),
                                resultSet.getTimestamp("effective_from").toInstant(),
                                instantValue(resultSet.getObject("effective_to")),
                                resultSet.getString("lifecycle_state"),
                                resultSet.getLong("lock_version"),
                                resultSet.getObject("activity_entry_id",UUID.class))
                        : null,
                context.organizationId(),serviceAssignmentId);
        if (assignment==null) {
            throw stale("The service assignment changed before eligibility reconciliation.");
        }
        if (!Set.of("scheduled","active","suspended").contains(assignment.state())) {
            throw conflict("Only a prospective service assignment can be reconciled.");
        }
        if (assignment.activityEntryId()==null) {
            throw conflict("The service assignment has no bound activity evidence to re-evaluate.");
        }
        var evaluatedFrom=now.isAfter(assignment.effectiveFrom())?now:assignment.effectiveFrom();
        if (assignment.effectiveTo()!=null && !assignment.effectiveTo().isAfter(evaluatedFrom)) {
            throw conflict("The service assignment has no remaining prospective range.");
        }
        var evaluated=evaluate(
                context,
                new WorkforceEligibilityStore.Command(
                        assignment.practitionerId(),assignment.serviceId(),assignment.facilityId(),
                        assignment.locationId(),assignment.activityEntryId(),assignment.supervisorId(),
                        evaluatedFrom,assignment.effectiveTo(),now));
        var nextState=evaluated.outcome().equals("eligible")
                ? assignment.state()
                : Set.of("scheduled","active").contains(assignment.state())
                        ? "suspended" : assignment.state();
        var changed=jdbc.update(
                """
                UPDATE practitioner_service_assignments
                SET eligibility_evidence_id=?,eligibility_digest=?,lifecycle_state=?,status=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lifecycle_state=? AND lock_version=?
                """,
                evaluated.resultId(),evaluated.resultDigest(),nextState,nextState,context.actorId(),
                context.organizationId(),serviceAssignmentId,assignment.state(),assignment.revision());
        requireChanged(changed,"The service assignment changed during eligibility reconciliation.");
        return new WorkforceEligibilityStore.Reconciliation(
                serviceAssignmentId,assignment.state(),nextState,assignment.revision()+1,evaluated);
    }

    @Override
    public List<UUID> affectedServiceAssignmentIds(
            AuthorizedTenantContext context, String eventName, UUID aggregateId) {
        Objects.requireNonNull(eventName, "eventName");
        Objects.requireNonNull(aggregateId, "aggregateId");
        return jdbc.query(
                """
                WITH parameters AS (
                    SELECT ?::uuid AS organization_id,?::uuid AS aggregate_id,?::text AS event_name
                ), affected_practitioners AS (
                    SELECT registration.practitioner_profile_id AS id
                    FROM professional_registrations registration,parameters
                    WHERE registration.organization_id=parameters.organization_id
                      AND registration.id=parameters.aggregate_id
                    UNION
                    SELECT credential.practitioner_profile_id
                    FROM practitioner_credentials credential,parameters
                    WHERE credential.organization_id=parameters.organization_id
                      AND credential.id=parameters.aggregate_id
                      AND credential.practitioner_profile_id IS NOT NULL
                    UNION
                    SELECT scope.practitioner_profile_id
                    FROM scopes_of_practice scope,parameters
                    WHERE scope.organization_id=parameters.organization_id
                      AND scope.id=parameters.aggregate_id
                    UNION
                    SELECT source_assignment.practitioner_profile_id
                    FROM practitioner_service_assignments source_assignment,parameters
                    WHERE source_assignment.organization_id=parameters.organization_id
                      AND source_assignment.id=parameters.aggregate_id
                    UNION
                    SELECT practitioner.id
                    FROM practitioner_profiles practitioner,parameters
                    WHERE practitioner.organization_id=parameters.organization_id
                      AND practitioner.id=parameters.aggregate_id
                    UNION
                    SELECT practitioner.id
                    FROM qualifications qualification
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=qualification.organization_id
                     AND practitioner.workforce_member_id=qualification.workforce_member_id
                    CROSS JOIN parameters
                    WHERE qualification.organization_id=parameters.organization_id
                      AND qualification.id=parameters.aggregate_id
                    UNION
                    SELECT practitioner.id
                    FROM workforce_assignments source_assignment
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=source_assignment.organization_id
                     AND practitioner.workforce_member_id=source_assignment.workforce_member_id
                    CROSS JOIN parameters
                    WHERE source_assignment.organization_id=parameters.organization_id
                      AND source_assignment.id=parameters.aggregate_id
                    UNION
                    SELECT practitioner.id
                    FROM workforce_members member
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=member.organization_id
                     AND practitioner.workforce_member_id=member.id
                    CROSS JOIN parameters
                    WHERE member.organization_id=parameters.organization_id
                      AND member.id=parameters.aggregate_id
                    UNION
                    SELECT practitioner.id
                    FROM workforce_offboarding_requests request
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=request.organization_id
                     AND practitioner.workforce_member_id=request.workforce_member_id
                    CROSS JOIN parameters
                    WHERE request.organization_id=parameters.organization_id
                      AND request.id=parameters.aggregate_id
                )
                SELECT DISTINCT assignment.id
                FROM practitioner_service_assignments assignment
                CROSS JOIN parameters
                WHERE assignment.organization_id=parameters.organization_id
                  AND assignment.lifecycle_state IN ('scheduled','active','suspended')
                  AND assignment.eligibility_evidence_id IS NOT NULL
                  AND (assignment.effective_to IS NULL
                    OR assignment.effective_to>clock_timestamp())
                  AND (
                    parameters.event_name IN (
                        'workforce.registry.activated','workforce.configuration.activated',
                        'workforce.configuration.superseded')
                    OR assignment.practitioner_profile_id IN (
                        SELECT id FROM affected_practitioners WHERE id IS NOT NULL)
                    OR assignment.supervisor_practitioner_id IN (
                        SELECT id FROM affected_practitioners WHERE id IS NOT NULL))
                ORDER BY assignment.id
                """,
                (resultSet,rowNumber) -> resultSet.getObject(1,UUID.class),
                context.organizationId(),aggregateId,eventName);
    }

    private List<WorkforceScreen.Row> memberRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rows(
                query,
                """
                SELECT member.id,member.id AS member_id,member.lifecycle_state AS status,
                       member.lock_version,person.display_name AS primary_value,
                       member.member_number AS secondary_value,member.pathway AS context_value,
                       COALESCE(member.proposed_start_date::text,member.updated_at::date::text) AS effective_value
                FROM workforce_members member
                JOIN organization_person_links link
                  ON link.organization_id=member.organization_id
                 AND link.id=member.organization_person_link_id
                JOIN person_profiles person ON person.id=link.person_id
                WHERE member.organization_id=?
                  AND link.relationship_status='active'
                  AND (?::uuid IS NULL OR member.id=?::uuid)
                  AND (?::text IS NULL OR lower(person.display_name) LIKE '%'||lower(?::text)||'%'
                       OR lower(member.member_number) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR member.lifecycle_state=?::text)
                ORDER BY person.display_name,member.member_number,member.id
                LIMIT ?
                """,
                context.organizationId(),
                query.memberId(),
                query.memberId(),
                query.search(),
                query.search(),
                query.search(),
                query.status(),
                query.status(),
                query.limit());
    }

    private List<WorkforceScreen.Row> offboardingRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rowsWithActionContext(
                query,
                """
                WITH records AS (
                    SELECT member.id,member.id AS member_id,member.lifecycle_state AS status,
                           member.lock_version,person.display_name AS primary_value,
                           member.member_number AS secondary_value,'Workforce member' AS context_value,
                           COALESCE(member.proposed_start_date::text,member.updated_at::date::text) AS effective_value,
                           'member'::text AS action_context
                    FROM workforce_members member
                    JOIN organization_person_links link ON link.organization_id=member.organization_id
                      AND link.id=member.organization_person_link_id
                    JOIN person_profiles person ON person.id=link.person_id
                    WHERE member.organization_id=?
                      AND link.relationship_status<>'candidate'
                    UNION ALL
                    SELECT request.id,request.workforce_member_id AS member_id,request.status,
                           request.lock_version,person.display_name||' · offboarding' AS primary_value,
                           left(request.impact_digest,12)||'…' AS secondary_value,
                           request.access_action AS context_value,request.effective_at::text AS effective_value,
                           CASE WHEN request.maker_id=? THEN 'maker'
                                WHEN EXISTS (
                                    SELECT 1 FROM access_assignment_scopes actor_scope
                                    JOIN organization_memberships actor_membership
                                      ON actor_membership.organization_id=actor_scope.organization_id
                                     AND actor_membership.id=actor_scope.access_assignment_id
                                    WHERE actor_scope.organization_id=request.organization_id
                                      AND actor_scope.workforce_member_id=request.workforce_member_id
                                      AND actor_scope.status IN ('approved','active')
                                      AND actor_scope.effective_from<=clock_timestamp()
                                      AND (actor_scope.effective_to IS NULL
                                        OR actor_scope.effective_to>clock_timestamp())
                                      AND actor_membership.user_id=?
                                      AND actor_membership.status='active') THEN 'subject'
                                ELSE 'independent' END AS action_context
                    FROM workforce_offboarding_requests request
                    JOIN workforce_members member ON member.organization_id=request.organization_id
                      AND member.id=request.workforce_member_id
                    JOIN organization_person_links link ON link.organization_id=member.organization_id
                      AND link.id=member.organization_person_link_id
                    JOIN person_profiles person ON person.id=link.person_id
                    WHERE request.organization_id=?
                      AND link.relationship_status<>'candidate'
                )
                SELECT id,member_id,status,lock_version,primary_value,secondary_value,context_value,
                       effective_value,action_context
                FROM records
                WHERE (?::uuid IS NULL OR member_id=?::uuid)
                  AND (?::text IS NULL OR lower(primary_value||' '||secondary_value) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR status=?::text)
                ORDER BY effective_value DESC,id LIMIT ?
                """,
                context.organizationId(),
                context.actorId(),
                context.actorId(),
                context.organizationId(),
                query.memberId(),
                query.memberId(),
                query.search(),
                query.search(),
                query.status(),
                query.status(),
                query.limit());
    }

    private List<WorkforceScreen.Row> personRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        if (query.screenId().equals("M2-04")) {
            var result = new ArrayList<WorkforceScreen.Row>();
            result.addAll(personMatchRows(context, query));
            result.addAll(activePersonLinkRows(context, query));
            result.addAll(personMergeRows(context, query));
            return List.copyOf(result.subList(0, Math.min(result.size(), query.limit())));
        }
        return rows(
                query,
                """
                SELECT member.id,member.id AS member_id,person.status,
                       person.lock_version,
                       person.display_name AS primary_value,
                       member.member_number AS secondary_value,
                       CASE WHEN person.birth_date IS NULL THEN 'Birth date not recorded'
                            ELSE 'Birth year '||extract(year from person.birth_date)::integer END AS context_value,
                       link.relationship_status AS effective_value
                FROM workforce_members member
                JOIN organization_person_links link ON link.organization_id=member.organization_id
                  AND link.id=member.organization_person_link_id
                JOIN person_profiles person ON person.id=link.person_id
                WHERE member.organization_id=?
                  AND link.relationship_status='active'
                  AND (?::uuid IS NULL OR member.id=?::uuid)
                  AND (?::text IS NULL OR lower(person.display_name) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR person.status=?::text)
                ORDER BY person.display_name,member.id LIMIT ?
                """,
                context.organizationId(),
                query.memberId(),
                query.memberId(),
                query.search(),
                query.search(),
                query.status(),
                query.status(),
                query.limit());
    }

    private List<WorkforceScreen.Row> personMatchRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        var onboardingCases = jdbc.query(
                """
                SELECT member.id,member.lock_version,member.member_number,member.updated_at,
                       link.id AS link_id,link.person_id,person.display_name
                FROM workforce_members member
                JOIN organization_person_links link
                  ON link.organization_id=member.organization_id
                 AND link.id=member.organization_person_link_id
                JOIN person_profiles person ON person.id=link.person_id
                WHERE member.organization_id=? AND member.lifecycle_state='draft'
                  AND link.relationship_status='candidate' AND link.effective_to IS NULL
                  AND (?::uuid IS NULL OR member.id=?::uuid)
                  AND (?::text IS NULL OR lower(person.display_name||' '||member.member_number)
                      LIKE '%'||lower(?::text)||'%')
                ORDER BY member.updated_at DESC,member.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> new PersonMatchCase(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("link_id", UUID.class),
                        resultSet.getObject("person_id", UUID.class),
                        resultSet.getString("display_name"),
                        resultSet.getString("member_number"),
                        resultSet.getLong("lock_version"),
                        resultSet.getTimestamp("updated_at").toInstant()),
                context.organizationId(),
                query.memberId(),
                query.memberId(),
                query.search(),
                query.search(),
                query.limit());
        var matchAt = Objects.requireNonNull(
                        jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .toInstant();
        var rows = new ArrayList<WorkforceScreen.Row>();
        for (var onboardingCase : onboardingCases) {
            var candidates = personMatchCandidates(
                    context, onboardingCase.currentLinkId(), matchAt);
            var status = candidates.isEmpty() ? "clear" : "possible_match";
            if (query.status() != null && !query.status().equals(status)) {
                continue;
            }
            var values = new LinkedHashMap<String, String>();
            values.put("primary", onboardingCase.displayName());
            values.put("secondary", onboardingCase.memberNumber());
            values.put(
                    "context",
                    candidates.isEmpty()
                            ? "No organization match found"
                            : candidates.size() + (candidates.size() == 1
                                    ? " possible organization match"
                                    : " possible organization matches"));
            values.put("effective", "Explicit decision required");
            values.put("matchRunId", personMatchRunDigest(context, onboardingCase, candidates));
            values.put("candidateReference", onboardingCase.currentLinkId().toString());
            values.put("candidateCount", Integer.toString(candidates.size()));
            values.put("candidate.0.value", onboardingCase.currentLinkId().toString());
            values.put("candidate.0.label", "Create a new organization person");
            for (var index = 0; index < candidates.size(); index++) {
                var candidate = candidates.get(index);
                values.put("candidate." + (index + 1) + ".value", candidate.linkId().toString());
                values.put(
                        "candidate." + (index + 1) + ".label",
                        candidate.displayLabel() + " · " + matchReasonLabel(candidate.keyTypes()));
            }
            rows.add(new WorkforceScreen.Row(
                    onboardingCase.memberId(),
                    onboardingCase.memberId(),
                    status,
                    onboardingCase.revision(),
                    "\"m2:M2-04:" + onboardingCase.memberId() + ":"
                            + onboardingCase.revision() + "\"",
                    values));
        }
        return rows;
    }

    private List<WorkforceScreen.Row> personMergeRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rowsWithActionContext(
                query,
                """
                SELECT request.id,retained_member.id AS member_id,request.decision_state AS status,
                       request.lock_version,
                       'Merge: '||retained_person.display_name AS primary_value,
                       discarded_person.display_name AS secondary_value,
                       request.retained_link_id::text||' ← '||request.discarded_link_id::text
                            AS context_value,
                       request.decision_state AS effective_value,
                       CASE WHEN request.requested_by=? THEN 'maker'
                            WHEN EXISTS (
                                SELECT 1 FROM workforce_members affected_member
                                JOIN access_assignment_scopes actor_scope
                                  ON actor_scope.organization_id=affected_member.organization_id
                                 AND actor_scope.workforce_member_id=affected_member.id
                                JOIN organization_memberships actor_membership
                                  ON actor_membership.organization_id=actor_scope.organization_id
                                 AND actor_membership.id=actor_scope.access_assignment_id
                                WHERE affected_member.organization_id=request.organization_id
                                  AND affected_member.organization_person_link_id
                                      IN (request.retained_link_id,request.discarded_link_id)
                                  AND affected_member.lifecycle_state<>'offboarded'
                                  AND actor_scope.status IN ('approved','active')
                                  AND actor_scope.effective_from<=clock_timestamp()
                                  AND (actor_scope.effective_to IS NULL
                                    OR actor_scope.effective_to>clock_timestamp())
                                  AND actor_membership.user_id=?
                                  AND actor_membership.status='active') THEN 'subject'
                            ELSE 'independent' END AS action_context
                FROM person_merge_requests request
                JOIN organization_person_links retained_link
                  ON retained_link.organization_id=request.organization_id
                 AND retained_link.id=request.retained_link_id
                JOIN person_profiles retained_person ON retained_person.id=retained_link.person_id
                JOIN organization_person_links discarded_link
                  ON discarded_link.organization_id=request.organization_id
                 AND discarded_link.id=request.discarded_link_id
                JOIN person_profiles discarded_person ON discarded_person.id=discarded_link.person_id
                JOIN LATERAL (
                    SELECT member.id FROM workforce_members member
                    WHERE member.organization_id=request.organization_id
                      AND member.organization_person_link_id=request.retained_link_id
                    ORDER BY (member.lifecycle_state<>'offboarded') DESC,
                             member.updated_at DESC,member.id DESC LIMIT 1
                ) retained_member ON true
                WHERE request.organization_id=?
                  AND (?::uuid IS NULL OR retained_member.id=?::uuid)
                  AND (?::text IS NULL OR lower(retained_person.display_name||' '
                      ||discarded_person.display_name) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR request.decision_state=?::text)
                ORDER BY request.created_at DESC,request.id LIMIT ?
                """,
                context.actorId(),
                context.actorId(),
                context.organizationId(),
                query.memberId(),
                query.memberId(),
                query.search(),
                query.search(),
                query.status(),
                query.status(),
                query.limit());
    }

    private List<WorkforceScreen.Row> activePersonLinkRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rows(
                query,
                """
                SELECT member.id,member.id AS member_id,link.relationship_status AS status,
                       member.lock_version,person.display_name AS primary_value,
                       member.member_number AS secondary_value,
                       'Organization person link '||link.id::text AS context_value,
                       member.lifecycle_state AS effective_value
                FROM workforce_members member
                JOIN organization_person_links link
                  ON link.organization_id=member.organization_id
                 AND link.id=member.organization_person_link_id
                JOIN person_profiles person ON person.id=link.person_id
                WHERE member.organization_id=? AND link.relationship_status='active'
                  AND (?::uuid IS NULL OR member.id=?::uuid)
                  AND (?::text IS NULL OR lower(person.display_name||' '||member.member_number)
                      LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR link.relationship_status=?::text)
                ORDER BY member.updated_at DESC,member.id LIMIT ?
                """,
                context.organizationId(),
                query.memberId(),
                query.memberId(),
                query.search(),
                query.search(),
                query.status(),
                query.status(),
                query.limit());
    }

    private List<PersonMatchCandidate> personMatchCandidates(
            AuthorizedTenantContext context, UUID sourceLinkId, Instant effectiveAt) {
        return jdbc.query(
                """
                SELECT candidate.id,candidate.display_label,live_member.id AS live_member_id,
                       array_agg(DISTINCT source_key.key_type ORDER BY source_key.key_type)
                           AS key_types
                FROM person_match_keys source_key
                JOIN person_match_keys matched_key
                  ON matched_key.organization_id=source_key.organization_id
                 AND matched_key.key_type=source_key.key_type
                 AND matched_key.hmac_key_version=source_key.hmac_key_version
                 AND matched_key.keyed_digest=source_key.keyed_digest
                 AND matched_key.organization_person_link_id<>source_key.organization_person_link_id
                 AND matched_key.status='active'
                 AND matched_key.effective_from<=?
                 AND (matched_key.effective_to IS NULL OR matched_key.effective_to>?)
                JOIN organization_person_links candidate
                  ON candidate.organization_id=matched_key.organization_id
                 AND candidate.id=matched_key.organization_person_link_id
                LEFT JOIN LATERAL (
                    SELECT existing.id
                    FROM workforce_members existing
                    WHERE existing.organization_id=candidate.organization_id
                      AND existing.organization_person_link_id=candidate.id
                      AND existing.lifecycle_state<>'offboarded'
                    ORDER BY existing.created_at DESC,existing.id DESC
                    LIMIT 1
                ) live_member ON true
                WHERE source_key.organization_id=?
                  AND source_key.organization_person_link_id=?
                  AND source_key.status='active'
                  AND source_key.effective_from<=?
                  AND (source_key.effective_to IS NULL OR source_key.effective_to>?)
                  AND candidate.relationship_status='active'
                  AND candidate.effective_from<=?
                  AND (candidate.effective_to IS NULL OR candidate.effective_to>?)
                GROUP BY candidate.id,candidate.display_label,live_member.id
                ORDER BY candidate.id
                """,
                (resultSet, rowNumber) -> new PersonMatchCandidate(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("display_label"),
                        resultSet.getObject("live_member_id", UUID.class),
                        Arrays.asList((String[]) resultSet.getArray("key_types").getArray())),
                Timestamp.from(effectiveAt),
                Timestamp.from(effectiveAt),
                context.organizationId(),
                sourceLinkId,
                Timestamp.from(effectiveAt),
                Timestamp.from(effectiveAt),
                Timestamp.from(effectiveAt),
                Timestamp.from(effectiveAt));
    }

    private String personMatchRunDigest(
            AuthorizedTenantContext context,
            PersonMatchCase onboardingCase,
            List<PersonMatchCandidate> candidates) {
        var canonical = new StringBuilder("m2-person-match-run-v1|")
                .append(context.organizationId()).append('|')
                .append(context.actorId()).append('|')
                .append(onboardingCase.memberId()).append('|')
                .append(onboardingCase.currentLinkId()).append('|')
                .append(onboardingCase.revision()).append('|')
                .append(matchKeys.keyVersion());
        for (var candidate : candidates) {
            canonical.append('|').append(candidate.linkId()).append(':')
                    .append(Objects.toString(candidate.liveMemberId(), "-")).append(':')
                    .append(String.join(",", candidate.keyTypes()));
        }
        return digest(canonical.toString());
    }

    private static String matchReasonLabel(List<String> keyTypes) {
        return keyTypes.stream()
                .map(keyType -> switch (keyType) {
                    case "legal_name_birth_date" -> "same legal name and birth date";
                    case "email" -> "same work email";
                    case "phone" -> "same work phone";
                    case "regulated_identifier" -> "same regulated identifier";
                    default -> "matching organization identity evidence";
                })
                .reduce((left, right) -> left + "; " + right)
                .orElse("matching organization identity evidence");
    }

    private List<WorkforceScreen.Row> engagementRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rows(
                query,
                """
                SELECT engagement.id,engagement.workforce_member_id AS member_id,engagement.status,
                       engagement.lock_version,person.display_name AS primary_value,
                       engagement.engagement_type AS secondary_value,
                       engagement.employment_category_key AS context_value,
                       engagement.effective_from::date::text AS effective_value
                FROM employment_engagements engagement
                JOIN workforce_members member ON member.organization_id=engagement.organization_id
                  AND member.id=engagement.workforce_member_id
                JOIN organization_person_links link ON link.organization_id=member.organization_id
                  AND link.id=member.organization_person_link_id
                JOIN person_profiles person ON person.id=link.person_id
                WHERE engagement.organization_id=?
                  AND (?::uuid IS NULL OR engagement.workforce_member_id=?::uuid)
                  AND (?::text IS NULL OR lower(person.display_name) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR engagement.status=?::text)
                ORDER BY engagement.effective_from DESC,engagement.id LIMIT ?
                """,
                context.organizationId(), query.memberId(), query.memberId(), query.search(),
                query.search(), query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> practitionerRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rows(
                query,
                """
                SELECT practitioner.id,practitioner.workforce_member_id AS member_id,practitioner.status,
                       practitioner.lock_version,practitioner.clinical_title AS primary_value,
                       entry.display_label AS secondary_value,
                       CASE WHEN practitioner.regulated THEN 'Regulated' ELSE 'Not regulated' END AS context_value,
                       practitioner.effective_from::date::text AS effective_value
                FROM practitioner_profiles practitioner
                JOIN workforce_registry_entries entry ON entry.organization_id=practitioner.organization_id
                  AND entry.id=practitioner.profession_entry_id
                WHERE practitioner.organization_id=?
                  AND (?::uuid IS NULL OR practitioner.workforce_member_id=?::uuid)
                  AND (?::text IS NULL OR lower(practitioner.clinical_title) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR practitioner.status=?::text)
                ORDER BY practitioner.updated_at DESC,practitioner.id LIMIT ?
                """,
                context.organizationId(), query.memberId(), query.memberId(), query.search(),
                query.search(), query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> qualificationRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rowsWithActionContext(
                query,
                """
                SELECT qualification.id,qualification.workforce_member_id AS member_id,qualification.status,
                       qualification.lock_version,entry.display_label AS primary_value,
                       qualification.awarding_body AS secondary_value,
                       qualification.country_code AS context_value,
                       qualification.awarded_on::text AS effective_value,
                       CASE WHEN qualification.updated_by=? OR EXISTS (
                           SELECT 1 FROM access_assignment_scopes actor_scope
                           JOIN organization_memberships actor_membership
                             ON actor_membership.organization_id=actor_scope.organization_id
                            AND actor_membership.id=actor_scope.access_assignment_id
                           WHERE actor_scope.organization_id=qualification.organization_id
                             AND actor_scope.workforce_member_id=qualification.workforce_member_id
                             AND actor_scope.status IN ('approved','active')
                             AND actor_scope.effective_from<=clock_timestamp()
                             AND (actor_scope.effective_to IS NULL
                               OR actor_scope.effective_to>clock_timestamp())
                             AND actor_membership.user_id=? AND actor_membership.status='active')
                            THEN 'blocked' ELSE 'independent' END AS action_context
                FROM qualifications qualification
                JOIN workforce_registry_entries entry ON entry.organization_id=qualification.organization_id
                  AND entry.id=qualification.qualification_entry_id
                WHERE qualification.organization_id=?
                  AND (?::uuid IS NULL OR qualification.workforce_member_id=?::uuid)
                  AND (?::text IS NULL OR lower(qualification.awarding_body) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR qualification.status=?::text)
                ORDER BY qualification.awarded_on DESC,qualification.id LIMIT ?
                """,
                context.actorId(), context.actorId(), context.organizationId(),
                query.memberId(), query.memberId(), query.search(),
                query.search(), query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> registrationRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rowsWithActionContext(
                query,
                """
                SELECT registration.id,practitioner.workforce_member_id AS member_id,registration.status,
                       registration.lock_version,registration.masked_display AS primary_value,
                       regulator.display_label AS secondary_value,
                       registration.jurisdiction_country AS context_value,
                       COALESCE(registration.expires_on::text,'No expiry') AS effective_value,
                       CASE WHEN registration.updated_by=? OR EXISTS (
                           SELECT 1 FROM access_assignment_scopes actor_scope
                           JOIN organization_memberships actor_membership
                             ON actor_membership.organization_id=actor_scope.organization_id
                            AND actor_membership.id=actor_scope.access_assignment_id
                           WHERE actor_scope.organization_id=registration.organization_id
                             AND actor_scope.workforce_member_id=practitioner.workforce_member_id
                             AND actor_scope.status IN ('approved','active')
                             AND actor_scope.effective_from<=clock_timestamp()
                             AND (actor_scope.effective_to IS NULL
                               OR actor_scope.effective_to>clock_timestamp())
                             AND actor_membership.user_id=? AND actor_membership.status='active')
                            THEN 'blocked' ELSE 'independent' END AS action_context
                FROM professional_registrations registration
                JOIN practitioner_profiles practitioner ON practitioner.organization_id=registration.organization_id
                  AND practitioner.id=registration.practitioner_profile_id
                JOIN workforce_registry_entries regulator ON regulator.organization_id=registration.organization_id
                  AND regulator.id=registration.regulator_entry_id
                WHERE registration.organization_id=?
                  AND (?::uuid IS NULL OR practitioner.workforce_member_id=?::uuid)
                  AND (?::text IS NULL OR lower(registration.masked_display) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR registration.status=?::text)
                ORDER BY registration.expires_on NULLS LAST,registration.id LIMIT ?
                """,
                context.actorId(), context.actorId(), context.organizationId(),
                query.memberId(), query.memberId(), query.search(),
                query.search(), query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> credentialRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        var verifiedSummaryOnly = hasActiveRole(context, "clinical_governance_approver")
                && !hasAnyActiveRole(
                        context,
                        Set.of(
                                "organization_owner",
                                "local_bootstrap",
                                "credentialing_officer",
                                "practitioner"));
        return rowsWithActionContext(
                query,
                """
                SELECT credential.id,credential.workforce_member_id AS member_id,credential.status,
                       credential.lock_version,entry.display_label AS primary_value,
                       credential.issuer AS secondary_value,credential.risk_tier AS context_value,
                       COALESCE(credential.expires_on::text,'No expiry') AS effective_value,
                       CASE
                         WHEN EXISTS (
                           SELECT 1 FROM access_assignment_scopes actor_scope
                           JOIN organization_memberships actor_membership
                             ON actor_membership.organization_id=actor_scope.organization_id
                            AND actor_membership.id=actor_scope.access_assignment_id
                           WHERE actor_scope.organization_id=credential.organization_id
                             AND actor_scope.workforce_member_id=credential.workforce_member_id
                             AND actor_scope.status IN ('approved','active')
                             AND actor_scope.effective_from<=clock_timestamp()
                             AND (actor_scope.effective_to IS NULL
                               OR actor_scope.effective_to>clock_timestamp())
                             AND actor_membership.user_id=?
                             AND actor_membership.status='active') THEN 'blocked'
                         WHEN credential.status='in_review' AND credential.reviewer_id=?
                           AND credential.review_lease_expires_at>clock_timestamp() THEN 'reviewer'
                         WHEN credential.status IN ('submitted','in_review')
                           AND (credential.status='submitted'
                             OR credential.review_lease_expires_at<=clock_timestamp())
                           AND credential.submitted_by<>?
                           AND NOT EXISTS (SELECT 1 FROM credential_documents actor_document
                               WHERE actor_document.organization_id=credential.organization_id
                                 AND actor_document.practitioner_credential_id=credential.id
                                 AND actor_document.created_by=?) THEN 'claimable'
                         ELSE 'blocked' END AS action_context
                FROM practitioner_credentials credential
                JOIN workforce_registry_entries entry ON entry.organization_id=credential.organization_id
                  AND entry.id=credential.credential_type_entry_id
                WHERE credential.organization_id=?
                  AND (NOT ?::boolean OR credential.status='verified')
                  AND (?::uuid IS NULL OR credential.workforce_member_id=?::uuid)
                  AND (?::text IS NULL OR lower(credential.issuer) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR credential.status=?::text)
                ORDER BY CASE credential.risk_tier WHEN 'critical' THEN 1 WHEN 'high' THEN 2
                         WHEN 'moderate' THEN 3 ELSE 4 END,
                         credential.submitted_at NULLS LAST,credential.id LIMIT ?
                """,
                context.actorId(), context.actorId(), context.actorId(), context.actorId(),
                context.organizationId(), verifiedSummaryOnly,
                query.memberId(), query.memberId(), query.search(),
                query.search(), query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> specialtyRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rows(
                query,
                """
                SELECT specialty.id,practitioner.workforce_member_id AS member_id,specialty.status,
                       specialty.lock_version,entry.display_label AS primary_value,
                       specialty.designation AS secondary_value,'Specialty' AS context_value,
                       specialty.effective_from::date::text AS effective_value
                FROM practitioner_specialties specialty
                JOIN practitioner_profiles practitioner ON practitioner.organization_id=specialty.organization_id
                  AND practitioner.id=specialty.practitioner_profile_id
                JOIN workforce_registry_entries entry ON entry.organization_id=specialty.organization_id
                  AND entry.id=specialty.specialty_entry_id
                WHERE specialty.organization_id=?
                  AND (?::uuid IS NULL OR practitioner.workforce_member_id=?::uuid)
                  AND (?::text IS NULL OR lower(entry.display_label) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR specialty.status=?::text)
                ORDER BY specialty.effective_from DESC,specialty.id LIMIT ?
                """,
                context.organizationId(), query.memberId(), query.memberId(), query.search(),
                query.search(), query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> scopeRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rowsWithActionContext(
                query,
                """
                SELECT scope.id,practitioner.workforce_member_id AS member_id,scope.status,
                       scope.lock_version,definition.name AS primary_value,
                       definition.definition_code AS secondary_value,
                       COALESCE(scope.decision_code,'Awaiting decision') AS context_value,
                       scope.effective_from::date::text AS effective_value,
                       CASE WHEN scope.submitted_by=? OR EXISTS (
                           SELECT 1 FROM access_assignment_scopes actor_scope
                           JOIN organization_memberships actor_membership
                             ON actor_membership.organization_id=actor_scope.organization_id
                            AND actor_membership.id=actor_scope.access_assignment_id
                           WHERE actor_scope.organization_id=scope.organization_id
                             AND actor_scope.workforce_member_id=practitioner.workforce_member_id
                             AND actor_scope.status IN ('approved','active')
                             AND actor_scope.effective_from<=clock_timestamp()
                             AND (actor_scope.effective_to IS NULL
                               OR actor_scope.effective_to>clock_timestamp())
                             AND actor_membership.user_id=? AND actor_membership.status='active')
                            THEN 'blocked' ELSE 'independent' END AS action_context
                FROM scopes_of_practice scope
                JOIN practitioner_profiles practitioner ON practitioner.organization_id=scope.organization_id
                  AND practitioner.id=scope.practitioner_profile_id
                JOIN scope_definitions definition ON definition.organization_id=scope.organization_id
                  AND definition.id=scope.scope_definition_id
                WHERE scope.organization_id=?
                  AND (?::uuid IS NULL OR practitioner.workforce_member_id=?::uuid)
                  AND (?::text IS NULL OR lower(definition.name) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR scope.status=?::text)
                ORDER BY scope.effective_from DESC,scope.id LIMIT ?
                """,
                context.actorId(), context.actorId(), context.organizationId(),
                query.memberId(), query.memberId(), query.search(),
                query.search(), query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> assignmentRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rows(
                query,
                """
                SELECT assignment.id,assignment.workforce_member_id AS member_id,assignment.status,
                       assignment.lock_version,facility.name AS primary_value,
                       COALESCE(unit.name,'Organization') AS secondary_value,
                       COALESCE(location.name,'All locations') AS context_value,
                       assignment.effective_from::date::text AS effective_value
                FROM workforce_assignments assignment
                JOIN facilities facility ON facility.organization_id=assignment.organization_id
                  AND facility.id=assignment.facility_id
                LEFT JOIN organization_units unit ON unit.organization_id=assignment.organization_id
                  AND unit.id=assignment.organization_unit_id
                LEFT JOIN service_locations location ON location.organization_id=assignment.organization_id
                  AND location.id=assignment.location_id
                WHERE assignment.organization_id=?
                  AND (?::uuid IS NULL OR assignment.workforce_member_id=?::uuid)
                  AND (?::text IS NULL OR lower(facility.name) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR assignment.status=?::text)
                ORDER BY assignment.effective_from DESC,assignment.id LIMIT ?
                """,
                context.organizationId(), query.memberId(), query.memberId(), query.search(),
                query.search(), query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> serviceAssignmentRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rows(
                query,
                """
                SELECT assignment.id,practitioner.workforce_member_id AS member_id,assignment.status,
                       assignment.lock_version,service.display_name AS primary_value,
                       facility.name AS secondary_value,
                       COALESCE(eligibility.outcome,'Not evaluated') AS context_value,
                       assignment.effective_from::date::text AS effective_value
                FROM practitioner_service_assignments assignment
                JOIN practitioner_profiles practitioner ON practitioner.organization_id=assignment.organization_id
                  AND practitioner.id=assignment.practitioner_profile_id
                JOIN service_definitions service ON service.organization_id=assignment.organization_id
                  AND service.id=assignment.service_id
                JOIN facilities facility ON facility.organization_id=assignment.organization_id
                  AND facility.id=assignment.facility_id
                LEFT JOIN practitioner_eligibility_evidence eligibility
                  ON eligibility.organization_id=assignment.organization_id
                 AND eligibility.id=assignment.eligibility_evidence_id
                WHERE assignment.organization_id=?
                  AND (?::uuid IS NULL OR practitioner.workforce_member_id=?::uuid)
                  AND (?::text IS NULL OR lower(service.display_name) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR assignment.status=?::text)
                ORDER BY assignment.effective_from DESC,assignment.id LIMIT ?
                """,
                context.organizationId(), query.memberId(), query.memberId(), query.search(),
                query.search(), query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> accessRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rows(
                query,
                """
                SELECT member.id,member.id AS member_id,member.lifecycle_state AS status,
                       member.lock_version,person.display_name AS primary_value,
                       member.account_access_intent AS secondary_value,
                       COALESCE(membership.role_key,'No active account') AS context_value,
                       member.updated_at::date::text AS effective_value
                FROM workforce_members member
                JOIN organization_person_links link ON link.organization_id=member.organization_id
                  AND link.id=member.organization_person_link_id
                JOIN person_profiles person ON person.id=link.person_id
                LEFT JOIN LATERAL (
                    SELECT candidate.organization_id,candidate.access_assignment_id
                    FROM access_assignment_scopes candidate
                    WHERE candidate.organization_id=member.organization_id
                      AND candidate.workforce_member_id=member.id
                      AND candidate.status IN ('approved','active')
                      AND candidate.effective_from<=clock_timestamp()
                      AND (candidate.effective_to IS NULL OR candidate.effective_to>clock_timestamp())
                    ORDER BY candidate.effective_from DESC,candidate.id DESC LIMIT 1
                ) scope ON true
                LEFT JOIN organization_memberships membership ON membership.organization_id=scope.organization_id
                  AND membership.id=scope.access_assignment_id AND membership.status='active'
                WHERE member.organization_id=?
                  AND (?::uuid IS NULL OR member.id=?::uuid)
                  AND (?::text IS NULL OR lower(person.display_name) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR member.lifecycle_state=?::text)
                ORDER BY person.display_name,member.id LIMIT ?
                """,
                context.organizationId(), query.memberId(), query.memberId(), query.search(),
                query.search(), query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> availabilityRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rows(
                query,
                """
                SELECT profile.id,profile.workforce_member_id AS member_id,profile.status,
                       profile.lock_version,profile.timezone AS primary_value,
                       'Version '||profile.profile_version AS secondary_value,
                       CASE WHEN profile.not_required THEN 'Not required'
                            ELSE (SELECT count(*)::text||' weekly intervals' FROM availability_periods period
                                  WHERE period.organization_id=profile.organization_id
                                    AND period.availability_profile_id=profile.id AND period.status='active') END AS context_value,
                       profile.effective_from::date::text AS effective_value
                FROM availability_profiles profile
                WHERE profile.organization_id=?
                  AND (?::uuid IS NULL OR profile.workforce_member_id=?::uuid)
                  AND (?::text IS NULL OR lower(profile.timezone) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR profile.status=?::text)
                ORDER BY profile.profile_version DESC,profile.id LIMIT ?
                """,
                context.organizationId(), query.memberId(), query.memberId(), query.search(),
                query.search(), query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> readinessRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rowsWithActionContext(
                query,
                """
                WITH records AS (
                    SELECT member.id,member.id AS member_id,member.lifecycle_state AS status,
                           member.lock_version,person.display_name AS primary_value,
                           member.pathway AS secondary_value,'Workforce member' AS context_value,
                           member.updated_at::text AS effective_value,member.updated_at AS sort_at,
                           'member'::text AS action_context
                    FROM workforce_members member
                    JOIN organization_person_links link ON link.organization_id=member.organization_id
                      AND link.id=member.organization_person_link_id
                    JOIN person_profiles person ON person.id=link.person_id
                    WHERE member.organization_id=?
                    UNION ALL
                    SELECT run.id,run.workforce_member_id AS member_id,run.status,run.lock_version,
                           'Readiness '||left(run.result_digest,12) AS primary_value,
                           run.pathway AS secondary_value,
                           COALESCE(run.blocker_count,0)||' blockers · '||COALESCE(run.warning_count,0)||' warnings' AS context_value,
                           COALESCE(run.expires_at::text,'Not complete') AS effective_value,
                           run.requested_at AS sort_at,'run'::text AS action_context
                    FROM workforce_readiness_runs run WHERE run.organization_id=?
                    UNION ALL
                    SELECT request.id,request.workforce_member_id AS member_id,request.status,request.lock_version,
                           'Activation '||left(request.result_digest,12) AS primary_value,
                           'Independent approval' AS secondary_value,
                           COALESCE(request.decision_code,'Awaiting decision') AS context_value,
                           request.expires_at::text AS effective_value,request.requested_at AS sort_at,
                           CASE WHEN request.maker_id=? THEN 'maker'
                                WHEN EXISTS (
                                    SELECT 1 FROM access_assignment_scopes actor_scope
                                    JOIN organization_memberships actor_membership
                                      ON actor_membership.organization_id=actor_scope.organization_id
                                     AND actor_membership.id=actor_scope.access_assignment_id
                                    WHERE actor_scope.organization_id=request.organization_id
                                      AND actor_scope.workforce_member_id=request.workforce_member_id
                                      AND actor_scope.status IN ('approved','active')
                                      AND actor_scope.effective_from<=clock_timestamp()
                                      AND (actor_scope.effective_to IS NULL
                                        OR actor_scope.effective_to>clock_timestamp())
                                      AND actor_membership.user_id=?
                                      AND actor_membership.status='active') THEN 'subject'
                                ELSE 'independent' END AS action_context
                    FROM workforce_activation_requests request WHERE request.organization_id=?
                )
                SELECT id,member_id,status,lock_version,primary_value,secondary_value,context_value,
                       effective_value,action_context
                FROM records
                WHERE (?::uuid IS NULL OR member_id=?::uuid)
                  AND (?::text IS NULL OR id::text LIKE '%'||?::text||'%'
                       OR lower(primary_value) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR status=?::text)
                ORDER BY sort_at DESC,id LIMIT ?
                """,
                context.organizationId(), context.organizationId(), context.actorId(),
                context.actorId(), context.organizationId(),
                query.memberId(), query.memberId(), query.search(), query.search(), query.search(),
                query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> expiryRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rows(
                query,
                """
                WITH records AS (
                    SELECT credential.id,credential.workforce_member_id AS member_id,credential.status,
                           credential.lock_version,entry.display_label AS primary_value,
                           'Credential · '||credential.issuer AS secondary_value,
                           credential.expires_on,credential.issuer AS searchable
                    FROM practitioner_credentials credential
                    JOIN workforce_registry_entries entry ON entry.organization_id=credential.organization_id
                      AND entry.id=credential.credential_type_entry_id
                    WHERE credential.organization_id=? AND credential.expires_on IS NOT NULL
                      AND credential.status IN ('verified','suspended','expired')
                    UNION ALL
                    SELECT registration.id,practitioner.workforce_member_id,registration.status,
                           registration.lock_version,type_entry.display_label,
                           'Registration · '||registration.masked_display,
                           registration.expires_on,
                           type_entry.display_label||' '||registration.masked_display
                    FROM professional_registrations registration
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=registration.organization_id
                     AND practitioner.id=registration.practitioner_profile_id
                    JOIN workforce_registry_entries type_entry
                      ON type_entry.organization_id=registration.organization_id
                     AND type_entry.id=registration.registration_type_entry_id
                    WHERE registration.organization_id=? AND registration.expires_on IS NOT NULL
                      AND registration.status IN ('verified','suspended','expired')
                )
                SELECT id,member_id,status,lock_version,primary_value,secondary_value,
                       CASE WHEN expires_on < current_date THEN 'Expired'
                            WHEN expires_on <= current_date+7 THEN '0-7 days'
                            WHEN expires_on <= current_date+30 THEN '8-30 days'
                            WHEN expires_on <= current_date+60 THEN '31-60 days'
                            ELSE '61-90 days' END AS context_value,
                       expires_on::text AS effective_value
                FROM records
                WHERE expires_on<=current_date+90
                  AND (?::uuid IS NULL OR member_id=?::uuid)
                  AND (?::text IS NULL OR lower(searchable) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR status=?::text)
                ORDER BY expires_on,id LIMIT ?
                """,
                context.organizationId(), context.organizationId(),
                query.memberId(), query.memberId(), query.search(),
                query.search(), query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> historyRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rows(
                query,
                """
                SELECT snapshot.id,NULL::uuid AS member_id,snapshot.status,snapshot.lock_version,
                       snapshot.display_number AS primary_value,
                       left(snapshot.snapshot_digest,16)||'…' AS secondary_value,
                       'Maker/checker/activator separated' AS context_value,
                       snapshot.effective_at::text AS effective_value
                FROM workforce_configuration_snapshots snapshot
                WHERE snapshot.organization_id=?
                  AND (?::uuid IS NULL OR snapshot.id=?::uuid)
                  AND (?::text IS NULL OR lower(snapshot.display_number) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR snapshot.status=?::text)
                ORDER BY snapshot.effective_at DESC,snapshot.id LIMIT ?
                """,
                context.organizationId(), query.memberId(), query.memberId(), query.search(),
                query.search(), query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> auditRows(
            AuthorizedTenantContext context, ScreenQuery query, boolean memberOnly) {
        var sql = memberOnly
                ? """
                  SELECT event.id,correlation.member_id,'recorded' AS status,
                         0::bigint AS lock_version,event.event_name AS primary_value,
                         event.subject_type AS secondary_value,event.correlation_id AS context_value,
                         event.occurred_at::text AS effective_value
                  FROM audit_events event
                  JOIN audit_event_definitions definition ON definition.event_name=event.event_name
                    AND definition.schema_version=event.schema_version
                  CROSS JOIN LATERAL (
                      SELECT careos_m2_audit_event_member_id(
                          event.organization_id,event.subject_type,event.subject_id,event.payload)
                          AS member_id
                  ) correlation
                  WHERE event.organization_id=? AND definition.registry_version='m2-candidate-1'
                    AND correlation.member_id IS NOT NULL
                    AND (?::uuid IS NULL OR correlation.member_id=?::uuid)
                    AND (?::text IS NULL OR lower(event.event_name) LIKE '%'||lower(?::text)||'%')
                    AND (?::text IS NULL OR event.event_name=?::text)
                  ORDER BY event.occurred_at DESC,event.id LIMIT ?
                  """
                : """
                  SELECT event.id,NULL::uuid AS member_id,'recorded' AS status,0::bigint AS lock_version,
                         event.event_name AS primary_value,event.subject_type AS secondary_value,
                         event.correlation_id AS context_value,event.occurred_at::text AS effective_value
                  FROM audit_events event
                  JOIN audit_event_definitions definition ON definition.event_name=event.event_name
                    AND definition.schema_version=event.schema_version
                  WHERE event.organization_id=? AND definition.registry_version='m2-candidate-1'
                    AND (?::uuid IS NULL OR event.subject_id=?::uuid)
                    AND (?::text IS NULL OR lower(event.event_name) LIKE '%'||lower(?::text)||'%')
                    AND (?::text IS NULL OR event.event_name=?::text)
                  ORDER BY event.occurred_at DESC,event.id LIMIT ?
                  """;
        return rows(
                query,
                sql,
                context.organizationId(), query.memberId(), query.memberId(), query.search(),
                query.search(), query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> registryRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return rowsWithActionContext(
                query,
                """
                SELECT version.id,NULL::uuid AS member_id,version.status,version.lock_version,
                       definition.display_name AS primary_value,entry.display_label AS secondary_value,
                       'Version '||version.version_number AS context_value,
                       version.effective_from::text AS effective_value,
                       CASE WHEN version.maker_id=? THEN 'maker'
                            ELSE 'independent' END AS action_context
                FROM workforce_registry_versions version
                JOIN workforce_registry_entries entry ON entry.organization_id=version.organization_id
                  AND entry.id=version.registry_entry_id
                JOIN workforce_registry_definitions definition ON definition.organization_id=entry.organization_id
                  AND definition.id=entry.registry_definition_id
                WHERE version.organization_id=?
                  AND (?::uuid IS NULL OR version.id=?::uuid)
                  AND (?::text IS NULL OR lower(definition.display_name||' '||entry.display_label) LIKE '%'||lower(?::text)||'%')
                  AND (?::text IS NULL OR version.status=?::text)
                ORDER BY definition.display_name,entry.display_label,version.version_number DESC LIMIT ?
                """,
                context.actorId(), context.organizationId(),
                query.memberId(), query.memberId(), query.search(),
                query.search(), query.status(), query.status(), query.limit());
    }

    private List<WorkforceScreen.Row> exportRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        var projections = switch (query.screenId()) {
            case "M2-26" -> List.of("workforce-configuration-summary-v1");
            case "M2-27" -> List.of(
                    "workforce-directory-summary-v1",
                    "credential-expiry-summary-v1",
                    "workforce-configuration-summary-v1",
                    "workforce-audit-summary-v1",
                    "member-timeline-summary-v1",
                    "credential-decision-detail-v1",
                    "scope-decision-detail-v1",
                    "workforce-audit-detail-v1",
                    "member-evidence-detail-v1");
            case "M2-29" -> List.of("member-timeline-summary-v1", "member-evidence-detail-v1");
            default -> List.<String>of();
        };
        if (projections.isEmpty()) return List.of();
        var canApprove = permissions(context).contains("workforce.export.approve");
        var placeholders = String.join(",", java.util.Collections.nCopies(projections.size(), "?"));
        var sql = """
                SELECT export_job.id,export_job.status,export_job.lock_version,
                       export_job.requester_id,export_job.projection AS primary_value,
                       upper(export_job.format)||' · '||export_job.purpose_key AS secondary_value,
                       CASE WHEN export_job.status='ready'
                                 THEN 'Available until '||export_job.expires_at::text
                            WHEN export_job.failure_code IS NOT NULL
                                 THEN export_job.failure_code
                            ELSE 'Snapshot '||export_job.snapshot_at::text END AS context_value,
                       export_job.created_at::text AS effective_value,
                       export_job.failure_code<>'authorization_denied'
                         AND retry.id IS NULL
                         AND COALESCE(lineage.depth,0)<5 AS retryable
                FROM workforce_export_jobs export_job
                LEFT JOIN workforce_export_jobs retry
                  ON retry.organization_id=export_job.organization_id
                 AND retry.retry_of_export_id=export_job.id
                LEFT JOIN LATERAL (
                    WITH RECURSIVE ancestors AS (
                        SELECT source.id,source.retry_of_export_id,1 AS depth
                        FROM workforce_export_jobs source
                        WHERE source.organization_id=export_job.organization_id
                          AND source.id=export_job.id
                        UNION ALL
                        SELECT parent.id,parent.retry_of_export_id,ancestors.depth+1
                        FROM workforce_export_jobs parent
                        JOIN ancestors ON parent.id=ancestors.retry_of_export_id
                        WHERE parent.organization_id=export_job.organization_id
                          AND ancestors.depth<6
                    )
                    SELECT max(depth) AS depth FROM ancestors
                ) lineage ON true
                WHERE export_job.organization_id=? AND export_job.projection IN (%s)
                  AND (export_job.requester_id=? OR ?)
                  AND (?::text IS NULL OR lower(export_job.projection) LIKE '%%'||lower(?::text)||'%%')
                  AND (?::text IS NULL OR export_job.status=?::text)
                ORDER BY export_job.created_at DESC,export_job.id DESC LIMIT ?
                """.formatted(placeholders);
        var parameters = new ArrayList<Object>();
        parameters.add(context.organizationId());
        parameters.addAll(projections);
        parameters.add(context.actorId());
        parameters.add(canApprove);
        parameters.add(query.search());
        parameters.add(query.search());
        parameters.add(query.status());
        parameters.add(query.status());
        parameters.add(query.limit());
        return jdbc.query(
                sql,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    var revision = resultSet.getLong("lock_version");
                    var values = new LinkedHashMap<String, String>();
                    values.put("primary", safe(resultSet.getString("primary_value")));
                    values.put("secondary", safe(resultSet.getString("secondary_value")));
                    values.put("context", safe(resultSet.getString("context_value")));
                    values.put("effective", safe(resultSet.getString("effective_value")));
                    values.put(
                            "$actionContext",
                            context.actorId().equals(resultSet.getObject("requester_id", UUID.class))
                                    ? (resultSet.getBoolean("retryable")
                                            ? "owner_retryable" : "owner")
                                    : "independent");
                    return new WorkforceScreen.Row(
                            id,
                            null,
                            resultSet.getString("status"),
                            revision,
                            "\"m2:" + query.screenId() + ":" + id + ":" + revision + "\"",
                            values);
                },
                parameters.toArray());
    }

    private List<WorkforceScreen.Row> rows(ScreenQuery query, String sql, Object... arguments) {
        return rows(query, false, sql, arguments);
    }

    private List<WorkforceScreen.Row> rowsWithActionContext(
            ScreenQuery query, String sql, Object... arguments) {
        return rows(query, true, sql, arguments);
    }

    private List<WorkforceScreen.Row> rows(
            ScreenQuery query, boolean includeActionContext, String sql, Object... arguments) {
        return jdbc.query(sql, (resultSet, rowNumber) -> {
            var id = resultSet.getObject("id", UUID.class);
            var memberId = resultSet.getObject("member_id", UUID.class);
            var status = resultSet.getString("status");
            var revision = resultSet.getLong("lock_version");
            var values = new LinkedHashMap<String, String>();
            values.put("primary", safe(resultSet.getString("primary_value")));
            values.put("secondary", safe(resultSet.getString("secondary_value")));
            values.put("context", safe(resultSet.getString("context_value")));
            values.put("effective", safe(resultSet.getString("effective_value")));
            if (includeActionContext) {
                values.put("$actionContext", safe(resultSet.getString("action_context")));
            }
            return new WorkforceScreen.Row(
                    id,
                    memberId,
                    status,
                    revision,
                    "\"m2:" + query.screenId() + ":" + id + ":" + revision + "\"",
                    values);
        }, arguments);
    }

    private static List<WorkforceScreen.Column> columns(String screenId) {
        var labels = switch (screenId) {
            case "M2-02", "M2-03", "M2-21", "M2-23", "M2-24" ->
                List.of("Member", "Member number", "Pathway", "Effective");
            case "M2-04", "M2-05" -> List.of("Person", "Member number", "Restricted identity", "Link state");
            case "M2-06" -> List.of("Member", "Engagement", "Category", "Effective");
            case "M2-07" -> List.of("Clinical title", "Profession", "Regulation", "Effective");
            case "M2-08" -> List.of("Qualification", "Awarding body", "Country", "Awarded");
            case "M2-09" -> List.of("Registration", "Regulator", "Jurisdiction", "Expiry");
            case "M2-10", "M2-11", "M2-12", "M2-25" ->
                List.of("Credential", "Issuer", "Risk / bucket", "Expiry");
            case "M2-13" -> List.of("Specialty", "Designation", "Type", "Effective");
            case "M2-14" -> List.of("Scope", "Code", "Decision", "Effective");
            case "M2-15", "M2-22" -> List.of("Facility", "Unit", "Location", "Effective");
            case "M2-16" -> List.of("Service", "Facility", "Eligibility", "Effective");
            case "M2-17", "M2-19" -> List.of("Member", "Access intent", "Canonical role", "Updated");
            case "M2-18" -> List.of("Timezone", "Version", "Pattern", "Effective");
            case "M2-20" -> List.of("Readiness", "Pathway", "Outcome", "Expires");
            case "M2-26" -> List.of("Version", "Digest", "Control", "Effective");
            case "M2-27", "M2-29" -> List.of("Event", "Subject", "Correlation", "Occurred");
            case "M2-28" -> List.of("Registry", "Entry", "Version", "Effective");
            default -> List.of("Record", "Reference", "Context", "Effective");
        };
        return List.of(
                new WorkforceScreen.Column("primary", labels.get(0)),
                new WorkforceScreen.Column("secondary", labels.get(1)),
                new WorkforceScreen.Column("context", labels.get(2)),
                new WorkforceScreen.Column("effective", labels.get(3)));
    }

    private List<WorkforceScreen.Metric> metrics(
            AuthorizedTenantContext context, String screenId, List<WorkforceScreen.Row> rows) {
        if ("M2-01".equals(screenId)) {
            var values = jdbc.queryForMap(
                    """
                    SELECT count(*) AS total,
                           count(*) FILTER (WHERE lifecycle_state='active') AS active,
                           count(*) FILTER (WHERE lifecycle_state='draft') AS onboarding,
                           count(*) FILTER (WHERE lifecycle_state IN ('suspended','offboarding')) AS attention
                    FROM workforce_members
                    WHERE organization_id=?
                      AND careos_m2_actor_can_access_member(organization_id,id)
                    """,
                    context.organizationId());
            return List.of(
                    metric("total", "Workforce", values.get("total"), "neutral"),
                    metric("active", "Active", values.get("active"), "success"),
                    metric("onboarding", "Onboarding", values.get("onboarding"), "info"),
                    metric("attention", "Needs attention", values.get("attention"), "warning"));
        }
        var active = rows.stream().filter(row -> Set.of("active", "verified", "approved", "complete", "ready").contains(row.status())).count();
        var attention = rows.stream().filter(row -> Set.of("rejected", "failed", "expired", "suspended", "blocked").contains(row.status())).count();
        return List.of(
                new WorkforceScreen.Metric("shown", "Records shown", rows.size(), "neutral"),
                new WorkforceScreen.Metric("current", "Current", active, "success"),
                new WorkforceScreen.Metric("attention", "Needs attention", attention, "warning"));
    }

    private static WorkforceScreen.Metric metric(
            String key, String label, Object value, String tone) {
        return new WorkforceScreen.Metric(key, label, ((Number) value).longValue(), tone);
    }

    private static List<WorkforceScreen.Notice> notices(String screenId) {
        return switch (screenId) {
            case "M2-10", "M2-12" -> List.of(new WorkforceScreen.Notice(
                    "info",
                    "Private evidence boundary",
                    "Provider object keys and signed URLs never enter this projection. Only clean, purpose-authorized evidence may be opened."));
            case "M2-14", "M2-16", "M2-17" -> List.of(new WorkforceScreen.Notice(
                    "warning",
                    "Independent controls",
                    "Clinical scope, point-in-time service eligibility, and application access are evaluated independently."));
            case "M2-20" -> List.of(new WorkforceScreen.Notice(
                    "warning",
                    "No readiness override",
                    "A stale result or any blocker prevents activation; maker and checker separation is enforced by the server."));
            case "M2-27", "M2-29" -> List.of(new WorkforceScreen.Notice(
                    "info",
                    "Minimum-necessary evidence",
                    "This projection states what happened without exposing document content, protected notes, or hidden match candidates."));
            default -> List.of();
        };
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String maskEmail(String value) {
        var at = value.indexOf('@');
        return value.substring(0, Math.min(1, at)) + "•••" + value.substring(at);
    }

    private static String maskPhone(String value) {
        var suffix = value.substring(Math.max(0, value.length() - 4));
        return "••••" + suffix;
    }

    // Mutation implementations follow below. They deliberately use explicit SQL per aggregate so
    // tenant keys, lifecycle guards, revisions and evidence payloads stay reviewable.

    private MutationResult startOnboarding(
            AuthorizedTenantContext context, MutationCommand command) {
        var personId = UuidV7Generator.randomUuid();
        var linkId = UuidV7Generator.randomUuid();
        var memberId = UuidV7Generator.randomUuid();
        var given = required(command, "legalGivenName");
        var family = required(command, "legalFamilyName");
        var display = required(command, "displayName");
        var birthDate = optionalDate(command, "birthDate");
        requireAdultBirthDate(birthDate, command.now());
        var startDate = optionalDate(command, "proposedStartDate");
        var memberNumber = "WF-" + memberId.toString().substring(0, 8).toUpperCase();
        jdbc.update(
                """
                INSERT INTO person_profiles(
                    id,legal_given_name,legal_family_name,display_name,birth_date,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?)
                """,
                personId,
                given,
                family,
                display,
                birthDate,
                context.actorId(),
                context.actorId());
        jdbc.update(
                """
                INSERT INTO organization_person_links(
                    id,organization_id,person_id,display_label,relationship_status,effective_from,
                    status,created_by,updated_by)
                VALUES (?,?,?,?, 'candidate',?, 'candidate',?,?)
                """,
                linkId,
                context.organizationId(),
                personId,
                display,
                Timestamp.from(command.now()),
                context.actorId(),
                context.actorId());
        jdbc.update(
                """
                INSERT INTO workforce_members(
                    id,organization_id,organization_person_link_id,pathway,member_number,
                    account_access_intent,proposed_start_date,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                memberId,
                context.organizationId(),
                linkId,
                required(command, "pathway"),
                memberNumber,
                required(command, "accountAccessIntent"),
                startDate,
                context.actorId(),
                context.actorId());
        var memberNumberDigest = digest(memberNumber);
        jdbc.update(
                """
                INSERT INTO workforce_identifiers(
                    id,organization_id,workforce_member_id,identifier_type_key,authority,
                    normalized_value,value_digest,masked_display,effective_from,
                    verification_state,primary_identifier,status,created_by,updated_by)
                VALUES (?,?,?,'workforce_member_number',?,?,?,?,?,'verified',true,'active',?,?)
                """,
                UuidV7Generator.randomUuid(),
                context.organizationId(),
                memberId,
                context.organizationId().toString(),
                memberNumber.getBytes(StandardCharsets.UTF_8),
                memberNumberDigest,
                memberNumber,
                Timestamp.from(command.now()),
                context.actorId(),
                context.actorId());
        jdbc.update(
                """
                UPDATE organization_person_links
                SET source_workforce_member_id=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=0
                """,
                memberId,
                context.actorId(),
                context.organizationId(),
                linkId);
        replaceLegalNameBirthDateMatchKey(
                context, linkId, given, family, birthDate, command.now());
        saveWorkContact(
                context, linkId, personId, "email", optional(command, "workEmail"), command.now());
        saveWorkContact(
                context, linkId, personId, "phone", optional(command, "workPhone"), command.now());
        var audit = ordered(
                "memberId", memberId,
                "organizationPersonLinkId", linkId,
                "linkType", "new_person",
                "lockVersion", 0);
        var outbox = ordered(
                "memberId", memberId,
                "pathway", required(command, "pathway"),
                "lockVersion", 0);
        return result(
                memberId,
                "workforce_member",
                "workforce.person.linked",
                "workforce.member.draft_created",
                "workforce_member",
                audit,
                outbox,
                201,
                0);
    }

    private MutationResult recordMatchDecision(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId = member(command);
        if (command.targetId() == null || !command.targetId().equals(memberId)) {
            throw conflict("The selected match case is not bound to this workforce member.");
        }
        var revision = requireRevision(command);
        requireMember(context, memberId);
        var suppliedMatchRunId = required(command, "matchRunId");
        if (!suppliedMatchRunId.matches("[0-9a-f]{64}")) {
            throw invalid("matchRunId must be the exact lowercase match-run digest.");
        }
        var decision = required(command, "decisionCode");
        if (!Set.of("use_existing", "create_new").contains(decision)) {
            throw invalid("The person-match decision is not supported.");
        }
        UUID candidateReference;
        try {
            candidateReference = UUID.fromString(required(command, "candidateReference"));
        } catch (IllegalArgumentException exception) {
            throw invalid("candidateReference must be an authorized opaque UUID.");
        }
        var onboardingCase = jdbc.query(
                """
                SELECT member.id,member.organization_person_link_id AS link_id,link.person_id,
                       member.member_number,member.lock_version,member.updated_at,
                       person.display_name
                FROM workforce_members member
                JOIN organization_person_links link
                  ON link.organization_id=member.organization_id
                 AND link.id=member.organization_person_link_id
                JOIN person_profiles person ON person.id=link.person_id
                WHERE member.organization_id=? AND member.id=?
                  AND member.lifecycle_state='draft' AND member.lock_version=?
                  AND link.relationship_status='candidate' AND link.effective_to IS NULL
                FOR UPDATE
                """,
                resultSet -> resultSet.next()
                        ? new PersonMatchCase(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("link_id", UUID.class),
                                resultSet.getObject("person_id", UUID.class),
                                resultSet.getString("display_name"),
                                resultSet.getString("member_number"),
                                resultSet.getLong("lock_version"),
                                resultSet.getTimestamp("updated_at").toInstant())
                        : null,
                context.organizationId(), memberId, revision);
        if (onboardingCase == null) {
            throw stale("The onboarding match decision is stale or unavailable.");
        }
        var candidates = personMatchCandidates(
                context, onboardingCase.currentLinkId(), command.now());
        var currentMatchRunId = personMatchRunDigest(context, onboardingCase, candidates);
        if (!MessageDigest.isEqual(
                suppliedMatchRunId.getBytes(StandardCharsets.UTF_8),
                currentMatchRunId.getBytes(StandardCharsets.UTF_8))) {
            throw conflict("The organization person-match result changed; review a fresh match run.");
        }
        PersonMatchCandidate selectedCandidate = null;
        if (decision.equals("create_new")) {
            if (!candidateReference.equals(onboardingCase.currentLinkId())) {
                throw conflict("The create-new decision is not bound to the provisional person link.");
            }
            var activated = jdbc.update(
                    """
                    UPDATE organization_person_links
                    SET relationship_status='active',status='active',
                        lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND relationship_status='candidate'
                    """,
                    context.actorId(), context.organizationId(), onboardingCase.currentLinkId());
            requireChanged(activated, "The candidate person link changed before the decision committed.");
        } else {
            selectedCandidate = candidates.stream()
                    .filter(candidate -> candidate.linkId().equals(candidateReference))
                    .findFirst()
                    .orElseThrow(() -> conflict(
                            "The selected existing person is not in this fresh organization match run."));
        }
        UUID resultMemberId = memberId;
        long resultRevision = revision + 1;
        if (selectedCandidate != null && selectedCandidate.liveMemberId() != null) {
            var existing = requireMember(context, selectedCandidate.liveMemberId());
            discardProvisionalOnboardingCase(context, onboardingCase);
            resultMemberId = existing.id();
            resultRevision = existing.revision();
        } else {
            if (selectedCandidate != null) {
                var ended = jdbc.update(
                        """
                        UPDATE organization_person_links
                        SET relationship_status='ended',status='ended',
                            effective_to=GREATEST(?::timestamptz,effective_from+interval '1 microsecond'),
                            lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                        WHERE organization_id=? AND id=? AND relationship_status='candidate'
                        """,
                        Timestamp.from(command.now()), context.actorId(), context.organizationId(),
                        onboardingCase.currentLinkId());
                requireChanged(ended, "The provisional person link changed before the decision committed.");
                jdbc.update(
                        """
                        UPDATE person_match_keys
                        SET effective_to=GREATEST(?::timestamptz,effective_from+interval '1 microsecond'),
                            status='revoked',lock_version=lock_version+1,
                            updated_at=clock_timestamp(),updated_by=?
                        WHERE organization_id=? AND organization_person_link_id=?
                          AND status='active' AND effective_to IS NULL
                        """,
                        Timestamp.from(command.now()), context.actorId(), context.organizationId(),
                        onboardingCase.currentLinkId());
            }
            var changed = jdbc.update(
                    """
                    UPDATE workforce_members
                    SET organization_person_link_id=?,lock_version=lock_version+1,
                        updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND lock_version=? AND lifecycle_state='draft'
                    """,
                    selectedCandidate == null
                            ? onboardingCase.currentLinkId()
                            : candidateReference,
                    context.actorId(),
                    context.organizationId(),
                    memberId,
                    revision);
            requireChanged(changed, "The onboarding match decision is stale or unavailable.");
        }
        if (selectedCandidate != null && selectedCandidate.liveMemberId() == null) {
            jdbc.update(
                    """
                    UPDATE organization_person_links
                    SET source_workforce_member_id=COALESCE(source_workforce_member_id,?),
                        lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND relationship_status='active'
                    """,
                    memberId, context.actorId(), context.organizationId(), candidateReference);
        }
        var payload = ordered(
                "onboardingId", memberId,
                "matchRunId", suppliedMatchRunId,
                "decisionCode", decision,
                "candidateReference", candidateReference,
                "lockVersion", revision + 1);
        return result(
                resultMemberId,
                "workforce_member",
                "workforce.person.match_decided",
                null,
                null,
                payload,
                Map.of(),
                200,
                resultRevision);
    }

    private void discardProvisionalOnboardingCase(
            AuthorizedTenantContext context, PersonMatchCase onboardingCase) {
        var discarded = jdbc.queryForObject(
                "SELECT careos_discard_provisional_onboarding_case(?,?,?,?,?)",
                Boolean.class,
                context.organizationId(),
                onboardingCase.memberId(),
                onboardingCase.currentLinkId(),
                onboardingCase.personId(),
                onboardingCase.revision());
        if (!Boolean.TRUE.equals(discarded)) {
            throw conflict("The provisional onboarding case changed before it could be resolved.");
        }
    }

    private MutationResult requestPersonMerge(
            AuthorizedTenantContext context, MutationCommand command) {
        var retained = requirePersonMergeMember(context, requireTarget(command));
        var revision = requireRevision(command);
        if (retained.memberRevision() != revision) {
            throw stale("The retained member changed before the merge request was prepared.");
        }
        var discarded = requirePersonMergeMember(context, uuid(command, "discardedMemberId"));
        if (retained.memberId().equals(discarded.memberId())
                || retained.linkId().equals(discarded.linkId())) {
            throw invalid("The retained and discarded person links must be different.");
        }
        if (!retained.linkStatus().equals("active") || !discarded.linkStatus().equals("active")) {
            throw conflict("Only active organization person links may be consolidated.");
        }
        lockPersonMergeLinks(context, retained.linkId(), discarded.linkId());
        if (exists(
                """
                SELECT EXISTS(SELECT 1 FROM person_merge_requests request
                WHERE request.organization_id=?
                  AND request.decision_state IN ('draft','submitted','approved')
                  AND (request.retained_link_id IN (?,?)
                    OR request.discarded_link_id IN (?,?)))
                """,
                context.organizationId(), retained.linkId(), discarded.linkId(),
                retained.linkId(), discarded.linkId())) {
            throw conflict("One of the person links already has an open merge request.");
        }
        var reviewedImpact = personMergeRequestImpact(context, command);
        requireCurrentImpact(command, reviewedImpact);
        var requestId = UuidV7Generator.randomUuid();
        var candidateDigest = personMergeCandidateDigest(context, retained.linkId(), discarded.linkId());
        var impactDigest = reviewedImpact.digest();
        jdbc.update(
                """
                INSERT INTO person_merge_requests(
                    id,organization_id,retained_link_id,discarded_link_id,requested_by,
                    candidate_evidence_digest,impact_digest,decision_state,reason_code,
                    submitted_at,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,'submitted','duplicate_organization_link',?,'submitted',?,?)
                """,
                requestId,
                context.organizationId(),
                retained.linkId(),
                discarded.linkId(),
                context.actorId(),
                candidateDigest,
                impactDigest,
                Timestamp.from(command.now()),
                context.actorId(),
                context.actorId());
        var payload = ordered(
                "mergeRequestId", requestId,
                "retainedLinkId", retained.linkId(),
                "discardedLinkId", discarded.linkId(),
                "impactDigest", impactDigest,
                "state", "submitted");
        return result(
                requestId,
                "person_merge_request",
                "workforce.person_merge.requested",
                null,
                null,
                payload,
                Map.of(),
                201,
                0);
    }

    private MutationResult decidePersonMerge(
            AuthorizedTenantContext context, MutationCommand command, boolean approved) {
        var requestId = requireTarget(command);
        var revision = requireRevision(command);
        var request = requirePersonMergeRequest(context, requestId);
        if (!request.state().equals("submitted") || request.revision() != revision) {
            throw stale("The person-link merge request is no longer awaiting a decision.");
        }
        if (request.requestedBy().equals(context.actorId())
                || isSubjectActor(context, request.retainedMemberId())
                || isSubjectActor(context, request.discardedMemberId())) {
            throw conflict("The requester or an affected account cannot decide this merge request.");
        }
        lockPersonMergeLinks(context, request.retainedLinkId(), request.discardedLinkId());
        if (approved) {
            requireCurrentPersonMergeImpact(context, request);
        }
        var state = approved ? "approved" : "rejected";
        var changed = jdbc.update(
                """
                UPDATE person_merge_requests
                SET decision_state=?,status=?,decision_by=?,decided_at=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND decision_state='submitted'
                  AND lock_version=? AND requested_by<>?
                """,
                state,
                state,
                context.actorId(),
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                requestId,
                revision,
                context.actorId());
        requireChanged(changed, "The person-link merge request changed before the decision committed.");
        var payload = ordered(
                "mergeRequestId", requestId,
                "retainedLinkId", request.retainedLinkId(),
                "discardedLinkId", request.discardedLinkId(),
                "impactDigest", request.impactDigest(),
                "state", state);
        return result(
                requestId,
                "person_merge_request",
                "workforce.person_merge." + state,
                null,
                null,
                payload,
                Map.of(),
                200,
                revision + 1);
    }

    private MutationResult cancelPersonMerge(
            AuthorizedTenantContext context, MutationCommand command) {
        var requestId = requireTarget(command);
        var revision = requireRevision(command);
        var request = requirePersonMergeRequest(context, requestId);
        if (!request.state().equals("submitted")
                || request.revision() != revision
                || !request.requestedBy().equals(context.actorId())) {
            throw conflict("Only the requester may cancel a submitted person-link merge.");
        }
        var changed = jdbc.update(
                """
                UPDATE person_merge_requests
                SET decision_state='cancelled',status='cancelled',
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND decision_state='submitted'
                  AND lock_version=? AND requested_by=?
                """,
                context.actorId(), context.organizationId(), requestId, revision, context.actorId());
        requireChanged(changed, "The person-link merge request changed before cancellation.");
        var payload = ordered(
                "mergeRequestId", requestId,
                "retainedLinkId", request.retainedLinkId(),
                "discardedLinkId", request.discardedLinkId(),
                "impactDigest", request.impactDigest(),
                "state", "cancelled");
        return result(
                requestId,
                "person_merge_request",
                "workforce.person_merge.cancelled",
                null,
                null,
                payload,
                Map.of(),
                200,
                revision + 1);
    }

    private MutationResult executePersonMerge(
            AuthorizedTenantContext context, MutationCommand command) {
        var requestId = requireTarget(command);
        var revision = requireRevision(command);
        var request = requirePersonMergeRequest(context, requestId);
        if (!request.state().equals("approved") || request.revision() != revision) {
            throw stale("The person-link merge request is not approved at this revision.");
        }
        if (request.requestedBy().equals(context.actorId())
                || isSubjectActor(context, request.retainedMemberId())
                || isSubjectActor(context, request.discardedMemberId())) {
            throw conflict("The requester or an affected account cannot execute this merge request.");
        }
        lockPersonMergeLinks(context, request.retainedLinkId(), request.discardedLinkId());
        requireCurrentPersonMergeImpact(context, request);
        var retainedLiveMember = currentMemberForLink(context, request.retainedLinkId());
        var discardedLiveMember = currentMemberForLink(context, request.discardedLinkId());
        if (retainedLiveMember != null && discardedLiveMember != null
                && !retainedLiveMember.equals(discardedLiveMember)) {
            throw conflict(
                    "Both person links still own live workforce records. Complete governed duplicate-record offboarding before consolidation.");
        }
        var affectedReferences = 0;
        UUID retainedMemberId = retainedLiveMember;
        if (retainedLiveMember == null && discardedLiveMember != null) {
            var moved = jdbc.update(
                    """
                    UPDATE workforce_members
                    SET organization_person_link_id=?,lock_version=lock_version+1,
                        updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND organization_person_link_id=?
                      AND lifecycle_state<>'offboarded'
                    """,
                    request.retainedLinkId(),
                    context.actorId(),
                    context.organizationId(),
                    discardedLiveMember,
                    request.discardedLinkId());
            requireChanged(moved, "The live workforce reference changed before consolidation.");
            retainedMemberId = discardedLiveMember;
            affectedReferences += moved;
        }
        if (retainedMemberId == null) {
            retainedMemberId = request.retainedMemberId();
        }
        var revokedKeys = jdbc.update(
                """
                UPDATE person_match_keys discarded
                SET status='revoked',
                    effective_to=GREATEST(?::timestamptz,discarded.effective_from+interval '1 microsecond'),
                    lock_version=discarded.lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE discarded.organization_id=?
                  AND discarded.organization_person_link_id=? AND discarded.status='active'
                  AND EXISTS(SELECT 1 FROM person_match_keys retained
                      WHERE retained.organization_id=discarded.organization_id
                        AND retained.organization_person_link_id=? AND retained.status='active'
                        AND retained.key_type=discarded.key_type
                        AND retained.hmac_key_version=discarded.hmac_key_version
                        AND retained.keyed_digest=discarded.keyed_digest)
                """,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                request.discardedLinkId(),
                request.retainedLinkId());
        var movedKeys = jdbc.update(
                """
                UPDATE person_match_keys
                SET organization_person_link_id=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND organization_person_link_id=? AND status='active'
                """,
                request.retainedLinkId(),
                context.actorId(),
                context.organizationId(),
                request.discardedLinkId());
        affectedReferences += revokedKeys + movedKeys;
        if (retainedMemberId != null) {
            jdbc.update(
                    """
                    UPDATE organization_person_links
                    SET source_workforce_member_id=COALESCE(source_workforce_member_id,?),
                        lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND relationship_status='active'
                    """,
                    retainedMemberId,
                    context.actorId(),
                    context.organizationId(),
                    request.retainedLinkId());
        }
        var merged = jdbc.update(
                """
                UPDATE organization_person_links
                SET relationship_status='merged',status='merged',
                    effective_to=GREATEST(?::timestamptz,effective_from+interval '1 microsecond'),
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND relationship_status='active'
                """,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                request.discardedLinkId());
        requireChanged(merged, "The discarded person link changed before consolidation.");
        affectedReferences += merged;
        var executed = jdbc.update(
                """
                UPDATE person_merge_requests
                SET decision_state='executed',status='executed',executed_at=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND decision_state='approved'
                  AND lock_version=? AND requested_by<>?
                """,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                requestId,
                revision,
                context.actorId());
        requireChanged(executed, "The approved person-link merge changed before execution.");
        var audit = ordered(
                "mergeRequestId", requestId,
                "retainedLinkId", request.retainedLinkId(),
                "discardedLinkId", request.discardedLinkId(),
                "impactDigest", request.impactDigest(),
                "state", "executed");
        var outbox = ordered(
                "mergeRequestId", requestId,
                "retainedMemberId", retainedMemberId,
                "affectedReferenceCount", affectedReferences);
        return result(
                requestId,
                "person_merge_request",
                "workforce.person_merge.executed",
                "workforce.person_merge.executed",
                "person_merge_request",
                audit,
                outbox,
                200,
                revision + 1);
    }

    private MutationResult saveIdentity(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId = requireTarget(command);
        var revision = requireRevision(command);
        requireMember(context, memberId);
        var identityLink = jdbc.query(
                """
                SELECT link.id,link.person_id FROM workforce_members member
                JOIN organization_person_links link ON link.organization_id=member.organization_id
                  AND link.id=member.organization_person_link_id
                WHERE member.organization_id=? AND member.id=?
                """,
                resultSet -> resultSet.next()
                        ? new PersonIdentityLink(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("person_id", UUID.class))
                        : null,
                context.organizationId(), memberId);
        if (identityLink == null) {
            throw notFound("The member identity link was not found.");
        }
        var birthDate = optionalDate(command, "birthDate");
        requireAdultBirthDate(birthDate, command.now());
        var given = required(command, "legalGivenName");
        var family = required(command, "legalFamilyName");
        var changed = jdbc.update(
                """
                UPDATE person_profiles person
                SET legal_given_name=?,legal_family_name=?,display_name=?,birth_date=?,preferred_locale=?,
                    identity_revision=identity_revision+1,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                FROM organization_person_links link
                JOIN workforce_members member ON member.organization_id=link.organization_id
                  AND member.organization_person_link_id=link.id
                WHERE member.organization_id=? AND member.id=? AND person.id=link.person_id
                  AND person.lock_version=?
                """,
                given,
                family,
                required(command, "displayName"),
                birthDate,
                optional(command, "preferredLocale"),
                context.actorId(),
                context.organizationId(),
                memberId,
                revision);
        requireChanged(changed, "The identity proposal is stale or unavailable.");
        replaceLegalNameBirthDateMatchKey(
                context, identityLink.linkId(), given, family, birthDate, command.now());
        saveWorkContact(
                context,
                identityLink.linkId(),
                identityLink.personId(),
                "email",
                optional(command, "workEmail"),
                command.now());
        saveWorkContact(
                context,
                identityLink.linkId(),
                identityLink.personId(),
                "phone",
                optional(command, "workPhone"),
                command.now());
        if (!exists(
                """
                SELECT EXISTS(SELECT 1 FROM person_contacts
                WHERE person_id=? AND contact_use='work' AND status='active')
                """,
                identityLink.personId())) {
            throw invalid("At least one work email or work phone is required.");
        }
        var changedFields = command.fields().keySet().stream().sorted().toList();
        var audit = ordered(
                "memberId", memberId,
                "changedFields", changedFields,
                "provenanceCode", required(command, "provenanceCode"),
                "lockVersion", revision + 1);
        var outbox = ordered(
                "memberId", memberId,
                "changeFamily", "identity",
                "lockVersion", revision + 1);
        return result(
                memberId,
                "workforce_member",
                "workforce.person.corrected",
                "workforce.member.changed",
                "workforce_member",
                audit,
                outbox,
                200,
                revision + 1);
    }

    private void saveWorkContact(
            AuthorizedTenantContext context,
            UUID organizationPersonLinkId,
            UUID personId,
            String channel,
            String value,
            Instant now) {
        if (value == null) {
            return;
        }
        var normalizedValue = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        var normalized = channel.equals("email")
                ? normalizedValue.toLowerCase(Locale.ROOT)
                : normalizedValue.replaceAll("[^0-9+]", "");
        if (channel.equals("email") && !normalized.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw invalid("workEmail has an invalid format.");
        }
        if (channel.equals("phone") && !normalized.matches("\\+?[0-9]{7,15}")) {
            throw invalid("workPhone has an invalid format.");
        }
        jdbc.update(
                """
                UPDATE person_contacts
                SET effective_to=GREATEST(?::timestamptz,effective_from+interval '1 microsecond'),
                    status='ended',primary_contact=false,preferred_contact=false,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE person_id=? AND channel=? AND contact_use='work'
                  AND status='active' AND effective_to IS NULL
                """,
                Timestamp.from(now), context.actorId(), personId, channel);
        var contactDigest = sensitiveValues.digest(
                context.organizationId(), "person_contact:" + channel, normalized);
        var masked = channel.equals("email") ? maskEmail(normalized) : maskPhone(normalized);
        jdbc.update(
                """
                INSERT INTO person_contacts(
                    id,person_id,channel,contact_use,encrypted_value,normalized_digest,
                    masked_display,verification_state,primary_contact,preferred_contact,
                    effective_from,status,created_by,updated_by)
                VALUES (?,?,?,'work',?,?,?,'unverified',true,? ,?,'active',?,?)
                """,
                UuidV7Generator.randomUuid(),
                personId,
                channel,
                sensitiveValues.encrypt(
                        context.organizationId(), "person_contact:" + channel, normalized),
                contactDigest,
                masked,
                channel.equals("email"),
                Timestamp.from(now),
                context.actorId(),
                context.actorId());
        replaceMatchKey(
                context,
                organizationPersonLinkId,
                channel,
                normalizeMatchValue(normalized),
                now);
    }

    private void replaceLegalNameBirthDateMatchKey(
            AuthorizedTenantContext context,
            UUID organizationPersonLinkId,
            String legalGivenName,
            String legalFamilyName,
            LocalDate birthDate,
            Instant now) {
        var normalized = birthDate == null
                ? null
                : normalizeMatchValue(legalGivenName) + "\u001f"
                        + normalizeMatchValue(legalFamilyName) + "\u001f" + birthDate;
        replaceMatchKey(
                context,
                organizationPersonLinkId,
                "legal_name_birth_date",
                normalized,
                now);
    }

    private void replaceMatchKey(
            AuthorizedTenantContext context,
            UUID organizationPersonLinkId,
            String keyType,
            String normalizedValue,
            Instant now) {
        jdbc.update(
                """
                UPDATE person_match_keys
                SET effective_to=GREATEST(?::timestamptz,effective_from+interval '1 microsecond'),
                    status='rotated',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND organization_person_link_id=? AND key_type=?
                  AND status='active' AND effective_to IS NULL
                """,
                Timestamp.from(now),
                context.actorId(),
                context.organizationId(),
                organizationPersonLinkId,
                keyType);
        if (normalizedValue == null || normalizedValue.isBlank()) {
            return;
        }
        jdbc.update(
                """
                INSERT INTO person_match_keys(
                    id,organization_id,organization_person_link_id,key_type,hmac_key_version,
                    keyed_digest,effective_from,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,'active',?,?)
                """,
                UuidV7Generator.randomUuid(),
                context.organizationId(),
                organizationPersonLinkId,
                keyType,
                matchKeys.keyVersion(),
                matchKeys.digest(context.organizationId(), keyType, normalizedValue),
                Timestamp.from(now),
                context.actorId(),
                context.actorId());
    }

    private static String normalizeMatchValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private MutationResult saveEngagement(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId = member(command);
        requireMember(context, memberId);
        var engagementId = UuidV7Generator.randomUuid();
        var effectiveFrom = instant(command, "effectiveFrom");
        var effectiveTo = optionalInstant(command, "effectiveTo");
        requireRange(effectiveFrom, effectiveTo);
        requireRegistryEntryKey(
                context, required(command, "employmentCategoryKey"), "employment_category", effectiveFrom);
        jdbc.update(
                """
                INSERT INTO employment_engagements(
                    id,organization_id,workforce_member_id,engagement_type,employment_category_key,
                    effective_from,effective_to,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,'draft',?,?)
                """,
                engagementId,
                context.organizationId(),
                memberId,
                required(command, "engagementType"),
                required(command, "employmentCategoryKey"),
                Timestamp.from(effectiveFrom),
                timestamp(effectiveTo),
                context.actorId(),
                context.actorId());
        var payload = ordered(
                "memberId", memberId,
                "engagementId", engagementId,
                "fromState", "none",
                "toState", "draft",
                "effectiveFrom", effectiveFrom,
                "effectiveTo", effectiveTo,
                "lockVersion", 0);
        return result(
                engagementId,
                "employment_engagement",
                "workforce.engagement.created",
                "workforce.engagement.created",
                "employment_engagement",
                payload,
                payload,
                201,
                0);
    }

    private MutationResult savePractitioner(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId = member(command);
        var member = requireMember(context, memberId);
        if (!member.pathway().equals("clinical")) {
            throw conflict("A practitioner profile requires the clinical onboarding pathway.");
        }
        var practitionerId = UuidV7Generator.randomUuid();
        var professionEntryId = uuid(command, "professionEntryId");
        var professionVersionId = uuid(command, "professionVersionId");
        var effectiveFrom = instant(command, "effectiveFrom");
        requireRegistryCategory(
                context, professionEntryId, professionVersionId, "profession", effectiveFrom);
        if (exists(
                """
                SELECT EXISTS(SELECT 1 FROM practitioner_profiles
                WHERE organization_id=? AND workforce_member_id=? AND status<>'ended')
                """,
                context.organizationId(), memberId)) {
            throw conflict("This workforce member already has a current practitioner profile.");
        }
        jdbc.update(
                """
                INSERT INTO practitioner_profiles(
                    id,organization_id,workforce_member_id,profession_entry_id,profession_version_id,
                    regulated,clinical_title,effective_from,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                practitionerId,
                context.organizationId(),
                memberId,
                professionEntryId,
                professionVersionId,
                bool(command, "regulated"),
                required(command, "clinicalTitle"),
                Timestamp.from(effectiveFrom),
                context.actorId(),
                context.actorId());
        var payload = ordered(
                "memberId", memberId,
                "practitionerId", practitionerId,
                "professionVersionId", professionVersionId,
                "fromState", "none",
                "toState", "draft",
                "lockVersion", 0);
        return result(
                practitionerId,
                "practitioner_profile",
                "practitioner.profile.created",
                "practitioner.profile.created",
                "practitioner_profile",
                payload,
                payload,
                201,
                0);
    }

    private MutationResult addQualification(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId = member(command);
        requireMember(context, memberId);
        var practitionerId = findPractitionerId(context, memberId, false);
        var qualificationId = UuidV7Generator.randomUuid();
        var qualificationEntryId = uuid(command, "qualificationEntryId");
        var qualificationVersionId = uuid(command, "qualificationVersionId");
        var awardedOn = date(command, "awardedOn");
        var expiresOn = optionalDate(command, "expiresOn");
        if (awardedOn.isAfter(command.now().atZone(ZoneOffset.UTC).toLocalDate())) {
            throw invalid("Qualification award date cannot be in the future.");
        }
        if (expiresOn != null && expiresOn.isBefore(awardedOn)) {
            throw invalid("Qualification expiry cannot precede its award date.");
        }
        requireRegistryCategory(
                context, qualificationEntryId, qualificationVersionId, "qualification_type", command.now());
        var supersedesId = optionalUuid(command, "supersedesId");
        if (supersedesId != null && !exists(
                """
                SELECT EXISTS(SELECT 1 FROM qualifications
                WHERE organization_id=? AND id=? AND workforce_member_id=?
                  AND qualification_entry_id=?
                  AND status IN ('verified','returned_for_correction'))
                """,
                context.organizationId(),supersedesId,memberId,qualificationEntryId)) {
            throw conflict("The qualification predecessor is unavailable or incompatible.");
        }
        jdbc.update(
                """
                INSERT INTO qualifications(
                    id,organization_id,workforce_member_id,practitioner_profile_id,
                    qualification_entry_id,qualification_version_id,awarding_body,country_code,
                    awarded_on,expires_on,supersedes_id,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                qualificationId,
                context.organizationId(),
                memberId,
                practitionerId,
                qualificationEntryId,
                qualificationVersionId,
                required(command, "awardingBody"),
                required(command, "countryCode").toUpperCase(),
                awardedOn,
                expiresOn,
                supersedesId,
                context.actorId(),
                context.actorId());
        var audit = ordered(
                "memberId", memberId,
                "qualificationId", qualificationId,
                "fromState", "none",
                "toState", "draft",
                "revision", 0);
        var outbox = ordered(
                "memberId", memberId,
                "qualificationId", qualificationId,
                "toState", "draft",
                "revision", 0);
        return result(
                qualificationId,
                "qualification",
                "credential.qualification.created",
                "credential.qualification.changed",
                "qualification",
                audit,
                outbox,
                201,
                0);
    }

    private MutationResult addRegistration(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId = member(command);
        var practitionerId = findPractitionerId(context, memberId, true);
        var registrationId = UuidV7Generator.randomUuid();
        var number = required(command, "registrationNumber").replaceAll("\\s+", "").toUpperCase();
        if (!number.matches("[A-Z0-9./-]{2,120}")) {
            throw invalid("registrationNumber contains unsupported characters.");
        }
        var numberDigest = sensitiveValues.digest(
                context.organizationId(), "professional_registration_number", number);
        var masked = number.length() <= 4
                ? "••••"
                : "••••" + number.substring(number.length() - 4);
        var validFrom = date(command, "validFrom");
        var expiresOn = optionalDate(command, "expiresOn");
        if (expiresOn != null && expiresOn.isBefore(validFrom)) {
            throw invalid("Registration expiry cannot precede its valid-from date.");
        }
        var regulatorEntryId = uuid(command, "regulatorEntryId");
        var regulatorVersionId = uuid(command, "regulatorVersionId");
        var registrationTypeEntryId = uuid(command, "registrationTypeEntryId");
        var registrationTypeVersionId = uuid(command, "registrationTypeVersionId");
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",
                resultSet -> {
                    resultSet.next();
                    return Boolean.TRUE;
                },
                "professional-registration:" + context.organizationId() + ":"
                        + regulatorEntryId + ":" + registrationTypeEntryId + ":" + numberDigest);
        requireRegistryCategory(
                context, regulatorEntryId, regulatorVersionId, "regulator", command.now());
        requireRegistryCategory(
                context, registrationTypeEntryId, registrationTypeVersionId,
                "registration_type", command.now());
        var supersedesId = optionalUuid(command, "supersedesId");
        if (supersedesId != null && !exists(
                """
                SELECT EXISTS(SELECT 1 FROM professional_registrations
                WHERE organization_id=? AND id=? AND practitioner_profile_id=?
                  AND regulator_entry_id=? AND registration_type_entry_id=?
                  AND status IN ('verified','suspended','expired'))
                """,
                context.organizationId(),supersedesId,practitionerId,
                regulatorEntryId,registrationTypeEntryId)) {
            throw conflict("The registration predecessor is unavailable or incompatible.");
        }
        if (exists(
                """
                SELECT EXISTS(SELECT 1 FROM professional_registrations
                WHERE organization_id=? AND regulator_entry_id=?
                  AND registration_type_entry_id=? AND number_digest=?
                  AND status NOT IN ('revoked','expired','superseded')
                  AND (?::uuid IS NULL OR id<>?::uuid))
                """,
                context.organizationId(),regulatorEntryId,registrationTypeEntryId,numberDigest,
                supersedesId,supersedesId)) {
            throw conflict("A current registration already uses this regulator, type, and number.");
        }
        jdbc.update(
                """
                INSERT INTO professional_registrations(
                    id,organization_id,practitioner_profile_id,regulator_entry_id,
                    regulator_version_id,registration_type_entry_id,registration_type_version_id,
                    encrypted_number,number_digest,masked_display,jurisdiction_country,valid_from,
                    expires_on,supersedes_id,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                registrationId,
                context.organizationId(),
                practitionerId,
                regulatorEntryId,
                regulatorVersionId,
                registrationTypeEntryId,
                registrationTypeVersionId,
                sensitiveValues.encrypt(
                        context.organizationId(), "professional_registration_number", number),
                numberDigest,
                masked,
                required(command, "jurisdictionCountry").toUpperCase(),
                validFrom,
                expiresOn,
                supersedesId,
                context.actorId(),
                context.actorId());
        var payload = ordered(
                "practitionerId", practitionerId,
                "registrationId", registrationId,
                "fromState", "none",
                "toState", "draft",
                "expiryDate", expiresOn,
                "revision", 0);
        return result(
                registrationId,
                "professional_registration",
                "credential.registration.created",
                "credential.registration.created",
                "professional_registration",
                payload,
                payload,
                201,
                0);
    }

    private MutationResult createCredential(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId = member(command);
        requireMember(context, memberId);
        var practitionerId = findPractitionerId(context, memberId, false);
        var credentialId = UuidV7Generator.randomUuid();
        var credentialTypeEntryId = uuid(command, "credentialTypeEntryId");
        var credentialTypeVersionId = uuid(command, "credentialTypeVersionId");
        var issuedOn = optionalDate(command, "issuedOn");
        var expiresOn = optionalDate(command, "expiresOn");
        if (issuedOn != null && expiresOn != null && expiresOn.isBefore(issuedOn)) {
            throw invalid("Credential expiry cannot precede its issue date.");
        }
        requireRegistryCategory(
                context, credentialTypeEntryId, credentialTypeVersionId,
                "credential_type", command.now());
        var supersedesId = optionalUuid(command, "supersedesId");
        if (supersedesId != null && !exists(
                """
                SELECT EXISTS(SELECT 1 FROM practitioner_credentials
                WHERE organization_id=? AND id=? AND workforce_member_id=?
                  AND credential_type_entry_id=?
                  AND status IN ('verified','more_information_required',
                                 'returned_for_correction','suspended','expired'))
                """,
                context.organizationId(),supersedesId,memberId,credentialTypeEntryId)) {
            throw conflict("The credential predecessor is unavailable or incompatible.");
        }
        var contentDigest = digest(String.join(
                "|",
                required(command, "credentialTypeEntryId"),
                required(command, "credentialTypeVersionId"),
                required(command, "issuer"),
                Objects.toString(issuedOn, ""),
                Objects.toString(expiresOn, ""),
                required(command, "riskTier"),
                Objects.toString(supersedesId, "")));
        jdbc.update(
                """
                INSERT INTO practitioner_credentials(
                    id,organization_id,practitioner_profile_id,workforce_member_id,
                    credential_type_entry_id,credential_type_version_id,issuer,issued_on,expires_on,
                    risk_tier,supersedes_id,content_digest,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                credentialId,
                context.organizationId(),
                practitionerId,
                memberId,
                credentialTypeEntryId,
                credentialTypeVersionId,
                required(command, "issuer"),
                issuedOn,
                expiresOn,
                required(command, "riskTier"),
                supersedesId,
                contentDigest,
                context.actorId(),
                context.actorId());
        var payload = ordered(
                "practitionerId", practitionerId,
                "credentialId", credentialId,
                "fromState", "none",
                "toState", "draft",
                "evidenceCount", 0,
                "revision", 0);
        return result(
                credentialId,
                "practitioner_credential",
                "credential.record.created",
                null,
                null,
                payload,
                Map.of(),
                201,
                0);
    }

    private MutationResult submitCredential(
            AuthorizedTenantContext context, MutationCommand command) {
        var credentialId = requireTarget(command);
        var revision = requireRevision(command);
        var credential = requireCredential(context, credentialId);
        var evidenceCount = Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT count(*) FROM credential_documents
                WHERE organization_id=? AND practitioner_credential_id=? AND status='clean'
                """,
                Integer.class,
                context.organizationId(),
                credentialId));
        if (evidenceCount < 1) {
            throw new WorkforceException(
                    WorkforceException.Reason.EVIDENCE_NOT_CLEAN,
                    "At least one clean promoted credential document is required before submission.");
        }
        var changed = jdbc.update(
                """
                UPDATE practitioner_credentials
                SET status='submitted',submitted_by=?,submitted_at=clock_timestamp(),
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                  AND status IN ('draft','evidence_pending')
                """,
                context.actorId(),
                context.actorId(),
                context.organizationId(),
                credentialId,
                revision);
        requireChanged(changed, "The credential is stale or cannot be submitted from its current state.");
        var audit = ordered(
                "practitionerId", credential.practitionerId(),
                "credentialId", credentialId,
                "fromState", credential.status(),
                "toState", "submitted",
                "evidenceCount", evidenceCount,
                "revision", revision + 1);
        var outbox = ordered(
                "practitionerId", credential.practitionerId(),
                "credentialId", credentialId,
                "toState", "submitted",
                "revision", revision + 1);
        return result(
                credentialId,
                "practitioner_credential",
                "credential.record.submitted",
                "credential.record.submitted",
                "practitioner_credential",
                audit,
                outbox,
                200,
                revision + 1);
    }

    private MutationResult claimReview(
            AuthorizedTenantContext context, MutationCommand command) {
        var credentialId = requireTarget(command);
        var revision = requireRevision(command);
        var credential = requireCredential(context, credentialId);
        if (isSubjectActor(context, credential.memberId())) {
            throw conflict("A workforce member cannot claim their own credential review.");
        }
        var expiredLease = jdbc.query(
                """
                SELECT reviewer_id,review_lease_expires_at
                FROM practitioner_credentials
                WHERE organization_id=? AND id=? AND lock_version=?
                  AND status='in_review'
                  AND review_lease_expires_at<=clock_timestamp()
                FOR UPDATE
                """,
                resultSet -> resultSet.next()
                        ? new ReviewLease(
                                resultSet.getObject("reviewer_id", UUID.class),
                                resultSet.getTimestamp("review_lease_expires_at").toInstant())
                        : null,
                context.organizationId(),
                credentialId,
                revision);
        var leaseExpiry = command.now().plusSeconds(900);
        var changed = jdbc.update(
                """
                UPDATE practitioner_credentials
                SET status='in_review',review_started_at=clock_timestamp(),reviewer_id=?,
                    review_lease_expires_at=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                  AND (status='submitted'
                    OR (status='in_review' AND review_lease_expires_at<=clock_timestamp()))
                  AND submitted_by<>?
                  AND NOT EXISTS (
                      SELECT 1 FROM credential_documents document
                      WHERE document.organization_id=practitioner_credentials.organization_id
                        AND document.practitioner_credential_id=practitioner_credentials.id
                        AND document.created_by=?)
                """,
                context.actorId(),
                Timestamp.from(leaseExpiry),
                context.actorId(),
                context.organizationId(),
                credentialId,
                revision,
                context.actorId(),
                context.actorId());
        requireChanged(changed, "The credential cannot be claimed or is already under review.");
        var payload = ordered(
                "credentialId", credentialId,
                "queueItemId", credentialId,
                "reviewerId", context.actorId(),
                "leaseExpiry", leaseExpiry,
                "state", "claimed");
        var additionalEvidence = expiredLease == null
                ? List.<MutationEvidence>of()
                : List.of(new MutationEvidence(
                        credentialId,
                        "practitioner_credential",
                        "credential.review.lease_expired",
                        null,
                        null,
                        ordered(
                                "credentialId", credentialId,
                                "queueItemId", credentialId,
                                "reviewerId", expiredLease.reviewerId(),
                                "leaseExpiry", expiredLease.expiresAt(),
                                "state", "expired"),
                        Map.of()));
        return result(
                credentialId,
                "practitioner_credential",
                "credential.review.claimed",
                null,
                null,
                payload,
                Map.of(),
                200,
                revision + 1,
                additionalEvidence);
    }

    private MutationResult decideCredential(
            AuthorizedTenantContext context, MutationCommand command) {
        var credentialId = requireTarget(command);
        var revision = requireRevision(command);
        var credential = requireCredential(context, credentialId);
        if (!credential.status().equals("in_review")
                || credential.revision() != revision) {
            throw stale("The credential review is stale or unavailable.");
        }
        if (!exists(
                """
                SELECT EXISTS(SELECT 1 FROM practitioner_credentials
                WHERE organization_id=? AND id=? AND status='in_review'
                  AND reviewer_id=? AND review_lease_expires_at>?)
                """,
                context.organizationId(), credentialId, context.actorId(), Timestamp.from(command.now()))) {
            throw stale("The credential review lease has expired or belongs to another reviewer.");
        }
        if (isSubjectActor(context, credential.memberId())) {
            throw conflict("A workforce member cannot decide their own credential review.");
        }
        var decision = required(command, "decisionCode");
        if (!Set.of(
                        "verified",
                        "rejected",
                        "more_information_required",
                        "returned_for_correction")
                .contains(decision)) {
            throw invalid("The credential decision is not supported.");
        }
        var evidenceIds = command.evidenceIds();
        if (evidenceIds.isEmpty()) {
            throw new WorkforceException(
                    WorkforceException.Reason.EVIDENCE_NOT_CLEAN,
                    "Select at least one clean promoted document for this credential decision.");
        }
        if (Set.copyOf(evidenceIds).size() != evidenceIds.size()) {
            throw invalid("Credential decision evidence IDs must be unique.");
        }
        if (exists(
                """
                SELECT EXISTS(SELECT 1 FROM credential_documents
                WHERE organization_id=? AND practitioner_credential_id=?
                  AND id=ANY(?::uuid[]) AND created_by=?)
                """,
                context.organizationId(),
                credentialId,
                evidenceIds.toArray(UUID[]::new),
                context.actorId())) {
            throw conflict("A credential evidence uploader cannot decide that evidence.");
        }
        var cleanCount = Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT count(*) FROM credential_documents document
                JOIN document_promotion_evidence promotion
                  ON promotion.organization_id=document.organization_id
                 AND promotion.document_id=document.platform_document_id
                 AND promotion.object_version_id=document.platform_object_version_id
                WHERE document.organization_id=? AND document.practitioner_credential_id=?
                  AND document.id=ANY(?::uuid[]) AND document.status='clean'
                """,
                Integer.class,
                context.organizationId(),
                credentialId,
                evidenceIds.toArray(UUID[]::new)));
        if (cleanCount != evidenceIds.size()) {
            throw new WorkforceException(
                    WorkforceException.Reason.EVIDENCE_NOT_CLEAN,
                    "Every selected credential document must have clean promotion evidence.");
        }
        CredentialPredecessor predecessor = null;
        if (decision.equals("verified") && credential.supersedesId() != null) {
            var candidate = jdbc.query(
                    """
                    SELECT practitioner_profile_id,status,lock_version
                    FROM practitioner_credentials
                    WHERE organization_id=? AND id=? AND workforce_member_id=?
                      AND credential_type_entry_id=(
                          SELECT credential_type_entry_id FROM practitioner_credentials
                          WHERE organization_id=? AND id=?)
                    FOR UPDATE
                    """,
                    resultSet -> resultSet.next()
                            ? new CredentialPredecessor(
                                    resultSet.getObject("practitioner_profile_id", UUID.class),
                                    resultSet.getString("status"),
                                    resultSet.getLong("lock_version"))
                            : null,
                    context.organizationId(),
                    credential.supersedesId(),
                    credential.memberId(),
                    context.organizationId(),
                    credentialId);
            if (candidate == null) {
                throw stale("The credential predecessor changed before supersession.");
            }
            if (Set.of(
                            "verified",
                            "more_information_required",
                            "returned_for_correction",
                            "suspended")
                    .contains(candidate.status())) {
                predecessor = candidate;
            } else if (!candidate.status().equals("expired")) {
                throw stale("The credential predecessor changed before supersession.");
            }
        }
        var evidenceDigest = digest(evidenceIds.stream().sorted().map(UUID::toString).reduce("", (a, b) -> a + "|" + b));
        var verificationId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO credential_verifications(
                    id,organization_id,practitioner_credential_id,credential_revision,
                    credential_digest,reviewer_id,decision,decision_reason_code,evidence_ids,
                    evidence_digest,policy_version,registry_version,decided_at,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?::uuid[],?,'m2-credential-v1','m2-candidate-1',?,?,?,?)
                """,
                verificationId,
                context.organizationId(),
                credentialId,
                revision,
                credential.digest(),
                context.actorId(),
                decision,
                required(command, "decisionReasonCode"),
                evidenceIds.toArray(UUID[]::new),
                evidenceDigest,
                Timestamp.from(command.now()),
                decision,
                context.actorId(),
                context.actorId());
        var changed = jdbc.update(
                """
                UPDATE practitioner_credentials
                SET current_verification_id=?,status=?,reviewer_id=NULL,
                    review_lease_expires_at=NULL,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status='in_review'
                  AND reviewer_id=? AND review_lease_expires_at>clock_timestamp()
                """,
                verificationId,
                decision,
                context.actorId(),
                context.organizationId(),
                credentialId,
                revision,
                context.actorId());
        requireChanged(changed, "The credential changed while the decision was being recorded.");
        if (predecessor != null) {
            var superseded = jdbc.update(
                    """
                    UPDATE practitioner_credentials
                    SET status='superseded',lock_version=lock_version+1,
                        updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND workforce_member_id=?
                      AND status=? AND lock_version=?
                    """,
                    context.actorId(),
                    context.organizationId(),
                    credential.supersedesId(),
                    credential.memberId(),
                    predecessor.status(),
                    predecessor.revision());
            requireChanged(superseded, "The credential predecessor changed before supersession.");
        }
        var event = "credential.review." + decision;
        var audit = ordered(
                "practitionerId", credential.practitionerId(),
                "credentialId", credentialId,
                "verificationId", verificationId,
                "decisionCode", decision,
                "evidenceDigest", evidenceDigest,
                "policyVersion", "m2-credential-v1",
                "revision", revision + 1);
        var outbox = ordered(
                "practitionerId", credential.practitionerId(),
                "credentialId", credentialId,
                "verificationId", verificationId,
                "decisionCode", decision,
                "revision", revision + 1);
        var additionalEvidence = predecessor == null
                ? List.<MutationEvidence>of()
                : List.of(new MutationEvidence(
                        credential.supersedesId(),
                        "practitioner_credential",
                        "credential.record.superseded",
                        "credential.record.superseded",
                        "practitioner_credential",
                        ordered(
                                "practitionerId", predecessor.practitionerId(),
                                "credentialId", credential.supersedesId(),
                                "fromState", predecessor.status(),
                                "toState", "superseded",
                                "effectiveTime", command.now(),
                                "revision", predecessor.revision() + 1),
                        ordered(
                                "practitionerId", predecessor.practitionerId(),
                                "credentialId", credential.supersedesId(),
                                "fromState", predecessor.status(),
                                "toState", "superseded",
                                "effectiveTime", command.now(),
                                "revision", predecessor.revision() + 1)));
        return result(
                credentialId,
                "practitioner_credential",
                event,
                "credential.review.decided",
                "practitioner_credential",
                audit,
                outbox,
                200,
                revision + 1,
                additionalEvidence);
    }

    private MutationResult addSpecialty(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId = member(command);
        var practitionerId = findPractitionerId(context, memberId, true);
        var specialtyId = UuidV7Generator.randomUuid();
        var specialtyVersionId = uuid(command, "specialtyVersionId");
        var specialtyEntryId = uuid(command, "specialtyEntryId");
        var effectiveFrom = instant(command, "effectiveFrom");
        var effectiveTo = optionalInstant(command, "effectiveTo");
        requireRange(effectiveFrom, effectiveTo);
        requireRegistryCategory(
                context, specialtyEntryId, specialtyVersionId, "specialty", effectiveFrom, effectiveTo);
        var supersedesId = optionalUuid(command, "supersedesId");
        if (supersedesId!=null) {
            var predecessorChanged=jdbc.update(
                    """
                    UPDATE practitioner_specialties
                    SET effective_to=?,lock_version=lock_version+1,
                        updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND practitioner_profile_id=?
                      AND designation=? AND status IN ('scheduled','active')
                      AND effective_from<? AND (effective_to IS NULL OR effective_to>?)
                    """,
                    Timestamp.from(effectiveFrom),context.actorId(),context.organizationId(),
                    supersedesId,practitionerId,required(command,"designation"),
                    Timestamp.from(effectiveFrom),Timestamp.from(effectiveFrom));
            requireChanged(predecessorChanged,"The specialty predecessor is unavailable or incompatible.");
        }
        jdbc.update(
                """
                INSERT INTO practitioner_specialties(
                    id,organization_id,practitioner_profile_id,specialty_entry_id,
                    specialty_version_id,designation,effective_from,effective_to,supersedes_id,
                    created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                specialtyId,
                context.organizationId(),
                practitionerId,
                specialtyEntryId,
                specialtyVersionId,
                required(command, "designation"),
                Timestamp.from(effectiveFrom),
                timestamp(effectiveTo),
                supersedesId,
                context.actorId(),
                context.actorId());
        var audit = ordered(
                "practitionerId", practitionerId,
                "specialtyId", specialtyId,
                "specialtyVersionId", specialtyVersionId,
                "designation", required(command, "designation"),
                "effectiveFrom", effectiveFrom,
                "effectiveTo", effectiveTo);
        var outbox = ordered(
                "practitionerId", practitionerId,
                "specialtyId", specialtyId,
                "specialtyVersionId", specialtyVersionId,
                "effectiveFrom", effectiveFrom,
                "effectiveTo", effectiveTo);
        return result(
                specialtyId,
                "practitioner_specialty",
                "practitioner.specialty.scheduled",
                "practitioner.specialty.changed",
                "practitioner_specialty",
                audit,
                outbox,
                201,
                0);
    }

    private MutationResult saveScope(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId = member(command);
        var practitionerId = findPractitionerId(context, memberId, true);
        var scopeId = UuidV7Generator.randomUuid();
        var effectiveFrom = instant(command, "effectiveFrom");
        var effectiveTo = optionalInstant(command, "effectiveTo");
        requireRange(effectiveFrom, effectiveTo);
        var definitionId = optionalUuid(command, "scopeDefinitionId");
        if (definitionId==null) {
            definitionId=createScopeDefinition(context,command,effectiveFrom,effectiveTo);
        } else if (optional(command,"requirements")!=null) {
            throw invalid("Requirements can only be supplied while creating a new scope definition.");
        }
        var definitionDigest = Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT encode(digest(
                    definition.id::text||'|'||definition.definition_code||'|'||definition.name||'|'
                    ||definition.profession_version_id::text||'|'
                    ||coalesce(definition.specialty_version_id::text,'')||'|'
                    ||coalesce(definition.service_id::text,'')||'|'
                    ||definition.jurisdiction_country||'|'
                    ||coalesce((SELECT string_agg(
                        requirement.requirement_order::text||':'||requirement.requirement_type||':'
                        ||coalesce(requirement.registry_version_id::text,'')||':'
                        ||requirement.mandatory::text||':'
                        ||coalesce(requirement.validity_window_days::text,''),
                        '|' ORDER BY requirement.requirement_order)
                      FROM scope_requirements requirement
                      WHERE requirement.organization_id=definition.organization_id
                        AND requirement.scope_definition_id=definition.id
                        AND requirement.status='active'),''),
                    'sha256'),'hex')
                FROM scope_definitions definition
                WHERE definition.organization_id=? AND definition.id=? AND definition.status='active'
                """,
                String.class,
                context.organizationId(),
                definitionId));
        var activities=parseScopeActivities(required(command,"activities"));
        var restrictions=parseScopeRestrictions(optional(command,"restrictions"));
        var supersedesId=optionalUuid(command,"supersedesId");
        if (supersedesId!=null && !exists(
                """
                SELECT EXISTS(SELECT 1 FROM scopes_of_practice
                WHERE organization_id=? AND id=? AND practitioner_profile_id=?
                  AND scope_definition_id=? AND lifecycle_state IN ('approved','suspended')
                  AND effective_from<? AND (effective_to IS NULL OR effective_to>?))
                """,
                context.organizationId(),supersedesId,practitionerId,definitionId,
                Timestamp.from(effectiveFrom),Timestamp.from(effectiveFrom))) {
            throw conflict("The scope predecessor is unavailable, incompatible, or does not cover the successor boundary.");
        }
        if (activities.isEmpty()) throw invalid("At least one controlled scope activity is required.");
        for (var activity:activities) {
            requireRegistryCategory(
                    context,activity.entryId(),activity.versionId(),"scope_activity",
                    effectiveFrom,effectiveTo);
            if (activity.supervisionEntryId()!=null) {
                requireRegistryCategory(
                        context,activity.supervisionEntryId(),activity.supervisionVersionId(),
                        "supervision_mode",effectiveFrom,effectiveTo);
            }
        }
        for (var restriction:restrictions) {
            requireRegistryCategory(
                    context,restriction.entryId(),restriction.versionId(),
                    "scope_restriction",effectiveFrom,effectiveTo);
        }
        var resultDigest=digest(
                definitionDigest+"|"+effectiveFrom+"|"+effectiveTo+"|"+activities+"|"+restrictions
                        +"|"+Objects.toString(supersedesId,""));
        jdbc.update(
                """
                INSERT INTO scopes_of_practice(
                    id,organization_id,practitioner_profile_id,scope_definition_id,
                    definition_version_digest,effective_from,effective_to,result_digest,
                    supersedes_id,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                scopeId,
                context.organizationId(),
                practitionerId,
                definitionId,
                definitionDigest,
                Timestamp.from(effectiveFrom),
                timestamp(effectiveTo),
                resultDigest,
                supersedesId,
                context.actorId(),
                context.actorId());
        for (var activity:activities) {
            jdbc.update(
                    """
                    INSERT INTO scope_activities(
                        id,organization_id,scope_of_practice_id,activity_entry_id,activity_version_id,
                        service_id,facility_id,location_id,supervision_mode_entry_id,
                        supervision_mode_version_id,effective_from,effective_to,created_by,updated_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    UuidV7Generator.randomUuid(),context.organizationId(),scopeId,
                    activity.entryId(),activity.versionId(),activity.serviceId(),activity.facilityId(),
                    activity.locationId(),activity.supervisionEntryId(),activity.supervisionVersionId(),
                    Timestamp.from(effectiveFrom),timestamp(effectiveTo),context.actorId(),context.actorId());
        }
        for (var restriction:restrictions) {
            jdbc.update(
                    """
                    INSERT INTO scope_restrictions(
                        id,organization_id,scope_of_practice_id,restriction_entry_id,
                        restriction_version_id,display_text,imposed_decision_id,
                        effective_from,effective_to,created_by,updated_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    UuidV7Generator.randomUuid(),context.organizationId(),scopeId,
                    restriction.entryId(),restriction.versionId(),restriction.displayText(),scopeId,
                    Timestamp.from(effectiveFrom),timestamp(effectiveTo),context.actorId(),context.actorId());
        }
        var payload = ordered(
                "practitionerId", practitionerId,
                "scopeId", scopeId,
                "definitionVersionId", definitionId,
                "fromState", "none",
                "toState", "draft",
                "resultDigest", resultDigest,
                "revision", 0);
        return result(
                scopeId,
                "scope_of_practice",
                "practitioner.scope.created",
                null,
                null,
                payload,
                Map.of(),
                201,
                0);
    }

    private MutationResult submitScope(
            AuthorizedTenantContext context, MutationCommand command) {
        var scopeId = requireTarget(command);
        var revision = requireRevision(command);
        var scope = requireScope(context, scopeId);
        var submittedRevision = revision + 1;
        if (!scopeRequirementsMet(context,scope,command.now())) {
            throw conflict("The active scope-definition requirements are not currently met.");
        }
        var resultDigest = scopeResultDigest(context,scope,submittedRevision);
        var changed = jdbc.update(
                """
                UPDATE scopes_of_practice
                SET lifecycle_state='submitted',status='submitted',submitted_revision=?,
                    result_digest=?,submitted_by=?,submitted_at=clock_timestamp(),
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                  AND lifecycle_state IN ('draft','changes_requested')
                """,
                submittedRevision,
                resultDigest,
                context.actorId(),
                context.actorId(),
                context.organizationId(),
                scopeId,
                revision);
        requireChanged(changed, "The scope is stale or cannot be submitted.");
        var audit = ordered(
                "practitionerId", scope.practitionerId(),
                "scopeId", scopeId,
                "definitionVersionId", scope.definitionId(),
                "fromState", scope.status(),
                "toState", "submitted",
                "resultDigest", resultDigest,
                "revision", submittedRevision);
        var outbox = ordered(
                "practitionerId", scope.practitionerId(),
                "scopeId", scopeId,
                "definitionVersionId", scope.definitionId(),
                "resultDigest", resultDigest,
                "revision", submittedRevision);
        return result(
                scopeId,
                "scope_of_practice",
                "practitioner.scope.submitted",
                "practitioner.scope.submitted",
                "scope_of_practice",
                audit,
                outbox,
                200,
                submittedRevision);
    }

    private MutationResult decideScope(
            AuthorizedTenantContext context, MutationCommand command) {
        var scopeId = requireTarget(command);
        var revision = requireRevision(command);
        var scope = requireScope(context, scopeId);
        var memberId = requirePractitionerMember(context, scope.practitionerId());
        if (isSubjectActor(context, memberId)) {
            throw conflict("A workforce member cannot decide their own scope of practice.");
        }
        if (exists(
                """
                SELECT EXISTS(SELECT 1 FROM scopes_of_practice
                WHERE organization_id=? AND id=? AND submitted_by=?)
                """,
                context.organizationId(), scopeId, context.actorId())) {
            throw conflict("The scope submitter cannot record its approval decision.");
        }
        var decision = required(command, "decisionCode");
        if (!Set.of("approved","rejected","changes_requested").contains(decision)) {
            throw invalid("The scope decision is not supported.");
        }
        if (decision.equals("approved") && !scopeRequirementsMet(context,scope,command.now())) {
            throw conflict("The active scope-definition requirements are no longer met.");
        }
        if (decision.equals("approved") && scope.effectiveFrom().isAfter(command.now())) {
            throw conflict("A future-dated scope of practice cannot be approved yet.");
        }
        LifecyclePredecessor predecessor = null;
        if (decision.equals("approved") && scope.supersedesId()!=null) {
            predecessor = jdbc.query(
                    """
                    SELECT lifecycle_state,lock_version FROM scopes_of_practice
                    WHERE organization_id=? AND id=? AND practitioner_profile_id=?
                      AND scope_definition_id=? AND lifecycle_state IN ('approved','suspended')
                      AND effective_from<? AND (effective_to IS NULL OR effective_to>?)
                    FOR UPDATE
                    """,
                    resultSet -> resultSet.next()
                            ? new LifecyclePredecessor(
                                    resultSet.getString("lifecycle_state"),
                                    resultSet.getLong("lock_version"))
                            : null,
                    context.organizationId(),
                    scope.supersedesId(),
                    scope.practitionerId(),
                    scope.definitionId(),
                    Timestamp.from(scope.effectiveFrom()),
                    Timestamp.from(scope.effectiveFrom()));
            if (predecessor == null) {
                throw stale("The scope predecessor changed before supersession.");
            }
            var superseded=jdbc.update(
                    """
                    UPDATE scopes_of_practice
                    SET lifecycle_state='superseded',status='superseded',effective_to=?,
                        lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND practitioner_profile_id=?
                      AND lifecycle_state=? AND lock_version=?
                    """,
                    Timestamp.from(scope.effectiveFrom()),context.actorId(),context.organizationId(),
                    scope.supersedesId(),scope.practitionerId(),
                    predecessor.status(),predecessor.revision());
            requireChanged(superseded,"The scope predecessor changed before supersession.");
        }
        var decisionId = UuidV7Generator.randomUuid();
        var changed = jdbc.update(
                """
                UPDATE scopes_of_practice
                SET lifecycle_state=?,status=?,decided_by=?,decision_code=?,decided_at=clock_timestamp(),
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                  AND lifecycle_state IN ('submitted','in_review')
                """,
                decision,
                decision,
                context.actorId(),
                decision,
                context.actorId(),
                context.organizationId(),
                scopeId,
                revision);
        requireChanged(changed, "The scope decision is stale or violates reviewer separation.");
        var audit = ordered(
                "practitionerId", scope.practitionerId(),
                "scopeId", scopeId,
                "decisionId", decisionId,
                "decisionCode", decision,
                "resultDigest", scope.resultDigest(),
                "policyVersion", "m2-scope-v1",
                "revision", revision + 1);
        var outbox = ordered(
                "practitionerId", scope.practitionerId(),
                "scopeId", scopeId,
                "decisionId", decisionId,
                "decisionCode", decision,
                "revision", revision + 1);
        var additionalEvidence = predecessor == null
                ? List.<MutationEvidence>of()
                : List.of(new MutationEvidence(
                        scope.supersedesId(),
                        "scope_of_practice",
                        "practitioner.scope.superseded",
                        "practitioner.scope.superseded",
                        "scope_of_practice",
                        ordered(
                                "practitionerId", scope.practitionerId(),
                                "scopeId", scope.supersedesId(),
                                "fromState", predecessor.status(),
                                "toState", "superseded",
                                "effectiveTime", scope.effectiveFrom(),
                                "revision", predecessor.revision() + 1),
                        ordered(
                                "practitionerId", scope.practitionerId(),
                                "scopeId", scope.supersedesId(),
                                "fromState", predecessor.status(),
                                "toState", "superseded",
                                "effectiveTime", scope.effectiveFrom(),
                                "revision", predecessor.revision() + 1)));
        return result(
                scopeId,
                "scope_of_practice",
                "practitioner.scope." + decision,
                "practitioner.scope.decided",
                "scope_of_practice",
                audit,
                outbox,
                200,
                revision + 1,
                additionalEvidence);
    }

    private MutationResult saveAssignment(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId = member(command);
        requireMember(context, memberId);
        var assignmentId = UuidV7Generator.randomUuid();
        var facilityId = uuid(command, "facilityId");
        var effectiveFrom = instant(command, "effectiveFrom");
        var effectiveTo = optionalInstant(command, "effectiveTo");
        requireRange(effectiveFrom, effectiveTo);
        var assignmentTypeEntryId = uuid(command, "assignmentTypeEntryId");
        var assignmentTypeVersionId = uuid(command, "assignmentTypeVersionId");
        requireRegistryCategory(
                context, assignmentTypeEntryId, assignmentTypeVersionId,
                "assignment_type", effectiveFrom, effectiveTo);
        var positionEntryId = optionalUuid(command, "positionEntryId");
        var positionVersionId = optionalUuid(command, "positionVersionId");
        if ((positionEntryId == null) != (positionVersionId == null)) {
            throw invalid("positionEntryId and positionVersionId must be supplied together.");
        }
        if (positionEntryId != null) {
            requireRegistryCategory(
                    context, positionEntryId, positionVersionId, "position", effectiveFrom, effectiveTo);
        }
        requireHierarchyContext(
                context, facilityId, optionalUuid(command, "organizationUnitId"),
                optionalUuid(command, "locationId"), effectiveFrom, effectiveTo);
        jdbc.update(
                """
                INSERT INTO workforce_assignments(
                    id,organization_id,workforce_member_id,facility_id,organization_unit_id,
                    location_id,assignment_type_entry_id,assignment_type_version_id,
                    position_entry_id,position_version_id,primary_assignment,effective_from,
                    effective_to,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                assignmentId,
                context.organizationId(),
                memberId,
                facilityId,
                optionalUuid(command, "organizationUnitId"),
                optionalUuid(command, "locationId"),
                assignmentTypeEntryId,
                assignmentTypeVersionId,
                positionEntryId,
                positionVersionId,
                bool(command, "primaryAssignment"),
                Timestamp.from(effectiveFrom),
                timestamp(effectiveTo),
                context.actorId(),
                context.actorId());
        var payload = ordered(
                "memberId", memberId,
                "assignmentId", assignmentId,
                "contextType", "facility",
                "contextId", facilityId,
                "fromState", "none",
                "toState", "draft",
                "effectiveFrom", effectiveFrom,
                "effectiveTo", effectiveTo,
                "revision", 0);
        return result(
                assignmentId,
                "workforce_assignment",
                "workforce.assignment.created",
                "workforce.assignment.created",
                "workforce_assignment",
                payload,
                payload,
                201,
                0);
    }

    private MutationResult createServiceAssignment(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId = member(command);
        var practitionerId = findPractitionerId(context, memberId, true);
        var serviceId = uuid(command, "serviceId");
        var facilityId = uuid(command, "facilityId");
        var locationId = optionalUuid(command, "locationId");
        var scopeId=uuid(command,"scopeOfPracticeId");
        var activityEntryId=uuid(command,"activityEntryId");
        var supervisorId=optionalUuid(command,"supervisorPractitionerId");
        var effectiveFrom = instant(command, "effectiveFrom");
        var effectiveTo = optionalInstant(command, "effectiveTo");
        requireRange(effectiveFrom, effectiveTo);
        var assignmentId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO practitioner_service_assignments(
                    id,organization_id,practitioner_profile_id,service_id,facility_id,location_id,
                    scope_of_practice_id,supervisor_practitioner_id,effective_from,effective_to,
                    created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                assignmentId,
                context.organizationId(),
                practitionerId,
                serviceId,
                facilityId,
                locationId,
                scopeId,
                supervisorId,
                Timestamp.from(effectiveFrom),
                timestamp(effectiveTo),
                context.actorId(),
                context.actorId());
        var evidence = evaluate(
                context,
                new WorkforceEligibilityStore.Command(
                        practitionerId,serviceId,facilityId,locationId,activityEntryId,supervisorId,
                        effectiveFrom,effectiveTo,command.now()));
        if (!"eligible".equals(evidence.outcome())) {
            throw conflict("The proposed service assignment is not currently eligible.");
        }
        var bound = jdbc.update(
                """
                UPDATE practitioner_service_assignments
                SET eligibility_evidence_id=?,eligibility_digest=?,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lifecycle_state='draft' AND lock_version=0
                """,
                evidence.resultId(),evidence.resultDigest(),context.actorId(),
                context.organizationId(),assignmentId);
        requireChanged(bound,"The service assignment changed before eligibility evidence was bound.");
        var contextId = locationId == null ? facilityId : locationId;
        var payload = ordered(
                "practitionerId", practitionerId,
                "serviceAssignmentId", assignmentId,
                "serviceId", serviceId,
                "contextId", contextId,
                "eligibilityEvidenceId", evidence.resultId(),
                "fromState", "none",
                "toState", "draft",
                "revision", 0);
        return result(
                assignmentId,
                "practitioner_service_assignment",
                "practitioner.service_assignment.created",
                "practitioner.service_assignment.created",
                "practitioner_service_assignment",
                payload,
                payload,
                201,
                0);
    }

    private MutationResult linkExistingAccount(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId = requireTarget(command);
        var expectedRevision = requireRevision(command);
        var member = requireMember(context, memberId);
        if (member.revision() != expectedRevision || member.status().equals("offboarded")) {
            throw conflict("The workforce member changed or cannot be linked to an account.");
        }
        var membershipId = uuid(command, "membershipId");
        var accountEmail = jdbc.query(
                """
                SELECT account.email
                FROM organization_memberships membership
                JOIN users account ON account.id=membership.user_id
                JOIN workforce_members member
                      ON member.organization_id=membership.organization_id
                     AND member.id=?
                JOIN organization_person_links person_link
                      ON person_link.organization_id=member.organization_id
                     AND person_link.id=member.organization_person_link_id
                     AND person_link.relationship_status='active'
                     AND person_link.effective_from<=clock_timestamp()
                     AND (person_link.effective_to IS NULL
                          OR person_link.effective_to>clock_timestamp())
                WHERE membership.organization_id=? AND membership.id=?
                      AND membership.status='active' AND account.status='active'
                      AND membership.effective_from<=clock_timestamp()
                      AND (membership.effective_to IS NULL
                           OR membership.effective_to>clock_timestamp())
                      AND NOT EXISTS(
                          SELECT 1 FROM access_assignment_scopes existing_scope
                          WHERE existing_scope.organization_id=membership.organization_id
                            AND existing_scope.access_assignment_id=membership.id
                            AND existing_scope.workforce_member_id<>member.id
                            AND existing_scope.status='active'
                            AND existing_scope.effective_from<=clock_timestamp()
                            AND (existing_scope.effective_to IS NULL
                                 OR existing_scope.effective_to>clock_timestamp()))
                      AND NOT EXISTS(
                          SELECT 1
                          FROM access_assignment_scopes existing_scope
                          JOIN organization_memberships existing_membership
                            ON existing_membership.organization_id=existing_scope.organization_id
                           AND existing_membership.id=existing_scope.access_assignment_id
                          WHERE existing_scope.organization_id=membership.organization_id
                            AND existing_scope.workforce_member_id=member.id
                            AND existing_membership.user_id<>membership.user_id
                            AND existing_scope.status='active'
                            AND existing_scope.effective_from<=clock_timestamp()
                            AND (existing_scope.effective_to IS NULL
                                 OR existing_scope.effective_to>clock_timestamp()))
                """,
                resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                memberId,
                context.organizationId(),
                membershipId);
        var normalizedAccountEmail = accountEmail == null
                ? null
                : Normalizer.normalize(accountEmail.strip(), Normalizer.Form.NFC)
                        .toLowerCase(Locale.ROOT);
        var emailDigest = normalizedAccountEmail == null
                ? null
                : sensitiveValues.digest(
                        context.organizationId(), "person_contact:email", normalizedAccountEmail);
        var membershipMatches = emailDigest != null && exists(
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM workforce_members member
                    JOIN organization_person_links person_link
                      ON person_link.organization_id=member.organization_id
                     AND person_link.id=member.organization_person_link_id
                    JOIN person_contacts contact ON contact.person_id=person_link.person_id
                    WHERE member.organization_id=? AND member.id=?
                      AND contact.channel='email' AND contact.contact_use='work'
                      AND contact.status='active'
                      AND contact.effective_from<=clock_timestamp()
                      AND (contact.effective_to IS NULL
                           OR contact.effective_to>clock_timestamp())
                      AND contact.normalized_digest=?)
                """,
                context.organizationId(), memberId, emailDigest);
        if (!membershipMatches) {
            throw conflict(
                    "The active organization membership does not match the member's current work email or is already linked elsewhere.");
        }
        var effectiveFrom = instant(command, "effectiveFrom");
        var effectiveTo = optionalInstant(command, "effectiveTo");
        requireRange(effectiveFrom, effectiveTo);
        var requestId = UuidV7Generator.randomUuid();
        var scopeId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO access_assignment_scopes(
                    id,organization_id,access_assignment_id,workforce_member_id,facility_id,
                    organization_unit_id,location_id,grant_request_id,approval_reference_id,
                    effective_from,effective_to,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'active',?,?)
                """,
                scopeId,
                context.organizationId(),
                membershipId,
                memberId,
                optionalUuid(command, "facilityId"),
                optionalUuid(command, "organizationUnitId"),
                optionalUuid(command, "locationId"),
                requestId,
                membershipId,
                Timestamp.from(effectiveFrom),
                timestamp(effectiveTo),
                context.actorId(),
                context.actorId());
        var changed = jdbc.update(
                """
                UPDATE workforce_members
                SET account_access_intent='existing_user',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND lifecycle_state<>'offboarded'
                """,
                context.actorId(),
                context.organizationId(),
                memberId,
                expectedRevision);
        requireChanged(changed, "The workforce member changed before the account link committed.");
        var payload = ordered(
                "memberId", memberId,
                "requestId", requestId,
                "linkMode", "existing_user",
                "state", "active",
                "membershipId", membershipId);
        return result(
                memberId,
                "workforce_member",
                "workforce.account_link.completed",
                null,
                null,
                payload,
                Map.of(),
                201,
                expectedRevision + 1);
    }

    private MutationResult saveAvailability(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId = member(command);
        requireMember(context, memberId);
        var profileId = UuidV7Generator.randomUuid();
        var version = Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT COALESCE(max(profile_version),0)+1 FROM availability_profiles
                WHERE organization_id=? AND workforce_member_id=?
                """,
                Integer.class,
                context.organizationId(),
                memberId));
        var effectiveFrom = instant(command, "effectiveFrom");
        var notRequired = bool(command, "notRequired");
        var intervals = parseIntervals(optional(command, "weeklyIntervals"));
        var timezone = timezone(required(command,"timezone"));
        var exceptions = parseExceptions(optional(command,"exceptions"),timezone);
        if (!notRequired && intervals.isEmpty()) {
            throw invalid("At least one weekly interval is required unless availability is not required.");
        }
        if (notRequired && (!intervals.isEmpty() || !exceptions.isEmpty())) {
            throw invalid("A not-required availability profile cannot contain intervals or exceptions.");
        }
        if (notRequired && !availabilityMayBeOmitted(context,memberId,effectiveFrom)) {
            throw conflict(
                    "Availability may be omitted only for an approved not-required employment category.");
        }
        jdbc.update(
                """
                UPDATE availability_profiles
                SET effective_to=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND workforce_member_id=?
                  AND lifecycle_state='active' AND effective_from<?
                  AND (effective_to IS NULL OR effective_to>?)
                """,
                Timestamp.from(effectiveFrom),
                context.actorId(),
                context.organizationId(),
                memberId,
                Timestamp.from(effectiveFrom),
                Timestamp.from(effectiveFrom));
        jdbc.update(
                """
                UPDATE availability_profiles
                SET lifecycle_state='cancelled',status='cancelled',
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND workforce_member_id=?
                  AND lifecycle_state='scheduled' AND effective_from>=?
                """,
                context.actorId(), context.organizationId(), memberId, Timestamp.from(effectiveFrom));
        jdbc.update(
                """
                INSERT INTO availability_profiles(
                    id,organization_id,workforce_member_id,timezone,profile_version,batch_revision,
                    not_required,effective_from,lifecycle_state,status,created_by,updated_by)
                VALUES (?,?,?,?,?,0,?,?,'scheduled','scheduled',?,?)
                """,
                profileId,
                context.organizationId(),
                memberId,
                timezone.getId(),
                version,
                notRequired,
                Timestamp.from(effectiveFrom),
                context.actorId(),
                context.actorId());
        for (var interval : intervals) {
            jdbc.update(
                    """
                    INSERT INTO availability_periods(
                        id,organization_id,availability_profile_id,iso_weekday,local_start_minute,
                        local_end_minute,ends_next_day,created_by,updated_by)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,
                    UuidV7Generator.randomUuid(),
                    context.organizationId(),
                    profileId,
                    interval.weekday(),
                    interval.startMinute(),
                    interval.endMinute(),
                    interval.nextDay(),
                    context.actorId(),
                    context.actorId());
        }
        for (var exception : exceptions) {
            jdbc.update(
                    """
                    INSERT INTO availability_exceptions(
                        id,organization_id,availability_profile_id,local_start_date,local_end_date,
                        exception_type,replacement_intervals,reason_code,timezone_snapshot,
                        created_by,updated_by)
                    VALUES (?,?,?,?,?,?,?::jsonb,?,?,?,?)
                    """,
                    UuidV7Generator.randomUuid(),
                    context.organizationId(),
                    profileId,
                    exception.startDate(),
                    exception.endDate(),
                    exception.type(),
                    exception.replacementJson(),
                    exception.reasonCode(),
                    timezone.getId(),
                    context.actorId(),
                    context.actorId());
        }
        var payload = ordered(
                "memberId", memberId,
                "profileId", profileId,
                "timezone", timezone.getId(),
                "effectiveFrom", effectiveFrom,
                "intervalCount", intervals.size(),
                "exceptionCount", exceptions.size(),
                "revision", 0);
        return result(
                profileId,
                "availability_profile",
                "workforce.availability.scheduled",
                "workforce.availability.scheduled",
                "availability_profile",
                payload,
                payload,
                201,
                0);
    }

    private MutationResult activateEngagement(
            AuthorizedTenantContext context, MutationCommand command) {
        var id = requireTarget(command);
        var revision = requireRevision(command);
        var row = jdbc.queryForMap(
                """
                SELECT workforce_member_id,employment_category_key,manager_membership_id,
                       effective_from,effective_to,status
                FROM employment_engagements WHERE organization_id=? AND id=?
                """,
                context.organizationId(), id);
        requireMember(context, (UUID) row.get("workforce_member_id"));
        var from = (String) row.get("status");
        var effectiveFrom = instantValue(row.get("effective_from"));
        var effectiveTo = instantValue(row.get("effective_to"));
        requireRegistryEntryKey(
                context, (String) row.get("employment_category_key"), "employment_category",
                effectiveFrom, effectiveTo);
        if (row.get("manager_membership_id") != null && !exists(
                """
                SELECT EXISTS(SELECT 1 FROM organization_memberships
                WHERE organization_id=? AND id=? AND status='active'
                  AND effective_from<=?
                  AND ((?::timestamptz IS NULL AND effective_to IS NULL)
                    OR (?::timestamptz IS NOT NULL
                      AND (effective_to IS NULL OR effective_to>=?))))
                """,
                context.organizationId(), row.get("manager_membership_id"),
                Timestamp.from(effectiveFrom), timestamp(effectiveTo), timestamp(effectiveTo),
                timestamp(effectiveTo))) {
            throw conflict("The engagement manager does not cover the engagement range.");
        }
        var changed = jdbc.update(
                """
                UPDATE employment_engagements SET status='active',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND status IN ('draft','scheduled')
                  AND effective_from<=clock_timestamp()
                  AND (effective_to IS NULL OR effective_to>clock_timestamp())
                """,
                context.actorId(), context.organizationId(), id, revision);
        requireChanged(changed, "The engagement is stale or not currently effective.");
        var payload = ordered(
                "memberId", row.get("workforce_member_id"),
                "engagementId", id,
                "fromState", from,
                "toState", "active",
                "effectiveFrom", instantValue(row.get("effective_from")),
                "effectiveTo", instantValue(row.get("effective_to")),
                "lockVersion", revision + 1);
        return result(id, "employment_engagement", "workforce.engagement.activated",
                "workforce.engagement.activated", "employment_engagement", payload, payload, 200, revision + 1);
    }

    private MutationResult activatePractitioner(
            AuthorizedTenantContext context, MutationCommand command) {
        var id = requireTarget(command);
        var revision = requireRevision(command);
        var row = jdbc.queryForMap(
                """
                SELECT practitioner.workforce_member_id,practitioner.profession_entry_id,
                       practitioner.profession_version_id,practitioner.effective_from,
                       practitioner.effective_to,practitioner.lifecycle_state,member.pathway
                FROM practitioner_profiles practitioner
                JOIN workforce_members member
                  ON member.organization_id=practitioner.organization_id
                 AND member.id=practitioner.workforce_member_id
                WHERE practitioner.organization_id=? AND practitioner.id=?
                """,
                context.organizationId(), id);
        requireMember(context, (UUID) row.get("workforce_member_id"));
        var from = (String) row.get("lifecycle_state");
        if (!"clinical".equals(row.get("pathway"))) {
            throw conflict("Only the clinical pathway can activate a practitioner profile.");
        }
        requireRegistryCategory(
                context, (UUID) row.get("profession_entry_id"),
                (UUID) row.get("profession_version_id"), "profession",
                instantValue(row.get("effective_from")), instantValue(row.get("effective_to")));
        var changed = jdbc.update(
                """
                UPDATE practitioner_profiles SET lifecycle_state='active',status='active',
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=? AND lifecycle_state='draft'
                  AND effective_from<=clock_timestamp()
                  AND (effective_to IS NULL OR effective_to>clock_timestamp())
                """,
                context.actorId(), context.organizationId(), id, revision);
        requireChanged(changed, "The practitioner profile is stale or not currently effective.");
        var payload = ordered(
                "memberId", row.get("workforce_member_id"),
                "practitionerId", id,
                "professionVersionId", row.get("profession_version_id"),
                "fromState", from,
                "toState", "active",
                "lockVersion", revision + 1);
        return result(id, "practitioner_profile", "practitioner.profile.activated",
                "practitioner.profile.activated", "practitioner_profile", payload, payload, 200, revision + 1);
    }

    private MutationResult transitionQualification(
            AuthorizedTenantContext context,
            MutationCommand command,
            String from,
            String to) {
        var id = requireTarget(command);
        var revision = requireRevision(command);
        var row = jdbc.queryForMap(
                "SELECT workforce_member_id,status FROM qualifications WHERE organization_id=? AND id=?",
                context.organizationId(), id);
        requireMember(context, (UUID) row.get("workforce_member_id"));
        var changed = jdbc.update(
                """
                UPDATE qualifications SET status=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status=? AND lock_version=?
                """,
                to, context.actorId(), context.organizationId(), id, from, revision);
        requireChanged(changed, "The qualification transition is stale or invalid.");
        var audit = ordered(
                "memberId", row.get("workforce_member_id"),
                "qualificationId", id,
                "fromState", from,
                "toState", to,
                "revision", revision + 1);
        var outbox = ordered(
                "memberId", row.get("workforce_member_id"),
                "qualificationId", id,
                "toState", to,
                "revision", revision + 1);
        return result(id, "qualification", "credential.qualification." + to,
                "credential.qualification.changed", "qualification", audit, outbox, 200, revision + 1);
    }

    private MutationResult decideQualification(
            AuthorizedTenantContext context, MutationCommand command) {
        var id = requireTarget(command);
        var revision = requireRevision(command);
        var decision = required(command, "decisionCode");
        if (!Set.of("verified", "rejected", "returned_for_correction").contains(decision)) {
            throw invalid("The qualification decision is not supported.");
        }
        var row = jdbc.queryForMap(
                """
                SELECT workforce_member_id,status,updated_by,supersedes_id FROM qualifications
                WHERE organization_id=? AND id=?
                """,
                context.organizationId(), id);
        requireMember(context, (UUID) row.get("workforce_member_id"));
        if (isSubjectActor(context, (UUID) row.get("workforce_member_id"))) {
            throw conflict("A workforce member cannot decide their own qualification.");
        }
        if (Objects.equals(row.get("updated_by"), context.actorId())) {
            throw conflict("The qualification submitter cannot record its verification decision.");
        }
        var predecessorId = (UUID) row.get("supersedes_id");
        LifecyclePredecessor predecessor = null;
        if (decision.equals("verified") && predecessorId != null) {
            predecessor = jdbc.query(
                    """
                    SELECT status,lock_version FROM qualifications
                    WHERE organization_id=? AND id=? AND workforce_member_id=?
                      AND qualification_entry_id=(
                          SELECT qualification_entry_id FROM qualifications
                          WHERE organization_id=? AND id=?)
                      AND status IN ('verified','returned_for_correction')
                    FOR UPDATE
                    """,
                    resultSet -> resultSet.next()
                            ? new LifecyclePredecessor(
                                    resultSet.getString("status"),
                                    resultSet.getLong("lock_version"))
                            : null,
                    context.organizationId(),
                    predecessorId,
                    row.get("workforce_member_id"),
                    context.organizationId(),
                    id);
            if (predecessor == null) {
                throw stale("The qualification predecessor changed before supersession.");
            }
        }
        var changed = jdbc.update(
                """
                UPDATE qualifications SET status=?,verification_reference_id=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='submitted' AND lock_version=?
                """,
                decision, UuidV7Generator.randomUuid(), context.actorId(),
                context.organizationId(), id, revision);
        requireChanged(changed, "The qualification decision is stale or invalid.");
        if (predecessor != null) {
            var superseded = jdbc.update(
                    """
                    UPDATE qualifications
                    SET status='superseded',lock_version=lock_version+1,
                        updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND workforce_member_id=?
                      AND status=? AND lock_version=?
                    """,
                    context.actorId(),
                    context.organizationId(),
                    predecessorId,
                    row.get("workforce_member_id"),
                    predecessor.status(),
                    predecessor.revision());
            requireChanged(superseded, "The qualification predecessor changed before supersession.");
        }
        var eventSuffix = decision.equals("returned_for_correction") ? "returned" : decision;
        var audit = ordered(
                "memberId", row.get("workforce_member_id"), "qualificationId", id,
                "fromState", "submitted", "toState", decision, "revision", revision + 1);
        var outbox = ordered(
                "memberId", row.get("workforce_member_id"), "qualificationId", id,
                "toState", decision, "revision", revision + 1);
        var additionalEvidence = predecessor == null
                ? List.<MutationEvidence>of()
                : List.of(new MutationEvidence(
                        predecessorId,
                        "qualification",
                        "credential.qualification.superseded",
                        "credential.qualification.changed",
                        "qualification",
                        ordered(
                                "memberId", row.get("workforce_member_id"),
                                "qualificationId", predecessorId,
                                "fromState", predecessor.status(),
                                "toState", "superseded",
                                "revision", predecessor.revision() + 1),
                        ordered(
                                "memberId", row.get("workforce_member_id"),
                                "qualificationId", predecessorId,
                                "toState", "superseded",
                                "revision", predecessor.revision() + 1)));
        return result(id, "qualification", "credential.qualification." + eventSuffix,
                "credential.qualification.changed", "qualification", audit, outbox, 200,
                revision + 1, additionalEvidence);
    }

    private MutationResult transitionRegistration(
            AuthorizedTenantContext context,
            MutationCommand command,
            String from,
            String to) {
        var id = requireTarget(command);
        var revision = requireRevision(command);
        var row = jdbc.queryForMap(
                """
                SELECT registration.practitioner_profile_id,registration.status,
                       registration.valid_from,registration.expires_on,
                       registration.updated_by,registration.supersedes_id,
                       practitioner.workforce_member_id
                FROM professional_registrations registration
                JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=registration.organization_id
                 AND practitioner.id=registration.practitioner_profile_id
                WHERE registration.organization_id=? AND registration.id=?
                """,
                context.organizationId(), id);
        requireMember(context, (UUID) row.get("workforce_member_id"));
        if (to.equals("verified")
                && isSubjectActor(context, (UUID) row.get("workforce_member_id"))) {
            throw conflict("A workforce member cannot verify their own registration.");
        }
        if (to.equals("verified")
                && localDateValue(row.get("valid_from"))
                        .isAfter(command.now().atZone(ZoneOffset.UTC).toLocalDate())) {
            throw conflict("A future-dated professional registration cannot be verified yet.");
        }
        if (to.equals("verified")
                && localDateValue(row.get("expires_on")) != null
                && localDateValue(row.get("expires_on"))
                        .isBefore(command.now().atZone(ZoneOffset.UTC).toLocalDate())) {
            throw conflict("An expired professional registration cannot be verified.");
        }
        if (to.equals("verified")) {
            var registry = jdbc.queryForMap(
                    """
                    SELECT regulator_entry_id,regulator_version_id,registration_type_entry_id,
                           registration_type_version_id
                    FROM professional_registrations WHERE organization_id=? AND id=?
                    """,
                    context.organizationId(), id);
            requireRegistryCategory(
                    context, (UUID) registry.get("regulator_entry_id"),
                    (UUID) registry.get("regulator_version_id"), "regulator", command.now());
            requireRegistryCategory(
                    context, (UUID) registry.get("registration_type_entry_id"),
                    (UUID) registry.get("registration_type_version_id"),
                    "registration_type", command.now());
        }
        if (to.equals("verified") && Objects.equals(row.get("updated_by"), context.actorId())) {
            throw conflict("The registration submitter cannot verify its own registration.");
        }
        var predecessorId = (UUID) row.get("supersedes_id");
        RegistrationPredecessor predecessor = null;
        if (to.equals("verified") && predecessorId != null) {
            var candidate = jdbc.query(
                    """
                    SELECT status,expires_on,lock_version FROM professional_registrations
                    WHERE organization_id=? AND id=? AND practitioner_profile_id=?
                      AND regulator_entry_id=(
                          SELECT regulator_entry_id FROM professional_registrations
                          WHERE organization_id=? AND id=?)
                      AND registration_type_entry_id=(
                          SELECT registration_type_entry_id FROM professional_registrations
                          WHERE organization_id=? AND id=?)
                    FOR UPDATE
                    """,
                    resultSet -> resultSet.next()
                            ? new RegistrationPredecessor(
                                    resultSet.getString("status"),
                                    resultSet.getObject("expires_on", LocalDate.class),
                                    resultSet.getLong("lock_version"))
                            : null,
                    context.organizationId(),
                    predecessorId,
                    row.get("practitioner_profile_id"),
                    context.organizationId(),
                    id,
                    context.organizationId(),
                    id);
            if (candidate == null) {
                throw stale("The registration predecessor changed before supersession.");
            }
            if (Set.of("verified", "suspended").contains(candidate.status())) {
                predecessor = candidate;
            } else if (!candidate.status().equals("expired")) {
                throw stale("The registration predecessor changed before supersession.");
            }
        }
        var decisionReference = to.equals("verified") ? UuidV7Generator.randomUuid() : null;
        var changed = jdbc.update(
                """
                UPDATE professional_registrations SET status=?,
                    decision_reference_id=COALESCE(?,decision_reference_id),
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status=? AND lock_version=?
                """,
                to, decisionReference, context.actorId(), context.organizationId(), id, from, revision);
        requireChanged(changed, "The registration transition is stale or invalid.");
        if (predecessor != null) {
            var superseded = jdbc.update(
                    """
                    UPDATE professional_registrations
                    SET status='superseded',lock_version=lock_version+1,
                        updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND practitioner_profile_id=?
                      AND status=? AND lock_version=?
                    """,
                    context.actorId(),
                    context.organizationId(),
                    predecessorId,
                    row.get("practitioner_profile_id"),
                    predecessor.status(),
                    predecessor.revision());
            requireChanged(superseded, "The registration predecessor changed before supersession.");
        }
        var payload = ordered(
                "practitionerId", row.get("practitioner_profile_id"),
                "registrationId", id,
                "fromState", from,
                "toState", to,
                "expiryDate", row.get("expires_on"),
                "revision", revision + 1);
        var additionalEvidence = predecessor == null
                ? List.<MutationEvidence>of()
                : List.of(new MutationEvidence(
                        predecessorId,
                        "professional_registration",
                        "credential.registration.superseded",
                        "credential.registration.superseded",
                        "professional_registration",
                        ordered(
                                "practitionerId", row.get("practitioner_profile_id"),
                                "registrationId", predecessorId,
                                "fromState", predecessor.status(),
                                "toState", "superseded",
                                "expiryDate", predecessor.expiresOn(),
                                "revision", predecessor.revision() + 1),
                        ordered(
                                "practitionerId", row.get("practitioner_profile_id"),
                                "registrationId", predecessorId,
                                "fromState", predecessor.status(),
                                "toState", "superseded",
                                "expiryDate", predecessor.expiresOn(),
                                "revision", predecessor.revision() + 1)));
        return result(id, "professional_registration", "credential.registration." + to,
                "credential.registration." + to, "professional_registration", payload, payload, 200,
                revision + 1, additionalEvidence);
    }

    private MutationResult transitionRegistrationLifecycle(
            AuthorizedTenantContext context, MutationCommand command, String to) {
        var id = requireTarget(command);
        var revision = requireRevision(command);
        var effectiveTime=currentEffectiveTime(command);
        reasonCode(command);
        uuid(command, "authorityEvidenceId");
        var row = jdbc.queryForMap(
                """
                SELECT registration.practitioner_profile_id,registration.status,
                       registration.expires_on,practitioner.workforce_member_id
                FROM professional_registrations registration
                JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=registration.organization_id
                 AND practitioner.id=registration.practitioner_profile_id
                WHERE registration.organization_id=? AND registration.id=?
                """,
                context.organizationId(),id);
        requireMember(context, (UUID) row.get("workforce_member_id"));
        var from = (String) row.get("status");
        var allowed = to.equals("suspended")
                ? Set.of("verified") : Set.of("verified","suspended");
        if (!allowed.contains(from)) {
            throw conflict("The registration cannot enter " + to + " from its current state.");
        }
        requireCurrentImpact(command,registrationLifecycleImpact(context,command,to));
        var changed = jdbc.update(
                """
                UPDATE professional_registrations
                SET status=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status=? AND lock_version=?
                """,
                to,context.actorId(),context.organizationId(),id,from,revision);
        requireChanged(changed,"The registration lifecycle transition is stale or invalid.");
        reconcileProspectiveServiceAssignments(
                context,(UUID) row.get("practitioner_profile_id"),null,effectiveTime);
        var payload = ordered(
                "practitionerId",row.get("practitioner_profile_id"),
                "registrationId",id,"fromState",from,"toState",to,
                "expiryDate",row.get("expires_on"),"revision",revision+1);
        return result(id,"professional_registration","credential.registration."+to,
                "credential.registration."+to,"professional_registration",
                payload,payload,200,revision+1);
    }

    private MutationResult transitionCredentialLifecycle(
            AuthorizedTenantContext context, MutationCommand command, String to) {
        var id = requireTarget(command);
        var revision = requireRevision(command);
        var effectiveTime = currentEffectiveTime(command);
        reasonCode(command);
        uuid(command, "authorityEvidenceId");
        var credential = requireCredential(context,id);
        var allowed = to.equals("suspended")
                ? Set.of("verified") : Set.of("verified","suspended");
        if (credential.revision()!=revision || !allowed.contains(credential.status())) {
            throw stale("The credential lifecycle transition is stale or invalid.");
        }
        requireCurrentImpact(command,credentialLifecycleImpact(context,command,to));
        var changed=jdbc.update(
                """
                UPDATE practitioner_credentials
                SET status=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status=? AND lock_version=?
                """,
                to,context.actorId(),context.organizationId(),id,credential.status(),revision);
        requireChanged(changed,"The credential lifecycle transition is stale or invalid.");
        reconcileProspectiveServiceAssignments(
                context,credential.practitionerId(),null,effectiveTime);
        var payload=ordered(
                "practitionerId",credential.practitionerId(),"credentialId",id,
                "fromState",credential.status(),"toState",to,
                "effectiveTime",effectiveTime,"revision",revision+1);
        return result(id,"practitioner_credential","credential.record."+to,
                "credential.record."+to,"practitioner_credential",
                payload,payload,200,revision+1);
    }

    private MutationResult activateSpecialty(
            AuthorizedTenantContext context, MutationCommand command) {
        var id = requireTarget(command);
        var revision = requireRevision(command);
        var row = jdbc.queryForMap(
                """
                SELECT specialty.practitioner_profile_id,specialty.specialty_entry_id,
                       specialty.specialty_version_id,specialty.designation,
                       specialty.effective_from,specialty.effective_to,specialty.supersedes_id
                FROM practitioner_specialties specialty
                JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=specialty.organization_id
                 AND practitioner.id=specialty.practitioner_profile_id
                 AND practitioner.lifecycle_state='active'
                WHERE specialty.organization_id=? AND specialty.id=?
                """,
                context.organizationId(), id);
        requirePractitionerMember(context, (UUID) row.get("practitioner_profile_id"));
        requireRegistryCategory(
                context, (UUID) row.get("specialty_entry_id"),
                (UUID) row.get("specialty_version_id"), "specialty",
                instantValue(row.get("effective_from")), instantValue(row.get("effective_to")));
        SpecialtyPredecessor predecessor = null;
        if (row.get("supersedes_id")!=null) {
            predecessor = jdbc.query(
                    """
                    SELECT specialty_version_id,designation,effective_from,effective_to,
                           status,lock_version
                    FROM practitioner_specialties
                    WHERE organization_id=? AND id=? AND practitioner_profile_id=?
                      AND status IN ('scheduled','active') AND effective_to=?
                    FOR UPDATE
                    """,
                    resultSet -> resultSet.next()
                            ? new SpecialtyPredecessor(
                                    resultSet.getObject("specialty_version_id", UUID.class),
                                    resultSet.getString("designation"),
                                    resultSet.getTimestamp("effective_from").toInstant(),
                                    resultSet.getTimestamp("effective_to").toInstant(),
                                    resultSet.getString("status"),
                                    resultSet.getLong("lock_version"))
                            : null,
                    context.organizationId(),
                    row.get("supersedes_id"),
                    row.get("practitioner_profile_id"),
                    row.get("effective_from"));
            if (predecessor == null) {
                throw stale("The specialty predecessor changed before activation.");
            }
            var superseded=jdbc.update(
                    """
                    UPDATE practitioner_specialties
                    SET status='superseded',lock_version=lock_version+1,
                        updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND practitioner_profile_id=?
                      AND status=? AND lock_version=?
                    """,
                    context.actorId(),context.organizationId(),row.get("supersedes_id"),
                    row.get("practitioner_profile_id"),predecessor.status(),predecessor.revision());
            requireChanged(superseded,"The specialty predecessor changed before activation.");
        }
        var changed = jdbc.update(
                """
                UPDATE practitioner_specialties SET status='active',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='scheduled' AND lock_version=?
                  AND effective_from<=clock_timestamp()
                  AND (effective_to IS NULL OR effective_to>clock_timestamp())
                """,
                context.actorId(), context.organizationId(), id, revision);
        requireChanged(changed, "The specialty is stale or not effective.");
        var audit = ordered(
                "practitionerId", row.get("practitioner_profile_id"), "specialtyId", id,
                "specialtyVersionId", row.get("specialty_version_id"),
                "designation", row.get("designation"), "effectiveFrom", instantValue(row.get("effective_from")),
                "effectiveTo", instantValue(row.get("effective_to")));
        var outbox = ordered(
                "practitionerId", row.get("practitioner_profile_id"), "specialtyId", id,
                "specialtyVersionId", row.get("specialty_version_id"),
                "effectiveFrom", instantValue(row.get("effective_from")), "effectiveTo", instantValue(row.get("effective_to")));
        var additionalEvidence = predecessor == null
                ? List.<MutationEvidence>of()
                : List.of(new MutationEvidence(
                        (UUID) row.get("supersedes_id"),
                        "practitioner_specialty",
                        "practitioner.specialty.superseded",
                        "practitioner.specialty.changed",
                        "practitioner_specialty",
                        ordered(
                                "practitionerId", row.get("practitioner_profile_id"),
                                "specialtyId", row.get("supersedes_id"),
                                "specialtyVersionId", predecessor.versionId(),
                                "designation", predecessor.designation(),
                                "effectiveFrom", predecessor.effectiveFrom(),
                                "effectiveTo", predecessor.effectiveTo()),
                        ordered(
                                "practitionerId", row.get("practitioner_profile_id"),
                                "specialtyId", row.get("supersedes_id"),
                                "specialtyVersionId", predecessor.versionId(),
                                "effectiveFrom", predecessor.effectiveFrom(),
                                "effectiveTo", predecessor.effectiveTo())));
        return result(id, "practitioner_specialty", "practitioner.specialty.activated",
                "practitioner.specialty.changed", "practitioner_specialty", audit, outbox, 200,
                revision + 1, additionalEvidence);
    }

    private MutationResult endSpecialty(
            AuthorizedTenantContext context, MutationCommand command) {
        var id=requireTarget(command);
        var revision=requireRevision(command);
        var effectiveTime=currentEffectiveTime(command);
        var row=jdbc.queryForMap(
                """
                SELECT practitioner_profile_id,specialty_version_id,designation,
                       effective_from,effective_to,status
                FROM practitioner_specialties WHERE organization_id=? AND id=?
                """,
                context.organizationId(),id);
        requirePractitionerMember(context, (UUID) row.get("practitioner_profile_id"));
        var effectiveFrom=instantValue(row.get("effective_from"));
        var effectiveTo=instantValue(row.get("effective_to"));
        if (!Set.of("scheduled","active").contains(row.get("status"))
                || !effectiveTime.isAfter(effectiveFrom)
                || (effectiveTo!=null && effectiveTime.isAfter(effectiveTo))) {
            throw conflict("The specialty cannot be ended at the requested time.");
        }
        var changed=jdbc.update(
                """
                UPDATE practitioner_specialties
                SET status='ended',effective_to=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status IN ('scheduled','active')
                  AND lock_version=?
                """,
                Timestamp.from(effectiveTime),context.actorId(),context.organizationId(),id,revision);
        requireChanged(changed,"The specialty lifecycle transition is stale or invalid.");
        var audit=ordered(
                "practitionerId",row.get("practitioner_profile_id"),"specialtyId",id,
                "specialtyVersionId",row.get("specialty_version_id"),
                "designation",row.get("designation"),"effectiveFrom",effectiveFrom,
                "effectiveTo",effectiveTime);
        var outbox=ordered(
                "practitionerId",row.get("practitioner_profile_id"),"specialtyId",id,
                "specialtyVersionId",row.get("specialty_version_id"),
                "effectiveFrom",effectiveFrom,"effectiveTo",effectiveTime);
        return result(id,"practitioner_specialty","practitioner.specialty.ended",
                "practitioner.specialty.changed","practitioner_specialty",
                audit,outbox,200,revision+1);
    }

    private MutationResult transitionScopeLifecycle(
            AuthorizedTenantContext context, MutationCommand command, String to) {
        var id=requireTarget(command);
        var revision=requireRevision(command);
        var effectiveTime=currentEffectiveTime(command);
        var reasonCode=reasonCode(command);
        var scope=requireScope(context,id);
        var allowed=to.equals("suspended")?Set.of("approved"):Set.of("approved","suspended");
        if (scope.revision()!=revision || !allowed.contains(scope.status())) {
            throw stale("The scope lifecycle transition is stale or invalid.");
        }
        if (!effectiveTime.isAfter(scope.effectiveFrom())
                || (scope.effectiveTo()!=null && effectiveTime.isAfter(scope.effectiveTo()))) {
            throw invalid("The scope lifecycle effective time must fall inside its effective range.");
        }
        requireCurrentImpact(command,scopeLifecycleImpact(context,command,to));
        var changed=jdbc.update(
                """
                UPDATE scopes_of_practice
                SET lifecycle_state=?,status=?,
                    suspension_reason_code=CASE WHEN ?='suspended' THEN ? ELSE suspension_reason_code END,
                    end_reason_code=CASE WHEN ?='ended' THEN ? ELSE end_reason_code END,
                    effective_to=CASE WHEN ?='ended' THEN ? ELSE effective_to END,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lifecycle_state=? AND lock_version=?
                """,
                to,to,to,reasonCode,to,reasonCode,to,Timestamp.from(effectiveTime),
                context.actorId(),context.organizationId(),id,scope.status(),revision);
        requireChanged(changed,"The scope lifecycle transition is stale or invalid.");
        reconcileProspectiveServiceAssignments(
                context,scope.practitionerId(),id,effectiveTime);
        var payload=ordered(
                "practitionerId",scope.practitionerId(),"scopeId",id,
                "fromState",scope.status(),"toState",to,
                "effectiveTime",effectiveTime,"revision",revision+1);
        return result(id,"scope_of_practice","practitioner.scope."+to,
                "practitioner.scope."+to,"scope_of_practice",payload,payload,200,revision+1);
    }

    private MutationResult activateAssignment(
            AuthorizedTenantContext context, MutationCommand command) {
        var id = requireTarget(command);
        var revision = requireRevision(command);
        var row = jdbc.queryForMap(
                """
                SELECT workforce_member_id,facility_id,organization_unit_id,location_id,
                       assignment_type_entry_id,assignment_type_version_id,
                       position_entry_id,position_version_id,effective_from,effective_to,lifecycle_state
                FROM workforce_assignments WHERE organization_id=? AND id=?
                """,
                context.organizationId(), id);
        requireMember(context, (UUID) row.get("workforce_member_id"));
        var from = (String) row.get("lifecycle_state");
        var effectiveFrom = instantValue(row.get("effective_from"));
        var effectiveTo = instantValue(row.get("effective_to"));
        requireRegistryCategory(
                context, (UUID) row.get("assignment_type_entry_id"),
                (UUID) row.get("assignment_type_version_id"), "assignment_type",
                effectiveFrom, effectiveTo);
        if (row.get("position_entry_id") != null) {
            requireRegistryCategory(
                    context, (UUID) row.get("position_entry_id"),
                    (UUID) row.get("position_version_id"), "position", effectiveFrom, effectiveTo);
        }
        requireHierarchyContext(
                context, (UUID) row.get("facility_id"), (UUID) row.get("organization_unit_id"),
                (UUID) row.get("location_id"), effectiveFrom, effectiveTo);
        if (!engagementCovers(context, (UUID) row.get("workforce_member_id"), effectiveFrom, effectiveTo)) {
            throw conflict("An active or scheduled engagement must cover the assignment range.");
        }
        var changed = jdbc.update(
                """
                UPDATE workforce_assignments SET lifecycle_state='active',status='active',
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lifecycle_state IN ('draft','scheduled')
                  AND lock_version=? AND effective_from<=clock_timestamp()
                  AND (effective_to IS NULL OR effective_to>clock_timestamp())
                """,
                context.actorId(), context.organizationId(), id, revision);
        requireChanged(changed, "The assignment is stale or not effective.");
        var payload = ordered(
                "memberId", row.get("workforce_member_id"), "assignmentId", id,
                "contextType", "facility", "contextId", row.get("facility_id"),
                "fromState", from, "toState", "active",
                "effectiveFrom", instantValue(row.get("effective_from")),
                "effectiveTo", instantValue(row.get("effective_to")), "revision", revision + 1);
        return result(id, "workforce_assignment", "workforce.assignment.activated",
                "workforce.assignment.activated", "workforce_assignment", payload, payload, 200, revision + 1);
    }

    private MutationResult activateServiceAssignment(
            AuthorizedTenantContext context, MutationCommand command) {
        var id = requireTarget(command);
        var revision = requireRevision(command);
        var sourceSql="""
                SELECT practitioner_profile_id,service_id,facility_id,location_id,
                       COALESCE(location_id,facility_id) AS context_id,scope_of_practice_id,
                       supervisor_practitioner_id,effective_from,effective_to,
                       eligibility_evidence_id,eligibility_digest,lifecycle_state,
                       (SELECT activity_entry_id FROM practitioner_eligibility_evidence evidence
                        WHERE evidence.organization_id=practitioner_service_assignments.organization_id
                          AND evidence.id=practitioner_service_assignments.eligibility_evidence_id)
                            AS activity_entry_id
                FROM practitioner_service_assignments WHERE organization_id=? AND id=?
                """;
        var row=jdbc.queryForMap(sourceSql,context.organizationId(),id);
        requirePractitionerMember(context, (UUID) row.get("practitioner_profile_id"));
        lockPractitionerForEligibility(context,(UUID) row.get("practitioner_profile_id"));
        row=jdbc.queryForMap(sourceSql,context.organizationId(),id);
        var from=(String) row.get("lifecycle_state");
        UUID eligibilityEvidenceId=(UUID) row.get("eligibility_evidence_id");
        String eligibilityDigest=(String) row.get("eligibility_digest");
        Instant reactivationTime=null;
        if (from.equals("suspended")) {
            reactivationTime=currentEffectiveTime(command);
            reasonCode(command);
            var evaluatedFrom=reactivationTime.isAfter(instantValue(row.get("effective_from")))
                    ? reactivationTime : instantValue(row.get("effective_from"));
            if (row.get("activity_entry_id")==null
                    || (instantValue(row.get("effective_to"))!=null
                        && !instantValue(row.get("effective_to")).isAfter(evaluatedFrom))) {
                throw conflict("The suspended service assignment cannot be re-evaluated in its current range.");
            }
            var evidence=evaluate(
                    context,
                    new WorkforceEligibilityStore.Command(
                            (UUID) row.get("practitioner_profile_id"),(UUID) row.get("service_id"),
                            (UUID) row.get("facility_id"),(UUID) row.get("location_id"),
                            (UUID) row.get("activity_entry_id"),
                            (UUID) row.get("supervisor_practitioner_id"),evaluatedFrom,
                            instantValue(row.get("effective_to")),command.now()));
            if (!evidence.outcome().equals("eligible")) {
                throw conflict("The suspended service assignment is no longer eligible.");
            }
            eligibilityEvidenceId=evidence.resultId();
            eligibilityDigest=evidence.resultDigest();
        }
        var changed = jdbc.update(
                """
                UPDATE practitioner_service_assignments assignment
                SET lifecycle_state='active',status='active',eligibility_evidence_id=?,eligibility_digest=?,
                    lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE assignment.organization_id=? AND assignment.id=?
                  AND assignment.lifecycle_state IN ('draft','scheduled','suspended') AND assignment.lock_version=?
                  AND assignment.effective_from<=clock_timestamp()
                  AND (assignment.effective_to IS NULL OR assignment.effective_to>clock_timestamp())
                  AND EXISTS(SELECT 1 FROM practitioner_eligibility_evidence evidence
                      WHERE evidence.organization_id=assignment.organization_id
                        AND evidence.id=?
                        AND evidence.result_digest=?
                        AND evidence.practitioner_profile_id=assignment.practitioner_profile_id
                        AND evidence.service_id=assignment.service_id
                        AND evidence.facility_id=assignment.facility_id
                        AND evidence.location_id IS NOT DISTINCT FROM assignment.location_id
                        AND evidence.scope_evidence->>'scopeId'=assignment.scope_of_practice_id::text
                        AND evidence.assignment_evidence->>'serviceAssignmentId'=assignment.id::text
                        AND coalesce(evidence.supervision_evidence->>'supervisorId','')=
                            coalesce(assignment.supervisor_practitioner_id::text,'')
                        AND ((assignment.lifecycle_state='suspended'
                              AND evidence.evaluated_from<=clock_timestamp())
                          OR (assignment.lifecycle_state<>'suspended'
                              AND evidence.evaluated_from<=assignment.effective_from))
                        AND (evidence.evaluated_to IS NULL
                          OR evidence.evaluated_to>=COALESCE(assignment.effective_to,assignment.effective_from))
                        AND evidence.outcome='eligible' AND evidence.expires_at>clock_timestamp())
                """,
                eligibilityEvidenceId,eligibilityDigest,context.actorId(),
                context.organizationId(),id,revision,eligibilityEvidenceId,eligibilityDigest);
        requireChanged(changed, "The service assignment or its eligibility evidence is stale.");
        var payload = ordered(
                "practitionerId", row.get("practitioner_profile_id"), "serviceAssignmentId", id,
                "serviceId", row.get("service_id"), "contextId", row.get("context_id"),
                "eligibilityEvidenceId", eligibilityEvidenceId,
                "fromState", from, "toState", "active", "revision", revision + 1);
        return result(id, "practitioner_service_assignment", "practitioner.service_assignment.activated",
                "practitioner.service_assignment.activated", "practitioner_service_assignment", payload, payload, 200, revision + 1);
    }

    private MutationResult transitionAssignmentLifecycle(
            AuthorizedTenantContext context, MutationCommand command, String to) {
        var id=requireTarget(command);
        var revision=requireRevision(command);
        var effectiveTime=currentEffectiveTime(command);
        reasonCode(command);
        var assignment=requireAssignment(context,id);
        if (assignment.revision()!=revision) {
            throw stale("The assignment lifecycle transition is stale.");
        }
        var allowed=switch (to) {
            case "suspended" -> Set.of("active");
            case "active" -> Set.of("suspended");
            case "ended" -> Set.of("active","suspended");
            case "cancelled" -> Set.of("draft","scheduled");
            default -> Set.<String>of();
        };
        if (!allowed.contains(assignment.status())) {
            throw conflict("The assignment cannot enter " + to + " from its current state.");
        }
        if (!to.equals("cancelled") && (!effectiveTime.isAfter(assignment.effectiveFrom())
                || (assignment.effectiveTo()!=null && effectiveTime.isAfter(assignment.effectiveTo())))) {
            throw invalid("The assignment lifecycle effective time must fall inside its effective range.");
        }
        if (to.equals("active")) {
            requireRegistryCategory(
                    context,assignment.assignmentTypeEntryId(),assignment.assignmentTypeVersionId(),
                    "assignment_type",effectiveTime,assignment.effectiveTo());
            if (assignment.positionEntryId()!=null) {
                requireRegistryCategory(
                        context,assignment.positionEntryId(),assignment.positionVersionId(),
                        "position",effectiveTime,assignment.effectiveTo());
            }
            requireHierarchyContext(
                    context,assignment.facilityId(),assignment.organizationUnitId(),assignment.locationId(),
                    effectiveTime,assignment.effectiveTo());
            if (!engagementCovers(context,assignment.memberId(),effectiveTime,assignment.effectiveTo())) {
                throw conflict("An active engagement must cover the reactivated assignment range.");
            }
        }
        if (!to.equals("cancelled")) {
            requireCurrentImpact(command, assignmentLifecycleImpact(context, command, to));
        }
        var changed=jdbc.update(
                """
                UPDATE workforce_assignments
                SET lifecycle_state=?,status=?,
                    effective_to=CASE WHEN ?='ended' THEN ? ELSE effective_to END,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lifecycle_state=? AND lock_version=?
                """,
                to,to,to,Timestamp.from(effectiveTime),context.actorId(),context.organizationId(),
                id,assignment.status(),revision);
        requireChanged(changed,"The assignment lifecycle transition is stale or invalid.");
        var contextType=assignment.locationId()!=null?"location"
                :assignment.organizationUnitId()!=null?"organization_unit":"facility";
        var contextId=assignment.locationId()!=null?assignment.locationId()
                :assignment.organizationUnitId()!=null?assignment.organizationUnitId():assignment.facilityId();
        var eventSuffix=to.equals("active")?"reactivated":to;
        var payload=ordered(
                "memberId",assignment.memberId(),"assignmentId",id,
                "contextType",contextType,"contextId",contextId,
                "fromState",assignment.status(),"toState",to,
                "effectiveFrom",assignment.effectiveFrom(),
                "effectiveTo",to.equals("ended")?effectiveTime:assignment.effectiveTo(),
                "revision",revision+1);
        return result(id,"workforce_assignment","workforce.assignment."+eventSuffix,
                "workforce.assignment."+eventSuffix,"workforce_assignment",
                payload,payload,200,revision+1);
    }

    private MutationResult transitionServiceAssignment(
            AuthorizedTenantContext context, MutationCommand command, String to) {
        var id=requireTarget(command);
        var revision=requireRevision(command);
        var effectiveTime=currentEffectiveTime(command);
        reasonCode(command);
        var sourceSql="""
                SELECT assignment.practitioner_profile_id,assignment.service_id,
                       assignment.facility_id,assignment.location_id,
                       coalesce(assignment.location_id,assignment.facility_id) AS context_id,
                       assignment.eligibility_evidence_id,assignment.effective_from,
                       assignment.effective_to,assignment.lifecycle_state,
                       practitioner.workforce_member_id
                FROM practitioner_service_assignments assignment
                JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=assignment.organization_id
                 AND practitioner.id=assignment.practitioner_profile_id
                WHERE assignment.organization_id=? AND assignment.id=?
                """;
        var row=jdbc.queryForMap(sourceSql,context.organizationId(),id);
        requireMember(context, (UUID) row.get("workforce_member_id"));
        lockPractitionerForEligibility(context,(UUID) row.get("practitioner_profile_id"));
        row=jdbc.queryForMap(sourceSql,context.organizationId(),id);
        var from=(String) row.get("lifecycle_state");
        var allowed=switch (to) {
            case "suspended" -> Set.of("active");
            case "ended" -> Set.of("active","suspended");
            case "cancelled" -> Set.of("draft","scheduled");
            default -> Set.<String>of();
        };
        if (!allowed.contains(from)) {
            throw conflict("The service assignment cannot enter " + to + " from its current state.");
        }
        var effectiveFrom=instantValue(row.get("effective_from"));
        var effectiveTo=instantValue(row.get("effective_to"));
        if (!to.equals("cancelled") && (!effectiveTime.isAfter(effectiveFrom)
                || (effectiveTo!=null && effectiveTime.isAfter(effectiveTo)))) {
            throw invalid("The service-assignment lifecycle time must fall inside its effective range.");
        }
        var changed=jdbc.update(
                """
                UPDATE practitioner_service_assignments
                SET lifecycle_state=?,status=?,
                    effective_to=CASE WHEN ?='ended' THEN ? ELSE effective_to END,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lifecycle_state=? AND lock_version=?
                """,
                to,to,to,Timestamp.from(effectiveTime),context.actorId(),context.organizationId(),
                id,from,revision);
        requireChanged(changed,"The service-assignment lifecycle transition is stale or invalid.");
        var payload=ordered(
                "practitionerId",row.get("practitioner_profile_id"),
                "serviceAssignmentId",id,"serviceId",row.get("service_id"),
                "contextId",row.get("context_id"),
                "eligibilityEvidenceId",row.get("eligibility_evidence_id"),
                "fromState",from,"toState",to,"revision",revision+1);
        return result(id,"practitioner_service_assignment",
                "practitioner.service_assignment."+to,
                "practitioner.service_assignment."+to,"practitioner_service_assignment",
                payload,payload,200,revision+1);
    }

    private MutationResult activateAvailability(
            AuthorizedTenantContext context, MutationCommand command) {
        var id = requireTarget(command);
        var revision = requireRevision(command);
        var row = jdbc.queryForMap(
                """
                SELECT workforce_member_id,timezone,effective_from,
                       (SELECT count(*) FROM availability_periods period
                        WHERE period.organization_id=profile.organization_id
                          AND period.availability_profile_id=profile.id AND period.status='active') AS interval_count,
                       (SELECT count(*) FROM availability_exceptions exception
                        WHERE exception.organization_id=profile.organization_id
                          AND exception.availability_profile_id=profile.id AND exception.status='active') AS exception_count
                FROM availability_profiles profile WHERE organization_id=? AND id=?
                """,
                context.organizationId(), id);
        requireMember(context, (UUID) row.get("workforce_member_id"));
        var predecessors = jdbc.queryForList(
                """
                SELECT current_profile.id,current_profile.timezone,
                       current_profile.effective_from,current_profile.lock_version,
                       (SELECT count(*) FROM availability_periods period
                        WHERE period.organization_id=current_profile.organization_id
                          AND period.availability_profile_id=current_profile.id
                          AND period.status='active') AS interval_count,
                       (SELECT count(*) FROM availability_exceptions exception
                        WHERE exception.organization_id=current_profile.organization_id
                          AND exception.availability_profile_id=current_profile.id
                          AND exception.status='active') AS exception_count
                FROM availability_profiles current_profile
                WHERE current_profile.organization_id=?
                  AND current_profile.workforce_member_id=?
                  AND current_profile.id<>?
                  AND current_profile.lifecycle_state='active'
                  AND current_profile.effective_from<?::timestamptz
                  AND COALESCE(current_profile.effective_to,?::timestamptz)<=clock_timestamp()
                FOR UPDATE OF current_profile
                """,
                context.organizationId(),
                row.get("workforce_member_id"),
                id,
                row.get("effective_from"),
                row.get("effective_from"));
        var superseded = jdbc.update(
                """
                UPDATE availability_profiles current_profile
                SET lifecycle_state='superseded',status='superseded',
                    effective_to=LEAST(COALESCE(current_profile.effective_to,?::timestamptz),?::timestamptz),
                    lock_version=current_profile.lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE current_profile.organization_id=?
                  AND current_profile.workforce_member_id=?
                  AND current_profile.id<>?
                  AND current_profile.lifecycle_state='active'
                  AND current_profile.effective_from<?::timestamptz
                  AND COALESCE(current_profile.effective_to,?::timestamptz)<=clock_timestamp()
                """,
                row.get("effective_from"), row.get("effective_from"), context.actorId(),
                context.organizationId(), row.get("workforce_member_id"), id,
                row.get("effective_from"), row.get("effective_from"));
        if (superseded != predecessors.size()) {
            throw stale("An active availability profile changed before supersession.");
        }
        var changed = jdbc.update(
                """
                UPDATE availability_profiles SET lifecycle_state='active',status='active',
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lifecycle_state='scheduled' AND lock_version=?
                  AND effective_from<=clock_timestamp()
                """,
                context.actorId(), context.organizationId(), id, revision);
        requireChanged(changed, "The availability profile is stale or not effective.");
        var payload = ordered(
                "memberId", row.get("workforce_member_id"), "profileId", id,
                "timezone", row.get("timezone"), "effectiveFrom", instantValue(row.get("effective_from")),
                "intervalCount", ((Number) row.get("interval_count")).intValue(),
                "exceptionCount", ((Number) row.get("exception_count")).intValue(),
                "revision", revision + 1);
        var additionalEvidence = predecessors.stream()
                .map(predecessor -> {
                    var predecessorPayload = ordered(
                            "memberId", row.get("workforce_member_id"),
                            "profileId", predecessor.get("id"),
                            "timezone", predecessor.get("timezone"),
                            "effectiveFrom", instantValue(predecessor.get("effective_from")),
                            "intervalCount", ((Number) predecessor.get("interval_count")).intValue(),
                            "exceptionCount", ((Number) predecessor.get("exception_count")).intValue(),
                            "revision", ((Number) predecessor.get("lock_version")).longValue() + 1);
                    return new MutationEvidence(
                            (UUID) predecessor.get("id"),
                            "availability_profile",
                            "workforce.availability.superseded",
                            "workforce.availability.superseded",
                            "availability_profile",
                            predecessorPayload,
                            predecessorPayload);
                })
                .toList();
        return result(id, "availability_profile", "workforce.availability.activated",
                "workforce.availability.activated", "availability_profile", payload, payload, 200,
                revision + 1, additionalEvidence);
    }

    private MutationResult runReadiness(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId = member(command);
        var revision = requireRevision(command);
        var member = requireMember(context, memberId);
        if (member.revision() != revision) {
            throw stale("The member changed before readiness evaluation began.");
        }
        if (!Set.of("draft", "submitted", "suspended").contains(member.status())) {
            throw conflict("Readiness can only be run for onboarding or suspended workforce members.");
        }
        var runId = UuidV7Generator.randomUuid();
        var requestedAt = command.now();
        var expiresAt = requestedAt.plusSeconds(900);
        var config = jdbc.query(
                """
                SELECT id,snapshot_digest,effective_at FROM workforce_configuration_snapshots
                WHERE organization_id=? AND status='active' AND superseded_at IS NULL
                  AND effective_at<=? ORDER BY effective_at DESC,id DESC LIMIT 1
                """,
                resultSet -> resultSet.next()
                        ? new Configuration(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("snapshot_digest"),
                                resultSet.getTimestamp("effective_at").toInstant())
                        : null,
                context.organizationId(),
                Timestamp.from(requestedAt));
        var configDigest = config == null ? EMPTY_DIGEST : config.digest();
        var configRevision = config == null ? 0 : config.effectiveAt().getEpochSecond();
        var memberDigest = digest(member.id()
                + "|"
                + member.pathway()
                + "|"
                + member.status()
                + "|"
                + member.revision());
        var gates = evaluateReadiness(context, member, config, requestedAt);
        var blockerCount = (int) gates.stream().filter(gate -> gate.outcome().equals("blocked")).count();
        var warningCount = (int) gates.stream().filter(gate -> gate.outcome().equals("warning")).count();
        var resultDigest = digest(gates.stream()
                .map(gate -> gate.key() + ":" + gate.outcome() + ":" + Objects.toString(gate.evidenceDigest(), ""))
                .reduce(
                        memberDigest + "|" + configDigest + "|" + requestedAt + "|" + expiresAt,
                        (left, right) -> left + "|" + right));
        jdbc.update(
                """
                INSERT INTO workforce_readiness_runs(
                    id,organization_id,workforce_member_id,pathway,member_revision,member_digest,
                    configuration_revision,configuration_digest,requested_at,completed_at,expires_at,
                    blocker_count,warning_count,result_digest,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'complete',?,?)
                """,
                runId,
                context.organizationId(),
                memberId,
                member.pathway(),
                member.revision(),
                memberDigest,
                configRevision,
                configDigest,
                Timestamp.from(requestedAt),
                Timestamp.from(requestedAt),
                Timestamp.from(expiresAt),
                blockerCount,
                warningCount,
                resultDigest,
                context.actorId(),
                context.actorId());
        for (var gate : gates) {
            jdbc.update(
                    """
                    INSERT INTO workforce_readiness_results(
                        id,organization_id,readiness_run_id,gate_key,gate_version,outcome,
                        reason_code,remediation_code,evidence_type,evidence_id,evidence_digest,
                        deep_link_screen,status,created_by,updated_by)
                    VALUES (?,?,?,?,'m2-readiness-v1',?,?,?,?,?,?,?, ?,?,?)
                    """,
                    UuidV7Generator.randomUuid(),
                    context.organizationId(),
                    runId,
                    gate.key(),
                    gate.outcome(),
                    gate.reasonCode(),
                    gate.remediationCode(),
                    gate.evidenceType(),
                    gate.evidenceId(),
                    gate.evidenceDigest(),
                    gate.deepLink(),
                    gate.outcome(),
                    context.actorId(),
                    context.actorId());
        }
        var changed = jdbc.update(
                """
                UPDATE workforce_members
                SET current_readiness_run_id=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                runId,
                context.actorId(),
                context.organizationId(),
                memberId,
                member.revision());
        requireChanged(changed, "The member changed during readiness evaluation.");
        var payload = ordered(
                "memberId", memberId,
                "runId", runId,
                "pathway", member.pathway(),
                "resultDigest", resultDigest,
                "blockerCount", blockerCount,
                "warningCount", warningCount,
                "expiresAt", expiresAt,
                "failureCode", null);
        return result(
                runId,
                "workforce_readiness_run",
                "workforce.readiness.completed",
                null,
                null,
                payload,
                Map.of(),
                201,
                0);
    }

    private List<Gate> evaluateReadiness(
            AuthorizedTenantContext context,
            Member member,
            Configuration configuration,
            Instant at) {
        var gates = new ArrayList<Gate>();
        gates.add(gate(
                "workforce.identity.linked",
                exists(
                        """
                        SELECT EXISTS(SELECT 1 FROM workforce_members member
                        JOIN organization_person_links link ON link.organization_id=member.organization_id
                          AND link.id=member.organization_person_link_id
                        WHERE member.organization_id=? AND member.id=?
                          AND link.relationship_status='active'
                          AND link.effective_from<=?
                          AND (link.effective_to IS NULL OR link.effective_to>?)
                          AND NOT EXISTS(SELECT 1 FROM person_merge_requests merge_request
                              WHERE merge_request.organization_id=member.organization_id
                                AND merge_request.decision_state IN ('draft','submitted','approved')
                                AND (merge_request.retained_link_id=link.id
                                  OR merge_request.discarded_link_id=link.id)))
                        """,
                        context.organizationId(), member.id(), Timestamp.from(at), Timestamp.from(at)),
                "M2-04"));
        gates.add(gate(
                "workforce.identity.minimum",
                exists(
                        """
                        SELECT EXISTS(SELECT 1 FROM workforce_members member
                        JOIN organization_person_links link ON link.organization_id=member.organization_id
                          AND link.id=member.organization_person_link_id
                        JOIN person_profiles person ON person.id=link.person_id
                        WHERE member.organization_id=? AND member.id=?
                          AND person.legal_given_name<>'' AND person.legal_family_name<>''
                          AND person.display_name<>''
                          AND (person.birth_date IS NULL
                            OR person.birth_date<=(?::date-interval '18 years')::date)
                          AND EXISTS(SELECT 1 FROM person_contacts contact
                              WHERE contact.person_id=person.id AND contact.contact_use='work'
                                AND contact.status='active' AND contact.effective_from<=?
                                AND (contact.effective_to IS NULL OR contact.effective_to>?)))
                        """,
                        context.organizationId(), member.id(), Timestamp.from(at),
                        Timestamp.from(at), Timestamp.from(at)),
                "M2-05"));
        gates.add(gate(
                "workforce.member.identifier",
                exists(
                        """
                        SELECT EXISTS(SELECT 1 FROM workforce_identifiers
                        WHERE organization_id=? AND workforce_member_id=? AND status='active'
                          AND effective_from<=? AND (effective_to IS NULL OR effective_to>?))
                        """,
                        context.organizationId(), member.id(), Timestamp.from(at), Timestamp.from(at)),
                "M2-03"));
        gates.add(gate(
                "workforce.engagement.coverage",
                exists(
                        """
                        SELECT EXISTS(SELECT 1 FROM employment_engagements
                        WHERE organization_id=? AND workforce_member_id=? AND status IN ('scheduled','active')
                          AND effective_from<=? AND (effective_to IS NULL OR effective_to>?))
                        """,
                        context.organizationId(), member.id(), Timestamp.from(at), Timestamp.from(at)),
                "M2-06"));
        gates.add(gate(
                "workforce.assignment.primary",
                exists(
                        """
                        SELECT count(*)=1 FROM workforce_assignments
                        WHERE organization_id=? AND workforce_member_id=? AND primary_assignment
                          AND lifecycle_state IN ('scheduled','active') AND effective_from<=?
                          AND (effective_to IS NULL OR effective_to>?)
                        """,
                        context.organizationId(), member.id(), Timestamp.from(at), Timestamp.from(at)),
                "M2-15"));
        gates.add(gate(
                "workforce.assignment.hierarchy",
                exists(
                        """
                        SELECT EXISTS(
                            SELECT 1 FROM workforce_assignments assignment
                            WHERE assignment.organization_id=? AND assignment.workforce_member_id=?
                              AND assignment.lifecycle_state IN ('scheduled','active')
                              AND assignment.effective_from<=?
                              AND (assignment.effective_to IS NULL OR assignment.effective_to>?)
                        ) AND NOT EXISTS(
                            SELECT 1 FROM workforce_assignments assignment
                            LEFT JOIN facilities facility
                              ON facility.organization_id=assignment.organization_id
                             AND facility.id=assignment.facility_id
                            WHERE assignment.organization_id=? AND assignment.workforce_member_id=?
                              AND assignment.lifecycle_state IN ('scheduled','active')
                              AND assignment.effective_from<=?
                              AND (assignment.effective_to IS NULL OR assignment.effective_to>?)
                              AND (facility.id IS NULL OR facility.status<>'active'
                                OR (assignment.organization_unit_id IS NOT NULL AND NOT EXISTS(
                                    SELECT 1 FROM organization_units unit
                                    WHERE unit.organization_id=assignment.organization_id
                                      AND unit.id=assignment.organization_unit_id
                                      AND unit.facility_id=assignment.facility_id
                                      AND unit.status='active' AND unit.effective_from<=?
                                      AND (unit.effective_to IS NULL OR unit.effective_to>?)))
                                OR (assignment.location_id IS NOT NULL AND NOT EXISTS(
                                    SELECT 1 FROM service_locations location
                                    WHERE location.organization_id=assignment.organization_id
                                      AND location.id=assignment.location_id
                                      AND location.facility_id=assignment.facility_id
                                      AND location.status='active'
                                      AND location.effective_from<=?
                                      AND (location.effective_to IS NULL OR location.effective_to>?))))
                        """,
                        context.organizationId(), member.id(), Timestamp.from(at), Timestamp.from(at),
                        context.organizationId(), member.id(), Timestamp.from(at), Timestamp.from(at),
                        Timestamp.from(at), Timestamp.from(at),
                        Timestamp.from(at), Timestamp.from(at)),
                "M2-15"));
        var availabilityNotRequired=availabilityMayBeOmitted(context,member.id(),at);
        gates.add(gate(
                "workforce.availability.valid",
                exists(
                        """
                        SELECT EXISTS(SELECT 1 FROM availability_profiles profile
                        WHERE profile.organization_id=? AND profile.workforce_member_id=?
                          AND profile.lifecycle_state IN ('scheduled','active')
                          AND profile.effective_from<=?
                          AND (profile.effective_to IS NULL OR profile.effective_to>?)
                          AND ((profile.not_required AND ?::boolean)
                            OR (NOT profile.not_required AND EXISTS(
                              SELECT 1 FROM availability_periods period
                              WHERE period.organization_id=profile.organization_id
                                AND period.availability_profile_id=profile.id AND period.status='active'))))
                        """,
                        context.organizationId(), member.id(), Timestamp.from(at), Timestamp.from(at),
                        availabilityNotRequired),
                "M2-18"));
        var accessLinked = exists(
                """
                SELECT EXISTS(SELECT 1 FROM access_assignment_scopes scope
                JOIN organization_memberships membership ON membership.organization_id=scope.organization_id
                  AND membership.id=scope.access_assignment_id
                WHERE scope.organization_id=? AND scope.workforce_member_id=?
                  AND scope.status IN ('approved','active')
                  AND scope.effective_from<=? AND (scope.effective_to IS NULL OR scope.effective_to>?)
                  AND membership.status='active' AND membership.effective_from<=?
                  AND (membership.effective_to IS NULL OR membership.effective_to>?))
                """,
                context.organizationId(), member.id(), Timestamp.from(at), Timestamp.from(at),
                Timestamp.from(at), Timestamp.from(at));
        var accessOutcome = accessLinked
                || Set.of("none_required", "deferred").contains(member.accessIntent())
                ? "complete"
                : member.accessIntent().equals("invitation") ? "warning" : "blocked";
        gates.add(new Gate(
                "workforce.access.intent_resolved",
                accessOutcome,
                accessOutcome.equals("complete")
                        ? null
                        : accessOutcome.equals("warning")
                                ? "account_invitation_pending"
                                : "existing_account_link_missing",
                accessOutcome.equals("complete") ? null : "resolve_account_access",
                "workforce_member",
                member.id(),
                digest(member.accessIntent() + "|linked=" + accessLinked),
                "M2-19"));
        gates.add(gate(
                "workforce.lifecycle.clear",
                Set.of("draft", "submitted", "suspended").contains(member.status())
                        && !exists(
                                """
                                SELECT EXISTS(SELECT 1 FROM workforce_offboarding_requests
                                WHERE organization_id=? AND workforce_member_id=?
                                  AND status IN ('submitted','approved','scheduled','executing','failed'))
                                """,
                                context.organizationId(),member.id()),
                "M2-23"));
        gates.add(gate(
                "workforce.registry.active",
                registryReferencesActive(context, member.id(), at),
                "M2-28"));
        gates.add(gate(
                "workforce.configuration.integrity",
                configuration != null
                        && exists(
                                """
                                SELECT EXISTS(SELECT 1 FROM workforce_configuration_snapshots snapshot
                                WHERE snapshot.organization_id=? AND snapshot.id=?
                                  AND snapshot.status='active' AND snapshot.superseded_at IS NULL
                                  AND snapshot.snapshot_digest=? AND snapshot.effective_at<=?)
                                  AND NOT EXISTS(SELECT 1
                                      FROM workforce_configuration_change_requests request
                                      WHERE request.organization_id=?
                                        AND request.status IN ('validating','ready','submitted','approved'))
                                """,
                                context.organizationId(), configuration.id(), configuration.digest(),
                                Timestamp.from(at),context.organizationId()),
                "M2-26"));
        gates.add(gate(
                "workforce.governance.separation",
                Objects.requireNonNull(jdbc.queryForObject(
                        """
                        SELECT count(DISTINCT membership.user_id)>=2
                        FROM organization_memberships membership
                        JOIN authorization_roles role
                          ON role.role_key=membership.role_key
                        JOIN authorization_role_permissions role_permission
                          ON role_permission.role_key=membership.role_key
                         AND role_permission.permission_key='workforce.activation.approve'
                        JOIN authorization_permissions permission
                          ON permission.permission_key=role_permission.permission_key
                        WHERE membership.organization_id=? AND membership.status='active'
                          AND membership.effective_from<=?
                          AND (membership.effective_to IS NULL OR membership.effective_to>?)
                          AND role.status='active' AND role.interactive
                          AND permission.status='active'
                          AND EXISTS(SELECT 1 FROM authorization_registry_releases release
                              WHERE release.registry_version=role.registry_version
                                AND release.status='active')
                          AND EXISTS(SELECT 1 FROM authorization_registry_releases release
                              WHERE release.registry_version=permission.registry_version
                                AND release.status='active')
                        """,
                        Boolean.class,context.organizationId(),Timestamp.from(at),Timestamp.from(at))),
                "M2-20"));
        gates.add(gate("workforce.platform.ready", platformReady(member.pathway()), "M2-20"));

        if (member.pathway().equals("clinical")) {
            gates.add(gate(
                    "practitioner.profile.active",
                    exists(
                            """
                            SELECT EXISTS(SELECT 1 FROM practitioner_profiles practitioner
                            JOIN workforce_registry_versions profession
                              ON profession.organization_id=practitioner.organization_id
                             AND profession.registry_entry_id=practitioner.profession_entry_id
                             AND profession.id=practitioner.profession_version_id
                            WHERE practitioner.organization_id=? AND practitioner.workforce_member_id=?
                              AND ((?::boolean AND practitioner.status IN ('active','suspended'))
                                OR (NOT ?::boolean AND practitioner.status='active'))
                              AND profession.status='active'
                              AND profession.superseded_at IS NULL
                              AND practitioner.effective_from<=?
                              AND (practitioner.effective_to IS NULL OR practitioner.effective_to>?))
                            """,
                            context.organizationId(),member.id(),member.status().equals("suspended"),
                            member.status().equals("suspended"),Timestamp.from(at),Timestamp.from(at)),
                    "M2-07"));
            gates.add(gate(
                    "practitioner.registration.current",
                    exists(
                            """
                            SELECT EXISTS(SELECT 1 FROM professional_registrations registration
                            JOIN practitioner_profiles practitioner ON practitioner.organization_id=registration.organization_id
                              AND practitioner.id=registration.practitioner_profile_id
                            JOIN workforce_registry_versions regulator_version
                              ON regulator_version.organization_id=registration.organization_id
                             AND regulator_version.id=registration.regulator_version_id
                             AND regulator_version.registry_entry_id=registration.regulator_entry_id
                            JOIN workforce_registry_versions type_version
                              ON type_version.organization_id=registration.organization_id
                             AND type_version.id=registration.registration_type_version_id
                             AND type_version.registry_entry_id=registration.registration_type_entry_id
                            WHERE registration.organization_id=? AND practitioner.workforce_member_id=?
                              AND registration.status='verified'
                              AND registration.valid_from<=?::date
                              AND (registration.expires_on IS NULL OR registration.expires_on>=?::date)
                              AND regulator_version.status='active'
                              AND regulator_version.effective_from<=?
                              AND (regulator_version.effective_to IS NULL OR regulator_version.effective_to>?)
                              AND type_version.status='active' AND type_version.effective_from<=?
                              AND (type_version.effective_to IS NULL OR type_version.effective_to>?))
                            """,
                            context.organizationId(), member.id(), Timestamp.from(at), Timestamp.from(at),
                            Timestamp.from(at),Timestamp.from(at),Timestamp.from(at),Timestamp.from(at)),
                    "M2-09"));
            gates.add(gate(
                    "practitioner.qualification.complete",
                    mandatoryRequirementsMet(
                            context,member.id(),at,Set.of("qualification","training")),
                    "M2-08"));
            gates.add(gate(
                    "practitioner.credential.complete",
                    mandatoryRequirementsMet(context,member.id(),at,Set.of("credential")),
                    "M2-12"));
            var specialtyCount=Objects.requireNonNull(jdbc.queryForObject(
                    """
                    SELECT count(*) FROM practitioner_specialties specialty
                    JOIN practitioner_profiles practitioner ON practitioner.organization_id=specialty.organization_id
                      AND practitioner.id=specialty.practitioner_profile_id
                    JOIN workforce_registry_versions version
                      ON version.organization_id=specialty.organization_id
                     AND version.id=specialty.specialty_version_id
                     AND version.registry_entry_id=specialty.specialty_entry_id
                    WHERE specialty.organization_id=? AND practitioner.workforce_member_id=?
                      AND specialty.designation='primary' AND specialty.status='active'
                      AND specialty.effective_from<=?
                      AND (specialty.effective_to IS NULL OR specialty.effective_to>?)
                      AND version.status='active' AND version.effective_from<=?
                      AND (version.effective_to IS NULL OR version.effective_to>?)
                    """,
                    Integer.class,context.organizationId(), member.id(),Timestamp.from(at),Timestamp.from(at),
                    Timestamp.from(at),Timestamp.from(at)));
            var specialtyRequired=exists(
                    """
                    SELECT EXISTS(SELECT 1 FROM scope_requirements requirement
                    JOIN scopes_of_practice scope
                      ON scope.organization_id=requirement.organization_id
                     AND scope.scope_definition_id=requirement.scope_definition_id
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=scope.organization_id
                     AND practitioner.id=scope.practitioner_profile_id
                    WHERE requirement.organization_id=? AND practitioner.workforce_member_id=?
                      AND scope.lifecycle_state='approved' AND requirement.status='active'
                      AND requirement.mandatory AND requirement.requirement_type='specialty')
                    """,
                    context.organizationId(),member.id());
            var specialtyComplete=specialtyCount==1;
            var specialtyOutcome=specialtyComplete?"complete":specialtyRequired?"blocked":"warning";
            gates.add(new Gate(
                    "practitioner.specialty.valid",
                    specialtyOutcome,
                    specialtyComplete?null:"primary_specialty_missing",
                    specialtyComplete?null:"review_specialty",
                    "server_evaluation",
                    null,
                    digest("practitioner.specialty.valid|"+member.id()),
                    "M2-13"));
            var approvedScopes=jdbc.queryForList(
                    """
                    SELECT scope.id FROM scopes_of_practice scope
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=scope.organization_id
                     AND practitioner.id=scope.practitioner_profile_id
                    JOIN scope_definitions definition
                      ON definition.organization_id=scope.organization_id
                     AND definition.id=scope.scope_definition_id
                    WHERE scope.organization_id=? AND practitioner.workforce_member_id=?
                      AND scope.lifecycle_state='approved' AND definition.status='active'
                      AND scope.effective_from<=?
                      AND (scope.effective_to IS NULL OR scope.effective_to>?)
                      AND scope.decided_by IS NOT NULL AND scope.decided_by<>scope.submitted_by
                      AND EXISTS(SELECT 1 FROM scope_activities activity
                          JOIN workforce_registry_versions version
                            ON version.organization_id=activity.organization_id
                           AND version.id=activity.activity_version_id
                           AND version.registry_entry_id=activity.activity_entry_id
                          WHERE activity.organization_id=scope.organization_id
                            AND activity.scope_of_practice_id=scope.id
                            AND activity.status='active' AND activity.effective_from<=?
                            AND (activity.effective_to IS NULL OR activity.effective_to>?)
                            AND version.status='active' AND version.effective_from<=?
                            AND (version.effective_to IS NULL OR version.effective_to>?))
                    """,
                    UUID.class,context.organizationId(),member.id(),Timestamp.from(at),Timestamp.from(at),
                    Timestamp.from(at),Timestamp.from(at),Timestamp.from(at),Timestamp.from(at));
            var approvedScopeComplete=approvedScopes.stream()
                    .map(scopeId -> requireScope(context,scopeId))
                    .anyMatch(scope -> scopeRequirementsMet(context,scope,at));
            gates.add(gate("practitioner.scope.approved",approvedScopeComplete,"M2-14"));
            var serviceAssignments=Objects.requireNonNull(jdbc.queryForObject(
                    """
                    SELECT count(*) FROM practitioner_service_assignments assignment
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=assignment.organization_id
                     AND practitioner.id=assignment.practitioner_profile_id
                    WHERE assignment.organization_id=? AND practitioner.workforce_member_id=?
                      AND assignment.lifecycle_state IN ('scheduled','active')
                    """,
                    Integer.class,context.organizationId(),member.id()));
            var ineligibleServices=Objects.requireNonNull(jdbc.queryForObject(
                    """
                    SELECT count(*) FROM practitioner_service_assignments assignment
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=assignment.organization_id
                     AND practitioner.id=assignment.practitioner_profile_id
                    LEFT JOIN practitioner_eligibility_evidence evidence
                      ON evidence.organization_id=assignment.organization_id
                     AND evidence.id=assignment.eligibility_evidence_id
                    WHERE assignment.organization_id=? AND practitioner.workforce_member_id=?
                      AND assignment.lifecycle_state IN ('scheduled','active')
                      AND (evidence.id IS NULL OR evidence.outcome<>'eligible'
                        OR evidence.result_digest<>assignment.eligibility_digest
                        OR evidence.practitioner_profile_id<>assignment.practitioner_profile_id
                        OR evidence.service_id<>assignment.service_id
                        OR evidence.facility_id<>assignment.facility_id
                        OR evidence.location_id IS DISTINCT FROM assignment.location_id
                        OR evidence.scope_evidence->>'scopeId'<>assignment.scope_of_practice_id::text
                        OR evidence.assignment_evidence->>'serviceAssignmentId'<>assignment.id::text
                        OR evidence.evaluated_from>assignment.effective_from
                        OR (assignment.effective_to IS NOT NULL
                          AND (evidence.evaluated_to IS NULL
                            OR evidence.evaluated_to<assignment.effective_to))
                        OR evidence.expires_at<=?)
                    """,
                    Integer.class,context.organizationId(),member.id(),Timestamp.from(at)));
            var serviceOutcome=serviceAssignments==0?"warning":ineligibleServices==0?"complete":"blocked";
            gates.add(new Gate(
                    "practitioner.service.eligible",serviceOutcome,
                    serviceAssignments==0?"no_required_service_assignment"
                            :ineligibleServices==0?null:"service_assignment_ineligible",
                    serviceOutcome.equals("complete")?null:"review_service_assignments",
                    "service_assignment_set",null,
                    digest(member.id()+"|services|"+serviceAssignments+"|invalid|"+ineligibleServices),
                    "M2-16"));
            var supervisionRequirements=Objects.requireNonNull(jdbc.queryForObject(
                    """
                    SELECT count(*) FROM practitioner_service_assignments assignment
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=assignment.organization_id
                     AND practitioner.id=assignment.practitioner_profile_id
                    JOIN scope_activities activity
                      ON activity.organization_id=assignment.organization_id
                     AND activity.scope_of_practice_id=assignment.scope_of_practice_id
                     AND activity.status='active' AND activity.supervision_mode_entry_id IS NOT NULL
                    WHERE assignment.organization_id=? AND practitioner.workforce_member_id=?
                      AND assignment.lifecycle_state IN ('scheduled','active')
                    """,
                    Integer.class,context.organizationId(),member.id()));
            var unresolvedSupervision=Objects.requireNonNull(jdbc.queryForObject(
                    """
                    SELECT count(*) FROM practitioner_service_assignments assignment
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=assignment.organization_id
                     AND practitioner.id=assignment.practitioner_profile_id
                    JOIN scope_activities activity
                      ON activity.organization_id=assignment.organization_id
                     AND activity.scope_of_practice_id=assignment.scope_of_practice_id
                     AND activity.status='active' AND activity.supervision_mode_entry_id IS NOT NULL
                    LEFT JOIN practitioner_profiles supervisor
                      ON supervisor.organization_id=assignment.organization_id
                     AND supervisor.id=assignment.supervisor_practitioner_id
                    LEFT JOIN workforce_members supervisor_member
                      ON supervisor_member.organization_id=supervisor.organization_id
                     AND supervisor_member.id=supervisor.workforce_member_id
                    LEFT JOIN practitioner_eligibility_evidence evidence
                      ON evidence.organization_id=assignment.organization_id
                     AND evidence.id=assignment.eligibility_evidence_id
                    WHERE assignment.organization_id=? AND practitioner.workforce_member_id=?
                      AND assignment.lifecycle_state IN ('scheduled','active')
                      AND (supervisor.id IS NULL OR supervisor.id=practitioner.id
                        OR supervisor.status<>'active' OR supervisor_member.lifecycle_state<>'active'
                        OR coalesce((evidence.supervision_evidence->>'complete')::boolean,false)=false
                        OR evidence.supervision_evidence->>'supervisorId'<>supervisor.id::text)
                    """,
                    Integer.class,context.organizationId(),member.id()));
            var supervisionOutcome=supervisionRequirements==0?"not_applicable"
                    :unresolvedSupervision==0?"complete":"blocked";
            gates.add(new Gate(
                    "practitioner.supervision.resolved",supervisionOutcome,
                    supervisionRequirements==0?"no_supervision_requirement"
                            :unresolvedSupervision==0?null:"supervision_unresolved",
                    unresolvedSupervision==0?null:"resolve_supervision",
                    "supervision_assignment_set",null,
                    digest(member.id()+"|supervision|"+supervisionRequirements+"|invalid|"+unresolvedSupervision),
                    "M2-16"));
            var minimumExpiryDays=jdbc.queryForObject(
                    """
                    SELECT min(expiry_date-?::date) FROM (
                        SELECT credential.expires_on AS expiry_date
                        FROM practitioner_credentials credential
                        WHERE credential.organization_id=? AND credential.workforce_member_id=?
                          AND credential.status='verified' AND credential.expires_on IS NOT NULL
                          AND NOT EXISTS(SELECT 1 FROM practitioner_credentials successor
                              WHERE successor.organization_id=credential.organization_id
                                AND successor.supersedes_id=credential.id
                                AND successor.status='verified'
                                AND (successor.issued_on IS NULL
                                  OR successor.issued_on<=credential.expires_on+1)
                                AND (successor.expires_on IS NULL
                                  OR successor.expires_on>credential.expires_on))
                        UNION ALL
                        SELECT registration.expires_on
                        FROM professional_registrations registration
                        JOIN practitioner_profiles practitioner
                          ON practitioner.organization_id=registration.organization_id
                         AND practitioner.id=registration.practitioner_profile_id
                        WHERE registration.organization_id=? AND practitioner.workforce_member_id=?
                          AND registration.status='verified' AND registration.expires_on IS NOT NULL
                          AND NOT EXISTS(SELECT 1 FROM professional_registrations successor
                              WHERE successor.organization_id=registration.organization_id
                                AND successor.supersedes_id=registration.id
                                AND successor.status='verified'
                                AND successor.valid_from<=registration.expires_on+1
                                AND (successor.expires_on IS NULL
                                  OR successor.expires_on>registration.expires_on))
                    ) expiry
                    """,
                    Integer.class,Timestamp.from(at),context.organizationId(),member.id(),
                    context.organizationId(),member.id());
            var expiryOutcome=minimumExpiryDays==null || minimumExpiryDays>30?"complete"
                    :minimumExpiryDays>7?"warning":"blocked";
            gates.add(new Gate(
                    "practitioner.expiry.horizon",expiryOutcome,
                    expiryOutcome.equals("complete")?null:
                            expiryOutcome.equals("warning")?"expiry_within_30_days":"expiry_within_7_days",
                    expiryOutcome.equals("complete")?null:"renew_expiring_evidence",
                    "expiry_horizon",null,
                    digest(member.id()+"|minimumExpiryDays|"+minimumExpiryDays),"M2-25"));
            gates.add(gate(
                    "practitioner.document.pipeline",
                    !exists(
                            """
                            SELECT EXISTS(SELECT 1 FROM practitioner_credentials credential
                            LEFT JOIN credential_verifications verification
                              ON verification.organization_id=credential.organization_id
                             AND verification.id=credential.current_verification_id
                            WHERE credential.organization_id=? AND credential.workforce_member_id=?
                              AND credential.status='verified'
                              AND (verification.id IS NULL OR verification.status<>'verified'
                                OR verification.decision<>'verified'
                                OR cardinality(verification.evidence_ids)=0
                                OR EXISTS(
                                  SELECT 1 FROM unnest(verification.evidence_ids) evidence_id
                                  LEFT JOIN credential_documents document
                                    ON document.organization_id=credential.organization_id
                                   AND document.id=evidence_id
                                   AND document.practitioner_credential_id=credential.id
                                  LEFT JOIN document_promotion_evidence promotion
                                    ON promotion.organization_id=document.organization_id
                                   AND promotion.document_id=document.platform_document_id
                                   AND promotion.object_version_id=document.platform_object_version_id
                                  WHERE document.id IS NULL OR document.status<>'clean'
                                     OR document.promoted_evidence_digest IS NULL
                                     OR promotion.id IS NULL)))
                            """,
                            context.organizationId(), member.id()),
                    "M2-10"));
        } else {
            for (var clinicalGate : List.of(
                    Map.entry("practitioner.profile.active", "M2-07"),
                    Map.entry("practitioner.registration.current", "M2-09"),
                    Map.entry("practitioner.qualification.complete", "M2-08"),
                    Map.entry("practitioner.credential.complete", "M2-12"),
                    Map.entry("practitioner.specialty.valid", "M2-13"),
                    Map.entry("practitioner.scope.approved", "M2-14"),
                    Map.entry("practitioner.service.eligible", "M2-16"),
                    Map.entry("practitioner.supervision.resolved", "M2-16"),
                    Map.entry("practitioner.expiry.horizon", "M2-25"),
                    Map.entry("practitioner.document.pipeline", "M2-10"))) {
                gates.add(new Gate(
                        clinicalGate.getKey(),
                        "not_applicable",
                        "non_clinical_pathway",
                        null,
                        "workforce_member",
                        member.id(),
                        digest("non_clinical|" + member.id()),
                        clinicalGate.getValue()));
            }
        }
        return List.copyOf(gates);
    }

    private MutationResult submitActivation(
            AuthorizedTenantContext context, MutationCommand command) {
        var runId = requireTarget(command);
        var revision = requireRevision(command);
        var run = requireReadinessRun(context, runId);
        if (run.revision() != revision
                || !run.status().equals("complete")
                || run.blockers() != 0
                || !run.expiresAt().isAfter(command.now())) {
            throw conflict("Only a fresh, zero-blocker readiness result may be submitted.");
        }
        var member = requireMember(context, run.memberId());
        if (!Set.of("draft", "submitted").contains(member.status())) {
            throw conflict("The member cannot be submitted from the current lifecycle state.");
        }
        if (!runId.equals(member.currentReadinessRunId())
                || member.revision() != run.memberRevision() + 1) {
            throw conflict("The readiness result is no longer bound to the current member revision.");
        }
        var requestId = UuidV7Generator.randomUuid();
        var submittedMemberRevision = member.status().equals("draft")
                ? member.revision() + 1
                : member.revision();
        var expiresAt = run.expiresAt().isBefore(command.now().plusSeconds(1800))
                ? run.expiresAt()
                : command.now().plusSeconds(1800);
        var acknowledgedWarnings = requireWarningAcknowledgements(context, runId, command);
        jdbc.update(
                """
                INSERT INTO workforce_activation_requests(
                    id,organization_id,workforce_member_id,member_revision,readiness_run_id,
                    result_digest,maker_id,submitted_reason_code,warning_acknowledgements,
                    requested_at,expires_at,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'submitted',?,?)
                """,
                requestId,
                context.organizationId(),
                run.memberId(),
                submittedMemberRevision,
                runId,
                run.resultDigest(),
                context.actorId(),
                required(command, "submittedReasonCode"),
                acknowledgedWarnings.toArray(String[]::new),
                Timestamp.from(command.now()),
                Timestamp.from(expiresAt),
                context.actorId(),
                context.actorId());
        if (member.status().equals("draft")) {
            var changed = jdbc.update(
                    """
                    UPDATE workforce_members
                    SET lifecycle_state='submitted',status='submitted',lock_version=lock_version+1,
                        updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND lifecycle_state='draft' AND lock_version=?
                    """,
                    context.actorId(),
                    context.organizationId(),
                    member.id(),
                    member.revision());
            requireChanged(changed, "The member changed while activation was submitted.");
            insertTransition(
                    context,
                    member.id(),
                    "draft",
                    "submitted",
                    command.now(),
                    required(command, "submittedReasonCode"),
                    "activation_request",
                    requestId,
                    member.revision() + 1);
        }
        var audit = ordered(
                "memberId", run.memberId(),
                "activationRequestId", requestId,
                "runId", runId,
                "resultDigest", run.resultDigest(),
                "decisionCode", null,
                "state", "submitted");
        return result(
                requestId,
                "workforce_activation_request",
                "workforce.activation.submitted",
                "workforce.activation.submitted",
                "workforce_activation_request",
                audit,
                audit,
                201,
                0);
    }

    private MutationResult approveActivation(
            AuthorizedTenantContext context, MutationCommand command) {
        var requestId = requireTarget(command);
        var revision = requireRevision(command);
        var request = requireActivation(context, requestId);
        if (isSubjectActor(context, request.memberId())) {
            throw conflict("A workforce member cannot decide their own activation request.");
        }
        var decision = required(command, "decisionCode");
        if (!Set.of("approved", "rejected").contains(decision)) {
            throw invalid("The activation decision is not supported.");
        }
        var changed = jdbc.update(
                """
                UPDATE workforce_activation_requests
                SET checker_id=?,decision_code=?,decided_at=clock_timestamp(),status=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='submitted' AND lock_version=?
                  AND maker_id<>? AND expires_at>clock_timestamp()
                """,
                context.actorId(),
                decision,
                decision,
                context.actorId(),
                context.organizationId(),
                requestId,
                revision,
                context.actorId());
        requireChanged(changed, "The activation request is stale, expired, or violates reviewer separation.");
        var payload = ordered(
                "memberId", request.memberId(),
                "activationRequestId", requestId,
                "runId", request.runId(),
                "resultDigest", request.resultDigest(),
                "decisionCode", decision,
                "state", decision);
        return result(
                requestId,
                "workforce_activation_request",
                "workforce.activation." + decision,
                "workforce.activation." + decision,
                "workforce_activation_request",
                payload,
                payload,
                200,
                revision + 1);
    }

    private MutationResult executeActivation(
            AuthorizedTenantContext context, MutationCommand command) {
        var requestId = requireTarget(command);
        var revision = requireRevision(command);
        var request = requireActivation(context, requestId);
        if (!request.status().equals("approved")
                || !request.expiresAt().isAfter(command.now())
                || request.makerId().equals(context.actorId())
                || isSubjectActor(context, request.memberId())) {
            throw conflict("The activation is not currently executable by this actor.");
        }
        var member = requireMember(context, request.memberId());
        if (!member.status().equals("submitted")) {
            throw conflict("The member is not in the submitted lifecycle state.");
        }
        if (member.revision() != request.memberRevision()
                || !request.runId().equals(member.currentReadinessRunId())) {
            throw conflict("The member changed after activation submission.");
        }
        if (!platformReady(member.pathway())) {
            throw conflict("A required workforce platform capability is not currently available.");
        }
        var run = requireReadinessRun(context, request.runId());
        if (!run.status().equals("complete")
                || run.blockers() != 0
                || !run.expiresAt().isAfter(command.now())
                || !run.resultDigest().equals(request.resultDigest())) {
            throw conflict("The activation readiness evidence is stale.");
        }
        var activeConfiguration = jdbc.query(
                """
                SELECT id,snapshot_digest,effective_at FROM workforce_configuration_snapshots
                WHERE organization_id=? AND status='active' AND superseded_at IS NULL
                  AND effective_at<=? ORDER BY effective_at DESC,id DESC LIMIT 1
                """,
                resultSet -> resultSet.next()
                        ? new Configuration(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("snapshot_digest"),
                                resultSet.getTimestamp("effective_at").toInstant())
                        : null,
                context.organizationId(),
                Timestamp.from(command.now()));
        if (activeConfiguration == null
                || !run.configurationDigest().equals(activeConfiguration.digest())
                || evaluateReadiness(context, member, activeConfiguration, command.now()).stream()
                        .anyMatch(gate -> gate.outcome().equals("blocked"))) {
            throw conflict("A non-overrideable activation gate changed after readiness evaluation.");
        }
        var memberRevision = member.revision() + 1;
        var memberChanged = jdbc.update(
                """
                UPDATE workforce_members
                SET lifecycle_state='active',status='active',activated_at=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lifecycle_state='submitted' AND lock_version=?
                """,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                member.id(),
                member.revision());
        requireChanged(memberChanged, "The member changed before activation committed.");
        var requestChanged = jdbc.update(
                """
                UPDATE workforce_activation_requests
                SET activator_id=?,activated_at=?,status='activated',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='approved' AND lock_version=?
                """,
                context.actorId(),
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                requestId,
                revision);
        requireChanged(requestChanged, "The activation approval changed before execution.");
        insertTransition(
                context,
                member.id(),
                "submitted",
                "active",
                command.now(),
                "activation_approved",
                "activation_request",
                requestId,
                memberRevision);
        var payload = ordered(
                "memberId", member.id(),
                "activationRequestId", requestId,
                "resultDigest", request.resultDigest(),
                "effectiveTime", command.now(),
                "revision", memberRevision);
        return result(
                member.id(),
                "workforce_member",
                "workforce.member.activated",
                "workforce.member.activated",
                "workforce_member",
                payload,
                payload,
                200,
                memberRevision);
    }

    private MutationResult transferAssignment(
            AuthorizedTenantContext context, MutationCommand command) {
        var predecessorId = requireTarget(command);
        var revision = requireRevision(command);
        var predecessor = requireAssignment(context, predecessorId);
        var effectiveTime = instant(command, "effectiveTime");
        if (predecessor.revision() != revision
                || !Set.of("scheduled", "active").contains(predecessor.status())) {
            throw stale("The assignment cannot be transferred from its current state.");
        }
        if (!effectiveTime.isAfter(predecessor.effectiveFrom())
                || (predecessor.effectiveTo() != null
                    && !effectiveTime.isBefore(predecessor.effectiveTo()))) {
            throw invalid("The transfer effective time must fall inside the predecessor range.");
        }
        var successorId = UuidV7Generator.randomUuid();
        var successorFacilityId = uuid(command, "successorFacilityId");
        var successorUnitId = optionalUuid(command, "successorOrganizationUnitId");
        var successorLocationId = optionalUuid(command, "successorLocationId");
        requireRegistryCategory(
                context, predecessor.assignmentTypeEntryId(), predecessor.assignmentTypeVersionId(),
                "assignment_type", effectiveTime, predecessor.effectiveTo());
        if (predecessor.positionEntryId() != null) {
            requireRegistryCategory(
                    context, predecessor.positionEntryId(), predecessor.positionVersionId(),
                    "position", effectiveTime, predecessor.effectiveTo());
        }
        requireHierarchyContext(
                context, successorFacilityId, successorUnitId, successorLocationId,
                effectiveTime, predecessor.effectiveTo());
        if (!engagementCovers(
                context, predecessor.memberId(), effectiveTime, predecessor.effectiveTo())) {
            throw conflict("An engagement must cover the successor assignment range.");
        }
        var impact=assignmentTransferImpact(context,command);
        requireCurrentImpact(command,impact);
        var impactDigest=impact.digest();
        var due = !effectiveTime.isAfter(command.now());
        var ended = jdbc.update(
                """
                UPDATE workforce_assignments
                SET effective_to=?,successor_id=?,
                    lifecycle_state=CASE WHEN ? THEN 'ended' ELSE lifecycle_state END,
                    status=CASE WHEN ? THEN 'ended' ELSE status END,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                  AND lifecycle_state IN ('scheduled','active')
                  AND effective_from<?
                """,
                Timestamp.from(effectiveTime),
                successorId,
                due,
                due,
                context.actorId(),
                context.organizationId(),
                predecessorId,
                revision,
                Timestamp.from(effectiveTime));
        requireChanged(ended, "The assignment cannot be transferred from its current state.");
        jdbc.update(
                """
                INSERT INTO workforce_assignments(
                    id,organization_id,workforce_member_id,facility_id,organization_unit_id,location_id,
                    assignment_type_entry_id,assignment_type_version_id,position_entry_id,position_version_id,
                    primary_assignment,effective_from,effective_to,predecessor_id,lifecycle_state,status,
                    created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                successorId,
                context.organizationId(),
                predecessor.memberId(),
                successorFacilityId,
                successorUnitId,
                successorLocationId,
                predecessor.assignmentTypeEntryId(),
                predecessor.assignmentTypeVersionId(),
                predecessor.positionEntryId(),
                predecessor.positionVersionId(),
                predecessor.primary(),
                Timestamp.from(effectiveTime),
                timestamp(predecessor.effectiveTo()),
                predecessorId,
                due ? "active" : "scheduled",
                due ? "active" : "scheduled",
                context.actorId(),
                context.actorId());
        var payload = ordered(
                "memberId", predecessor.memberId(),
                "predecessorId", predecessorId,
                "successorId", successorId,
                "impactDigest", impactDigest,
                "effectiveTime", effectiveTime);
        return result(
                predecessor.memberId(),
                "workforce_member",
                "workforce.assignment.transferred",
                "workforce.assignment.transferred",
                "workforce_assignment",
                payload,
                payload,
                200,
                revision + 1);
    }

    private MutationResult lifecycle(
            AuthorizedTenantContext context,
            MutationCommand command,
            String fromState,
            String toState) {
        var memberId = requireTarget(command);
        var revision = requireRevision(command);
        var effectiveTime = currentEffectiveTime(command);
        var categoryCode = required(command, "categoryCode");
        if (!categoryCode.matches("[a-z][a-z0-9._:-]{1,79}")) {
            throw invalid("categoryCode must be a stable lower-case machine key.");
        }
        var member = requireMember(context, memberId);
        if (!member.status().equals(fromState) || member.revision() != revision) {
            throw stale("The member lifecycle transition is stale or invalid.");
        }
        if (member.pathway().equals("clinical")
                && !permissions(context).contains("practitioner.scope.lifecycle")) {
            throw conflict("Clinical lifecycle changes require clinical governance authority.");
        }
        if (toState.equals("active")) {
            var run = requireReadinessRun(context, uuid(command, "readinessRunId"));
            var currentRun = Boolean.TRUE.equals(jdbc.queryForObject(
                    """
                    SELECT current_readiness_run_id=? FROM workforce_members
                    WHERE organization_id=? AND id=?
                    """,
                    Boolean.class,
                    run.id(),
                    context.organizationId(),
                    memberId));
            if (!run.memberId().equals(memberId)
                    || !run.status().equals("complete")
                    || run.blockers() != 0
                    || !run.expiresAt().isAfter(command.now())
                    || run.memberRevision() + 1 != revision
                    || run.requestedBy().equals(context.actorId())
                    || !currentRun
                    || !platformReady(member.pathway())) {
                throw conflict("Reactivation requires a fresh independently evaluated zero-risk readiness result.");
            }
            requireWarningAcknowledgements(context, run.id(), command);
        }
        var impact=memberLifecycleImpact(context,command,fromState,toState);
        requireCurrentImpact(command,impact);
        var transitionId = UuidV7Generator.randomUuid();
        var impactDigest = impact.digest();
        var changed = jdbc.update(
                """
                UPDATE workforce_members
                SET lifecycle_state=?,status=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lifecycle_state=? AND lock_version=?
                """,
                toState,
                toState,
                context.actorId(),
                context.organizationId(),
                memberId,
                fromState,
                revision);
        requireChanged(changed, "The member lifecycle transition is stale or invalid.");
        insertTransition(
                context,
                transitionId,
                memberId,
                fromState,
                toState,
                effectiveTime,
                categoryCode,
                "lifecycle_action",
                transitionId,
                revision + 1);
        if (toState.equals("suspended")) {
            var practitionerId=findPractitionerId(context,memberId,false);
            jdbc.update(
                    """
                    UPDATE practitioner_profiles SET lifecycle_state='suspended',status='suspended',
                        lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND workforce_member_id=? AND lifecycle_state='active'
                    """,
                    context.actorId(), context.organizationId(), memberId);
            reconcileProspectiveServiceAssignments(
                    context,practitionerId,null,effectiveTime);
        } else {
            jdbc.update(
                    """
                    UPDATE practitioner_profiles SET lifecycle_state='active',status='active',
                        lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND workforce_member_id=? AND lifecycle_state='suspended'
                    """,
                    context.actorId(), context.organizationId(), memberId);
        }
        var payload = ordered(
                "memberId", memberId,
                "transitionId", transitionId,
                "categoryCode", categoryCode,
                "impactDigest", impactDigest,
                "effectiveTime", effectiveTime,
                "revision", revision + 1);
        var event = "workforce.member." + (toState.equals("suspended") ? "suspended" : "reactivated");
        return result(
                memberId,
                "workforce_member",
                event,
                event,
                "workforce_member",
                payload,
                payload,
                200,
                revision + 1);
    }

    private MutationResult requestOffboarding(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId = requireTarget(command);
        var revision = requireRevision(command);
        var member = requireMember(context, memberId);
        if (member.revision() != revision) {
            throw stale("The member changed before the offboarding impact was calculated.");
        }
        if (!Set.of("active", "suspended").contains(member.status())) {
            throw conflict("Only an active or suspended member may enter offboarding.");
        }
        var requestId = UuidV7Generator.randomUuid();
        var engagementEndAt = instant(command, "engagementEndAt");
        var effectiveAt = instant(command, "effectiveAt");
        if (effectiveAt.isBefore(engagementEndAt)) {
            throw invalid("The offboarding effective time cannot precede the engagement end.");
        }
        var accessAction = required(command, "accessAction");
        if (!Set.of("revoke_at_effective", "revoke_immediately", "none").contains(accessAction)) {
            throw invalid("The offboarding access action is not supported.");
        }
        var reasonEntryId = uuid(command, "reasonEntryId");
        var reasonVersionId = uuid(command, "reasonVersionId");
        requireRegistryCategory(
                context, reasonEntryId, reasonVersionId, "offboarding_reason",
                command.now(), effectiveAt);
        var impact=offboardingRequestImpact(context,command);
        requireCurrentImpact(command,impact);
        var impactDigest=impact.digest();
        jdbc.update(
                """
                INSERT INTO workforce_offboarding_requests(
                    id,organization_id,workforce_member_id,engagement_end_at,effective_at,
                    reason_entry_id,reason_version_id,impact_digest,maker_id,access_action,
                    assignment_action,service_action,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,'end_at_effective','end_at_effective','submitted',?,?)
                """,
                requestId,
                context.organizationId(),
                memberId,
                Timestamp.from(engagementEndAt),
                Timestamp.from(effectiveAt),
                reasonEntryId,
                reasonVersionId,
                impactDigest,
                context.actorId(),
                accessAction,
                context.actorId(),
                context.actorId());
        var payload = ordered(
                "memberId", memberId,
                "offboardingRequestId", requestId,
                "impactDigest", impactDigest,
                "effectiveTime", effectiveAt,
                "state", "submitted",
                "failureCode", null);
        return result(
                requestId,
                "workforce_offboarding_request",
                "workforce.offboarding.requested",
                null,
                null,
                payload,
                Map.of(),
                201,
                0);
    }

    private MutationResult approveOffboarding(
            AuthorizedTenantContext context, MutationCommand command) {
        var requestId = requireTarget(command);
        var revision = requireRevision(command);
        var request = requireOffboarding(context, requestId);
        if (isSubjectActor(context, request.memberId())) {
            throw conflict("A workforce member cannot approve their own offboarding request.");
        }
        var currentImpact = offboardingImpactDigest(
                context, request.memberId(), request.engagementEndAt(), request.effectiveAt(),
                request.accessAction());
        if (!currentImpact.equals(request.impactDigest())) {
            throw conflict("The offboarding impact changed and must be reviewed again.");
        }
        var approvedState = jdbc.query(
                """
                UPDATE workforce_offboarding_requests
                SET checker_id=?,
                    status=CASE WHEN effective_at>statement_timestamp()
                                THEN 'scheduled' ELSE 'approved' END,
                    lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='submitted' AND lock_version=?
                  AND maker_id<>?
                RETURNING status
                """,
                resultSet -> resultSet.next() ? resultSet.getString("status") : null,
                context.actorId(),
                context.actorId(),
                context.organizationId(),
                requestId,
                revision,
                context.actorId());
        if (approvedState == null) {
            throw stale("The offboarding request is stale or violates reviewer separation.");
        }
        var audit = ordered(
                "memberId", request.memberId(),
                "offboardingRequestId", requestId,
                "impactDigest", request.impactDigest(),
                "effectiveTime", request.effectiveAt(),
                "state", "approved",
                "failureCode", null);
        var outbox = ordered(
                "memberId", request.memberId(),
                "offboardingRequestId", requestId,
                "impactDigest", request.impactDigest(),
                "effectiveTime", request.effectiveAt(),
                "state", "approved");
        var additionalEvidence = approvedState.equals("scheduled")
                ? List.of(new MutationEvidence(
                        requestId,
                        "workforce_offboarding_request",
                        "workforce.offboarding.scheduled",
                        null,
                        null,
                        ordered(
                                "memberId", request.memberId(),
                                "offboardingRequestId", requestId,
                                "impactDigest", request.impactDigest(),
                                "effectiveTime", request.effectiveAt(),
                                "state", "scheduled",
                                "failureCode", null),
                        Map.of()))
                : List.<MutationEvidence>of();
        return result(
                requestId,
                "workforce_offboarding_request",
                "workforce.offboarding.approved",
                "workforce.offboarding.approved",
                "workforce_offboarding_request",
                audit,
                outbox,
                200,
                revision + 1,
                additionalEvidence);
    }

    private MutationResult executeOffboarding(
            AuthorizedTenantContext context, MutationCommand command) {
        var requestId = requireTarget(command);
        var revision = requireRevision(command);
        var request = requireOffboarding(context, requestId);
        var retryDue = !request.status().equals("failed") || exists(
                """
                SELECT EXISTS(SELECT 1 FROM workforce_offboarding_requests
                WHERE organization_id=? AND id=? AND status='failed'
                  AND dead_lettered_at IS NULL AND next_attempt_at<=?)
                """,
                context.organizationId(),requestId,Timestamp.from(command.now()));
        if (!Set.of("approved", "scheduled", "failed").contains(request.status())
                || request.effectiveAt().isAfter(command.now())
                || !retryDue
                || isSubjectActor(context, request.memberId())) {
            throw conflict("The approved offboarding plan is not due for execution.");
        }
        var currentImpact = offboardingImpactDigest(
                context, request.memberId(), request.engagementEndAt(), request.effectiveAt(),
                request.accessAction());
        if (!currentImpact.equals(request.impactDigest())) {
            throw conflict("The approved offboarding impact is no longer current.");
        }
        var accessEvidence = reconcileOffboardingAccess(context,request,command.now());
        var member = requireMember(context, request.memberId());
        if (!Set.of("active", "suspended").contains(member.status())) {
            throw conflict("The member cannot enter offboarding from the current lifecycle state.");
        }
        var memberChanged = jdbc.update(
                """
                UPDATE workforce_members
                SET lifecycle_state='offboarding',status='offboarding',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                context.actorId(),
                context.organizationId(),
                member.id(),
                member.revision());
        requireChanged(memberChanged, "The workforce member changed before offboarding execution.");
        insertTransition(
                context,
                member.id(),
                member.status(),
                "offboarding",
                request.effectiveAt(),
                "approved_offboarding",
                "offboarding_request",
                requestId,
                member.revision() + 1);
        jdbc.update(
                """
                UPDATE employment_engagements
                SET status=CASE WHEN effective_from>=? THEN 'cancelled' ELSE 'ended' END,
                    effective_to=CASE WHEN effective_from>=? THEN effective_to
                        ELSE LEAST(COALESCE(effective_to,?),?) END,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND workforce_member_id=?
                  AND status IN ('draft','scheduled','active','suspended')
                """,
                Timestamp.from(request.effectiveAt()), Timestamp.from(request.effectiveAt()),
                Timestamp.from(request.effectiveAt()), Timestamp.from(request.effectiveAt()),
                context.actorId(),
                context.organizationId(),
                member.id());
        jdbc.update(
                """
                UPDATE workforce_assignments
                SET lifecycle_state=CASE WHEN effective_from>=? THEN 'cancelled' ELSE 'ended' END,
                    status=CASE WHEN effective_from>=? THEN 'cancelled' ELSE 'ended' END,
                    effective_to=CASE WHEN effective_from>=? THEN effective_to
                        ELSE LEAST(COALESCE(effective_to,?),?) END,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND workforce_member_id=?
                  AND lifecycle_state IN ('draft','scheduled','active','suspended')
                """,
                Timestamp.from(request.effectiveAt()), Timestamp.from(request.effectiveAt()),
                Timestamp.from(request.effectiveAt()), Timestamp.from(request.effectiveAt()),
                Timestamp.from(request.effectiveAt()),
                context.actorId(),
                context.organizationId(),
                member.id());
        jdbc.update(
                """
                UPDATE practitioner_service_assignments assignment
                SET lifecycle_state=CASE WHEN assignment.effective_from>=? THEN 'cancelled' ELSE 'ended' END,
                    status=CASE WHEN assignment.effective_from>=? THEN 'cancelled' ELSE 'ended' END,
                    effective_to=CASE WHEN assignment.effective_from>=? THEN assignment.effective_to
                        ELSE LEAST(COALESCE(assignment.effective_to,?),?) END,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                FROM practitioner_profiles practitioner
                WHERE assignment.organization_id=? AND practitioner.organization_id=assignment.organization_id
                  AND practitioner.id=assignment.practitioner_profile_id
                  AND practitioner.workforce_member_id=?
                  AND assignment.lifecycle_state IN ('draft','scheduled','active','suspended')
                """,
                Timestamp.from(request.effectiveAt()), Timestamp.from(request.effectiveAt()),
                Timestamp.from(request.effectiveAt()), Timestamp.from(request.effectiveAt()),
                Timestamp.from(request.effectiveAt()),
                context.actorId(),
                context.organizationId(),
                member.id());
        jdbc.update(
                """
                UPDATE practitioner_profiles
                SET lifecycle_state='ended',status='ended',
                    effective_to=LEAST(COALESCE(effective_to,?),?),
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND workforce_member_id=?
                  AND lifecycle_state IN ('active','suspended')
                  AND effective_from<?
                """,
                Timestamp.from(request.effectiveAt()), Timestamp.from(request.effectiveAt()),
                context.actorId(), context.organizationId(), member.id(),
                Timestamp.from(request.effectiveAt()));
        jdbc.update(
                """
                UPDATE availability_profiles
                SET lifecycle_state='cancelled',status='cancelled',
                    effective_to=CASE WHEN effective_from<?
                        THEN LEAST(COALESCE(effective_to,?),?) ELSE effective_to END,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND workforce_member_id=?
                  AND lifecycle_state IN ('draft','scheduled','active')
                """,
                Timestamp.from(request.effectiveAt()), Timestamp.from(request.effectiveAt()),
                Timestamp.from(request.effectiveAt()), context.actorId(),
                context.organizationId(), member.id());
        jdbc.update(
                """
                UPDATE access_assignment_scopes
                SET status=CASE WHEN effective_from>=? THEN 'cancelled' ELSE 'ended' END,
                    effective_to=CASE WHEN effective_from>=? THEN effective_to
                                      ELSE LEAST(COALESCE(effective_to,?),?) END,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND workforce_member_id=?
                  AND status IN ('requested','approved','active')
                """,
                Timestamp.from(request.effectiveAt()),
                Timestamp.from(request.effectiveAt()),
                Timestamp.from(request.effectiveAt()),
                Timestamp.from(request.effectiveAt()),
                context.actorId(),
                context.organizationId(),
                member.id());
        var finalRevision = member.revision() + 2;
        var offboarded = jdbc.update(
                """
                UPDATE workforce_members
                SET lifecycle_state='offboarded',status='offboarded',offboarded_at=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lifecycle_state='offboarding'
                  AND lock_version=?
                """,
                Timestamp.from(request.effectiveAt()),
                context.actorId(),
                context.organizationId(),
                member.id(),
                member.revision() + 1);
        requireChanged(offboarded, "The workforce member changed while offboarding completed.");
        insertTransition(
                context,
                member.id(),
                "offboarding",
                "offboarded",
                request.effectiveAt(),
                "approved_offboarding",
                "offboarding_request",
                requestId,
                finalRevision);
        var changed = jdbc.update(
                """
                UPDATE workforce_offboarding_requests
                SET status='completed',failure_code=NULL,next_attempt_at=NULL,dead_lettered_at=NULL,
                    dead_letter_owner=NULL,
                    lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status IN ('approved','scheduled','failed')
                  AND lock_version=?
                """,
                context.actorId(),
                context.organizationId(),
                requestId,
                revision);
        requireChanged(changed, "The offboarding approval changed before execution.");
        var audit = ordered(
                "memberId", member.id(),
                "offboardingRequestId", requestId,
                "impactDigest", request.impactDigest(),
                "effectiveTime", request.effectiveAt(),
                "state", "completed",
                "failureCode", null);
        var outbox = ordered(
                "memberId", member.id(),
                "offboardingRequestId", requestId,
                "impactDigest", request.impactDigest(),
                "effectiveTime", request.effectiveAt(),
                "state", "completed");
        return result(
                requestId,
                "workforce_offboarding_request",
                "workforce.offboarding.completed",
                "workforce.offboarding.completed",
                "workforce_offboarding_request",
                audit,
                outbox,
                200,
                finalRevision,
                accessEvidence);
    }

    private List<MutationEvidence> reconcileOffboardingAccess(
            AuthorizedTenantContext context, Offboarding request, Instant now) {
        var impact=offboardingAccessImpact(context,request.memberId());
        if (impact.finalOwnerMemberships()>0) {
            throw conflict(
                    "A linked final-owner role requires a governed M1 owner transfer before offboarding can execute.");
        }
        if (impact.otherLiveMemberLinks()>0) {
            throw conflict(
                    "The linked account is also assigned to another live workforce member and cannot be revoked by this plan.");
        }
        if (request.accessAction().equals("none")) {
            if (impact.activeMemberships()>0) {
                throw conflict(
                        "Canonical access remains active and the approved plan contains no access revocation action.");
            }
            return List.of();
        }

        jdbc.queryForObject(
                "SELECT set_config('app.current_offboarding_request_id',?,true)",
                String.class,
                request.id().toString());
        var memberships=jdbc.query(
                """
                WITH target_users AS (
                    SELECT DISTINCT linked_membership.user_id
                    FROM access_assignment_scopes linked_scope
                    JOIN organization_memberships linked_membership
                      ON linked_membership.organization_id=linked_scope.organization_id
                     AND linked_membership.id=linked_scope.access_assignment_id
                    WHERE linked_scope.organization_id=?
                      AND linked_scope.workforce_member_id=?
                      AND linked_scope.status IN ('approved','active')
                )
                SELECT membership.id,membership.user_id,membership.role_key,
                       membership.lock_version
                FROM organization_memberships membership
                JOIN target_users target ON target.user_id=membership.user_id
                JOIN authorization_roles role ON role.role_key=membership.role_key
                WHERE membership.organization_id=? AND membership.status='active'
                  AND NOT role.final_owner
                ORDER BY membership.id
                FOR UPDATE OF membership
                """,
                (resultSet,rowNumber) -> new OffboardingMembership(
                        resultSet.getObject("id",UUID.class),
                        resultSet.getObject("user_id",UUID.class),
                        resultSet.getString("role_key"),
                        resultSet.getLong("lock_version")),
                context.organizationId(),request.memberId(),context.organizationId());
        var evidence=new ArrayList<MutationEvidence>();
        var affectedUsers=new java.util.LinkedHashSet<UUID>();
        for (var membership : memberships) {
            var changed=jdbc.update(
                    """
                    UPDATE organization_memberships
                    SET status='revoked',
                        effective_to=CASE WHEN effective_from>=?
                            THEN effective_from+interval '1 microsecond' ELSE ? END,
                        lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND status='active' AND lock_version=?
                    """,
                    Timestamp.from(now),Timestamp.from(now),request.checkerId(),
                    context.organizationId(),membership.id(),membership.revision());
            requireChanged(changed,"Canonical membership changed before offboarding revocation.");
            affectedUsers.add(membership.userId());
            var payload=ordered(
                    "approvalId",request.id(),
                    "changeType","revoke",
                    "fromRole",membership.roleKey(),
                    "lockVersion",membership.revision()+1,
                    "membershipId",membership.id(),
                    "toRole",null);
            evidence.add(new MutationEvidence(
                    membership.id(),"membership","identity.membership.revoked",
                    "identity.membership.revoked","membership",payload,payload));
        }
        for (var userId : affectedUsers) {
            jdbc.update(
                    """
                    UPDATE user_sessions
                    SET revoked_at=?,revocation_reason='workforce_offboarding'
                    WHERE user_id=? AND revoked_at IS NULL
                    """,
                    Timestamp.from(now),userId);
        }
        if (offboardingAccessImpact(context,request.memberId()).activeMemberships()>0) {
            throw conflict("Canonical organization access remained active after revocation.");
        }
        return List.copyOf(evidence);
    }

    private MutationResult escalateExpiry(
            AuthorizedTenantContext context, MutationCommand command) {
        var credentialId = requireTarget(command);
        var source = requireExpirySource(context,credentialId);
        var milestone = required(command, "milestone");
        if (!Set.of("90", "60", "30", "7", "0", "expired").contains(milestone)) {
            throw invalid("The expiry milestone is not supported.");
        }
        var evaluationDate=LocalDate.ofInstant(command.now(),ZoneOffset.UTC);
        var daysUntilExpiry=source.expiresOn().toEpochDay()-evaluationDate.toEpochDay();
        var currentMilestone=daysUntilExpiry<0?"expired"
                :daysUntilExpiry==90?"90"
                :daysUntilExpiry==60?"60"
                :daysUntilExpiry==30?"30"
                :daysUntilExpiry==7?"7"
                :daysUntilExpiry==0?"0":null;
        if (!Objects.equals(milestone,currentMilestone)) {
            throw conflict("The requested milestone does not match the source expiry date.");
        }
        var target = jdbc.query(
                """
                SELECT entry.id AS template_entry_id,version.id AS template_version_id,
                       (SELECT membership.user_id
                          FROM access_assignment_scopes scope
                          JOIN organization_memberships membership
                            ON membership.organization_id=scope.organization_id
                           AND membership.id=scope.access_assignment_id
                         WHERE scope.organization_id=definition.organization_id
                           AND scope.workforce_member_id=?
                           AND scope.status='active' AND membership.status='active'
                           AND scope.effective_from<=?
                           AND (scope.effective_to IS NULL OR scope.effective_to>?)
                           AND membership.effective_from<=?
                           AND (membership.effective_to IS NULL OR membership.effective_to>?)
                         ORDER BY scope.effective_from DESC,scope.id DESC LIMIT 1) AS recipient_id
                FROM workforce_registry_definitions definition
                JOIN workforce_registry_entries entry
                  ON entry.organization_id=definition.organization_id
                 AND entry.registry_definition_id=definition.id
                JOIN workforce_registry_versions version
                  ON version.organization_id=entry.organization_id
                 AND version.registry_entry_id=entry.id
                WHERE definition.organization_id=?
                  AND definition.category='notification_template_metadata'
                  AND definition.status='active' AND entry.status='active' AND version.status='active'
                  AND version.effective_from<=?
                  AND (version.effective_to IS NULL OR version.effective_to>?)
                  AND coalesce((version.version_fields->>'enabled')::boolean,false)
                ORDER BY version.effective_from DESC,version.version_number DESC,version.id DESC LIMIT 1
                """,
                resultSet -> resultSet.next()
                        ? new NotificationTarget(
                                resultSet.getObject("template_entry_id", UUID.class),
                                resultSet.getObject("template_version_id", UUID.class),
                                resultSet.getObject("recipient_id", UUID.class))
                        : null,
                source.memberId(),
                Timestamp.from(command.now()),
                Timestamp.from(command.now()),
                Timestamp.from(command.now()),
                Timestamp.from(command.now()),
                context.organizationId(),
                Timestamp.from(command.now()),
                Timestamp.from(command.now()));
        if (target == null) {
            throw conflict("An active expiry notification template is required.");
        }
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",
                resultSet -> {
                    resultSet.next();
                    return Boolean.TRUE;
                },
                "workforce-expiry-notification:"+context.organizationId()+":"
                        +source.sourceType()+":"+credentialId+":"+milestone+":"
                        +target.templateVersionId());
        var notificationId = UuidV7Generator.randomUuid();
        var planned = target.recipientId() != null;
        var existingAttempt = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1 FROM workforce_notification_deliveries
                    WHERE organization_id=?
                      AND ((?='credential' AND practitioner_credential_id=?)
                        OR (?='registration' AND professional_registration_id=?))
                      AND milestone=? AND template_version_id=?)
                """,
                Boolean.class,
                context.organizationId(),source.sourceType(),credentialId,
                source.sourceType(),credentialId,milestone,target.templateVersionId()));
        if (existingAttempt) {
            throw conflict("This expiry milestone already has notification evidence.");
        }
        jdbc.update(
                """
                INSERT INTO workforce_notification_deliveries(
                    id,organization_id,workforce_member_id,practitioner_credential_id,
                    professional_registration_id,template_entry_id,template_version_id,milestone,channel,
                    recipient_opaque_reference,purpose_key,attempt_number,failure_code,status,
                    created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                notificationId,
                context.organizationId(),
                source.memberId(),
                source.sourceType().equals("credential") ? credentialId : null,
                source.sourceType().equals("registration") ? credentialId : null,
                target.templateEntryId(),
                target.templateVersionId(),
                milestone,
                "email",
                planned ? target.recipientId() : source.memberId(),
                "credential_expiry_escalation",
                1,
                planned ? null : "recipient_unavailable",
                planned ? "planned" : "suppressed",
                context.actorId(),
                context.actorId());
        var payload = ordered(
                "credentialId", credentialId,
                "milestone", milestone,
                "expiryDate", source.expiresOn(),
                "notificationId", notificationId,
                "outcomeCode", planned
                        ? "manual_escalation_planned"
                        : "manual_escalation_suppressed");
        return result(
                credentialId,
                "practitioner_credential",
                "credential.expiry.escalated",
                null,
                null,
                payload,
                Map.of(),
                200,
                source.revision());
    }

    private MutationResult requestExport(
            AuthorizedTenantContext context, MutationCommand command) {
        var exportId = UuidV7Generator.randomUuid();
        var projection = required(command, "projection");
        var format = required(command, "format");
        var purpose = required(command, "purposeKey");
        var legalBasis = required(command, "legalBasisKey");
        if (!legalBasis.matches("[a-z][a-z0-9._:-]{1,79}")) {
            throw invalid("legalBasisKey must be an approved registry key.");
        }
        if (!Set.of(
                        "workforce_operations",
                        "credentialing_review",
                        "regulatory_evidence",
                        "security_investigation",
                        "employment_record_request",
                        "data_correction")
                .contains(purpose)) {
            throw invalid("The export purpose is not supported.");
        }
        if (!Set.of("csv", "jsonl").contains(format)) {
            throw invalid("The export format is not supported.");
        }
        var restricted = Set.of(
                        "credential-decision-detail-v1",
                        "scope-decision-detail-v1",
                        "workforce-audit-detail-v1",
                        "member-evidence-detail-v1")
                .contains(projection);
        if (!Set.of(
                        "workforce-directory-summary-v1",
                        "credential-expiry-summary-v1",
                        "workforce-configuration-summary-v1",
                        "workforce-audit-summary-v1",
                        "member-timeline-summary-v1",
                        "credential-decision-detail-v1",
                        "scope-decision-detail-v1",
                        "workforce-audit-detail-v1",
                        "member-evidence-detail-v1")
                .contains(projection)) {
            throw invalid("The workforce export projection is not supported.");
        }
        var allowedScreens=switch (projection) {
            case "workforce-directory-summary-v1" -> Set.of("M2-02");
            case "credential-expiry-summary-v1" -> Set.of("M2-25");
            case "workforce-configuration-summary-v1" -> Set.of("M2-26");
            case "workforce-audit-summary-v1","workforce-audit-detail-v1" -> Set.of("M2-27");
            case "member-timeline-summary-v1","member-evidence-detail-v1" -> Set.of("M2-29");
            case "credential-decision-detail-v1" -> Set.of("M2-12");
            case "scope-decision-detail-v1" -> Set.of("M2-14");
            default -> Set.<String>of();
        };
        if (!allowedScreens.contains(command.screenId())) {
            throw invalid("The export projection is not available from this source screen.");
        }
        requireExportSourcePermissions(context,projection);
        if (Set.of(
                                "member-timeline-summary-v1",
                                "member-evidence-detail-v1",
                                "credential-decision-detail-v1",
                                "scope-decision-detail-v1")
                        .contains(projection)
                && command.memberId() == null) {
            throw invalid("This export projection requires one authorized workforce member.");
        }
        if (command.memberId()!=null) {
            requireMember(context,command.memberId());
        }
        var filterSearch = optional(command, "filterSearch");
        if (filterSearch != null) {
            filterSearch = Normalizer.normalize(filterSearch, Normalizer.Form.NFC).strip();
            var length = filterSearch.codePointCount(0, filterSearch.length());
            if (length < 2 || length > 100
                    || filterSearch.codePoints().anyMatch(Character::isISOControl)) {
                throw invalid("Export search filters must contain 2 to 100 safe characters.");
            }
        }
        var filterStatus = optional(command, "filterStatus");
        if (filterStatus != null
                && (filterStatus.length() > 120
                        || !filterStatus.matches(
                                "[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*"))) {
            throw invalid("Export status filters must be stable lower-case state or event keys.");
        }
        var filterDigest = digest(String.join(
                "|",
                command.screenId(),
                Objects.toString(command.memberId(), "all"),
                Objects.toString(filterSearch, ""),
                Objects.toString(filterStatus, "")));
        var sortDigest = digest("effective_desc|stable_id");
        var rowLimit = restricted ? 25_000 : 100_000;
        var sizeLimit = restricted ? 104_857_600L : 262_144_000L;
        var initialStatus = restricted ? "requested" : "authorized";
        jdbc.update(
                """
                INSERT INTO workforce_export_jobs(
                    id,organization_id,requester_id,purpose_key,legal_basis_key,projection,
                    filters_digest,sort_digest,snapshot_at,format,row_limit,size_limit_bytes,
                    policy_version,status,filters_json,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'm2-export-v1',?,
                        jsonb_build_object(
                            'screenId',?::text,'memberId',?::text,
                            'search',?::text,'status',?::text),?,?)
                """,
                exportId,
                context.organizationId(),
                context.actorId(),
                purpose,
                legalBasis,
                projection,
                filterDigest,
                sortDigest,
                Timestamp.from(command.now()),
                format,
                rowLimit,
                sizeLimit,
                initialStatus,
                command.screenId(),
                command.memberId() == null ? null : command.memberId().toString(),
                filterSearch,
                filterStatus,
                context.actorId(),
                context.actorId());
        var payload = ordered(
                "exportId", exportId,
                "projection", projection,
                "format", format,
                "filterDigest", filterDigest,
                "purposeCode", purpose,
                "approvalId", null);
        var worker = ordered(
                "exportId", exportId,
                "projection", projection,
                "format", format,
                "filterDigest", filterDigest,
                "purposeCode", purpose);
        return result(
                exportId,
                "workforce_export",
                "workforce.export.requested",
                restricted ? null : "workforce.export.authorized",
                restricted ? null : "workforce_export",
                payload,
                restricted ? Map.of() : worker,
                201,
                0);
    }

    private MutationResult retryExport(
            AuthorizedTenantContext context, MutationCommand command) {
        var failedExportId = requireTarget(command);
        var revision = requireRevision(command);
        var source = jdbc.query(
                """
                SELECT export_job.id,export_job.requester_id,export_job.purpose_key,
                       export_job.legal_basis_key,export_job.projection,
                       export_job.filters_digest,export_job.sort_digest,export_job.format,
                       export_job.row_limit,export_job.size_limit_bytes,
                       export_job.policy_version,export_job.filters_json::text,
                       (export_job.filters_json->>'memberId')::uuid AS member_id,
                       export_job.status,export_job.failure_code,
                       export_job.lock_version,export_job.updated_at
                FROM workforce_export_jobs export_job
                WHERE export_job.organization_id=? AND export_job.id=?
                FOR UPDATE
                """,
                resultSet -> resultSet.next()
                        ? new ExportRetrySource(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("requester_id", UUID.class),
                                resultSet.getString("purpose_key"),
                                resultSet.getString("legal_basis_key"),
                                resultSet.getString("projection"),
                                resultSet.getString("filters_digest"),
                                resultSet.getString("sort_digest"),
                                resultSet.getString("format"),
                                resultSet.getInt("row_limit"),
                                resultSet.getLong("size_limit_bytes"),
                                resultSet.getString("policy_version"),
                                resultSet.getString("filters_json"),
                                resultSet.getObject("member_id", UUID.class),
                                resultSet.getString("status"),
                                resultSet.getString("failure_code"),
                                resultSet.getLong("lock_version"),
                                resultSet.getTimestamp("updated_at").toInstant())
                        : null,
                context.organizationId(),
                failedExportId);
        if (source == null) {
            throw notFound("The failed workforce export was not found.");
        }
        if (!source.status().equals("failed") || source.revision() != revision
                || "authorization_denied".equals(source.failureCode())
                || !source.requesterId().equals(context.actorId())) {
            throw conflict("Only the requester may retry this terminal failed export revision.");
        }
        var visibleFromScreen = switch (command.screenId()) {
            case "M2-26" -> source.projection().equals("workforce-configuration-summary-v1");
            case "M2-27" -> true;
            case "M2-29" -> Set.of(
                            "member-timeline-summary-v1", "member-evidence-detail-v1")
                    .contains(source.projection());
            default -> false;
        };
        if (!visibleFromScreen) {
            throw invalid("The failed export is not available from this recovery screen.");
        }
        requireExportSourcePermissions(context, source.projection());
        if (source.memberId() != null) {
            requireMember(context, source.memberId());
        }
        if (exists(
                """
                SELECT EXISTS(SELECT 1 FROM workforce_export_jobs retry
                WHERE retry.organization_id=? AND retry.retry_of_export_id=?)
                """,
                context.organizationId(), failedExportId)) {
            throw conflict("This failed export already has a linked retry job.");
        }
        var lineageDepth = jdbc.queryForObject(
                """
                WITH RECURSIVE lineage AS (
                    SELECT id,retry_of_export_id,1 AS depth
                    FROM workforce_export_jobs
                    WHERE organization_id=? AND id=?
                    UNION ALL
                    SELECT parent.id,parent.retry_of_export_id,lineage.depth+1
                    FROM workforce_export_jobs parent
                    JOIN lineage ON parent.id=lineage.retry_of_export_id
                    WHERE parent.organization_id=? AND lineage.depth<6
                )
                SELECT COALESCE(max(depth),0) FROM lineage
                """,
                Integer.class,
                context.organizationId(), failedExportId, context.organizationId());
        if (lineageDepth == null || lineageDepth < 1) {
            throw conflict("The export retry lineage could not be verified.");
        }
        if (lineageDepth >= 5) {
            throw conflict("This export retry lineage has reached its five-attempt limit.");
        }
        if (exists(
                """
                SELECT EXISTS(SELECT 1 FROM workforce_export_jobs active_job
                WHERE active_job.organization_id=? AND active_job.requester_id=?
                  AND active_job.projection=?
                  AND active_job.status IN ('requested','authorized','running','ready'))
                """,
                context.organizationId(), context.actorId(), source.projection())) {
            throw conflict("An active export already exists for this requester and projection.");
        }
        var exportId = UuidV7Generator.randomUuid();
        var initialStatus = source.restricted() ? "requested" : "authorized";
        var nextAttemptAt = source.failedAt().plusSeconds(60L << (lineageDepth - 1));
        jdbc.update(
                """
                INSERT INTO workforce_export_jobs(
                    id,organization_id,requester_id,purpose_key,legal_basis_key,projection,
                    filters_digest,sort_digest,snapshot_at,format,row_limit,size_limit_bytes,
                    policy_version,status,filters_json,retry_of_export_id,next_attempt_at,
                    created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, ?,?::jsonb,?,?,?,?)
                """,
                exportId,
                context.organizationId(),
                context.actorId(),
                source.purposeKey(),
                source.legalBasisKey(),
                source.projection(),
                source.filterDigest(),
                source.sortDigest(),
                Timestamp.from(command.now()),
                source.format(),
                source.rowLimit(),
                source.sizeLimitBytes(),
                source.policyVersion(),
                initialStatus,
                source.filtersJson(),
                failedExportId,
                Timestamp.from(nextAttemptAt),
                context.actorId(),
                context.actorId());
        var payload = ordered(
                "exportId", exportId,
                "projection", source.projection(),
                "format", source.format(),
                "filterDigest", source.filterDigest(),
                "purposeCode", source.purposeKey(),
                "approvalId", null);
        var worker = ordered(
                "exportId", exportId,
                "projection", source.projection(),
                "format", source.format(),
                "filterDigest", source.filterDigest(),
                "purposeCode", source.purposeKey());
        return result(
                exportId,
                "workforce_export",
                "workforce.export.requested",
                source.restricted() ? null : "workforce.export.authorized",
                source.restricted() ? null : "workforce_export",
                payload,
                source.restricted() ? Map.of() : worker,
                201,
                0);
    }

    private MutationResult decideExport(
            AuthorizedTenantContext context, MutationCommand command, boolean authorize) {
        var exportId = requireTarget(command);
        var revision = requireRevision(command);
        var export = requireExport(context, exportId);
        if (export.revision() != revision
                || !export.status().equals("requested")
                || export.requesterId().equals(context.actorId())
                || !export.restricted()) {
            throw conflict("The restricted export cannot be decided by this actor or revision.");
        }
        var approvalId = UuidV7Generator.randomUuid();
        var changed = jdbc.update(
                """
                UPDATE workforce_export_jobs
                SET status=?,approval_reference_id=?,failure_code=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='requested'
                  AND requester_id<>? AND lock_version=?
                """,
                authorize ? "authorized" : "failed",
                approvalId,
                authorize ? null : "authorization_denied",
                context.actorId(),
                context.organizationId(),
                exportId,
                context.actorId(),
                revision);
        requireChanged(changed, "The restricted export decision is stale.");
        var audit = ordered(
                "exportId", exportId,
                "projection", export.projection(),
                "format", export.format(),
                "filterDigest", export.filterDigest(),
                "purposeCode", export.purposeKey(),
                "approvalId", approvalId);
        var worker = ordered(
                "exportId", exportId,
                "projection", export.projection(),
                "format", export.format(),
                "filterDigest", export.filterDigest(),
                "purposeCode", export.purposeKey());
        return result(
                exportId,
                "workforce_export",
                authorize ? "workforce.export.authorized" : "workforce.export.denied",
                authorize ? "workforce.export.authorized" : null,
                authorize ? "workforce_export" : null,
                audit,
                authorize ? worker : Map.of(),
                200,
                revision + 1);
    }

    private void requireExportSourcePermissions(
            AuthorizedTenantContext context, String projection) {
        var required=switch (projection) {
            case "workforce-directory-summary-v1" -> Set.of("workforce.directory.read");
            case "credential-expiry-summary-v1" -> Set.of("workforce.expiry.read");
            case "workforce-configuration-summary-v1" -> Set.of("workforce.history.read");
            case "workforce-audit-summary-v1","workforce-audit-detail-v1" ->
                    Set.of("workforce.audit.read");
            case "member-timeline-summary-v1" -> Set.of("workforce.timeline.read");
            case "member-evidence-detail-v1" -> Set.of(
                    "workforce.timeline.read","workforce.audit.read",
                    "credential.qualification.read","credential.registration.read",
                    "credential.record.read","practitioner.scope.read",
                    "workforce.assignment.read","practitioner.service_assignment.read");
            case "credential-decision-detail-v1" ->
                    Set.of("credential.record.read","credential.document.read");
            case "scope-decision-detail-v1" -> Set.of("practitioner.scope.read");
            default -> Set.<String>of();
        };
        if (required.isEmpty() || !permissions(context).containsAll(required)) {
            throw notFound("The requested export projection is unavailable.");
        }
    }

    private MutationResult createRegistryChange(
            AuthorizedTenantContext context, MutationCommand command) {
        var entryId = UuidV7Generator.randomUuid();
        var versionId = UuidV7Generator.randomUuid();
        var requestId = UuidV7Generator.randomUuid();
        var itemId = UuidV7Generator.randomUuid();
        var registryKey = required(command, "registryKey");
        var entryKey = required(command, "entryKey");
        var versionFields = "{\"enabled\":true}";
        var versionDigest = digest(versionFields);
        var requestedCategory = required(command, "category");
        var definitionId = jdbc.query(
                """
                SELECT id FROM workforce_registry_definitions
                WHERE organization_id=? AND registry_key=? AND category=?
                  AND lifecycle_state<>'retired'
                """,
                resultSet -> resultSet.next() ? resultSet.getObject(1, UUID.class) : null,
                context.organizationId(),
                registryKey,
                requestedCategory);
        if (definitionId == null) {
            definitionId = UuidV7Generator.randomUuid();
            jdbc.update(
                    """
                    INSERT INTO workforce_registry_definitions(
                        id,organization_id,registry_key,category,display_name,value_schema,
                        review_cadence_days,created_by,updated_by)
                    VALUES (?,?,?,?,?,
                        '{"type":"object","properties":{"enabled":{"type":"boolean"}},"required":["enabled"],"additionalProperties":false}'::jsonb,
                        365,?,?)
                    """,
                    definitionId,
                    context.organizationId(),
                    registryKey,
                    requestedCategory,
                    required(command, "displayName"),
                    context.actorId(),
                    context.actorId());
        }
        var parentSnapshot = jdbc.query(
                """
                SELECT id FROM workforce_configuration_snapshots
                WHERE organization_id=? AND status='active' AND superseded_at IS NULL
                ORDER BY effective_at DESC,id DESC LIMIT 1
                """,
                resultSet -> resultSet.next() ? resultSet.getObject(1, UUID.class) : null,
                context.organizationId());
        jdbc.update(
                """
                INSERT INTO workforce_registry_entries(
                    id,organization_id,registry_definition_id,entry_key,code,display_label,
                    created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                entryId,
                context.organizationId(),
                definitionId,
                entryKey,
                required(command, "code").toUpperCase(),
                required(command, "entryLabel"),
                context.actorId(),
                context.actorId());
        jdbc.update(
                """
                INSERT INTO workforce_registry_versions(
                    id,organization_id,registry_entry_id,version_number,version_fields,
                    version_digest,effective_from,maker_id,lifecycle_state,status,created_by,updated_by)
                VALUES (?,?,?,1,?::jsonb,?,?,?,'draft','draft',?,?)
                """,
                versionId,
                context.organizationId(),
                entryId,
                versionFields,
                versionDigest,
                Timestamp.from(command.now()),
                context.actorId(),
                context.actorId(),
                context.actorId());
        jdbc.update(
                """
                INSERT INTO workforce_configuration_change_requests(
                    id,organization_id,parent_snapshot_id,summary,reason_code,maker_id,
                    requested_effective_at,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,'draft',?,?)
                """,
                requestId,
                context.organizationId(),
                parentSnapshot,
                "Create " + required(command, "displayName") + " registry entry",
                "registry_change",
                context.actorId(),
                Timestamp.from(command.now()),
                context.actorId(),
                context.actorId());
        jdbc.update(
                """
                INSERT INTO workforce_configuration_change_items(
                    id,organization_id,change_request_id,item_order,target_type,target_id,
                    new_revision,new_digest,change_type,changed_fields,created_by,updated_by)
                VALUES (?,?,?,1,'registry_version',?,1,?,'added',?::varchar[],?,?)
                """,
                itemId,
                context.organizationId(),
                requestId,
                versionId,
                versionDigest,
                new String[] {"version_fields"},
                context.actorId(),
                context.actorId());
        var payload = ordered(
                "definitionId", definitionId,
                "entryId", entryId,
                "versionId", versionId,
                "changeRequestId", requestId,
                "fromState", "none",
                "toState", "draft",
                "resultDigest", versionDigest);
        return result(
                versionId,
                "workforce_registry_version",
                "workforce.registry.created",
                null,
                null,
                payload,
                Map.of(),
                201,
                0);
    }

    private MutationResult createRegistryVersionChange(
            AuthorizedTenantContext context, MutationCommand command) {
        var predecessorId = requireTarget(command);
        var expectedRevision = requireRevision(command);
        var predecessor = requireRegistryVersion(context, predecessorId);
        if (predecessor.revision() != expectedRevision || !predecessor.status().equals("active")) {
            throw stale("The selected active registry version changed.");
        }
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",
                resultSet -> {
                    resultSet.next();
                    return Boolean.TRUE;
                },
                "workforce-registry-entry:" + context.organizationId() + ":" + predecessor.entryId());
        var entry = jdbc.query(
                """
                SELECT entry.display_label,entry.lifecycle_state,definition.display_name,
                       definition.category,
                       coalesce((version.version_fields->>'enabled')::boolean,false) AS enabled
                FROM workforce_registry_versions version
                JOIN workforce_registry_entries entry
                  ON entry.organization_id=version.organization_id
                 AND entry.id=version.registry_entry_id
                JOIN workforce_registry_definitions definition
                  ON definition.organization_id=entry.organization_id
                 AND definition.id=entry.registry_definition_id
                WHERE version.organization_id=? AND version.id=?
                  AND version.status='active' AND version.superseded_at IS NULL
                  AND entry.lifecycle_state IN ('active','retired')
                  AND definition.lifecycle_state='active'
                """,
                resultSet -> resultSet.next()
                        ? new RegistryEntryContext(
                                resultSet.getString("display_label"),
                                resultSet.getString("lifecycle_state"),
                                resultSet.getString("display_name"),
                                resultSet.getString("category"),
                                resultSet.getBoolean("enabled"))
                        : null,
                context.organizationId(),
                predecessorId);
        if (entry == null) {
            throw conflict("The selected version is not the current registry baseline.");
        }
        var enabled = bool(command, "enabled");
        if (enabled == entry.enabled()) {
            throw invalid("A successor registry version must change the enabled state.");
        }
        if (exists(
                """
                SELECT EXISTS(SELECT 1 FROM workforce_registry_versions version
                WHERE version.organization_id=? AND version.registry_entry_id=?
                  AND version.status IN ('draft','submitted','approved'))
                """,
                context.organizationId(), predecessor.entryId())) {
            throw conflict("The registry entry already has an open successor version.");
        }
        var versionNumber = Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT coalesce(max(version_number),0)+1
                FROM workforce_registry_versions
                WHERE organization_id=? AND registry_entry_id=?
                """,
                Integer.class,
                context.organizationId(),
                predecessor.entryId()));
        var versionId = UuidV7Generator.randomUuid();
        var requestId = UuidV7Generator.randomUuid();
        var itemId = UuidV7Generator.randomUuid();
        var versionFields = enabled ? "{\"enabled\":true}" : "{\"enabled\":false}";
        var versionDigest = digest(versionFields);
        var parentSnapshot = jdbc.query(
                """
                SELECT id FROM workforce_configuration_snapshots
                WHERE organization_id=? AND status='active' AND superseded_at IS NULL
                ORDER BY effective_at DESC,id DESC LIMIT 1
                """,
                resultSet -> resultSet.next() ? resultSet.getObject(1, UUID.class) : null,
                context.organizationId());
        jdbc.update(
                """
                INSERT INTO workforce_registry_versions(
                    id,organization_id,registry_entry_id,version_number,version_fields,
                    version_digest,effective_from,maker_id,lifecycle_state,status,
                    created_by,updated_by)
                VALUES (?,?,?,?,?::jsonb,?,?,?,'draft','draft',?,?)
                """,
                versionId,
                context.organizationId(),
                predecessor.entryId(),
                versionNumber,
                versionFields,
                versionDigest,
                Timestamp.from(command.now()),
                context.actorId(),
                context.actorId(),
                context.actorId());
        jdbc.update(
                """
                INSERT INTO workforce_configuration_change_requests(
                    id,organization_id,parent_snapshot_id,summary,reason_code,maker_id,
                    requested_effective_at,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,'draft',?,?)
                """,
                requestId,
                context.organizationId(),
                parentSnapshot,
                (enabled ? "Re-enable " : "Retire ") + entry.definitionLabel()
                        + " entry " + entry.entryLabel(),
                enabled ? "registry_entry_reenabled" : "registry_entry_retired",
                context.actorId(),
                Timestamp.from(command.now()),
                context.actorId(),
                context.actorId());
        jdbc.update(
                """
                INSERT INTO workforce_configuration_change_items(
                    id,organization_id,change_request_id,item_order,target_type,target_id,
                    baseline_revision,new_revision,baseline_digest,new_digest,change_type,
                    changed_fields,created_by,updated_by)
                VALUES (?,?,?,1,'registry_version',?,?,?,?,?,?,?::varchar[],?,?)
                """,
                itemId,
                context.organizationId(),
                requestId,
                versionId,
                predecessor.versionNumber(),
                versionNumber,
                predecessor.digest(),
                versionDigest,
                enabled ? "changed" : "removed",
                new String[] {"version_fields.enabled"},
                context.actorId(),
                context.actorId());
        var payload = ordered(
                "definitionId", predecessor.definitionId(),
                "entryId", predecessor.entryId(),
                "versionId", versionId,
                "changeRequestId", requestId,
                "fromState", "active",
                "toState", "draft",
                "resultDigest", versionDigest);
        return result(
                versionId,
                "workforce_registry_version",
                "workforce.registry.created",
                null,
                null,
                payload,
                Map.of(),
                201,
                0);
    }

    private MutationResult submitRegistryChange(
            AuthorizedTenantContext context, MutationCommand command) {
        var draftVersionId=requireTarget(command);
        var expectedRevision=requireRevision(command);
        var version=requireRegistryVersion(context,draftVersionId);
        if (version.revision()!=expectedRevision || !version.status().equals("draft")
                || !version.makerId().equals(context.actorId())) {
            throw conflict("Only the maker can submit the current registry draft.");
        }
        var change=findRegistryChange(context,draftVersionId);
        if (!change.requestStatus().equals("draft") || !change.itemStatus().equals("draft")) {
            throw conflict("The registry change request is not a current draft.");
        }
        if (!exists(
                """
                SELECT EXISTS(SELECT 1
                FROM workforce_registry_versions version
                JOIN workforce_registry_entries entry
                  ON entry.organization_id=version.organization_id
                 AND entry.id=version.registry_entry_id
                JOIN workforce_registry_definitions definition
                  ON definition.organization_id=entry.organization_id
                 AND definition.id=entry.registry_definition_id
                JOIN workforce_configuration_change_items item
                  ON item.organization_id=version.organization_id
                 AND item.target_id=version.id
                JOIN workforce_configuration_change_requests request
                  ON request.organization_id=item.organization_id
                 AND request.id=item.change_request_id
                WHERE version.organization_id=? AND version.id=?
                  AND version.status='draft' AND entry.status IN ('draft','active','retired')
                  AND definition.status IN ('draft','active')
                  AND request.id=? AND request.status='draft' AND request.maker_id=?
                  AND item.id=? AND item.status='draft'
                  AND jsonb_typeof(version.version_fields->'enabled')='boolean'
                  AND jsonb_object_length(version.version_fields)=1
                  AND ((request.parent_snapshot_id IS NULL AND NOT EXISTS(
                        SELECT 1 FROM workforce_configuration_snapshots active
                        WHERE active.organization_id=request.organization_id
                          AND active.status='active' AND active.superseded_at IS NULL))
                    OR request.parent_snapshot_id=(
                        SELECT active.id FROM workforce_configuration_snapshots active
                        WHERE active.organization_id=request.organization_id
                          AND active.status='active' AND active.superseded_at IS NULL
                        ORDER BY active.effective_at DESC,active.id DESC LIMIT 1)))
                """,
                context.organizationId(),draftVersionId,change.requestId(),context.actorId(),
                change.itemId())) {
            throw conflict("The registry draft failed schema, ownership, or baseline validation.");
        }
        var validationDigest=registryValidationDigest(context,version,change,command.now());
        var versionChanged=jdbc.update(
                """
                UPDATE workforce_registry_versions
                SET lifecycle_state='submitted',status='submitted',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='draft' AND lock_version=?
                  AND maker_id=?
                """,
                context.actorId(),context.organizationId(),draftVersionId,expectedRevision,
                context.actorId());
        requireChanged(versionChanged,"The registry draft changed before submission.");
        var requestChanged=jdbc.update(
                """
                UPDATE workforce_configuration_change_requests
                SET validation_digest=?,validation_expires_at=?,status='submitted',
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='draft' AND maker_id=?
                """,
                validationDigest,Timestamp.from(command.now().plusSeconds(900)),context.actorId(),
                context.organizationId(),change.requestId(),context.actorId());
        requireChanged(requestChanged,"The registry change request changed before submission.");
        var itemChanged=jdbc.update(
                """
                UPDATE workforce_configuration_change_items
                SET status='submitted',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='draft'
                """,
                context.actorId(),context.organizationId(),change.itemId());
        requireChanged(itemChanged,"The registry change item changed before submission.");
        var payload=ordered(
                "definitionId",version.definitionId(),"entryId",version.entryId(),
                "versionId",version.id(),"changeRequestId",change.requestId(),
                "fromState","draft","toState","submitted","resultDigest",validationDigest);
        return result(version.id(),"workforce_registry_version","workforce.registry.submitted",
                null,null,payload,Map.of(),200,expectedRevision+1);
    }

    private MutationResult decideRegistryChange(
            AuthorizedTenantContext context, MutationCommand command, boolean approved) {
        var submittedVersionId = requireTarget(command);
        var expectedRevision = requireRevision(command);
        var version = requireRegistryVersion(context, submittedVersionId);
        if (version.revision() != expectedRevision
                || !version.status().equals("submitted")
                || version.makerId().equals(context.actorId())) {
            throw conflict("The submitted registry change cannot be decided by this actor.");
        }
        var change = findRegistryChange(context, submittedVersionId);
        if (!change.requestStatus().equals("submitted")
                || !change.itemStatus().equals("submitted")
                || change.validationDigest()==null
                || change.validationExpiresAt()==null
                || !change.validationExpiresAt().isAfter(command.now())) {
            throw conflict("The registry validation is stale or no longer bound to this change.");
        }
        var validationDigest=approved
                ? registryValidationDigest(context,version,change,command.now())
                : change.validationDigest();
        if (approved && !validationDigest.equals(change.validationDigest())) {
            throw stale("The registry baseline or referenced-value impact changed after validation.");
        }
        var decidedState=approved?"approved":"rejected";
        var decisionDigest=digest("m2-registry-decision-v1|"+decidedState+"|"
                +validationDigest+"|"+version.digest());
        var versionChanged=jdbc.update(
                """
                UPDATE workforce_registry_versions
                SET checker_id=?,decision_code=?,lifecycle_state=?,status=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='submitted' AND lock_version=?
                  AND maker_id<>?
                """,
                context.actorId(),decidedState,decidedState,decidedState,context.actorId(),
                context.organizationId(),submittedVersionId,expectedRevision,context.actorId());
        requireChanged(versionChanged,"The registry version changed before the decision committed.");
        var requestChanged = jdbc.update(
                """
                UPDATE workforce_configuration_change_requests
                SET checker_id=?,decision_digest=?,decision_expires_at=?,status=?,
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='submitted' AND maker_id<>?
                """,
                context.actorId(),
                decisionDigest,
                approved?Timestamp.from(command.now().plusSeconds(1800)):null,
                decidedState,
                context.actorId(),
                context.organizationId(),
                change.requestId(),
                context.actorId());
        requireChanged(requestChanged, "The registry change request is stale or violates reviewer separation.");
        var itemChanged = jdbc.update(
                """
                UPDATE workforce_configuration_change_items
                SET status=?,lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='submitted'
                """,
                approved?"approved":"cancelled",
                context.actorId(),
                context.organizationId(),
                change.itemId());
        requireChanged(itemChanged, "The registry change item changed before approval committed.");
        var payload = ordered(
                "definitionId", version.definitionId(),
                "entryId", version.entryId(),
                "versionId", submittedVersionId,
                "changeRequestId", change.requestId(),
                "fromState", "submitted",
                "toState", decidedState,
                "resultDigest", decisionDigest);
        return result(
                submittedVersionId,
                "workforce_registry_version",
                "workforce.registry."+decidedState,
                null,
                null,
                payload,
                Map.of(),
                200,
                expectedRevision+1);
    }

    private MutationResult activateRegistryChange(
            AuthorizedTenantContext context, MutationCommand command) {
        var approvedVersionId = requireTarget(command);
        var expectedRevision = requireRevision(command);
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",
                resultSet -> {
                    resultSet.next();
                    return Boolean.TRUE;
                },
                "workforce-configuration:" + context.organizationId());
        var version = requireRegistryVersion(context, approvedVersionId);
        if (version.revision() != expectedRevision
                || !version.status().equals("approved")
                || version.makerId().equals(context.actorId())
                || Objects.equals(version.checkerId(), context.actorId())) {
            throw conflict("Registry activation requires a third independent actor.");
        }
        var change = findRegistryChange(context, approvedVersionId);
        if (!change.requestStatus().equals("approved")
                || !change.itemStatus().equals("approved")
                || change.validationDigest()==null
                || change.decisionDigest()==null
                || change.decisionExpiresAt()==null
                || !change.decisionExpiresAt().isAfter(command.now())) {
            throw conflict("The registry approval is stale or its decision digest changed.");
        }
        var validationDigest=registryValidationDigest(context,version,change,command.now());
        var expectedDecisionDigest=digest("m2-registry-decision-v1|approved|"
                +validationDigest+"|"+version.digest());
        if (!validationDigest.equals(change.validationDigest())
                || !expectedDecisionDigest.equals(change.decisionDigest())) {
            throw stale("The registry baseline or referenced-value impact changed after approval.");
        }
        var activeVersionId = version.id();
        var activeNumber = version.versionNumber();
        var previousVersion = jdbc.query(
                """
                SELECT id,version_digest,lock_version FROM workforce_registry_versions
                WHERE organization_id=? AND registry_entry_id=?
                  AND status='active' AND superseded_at IS NULL
                ORDER BY effective_from DESC,id DESC LIMIT 1
                FOR UPDATE
                """,
                resultSet -> resultSet.next()
                        ? new RegistryPredecessor(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("version_digest"),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),
                version.entryId());
        var previousVersionId = previousVersion == null ? null : previousVersion.id();
        if (previousVersion != null) {
            var superseded = jdbc.update(
                    """
                    UPDATE workforce_registry_versions
                    SET lifecycle_state='superseded',status='superseded',effective_to=?,
                        superseded_at=?,lock_version=lock_version+1,
                        updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND status='active'
                      AND superseded_at IS NULL AND effective_from<? AND lock_version=?
                    """,
                    Timestamp.from(command.now()),
                    Timestamp.from(command.now()),
                    context.actorId(),
                    context.organizationId(),
                    previousVersionId,
                    Timestamp.from(command.now()),
                    previousVersion.revision());
            requireChanged(superseded, "The current registry version changed before activation.");
        }
        var activated=jdbc.update(
                """
                UPDATE workforce_registry_versions
                SET effective_from=?,activated_at=?,lifecycle_state='active',status='active',
                    lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='approved' AND lock_version=?
                  AND maker_id<>? AND checker_id<>?
                """,
                Timestamp.from(command.now()),
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),approvedVersionId,expectedRevision,
                context.actorId(),context.actorId());
        requireChanged(activated,"The approved registry version changed before activation.");
        var entryEnabled = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT coalesce((version_fields->>'enabled')::boolean,false)
                FROM workforce_registry_versions WHERE organization_id=? AND id=?
                """,
                Boolean.class,
                context.organizationId(),
                approvedVersionId));
        jdbc.update(
                """
                UPDATE workforce_registry_entries
                SET lifecycle_state=CASE WHEN ?::boolean THEN 'active' ELSE 'retired' END,
                    status=CASE WHEN ?::boolean THEN 'active' ELSE 'retired' END,
                    lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=?
                  AND lifecycle_state<>CASE WHEN ?::boolean THEN 'active' ELSE 'retired' END
                """,
                entryEnabled,
                entryEnabled,
                context.actorId(),
                context.organizationId(),
                version.entryId(),
                entryEnabled);
        jdbc.update(
                """
                UPDATE workforce_registry_definitions
                SET lifecycle_state='active',status='active',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND lifecycle_state='draft'
                """,
                context.actorId(), context.organizationId(), version.definitionId());
        var requestChanged = jdbc.update(
                """
                UPDATE workforce_configuration_change_requests
                SET activator_id=?,status='active',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='approved'
                  AND maker_id<>? AND checker_id<>?
                """,
                context.actorId(),
                context.actorId(),
                context.organizationId(),
                change.requestId(),
                context.actorId(),
                context.actorId());
        requireChanged(requestChanged, "The registry approval is stale or violates actor separation.");
        var itemChanged = jdbc.update(
                """
                UPDATE workforce_configuration_change_items
                SET target_id=?,new_revision=?,status='activated',lock_version=lock_version+1,
                    updated_at=clock_timestamp(),updated_by=?
                WHERE organization_id=? AND id=? AND status='approved'
                """,
                activeVersionId,
                activeNumber,
                context.actorId(),
                context.organizationId(),
                change.itemId());
        requireChanged(itemChanged, "The registry change item changed before activation committed.");
        var parent = jdbc.query(
                """
                SELECT id,parent_snapshot_id,snapshot_digest,lock_version
                FROM workforce_configuration_snapshots
                WHERE organization_id=? AND status='active' AND superseded_at IS NULL
                ORDER BY effective_at DESC,id DESC LIMIT 1
                FOR UPDATE
                """,
                resultSet -> resultSet.next()
                        ? new ConfigurationPredecessor(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("parent_snapshot_id", UUID.class),
                                resultSet.getString("snapshot_digest"),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId());
        var parentId = parent == null ? null : parent.id();
        var snapshotId = UuidV7Generator.randomUuid();
        var snapshotDigest = digest(Objects.toString(parentId, "root")
                + "|"
                + activeVersionId
                + "|"
                + version.digest());
        var year = java.time.ZonedDateTime.ofInstant(command.now(), ZoneOffset.UTC).getYear();
        var sequence = Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT COALESCE(max(right(display_number,6)::integer),0)+1
                FROM workforce_configuration_snapshots
                WHERE organization_id=? AND display_number LIKE ?
                """,
                Integer.class,
                context.organizationId(),
                "WCFG-" + year + "-%"));
        if (sequence > 999_999) {
            throw conflict("The yearly workforce configuration display-number range is exhausted.");
        }
        var displayNumber = "WCFG-" + year + "-" + String.format("%06d", sequence);
        if (parent != null) {
            var superseded = jdbc.update(
                    """
                    UPDATE workforce_configuration_snapshots
                    SET status='superseded',superseded_at=?,lock_version=lock_version+1,
                        updated_at=clock_timestamp(),updated_by=?
                    WHERE organization_id=? AND id=? AND status='active'
                      AND superseded_at IS NULL AND effective_at<? AND lock_version=?
                    """,
                    Timestamp.from(command.now()),
                    context.actorId(),
                    context.organizationId(),
                    parentId,
                    Timestamp.from(command.now()),
                    parent.revision());
            requireChanged(superseded, "The active workforce configuration changed before activation.");
        }
        jdbc.update(
                """
                INSERT INTO workforce_configuration_snapshots(
                    id,organization_id,display_number,parent_snapshot_id,snapshot_digest,
                    policy_versions,maker_id,checker_id,activator_id,effective_at,
                    created_by,updated_by)
                VALUES (?,?,?,?,?,
                        '{"authorization":"m2-candidate-1","registry":"m2-candidate-1","readiness":"m2-readiness-v1","eligibility":"m2-eligibility-v1"}'::jsonb,
                        ?,?,?,?, ?,?)
                """,
                snapshotId,
                context.organizationId(),
                displayNumber,
                parentId,
                snapshotDigest,
                version.makerId(),
                version.checkerId(),
                context.actorId(),
                Timestamp.from(command.now()),
                context.actorId(),
                context.actorId());
        var audit = ordered(
                "definitionId", version.definitionId(),
                "entryId", version.entryId(),
                "versionId", activeVersionId,
                "changeRequestId", change.requestId(),
                "fromState", "approved",
                "toState", "active",
                "resultDigest", version.digest());
        var outbox = ordered(
                "definitionId", version.definitionId(),
                "entryId", version.entryId(),
                "versionId", activeVersionId,
                "previousVersionId", previousVersionId,
                "resultDigest", version.digest());
        var additionalEvidence = new ArrayList<MutationEvidence>();
        if (previousVersion != null) {
            additionalEvidence.add(new MutationEvidence(
                    previousVersion.id(),
                    "workforce_registry_version",
                    "workforce.registry.superseded",
                    null,
                    null,
                    ordered(
                            "definitionId", version.definitionId(),
                            "entryId", version.entryId(),
                            "versionId", previousVersion.id(),
                            "changeRequestId", change.requestId(),
                            "fromState", "active",
                            "toState", "superseded",
                            "resultDigest", previousVersion.digest()),
                    Map.of()));
        }
        if (parent != null) {
            var supersededSnapshotPayload = ordered(
                    "snapshotId", parent.id(),
                    "previousSnapshotId", parent.parentId(),
                    "changeRequestId", change.requestId(),
                    "resultDigest", parent.digest(),
                    "effectiveTime", command.now());
            additionalEvidence.add(new MutationEvidence(
                    parent.id(),
                    "workforce_configuration_snapshot",
                    "workforce.configuration.superseded",
                    "workforce.configuration.superseded",
                    "workforce_configuration_snapshot",
                    supersededSnapshotPayload,
                    supersededSnapshotPayload));
        }
        var activatedSnapshotPayload = ordered(
                "snapshotId", snapshotId,
                "previousSnapshotId", parentId,
                "changeRequestId", change.requestId(),
                "resultDigest", snapshotDigest,
                "effectiveTime", command.now());
        additionalEvidence.add(new MutationEvidence(
                snapshotId,
                "workforce_configuration_snapshot",
                "workforce.configuration.activated",
                "workforce.configuration.activated",
                "workforce_configuration_snapshot",
                activatedSnapshotPayload,
                activatedSnapshotPayload));
        return result(
                activeVersionId,
                "workforce_registry_version",
                "workforce.registry.activated",
                "workforce.registry.activated",
                "workforce_registry_version",
                audit,
                outbox,
                200,
                expectedRevision+1,
                additionalEvidence);
    }

    private Member requireMember(AuthorizedTenantContext context, UUID memberId) {
        var member = jdbc.query(
                """
                SELECT member.id,member.pathway,member.lifecycle_state,
                       member.account_access_intent,member.current_readiness_run_id,
                       member.lock_version
                FROM workforce_members member
                JOIN organization_person_links link
                  ON link.organization_id=member.organization_id
                 AND link.id=member.organization_person_link_id
                WHERE member.organization_id=? AND member.id=?
                  AND (link.relationship_status='active'
                       OR (link.relationship_status='candidate'
                           AND nullif(current_setting('app.current_operation_key',true),'')
                               ='workforce.person.match'))
                """,
                resultSet -> resultSet.next()
                        ? new Member(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("pathway"),
                                resultSet.getString("lifecycle_state"),
                                resultSet.getString("account_access_intent"),
                                resultSet.getObject("current_readiness_run_id", UUID.class),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),
                memberId);
        if (member == null || !canAccessMember(context, memberId)) {
            throw notFound("The workforce member was not found.");
        }
        return member;
    }

    private PersonMergeMember requirePersonMergeMember(
            AuthorizedTenantContext context, UUID memberId) {
        requireMember(context, memberId);
        var member = jdbc.query(
                """
                SELECT member.id,member.organization_person_link_id,link.person_id,
                       member.lifecycle_state,member.lock_version,
                       link.relationship_status,link.lock_version
                FROM workforce_members member
                JOIN organization_person_links link
                  ON link.organization_id=member.organization_id
                 AND link.id=member.organization_person_link_id
                WHERE member.organization_id=? AND member.id=?
                """,
                resultSet -> resultSet.next()
                        ? new PersonMergeMember(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("organization_person_link_id", UUID.class),
                                resultSet.getObject("person_id", UUID.class),
                                resultSet.getString("lifecycle_state"),
                                resultSet.getLong(5),
                                resultSet.getString("relationship_status"),
                                resultSet.getLong(7))
                        : null,
                context.organizationId(),
                memberId);
        if (member == null) {
            throw notFound("The workforce member identity link was not found.");
        }
        return member;
    }

    private PersonMergeRequest requirePersonMergeRequest(
            AuthorizedTenantContext context, UUID requestId) {
        var request = jdbc.query(
                """
                SELECT request.id,request.retained_link_id,request.discarded_link_id,
                       request.requested_by,request.candidate_evidence_digest,
                       request.impact_digest,request.decision_state,request.decision_by,
                       request.lock_version,retained_member.id AS retained_member_id,
                       discarded_member.id AS discarded_member_id
                FROM person_merge_requests request
                JOIN LATERAL (
                    SELECT member.id FROM workforce_members member
                    WHERE member.organization_id=request.organization_id
                      AND member.organization_person_link_id=request.retained_link_id
                    ORDER BY (member.lifecycle_state<>'offboarded') DESC,
                             member.updated_at DESC,member.id DESC LIMIT 1
                ) retained_member ON true
                JOIN LATERAL (
                    SELECT member.id FROM workforce_members member
                    WHERE member.organization_id=request.organization_id
                      AND member.organization_person_link_id=request.discarded_link_id
                    ORDER BY (member.lifecycle_state<>'offboarded') DESC,
                             member.updated_at DESC,member.id DESC LIMIT 1
                ) discarded_member ON true
                WHERE request.organization_id=? AND request.id=?
                FOR UPDATE OF request
                """,
                resultSet -> resultSet.next()
                        ? new PersonMergeRequest(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("retained_link_id", UUID.class),
                                resultSet.getObject("discarded_link_id", UUID.class),
                                resultSet.getObject("retained_member_id", UUID.class),
                                resultSet.getObject("discarded_member_id", UUID.class),
                                resultSet.getObject("requested_by", UUID.class),
                                resultSet.getString("candidate_evidence_digest"),
                                resultSet.getString("impact_digest"),
                                resultSet.getString("decision_state"),
                                resultSet.getObject("decision_by", UUID.class),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),
                requestId);
        if (request == null) {
            throw notFound("The person-link merge request was not found.");
        }
        requireMember(context, request.retainedMemberId());
        requireMember(context, request.discardedMemberId());
        return request;
    }

    private void lockPersonMergeLinks(
            AuthorizedTenantContext context, UUID firstLinkId, UUID secondLinkId) {
        var keys = java.util.stream.Stream.of(firstLinkId, secondLinkId)
                .map(UUID::toString)
                .sorted()
                .toList();
        for (var key : keys) {
            jdbc.query(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",
                    resultSet -> {
                        resultSet.next();
                        return Boolean.TRUE;
                    },
                    context.organizationId() + ":person-link:" + key);
        }
    }

    private String personMergeCandidateDigest(
            AuthorizedTenantContext context, UUID retainedLinkId, UUID discardedLinkId) {
        var evidence = jdbc.queryForMap(
                """
                SELECT retained.person_id AS retained_person_id,
                       retained.relationship_status AS retained_status,
                       retained.lock_version AS retained_revision,
                       discarded.person_id AS discarded_person_id,
                       discarded.relationship_status AS discarded_status,
                       discarded.lock_version AS discarded_revision,
                       COALESCE((SELECT string_agg(
                           key.organization_person_link_id::text||':'||key.key_type||':'||
                           key.hmac_key_version||':'||key.keyed_digest||':'||key.status||':'||
                           key.lock_version::text,'|' ORDER BY key.organization_person_link_id,
                           key.key_type,key.hmac_key_version,key.keyed_digest,key.id)
                         FROM person_match_keys key
                         WHERE key.organization_id=retained.organization_id
                           AND key.organization_person_link_id IN (retained.id,discarded.id)), '')
                           AS match_evidence
                FROM organization_person_links retained
                JOIN organization_person_links discarded
                  ON discarded.organization_id=retained.organization_id
                WHERE retained.organization_id=? AND retained.id=? AND discarded.id=?
                """,
                context.organizationId(), retainedLinkId, discardedLinkId);
        return digest(retainedLinkId
                + "|" + evidence.get("retained_person_id")
                + "|" + evidence.get("retained_status")
                + "|" + evidence.get("retained_revision")
                + "|" + discardedLinkId
                + "|" + evidence.get("discarded_person_id")
                + "|" + evidence.get("discarded_status")
                + "|" + evidence.get("discarded_revision")
                + "|" + evidence.get("match_evidence"));
    }

    private String personMergeImpactDigest(
            AuthorizedTenantContext context,
            UUID retainedLinkId,
            UUID discardedLinkId,
            String candidateDigest) {
        var impact = jdbc.queryForMap(
                """
                SELECT COALESCE((SELECT string_agg(
                           member.id::text||':'||member.lifecycle_state||':'||
                           member.lock_version::text,'|' ORDER BY member.id)
                         FROM workforce_members member
                         WHERE member.organization_id=?
                           AND member.organization_person_link_id IN (?,?)), '') AS members,
                       COALESCE((SELECT string_agg(
                           key.id::text||':'||key.organization_person_link_id::text||':'||
                           key.status||':'||key.lock_version::text,'|' ORDER BY key.id)
                         FROM person_match_keys key
                         WHERE key.organization_id=?
                           AND key.organization_person_link_id IN (?,?)), '') AS match_keys
                """,
                context.organizationId(), retainedLinkId, discardedLinkId,
                context.organizationId(), retainedLinkId, discardedLinkId);
        return digest(candidateDigest
                + "|" + impact.get("members")
                + "|" + impact.get("match_keys"));
    }

    private ImpactAnalysis personMergeRequestImpact(
            AuthorizedTenantContext context, MutationCommand command) {
        var retained = requirePersonMergeMember(context, requireTarget(command));
        var revision = requireRevision(command);
        if (retained.memberRevision() != revision) {
            throw stale("The retained member changed before the merge impact was reviewed.");
        }
        var discarded = requirePersonMergeMember(context, uuid(command, "discardedMemberId"));
        if (retained.memberId().equals(discarded.memberId())
                || retained.linkId().equals(discarded.linkId())) {
            throw invalid("The retained and discarded person links must be different.");
        }
        if (!retained.linkStatus().equals("active") || !discarded.linkStatus().equals("active")) {
            throw conflict("Only active organization person links may be consolidated.");
        }
        if (exists(
                """
                SELECT EXISTS(SELECT 1 FROM person_merge_requests request
                WHERE request.organization_id=?
                  AND request.decision_state IN ('draft','submitted','approved')
                  AND (request.retained_link_id IN (?,?)
                    OR request.discarded_link_id IN (?,?)))
                """,
                context.organizationId(), retained.linkId(), discarded.linkId(),
                retained.linkId(), discarded.linkId())) {
            throw conflict("One of the person links already has an open merge request.");
        }
        var candidateDigest = personMergeCandidateDigest(
                context, retained.linkId(), discarded.linkId());
        var impactDigest = personMergeImpactDigest(
                context, retained.linkId(), discarded.linkId(), candidateDigest);
        var counts = jdbc.queryForMap(
                """
                SELECT
                  (SELECT count(*) FROM workforce_members member
                    WHERE member.organization_id=?
                      AND member.organization_person_link_id IN (?,?)) AS member_count,
                  (SELECT count(*) FROM workforce_members member
                    WHERE member.organization_id=?
                      AND member.organization_person_link_id IN (?,?)
                      AND member.lifecycle_state<>'offboarded') AS live_member_count,
                  (SELECT count(*) FROM person_match_keys match_key
                    WHERE match_key.organization_id=?
                      AND match_key.organization_person_link_id IN (?,?)
                      AND match_key.status='active') AS active_match_key_count
                """,
                context.organizationId(), retained.linkId(), discarded.linkId(),
                context.organizationId(), retained.linkId(), discarded.linkId(),
                context.organizationId(), retained.linkId(), discarded.linkId());
        var memberCount = ((Number) counts.get("member_count")).intValue();
        var liveMemberCount = ((Number) counts.get("live_member_count")).intValue();
        var matchKeyCount = ((Number) counts.get("active_match_key_count")).intValue();
        return new ImpactAnalysis(impactDigest, List.of(
                new ImpactItem("person_links_consolidated", "impact",
                        "The discarded organization person link will be consolidated into the retained link.", 2),
                new ImpactItem("workforce_records_reconciled",
                        liveMemberCount > 1 ? "warning" : "impact",
                        liveMemberCount > 1
                                ? "Both links have live workforce records; resolve the duplicate record before execution."
                                : "Workforce references will remain attached to the retained identity.",
                        memberCount),
                new ImpactItem("active_match_keys_reassigned",
                        matchKeyCount > 0 ? "warning" : "impact",
                        "Active organization-scoped match keys will be deduplicated and reassigned.",
                        matchKeyCount),
                new ImpactItem("identity_history_preserved", "impact",
                        "Identity, decision, and attribution history will be preserved.", 2)));
    }

    private void requireCurrentPersonMergeImpact(
            AuthorizedTenantContext context, PersonMergeRequest request) {
        var candidateDigest = personMergeCandidateDigest(
                context, request.retainedLinkId(), request.discardedLinkId());
        var impactDigest = personMergeImpactDigest(
                context, request.retainedLinkId(), request.discardedLinkId(), candidateDigest);
        if (!candidateDigest.equals(request.candidateDigest())
                || !impactDigest.equals(request.impactDigest())) {
            throw conflict("The person-link merge evidence changed and requires a new request.");
        }
    }

    private UUID currentMemberForLink(AuthorizedTenantContext context, UUID linkId) {
        return jdbc.query(
                """
                SELECT id FROM workforce_members
                WHERE organization_id=? AND organization_person_link_id=?
                  AND lifecycle_state<>'offboarded'
                ORDER BY updated_at DESC,id DESC LIMIT 1
                """,
                resultSet -> resultSet.next() ? resultSet.getObject(1, UUID.class) : null,
                context.organizationId(),
                linkId);
    }

    private boolean canAccessMember(AuthorizedTenantContext context, UUID memberId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT careos_m2_actor_can_access_member(?,?)",
                Boolean.class,
                context.organizationId(),
                memberId));
    }

    private boolean hasActiveRole(AuthorizedTenantContext context, String roleKey) {
        return exists(
                """
                SELECT EXISTS(SELECT 1 FROM organization_memberships membership
                JOIN authorization_roles role ON role.role_key=membership.role_key
                WHERE membership.organization_id=? AND membership.user_id=?
                  AND membership.role_key=? AND membership.status='active'
                  AND membership.effective_from<=clock_timestamp()
                  AND (membership.effective_to IS NULL OR membership.effective_to>clock_timestamp())
                  AND role.status='active' AND role.interactive)
                """,
                context.organizationId(), context.actorId(), roleKey);
    }

    private boolean hasAnyActiveRole(
            AuthorizedTenantContext context, Set<String> roleKeys) {
        return roleKeys.stream().anyMatch(roleKey -> hasActiveRole(context, roleKey));
    }

    private void requireOperationScope() {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT careos_m2_actor_has_resource_scope()", Boolean.class))) {
            throw notFound("The workforce resource is unavailable or is not assigned to this account.");
        }
    }

    private UUID requirePractitionerMember(
            AuthorizedTenantContext context, UUID practitionerId) {
        var memberId = jdbc.query(
                """
                SELECT workforce_member_id FROM practitioner_profiles
                WHERE organization_id=? AND id=?
                """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject("workforce_member_id", UUID.class)
                        : null,
                context.organizationId(),
                practitionerId);
        if (memberId == null) {
            throw notFound("The practitioner profile was not found.");
        }
        requireMember(context, memberId);
        return memberId;
    }

    private UUID lockPractitionerForEligibility(
            AuthorizedTenantContext context, UUID practitionerId) {
        var memberId=jdbc.query(
                """
                SELECT workforce_member_id FROM practitioner_profiles
                WHERE organization_id=? AND id=?
                FOR NO KEY UPDATE
                """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject("workforce_member_id",UUID.class)
                        : null,
                context.organizationId(),practitionerId);
        if (memberId==null) {
            throw notFound("The practitioner was not found for eligibility evaluation.");
        }
        return memberId;
    }

    private int reconcileProspectiveServiceAssignments(
            AuthorizedTenantContext context,
            UUID practitionerId,
            UUID scopeId,
            Instant now) {
        if (practitionerId==null) {
            return 0;
        }
        var assignmentIds=jdbc.query(
                """
                SELECT id FROM practitioner_service_assignments
                WHERE organization_id=? AND practitioner_profile_id=?
                  AND (?::uuid IS NULL OR scope_of_practice_id=?::uuid)
                  AND lifecycle_state IN ('scheduled','active','suspended')
                  AND (effective_to IS NULL OR effective_to>?)
                ORDER BY id
                FOR UPDATE
                """,
                (resultSet,rowNumber) -> resultSet.getObject("id",UUID.class),
                context.organizationId(),practitionerId,scopeId,scopeId,Timestamp.from(now));
        assignmentIds.forEach(assignmentId -> reconcile(context,assignmentId,now));
        return assignmentIds.size();
    }

    private UUID findPractitionerId(
            AuthorizedTenantContext context, UUID memberId, boolean required) {
        requireMember(context, memberId);
        var practitionerId = jdbc.query(
                """
                SELECT id FROM practitioner_profiles
                WHERE organization_id=? AND workforce_member_id=?
                """,
                resultSet -> resultSet.next() ? resultSet.getObject(1, UUID.class) : null,
                context.organizationId(),
                memberId);
        if (required && practitionerId == null) {
            throw conflict("A practitioner profile is required for this action.");
        }
        return practitionerId;
    }

    private Credential requireCredential(AuthorizedTenantContext context, UUID credentialId) {
        var credential = jdbc.query(
                """
                SELECT id,workforce_member_id,practitioner_profile_id,status,lock_version,
                       content_digest,expires_on,supersedes_id
                FROM practitioner_credentials WHERE organization_id=? AND id=?
                """,
                resultSet -> resultSet.next()
                        ? new Credential(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("workforce_member_id", UUID.class),
                                resultSet.getObject("practitioner_profile_id", UUID.class),
                                resultSet.getString("status"),
                                resultSet.getLong("lock_version"),
                                resultSet.getString("content_digest"),
                                resultSet.getObject("expires_on", LocalDate.class),
                                resultSet.getObject("supersedes_id", UUID.class))
                        : null,
                context.organizationId(),
                credentialId);
        if (credential == null) {
            throw notFound("The credential was not found.");
        }
        requireMember(context, credential.memberId());
        return credential;
    }

    private ExpirySource requireExpirySource(
            AuthorizedTenantContext context, UUID sourceId) {
        var source = jdbc.query(
                """
                SELECT 'credential'::text AS source_type,credential.id,
                       credential.workforce_member_id,credential.expires_on,credential.lock_version
                FROM practitioner_credentials credential
                WHERE credential.organization_id=? AND credential.id=?
                UNION ALL
                SELECT 'registration'::text,registration.id,practitioner.workforce_member_id,
                       registration.expires_on,registration.lock_version
                FROM professional_registrations registration
                JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=registration.organization_id
                 AND practitioner.id=registration.practitioner_profile_id
                WHERE registration.organization_id=? AND registration.id=?
                """,
                resultSet -> resultSet.next()
                        ? new ExpirySource(
                                resultSet.getString("source_type"),
                                resultSet.getObject("id",UUID.class),
                                resultSet.getObject("workforce_member_id",UUID.class),
                                resultSet.getObject("expires_on",LocalDate.class),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),sourceId,context.organizationId(),sourceId);
        if (source==null) throw notFound("The expiry item was not found.");
        requireMember(context, source.memberId());
        if (source.expiresOn()==null) {
            throw conflict("A non-expiring credential or registration cannot be escalated.");
        }
        return source;
    }

    private Scope requireScope(AuthorizedTenantContext context, UUID scopeId) {
        var scope = jdbc.query(
                """
                SELECT scope.id,scope.practitioner_profile_id,scope.scope_definition_id,
                       scope.definition_version_digest,scope.result_digest,scope.effective_from,
                       scope.effective_to,scope.supersedes_id,scope.lifecycle_state,scope.lock_version
                FROM scopes_of_practice scope WHERE scope.organization_id=? AND scope.id=?
                """,
                resultSet -> resultSet.next()
                        ? new Scope(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("practitioner_profile_id", UUID.class),
                                resultSet.getObject("scope_definition_id", UUID.class),
                                resultSet.getString("definition_version_digest"),
                                resultSet.getString("result_digest"),
                                resultSet.getTimestamp("effective_from").toInstant(),
                                resultSet.getTimestamp("effective_to") == null
                                        ? null : resultSet.getTimestamp("effective_to").toInstant(),
                                resultSet.getObject("supersedes_id", UUID.class),
                                resultSet.getString("lifecycle_state"),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),
                scopeId);
        if (scope == null) {
            throw notFound("The scope of practice was not found.");
        }
        requirePractitionerMember(context, scope.practitionerId());
        return scope;
    }

    private Assignment requireAssignment(AuthorizedTenantContext context, UUID assignmentId) {
        var assignment = jdbc.query(
                """
                SELECT id,workforce_member_id,facility_id,organization_unit_id,location_id,
                       assignment_type_entry_id,assignment_type_version_id,
                       position_entry_id,position_version_id,primary_assignment,
                       effective_from,effective_to,lifecycle_state,lock_version
                FROM workforce_assignments WHERE organization_id=? AND id=?
                """,
                resultSet -> resultSet.next()
                        ? new Assignment(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("workforce_member_id", UUID.class),
                                resultSet.getObject("facility_id", UUID.class),
                                resultSet.getObject("organization_unit_id", UUID.class),
                                resultSet.getObject("location_id", UUID.class),
                                resultSet.getObject("assignment_type_entry_id", UUID.class),
                                resultSet.getObject("assignment_type_version_id", UUID.class),
                                resultSet.getObject("position_entry_id", UUID.class),
                                resultSet.getObject("position_version_id", UUID.class),
                                resultSet.getBoolean("primary_assignment"),
                                resultSet.getTimestamp("effective_from").toInstant(),
                                resultSet.getTimestamp("effective_to") == null
                                        ? null
                                        : resultSet.getTimestamp("effective_to").toInstant(),
                                resultSet.getString("lifecycle_state"),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),
                assignmentId);
        if (assignment == null) {
            throw notFound("The workforce assignment was not found.");
        }
        requireMember(context, assignment.memberId());
        return assignment;
    }

    private ReadinessRun requireReadinessRun(AuthorizedTenantContext context, UUID runId) {
        var run = jdbc.query(
                """
                SELECT id,workforce_member_id,member_revision,configuration_digest,status,
                       blocker_count,warning_count,result_digest,expires_at,created_by,lock_version
                FROM workforce_readiness_runs WHERE organization_id=? AND id=?
                """,
                resultSet -> resultSet.next()
                        ? new ReadinessRun(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("workforce_member_id", UUID.class),
                                resultSet.getLong("member_revision"),
                                resultSet.getString("configuration_digest"),
                                resultSet.getString("status"),
                                resultSet.getInt("blocker_count"),
                                resultSet.getInt("warning_count"),
                                resultSet.getString("result_digest"),
                                resultSet.getTimestamp("expires_at").toInstant(),
                                resultSet.getObject("created_by", UUID.class),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),
                runId);
        if (run == null) {
            throw notFound("The readiness run was not found.");
        }
        requireMember(context, run.memberId());
        return run;
    }

    private Set<String> requireWarningAcknowledgements(
            AuthorizedTenantContext context, UUID runId, MutationCommand command) {
        var warningKeys = Set.copyOf(jdbc.queryForList(
                """
                SELECT gate_key FROM workforce_readiness_results
                WHERE organization_id=? AND readiness_run_id=? AND outcome='warning'
                """,
                String.class,
                context.organizationId(),
                runId));
        var supplied = optional(command, "warningAcknowledgements");
        var acknowledged = supplied == null
                ? Set.<String>of()
                : java.util.Arrays.stream(supplied.split(","))
                        .map(String::strip)
                        .filter(value -> !value.isEmpty())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!acknowledged.equals(warningKeys)) {
            throw invalid("Every current readiness warning must be acknowledged by exact gate key.");
        }
        return acknowledged;
    }

    private Activation requireActivation(AuthorizedTenantContext context, UUID requestId) {
        var request = jdbc.query(
                """
                SELECT id,workforce_member_id,member_revision,readiness_run_id,result_digest,
                       maker_id,status,expires_at,lock_version
                FROM workforce_activation_requests WHERE organization_id=? AND id=?
                """,
                resultSet -> resultSet.next()
                        ? new Activation(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("workforce_member_id", UUID.class),
                                resultSet.getLong("member_revision"),
                                resultSet.getObject("readiness_run_id", UUID.class),
                                resultSet.getString("result_digest"),
                                resultSet.getObject("maker_id", UUID.class),
                                resultSet.getString("status"),
                                resultSet.getTimestamp("expires_at").toInstant(),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),
                requestId);
        if (request == null) {
            throw notFound("The activation request was not found.");
        }
        requireMember(context, request.memberId());
        return request;
    }

    private Offboarding requireOffboarding(AuthorizedTenantContext context, UUID requestId) {
        var request = jdbc.query(
                """
                SELECT id,workforce_member_id,impact_digest,engagement_end_at,effective_at,
                       access_action,checker_id,status,lock_version
                FROM workforce_offboarding_requests WHERE organization_id=? AND id=?
                """,
                resultSet -> resultSet.next()
                        ? new Offboarding(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("workforce_member_id", UUID.class),
                                resultSet.getString("impact_digest"),
                                resultSet.getTimestamp("engagement_end_at").toInstant(),
                                resultSet.getTimestamp("effective_at").toInstant(),
                                resultSet.getString("access_action"),
                                resultSet.getObject("checker_id",UUID.class),
                                resultSet.getString("status"),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),
                requestId);
        if (request == null) {
            throw notFound("The offboarding request was not found.");
        }
        requireMember(context, request.memberId());
        return request;
    }

    private ExportJob requireExport(AuthorizedTenantContext context, UUID exportId) {
        var export = jdbc.query(
                """
                SELECT id,requester_id,projection,format,filters_digest,purpose_key,status,lock_version
                FROM workforce_export_jobs WHERE organization_id=? AND id=?
                """,
                resultSet -> resultSet.next()
                        ? new ExportJob(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("requester_id", UUID.class),
                                resultSet.getString("projection"),
                                resultSet.getString("format"),
                                resultSet.getString("filters_digest"),
                                resultSet.getString("purpose_key"),
                                resultSet.getString("status"),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),
                exportId);
        if (export == null) {
            throw notFound("The workforce export was not found.");
        }
        return export;
    }

    private RegistryVersion requireRegistryVersion(
            AuthorizedTenantContext context, UUID versionId) {
        var version = jdbc.query(
                """
                SELECT version.id,version.registry_entry_id,entry.registry_definition_id,
                       version.version_number,version.version_fields::text,version.version_digest,
                       version.maker_id,version.checker_id,version.status,version.lock_version
                FROM workforce_registry_versions version
                JOIN workforce_registry_entries entry ON entry.organization_id=version.organization_id
                  AND entry.id=version.registry_entry_id
                WHERE version.organization_id=? AND version.id=?
                """,
                resultSet -> resultSet.next()
                        ? new RegistryVersion(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("registry_entry_id", UUID.class),
                                resultSet.getObject("registry_definition_id", UUID.class),
                                resultSet.getInt("version_number"),
                                resultSet.getString("version_fields"),
                                resultSet.getString("version_digest"),
                                resultSet.getObject("maker_id", UUID.class),
                                resultSet.getObject("checker_id", UUID.class),
                                resultSet.getString("status"),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),
                versionId);
        if (version == null) {
            throw notFound("The registry version was not found.");
        }
        return version;
    }

    private String registryValidationDigest(
            AuthorizedTenantContext context,
            RegistryVersion version,
            RegistryChange change,
            Instant validationAt) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",
                resultSet -> {
                    resultSet.next();
                    return Boolean.TRUE;
                },
                "workforce-registry-entry:"+context.organizationId()+":"+version.entryId());
        var state=jdbc.query(
                """
                SELECT request.parent_snapshot_id,
                       active_snapshot.id AS active_snapshot_id,
                       active_snapshot.snapshot_digest AS active_snapshot_digest,
                       item.baseline_revision,item.baseline_digest,
                       active_version.id AS active_version_id,
                       active_version.version_number AS active_version_number,
                       active_version.version_digest AS active_version_digest,
                       CASE WHEN jsonb_typeof(candidate.version_fields->'enabled')='boolean'
                            THEN (candidate.version_fields->>'enabled')::boolean ELSE NULL END
                            AS candidate_enabled
                FROM workforce_configuration_change_requests request
                JOIN workforce_configuration_change_items item
                  ON item.organization_id=request.organization_id
                 AND item.change_request_id=request.id
                JOIN workforce_registry_versions candidate
                  ON candidate.organization_id=item.organization_id
                 AND candidate.id=item.target_id
                LEFT JOIN LATERAL (
                    SELECT snapshot.id,snapshot.snapshot_digest
                    FROM workforce_configuration_snapshots snapshot
                    WHERE snapshot.organization_id=request.organization_id
                      AND snapshot.status='active' AND snapshot.superseded_at IS NULL
                    ORDER BY snapshot.effective_at DESC,snapshot.id DESC LIMIT 1
                ) active_snapshot ON true
                LEFT JOIN LATERAL (
                    SELECT current.id,current.version_number,current.version_digest
                    FROM workforce_registry_versions current
                    WHERE current.organization_id=candidate.organization_id
                      AND current.registry_entry_id=candidate.registry_entry_id
                      AND current.status='active' AND current.superseded_at IS NULL
                    ORDER BY current.effective_from DESC,current.id DESC LIMIT 1
                ) active_version ON true
                WHERE request.organization_id=? AND request.id=? AND item.id=?
                  AND candidate.id=?
                """,
                resultSet -> resultSet.next()
                        ? new RegistryValidationState(
                                resultSet.getObject("parent_snapshot_id",UUID.class),
                                resultSet.getObject("active_snapshot_id",UUID.class),
                                resultSet.getString("active_snapshot_digest"),
                                (Long) resultSet.getObject("baseline_revision"),
                                resultSet.getString("baseline_digest"),
                                resultSet.getObject("active_version_id",UUID.class),
                                (Integer) resultSet.getObject("active_version_number"),
                                resultSet.getString("active_version_digest"),
                                (Boolean) resultSet.getObject("candidate_enabled"))
                        : null,
                context.organizationId(),change.requestId(),change.itemId(),version.id());
        if (state==null || state.candidateEnabled()==null
                || !Objects.equals(state.parentSnapshotId(),state.activeSnapshotId())) {
            throw conflict("The workforce configuration baseline changed during registry validation.");
        }
        if (state.baselineRevision()==null) {
            if (state.baselineDigest()!=null || state.activeVersionId()!=null) {
                throw conflict("The registry entry acquired an unexpected active baseline.");
            }
        } else if (state.activeVersionId()==null
                || !Objects.equals(state.baselineRevision().intValue(),state.activeVersionNumber())
                || !Objects.equals(state.baselineDigest(),state.activeVersionDigest())) {
            throw conflict("The active registry version changed during validation.");
        }
        if (!state.candidateEnabled() && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT careos_m2_registry_entry_has_live_references(?,?,?)",
                Boolean.class,
                context.organizationId(),version.entryId(),Timestamp.from(validationAt)))) {
            throw conflict("The registry entry still has unresolved live or future references.");
        }
        return digest("m2-registry-validation-v1|"+version.definitionId()+"|"
                +version.entryId()+"|"+version.id()+"|"+version.digest()+"|"
                +Objects.toString(state.activeSnapshotId(),"root")+"|"
                +Objects.toString(state.activeSnapshotDigest(),"root")+"|"
                +Objects.toString(state.activeVersionId(),"none")+"|"
                +Objects.toString(state.activeVersionDigest(),"none")+"|references-clear");
    }

    private RegistryChange findRegistryChange(
            AuthorizedTenantContext context, UUID versionId) {
        var change = jdbc.query(
                """
                SELECT request.id AS request_id,item.id AS item_id,
                       request.status AS request_status,item.status AS item_status,
                       request.validation_digest,request.validation_expires_at,
                       request.decision_digest,request.decision_expires_at
                FROM workforce_configuration_change_items item
                JOIN workforce_configuration_change_requests request
                  ON request.organization_id=item.organization_id
                 AND request.id=item.change_request_id
                WHERE item.organization_id=? AND item.target_id=?
                ORDER BY item.created_at DESC LIMIT 1
                """,
                resultSet -> resultSet.next()
                        ? new RegistryChange(
                                resultSet.getObject("request_id", UUID.class),
                                resultSet.getObject("item_id", UUID.class),
                                resultSet.getString("request_status"),
                                resultSet.getString("item_status"),
                                resultSet.getString("validation_digest"),
                                instant(resultSet.getTimestamp("validation_expires_at")),
                                resultSet.getString("decision_digest"),
                                instant(resultSet.getTimestamp("decision_expires_at")))
                        : null,
                context.organizationId(),
                versionId);
        if (change == null) {
            throw conflict("The registry version is not attached to a governed change request.");
        }
        return change;
    }

    private Instant currentEffectiveTime(MutationCommand command) {
        var effectiveTime=instant(command,"effectiveTime");
        if (effectiveTime.isAfter(command.now().plusSeconds(60))
                || effectiveTime.isBefore(command.now().minusSeconds(300))) {
            throw invalid("The lifecycle effective time must be current.");
        }
        return effectiveTime;
    }

    private String reasonCode(MutationCommand command) {
        var reasonCode=required(command,"reasonCode");
        if (!reasonCode.matches("[a-z][a-z0-9._:-]{1,79}")) {
            throw invalid("reasonCode must be a stable lower-case machine key.");
        }
        return reasonCode;
    }

    private void insertTransition(
            AuthorizedTenantContext context,
            UUID memberId,
            String fromState,
            String toState,
            Instant effectiveAt,
            String reasonCode,
            String sourceType,
            UUID sourceId,
            long memberRevision) {
        insertTransition(
                context,
                UuidV7Generator.randomUuid(),
                memberId,
                fromState,
                toState,
                effectiveAt,
                reasonCode,
                sourceType,
                sourceId,
                memberRevision);
    }

    private void insertTransition(
            AuthorizedTenantContext context,
            UUID transitionId,
            UUID memberId,
            String fromState,
            String toState,
            Instant effectiveAt,
            String reasonCode,
            String sourceType,
            UUID sourceId,
            long memberRevision) {
        jdbc.update(
                """
                INSERT INTO workforce_lifecycle_transitions(
                    id,organization_id,workforce_member_id,from_state,to_state,effective_at,
                    reason_code,source_request_type,source_request_id,actor_kind,actor_id,
                    member_revision,correlation_id,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,
                        coalesce(nullif(current_setting('app.current_actor_kind',true),''),'user'),
                        ?,?,?,?,?,?)
                """,
                transitionId,
                context.organizationId(),
                memberId,
                fromState,
                toState,
                Timestamp.from(effectiveAt),
                reasonCode,
                sourceType,
                sourceId,
                context.actorId(),
                memberRevision,
                context.correlationId(),
                context.actorId(),
                context.actorId());
    }

    private String scopeResultDigest(
            AuthorizedTenantContext context,Scope scope,long submittedRevision) {
        var activityDigest=Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT coalesce(string_agg(
                    activity.activity_entry_id::text||':'||activity.activity_version_id::text||':'
                    ||coalesce(activity.service_id::text,'')||':'||coalesce(activity.facility_id::text,'')||':'
                    ||coalesce(activity.location_id::text,'')||':'
                    ||coalesce(activity.supervision_mode_entry_id::text,'')||':'
                    ||coalesce(activity.supervision_mode_version_id::text,''),
                    '|' ORDER BY activity.id),'')
                FROM scope_activities activity
                WHERE activity.organization_id=? AND activity.scope_of_practice_id=?
                  AND activity.status='active'
                """,
                String.class,context.organizationId(),scope.id()));
        var restrictionDigest=Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT coalesce(string_agg(
                    restriction.restriction_entry_id::text||':'
                    ||restriction.restriction_version_id::text||':'||restriction.display_text,
                    '|' ORDER BY restriction.id),'')
                FROM scope_restrictions restriction
                WHERE restriction.organization_id=? AND restriction.scope_of_practice_id=?
                  AND restriction.status='active'
                """,
                String.class,context.organizationId(),scope.id()));
        return digest(scope.definitionDigest()+"|"+scope.id()+"|"+submittedRevision+"|"
                +scope.effectiveFrom()+"|"+activityDigest+"|"+restrictionDigest);
    }

    private boolean scopeRequirementsMet(
            AuthorizedTenantContext context,Scope scope,Instant evaluatedAt) {
        return scopeRequirementsMet(context, scope, evaluatedAt, evaluatedAt);
    }

    private boolean mandatoryRequirementsMet(
            AuthorizedTenantContext context,
            UUID memberId,
            Instant evaluatedAt,
            Set<String> requirementTypes) {
        return exists(
                """
                SELECT NOT EXISTS(
                    SELECT 1 FROM scope_requirements requirement
                    JOIN scopes_of_practice scope
                      ON scope.organization_id=requirement.organization_id
                     AND scope.scope_definition_id=requirement.scope_definition_id
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=scope.organization_id
                     AND practitioner.id=scope.practitioner_profile_id
                    WHERE requirement.organization_id=? AND practitioner.workforce_member_id=?
                      AND scope.lifecycle_state='approved' AND scope.effective_from<=?
                      AND (scope.effective_to IS NULL OR scope.effective_to>?)
                      AND requirement.status='active' AND requirement.mandatory
                      AND requirement.requirement_type=ANY(?::varchar[])
                      AND NOT CASE
                        WHEN requirement.requirement_type IN ('qualification','training') THEN EXISTS(
                            SELECT 1 FROM qualifications qualification
                            JOIN workforce_registry_versions version
                              ON version.organization_id=qualification.organization_id
                             AND version.id=qualification.qualification_version_id
                             AND version.registry_entry_id=qualification.qualification_entry_id
                            WHERE qualification.organization_id=practitioner.organization_id
                              AND qualification.workforce_member_id=practitioner.workforce_member_id
                              AND qualification.status='verified'
                              AND (qualification.expires_on IS NULL
                                OR qualification.expires_on>=?::date
                                    + coalesce(requirement.validity_window_days,0))
                              AND (requirement.registry_entry_id IS NULL
                                OR qualification.qualification_entry_id=requirement.registry_entry_id)
                              AND (requirement.registry_version_id IS NULL
                                OR qualification.qualification_version_id=requirement.registry_version_id)
                              AND version.status='active' AND version.effective_from<=?
                              AND (version.effective_to IS NULL OR version.effective_to>?))
                        WHEN requirement.requirement_type='credential' THEN EXISTS(
                            SELECT 1 FROM practitioner_credentials credential
                            JOIN workforce_registry_versions version
                              ON version.organization_id=credential.organization_id
                             AND version.id=credential.credential_type_version_id
                             AND version.registry_entry_id=credential.credential_type_entry_id
                            JOIN credential_verifications verification
                              ON verification.organization_id=credential.organization_id
                             AND verification.id=credential.current_verification_id
                            WHERE credential.organization_id=practitioner.organization_id
                              AND credential.workforce_member_id=practitioner.workforce_member_id
                              AND credential.status='verified'
                              AND (credential.expires_on IS NULL
                                OR credential.expires_on>=?::date
                                    + coalesce(requirement.validity_window_days,0))
                              AND (requirement.registry_entry_id IS NULL
                                OR credential.credential_type_entry_id=requirement.registry_entry_id)
                              AND (requirement.registry_version_id IS NULL
                                OR credential.credential_type_version_id=requirement.registry_version_id)
                              AND version.status='active' AND version.effective_from<=?
                              AND (version.effective_to IS NULL OR version.effective_to>?)
                              AND verification.status='verified' AND verification.decision='verified'
                              AND cardinality(verification.evidence_ids)>0
                              AND NOT EXISTS(
                                  SELECT 1 FROM unnest(verification.evidence_ids) evidence_id
                                  LEFT JOIN credential_documents document
                                    ON document.organization_id=credential.organization_id
                                   AND document.id=evidence_id
                                   AND document.practitioner_credential_id=credential.id
                                  LEFT JOIN document_promotion_evidence promotion
                                    ON promotion.organization_id=document.organization_id
                                   AND promotion.document_id=document.platform_document_id
                                   AND promotion.object_version_id=document.platform_object_version_id
                                  WHERE document.id IS NULL OR document.status<>'clean'
                                     OR document.promoted_evidence_digest IS NULL OR promotion.id IS NULL))
                        ELSE false
                      END)
                """,
                context.organizationId(),memberId,Timestamp.from(evaluatedAt),
                Timestamp.from(evaluatedAt),requirementTypes.toArray(String[]::new),
                Timestamp.from(evaluatedAt),Timestamp.from(evaluatedAt),Timestamp.from(evaluatedAt),
                Timestamp.from(evaluatedAt),Timestamp.from(evaluatedAt),Timestamp.from(evaluatedAt));
    }

    private boolean scopeRequirementsMet(
            AuthorizedTenantContext context,
            Scope scope,
            Instant evaluatedAt,
            Instant coverageEnd) {
        var at=scope.effectiveFrom().isAfter(evaluatedAt)?scope.effectiveFrom():evaluatedAt;
        var through=coverageEnd.isAfter(at)?coverageEnd:at;
        return exists(
                """
                WITH parameters AS (
                    SELECT ?::timestamptz AS evaluated_at, ?::timestamptz AS coverage_end
                )
                SELECT NOT EXISTS(
                    SELECT 1
                    FROM scope_requirements requirement
                    JOIN scopes_of_practice candidate
                      ON candidate.organization_id=requirement.organization_id
                     AND candidate.scope_definition_id=requirement.scope_definition_id
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=candidate.organization_id
                     AND practitioner.id=candidate.practitioner_profile_id
                    CROSS JOIN parameters parameter
                    WHERE requirement.organization_id=? AND candidate.id=?
                      AND requirement.status='active' AND requirement.mandatory
                      AND NOT CASE requirement.requirement_type
                        WHEN 'registration' THEN EXISTS(
                            SELECT 1 FROM professional_registrations registration
                            WHERE registration.organization_id=practitioner.organization_id
                              AND registration.practitioner_profile_id=practitioner.id
                              AND registration.status='verified'
                              AND registration.valid_from<=parameter.evaluated_at::date
                              AND (registration.expires_on IS NULL
                                OR registration.expires_on>=parameter.coverage_end::date
                                    + coalesce(requirement.validity_window_days,0))
                              AND (requirement.registry_entry_id IS NULL
                                OR registration.regulator_entry_id=requirement.registry_entry_id
                                OR registration.registration_type_entry_id=requirement.registry_entry_id)
                              AND (requirement.registry_version_id IS NULL
                                OR registration.regulator_version_id=requirement.registry_version_id
                                OR registration.registration_type_version_id=requirement.registry_version_id)
                              AND EXISTS(SELECT 1 FROM workforce_registry_versions version
                                  WHERE version.organization_id=registration.organization_id
                                    AND version.id IN (registration.regulator_version_id,
                                                       registration.registration_type_version_id)
                                    AND version.status='active'
                                    AND version.effective_from<=parameter.evaluated_at
                                    AND (version.effective_to IS NULL
                                      OR version.effective_to>=parameter.coverage_end)))
                        WHEN 'qualification' THEN EXISTS(
                            SELECT 1 FROM qualifications qualification
                            JOIN workforce_registry_versions version
                              ON version.organization_id=qualification.organization_id
                             AND version.id=qualification.qualification_version_id
                             AND version.registry_entry_id=qualification.qualification_entry_id
                            WHERE qualification.organization_id=practitioner.organization_id
                              AND qualification.workforce_member_id=practitioner.workforce_member_id
                              AND qualification.status='verified'
                              AND (qualification.expires_on IS NULL
                                OR qualification.expires_on>=parameter.coverage_end::date
                                    + coalesce(requirement.validity_window_days,0))
                              AND (requirement.registry_entry_id IS NULL
                                OR qualification.qualification_entry_id=requirement.registry_entry_id)
                              AND (requirement.registry_version_id IS NULL
                                OR qualification.qualification_version_id=requirement.registry_version_id)
                              AND version.status='active'
                              AND version.effective_from<=parameter.evaluated_at
                              AND (version.effective_to IS NULL OR version.effective_to>=parameter.coverage_end))
                        WHEN 'credential' THEN EXISTS(
                            SELECT 1 FROM practitioner_credentials credential
                            JOIN workforce_registry_versions version
                              ON version.organization_id=credential.organization_id
                             AND version.id=credential.credential_type_version_id
                             AND version.registry_entry_id=credential.credential_type_entry_id
                            JOIN credential_verifications verification
                              ON verification.organization_id=credential.organization_id
                             AND verification.id=credential.current_verification_id
                            WHERE credential.organization_id=practitioner.organization_id
                              AND credential.workforce_member_id=practitioner.workforce_member_id
                              AND credential.status='verified'
                              AND (credential.expires_on IS NULL
                                OR credential.expires_on>=parameter.coverage_end::date
                                    + coalesce(requirement.validity_window_days,0))
                              AND (requirement.registry_entry_id IS NULL
                                OR credential.credential_type_entry_id=requirement.registry_entry_id)
                              AND (requirement.registry_version_id IS NULL
                                OR credential.credential_type_version_id=requirement.registry_version_id)
                              AND version.status='active'
                              AND version.effective_from<=parameter.evaluated_at
                              AND (version.effective_to IS NULL OR version.effective_to>=parameter.coverage_end)
                              AND verification.status='verified' AND verification.decision='verified'
                              AND cardinality(verification.evidence_ids)>0
                              AND NOT EXISTS(
                                  SELECT 1 FROM unnest(verification.evidence_ids) evidence_id
                                  LEFT JOIN credential_documents document
                                    ON document.organization_id=credential.organization_id
                                   AND document.id=evidence_id
                                   AND document.practitioner_credential_id=credential.id
                                  LEFT JOIN document_promotion_evidence promotion
                                    ON promotion.organization_id=document.organization_id
                                   AND promotion.document_id=document.platform_document_id
                                   AND promotion.object_version_id=document.platform_object_version_id
                                  WHERE document.id IS NULL OR document.status<>'clean'
                                     OR document.promoted_evidence_digest IS NULL OR promotion.id IS NULL))
                        WHEN 'specialty' THEN EXISTS(
                            SELECT 1 FROM practitioner_specialties specialty
                            JOIN workforce_registry_versions version
                              ON version.organization_id=specialty.organization_id
                             AND version.id=specialty.specialty_version_id
                             AND version.registry_entry_id=specialty.specialty_entry_id
                            WHERE specialty.organization_id=practitioner.organization_id
                              AND specialty.practitioner_profile_id=practitioner.id
                              AND specialty.status='active'
                              AND specialty.effective_from<=parameter.evaluated_at
                              AND (specialty.effective_to IS NULL
                                OR specialty.effective_to>=parameter.coverage_end)
                              AND (requirement.registry_entry_id IS NULL
                                OR specialty.specialty_entry_id=requirement.registry_entry_id)
                              AND (requirement.registry_version_id IS NULL
                                OR specialty.specialty_version_id=requirement.registry_version_id)
                              AND version.status='active'
                              AND version.effective_from<=parameter.evaluated_at
                              AND (version.effective_to IS NULL OR version.effective_to>=parameter.coverage_end))
                        WHEN 'supervision' THEN EXISTS(
                            SELECT 1 FROM scope_activities activity
                            JOIN workforce_registry_versions version
                              ON version.organization_id=activity.organization_id
                             AND version.id=activity.supervision_mode_version_id
                             AND version.registry_entry_id=activity.supervision_mode_entry_id
                            WHERE activity.organization_id=candidate.organization_id
                              AND activity.scope_of_practice_id=candidate.id
                              AND activity.status='active'
                              AND activity.supervision_mode_entry_id IS NOT NULL
                              AND (requirement.registry_entry_id IS NULL
                                OR activity.supervision_mode_entry_id=requirement.registry_entry_id)
                              AND (requirement.registry_version_id IS NULL
                                OR activity.supervision_mode_version_id=requirement.registry_version_id)
                              AND version.status='active'
                              AND version.effective_from<=parameter.evaluated_at
                              AND (version.effective_to IS NULL OR version.effective_to>=parameter.coverage_end))
                        WHEN 'training' THEN EXISTS(
                            SELECT 1 FROM qualifications training
                            JOIN workforce_registry_versions version
                              ON version.organization_id=training.organization_id
                             AND version.id=training.qualification_version_id
                             AND version.registry_entry_id=training.qualification_entry_id
                            WHERE training.organization_id=practitioner.organization_id
                              AND training.workforce_member_id=practitioner.workforce_member_id
                              AND training.status='verified'
                              AND (training.expires_on IS NULL
                                OR training.expires_on>=parameter.coverage_end::date
                                    + coalesce(requirement.validity_window_days,0))
                              AND (requirement.registry_entry_id IS NULL
                                OR training.qualification_entry_id=requirement.registry_entry_id)
                              AND (requirement.registry_version_id IS NULL
                                OR training.qualification_version_id=requirement.registry_version_id)
                              AND version.status='active'
                              AND version.effective_from<=parameter.evaluated_at
                              AND (version.effective_to IS NULL OR version.effective_to>=parameter.coverage_end))
                        ELSE false
                      END)
                """,
                Timestamp.from(at),Timestamp.from(through),context.organizationId(),scope.id());
    }

    private UUID createScopeDefinition(
            AuthorizedTenantContext context,
            MutationCommand command,
            Instant effectiveFrom,
            Instant effectiveTo) {
        var definitionId=UuidV7Generator.randomUuid();
        var code=required(command,"definitionCode").toUpperCase(java.util.Locale.ROOT);
        var name=required(command,"definitionName");
        var country=required(command,"jurisdictionCountry").toUpperCase(java.util.Locale.ROOT);
        if (!code.matches("[A-Z0-9][A-Z0-9_-]{2,47}")) {
            throw invalid("definitionCode must contain 3 to 48 upper-case letters, digits, underscores or hyphens.");
        }
        if (name.length()<2 || name.length()>180 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("definitionName must contain 2 to 180 safe characters.");
        }
        if (!country.matches("[A-Z]{2}")) {
            throw invalid("jurisdictionCountry must be an ISO alpha-2 code.");
        }
        var professionEntry=uuid(command,"professionEntryId");
        var professionVersion=uuid(command,"professionVersionId");
        requireRegistryCategory(context,professionEntry,professionVersion,"profession",effectiveFrom);
        var specialtyEntry=optionalUuid(command,"specialtyEntryId");
        var specialtyVersion=optionalUuid(command,"specialtyVersionId");
        if ((specialtyEntry==null)!=(specialtyVersion==null)) {
            throw invalid("specialtyEntryId and specialtyVersionId must be supplied together.");
        }
        if (specialtyEntry!=null) {
            requireRegistryCategory(context,specialtyEntry,specialtyVersion,"specialty",effectiveFrom);
        }
        var serviceId=optionalUuid(command,"definitionServiceId");
        if (serviceId!=null && !exists(
                "SELECT EXISTS(SELECT 1 FROM service_definitions WHERE organization_id=? AND id=? AND status='active')",
                context.organizationId(),serviceId)) {
            throw conflict("The scope-definition service is not active in this organization.");
        }
        var ownerMembership=jdbc.query(
                """
                SELECT id FROM organization_memberships
                WHERE organization_id=? AND user_id=? AND status='active'
                  AND effective_from<=? AND (effective_to IS NULL OR effective_to>?)
                ORDER BY effective_from DESC,id DESC LIMIT 1
                """,
                resultSet -> resultSet.next()?resultSet.getObject(1,UUID.class):null,
                context.organizationId(),context.actorId(),Timestamp.from(effectiveFrom),
                Timestamp.from(effectiveFrom));
        jdbc.update(
                """
                INSERT INTO scope_definitions(
                    id,organization_id,definition_code,name,profession_entry_id,
                    profession_version_id,specialty_entry_id,specialty_version_id,service_id,
                    jurisdiction_country,jurisdiction_region,owner_membership_id,
                    effective_from,effective_to,lifecycle_state,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'active','active',?,?)
                """,
                definitionId,context.organizationId(),code,name,professionEntry,professionVersion,
                specialtyEntry,specialtyVersion,serviceId,country,optional(command,"jurisdictionRegion"),
                ownerMembership,Timestamp.from(effectiveFrom),timestamp(effectiveTo),
                context.actorId(),context.actorId());
        var requirements=parseScopeRequirements(optional(command,"requirements"));
        var order=0;
        for (var requirement:requirements) {
            order++;
            if (requirement.entryId()!=null) {
                var categories=switch (requirement.type()) {
                    case "registration" -> Set.of("regulator","registration_type");
                    case "qualification","training" -> Set.of("qualification_type");
                    case "credential" -> Set.of("credential_type");
                    case "specialty" -> Set.of("specialty");
                    case "supervision" -> Set.of("supervision_mode");
                    default -> Set.<String>of();
                };
                requireRegistryCategories(
                        context,requirement.entryId(),requirement.versionId(),categories,effectiveFrom);
            }
            jdbc.update(
                    """
                    INSERT INTO scope_requirements(
                        id,organization_id,scope_definition_id,requirement_order,requirement_type,
                        registry_entry_id,registry_version_id,mandatory,validity_window_days,
                        evidence_rule,status,created_by,updated_by)
                    VALUES (?,?,?,?,?,?,?,?,?,'{\"rule\":\"current_verified\"}'::jsonb,'active',?,?)
                    """,
                    UuidV7Generator.randomUuid(),context.organizationId(),definitionId,order,
                    requirement.type(),requirement.entryId(),requirement.versionId(),
                    requirement.mandatory(),requirement.validityWindowDays(),
                    context.actorId(),context.actorId());
        }
        return definitionId;
    }

    private void requireRegistryCategory(
            AuthorizedTenantContext context,
            UUID entryId,
            UUID versionId,
            String category,
            Instant effectiveAt) {
        requireRegistryCategory(context, entryId, versionId, category, effectiveAt, effectiveAt);
    }

    private void requireRegistryCategory(
            AuthorizedTenantContext context,
            UUID entryId,
            UUID versionId,
            String category,
            Instant effectiveFrom,
            Instant effectiveTo) {
        if (!exists(
                """
                SELECT EXISTS(SELECT 1
                FROM workforce_registry_versions version
                JOIN workforce_registry_entries entry
                  ON entry.organization_id=version.organization_id
                 AND entry.id=version.registry_entry_id
                JOIN workforce_registry_definitions definition
                  ON definition.organization_id=entry.organization_id
                 AND definition.id=entry.registry_definition_id
                WHERE version.organization_id=? AND entry.id=? AND version.id=?
                  AND definition.category=? AND definition.status='active' AND entry.status='active'
                  AND version.status='active' AND version.effective_from<=?
                  AND ((?::timestamptz IS NULL AND version.effective_to IS NULL)
                    OR (?::timestamptz IS NOT NULL
                      AND (version.effective_to IS NULL OR version.effective_to>=?))))
                """,
                context.organizationId(),entryId,versionId,category,
                Timestamp.from(effectiveFrom),timestamp(effectiveTo),timestamp(effectiveTo),
                timestamp(effectiveTo))) {
            throw conflict("A referenced "+category+" registry version is not active.");
        }
    }

    private void requireRegistryEntryKey(
            AuthorizedTenantContext context,
            String entryKey,
            String category,
            Instant effectiveAt) {
        requireRegistryEntryKey(context, entryKey, category, effectiveAt, effectiveAt);
    }

    private void requireRegistryEntryKey(
            AuthorizedTenantContext context,
            String entryKey,
            String category,
            Instant effectiveFrom,
            Instant effectiveTo) {
        if (!exists(
                """
                SELECT EXISTS(SELECT 1
                FROM workforce_registry_definitions definition
                JOIN workforce_registry_entries entry
                  ON entry.organization_id=definition.organization_id
                 AND entry.registry_definition_id=definition.id
                JOIN workforce_registry_versions version
                  ON version.organization_id=entry.organization_id
                 AND version.registry_entry_id=entry.id
                WHERE definition.organization_id=? AND definition.category=?
                  AND entry.entry_key=? AND definition.status='active' AND entry.status='active'
                  AND version.status='active' AND version.effective_from<=?
                  AND ((?::timestamptz IS NULL AND version.effective_to IS NULL)
                    OR (?::timestamptz IS NOT NULL
                      AND (version.effective_to IS NULL OR version.effective_to>=?))))
                """,
                context.organizationId(), category, entryKey, Timestamp.from(effectiveFrom),
                timestamp(effectiveTo), timestamp(effectiveTo), timestamp(effectiveTo))) {
            throw conflict("A referenced " + category + " registry entry is not active.");
        }
    }

    private boolean availabilityMayBeOmitted(
            AuthorizedTenantContext context, UUID memberId, Instant effectiveAt) {
        return exists(
                """
                SELECT EXISTS(
                    SELECT 1 FROM employment_engagements engagement
                    JOIN workforce_registry_definitions definition
                      ON definition.organization_id=engagement.organization_id
                     AND definition.category='employment_category'
                    JOIN workforce_registry_entries entry
                      ON entry.organization_id=definition.organization_id
                     AND entry.registry_definition_id=definition.id
                     AND entry.entry_key=engagement.employment_category_key
                    JOIN workforce_registry_versions version
                      ON version.organization_id=entry.organization_id
                     AND version.registry_entry_id=entry.id
                    WHERE engagement.organization_id=?
                      AND engagement.workforce_member_id=?
                      AND engagement.employment_category_key='availability_not_required'
                      AND engagement.status IN ('scheduled','active')
                      AND engagement.effective_from<=?
                      AND (engagement.effective_to IS NULL OR engagement.effective_to>?)
                      AND definition.status='active' AND entry.status='active'
                      AND version.status='active' AND version.effective_from<=?
                      AND (version.effective_to IS NULL OR version.effective_to>?))
                """,
                context.organizationId(),memberId,Timestamp.from(effectiveAt),
                Timestamp.from(effectiveAt),Timestamp.from(effectiveAt),Timestamp.from(effectiveAt));
    }

    private boolean engagementCovers(
            AuthorizedTenantContext context,
            UUID memberId,
            Instant effectiveFrom,
            Instant effectiveTo) {
        return exists(
                """
                SELECT EXISTS(SELECT 1 FROM employment_engagements engagement
                WHERE engagement.organization_id=? AND engagement.workforce_member_id=?
                  AND engagement.status IN ('scheduled','active')
                  AND engagement.effective_from<=?
                  AND ((?::timestamptz IS NULL AND engagement.effective_to IS NULL)
                    OR (?::timestamptz IS NOT NULL
                      AND (engagement.effective_to IS NULL OR engagement.effective_to>=?))))
                """,
                context.organizationId(), memberId, Timestamp.from(effectiveFrom),
                timestamp(effectiveTo), timestamp(effectiveTo), timestamp(effectiveTo));
    }

    private ImpactAnalysis registrationLifecycleImpact(
            AuthorizedTenantContext context, MutationCommand command, String toState) {
        var registrationId=requireTarget(command);
        var revision=requireRevision(command);
        var effectiveTime=currentEffectiveTime(command);
        var authorityEvidenceId=uuid(command,"authorityEvidenceId");
        reasonCode(command);
        var practitionerId=jdbc.query(
                """
                SELECT practitioner_profile_id FROM professional_registrations
                WHERE organization_id=? AND id=?
                """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject("practitioner_profile_id",UUID.class)
                        : null,
                context.organizationId(),registrationId);
        if (practitionerId==null) {
            throw notFound("The professional registration was not found.");
        }
        lockPractitionerForEligibility(context,practitionerId);
        var row=jdbc.queryForMap(
                """
                SELECT registration.practitioner_profile_id,registration.status,
                       registration.lock_version,practitioner.workforce_member_id,
                       member.lock_version AS member_revision,
                       (SELECT count(*) FROM practitioner_service_assignments assignment
                          WHERE assignment.organization_id=registration.organization_id
                            AND assignment.practitioner_profile_id=registration.practitioner_profile_id
                            AND assignment.lifecycle_state IN ('scheduled','active','suspended')
                            AND (assignment.effective_to IS NULL OR assignment.effective_to>?))
                            AS service_count,
                       (SELECT count(*) FROM scopes_of_practice scope
                         WHERE scope.organization_id=registration.organization_id
                           AND scope.practitioner_profile_id=registration.practitioner_profile_id
                           AND scope.lifecycle_state IN ('approved','suspended')) AS scope_count
                FROM professional_registrations registration
                JOIN practitioner_profiles practitioner
                  ON practitioner.organization_id=registration.organization_id
                 AND practitioner.id=registration.practitioner_profile_id
                JOIN workforce_members member
                  ON member.organization_id=practitioner.organization_id
                 AND member.id=practitioner.workforce_member_id
                WHERE registration.organization_id=? AND registration.id=?
                """,
                Timestamp.from(effectiveTime),context.organizationId(),registrationId);
        requireMember(context,(UUID) row.get("workforce_member_id"));
        var fromState=(String) row.get("status");
        var allowed=toState.equals("suspended")?Set.of("verified"):Set.of("verified","suspended");
        if (((Number) row.get("lock_version")).longValue()!=revision || !allowed.contains(fromState)) {
            throw stale("The registration lifecycle transition is stale or invalid.");
        }
        var serviceCount=((Number) row.get("service_count")).intValue();
        var scopeCount=((Number) row.get("scope_count")).intValue();
        var impactDigest=digest("registration|"+registrationId+"|"+revision+"|"+fromState+"|"
                +toState+"|"+effectiveTime+"|"+authorityEvidenceId+"|"
                +row.get("member_revision")+"|"+serviceCount+"|"+scopeCount);
        return new ImpactAnalysis(impactDigest,List.of(
                new ImpactItem("prospective_eligibility_invalidated","impact",
                        "Existing prospective registration eligibility evidence will be replaced.",1),
                new ImpactItem("service_assignments_reconciled",serviceCount>0?"warning":"impact",
                        "Service assignments will be re-evaluated; ineligible work is suspended.",serviceCount),
                new ImpactItem("approved_scopes_rechecked",scopeCount>0?"warning":"impact",
                        "Approved scopes remain historical but must be rechecked.",scopeCount)));
    }

    private ImpactAnalysis credentialLifecycleImpact(
            AuthorizedTenantContext context, MutationCommand command, String toState) {
        var credentialId=requireTarget(command);
        var revision=requireRevision(command);
        var effectiveTime=currentEffectiveTime(command);
        var authorityEvidenceId=uuid(command,"authorityEvidenceId");
        reasonCode(command);
        var credential=requireCredential(context,credentialId);
        if (credential.practitionerId()!=null) {
            lockPractitionerForEligibility(context,credential.practitionerId());
            credential=requireCredential(context,credentialId);
        }
        var allowed=toState.equals("suspended")?Set.of("verified"):Set.of("verified","suspended");
        if (credential.revision()!=revision || !allowed.contains(credential.status())) {
            throw stale("The credential lifecycle transition is stale or invalid.");
        }
        var impact=jdbc.queryForMap(
                """
                SELECT member.lock_version AS member_revision,
                       (SELECT count(*) FROM practitioner_service_assignments assignment
                          WHERE assignment.organization_id=member.organization_id
                            AND assignment.practitioner_profile_id=credential.practitioner_profile_id
                            AND assignment.lifecycle_state IN ('scheduled','active','suspended')
                            AND (assignment.effective_to IS NULL OR assignment.effective_to>?))
                            AS service_count,
                       (SELECT count(*) FROM workforce_readiness_runs run
                         WHERE run.organization_id=member.organization_id
                           AND run.workforce_member_id=member.id
                           AND run.status='complete' AND run.expires_at>?) AS readiness_count
                FROM practitioner_credentials credential
                JOIN workforce_members member
                  ON member.organization_id=credential.organization_id
                 AND member.id=credential.workforce_member_id
                WHERE credential.organization_id=? AND credential.id=?
                """,
                Timestamp.from(effectiveTime),Timestamp.from(command.now()),
                context.organizationId(),credentialId);
        var serviceCount=((Number) impact.get("service_count")).intValue();
        var readinessCount=((Number) impact.get("readiness_count")).intValue();
        var impactDigest=digest("credential|"+credentialId+"|"+revision+"|"
                +credential.status()+"|"+toState+"|"+effectiveTime+"|"+authorityEvidenceId+"|"
                +impact.get("member_revision")+"|"+serviceCount+"|"+readinessCount);
        return new ImpactAnalysis(impactDigest,List.of(
                new ImpactItem("prospective_eligibility_invalidated","impact",
                        "Existing prospective credential eligibility evidence will be replaced.",1),
                new ImpactItem("service_assignments_reconciled",serviceCount>0?"warning":"impact",
                        "Service assignments will be re-evaluated; ineligible work is suspended.",serviceCount),
                new ImpactItem("readiness_invalidated",readinessCount>0?"warning":"impact",
                        "Fresh readiness evidence will be invalidated.",readinessCount)));
    }

    private ImpactAnalysis scopeLifecycleImpact(
            AuthorizedTenantContext context, MutationCommand command, String toState) {
        var scopeId=requireTarget(command);
        var revision=requireRevision(command);
        var effectiveTime=currentEffectiveTime(command);
        reasonCode(command);
        var scope=requireScope(context,scopeId);
        lockPractitionerForEligibility(context,scope.practitionerId());
        scope=requireScope(context,scopeId);
        var allowed=toState.equals("suspended")?Set.of("approved"):Set.of("approved","suspended");
        if (scope.revision()!=revision || !allowed.contains(scope.status())
                || !effectiveTime.isAfter(scope.effectiveFrom())
                || (scope.effectiveTo()!=null && effectiveTime.isAfter(scope.effectiveTo()))) {
            throw stale("The scope lifecycle transition is stale or invalid.");
        }
        var impact=jdbc.queryForMap(
                """
                SELECT
                  (SELECT count(*) FROM practitioner_service_assignments assignment
                    WHERE assignment.organization_id=? AND assignment.scope_of_practice_id=?
                      AND assignment.lifecycle_state IN ('scheduled','active','suspended')
                      AND (assignment.effective_to IS NULL OR assignment.effective_to>?))
                      AS service_count,
                  (SELECT count(*) FROM scope_activities activity
                    WHERE activity.organization_id=? AND activity.scope_of_practice_id=?
                      AND activity.status='active'
                      AND (activity.effective_to IS NULL OR activity.effective_to>?)) AS activity_count,
                  (SELECT count(*) FROM scope_restrictions restriction
                    WHERE restriction.organization_id=? AND restriction.scope_of_practice_id=?
                      AND restriction.status='active'
                      AND (restriction.effective_to IS NULL OR restriction.effective_to>?)) AS restriction_count
                """,
                context.organizationId(),scopeId,Timestamp.from(effectiveTime),
                context.organizationId(),scopeId,Timestamp.from(effectiveTime),
                context.organizationId(),scopeId,
                Timestamp.from(effectiveTime));
        var serviceCount=((Number) impact.get("service_count")).intValue();
        var activityCount=((Number) impact.get("activity_count")).intValue();
        var restrictionCount=((Number) impact.get("restriction_count")).intValue();
        var impactDigest=digest("scope|"+scopeId+"|"+revision+"|"+scope.status()+"|"
                +toState+"|"+effectiveTime+"|"+serviceCount+"|"+activityCount+"|"
                +restrictionCount);
        return new ImpactAnalysis(impactDigest,List.of(
                new ImpactItem("service_assignments_reconciled",serviceCount>0?"warning":"impact",
                        "Linked service assignments will be re-evaluated; ineligible work is suspended.",serviceCount),
                new ImpactItem("scope_activities_preserved","impact",
                        "Controlled activities remain as historical evidence.",activityCount),
                new ImpactItem("scope_restrictions_preserved","impact",
                        "Restrictions remain as historical evidence.",restrictionCount)));
    }

    private ImpactAnalysis assignmentTransferImpact(
            AuthorizedTenantContext context, MutationCommand command) {
        var predecessorId=requireTarget(command);
        var revision=requireRevision(command);
        var predecessor=requireAssignment(context,predecessorId);
        var practitionerId=findPractitionerId(context,predecessor.memberId(),false);
        if (practitionerId!=null) {
            lockPractitionerForEligibility(context,practitionerId);
            predecessor=requireAssignment(context,predecessorId);
        }
        var effectiveTime=instant(command,"effectiveTime");
        if (predecessor.revision()!=revision
                || !Set.of("scheduled","active").contains(predecessor.status())) {
            throw stale("The assignment cannot be transferred from its current state.");
        }
        if (!effectiveTime.isAfter(predecessor.effectiveFrom())
                || (predecessor.effectiveTo()!=null
                    && !effectiveTime.isBefore(predecessor.effectiveTo()))) {
            throw invalid("The transfer effective time must fall inside the predecessor range.");
        }
        var successorFacilityId=uuid(command,"successorFacilityId");
        var successorUnitId=optionalUuid(command,"successorOrganizationUnitId");
        var successorLocationId=optionalUuid(command,"successorLocationId");
        requireRegistryCategory(context,predecessor.assignmentTypeEntryId(),
                predecessor.assignmentTypeVersionId(),"assignment_type",effectiveTime,
                predecessor.effectiveTo());
        if (predecessor.positionEntryId()!=null) {
            requireRegistryCategory(context,predecessor.positionEntryId(),
                    predecessor.positionVersionId(),"position",effectiveTime,
                    predecessor.effectiveTo());
        }
        requireHierarchyContext(context,successorFacilityId,successorUnitId,successorLocationId,
                effectiveTime,predecessor.effectiveTo());
        if (!engagementCovers(context,predecessor.memberId(),effectiveTime,
                predecessor.effectiveTo())) {
            throw conflict("An engagement must cover the successor assignment range.");
        }
        var downstream=jdbc.queryForMap(
                """
                SELECT member.lock_version AS member_revision,
                       (SELECT count(*) FROM practitioner_service_assignments service_assignment
                         JOIN practitioner_profiles practitioner
                           ON practitioner.organization_id=service_assignment.organization_id
                          AND practitioner.id=service_assignment.practitioner_profile_id
                         WHERE practitioner.organization_id=member.organization_id
                           AND practitioner.workforce_member_id=member.id
                           AND service_assignment.lifecycle_state IN ('scheduled','active')
                           AND service_assignment.effective_from<=?
                           AND (service_assignment.effective_to IS NULL
                             OR service_assignment.effective_to>?)
                           AND service_assignment.facility_id<>?
                           AND NOT EXISTS(
                               SELECT 1 FROM workforce_assignments other_assignment
                               WHERE other_assignment.organization_id=member.organization_id
                                 AND other_assignment.workforce_member_id=member.id
                                 AND other_assignment.id<>?
                                 AND other_assignment.facility_id=service_assignment.facility_id
                                 AND other_assignment.lifecycle_state IN ('scheduled','active')
                                 AND other_assignment.effective_from<=?
                                 AND (other_assignment.effective_to IS NULL
                                   OR other_assignment.effective_to>?))) AS uncovered_services
                FROM workforce_members member
                WHERE member.organization_id=? AND member.id=?
                """,
                Timestamp.from(effectiveTime),Timestamp.from(effectiveTime),successorFacilityId,
                predecessorId,Timestamp.from(effectiveTime),Timestamp.from(effectiveTime),
                context.organizationId(),predecessor.memberId());
        var uncovered=((Number) downstream.get("uncovered_services")).intValue();
        var impactDigest=digest("assignment-transfer|"+predecessorId+"|"+revision+"|"
                +successorFacilityId+"|"+Objects.toString(successorUnitId,"")+"|"
                +Objects.toString(successorLocationId,"")+"|"+effectiveTime+"|"
                +Objects.toString(predecessor.effectiveTo(),"")+"|"
                +downstream.get("member_revision")+"|"+uncovered);
        return new ImpactAnalysis(impactDigest,List.of(
                new ImpactItem("predecessor_ended","impact",
                        "The predecessor assignment ends at the successor boundary.",1),
                new ImpactItem("successor_created","impact",
                        "One successor assignment will be created atomically.",1),
                new ImpactItem("service_context_uncovered",uncovered>0?"blocker":"impact",
                        uncovered>0
                                ? "Resolve service assignments that would lose organization coverage."
                                : "No service assignment loses organization coverage.",uncovered)));
    }

    private ImpactAnalysis assignmentLifecycleImpact(
            AuthorizedTenantContext context, MutationCommand command, String toState) {
        var assignmentId=requireTarget(command);
        var revision=requireRevision(command);
        var effectiveTime=currentEffectiveTime(command);
        reasonCode(command);
        var assignment=requireAssignment(context,assignmentId);
        var practitionerId=findPractitionerId(context,assignment.memberId(),false);
        if (practitionerId!=null) {
            lockPractitionerForEligibility(context,practitionerId);
            assignment=requireAssignment(context,assignmentId);
        }
        var allowed=switch (toState) {
            case "suspended" -> Set.of("active");
            case "active" -> Set.of("suspended");
            case "ended" -> Set.of("active","suspended");
            default -> Set.<String>of();
        };
        if (assignment.revision()!=revision || !allowed.contains(assignment.status())) {
            throw stale("The assignment lifecycle transition is stale or invalid.");
        }
        if (!effectiveTime.isAfter(assignment.effectiveFrom())
                || (assignment.effectiveTo()!=null && effectiveTime.isAfter(assignment.effectiveTo()))) {
            throw invalid("The assignment lifecycle effective time must fall inside its effective range.");
        }
        if (toState.equals("active")) {
            requireRegistryCategory(
                    context,assignment.assignmentTypeEntryId(),assignment.assignmentTypeVersionId(),
                    "assignment_type",effectiveTime,assignment.effectiveTo());
            if (assignment.positionEntryId()!=null) {
                requireRegistryCategory(
                        context,assignment.positionEntryId(),assignment.positionVersionId(),
                        "position",effectiveTime,assignment.effectiveTo());
            }
            requireHierarchyContext(
                    context,assignment.facilityId(),assignment.organizationUnitId(),
                    assignment.locationId(),effectiveTime,assignment.effectiveTo());
            if (!engagementCovers(
                    context,assignment.memberId(),effectiveTime,assignment.effectiveTo())) {
                throw conflict("An active engagement must cover the reactivated assignment range.");
            }
        }
        var downstream=jdbc.queryForMap(
                """
                SELECT member.lock_version AS member_revision,
                       (SELECT count(*) FROM workforce_readiness_runs run
                         WHERE run.organization_id=member.organization_id
                           AND run.workforce_member_id=member.id
                           AND run.status='complete' AND run.expires_at>?) AS readiness_count,
                       (SELECT count(*) FROM practitioner_service_assignments service_assignment
                         JOIN practitioner_profiles practitioner
                           ON practitioner.organization_id=service_assignment.organization_id
                          AND practitioner.id=service_assignment.practitioner_profile_id
                         WHERE practitioner.organization_id=member.organization_id
                           AND practitioner.workforce_member_id=member.id
                           AND service_assignment.facility_id=?
                           AND service_assignment.lifecycle_state IN ('scheduled','active')
                           AND (service_assignment.effective_to IS NULL
                             OR service_assignment.effective_to>?)
                           AND NOT EXISTS(
                               SELECT 1 FROM workforce_assignments other_assignment
                               WHERE other_assignment.organization_id=member.organization_id
                                 AND other_assignment.workforce_member_id=member.id
                                 AND other_assignment.id<>?
                                 AND other_assignment.facility_id=service_assignment.facility_id
                                 AND other_assignment.lifecycle_state IN ('scheduled','active')
                                 AND other_assignment.effective_from<=greatest(
                                     service_assignment.effective_from,?::timestamptz)
                                 AND ((service_assignment.effective_to IS NULL
                                       AND other_assignment.effective_to IS NULL)
                                   OR (service_assignment.effective_to IS NOT NULL
                                       AND (other_assignment.effective_to IS NULL
                                         OR other_assignment.effective_to>=service_assignment.effective_to)))))
                           AS uncovered_service_count,
                       (SELECT count(*) FROM practitioner_service_assignments service_assignment
                         JOIN practitioner_profiles practitioner
                           ON practitioner.organization_id=service_assignment.organization_id
                          AND practitioner.id=service_assignment.practitioner_profile_id
                         WHERE practitioner.organization_id=member.organization_id
                           AND practitioner.workforce_member_id=member.id
                           AND service_assignment.facility_id=?
                           AND service_assignment.lifecycle_state='suspended'
                           AND (service_assignment.effective_to IS NULL
                             OR service_assignment.effective_to>?)) AS suspended_service_count
                FROM workforce_members member
                WHERE member.organization_id=? AND member.id=?
                """,
                Timestamp.from(command.now()),assignment.facilityId(),Timestamp.from(effectiveTime),
                assignmentId,Timestamp.from(effectiveTime),assignment.facilityId(),
                Timestamp.from(effectiveTime),context.organizationId(),assignment.memberId());
        var readinessCount=((Number) downstream.get("readiness_count")).intValue();
        var uncoveredServiceCount=((Number) downstream.get("uncovered_service_count")).intValue();
        var suspendedServiceCount=((Number) downstream.get("suspended_service_count")).intValue();
        var impactDigest=digest("assignment-lifecycle|"+assignmentId+"|"+revision+"|"
                +assignment.status()+"|"+toState+"|"+effectiveTime+"|"
                +assignment.memberId()+"|"+assignment.facilityId()+"|"
                +Objects.toString(assignment.organizationUnitId(),"")+"|"
                +Objects.toString(assignment.locationId(),"")+"|"
                +assignment.assignmentTypeEntryId()+"|"+assignment.assignmentTypeVersionId()+"|"
                +Objects.toString(assignment.positionEntryId(),"")+"|"
                +Objects.toString(assignment.positionVersionId(),"")+"|"
                +assignment.primary()+"|"+assignment.effectiveFrom()+"|"
                +Objects.toString(assignment.effectiveTo(),"")+"|"
                +downstream.get("member_revision")+"|"+readinessCount+"|"
                +uncoveredServiceCount+"|"+suspendedServiceCount);
        var serviceImpact=toState.equals("active")
                ? new ImpactItem(
                        "service_eligibility_recheck",
                        suspendedServiceCount>0?"warning":"impact",
                        suspendedServiceCount>0
                                ? "Suspended service assignments require separate eligibility re-evaluation."
                                : "No suspended service assignment requires re-evaluation.",
                        suspendedServiceCount)
                : new ImpactItem(
                        "service_context_dependencies",
                        uncoveredServiceCount>0?"blocker":"impact",
                        uncoveredServiceCount>0
                                ? "Resolve service assignments that would lose workforce coverage."
                                : "No service assignment loses workforce coverage.",
                        uncoveredServiceCount);
        return new ImpactAnalysis(impactDigest,List.of(
                serviceImpact,
                new ImpactItem("readiness_invalidated",readinessCount>0?"warning":"impact",
                        "Fresh readiness evidence will be invalidated.",readinessCount),
                new ImpactItem("historical_assignment_preserved","impact",
                        "Historical assignment attribution remains immutable.",1)));
    }

    private ImpactAnalysis memberLifecycleImpact(
            AuthorizedTenantContext context,
            MutationCommand command,
            String fromState,
            String toState) {
        var memberId=requireTarget(command);
        var revision=requireRevision(command);
        var effectiveTime=currentEffectiveTime(command);
        var categoryCode=required(command,"categoryCode");
        if (!categoryCode.matches("[a-z][a-z0-9._:-]{1,79}")) {
            throw invalid("categoryCode must be a stable lower-case machine key.");
        }
        var member=requireMember(context,memberId);
        var practitionerId=findPractitionerId(context,memberId,false);
        if (practitionerId!=null) {
            lockPractitionerForEligibility(context,practitionerId);
            member=requireMember(context,memberId);
        }
        if (member.revision()!=revision || !member.status().equals(fromState)) {
            throw stale("The member lifecycle transition is stale or invalid.");
        }
        if (member.pathway().equals("clinical")
                && !permissions(context).contains("practitioner.scope.lifecycle")) {
            throw conflict("Clinical lifecycle changes require clinical governance authority.");
        }
        String readinessDigest="none";
        var readinessWarnings=0;
        if (toState.equals("active")) {
            var run=requireReadinessRun(context,uuid(command,"readinessRunId"));
            var currentRun=Boolean.TRUE.equals(jdbc.queryForObject(
                    """
                    SELECT current_readiness_run_id=? FROM workforce_members
                    WHERE organization_id=? AND id=?
                    """,
                    Boolean.class,run.id(),context.organizationId(),memberId));
            if (!run.memberId().equals(memberId) || !run.status().equals("complete")
                    || run.blockers()!=0 || !run.expiresAt().isAfter(command.now())
                    || run.memberRevision()+1!=revision
                    || run.requestedBy().equals(context.actorId()) || !currentRun
                    || !platformReady(member.pathway())) {
                throw conflict("Reactivation requires a fresh independently evaluated zero-risk readiness result.");
            }
            readinessDigest=run.resultDigest();
            readinessWarnings=run.warnings();
        }
        var impact=jdbc.queryForMap(
                """
                SELECT
                  (SELECT count(*) FROM workforce_assignments assignment
                    WHERE assignment.organization_id=member.organization_id
                      AND assignment.workforce_member_id=member.id
                      AND assignment.lifecycle_state IN ('scheduled','active')) AS assignment_count,
                  (SELECT count(*) FROM practitioner_service_assignments service_assignment
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=service_assignment.organization_id
                     AND practitioner.id=service_assignment.practitioner_profile_id
                    WHERE practitioner.organization_id=member.organization_id
                      AND practitioner.workforce_member_id=member.id
                      AND service_assignment.lifecycle_state IN ('scheduled','active','suspended')
                      AND (service_assignment.effective_to IS NULL OR service_assignment.effective_to>?))
                      AS service_count,
                  (SELECT count(*) FROM access_assignment_scopes access_scope
                    WHERE access_scope.organization_id=member.organization_id
                      AND access_scope.workforce_member_id=member.id
                      AND access_scope.status IN ('approved','active')) AS access_count
                FROM workforce_members member
                WHERE member.organization_id=? AND member.id=?
                """,
                Timestamp.from(effectiveTime),context.organizationId(),memberId);
        var assignments=((Number) impact.get("assignment_count")).intValue();
        var services=((Number) impact.get("service_count")).intValue();
        var access=((Number) impact.get("access_count")).intValue();
        var impactDigest=digest("member-lifecycle|"+memberId+"|"+revision+"|"+fromState+"|"
                +toState+"|"+categoryCode+"|"+effectiveTime+"|"+readinessDigest+"|"
                +readinessWarnings+"|"+assignments+"|"+services+"|"+access);
        return new ImpactAnalysis(impactDigest,List.of(
                new ImpactItem("readiness_and_eligibility_reconciled","impact",
                        "Readiness and prospective eligibility are re-evaluated.",1),
                new ImpactItem("assignments_reviewed",assignments>0?"warning":"impact",
                        "Current and scheduled assignments require review.",assignments),
                new ImpactItem("service_assignments_suspended",services>0?"warning":"impact",
                        toState.equals("suspended")
                                ? "Prospective service assignments will be re-evaluated and suspended."
                                : "Service assignments are not automatically reactivated.",services),
                new ImpactItem("application_access_unchanged",access>0?"warning":"impact",
                        "Application access changes require the separate governed access workflow.",access)));
    }

    private ImpactAnalysis offboardingRequestImpact(
            AuthorizedTenantContext context, MutationCommand command) {
        var memberId=requireTarget(command);
        var revision=requireRevision(command);
        var member=requireMember(context,memberId);
        if (member.revision()!=revision) {
            throw stale("The member changed before the offboarding impact was calculated.");
        }
        if (!Set.of("active","suspended").contains(member.status())) {
            throw conflict("Only an active or suspended member may enter offboarding.");
        }
        var engagementEndAt=instant(command,"engagementEndAt");
        var effectiveAt=instant(command,"effectiveAt");
        if (effectiveAt.isBefore(engagementEndAt)) {
            throw invalid("The offboarding effective time cannot precede the engagement end.");
        }
        var accessAction=required(command,"accessAction");
        if (!Set.of("revoke_at_effective","revoke_immediately","none").contains(accessAction)) {
            throw invalid("The offboarding access action is not supported.");
        }
        if (accessAction.equals("revoke_immediately")
                && effectiveAt.isAfter(command.now().plusSeconds(5))) {
            throw invalid(
                    "Immediate access revocation requires an effective time at or before the current authorization boundary.");
        }
        requireRegistryCategory(context,uuid(command,"reasonEntryId"),uuid(command,"reasonVersionId"),
                "offboarding_reason",command.now(),effectiveAt);
        var counts=jdbc.queryForMap(
                """
                SELECT
                  (SELECT count(*) FROM employment_engagements engagement
                    WHERE engagement.organization_id=? AND engagement.workforce_member_id=?
                      AND engagement.status IN ('draft','scheduled','active','suspended')) AS engagement_count,
                  (SELECT count(*) FROM workforce_assignments assignment
                    WHERE assignment.organization_id=? AND assignment.workforce_member_id=?
                      AND assignment.lifecycle_state IN ('draft','scheduled','active','suspended')) AS assignment_count,
                  (SELECT count(*) FROM practitioner_service_assignments service_assignment
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=service_assignment.organization_id
                     AND practitioner.id=service_assignment.practitioner_profile_id
                    WHERE practitioner.organization_id=? AND practitioner.workforce_member_id=?
                      AND service_assignment.lifecycle_state IN ('draft','scheduled','active','suspended')) AS service_count,
                  (SELECT count(*) FROM practitioner_profiles practitioner
                    WHERE practitioner.organization_id=? AND practitioner.workforce_member_id=?
                      AND practitioner.lifecycle_state IN ('active','suspended')) AS practitioner_count,
                  (SELECT count(*) FROM availability_profiles availability
                    WHERE availability.organization_id=? AND availability.workforce_member_id=?
                      AND availability.lifecycle_state IN ('draft','scheduled','active')) AS availability_count,
                  (SELECT count(*) FROM access_assignment_scopes access_scope
                    WHERE access_scope.organization_id=? AND access_scope.workforce_member_id=?
                      AND access_scope.status IN ('requested','approved','active')) AS access_count
                """,
                context.organizationId(),memberId,context.organizationId(),memberId,
                context.organizationId(),memberId,context.organizationId(),memberId,
                context.organizationId(),memberId,context.organizationId(),memberId);
        var engagements=((Number) counts.get("engagement_count")).intValue();
        var assignments=((Number) counts.get("assignment_count")).intValue();
        var services=((Number) counts.get("service_count")).intValue();
        var practitioners=((Number) counts.get("practitioner_count")).intValue();
        var availability=((Number) counts.get("availability_count")).intValue();
        var access=((Number) counts.get("access_count")).intValue();
        var accessImpact=offboardingAccessImpact(context,memberId);
        var impactDigest=offboardingImpactDigest(
                context,memberId,engagementEndAt,effectiveAt,accessAction);
        var items=new ArrayList<ImpactItem>();
        items.addAll(List.of(
                new ImpactItem("engagements_ended","impact",
                        "Current engagements will end at the governed boundary.",engagements),
                new ImpactItem("assignments_ended","impact",
                        "Current and future organization assignments will end or cancel.",assignments),
                new ImpactItem("service_assignments_ended","impact",
                        "Current and future service assignments will end or cancel.",services),
                new ImpactItem("practitioner_profiles_ended","impact",
                        "Current practitioner profiles will end prospectively.",practitioners),
                new ImpactItem("availability_cancelled","impact",
                        "Current and scheduled availability will end or cancel.",availability)));
        items.add(new ImpactItem(
                "access_requires_m1_governance",
                accessImpact.activeMemberships()>0 && accessAction.equals("none")
                        ? "blocker"
                        : accessImpact.activeMemberships()>0 ? "warning" : "impact",
                accessImpact.activeMemberships()>0 && accessAction.equals("none")
                        ? "Active canonical organization access requires an explicit revocation action."
                        : "Canonical organization access and sessions will be revoked through the governed M1 boundary.",
                Math.max(access,accessImpact.activeMemberships())));
        items.add(new ImpactItem(
                "final_owner_transfer_required",
                accessImpact.finalOwnerMemberships()>0 ? "blocker" : "impact",
                "A linked final-owner role must complete the governed M1 owner-transfer workflow before offboarding.",
                accessImpact.finalOwnerMemberships()));
        items.add(new ImpactItem(
                "shared_account_link_conflict",
                accessImpact.otherLiveMemberLinks()>0 ? "blocker" : "impact",
                "An account linked to another live workforce member cannot be revoked by this plan.",
                accessImpact.otherLiveMemberLinks()));
        items.add(
                new ImpactItem("historical_attribution_preserved","impact",
                        "Historical workforce and clinical attribution is retained.",1));
        return new ImpactAnalysis(impactDigest,items);
    }

    private void requireCurrentImpact(MutationCommand command, ImpactAnalysis analysis) {
        if (analysis.blocked()) {
            throw conflict("The reviewed impact contains unresolved blockers.");
        }
        var suppliedDigest=command.fields().get("_impactDigest");
        var suppliedExpiry=command.fields().get("_impactExpiresAt");
        final Instant expiresAt;
        try {
            expiresAt=Instant.parse(Objects.requireNonNull(suppliedExpiry));
        } catch (RuntimeException exception) {
            throw stale("A fresh impact preview is required for this action.");
        }
        if (suppliedDigest==null || !suppliedDigest.equals(analysis.digest())
                || !expiresAt.isAfter(command.now())
                || expiresAt.isAfter(command.now().plusSeconds(605))) {
            throw stale("The impact preview expired or authoritative impact changed.");
        }
    }

    private String offboardingImpactDigest(
            AuthorizedTenantContext context,
            UUID memberId,
            Instant engagementEndAt,
            Instant effectiveAt,
            String accessAction) {
        var impact = jdbc.queryForMap(
                """
                SELECT member.lifecycle_state,member.lock_version,
                       (SELECT count(*) FROM employment_engagements engagement
                         WHERE engagement.organization_id=member.organization_id
                           AND engagement.workforce_member_id=member.id
                           AND engagement.status IN ('draft','scheduled','active','suspended')
                           AND (engagement.effective_to IS NULL OR engagement.effective_to>?)) AS engagements,
                       (SELECT count(*) FROM workforce_assignments assignment
                         WHERE assignment.organization_id=member.organization_id
                           AND assignment.workforce_member_id=member.id
                           AND assignment.lifecycle_state IN ('draft','scheduled','active','suspended')
                           AND (assignment.effective_to IS NULL OR assignment.effective_to>?)) AS assignments,
                       (SELECT count(*) FROM practitioner_service_assignments service_assignment
                         JOIN practitioner_profiles practitioner
                           ON practitioner.organization_id=service_assignment.organization_id
                          AND practitioner.id=service_assignment.practitioner_profile_id
                         WHERE practitioner.organization_id=member.organization_id
                           AND practitioner.workforce_member_id=member.id
                           AND service_assignment.lifecycle_state IN ('draft','scheduled','active','suspended')
                           AND (service_assignment.effective_to IS NULL
                             OR service_assignment.effective_to>?)) AS service_assignments,
                       (SELECT count(*) FROM practitioner_profiles practitioner
                         WHERE practitioner.organization_id=member.organization_id
                           AND practitioner.workforce_member_id=member.id
                           AND practitioner.lifecycle_state IN ('active','suspended')
                           AND (practitioner.effective_to IS NULL
                             OR practitioner.effective_to>?)) AS practitioner_profiles,
                       (SELECT count(*) FROM availability_profiles availability
                         WHERE availability.organization_id=member.organization_id
                           AND availability.workforce_member_id=member.id
                           AND availability.lifecycle_state IN ('draft','scheduled','active')
                           AND (availability.effective_to IS NULL
                             OR availability.effective_to>?)) AS availability_profiles,
                       (SELECT count(*) FROM access_assignment_scopes access_scope
                         WHERE access_scope.organization_id=member.organization_id
                           AND access_scope.workforce_member_id=member.id
                           AND access_scope.status IN ('requested','approved','active')
                           AND (access_scope.effective_to IS NULL OR access_scope.effective_to>?)) AS access_scopes
                       ,(SELECT coalesce(string_agg(access_evidence.evidence,','
                                                   ORDER BY access_evidence.evidence),'none')
                           FROM (
                               SELECT DISTINCT all_membership.id::text||':'
                                   ||all_membership.lock_version::text||':'
                                   ||all_membership.role_key AS evidence
                               FROM access_assignment_scopes linked_scope
                               JOIN organization_memberships linked_membership
                                 ON linked_membership.organization_id=linked_scope.organization_id
                                AND linked_membership.id=linked_scope.access_assignment_id
                               JOIN organization_memberships all_membership
                                 ON all_membership.organization_id=linked_membership.organization_id
                                AND all_membership.user_id=linked_membership.user_id
                               WHERE linked_scope.organization_id=member.organization_id
                                 AND linked_scope.workforce_member_id=member.id
                                 AND linked_scope.status IN ('approved','active')
                                 AND all_membership.status='active'
                           ) access_evidence) AS access_memberships
                FROM workforce_members member
                WHERE member.organization_id=? AND member.id=?
                """,
                Timestamp.from(engagementEndAt), Timestamp.from(effectiveAt),
                Timestamp.from(effectiveAt), Timestamp.from(effectiveAt),
                Timestamp.from(effectiveAt), Timestamp.from(effectiveAt),
                context.organizationId(), memberId);
        return digest(memberId
                + "|" + impact.get("lifecycle_state")
                + "|" + impact.get("lock_version")
                + "|" + engagementEndAt
                + "|" + effectiveAt
                + "|" + accessAction
                + "|end_at_effective|end_at_effective"
                + "|" + impact.get("engagements")
                + "|" + impact.get("assignments")
                + "|" + impact.get("service_assignments")
                + "|" + impact.get("practitioner_profiles")
                + "|" + impact.get("availability_profiles")
                + "|" + impact.get("access_scopes")
                + "|" + impact.get("access_memberships")
                + "|" + offboardingAccessImpact(context,memberId));
    }

    private OffboardingAccessImpact offboardingAccessImpact(
            AuthorizedTenantContext context, UUID memberId) {
        return jdbc.query(
                """
                WITH target_users AS (
                    SELECT DISTINCT linked_membership.user_id
                    FROM access_assignment_scopes linked_scope
                    JOIN organization_memberships linked_membership
                      ON linked_membership.organization_id=linked_scope.organization_id
                     AND linked_membership.id=linked_scope.access_assignment_id
                    WHERE linked_scope.organization_id=?
                      AND linked_scope.workforce_member_id=?
                      AND linked_scope.status IN ('approved','active')
                ), active_memberships AS (
                    SELECT membership.id,membership.role_key
                    FROM organization_memberships membership
                    JOIN target_users target ON target.user_id=membership.user_id
                    WHERE membership.organization_id=? AND membership.status='active'
                )
                SELECT count(*) AS active_memberships,
                       count(*) FILTER (WHERE role.final_owner) AS final_owner_memberships,
                       (SELECT count(DISTINCT other_scope.workforce_member_id)
                          FROM access_assignment_scopes other_scope
                          JOIN organization_memberships other_membership
                            ON other_membership.organization_id=other_scope.organization_id
                           AND other_membership.id=other_scope.access_assignment_id
                          JOIN target_users target ON target.user_id=other_membership.user_id
                          JOIN workforce_members other_member
                            ON other_member.organization_id=other_scope.organization_id
                           AND other_member.id=other_scope.workforce_member_id
                         WHERE other_scope.organization_id=?
                           AND other_scope.workforce_member_id<>?
                           AND other_scope.status IN ('approved','active')
                           AND other_member.lifecycle_state<>'offboarded') AS other_live_member_links
                FROM active_memberships membership
                JOIN authorization_roles role ON role.role_key=membership.role_key
                """,
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Unable to calculate offboarding access impact.");
                    }
                    return new OffboardingAccessImpact(
                            resultSet.getInt("active_memberships"),
                            resultSet.getInt("final_owner_memberships"),
                            resultSet.getInt("other_live_member_links"));
                },
                context.organizationId(),memberId,context.organizationId(),
                context.organizationId(),memberId);
    }

    private EligibilityEvidenceSnapshot eligibilityEvidenceSnapshot(
            AuthorizedTenantContext context,
            UUID registrationId,
            UUID qualificationId,
            UUID credentialId,
            UUID scopeId,
            UUID activityId,
            UUID assignmentId,
            UUID engagementId) {
        return jdbc.query(
                """
                WITH parameters AS (
                    SELECT ?::uuid AS organization_id,?::uuid AS registration_id,
                           ?::uuid AS qualification_id,?::uuid AS credential_id,
                           ?::uuid AS scope_id,?::uuid AS activity_id,
                           ?::uuid AS assignment_id,?::uuid AS engagement_id
                )
                SELECT coalesce(registration.lock_version,-1),
                       coalesce(qualification.lock_version,-1),
                       coalesce(credential.lock_version,-1),
                       coalesce(scope.lock_version,-1),coalesce(activity.lock_version,-1),
                       coalesce(assignment.lock_version,-1),coalesce(engagement.lock_version,-1),
                       encode(digest(concat_ws('|',
                           coalesce(registration.id::text||':'||registration.lock_version::text||':'
                               ||registration.number_digest,''),
                           coalesce(qualification.id::text||':'||qualification.lock_version::text||':'
                               ||qualification.qualification_version_id::text,''),
                           coalesce(credential.id::text||':'||credential.lock_version::text||':'
                               ||credential.content_digest||':'
                               ||coalesce(verification.evidence_digest,''),''),
                           coalesce(scope.id::text||':'||scope.lock_version::text||':'
                               ||coalesce(scope.result_digest,''),''),
                           coalesce(activity.id::text||':'||activity.lock_version::text||':'
                               ||activity.activity_version_id::text,''),
                           coalesce(assignment.id::text||':'||assignment.lock_version::text||':'
                               ||assignment.assignment_type_version_id::text,''),
                           coalesce(engagement.id::text||':'||engagement.lock_version::text,'')),
                           'sha256'),'hex') AS source_digest,
                       NULLIF(LEAST(
                           coalesce((registration.expires_on+1)::timestamp AT TIME ZONE 'UTC','infinity'),
                           coalesce((qualification.expires_on+1)::timestamp AT TIME ZONE 'UTC','infinity'),
                           coalesce((credential.expires_on+1)::timestamp AT TIME ZONE 'UTC','infinity'),
                           coalesce(scope.effective_to,'infinity'),coalesce(activity.effective_to,'infinity'),
                           coalesce(assignment.effective_to,'infinity'),
                           coalesce(engagement.effective_to,'infinity')),'infinity') AS dependency_expires_at
                FROM parameters parameter
                LEFT JOIN professional_registrations registration
                  ON registration.organization_id=parameter.organization_id
                 AND registration.id=parameter.registration_id
                LEFT JOIN qualifications qualification
                  ON qualification.organization_id=parameter.organization_id
                 AND qualification.id=parameter.qualification_id
                LEFT JOIN practitioner_credentials credential
                  ON credential.organization_id=parameter.organization_id
                 AND credential.id=parameter.credential_id
                LEFT JOIN credential_verifications verification
                  ON verification.organization_id=credential.organization_id
                 AND verification.id=credential.current_verification_id
                LEFT JOIN scopes_of_practice scope
                  ON scope.organization_id=parameter.organization_id AND scope.id=parameter.scope_id
                LEFT JOIN scope_activities activity
                  ON activity.organization_id=parameter.organization_id AND activity.id=parameter.activity_id
                LEFT JOIN workforce_assignments assignment
                  ON assignment.organization_id=parameter.organization_id
                 AND assignment.id=parameter.assignment_id
                LEFT JOIN employment_engagements engagement
                  ON engagement.organization_id=parameter.organization_id
                 AND engagement.id=parameter.engagement_id
                """,
                resultSet -> {
                    if (!resultSet.next()) throw new IllegalStateException("Eligibility evidence snapshot failed.");
                    var dependencyExpiry = resultSet.getTimestamp("dependency_expires_at");
                    return new EligibilityEvidenceSnapshot(
                            resultSet.getLong(1),resultSet.getLong(2),resultSet.getLong(3),
                            resultSet.getLong(4),resultSet.getLong(5),resultSet.getLong(6),
                            resultSet.getLong(7),resultSet.getString("source_digest"),
                            dependencyExpiry == null ? null : dependencyExpiry.toInstant());
                },
                context.organizationId(),registrationId,qualificationId,credentialId,
                scopeId,activityId,assignmentId,engagementId);
    }

    private boolean registryReferencesActive(
            AuthorizedTenantContext context, UUID memberId, Instant evaluatedAt) {
        return exists(
                """
                WITH referenced_versions(version_id) AS (
                    SELECT practitioner.profession_version_id
                    FROM practitioner_profiles practitioner
                    WHERE practitioner.organization_id=? AND practitioner.workforce_member_id=?
                      AND practitioner.lifecycle_state<>'ended'
                    UNION SELECT qualification.qualification_version_id
                    FROM qualifications qualification
                    WHERE qualification.organization_id=? AND qualification.workforce_member_id=?
                      AND qualification.status NOT IN ('rejected','superseded')
                    UNION SELECT registration.regulator_version_id
                    FROM professional_registrations registration
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=registration.organization_id
                     AND practitioner.id=registration.practitioner_profile_id
                    WHERE registration.organization_id=? AND practitioner.workforce_member_id=?
                      AND registration.status NOT IN ('revoked','expired','superseded')
                    UNION SELECT registration.registration_type_version_id
                    FROM professional_registrations registration
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=registration.organization_id
                     AND practitioner.id=registration.practitioner_profile_id
                    WHERE registration.organization_id=? AND practitioner.workforce_member_id=?
                      AND registration.status NOT IN ('revoked','expired','superseded')
                    UNION SELECT credential.credential_type_version_id
                    FROM practitioner_credentials credential
                    WHERE credential.organization_id=? AND credential.workforce_member_id=?
                      AND credential.status NOT IN ('rejected','revoked','expired','superseded')
                    UNION SELECT specialty.specialty_version_id
                    FROM practitioner_specialties specialty
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=specialty.organization_id
                     AND practitioner.id=specialty.practitioner_profile_id
                    WHERE specialty.organization_id=? AND practitioner.workforce_member_id=?
                      AND specialty.status IN ('scheduled','active')
                    UNION SELECT activity.activity_version_id
                    FROM scope_activities activity
                    JOIN scopes_of_practice scope
                      ON scope.organization_id=activity.organization_id
                     AND scope.id=activity.scope_of_practice_id
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=scope.organization_id
                     AND practitioner.id=scope.practitioner_profile_id
                    WHERE activity.organization_id=? AND practitioner.workforce_member_id=?
                      AND scope.lifecycle_state='approved' AND activity.status='active'
                    UNION SELECT activity.supervision_mode_version_id
                    FROM scope_activities activity
                    JOIN scopes_of_practice scope
                      ON scope.organization_id=activity.organization_id
                     AND scope.id=activity.scope_of_practice_id
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=scope.organization_id
                     AND practitioner.id=scope.practitioner_profile_id
                    WHERE activity.organization_id=? AND practitioner.workforce_member_id=?
                      AND scope.lifecycle_state='approved' AND activity.status='active'
                    UNION SELECT restriction.restriction_version_id
                    FROM scope_restrictions restriction
                    JOIN scopes_of_practice scope
                      ON scope.organization_id=restriction.organization_id
                     AND scope.id=restriction.scope_of_practice_id
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=scope.organization_id
                     AND practitioner.id=scope.practitioner_profile_id
                    WHERE restriction.organization_id=? AND practitioner.workforce_member_id=?
                      AND scope.lifecycle_state='approved' AND restriction.status='active'
                    UNION SELECT assignment.assignment_type_version_id
                    FROM workforce_assignments assignment
                    WHERE assignment.organization_id=? AND assignment.workforce_member_id=?
                      AND assignment.lifecycle_state IN ('scheduled','active')
                    UNION SELECT assignment.position_version_id
                    FROM workforce_assignments assignment
                    WHERE assignment.organization_id=? AND assignment.workforce_member_id=?
                      AND assignment.lifecycle_state IN ('scheduled','active')
                )
                SELECT NOT EXISTS(
                    SELECT 1 FROM referenced_versions reference
                    LEFT JOIN workforce_registry_versions version
                      ON version.organization_id=? AND version.id=reference.version_id
                    WHERE reference.version_id IS NOT NULL
                      AND (version.id IS NULL OR version.status<>'active'
                        OR version.effective_from>?
                        OR (version.effective_to IS NOT NULL AND version.effective_to<=?)))
                  AND NOT EXISTS(
                    SELECT 1 FROM employment_engagements engagement
                    WHERE engagement.organization_id=? AND engagement.workforce_member_id=?
                      AND engagement.status IN ('scheduled','active')
                      AND engagement.effective_from<=?
                      AND (engagement.effective_to IS NULL OR engagement.effective_to>?)
                      AND NOT EXISTS(
                          SELECT 1 FROM workforce_registry_definitions definition
                          JOIN workforce_registry_entries entry
                            ON entry.organization_id=definition.organization_id
                           AND entry.registry_definition_id=definition.id
                          JOIN workforce_registry_versions version
                            ON version.organization_id=entry.organization_id
                           AND version.registry_entry_id=entry.id
                          WHERE definition.organization_id=engagement.organization_id
                            AND definition.category='employment_category'
                            AND entry.entry_key=engagement.employment_category_key
                            AND definition.status='active' AND entry.status='active'
                            AND version.status='active' AND version.effective_from<=?
                            AND (version.effective_to IS NULL OR version.effective_to>?)))
                  AND (SELECT count(*)=2 FROM authorization_registry_releases release
                       WHERE release.registry_version IN ('m1-candidate-1','m2-candidate-1')
                         AND release.status='active')
                """,
                context.organizationId(),memberId,context.organizationId(),memberId,
                context.organizationId(),memberId,context.organizationId(),memberId,
                context.organizationId(),memberId,context.organizationId(),memberId,
                context.organizationId(),memberId,context.organizationId(),memberId,
                context.organizationId(),memberId,context.organizationId(),memberId,
                context.organizationId(),memberId,context.organizationId(),memberId,
                context.organizationId(),Timestamp.from(evaluatedAt),Timestamp.from(evaluatedAt),
                context.organizationId(),memberId,Timestamp.from(evaluatedAt),Timestamp.from(evaluatedAt),
                Timestamp.from(evaluatedAt),Timestamp.from(evaluatedAt));
    }

    private void requireHierarchyContext(
            AuthorizedTenantContext context,
            UUID facilityId,
            UUID unitId,
            UUID locationId,
            Instant effectiveFrom,
            Instant effectiveTo) {
        if (!exists(
                """
                SELECT EXISTS(SELECT 1 FROM facilities facility
                WHERE facility.organization_id=? AND facility.id=? AND facility.status='active'
                  AND (?::uuid IS NULL OR EXISTS(
                      SELECT 1 FROM organization_units unit
                      WHERE unit.organization_id=facility.organization_id AND unit.id=?
                        AND unit.facility_id=facility.id AND unit.status='active'
                        AND unit.effective_from<=?
                        AND ((?::timestamptz IS NULL AND unit.effective_to IS NULL)
                          OR (?::timestamptz IS NOT NULL
                            AND (unit.effective_to IS NULL OR unit.effective_to>=?))))
                  AND (?::uuid IS NULL OR EXISTS(
                      SELECT 1 FROM service_locations location
                      WHERE location.organization_id=facility.organization_id AND location.id=?
                        AND location.facility_id=facility.id
                        AND (?::uuid IS NULL OR location.unit_id IS NOT DISTINCT FROM ?)
                        AND location.status='active' AND location.effective_from<=?
                        AND ((?::timestamptz IS NULL AND location.effective_to IS NULL)
                          OR (?::timestamptz IS NOT NULL
                            AND (location.effective_to IS NULL OR location.effective_to>=?)))))
                """,
                context.organizationId(), facilityId,
                unitId, unitId, Timestamp.from(effectiveFrom), timestamp(effectiveTo),
                timestamp(effectiveTo), timestamp(effectiveTo),
                locationId, locationId, unitId, unitId, Timestamp.from(effectiveFrom),
                timestamp(effectiveTo), timestamp(effectiveTo), timestamp(effectiveTo))) {
            throw conflict("The selected hierarchy does not cover the assignment range.");
        }
    }

    private void requireRegistryCategories(
            AuthorizedTenantContext context,
            UUID entryId,
            UUID versionId,
            Set<String> categories,
            Instant effectiveAt) {
        var category=jdbc.query(
                """
                SELECT definition.category
                FROM workforce_registry_versions version
                JOIN workforce_registry_entries entry
                  ON entry.organization_id=version.organization_id
                 AND entry.id=version.registry_entry_id
                JOIN workforce_registry_definitions definition
                  ON definition.organization_id=entry.organization_id
                 AND definition.id=entry.registry_definition_id
                WHERE version.organization_id=? AND entry.id=? AND version.id=?
                  AND definition.status='active' AND entry.status='active' AND version.status='active'
                  AND version.effective_from<=?
                  AND (version.effective_to IS NULL OR version.effective_to>?)
                """,
                resultSet -> resultSet.next()?resultSet.getString(1):null,
                context.organizationId(),entryId,versionId,
                Timestamp.from(effectiveAt),Timestamp.from(effectiveAt));
        if (category==null || !categories.contains(category)) {
            throw conflict("A scope requirement references an incompatible registry version.");
        }
    }

    private static List<ScopeRequirement> parseScopeRequirements(String value) {
        if (value==null) return List.of();
        var requirements=new ArrayList<ScopeRequirement>();
        for (var encoded:value.split("\\|")) {
            if (encoded.isBlank()) continue;
            var parts=encoded.split(",",-1);
            if (parts.length!=5 || !Set.of("true","false").contains(parts[3])) {
                throw invalid("Each scope requirement must contain type, entry, version, mandatory and validity days.");
            }
            var type=parts[0];
            if (!Set.of("registration","qualification","credential","specialty","supervision","training").contains(type)) {
                throw invalid("A scope requirement type is not supported.");
            }
            try {
                var entry=nullableUuid(parts[1]);
                var version=nullableUuid(parts[2]);
                if ((entry==null)!=(version==null)) {
                    throw invalid("A scope requirement registry entry and version must be supplied together.");
                }
                var days=parts[4].isBlank()?null:Integer.valueOf(parts[4]);
                if (days!=null && (days<1 || days>3660)) {
                    throw invalid("Scope requirement validity days must be between 1 and 3660.");
                }
                requirements.add(new ScopeRequirement(
                        type,entry,version,Boolean.parseBoolean(parts[3]),days));
                if (requirements.size()>100) throw invalid("A scope definition may contain at most 100 requirements.");
            } catch (NumberFormatException exception) {
                throw invalid("Scope requirement validity days must be numeric.");
            } catch (IllegalArgumentException exception) {
                throw invalid("A scope requirement contains an invalid UUID.");
            }
        }
        return List.copyOf(requirements);
    }

    private static List<ScopeActivity> parseScopeActivities(String value) {
        var activities=new ArrayList<ScopeActivity>();
        for (var encoded:value.split("\\|")) {
            if (encoded.isBlank()) continue;
            var parts=encoded.split(",",-1);
            if (parts.length!=7) {
                throw invalid("Each scope activity must contain seven comma-separated references.");
            }
            try {
                var supervisionEntry=nullableUuid(parts[5]);
                var supervisionVersion=nullableUuid(parts[6]);
                if ((supervisionEntry==null)!=(supervisionVersion==null)) {
                    throw invalid("Scope supervision entry and version must be supplied together.");
                }
                activities.add(new ScopeActivity(
                        UUID.fromString(parts[0]),UUID.fromString(parts[1]),nullableUuid(parts[2]),
                        nullableUuid(parts[3]),nullableUuid(parts[4]),supervisionEntry,supervisionVersion));
                if (activities.size()>100) throw invalid("A scope may contain at most 100 activities.");
            } catch (IllegalArgumentException exception) {
                throw invalid("A scope activity contains an invalid UUID.");
            }
        }
        if (activities.stream().distinct().count()!=activities.size()) {
            throw invalid("Duplicate scope activities are not permitted.");
        }
        return List.copyOf(activities);
    }

    private static List<ScopeRestriction> parseScopeRestrictions(String value) {
        if (value==null) return List.of();
        var restrictions=new ArrayList<ScopeRestriction>();
        for (var encoded:value.split("\\|")) {
            if (encoded.isBlank()) continue;
            var parts=encoded.split(",",-1);
            if (parts.length!=3) {
                throw invalid("Each scope restriction must contain entry, version and display text.");
            }
            try {
                var display=parts[2].strip();
                if (display.length()<2 || display.length()>500
                        || display.codePoints().anyMatch(Character::isISOControl)) {
                    throw invalid("A scope restriction display text must contain 2 to 500 safe characters.");
                }
                restrictions.add(new ScopeRestriction(
                        UUID.fromString(parts[0]),UUID.fromString(parts[1]),display));
                if (restrictions.size()>100) throw invalid("A scope may contain at most 100 restrictions.");
            } catch (IllegalArgumentException exception) {
                throw invalid("A scope restriction contains an invalid UUID.");
            }
        }
        return List.copyOf(restrictions);
    }

    private static UUID nullableUuid(String value) {
        return value==null || value.isBlank()?null:UUID.fromString(value.strip());
    }

    private static List<Interval> parseIntervals(String value) {
        if (value == null) {
            return List.of();
        }
        var intervals = new ArrayList<Interval>();
        for (var encoded : value.split("\\|")) {
            if (encoded.isBlank()) {
                continue;
            }
            var parts = encoded.split(",", -1);
            if (parts.length != 4) {
                throw invalid("Each weekly interval must contain day,start,end,next-day.");
            }
            try {
                if (!Set.of("true","false").contains(parts[3])) {
                    throw invalid("A weekly interval next-day value must be true or false.");
                }
                var interval = new Interval(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Boolean.parseBoolean(parts[3]));
                if (interval.weekday() < 1
                        || interval.weekday() > 7
                        || interval.startMinute() < 0
                        || interval.startMinute() > 1439
                        || interval.endMinute() < 0
                        || interval.endMinute() > 1439
                        || (!interval.nextDay() && interval.endMinute() <= interval.startMinute())
                        || (interval.nextDay() && interval.endMinute() > interval.startMinute())) {
                    throw invalid("A weekly interval is outside its allowed range.");
                }
                intervals.add(interval);
                if (intervals.size()>64) {
                    throw invalid("A weekly availability profile may contain at most 64 intervals.");
                }
            } catch (NumberFormatException exception) {
                throw invalid("A weekly interval contains a non-numeric value.");
            }
        }
        return List.copyOf(intervals);
    }

    private static ZoneId timezone(String value) {
        if (!value.contains("/")) throw invalid("timezone must be an IANA area/location identifier.");
        try {
            return ZoneId.of(value);
        } catch (java.time.DateTimeException exception) {
            throw invalid("timezone must be a recognized IANA identifier.");
        }
    }

    private static List<AvailabilityException> parseExceptions(String value,ZoneId timezone) {
        if (value==null) return List.of();
        var exceptions=new ArrayList<AvailabilityException>();
        for (var encoded:value.split("\\|")) {
            if (encoded.isBlank()) continue;
            var parts=encoded.split(",",-1);
            if (parts.length<4 || parts.length>5) {
                throw invalid("Each exception must contain start,end,type,reason and optional replacements.");
            }
            try {
                var start=LocalDate.parse(parts[0]);
                var end=LocalDate.parse(parts[1]);
                if (end.isBefore(start) || end.isAfter(start.plusDays(366))) {
                    throw invalid("An availability exception range must contain at most 367 days.");
                }
                var type=parts[2];
                if (!Set.of("unavailable","replacement").contains(type)) {
                    throw invalid("An availability exception type must be unavailable or replacement.");
                }
                var reason=parts[3];
                if (!reason.matches("[a-z][a-z0-9._:-]{1,79}")) {
                    throw invalid("An availability exception reason must be a safe registry key.");
                }
                var replacements=parts.length==5
                        ? parseReplacementIntervals(parts[4],start,end,timezone)
                        : List.<ReplacementInterval>of();
                if (type.equals("replacement") && replacements.isEmpty()) {
                    throw invalid("A replacement exception requires at least one interval.");
                }
                if (type.equals("unavailable") && !replacements.isEmpty()) {
                    throw invalid("An unavailable exception cannot contain replacement intervals.");
                }
                exceptions.add(new AvailabilityException(
                        start,end,type,reason,
                        replacements.isEmpty()?null:replacementJson(replacements)));
                if (exceptions.size()>64) {
                    throw invalid("An availability batch may contain at most 64 exceptions.");
                }
            } catch (java.time.format.DateTimeParseException exception) {
                throw invalid("An availability exception contains an invalid ISO date.");
            }
        }
        return List.copyOf(exceptions);
    }

    private static List<ReplacementInterval> parseReplacementIntervals(
            String value,LocalDate startDate,LocalDate endDate,ZoneId timezone) {
        var intervals=new ArrayList<ReplacementInterval>();
        for (var encoded:value.split(";")) {
            if (encoded.isBlank()) continue;
            var parts=encoded.split("-",-1);
            if (parts.length!=3 || !Set.of("true","false").contains(parts[2])) {
                throw invalid("Each replacement interval must contain start-end-next-day.");
            }
            try {
                var interval=new ReplacementInterval(
                        Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),
                        Boolean.parseBoolean(parts[2]));
                if (interval.startMinute()<0 || interval.startMinute()>1439
                        || interval.endMinute()<0 || interval.endMinute()>1439
                        || (!interval.nextDay() && interval.endMinute()<=interval.startMinute())
                        || (interval.nextDay() && interval.endMinute()>interval.startMinute())) {
                    throw invalid("A replacement interval is outside its allowed range.");
                }
                var normalizedEnd=interval.endMinute()+(interval.nextDay()?1440:0);
                if (intervals.stream().anyMatch(existing -> {
                    var existingEnd=existing.endMinute()+(existing.nextDay()?1440:0);
                    return existing.startMinute()<normalizedEnd && interval.startMinute()<existingEnd;
                })) {
                    throw invalid("Replacement availability intervals cannot overlap.");
                }
                for (var date=startDate;!date.isAfter(endDate);date=date.plusDays(1)) {
                    requireUnambiguous(date,interval.startMinute(),timezone);
                    requireUnambiguous(
                            interval.nextDay()?date.plusDays(1):date,
                            interval.endMinute(),timezone);
                }
                intervals.add(interval);
                if (intervals.size()>32) {
                    throw invalid("A replacement exception may contain at most 32 intervals.");
                }
            } catch (NumberFormatException exception) {
                throw invalid("A replacement interval contains a non-numeric value.");
            }
        }
        return List.copyOf(intervals);
    }

    private static void requireUnambiguous(LocalDate date,int minute,ZoneId timezone) {
        var local=LocalDateTime.of(date,java.time.LocalTime.of(minute/60,minute%60));
        if (timezone.getRules().getValidOffsets(local).size()!=1) {
            throw invalid("An availability exception crosses an ambiguous or nonexistent local time.");
        }
    }

    private static String replacementJson(List<ReplacementInterval> intervals) {
        return intervals.stream()
                .map(interval -> "{\"startMinute\":"+interval.startMinute()
                        +",\"endMinute\":"+interval.endMinute()
                        +",\"endsNextDay\":"+interval.nextDay()+"}")
                .collect(java.util.stream.Collectors.joining(",","[","]"));
    }

    private boolean exists(String sql, Object... arguments) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, arguments));
    }

    private static Gate gate(String key, boolean complete, String deepLink) {
        return new Gate(
                key,
                complete ? "complete" : "blocked",
                complete ? null : key.replace('.', '_') + "_missing",
                complete ? null : "complete_required_step",
                "server_evaluation",
                null,
                digest(key+"|"+(complete?"complete":"blocked")),
                deepLink);
    }

    private static UUID member(MutationCommand command) {
        var memberId = command.memberId() == null ? command.targetId() : command.memberId();
        if (memberId == null) {
            throw invalid("memberId or targetId is required for this action.");
        }
        return memberId;
    }

    private static UUID requireTarget(MutationCommand command) {
        if (command.targetId() == null) {
            throw invalid("targetId is required for this action.");
        }
        return command.targetId();
    }

    private static long requireRevision(MutationCommand command) {
        if (command.expectedRevision() == null) {
            throw new WorkforceException(
                    WorkforceException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match revision is required.");
        }
        return command.expectedRevision();
    }

    private static String required(MutationCommand command, String key) {
        var value = command.fields().get(key);
        if (value == null || value.isBlank()) {
            throw invalid(key + " is required.");
        }
        return value.strip();
    }

    private static String optional(MutationCommand command, String key) {
        var value = command.fields().get(key);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static UUID uuid(MutationCommand command, String key) {
        try {
            return UUID.fromString(required(command, key));
        } catch (IllegalArgumentException exception) {
            throw invalid(key + " must be a UUID.");
        }
    }

    private static UUID optionalUuid(MutationCommand command, String key) {
        var value = optional(command, key);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(key + " must be a UUID.");
        }
    }

    private static LocalDate date(MutationCommand command, String key) {
        try {
            return LocalDate.parse(required(command, key));
        } catch (Exception exception) {
            throw invalid(key + " must be an ISO date.");
        }
    }

    private static LocalDate optionalDate(MutationCommand command, String key) {
        var value = optional(command, key);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception exception) {
            throw invalid(key + " must be an ISO date.");
        }
    }

    private static Instant instant(MutationCommand command, String key) {
        try {
            return Instant.parse(required(command, key));
        } catch (Exception exception) {
            throw invalid(key + " must be an ISO instant.");
        }
    }

    private static Instant optionalInstant(MutationCommand command, String key) {
        var value = optional(command, key);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception exception) {
            throw invalid(key + " must be an ISO instant.");
        }
    }

    private static boolean bool(MutationCommand command, String key) {
        var value = required(command, key);
        if (!value.equals("true") && !value.equals("false")) {
            throw invalid(key + " must be true or false.");
        }
        return Boolean.parseBoolean(value);
    }

    private static void requireRange(Instant start, Instant end) {
        if (end != null && !end.isAfter(start)) {
            throw invalid("The effective end must follow the effective start.");
        }
    }

    private static void requireAdultBirthDate(LocalDate birthDate, Instant now) {
        if (birthDate == null) return;
        var today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (birthDate.isAfter(today) || birthDate.plusYears(18).isAfter(today)) {
            throw invalid("Workforce members must be at least 18 years old.");
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instantValue(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toInstant() : null;
    }

    private static LocalDate localDateValue(Object value) {
        if (value instanceof LocalDate localDate) return localDate;
        if (value instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
        return null;
    }

    private boolean platformReady(String pathway) {
        var required = new ArrayList<PlatformCapability>();
        required.add(PlatformCapability.REDIS_JOB_QUEUE);
        required.add(PlatformCapability.WORKER_EXECUTION);
        required.add(PlatformCapability.SCHEDULER_EXECUTION);
        if ("clinical".equals(pathway)) {
            required.add(PlatformCapability.PRIVATE_DOCUMENT_QUARANTINE);
            required.add(PlatformCapability.MALWARE_SCANNING);
            required.add(PlatformCapability.DOCUMENT_PROMOTION);
            required.add(PlatformCapability.SIGNED_DOCUMENT_ACCESS);
            required.add(PlatformCapability.DOCUMENT_RETENTION);
            required.add(PlatformCapability.DURABLE_NOTIFICATION_DELIVERY);
        }
        return required.stream().allMatch(capability ->
                capabilities.status(capability).availability() == CapabilityAvailability.AVAILABLE);
    }

    private boolean isSubjectActor(AuthorizedTenantContext context, UUID memberId) {
        return exists(
                """
                SELECT EXISTS(SELECT 1 FROM access_assignment_scopes scope
                JOIN organization_memberships membership
                  ON membership.organization_id=scope.organization_id
                 AND membership.id=scope.access_assignment_id
                WHERE scope.organization_id=? AND scope.workforce_member_id=?
                  AND scope.status IN ('approved','active')
                  AND scope.effective_from<=clock_timestamp()
                  AND (scope.effective_to IS NULL OR scope.effective_to>clock_timestamp())
                  AND membership.user_id=? AND membership.status='active')
                """,
                context.organizationId(),
                memberId,
                context.actorId());
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate workforce evidence digest.", exception);
        }
    }

    private static String uuidJson(UUID value) {
        return value==null?"null":"\""+value+"\"";
    }

    private static LinkedHashMap<String, Object> ordered(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (var index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static MutationResult result(
            UUID subjectId,
            String subjectType,
            String auditEvent,
            String outboxEvent,
            String aggregateType,
            Map<String, Object> auditPayload,
            Map<String, Object> outboxPayload,
            int statusCode,
            long revision) {
        return new MutationResult(
                subjectId,
                subjectType,
                auditEvent,
                outboxEvent,
                aggregateType,
                auditPayload,
                outboxPayload,
                statusCode,
                revision);
    }

    private static MutationResult result(
            UUID subjectId,
            String subjectType,
            String auditEvent,
            String outboxEvent,
            String aggregateType,
            Map<String, Object> auditPayload,
            Map<String, Object> outboxPayload,
            int statusCode,
            long revision,
            List<MutationEvidence> additionalEvidence) {
        return new MutationResult(
                subjectId,
                subjectType,
                auditEvent,
                outboxEvent,
                aggregateType,
                auditPayload,
                outboxPayload,
                statusCode,
                revision,
                additionalEvidence);
    }

    private static void requireChanged(int changed, String message) {
        if (changed != 1) {
            throw stale(message);
        }
    }

    private static WorkforceException invalid(String message) {
        return new WorkforceException(WorkforceException.Reason.INVALID, message);
    }

    private static WorkforceException notFound(String message) {
        return new WorkforceException(WorkforceException.Reason.NOT_FOUND, message);
    }

    private static WorkforceException conflict(String message) {
        return new WorkforceException(WorkforceException.Reason.CONFLICT, message);
    }

    private static WorkforceException stale(String message) {
        return new WorkforceException(WorkforceException.Reason.STALE, message);
    }

    private record Member(
            UUID id,
            String pathway,
            String status,
            String accessIntent,
            UUID currentReadinessRunId,
            long revision) {}

    private record PersonIdentityLink(UUID linkId, UUID personId) {}

    private record PersonMatchCase(
            UUID memberId,
            UUID currentLinkId,
            UUID personId,
            String displayName,
            String memberNumber,
            long revision,
            Instant updatedAt) {}

    private record PersonMatchCandidate(
            UUID linkId, String displayLabel, UUID liveMemberId, List<String> keyTypes) {
        private PersonMatchCandidate {
            keyTypes = List.copyOf(keyTypes);
        }
    }

    private record CredentialEvidenceSummary(
            UUID documentId,
            String mediaType,
            long byteCount,
            String declaredDigest,
            String promotionDigest) {}

    private record PersonMergeMember(
            UUID memberId,
            UUID linkId,
            UUID personId,
            String memberStatus,
            long memberRevision,
            String linkStatus,
            long linkRevision) {}

    private record PersonMergeRequest(
            UUID id,
            UUID retainedLinkId,
            UUID discardedLinkId,
            UUID retainedMemberId,
            UUID discardedMemberId,
            UUID requestedBy,
            String candidateDigest,
            String impactDigest,
            String state,
            UUID decisionBy,
            long revision) {}

    private record Credential(
            UUID id,
            UUID memberId,
            UUID practitionerId,
            String status,
            long revision,
            String digest,
            LocalDate expiresOn,
            UUID supersedesId) {}

    private record ReviewLease(UUID reviewerId, Instant expiresAt) {}

    private record CredentialPredecessor(
            UUID practitionerId, String status, long revision) {}

    private record LifecyclePredecessor(String status, long revision) {}

    private record RegistrationPredecessor(
            String status, LocalDate expiresOn, long revision) {}

    private record SpecialtyPredecessor(
            UUID versionId,
            String designation,
            Instant effectiveFrom,
            Instant effectiveTo,
            String status,
            long revision) {}

    private record Scope(
            UUID id,
            UUID practitionerId,
            UUID definitionId,
            String definitionDigest,
            String resultDigest,
            Instant effectiveFrom,
            Instant effectiveTo,
            UUID supersedesId,
            String status,
            long revision) {}

    private record Assignment(
            UUID id,
            UUID memberId,
            UUID facilityId,
            UUID organizationUnitId,
            UUID locationId,
            UUID assignmentTypeEntryId,
            UUID assignmentTypeVersionId,
            UUID positionEntryId,
            UUID positionVersionId,
            boolean primary,
            Instant effectiveFrom,
            Instant effectiveTo,
            String status,
            long revision) {}

    private record ReadinessRun(
            UUID id,
            UUID memberId,
            long memberRevision,
            String configurationDigest,
            String status,
            int blockers,
            int warnings,
            String resultDigest,
            Instant expiresAt,
            UUID requestedBy,
            long revision) {}

    private record Activation(
            UUID id,
            UUID memberId,
            long memberRevision,
            UUID runId,
            String resultDigest,
            UUID makerId,
            String status,
            Instant expiresAt,
            long revision) {}

    private record Offboarding(
            UUID id,
            UUID memberId,
            String impactDigest,
            Instant engagementEndAt,
            Instant effectiveAt,
            String accessAction,
            UUID checkerId,
            String status,
            long revision) {}

    private record OffboardingMembership(
            UUID id, UUID userId, String roleKey, long revision) {}

    private record OffboardingAccessImpact(
            int activeMemberships,
            int finalOwnerMemberships,
            int otherLiveMemberLinks) {}

    private record ExportJob(
            UUID id,
            UUID requesterId,
            String projection,
            String format,
            String filterDigest,
            String purposeKey,
            String status,
            long revision) {
        boolean restricted() {
            return projection.endsWith("-detail-v1");
        }
    }

    private record ExportRetrySource(
            UUID id,
            UUID requesterId,
            String purposeKey,
            String legalBasisKey,
            String projection,
            String filterDigest,
            String sortDigest,
            String format,
            int rowLimit,
            long sizeLimitBytes,
            String policyVersion,
            String filtersJson,
            UUID memberId,
            String status,
            String failureCode,
            long revision,
            Instant failedAt) {
        boolean restricted() {
            return projection.endsWith("-detail-v1");
        }
    }

    private record EligibilityScope(UUID id,UUID activityId,boolean supervisionRequired) {}

    private record ServiceAssignmentReconciliationSource(
            UUID practitionerId,
            UUID serviceId,
            UUID facilityId,
            UUID locationId,
            UUID supervisorId,
            Instant effectiveFrom,
            Instant effectiveTo,
            String state,
            long revision,
            UUID activityEntryId) {}

    private record EligibilityEvidenceSnapshot(
            long registrationRevision,
            long qualificationRevision,
            long credentialRevision,
            long scopeRevision,
            long activityRevision,
            long assignmentRevision,
            long engagementRevision,
            String digest,
            Instant dependencyExpiresAt) {}

    private record Configuration(UUID id, String digest, Instant effectiveAt) {}

    private record NotificationTarget(
            UUID templateEntryId, UUID templateVersionId, UUID recipientId) {}

    private record ExpirySource(
            String sourceType, UUID id, UUID memberId, LocalDate expiresOn, long revision) {}

    private record Gate(
            String key,
            String outcome,
            String reasonCode,
            String remediationCode,
            String evidenceType,
            UUID evidenceId,
            String evidenceDigest,
            String deepLink) {}

    private record Interval(int weekday, int startMinute, int endMinute, boolean nextDay) {}

    private record ReplacementInterval(int startMinute,int endMinute,boolean nextDay) {}

    private record AvailabilityException(
            LocalDate startDate,LocalDate endDate,String type,String reasonCode,String replacementJson) {}

    private record ScopeActivity(
            UUID entryId,
            UUID versionId,
            UUID serviceId,
            UUID facilityId,
            UUID locationId,
            UUID supervisionEntryId,
            UUID supervisionVersionId) {}

    private record ScopeRestriction(UUID entryId,UUID versionId,String displayText) {}

    private record ScopeRequirement(
            String type,UUID entryId,UUID versionId,boolean mandatory,Integer validityWindowDays) {}

    private record RegistryVersion(
            UUID id,
            UUID entryId,
            UUID definitionId,
            int versionNumber,
            String fieldsJson,
            String digest,
            UUID makerId,
            UUID checkerId,
            String status,
            long revision) {}

    private record RegistryPredecessor(UUID id, String digest, long revision) {}

    private record ConfigurationPredecessor(
            UUID id, UUID parentId, String digest, long revision) {}

    private record RegistryEntryContext(
            String entryLabel,
            String lifecycleState,
            String definitionLabel,
            String category,
            boolean enabled) {}

    private record RegistryValidationState(
            UUID parentSnapshotId,
            UUID activeSnapshotId,
            String activeSnapshotDigest,
            Long baselineRevision,
            String baselineDigest,
            UUID activeVersionId,
            Integer activeVersionNumber,
            String activeVersionDigest,
            Boolean candidateEnabled) {}

    private record RegistryChange(
            UUID requestId,
            UUID itemId,
            String requestStatus,
            String itemStatus,
            String validationDigest,
            Instant validationExpiresAt,
            String decisionDigest,
            Instant decisionExpiresAt) {}
}
