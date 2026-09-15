package com.rootopathy.careos.platform.infrastructure;

import com.rootopathy.careos.platform.domain.DocumentPromotionPolicy;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("careos.documents.promotion")
public final class DocumentPromotionProperties {
    private boolean enabled;
    private String policyKey;
    private Set<String> acceptedScannerKeys = new LinkedHashSet<>();
    private Duration maximumScanAge;
    private Duration maximumFutureSkew = Duration.ZERO;

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

    public Set<String> getAcceptedScannerKeys() {
        return acceptedScannerKeys;
    }

    public void setAcceptedScannerKeys(Set<String> acceptedScannerKeys) {
        this.acceptedScannerKeys = acceptedScannerKeys;
    }

    public Duration getMaximumScanAge() {
        return maximumScanAge;
    }

    public void setMaximumScanAge(Duration maximumScanAge) {
        this.maximumScanAge = maximumScanAge;
    }

    public Duration getMaximumFutureSkew() {
        return maximumFutureSkew;
    }

    public void setMaximumFutureSkew(Duration maximumFutureSkew) {
        this.maximumFutureSkew = maximumFutureSkew;
    }

    public DocumentPromotionPolicy policy() {
        if (!enabled) {
            throw new IllegalStateException("Document promotion is not enabled");
        }
        try {
            return new DocumentPromotionPolicy(
                    policyKey, acceptedScannerKeys, maximumScanAge, maximumFutureSkew);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("Document promotion policy is invalid", exception);
        }
    }

    @Override
    public String toString() {
        return "DocumentPromotionProperties[enabled=" + enabled
                + ", policyKey=" + policyKey
                + ", acceptedScannerKeys=" + acceptedScannerKeys
                + ", maximumScanAge=" + maximumScanAge
                + ", maximumFutureSkew=" + maximumFutureSkew + "]";
    }
}
