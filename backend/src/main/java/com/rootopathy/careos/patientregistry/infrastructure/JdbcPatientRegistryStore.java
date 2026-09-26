package com.rootopathy.careos.patientregistry.infrastructure;

import com.rootopathy.careos.patientregistry.application.PatientRegistryException;
import com.rootopathy.careos.patientregistry.application.PatientRegistryStore;
import com.rootopathy.careos.patientregistry.application.PatientSensitiveValueCodec;
import com.rootopathy.careos.patientregistry.domain.PatientRegistryScreen;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

@Repository
public class JdbcPatientRegistryStore implements PatientRegistryStore {
    private static final String DETECTOR_VERSION = "m3-deterministic-v1";
    private static final String POLICY_VERSION = "m3-candidate-1";
    private static final String REGISTRATION_VALIDATION_VERSION = "patient-registration-v1";
    private final JdbcTemplate jdbc;
    private final PatientSensitiveValueCodec sensitiveValues;

    public JdbcPatientRegistryStore(
            JdbcTemplate jdbc, PatientSensitiveValueCodec sensitiveValues) {
        this.jdbc = jdbc;
        this.sensitiveValues = sensitiveValues;
    }

    @Override
    public Projection projection(AuthorizedTenantContext context, ScreenQuery query) {
        requireOperationScope(context);
        var requestedPatientId = query.patientId();
        var canonicalPatientId = canonicalPatientId(context, requestedPatientId);
        var effectiveQuery = queryWithPatient(query, canonicalPatientId);
        var redirected = requestedPatientId != null
                && canonicalPatientId != null
                && !requestedPatientId.equals(canonicalPatientId);
        var rows = switch (effectiveQuery.screenId()) {
            case "P3-01", "P3-02", "P3-05", "P3-13" -> patientRows(context, effectiveQuery);
            case "P3-03" -> List.<PatientRegistryScreen.Row>of();
            case "P3-12" -> registrationRows(context, effectiveQuery);
            case "P3-04" -> registrationSearchRows(context, effectiveQuery);
            case "P3-06" -> contactAddressRows(context, effectiveQuery);
            case "P3-07" -> preferenceRows(context, effectiveQuery);
            case "P3-08" -> identifierRows(context, effectiveQuery);
            case "P3-09" -> relationshipRows(context, effectiveQuery);
            case "P3-10" -> consentPrivacyRows(context, effectiveQuery);
            case "P3-11" -> safetyFlagRows(context, effectiveQuery);
            case "P3-14" -> duplicateRows(context, effectiveQuery);
            case "P3-15" -> mergeRows(context, effectiveQuery);
            case "P3-16" -> timelineRows(context, effectiveQuery);
            default -> throw notFound("The requested patient projection does not exist.");
        };
        if (redirected) {
            rows = rows.stream().map(this::withMergeLineageMarker).toList();
        }
        rows = rows.stream().map(row -> withAllowedActions(query.screenId(), row)).toList();
        var generatedAt = Objects.requireNonNull(
                        jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .toInstant();
        return new Projection(
                metrics(context, query.screenId()),
                columns(query.screenId()),
                rows,
                notices(query.screenId()),
                generatedAt);
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
                  AND (memberships.effective_to IS NULL
                       OR memberships.effective_to>clock_timestamp())
                  AND roles.status='active' AND roles.interactive
                  AND permissions.status='active'
                  AND EXISTS(SELECT 1 FROM authorization_registry_releases release
                      WHERE release.registry_version=roles.registry_version
                        AND release.status='active')
                  AND EXISTS(SELECT 1 FROM authorization_registry_releases release
                      WHERE release.registry_version=permissions.registry_version
                        AND release.status='active')
                """,
                String.class,
                context.organizationId(),
                context.actorId()));
    }

    @Override
    public ImpactAnalysis previewImpact(
            AuthorizedTenantContext context, MutationCommand command) {
        requireOperationScope(context);
        return switch (command.actionKey()) {
            case "request-patient-merge" -> mergeRequestImpact(context, command);
            case "execute-patient-merge" -> mergeExecutionImpact(context, command);
            default -> throw invalid("The requested action does not define an impact preview.");
        };
    }

    @Override
    public MutationResult mutate(AuthorizedTenantContext context, MutationCommand command) {
        requireOperationScope(context);
        return switch (command.actionKey()) {
            case "start-registration" -> startRegistration(context, command);
            case "search-duplicates" -> searchDuplicates(context, command);
            case "record-registration-disposition" ->
                recordRegistrationDisposition(context, command);
            case "correct-identity" -> correctIdentity(context, command);
            case "add-contact" -> addContact(context, command);
            case "add-address" -> addAddress(context, command);
            case "set-communication-preference" -> setPreference(context, command);
            case "add-caregiver-relationship" -> addRelationship(context, command);
            case "validate-registration" -> validateRegistration(context, command);
            case "submit-registration" -> submitRegistration(context, command);
            case "change-patient-lifecycle" -> changeLifecycle(context, command);
            case "claim-duplicate" -> claimDuplicate(context, command);
            case "disposition-duplicate" -> dispositionDuplicate(context, command);
            case "request-patient-merge" -> requestMerge(context, command);
            case "decide-patient-merge" -> decideMerge(context, command);
            case "execute-patient-merge" -> executeMerge(context, command);
            default -> throw notFound("The requested patient-registry action does not exist.");
        };
    }

    private List<PatientRegistryScreen.Row> patientRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        var search = query.search();
        if (search != null) requireLiteralSearch(search, 2, "Patient directory search");
        return jdbc.query(
                """
                SELECT id,patient_number,lifecycle_state,official_given_name,
                       official_family_name,name_to_use,name_state,birth_date_value,
                       birth_date_precision,birth_date_certainty,temporary_identity,
                       verification_state,updated_at,lock_version
                FROM patient_profiles
                WHERE organization_id=?
                  AND (CAST(? AS uuid) IS NULL OR id=CAST(? AS uuid))
                  AND (CAST(? AS text) IS NULL OR lifecycle_state=CAST(? AS text))
                  AND (CAST(? AS text) IS NULL
                       OR patient_number ILIKE '%'||CAST(? AS text)||'%'
                       OR concat_ws(' ',official_given_name,official_family_name,name_to_use)
                          ILIKE '%'||CAST(? AS text)||'%')
                ORDER BY updated_at DESC,id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    var values = values(
                            "$kind", "patient",
                            "primary", displayName(
                                    resultSet.getString("official_given_name"),
                                    resultSet.getString("official_family_name"),
                                    resultSet.getString("name_to_use"),
                                    resultSet.getString("name_state")),
                            "secondary", maskPatientNumber(resultSet.getString("patient_number")),
                            "context", birthDisplay(
                                    resultSet.getObject("birth_date_value", LocalDate.class),
                                    resultSet.getString("birth_date_precision"),
                                    resultSet.getString("birth_date_certainty")),
                            "verification", resultSet.getString("verification_state"),
                            "temporary", Boolean.toString(resultSet.getBoolean("temporary_identity")),
                            "updated", resultSet.getTimestamp("updated_at").toInstant().toString());
                    var revision = resultSet.getLong("lock_version");
                    return row(query.screenId(), id, id, resultSet.getString("lifecycle_state"), revision, values);
                },
                context.organizationId(),
                query.patientId(),
                query.patientId(),
                query.status(),
                query.status(),
                search,
                search,
                search,
                query.limit());
    }

    private List<PatientRegistryScreen.Row> registrationRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        var mayManage = hasPermission(context, "patient.registration.manage");
        return jdbc.query(
                """
                WITH projected AS (
                    SELECT id,selected_patient_id,registration_source,
                           supplier_relationship_key,purpose_key,current_step,
                           duplicate_disposition,validation_digest,
                           validation_schema_version,validation_expires_at,expires_at,
                           CASE
                               WHEN status IN ('collecting','duplicate_review','ready','submitted')
                                AND expires_at<=clock_timestamp() THEN 'expired'
                               ELSE status
                           END projected_status,
                           updated_at,lock_version
                    FROM patient_registration_runs
                    WHERE organization_id=?
                      AND (CAST(? AS uuid) IS NULL OR id=CAST(? AS uuid))
                      AND (creator_id=? OR CAST(? AS boolean))
                )
                SELECT id,selected_patient_id,registration_source,
                       supplier_relationship_key,purpose_key,current_step,
                       duplicate_disposition,validation_digest,
                       validation_schema_version,validation_expires_at,expires_at,
                       CASE
                           WHEN validation_digest IS NULL THEN 'Not validated'
                           WHEN validation_expires_at<=clock_timestamp()
                               THEN 'Validation expired'
                           ELSE 'Validated'
                       END validation_status,
                       projected_status AS status,updated_at,lock_version
                FROM projected
                WHERE (CAST(? AS text) IS NULL OR projected_status=CAST(? AS text))
                ORDER BY updated_at DESC,id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    var revision = resultSet.getLong("lock_version");
                    return row(
                            query.screenId(),
                            id,
                            resultSet.getObject("selected_patient_id", UUID.class),
                            resultSet.getString("status"),
                            revision,
                            values(
                                    "$kind", "registration",
                                    "primary", "Registration " + shortId(id),
                                    "secondary", resultSet.getString("registration_source"),
                                    "context", "Step " + resultSet.getString("current_step"),
                                    "supplierRelationship", safe(
                                            resultSet.getString("supplier_relationship_key"),
                                            "Not supplied"),
                                    "purpose", resultSet.getString("purpose_key"),
                                    "disposition", safe(resultSet.getString("duplicate_disposition"), "Pending"),
                                    "validation", resultSet.getString("validation_status"),
                                    "validationVersion", safe(
                                            resultSet.getString("validation_schema_version"),
                                            "Not validated"),
                                    "validationExpires", instantString(
                                            resultSet.getTimestamp("validation_expires_at")),
                                    "expires", resultSet.getTimestamp("expires_at").toInstant().toString()));
                },
                context.organizationId(),
                query.registrationId(),
                query.registrationId(),
                context.actorId(),
                mayManage,
                query.status(),
                query.status(),
                query.limit());
    }

    private List<PatientRegistryScreen.Row> registrationSearchRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        var mayManage = hasPermission(context, "patient.registration.manage");
        var rows = new ArrayList<>(registrationRows(context, query));
        if (query.registrationId() == null) {
            return List.copyOf(rows);
        }
        rows.addAll(jdbc.query(
                """
                SELECT patient.id,patient.patient_number,patient.lifecycle_state,
                       patient.official_given_name,patient.official_family_name,
                       patient.name_to_use,patient.name_state,patient.birth_date_value,
                       patient.birth_date_precision,patient.birth_date_certainty,
                       patient.lock_version
                FROM patient_registration_runs run
                CROSS JOIN LATERAL jsonb_array_elements_text(
                    COALESCE(run.staged_summary->'candidateIds','[]'::jsonb)) candidate(id)
                JOIN patient_profiles patient
                  ON patient.organization_id=run.organization_id
                 AND patient.id=candidate.id::uuid
                WHERE run.organization_id=? AND run.id=?
                  AND (run.creator_id=? OR CAST(? AS boolean))
                ORDER BY patient.updated_at DESC,patient.id
                LIMIT 20
                """,
                (resultSet, rowNumber) -> {
                    var patientId = resultSet.getObject("id", UUID.class);
                    var revision = resultSet.getLong("lock_version");
                    return row(
                            query.screenId(),
                            patientId,
                            patientId,
                            resultSet.getString("lifecycle_state"),
                            revision,
                            values(
                                    "$kind", "candidate",
                                    "primary", displayName(
                                            resultSet.getString("official_given_name"),
                                            resultSet.getString("official_family_name"),
                                            resultSet.getString("name_to_use"),
                                            resultSet.getString("name_state")),
                                    "secondary", maskPatientNumber(resultSet.getString("patient_number")),
                                    "context", birthDisplay(
                                            resultSet.getObject("birth_date_value", LocalDate.class),
                                            resultSet.getString("birth_date_precision"),
                                            resultSet.getString("birth_date_certainty")),
                                    "match", "Potential organization-local match"));
                },
                context.organizationId(),
                query.registrationId(),
                context.actorId(),
                mayManage));
        return List.copyOf(rows);
    }

    private List<PatientRegistryScreen.Row> contactAddressRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        var rows = new ArrayList<>(patientRows(context, parentQuery(query)));
        if (query.patientId() == null) {
            return List.copyOf(rows);
        }
        rows.addAll(jdbc.query(
                """
                SELECT id,channel,contact_use,purpose_key,masked_display,
                       verification_state,primary_contact,preferred_contact,status,
                       updated_at,lock_version
                FROM patient_contacts
                WHERE organization_id=? AND patient_id=?
                ORDER BY primary_contact DESC,updated_at DESC,id
                LIMIT 100
                """,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    return row(
                            query.screenId(),
                            id,
                            query.patientId(),
                            resultSet.getString("status"),
                            resultSet.getLong("lock_version"),
                            values(
                                    "$kind", "contact",
                                    "primary", resultSet.getString("masked_display"),
                                    "secondary", resultSet.getString("channel") + " · " + resultSet.getString("contact_use"),
                                    "context", resultSet.getString("purpose_key"),
                                    "verification", resultSet.getString("verification_state"),
                                    "preferred", Boolean.toString(resultSet.getBoolean("preferred_contact"))));
                },
                context.organizationId(),
                query.patientId()));
        rows.addAll(jdbc.query(
                """
                SELECT id,address_use,purpose_key,masked_summary,country_code,
                       validation_state,primary_address,preferred_address,status,
                       updated_at,lock_version
                FROM patient_addresses
                WHERE organization_id=? AND patient_id=?
                ORDER BY primary_address DESC,updated_at DESC,id
                LIMIT 100
                """,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    return row(
                            query.screenId(),
                            id,
                            query.patientId(),
                            resultSet.getString("status"),
                            resultSet.getLong("lock_version"),
                            values(
                                    "$kind", "address",
                                    "primary", resultSet.getString("masked_summary"),
                                    "secondary", resultSet.getString("address_use") + " · " + resultSet.getString("country_code"),
                                    "context", resultSet.getString("purpose_key"),
                                    "verification", resultSet.getString("validation_state"),
                                    "preferred", Boolean.toString(resultSet.getBoolean("preferred_address"))));
                },
                context.organizationId(),
                query.patientId()));
        return List.copyOf(rows);
    }

    private List<PatientRegistryScreen.Row> preferenceRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        var rows = new ArrayList<>(patientRows(context, parentQuery(query)));
        if (query.patientId() == null) {
            return List.copyOf(rows);
        }
        rows.addAll(jdbc.query(
                """
                SELECT id,purpose_key,channel,decision,language_tag,
                       accessible_format_key,status,lock_version
                FROM communication_preferences
                WHERE organization_id=? AND patient_id=?
                ORDER BY updated_at DESC,id LIMIT 100
                """,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    return row(
                            query.screenId(),
                            id,
                            query.patientId(),
                            resultSet.getString("status"),
                            resultSet.getLong("lock_version"),
                            values(
                                    "$kind", "preference",
                                    "primary", resultSet.getString("purpose_key") + " · " + resultSet.getString("channel"),
                                    "secondary", resultSet.getString("decision"),
                                    "context", safe(resultSet.getString("language_tag"), "No language preference"),
                                    "format", safe(resultSet.getString("accessible_format_key"), "Default format")));
                },
                context.organizationId(),
                query.patientId()));
        return List.copyOf(rows);
    }

    private List<PatientRegistryScreen.Row> identifierRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        // Identifier rows remain unavailable until an approved local
        // scheme/version and field-projection policy are active.
        return List.copyOf(patientRows(context, parentQuery(query)));
    }

    private List<PatientRegistryScreen.Row> relationshipRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        var rows = new ArrayList<>(patientRows(context, parentQuery(query)));
        if (query.patientId() == null) {
            return List.copyOf(rows);
        }
        rows.addAll(jdbc.query(
                """
                SELECT relationship.id,relationship.relationship_type_key,
                       relationship.display_label,relationship.status,
                       relationship.lock_version,
                       count(authority.id) FILTER (WHERE authority.status='active') active_authorities
                FROM caregiver_relationships relationship
                LEFT JOIN patient_authority_grants authority
                  ON authority.organization_id=relationship.organization_id
                 AND authority.caregiver_relationship_id=relationship.id
                WHERE relationship.organization_id=? AND relationship.patient_id=?
                GROUP BY relationship.id
                ORDER BY relationship.updated_at DESC,relationship.id LIMIT 100
                """,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    return row(
                            query.screenId(),
                            id,
                            query.patientId(),
                            resultSet.getString("status"),
                            resultSet.getLong("lock_version"),
                            values(
                                    "$kind", "relationship",
                                    "primary", resultSet.getString("display_label"),
                                    "secondary", resultSet.getString("relationship_type_key"),
                                    "context", resultSet.getLong("active_authorities") == 0
                                            ? "Authority not established"
                                            : "Active authority present"));
                },
                context.organizationId(),
                query.patientId()));
        return List.copyOf(rows);
    }

    private List<PatientRegistryScreen.Row> consentPrivacyRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        var rows = new ArrayList<>(patientRows(context, parentQuery(query)));
        if (query.patientId() == null) {
            return List.copyOf(rows);
        }
        rows.addAll(jdbc.query(
                """
                SELECT id,purpose_key,action_key,decision,verification_state,status,lock_version
                FROM patient_consents
                WHERE organization_id=? AND patient_id=?
                ORDER BY updated_at DESC,id LIMIT 100
                """,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    return row(
                            query.screenId(),
                            id,
                            query.patientId(),
                            resultSet.getString("status"),
                            resultSet.getLong("lock_version"),
                            values(
                                    "$kind", "consent",
                                    "primary", resultSet.getString("purpose_key") + " · " + resultSet.getString("action_key"),
                                    "secondary", resultSet.getString("decision"),
                                    "context", resultSet.getString("verification_state")));
                },
                context.organizationId(),
                query.patientId()));
        rows.addAll(jdbc.query(
                """
                SELECT id,restriction_type_key,projection_consequence_key,decision,status,lock_version
                FROM privacy_restrictions
                WHERE organization_id=? AND patient_id=?
                ORDER BY updated_at DESC,id LIMIT 100
                """,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    return row(
                            query.screenId(),
                            id,
                            query.patientId(),
                            resultSet.getString("status"),
                            resultSet.getLong("lock_version"),
                            values(
                                    "$kind", "privacy",
                                    "primary", resultSet.getString("restriction_type_key"),
                                    "secondary", safe(resultSet.getString("decision"), "Pending decision"),
                                    "context", resultSet.getString("projection_consequence_key")));
                },
                context.organizationId(),
                query.patientId()));
        return List.copyOf(rows);
    }

    private List<PatientRegistryScreen.Row> safetyFlagRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        var rows = new ArrayList<>(patientRows(context, parentQuery(query)));
        if (query.patientId() == null) {
            return List.copyOf(rows);
        }
        rows.addAll(jdbc.query(
                """
                SELECT id,category_key,severity_key,statement_code,visibility_key,status,
                       review_at,expires_at,lock_version
                FROM patient_safety_flags
                WHERE organization_id=? AND patient_id=?
                ORDER BY updated_at DESC,id LIMIT 100
                """,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    return row(
                            query.screenId(),
                            id,
                            query.patientId(),
                            resultSet.getString("status"),
                            resultSet.getLong("lock_version"),
                            values(
                                    "$kind", "safety-flag",
                                    "primary", resultSet.getString("statement_code"),
                                    "secondary", resultSet.getString("category_key") + " · " + resultSet.getString("severity_key"),
                                    "context", resultSet.getString("visibility_key"),
                                    "review", instantString(resultSet.getTimestamp("review_at"))));
                },
                context.organizationId(),
                query.patientId()));
        return List.copyOf(rows);
    }

    private List<PatientRegistryScreen.Row> duplicateRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        return jdbc.query(
                """
                WITH projected AS (
                    SELECT candidate.*,
                           CASE
                               WHEN candidate.status='under_review'
                                AND (candidate.lease_expires_at IS NULL
                                     OR candidate.lease_expires_at<=clock_timestamp()) THEN 'open'
                               ELSE candidate.status
                           END projected_status
                    FROM patient_duplicate_candidates candidate
                    WHERE candidate.organization_id=?
                )
                SELECT candidate.id,candidate.patient_a_id,candidate.patient_b_id,
                       candidate.detector_version,
                       array_to_string(candidate.factor_categories,', ') factors,
                       candidate.match_band,candidate.priority_key,candidate.review_due_at,
                       candidate.assigned_reviewer_id,candidate.lease_expires_at,
                       candidate.projected_status AS status,candidate.lock_version,
                       a.patient_number patient_a_number,b.patient_number patient_b_number
                FROM projected candidate
                JOIN patient_profiles a
                  ON a.organization_id=candidate.organization_id AND a.id=candidate.patient_a_id
                JOIN patient_profiles b
                  ON b.organization_id=candidate.organization_id AND b.id=candidate.patient_b_id
                WHERE (CAST(? AS text) IS NULL OR candidate.projected_status=CAST(? AS text))
                ORDER BY CASE candidate.priority_key
                    WHEN 'urgent' THEN 0 WHEN 'high' THEN 1
                    WHEN 'routine' THEN 2 ELSE 3 END,
                    candidate.review_due_at,candidate.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    var assigned = resultSet.getObject("assigned_reviewer_id", UUID.class);
                    return row(
                            query.screenId(),
                            id,
                            null,
                            resultSet.getString("status"),
                            resultSet.getLong("lock_version"),
                            values(
                                    "$kind", "duplicate-candidate",
                                    "primary", maskPatientNumber(resultSet.getString("patient_a_number"))
                                            + " ↔ "
                                            + maskPatientNumber(resultSet.getString("patient_b_number")),
                                    "secondary", resultSet.getString("match_band"),
                                    "context", resultSet.getString("factors"),
                                    "patientAId", resultSet.getObject("patient_a_id", UUID.class).toString(),
                                    "patientBId", resultSet.getObject("patient_b_id", UUID.class).toString(),
                                    "priority", resultSet.getString("priority_key"),
                                    "due", resultSet.getTimestamp("review_due_at").toInstant().toString(),
                                    "leaseExpires", instantString(
                                            resultSet.getTimestamp("lease_expires_at")),
                                    "assignment", assigned == null
                                                    || resultSet.getString("status").equals("open")
                                            ? "Unassigned"
                                            : assigned.equals(context.actorId())
                                                    ? "Assigned to you"
                                                    : "Assigned"));
                },
                context.organizationId(),
                query.status(),
                query.status(),
                query.limit());
    }

    private List<PatientRegistryScreen.Row> mergeRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        var rows = new ArrayList<PatientRegistryScreen.Row>();
        rows.addAll(jdbc.query(
                """
                WITH projected AS (
                    SELECT request.*,
                           CASE
                               WHEN request.status IN ('submitted','approved')
                                AND request.expires_at<=clock_timestamp() THEN 'expired'
                               ELSE request.status
                           END projected_status
                    FROM patient_merge_requests request
                    WHERE request.organization_id=?
                )
                SELECT request.id,request.survivor_patient_id,request.duplicate_patient_id,
                       request.affected_reference_count,request.impact_digest,
                       request.requested_by,request.expires_at,
                       request.projected_status AS status,
                       request.lock_version,survivor.patient_number survivor_number,
                       duplicate.patient_number duplicate_number,
                       approved_decision.id approved_decision_id
                FROM projected request
                JOIN patient_profiles survivor
                  ON survivor.organization_id=request.organization_id
                 AND survivor.id=request.survivor_patient_id
                JOIN patient_profiles duplicate
                  ON duplicate.organization_id=request.organization_id
                 AND duplicate.id=request.duplicate_patient_id
                LEFT JOIN LATERAL (
                    SELECT decision.id
                    FROM patient_merge_decisions decision
                    WHERE decision.organization_id=request.organization_id
                      AND decision.merge_request_id=request.id
                      AND decision.status='approved'
                      AND decision.decision_expires_at>clock_timestamp()
                    ORDER BY decision.created_at DESC,decision.id DESC
                    LIMIT 1
                ) approved_decision ON true
                WHERE (CAST(? AS text) IS NULL
                       OR request.projected_status=CAST(? AS text))
                ORDER BY request.updated_at DESC,request.id LIMIT ?
                """,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    var approvedDecision = resultSet.getObject("approved_decision_id", UUID.class);
                    return row(
                            query.screenId(),
                            id,
                            null,
                            resultSet.getString("status"),
                            resultSet.getLong("lock_version"),
                            values(
                                    "$kind", "merge-request",
                                    "primary", maskPatientNumber(resultSet.getString("survivor_number"))
                                            + " ← "
                                            + maskPatientNumber(resultSet.getString("duplicate_number")),
                                    "secondary", resultSet.getInt("affected_reference_count") + " affected references",
                                    "context", "Impact " + resultSet.getString("impact_digest").substring(0, 12) + "…",
                                    "survivorPatientId", resultSet.getObject("survivor_patient_id", UUID.class).toString(),
                                    "duplicatePatientId", resultSet.getObject("duplicate_patient_id", UUID.class).toString(),
                                    "decisionId", approvedDecision == null ? "" : approvedDecision.toString(),
                                    "maker", resultSet.getObject("requested_by", UUID.class).equals(context.actorId())
                                            ? "Requested by you"
                                            : "Independent request",
                                    "expires", resultSet.getTimestamp("expires_at").toInstant().toString()));
                },
                context.organizationId(),
                query.status(),
                query.status(),
                query.limit()));
        if (rows.isEmpty()) {
            rows.addAll(duplicateRows(context, query));
        }
        return List.copyOf(rows);
    }

    private List<PatientRegistryScreen.Row> timelineRows(
            AuthorizedTenantContext context, ScreenQuery query) {
        if (query.patientId() == null) {
            return patientRows(context, parentQuery(query));
        }
        return jdbc.query(
                """
                WITH RECURSIVE patient_lineage(id,path,depth) AS (
                    SELECT patient.id,ARRAY[patient.id],0
                    FROM patient_profiles patient
                    WHERE patient.organization_id=? AND patient.id=?
                    UNION ALL
                    SELECT source.id,lineage.path||source.id,lineage.depth+1
                    FROM patient_profiles source
                    JOIN patient_lineage lineage
                      ON source.merged_into_patient_id=lineage.id
                    WHERE source.organization_id=?
                      AND source.lifecycle_state='merged'
                      AND lineage.depth<32
                      AND NOT source.id=ANY(lineage.path)
                )
                SELECT event.id,event.event_name,event.subject_type,event.subject_id,
                       event.actor_kind,event.occurred_at,event.schema_version,
                       event.correlation_id
                FROM audit_events event
                JOIN audit_event_definitions definition
                  ON definition.event_name=event.event_name
                 AND definition.schema_version=event.schema_version
                 AND definition.registry_version='m3-candidate-1'
                 AND definition.status='active'
                WHERE event.organization_id=?
                  AND (
                      event.subject_id IN (SELECT id FROM patient_lineage)
                      OR EXISTS (
                          SELECT 1 FROM patient_lineage lineage
                          WHERE lineage.id::text IN (
                              event.payload->>'patientId',
                              event.payload->>'survivorPatientId',
                              event.payload->>'duplicatePatientId',
                              event.payload->>'patientAId',
                              event.payload->>'patientBId'))
                      OR (event.subject_type='patient_registration'
                          AND event.subject_id IN (
                              SELECT run.id FROM patient_registration_runs run
                              WHERE run.organization_id=event.organization_id
                                AND (run.selected_patient_id IN (SELECT id FROM patient_lineage)
                                     OR run.completed_patient_id IN (SELECT id FROM patient_lineage))))
                      OR (event.subject_type='patient_duplicate_candidate'
                          AND event.subject_id IN (
                              SELECT candidate.id FROM patient_duplicate_candidates candidate
                              WHERE candidate.organization_id=event.organization_id
                                AND (candidate.patient_a_id IN (SELECT id FROM patient_lineage)
                                     OR candidate.patient_b_id IN (SELECT id FROM patient_lineage))))
                      OR (event.subject_type='patient_merge_request'
                          AND event.subject_id IN (
                              SELECT request.id FROM patient_merge_requests request
                              WHERE request.organization_id=event.organization_id
                                AND (request.survivor_patient_id IN (SELECT id FROM patient_lineage)
                                     OR request.duplicate_patient_id IN (SELECT id FROM patient_lineage))))
                  )
                ORDER BY event.occurred_at DESC,event.id DESC LIMIT ?
                """,
                (resultSet, rowNumber) -> {
                    var id = resultSet.getObject("id", UUID.class);
                    return row(
                            query.screenId(),
                            id,
                            query.patientId(),
                            "recorded",
                            0,
                            values(
                                    "$kind", "timeline",
                                    "primary", timelineTitle(resultSet.getString("event_name")),
                                    "secondary", resultSet.getString("subject_type"),
                                    "context", resultSet.getString("actor_kind"),
                                    "occurred", resultSet.getTimestamp("occurred_at").toInstant().toString(),
                                    "schema", "v" + resultSet.getInt("schema_version"),
                                    "correlation", resultSet.getString("correlation_id"),
                                    "redaction", "Minimum-necessary summary"));
                },
                context.organizationId(),
                query.patientId(),
                context.organizationId(),
                context.organizationId(),
                query.limit());
    }

    private UUID canonicalPatientId(
            AuthorizedTenantContext context, UUID requestedPatientId) {
        if (requestedPatientId == null) return null;
        var resolved = jdbc.query(
                """
                WITH RECURSIVE lineage(id,merged_into_patient_id,lifecycle_state,path,depth) AS (
                    SELECT patient.id,patient.merged_into_patient_id,
                           patient.lifecycle_state,ARRAY[patient.id],0
                    FROM patient_profiles patient
                    WHERE patient.organization_id=? AND patient.id=?
                    UNION ALL
                    SELECT target.id,target.merged_into_patient_id,
                           target.lifecycle_state,lineage.path||target.id,lineage.depth+1
                    FROM patient_profiles target
                    JOIN lineage ON target.id=lineage.merged_into_patient_id
                    WHERE target.organization_id=?
                      AND lineage.lifecycle_state='merged'
                      AND lineage.depth<32
                      AND NOT target.id=ANY(lineage.path)
                )
                SELECT id FROM lineage ORDER BY depth DESC LIMIT 1
                """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject("id", UUID.class)
                        : null,
                context.organizationId(),
                requestedPatientId,
                context.organizationId());
        return resolved == null ? requestedPatientId : resolved;
    }

    private static ScreenQuery queryWithPatient(
            ScreenQuery query, UUID patientId) {
        if (Objects.equals(query.patientId(), patientId)) return query;
        return new ScreenQuery(
                query.screenId(),
                patientId,
                query.registrationId(),
                query.search(),
                query.status(),
                query.limit());
    }

    private PatientRegistryScreen.Row withMergeLineageMarker(
            PatientRegistryScreen.Row row) {
        if (!row.values().getOrDefault("$kind", "").equals("patient")) return row;
        var values = new LinkedHashMap<>(row.values());
        values.put("lineage", "Resolved from a merged patient record");
        return new PatientRegistryScreen.Row(
                row.id(),
                row.patientId(),
                row.status(),
                row.revision(),
                row.etag(),
                values,
                row.allowedActionKeys());
    }

    private MutationResult startRegistration(
            AuthorizedTenantContext context, MutationCommand command) {
        var registrationId = UuidV7Generator.randomUuid();
        var source = token(command, "registrationSource", 2, 80);
        var supplierRelationship = optionalToken(command, "supplierRelationshipKey", 2, 80);
        var purpose = token(command, "purposeKey", 2, 80);
        var facilityId = optionalUuid(command, "facilityId");
        var urgent = bool(command, "urgent");
        var urgentReason = optional(command, "urgentReasonCode");
        if (urgent && urgentReason == null) {
            throw invalid("urgentReasonCode is required for urgent registration.");
        }
        if (!urgent && urgentReason != null) {
            throw invalid("urgentReasonCode is allowed only for urgent registration.");
        }
        if (urgent) {
            throw conflict(
                    "Urgent temporary registration is unavailable until the reconciliation worker is active.");
        }
        var expiresAt = command.now().plusSeconds(86_400);
        jdbc.update(
                """
                INSERT INTO patient_registration_runs
                    (id,organization_id,registration_source,supplier_relationship_key,
                     purpose_key,facility_id,
                     creator_id,current_step,expires_at,urgent,urgent_reason_code,status,
                     provenance_source,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,'search',?,?,?,'collecting','staff_entry',?,?)
                """,
                registrationId,
                context.organizationId(),
                source,
                supplierRelationship,
                purpose,
                facilityId,
                context.actorId(),
                Timestamp.from(expiresAt),
                urgent,
                urgentReason,
                context.actorId(),
                context.actorId());
        return result(
                registrationId,
                null,
                "patient_registration",
                "patient.registration.started",
                null,
                "patient_registration",
                valuesObject(
                        "registrationId", registrationId,
                        "sourceCode", source,
                        "expiresAt", expiresAt,
                        "currentStep", "search"),
                Map.of(),
                201,
                0);
    }

    private MutationResult searchDuplicates(
            AuthorizedTenantContext context, MutationCommand command) {
        var run = lockRegistration(context, requireTarget(command));
        requireRegistrationAccess(context, run);
        requireRevision(run.revision(), command);
        requireRegistrationMutable(run, command.now());
        if (!Set.of("collecting", "duplicate_review").contains(run.state())
                || (run.disposition() != null
                        && !run.disposition().equals("escalate_review"))) {
            throw conflict("Duplicate search is not available in the current registration state.");
        }
        var officialName = optional(command, "officialName");
        var birthDate = optionalDate(command, "birthDate");
        var contact = optional(command, "contact");
        var identifier = optional(command, "identifier");
        if (officialName == null && birthDate == null && contact == null && identifier == null) {
            throw invalid("At least one bounded duplicate-search factor is required.");
        }
        if (officialName != null) requireLiteralSearch(officialName, 3, "Name search");
        if (identifier != null) {
            throw invalid(
                    "Identifier search is unavailable until an approved local scheme version is active.");
        }
        var factorCategories = new ArrayList<String>();
        if (officialName != null) factorCategories.add("name");
        if (birthDate != null) factorCategories.add("birth_date");
        if (contact != null) factorCategories.add("contact");
        var emailDigest = contact != null && contact.contains("@")
                ? sensitiveValues.digest(
                        context.organizationId(), "contact:email", normalizeEmail(contact))
                : null;
        var phoneDigest = contact != null && !contact.contains("@")
                ? sensitiveValues.digest(
                        context.organizationId(), "contact:phone", normalizePhone(contact))
                : null;
        var candidates = jdbc.query(
                """
                SELECT DISTINCT patient.id,patient.lock_version
                FROM patient_profiles patient
                LEFT JOIN patient_contacts contact
                  ON contact.organization_id=patient.organization_id
                 AND contact.patient_id=patient.id AND contact.status='active'
                WHERE patient.organization_id=?
                  AND patient.lifecycle_state NOT IN ('merged','entered_in_error')
                  AND (
                    (CAST(? AS text) IS NOT NULL
                     AND concat_ws(' ',patient.official_given_name,
                         patient.official_family_name,patient.name_to_use)
                         ILIKE '%'||CAST(? AS text)||'%')
                    OR (CAST(? AS date) IS NOT NULL AND patient.birth_date_value=CAST(? AS date))
                    OR (CAST(? AS text) IS NOT NULL
                        AND contact.value_digest IN (CAST(? AS text),CAST(? AS text)))
                  )
                ORDER BY patient.lock_version DESC,patient.id
                LIMIT 20
                """,
                (resultSet, rowNumber) -> new Candidate(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getLong("lock_version")),
                context.organizationId(),
                officialName,
                officialName,
                birthDate,
                birthDate,
                contact,
                emailDigest,
                phoneDigest);
        var candidateDigest = digest(
                DETECTOR_VERSION
                        + "|"
                        + String.join(",", factorCategories)
                        + "|"
                        + candidates.stream()
                                .map(candidate -> candidate.id() + ":" + candidate.revision())
                                .collect(java.util.stream.Collectors.joining("|")));
        var candidateIdsJson = candidates.stream()
                .map(candidate -> "\"" + candidate.id() + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        var factorCategoriesJson = factorCategories.stream()
                .map(category -> "\"" + category + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        var candidateBandsJson = candidates.isEmpty() ? "[]" : "[\"possible_review\"]";
        var summary = "{\"candidateCount\":"
                + candidates.size()
                + ",\"candidateIds\":"
                + candidateIdsJson
                + ",\"candidateBands\":"
                + candidateBandsJson
                + ",\"factorCategories\":"
                + factorCategoriesJson
                + "}";
        var changed = jdbc.update(
                """
                UPDATE patient_registration_runs
                SET duplicate_search_completed_at=?,duplicate_detector_version=?,
                    duplicate_result_digest=?,staged_summary=CAST(? AS jsonb),
                    current_step='search',status='duplicate_review',updated_at=?,
                    updated_by=?,lock_version=lock_version+1
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                Timestamp.from(command.now()),
                DETECTOR_VERSION,
                candidateDigest,
                summary,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                run.id(),
                run.revision());
        requireChanged(changed, "The registration changed before duplicate search completed.");
        return result(
                run.id(),
                run.patientId(),
                "patient_registration",
                "patient.registration.search_completed",
                null,
                "patient_registration",
                valuesObject(
                        "registrationId", run.id(),
                        "detectorVersion", DETECTOR_VERSION,
                        "candidateCount", candidates.size(),
                        "candidateBands", candidates.isEmpty()
                                ? List.of()
                                : List.of("possible_review"),
                        "resultDigest", candidateDigest),
                Map.of(),
                200,
                run.revision() + 1);
    }

    private MutationResult recordRegistrationDisposition(
            AuthorizedTenantContext context, MutationCommand command) {
        var run = lockRegistration(context, requireTarget(command));
        requireRegistrationAccess(context, run);
        requireRevision(run.revision(), command);
        requireRegistrationMutable(run, command.now());
        if (!run.state().equals("duplicate_review")) {
            throw conflict("A current duplicate review is required before disposition.");
        }
        if (run.searchDigest() == null) {
            throw conflict("Duplicate search must complete before recording a disposition.");
        }
        var disposition = required(command, "dispositionCode");
        if (!Set.of("create_new", "use_existing", "escalate_review", "urgent_temporary")
                .contains(disposition)) {
            throw invalid("The registration disposition is invalid.");
        }
        if (disposition.equals("urgent_temporary") && !run.urgent()) {
            throw conflict("The registration was not started as an urgent temporary pathway.");
        }
        if (disposition.equals("urgent_temporary")) {
            throw conflict(
                    "Urgent temporary identity is unavailable until the reconciliation worker is active.");
        }
        UUID patientId = null;
        var additionalAudits = new ArrayList<PatientRegistryStore.AdditionalAudit>();
        if (disposition.equals("use_existing")) {
            patientId = uuid(command, "selectedPatientId");
            requireUsablePatient(context, patientId);
        } else if (disposition.equals("create_new")
                || disposition.equals("urgent_temporary")) {
            patientId = createDraftPatient(context, command, disposition);
            additionalAudits.addAll(createDuplicateCandidates(
                    context,
                    run,
                    patientId,
                    disposition.equals("urgent_temporary"),
                    command.now(),
                    command.reason()));
        }
        var status = disposition.equals("escalate_review") ? "duplicate_review" : "collecting";
        var nextStep = disposition.equals("escalate_review") ? "search" : "identity";
        var changed = jdbc.update(
                """
                UPDATE patient_registration_runs
                SET duplicate_disposition=?,selected_patient_id=?,current_step=?,status=?,
                    updated_at=?,updated_by=?,lock_version=lock_version+1
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                disposition,
                patientId,
                nextStep,
                status,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                run.id(),
                run.revision());
        requireChanged(changed, "The registration changed before disposition was recorded.");
        var dispositionDigest = digest(
                run.id() + "|" + disposition + "|" + patientId + "|" + run.searchDigest());
        return result(
                run.id(),
                patientId,
                "patient_registration",
                "patient.registration.disposition_recorded",
                null,
                "patient_registration",
                valuesObject(
                        "registrationId", run.id(),
                        "dispositionCode", disposition,
                        "patientId", patientId,
                        "sourceRevision", run.revision(),
                        "resultDigest", dispositionDigest),
                Map.of(),
                patientId == null ? 200 : 201,
                run.revision() + 1,
                additionalAudits);
    }

    private MutationResult correctIdentity(
            AuthorizedTenantContext context, MutationCommand command) {
        var patient = lockPatient(context, requireTarget(command));
        requireRevision(patient.revision(), command);
        if (Set.of("merged", "entered_in_error").contains(patient.state())) {
            throw conflict("Merged or entered-in-error patient identity cannot be changed.");
        }
        var given = suppliedOrCurrent(command, "officialGivenName", patient.givenName());
        var family = suppliedOrCurrent(command, "officialFamilyName", patient.familyName());
        var nameToUse = suppliedOrCurrent(command, "nameToUse", patient.nameToUse());
        var nameState = suppliedOrCurrent(command, "nameState", patient.nameState());
        if (!Set.of("provided", "temporary", "unnamed", "unknown").contains(nameState)) {
            throw invalid("nameState is invalid.");
        }
        if (nameState.equals("provided") && blank(given) && blank(family) && blank(nameToUse)) {
            throw invalid("A provided identity requires at least one name value.");
        }
        var certainty = suppliedOrCurrent(
                command, "birthDateCertainty", patient.birthCertainty());
        if (!Set.of("exact", "estimated", "unknown").contains(certainty)) {
            throw invalid("birthDateCertainty is invalid.");
        }
        LocalDate birthDate;
        String precision;
        if (certainty.equals("unknown")) {
            birthDate = null;
            precision = null;
        } else {
            birthDate = command.fields().containsKey("birthDate")
                    ? optionalDate(command, "birthDate")
                    : patient.birthDate();
            precision = suppliedOrCurrent(
                    command, "birthDatePrecision", patient.birthPrecision());
            if (birthDate == null || !Set.of("year", "month", "day").contains(precision)) {
                throw invalid("Known birth dates require a value and precision.");
            }
            if (birthDate.isAfter(LocalDate.ofInstant(command.now(), ZoneOffset.UTC))) {
                throw invalid("Birth date must not be in the future.");
            }
        }
        var provenance = token(command, "provenanceCode", 2, 80);
        var administrativeSex = suppliedOrCurrent(
                command, "administrativeSexCode", patient.administrativeSex());
        var genderIdentity = suppliedOrCurrent(
                command, "genderIdentityCode", patient.genderIdentity());
        var pronouns = suppliedOrCurrent(command, "pronounsCode", patient.pronouns());
        var predecessorReference = latestIdentityRevisionId(context, patient.id());
        var changed = jdbc.update(
                """
                UPDATE patient_profiles
                SET official_given_name=?,official_family_name=?,name_to_use=?,name_state=?,
                    birth_date_value=?,birth_date_precision=?,birth_date_certainty=?,
                    administrative_sex_code=?,gender_identity_code=?,pronouns_code=?,
                    provenance_source=?,updated_at=?,updated_by=?,lock_version=lock_version+1
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                nullIfBlank(given),
                nullIfBlank(family),
                nullIfBlank(nameToUse),
                nameState,
                birthDate,
                precision,
                certainty,
                nullIfBlank(administrativeSex),
                nullIfBlank(genderIdentity),
                nullIfBlank(pronouns),
                provenance,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                patient.id(),
                patient.revision());
        requireChanged(changed, "The patient identity changed before this correction completed.");
        var successorReference = appendIdentityRevision(
                context,
                patient.id(),
                predecessorReference,
                patient.revision() + 1,
                given,
                family,
                nameToUse,
                nameState,
                birthDate,
                precision,
                certainty,
                administrativeSex,
                genderIdentity,
                pronouns,
                provenance,
                command.now());
        var fields = changedIdentityFields(patient, given, family, nameToUse, nameState, birthDate,
                precision, certainty, administrativeSex, genderIdentity, pronouns);
        return result(
                patient.id(),
                patient.id(),
                "patient_profile",
                "patient.identity.corrected",
                "m3.patient.identity-changed.v1",
                "patient",
                valuesObject(
                        "patientId", patient.id(),
                        "changedFields", fields,
                        "predecessorReference", predecessorReference,
                        "successorReference", successorReference,
                        "provenanceCode", provenance,
                        "revision", patient.revision() + 1),
                valuesObject(
                        "patientId", patient.id(),
                        "changeFamily", "identity",
                        "revision", patient.revision() + 1),
                200,
                patient.revision() + 1);
    }

    private List<PatientRegistryStore.AdditionalAudit> createDuplicateCandidates(
            AuthorizedTenantContext context,
            Registration run,
            UUID newPatientId,
            boolean urgent,
            Instant now,
            String reason) {
        var candidateIds = jdbc.queryForList(
                """
                SELECT candidate.value::uuid
                FROM patient_registration_runs registration
                CROSS JOIN LATERAL jsonb_array_elements_text(
                    COALESCE(registration.staged_summary->'candidateIds','[]'::jsonb)
                ) candidate(value)
                WHERE registration.organization_id=? AND registration.id=?
                ORDER BY candidate.value
                LIMIT 20
                """,
                UUID.class,
                context.organizationId(),
                run.id());
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        var factorCategories = jdbc.queryForList(
                """
                SELECT factor.value
                FROM patient_registration_runs registration
                CROSS JOIN LATERAL jsonb_array_elements_text(
                    COALESCE(registration.staged_summary->'factorCategories','[]'::jsonb)
                ) factor(value)
                WHERE registration.organization_id=? AND registration.id=?
                ORDER BY factor.value
                """,
                String.class,
                context.organizationId(),
                run.id());
        if (factorCategories.isEmpty()) {
            factorCategories = List.of("registration_search");
        }
        var audits = new ArrayList<PatientRegistryStore.AdditionalAudit>();
        for (var candidateId : candidateIds) {
            var candidate = lockPatient(context, candidateId);
            var patientAId = newPatientId.toString().compareTo(candidateId.toString()) < 0
                    ? newPatientId
                    : candidateId;
            var patientBId = patientAId.equals(newPatientId) ? candidateId : newPatientId;
            var patientARevision = patientAId.equals(newPatientId) ? 0L : candidate.revision();
            var patientBRevision = patientBId.equals(newPatientId) ? 0L : candidate.revision();
            var resultDigest = digest(
                    DETECTOR_VERSION
                            + "|"
                            + run.searchDigest()
                            + "|"
                            + patientAId
                            + ":"
                            + patientARevision
                            + "|"
                            + patientBId
                            + ":"
                            + patientBRevision);
            var candidateRecordId = UuidV7Generator.randomUuid();
            var reviewDueAt = now.plusSeconds(urgent ? 3_600 : 86_400);
            var inserted = jdbc.query(
                    """
                    INSERT INTO patient_duplicate_candidates
                        (id,organization_id,patient_a_id,patient_b_id,
                         patient_a_revision,patient_b_revision,detector_version,
                         factor_categories,result_digest,match_band,priority_key,
                         review_due_at,status,provenance_source,created_by,updated_by)
                    VALUES (?,?,?,?,?,?,?,?::varchar[],?,? ,?,?,'open',?,?,?)
                    ON CONFLICT (organization_id,patient_a_id,patient_b_id)
                        WHERE status IN ('open','under_review','merge_requested')
                    DO NOTHING
                    RETURNING id
                    """,
                    resultSet -> resultSet.next()
                            ? resultSet.getObject("id", UUID.class)
                            : null,
                    candidateRecordId,
                    context.organizationId(),
                    patientAId,
                    patientBId,
                    patientARevision,
                    patientBRevision,
                    DETECTOR_VERSION,
                    factorCategories.toArray(String[]::new),
                    resultDigest,
                    urgent ? "high_review" : "possible_review",
                    urgent ? "urgent" : "routine",
                    Timestamp.from(reviewDueAt),
                    "registration-disposition",
                    context.actorId(),
                    context.actorId());
            if (inserted != null) {
                audits.add(new PatientRegistryStore.AdditionalAudit(
                        "patient.duplicate.detected",
                        "patient_duplicate_candidate",
                        inserted,
                        reason,
                        valuesObject(
                                "candidateId", inserted,
                                "patientAId", patientAId,
                                "patientBId", patientBId,
                                "patientARevision", patientARevision,
                                "patientBRevision", patientBRevision,
                                "detectorVersion", DETECTOR_VERSION,
                                "factorCategories", factorCategories,
                                "matchBand", urgent ? "high_review" : "possible_review",
                                "resultDigest", resultDigest,
                                "reviewDueAt", reviewDueAt)));
            }
        }
        return List.copyOf(audits);
    }

    private MutationResult addContact(
            AuthorizedTenantContext context, MutationCommand command) {
        var patient = lockPatient(context, requireTarget(command));
        requireRevision(patient.revision(), command);
        requireMutablePatient(patient);
        var channel = required(command, "channel");
        if (!Set.of("email", "phone", "sms", "other").contains(channel)) {
            throw invalid("Contact channel is invalid.");
        }
        var normalized = channel.equals("email")
                ? normalizeEmail(required(command, "value"))
                : normalizePhone(required(command, "value"));
        var use = token(command, "contactUse", 1, 32);
        var purpose = token(command, "purposeKey", 2, 80);
        var primary = bool(command, "primary");
        var preferred = bool(command, "preferred");
        var confidential = bool(command, "confidential");
        var provenance = token(command, "provenanceCode", 2, 80);
        var priorPrimaryReferences = primary
                ? jdbc.queryForList(
                        """
                        SELECT id FROM patient_contacts
                        WHERE organization_id=? AND patient_id=? AND channel=? AND purpose_key=?
                          AND primary_contact AND status='active' AND effective_to IS NULL
                        ORDER BY id
                        """,
                        UUID.class,
                        context.organizationId(),
                        patient.id(),
                        channel,
                        purpose)
                : List.<UUID>of();
        if (primary) {
            jdbc.update(
                    """
                    UPDATE patient_contacts
                    SET primary_contact=false,updated_at=?,updated_by=?,lock_version=lock_version+1
                    WHERE organization_id=? AND patient_id=? AND channel=? AND purpose_key=?
                      AND primary_contact AND status='active' AND effective_to IS NULL
                    """,
                    Timestamp.from(command.now()),
                    context.actorId(),
                    context.organizationId(),
                    patient.id(),
                    channel,
                    purpose);
        }
        var id = UuidV7Generator.randomUuid();
        var digest = sensitiveValues.digest(
                context.organizationId(), "contact:" + channel, normalized);
        var encrypted = sensitiveValues.encrypt(
                context.organizationId(), "contact:" + channel, normalized);
        jdbc.update(
                """
                INSERT INTO patient_contacts
                    (id,organization_id,patient_id,channel,contact_use,purpose_key,
                     encrypted_value,normalization_version,value_digest,masked_display,
                     primary_contact,preferred_contact,confidential,effective_from,status,
                     provenance_source,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,'m3-contact-normalization-v1',?,?,
                        ?,?,?,?,'active',?,?,?)
                """,
                id,
                context.organizationId(),
                patient.id(),
                channel,
                use,
                purpose,
                encrypted,
                digest,
                maskContact(channel, normalized),
                primary,
                preferred,
                confidential,
                Timestamp.from(command.now()),
                provenance,
                context.actorId(),
                context.actorId());
        touchPatient(context, patient, command.now());
        return result(
                id,
                patient.id(),
                "patient_contact",
                "patient.contact.changed",
                null,
                "patient",
                valuesObject(
                        "patientId", patient.id(),
                        "contactId", id,
                        "channel", channel,
                        "contactUse", use,
                        "state", "active",
                        "verificationClass", "unverified",
                        "primary", primary,
                        "preferred", preferred,
                        "priorPrimaryReferences", priorPrimaryReferences,
                        "revision", 0),
                Map.of(),
                201,
                0);
    }

    private MutationResult addAddress(
            AuthorizedTenantContext context, MutationCommand command) {
        var patient = lockPatient(context, requireTarget(command));
        requireRevision(patient.revision(), command);
        requireMutablePatient(patient);
        var addressUse = token(command, "addressUse", 1, 32);
        var purpose = token(command, "purposeKey", 2, 80);
        var line1 = required(command, "line1");
        var line2 = optional(command, "line2");
        var locality = required(command, "locality");
        var region = optional(command, "region");
        var postalCode = optional(command, "postalCode");
        var country = required(command, "countryCode").toUpperCase(Locale.ROOT);
        if (!country.matches("[A-Z]{2}")) {
            throw invalid("countryCode must contain two uppercase letters.");
        }
        var primary = bool(command, "primary");
        var preferred = bool(command, "preferred");
        var confidential = bool(command, "confidential");
        var provenance = token(command, "provenanceCode", 2, 80);
        var normalized = normalizeAddress(line1, line2, locality, region, postalCode, country);
        var priorPrimaryReferences = primary
                ? jdbc.queryForList(
                        """
                        SELECT id FROM patient_addresses
                        WHERE organization_id=? AND patient_id=?
                          AND address_use=? AND purpose_key=?
                          AND primary_address AND status='active' AND effective_to IS NULL
                        ORDER BY id
                        """,
                        UUID.class,
                        context.organizationId(),
                        patient.id(),
                        addressUse,
                        purpose)
                : List.<UUID>of();
        if (primary) {
            jdbc.update(
                    """
                    UPDATE patient_addresses
                    SET primary_address=false,updated_at=?,updated_by=?,lock_version=lock_version+1
                    WHERE organization_id=? AND patient_id=? AND address_use=? AND purpose_key=?
                      AND primary_address AND status='active' AND effective_to IS NULL
                    """,
                    Timestamp.from(command.now()),
                    context.actorId(),
                    context.organizationId(),
                    patient.id(),
                    addressUse,
                    purpose);
        }
        var id = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO patient_addresses
                    (id,organization_id,patient_id,address_use,purpose_key,
                     encrypted_payload,normalized_digest,masked_summary,country_code,
                     primary_address,preferred_address,confidential,effective_from,status,
                     provenance_source,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'active',?,?,?)
                """,
                id,
                context.organizationId(),
                patient.id(),
                addressUse,
                purpose,
                sensitiveValues.encrypt(context.organizationId(), "address", normalized),
                sensitiveValues.digest(context.organizationId(), "address", normalized),
                "•••• · " + locality + " · " + country,
                country,
                primary,
                preferred,
                confidential,
                Timestamp.from(command.now()),
                provenance,
                context.actorId(),
                context.actorId());
        touchPatient(context, patient, command.now());
        return result(
                id,
                patient.id(),
                "patient_address",
                "patient.address.changed",
                null,
                "patient",
                valuesObject(
                        "patientId", patient.id(),
                        "addressId", id,
                        "addressUse", addressUse,
                        "state", "active",
                        "validationClass", "unvalidated",
                        "primary", primary,
                        "preferred", preferred,
                        "priorPrimaryReferences", priorPrimaryReferences,
                        "revision", 0),
                Map.of(),
                201,
                0);
    }

    private MutationResult setPreference(
            AuthorizedTenantContext context, MutationCommand command) {
        var patient = lockPatient(context, requireTarget(command));
        requireRevision(patient.revision(), command);
        requireMutablePatient(patient);
        var purpose = token(command, "purposeKey", 2, 80);
        var channel = required(command, "channel");
        var decision = required(command, "decision");
        if (!Set.of("email", "phone", "sms", "postal", "portal", "other")
                        .contains(channel)
                || !Set.of("allow", "deny", "prefer").contains(decision)) {
            throw invalid("Communication preference values are invalid.");
        }
        var language = optional(command, "languageTag");
        if (language != null
                && !language.matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")) {
            throw invalid("languageTag is invalid.");
        }
        var format = optional(command, "accessibleFormatKey");
        var provenance = token(command, "provenanceCode", 2, 80);
        var predecessorReference = jdbc.query(
                """
                SELECT id FROM communication_preferences
                WHERE organization_id=? AND patient_id=? AND purpose_key=? AND channel=?
                  AND status='active' AND effective_to IS NULL
                ORDER BY effective_from DESC,id DESC LIMIT 1
                """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject("id", UUID.class)
                        : null,
                context.organizationId(),
                patient.id(),
                purpose,
                channel);
        jdbc.update(
                """
                UPDATE communication_preferences
                SET effective_to=?,status='superseded',updated_at=?,updated_by=?,
                    lock_version=lock_version+1
                WHERE organization_id=? AND patient_id=? AND purpose_key=? AND channel=?
                  AND status='active' AND effective_to IS NULL
                """,
                Timestamp.from(command.now()),
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                patient.id(),
                purpose,
                channel);
        var id = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO communication_preferences
                    (id,organization_id,patient_id,purpose_key,channel,decision,
                     language_tag,accessible_format_key,effective_from,status,
                     provenance_source,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,'active',?,?,?)
                """,
                id,
                context.organizationId(),
                patient.id(),
                purpose,
                channel,
                decision,
                language,
                format,
                Timestamp.from(command.now()),
                provenance,
                context.actorId(),
                context.actorId());
        touchPatient(context, patient, command.now());
        return result(
                id,
                patient.id(),
                "communication_preference",
                "patient.preference.changed",
                null,
                "patient",
                valuesObject(
                        "patientId", patient.id(),
                        "preferenceId", id,
                        "purposeKey", purpose,
                        "channel", channel,
                        "languageTag", language,
                        "formatKey", format,
                        "predecessorReference", predecessorReference,
                        "state", "active",
                        "revision", 0),
                Map.of(),
                201,
                0);
    }

    private MutationResult addRelationship(
            AuthorizedTenantContext context, MutationCommand command) {
        var patient = lockPatient(context, requireTarget(command));
        requireRevision(patient.revision(), command);
        requireMutablePatient(patient);
        var relatedReference = uuid(command, "relatedPersonReference");
        var relatedPatient = optionalUuid(command, "relatedPatientId");
        if (patient.id().equals(relatedPatient)) {
            throw invalid("A patient cannot be their own related patient.");
        }
        if (relatedPatient != null) {
            requireUsablePatient(context, relatedPatient);
        }
        var relationshipType = token(command, "relationshipTypeKey", 2, 80);
        var displayLabel = required(command, "displayLabel");
        var provenance = token(command, "provenanceCode", 2, 80);
        var id = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO caregiver_relationships
                    (id,organization_id,patient_id,related_person_reference,
                     related_patient_id,relationship_type_key,display_label,
                     effective_from,status,provenance_source,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,'active',?,?,?)
                """,
                id,
                context.organizationId(),
                patient.id(),
                relatedReference,
                relatedPatient,
                relationshipType,
                displayLabel,
                Timestamp.from(command.now()),
                provenance,
                context.actorId(),
                context.actorId());
        touchPatient(context, patient, command.now());
        return result(
                id,
                patient.id(),
                "caregiver_relationship",
                "patient.relationship.changed",
                null,
                "patient",
                valuesObject(
                        "patientId", patient.id(),
                        "relationshipId", id,
                        "relationshipTypeKey", relationshipType,
                        "state", "active",
                        "revision", 0),
                Map.of(),
                201,
                0);
    }

    private MutationResult validateRegistration(
            AuthorizedTenantContext context, MutationCommand command) {
        var run = lockRegistration(context, requireTarget(command));
        requireRegistrationAccess(context, run);
        requireRevision(run.revision(), command);
        requireRegistrationMutable(run, command.now());
        if (!Set.of("collecting", "ready").contains(run.state())) {
            throw conflict("Registration validation is unavailable in the current state.");
        }
        if (run.patientId() == null || run.disposition() == null) {
            throw conflict("A duplicate disposition and patient selection are required.");
        }
        var patient = lockPatient(context, run.patientId());
        var identityReady = !patient.nameState().equals("provided")
                || !blank(patient.givenName())
                || !blank(patient.familyName())
                || !blank(patient.nameToUse());
        if (!identityReady) {
            throw conflict("Patient identity requires a name value or explicit unnamed state.");
        }
        var validationCompletedAt = command.now().truncatedTo(ChronoUnit.MICROS);
        var validationExpiresAt = validationCompletedAt.plusSeconds(900);
        var validationDigest = registrationValidationDigest(
                run,
                run.revision(),
                patient.id(),
                patient.revision(),
                validationExpiresAt);
        var validationResult = registrationValidationResult(
                context,
                run,
                patient,
                validationCompletedAt,
                validationExpiresAt,
                validationDigest);
        var changed = jdbc.update(
                """
                UPDATE patient_registration_runs
                SET validation_digest=?,validation_completed_at=?,
                    validation_schema_version=?,validation_source_revision=?,
                    validated_patient_revision=?,validation_expires_at=?,
                    validation_result=CAST(? AS jsonb),current_step='review',status='ready',
                    updated_at=?,updated_by=?,lock_version=lock_version+1
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                validationDigest,
                Timestamp.from(validationCompletedAt),
                REGISTRATION_VALIDATION_VERSION,
                run.revision(),
                patient.revision(),
                Timestamp.from(validationExpiresAt),
                validationResult,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                run.id(),
                run.revision());
        requireChanged(changed, "The registration changed before validation completed.");
        return result(
                run.id(),
                patient.id(),
                "patient_registration",
                "patient.registration.validated",
                null,
                "patient_registration",
                valuesObject(
                        "registrationId", run.id(),
                        "patientId", patient.id(),
                        "revision", run.revision() + 1,
                        "validationDigest", validationDigest,
                        "blockerCount", 0,
                        "warningCount", 0),
                Map.of(),
                200,
                run.revision() + 1);
    }

    private MutationResult submitRegistration(
            AuthorizedTenantContext context, MutationCommand command) {
        var run = lockRegistration(context, requireTarget(command));
        requireRegistrationAccess(context, run);
        requireRevision(run.revision(), command);
        requireRegistrationMutable(run, command.now());
        if (!run.state().equals("ready")
                || run.validationDigest() == null
                || run.patientId() == null) {
            throw conflict("A fresh zero-blocker registration validation is required.");
        }
        if (!REGISTRATION_VALIDATION_VERSION.equals(run.validationSchemaVersion())
                || run.validationSourceRevision() == null
                || run.validatedPatientRevision() == null
                || run.validationExpiresAt() == null
                || !run.validationExpiresAt().isAfter(command.now())
                || run.revision() != run.validationSourceRevision() + 1) {
            throw stale("The registration validation is stale; run validation again.");
        }
        var patient = lockPatient(context, run.patientId());
        var currentValidationDigest = registrationValidationDigest(
                run,
                run.validationSourceRevision(),
                patient.id(),
                patient.revision(),
                run.validationExpiresAt());
        if (patient.revision() != run.validatedPatientRevision()
                || !run.validationDigest().equals(currentValidationDigest)) {
            throw stale("The patient changed after validation; run validation again.");
        }
        var resultKind = run.disposition().equals("use_existing") ? "existing" : "new";
        long patientRevision = patient.revision();
        if (!resultKind.equals("existing")) {
            var changedPatient = jdbc.update(
                    """
                    UPDATE patient_profiles
                    SET lifecycle_state='active',updated_at=?,updated_by=?,
                        lock_version=lock_version+1
                    WHERE organization_id=? AND id=? AND lock_version=?
                      AND lifecycle_state IN ('draft','pending_review')
                    """,
                    Timestamp.from(command.now()),
                    context.actorId(),
                    context.organizationId(),
                    patient.id(),
                    patient.revision());
            requireChanged(changedPatient, "The patient changed before registration completed.");
            patientRevision++;
        }
        var changed = jdbc.update(
                """
                UPDATE patient_registration_runs
                SET completed_patient_id=?,completed_at=?,current_step='complete',
                    status='completed',updated_at=?,updated_by=?,lock_version=lock_version+1
                WHERE organization_id=? AND id=? AND lock_version=? AND status='ready'
                """,
                patient.id(),
                Timestamp.from(command.now()),
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                run.id(),
                run.revision());
        requireChanged(changed, "The registration changed before completion.");
        return result(
                run.id(),
                patient.id(),
                "patient_registration",
                "patient.registration.completed",
                resultKind.equals("new") ? "m3.patient.registered.v1" : null,
                "patient",
                valuesObject(
                        "registrationId", run.id(),
                        "patientId", patient.id(),
                        "resultKind", resultKind,
                        "revision", patientRevision,
                        "validationDigest", run.validationDigest()),
                resultKind.equals("new")
                        ? valuesObject(
                                "patientId", patient.id(),
                                "registrationId", run.id(),
                                "revision", patientRevision,
                                "validationDigest", run.validationDigest())
                        : Map.of(),
                200,
                run.revision() + 1);
    }

    private static String registrationValidationDigest(
            Registration run,
            long sourceRevision,
            UUID patientId,
            long patientRevision,
            Instant validationExpiresAt) {
        return digest(
                REGISTRATION_VALIDATION_VERSION
                        + "|"
                        + POLICY_VERSION
                        + "|"
                        + run.id()
                        + "|"
                        + sourceRevision
                        + "|"
                        + patientId
                        + "|"
                        + patientRevision
                        + "|"
                        + run.source()
                        + "|"
                        + run.purpose()
                        + "|"
                        + run.searchDigest()
                        + "|"
                        + run.disposition()
                        + "|"
                        + validationExpiresAt);
    }

    private String registrationValidationResult(
            AuthorizedTenantContext context,
            Registration run,
            Patient patient,
            Instant evaluatedAt,
            Instant expiresAt,
            String validationDigest) {
        var contactOutcome = count("patient_contacts", context, patient.id())
                                + count("patient_addresses", context, patient.id())
                        == 0
                ? "not_applicable"
                : "complete";
        var preferenceOutcome = count("communication_preferences", context, patient.id()) == 0
                ? "not_applicable"
                : "complete";
        return """
                {"schemaVersion":"%s","evaluatorVersion":"%s","organizationId":"%s",\
                "registrationId":"%s","actorId":"%s","source":"%s","purpose":"%s",\
                "sourceRevision":%d,"patientId":"%s","patientRevision":%d,\
                "policyVersion":"%s","outcome":"complete","warningCount":0,\
                "evaluatedAt":"%s","expiresAt":"%s","digest":"%s","gates":[\
                {"key":"patient.registration.context","outcome":"complete"},\
                {"key":"patient.identity.minimum","outcome":"complete"},\
                {"key":"patient.identity.provenance","outcome":"complete"},\
                {"key":"patient.duplicate.search","outcome":"complete"},\
                {"key":"patient.duplicate.disposition","outcome":"complete"},\
                {"key":"patient.identifier.valid","outcome":"not_applicable"},\
                {"key":"patient.contact.address.valid","outcome":"%s"},\
                {"key":"patient.preference.valid","outcome":"%s"},\
                {"key":"patient.proxy.valid","outcome":"not_applicable"},\
                {"key":"patient.consent_privacy.valid","outcome":"not_applicable"},\
                {"key":"patient.safety.valid","outcome":"not_applicable"},\
                {"key":"patient.registration.review","outcome":"complete"},\
                {"key":"patient.platform.integrity","outcome":"complete"}]}
                """
                .formatted(
                        REGISTRATION_VALIDATION_VERSION,
                        REGISTRATION_VALIDATION_VERSION,
                        context.organizationId(),
                        run.id(),
                        context.actorId(),
                        run.source(),
                        run.purpose(),
                        run.revision(),
                        patient.id(),
                        patient.revision(),
                        POLICY_VERSION,
                        evaluatedAt,
                        expiresAt,
                        validationDigest,
                        contactOutcome,
                        preferenceOutcome);
    }

    private MutationResult changeLifecycle(
            AuthorizedTenantContext context, MutationCommand command) {
        var patient = lockPatient(context, requireTarget(command));
        requireRevision(patient.revision(), command);
        var next = required(command, "newState");
        var reasonCode = token(command, "reasonCode", 2, 80);
        if (!Set.of("active", "inactive", "entered_in_error").contains(next)) {
            throw invalid("The requested patient lifecycle state is unavailable.");
        }
        if (patient.state().equals("merged") || patient.state().equals(next)) {
            throw conflict("The requested lifecycle transition is not available.");
        }
        if (next.equals("active") && !patient.state().equals("inactive")) {
            throw conflict("Only an inactive patient can be reactivated.");
        }
        if (next.equals("inactive") && !patient.state().equals("active")) {
            throw conflict("Only an active patient can be made inactive.");
        }
        var changed = jdbc.update(
                """
                UPDATE patient_profiles
                SET lifecycle_state=?,updated_at=?,updated_by=?,lock_version=lock_version+1
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                next,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                patient.id(),
                patient.revision());
        requireChanged(changed, "The patient changed before the lifecycle transition completed.");
        var invalidationDigest = digest(
                patient.id() + "|" + patient.state() + "|" + next + "|" + (patient.revision() + 1));
        return result(
                patient.id(),
                patient.id(),
                "patient_profile",
                "patient.lifecycle.changed",
                "m3.patient.lifecycle-changed.v1",
                "patient",
                valuesObject(
                        "patientId", patient.id(),
                        "priorState", patient.state(),
                        "newState", next,
                        "effectiveTime", command.now(),
                        "reasonCode", reasonCode,
                        "revision", patient.revision() + 1),
                valuesObject(
                        "patientId", patient.id(),
                        "priorState", patient.state(),
                        "newState", next,
                        "revision", patient.revision() + 1,
                        "invalidationDigest", invalidationDigest),
                200,
                patient.revision() + 1);
    }

    private MutationResult claimDuplicate(
            AuthorizedTenantContext context, MutationCommand command) {
        var candidate = lockDuplicate(context, requireTarget(command));
        requireRevision(candidate.revision(), command);
        if (!Set.of("open", "under_review").contains(candidate.state())) {
            throw conflict("The duplicate candidate is no longer claimable.");
        }
        if (candidate.reviewerId() != null
                && !candidate.reviewerId().equals(context.actorId())
                && candidate.leaseExpiresAt() != null
                && candidate.leaseExpiresAt().isAfter(command.now())) {
            throw conflict("The duplicate candidate is assigned to another reviewer.");
        }
        var leaseId = UuidV7Generator.randomUuid();
        var leaseExpiresAt = command.now().plusSeconds(900);
        var changed = jdbc.update(
                """
                UPDATE patient_duplicate_candidates
                SET assigned_reviewer_id=?,lease_reference=?,lease_expires_at=?,
                    status='under_review',updated_at=?,updated_by=?,lock_version=lock_version+1
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                context.actorId(),
                leaseId,
                Timestamp.from(leaseExpiresAt),
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                candidate.id(),
                candidate.revision());
        requireChanged(changed, "The duplicate candidate changed before it was claimed.");
        return result(
                candidate.id(),
                null,
                "patient_duplicate_candidate",
                "patient.duplicate.claimed",
                null,
                "patient_duplicate_candidate",
                valuesObject(
                        "candidateId", candidate.id(),
                        "reviewerId", context.actorId(),
                        "leaseReference", leaseId,
                        "leaseExpiry", leaseExpiresAt,
                        "revision", candidate.revision() + 1),
                Map.of(),
                200,
                candidate.revision() + 1);
    }

    private MutationResult dispositionDuplicate(
            AuthorizedTenantContext context, MutationCommand command) {
        var candidate = lockDuplicate(context, requireTarget(command));
        requireRevision(candidate.revision(), command);
        requireActiveLease(candidate, context, command.now());
        var disposition = required(command, "dispositionCode");
        if (!Set.of("not_duplicate", "same_patient_no_merge", "insufficient_evidence")
                .contains(disposition)) {
            throw invalid("Duplicate disposition is invalid.");
        }
        var reasonCode = token(command, "reasonCode", 2, 80);
        var newState = disposition.equals("insufficient_evidence") ? "open" : "dismissed";
        var snapshotDigest = digest(
                candidate.id() + "|" + candidate.resultDigest() + "|" + disposition);
        var changed = jdbc.update(
                """
                UPDATE patient_duplicate_candidates
                SET disposition=?,disposition_reason_code=?,dismissed_snapshot_digest=?,
                    assigned_reviewer_id=NULL,lease_reference=NULL,lease_expires_at=NULL,
                    status=?,updated_at=?,updated_by=?,lock_version=lock_version+1
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                disposition,
                reasonCode,
                snapshotDigest,
                newState,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                candidate.id(),
                candidate.revision());
        requireChanged(changed, "The duplicate candidate changed before disposition.");
        return result(
                candidate.id(),
                null,
                "patient_duplicate_candidate",
                "patient.duplicate.dispositioned",
                null,
                "patient_duplicate_candidate",
                valuesObject(
                        "candidateId", candidate.id(),
                        "detectorVersion", candidate.detectorVersion(),
                        "dispositionCode", disposition,
                        "reasonCode", reasonCode,
                        "resultDigest", snapshotDigest,
                        "revision", candidate.revision() + 1),
                Map.of(),
                200,
                candidate.revision() + 1);
    }

    private MutationResult requestMerge(
            AuthorizedTenantContext context, MutationCommand command) {
        var candidate = lockDuplicate(context, requireTarget(command));
        requireRevision(candidate.revision(), command);
        requireActiveLease(candidate, context, command.now());
        var survivorId = uuid(command, "survivorPatientId");
        var duplicateId = uuid(command, "duplicatePatientId");
        if (!Set.of(candidate.patientAId(), candidate.patientBId()).contains(survivorId)
                || !Set.of(candidate.patientAId(), candidate.patientBId()).contains(duplicateId)
                || survivorId.equals(duplicateId)) {
            throw invalid("The survivor and duplicate must be the exact candidate pair.");
        }
        var impact = mergeRequestImpact(context, command);
        requireCurrentImpact(command, impact);
        var survivor = lockPatient(context, survivorId);
        var duplicate = lockPatient(context, duplicateId);
        requireMergeablePatients(survivor, duplicate);
        var requestId = UuidV7Generator.randomUuid();
        var expiry = command.now().plusSeconds(86_400);
        var sourceRevisionDigest = digest(
                survivor.id() + ":" + survivor.revision() + "|" + duplicate.id() + ":" + duplicate.revision());
        var fieldDispositionDigest = digest(
                "survivor-authoritative|" + survivor.id() + "|duplicate-lineage|" + duplicate.id());
        jdbc.update(
                """
                INSERT INTO patient_merge_requests
                    (id,organization_id,duplicate_candidate_id,survivor_patient_id,
                     duplicate_patient_id,survivor_revision,duplicate_revision,
                     field_disposition_digest,reference_inventory_digest,impact_digest,
                     affected_reference_count,reason_code,requested_by,submitted_at,
                     expires_at,status,provenance_source,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'submitted','duplicate_review',?,?)
                """,
                requestId,
                context.organizationId(),
                candidate.id(),
                survivor.id(),
                duplicate.id(),
                survivor.revision(),
                duplicate.revision(),
                fieldDispositionDigest,
                impact.digest(),
                impact.digest(),
                impact.items().stream().mapToInt(ImpactItem::affectedCount).sum(),
                token(command, "reasonCode", 2, 80),
                context.actorId(),
                Timestamp.from(command.now()),
                Timestamp.from(expiry),
                context.actorId(),
                context.actorId());
        jdbc.update(
                """
                UPDATE patient_duplicate_candidates
                SET disposition='merge_requested',disposition_reason_code=?,
                    status='merge_requested',updated_at=?,updated_by=?,lock_version=lock_version+1
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                token(command, "reasonCode", 2, 80),
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                candidate.id(),
                candidate.revision());
        return result(
                requestId,
                survivor.id(),
                "patient_merge_request",
                "patient.merge.requested",
                null,
                "patient_merge_request",
                valuesObject(
                        "mergeRequestId", requestId,
                        "survivorPatientId", survivor.id(),
                        "duplicatePatientId", duplicate.id(),
                        "sourceRevisionDigest", sourceRevisionDigest,
                        "fieldDispositionDigest", fieldDispositionDigest,
                        "affectedReferenceDigest", impact.digest(),
                        "impactDigest", impact.digest(),
                        "expiry", expiry),
                Map.of(),
                201,
                0);
    }

    private MutationResult decideMerge(
            AuthorizedTenantContext context, MutationCommand command) {
        var request = lockMergeRequest(context, requireTarget(command));
        requireRevision(request.revision(), command);
        if (!request.state().equals("submitted") || !request.expiresAt().isAfter(command.now())) {
            throw conflict("The merge request is not available for decision.");
        }
        if (request.makerId().equals(context.actorId())) {
            throw conflict("The merge requester cannot decide their own request.");
        }
        var decisionCode = required(command, "decisionCode");
        if (!Set.of("approve", "reject").contains(decisionCode)) {
            throw invalid("Merge decision must be approve or reject.");
        }
        var currentImpact = mergeRequestImpact(context, request);
        if (!currentImpact.digest().equals(request.impactDigest())) {
            throw stale("The merge impact changed and the request must be reviewed again.");
        }
        var decisionId = UuidV7Generator.randomUuid();
        var decisionExpiry = command.now().plusSeconds(1_800);
        var reasonCode = token(command, "reasonCode", 2, 80);
        jdbc.update(
                """
                INSERT INTO patient_merge_decisions
                    (id,organization_id,merge_request_id,request_revision,impact_digest,
                     decision,reason_code,checker_id,assurance_reference,
                     decision_expires_at,status,provenance_source,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'interactive_mfa',?,?)
                """,
                decisionId,
                context.organizationId(),
                request.id(),
                request.revision(),
                request.impactDigest(),
                decisionCode,
                reasonCode,
                context.actorId(),
                UuidV7Generator.randomUuid(),
                Timestamp.from(decisionExpiry),
                decisionCode.equals("approve") ? "approved" : "rejected",
                context.actorId(),
                context.actorId());
        var nextState = decisionCode.equals("approve") ? "approved" : "rejected";
        var changed = jdbc.update(
                """
                UPDATE patient_merge_requests
                SET status=?,updated_at=?,updated_by=?,lock_version=lock_version+1
                WHERE organization_id=? AND id=? AND lock_version=? AND status='submitted'
                """,
                nextState,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                request.id(),
                request.revision());
        requireChanged(changed, "The merge request changed before the decision completed.");
        return result(
                request.id(),
                request.survivorId(),
                "patient_merge_request",
                "patient.merge.decided",
                null,
                "patient_merge_request",
                valuesObject(
                        "mergeRequestId", request.id(),
                        "decisionId", decisionId,
                        "requestRevision", request.revision(),
                        "decisionCode", decisionCode,
                        "reasonCode", reasonCode,
                        "impactDigest", request.impactDigest(),
                        "decisionExpiry", decisionExpiry),
                Map.of(),
                200,
                request.revision() + 1);
    }

    private MutationResult executeMerge(
            AuthorizedTenantContext context, MutationCommand command) {
        var request = lockMergeRequest(context, requireTarget(command));
        requireRevision(request.revision(), command);
        if (!request.state().equals("approved") || !request.expiresAt().isAfter(command.now())) {
            throw conflict("The merge request is not approved and current.");
        }
        if (request.makerId().equals(context.actorId())) {
            throw conflict("The merge requester cannot execute their own request.");
        }
        var decisionId = uuid(command, "decisionId");
        var decision = jdbc.query(
                """
                SELECT id,checker_id,decision_expires_at,status
                FROM patient_merge_decisions
                WHERE organization_id=? AND id=? AND merge_request_id=?
                FOR SHARE
                """,
                resultSet -> resultSet.next()
                        ? new MergeDecision(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("checker_id", UUID.class),
                                resultSet.getTimestamp("decision_expires_at").toInstant(),
                                resultSet.getString("status"))
                        : null,
                context.organizationId(),
                decisionId,
                request.id());
        if (decision == null
                || !decision.state().equals("approved")
                || !decision.expiresAt().isAfter(command.now())
                || decision.checkerId().equals(context.actorId())) {
            throw conflict("An unexpired approval from a separate checker is required.");
        }
        var impact = mergeExecutionImpact(context, command);
        requireCurrentImpact(command, impact);
        if (!impact.digest().equals(request.impactDigest())) {
            throw stale("The approved merge impact is no longer current.");
        }
        var survivor = lockPatient(context, request.survivorId());
        var duplicate = lockPatient(context, request.duplicateId());
        requireMergeablePatients(survivor, duplicate);
        if (survivor.revision() != request.survivorRevision()
                || duplicate.revision() != request.duplicateRevision()) {
            throw stale("A patient changed after the merge request was submitted.");
        }
        var consumedDecision = jdbc.update(
                """
                UPDATE patient_merge_decisions
                SET status='consumed',consumed_by=?,consumed_at=?,updated_at=?,
                    updated_by=?,lock_version=lock_version+1
                WHERE organization_id=? AND id=? AND merge_request_id=?
                  AND status='approved' AND lock_version=0
                """,
                context.actorId(),
                Timestamp.from(command.now()),
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                decision.id(),
                request.id());
        requireChanged(consumedDecision, "The merge approval was already consumed or changed.");
        var changedDuplicate = jdbc.update(
                """
                UPDATE patient_profiles
                SET lifecycle_state='merged',merged_into_patient_id=?,updated_at=?,
                    updated_by=?,lock_version=lock_version+1
                WHERE organization_id=? AND id=? AND lock_version=?
                  AND lifecycle_state NOT IN ('merged','entered_in_error')
                """,
                survivor.id(),
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                duplicate.id(),
                duplicate.revision());
        requireChanged(changedDuplicate, "The duplicate patient changed before merge execution.");
        jdbc.update(
                """
                UPDATE patient_duplicate_candidates
                SET status='resolved',updated_at=?,updated_by=?,lock_version=lock_version+1
                WHERE organization_id=? AND id=? AND status='merge_requested'
                """,
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                request.candidateId());
        var changedRequest = jdbc.update(
                """
                UPDATE patient_merge_requests
                SET executed_at=?,status='executed',updated_at=?,updated_by=?,
                    lock_version=lock_version+1
                WHERE organization_id=? AND id=? AND lock_version=? AND status='approved'
                """,
                Timestamp.from(command.now()),
                Timestamp.from(command.now()),
                context.actorId(),
                context.organizationId(),
                request.id(),
                request.revision());
        requireChanged(changedRequest, "The merge request changed before execution.");
        var affectedCount = impact.items().stream().mapToInt(ImpactItem::affectedCount).sum();
        var invalidationDigest = digest(
                request.id() + "|" + survivor.id() + "|" + duplicate.id() + "|" + affectedCount);
        return result(
                request.id(),
                survivor.id(),
                "patient_merge_request",
                "patient.merge.executed",
                "m3.patient.merged.v1",
                "patient_merge_request",
                valuesObject(
                        "mergeRequestId", request.id(),
                        "decisionId", decision.id(),
                        "survivorPatientId", survivor.id(),
                        "duplicatePatientId", duplicate.id(),
                        "survivorRevision", survivor.revision(),
                        "duplicateRevision", duplicate.revision() + 1,
                        "affectedReferenceCount", affectedCount,
                        "invalidationDigest", invalidationDigest),
                valuesObject(
                        "mergeRequestId", request.id(),
                        "survivorPatientId", survivor.id(),
                        "duplicatePatientId", duplicate.id(),
                        "affectedReferenceCount", affectedCount,
                        "invalidationDigest", invalidationDigest),
                200,
                request.revision() + 1);
    }

    private ImpactAnalysis mergeRequestImpact(
            AuthorizedTenantContext context, MutationCommand command) {
        var candidate = lockDuplicate(context, requireTarget(command));
        requireRevision(candidate.revision(), command);
        var survivorId = uuid(command, "survivorPatientId");
        var duplicateId = uuid(command, "duplicatePatientId");
        if (!Set.of(candidate.patientAId(), candidate.patientBId()).contains(survivorId)
                || !Set.of(candidate.patientAId(), candidate.patientBId()).contains(duplicateId)
                || survivorId.equals(duplicateId)) {
            throw invalid("The survivor and duplicate must be the exact candidate pair.");
        }
        var survivor = lockPatient(context, survivorId);
        var duplicate = lockPatient(context, duplicateId);
        requireMergeablePatients(survivor, duplicate);
        return mergeImpact(context, survivor, duplicate);
    }

    private ImpactAnalysis mergeExecutionImpact(
            AuthorizedTenantContext context, MutationCommand command) {
        var request = lockMergeRequest(context, requireTarget(command));
        requireRevision(request.revision(), command);
        return mergeRequestImpact(context, request);
    }

    private ImpactAnalysis mergeRequestImpact(
            AuthorizedTenantContext context, MergeRequest request) {
        var survivor = lockPatient(context, request.survivorId());
        var duplicate = lockPatient(context, request.duplicateId());
        return mergeImpact(context, survivor, duplicate);
    }

    private ImpactAnalysis mergeImpact(
            AuthorizedTenantContext context,
            Patient survivor,
            Patient duplicate) {
        var contacts = count("patient_contacts", context, duplicate.id());
        var addresses = count("patient_addresses", context, duplicate.id());
        var preferences = count("communication_preferences", context, duplicate.id());
        var identifiers = count("patient_identifiers", context, duplicate.id());
        var relationships = count("caregiver_relationships", context, duplicate.id());
        var directives = count("patient_consents", context, duplicate.id())
                + count("privacy_restrictions", context, duplicate.id());
        var flags = count("patient_safety_flags", context, duplicate.id());
        var digest = digest(
                survivor.id()
                        + ":"
                        + survivor.revision()
                        + "|"
                        + duplicate.id()
                        + ":"
                        + duplicate.revision()
                        + "|"
                        + contacts
                        + "|"
                        + addresses
                        + "|"
                        + preferences
                        + "|"
                        + identifiers
                        + "|"
                        + relationships
                        + "|"
                        + directives
                        + "|"
                        + flags);
        return new ImpactAnalysis(
                digest,
                List.of(
                        new ImpactItem("contacts_preserved", "impact", "Contact lineage remains attached to the merged source record.", contacts),
                        new ImpactItem("addresses_preserved", "impact", "Address lineage remains attached to the merged source record.", addresses),
                        new ImpactItem("identifiers_reviewed", identifiers > 0 ? "warning" : "impact", "Identifier conflicts require survivor projection rules.", identifiers),
                        new ImpactItem("authority_and_directives_reviewed", relationships + directives > 0 ? "warning" : "impact", "Authority, consent and privacy facts are not silently combined.", relationships + directives),
                        new ImpactItem("safety_flags_preserved", flags > 0 ? "warning" : "impact", "Safety flag attribution and source lineage are preserved.", flags),
                        new ImpactItem("merge_lineage_created", "impact", "The duplicate resolves to one survivor; no automatic unmerge is asserted.", 1)));
    }

    private UUID createDraftPatient(
            AuthorizedTenantContext context, MutationCommand command, String disposition) {
        var id = UuidV7Generator.randomUuid();
        var urgent = disposition.equals("urgent_temporary");
        var given = optional(command, "officialGivenName");
        var family = optional(command, "officialFamilyName");
        var nameToUse = optional(command, "nameToUse");
        var temporaryReason = optional(command, "temporaryReasonCode");
        if (urgent && temporaryReason == null) {
            throw invalid("temporaryReasonCode is required for urgent temporary identity.");
        }
        if (!urgent && blank(given) && blank(family) && blank(nameToUse)) {
            throw invalid("A new patient requires a supplied name or the urgent temporary pathway.");
        }
        var patientNumber = "P" + id.toString().replace("-", "").substring(0, 15).toUpperCase(Locale.ROOT);
        var provenance = token(command, "provenanceCode", 2, 80);
        jdbc.update(
                """
                INSERT INTO patient_profiles
                    (id,organization_id,patient_number,lifecycle_state,official_given_name,
                     official_family_name,name_to_use,name_state,birth_date_certainty,
                     temporary_identity,temporary_reason_code,provenance_source,
                     created_by,updated_by)
                VALUES (?,?,?,'draft',?,?,?,?, 'unknown',?,?,?, ?,?)
                """,
                id,
                context.organizationId(),
                patientNumber,
                given,
                family,
                nameToUse,
                urgent ? "temporary" : "provided",
                urgent,
                temporaryReason,
                provenance,
                context.actorId(),
                context.actorId());
        appendIdentityRevision(
                context,
                id,
                null,
                0,
                given,
                family,
                nameToUse,
                urgent ? "temporary" : "provided",
                null,
                null,
                "unknown",
                null,
                null,
                null,
                provenance,
                command.now());
        return id;
    }

    private UUID latestIdentityRevisionId(
            AuthorizedTenantContext context, UUID patientId) {
        var revisionId = jdbc.query(
                """
                SELECT id
                FROM patient_identity_revisions
                WHERE organization_id=? AND patient_id=?
                ORDER BY profile_revision DESC,effective_from DESC,id DESC
                LIMIT 1
                """,
                resultSet -> resultSet.next()
                        ? resultSet.getObject("id", UUID.class)
                        : null,
                context.organizationId(),
                patientId);
        if (revisionId == null) {
            throw conflict("The patient identity history is incomplete.");
        }
        return revisionId;
    }

    private UUID appendIdentityRevision(
            AuthorizedTenantContext context,
            UUID patientId,
            UUID predecessorReference,
            long profileRevision,
            String given,
            String family,
            String nameToUse,
            String nameState,
            LocalDate birthDate,
            String birthPrecision,
            String birthCertainty,
            String administrativeSex,
            String genderIdentity,
            String pronouns,
            String provenance,
            Instant effectiveFrom) {
        var revisionId = UuidV7Generator.randomUuid();
        jdbc.update(
                """
                INSERT INTO patient_identity_revisions
                    (id,organization_id,patient_id,predecessor_revision_id,
                     profile_revision,official_given_name,official_family_name,
                     name_to_use,name_state,birth_date_value,birth_date_precision,
                     birth_date_certainty,administrative_sex_code,
                     gender_identity_code,pronouns_code,provenance_source,
                     effective_from,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                revisionId,
                context.organizationId(),
                patientId,
                predecessorReference,
                profileRevision,
                nullIfBlank(given),
                nullIfBlank(family),
                nullIfBlank(nameToUse),
                nameState,
                birthDate,
                birthPrecision,
                birthCertainty,
                nullIfBlank(administrativeSex),
                nullIfBlank(genderIdentity),
                nullIfBlank(pronouns),
                provenance,
                Timestamp.from(effectiveFrom),
                context.actorId());
        return revisionId;
    }

    private void touchPatient(
            AuthorizedTenantContext context, Patient patient, Instant now) {
        var changed = jdbc.update(
                """
                UPDATE patient_profiles
                SET updated_at=?,updated_by=?,lock_version=lock_version+1
                WHERE organization_id=? AND id=? AND lock_version=?
                """,
                Timestamp.from(now),
                context.actorId(),
                context.organizationId(),
                patient.id(),
                patient.revision());
        requireChanged(changed, "The patient changed before the child record was saved.");
    }

    private Registration lockRegistration(AuthorizedTenantContext context, UUID id) {
        var value = jdbc.query(
                """
                SELECT id,selected_patient_id,registration_source,purpose_key,creator_id,urgent,
                       duplicate_result_digest,duplicate_disposition,validation_digest,
                       validation_schema_version,validation_source_revision,
                       validated_patient_revision,validation_expires_at,
                       expires_at,status,lock_version
                FROM patient_registration_runs
                WHERE organization_id=? AND id=? FOR NO KEY UPDATE
                """,
                resultSet -> resultSet.next()
                        ? new Registration(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("selected_patient_id", UUID.class),
                                resultSet.getString("registration_source"),
                                resultSet.getString("purpose_key"),
                                resultSet.getObject("creator_id", UUID.class),
                                resultSet.getBoolean("urgent"),
                                resultSet.getString("duplicate_result_digest"),
                                resultSet.getString("duplicate_disposition"),
                                resultSet.getString("validation_digest"),
                                resultSet.getString("validation_schema_version"),
                                resultSet.getObject("validation_source_revision", Long.class),
                                resultSet.getObject("validated_patient_revision", Long.class),
                                instantValue(resultSet.getTimestamp("validation_expires_at")),
                                resultSet.getTimestamp("expires_at").toInstant(),
                                resultSet.getString("status"),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),
                id);
        if (value == null) {
            throw notFound("The patient registration was not found.");
        }
        return value;
    }

    private Patient lockPatient(AuthorizedTenantContext context, UUID id) {
        var value = jdbc.query(
                """
                SELECT id,lifecycle_state,official_given_name,official_family_name,
                       name_to_use,name_state,birth_date_value,birth_date_precision,
                       birth_date_certainty,administrative_sex_code,gender_identity_code,
                       pronouns_code,lock_version
                FROM patient_profiles
                WHERE organization_id=? AND id=? FOR NO KEY UPDATE
                """,
                resultSet -> resultSet.next()
                        ? new Patient(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("lifecycle_state"),
                                resultSet.getString("official_given_name"),
                                resultSet.getString("official_family_name"),
                                resultSet.getString("name_to_use"),
                                resultSet.getString("name_state"),
                                resultSet.getObject("birth_date_value", LocalDate.class),
                                resultSet.getString("birth_date_precision"),
                                resultSet.getString("birth_date_certainty"),
                                resultSet.getString("administrative_sex_code"),
                                resultSet.getString("gender_identity_code"),
                                resultSet.getString("pronouns_code"),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),
                id);
        if (value == null) {
            throw notFound("The patient was not found.");
        }
        return value;
    }

    private DuplicateCandidate lockDuplicate(AuthorizedTenantContext context, UUID id) {
        var value = jdbc.query(
                """
                SELECT id,patient_a_id,patient_b_id,detector_version,result_digest,
                       assigned_reviewer_id,lease_expires_at,status,lock_version
                FROM patient_duplicate_candidates
                WHERE organization_id=? AND id=? FOR NO KEY UPDATE
                """,
                resultSet -> resultSet.next()
                        ? new DuplicateCandidate(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("patient_a_id", UUID.class),
                                resultSet.getObject("patient_b_id", UUID.class),
                                resultSet.getString("detector_version"),
                                resultSet.getString("result_digest"),
                                resultSet.getObject("assigned_reviewer_id", UUID.class),
                                instantValue(resultSet.getTimestamp("lease_expires_at")),
                                resultSet.getString("status"),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),
                id);
        if (value == null) {
            throw notFound("The duplicate candidate was not found.");
        }
        return value;
    }

    private MergeRequest lockMergeRequest(AuthorizedTenantContext context, UUID id) {
        var value = jdbc.query(
                """
                SELECT id,duplicate_candidate_id,survivor_patient_id,duplicate_patient_id,
                       survivor_revision,duplicate_revision,impact_digest,requested_by,
                       expires_at,status,lock_version
                FROM patient_merge_requests
                WHERE organization_id=? AND id=? FOR NO KEY UPDATE
                """,
                resultSet -> resultSet.next()
                        ? new MergeRequest(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("duplicate_candidate_id", UUID.class),
                                resultSet.getObject("survivor_patient_id", UUID.class),
                                resultSet.getObject("duplicate_patient_id", UUID.class),
                                resultSet.getLong("survivor_revision"),
                                resultSet.getLong("duplicate_revision"),
                                resultSet.getString("impact_digest"),
                                resultSet.getObject("requested_by", UUID.class),
                                resultSet.getTimestamp("expires_at").toInstant(),
                                resultSet.getString("status"),
                                resultSet.getLong("lock_version"))
                        : null,
                context.organizationId(),
                id);
        if (value == null) {
            throw notFound("The merge request was not found.");
        }
        return value;
    }

    private void requireUsablePatient(AuthorizedTenantContext context, UUID patientId) {
        var exists = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS(SELECT 1 FROM patient_profiles
                  WHERE organization_id=? AND id=?
                    AND lifecycle_state NOT IN ('merged','entered_in_error'))
                """,
                Boolean.class,
                context.organizationId(),
                patientId));
        if (!exists) {
            throw notFound("The selected patient was not found.");
        }
    }

    private static void requireRegistrationMutable(Registration run, Instant now) {
        if (!run.expiresAt().isAfter(now)) {
            throw conflict("The patient registration has expired.");
        }
        if (Set.of("completed", "abandoned", "rejected", "expired").contains(run.state())) {
            throw conflict("The patient registration is terminal.");
        }
    }

    private void requireRegistrationAccess(
            AuthorizedTenantContext context, Registration run) {
        if (!run.creatorId().equals(context.actorId())
                && !hasPermission(context, "patient.registration.manage")) {
            throw notFound("The patient registration was not found.");
        }
    }

    private boolean hasPermission(
            AuthorizedTenantContext context, String permission) {
        return permissions(context).contains(permission);
    }

    private static void requireMutablePatient(Patient patient) {
        if (Set.of("merged", "entered_in_error").contains(patient.state())) {
            throw conflict("The patient aggregate is not mutable in its current lifecycle.");
        }
    }

    private static void requireMergeablePatients(Patient survivor, Patient duplicate) {
        if (survivor.id().equals(duplicate.id())
                || Set.of("merged", "entered_in_error").contains(survivor.state())
                || Set.of("merged", "entered_in_error").contains(duplicate.state())) {
            throw conflict("The patient pair is not eligible for merge.");
        }
    }

    private static void requireActiveLease(
            DuplicateCandidate candidate, AuthorizedTenantContext context, Instant now) {
        if (!candidate.state().equals("under_review")
                || !context.actorId().equals(candidate.reviewerId())
                || candidate.leaseExpiresAt() == null
                || !candidate.leaseExpiresAt().isAfter(now)) {
            throw conflict("A current review lease held by this actor is required.");
        }
    }

    private void requireCurrentImpact(MutationCommand command, ImpactAnalysis impact) {
        if (impact.blocked()) {
            throw conflict("The reviewed patient merge impact contains unresolved blockers.");
        }
        var suppliedDigest = command.fields().get("_impactDigest");
        var suppliedExpiry = optionalInstant(command, "_impactExpiresAt");
        if (!impact.digest().equals(suppliedDigest)
                || suppliedExpiry == null
                || !suppliedExpiry.isAfter(command.now())) {
            throw stale("A fresh patient merge impact preview is required.");
        }
    }

    private int count(String table, AuthorizedTenantContext context, UUID patientId) {
        var permitted = Set.of(
                "patient_contacts",
                "patient_addresses",
                "communication_preferences",
                "patient_identifiers",
                "caregiver_relationships",
                "patient_consents",
                "privacy_restrictions",
                "patient_safety_flags");
        if (!permitted.contains(table)) {
            throw new IllegalArgumentException("Unsupported patient reference family.");
        }
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE organization_id=? AND patient_id=?",
                Integer.class,
                context.organizationId(),
                patientId));
    }

    private List<PatientRegistryScreen.Metric> metrics(
            AuthorizedTenantContext context, String screenId) {
        if (screenId.equals("P3-01")) {
            return List.of(
                    metric("active", "Active patients", countWhere(
                            "patient_profiles", context, "lifecycle_state='active'"), "neutral"),
                    metric("registrations", "Open registrations", countWhere(
                            "patient_registration_runs", context,
                            "status IN ('collecting','duplicate_review','ready','submitted') "
                                    + "AND expires_at>clock_timestamp()"), "neutral"),
                    metric("duplicates", "Duplicate review", countWhere(
                            "patient_duplicate_candidates", context,
                            "status IN ('open','under_review','merge_requested')"), "warning"),
                    metric("temporary", "Temporary identities", countWhere(
                            "patient_profiles", context,
                            "temporary_identity AND lifecycle_state NOT IN ('merged','entered_in_error')"), "warning"));
        }
        return List.of();
    }

    private long countWhere(
            String table, AuthorizedTenantContext context, String predicate) {
        var allowed = Set.of(
                "patient_profiles", "patient_registration_runs", "patient_duplicate_candidates");
        if (!allowed.contains(table)) {
            throw new IllegalArgumentException("Unsupported metric source.");
        }
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE organization_id=? AND " + predicate,
                Long.class,
                context.organizationId()));
    }

    private static List<PatientRegistryScreen.Column> columns(String screenId) {
        return List.of(
                new PatientRegistryScreen.Column("primary", "Record"),
                new PatientRegistryScreen.Column("secondary", "Type or identifier"),
                new PatientRegistryScreen.Column("context", "Context"),
                new PatientRegistryScreen.Column("status", "Status"));
    }

    private static List<PatientRegistryScreen.Notice> notices(String screenId) {
        var notices = new ArrayList<PatientRegistryScreen.Notice>();
        if (screenId.equals("P3-01")) {
            notices.add(new PatientRegistryScreen.Notice(
                    "warning",
                    "Automated registry jobs unavailable",
                    "Expired work is denied and projected safely, but patient-registry worker identities and dispatch remain inactive."));
        }
        if (screenId.equals("P3-03") || screenId.equals("P3-04")) {
            notices.add(new PatientRegistryScreen.Notice(
                    "warning",
                    "Urgent temporary pathway unavailable",
                    "Temporary identity creation remains fail-closed until an immediate reconciliation worker is active."));
        }
        if (screenId.equals("P3-04") || screenId.equals("P3-14") || screenId.equals("P3-15")) {
            notices.add(new PatientRegistryScreen.Notice(
                    "info",
                    "No automatic merge",
                    "Matching is organization-local and explainable. A candidate never authorizes a merge."));
        }
        if (screenId.equals("P3-08")) {
            notices.add(new PatientRegistryScreen.Notice(
                    "warning",
                    "Identifier scheme catalogue unavailable",
                    "External identifier activation remains fail-closed until a local scheme/version is approved."));
        }
        if (screenId.equals("P3-09")) {
            notices.add(new PatientRegistryScreen.Notice(
                    "warning",
                    "Authority policy unavailable",
                    "Relationship facts can be recorded, but relationship alone grants no proxy authority."));
        }
        if (screenId.equals("P3-10")) {
            notices.add(new PatientRegistryScreen.Notice(
                    "warning",
                    "Consent and privacy policy unavailable",
                    "Directive activation and restriction decisions remain fail-closed pending an approved local catalogue."));
        }
        if (screenId.equals("P3-11")) {
            notices.add(new PatientRegistryScreen.Notice(
                    "warning",
                    "Clinical safety catalogue unavailable",
                    "Flag proposal and verification remain fail-closed pending approved categories, severities and eligible roles."));
        }
        if (screenId.equals("P3-13")) {
            notices.add(new PatientRegistryScreen.Notice(
                    "warning",
                    "Patient export unavailable",
                    "Export remains fail-closed until an exact projection, row and byte bounds, approval policy, retention schedule and private artifact provider are active."));
            notices.add(new PatientRegistryScreen.Notice(
                    "warning",
                    "Deceased verification unavailable",
                    "A deceased lifecycle fact cannot be activated until the local verification and post-death access policy is approved."));
            notices.add(new PatientRegistryScreen.Notice(
                    "warning",
                    "FHIR exchange unavailable",
                    "Interoperability remains fail-closed until an exact partner, base release, implementation guide and terminology package are active."));
        }
        if (screenId.equals("P3-16")) {
            notices.add(new PatientRegistryScreen.Notice(
                    "info",
                    "Minimum-necessary timeline",
                    "This view exposes allow-listed summaries only. Restricted detail, arbitrary audit payloads and timeline export remain unavailable."));
        }
        return List.copyOf(notices);
    }

    private PatientRegistryScreen.Row withAllowedActions(
            String screenId, PatientRegistryScreen.Row row) {
        var kind = row.values().getOrDefault("$kind", "");
        var actions = new ArrayList<String>();
        switch (screenId) {
            case "P3-04" -> {
                if (kind.equals("registration")
                        && Set.of("collecting", "duplicate_review").contains(row.status())) {
                    actions.addAll(List.of("search-duplicates", "record-registration-disposition"));
                }
            }
            case "P3-05" -> {
                if (kind.equals("patient")) actions.add("correct-identity");
            }
            case "P3-06" -> {
                if (kind.equals("patient")) actions.addAll(List.of("add-contact", "add-address"));
            }
            case "P3-07" -> {
                if (kind.equals("patient")) actions.add("set-communication-preference");
            }
            case "P3-09" -> {
                if (kind.equals("patient")) actions.add("add-caregiver-relationship");
            }
            case "P3-12" -> {
                if (kind.equals("registration")) {
                    if (Set.of("collecting", "duplicate_review", "ready")
                            .contains(row.status())) {
                        actions.add("validate-registration");
                    }
                    if (row.status().equals("ready")) actions.add("submit-registration");
                }
            }
            case "P3-13" -> {
                if (kind.equals("patient")
                        && Set.of("active", "inactive").contains(row.status())) {
                    actions.add("change-patient-lifecycle");
                }
            }
            case "P3-14" -> {
                if (kind.equals("duplicate-candidate")) {
                    if (Set.of("open", "under_review").contains(row.status())) {
                        actions.add("claim-duplicate");
                    }
                    if (row.status().equals("under_review")) {
                        actions.add("disposition-duplicate");
                    }
                }
            }
            case "P3-15" -> {
                if (kind.equals("duplicate-candidate") && row.status().equals("under_review")) {
                    actions.add("request-patient-merge");
                }
                if (kind.equals("merge-request") && row.status().equals("submitted")) {
                    actions.add("decide-patient-merge");
                }
                if (kind.equals("merge-request")
                        && row.status().equals("approved")
                        && !row.values().getOrDefault("decisionId", "").isBlank()) {
                    actions.add("execute-patient-merge");
                }
            }
            default -> {
                // Link-only or read-only screens do not expose target mutations.
            }
        }
        var publicValues = new LinkedHashMap<>(row.values());
        publicValues.remove("$kind");
        return new PatientRegistryScreen.Row(
                row.id(),
                row.patientId(),
                row.status(),
                row.revision(),
                row.etag(),
                publicValues,
                actions);
    }

    private static ScreenQuery parentQuery(ScreenQuery query) {
        return new ScreenQuery(
                query.screenId(), query.patientId(), null, null, null, Math.min(query.limit(), 1));
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
            throw notFound("The patient resource is unavailable or is not assigned to this account.");
        }
    }

    private static PatientRegistryScreen.Row row(
            String screenId,
            UUID id,
            UUID patientId,
            String status,
            long revision,
            Map<String, String> values) {
        return new PatientRegistryScreen.Row(
                id,
                patientId,
                status,
                revision,
                "\"m3:" + screenId + ":" + id + ":" + revision + "\"",
                values,
                List.of());
    }

    private static PatientRegistryScreen.Metric metric(
            String key, String label, long value, String tone) {
        return new PatientRegistryScreen.Metric(key, label, value, tone);
    }

    private static Map<String, String> values(String... entries) {
        var values = new LinkedHashMap<String, String>();
        for (var index = 0; index < entries.length; index += 2) {
            values.put(entries[index], entries[index + 1]);
        }
        return values;
    }

    private static LinkedHashMap<String, Object> valuesObject(Object... entries) {
        var values = new LinkedHashMap<String, Object>();
        for (var index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return values;
    }

    private static MutationResult result(
            UUID subjectId,
            UUID patientId,
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
                patientId,
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
            UUID patientId,
            String subjectType,
            String auditEvent,
            String outboxEvent,
            String aggregateType,
            Map<String, Object> auditPayload,
            Map<String, Object> outboxPayload,
            int statusCode,
            long revision,
            List<PatientRegistryStore.AdditionalAudit> additionalAudits) {
        return new MutationResult(
                subjectId,
                patientId,
                subjectType,
                auditEvent,
                outboxEvent,
                aggregateType,
                auditPayload,
                outboxPayload,
                statusCode,
                revision,
                additionalAudits);
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

    private static String token(
            MutationCommand command, String key, int minimum, int maximum) {
        var value = required(command, key);
        if (value.length() < minimum
                || value.length() > maximum
                || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) {
            throw invalid(key + " is invalid.");
        }
        return value;
    }

    private static String optionalToken(
            MutationCommand command, String key, int minimum, int maximum) {
        var value = optional(command, key);
        if (value == null) {
            return null;
        }
        if (value.length() < minimum
                || value.length() > maximum
                || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) {
            throw invalid(key + " is invalid.");
        }
        return value;
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

    private static UUID requireTarget(MutationCommand command) {
        if (command.targetId() == null) {
            throw invalid("targetId is required for this action.");
        }
        return command.targetId();
    }

    private static void requireRevision(long current, MutationCommand command) {
        if (command.expectedRevision() == null) {
            throw new PatientRegistryException(
                    PatientRegistryException.Reason.PRECONDITION_REQUIRED,
                    "A strong If-Match revision is required.");
        }
        if (command.expectedRevision() != current) {
            throw stale("The patient resource changed; reload and retry.");
        }
    }

    private static void requireChanged(int changed, String message) {
        if (changed != 1) {
            throw stale(message);
        }
    }

    private static String suppliedOrCurrent(
            MutationCommand command, String key, String current) {
        return command.fields().containsKey(key) ? command.fields().get(key) : current;
    }

    private static List<String> changedIdentityFields(
            Patient patient,
            String given,
            String family,
            String nameToUse,
            String nameState,
            LocalDate birthDate,
            String birthPrecision,
            String birthCertainty,
            String administrativeSex,
            String genderIdentity,
            String pronouns) {
        var changed = new ArrayList<String>();
        if (!Objects.equals(patient.givenName(), nullIfBlank(given))) changed.add("officialGivenName");
        if (!Objects.equals(patient.familyName(), nullIfBlank(family))) changed.add("officialFamilyName");
        if (!Objects.equals(patient.nameToUse(), nullIfBlank(nameToUse))) changed.add("nameToUse");
        if (!Objects.equals(patient.nameState(), nameState)) changed.add("nameState");
        if (!Objects.equals(patient.birthDate(), birthDate)
                || !Objects.equals(patient.birthPrecision(), birthPrecision)
                || !Objects.equals(patient.birthCertainty(), birthCertainty)) {
            changed.add("birthDate");
        }
        if (!Objects.equals(patient.administrativeSex(), nullIfBlank(administrativeSex))) {
            changed.add("administrativeSexCode");
        }
        if (!Objects.equals(patient.genderIdentity(), nullIfBlank(genderIdentity))) {
            changed.add("genderIdentityCode");
        }
        if (!Objects.equals(patient.pronouns(), nullIfBlank(pronouns))) changed.add("pronounsCode");
        return List.copyOf(changed);
    }

    private static String normalizeEmail(String value) {
        var normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
                .strip()
                .toLowerCase(Locale.ROOT);
        if (!normalized.matches("[^@\\s]{1,64}@[^@\\s]{1,253}")) {
            throw invalid("The email value is invalid.");
        }
        return normalized;
    }

    private static String normalizePhone(String value) {
        var normalized = value.strip().replaceAll("[\\s().-]", "");
        if (!normalized.matches("\\+?[0-9]{7,15}")) {
            throw invalid("The phone value is invalid.");
        }
        return normalized;
    }

    private static String normalizeAddress(
            String line1,
            String line2,
            String locality,
            String region,
            String postalCode,
            String country) {
        return List.of(line1, safe(line2, ""), locality, safe(region, ""), safe(postalCode, ""), country)
                .stream()
                .map(value -> Normalizer.normalize(value, Normalizer.Form.NFC).strip())
                .collect(java.util.stream.Collectors.joining("\u001f"));
    }

    private static String maskContact(String channel, String value) {
        if (channel.equals("email")) {
            var at = value.indexOf('@');
            return value.substring(0, 1) + "•••" + value.substring(at);
        }
        var suffix = value.length() <= 4 ? value : value.substring(value.length() - 4);
        return "••••" + suffix;
    }

    private static String displayName(
            String given, String family, String nameToUse, String nameState) {
        if (!blank(nameToUse)) return nameToUse;
        var combined = (safe(given, "") + " " + safe(family, "")).strip();
        if (!combined.isBlank()) return combined;
        return switch (nameState) {
            case "temporary" -> "Temporary patient identity";
            case "unnamed" -> "Unnamed patient";
            default -> "Patient identity unavailable";
        };
    }

    private static String birthDisplay(LocalDate date, String precision, String certainty) {
        if (date == null || precision == null || certainty == null || certainty.equals("unknown")) {
            return "Birth date unknown";
        }
        var value = switch (precision) {
            case "year" -> Integer.toString(date.getYear());
            case "month" -> "%04d-%02d".formatted(date.getYear(), date.getMonthValue());
            default -> date.toString();
        };
        return value + (certainty.equals("estimated") ? " · estimated" : "");
    }

    private static String maskPatientNumber(String value) {
        if (value == null || value.length() <= 4) return "••••";
        return "••••" + value.substring(value.length() - 4);
    }

    private static String timelineTitle(String eventName) {
        return java.util.Arrays.stream(eventName.split("\\."))
                .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireLiteralSearch(
            String value, int minimumLength, String label) {
        var codePoints = value.codePointCount(0, value.length());
        var meaningful = value.codePoints().filter(Character::isLetterOrDigit).count();
        if (codePoints < minimumLength
                || meaningful < 2
                || value.indexOf('%') >= 0
                || value.indexOf('_') >= 0
                || value.indexOf('\\') >= 0) {
            throw invalid(label + " requires bounded literal characters without wildcards.");
        }
    }

    private static String nullIfBlank(String value) {
        return blank(value) ? null : value.strip();
    }

    private static String instantString(Timestamp value) {
        return value == null ? "Not scheduled" : value.toInstant().toString();
    }

    private static Instant instantValue(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate patient evidence digest.", exception);
        }
    }

    private static PatientRegistryException invalid(String message) {
        return new PatientRegistryException(PatientRegistryException.Reason.INVALID, message);
    }

    private static PatientRegistryException notFound(String message) {
        return new PatientRegistryException(PatientRegistryException.Reason.NOT_FOUND, message);
    }

    private static PatientRegistryException conflict(String message) {
        return new PatientRegistryException(PatientRegistryException.Reason.CONFLICT, message);
    }

    private static PatientRegistryException stale(String message) {
        return new PatientRegistryException(PatientRegistryException.Reason.STALE, message);
    }

    private record Candidate(UUID id, long revision) {}

    private record Registration(
            UUID id,
            UUID patientId,
            String source,
            String purpose,
            UUID creatorId,
            boolean urgent,
            String searchDigest,
            String disposition,
            String validationDigest,
            String validationSchemaVersion,
            Long validationSourceRevision,
            Long validatedPatientRevision,
            Instant validationExpiresAt,
            Instant expiresAt,
            String state,
            long revision) {}

    private record Patient(
            UUID id,
            String state,
            String givenName,
            String familyName,
            String nameToUse,
            String nameState,
            LocalDate birthDate,
            String birthPrecision,
            String birthCertainty,
            String administrativeSex,
            String genderIdentity,
            String pronouns,
            long revision) {}

    private record DuplicateCandidate(
            UUID id,
            UUID patientAId,
            UUID patientBId,
            String detectorVersion,
            String resultDigest,
            UUID reviewerId,
            Instant leaseExpiresAt,
            String state,
            long revision) {}

    private record MergeRequest(
            UUID id,
            UUID candidateId,
            UUID survivorId,
            UUID duplicateId,
            long survivorRevision,
            long duplicateRevision,
            String impactDigest,
            UUID makerId,
            Instant expiresAt,
            String state,
            long revision) {}

    private record MergeDecision(UUID id, UUID checkerId, Instant expiresAt, String state) {}
}
