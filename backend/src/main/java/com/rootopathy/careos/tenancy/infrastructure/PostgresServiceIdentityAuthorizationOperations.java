package com.rootopathy.careos.tenancy.infrastructure;

import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationException;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationException.Reason;
import com.rootopathy.careos.tenancy.application.ServiceIdentityAuthorizationOperations;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.domain.ServiceIdentityAuthorizationRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class PostgresServiceIdentityAuthorizationOperations
        implements ServiceIdentityAuthorizationOperations {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;
    private final boolean referencePolicyEnabled;
    private final byte[] credentialPepper;

    public PostgresServiceIdentityAuthorizationOperations(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ServiceIdentityProperties properties,
            @Value("${careos.authorization.reference-policy-enabled:false}")
                    boolean referencePolicyEnabled) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.enabled = properties.enabled();
        this.referencePolicyEnabled = referencePolicyEnabled;
        this.credentialPepper = properties.credentialPepper().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <T> T execute(
            ServiceIdentityAuthorizationRequest request,
            Function<AuthorizedTenantContext, T> authorizedWork) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(authorizedWork, "authorizedWork");
        if (!enabled) {
            throw new ServiceIdentityAuthorizationException(Reason.DISABLED);
        }

        return transactionTemplate.execute(status -> {
            bindCandidate(request);
            var identities = jdbcTemplate.queryForList(
                    "select service_identity_id from careos_authorize_service_identity(?, ?, ?, ?)",
                    UUID.class,
                    request.organizationId(),
                    digest(request.presentedCredential()),
                    request.purpose(),
                    request.requiredOperation().value());
            if (identities.size() != 1) {
                throw new ServiceIdentityAuthorizationException(
                        Reason.CREDENTIAL_OR_PERMISSION_DENIED);
            }
            var identityId = identities.getFirst();
            setTransactionLocal("app.current_actor_id", identityId.toString());
            setTransactionLocal("app.current_actor_kind", "service");
            return authorizedWork.apply(new AuthorizedTenantContext(
                    request.organizationId(),
                    identityId,
                    request.purpose(),
                    request.correlationId()));
        });
    }

    private void bindCandidate(ServiceIdentityAuthorizationRequest request) {
        setTransactionLocal("app.current_organization_id", request.organizationId().toString());
        setTransactionLocal("app.current_actor_id", "");
        setTransactionLocal("app.current_actor_kind", "service");
        setTransactionLocal("app.current_purpose", request.purpose());
        setTransactionLocal("app.current_correlation_id", request.correlationId());
        setTransactionLocal("app.current_operation_key", request.requiredOperation().value());
        setTransactionLocal("app.current_authorization_reason", "");
        setTransactionLocal(
                "app.reference_authorization_policy_enabled",
                Boolean.toString(referencePolicyEnabled));
    }

    private String digest(String value) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(credentialPepper, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Required service credential digest algorithm is unavailable", exception);
        }
    }

    private void setTransactionLocal(String setting, String value) {
        jdbcTemplate.queryForObject("select set_config(?, ?, true)", String.class, setting, value);
    }
}
