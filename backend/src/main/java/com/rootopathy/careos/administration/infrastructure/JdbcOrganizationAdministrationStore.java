package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.OrganizationAdministrationException;
import com.rootopathy.careos.administration.application.OrganizationAdministrationStore;
import com.rootopathy.careos.administration.application.EvidenceExportArtifactStore;
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
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrganizationAdministrationStore implements OrganizationAdministrationStore {
    private final JdbcTemplate jdbcTemplate;
    private final EvidenceExportArtifactStore exportArtifacts;
    private final RedisConnectionFactory redisConnectionFactory;

    public JdbcOrganizationAdministrationStore(
            JdbcTemplate jdbcTemplate,
            EvidenceExportArtifactStore exportArtifacts,
            RedisConnectionFactory redisConnectionFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.exportArtifacts = exportArtifacts;
        this.redisConnectionFactory = redisConnectionFactory;
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
                       count(*) FILTER (WHERE f.status = 'draft') AS drafts,
                       count(*) FILTER (WHERE f.status IN ('under_review','active')
                         AND f.address_id IS NOT NULL AND (f.timezone IS NOT NULL OR o.timezone IS NOT NULL)
                         AND a.validation_status='validated') AS eligible
                FROM facilities f JOIN organizations o ON o.id=f.organization_id
                LEFT JOIN organization_addresses a ON a.organization_id=f.organization_id AND a.id=f.address_id
                WHERE f.organization_id = ?
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
        var identifierReadiness = jdbcTemplate.queryForObject(
                """
                SELECT count(*) AS required_types,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1
                           FROM organization_identifiers identifiers
                           WHERE identifiers.organization_id = organizations.id
                             AND identifiers.identifier_type = types.identifier_type
                             AND identifiers.is_primary
                             AND identifiers.verification_status = 'verified'
                             AND identifiers.status IN ('verified', 'active')
                             AND identifiers.effective_from <= clock_timestamp()
                             AND (identifiers.effective_to IS NULL
                                  OR identifiers.effective_to > clock_timestamp())
                             AND (identifiers.expiry_date IS NULL
                                  OR identifiers.expiry_date >=
                                     (clock_timestamp() AT TIME ZONE organizations.timezone)::date)
                       )) AS verified_types
                FROM organizations
                JOIN organization_identifier_types types
                  ON types.status = 'active'
                 AND types.registry_version = 'm1-candidate-1'
                 AND types.primary_required
                 AND (types.jurisdiction_country_code IS NULL
                      OR types.jurisdiction_country_code = organizations.country_code)
                WHERE organizations.id = ?
                """,
                (resultSet, rowNumber) -> new IdentifierReadiness(
                        resultSet.getInt("required_types"),
                        resultSet.getInt("verified_types")),
                context.organizationId());
        var contactReadiness = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FILTER (WHERE EXISTS (
                           SELECT 1
                           FROM organization_addresses addresses
                           WHERE addresses.organization_id = organizations.id
                             AND addresses.address_type = 'registered'
                             AND addresses.status = 'active'
                             AND addresses.effective_from <= clock_timestamp()
                             AND (addresses.effective_to IS NULL
                                  OR addresses.effective_to > clock_timestamp())
                       )) AS registered_addresses,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1
                           FROM organization_contacts contacts
                           WHERE contacts.organization_id = organizations.id
                             AND contacts.purpose_key = 'operational'
                             AND contacts.is_primary
                             AND contacts.status = 'active'
                             AND contacts.effective_from <= clock_timestamp()
                             AND (contacts.effective_to IS NULL
                                  OR contacts.effective_to > clock_timestamp())
                       )) AS primary_operational_contacts,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1
                           FROM organization_contacts contacts
                           WHERE contacts.organization_id = organizations.id
                             AND contacts.purpose_key = 'operational'
                             AND contacts.is_primary
                             AND contacts.verification_status = 'verified'
                             AND contacts.status = 'active'
                             AND contacts.effective_from <= clock_timestamp()
                             AND (contacts.effective_to IS NULL
                                  OR contacts.effective_to > clock_timestamp())
                       )) AS verified_primary_operational_contacts
                FROM organizations
                WHERE organizations.id = ?
                """,
                (resultSet, rowNumber) -> new ContactReadiness(
                        resultSet.getInt("registered_addresses"),
                        resultSet.getInt("primary_operational_contacts"),
                        resultSet.getInt("verified_primary_operational_contacts")),
                context.organizationId());
        var governanceReadiness = jdbcTemplate.queryForObject(
                """
                SELECT count(DISTINCT responsibility_type) FILTER (
                           WHERE is_primary AND status = 'active'
                             AND effective_from <= clock_timestamp()
                             AND (effective_to IS NULL OR effective_to > clock_timestamp())
                             AND (escalation_email IS NOT NULL OR escalation_phone IS NOT NULL)) AS covered_types
                FROM organization_governance_responsibilities
                WHERE organization_id = ?
                """,
                Integer.class,
                context.organizationId());
        var hierarchyReadiness = jdbcTemplate.queryForObject(
                """
                WITH RECURSIVE eligible_facilities AS (
                    SELECT facilities.id
                    FROM facilities
                    JOIN organizations
                      ON organizations.id = facilities.organization_id
                    JOIN organization_addresses addresses
                      ON addresses.organization_id = facilities.organization_id
                     AND addresses.id = facilities.address_id
                    WHERE facilities.organization_id = ?
                      AND facilities.status IN ('under_review', 'active')
                      AND (facilities.timezone IS NOT NULL OR organizations.timezone IS NOT NULL)
                      AND addresses.validation_status = 'validated'
                ), active_units AS (
                    SELECT id, facility_id, parent_id, effective_from, effective_to
                    FROM organization_units
                    WHERE organization_id = ? AND status = 'active'
                ), walks AS (
                    SELECT units.id AS origin_id, units.facility_id, units.id AS current_id,
                           units.parent_id, 1 AS depth, ARRAY[units.id] AS path
                    FROM active_units units
                    WHERE units.effective_from <= statement_timestamp()
                      AND (units.effective_to IS NULL OR units.effective_to > statement_timestamp())
                    UNION ALL
                    SELECT walks.origin_id, walks.facility_id, parents.id, parents.parent_id,
                           walks.depth + 1, walks.path || parents.id
                    FROM walks
                    JOIN organization_units parents
                      ON parents.organization_id = ?
                     AND parents.id = walks.parent_id
                     AND parents.facility_id = walks.facility_id
                     AND parents.status = 'active'
                     AND parents.effective_from <= statement_timestamp()
                     AND (parents.effective_to IS NULL OR parents.effective_to > statement_timestamp())
                    WHERE walks.depth < 8 AND NOT parents.id = ANY(walks.path)
                ), valid_units AS (
                    SELECT DISTINCT origin_id, facility_id
                    FROM walks
                    WHERE parent_id IS NULL
                ), active_locations AS (
                    SELECT id, facility_id, unit_id, parent_id, effective_from, effective_to
                    FROM service_locations
                    WHERE organization_id = ? AND status = 'active'
                ), location_walks AS (
                    SELECT locations.id AS origin_id, locations.facility_id,
                           locations.id AS current_id, locations.unit_id, locations.parent_id,
                           1 AS depth, ARRAY[locations.id] AS path
                    FROM active_locations locations
                    WHERE locations.effective_from <= statement_timestamp()
                      AND (locations.effective_to IS NULL OR locations.effective_to > statement_timestamp())
                      AND (locations.unit_id IS NULL OR EXISTS (
                          SELECT 1 FROM valid_units units
                          WHERE units.origin_id = locations.unit_id
                            AND units.facility_id = locations.facility_id))
                    UNION ALL
                    SELECT location_walks.origin_id, location_walks.facility_id,
                           parents.id, parents.unit_id, parents.parent_id,
                           location_walks.depth + 1, location_walks.path || parents.id
                    FROM location_walks
                    JOIN service_locations parents
                      ON parents.organization_id = ?
                     AND parents.id = location_walks.parent_id
                     AND parents.facility_id = location_walks.facility_id
                     AND parents.status = 'active'
                     AND parents.effective_from <= statement_timestamp()
                     AND (parents.effective_to IS NULL OR parents.effective_to > statement_timestamp())
                    WHERE location_walks.depth < 8
                      AND NOT parents.id = ANY(location_walks.path)
                      AND (parents.unit_id IS NULL OR EXISTS (
                          SELECT 1 FROM valid_units units
                          WHERE units.origin_id = parents.unit_id
                            AND units.facility_id = parents.facility_id))
                ), valid_locations AS (
                    SELECT DISTINCT origin_id, facility_id
                    FROM location_walks
                    WHERE parent_id IS NULL
                ), covered_hierarchies AS (
                    SELECT facility_id FROM valid_units
                    UNION
                    SELECT facility_id FROM valid_locations
                )
                SELECT (SELECT count(*) FROM eligible_facilities) AS eligible_facilities,
                       (SELECT count(DISTINCT eligible_facilities.id)
                        FROM eligible_facilities
                        JOIN covered_hierarchies
                          ON covered_hierarchies.facility_id = eligible_facilities.id)
                           AS covered_facilities,
                       (SELECT count(*) FROM active_units) AS active_units,
                       (SELECT count(*) FROM valid_units) AS valid_active_units,
                       (SELECT count(*) FROM active_locations) AS active_locations,
                       (SELECT count(*) FROM valid_locations) AS valid_active_locations
                """,
                (resultSet, rowNumber) -> new HierarchyReadiness(
                        resultSet.getInt("eligible_facilities"),
                        resultSet.getInt("covered_facilities"),
                        resultSet.getInt("active_units"),
                        resultSet.getInt("valid_active_units"),
                        resultSet.getInt("active_locations"),
                        resultSet.getInt("valid_active_locations")),
                context.organizationId(),
                context.organizationId(),
                context.organizationId(),
                context.organizationId(),
                context.organizationId());
        var hoursCoveredFacilities = jdbcTemplate.queryForObject(
                """
                SELECT count(DISTINCT facilities.id)
                FROM facilities
                JOIN organizations ON organizations.id=facilities.organization_id
                JOIN organization_addresses addresses
                  ON addresses.organization_id=facilities.organization_id
                 AND addresses.id=facilities.address_id
                JOIN operating_hours_batches batches
                  ON batches.organization_id=facilities.organization_id
                 AND batches.target_type='facility' AND batches.target_id=facilities.id
                 AND batches.status='active' AND batches.effective_from<=clock_timestamp()
                 AND (batches.effective_to IS NULL OR batches.effective_to>clock_timestamp())
                WHERE facilities.organization_id=? AND facilities.status IN ('under_review','active')
                  AND (facilities.timezone IS NOT NULL OR organizations.timezone IS NOT NULL)
                  AND addresses.validation_status='validated'
                """,
                Integer.class,
                context.organizationId());
        var serviceReadiness = jdbcTemplate.queryForMap(
                """
                SELECT count(*) FILTER (WHERE status='active') AS active_services,
                       count(*) FILTER (WHERE status='active' AND owner_responsibility_id IS NOT NULL) AS owned_active_services,
                       count(*) FILTER (WHERE status='active' AND EXISTS (
                         SELECT 1 FROM service_assignments assignments
                         WHERE assignments.organization_id=service_definitions.organization_id
                           AND assignments.service_id=service_definitions.id
                           AND assignments.status='active'
                           AND assignments.effective_from<=clock_timestamp()
                           AND (assignments.effective_to IS NULL OR assignments.effective_to>clock_timestamp()))) AS assigned_active_services
                FROM service_definitions WHERE organization_id=?
                """,
                context.organizationId());
        var schemeReadiness = jdbcTemplate.queryForMap(
                """
                SELECT count(*) AS schemes,
                       count(*) FILTER (WHERE status='active' AND EXISTS (
                         SELECT 1 FROM identifier_scheme_versions versions
                         WHERE versions.organization_id=identifier_schemes.organization_id
                           AND versions.scheme_id=identifier_schemes.id AND versions.status='active'
                           AND versions.effective_from<=clock_timestamp())) AS active_schemes
                FROM identifier_schemes WHERE organization_id=? AND status<>'retired'
                """,
                context.organizationId());
        var activeConfiguration = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM configuration_versions WHERE organization_id=? AND status='active')",
                Boolean.class, context.organizationId()));
        var activeRegistry = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM authorization_registry_releases WHERE registry_version='m1-candidate-1' AND status='active')",
                Boolean.class));
        var redisReady = redisReady();
        var facilityCount = ((Number) facilityCounts.get("total")).intValue();
        var draftFacilityCount = ((Number) facilityCounts.get("drafts")).intValue();
        var eligibleFacilityCount = ((Number) facilityCounts.get("eligible")).intValue();
        var effectivePermissions = effectivePermissions(context);
        var gates = gates(
                profile,
                facilityCount,
                draftFacilityCount,
                eligibleFacilityCount,
                effectiveOwners == null ? 0 : effectiveOwners,
                pendingOwnerDemotions == null ? 0 : pendingOwnerDemotions,
                missingMandatoryMfa == null ? 0 : missingMandatoryMfa,
                expiredMfaResetApprovals == null ? 0 : expiredMfaResetApprovals,
                identifierReadiness == null
                        ? new IdentifierReadiness(0, 0)
                        : identifierReadiness,
                contactReadiness == null
                        ? new ContactReadiness(0, 0, 0)
                        : contactReadiness,
                governanceReadiness == null ? 0 : governanceReadiness,
                hierarchyReadiness == null
                        ? new HierarchyReadiness(0, 0, 0, 0, 0, 0)
                        : hierarchyReadiness,
                hoursCoveredFacilities == null ? 0 : hoursCoveredFacilities,
                ((Number) serviceReadiness.get("active_services")).intValue(),
                ((Number) serviceReadiness.get("owned_active_services")).intValue(),
                ((Number) serviceReadiness.get("assigned_active_services")).intValue(),
                ((Number) schemeReadiness.get("schemes")).intValue(),
                ((Number) schemeReadiness.get("active_schemes")).intValue(),
                activeConfiguration,
                activeRegistry,
                redisReady,
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

    private List<ReadinessGate> gates(
            OrganizationProfile profile,
            int facilities,
            int draftFacilities,
            int eligibleFacilities,
            int effectiveOwners,
            int pendingOwnerDemotions,
            int missingMandatoryMfa,
            int expiredMfaResetApprovals,
            IdentifierReadiness identifierReadiness,
            ContactReadiness contactReadiness,
            int governanceCoveredTypes,
            HierarchyReadiness hierarchyReadiness,
            int hoursCoveredFacilities,
            int activeServices,
            int ownedActiveServices,
            int assignedActiveServices,
            int identifierSchemes,
            int activeIdentifierSchemes,
            boolean activeConfiguration,
            boolean activeRegistry,
            boolean redisReady,
            Set<String> effectivePermissions) {
        var ownerReady = effectiveOwners > pendingOwnerDemotions;
        var mfaReady = missingMandatoryMfa == 0 && expiredMfaResetApprovals == 0;
        var profileGaps = profile.readinessGaps();
        var profileReady = profileGaps.isEmpty();
        var identifierPolicyConfigured = identifierReadiness.requiredTypes() > 0;
        var identifierReady = identifierPolicyConfigured
                && identifierReadiness.verifiedTypes() == identifierReadiness.requiredTypes();
        var identifierOutcome = identifierReady
                ? "complete"
                : identifierPolicyConfigured ? "blocked" : "warning";
        var registeredAddressReady = contactReadiness.registeredAddresses() > 0;
        var verifiedContactReady =
                contactReadiness.verifiedPrimaryOperationalContacts() > 0;
        var contactReady = registeredAddressReady && verifiedContactReady;
        var contactOutcome = contactReady
                ? "complete"
                : !registeredAddressReady || !"draft".equals(profile.lifecycleStatus())
                        ? "blocked"
                        : "warning";
        var hierarchyReady = hierarchyReadiness.eligibleFacilities() > 0
                && hierarchyReadiness.coveredFacilities()
                        == hierarchyReadiness.eligibleFacilities()
                && hierarchyReadiness.activeUnits() == hierarchyReadiness.validActiveUnits()
                && hierarchyReadiness.activeLocations()
                        == hierarchyReadiness.validActiveLocations();
        var hoursReady = eligibleFacilities > 0 && hoursCoveredFacilities == eligibleFacilities;
        var servicesApplicable = !"administrative".equals(profile.organizationType());
        var servicesReady = activeServices > 0 && activeServices == ownedActiveServices;
        var assignmentsReady = activeServices > 0 && assignedActiveServices == activeServices;
        var schemesApplicable = identifierSchemes > 0;
        var schemesReady = schemesApplicable && activeIdentifierSchemes == identifierSchemes;
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
                        identifierOutcome,
                        identifierReady
                                ? "m1.readiness.primary_identifier_verified"
                                : identifierPolicyConfigured
                                        ? "m1.readiness.primary_identifier_missing"
                                        : "m1.readiness.primary_identifier_policy_optional",
                        identifierReady
                                ? "m1.remediation.none"
                                : identifierPolicyConfigured
                                        ? "m1.remediation.verify_primary_identifier"
                                        : "m1.remediation.review_identifier_policy",
                        identifierReady
                                ? "Every jurisdiction-required identifier type has a current verified primary registration identifier."
                                : identifierPolicyConfigured
                                        ? "A current verified primary registration identifier is missing for one or more required types."
                                        : "No jurisdiction policy currently requires a primary registration identifier.",
                        List.of(
                                "required-identifier-type-count:"
                                        + identifierReadiness.requiredTypes(),
                                "verified-primary-identifier-count:"
                                        + identifierReadiness.verifiedTypes()),
                        deepLink(effectivePermissions, "organization.identifier.read", "#/M1-08")),
                gate(
                        "organization.contact.coverage",
                        "Address and contact coverage",
                        contactOutcome,
                        contactReady
                                ? "m1.readiness.contact_coverage_complete"
                                : !registeredAddressReady
                                        ? "m1.readiness.registered_address_missing"
                                        : "m1.readiness.operational_contact_unverified",
                        contactReady
                                ? "m1.remediation.none"
                                : !registeredAddressReady
                                        ? "m1.remediation.add_registered_address"
                                        : "m1.remediation.verify_operational_contact",
                        contactReady
                                ? "A current registered address and verified primary operational contact are available."
                                : !registeredAddressReady
                                        ? "A current registered address is required."
                                        : "Verify a current primary operational contact before activation.",
                        List.of(
                                "current-registered-address-count:"
                                        + contactReadiness.registeredAddresses(),
                                "current-primary-operational-contact-count:"
                                        + contactReadiness.primaryOperationalContacts(),
                                "verified-primary-operational-contact-count:"
                                        + contactReadiness
                                                .verifiedPrimaryOperationalContacts()),
                        deepLink(effectivePermissions, "organization.contact.read", "#/M1-09")),
                gate(
                        "organization.governance.coverage",
                        "Governance responsibility coverage",
                        governanceCoveredTypes == 4 ? "complete" : "blocked",
                        governanceCoveredTypes == 4
                                ? "m1.readiness.governance_coverage_complete"
                                : "m1.readiness.governance_coverage_missing",
                        governanceCoveredTypes == 4
                                ? "m1.remediation.none"
                                : "m1.remediation.assign_governance_responsibilities",
                        governanceCoveredTypes == 4
                                ? "Clinical, privacy, security and billing responsibilities have effective primary coverage and escalation channels."
                                : "Assign effective primary clinical, privacy, security and billing responsibilities with escalation channels.",
                        List.of("covered-governance-responsibility-type-count:"
                                + governanceCoveredTypes),
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
                        eligibleFacilities > 0 ? "complete" : "blocked",
                        eligibleFacilities > 0 ? "m1.readiness.eligible_facility_present" : "m1.readiness.eligible_facility_missing",
                        eligibleFacilities > 0 ? "m1.remediation.none" : "m1.remediation.complete_facility",
                        eligibleFacilities > 0
                                ? "At least one submitted or active facility has a validated address and effective timezone."
                                : facilities > 0
                                ? "Foundation facility records exist, but none can prove the approved address, timezone and lifecycle requirements."
                                : "At least one submitted or active facility with complete address and timezone is required.",
                        List.of(
                                "facility-count:" + facilities,
                                "draft-facility-count:" + draftFacilities,
                                "eligible-facility-count:" + eligibleFacilities),
                        deepLink(effectivePermissions, "network.facility.read", "#/M1-12")),
                gate(
                        "network.hierarchy.valid",
                        "Network hierarchy",
                        hierarchyReady ? "complete" : "blocked",
                        hierarchyReady
                                ? "m1.readiness.hierarchy_valid"
                                : "m1.readiness.hierarchy_invalid",
                        hierarchyReady
                                ? "m1.remediation.none"
                                : "m1.remediation.configure_network_hierarchy",
                        hierarchyReady
                                ? "Every eligible facility has a current active unit or location hierarchy with valid effective ancestor chains."
                                : "Every eligible facility requires a current active unit or location hierarchy without orphaned, cyclic, over-depth, inactive, or ineffective ancestors.",
                        List.of(
                                "eligible-facility-count:"
                                        + hierarchyReadiness.eligibleFacilities(),
                                "hierarchy-covered-facility-count:"
                                        + hierarchyReadiness.coveredFacilities(),
                                "active-unit-count:"
                                        + hierarchyReadiness.activeUnits()
                                        + ";valid:"
                                        + hierarchyReadiness.validActiveUnits(),
                                "active-location-count:"
                                        + hierarchyReadiness.activeLocations()
                                        + ";valid:"
                                        + hierarchyReadiness.validActiveLocations()),
                        deepLink(
                                effectivePermissions,
                                "network.structure.read",
                                hierarchyReadiness.activeLocations()
                                                != hierarchyReadiness.validActiveLocations()
                                        ? "#/M1-15"
                                        : "#/M1-14")),
                gate(
                        "network.hours.valid",
                        "Operating hours",
                        hoursReady ? "complete" : "blocked",
                        hoursReady
                                ? "m1.readiness.hours_valid"
                                : "m1.readiness.hours_missing",
                        hoursReady ? "m1.remediation.none" : "m1.remediation.activate_operating_hours",
                        hoursReady
                                ? "Every eligible facility has one current atomic operating-hours batch."
                                : "Every eligible facility requires one current atomic operating-hours batch.",
                        List.of(
                                "eligible-facility-count:" + eligibleFacilities,
                                "hours-covered-facility-count:" + hoursCoveredFacilities),
                        deepLink(effectivePermissions, "network.hours.read", "#/M1-16")),
                gate(
                        "service.catalogue.active",
                        "Active service catalogue",
                        !servicesApplicable ? "not_applicable" : servicesReady ? "complete" : "blocked",
                        !servicesApplicable ? "m1.readiness.service_catalogue_not_applicable" : servicesReady ? "m1.readiness.service_catalogue_active" : "m1.readiness.service_catalogue_missing",
                        !servicesApplicable || servicesReady ? "m1.remediation.none" : "m1.remediation.activate_service_catalogue",
                        !servicesApplicable ? "Service delivery is not applicable to an administrative organization." : servicesReady ? "Every active service has an eligible clinical owner." : "At least one active service is required and every active service must have an eligible clinical owner.",
                        List.of("active-service-count:"+activeServices,"owned-active-service-count:"+ownedActiveServices),
                        deepLink(effectivePermissions, "service.catalog.read", "#/M1-17")),
                gate(
                        "service.assignment.valid",
                        "Service assignments",
                        !servicesApplicable ? "not_applicable" : assignmentsReady ? "complete" : activeServices==0 ? "warning" : "blocked",
                        !servicesApplicable ? "m1.readiness.delivery_not_applicable" : assignmentsReady ? "m1.readiness.assignments_valid" : activeServices==0 ? "m1.readiness.delivery_not_declared" : "m1.readiness.assignments_missing",
                        !servicesApplicable || assignmentsReady ? "m1.remediation.none" : "m1.remediation.review_service_assignments",
                        !servicesApplicable ? "Service delivery is not applicable." : assignmentsReady ? "Every active service has a current valid delivery assignment." : activeServices==0 ? "Service delivery has not been declared." : "Every active service requires a current active delivery assignment.",
                        List.of("active-service-count:"+activeServices,"assigned-active-service-count:"+assignedActiveServices),
                        deepLink(effectivePermissions, "service.assignment.read", "#/M1-18")),
                gate(
                        "identifier.scheme.active",
                        "Identifier scheme",
                        !schemesApplicable ? "not_applicable" : schemesReady ? "complete" : "blocked",
                        !schemesApplicable ? "m1.readiness.identifier_issuance_not_declared" : schemesReady ? "m1.readiness.identifier_scheme_active" : "m1.readiness.identifier_scheme_incomplete",
                        !schemesApplicable || schemesReady ? "m1.remediation.none" : "m1.remediation.activate_identifier_scheme",
                        !schemesApplicable ? "Identifier issuance is not declared for the current organization configuration." : schemesReady ? "Every declared identifier scope has one active immutable scheme version." : "Every declared identifier scope requires one active immutable scheme version.",
                        List.of("identifier-scheme-count:"+identifierSchemes,"active-identifier-scheme-count:"+activeIdentifierSchemes),
                        deepLink(effectivePermissions, "identifier.scheme.read", "#/M1-19")),
                gate(
                        "configuration.integrity",
                        "Configuration integrity",
                        activeConfiguration ? "complete" : "blocked",
                        activeConfiguration ? "m1.readiness.configuration_version_active" : "m1.readiness.configuration_version_pending",
                        "m1.remediation.create_configuration_version",
                        activeConfiguration ? "An activated configuration version anchors the current canonical baseline." : "No configuration version is active yet; validate and activate the initial candidate.",
                        List.of("active-configuration:"+activeConfiguration),
                        configurationLink(effectivePermissions)),
                gate(
                        "governance.registry.active",
                        "Governance registries",
                        activeRegistry ? "complete" : "blocked",
                        activeRegistry ? "m1.readiness.registry_set_active" : "m1.readiness.registry_set_incomplete",
                        "m1.remediation.activate_governance_registries",
                        "The full approved operation, event, readiness, retention and export registry set is not active.",
                        List.of("authorization-registry:m1-candidate-1;active:"+activeRegistry),
                        configurationLink(effectivePermissions)),
                gate(
                        "platform.dependencies.ready",
                        "Required platform dependencies",
                        redisReady ? "complete" : "blocked",
                        redisReady ? "m1.readiness.platform_dependencies_ready" : "m1.readiness.redis_unavailable",
                        redisReady ? "m1.remediation.none" : "m1.remediation.restore_redis",
                        redisReady ? "The transactional database and Redis session dependency are ready; unused export storage remains optional." : "The required Redis session dependency is unavailable.",
                        List.of(
                                "postgresql:ready",
                                "redis:" + (redisReady ? "ready" : "unavailable"),
                                "evidence-export-storage:optional-"
                                        + (exportArtifacts.available() ? "ready" : "unavailable")),
                        configurationLink(effectivePermissions)));
    }

    private boolean redisReady() {
        try (var connection = redisConnectionFactory.getConnection()) {
            return "PONG".equals(connection.commands().ping());
        } catch (RuntimeException exception) {
            return false;
        }
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

    private record IdentifierReadiness(int requiredTypes, int verifiedTypes) {}

    private record ContactReadiness(
            int registeredAddresses,
            int primaryOperationalContacts,
            int verifiedPrimaryOperationalContacts) {}

    private record HierarchyReadiness(
            int eligibleFacilities,
            int coveredFacilities,
            int activeUnits,
            int validActiveUnits,
            int activeLocations,
            int validActiveLocations) {}
}
