package com.rootopathy.careos.platform.infrastructure;

import com.rootopathy.careos.platform.domain.DocumentAccessPolicy;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("careos.documents.signed-access")
public final class SignedDocumentAccessProperties {
    private boolean enabled;
    private String policyKey;
    private Set<String> acceptedPurposes = new LinkedHashSet<>();
    private Duration maximumTtl;
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

    public Duration getMaximumTtl() {
        return maximumTtl;
    }

    public void setMaximumTtl(Duration maximumTtl) {
        this.maximumTtl = maximumTtl;
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

    public DocumentAccessPolicy policy() {
        if (!enabled) {
            throw new IllegalStateException("Signed document access is not enabled");
        }
        try {
            return new DocumentAccessPolicy(
                    policyKey,
                    acceptedPurposes,
                    maximumTtl,
                    maximumAuthorizationAge,
                    maximumFutureSkew);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("Signed document access policy is invalid", exception);
        }
    }

    @Override
    public String toString() {
        return "SignedDocumentAccessProperties[enabled=" + enabled
                + ", policyKey=" + policyKey
                + ", acceptedPurposes=" + acceptedPurposes
                + ", maximumTtl=" + maximumTtl
                + ", maximumAuthorizationAge=" + maximumAuthorizationAge
                + ", maximumFutureSkew=" + maximumFutureSkew + "]";
    }
}
