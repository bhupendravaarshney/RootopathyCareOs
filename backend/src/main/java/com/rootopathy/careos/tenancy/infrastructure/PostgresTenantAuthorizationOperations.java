package com.rootopathy.careos.tenancy.infrastructure;

import static com.rootopathy.careos.tenancy.application.TenantAuthorizationException.Reason.MEMBERSHIP_NOT_FOUND;
import static com.rootopathy.careos.tenancy.application.TenantAuthorizationException.Reason.PERMISSION_DENIED;

import com.rootopathy.careos.tenancy.application.TenantAuthorizationException;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class PostgresTenantAuthorizationOperations implements TenantAuthorizationOperations {
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public PostgresTenantAuthorizationOperations(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Override
    public <T> T execute(
            TenantAuthorizationRequest request,
            Function<AuthorizedTenantContext, T> authorizedWork) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(authorizedWork, "authorizedWork");
        return transactionTemplate.execute(status -> {
            bindRequest(request);
            var now = Timestamp.from(clock.instant());
            var activeMemberships = jdbcTemplate.queryForList(
                    """
                    SELECT memberships.id
                    FROM organization_memberships memberships
                    JOIN organizations ON organizations.id = memberships.organization_id
                    WHERE memberships.organization_id = ?
                      AND memberships.user_id = ?
                      AND memberships.status = 'active'
                      AND memberships.effective_from <= ?
                      AND (memberships.effective_to IS NULL OR memberships.effective_to > ?)
                      AND organizations.status IN ('draft', 'active')
                    FOR SHARE OF memberships, organizations
                    """,
                    UUID.class,
                    request.organizationId(),
                    request.actor().actorId(),
                    now,
                    now);
            if (activeMemberships.isEmpty()) {
                throw new TenantAuthorizationException(
                        MEMBERSHIP_NOT_FOUND,
                        "The resource is unavailable or is not assigned to this account.");
            }

            var permissionGranted = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM organization_memberships memberships
                        JOIN authorization_roles roles
                          ON roles.role_key = memberships.role_key AND roles.status = 'active'
                        JOIN authorization_role_permissions role_permissions
                          ON role_permissions.role_key = roles.role_key
                        JOIN authorization_permissions permissions
                          ON permissions.permission_key = role_permissions.permission_key
                         AND permissions.status = 'active'
                        WHERE memberships.organization_id = ?
                          AND memberships.user_id = ?
                          AND memberships.status = 'active'
                          AND memberships.effective_from <= ?
                          AND (memberships.effective_to IS NULL OR memberships.effective_to > ?)
                          AND permissions.permission_key = ?
                    )
                    """,
                    Boolean.class,
                    request.organizationId(),
                    request.actor().actorId(),
                    now,
                    now,
                    request.requiredPermission().value()));
            if (!permissionGranted) {
                throw new TenantAuthorizationException(
                        PERMISSION_DENIED,
                        "The current account does not have permission to perform this operation.");
            }

            var context = new AuthorizedTenantContext(
                    request.organizationId(),
                    request.actor().actorId(),
                    request.actor().purpose(),
                    request.actor().correlationId());
            return authorizedWork.apply(context);
        });
    }

    private void bindRequest(TenantAuthorizationRequest request) {
        setTransactionLocal("app.current_organization_id", request.organizationId().toString());
        setTransactionLocal("app.current_actor_id", request.actor().actorId().toString());
        setTransactionLocal("app.current_purpose", request.actor().purpose());
        setTransactionLocal("app.current_correlation_id", request.actor().correlationId());
    }

    private void setTransactionLocal(String setting, String value) {
        jdbcTemplate.queryForObject("select set_config(?, ?, true)", String.class, setting, value);
    }
}
