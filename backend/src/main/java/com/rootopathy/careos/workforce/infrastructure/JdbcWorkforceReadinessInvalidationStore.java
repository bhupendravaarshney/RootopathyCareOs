package com.rootopathy.careos.workforce.infrastructure;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.workforce.application.WorkforceReadinessInvalidationStore;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcWorkforceReadinessInvalidationStore
        implements WorkforceReadinessInvalidationStore {
    private final JdbcTemplate jdbc;

    public JdbcWorkforceReadinessInvalidationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int reconcile(
            AuthorizedTenantContext context,
            String aggregateType,
            UUID aggregateId,
            String invalidationCode) {
        var memberIds = affectedMemberIds(context, aggregateType, aggregateId);
        for (var memberId : memberIds) {
            jdbc.queryForObject(
                    "select careos_invalidate_member_readiness(?,?,?)",
                    Object.class,
                    context.organizationId(),
                    memberId,
                    invalidationCode);
        }
        return memberIds.size();
    }

    private List<UUID> affectedMemberIds(
            AuthorizedTenantContext context, String aggregateType, UUID aggregateId) {
        var organizationId = context.organizationId();
        return switch (aggregateType) {
            case "workforce_member" -> members(
                    "SELECT id FROM workforce_members WHERE organization_id=? AND id=?",
                    organizationId, aggregateId);
            case "workforce_identifier" -> members(
                    "SELECT workforce_member_id FROM workforce_identifiers WHERE organization_id=? AND id=?",
                    organizationId, aggregateId);
            case "employment_engagement" -> members(
                    "SELECT workforce_member_id FROM employment_engagements WHERE organization_id=? AND id=?",
                    organizationId, aggregateId);
            case "qualification" -> members(
                    "SELECT workforce_member_id FROM qualifications WHERE organization_id=? AND id=?",
                    organizationId, aggregateId);
            case "practitioner_profile" -> members(
                    "SELECT workforce_member_id FROM practitioner_profiles WHERE organization_id=? AND id=?",
                    organizationId, aggregateId);
            case "professional_registration" -> members(
                    """
                    SELECT practitioner.workforce_member_id
                    FROM professional_registrations registration
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=registration.organization_id
                     AND practitioner.id=registration.practitioner_profile_id
                    WHERE registration.organization_id=? AND registration.id=?
                    """,
                    organizationId, aggregateId);
            case "practitioner_credential" -> members(
                    "SELECT workforce_member_id FROM practitioner_credentials WHERE organization_id=? AND id=?",
                    organizationId, aggregateId);
            case "credential_document" -> members(
                    """
                    SELECT credential.workforce_member_id
                    FROM credential_documents document
                    JOIN practitioner_credentials credential
                      ON credential.organization_id=document.organization_id
                     AND credential.id=document.practitioner_credential_id
                    WHERE document.organization_id=? AND document.id=?
                    """,
                    organizationId, aggregateId);
            case "credential_legal_hold" -> members(
                    """
                    SELECT DISTINCT credential.workforce_member_id
                    FROM credential_legal_holds hold
                    LEFT JOIN credential_documents document
                      ON document.organization_id=hold.organization_id
                     AND document.id=hold.credential_document_id
                    JOIN practitioner_credentials credential
                      ON credential.organization_id=hold.organization_id
                     AND credential.id=coalesce(
                         hold.practitioner_credential_id,
                         document.practitioner_credential_id)
                    WHERE hold.organization_id=? AND hold.id=?
                    """,
                    organizationId, aggregateId);
            case "practitioner_specialty" -> members(
                    """
                    SELECT practitioner.workforce_member_id
                    FROM practitioner_specialties specialty
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=specialty.organization_id
                     AND practitioner.id=specialty.practitioner_profile_id
                    WHERE specialty.organization_id=? AND specialty.id=?
                    """,
                    organizationId, aggregateId);
            case "scope_of_practice" -> members(
                    """
                    SELECT practitioner.workforce_member_id
                    FROM scopes_of_practice scope
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=scope.organization_id
                     AND practitioner.id=scope.practitioner_profile_id
                    WHERE scope.organization_id=? AND scope.id=?
                    """,
                    organizationId, aggregateId);
            case "workforce_assignment" -> members(
                    "SELECT workforce_member_id FROM workforce_assignments WHERE organization_id=? AND id=?",
                    organizationId, aggregateId);
            case "practitioner_service_assignment" -> members(
                    """
                    SELECT practitioner.workforce_member_id
                    FROM practitioner_service_assignments assignment
                    JOIN practitioner_profiles practitioner
                      ON practitioner.organization_id=assignment.organization_id
                     AND practitioner.id=assignment.practitioner_profile_id
                    WHERE assignment.organization_id=? AND assignment.id=?
                    """,
                    organizationId, aggregateId);
            case "availability_profile" -> members(
                    "SELECT workforce_member_id FROM availability_profiles WHERE organization_id=? AND id=?",
                    organizationId, aggregateId);
            case "person_merge_request" -> members(
                    """
                    SELECT DISTINCT member.id
                    FROM person_merge_requests merge_request
                    JOIN organization_person_links link
                      ON link.organization_id=merge_request.organization_id
                     AND link.id IN (
                         merge_request.retained_link_id,merge_request.discarded_link_id)
                    JOIN workforce_members member
                      ON member.organization_id=link.organization_id
                     AND member.organization_person_link_id=link.id
                    WHERE merge_request.organization_id=? AND merge_request.id=?
                    """,
                    organizationId, aggregateId);
            case "workforce_offboarding_request" -> members(
                    "SELECT workforce_member_id FROM workforce_offboarding_requests WHERE organization_id=? AND id=?",
                    organizationId, aggregateId);
            case "organization", "membership", "facility", "organization_unit",
                    "service_location", "operating_hours_batch", "service_definition",
                    "service_assignment", "workforce_registry_version",
                    "workforce_configuration_snapshot" -> members(
                    "SELECT id FROM workforce_members WHERE organization_id=? ORDER BY id",
                    organizationId);
            default -> throw new IllegalArgumentException(
                    "unsupported readiness-invalidation aggregate type");
        };
    }

    private List<UUID> members(String sql, Object... parameters) {
        return jdbc.queryForList(sql, UUID.class, parameters);
    }
}
