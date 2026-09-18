package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.OrganizationAdministrationException;
import com.rootopathy.careos.administration.application.OrganizationAdministrationStore;
import com.rootopathy.careos.administration.application.OrganizationAdministrationStore.MembershipPageSlice;
import com.rootopathy.careos.administration.application.OrganizationAdministrationStore.MembershipQuery;
import com.rootopathy.careos.administration.domain.AdministrationReadiness;
import com.rootopathy.careos.administration.domain.OrganizationMembership;
import com.rootopathy.careos.administration.domain.OrganizationProfile;
import com.rootopathy.careos.administration.domain.ReadinessGate;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.infrastructure.AuthorizedTenantTransactionGuard;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrganizationAdministrationStore implements OrganizationAdministrationStore {
    private final JdbcTemplate jdbcTemplate;

    public JdbcOrganizationAdministrationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public OrganizationProfile profile(AuthorizedTenantContext context) {
        var editable = effectivePermissions(context).contains("organization.profile.manage");
        return jdbcTemplate.query(
                        """
                        SELECT id, legal_name, display_name, trading_name, organization_type,
                               country_code, timezone, locale, status, lock_version, updated_at
                        FROM organizations
                        WHERE id = ?
                        """,
                        (resultSet, rowNumber) -> mapProfile(resultSet, rowNumber, editable),
                        context.organizationId())
                .stream()
                .findFirst()
                .orElseThrow(JdbcOrganizationAdministrationStore::notFound);
    }

    @Override
    public AdministrationReadiness readiness(AuthorizedTenantContext context) {
        var profile = profile(context);
        var facilityCounts = jdbcTemplate.queryForMap(
                """
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'draft') AS drafts
                FROM facilities
                WHERE organization_id = ?
                """,
                context.organizationId());
        var activeMemberships = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM organization_memberships
                WHERE organization_id = ?
                  AND status = 'active'
                  AND effective_from <= clock_timestamp()
                  AND (effective_to IS NULL OR effective_to > clock_timestamp())
                """,
                Integer.class,
                context.organizationId());
        var effectiveOwners = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM organization_memberships memberships
                JOIN authorization_roles roles ON roles.role_key = memberships.role_key
                WHERE memberships.organization_id = ?
                  AND memberships.status = 'active'
                  AND memberships.effective_from <= clock_timestamp()
                  AND (memberships.effective_to IS NULL
                       OR memberships.effective_to > clock_timestamp())
                  AND roles.final_owner
                  AND roles.status = 'active'
                  AND roles.registry_version = 'm1-candidate-1'
                  AND memberships.effective_to IS NULL
                """,
                Integer.class,
                context.organizationId());
        var pendingOwnerDemotions = jdbcTemplate.queryForObject(
                """
                SELECT count(DISTINCT transfers.membership_id)
                FROM owner_transfer_requests transfers
                JOIN authorization_approval_requests approvals
                  ON approvals.organization_id = transfers.organization_id
                 AND approvals.id = transfers.id
                JOIN organization_memberships memberships
                  ON memberships.organization_id = transfers.organization_id
                 AND memberships.id = transfers.membership_id
                JOIN authorization_roles roles ON roles.role_key = memberships.role_key
                WHERE transfers.organization_id = ?
                  AND transfers.change_type = 'owner_demotion'
                  AND approvals.status IN ('pending', 'approved')
                  AND approvals.expires_at > clock_timestamp()
                  AND memberships.status = 'active'
                  AND memberships.effective_from <= clock_timestamp()
                  AND memberships.effective_to IS NULL
                  AND roles.final_owner
                  AND roles.status = 'active'
                  AND roles.registry_version = 'm1-candidate-1'
                """,
                Integer.class,
                context.organizationId());
        var missingMandatoryMfa = jdbcTemplate.queryForObject(
                """
                SELECT count(DISTINCT memberships.user_id)
                FROM organization_memberships memberships
                JOIN authorization_roles roles ON roles.role_key = memberships.role_key
                WHERE memberships.organization_id = ?
                  AND memberships.status = 'active'
                  AND memberships.effective_from <= clock_timestamp()
                  AND (memberships.effective_to IS NULL
                       OR memberships.effective_to > clock_timestamp())
                  AND roles.mfa_required
                  AND roles.status = 'active'
                  AND roles.registry_version = 'm1-candidate-1'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM mfa_methods methods
                      WHERE methods.user_id = memberships.user_id
                        AND methods.status = 'enabled'
                  )
                """,
                Integer.class,
                context.organizationId());
        var expiredMfaResetApprovals = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM authorization_approval_requests approvals
                WHERE approvals.organization_id = ?
                  AND approvals.operation_key = 'identity.mfa.admin-reset.execute'
                  AND approvals.status = 'approved'
                  AND approvals.expires_at <= clock_timestamp()
                """,
                Integer.class,
                context.organizationId());
        var facilityCount = ((Number) facilityCounts.get("total")).intValue();
        var draftFacilityCount = ((Number) facilityCounts.get("drafts")).intValue();
        var effectivePermissions = effectivePermissions(context);
        var gates = gates(
                profile,
                facilityCount,
                draftFacilityCount,
                effectiveOwners == null ? 0 : effectiveOwners,
                pendingOwnerDemotions == null ? 0 : pendingOwnerDemotions,
                missingMandatoryMfa == null ? 0 : missingMandatoryMfa,
                expiredMfaResetApprovals == null ? 0 : expiredMfaResetApprovals,
                effectivePermissions);
        var evaluatedTimestamp = jdbcTemplate.queryForObject(
                "SELECT clock_timestamp()", Timestamp.class);
        if (evaluatedTimestamp == null) {
            throw new IllegalStateException("Database time was unavailable for readiness evaluation");
        }
        var evaluatedAt = evaluatedTimestamp.toInstant();
        return new AdministrationReadiness(
                profile.organizationId(),
                profile.lifecycleStatus(),
                ReadinessGate.CATALOGUE_VERSION,
                profile.lockVersion(),
                evaluatedAt,
                evaluatedAt.plusSeconds(15 * 60L),
                count(gates, "complete"),
                count(gates, "blocked"),
                count(gates, "warning"),
                count(gates, "not_applicable"),
                gates.size(),
                activeMemberships == null ? 0 : activeMemberships,
                facilityCount,
                draftFacilityCount,
                gates);
    }

    @Override
    public MembershipPageSlice memberships(
            AuthorizedTenantContext context, MembershipQuery query) {
        AuthorizedTenantTransactionGuard.requireBound(jdbcTemplate, context);
        validateRoleFilter(query.roleKey());
        var effectivePermissions = effectivePermissions(context);
        var canIssueInvitation = effectivePermissions.contains("access.invitation.issue");
        var canRequestMfaReset = effectivePermissions.contains("access.mfa_reset.request");
        var canManageMembership = effectivePermissions.contains("access.membership.manage");
        var canApproveMembership = effectivePermissions.contains("access.membership.approve");
        var canRequestOwnerTransfer =
                effectivePermissions.contains("access.owner_transfer.request");
        var canApproveOwnerTransfer =
                effectivePermissions.contains("access.owner_transfer.approve");
        var canExecuteOwnerTransfer =
                effectivePermissions.contains("access.owner_transfer.execute");

        var statement = new StringBuilder("""
                WITH query_context AS (
                    SELECT CAST(? AS timestamptz) AS as_of
                ), projected AS (
                    SELECT memberships.id AS membership_id,
                           memberships.user_id,
                           users.display_name,
                           users.email,
                           users.status AS account_status,
                           memberships.role_key,
                           roles.display_name AS role_display_name,
                           roles.status AS role_status,
                           roles.final_owner,
                           CASE
                               WHEN memberships.status = 'revoked' THEN 'revoked'
                               WHEN memberships.status = 'suspended' THEN 'suspended'
                               WHEN memberships.status = 'expired'
                                    OR memberships.effective_to <= query_context.as_of
                                   THEN 'expired'
                               WHEN memberships.effective_from > query_context.as_of
                                   THEN 'scheduled'
                               ELSE 'active'
                           END AS access_state,
                           memberships.effective_from,
                           memberships.effective_to,
                           memberships.lock_version,
                           EXISTS (
                               SELECT 1
                               FROM mfa_methods
                               WHERE mfa_methods.user_id = memberships.user_id
                                 AND mfa_methods.status = 'enabled'
                           ) AS mfa_enabled
                    FROM organization_memberships memberships
                    CROSS JOIN query_context
                    JOIN users ON users.id = memberships.user_id
                    JOIN authorization_roles roles ON roles.role_key = memberships.role_key
                    WHERE memberships.organization_id = ?
                )
                SELECT membership_id, user_id, display_name, email, account_status,
                       role_key, role_display_name, role_status, final_owner, access_state,
                       effective_from, effective_to, lock_version, mfa_enabled
                FROM projected
                WHERE 1 = 1
                """);
        var parameters = new ArrayList<Object>();
        parameters.add(Timestamp.from(query.asOf()));
        parameters.add(context.organizationId());
        if (query.search() != null) {
            statement.append("""
                     AND (position(lower(?) in lower(display_name)) > 0
                          OR position(lower(?) in lower(email)) > 0)
                    """);
            parameters.add(query.search());
            parameters.add(query.search());
        }
        if (query.accessState() != null) {
            statement.append(" AND access_state = ?\n");
            parameters.add(query.accessState());
        }
        if (query.roleKey() != null) {
            statement.append(" AND role_key = ?\n");
            parameters.add(query.roleKey());
        }
        if (query.after() != null) {
            statement.append("""
                     AND (effective_from < ?
                          OR (effective_from = ? AND membership_id < ?))
                    """);
            parameters.add(Timestamp.from(query.after().effectiveFrom()));
            parameters.add(Timestamp.from(query.after().effectiveFrom()));
            parameters.add(query.after().membershipId());
        }
        statement.append(" ORDER BY effective_from DESC, membership_id DESC LIMIT ?");
        parameters.add(query.limit() + 1);

        var rows = jdbcTemplate.query(
                statement.toString(),
                (resultSet, rowNumber) -> mapMembership(
                        resultSet,
                        context.actorId(),
                        canRequestMfaReset,
                        canManageMembership,
                        canRequestOwnerTransfer),
                parameters.toArray());
        var hasMore = rows.size() > query.limit();
        var items = hasMore ? List.copyOf(rows.subList(0, query.limit())) : List.copyOf(rows);
        var pageActions = new ArrayList<String>();
        if (canIssueInvitation) {
            pageActions.add("issueInvitation");
        }
        if (canApproveMembership) {
            pageActions.add("approveMembershipChange");
        }
        if (canManageMembership) {
            pageActions.add("executeMembershipChange");
        }
        if (canApproveOwnerTransfer) {
            pageActions.add("approveOwnerTransfer");
        }
        if (canExecuteOwnerTransfer) {
            pageActions.add("executeOwnerTransfer");
        }
        return new MembershipPageSlice(items, hasMore, pageActions);
    }

    @Override
    public ProfileUpdateResult updateProfile(
            AuthorizedTenantContext context, ProfileUpdate update) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        var current = jdbcTemplate.query(
                        """
                        SELECT id, legal_name, display_name, trading_name, organization_type,
                               country_code, timezone, locale, status, lock_version, updated_at
                        FROM organizations
                        WHERE id = ?
                        FOR UPDATE
                        """,
                        (resultSet, rowNumber) -> mapProfile(resultSet, rowNumber, true),
                        context.organizationId())
                .stream()
                .findFirst()
                .orElseThrow(JdbcOrganizationAdministrationStore::notFound);
        if (current.lockVersion() != update.expectedLockVersion()) {
            throw new OrganizationAdministrationException(
                    OrganizationAdministrationException.Reason.STALE_REVISION,
                    "The organization profile changed. Reload it before saving again.");
        }

        var changedFields = changedFields(current, update);
        if (changedFields.isEmpty()) {
            throw new OrganizationAdministrationException(
                    OrganizationAdministrationException.Reason.NO_CHANGES,
                    "Change at least one organization profile field before saving.");
        }

        return new ProfileUpdateResult(
                jdbcTemplate.query(
                                """
                                UPDATE organizations
                                SET legal_name = ?, display_name = ?, trading_name = ?,
                                    organization_type = ?, country_code = ?, timezone = ?, locale = ?,
                                    updated_at = clock_timestamp(), updated_by = ?,
                                    lock_version = lock_version + 1
                                WHERE id = ? AND lock_version = ?
                                RETURNING id, legal_name, display_name, trading_name,
                                          organization_type, country_code, timezone, locale,
                                          status, lock_version, updated_at
                                """,
                                (resultSet, rowNumber) -> mapProfile(resultSet, rowNumber, true),
                                update.legalName(),
                                update.displayName(),
                                update.tradingName(),
                                update.organizationType(),
                                update.countryCode(),
                                update.timezone(),
                                update.locale(),
                                context.actorId(),
                                context.organizationId(),
                                update.expectedLockVersion())
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new OrganizationAdministrationException(
                                OrganizationAdministrationException.Reason.STALE_REVISION,
                                "The organization profile changed. Reload it before saving again.")),
                changedFields);
    }

    private static List<String> changedFields(
            OrganizationProfile current, ProfileUpdate update) {
        var changed = new ArrayList<String>();
        if (!current.legalName().equals(update.legalName())) {
            changed.add("legalName");
        }
        if (!current.displayName().equals(update.displayName())) {
            changed.add("displayName");
        }
        if (!java.util.Objects.equals(current.tradingName(), update.tradingName())) {
            changed.add("tradingName");
        }
        if (!java.util.Objects.equals(current.organizationType(), update.organizationType())) {
            changed.add("organizationType");
        }
        if (!current.countryCode().equals(update.countryCode())) {
            changed.add("countryCode");
        }
        if (!current.timezone().equals(update.timezone())) {
            changed.add("timezone");
        }
        if (!java.util.Objects.equals(current.locale(), update.locale())) {
            changed.add("locale");
        }
        changed.sort(String::compareTo);
        return List.copyOf(changed);
    }

    private void validateRoleFilter(String roleKey) {
        if (roleKey == null) {
            return;
        }
        var exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM authorization_roles
                    WHERE role_key = ?
                      AND registry_version = 'm1-candidate-1'
                      AND interactive
                      AND (status = 'active'
                           OR (coalesce(nullif(current_setting(
                                   'app.reference_authorization_policy_enabled', true), '')::boolean,
                               false)
                               AND status = 'reference'))
                )
                """,
                Boolean.class,
                roleKey);
        if (!Boolean.TRUE.equals(exists)) {
            throw new OrganizationAdministrationException(
                    OrganizationAdministrationException.Reason.MEMBERSHIP_LIST_INVALID,
                    "roleKey is not an active organization membership role.");
        }
    }

    private Set<String> effectivePermissions(AuthorizedTenantContext context) {
        return Set.copyOf(jdbcTemplate.queryForList(
                """
                SELECT DISTINCT permissions.permission_key
                FROM organization_memberships memberships
                JOIN authorization_roles roles ON roles.role_key = memberships.role_key
                JOIN authorization_role_permissions role_permissions
                  ON role_permissions.role_key = roles.role_key
                JOIN authorization_permissions permissions
                  ON permissions.permission_key = role_permissions.permission_key
                 AND permissions.registry_version = roles.registry_version
                WHERE memberships.organization_id = ?
                  AND memberships.user_id = ?
                  AND memberships.status = 'active'
                  AND memberships.effective_from <= clock_timestamp()
                  AND (memberships.effective_to IS NULL
                       OR memberships.effective_to > clock_timestamp())
                  AND roles.registry_version = 'm1-candidate-1'
                  AND roles.interactive
                  AND (roles.status = 'active'
                       OR (coalesce(nullif(current_setting(
                               'app.reference_authorization_policy_enabled', true), '')::boolean,
                           false)
                           AND roles.status = 'reference'))
                  AND (permissions.status = 'active'
                       OR (coalesce(nullif(current_setting(
                               'app.reference_authorization_policy_enabled', true), '')::boolean,
                           false)
                           AND permissions.status = 'reference'))
                """,
                String.class,
                context.organizationId(),
                context.actorId()));
    }

    private static int count(List<ReadinessGate> gates, String outcome) {
        return (int) gates.stream().filter(gate -> outcome.equals(gate.outcome())).count();
    }

    private static List<ReadinessGate> gates(
            OrganizationProfile profile,
            int facilities,
            int draftFacilities,
            int effectiveOwners,
            int pendingOwnerDemotions,
            int missingMandatoryMfa,
            int expiredMfaResetApprovals,
            Set<String> effectivePermissions) {
        var ownerReady = effectiveOwners > pendingOwnerDemotions;
        var mfaReady = missingMandatoryMfa == 0 && expiredMfaResetApprovals == 0;
        var profileGaps = profile.readinessGaps();
        var profileReady = profileGaps.isEmpty();
        return List.of(
                gate(
                        "organization.profile.complete",
                        "Organization profile",
                        profileReady ? "complete" : "blocked",
                        profileReady
                                ? "m1.readiness.profile_complete"
                                : "m1.readiness.profile_incomplete",
                        profileReady ? "m1.remediation.none" : "m1.remediation.complete_profile",
                        profileReady
                                ? "The approved organization profile is complete at the current revision."
                                : "Complete or correct these organization profile fields: "
                                        + String.join(", ", profileGaps)
                                        + ".",
                        List.of("organization-revision:" + profile.lockVersion()),
                        deepLink(effectivePermissions, "organization.profile.read", "#/M1-07")),
                gate(
                        "organization.identifier.primary_verified",
                        "Primary registration identifier",
                        "blocked",
                        "m1.readiness.primary_identifier_missing",
                        "m1.remediation.verify_primary_identifier",
                        "No approved primary registration identifier can be verified from persisted organization state.",
                        List.of(),
                        deepLink(effectivePermissions, "organization.identifier.read", "#/M1-08")),
                gate(
                        "organization.contact.coverage",
                        "Address and contact coverage",
                        "blocked",
                        "m1.readiness.contact_coverage_missing",
                        "m1.remediation.complete_contact_coverage",
                        "A current registered address and verified primary operational contact are not available.",
                        List.of(),
                        deepLink(effectivePermissions, "organization.contact.read", "#/M1-09")),
                gate(
                        "organization.governance.coverage",
                        "Governance responsibility coverage",
                        "blocked",
                        "m1.readiness.governance_coverage_missing",
                        "m1.remediation.assign_governance_responsibilities",
                        "Clinical, privacy, security and billing responsibilities are not persisted.",
                        List.of(),
                        deepLink(effectivePermissions, "organization.governance.read", "#/M1-11")),
                gate(
                        "access.final_owner",
                        "Final owner protection",
                        ownerReady ? "complete" : "blocked",
                        ownerReady
                                ? "m1.readiness.final_owner_present"
                                : "m1.readiness.final_owner_missing",
                        ownerReady
                                ? "m1.remediation.none"
                                : "m1.remediation.restore_final_owner",
                        ownerReady
                                ? "At least one active indefinite owner remains after every open owner demotion."
                                : "An active indefinite owner must remain after every open owner demotion.",
                        List.of(
                                "effective-owner-count:" + effectiveOwners,
                                "pending-owner-demotion-count:" + pendingOwnerDemotions),
                        deepLink(effectivePermissions, "access.membership.read", "#/M1-20")),
                gate(
                        "access.mfa_enforced",
                        "Mandatory-role MFA",
                        mfaReady ? "complete" : "blocked",
                        mfaReady
                                ? "m1.readiness.mfa_enforced"
                                : "m1.readiness.mfa_enforcement_incomplete",
                        mfaReady
                                ? "m1.remediation.none"
                                : "m1.remediation.enrol_mandatory_role_mfa",
                        mfaReady
                                ? "MFA is enabled for every mandatory-role account and no expired reset approval remains."
                                : missingMandatoryMfa + " mandatory-role account(s) lack MFA; "
                                        + expiredMfaResetApprovals
                                        + " expired reset approval(s) remain.",
                        List.of(
                                "mandatory-role-without-mfa-count:" + missingMandatoryMfa,
                                "expired-reset-approval-count:" + expiredMfaResetApprovals),
                        "#/M1-03"),
                gate(
                        "network.facility.minimum",
                        "Minimum eligible facility",
                        "blocked",
                        "m1.readiness.eligible_facility_missing",
                        "m1.remediation.complete_facility",
                        facilities > 0
                                ? "Foundation facility records exist, but none can prove the approved address, timezone and lifecycle requirements."
                                : "At least one submitted or active facility with complete address and timezone is required.",
                        List.of(
                                "facility-count:" + facilities,
                                "draft-facility-count:" + draftFacilities),
                        deepLink(effectivePermissions, "network.facility.read", "#/M1-12")),
                gate(
                        "network.hierarchy.valid",
                        "Network hierarchy",
                        "blocked",
                        "m1.readiness.hierarchy_evaluator_unavailable",
                        "m1.remediation.configure_network_hierarchy",
                        "The approved orphan, cycle, depth and effective-ancestor evaluator is not implemented.",
                        List.of(),
                        deepLink(effectivePermissions, "network.structure.read", "#/M1-14")),
                gate(
                        "network.hours.valid",
                        "Operating hours",
                        "blocked",
                        "m1.readiness.hours_evaluator_unavailable",
                        "m1.remediation.activate_operating_hours",
                        "No approved atomic operating-hours evaluation is available for eligible facilities.",
                        List.of(),
                        deepLink(effectivePermissions, "network.hours.read", "#/M1-16")),
                gate(
                        "service.catalogue.active",
                        "Active service catalogue",
                        "blocked",
                        "m1.readiness.service_catalogue_unavailable",
                        "m1.remediation.activate_service_catalogue",
                        "Organization type and eligible active-service persistence are not available for evaluation.",
                        List.of(),
                        deepLink(effectivePermissions, "service.catalog.read", "#/M1-17")),
                gate(
                        "service.assignment.valid",
                        "Service assignments",
                        "warning",
                        "m1.readiness.delivery_not_declared",
                        "m1.remediation.review_service_assignments",
                        "Service delivery has not been declared; assignment validity must be resolved before delivery is enabled.",
                        List.of(),
                        deepLink(effectivePermissions, "service.assignment.read", "#/M1-18")),
                gate(
                        "identifier.scheme.active",
                        "Identifier scheme",
                        "not_applicable",
                        "m1.readiness.identifier_issuance_not_declared",
                        "m1.remediation.declare_identifier_issuance",
                        "Identifier issuance is not declared for the current organization configuration.",
                        List.of(),
                        deepLink(effectivePermissions, "identifier.scheme.read", "#/M1-19")),
                gate(
                        "configuration.integrity",
                        "Configuration integrity",
                        "blocked",
                        "m1.readiness.configuration_version_unavailable",
                        "m1.remediation.create_configuration_version",
                        "A versioned configuration baseline, parent digest and conflict evaluation are not implemented.",
                        List.of(),
                        configurationLink(effectivePermissions)),
                gate(
                        "governance.registry.active",
                        "Governance registries",
                        "blocked",
                        "m1.readiness.registry_set_incomplete",
                        "m1.remediation.activate_governance_registries",
                        "The full approved operation, event, readiness, retention and export registry set is not active.",
                        List.of("authorization-registry:m1-candidate-1"),
                        configurationLink(effectivePermissions)),
                gate(
                        "platform.dependencies.ready",
                        "Required platform dependencies",
                        "blocked",
                        "m1.readiness.dependency_projection_unavailable",
                        "m1.remediation.verify_required_dependencies",
                        "Fresh database, Redis and candidate-required provider readiness is not bound to this projection.",
                        List.of(),
                        configurationLink(effectivePermissions)));
    }

    private static String deepLink(
            Set<String> effectivePermissions, String requiredPermission, String target) {
        return effectivePermissions.contains(requiredPermission) ? target : "#/M1-06";
    }

    private static String configurationLink(Set<String> effectivePermissions) {
        return effectivePermissions.stream().anyMatch(Set.of(
                                "configuration.validation.run",
                                "configuration.approve",
                                "configuration.activate")
                        ::contains)
                ? "#/M1-21"
                : "#/M1-06";
    }

    private static ReadinessGate gate(
            String key,
            String label,
            String outcome,
            String reasonCode,
            String remediationCode,
            String detail,
            List<String> evidenceReferences,
            String href) {
        return new ReadinessGate(
                key,
                ReadinessGate.CATALOGUE_VERSION,
                label,
                outcome,
                reasonCode,
                remediationCode,
                detail,
                evidenceReferences,
                href);
    }

    private static OrganizationProfile mapProfile(
            ResultSet resultSet, int rowNumber, boolean editable)
            throws SQLException {
        return new OrganizationProfile(
                resultSet.getObject("id", java.util.UUID.class),
                resultSet.getString("legal_name"),
                resultSet.getString("display_name"),
                resultSet.getString("trading_name"),
                resultSet.getString("organization_type"),
                resultSet.getString("country_code"),
                resultSet.getString("timezone"),
                resultSet.getString("locale"),
                resultSet.getString("status"),
                editable,
                resultSet.getLong("lock_version"),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static OrganizationMembership mapMembership(
            ResultSet resultSet,
            java.util.UUID actorId,
            boolean canRequestMfaReset,
            boolean canManageMembership,
            boolean canRequestOwnerTransfer)
            throws SQLException {
        var effectiveTo = resultSet.getTimestamp("effective_to");
        var userId = resultSet.getObject("user_id", java.util.UUID.class);
        var accessState = resultSet.getString("access_state");
        var mfaEnabled = resultSet.getBoolean("mfa_enabled");
        var actions = new ArrayList<String>();
        var actionable = !actorId.equals(userId)
                && "active".equals(accessState)
                && "active".equals(resultSet.getString("account_status"));
        if (actionable && canRequestMfaReset && mfaEnabled) {
            actions.add("requestMfaReset");
        }
        if (actionable && canManageMembership && !resultSet.getBoolean("final_owner")) {
            actions.add("requestRoleChange");
            actions.add("requestRevocation");
        }
        if (actionable && canRequestOwnerTransfer) {
            actions.add("requestOwnerTransfer");
        }
        return new OrganizationMembership(
                resultSet.getObject("membership_id", java.util.UUID.class),
                userId,
                resultSet.getString("display_name"),
                resultSet.getString("email"),
                resultSet.getString("account_status"),
                resultSet.getString("role_key"),
                resultSet.getString("role_display_name"),
                resultSet.getString("role_status"),
                resultSet.getBoolean("final_owner"),
                accessState,
                resultSet.getTimestamp("effective_from").toInstant(),
                effectiveTo == null ? null : effectiveTo.toInstant(),
                resultSet.getLong("lock_version"),
                mfaEnabled,
                actions);
    }

    private static OrganizationAdministrationException notFound() {
        return new OrganizationAdministrationException(
                OrganizationAdministrationException.Reason.PROFILE_NOT_FOUND,
                "The organization profile is unavailable.");
    }
}
