package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.PlatformCapability;
import java.util.Objects;

public final class PlatformCapabilityUnavailableException extends IllegalStateException {
    private final PlatformCapability capability;
    private final String reasonCode;

    public PlatformCapabilityUnavailableException(PlatformCapability capability, String reasonCode) {
        super("Platform capability " + Objects.requireNonNull(capability, "capability").key()
                + " is unavailable (" + Objects.requireNonNull(reasonCode, "reasonCode") + ")");
        this.capability = capability;
        this.reasonCode = reasonCode;
    }

    public PlatformCapability capability() {
        return capability;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
