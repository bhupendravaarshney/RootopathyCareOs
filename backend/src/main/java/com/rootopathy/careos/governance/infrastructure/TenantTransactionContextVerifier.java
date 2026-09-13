package com.rootopathy.careos.governance.infrastructure;

import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.infrastructure.AuthorizedTenantTransactionGuard;
import org.springframework.jdbc.core.JdbcTemplate;

final class TenantTransactionContextVerifier {
    private TenantTransactionContextVerifier() {}

    static void requireAuthorizedWriteTransaction(
            JdbcTemplate jdbcTemplate, AuthorizedTenantContext context) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
    }
}
