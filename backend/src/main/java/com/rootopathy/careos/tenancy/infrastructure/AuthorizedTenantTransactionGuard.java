package com.rootopathy.careos.tenancy.infrastructure;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Verifies that tenant-sensitive infrastructure is executing inside its authorized transaction. */
public final class AuthorizedTenantTransactionGuard {
    private AuthorizedTenantTransactionGuard() {}

    public static void requireBound(
            JdbcTemplate jdbcTemplate, AuthorizedTenantContext context) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        Objects.requireNonNull(context, "context");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An active tenant transaction is required");
        }
        requireMatchingSettings(jdbcTemplate, context);
    }

    public static void requireWritable(
            JdbcTemplate jdbcTemplate, AuthorizedTenantContext context) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        Objects.requireNonNull(context, "context");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("An active writable tenant transaction is required");
        }
        requireMatchingSettings(jdbcTemplate, context);
    }

    private static void requireMatchingSettings(
            JdbcTemplate jdbcTemplate, AuthorizedTenantContext context) {
        Map<String, Object> settings = jdbcTemplate.queryForMap("""
                SELECT current_setting('app.current_organization_id', true) AS organization_id,
                       current_setting('app.current_actor_id', true) AS actor_id,
                       current_setting('app.current_purpose', true) AS purpose,
                       current_setting('app.current_correlation_id', true) AS correlation_id
                """);
        if (!context.organizationId().toString().equals(settings.get("organization_id"))
                || !context.actorId().toString().equals(settings.get("actor_id"))
                || !context.purpose().equals(settings.get("purpose"))
                || !context.correlationId().equals(settings.get("correlation_id"))) {
            throw new IllegalStateException("Tenant transaction context does not match authorization");
        }
    }
}
