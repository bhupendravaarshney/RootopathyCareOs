package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.OrganizationInternationalSettingsException;
import com.rootopathy.careos.administration.application.OrganizationInternationalSettingsStore;
import com.rootopathy.careos.administration.domain.InternationalSettingsFormatPreview;
import com.rootopathy.careos.administration.domain.InternationalSettingsVersion;
import com.rootopathy.careos.administration.domain.OrganizationInternationalSettings;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.infrastructure.AuthorizedTenantTransactionGuard;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Currency;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrganizationInternationalSettingsStore
        implements OrganizationInternationalSettingsStore {
    private final JdbcTemplate jdbcTemplate;

    public JdbcOrganizationInternationalSettingsStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public OrganizationInternationalSettings settings(AuthorizedTenantContext context) {
        AuthorizedTenantTransactionGuard.requireBound(jdbcTemplate, context);
        var defaults = defaults(context.organizationId());
        var evaluatedTimestamp = jdbcTemplate.queryForObject(
                "SELECT clock_timestamp()", Timestamp.class);
        if (evaluatedTimestamp == null) {
            throw new IllegalStateException(
                    "Database time was unavailable for international-settings evaluation");
        }
        var evaluatedAt = evaluatedTimestamp.toInstant();
        var stored = jdbcTemplate.query(
                """
                SELECT id, source, country_code, timezone, locale, language,
                       currency_code, week_start, effective_from, effective_to,
                       supersedes_id, status, lock_version, updated_at
                FROM organization_international_settings
                WHERE organization_id = ?
                ORDER BY effective_from DESC, lock_version DESC, id DESC
                """,
                JdbcOrganizationInternationalSettingsStore::mapStored,
                context.organizationId());
        var editable = effectivePermissions(context).contains("organization.settings.manage");
        if (stored.isEmpty()) {
            var defaultVersion = defaultVersion(defaults, null, "default");
            return new OrganizationInternationalSettings(
                    context.organizationId(),
                    editable,
                    editable,
                    defaults.lockVersion(),
                    evaluatedAt,
                    OrganizationInternationalSettings.APPROVED_WEEK_STARTS,
                    OrganizationInternationalSettings.APPROVED_IMPACT_RULES,
                    List.of(defaultVersion));
        }

        var versions = stored.stream()
                .map(version -> version(version, evaluatedAt))
                .toList();
        var revision = stored.stream()
                .mapToLong(StoredSettings::lockVersion)
                .max()
                .orElseThrow();
        var hasScheduled = versions.stream()
                .anyMatch(version -> "scheduled".equals(version.lifecycle()));
        return new OrganizationInternationalSettings(
                context.organizationId(),
                editable,
                editable && !hasScheduled,
                revision,
                evaluatedAt,
                OrganizationInternationalSettings.APPROVED_WEEK_STARTS,
                OrganizationInternationalSettings.APPROVED_IMPACT_RULES,
                versions);
    }

    @Override
    public ScheduleResult schedule(
            AuthorizedTenantContext context, SettingsDraft draft, long expectedLockVersion) {
        AuthorizedTenantTransactionGuard.requireBound(jdbcTemplate, context);
        try {
            var before = settings(context);
            if (before.lockVersion() != expectedLockVersion) {
                throw stale();
            }
            if (!before.canSchedule()) {
                throw new OrganizationInternationalSettingsException(
                        OrganizationInternationalSettingsException.Reason.SCHEDULE_CONFLICT,
                        "A future international-settings version is already scheduled.");
            }
            var current = before.versions().stream()
                    .filter(version -> Set.of("default", "active").contains(version.lifecycle()))
                    .findFirst()
                    .orElseThrow(() -> invalidTransition(
                            "The current international-settings version is unavailable."));
            var changedFields = changedFields(current, draft);
            if (changedFields.isEmpty()) {
                throw new OrganizationInternationalSettingsException(
                        OrganizationInternationalSettingsException.Reason.NO_CHANGE,
                        "At least one international setting must change.");
            }
            long nextRevision;
            try {
                nextRevision = Math.addExact(expectedLockVersion, 1L);
            } catch (ArithmeticException exception) {
                throw invalidTransition("The international-settings revision cannot advance.");
            }

            UUID predecessorId;
            if (current.settingsId() == null) {
                predecessorId = insert(
                        context,
                        "organization_default",
                        current.countryCode(),
                        current.timezone(),
                        current.locale(),
                        current.language(),
                        current.currencyCode(),
                        current.weekStart(),
                        current.effectiveFrom(),
                        draft.effectiveFrom(),
                        null,
                        "superseded",
                        nextRevision);
            } else {
                var updated = jdbcTemplate.update(
                        """
                        UPDATE organization_international_settings
                        SET effective_to = ?, status = 'superseded', lock_version = ?,
                            updated_by = ?
                        WHERE organization_id = ? AND id = ? AND lock_version = ?
                          AND effective_to IS NULL
                        """,
                        timestamp(draft.effectiveFrom()),
                        nextRevision,
                        context.actorId(),
                        context.organizationId(),
                        current.settingsId(),
                        expectedLockVersion);
                if (updated != 1) {
                    throw stale();
                }
                predecessorId = current.settingsId();
            }

            var settingsId = insert(
                    context,
                    "configured",
                    draft.countryCode(),
                    draft.timezone(),
                    draft.locale(),
                    draft.language(),
                    draft.currencyCode(),
                    draft.weekStart(),
                    draft.effectiveFrom(),
                    null,
                    predecessorId,
                    "scheduled",
                    nextRevision);
            var after = settings(context);
            if (after.lockVersion() != nextRevision) {
                throw new IllegalStateException(
                        "International-settings store returned inconsistent revision evidence");
            }
            return new ScheduleResult(after, settingsId, changedFields);
        } catch (OrganizationInternationalSettingsException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    private UUID insert(
            AuthorizedTenantContext context,
            String source,
            String countryCode,
            String timezone,
            String locale,
            String language,
            String currencyCode,
            String weekStart,
            Instant effectiveFrom,
            Instant effectiveTo,
            UUID supersedesId,
            String status,
            long lockVersion) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO organization_international_settings
                    (organization_id, source, country_code, timezone, locale,
                     language, currency_code, week_start, effective_from,
                     effective_to, supersedes_id, status, lock_version,
                     created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                UUID.class,
                context.organizationId(),
                source,
                countryCode,
                timezone,
                locale,
                language,
                currencyCode,
                weekStart,
                timestamp(effectiveFrom),
                timestamp(effectiveTo),
                supersedesId,
                status,
                lockVersion,
                context.actorId(),
                context.actorId());
    }

    private OrganizationDefaults defaults(UUID organizationId) {
        return jdbcTemplate.query(
                        """
                        SELECT id, country_code, timezone, locale, lock_version,
                               created_at, updated_at
                        FROM organizations
                        WHERE id = ?
                        """,
                        (resultSet, rowNumber) -> mapDefaults(resultSet),
                        organizationId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new OrganizationInternationalSettingsException(
                        OrganizationInternationalSettingsException.Reason.NOT_FOUND,
                        "The organization is unavailable."));
    }

    private static OrganizationDefaults mapDefaults(ResultSet resultSet) throws SQLException {
        var countryCode = resultSet.getString("country_code");
        var locale = defaultLocale(resultSet.getString("locale"), countryCode);
        var language = locale.getLanguage();
        var currency = defaultCurrency(countryCode);
        var weekStart = WeekFields.of(locale).getFirstDayOfWeek().name();
        return new OrganizationDefaults(
                resultSet.getString("timezone"),
                countryCode,
                locale.toLanguageTag(),
                language,
                currency,
                weekStart,
                resultSet.getLong("lock_version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static Locale defaultLocale(String localeTag, String countryCode) {
        if (localeTag != null && !localeTag.isBlank()) {
            try {
                var locale = new Locale.Builder().setLanguageTag(localeTag).build();
                if (!locale.getLanguage().isBlank() && !"und".equals(locale.toLanguageTag())) {
                    return locale;
                }
            } catch (IllformedLocaleException ignored) {
                // Legacy incomplete profiles use the deterministic approved default below.
            }
        }
        return new Locale.Builder().setLanguage("en").setRegion(countryCode).build();
    }

    private static String defaultCurrency(String countryCode) {
        try {
            var locale = new Locale.Builder().setRegion(countryCode).build();
            var currency = Currency.getInstance(locale);
            return currency == null ? "XXX" : currency.getCurrencyCode();
        } catch (IllegalArgumentException exception) {
            return "XXX";
        }
    }

    private static InternationalSettingsVersion defaultVersion(
            OrganizationDefaults defaults, Instant effectiveTo, String lifecycle) {
        return new InternationalSettingsVersion(
                null,
                "organization_default",
                defaults.countryCode(),
                defaults.timezone(),
                defaults.locale(),
                defaults.language(),
                defaults.currencyCode(),
                defaults.weekStart(),
                defaults.createdAt(),
                effectiveTo,
                lifecycle,
                null,
                defaults.lockVersion(),
                defaults.updatedAt(),
                InternationalSettingsFormatPreview.create(
                        defaults.locale(), defaults.timezone(), defaults.currencyCode()));
    }

    private static InternationalSettingsVersion version(
            StoredSettings stored, Instant evaluatedAt) {
        String lifecycle;
        if (stored.effectiveFrom().isAfter(evaluatedAt)) {
            lifecycle = "scheduled";
        } else if (stored.effectiveTo() == null || stored.effectiveTo().isAfter(evaluatedAt)) {
            lifecycle = "active";
        } else {
            lifecycle = "superseded";
        }
        return new InternationalSettingsVersion(
                stored.id(),
                stored.source(),
                stored.countryCode(),
                stored.timezone(),
                stored.locale(),
                stored.language(),
                stored.currencyCode(),
                stored.weekStart(),
                stored.effectiveFrom(),
                stored.effectiveTo(),
                lifecycle,
                stored.supersedesId(),
                stored.lockVersion(),
                stored.updatedAt(),
                InternationalSettingsFormatPreview.create(
                        stored.locale(), stored.timezone(), stored.currencyCode()));
    }

    private static StoredSettings mapStored(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new StoredSettings(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source"),
                resultSet.getString("country_code"),
                resultSet.getString("timezone"),
                resultSet.getString("locale"),
                resultSet.getString("language"),
                resultSet.getString("currency_code"),
                resultSet.getString("week_start"),
                resultSet.getTimestamp("effective_from").toInstant(),
                nullableInstant(resultSet, "effective_to"),
                resultSet.getObject("supersedes_id", UUID.class),
                resultSet.getString("status"),
                resultSet.getLong("lock_version"),
                resultSet.getTimestamp("updated_at").toInstant());
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

    private static List<String> changedFields(
            InternationalSettingsVersion current, SettingsDraft draft) {
        var fields = new ArrayList<String>();
        if (!current.countryCode().equals(draft.countryCode())) {
            fields.add("countryCode");
        }
        if (!current.currencyCode().equals(draft.currencyCode())) {
            fields.add("currencyCode");
        }
        if (!current.language().equals(draft.language())) {
            fields.add("language");
        }
        if (!current.locale().equals(draft.locale())) {
            fields.add("locale");
        }
        if (!current.timezone().equals(draft.timezone())) {
            fields.add("timezone");
        }
        if (!current.weekStart().equals(draft.weekStart())) {
            fields.add("weekStart");
        }
        return List.copyOf(fields);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant nullableInstant(ResultSet resultSet, String column)
            throws SQLException {
        var timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static OrganizationInternationalSettingsException translate(
            DataAccessException exception) {
        var postgres = postgres(exception);
        if (postgres != null) {
            var message = postgres.getMessage() == null ? "" : postgres.getMessage();
            if ("23505".equals(postgres.getSQLState())
                    && message.contains("one_pending")) {
                return new OrganizationInternationalSettingsException(
                        OrganizationInternationalSettingsException.Reason.SCHEDULE_CONFLICT,
                        "A future international-settings version is already scheduled.");
            }
            if ("23P01".equals(postgres.getSQLState())
                    || message.contains("effective ranges overlap")) {
                return new OrganizationInternationalSettingsException(
                        OrganizationInternationalSettingsException.Reason.EFFECTIVE_OVERLAP,
                        "The international-settings effective range overlaps another version.");
            }
            if ("23514".equals(postgres.getSQLState())
                    || "42501".equals(postgres.getSQLState())) {
                return invalidTransition(
                        "The requested international-settings transition is invalid.");
            }
        }
        throw exception;
    }

    private static SQLException postgres(Throwable exception) {
        for (var current = exception; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        return null;
    }

    private static OrganizationInternationalSettingsException stale() {
        return new OrganizationInternationalSettingsException(
                OrganizationInternationalSettingsException.Reason.STALE_REVISION,
                "International settings changed. Reload them before continuing.");
    }

    private static OrganizationInternationalSettingsException invalidTransition(String detail) {
        return new OrganizationInternationalSettingsException(
                OrganizationInternationalSettingsException.Reason.INVALID_TRANSITION, detail);
    }

    private record OrganizationDefaults(
            String timezone,
            String countryCode,
            String locale,
            String language,
            String currencyCode,
            String weekStart,
            long lockVersion,
            Instant createdAt,
            Instant updatedAt) {}

    private record StoredSettings(
            UUID id,
            String source,
            String countryCode,
            String timezone,
            String locale,
            String language,
            String currencyCode,
            String weekStart,
            Instant effectiveFrom,
            Instant effectiveTo,
            UUID supersedesId,
            String status,
            long lockVersion,
            Instant updatedAt) {}
}
