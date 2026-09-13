package com.rootopathy.careos.tenancy.infrastructure;

import com.rootopathy.careos.tenancy.application.ActorTransactionOperations;
import com.rootopathy.careos.tenancy.domain.AuthenticatedActorContext;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class PostgresActorTransactionOperations implements ActorTransactionOperations {
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public PostgresActorTransactionOperations(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setReadOnly(true);
    }

    @Override
    public <T> T execute(AuthenticatedActorContext context, Supplier<T> work) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(work, "work");
        return transactionTemplate.execute(status -> {
            // The empty organization is intentional: actor-discovery RLS is a separate, read-only mode.
            setTransactionLocal("app.current_organization_id", "");
            setTransactionLocal("app.current_actor_id", context.actorId().toString());
            setTransactionLocal("app.current_purpose", context.purpose());
            setTransactionLocal("app.current_correlation_id", context.correlationId());
            return work.get();
        });
    }

    private void setTransactionLocal(String setting, String value) {
        jdbcTemplate.queryForObject("select set_config(?, ?, true)", String.class, setting, value);
    }
}
