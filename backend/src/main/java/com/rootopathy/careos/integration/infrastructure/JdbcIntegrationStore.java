package com.rootopathy.careos.integration.infrastructure;

import com.rootopathy.careos.integration.application.IntegrationException;
import com.rootopathy.careos.integration.application.IntegrationStore;
import com.rootopathy.careos.integration.domain.IntegrationScreen;
import com.rootopathy.careos.shared.domain.UuidV7Generator;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed Module 13 secret-free integration registry and replay authorization workflow. */
@Repository
public class JdbcIntegrationStore implements IntegrationStore {
    private static final String OUTBOX_EVENT = "m13.integration-artifact-changed.v1";
    private static final Set<String> MAPPING_KINDS = Set.of(
            "fhir", "terminology", "message", "payment", "calendar", "diagnostic", "api");
    private static final Map<String, String> SCREEN_KINDS = Map.of(
            "P13-02", "fhir",
            "P13-04", "whatsapp",
            "P13-05", "payment",
            "P13-06", "calendar",
            "P13-07", "lab_imaging",
            "P13-08", "webhook");

    private final JdbcTemplate jdbc;

    public JdbcIntegrationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Projection projection(AuthorizedTenantContext context, ScreenQuery query) {
        requireOperationScope(context);
        var projected = switch (query.screenId()) {
            case "P13-01" -> dashboardRows(context);
            case "P13-02", "P13-04", "P13-05", "P13-06", "P13-07", "P13-08" ->
                    connectionRows(context, query.screenId(), SCREEN_KINDS.get(query.screenId()));
            case "P13-03" -> mappingRows(context, query.screenId());
            case "P13-09" -> apiClientRows(context, query.screenId());
            case "P13-10" -> auditRows(context, query.screenId());
            default -> throw notFound("The requested integration screen does not exist.");
        };
        var rows = projected.stream()
                .filter(row -> matches(query, row))
                .limit(query.limit())
                .toList();
        return new Projection(
                metrics(context), columns(query.screenId()), rows,
                notices(query.screenId()), databaseNow());
    }

    @Override
    public Set<String> permissions(AuthorizedTenantContext context) {
        requireOperationScope(context);
        return Set.copyOf(jdbc.queryForList(
                "SELECT permission_key FROM careos_projected_interactive_permissions(?, ?)",
                String.class, context.organizationId(), context.actorId()));
    }

    @Override
    public MutationResult mutate(AuthorizedTenantContext context, MutationCommand command) {
        requireOperationScope(context);
        return switch (command.actionKey()) {
            case "create-connection" -> createConnection(context, command);
            case "validate-connection" -> transitionConnection(
                    context, command, Set.of("draft", "suspended"), "validated",
                    "integration.connection.validated");
            case "suspend-connection" -> transitionConnection(
                    context, command, Set.of("validated"), "suspended",
                    "integration.connection.suspended");
            case "retire-connection" -> transitionConnection(
                    context, command, Set.of("validated", "suspended"), "retired",
                    "integration.connection.retired");
            case "create-mapping" -> createMapping(context, command);
            case "activate-mapping" -> transitionMapping(
                    context, command, "draft", "active", "integration.mapping.activated");
            case "retire-mapping" -> transitionMapping(
                    context, command, "active", "retired", "integration.mapping.retired");
            case "register-api-client" -> registerApiClient(context, command);
            case "revoke-api-client" -> revokeApiClient(context, command);
            case "authorize-replay" -> authorizeReplay(context, command);
            default -> throw notFound("The requested integration action does not exist.");
        };
    }

    private MutationResult createConnection(
            AuthorizedTenantContext context, MutationCommand command) {
        var providerKind = SCREEN_KINDS.get(command.screenId());
        if (providerKind == null) throw invalid("This screen does not define a connection family.");
        var id = UuidV7Generator.randomUuid();
        var code = code(field(command, "connectionCode"), "connectionCode");
        var displayName = bounded(field(command, "displayName"), 1, 160, "displayName");
        var contractVersion = bounded(field(command, "contractVersion"), 1, 80, "contractVersion");
        var endpointReference = code(field(command, "endpointReference"), "endpointReference", 120);
        if (endpointReference.contains("://") || endpointReference.contains("@")) {
            throw invalid("endpointReference must be an opaque configuration alias, not a URL.");
        }
        var securityProfile = code(field(command, "securityProfileKey"), "securityProfileKey");
        var credentialDigest = digest(field(command, "credentialReferenceDigest"),
                "credentialReferenceDigest");
        var mappingId = optionalUuid(command.fields().get("mappingVersionId"), "mappingVersionId");
        jdbc.update(
                """
                INSERT INTO integration_connections(
                    id,organization_id,connection_code,display_name,provider_kind,contract_version,
                    endpoint_reference,security_profile_key,credential_reference_digest,
                    mapping_version_id,status,last_transition_at,last_transition_by,
                    last_transition_reason,created_at,created_by,updated_at,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,'draft',?,?,?,?,?,?,?)
                """,
                id, context.organizationId(), code, displayName, providerKind, contractVersion,
                endpointReference, securityProfile, credentialDigest, mappingId,
                Timestamp.from(command.now()), context.actorId(), command.reason(),
                Timestamp.from(command.now()), context.actorId(), Timestamp.from(command.now()),
                context.actorId());
        return result(id, id, "integration_connection", "integration.connection.created",
                "none", "draft", 0, 201);
    }

    private MutationResult transitionConnection(
            AuthorizedTenantContext context,
            MutationCommand command,
            Set<String> expectedStatuses,
            String nextStatus,
            String event) {
        var connection = lockConnection(context, command.targetId());
        requireScreenKind(command.screenId(), connection.providerKind());
        requireRevision(connection.revision(), command.expectedRevision(), "integration connection");
        if (!expectedStatuses.contains(connection.status())) {
            throw conflict("The integration connection is not in the required state.");
        }
        int changed;
        if (nextStatus.equals("validated")) {
            changed = jdbc.update(
                    """
                    UPDATE integration_connections
                       SET status='validated',validated_at=clock_timestamp(),validated_by=?,
                           last_transition_at=clock_timestamp(),last_transition_by=?,
                           last_transition_reason=?,lock_version=lock_version+1,
                           updated_at=clock_timestamp(),updated_by=?
                     WHERE organization_id=? AND id=? AND lock_version=?
                       AND status IN ('draft','suspended')
                    """,
                    context.actorId(), context.actorId(), command.reason(), context.actorId(),
                    context.organizationId(), connection.id(), connection.revision());
        } else {
            changed = jdbc.update(
                    """
                    UPDATE integration_connections
                       SET status=?,last_transition_at=clock_timestamp(),last_transition_by=?,
                           last_transition_reason=?,lock_version=lock_version+1,
                           updated_at=clock_timestamp(),updated_by=?
                     WHERE organization_id=? AND id=? AND lock_version=? AND status=?
                    """,
                    nextStatus, context.actorId(), command.reason(), context.actorId(),
                    context.organizationId(), connection.id(), connection.revision(),
                    connection.status());
        }
        requireChanged(changed, "The integration connection changed; reload before continuing.");
        return result(connection.id(), connection.id(), "integration_connection", event,
                connection.status(), nextStatus, connection.revision() + 1, 200);
    }

    private MutationResult createMapping(
            AuthorizedTenantContext context, MutationCommand command) {
        var id = UuidV7Generator.randomUuid();
        var mappingCode = code(field(command, "mappingCode"), "mappingCode");
        var mappingKind = oneOf(field(command, "mappingKind"), "mappingKind", MAPPING_KINDS);
        var version = integer(field(command, "versionNumber"), "versionNumber", 1, 1_000_000);
        var sourceVersion = bounded(field(command, "sourceVersion"), 1, 80, "sourceVersion");
        var targetVersion = bounded(field(command, "targetVersion"), 1, 80, "targetVersion");
        var fhirRelease = optional(command.fields().get("fhirRelease"), 40, "fhirRelease");
        var profilePackage = optional(command.fields().get("profilePackage"), 160, "profilePackage");
        var profileVersion = optional(command.fields().get("profileVersion"), 80, "profileVersion");
        var terminologyVersion = optional(
                command.fields().get("terminologyVersion"), 80, "terminologyVersion");
        if (mappingKind.equals("fhir")
                && (fhirRelease == null || profilePackage == null
                        || profileVersion == null || terminologyVersion == null)) {
            throw invalid("FHIR mappings require an exact release, profile package/version and terminology version.");
        }
        if (mappingKind.equals("terminology") && terminologyVersion == null) {
            throw invalid("Terminology mappings require an exact terminology version.");
        }
        var contentDigest = digest(field(command, "contentDigest"), "contentDigest");
        jdbc.update(
                """
                INSERT INTO integration_mapping_versions(
                    id,organization_id,mapping_code,mapping_kind,version_number,source_version,
                    target_version,fhir_release,profile_package,profile_version,terminology_version,
                    content_digest,status,created_at,created_by,updated_at,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'draft',?,?,?,?)
                """,
                id, context.organizationId(), mappingCode, mappingKind, version, sourceVersion,
                targetVersion, fhirRelease, profilePackage, profileVersion, terminologyVersion,
                contentDigest, Timestamp.from(command.now()), context.actorId(),
                Timestamp.from(command.now()), context.actorId());
        return result(id, null, "integration_mapping", "integration.mapping.created",
                "none", "draft", 0, 201);
    }

    private MutationResult transitionMapping(
            AuthorizedTenantContext context,
            MutationCommand command,
            String expectedStatus,
            String nextStatus,
            String event) {
        var mapping = lockMapping(context, command.targetId());
        requireRevision(mapping.revision(), command.expectedRevision(), "integration mapping");
        if (!mapping.status().equals(expectedStatus)) {
            throw conflict("The integration mapping is not in the required state.");
        }
        int changed;
        if (nextStatus.equals("active")) {
            changed = jdbc.update(
                    """
                    UPDATE integration_mapping_versions
                       SET status='active',activated_at=clock_timestamp(),activated_by=?,
                           lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                     WHERE organization_id=? AND id=? AND lock_version=? AND status='draft'
                    """,
                    context.actorId(), context.actorId(), context.organizationId(), mapping.id(),
                    mapping.revision());
        } else {
            changed = jdbc.update(
                    """
                    UPDATE integration_mapping_versions
                       SET status='retired',retired_at=clock_timestamp(),retired_by=?,
                           lock_version=lock_version+1,updated_at=clock_timestamp(),updated_by=?
                     WHERE organization_id=? AND id=? AND lock_version=? AND status='active'
                    """,
                    context.actorId(), context.actorId(), context.organizationId(), mapping.id(),
                    mapping.revision());
        }
        requireChanged(changed, "The integration mapping changed; reload before continuing.");
        return result(mapping.id(), null, "integration_mapping", event,
                expectedStatus, nextStatus, mapping.revision() + 1, 200);
    }

    private MutationResult registerApiClient(
            AuthorizedTenantContext context, MutationCommand command) {
        var id = UuidV7Generator.randomUuid();
        var clientCode = code(field(command, "clientCode"), "clientCode");
        var displayName = bounded(field(command, "displayName"), 1, 160, "displayName");
        var publicIdentifierDigest = digest(
                field(command, "publicIdentifierDigest"), "publicIdentifierDigest");
        var credentialDigest = digest(
                field(command, "credentialReferenceDigest"), "credentialReferenceDigest");
        var scopesDigest = digest(field(command, "scopesDigest"), "scopesDigest");
        var purpose = code(field(command, "purposeKey"), "purposeKey");
        var accessPolicyVersion = bounded(
                field(command, "accessPolicyVersion"), 1, 80, "accessPolicyVersion");
        jdbc.update(
                """
                INSERT INTO integration_api_clients(
                    id,organization_id,client_code,display_name,public_identifier_digest,
                    credential_reference_digest,scopes_digest,purpose_key,token_policy_version,
                    status,access_enabled,last_transition_reason,
                    created_at,created_by,updated_at,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,'registered',false,?,?,?,?,?)
                """,
                id, context.organizationId(), clientCode, displayName, publicIdentifierDigest,
                credentialDigest, scopesDigest, purpose, accessPolicyVersion, command.reason(),
                Timestamp.from(command.now()), context.actorId(), Timestamp.from(command.now()),
                context.actorId());
        return result(id, null, "integration_api_client", "integration.api-client.registered",
                "none", "registered", 0, 201);
    }

    private MutationResult revokeApiClient(
            AuthorizedTenantContext context, MutationCommand command) {
        var client = lockApiClient(context, command.targetId());
        requireRevision(client.revision(), command.expectedRevision(), "API client");
        if (!client.status().equals("registered")) {
            throw conflict("Only a registered API client can be revoked.");
        }
        var changed = jdbc.update(
                """
                UPDATE integration_api_clients
                   SET status='revoked',access_enabled=false,revoked_at=clock_timestamp(),revoked_by=?,
                       last_transition_reason=?,lock_version=lock_version+1,
                       updated_at=clock_timestamp(),updated_by=?
                 WHERE organization_id=? AND id=? AND lock_version=? AND status='registered'
                """,
                context.actorId(), command.reason(), context.actorId(), context.organizationId(),
                client.id(), client.revision());
        requireChanged(changed, "The API client changed; reload before continuing.");
        return result(client.id(), null, "integration_api_client", "integration.api-client.revoked",
                "registered", "revoked", client.revision() + 1, 200);
    }

    private MutationResult authorizeReplay(
            AuthorizedTenantContext context, MutationCommand command) {
        var delivery = lockDelivery(context, command.targetId());
        requireRevision(delivery.revision(), command.expectedRevision(), "dead-letter delivery");
        if (!delivery.status().equals("dead_lettered")) {
            throw conflict("Only an exact dead-letter delivery can be authorized for replay.");
        }
        var id = UuidV7Generator.randomUuid();
        var expires = command.now().plus(1, ChronoUnit.HOURS);
        jdbc.update(
                """
                INSERT INTO integration_replay_requests(
                    id,organization_id,delivery_id,delivery_revision,authorization_reason,
                    authorized_at,authorized_by,expires_at,status,
                    created_at,created_by,updated_at,updated_by)
                VALUES (?,?,?,?,?,?,?,?,'authorized',?,?,?,?)
                """,
                id, context.organizationId(), delivery.id(), delivery.revision(), command.reason(),
                Timestamp.from(command.now()), context.actorId(), Timestamp.from(expires),
                Timestamp.from(command.now()), context.actorId(), Timestamp.from(command.now()),
                context.actorId());
        return result(id, delivery.connectionId(), "integration_replay",
                "integration.replay.authorized", "dead_lettered", "authorized", 0, 201);
    }

    private List<IntegrationScreen.Row> dashboardRows(AuthorizedTenantContext context) {
        var rows = new ArrayList<IntegrationScreen.Row>();
        rows.addAll(connectionRows(context, "P13-01", null));
        rows.addAll(mappingRows(context, "P13-01"));
        rows.addAll(apiClientRows(context, "P13-01"));
        return rows.stream().limit(200).toList();
    }

    private List<IntegrationScreen.Row> connectionRows(
            AuthorizedTenantContext context, String screenId, String providerKind) {
        return jdbc.query(
                """
                SELECT connection.id,connection.connection_code,connection.display_name,
                       connection.provider_kind,connection.contract_version,
                       connection.endpoint_reference,connection.security_profile_key,
                       connection.credential_reference_digest,connection.mapping_version_id,
                       mapping.mapping_code,mapping.version_number AS mapping_version,
                       connection.status,connection.lock_version,connection.created_at
                  FROM integration_connections connection
                  LEFT JOIN integration_mapping_versions mapping
                    ON mapping.organization_id=connection.organization_id
                   AND mapping.id=connection.mapping_version_id
                 WHERE connection.organization_id=?
                   AND (CAST(? AS varchar) IS NULL OR connection.provider_kind=?)
                 ORDER BY connection.created_at DESC,connection.id LIMIT 200
                """,
                (rs, index) -> {
                    var id = rs.getObject("id", UUID.class);
                    var status = rs.getString("status");
                    var actions = new ArrayList<String>();
                    if (SCREEN_KINDS.containsKey(screenId)) {
                        if (status.equals("draft") || status.equals("suspended")) {
                            actions.add("validate-connection");
                        }
                        if (status.equals("validated")) actions.add("suspend-connection");
                        if (status.equals("validated") || status.equals("suspended")) {
                            actions.add("retire-connection");
                        }
                    }
                    return row(screenId, id, id, null, null, null, null, null,
                            status, rs.getLong("lock_version"),
                            values("artifact", "connection", "code", rs.getString("connection_code"),
                                    "name", rs.getString("display_name"),
                                    "provider", rs.getString("provider_kind"),
                                    "contract", rs.getString("contract_version"),
                                    "endpointReference", rs.getString("endpoint_reference"),
                                    "securityProfile", rs.getString("security_profile_key"),
                                    "credentialReference", abbreviated(
                                            rs.getString("credential_reference_digest")),
                                    "mapping", mappingText(
                                            rs.getString("mapping_code"), rs.getObject("mapping_version")),
                                    "recordedAt", instantText(rs.getObject("created_at"))),
                            List.copyOf(actions));
                },
                context.organizationId(), providerKind, providerKind);
    }

    private List<IntegrationScreen.Row> mappingRows(
            AuthorizedTenantContext context, String screenId) {
        return jdbc.query(
                """
                SELECT id,mapping_code,mapping_kind,version_number,source_version,target_version,
                       fhir_release,profile_package,profile_version,terminology_version,
                       content_digest,status,lock_version,created_at
                  FROM integration_mapping_versions WHERE organization_id=?
                 ORDER BY created_at DESC,id LIMIT 200
                """,
                (rs, index) -> {
                    var id = rs.getObject("id", UUID.class);
                    var status = rs.getString("status");
                    var actions = screenId.equals("P13-03") && status.equals("draft")
                            ? List.of("activate-mapping")
                            : screenId.equals("P13-03") && status.equals("active")
                                    ? List.of("retire-mapping")
                                    : List.<String>of();
                    return row(screenId, id, null, id, null, null, null, null,
                            status, rs.getLong("lock_version"),
                            values("artifact", "mapping", "code", rs.getString("mapping_code"),
                                    "kind", rs.getString("mapping_kind"),
                                    "version", rs.getString("version_number"),
                                    "source", rs.getString("source_version"),
                                    "target", rs.getString("target_version"),
                                    "fhirRelease", rs.getString("fhir_release"),
                                    "profile", profileText(rs.getString("profile_package"),
                                            rs.getString("profile_version")),
                                    "terminology", rs.getString("terminology_version"),
                                    "digest", abbreviated(rs.getString("content_digest")),
                                    "recordedAt", instantText(rs.getObject("created_at"))),
                            actions);
                },
                context.organizationId());
    }

    private List<IntegrationScreen.Row> apiClientRows(
            AuthorizedTenantContext context, String screenId) {
        return jdbc.query(
                """
                SELECT id,client_code,display_name,public_identifier_digest,scopes_digest,
                       purpose_key,token_policy_version,status,access_enabled,lock_version,created_at
                  FROM integration_api_clients WHERE organization_id=?
                 ORDER BY created_at DESC,id LIMIT 200
                """,
                (rs, index) -> {
                    var id = rs.getObject("id", UUID.class);
                    var status = rs.getString("status");
                    var actions = screenId.equals("P13-09") && status.equals("registered")
                            ? List.of("revoke-api-client") : List.<String>of();
                    return row(screenId, id, null, null, null, null, id, null,
                            status, rs.getLong("lock_version"),
                            values("artifact", "api_client", "code", rs.getString("client_code"),
                                    "name", rs.getString("display_name"),
                                    "publicIdentifier", abbreviated(
                                            rs.getString("public_identifier_digest")),
                                    "scopes", abbreviated(rs.getString("scopes_digest")),
                                    "purpose", rs.getString("purpose_key"),
                                    "accessPolicy", rs.getString("token_policy_version"),
                                    "access", rs.getBoolean("access_enabled") ? "enabled" : "unavailable",
                                    "recordedAt", instantText(rs.getObject("created_at"))),
                            actions);
                },
                context.organizationId());
    }

    private List<IntegrationScreen.Row> auditRows(
            AuthorizedTenantContext context, String screenId) {
        var rows = new ArrayList<IntegrationScreen.Row>();
        rows.addAll(deliveryRows(context, screenId));
        rows.addAll(webhookRows(context, screenId));
        rows.addAll(fhirRows(context, screenId));
        rows.addAll(replayRows(context, screenId));
        return rows.stream().limit(200).toList();
    }

    private List<IntegrationScreen.Row> deliveryRows(
            AuthorizedTenantContext context, String screenId) {
        return jdbc.query(
                """
                SELECT delivery.id,delivery.connection_id,connection.connection_code,
                       delivery.event_name,delivery.schema_version,delivery.payload_digest,
                       delivery.predecessor_delivery_id,delivery.attempt_count,delivery.status,
                       delivery.last_error_code,delivery.lock_version,delivery.created_at,
                       NOT EXISTS(SELECT 1 FROM integration_replay_requests request
                           WHERE request.organization_id=delivery.organization_id
                             AND request.delivery_id=delivery.id
                             AND request.status='authorized'
                             AND request.expires_at>clock_timestamp()) replay_available
                  FROM integration_outbound_deliveries delivery
                  JOIN integration_connections connection
                    ON connection.organization_id=delivery.organization_id
                   AND connection.id=delivery.connection_id
                 WHERE delivery.organization_id=?
                 ORDER BY delivery.created_at DESC,delivery.id LIMIT 200
                """,
                (rs, index) -> {
                    var id = rs.getObject("id", UUID.class);
                    var status = rs.getString("status");
                    var actions = screenId.equals("P13-10") && status.equals("dead_lettered")
                                    && rs.getBoolean("replay_available")
                            ? List.of("authorize-replay") : List.<String>of();
                    return row(screenId, id, rs.getObject("connection_id", UUID.class), null,
                            id, null, null, null, status, rs.getLong("lock_version"),
                            values("artifact", "outbound_delivery",
                                    "connection", rs.getString("connection_code"),
                                    "event", rs.getString("event_name") + "@"
                                            + rs.getString("schema_version"),
                                    "payloadDigest", abbreviated(rs.getString("payload_digest")),
                                    "attempts", rs.getString("attempt_count"),
                                    "predecessor", uuidText(
                                            rs.getObject("predecessor_delivery_id", UUID.class)),
                                    "error", rs.getString("last_error_code"),
                                    "recordedAt", instantText(rs.getObject("created_at"))),
                            actions);
                },
                context.organizationId());
    }

    private List<IntegrationScreen.Row> webhookRows(
            AuthorizedTenantContext context, String screenId) {
        return jdbc.query(
                """
                SELECT receipt.id,receipt.connection_id,connection.connection_code,
                       receipt.signature_scheme,receipt.signature_key_version,
                       receipt.payload_digest,receipt.status,receipt.rejection_code,
                       receipt.received_at
                  FROM inbound_webhook_receipts receipt
                  JOIN integration_connections connection
                    ON connection.organization_id=receipt.organization_id
                   AND connection.id=receipt.connection_id
                 WHERE receipt.organization_id=?
                 ORDER BY receipt.received_at DESC,receipt.id LIMIT 200
                """,
                (rs, index) -> {
                    var id = rs.getObject("id", UUID.class);
                    return row(screenId, id, rs.getObject("connection_id", UUID.class), null,
                            null, null, null, null, rs.getString("status"), 0,
                            values("artifact", "webhook_receipt",
                                    "connection", rs.getString("connection_code"),
                                    "signature", rs.getString("signature_scheme") + "@"
                                            + rs.getString("signature_key_version"),
                                    "payloadDigest", abbreviated(rs.getString("payload_digest")),
                                    "error", rs.getString("rejection_code"),
                                    "recordedAt", instantText(rs.getObject("received_at"))),
                            List.of());
                },
                context.organizationId());
    }

    private List<IntegrationScreen.Row> fhirRows(
            AuthorizedTenantContext context, String screenId) {
        return jdbc.query(
                """
                SELECT exchange.id,exchange.connection_id,exchange.mapping_version_id,
                       connection.connection_code,exchange.direction,exchange.resource_type,
                       exchange.fhir_release,exchange.profile_package,exchange.profile_version,
                       exchange.resource_digest,exchange.validation_status,exchange.validation_code,
                       exchange.recorded_at
                  FROM fhir_exchange_records exchange
                  JOIN integration_connections connection
                    ON connection.organization_id=exchange.organization_id
                   AND connection.id=exchange.connection_id
                 WHERE exchange.organization_id=?
                 ORDER BY exchange.recorded_at DESC,exchange.id LIMIT 200
                """,
                (rs, index) -> {
                    var id = rs.getObject("id", UUID.class);
                    return row(screenId, id, rs.getObject("connection_id", UUID.class),
                            rs.getObject("mapping_version_id", UUID.class), null, null, null, id,
                            rs.getString("validation_status"), 0,
                            values("artifact", "fhir_exchange",
                                    "connection", rs.getString("connection_code"),
                                    "direction", rs.getString("direction"),
                                    "resource", rs.getString("resource_type"),
                                    "release", rs.getString("fhir_release"),
                                    "profile", profileText(rs.getString("profile_package"),
                                            rs.getString("profile_version")),
                                    "resourceDigest", abbreviated(rs.getString("resource_digest")),
                                    "validation", rs.getString("validation_code"),
                                    "recordedAt", instantText(rs.getObject("recorded_at"))),
                            List.of());
                },
                context.organizationId());
    }

    private List<IntegrationScreen.Row> replayRows(
            AuthorizedTenantContext context, String screenId) {
        return jdbc.query(
                """
                SELECT request.id,delivery.connection_id,request.delivery_id,
                       request.delivery_revision,request.expires_at,request.successor_delivery_id,
                       request.status,request.lock_version,request.created_at
                  FROM integration_replay_requests request
                  JOIN integration_outbound_deliveries delivery
                    ON delivery.organization_id=request.organization_id
                   AND delivery.id=request.delivery_id
                 WHERE request.organization_id=?
                 ORDER BY request.created_at DESC,request.id LIMIT 200
                """,
                (rs, index) -> {
                    var id = rs.getObject("id", UUID.class);
                    return row(screenId, id, rs.getObject("connection_id", UUID.class), null,
                            rs.getObject("delivery_id", UUID.class), id, null, null,
                            rs.getString("status"), rs.getLong("lock_version"),
                            values("artifact", "replay_authorization",
                                    "delivery", rs.getString("delivery_id"),
                                    "deliveryRevision", rs.getString("delivery_revision"),
                                    "expiresAt", instantText(rs.getObject("expires_at")),
                                    "successor", uuidText(
                                            rs.getObject("successor_delivery_id", UUID.class)),
                                    "recordedAt", instantText(rs.getObject("created_at"))),
                            List.of());
                },
                context.organizationId());
    }

    private List<IntegrationScreen.Metric> metrics(AuthorizedTenantContext context) {
        var summary = jdbc.queryForMap(
                """
                SELECT
                  (SELECT count(*) FROM integration_connections
                    WHERE organization_id=? AND status='validated') validated_connections,
                  (SELECT count(*) FROM integration_mapping_versions
                    WHERE organization_id=? AND status='active') active_mappings,
                  (SELECT count(*) FROM inbound_webhook_receipts
                    WHERE organization_id=? AND status='rejected') rejected_webhooks,
                  (SELECT count(*) FROM integration_outbound_deliveries
                    WHERE organization_id=? AND status='dead_lettered') dead_letters
                """,
                context.organizationId(), context.organizationId(), context.organizationId(),
                context.organizationId());
        return List.of(
                metric("validatedConnections", "Validated connections",
                        number(summary.get("validated_connections")), "info"),
                metric("activeMappings", "Active mappings",
                        number(summary.get("active_mappings")), "success"),
                metric("rejectedWebhooks", "Rejected webhooks",
                        number(summary.get("rejected_webhooks")), "warning"),
                metric("deadLetters", "Dead letters",
                        number(summary.get("dead_letters")), "danger"));
    }

    private static List<IntegrationScreen.Column> columns(String screenId) {
        return switch (screenId) {
            case "P13-03" -> List.of(column("code", "Mapping"), column("kind", "Kind"),
                    column("version", "Version"), column("profile", "Profile"));
            case "P13-09" -> List.of(column("code", "Client"), column("purpose", "Purpose"),
                    column("accessPolicy", "Access policy"), column("access", "Access"));
            case "P13-10" -> List.of(column("artifact", "Artifact"),
                    column("connection", "Connection"), column("event", "Event"),
                    column("recordedAt", "Recorded at"));
            default -> List.of(column("artifact", "Artifact"), column("code", "Code"),
                    column("provider", "Provider"), column("contract", "Contract"),
                    column("recordedAt", "Recorded at"));
        };
    }

    private static List<IntegrationScreen.Notice> notices(String screenId) {
        var notices = new ArrayList<IntegrationScreen.Notice>();
        notices.add(notice("warning", "Transport unavailable",
                "A validated registry entry does not activate an external adapter, credential or production connection."));
        notices.add(notice("info", "Payload-free evidence",
                "Secrets, access tokens, message bodies, FHIR resources and provider response bodies are not stored here."));
        if (Set.of("P13-02", "P13-03").contains(screenId)) {
            notices.add(notice("info", "Profile-first FHIR",
                    "FHIR remains an external representation; no broad generic server is exposed without approved profiles and policy."));
        }
        if (screenId.equals("P13-10")) {
            notices.add(notice("warning", "Successor-only replay",
                    "Dead-letter evidence remains terminal; authorization permits at most one exact-lineage successor delivery."));
        }
        return List.copyOf(notices);
    }

    private ConnectionRecord lockConnection(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,provider_kind,status,lock_version FROM integration_connections
                 WHERE organization_id=? AND id=? FOR UPDATE
                """,
                (rs, index) -> new ConnectionRecord(
                        rs.getObject("id", UUID.class), rs.getString("provider_kind"),
                        rs.getString("status"), rs.getLong("lock_version")),
                context.organizationId(), id);
        if (rows.isEmpty()) throw notFound("The integration connection is unavailable.");
        return rows.getFirst();
    }

    private MappingRecord lockMapping(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,status,lock_version FROM integration_mapping_versions
                 WHERE organization_id=? AND id=? FOR UPDATE
                """,
                (rs, index) -> new MappingRecord(
                        rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getLong("lock_version")),
                context.organizationId(), id);
        if (rows.isEmpty()) throw notFound("The integration mapping is unavailable.");
        return rows.getFirst();
    }

    private ApiClientRecord lockApiClient(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,status,lock_version FROM integration_api_clients
                 WHERE organization_id=? AND id=? FOR UPDATE
                """,
                (rs, index) -> new ApiClientRecord(
                        rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getLong("lock_version")),
                context.organizationId(), id);
        if (rows.isEmpty()) throw notFound("The API client is unavailable.");
        return rows.getFirst();
    }

    private DeliveryRecord lockDelivery(AuthorizedTenantContext context, UUID id) {
        var rows = jdbc.query(
                """
                SELECT id,connection_id,status,lock_version FROM integration_outbound_deliveries
                 WHERE organization_id=? AND id=? FOR UPDATE
                """,
                (rs, index) -> new DeliveryRecord(
                        rs.getObject("id", UUID.class), rs.getObject("connection_id", UUID.class),
                        rs.getString("status"), rs.getLong("lock_version")),
                context.organizationId(), id);
        if (rows.isEmpty()) throw notFound("The outbound delivery is unavailable.");
        return rows.getFirst();
    }

    private static boolean matches(ScreenQuery query, IntegrationScreen.Row row) {
        if (query.connectionId() != null && !query.connectionId().equals(row.connectionId())) return false;
        if (query.status() != null && !query.status().equalsIgnoreCase(row.status())) return false;
        if (query.search() == null) return true;
        var needle = query.search().toLowerCase(Locale.ROOT);
        return row.values().values().stream()
                .filter(Objects::nonNull)
                .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(needle));
    }

    private void requireOperationScope(AuthorizedTenantContext context) {
        var bound = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT nullif(current_setting('app.current_organization_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_actor_id',true),'')::uuid=?
                   AND nullif(current_setting('app.current_operation_key',true),'') IS NOT NULL
                """,
                Boolean.class, context.organizationId(), context.actorId()));
        if (!bound) {
            throw notFound("The integration resource is unavailable or is not assigned to this account.");
        }
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .toInstant();
    }

    private static IntegrationScreen.Row row(
            String screenId,
            UUID id,
            UUID connectionId,
            UUID mappingVersionId,
            UUID outboundDeliveryId,
            UUID replayRequestId,
            UUID apiClientId,
            UUID fhirExchangeId,
            String status,
            long revision,
            Map<String, String> values,
            List<String> actions) {
        return new IntegrationScreen.Row(
                id, connectionId, mappingVersionId, outboundDeliveryId, replayRequestId,
                apiClientId, fhirExchangeId, status, revision, etag(screenId, id, revision),
                values, actions);
    }

    private static String etag(String screenId, UUID id, long revision) {
        return "\"m13:" + screenId + ":" + id + ":" + revision + "\"";
    }

    private static MutationResult result(
            UUID subjectId,
            UUID connectionId,
            String subjectType,
            String auditEvent,
            String fromState,
            String toState,
            long revision,
            int statusCode) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("artifactId", subjectId);
        payload.put("artifactType", subjectType);
        payload.put("fromState", fromState);
        payload.put("toState", toState);
        payload.put("revision", revision);
        var immutable = Map.<String, Object>copyOf(payload);
        return new MutationResult(
                subjectId, connectionId, subjectType, auditEvent, OUTBOX_EVENT,
                "integration_artifact", subjectId, immutable, immutable, statusCode, revision);
    }

    private static void requireScreenKind(String screenId, String actualKind) {
        var expected = SCREEN_KINDS.get(screenId);
        if (expected == null || !expected.equals(actualKind)) {
            throw notFound("The integration connection is unavailable on this screen.");
        }
    }

    private static String field(MutationCommand command, String key) {
        var value = command.fields().get(key);
        if (value == null || value.isBlank()) throw invalid(key + " is required.");
        return value.strip();
    }

    private static String bounded(String value, int minimum, int maximum, String field) {
        if (value == null) throw invalid(field + " is required.");
        var normalized = value.strip();
        var length = normalized.codePointCount(0, normalized.length());
        if (length < minimum || length > maximum) {
            throw invalid(field + " must contain " + minimum + " to " + maximum + " characters.");
        }
        return normalized;
    }

    private static String optional(String value, int maximum, String field) {
        if (value == null || value.isBlank()) return null;
        return bounded(value, 1, maximum, field);
    }

    private static String code(String value, String field) {
        return code(value, field, 80);
    }

    private static String code(String value, String field, int maximum) {
        var normalized = bounded(value, 3, maximum, field).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9_.:-]*")) {
            throw invalid(field + " has an invalid format.");
        }
        return normalized;
    }

    private static String digest(String value, String field) {
        var normalized = value == null ? "" : value.strip();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw invalid(field + " must be a lowercase SHA-256 digest.");
        }
        return normalized;
    }

    private static UUID optionalUuid(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException exception) {
            throw invalid(field + " must contain a UUID.");
        }
    }

    private static int integer(String value, String field, int minimum, int maximum) {
        try {
            var parsed = Integer.parseInt(value.strip());
            if (parsed < minimum || parsed > maximum) throw invalid(field + " is outside its range.");
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid(field + " must be a whole number.");
        }
    }

    private static String oneOf(String value, String field, Set<String> options) {
        if (options.contains(value)) return value;
        throw invalid(field + " contains an unsupported value.");
    }

    private static void requireRevision(long actual, Long expected, String target) {
        if (expected == null) {
            throw new IntegrationException(
                    IntegrationException.Reason.PRECONDITION_REQUIRED,
                    "A strong " + target + " revision is required.");
        }
        if (actual != expected) {
            throw new IntegrationException(
                    IntegrationException.Reason.STALE,
                    "The " + target + " changed; reload before continuing.");
        }
    }

    private static void requireChanged(int changed, String message) {
        if (changed != 1) throw conflict(message);
    }

    private static Map<String, String> values(String... entries) {
        var values = new LinkedHashMap<String, String>();
        for (var index = 0; index < entries.length; index += 2) {
            values.put(entries[index], safe(entries[index + 1], "Not recorded"));
        }
        return Map.copyOf(values);
    }

    private static IntegrationScreen.Column column(String key, String label) {
        return new IntegrationScreen.Column(key, label);
    }

    private static IntegrationScreen.Metric metric(String key, String label, long value, String tone) {
        return new IntegrationScreen.Metric(key, label, value, tone);
    }

    private static IntegrationScreen.Notice notice(String tone, String title, String detail) {
        return new IntegrationScreen.Notice(tone, title, detail);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String abbreviated(String digest) {
        return digest == null || digest.length() < 12 ? "Not recorded" : digest.substring(0, 12) + "…";
    }

    private static String mappingText(String code, Object version) {
        return code == null ? "Not assigned" : code + "@" + Objects.toString(version, "?");
    }

    private static String profileText(String profile, String version) {
        return profile == null ? "Not applicable" : profile + "@" + safe(version, "?");
    }

    private static String uuidText(UUID value) {
        return value == null ? "None" : value.toString();
    }

    private static String instantText(Object value) {
        return value == null ? "Not recorded" : value.toString();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static IntegrationException invalid(String message) {
        return new IntegrationException(IntegrationException.Reason.INVALID, message);
    }

    private static IntegrationException notFound(String message) {
        return new IntegrationException(IntegrationException.Reason.NOT_FOUND, message);
    }

    private static IntegrationException conflict(String message) {
        return new IntegrationException(IntegrationException.Reason.CONFLICT, message);
    }

    private record ConnectionRecord(UUID id, String providerKind, String status, long revision) {}

    private record MappingRecord(UUID id, String status, long revision) {}

    private record ApiClientRecord(UUID id, String status, long revision) {}

    private record DeliveryRecord(UUID id, UUID connectionId, String status, long revision) {}
}
