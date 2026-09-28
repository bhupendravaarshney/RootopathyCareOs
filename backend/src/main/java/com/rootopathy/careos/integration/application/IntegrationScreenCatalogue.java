package com.rootopathy.careos.integration.application;

import com.rootopathy.careos.integration.domain.IntegrationScreen;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Repository-owned contract for the ten Module 13 integration screens. */
final class IntegrationScreenCatalogue {
    private static final List<String> TITLES = List.of(
            "Integration dashboard",
            "FHIR endpoints",
            "Terminology mappings",
            "WhatsApp messaging",
            "Payment configuration",
            "Calendar integration",
            "Lab/imaging interfaces",
            "Webhook management",
            "Mobile/API clients",
            "Integration audit and replay");
    private static final Map<String, ScreenSpec> SCREENS = screens();

    private IntegrationScreenCatalogue() {}

    static ScreenSpec screen(String id) {
        var screen = SCREENS.get(id);
        if (screen == null) {
            throw new IntegrationException(
                    IntegrationException.Reason.NOT_FOUND,
                    "The requested integration screen does not exist.");
        }
        return screen;
    }

    static ActionSpec mutation(String screenId, String actionKey) {
        return screen(screenId).actions().stream()
                .filter(action -> action.key().equals(actionKey))
                .findFirst()
                .orElseThrow(() -> new IntegrationException(
                        IntegrationException.Reason.NOT_FOUND,
                        "The requested integration action does not exist."));
    }

    static List<IntegrationScreen.Action> projectedActions(
            ScreenSpec screen, Set<String> permissions) {
        return screen.actions().stream()
                .filter(action -> permissions.contains(action.permission()))
                .map(ActionSpec::projection)
                .toList();
    }

    static List<String> titles() {
        return TITLES;
    }

    record ScreenSpec(
            String id,
            String title,
            String purpose,
            String providerKind,
            String readOperation,
            List<ActionSpec> actions) {}

    record ActionSpec(
            String key,
            String label,
            String operation,
            String permission,
            String style,
            boolean targetRequired,
            boolean ifMatchRequired,
            boolean reasonRequired,
            List<IntegrationScreen.Field> fields) {
        IntegrationScreen.Action projection() {
            return new IntegrationScreen.Action(
                    key, label, style, targetRequired, ifMatchRequired, reasonRequired, fields);
        }
    }

    private static Map<String, ScreenSpec> screens() {
        var screens = new LinkedHashMap<String, ScreenSpec>();
        add(screens, 1,
                "Review secret-free connection readiness, validation failures, dead letters and replay decisions.",
                null);
        add(screens, 2,
                "Govern purpose-specific FHIR endpoints with exact active profiles and no generic server.",
                "fhir", connectionActions());
        add(screens, 3,
                "Create and activate immutable FHIR, terminology and partner mapping versions.",
                null, mappingActions());
        add(screens, 4,
                "Govern WhatsApp contract metadata without templates, destinations, tokens or message content.",
                "whatsapp", connectionActions());
        add(screens, 5,
                "Govern payment-provider contract metadata without card data, tokens or callback secrets.",
                "payment", connectionActions());
        add(screens, 6,
                "Govern calendar contract metadata without calendar content, OAuth tokens or participant data.",
                "calendar", connectionActions());
        add(screens, 7,
                "Govern laboratory and imaging contracts with exact mappings and payload-free provenance.",
                "lab_imaging", connectionActions());
        add(screens, 8,
                "Govern signed inbound webhook definitions and review digest-only replay/idempotency evidence.",
                "webhook", connectionActions());
        add(screens, 9,
                "Register disabled-by-default mobile/API client metadata without secrets or bearer tokens.",
                null, apiClientActions());
        add(screens, 10,
                "Review payload-free delivery, validation and dead-letter evidence and authorize one safe successor replay.",
                null,
                action("authorize-replay", "Authorize replay", "integration.replay.authorize", true, true));
        return Map.copyOf(screens);
    }

    private static ActionSpec[] connectionActions() {
        return new ActionSpec[] {
            action("create-connection", "Create connection", "integration.connection.create", false, false,
                    text("connectionCode", "Connection code", true),
                    text("displayName", "Display name", true),
                    text("contractVersion", "Contract version", true),
                    text("endpointReference", "Endpoint reference", true),
                    text("securityProfileKey", "Security profile", true),
                    digest("credentialReferenceDigest", "Credential reference digest", true),
                    uuid("mappingVersionId", "Mapping version", false)),
            action("validate-connection", "Validate configuration", "integration.connection.validate", true, true),
            action("suspend-connection", "Suspend connection", "integration.connection.suspend", true, true),
            action("retire-connection", "Retire connection", "integration.connection.retire", true, true)
        };
    }

    private static ActionSpec[] mappingActions() {
        return new ActionSpec[] {
            action("create-mapping", "Create mapping version", "integration.mapping.create", false, false,
                    text("mappingCode", "Mapping code", true),
                    select("mappingKind", "Mapping kind", true,
                            "fhir", "FHIR", "terminology", "Terminology", "message", "Message",
                            "payment", "Payment", "calendar", "Calendar", "diagnostic", "Diagnostic",
                            "api", "API"),
                    number("versionNumber", "Version", true),
                    text("sourceVersion", "Source version", true),
                    text("targetVersion", "Target version", true),
                    text("fhirRelease", "FHIR release", false),
                    text("profilePackage", "Profile package", false),
                    text("profileVersion", "Profile version", false),
                    text("terminologyVersion", "Terminology version", false),
                    digest("contentDigest", "Content digest", true)),
            action("activate-mapping", "Activate mapping", "integration.mapping.activate", true, true),
            action("retire-mapping", "Retire mapping", "integration.mapping.retire", true, true)
        };
    }

    private static ActionSpec[] apiClientActions() {
        return new ActionSpec[] {
            action("register-api-client", "Register client", "integration.api_client.create", false, false,
                    text("clientCode", "Client code", true),
                    text("displayName", "Display name", true),
                    digest("publicIdentifierDigest", "Public identifier digest", true),
                    digest("credentialReferenceDigest", "Credential reference digest", true),
                    digest("scopesDigest", "Scopes digest", true),
                    text("purposeKey", "Purpose", true),
                    text("accessPolicyVersion", "Access policy version", true)),
            action("revoke-api-client", "Revoke client", "integration.api_client.revoke", true, true)
        };
    }

    private static void add(
            Map<String, ScreenSpec> target,
            int sequence,
            String purpose,
            String providerKind,
            ActionSpec... actions) {
        var id = "P13-%02d".formatted(sequence);
        target.put(id, new ScreenSpec(
                id, TITLES.get(sequence - 1), purpose, providerKind,
                "integration.read", List.of(actions)));
    }

    private static ActionSpec action(
            String key,
            String label,
            String operation,
            boolean targetRequired,
            boolean ifMatchRequired,
            IntegrationScreen.Field... fields) {
        return new ActionSpec(
                key, label, operation, operation, "primary", targetRequired,
                ifMatchRequired, true, List.of(fields));
    }

    private static IntegrationScreen.Field text(String key, String label, boolean required) {
        return new IntegrationScreen.Field(key, label, "text", required, null, List.of());
    }

    private static IntegrationScreen.Field uuid(String key, String label, boolean required) {
        return new IntegrationScreen.Field(key, label, "uuid", required, null, List.of());
    }

    private static IntegrationScreen.Field digest(String key, String label, boolean required) {
        return new IntegrationScreen.Field(
                key, label, "text", required, "Lowercase SHA-256; never paste secret material.", List.of());
    }

    private static IntegrationScreen.Field number(String key, String label, boolean required) {
        return new IntegrationScreen.Field(key, label, "number", required, null, List.of());
    }

    private static IntegrationScreen.Field select(
            String key, String label, boolean required, String... entries) {
        var options = new ArrayList<IntegrationScreen.Option>();
        for (var index = 0; index < entries.length; index += 2) {
            options.add(new IntegrationScreen.Option(entries[index], entries[index + 1]));
        }
        return new IntegrationScreen.Field(key, label, "select", required, null, options);
    }
}
