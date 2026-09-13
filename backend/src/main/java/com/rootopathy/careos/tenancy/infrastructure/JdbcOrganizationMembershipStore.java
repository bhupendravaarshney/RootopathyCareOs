package com.rootopathy.careos.tenancy.infrastructure;

import com.rootopathy.careos.tenancy.application.OrganizationMembershipStore;
import com.rootopathy.careos.tenancy.domain.OrganizationAccess;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrganizationMembershipStore implements OrganizationMembershipStore {
    private final JdbcTemplate jdbcTemplate;

    public JdbcOrganizationMembershipStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<OrganizationAccess> findSelectableOrganizations(UUID actorId, Instant at) {
        var rows = jdbcTemplate.query(
                """
                SELECT organizations.id, organizations.display_name, organizations.status,
                       memberships.role_key
                FROM organizations
                JOIN organization_memberships memberships
                  ON memberships.organization_id = organizations.id
                WHERE memberships.user_id = ?
                  AND memberships.status = 'active'
                  AND memberships.effective_from <= ?
                  AND (memberships.effective_to IS NULL OR memberships.effective_to > ?)
                  AND organizations.status IN ('draft', 'active')
                ORDER BY lower(organizations.display_name), organizations.id, memberships.role_key
                """,
                (resultSet, rowNumber) -> new MembershipRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("display_name"),
                        resultSet.getString("status"),
                        resultSet.getString("role_key")),
                actorId,
                Timestamp.from(at),
                Timestamp.from(at));

        var grouped = new LinkedHashMap<UUID, OrganizationAccessBuilder>();
        for (var row : rows) {
            grouped.computeIfAbsent(
                            row.organizationId(),
                            ignored -> new OrganizationAccessBuilder(
                                    row.organizationId(), row.displayName(), row.status()))
                    .roleKeys()
                    .add(row.roleKey());
        }
        return grouped.values().stream().map(OrganizationAccessBuilder::build).toList();
    }

    private record MembershipRow(UUID organizationId, String displayName, String status, String roleKey) {}

    private record OrganizationAccessBuilder(
            UUID organizationId, String displayName, String status, List<String> roleKeys) {
        private OrganizationAccessBuilder(UUID organizationId, String displayName, String status) {
            this(organizationId, displayName, status, new ArrayList<>());
        }

        private OrganizationAccess build() {
            return new OrganizationAccess(organizationId, displayName, status, roleKeys);
        }
    }
}
