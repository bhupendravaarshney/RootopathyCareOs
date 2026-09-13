package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.CapabilityAvailability;
import com.rootopathy.careos.platform.domain.CapabilityStatus;
import com.rootopathy.careos.platform.domain.PlatformCapability;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/**
 * Startup contract for optional infrastructure. Every known capability must have exactly one probe;
 * an absent or ambiguous adapter is a configuration error rather than an implicit fallback.
 */
public final class PlatformCapabilityRegistry {
    private final EnumMap<PlatformCapability, CapabilityStatus> statuses;

    public PlatformCapabilityRegistry(List<CapabilityProbe> probes) {
        Objects.requireNonNull(probes, "probes");
        statuses = new EnumMap<>(PlatformCapability.class);
        for (var probe : probes) {
            var status = Objects.requireNonNull(
                    Objects.requireNonNull(probe, "probe").status(), "probe status");
            if (statuses.putIfAbsent(status.capability(), status) != null) {
                throw new IllegalStateException(
                        "Multiple adapters registered for platform capability " + status.capability().key());
            }
        }

        var missing = new ArrayList<String>();
        for (var capability : PlatformCapability.values()) {
            if (!statuses.containsKey(capability)) {
                missing.add(capability.key());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "No adapter registered for platform capabilities: " + String.join(", ", missing));
        }
    }

    public List<CapabilityStatus> statuses() {
        return List.copyOf(statuses.values());
    }

    public CapabilityStatus status(PlatformCapability capability) {
        return statuses.get(Objects.requireNonNull(capability, "capability"));
    }

    public void requireAvailable(PlatformCapability capability) {
        var status = status(capability);
        if (status.availability() != CapabilityAvailability.AVAILABLE) {
            throw new PlatformCapabilityUnavailableException(status.capability(), status.reasonCode());
        }
    }
}
