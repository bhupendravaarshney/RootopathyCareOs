package com.rootopathy.careos.platform.infrastructure;

import com.rootopathy.careos.platform.domain.DocumentRetentionPolicy;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("careos.documents.retention")
public final class DocumentRetentionProperties {
    private boolean enabled;
    private String policyKey;
    private Set<String> acceptedPurposes = new LinkedHashSet<>();
    private Duration minimumRetention;
    private Duration maximumRetention;
    private Duration maximumAuthorizationAge = Duration.ofSeconds(30);
    private Duration maximumFutureSkew = Duration.ofSeconds(2);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPolicyKey() {
        return policyKey;
    }

    public void setPolicyKey(String policyKey) {
        this.policyKey = policyKey;
    }

    public Set<String> getAcceptedPurposes() {
        return acceptedPurposes;
    }

    public void setAcceptedPurposes(Set<String> acceptedPurposes) {
        this.acceptedPurposes = acceptedPurposes;
    }

    public Duration getMinimumRetention() {
        return minimumRetention;
    }

    public void setMinimumRetention(Duration minimumRetention) {
        this.minimumRetention = minimumRetention;
    }

    public Duration getMaximumRetention() {
        return maximumRetention;
    }

    public void setMaximumRetention(Duration maximumRetention) {
        this.maximumRetention = maximumRetention;
    }

    public Duration getMaximumAuthorizationAge() {
        return maximumAuthorizationAge;
    }

    public void setMaximumAuthorizationAge(Duration maximumAuthorizationAge) {
        this.maximumAuthorizationAge = maximumAuthorizationAge;
    }

    public Duration getMaximumFutureSkew() {
        return maximumFutureSkew;
    }

    public void setMaximumFutureSkew(Duration maximumFutureSkew) {
        this.maximumFutureSkew = maximumFutureSkew;
    }

    public DocumentRetentionPolicy policy() {
        if (!enabled) {
            throw new IllegalStateException("Document retention is not enabled");
        }
        try {
            return new DocumentRetentionPolicy(
                    policyKey,
                    acceptedPurposes,
                    minimumRetention,
                    maximumRetention,
                    maximumAuthorizationAge,
                    maximumFutureSkew);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("Document retention policy is invalid", exception);
        }
    }

    @Override
    public String toString() {
        return "DocumentRetentionProperties[enabled=" + enabled
                + ", policyKey=" + policyKey
                + ", acceptedPurposes=" + acceptedPurposes
                + ", minimumRetention=" + minimumRetention
                + ", maximumRetention=" + maximumRetention
                + ", maximumAuthorizationAge=" + maximumAuthorizationAge
                + ", maximumFutureSkew=" + maximumFutureSkew + "]";
    }
}
