package com.rootopathy.careos.identity.infrastructure.persistence;

import com.rootopathy.careos.identity.application.IdentitySecurityService;
import com.rootopathy.careos.identity.application.PasswordHashingPort;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
public class LocalIdentityBootstrap implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalIdentityBootstrap.class);
    private static final UUID LOCAL_ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");

    private final JdbcTemplate jdbcTemplate;
    private final PasswordHashingPort passwordHasher;
    private final String email;
    private final String password;

    public LocalIdentityBootstrap(
            JdbcTemplate jdbcTemplate,
            PasswordHashingPort passwordHasher,
            @Value("${careos.bootstrap-admin.email}") String email,
            @Value("${careos.bootstrap-admin.password}") String password) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordHasher = passwordHasher;
        this.email = IdentitySecurityService.normalizeEmail(email);
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        var userId = jdbcTemplate
                .queryForList("select id from users where lower(email) = ?", UUID.class, email)
                .stream()
                .findFirst()
                .orElseGet(this::createUser);
        var credentialCount = jdbcTemplate.queryForObject(
                "select count(*) from password_credentials where user_id = ?", Integer.class, userId);
        if (credentialCount != null && credentialCount == 0) {
            jdbcTemplate.update(
                    "insert into password_credentials (user_id, password_hash) values (?, ?)",
                    userId,
                    passwordHasher.hash(password));
        }
        bindLocalBootstrapContext(userId);
        jdbcTemplate.update(
                """
                INSERT INTO organization_memberships
                    (organization_id, user_id, role_key, status)
                VALUES (?, ?, 'local_bootstrap', 'active')
                ON CONFLICT (organization_id, user_id, role_key) DO NOTHING
                """,
                LOCAL_ORGANIZATION_ID,
                userId);
        LOGGER.info("Local persisted bootstrap identity is available for {}", email);
    }

    private void bindLocalBootstrapContext(UUID userId) {
        jdbcTemplate.queryForObject(
                "select set_config('app.current_organization_id', ?, true)",
                String.class,
                LOCAL_ORGANIZATION_ID.toString());
        jdbcTemplate.queryForObject(
                "select set_config('app.current_actor_id', ?, true)", String.class, userId.toString());
        jdbcTemplate.queryForObject(
                "select set_config('app.current_purpose', 'local-bootstrap', true)", String.class);
        jdbcTemplate.queryForObject(
                "select set_config('app.current_correlation_id', 'local-bootstrap', true)", String.class);
    }

    private UUID createUser() {
        var userId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO users (id, email, display_name, status)
                VALUES (?, ?, 'Local Organization Owner', 'active')
                """,
                userId,
                email);
        return userId;
    }
}
