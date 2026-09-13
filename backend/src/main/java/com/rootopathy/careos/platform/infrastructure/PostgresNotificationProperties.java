package com.rootopathy.careos.platform.infrastructure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("careos.notifications.postgres")
public final class PostgresNotificationProperties {
    private static final Pattern KEY_ID = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private static final Pattern TEMPLATE_DEFINITION = Pattern.compile(
            "([a-z][a-z0-9]*([.:-][a-z0-9]+)*)@([1-9][0-9]{0,8})");

    private boolean enabled;
    private String activeEncryptionKeyId = "";
    private List<String> encryptionKeys = new ArrayList<>();
    private Duration leaseDuration = Duration.ofSeconds(30);
    private int maximumAttempts = 5;
    private Duration initialRetryDelay = Duration.ofSeconds(5);
    private Duration maximumRetryDelay = Duration.ofMinutes(15);
    private Duration terminalRetention = Duration.ofDays(14);
    private Duration maximumScheduleAhead = Duration.ofDays(365);
    private int maximumClaimBatch = 25;
    private int cleanupBatch = 100;
    private List<String> allowedTemplateDefinitions = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getActiveEncryptionKeyId() {
        return activeEncryptionKeyId;
    }

    public void setActiveEncryptionKeyId(String activeEncryptionKeyId) {
        this.activeEncryptionKeyId = activeEncryptionKeyId;
    }

    public List<String> getEncryptionKeys() {
        return encryptionKeys;
    }

    public void setEncryptionKeys(List<String> encryptionKeys) {
        this.encryptionKeys = encryptionKeys;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public int getMaximumAttempts() {
        return maximumAttempts;
    }

    public void setMaximumAttempts(int maximumAttempts) {
        this.maximumAttempts = maximumAttempts;
    }

    public Duration getInitialRetryDelay() {
        return initialRetryDelay;
    }

    public void setInitialRetryDelay(Duration initialRetryDelay) {
        this.initialRetryDelay = initialRetryDelay;
    }

    public Duration getMaximumRetryDelay() {
        return maximumRetryDelay;
    }

    public void setMaximumRetryDelay(Duration maximumRetryDelay) {
        this.maximumRetryDelay = maximumRetryDelay;
    }

    public Duration getTerminalRetention() {
        return terminalRetention;
    }

    public void setTerminalRetention(Duration terminalRetention) {
        this.terminalRetention = terminalRetention;
    }

    public Duration getMaximumScheduleAhead() {
        return maximumScheduleAhead;
    }

    public void setMaximumScheduleAhead(Duration maximumScheduleAhead) {
        this.maximumScheduleAhead = maximumScheduleAhead;
    }

    public int getMaximumClaimBatch() {
        return maximumClaimBatch;
    }

    public void setMaximumClaimBatch(int maximumClaimBatch) {
        this.maximumClaimBatch = maximumClaimBatch;
    }

    public int getCleanupBatch() {
        return cleanupBatch;
    }

    public void setCleanupBatch(int cleanupBatch) {
        this.cleanupBatch = cleanupBatch;
    }

    public List<String> getAllowedTemplateDefinitions() {
        return allowedTemplateDefinitions;
    }

    public void setAllowedTemplateDefinitions(List<String> allowedTemplateDefinitions) {
        this.allowedTemplateDefinitions = allowedTemplateDefinitions;
    }

    public void validateForActivation() {
        if (!enabled) {
            throw new IllegalStateException("PostgreSQL notification store is not enabled");
        }
        var keys = validatedEncryptionKeys();
        if (activeEncryptionKeyId == null
                || !KEY_ID.matcher(activeEncryptionKeyId).matches()
                || !keys.containsKey(activeEncryptionKeyId)) {
            throw new IllegalStateException(
                    "PostgreSQL notification active encryption key is not configured");
        }
        requireDuration(
                leaseDuration, Duration.ofMillis(100), Duration.ofHours(1), "lease duration");
        if (maximumAttempts < 1 || maximumAttempts > 100) {
            throw new IllegalStateException(
                    "PostgreSQL notification maximum attempts is outside the supported range");
        }
        requireDuration(
                initialRetryDelay,
                Duration.ofMillis(10),
                Duration.ofHours(1),
                "initial retry delay");
        requireDuration(
                maximumRetryDelay,
                initialRetryDelay,
                Duration.ofDays(1),
                "maximum retry delay");
        requireDuration(
                terminalRetention, Duration.ofMinutes(1), Duration.ofDays(90), "terminal retention");
        requireDuration(
                maximumScheduleAhead,
                Duration.ofHours(1),
                Duration.ofDays(365),
                "maximum schedule ahead");
        if (maximumClaimBatch < 1 || maximumClaimBatch > 100) {
            throw new IllegalStateException(
                    "PostgreSQL notification maximum claim batch is outside the supported range");
        }
        if (cleanupBatch < 1 || cleanupBatch > 1000) {
            throw new IllegalStateException(
                    "PostgreSQL notification cleanup batch is outside the supported range");
        }
        validatedAllowedTemplateDefinitions();
    }

    public Map<String, SecretKeySpec> validatedEncryptionKeys() {
        if (encryptionKeys == null || encryptionKeys.isEmpty() || encryptionKeys.size() > 8) {
            throw new IllegalStateException(
                    "PostgreSQL notification encryption keys must contain between 1 and 8 entries");
        }
        var decodedKeys = new LinkedHashMap<String, SecretKeySpec>();
        for (var entry : encryptionKeys) {
            int separator = entry == null ? -1 : entry.indexOf(':');
            if (separator < 1 || separator == entry.length() - 1) {
                throw new IllegalStateException(
                        "PostgreSQL notification encryption key has an invalid format");
            }
            var keyId = entry.substring(0, separator);
            if (!KEY_ID.matcher(keyId).matches() || decodedKeys.containsKey(keyId)) {
                throw new IllegalStateException(
                        "PostgreSQL notification encryption key identifier is invalid or duplicated");
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(entry.substring(separator + 1));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "PostgreSQL notification encryption key is not valid Base64", exception);
            }
            try {
                if (decoded.length != 32) {
                    throw new IllegalStateException(
                            "PostgreSQL notification encryption keys must be 256-bit");
                }
                decodedKeys.put(keyId, new SecretKeySpec(decoded, "AES"));
            } finally {
                Arrays.fill(decoded, (byte) 0);
            }
        }
        return Map.copyOf(decodedKeys);
    }

    public Set<String> validatedAllowedTemplateDefinitions() {
        if (allowedTemplateDefinitions == null
                || allowedTemplateDefinitions.isEmpty()
                || allowedTemplateDefinitions.size() > 256) {
            throw new IllegalStateException(
                    "PostgreSQL notification allowed definitions must contain between 1 and 256 entries");
        }
        var validated = new LinkedHashSet<String>();
        for (var definition : allowedTemplateDefinitions) {
            if (definition == null
                    || definition.length() > 176
                    || !TEMPLATE_DEFINITION.matcher(definition).matches()) {
                throw new IllegalStateException(
                        "PostgreSQL notification template definition has an invalid format");
            }
            if (!validated.add(definition)) {
                throw new IllegalStateException(
                        "PostgreSQL notification template definitions must not contain duplicates");
            }
        }
        return Set.copyOf(validated);
    }

    @Override
    public String toString() {
        return "PostgresNotificationProperties[enabled=" + enabled
                + ", activeEncryptionKeyId=" + activeEncryptionKeyId
                + ", encryptionKeyCount=" + (encryptionKeys == null ? 0 : encryptionKeys.size())
                + ", leaseDuration=" + leaseDuration
                + ", maximumAttempts=" + maximumAttempts
                + ", initialRetryDelay=" + initialRetryDelay
                + ", maximumRetryDelay=" + maximumRetryDelay
                + ", terminalRetention=" + terminalRetention
                + ", maximumScheduleAhead=" + maximumScheduleAhead
                + ", maximumClaimBatch=" + maximumClaimBatch
                + ", cleanupBatch=" + cleanupBatch
                + ", allowedTemplateDefinitionCount="
                + (allowedTemplateDefinitions == null ? 0 : allowedTemplateDefinitions.size()) + "]";
    }

    private static void requireDuration(
            Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalStateException(
                    "PostgreSQL notification " + name + " is outside the supported range");
        }
    }
}
