package com.rootopathy.careos.tenancy.infrastructure;

import static com.rootopathy.careos.tenancy.application.TenantAuthorizationException.Reason.MEMBERSHIP_NOT_FOUND;
import static com.rootopathy.careos.tenancy.application.TenantAuthorizationException.Reason.PERMISSION_DENIED;
import static com.rootopathy.careos.tenancy.application.TenantAuthorizationException.Reason.REASON_REQUIRED;
import static com.rootopathy.careos.tenancy.application.TenantAuthorizationException.Reason.RECENT_AUTHENTICATION_REQUIRED;
import static com.rootopathy.careos.tenancy.application.TenantAuthorizationException.Reason.MFA_REQUIRED;
import static com.rootopathy.careos.tenancy.application.TenantAuthorizationException.Reason.INDEPENDENT_APPROVAL_REQUIRED;

import com.rootopathy.careos.tenancy.application.TenantAuthorizationException;
import com.rootopathy.careos.tenancy.application.TenantAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.PermissionKey;
import com.rootopathy.careos.tenancy.domain.TenantAuthorizationRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean referencePolicyEnabled;

    public PostgresTenantAuthorizationOperations(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${careos.authorization.reference-policy-enabled:false}")
                    boolean referencePolicyEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.referencePolicyEnabled = referencePolicyEnabled;
    }

    @Override
    public <T> T execute(
            TenantAuthorizationRequest request,
            Function<AuthorizedTenantContext, T> authorizedWork) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(authorizedWork, "authorizedWork");
        return transactionTemplate.execute(status -> {
            bindRequest(request);
            var nowInstant = clock.instant();
            var now = Timestamp.from(nowInstant);
            var operation = loadOperationPolicy(request);
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
                          ON roles.role_key = memberships.role_key
                        JOIN authorization_role_permissions role_permissions
                          ON role_permissions.role_key = roles.role_key
                        JOIN authorization_permissions permissions
                          ON permissions.permission_key = role_permissions.permission_key
                        WHERE memberships.organization_id = ?
                          AND memberships.user_id = ?
                          AND memberships.status = 'active'
                          AND memberships.effective_from <= ?
                          AND (memberships.effective_to IS NULL OR memberships.effective_to > ?)
                          AND permissions.permission_key = ?
                          AND permissions.registry_version = ?
                          AND roles.registry_version = ?
                          AND roles.interactive
                          AND (roles.status = 'active' OR (? AND roles.status = 'reference'))
                          AND (permissions.status = 'active' OR (? AND permissions.status = 'reference'))
                    )
                    """,
                    Boolean.class,
                    request.organizationId(),
                    request.actor().actorId(),
                    now,
                    now,
                    operation.permissionKey().value(),
                    operation.registryVersion(),
                    operation.registryVersion(),
                    referencePolicyEnabled,
                    referencePolicyEnabled));
            if (!permissionGranted) {
                throw denied(operation);
            }
            enforceRequirements(operation, request, nowInstant);

            var context = new AuthorizedTenantContext(
                    request.organizationId(),
                    request.actor().actorId(),
                    request.actor().purpose(),
                    request.actor().correlationId());
            return authorizedWork.apply(context);
        });
    }

    private OperationPolicy loadOperationPolicy(TenantAuthorizationRequest request) {
        var policies = jdbcTemplate.query(
                """
                SELECT operations.permission_key,
                       operations.denial_mode,
                       operations.reason_required,
                       operations.recent_authentication_required,
                       operations.recent_authentication_max_age_seconds,
                       operations.maximum_future_skew_seconds,
                       operations.mfa_required,
                       operations.maker_checker_required,
                       operations.registry_version
                FROM authorization_operations operations
                JOIN authorization_permissions permissions
                  ON permissions.permission_key = operations.permission_key
                 AND permissions.registry_version = operations.registry_version
                WHERE operations.operation_key = ?
                  AND (operations.status = 'active' OR (? AND operations.status = 'reference'))
                  AND (permissions.status = 'active' OR (? AND permissions.status = 'reference'))
                """,
                (result, rowNumber) -> new OperationPolicy(
                        new PermissionKey(result.getString("permission_key")),
                        "hidden".equals(result.getString("denial_mode")),
                        result.getBoolean("reason_required"),
                        result.getBoolean("recent_authentication_required"),
                        result.getObject("recent_authentication_max_age_seconds", Integer.class),
                        result.getObject("maximum_future_skew_seconds", Integer.class),
                        result.getBoolean("mfa_required"),
                        result.getBoolean("maker_checker_required"),
                        result.getString("registry_version")),
                request.requiredOperation().value(),
                referencePolicyEnabled,
                referencePolicyEnabled);
        if (policies.size() != 1) {
            throw new TenantAuthorizationException(
                    PERMISSION_DENIED,
                    "The current account does not have permission to perform this operation.");
        }
        return policies.getFirst();
    }

    private static TenantAuthorizationException denied(OperationPolicy operation) {
        if (operation.hiddenWhenDenied()) {
            return new TenantAuthorizationException(
                    MEMBERSHIP_NOT_FOUND,
                    "The resource is unavailable or is not assigned to this account.");
        }
        return new TenantAuthorizationException(
                PERMISSION_DENIED,
                "The current account does not have permission to perform this operation.");
    }

    private void enforceRequirements(
            OperationPolicy operation, TenantAuthorizationRequest request, Instant now) {
        if (operation.reasonRequired() && request.reason() == null) {
            throw new TenantAuthorizationException(
                    REASON_REQUIRED,
                    "A reason is required to perform this operation.");
        }
        if (operation.recentAuthenticationRequired()) {
            var recentAuthenticationAt = request.recentAuthenticationAt();
            if (recentAuthenticationAt == null
                    || recentAuthenticationAt.isBefore(
                            now.minusSeconds(operation.recentAuthenticationMaximumAgeSeconds()))
                    || recentAuthenticationAt.isAfter(
                            now.plusSeconds(operation.maximumFutureSkewSeconds()))) {
                throw new TenantAuthorizationException(
                        RECENT_AUTHENTICATION_REQUIRED,
                        "Recent authentication is required to perform this operation.");
            }
        }
        if (operation.mfaRequired()) {
            var mfaAuthenticatedAt = request.mfaAuthenticatedAt();
            if (mfaAuthenticatedAt == null
                    || mfaAuthenticatedAt.isBefore(
                            now.minusSeconds(operation.recentAuthenticationMaximumAgeSeconds()))
                    || mfaAuthenticatedAt.isAfter(
                            now.plusSeconds(operation.maximumFutureSkewSeconds()))) {
                throw new TenantAuthorizationException(
                        MFA_REQUIRED,
                        "A recent multi-factor authentication is required to perform this operation.");
            }
        }
        if (operation.makerCheckerRequired()) {
            consumeIndependentApproval(request, now);
        }
    }

    private void consumeIndependentApproval(TenantAuthorizationRequest request, Instant now) {
        var approval = request.independentApproval();
        if (approval == null || request.reason() == null) {
            throw independentApprovalRequired();
        }
        var consumed = jdbcTemplate.queryForList(
                """
                UPDATE authorization_approval_requests
                SET status = 'consumed',
                    consumed_by_user_id = ?,
                    consumed_at = ?,
                    consumption_correlation_id = ?,
                    consumed_idempotency_key = ?,
                    updated_at = ?,
                    lock_version = lock_version + 1
                WHERE id = ?
                  AND organization_id = ?
                  AND operation_key = ?
                  AND subject_type = ?
                  AND subject_id = ?
                  AND requested_by_user_id = ?
                  AND decided_by_user_id <> ?
                  AND request_reason = ?
                  AND status = 'approved'
                  AND expires_at > ?
                RETURNING id
                """,
                UUID.class,
                request.actor().actorId(),
                Timestamp.from(now),
                request.actor().correlationId(),
                approval.idempotencyKey(),
                Timestamp.from(now),
                approval.approvalId(),
                request.organizationId(),
                request.requiredOperation().value(),
                approval.subjectType(),
                approval.subjectId(),
                request.actor().actorId(),
                request.actor().actorId(),
                request.reason(),
                Timestamp.from(now));
        if (consumed.size() == 1) {
            return;
        }
        var exactReplay = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM authorization_approval_requests
                    WHERE id = ?
                      AND organization_id = ?
                      AND operation_key = ?
                      AND subject_type = ?
                      AND subject_id = ?
                      AND requested_by_user_id = ?
                      AND decided_by_user_id <> ?
                      AND request_reason = ?
                      AND status = 'consumed'
                      AND consumed_by_user_id = ?
                      AND consumed_idempotency_key = ?
                )
                """,
                Boolean.class,
                approval.approvalId(),
                request.organizationId(),
                request.requiredOperation().value(),
                approval.subjectType(),
                approval.subjectId(),
                request.actor().actorId(),
                request.actor().actorId(),
                request.reason(),
                request.actor().actorId(),
                approval.idempotencyKey()));
        if (!exactReplay) {
            throw independentApprovalRequired();
        }
    }

    private static TenantAuthorizationException independentApprovalRequired() {
        return new TenantAuthorizationException(
                INDEPENDENT_APPROVAL_REQUIRED,
                "A current approval from a different authorized account is required.");
    }

    private void bindRequest(TenantAuthorizationRequest request) {
        setTransactionLocal("app.current_organization_id", request.organizationId().toString());
        setTransactionLocal("app.current_actor_id", request.actor().actorId().toString());
        setTransactionLocal("app.current_actor_kind", "user");
        setTransactionLocal("app.current_purpose", request.actor().purpose());
        setTransactionLocal("app.current_correlation_id", request.actor().correlationId());
        setTransactionLocal("app.current_operation_key", request.requiredOperation().value());
        setTransactionLocal(
                "app.current_authorization_reason",
                request.reason() == null ? "" : request.reason());
        setTransactionLocal(
                "app.current_approval_id",
                request.independentApproval() == null
                        ? ""
                        : request.independentApproval().approvalId().toString());
        setTransactionLocal(
                "app.reference_authorization_policy_enabled",
                Boolean.toString(referencePolicyEnabled));
    }

    private void setTransactionLocal(String setting, String value) {
        jdbcTemplate.queryForObject("select set_config(?, ?, true)", String.class, setting, value);
    }

    private record OperationPolicy(
            PermissionKey permissionKey,
            boolean hiddenWhenDenied,
            boolean reasonRequired,
            boolean recentAuthenticationRequired,
            Integer recentAuthenticationMaximumAgeSeconds,
            Integer maximumFutureSkewSeconds,
            boolean mfaRequired,
            boolean makerCheckerRequired,
            String registryVersion) {}
}
