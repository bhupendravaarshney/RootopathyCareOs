package com.rootopathy.careos.assessment.infrastructure;

import com.rootopathy.careos.assessment.application.AssessmentException;
import com.rootopathy.careos.assessment.application.AssessmentStore;
import com.rootopathy.careos.assessment.domain.AssessmentScreen;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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

/** PostgreSQL-backed COS projections and governed clinical assessment mutations. */
@Repository
public class JdbcAssessmentStore implements AssessmentStore {
    private static final String POLICY_VERSION = "m6-standing-direction-v1";
    private static final String ATTESTATION = "structural-clinician-review-v1";
    private static final List<String> TITLES = List.of(
            "Consultation context",
            "Patient story",
            "Presenting concerns",
            "Clinical timeline",
            "Medication review",
            "Allergies and safety",
            "Investigations",
            "Vital signs",
            "Clinical examination",
            "Red-flag assessment",
            "Problem list",
            "Differential assessment",
            "ROOT360 overview",
            "PhysioCore assessment",
            "Mind and narrative",
            "Lifestyle and environment",
            "Integrative evidence review",
            "Clinical synthesis",
            "Priorities and goals",
            "Coordinated care plan",
            "Intervention safety",
            "Consent and shared decision",
            "Document review",
            "AI-assisted synthesis",
            "Clinician review and approval",
            "Monitoring and follow-up",
            "Confirm and close");

    private final JdbcTemplate jdbc;

    public JdbcAssessmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Projection projection(AuthorizedTenantContext context, ScreenQuery query) {
        requireOperationScope(context);
        var rows = new ArrayList<>(sessionRows(context, query));
        if (query.screenId().equals("COS-01")) {
            rows.addAll(encounterCandidateRows(context, query));
        }
        var projected = rows.stream()
                .map(row -> withAllowedActions(query.screenId(), row))
                .limit(query.limit())
                .toList();
        return new Projection(
                metrics(context),
                columns(),
                projected,
                notices(query.screenId()),
                databaseNow());
    }

    @Override
    public Set<String> permissions(AuthorizedTenantContext context) {
        requireOperationScope(context);
        return Set.copyOf(jdbc.queryForList(
                """
                SELECT DISTINCT grants.permission_key
                FROM organization_memberships memberships
                JOIN authorization_roles roles ON roles.role_key=memberships.role_key
                JOIN authorization_role_permissions grants ON grants.role_key=roles.role_key
                JOIN authorization_permissions permissions
                  ON permissions.permission_key=grants.permission_key
                WHERE memberships.organization_id=? AND memberships.user_id=?
                  AND memberships.status='active'
                  AND memberships.effective_from<=clock_timestamp()
                  AND (memberships.effective_to IS NULL OR memberships.effective_to>clock_timestamp())
                  AND roles.status='active' AND roles.interactive
                  AND permissions.status='active'
                  AND EXISTS(SELECT 1 FROM authorization_registry_releases release
                      WHERE release.registry_version=roles.registry_version AND release.status='active')
                  AND EXISTS(SELECT 1 FROM authorization_registry_releases release
                      WHERE release.registry_version=permissions.registry_version AND release.status='active')
                """,
                String.class,
                context.organizationId(),
                context.actorId()));
    }

    @Override
    public MutationResult mutate(AuthorizedTenantContext context, MutationCommand command) {
        requireOperationScope(context);
        return switch (command.actionKey()) {
            case "start-assessment" -> startAssessment(context, command);
            case "save-section-response" -> saveResponse(context, command);
            case "record-measurement" -> recordMeasurement(context, command);
            case "record-red-flag" -> recordRedFlag(context, command);
            case "acknowledge-red-flag", "resolve-red-flag" ->
                progressRedFlag(context, command);
            case "submit-assessment-review" -> reviewAssessment(context, command);
            case "sign-assessment" -> signAssessment(context, command);
            case "amend-assessment" -> amendAssessment(context, command);
            case "return-assessment-to-draft", "complete-assessment", "cancel-assessment",
                    "enter-assessment-in-error" -> transitionAssessment(context, command);
            default -> throw notFound("The requested assessment action does not exist.");
        };
    }

    private List<AssessmentScreen.Row> sessionRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        var search = query.search();
        if (search != null) requireLiteralSearch(search, 2, "Assessment search");
        return jdbc.query(
                """
                SELECT session.id,session.encounter_id,session.patient_id,
                       session.responsible_practitioner_id,session.status,
                       session.source_package_status,session.started_at,session.updated_at,
                       session.lock_version,section.id section_id,section.status section_status,
                       section.response_count,patient.patient_number,patient.verification_state,
                       coalesce(patient.name_to_use,
                         nullif(concat_ws(' ',patient.official_given_name,patient.official_family_name),''),
                         'Patient '||left(patient.id::text,8)) patient_label,
                       encounter.status encounter_status,encounter.encounter_type_key,
                       participant.display_name_snapshot clinician_label,
                       (SELECT count(*) FROM assessment_sections completed
                         WHERE completed.organization_id=session.organization_id
                           AND completed.assessment_session_id=session.id
                           AND completed.status='complete') completed_sections,
                       (SELECT count(*) FROM patient_safety_flags safety
                         WHERE safety.organization_id=session.organization_id
                           AND safety.patient_id=session.patient_id
                           AND safety.status IN ('provisional','active')) patient_alerts,
                       (SELECT count(*) FROM red_flags flag
                         WHERE flag.organization_id=session.organization_id
                           AND flag.assessment_session_id=session.id
                           AND flag.status<>'resolved') open_red_flags,
                       (SELECT left(version.content_text,240)
                          FROM assessment_responses response
                          JOIN response_versions version
                            ON version.organization_id=response.organization_id
                           AND version.id=response.current_version_id
                         WHERE response.organization_id=session.organization_id
                           AND response.assessment_section_id=section.id
                           AND response.status='draft'
                         ORDER BY response.updated_at DESC,response.id LIMIT 1) latest_response
                FROM assessment_sessions session
                JOIN assessment_sections section
                  ON section.organization_id=session.organization_id
                 AND section.assessment_session_id=session.id AND section.screen_id=?
                JOIN patient_profiles patient
                  ON patient.organization_id=session.organization_id AND patient.id=session.patient_id
                JOIN encounters encounter
                  ON encounter.organization_id=session.organization_id AND encounter.id=session.encounter_id
                JOIN encounter_participants participant
                  ON participant.organization_id=session.organization_id
                 AND participant.encounter_id=session.encounter_id
                 AND participant.practitioner_profile_id=session.responsible_practitioner_id
                 AND participant.role_key='responsible_clinician' AND participant.status='active'
                WHERE session.organization_id=?
                  AND (CAST(? AS uuid) IS NULL OR session.patient_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR session.encounter_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR session.id=CAST(? AS uuid))
                  AND (CAST(? AS text) IS NULL OR session.status=CAST(? AS text))
                  AND (CAST(? AS text) IS NULL OR position(lower(CAST(? AS text)) in lower(
                    patient.patient_number||' '||coalesce(patient.name_to_use,'')||' '||
                    coalesce(patient.official_given_name,'')||' '||coalesce(patient.official_family_name,'')))>0)
                ORDER BY session.updated_at DESC,session.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", "assessment",
                                "primary", resultSet.getString("patient_label"),
                                "secondary", maskPatientNumber(resultSet.getString("patient_number")),
                                "context", resultSet.getString("encounter_type_key") + " · "
                                        + resultSet.getString("encounter_status"),
                                "clinician", resultSet.getString("clinician_label"),
                                "patientVerification", resultSet.getString("verification_state"),
                                "patientAlerts", Long.toString(resultSet.getLong("patient_alerts")),
                                "sectionStatus", resultSet.getString("section_status"),
                                "completion", resultSet.getLong("completed_sections") + "/27",
                                "openRedFlags", Long.toString(resultSet.getLong("open_red_flags")),
                                "latestResponse", resultSet.getString("latest_response"),
                                "sourcePackageStatus", resultSet.getString("source_package_status"),
                                "responsiblePractitionerId",
                                resultSet.getObject("responsible_practitioner_id", UUID.class).toString(),
                                "sectionId", resultSet.getObject("section_id", UUID.class).toString(),
                                "startedAt", instant(resultSet.getTimestamp("started_at")),
                                "updatedAt", instant(resultSet.getTimestamp("updated_at")))),
                query.screenId(),
                context.organizationId(),
                query.patientId(),
                query.patientId(),
                query.encounterId(),
                query.encounterId(),
                query.assessmentSessionId(),
                query.assessmentSessionId(),
                query.status(),
                query.status(),
                search,
                search,
                query.limit());
    }

    private List<AssessmentScreen.Row> encounterCandidateRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                SELECT encounter.id,encounter.patient_id,encounter.responsible_practitioner_id,
                       encounter.status,encounter.encounter_type_key,encounter.lock_version,
                       patient.patient_number,patient.verification_state,
                       coalesce(patient.name_to_use,
                         nullif(concat_ws(' ',patient.official_given_name,patient.official_family_name),''),
                         'Patient '||left(patient.id::text,8)) patient_label,
                       participant.display_name_snapshot clinician_label,
                       (SELECT count(*) FROM patient_safety_flags safety
                         WHERE safety.organization_id=encounter.organization_id
                           AND safety.patient_id=encounter.patient_id
                           AND safety.status IN ('provisional','active')) patient_alerts
                FROM encounters encounter
                JOIN patient_profiles patient
                  ON patient.organization_id=encounter.organization_id AND patient.id=encounter.patient_id
                JOIN encounter_participants participant
                  ON participant.organization_id=encounter.organization_id
                 AND participant.encounter_id=encounter.id
                 AND participant.practitioner_profile_id=encounter.responsible_practitioner_id
                 AND participant.role_key='responsible_clinician' AND participant.status='active'
                WHERE encounter.organization_id=? AND encounter.status IN ('in_progress','on_hold')
                  AND (CAST(? AS uuid) IS NULL OR encounter.patient_id=CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR encounter.id=CAST(? AS uuid))
                  AND NOT EXISTS (SELECT 1 FROM assessment_sessions session
                    WHERE session.organization_id=encounter.organization_id
                      AND session.encounter_id=encounter.id)
                ORDER BY encounter.updated_at DESC,encounter.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> row(
                        query.screenId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("id", UUID.class),
                        null,
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version"),
                        values(
                                "$kind", "encounter_candidate",
                                "primary", resultSet.getString("patient_label"),
                                "secondary", maskPatientNumber(resultSet.getString("patient_number")),
                                "context", resultSet.getString("encounter_type_key") + " · ready for assessment",
                                "clinician", resultSet.getString("clinician_label"),
                                "patientVerification", resultSet.getString("verification_state"),
                                "patientAlerts", Long.toString(resultSet.getLong("patient_alerts")),
                                "responsiblePractitionerId",
                                resultSet.getObject("responsible_practitioner_id", UUID.class).toString(),
                                "sourcePackageStatus", "unavailable")),
                context.organizationId(),
                query.patientId(),
                query.patientId(),
                query.encounterId(),
                query.encounterId(),
                query.limit());
    }

    private List<AssessmentScreen.Metric> metrics(AuthorizedTenantContext context) {
        var values = jdbc.queryForMap(
                """
                SELECT count(*) sessions,
                       count(*) FILTER (WHERE status='in_progress') in_progress,
                       count(*) FILTER (WHERE status='in_review') in_review,
                       (SELECT count(*) FROM red_flags flag
                         WHERE flag.organization_id=? AND flag.status<>'resolved') open_flags
                FROM assessment_sessions WHERE organization_id=?
                """,
                context.organizationId(),
                context.organizationId());
        return List.of(
                metric("sessions", "Assessments", number(values.get("sessions")), "neutral"),
                metric("inProgress", "In progress", number(values.get("in_progress")), "info"),
                metric("inReview", "In review", number(values.get("in_review")), "warning"),
                metric("openFlags", "Open red flags", number(values.get("open_flags")), "critical"));
    }

    private static List<AssessmentScreen.Column> columns() {
        return List.of(
                column("primary", "Patient"),
                column("context", "Encounter"),
                column("clinician", "Responsible clinician"),
                column("sectionStatus", "Step status"),
                column("completion", "Completion"),
                column("openRedFlags", "Open red flags"));
    }

    private static List<AssessmentScreen.Notice> notices(String screenId) {
        var notices = new ArrayList<AssessmentScreen.Notice>();
        notices.add(notice(
                "warning",
                "Protected COS source package unavailable",
                "The 27-step order and current route titles are preserved, but visual/source acceptance and local clinical policy remain fail closed."));
        notices.add(notice(
                "info",
                "Versioned autosave",
                "Each save appends attributed source, method, interpretation and uncertainty evidence; stale revisions are rejected."));
        if (screenId.equals("COS-10")) {
            notices.add(notice(
                    "warning",
                    "Red flags remain visible",
                    "Review, signing and completion are blocked until each flag is explicitly acknowledged and resolved."));
        }
        if (screenId.equals("COS-24")) {
            notices.add(notice(
                    "warning",
                    "AI synthesis unavailable",
                    "This protected step is retained without a mutation until the separately governed Module 8 boundary is active."));
        }
        if (screenId.equals("COS-25")) {
            notices.add(notice(
                    "warning",
                    "Eligible clinician signature required",
                    "Signing requires exact actor/practitioner correlation, current eligibility, recent authentication and MFA."));
        }
        if (screenId.equals("COS-26")) {
            notices.add(notice(
                    "info",
                    "No composite cure score",
                    "Each measure retains purpose, baseline, source, method, unit, cadence, owner and action threshold independently."));
        }
        return List.copyOf(notices);
    }

    private static AssessmentScreen.Row withAllowedActions(
            String screenId, AssessmentScreen.Row row) {
        var actions = new ArrayList<String>();
        var kind = row.values().getOrDefault("$kind", "");
        var sequence = Integer.parseInt(screenId.substring(4));
        if (kind.equals("encounter_candidate") && sequence == 1) {
            actions.add("start-assessment");
        }
        if (kind.equals("assessment")) {
            if (sequence == 1 && row.status().equals("in_progress")) {
                actions.add("cancel-assessment");
                actions.add("enter-assessment-in-error");
            }
            if (row.status().equals("in_progress")
                    && sequence >= 2
                    && sequence <= 23
                    && sequence != 24) {
                actions.add("save-section-response");
            }
            if (row.status().equals("in_progress") && sequence == 26) {
                actions.add("save-section-response");
            }
            if (row.status().equals("in_progress") && (sequence == 8 || sequence == 26)) {
                actions.add("record-measurement");
            }
            if (row.status().equals("in_progress") && sequence == 10) {
                actions.add("record-red-flag");
                var openFlags = Long.parseLong(row.values().getOrDefault("openRedFlags", "0"));
                if (openFlags > 0) {
                    actions.add("acknowledge-red-flag");
                    actions.add("resolve-red-flag");
                }
            }
            if (sequence == 25) {
                switch (row.status()) {
                    case "in_progress" -> actions.add("submit-assessment-review");
                    case "in_review" -> {
                        actions.add("sign-assessment");
                        actions.add("return-assessment-to-draft");
                    }
                    case "signed", "amended" -> actions.add("amend-assessment");
                    default -> {
                        // Terminal assessment rows are evidence-only.
                    }
                }
            }
            if (sequence == 27
                    && (row.status().equals("signed") || row.status().equals("amended"))) {
                actions.add("complete-assessment");
            }
        }
        var publicValues = new LinkedHashMap<>(row.values());
        publicValues.remove("$kind");
        return new AssessmentScreen.Row(
                row.id(),
                row.patientId(),
                row.encounterId(),
                row.assessmentSessionId(),
                row.status(),
                row.revision(),
                row.etag(),
                publicValues,
                actions);
    }

    private MutationResult startAssessment(
            AuthorizedTenantContext context, MutationCommand command) {
        var encounterId = firstNonNull(command.encounterId(), command.targetId());
        if (encounterId == null) throw invalid("encounterId or targetId is required.");
        var encounter = encounter(context, encounterId, true);
        if (!encounter.status().equals("in_progress") && !encounter.status().equals("on_hold")) {
            throw conflict("The encounter must be in progress or on hold before assessment starts.");
        }
        if (command.patientId() != null && !command.patientId().equals(encounter.patientId())) {
            throw invalid("patientId does not match the selected encounter.");
        }
        var practitionerId = fieldUuid(command, "responsiblePractitionerId");
        if (!practitionerId.equals(encounter.practitionerId())) {
            throw conflict("The responsible clinician must match the encounter assignment.");
        }
        var sessionId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO assessment_sessions
                  (id,organization_id,encounter_id,patient_id,responsible_practitioner_id,
                   status,source_package_status,started_at,policy_version,created_by,updated_by)
                VALUES (?,?,?,?,?,'in_progress','unavailable',?,?,?,?)
                """,
                sessionId,
                context.organizationId(),
                encounter.id(),
                encounter.patientId(),
                practitionerId,
                Timestamp.from(command.now()),
                POLICY_VERSION,
                context.actorId(),
                context.actorId());
        for (var index = 0; index < TITLES.size(); index++) {
            jdbc.update(
                    """
                    INSERT INTO assessment_sections
                      (id,organization_id,assessment_session_id,screen_id,sequence_number,title,
                       status,response_count,source_package_verified,created_by,updated_by)
                    VALUES (?,?,?,?,?,?,'not_started',0,false,?,?)
                    """,
                    UuidV7Generator.randomUuid(),
                    context.organizationId(),
                    sessionId,
                    "COS-%02d".formatted(index + 1),
                    index + 1,
                    TITLES.get(index),
                    context.actorId(),
                    context.actorId());
        }
        var audit = map(
                "assessmentSessionId", sessionId,
                "encounterId", encounter.id(),
                "patientId", encounter.patientId(),
                "responsiblePractitionerId", practitionerId,
                "status", "in_progress",
                "sourcePackageStatus", "unavailable",
                "revision", 0L);
        var outbox = map(
                "assessmentSessionId", sessionId,
                "encounterId", encounter.id(),
                "patientId", encounter.patientId(),
                "responsiblePractitionerId", practitionerId);
        return result(
                sessionId,
                encounter.patientId(),
                encounter.id(),
                sessionId,
                "assessment_session",
                "assessment.started",
                "m6.assessment.started.v1",
                "assessment_session",
                audit,
                outbox,
                201,
                0L);
    }

    private MutationResult saveResponse(
            AuthorizedTenantContext context, MutationCommand command) {
        var session = mutableSession(context, command);
        requireInProgress(session);
        var section = section(context, session.id(), command.screenId());
        var authorId = fieldUuid(command, "authorPractitionerId");
        var responseKey = boundedCode(field(command, "responseKey"), "responseKey");
        var content = bounded(field(command, "content"), 1, 20000, "content");
        var source = boundedCode(field(command, "sourceKey"), "sourceKey");
        var method = boundedCode(field(command, "methodKey"), "methodKey");
        var unit = optionalBounded(command.fields().get("unit"), 80, "unit");
        var interpretation = field(command, "interpretationStatus");
        requireOneOf(
                interpretation,
                "interpretationStatus",
                "uninterpreted",
                "provisional",
                "reviewed",
                "not_applicable");
        var uncertainty = optionalBounded(command.fields().get("uncertainty"), 2000, "uncertainty");
        var response = response(context, section.id(), responseKey, true);
        UUID responseId;
        UUID priorVersionId;
        int versionNumber;
        long responseRevision;
        if (response == null) {
            responseId = UuidV7Generator.randomUuid();
            jdbc.update(
                    """
                    INSERT INTO assessment_responses
                      (id,organization_id,assessment_session_id,assessment_section_id,response_key,
                       status,created_at,created_by,updated_at,updated_by)
                    VALUES (?,?,?,?,?,'draft',?,?,?,?)
                    """,
                    responseId,
                    context.organizationId(),
                    session.id(),
                    section.id(),
                    responseKey,
                    Timestamp.from(command.now()),
                    context.actorId(),
                    Timestamp.from(command.now()),
                    context.actorId());
            priorVersionId = null;
            versionNumber = 1;
            responseRevision = 0;
        } else {
            responseId = response.id();
            priorVersionId = response.currentVersionId();
            versionNumber = response.currentVersionNumber() + 1;
            responseRevision = response.revision();
        }
        var versionId = UuidV7Generator.randomUuid();
        var contentDigest = digest(content);
        jdbc.update(
                """
                INSERT INTO response_versions
                  (id,organization_id,assessment_response_id,version_number,prior_version_id,
                   content_text,content_digest,source_key,method_key,unit_text,
                   interpretation_status,uncertainty_text,author_practitioner_id,recorded_at,
                   created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                versionId,
                context.organizationId(),
                responseId,
                versionNumber,
                priorVersionId,
                content,
                contentDigest,
                source,
                method,
                unit,
                interpretation,
                uncertainty,
                authorId,
                Timestamp.from(command.now()),
                context.actorId(),
                context.actorId());
        jdbc.update(
                """
                UPDATE assessment_responses
                   SET current_version_id=?,current_version_number=?,updated_at=?,updated_by=?,
                       lock_version=lock_version+1
                 WHERE organization_id=? AND id=? AND lock_version=?
                """,
                versionId,
                versionNumber,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                responseId,
                responseRevision);
        jdbc.update(
                """
                UPDATE assessment_sections section
                   SET status='complete',
                       response_count=(SELECT count(*) FROM assessment_responses response
                         WHERE response.organization_id=section.organization_id
                           AND response.assessment_section_id=section.id AND response.status='draft'),
                       updated_at=?,updated_by=?,lock_version=lock_version+1
                 WHERE organization_id=? AND id=? AND lock_version=?
                """,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                section.id(),
                section.revision());
        var revision = bumpSession(context, session, command.now());
        return result(
                responseId,
                session.patientId(),
                session.encounterId(),
                session.id(),
                "assessment_response",
                "assessment.response.versioned",
                null,
                null,
                map(
                        "assessmentSessionId", session.id(),
                        "sectionId", section.id(),
                        "responseId", responseId,
                        "versionId", versionId,
                        "versionNumber", versionNumber,
                        "contentDigest", contentDigest,
                        "sourceKey", source,
                        "methodKey", method,
                        "interpretationStatus", interpretation,
                        "revision", revision),
                Map.of(),
                200,
                revision);
    }

    private MutationResult recordMeasurement(
            AuthorizedTenantContext context, MutationCommand command) {
        var session = mutableSession(context, command);
        requireInProgress(session);
        var section = section(context, session.id(), command.screenId());
        var id = UuidV7Generator.randomUuid();
        var recordedBy = fieldUuid(command, "recordedByPractitionerId");
        var owner = fieldUuid(command, "ownerPractitionerId");
        var key = boundedCode(field(command, "measurementKey"), "measurementKey");
        if (key.equalsIgnoreCase("composite_cure_score")) {
            throw invalid("Composite cure scores are prohibited.");
        }
        var purpose = bounded(field(command, "purpose"), 2, 1000, "purpose");
        var baseline = booleanField(command, "baseline", false);
        var value = bounded(field(command, "value"), 1, 500, "value");
        var source = boundedCode(field(command, "sourceKey"), "sourceKey");
        var method = boundedCode(field(command, "methodKey"), "methodKey");
        var unitScale = bounded(field(command, "unitScale"), 1, 120, "unitScale");
        var cadence = bounded(field(command, "cadence"), 2, 240, "cadence");
        var threshold = bounded(field(command, "actionThreshold"), 2, 1000, "actionThreshold");
        var interpretation = field(command, "interpretationStatus");
        requireOneOf(
                interpretation,
                "interpretationStatus",
                "uninterpreted",
                "provisional",
                "reviewed",
                "not_applicable");
        jdbc.update(
                """
                INSERT INTO measurements
                  (id,organization_id,assessment_session_id,assessment_section_id,
                   measurement_key,purpose_text,baseline,value_text,source_key,method_key,
                   unit_scale,cadence_text,owner_practitioner_id,action_threshold_text,
                   interpretation_status,recorded_by_practitioner_id,recorded_at,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id,
                context.organizationId(),
                session.id(),
                section.id(),
                key,
                purpose,
                baseline,
                value,
                source,
                method,
                unitScale,
                cadence,
                owner,
                threshold,
                interpretation,
                recordedBy,
                Timestamp.from(command.now()),
                context.actorId(),
                context.actorId());
        var revision = bumpSession(context, session, command.now());
        return result(
                id,
                session.patientId(),
                session.encounterId(),
                session.id(),
                "measurement",
                "assessment.measurement.recorded",
                null,
                null,
                map(
                        "assessmentSessionId", session.id(),
                        "sectionId", section.id(),
                        "measurementId", id,
                        "measurementKey", key,
                        "baseline", baseline,
                        "sourceKey", source,
                        "methodKey", method,
                        "unitScale", unitScale,
                        "revision", revision),
                Map.of(),
                201,
                revision);
    }

    private MutationResult recordRedFlag(
            AuthorizedTenantContext context, MutationCommand command) {
        var session = mutableSession(context, command);
        requireInProgress(session);
        var section = section(context, session.id(), command.screenId());
        var id = UuidV7Generator.randomUuid();
        var raisedBy = fieldUuid(command, "raisedByPractitionerId");
        var owner = fieldUuid(command, "ownerPractitionerId");
        var severity = field(command, "severityKey");
        requireOneOf(severity, "severityKey", "moderate", "severe", "critical");
        var summary = bounded(field(command, "summary"), 2, 4000, "summary");
        var summaryDigest = digest(summary);
        var source = boundedCode(field(command, "sourceKey"), "sourceKey");
        var method = boundedCode(field(command, "methodKey"), "methodKey");
        jdbc.update(
                """
                INSERT INTO red_flags
                  (id,organization_id,assessment_session_id,assessment_section_id,severity_key,
                   summary_text,summary_digest,source_key,method_key,owner_practitioner_id,
                   raised_by_practitioner_id,raised_at,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'raised',?,?)
                """,
                id,
                context.organizationId(),
                session.id(),
                section.id(),
                severity,
                summary,
                summaryDigest,
                source,
                method,
                owner,
                raisedBy,
                Timestamp.from(command.now()),
                context.actorId(),
                context.actorId());
        var revision = bumpSession(context, session, command.now());
        return result(
                id,
                session.patientId(),
                session.encounterId(),
                session.id(),
                "red_flag",
                "assessment.red_flag.raised",
                "m6.assessment.red-flag-raised.v1",
                "red_flag",
                map(
                        "assessmentSessionId", session.id(),
                        "sectionId", section.id(),
                        "redFlagId", id,
                        "severityKey", severity,
                        "status", "raised",
                        "summaryDigest", summaryDigest,
                        "ownerPractitionerId", owner,
                        "revision", revision),
                map(
                        "assessmentSessionId", session.id(),
                        "redFlagId", id,
                        "severityKey", severity,
                        "status", "raised",
                        "ownerPractitionerId", owner),
                201,
                revision);
    }

    private MutationResult progressRedFlag(
            AuthorizedTenantContext context, MutationCommand command) {
        var session = mutableSession(context, command);
        requireInProgress(session);
        var flagId = fieldUuid(command, "redFlagId");
        var flag = redFlag(context, flagId, true);
        if (!flag.sessionId().equals(session.id())) {
            throw notFound("The red flag is unavailable for this assessment.");
        }
        var practitionerId = fieldUuid(command, "practitionerId");
        var acknowledge = command.actionKey().equals("acknowledge-red-flag");
        var expected = acknowledge ? "raised" : "acknowledged";
        var next = acknowledge ? "acknowledged" : "resolved";
        if (!flag.status().equals(expected)) {
            throw conflict("The red flag is not in the required " + expected + " state.");
        }
        if (acknowledge) {
            jdbc.update(
                    """
                    UPDATE red_flags
                       SET status='acknowledged',acknowledged_by_practitioner_id=?,
                           acknowledged_at=?,acknowledgement_reason=?,updated_at=?,updated_by=?,
                           lock_version=lock_version+1
                     WHERE organization_id=? AND id=? AND lock_version=?
                    """,
                    practitionerId,
                    Timestamp.from(command.now()),
                    command.reason(),
                    Timestamp.from(command.now()),
                    context.actorId(),
                    context.organizationId(),
                    flag.id(),
                    flag.revision());
        } else {
            jdbc.update(
                    """
                    UPDATE red_flags
                       SET status='resolved',resolved_by_practitioner_id=?,resolved_at=?,
                           resolution_reason=?,updated_at=?,updated_by=?,lock_version=lock_version+1
                     WHERE organization_id=? AND id=? AND lock_version=?
                    """,
                    practitionerId,
                    Timestamp.from(command.now()),
                    command.reason(),
                    Timestamp.from(command.now()),
                    context.actorId(),
                    context.organizationId(),
                    flag.id(),
                    flag.revision());
        }
        var revision = bumpSession(context, session, command.now());
        var event = acknowledge
                ? "assessment.red_flag.acknowledged"
                : "assessment.red_flag.resolved";
        return result(
                flag.id(),
                session.patientId(),
                session.encounterId(),
                session.id(),
                "red_flag",
                event,
                "m6.assessment.red-flag-changed.v1",
                "red_flag",
                map(
                        "assessmentSessionId", session.id(),
                        "redFlagId", flag.id(),
                        "fromStatus", expected,
                        "toStatus", next,
                        "revision", revision),
                map(
                        "assessmentSessionId", session.id(),
                        "redFlagId", flag.id(),
                        "status", next),
                200,
                revision);
    }

    private MutationResult reviewAssessment(
            AuthorizedTenantContext context, MutationCommand command) {
        var session = mutableSession(context, command);
        requireInProgress(session);
        var reviewer = fieldUuid(command, "reviewerPractitionerId");
        if (!reviewer.equals(session.practitionerId())) {
            throw conflict("The responsible clinician must submit the assessment review.");
        }
        if (!booleanField(command, "completenessConfirmed", true)
                || !booleanField(command, "sourceReviewed", true)
                || !booleanField(command, "uncertaintyReviewed", true)) {
            throw invalid("Completeness, source and uncertainty review must each be explicitly confirmed.");
        }
        var summary = bounded(field(command, "reviewSummary"), 2, 4000, "reviewSummary");
        var completed = Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT count(*) FROM assessment_sections
                 WHERE organization_id=? AND assessment_session_id=? AND status='complete'
                """,
                Integer.class,
                context.organizationId(),
                session.id()));
        var reviewId = UuidV7Generator.randomUuid();
        var revision = session.revision() + 1;
        var reviewDigest = digest(summary);
        jdbc.update(
                """
                INSERT INTO assessment_reviews
                  (id,organization_id,assessment_session_id,assessment_revision,
                   completed_section_count,total_section_count,completeness_confirmed,
                   source_reviewed,uncertainty_reviewed,review_summary_text,review_digest,
                   reviewer_practitioner_id,reviewed_at,created_by,updated_by)
                VALUES (?,?,?,?,?,27,true,true,true,?,?,?,?,?,?)
                """,
                reviewId,
                context.organizationId(),
                session.id(),
                revision,
                completed,
                summary,
                reviewDigest,
                reviewer,
                Timestamp.from(command.now()),
                context.actorId(),
                context.actorId());
        requireUpdated(jdbc.update(
                """
                UPDATE assessment_sessions
                   SET status='in_review',submitted_for_review_at=?,updated_at=?,updated_by=?,
                       lock_version=lock_version+1
                 WHERE organization_id=? AND id=? AND lock_version=?
                """,
                Timestamp.from(command.now()),
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                session.id(),
                session.revision()));
        return result(
                reviewId,
                session.patientId(),
                session.encounterId(),
                session.id(),
                "assessment_review",
                "assessment.review.submitted",
                null,
                null,
                map(
                        "assessmentSessionId", session.id(),
                        "reviewId", reviewId,
                        "assessmentRevision", revision,
                        "completedSectionCount", completed,
                        "totalSectionCount", 27,
                        "completenessConfirmed", true,
                        "sourceReviewed", true,
                        "uncertaintyReviewed", true,
                        "reviewDigest", reviewDigest),
                Map.of(),
                200,
                revision);
    }

    private MutationResult signAssessment(
            AuthorizedTenantContext context, MutationCommand command) {
        var session = mutableSession(context, command);
        if (!session.status().equals("in_review")) {
            throw conflict("Only an assessment in review can be signed.");
        }
        var signer = fieldUuid(command, "signerPractitionerId");
        if (!signer.equals(session.practitionerId())) {
            throw conflict("The responsible clinician must sign the assessment.");
        }
        var review = review(context, session.id(), session.revision());
        var signatureId = UuidV7Generator.randomUuid();
        var revision = session.revision() + 1;
        var signatureDigest = digest(String.join(
                "|",
                session.id().toString(),
                review.id().toString(),
                Long.toString(revision),
                signer.toString(),
                ATTESTATION));
        jdbc.update(
                """
                INSERT INTO assessment_signatures
                  (id,organization_id,assessment_session_id,assessment_review_id,
                   assessment_revision,signer_practitioner_id,attestation_key,
                   signature_digest,signed_at,status,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,? ,?,'signed',?,?)
                """,
                signatureId,
                context.organizationId(),
                session.id(),
                review.id(),
                revision,
                signer,
                ATTESTATION,
                signatureDigest,
                Timestamp.from(command.now()),
                context.actorId(),
                context.actorId());
        requireUpdated(jdbc.update(
                """
                UPDATE assessment_sessions
                   SET status='signed',signed_at=?,updated_at=?,updated_by=?,lock_version=lock_version+1
                 WHERE organization_id=? AND id=? AND lock_version=?
                """,
                Timestamp.from(command.now()),
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                session.id(),
                session.revision()));
        var audit = map(
                "assessmentSessionId", session.id(),
                "reviewId", review.id(),
                "signatureId", signatureId,
                "assessmentRevision", revision,
                "signerPractitionerId", signer,
                "signatureDigest", signatureDigest,
                "revision", revision);
        var outbox = map(
                "assessmentSessionId", session.id(),
                "reviewId", review.id(),
                "signatureId", signatureId,
                "assessmentRevision", revision,
                "signatureDigest", signatureDigest);
        return result(
                signatureId,
                session.patientId(),
                session.encounterId(),
                session.id(),
                "assessment_signature",
                "assessment.signed",
                "m6.assessment.signed.v1",
                "assessment_session",
                session.id(),
                audit,
                outbox,
                200,
                revision);
    }

    private MutationResult amendAssessment(
            AuthorizedTenantContext context, MutationCommand command) {
        var session = mutableSession(context, command);
        if (!session.status().equals("signed") && !session.status().equals("amended")) {
            throw conflict("Only a signed assessment can receive an amendment.");
        }
        var author = fieldUuid(command, "authorPractitionerId");
        if (!author.equals(session.practitionerId())) {
            throw conflict("The responsible clinician must author the amendment.");
        }
        var signature = latestSignature(context, session.id());
        var text = bounded(field(command, "amendmentText"), 2, 20000, "amendmentText");
        var source = boundedCode(field(command, "sourceKey"), "sourceKey");
        var method = boundedCode(field(command, "methodKey"), "methodKey");
        var uncertainty = optionalBounded(command.fields().get("uncertainty"), 2000, "uncertainty");
        var amendmentId = UuidV7Generator.randomUuid();
        var revision = session.revision() + 1;
        var amendmentDigest = digest(text);
        jdbc.update(
                """
                INSERT INTO assessment_amendments
                  (id,organization_id,assessment_session_id,assessment_signature_id,
                   assessment_revision,amendment_text,amendment_digest,source_key,method_key,
                   uncertainty_text,author_practitioner_id,recorded_at,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                amendmentId,
                context.organizationId(),
                session.id(),
                signature.id(),
                revision,
                text,
                amendmentDigest,
                source,
                method,
                uncertainty,
                author,
                Timestamp.from(command.now()),
                context.actorId(),
                context.actorId());
        requireUpdated(jdbc.update(
                """
                UPDATE assessment_sessions
                   SET status='amended',updated_at=?,updated_by=?,lock_version=lock_version+1
                 WHERE organization_id=? AND id=? AND lock_version=?
                """,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                session.id(),
                session.revision()));
        var audit = map(
                "assessmentSessionId", session.id(),
                "signatureId", signature.id(),
                "amendmentId", amendmentId,
                "assessmentRevision", revision,
                "authorPractitionerId", author,
                "amendmentDigest", amendmentDigest,
                "revision", revision);
        var outbox = map(
                "assessmentSessionId", session.id(),
                "signatureId", signature.id(),
                "amendmentId", amendmentId,
                "assessmentRevision", revision,
                "amendmentDigest", amendmentDigest);
        return result(
                amendmentId,
                session.patientId(),
                session.encounterId(),
                session.id(),
                "assessment_amendment",
                "assessment.amended",
                "m6.assessment.amended.v1",
                "assessment_session",
                session.id(),
                audit,
                outbox,
                201,
                revision);
    }

    private MutationResult transitionAssessment(
            AuthorizedTenantContext context, MutationCommand command) {
        var session = mutableSession(context, command);
        var next = switch (command.actionKey()) {
            case "return-assessment-to-draft" -> "in_progress";
            case "complete-assessment" -> "completed";
            case "cancel-assessment" -> "cancelled";
            case "enter-assessment-in-error" -> "entered_in_error";
            default -> throw invalid("Unsupported assessment lifecycle transition.");
        };
        requireLifecycleTransition(session.status(), next);
        var submitted = next.equals("in_progress") ? null : session.submittedForReviewAt();
        var completed = next.equals("completed") ? Timestamp.from(command.now()) : null;
        var cancelled = next.equals("cancelled") ? Timestamp.from(command.now()) : null;
        var enteredInError = next.equals("entered_in_error") ? Timestamp.from(command.now()) : null;
        requireUpdated(jdbc.update(
                """
                UPDATE assessment_sessions
                   SET status=?,submitted_for_review_at=?,completed_at=?,cancelled_at=?,
                       entered_in_error_at=?,updated_at=?,updated_by=?,lock_version=lock_version+1
                 WHERE organization_id=? AND id=? AND lock_version=?
                """,
                next,
                submitted == null ? null : Timestamp.from(submitted),
                completed,
                cancelled,
                enteredInError,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                session.id(),
                session.revision()));
        var revision = session.revision() + 1;
        var audit = map(
                "assessmentSessionId", session.id(),
                "encounterId", session.encounterId(),
                "patientId", session.patientId(),
                "fromStatus", session.status(),
                "toStatus", next,
                "revision", revision);
        var outbox = map(
                "assessmentSessionId", session.id(),
                "encounterId", session.encounterId(),
                "patientId", session.patientId(),
                "fromStatus", session.status(),
                "toStatus", next);
        return result(
                session.id(),
                session.patientId(),
                session.encounterId(),
                session.id(),
                "assessment_session",
                "assessment.status.changed",
                "m6.assessment.status-changed.v1",
                "assessment_session",
                audit,
                outbox,
                200,
                revision);
    }

    private Session mutableSession(
            AuthorizedTenantContext context, MutationCommand command) {
        var id = firstNonNull(command.assessmentSessionId(), command.targetId());
        if (id == null) throw invalid("assessmentSessionId or targetId is required.");
        var session = session(context, id, true);
        if (command.patientId() != null && !command.patientId().equals(session.patientId())) {
            throw invalid("patientId does not match the assessment.");
        }
        if (command.encounterId() != null && !command.encounterId().equals(session.encounterId())) {
            throw invalid("encounterId does not match the assessment.");
        }
        requireRevision(session.revision(), command.expectedRevision());
        return session;
    }

    private Encounter encounter(
            AuthorizedTenantContext context, UUID encounterId, boolean lock) {
        var rows = jdbc.query(
                """
                SELECT id,patient_id,responsible_practitioner_id,status,lock_version
                  FROM encounters WHERE organization_id=? AND id=?
                """ + (lock ? " FOR UPDATE" : ""),
                (resultSet, rowNumber) -> new Encounter(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("responsible_practitioner_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version")),
                context.organizationId(),
                encounterId);
        if (rows.isEmpty()) throw notFound("The encounter is unavailable.");
        return rows.getFirst();
    }

    private Session session(
            AuthorizedTenantContext context, UUID sessionId, boolean lock) {
        var rows = jdbc.query(
                """
                SELECT id,encounter_id,patient_id,responsible_practitioner_id,status,
                       submitted_for_review_at,lock_version
                  FROM assessment_sessions WHERE organization_id=? AND id=?
                """ + (lock ? " FOR UPDATE" : ""),
                (resultSet, rowNumber) -> new Session(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("encounter_id", UUID.class),
                        resultSet.getObject("patient_id", UUID.class),
                        resultSet.getObject("responsible_practitioner_id", UUID.class),
                        resultSet.getString("status"),
                        toInstant(resultSet.getTimestamp("submitted_for_review_at")),
                        resultSet.getLong("lock_version")),
                context.organizationId(),
                sessionId);
        if (rows.isEmpty()) throw notFound("The assessment session is unavailable.");
        return rows.getFirst();
    }

    private Section section(
            AuthorizedTenantContext context, UUID sessionId, String screenId) {
        var rows = jdbc.query(
                """
                SELECT id,status,lock_version FROM assessment_sections
                 WHERE organization_id=? AND assessment_session_id=? AND screen_id=? FOR UPDATE
                """,
                (resultSet, rowNumber) -> new Section(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version")),
                context.organizationId(),
                sessionId,
                screenId);
        if (rows.isEmpty()) throw notFound("The assessment section is unavailable.");
        return rows.getFirst();
    }

    private Response response(
            AuthorizedTenantContext context, UUID sectionId, String responseKey, boolean lock) {
        var rows = jdbc.query(
                """
                SELECT id,current_version_id,current_version_number,lock_version
                  FROM assessment_responses
                 WHERE organization_id=? AND assessment_section_id=? AND response_key=?
                """ + (lock ? " FOR UPDATE" : ""),
                (resultSet, rowNumber) -> new Response(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("current_version_id", UUID.class),
                        resultSet.getInt("current_version_number"),
                        resultSet.getLong("lock_version")),
                context.organizationId(),
                sectionId,
                responseKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private RedFlag redFlag(
            AuthorizedTenantContext context, UUID flagId, boolean lock) {
        var rows = jdbc.query(
                """
                SELECT id,assessment_session_id,status,lock_version FROM red_flags
                 WHERE organization_id=? AND id=?
                """ + (lock ? " FOR UPDATE" : ""),
                (resultSet, rowNumber) -> new RedFlag(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("assessment_session_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getLong("lock_version")),
                context.organizationId(),
                flagId);
        if (rows.isEmpty()) throw notFound("The red flag is unavailable.");
        return rows.getFirst();
    }

    private Review review(
            AuthorizedTenantContext context, UUID sessionId, long assessmentRevision) {
        var rows = jdbc.query(
                """
                SELECT id,assessment_revision FROM assessment_reviews
                 WHERE organization_id=? AND assessment_session_id=? AND assessment_revision=?
                """,
                (resultSet, rowNumber) -> new Review(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getLong("assessment_revision")),
                context.organizationId(),
                sessionId,
                assessmentRevision);
        if (rows.isEmpty()) throw conflict("The current assessment revision has not been reviewed.");
        return rows.getFirst();
    }

    private Signature latestSignature(
            AuthorizedTenantContext context, UUID sessionId) {
        var rows = jdbc.query(
                """
                SELECT id,assessment_revision FROM assessment_signatures
                 WHERE organization_id=? AND assessment_session_id=?
                 ORDER BY assessment_revision DESC,id DESC LIMIT 1
                """,
                (resultSet, rowNumber) -> new Signature(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getLong("assessment_revision")),
                context.organizationId(),
                sessionId);
        if (rows.isEmpty()) throw conflict("The assessment has no immutable signature.");
        return rows.getFirst();
    }

    private long bumpSession(
            AuthorizedTenantContext context, Session session, Instant now) {
        requireUpdated(jdbc.update(
                """
                UPDATE assessment_sessions
                   SET updated_at=?,updated_by=?,lock_version=lock_version+1
                 WHERE organization_id=? AND id=? AND lock_version=?
                """,
                Timestamp.from(now),
                context.actorId(),
                context.organizationId(),
                session.id(),
                session.revision()));
        return session.revision() + 1;
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(
                        jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .toInstant();
    }

    private void requireOperationScope(AuthorizedTenantContext context) {
        var bound = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT nullif(current_setting('app.current_organization_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_actor_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_operation_key',true),'') IS NOT NULL
                """,
                Boolean.class,
                context.organizationId(),
                context.actorId()));
        if (!bound) {
            throw notFound("The assessment resource is unavailable or is not assigned to this account.");
        }
    }

    private static void requireInProgress(Session session) {
        if (!session.status().equals("in_progress")) {
            throw conflict("Assessment content can change only while the session is in progress.");
        }
    }

    private static void requireLifecycleTransition(String current, String next) {
        var allowed = (current.equals("in_review") && next.equals("in_progress"))
                || ((current.equals("signed") || current.equals("amended"))
                        && next.equals("completed"))
                || ((current.equals("in_progress") || current.equals("in_review"))
                        && (next.equals("cancelled") || next.equals("entered_in_error")));
        if (!allowed) {
            throw conflict("The requested assessment lifecycle transition is not allowed.");
        }
    }

    private static void requireRevision(long actual, Long expected) {
        if (expected == null) {
            throw new AssessmentException(
                    AssessmentException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match revision is required.");
        }
        if (actual != expected) {
            throw stale("The assessment changed; refresh and review it before retrying.");
        }
    }

    private static void requireUpdated(int count) {
        if (count != 1) throw stale("The assessment changed while the request was being applied.");
    }

    private static AssessmentScreen.Row row(
            String screenId,
            UUID id,
            UUID patientId,
            UUID encounterId,
            UUID assessmentSessionId,
            String status,
            long revision,
            Map<String, String> values) {
        return new AssessmentScreen.Row(
                id,
                patientId,
                encounterId,
                assessmentSessionId,
                status,
                revision,
                "\"m6:" + screenId + ":" + id + ":" + revision + "\"",
                values,
                List.of());
    }

    private static MutationResult result(
            UUID subjectId,
            UUID patientId,
            UUID encounterId,
            UUID assessmentSessionId,
            String subjectType,
            String auditEvent,
            String outboxEvent,
            String aggregateType,
            Map<String, Object> audit,
            Map<String, Object> outbox,
            int statusCode,
            long revision) {
        return result(
                subjectId,
                patientId,
                encounterId,
                assessmentSessionId,
                subjectType,
                auditEvent,
                outboxEvent,
                aggregateType,
                null,
                audit,
                outbox,
                statusCode,
                revision);
    }

    private static MutationResult result(
            UUID subjectId,
            UUID patientId,
            UUID encounterId,
            UUID assessmentSessionId,
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
                subjectId,
                patientId,
                encounterId,
                assessmentSessionId,
                subjectType,
                auditEvent,
                outboxEvent,
                aggregateType,
                outboxAggregateId,
                audit,
                outbox,
                statusCode,
                revision);
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

    private static boolean booleanField(
            MutationCommand command, String key, boolean requiredTrue) {
        var value = command.fields().get(key);
        if (value == null || value.isBlank()) {
            if (requiredTrue) throw invalid(key + " must be explicitly confirmed.");
            return false;
        }
        if (!value.equals("true") && !value.equals("false")) {
            throw invalid(key + " must be true or false.");
        }
        var result = Boolean.parseBoolean(value);
        if (requiredTrue && !result) throw invalid(key + " must be explicitly confirmed.");
        return result;
    }

    private static String bounded(
            String value, int minimum, int maximum, String fieldName) {
        var normalized = value == null ? "" : value.strip();
        var length = normalized.codePointCount(0, normalized.length());
        if (length < minimum || length > maximum) {
            throw invalid(fieldName + " must contain " + minimum + " to " + maximum + " characters.");
        }
        return normalized;
    }

    private static String optionalBounded(String value, int maximum, String fieldName) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.strip();
        if (normalized.codePointCount(0, normalized.length()) > maximum) {
            throw invalid(fieldName + " must contain no more than " + maximum + " characters.");
        }
        return normalized;
    }

    private static String boundedCode(String value, String fieldName) {
        var normalized = value == null ? "" : value.strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{1,79}")) {
            throw invalid(fieldName + " must contain 2 to 80 safe code characters.");
        }
        return normalized;
    }

    private static void requireOneOf(
            String value, String fieldName, String... supportedValues) {
        for (var supported : supportedValues) {
            if (supported.equals(value)) return;
        }
        throw invalid(fieldName + " contains an unsupported value.");
    }

    private static void requireLiteralSearch(String value, int minimum, String label) {
        var meaningful = value.codePoints().filter(Character::isLetterOrDigit).count();
        if (value.codePointCount(0, value.length()) < minimum
                || meaningful < 2
                || value.indexOf('%') >= 0
                || value.indexOf('_') >= 0
                || value.indexOf('\\') >= 0) {
            throw invalid(label + " requires bounded literal characters without wildcards.");
        }
    }

    private static UUID firstNonNull(UUID first, UUID second) {
        return first == null ? second : first;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate assessment evidence digest.", exception);
        }
    }

    private static Map<String, Object> map(Object... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("Map entries must be paired.");
        var values = new LinkedHashMap<String, Object>();
        for (var index = 0; index < entries.length; index += 2) {
            if (entries[index + 1] != null) {
                values.put((String) entries[index], entries[index + 1]);
            }
        }
        return Map.copyOf(values);
    }

    private static Map<String, String> values(String... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("Value entries must be paired.");
        var values = new LinkedHashMap<String, String>();
        for (var index = 0; index < entries.length; index += 2) {
            values.put(entries[index], safe(entries[index + 1], "Not recorded"));
        }
        return values;
    }

    private static AssessmentScreen.Column column(String key, String label) {
        return new AssessmentScreen.Column(key, label);
    }

    private static AssessmentScreen.Metric metric(
            String key, String label, long value, String tone) {
        return new AssessmentScreen.Metric(key, label, value, tone);
    }

    private static AssessmentScreen.Notice notice(String tone, String title, String detail) {
        return new AssessmentScreen.Notice(tone, title, detail);
    }

    private static String instant(Timestamp value) {
        return value == null ? "Not recorded" : value.toInstant().toString();
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String maskPatientNumber(String value) {
        if (value == null || value.length() <= 4) return "••••";
        return "••••" + value.substring(value.length() - 4);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static AssessmentException invalid(String message) {
        return new AssessmentException(AssessmentException.Reason.INVALID, message);
    }

    private static AssessmentException notFound(String message) {
        return new AssessmentException(AssessmentException.Reason.NOT_FOUND, message);
    }

    private static AssessmentException conflict(String message) {
        return new AssessmentException(AssessmentException.Reason.CONFLICT, message);
    }

    private static AssessmentException stale(String message) {
        return new AssessmentException(AssessmentException.Reason.STALE, message);
    }

    private record Encounter(
            UUID id, UUID patientId, UUID practitionerId, String status, long revision) {}

    private record Session(
            UUID id,
            UUID encounterId,
            UUID patientId,
            UUID practitionerId,
            String status,
            Instant submittedForReviewAt,
            long revision) {}

    private record Section(UUID id, String status, long revision) {}

    private record Response(
            UUID id, UUID currentVersionId, int currentVersionNumber, long revision) {}

    private record RedFlag(UUID id, UUID sessionId, String status, long revision) {}

    private record Review(UUID id, long assessmentRevision) {}

    private record Signature(UUID id, long assessmentRevision) {}
}
