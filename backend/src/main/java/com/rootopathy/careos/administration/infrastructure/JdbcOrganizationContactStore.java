package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.OrganizationContactException;
import com.rootopathy.careos.administration.application.OrganizationContactStore;
import com.rootopathy.careos.administration.domain.OrganizationAddress;
import com.rootopathy.careos.administration.domain.OrganizationContact;
import com.rootopathy.careos.administration.domain.OrganizationContactCollection;
import com.rootopathy.careos.administration.domain.OrganizationContactPurpose;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import com.rootopathy.careos.tenancy.infrastructure.AuthorizedTenantTransactionGuard;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrganizationContactStore implements OrganizationContactStore {
    private static final List<String> ADDRESS_TYPES =
            List.of("registered", "postal", "service", "billing");
    private static final String ADDRESS_PROJECTION = """
            SELECT addresses.id, addresses.address_type,
                   addresses.address_line_1, addresses.address_line_2,
                   addresses.address_line_3, addresses.address_line_4,
                   addresses.locality, addresses.region, addresses.postcode,
                   addresses.country_code, addresses.validation_status,
                   addresses.validation_source, addresses.is_primary,
                   addresses.effective_from, addresses.effective_to,
                   addresses.supersedes_id, addresses.status,
                   addresses.lock_version, addresses.created_at, addresses.updated_at
            FROM organization_addresses addresses
            """;
    private static final String CONTACT_PROJECTION = """
            SELECT contacts.id, contacts.channel, contacts.purpose_key,
                   purposes.display_name AS purpose_display_name,
                   contacts.value_normalized, contacts.verification_status,
                   contacts.is_primary, contacts.is_preferred,
                   contacts.effective_from, contacts.effective_to,
                   contacts.supersedes_id, contacts.status,
                   contacts.lock_version, contacts.created_at, contacts.updated_at
            FROM organization_contacts contacts
            JOIN organization_contact_purposes purposes
              ON purposes.purpose_key = contacts.purpose_key
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcOrganizationContactStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public OrganizationContactCollection directory(AuthorizedTenantContext context) {
        AuthorizedTenantTransactionGuard.requireBound(jdbcTemplate, context);
        var organizationExists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM organizations WHERE id = ?",
                Integer.class,
                context.organizationId());
        if (organizationExists == null || organizationExists != 1) {
            throw notFound("The organization address and contact directory is unavailable.");
        }
        var canManage = effectivePermissions(context).contains("organization.contact.manage");
        var purposes = jdbcTemplate.query(
                """
                SELECT purpose_key, display_name, public_projection_allowed
                FROM organization_contact_purposes
                WHERE status = 'active' AND registry_version = 'm1-candidate-1'
                ORDER BY display_name, purpose_key
                """,
                (resultSet, rowNumber) -> new OrganizationContactPurpose(
                        resultSet.getString("purpose_key"),
                        resultSet.getString("display_name"),
                        resultSet.getBoolean("public_projection_allowed")));
        var addresses = jdbcTemplate.query(
                ADDRESS_PROJECTION
                        + " WHERE addresses.organization_id = ?"
                        + " ORDER BY addresses.is_primary DESC,"
                        + " addresses.address_type, addresses.created_at DESC, addresses.id DESC",
                (resultSet, rowNumber) -> mapAddress(resultSet, canManage),
                context.organizationId());
        var contacts = jdbcTemplate.query(
                CONTACT_PROJECTION
                        + " WHERE contacts.organization_id = ?"
                        + " ORDER BY contacts.is_primary DESC, contacts.is_preferred DESC,"
                        + " purposes.display_name, contacts.channel,"
                        + " contacts.created_at DESC, contacts.id DESC",
                (resultSet, rowNumber) -> mapContact(resultSet, canManage),
                context.organizationId());
        return new OrganizationContactCollection(
                context.organizationId(),
                canManage && !purposes.isEmpty(),
                ADDRESS_TYPES,
                purposes,
                addresses,
                contacts);
    }

    @Override
    public OrganizationAddress createAddress(
            AuthorizedTenantContext context, AddressDraft draft) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        try {
            return address(context, insertAddress(context, draft, null));
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    @Override
    public SupersededAddress supersedeAddress(
            AuthorizedTenantContext context,
            UUID addressId,
            long expectedLockVersion,
            AddressDraft replacement) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        var current = currentAddress(context, addressId);
        requireRevision(current.lockVersion(), expectedLockVersion);
        if (!Set.of("scheduled", "active").contains(current.status())) {
            throw invalidTransition(
                    "Only a scheduled or active organization address can be superseded.");
        }
        if (!current.addressType().equals(replacement.addressType())) {
            throw invalidTransition("A replacement address must use the same address type.");
        }
        if (current.isPrimary() != replacement.isPrimary()) {
            throw invalidTransition(
                    "A replacement address must preserve its primary designation.");
        }
        try {
            var changed = jdbcTemplate.update(
                    """
                    UPDATE organization_addresses
                    SET status = 'superseded', updated_by = ?, lock_version = lock_version + 1
                    WHERE id = ? AND organization_id = ? AND lock_version = ?
                    """,
                    context.actorId(),
                    addressId,
                    context.organizationId(),
                    expectedLockVersion);
            if (changed != 1) {
                throw stale();
            }
            var replacementId = insertAddress(context, replacement, addressId);
            return new SupersededAddress(address(context, replacementId), addressId);
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    @Override
    public OrganizationAddress endAddress(
            AuthorizedTenantContext context, UUID addressId, long expectedLockVersion) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        var current = currentAddress(context, addressId);
        requireRevision(current.lockVersion(), expectedLockVersion);
        if (!"active".equals(current.status())) {
            throw invalidTransition("Only an active organization address can be ended.");
        }
        try {
            var changed = jdbcTemplate.update(
                    """
                    UPDATE organization_addresses
                    SET status = 'ended', effective_to = clock_timestamp(),
                        updated_by = ?, lock_version = lock_version + 1
                    WHERE id = ? AND organization_id = ? AND lock_version = ?
                    """,
                    context.actorId(),
                    addressId,
                    context.organizationId(),
                    expectedLockVersion);
            if (changed != 1) {
                throw stale();
            }
            return address(context, addressId);
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    @Override
    public OrganizationContact createContact(
            AuthorizedTenantContext context, ContactDraft draft) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        validatePurpose(draft.purpose());
        try {
            return contact(context, insertContact(context, draft, null));
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    @Override
    public OrganizationContact verifyContact(
            AuthorizedTenantContext context, UUID contactId, long expectedLockVersion) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        var current = currentContact(context, contactId);
        requireRevision(current.lockVersion(), expectedLockVersion);
        if (!Set.of("scheduled", "active").contains(current.status())
                || !"unverified".equals(current.verificationStatus())) {
            throw invalidTransition(
                    "Only an unverified scheduled or active organization contact can be verified.");
        }
        try {
            var changed = jdbcTemplate.update(
                    """
                    UPDATE organization_contacts
                    SET verification_status = 'verified', verified_by = ?,
                        updated_by = ?, lock_version = lock_version + 1
                    WHERE id = ? AND organization_id = ? AND lock_version = ?
                    """,
                    context.actorId(),
                    context.actorId(),
                    contactId,
                    context.organizationId(),
                    expectedLockVersion);
            if (changed != 1) {
                throw stale();
            }
            return contact(context, contactId);
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    @Override
    public SupersededContact supersedeContact(
            AuthorizedTenantContext context,
            UUID contactId,
            long expectedLockVersion,
            ContactDraft replacement) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        var current = currentContact(context, contactId);
        requireRevision(current.lockVersion(), expectedLockVersion);
        if (!Set.of("scheduled", "active").contains(current.status())) {
            throw invalidTransition(
                    "Only a scheduled or active organization contact can be superseded.");
        }
        if (!current.channel().equals(replacement.channel())
                || !current.purpose().equals(replacement.purpose())) {
            throw invalidTransition(
                    "A replacement contact must preserve its channel and purpose.");
        }
        if (current.isPrimary() != replacement.isPrimary()
                || current.isPreferred() != replacement.isPreferred()) {
            throw invalidTransition(
                    "A replacement contact must preserve its primary and preferred designations.");
        }
        validatePurpose(replacement.purpose());
        try {
            var changed = jdbcTemplate.update(
                    """
                    UPDATE organization_contacts
                    SET status = 'superseded', updated_by = ?, lock_version = lock_version + 1
                    WHERE id = ? AND organization_id = ? AND lock_version = ?
                    """,
                    context.actorId(),
                    contactId,
                    context.organizationId(),
                    expectedLockVersion);
            if (changed != 1) {
                throw stale();
            }
            var replacementId = insertContact(context, replacement, contactId);
            return new SupersededContact(contact(context, replacementId), contactId);
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    @Override
    public OrganizationContact endContact(
            AuthorizedTenantContext context, UUID contactId, long expectedLockVersion) {
        AuthorizedTenantTransactionGuard.requireWritable(jdbcTemplate, context);
        var current = currentContact(context, contactId);
        requireRevision(current.lockVersion(), expectedLockVersion);
        if (!"active".equals(current.status())) {
            throw invalidTransition("Only an active organization contact can be ended.");
        }
        try {
            var changed = jdbcTemplate.update(
                    """
                    UPDATE organization_contacts
                    SET status = 'ended', effective_to = clock_timestamp(),
                        updated_by = ?, lock_version = lock_version + 1
                    WHERE id = ? AND organization_id = ? AND lock_version = ?
                    """,
                    context.actorId(),
                    contactId,
                    context.organizationId(),
                    expectedLockVersion);
            if (changed != 1) {
                throw stale();
            }
            return contact(context, contactId);
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    private UUID insertAddress(
            AuthorizedTenantContext context, AddressDraft draft, UUID supersedesId) {
        var lines = draft.addressLines();
        var id = jdbcTemplate.queryForObject(
                """
                INSERT INTO organization_addresses
                    (organization_id, address_type, address_line_1, address_line_2,
                     address_line_3, address_line_4, locality, region, postcode,
                     country_code, validation_status, validation_source, is_primary,
                     effective_from, effective_to, supersedes_id, status,
                     created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'scheduled', ?, ?)
                RETURNING id
                """,
                UUID.class,
                context.organizationId(),
                draft.addressType(),
                lines.getFirst(),
                line(lines, 1),
                line(lines, 2),
                line(lines, 3),
                draft.locality(),
                draft.region(),
                draft.postcode(),
                draft.countryCode(),
                draft.validationStatus(),
                draft.validationSource(),
                draft.isPrimary(),
                java.sql.Timestamp.from(draft.effectiveFrom()),
                timestamp(draft.effectiveTo()),
                supersedesId,
                context.actorId(),
                context.actorId());
        if (id == null) {
            throw new IllegalStateException("Organization address creation omitted its identifier");
        }
        return id;
    }

    private UUID insertContact(
            AuthorizedTenantContext context, ContactDraft draft, UUID supersedesId) {
        var id = jdbcTemplate.queryForObject(
                """
                INSERT INTO organization_contacts
                    (organization_id, channel, purpose_key, value_normalized,
                     verification_status, is_primary, is_preferred,
                     effective_from, effective_to, supersedes_id, status,
                     created_by, updated_by)
                VALUES (?, ?, ?, ?, 'unverified', ?, ?, ?, ?, ?, 'scheduled', ?, ?)
                RETURNING id
                """,
                UUID.class,
                context.organizationId(),
                draft.channel(),
                draft.purpose(),
                draft.value(),
                draft.isPrimary(),
                draft.isPreferred(),
                java.sql.Timestamp.from(draft.effectiveFrom()),
                timestamp(draft.effectiveTo()),
                supersedesId,
                context.actorId(),
                context.actorId());
        if (id == null) {
            throw new IllegalStateException("Organization contact creation omitted its identifier");
        }
        return id;
    }

    private OrganizationAddress address(
            AuthorizedTenantContext context, UUID addressId) {
        var canManage = effectivePermissions(context).contains("organization.contact.manage");
        return jdbcTemplate.query(
                        ADDRESS_PROJECTION
                                + " WHERE addresses.organization_id = ? AND addresses.id = ?",
                        (resultSet, rowNumber) -> mapAddress(resultSet, canManage),
                        context.organizationId(),
                        addressId)
                .stream()
                .findFirst()
                .orElseThrow(() -> notFound("The organization address is unavailable."));
    }

    private OrganizationContact contact(
            AuthorizedTenantContext context, UUID contactId) {
        var canManage = effectivePermissions(context).contains("organization.contact.manage");
        return jdbcTemplate.query(
                        CONTACT_PROJECTION
                                + " WHERE contacts.organization_id = ? AND contacts.id = ?",
                        (resultSet, rowNumber) -> mapContact(resultSet, canManage),
                        context.organizationId(),
                        contactId)
                .stream()
                .findFirst()
                .orElseThrow(() -> notFound("The organization contact is unavailable."));
    }

    private CurrentAddress currentAddress(
            AuthorizedTenantContext context, UUID addressId) {
        return jdbcTemplate.query(
                        """
                        SELECT id, address_type, is_primary, effective_from,
                               effective_to, status, lock_version
                        FROM organization_addresses
                        WHERE organization_id = ? AND id = ?
                        FOR UPDATE
                        """,
                        (resultSet, rowNumber) -> new CurrentAddress(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("address_type"),
                                resultSet.getBoolean("is_primary"),
                                resultSet.getTimestamp("effective_from").toInstant(),
                                nullableTimestamp(resultSet, "effective_to"),
                                resultSet.getString("status"),
                                resultSet.getLong("lock_version")),
                        context.organizationId(),
                        addressId)
                .stream()
                .findFirst()
                .orElseThrow(() -> notFound("The organization address is unavailable."));
    }

    private CurrentContact currentContact(
            AuthorizedTenantContext context, UUID contactId) {
        return jdbcTemplate.query(
                        """
                        SELECT id, channel, purpose_key, verification_status,
                               is_primary, is_preferred, effective_from,
                               effective_to, status, lock_version
                        FROM organization_contacts
                        WHERE organization_id = ? AND id = ?
                        FOR UPDATE
                        """,
                        (resultSet, rowNumber) -> new CurrentContact(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("channel"),
                                resultSet.getString("purpose_key"),
                                resultSet.getString("verification_status"),
                                resultSet.getBoolean("is_primary"),
                                resultSet.getBoolean("is_preferred"),
                                resultSet.getTimestamp("effective_from").toInstant(),
                                nullableTimestamp(resultSet, "effective_to"),
                                resultSet.getString("status"),
                                resultSet.getLong("lock_version")),
                        context.organizationId(),
                        contactId)
                .stream()
                .findFirst()
                .orElseThrow(() -> notFound("The organization contact is unavailable."));
    }

    private void validatePurpose(String purpose) {
        var active = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM organization_contact_purposes
                WHERE purpose_key = ? AND status = 'active'
                  AND registry_version = 'm1-candidate-1'
                """,
                Integer.class,
                purpose);
        if (active == null || active != 1) {
            throw new OrganizationContactException(
                    OrganizationContactException.Reason.INVALID_REQUEST,
                    "purpose is not active in the approved contact-purpose registry.",
                    "purpose",
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

    private static OrganizationAddress mapAddress(
            ResultSet resultSet, boolean canManage) throws SQLException {
        var status = resultSet.getString("status");
        var actions = new ArrayList<String>();
        if (canManage && Set.of("scheduled", "active").contains(status)) {
            actions.add("supersede");
        }
        if (canManage && "active".equals(status)) {
            actions.add("end");
        }
        var lines = new ArrayList<String>();
        for (var column : List.of(
                "address_line_1", "address_line_2", "address_line_3", "address_line_4")) {
            var line = resultSet.getString(column);
            if (line != null) {
                lines.add(line);
            }
        }
        return new OrganizationAddress(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("address_type"),
                lines,
                resultSet.getString("locality"),
                resultSet.getString("region"),
                resultSet.getString("postcode"),
                resultSet.getString("country_code"),
                resultSet.getString("validation_status"),
                resultSet.getString("validation_source"),
                resultSet.getBoolean("is_primary"),
                resultSet.getTimestamp("effective_from").toInstant(),
                nullableTimestamp(resultSet, "effective_to"),
                resultSet.getObject("supersedes_id", UUID.class),
                status,
                actions,
                resultSet.getLong("lock_version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static OrganizationContact mapContact(
            ResultSet resultSet, boolean canManage) throws SQLException {
        var status = resultSet.getString("status");
        var verificationStatus = resultSet.getString("verification_status");
        var actions = new ArrayList<String>();
        if (canManage
                && "unverified".equals(verificationStatus)
                && Set.of("scheduled", "active").contains(status)) {
            actions.add("verify");
        }
        if (canManage && Set.of("scheduled", "active").contains(status)) {
            actions.add("supersede");
        }
        if (canManage && "active".equals(status)) {
            actions.add("end");
        }
        var channel = resultSet.getString("channel");
        return new OrganizationContact(
                resultSet.getObject("id", UUID.class),
                channel,
                resultSet.getString("purpose_key"),
                resultSet.getString("purpose_display_name"),
                mask(channel, resultSet.getString("value_normalized")),
                verificationStatus,
                resultSet.getBoolean("is_primary"),
                resultSet.getBoolean("is_preferred"),
                resultSet.getTimestamp("effective_from").toInstant(),
                nullableTimestamp(resultSet, "effective_to"),
                resultSet.getObject("supersedes_id", UUID.class),
                status,
                actions,
                resultSet.getLong("lock_version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static String mask(String channel, String value) {
        return switch (channel) {
            case "email" -> maskEmail(value);
            case "phone" -> value.length() <= 4
                    ? "+***"
                    : "+***" + value.substring(value.length() - 4);
            case "web" -> maskWeb(value);
            default -> throw new IllegalStateException("Unexpected contact channel");
        };
    }

    private static String maskEmail(String value) {
        var separator = value.lastIndexOf('@');
        var local = separator > 0 ? value.substring(0, separator) : value;
        var domain = separator > 0 ? value.substring(separator + 1) : "";
        var suffix = domain.lastIndexOf('.') > 0
                ? domain.substring(domain.lastIndexOf('.'))
                : "";
        return local.substring(0, 1) + "***@***" + suffix;
    }

    private static String maskWeb(String value) {
        try {
            var host = URI.create(value).getHost();
            if (host != null && !host.isEmpty()) {
                return "https://" + host.substring(0, 1) + "***/";
            }
        } catch (IllegalArgumentException ignored) {
            // Database validation makes this defensive branch unreachable for governed data.
        }
        return "https://***/";
    }

    private static String line(List<String> lines, int index) {
        return lines.size() > index ? lines.get(index) : null;
    }

    private static java.sql.Timestamp timestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private static Instant nullableTimestamp(ResultSet resultSet, String column)
            throws SQLException {
        var value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void requireRevision(long actual, long expected) {
        if (actual != expected) {
            throw stale();
        }
    }

    private static OrganizationContactException translate(DataAccessException exception) {
        var postgres = postgres(exception);
        if (postgres != null) {
            if ("23505".equals(postgres.getSQLState())) {
                return new OrganizationContactException(
                        OrganizationContactException.Reason.DUPLICATE,
                        "An equivalent current organization contact already exists.");
            }
            if ("23P01".equals(postgres.getSQLState())) {
                return new OrganizationContactException(
                        OrganizationContactException.Reason.EFFECTIVE_OVERLAP,
                        "A primary record already covers that effective range.");
            }
            var message = postgres.getMessage() == null ? "" : postgres.getMessage();
            if (message.contains("effective ranges overlap")) {
                return new OrganizationContactException(
                        OrganizationContactException.Reason.EFFECTIVE_OVERLAP,
                        "A primary record already covers that effective range.");
            }
            if (message.contains("purpose is unknown")) {
                return new OrganizationContactException(
                        OrganizationContactException.Reason.INVALID_REQUEST,
                        "purpose is not active in the approved contact-purpose registry.",
                        "purpose",
                        "m1.field.enum");
            }
            if ("23514".equals(postgres.getSQLState())
                    || "42501".equals(postgres.getSQLState())) {
                return invalidTransition(
                        "The requested organization address or contact transition is invalid.");
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

    private static OrganizationContactException notFound(String detail) {
        return new OrganizationContactException(
                OrganizationContactException.Reason.NOT_FOUND, detail);
    }

    private static OrganizationContactException stale() {
        return new OrganizationContactException(
                OrganizationContactException.Reason.STALE_REVISION,
                "The organization address or contact changed. Reload it before continuing.");
    }

    private static OrganizationContactException invalidTransition(String detail) {
        return new OrganizationContactException(
                OrganizationContactException.Reason.INVALID_TRANSITION, detail);
    }

    private record CurrentAddress(
            UUID addressId,
            String addressType,
            boolean isPrimary,
            Instant effectiveFrom,
            Instant effectiveTo,
            String status,
            long lockVersion) {}

    private record CurrentContact(
            UUID contactId,
            String channel,
            String purpose,
            String verificationStatus,
            boolean isPrimary,
            boolean isPreferred,
            Instant effectiveFrom,
            Instant effectiveTo,
            String status,
            long lockVersion) {}
}
