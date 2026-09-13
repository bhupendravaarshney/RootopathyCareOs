package com.rootopathy.careos.platform.infrastructure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("careos.jobs.redis")
public final class RedisJobQueueProperties {
    private static final Pattern KEY_PREFIX =
            Pattern.compile("[a-z][a-z0-9]*(?::[a-z0-9]+)*");
    private static final Pattern JOB_DEFINITION = Pattern.compile(
            "([a-z][a-z0-9]*([.:-][a-z0-9]+)*)@([1-9][0-9]{0,8})");

    private boolean enabled;
    private String keyPrefix = "careos:jobs";
    private Duration leaseDuration = Duration.ofSeconds(30);
    private int maximumAttempts = 5;
    private Duration initialRetryDelay = Duration.ofSeconds(5);
    private Duration maximumRetryDelay = Duration.ofMinutes(15);
    private Duration terminalRetention = Duration.ofDays(14);
    private Duration maximumScheduleAhead = Duration.ofDays(365);
    private int maximumClaimBatch = 25;
    private int cleanupBatch = 100;
    private List<String> allowedJobDefinitions = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
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

    public List<String> getAllowedJobDefinitions() {
        return allowedJobDefinitions;
    }

    public void setAllowedJobDefinitions(List<String> allowedJobDefinitions) {
        this.allowedJobDefinitions = allowedJobDefinitions;
    }

    public Set<String> validatedAllowedJobDefinitions() {
        if (allowedJobDefinitions == null
                || allowedJobDefinitions.isEmpty()
                || allowedJobDefinitions.size() > 256) {
            throw new IllegalStateException(
                    "Redis job allowed definitions must contain between 1 and 256 entries");
        }
        var validated = new LinkedHashSet<String>();
        for (var definition : allowedJobDefinitions) {
            if (definition == null
                    || definition.length() > 176
                    || !JOB_DEFINITION.matcher(definition).matches()) {
                throw new IllegalStateException("Redis job definition has an invalid format");
            }
            if (!validated.add(definition)) {
                throw new IllegalStateException("Redis job definitions must not contain duplicates");
            }
        }
        return Set.copyOf(validated);
    }

    public void validateForActivation() {
        if (!enabled) {
            throw new IllegalStateException("Redis job queue is not enabled");
        }
        if (keyPrefix == null
                || keyPrefix.length() > 96
                || !KEY_PREFIX.matcher(keyPrefix).matches()) {
            throw new IllegalStateException("Redis job key prefix has an invalid format");
        }
        requireDuration(
                leaseDuration, Duration.ofMillis(100), Duration.ofHours(1), "lease duration");
        if (maximumAttempts < 1 || maximumAttempts > 100) {
            throw new IllegalStateException("Redis job maximum attempts is outside the supported range");
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
            throw new IllegalStateException("Redis job maximum claim batch is outside the supported range");
        }
        if (cleanupBatch < 1 || cleanupBatch > 1000) {
            throw new IllegalStateException("Redis job cleanup batch is outside the supported range");
        }
        validatedAllowedJobDefinitions();
    }

    @Override
    public String toString() {
        return "RedisJobQueueProperties[enabled=" + enabled
                + ", keyPrefix=" + keyPrefix
                + ", leaseDuration=" + leaseDuration
                + ", maximumAttempts=" + maximumAttempts
                + ", initialRetryDelay=" + initialRetryDelay
                + ", maximumRetryDelay=" + maximumRetryDelay
                + ", terminalRetention=" + terminalRetention
                + ", maximumScheduleAhead=" + maximumScheduleAhead
                + ", maximumClaimBatch=" + maximumClaimBatch
                + ", cleanupBatch=" + cleanupBatch
                + ", allowedJobDefinitionCount="
                + (allowedJobDefinitions == null ? 0 : allowedJobDefinitions.size()) + "]";
    }

    private static void requireDuration(
            Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalStateException("Redis job " + name + " is outside the supported range");
        }
    }
}
