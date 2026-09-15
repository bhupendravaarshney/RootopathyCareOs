package com.rootopathy.careos.config;

import com.rootopathy.careos.identity.infrastructure.config.IdentitySecurityProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("production")
final class ProductionConfigurationGuard {
    private static final Set<String> NON_PRODUCTION_PROFILES = Set.of("local", "test");
    private static final Set<String> DOCUMENTED_NON_PRODUCTION_SECRETS = Set.of(
            "careos-app-local-only",
            "careos-migrator-local-only",
            "careos-app-test-only",
            "careos-migrator-test-only",
            "CareOS-Local-Token-Pepper-Change-Me",
            "CareOS-Test-Token-Pepper-Never-Production",
            "CareOS-Local-Service-Credential-Pepper-Change-Me",
            "CareOS-Test-Service-Credential-Pepper-Never-Production",
            "Q2FyZU9TLUxvY2FsLU1GQS1LZXktTXVzdC1DaGFuZ2U=",
            "careos-local",
            "careos-local-change-me");

    ProductionConfigurationGuard(
            IdentitySecurityProperties securityProperties, Environment environment) {
        validate(securityProperties, environment);
    }

    static void validate(
            IdentitySecurityProperties securityProperties, Environment environment) {
        var violations = new ArrayList<String>();
        var activeProfiles = Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (!activeProfiles.contains("production")) {
            violations.add("the production profile must be active");
        }
        if (activeProfiles.stream().anyMatch(NON_PRODUCTION_PROFILES::contains)) {
            violations.add("local and test profiles cannot be combined with production");
        }

        var canonicalOrigins = securityProperties.allowedOrigins().stream()
                .map(origin -> secureOrigin(origin, "allowed browser origin", violations))
                .toList();
        var allowedOrigins = canonicalOrigins.stream()
                .filter(origin -> origin != null)
                .collect(Collectors.toUnmodifiableSet());
        var validOriginCount = canonicalOrigins.stream().filter(origin -> origin != null).count();
        if (allowedOrigins.size() != validOriginCount) {
            violations.add("allowed browser origins must not contain duplicates");
        }
        var applicationOrigin = secureOrigin(
                securityProperties.applicationBaseUrl().toString(),
                "application base URL",
                violations);
        if (applicationOrigin != null && !allowedOrigins.contains(applicationOrigin)) {
            violations.add("the application base URL must be an allowed browser origin");
        }

        requireTrue(environment, "server.servlet.session.cookie.secure", "secure session cookies", violations);
        requireTrue(environment, "spring.data.redis.ssl.enabled", "Redis TLS", violations);
        requireTrue(environment, "spring.mail.properties.mail.smtp.auth", "SMTP authentication", violations);
        requireTrue(
                environment,
                "spring.mail.properties.mail.smtp.starttls.enable",
                "SMTP STARTTLS",
                violations);
        requireTrue(
                environment,
                "spring.mail.properties.mail.smtp.starttls.required",
                "mandatory SMTP STARTTLS",
                violations);
        requireTrue(
                environment,
                "spring.mail.properties.mail.smtp.ssl.checkserveridentity",
                "SMTP server identity verification",
                violations);

        requireVerifiedPostgresTls(
                environment.getProperty("spring.datasource.url"), "application database URL", violations);
        requireVerifiedPostgresTls(
                environment.getProperty("spring.flyway.url"), "migration database URL", violations);
        requireNonProductionSecretAbsent(
                environment, "spring.datasource.password", "application database password", violations);
        requireNonProductionSecretAbsent(
                environment, "spring.flyway.password", "migration database password", violations);
        requireNonProductionSecretAbsent(
                environment, "spring.data.redis.password", "Redis password", violations);
        requireNonProductionSecretAbsent(
                environment, "spring.mail.password", "SMTP password", violations);
        requireText(environment, "spring.datasource.username", "application database username", violations);
        requireText(environment, "spring.flyway.user", "migration database username", violations);
        requireText(environment, "spring.data.redis.host", "Redis host", violations);
        requireText(environment, "spring.data.redis.username", "Redis username", violations);
        requireText(environment, "spring.mail.host", "SMTP host", violations);
        requireText(environment, "spring.mail.username", "SMTP username", violations);
        if (DOCUMENTED_NON_PRODUCTION_SECRETS.contains(securityProperties.tokenPepper())) {
            violations.add("the token pepper must not use documented local or test material");
        }
        if (DOCUMENTED_NON_PRODUCTION_SECRETS.contains(securityProperties.mfaEncryptionKey())) {
            violations.add("the MFA encryption key must not use documented local or test material");
        } else if (containsOnlyOneByteValue(securityProperties.mfaEncryptionKey())) {
            violations.add("the MFA encryption key must not use trivial repeated-byte material");
        }

        if (environment.getProperty("careos.storage.s3.allow-http", Boolean.class, false)) {
            violations.add("the S3 document-storage HTTP override must be disabled");
        }
        if (environment.getProperty(
                "careos.storage.s3.create-bucket-if-missing", Boolean.class, false)) {
            violations.add("production must not create document buckets at runtime");
        }
        if (environment.getProperty(
                "careos.authorization.reference-policy-enabled", Boolean.class, false)) {
            violations.add("the provisional reference authorization policy must be disabled");
        }
        if (environment.getProperty("careos.invitations.enabled", Boolean.class, false)) {
            violations.add(
                    "governed invitations must remain disabled until the reference policy is owner-approved");
        }
        if (environment.getProperty("careos.service-identities.enabled", Boolean.class, false)) {
            requireNonProductionSecretAbsent(
                    environment,
                    "careos.service-identities.credential-pepper",
                    "service identity credential pepper",
                    violations);
        }
        if (environment.getProperty("careos.mfa-administration.enabled", Boolean.class, false)) {
            violations.add(
                    "MFA administration must remain disabled until the reference maker-checker policy is owner-approved");
        }
        var s3Enabled = environment.getProperty("careos.storage.s3.enabled", Boolean.class, false);
        if (s3Enabled) {
            requireNonProductionSecretAbsent(
                    environment, "careos.storage.s3.access-key", "S3 access key", violations);
            requireNonProductionSecretAbsent(
                    environment, "careos.storage.s3.secret-key", "S3 secret key", violations);
        }
        if (environment.getProperty(
                "careos.documents.promotion.enabled", Boolean.class, false)) {
            if (!s3Enabled) {
                violations.add("document promotion requires private S3 document storage");
            }
            requireText(
                    environment,
                    "careos.documents.promotion.policy-key",
                    "document promotion policy key",
                    violations);
            requireText(
                    environment,
                    "careos.documents.promotion.accepted-scanner-keys",
                    "document promotion scanner allow-list",
                    violations);
            requireText(
                    environment,
                    "careos.documents.promotion.maximum-scan-age",
                    "document promotion maximum scan age",
                    violations);
        }
        if (environment.getProperty(
                "careos.documents.signed-access.enabled", Boolean.class, false)) {
            if (!s3Enabled) {
                violations.add("signed document access requires private S3 document storage");
            }
            requireText(
                    environment,
                    "careos.documents.signed-access.policy-key",
                    "signed document access policy key",
                    violations);
            requireText(
                    environment,
                    "careos.documents.signed-access.accepted-purposes",
                    "signed document access purpose allow-list",
                    violations);
            requireText(
                    environment,
                    "careos.documents.signed-access.maximum-ttl",
                    "signed document access maximum TTL",
                    violations);
            requireText(
                    environment,
                    "careos.documents.signed-access.maximum-authorization-age",
                    "signed document access maximum authorization age",
                    violations);
        }
        if (environment.getProperty(
                "careos.documents.retention.enabled", Boolean.class, false)) {
            if (!s3Enabled) {
                violations.add("document retention requires private S3 document storage");
            }
            requireText(
                    environment,
                    "careos.documents.retention.policy-key",
                    "document retention policy key",
                    violations);
            requireText(
                    environment,
                    "careos.documents.retention.accepted-purposes",
                    "document retention purpose allow-list",
                    violations);
            requireText(
                    environment,
                    "careos.documents.retention.minimum-retention",
                    "document retention minimum duration",
                    violations);
            requireText(
                    environment,
                    "careos.documents.retention.maximum-retention",
                    "document retention maximum duration",
                    violations);
            requireText(
                    environment,
                    "careos.documents.retention.maximum-authorization-age",
                    "document retention maximum authorization age",
                    violations);
        }

        if (environment.getProperty("management.tracing.export.otlp.enabled", Boolean.class, false)) {
            requireSecureEndpoint(
                    environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"),
                    "OTLP tracing endpoint",
                    violations);
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Production configuration rejected: " + String.join("; ", violations));
        }
    }

    private static String secureOrigin(
            String value, String description, ArrayList<String> violations) {
        try {
            var uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getPort() == 443
                    || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                violations.add(description + " must be a canonical HTTPS origin");
                return null;
            }
            var canonical = new URI(
                            "https",
                            null,
                            uri.getHost().toLowerCase(Locale.ROOT),
                            uri.getPort(),
                            null,
                            null,
                            null)
                    .toString();
            if (!canonical.equals(value)) {
                violations.add(description + " must be a canonical HTTPS origin");
                return null;
            }
            return canonical;
        } catch (URISyntaxException | IllegalArgumentException exception) {
            violations.add(description + " must be a canonical HTTPS origin");
            return null;
        }
    }

    private static void requireTrue(
            Environment environment,
            String property,
            String description,
            ArrayList<String> violations) {
        if (!environment.getProperty(property, Boolean.class, false)) {
            violations.add(description + " must be enabled");
        }
    }

    private static void requireVerifiedPostgresTls(
            String value, String description, ArrayList<String> violations) {
        if (value == null
                || !value.startsWith("jdbc:postgresql://")
                || !value.toLowerCase(Locale.ROOT).matches(".*[?&]sslmode=verify-full(?:&.*)?$")
                || value.toLowerCase(Locale.ROOT).matches(".*[?&](?:user|password)=[^&]*.*")) {
            violations.add(
                    description
                            + " must use PostgreSQL sslmode=verify-full and keep credentials in separate properties");
        }
    }

    private static void requireText(
            Environment environment,
            String property,
            String description,
            ArrayList<String> violations) {
        var value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            violations.add(description + " is required");
        }
    }

    private static void requireNonProductionSecretAbsent(
            Environment environment,
            String property,
            String description,
            ArrayList<String> violations) {
        var value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            violations.add(description + " is required");
        } else if (DOCUMENTED_NON_PRODUCTION_SECRETS.contains(value)) {
            violations.add(description + " must not use documented local or test material");
        }
    }

    private static void requireSecureEndpoint(
            String value, String description, ArrayList<String> violations) {
        try {
            var uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                violations.add(description + " must use an HTTPS URL without credentials");
            }
        } catch (URISyntaxException | IllegalArgumentException | NullPointerException exception) {
            violations.add(description + " must use an HTTPS URL without credentials");
        }
    }

    private static boolean containsOnlyOneByteValue(String encodedKey) {
        var decoded = Base64.getDecoder().decode(encodedKey);
        try {
            for (int index = 1; index < decoded.length; index++) {
                if (decoded[index] != decoded[0]) {
                    return false;
                }
            }
            return true;
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }
}
