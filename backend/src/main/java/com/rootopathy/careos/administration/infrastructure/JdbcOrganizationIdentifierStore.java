package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.OrganizationIdentifierException;
import com.rootopathy.careos.administration.application.OrganizationIdentifierStore;
import com.rootopathy.careos.administration.domain.OrganizationIdentifier;
import com.rootopathy.careos.administration.domain.OrganizationIdentifierCollection;
import com.rootopathy.careos.administration.domain.OrganizationIdentifierType;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.infrastructure.AuthorizedTenantTransactionGuard;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrganizationIdentifierStore implements OrganizationIdentifierStore {
    private static final String IDENTIFIER_PROJECTION = """
            SELECT identifiers.id, identifiers.identifier_type,
                   types.display_name AS type_display_name,
                   identifiers.assigning_authority, identifiers.value_normalized,
                   identifiers.jurisdiction_country_code,
                   identifiers.verification_status,
                   identifiers.verification_evidence_reference,
                   identifiers.is_primary, identifiers.issue_date,
                   identifiers.expiry_date, identifiers.effective_from,
                   identifiers.effective_to, identifiers.supersedes_id, identifiers.status,
                   identifiers.lock_version, identifiers.created_at,
                   identifiers.updated_at,
                   (NOT identifiers.is_primary OR NOT types.primary_required OR EXISTS (
                       SELECT 1
                       FROM organization_identifiers replacement
                       WHERE replacement.organization_id = identifiers.organization_id
                         AND replacement.id <> identifiers.id
                         AND replacement.identifier_type = identifiers.identifier_type
                         AND replacement.is_primary
                         AND replacement.status IN ('verified', 'active')
                         AND replacement.effective_from <= clock_timestamp()
                         AND (replacement.effective_to IS NULL
                              OR replacement.effective_to > clock_timestamp())
                         AND (replacement.expiry_date IS NULL
                              OR replacement.expiry_date >=
                                 (clock_timestamp() AT TIME ZONE organizations.timezone)::date)
                   )) AS revocable
            FROM organization_identifiers identifiers
            JOIN organization_identifier_types types
              ON types.identifier_type = identifiers.identifier_type
            JOIN organizations ON organizations.id = identifiers.organization_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcOrganizationIdentifierStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public OrganizationIdentifierCollection identifiers(AuthorizedTenantContext context) {
        AuthorizedTenantTransactionGuard.requireBound(jdbcTemplate, context);
        var permissions = effectivePermissions(context);
        var canManage = permissions.contains("organization.identifier.manage");
        var canVerify = permissions.contains("organization.identifier.verify");
        var countryCode = jdbcTemplate.queryForObject(
                "SELECT country_code FROM organizations WHERE id = ?",
                String.class,
                context.organizationId());
        if (countryCode == null) {
            throw notFound();
        }
        var types = jdbcTemplate.query(
                """
                SELECT identifier_type, display_name, jurisdiction_country_code,
                       primary_required
                FROM organization_identifier_types
                WHERE status = 'active'
                  AND registry_version = 'm1-candidate-1'
                  AND (jurisdiction_country_code IS NULL
                       OR jurisdiction_country_code = ?)
                ORDER BY display_name, identifier_type
                """,
                (resultSet, rowNumber) -> new OrganizationIdentifierType(
                        resultSet.getString("identifier_type"),
                        resultSet.getString("display_name"),
                        resultSet.getString("jurisdiction_country_code"),
                        resultSet.getBoolean("primary_required")),
                countryCode);
        var stateFilter = canManage || canVerify
                ? ""
                : " AND identifiers.status IN ('verified', 'active')";
        var items = jdbcTemplate.query(
                IDENTIFIER_PROJECTION
                        + " WHERE identifiers.organization_id = ?"
                        + stateFilter
                        + " ORDER BY identifiers.is_primary DESC, types.display_name, "
                        + "identifiers.created_at DESC, identifiers.id DESC",
                (resultSet, rowNumber) -> mapIdentifier(
                        resultSet, canManage, canVerify, canVerify),
                context.organizationId());
        return new OrganizationIdentifierCollection(
                context.organizationId(), canManage && !types.isEmpty(), types, items);
    }

    @Override
    public OrganizationIdentifier create(
            AuthorizedTenantContext context, IdentifierDraft draft) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        validateType(context, draft);
        try {
            var identifierId = jdbcTemplate.queryForObject(
                    """
                    INSERT INTO organization_identifiers
                        (organization_id, identifier_type, assigning_authority,
                         value_normalized, jurisdiction_country_code, is_primary,
                         issue_date, expiry_date, effective_from, effective_to,
                         created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                    UUID.class,
                    context.organizationId(),
                    draft.identifierType(),
                    draft.assigningAuthority(),
                    draft.value(),
                    draft.jurisdictionCountryCode(),
                    draft.isPrimary(),
                    draft.issueDate(),
                    draft.expiryDate(),
                    java.sql.Timestamp.from(draft.effectiveFrom()),
                    sqlTimestamp(draft.effectiveTo()),
                    context.actorId(),
                    context.actorId());
            if (identifierId == null) {
                throw new IllegalStateException("Identifier creation omitted its identifier");
            }
            return identifier(context, identifierId);
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    @Override
    public OrganizationIdentifier update(
            AuthorizedTenantContext context,
            UUID identifierId,
            IdentifierDraft draft,
            long expectedLockVersion) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        var current = current(identifierId);
        requireRevision(current, expectedLockVersion);
        if (!"draft".equals(current.status())) {
            throw invalidTransition("Only a draft organization identifier can be edited.");
        }
        validateType(context, draft);
        if (sameDraft(current, draft)) {
            throw new OrganizationIdentifierException(
                    OrganizationIdentifierException.Reason.NO_CHANGES,
                    "Change at least one organization identifier field before saving.");
        }
        try {
            var changed = jdbcTemplate.update(
                    """
                    UPDATE organization_identifiers
                    SET identifier_type = ?, assigning_authority = ?, value_normalized = ?,
                        jurisdiction_country_code = ?, is_primary = ?, issue_date = ?,
                        expiry_date = ?, effective_from = ?, effective_to = ?,
                        updated_by = ?, lock_version = lock_version + 1
                    WHERE id = ? AND organization_id = ? AND lock_version = ?
                    """,
                    draft.identifierType(),
                    draft.assigningAuthority(),
                    draft.value(),
                    draft.jurisdictionCountryCode(),
                    draft.isPrimary(),
                    draft.issueDate(),
                    draft.expiryDate(),
                    java.sql.Timestamp.from(draft.effectiveFrom()),
                    sqlTimestamp(draft.effectiveTo()),
                    context.actorId(),
                    identifierId,
                    context.organizationId(),
                    expectedLockVersion);
            if (changed != 1) {
                throw stale();
            }
            return identifier(context, identifierId);
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    @Override
    public OrganizationIdentifier verify(
            AuthorizedTenantContext context,
            UUID identifierId,
            String evidenceReference,
            long expectedLockVersion) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        var current = current(identifierId);
        requireRevision(current, expectedLockVersion);
        if (!"draft".equals(current.status())) {
            throw invalidTransition("Only a draft organization identifier can be verified.");
        }
        try {
            var changed = jdbcTemplate.update(
                    """
                    UPDATE organization_identifiers
                    SET status = 'verified', verification_status = 'verified',
                        verification_evidence_reference = ?, verified_by = ?,
                        updated_by = ?, lock_version = lock_version + 1
                    WHERE id = ? AND organization_id = ? AND lock_version = ?
                    """,
                    evidenceReference,
                    context.actorId(),
                    context.actorId(),
                    identifierId,
                    context.organizationId(),
                    expectedLockVersion);
            if (changed != 1) {
                throw stale();
            }
            return identifier(context, identifierId);
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    @Override
    public RevokeResult revoke(
            AuthorizedTenantContext context,
            UUID identifierId,
            long expectedLockVersion) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        var current = current(identifierId);
        requireRevision(current, expectedLockVersion);
        if (!Set.of("verified", "active").contains(current.status())) {
            throw invalidTransition("Only a verified or active organization identifier can be revoked.");
        }
        try {
            var changed = jdbcTemplate.update(
                    """
                    UPDATE organization_identifiers
                    SET status = 'revoked', updated_by = ?, lock_version = lock_version + 1
                    WHERE id = ? AND organization_id = ? AND lock_version = ?
                    """,
                    context.actorId(),
                    identifierId,
                    context.organizationId(),
                    expectedLockVersion);
            if (changed != 1) {
                throw stale();
            }
            return new RevokeResult(identifier(context, identifierId), current.status());
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    @Override
    public SupersedeResult supersede(
            AuthorizedTenantContext context,
            UUID identifierId,
            long expectedLockVersion,
            UUID replacementId,
            long expectedReplacementLockVersion) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        var locked = currents(identifierId, replacementId);
        var current = locked.stream()
                .filter(identifier -> identifier.identifierId().equals(identifierId))
                .findFirst()
                .orElseThrow(JdbcOrganizationIdentifierStore::notFound);
        var replacement = locked.stream()
                .filter(identifier -> identifier.identifierId().equals(replacementId))
                .findFirst()
                .orElseThrow(JdbcOrganizationIdentifierStore::notFound);
        requireRevision(current, expectedLockVersion);
        requireRevision(replacement, expectedReplacementLockVersion);
        if (!Set.of("verified", "active").contains(current.status())) {
            throw invalidTransition(
                    "Only a verified or active organization identifier can be superseded.");
        }
        if (!Set.of("verified", "active").contains(replacement.status())) {
            throw invalidTransition("The replacement organization identifier must be verified.");
        }
        if (!current.identifierType().equals(replacement.identifierType())) {
            throw invalidTransition(
                    "The replacement organization identifier must use the same identifier type.");
        }
        if (replacement.supersedesId() != null) {
            throw invalidTransition(
                    "The replacement organization identifier already supersedes another record.");
        }
        try {
            var superseded = jdbcTemplate.update(
                    """
                    UPDATE organization_identifiers
                    SET status = 'superseded', updated_by = ?, lock_version = lock_version + 1
                    WHERE id = ? AND organization_id = ? AND lock_version = ?
                    """,
                    context.actorId(),
                    identifierId,
                    context.organizationId(),
                    expectedLockVersion);
            if (superseded != 1) {
                throw stale();
            }
            var linked = jdbcTemplate.update(
                    """
                    UPDATE organization_identifiers
                    SET supersedes_id = ?,
                        is_primary = CASE WHEN ? THEN true ELSE is_primary END,
                        updated_by = ?, lock_version = lock_version + 1
                    WHERE id = ? AND organization_id = ? AND lock_version = ?
                    """,
                    identifierId,
                    current.isPrimary(),
                    context.actorId(),
                    replacementId,
                    context.organizationId(),
                    expectedReplacementLockVersion);
            if (linked != 1) {
                throw stale();
            }
            return new SupersedeResult(
                    identifier(context, identifierId), current.status(), replacementId);
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    private OrganizationIdentifier identifier(
            AuthorizedTenantContext context, UUID identifierId) {
        var permissions = effectivePermissions(context);
        return jdbcTemplate.query(
                        IDENTIFIER_PROJECTION
                                + " WHERE identifiers.organization_id = ? AND identifiers.id = ?",
                        (resultSet, rowNumber) -> mapIdentifier(
                                resultSet,
                                permissions.contains("organization.identifier.manage"),
                                permissions.contains("organization.identifier.verify"),
                                permissions.contains("organization.identifier.verify")),
                        context.organizationId(),
                        identifierId)
                .stream()
                .findFirst()
                .orElseThrow(JdbcOrganizationIdentifierStore::notFound);
    }

    private CurrentIdentifier current(UUID identifierId) {
        return currents(identifierId).stream()
                .findFirst()
                .orElseThrow(JdbcOrganizationIdentifierStore::notFound);
    }

    private List<CurrentIdentifier> currents(UUID... identifierIds) {
        if (identifierIds.length == 0 || identifierIds.length > 2) {
            throw new IllegalArgumentException("One or two organization identifiers are required");
        }
        var placeholders = identifierIds.length == 1 ? "?" : "?, ?";
        return jdbcTemplate.query(
                        """
                        SELECT id, identifier_type, assigning_authority, value_normalized,
                               jurisdiction_country_code, is_primary, issue_date, expiry_date,
                               effective_from, effective_to, supersedes_id, status, lock_version
                        FROM organization_identifiers
                        WHERE id IN ("""
                                + placeholders
                                + ") ORDER BY id FOR UPDATE",
                        (resultSet, rowNumber) -> new CurrentIdentifier(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("identifier_type"),
                                resultSet.getString("assigning_authority"),
                                resultSet.getString("value_normalized"),
                                resultSet.getString("jurisdiction_country_code"),
                                resultSet.getBoolean("is_primary"),
                                resultSet.getObject("issue_date", java.time.LocalDate.class),
                                resultSet.getObject("expiry_date", java.time.LocalDate.class),
                                resultSet.getTimestamp("effective_from").toInstant(),
                                timestamp(resultSet, "effective_to"),
                                resultSet.getObject("supersedes_id", UUID.class),
                                resultSet.getString("status"),
                                resultSet.getLong("lock_version")),
                        (Object[]) identifierIds);
    }

    private void validateType(AuthorizedTenantContext context, IdentifierDraft draft) {
        var match = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM organization_identifier_types types
                JOIN organizations ON organizations.id = ?
                WHERE types.identifier_type = ?
                  AND types.status = 'active'
                  AND types.registry_version = 'm1-candidate-1'
                  AND (types.jurisdiction_country_code IS NULL
                       OR types.jurisdiction_country_code = ?)
                  AND (types.jurisdiction_country_code IS NULL
                       OR types.jurisdiction_country_code = organizations.country_code)
                """,
                Integer.class,
                context.organizationId(),
                draft.identifierType(),
                draft.jurisdictionCountryCode());
        if (match == null || match != 1) {
            throw new OrganizationIdentifierException(
                    OrganizationIdentifierException.Reason.INVALID_REQUEST,
                    "identifierType is not active for the selected jurisdiction.",
                    "identifierType",
                    "m1.field.enum");
        }
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

    private static OrganizationIdentifier mapIdentifier(
            ResultSet resultSet,
            boolean canManage,
            boolean canVerify,
            boolean canReadEvidence)
            throws SQLException {
        var status = resultSet.getString("status");
        var actions = new ArrayList<String>();
        if (canManage && "draft".equals(status)) {
            actions.add("edit");
        }
        if (canVerify && "draft".equals(status)) {
            actions.add("verify");
        }
        if (canManage
                && Set.of("verified", "active").contains(status)
                && resultSet.getBoolean("revocable")) {
            actions.add("revoke");
        }
        if (canManage && Set.of("verified", "active").contains(status)) {
            actions.add("supersede");
        }
        return new OrganizationIdentifier(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("identifier_type"),
                resultSet.getString("type_display_name"),
                resultSet.getString("assigning_authority"),
                resultSet.getString("value_normalized"),
                resultSet.getString("jurisdiction_country_code"),
                resultSet.getString("verification_status"),
                canReadEvidence
                        ? resultSet.getString("verification_evidence_reference")
                        : null,
                resultSet.getBoolean("is_primary"),
                resultSet.getObject("issue_date", java.time.LocalDate.class),
                resultSet.getObject("expiry_date", java.time.LocalDate.class),
                resultSet.getTimestamp("effective_from").toInstant(),
                timestamp(resultSet, "effective_to"),
                resultSet.getObject("supersedes_id", UUID.class),
                status,
                actions,
                resultSet.getLong("lock_version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static java.time.Instant timestamp(ResultSet resultSet, String column)
            throws SQLException {
        var timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static java.sql.Timestamp sqlTimestamp(java.time.Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private static boolean sameDraft(CurrentIdentifier current, IdentifierDraft draft) {
        return current.identifierType().equals(draft.identifierType())
                && current.assigningAuthority().equals(draft.assigningAuthority())
                && current.value().equals(draft.value())
                && Objects.equals(
                        current.jurisdictionCountryCode(), draft.jurisdictionCountryCode())
                && current.isPrimary() == draft.isPrimary()
                && Objects.equals(current.issueDate(), draft.issueDate())
                && Objects.equals(current.expiryDate(), draft.expiryDate())
                && current.effectiveFrom().equals(draft.effectiveFrom())
                && Objects.equals(current.effectiveTo(), draft.effectiveTo());
    }

    private static void requireRevision(CurrentIdentifier current, long expectedLockVersion) {
        if (current.lockVersion() != expectedLockVersion) {
            throw stale();
        }
    }

    private static OrganizationIdentifierException translate(DataAccessException exception) {
        var postgres = postgres(exception);
        if (postgres != null) {
            if ("23505".equals(postgres.getSQLState())) {
                return new OrganizationIdentifierException(
                        OrganizationIdentifierException.Reason.DUPLICATE,
                        "This organization identifier already exists in non-revoked history.");
            }
            if ("23P01".equals(postgres.getSQLState())) {
                return new OrganizationIdentifierException(
                        OrganizationIdentifierException.Reason.EFFECTIVE_OVERLAP,
                        "A primary identifier of this type already covers that effective range.");
            }
            var message = postgres.getMessage() == null ? "" : postgres.getMessage();
            if (message.contains("cannot be revoked without replacement")) {
                return new OrganizationIdentifierException(
                        OrganizationIdentifierException.Reason.PRIMARY_REPLACEMENT_REQUIRED,
                        "A required current primary identifier cannot be revoked without a verified replacement.");
            }
            if (message.contains("type is unknown") || message.contains("jurisdiction")) {
                return new OrganizationIdentifierException(
                        OrganizationIdentifierException.Reason.INVALID_REQUEST,
                        "identifierType is not active for the selected jurisdiction.",
                        "identifierType",
                        "m1.field.enum");
            }
            if ("23514".equals(postgres.getSQLState()) || "42501".equals(postgres.getSQLState())) {
                return invalidTransition("The requested organization identifier transition is invalid.");
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

    private static OrganizationIdentifierException notFound() {
        return new OrganizationIdentifierException(
                OrganizationIdentifierException.Reason.NOT_FOUND,
                "The organization identifier is unavailable.");
    }

    private static OrganizationIdentifierException stale() {
        return new OrganizationIdentifierException(
                OrganizationIdentifierException.Reason.STALE_REVISION,
                "The organization identifier changed. Reload it before continuing.");
    }

    private static OrganizationIdentifierException invalidTransition(String detail) {
        return new OrganizationIdentifierException(
                OrganizationIdentifierException.Reason.INVALID_TRANSITION, detail);
    }

    private record CurrentIdentifier(
            UUID identifierId,
            String identifierType,
            String assigningAuthority,
            String value,
            String jurisdictionCountryCode,
            boolean isPrimary,
            java.time.LocalDate issueDate,
            java.time.LocalDate expiryDate,
            java.time.Instant effectiveFrom,
            java.time.Instant effectiveTo,
            UUID supersedesId,
            String status,
            long lockVersion) {}
}
