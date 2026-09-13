package com.rootopathy.careos.platform.domain;

import java.util.Objects;

public record CapabilityStatus(
        PlatformCapability capability, CapabilityAvailability availability, String reasonCode) {
    public CapabilityStatus {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(availability, "availability");
        reasonCode = PlatformValues.key(reasonCode, "reasonCode", 160);
    }
}
