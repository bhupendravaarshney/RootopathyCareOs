package com.rootopathy.careos.platform.infrastructure;

import com.rootopathy.careos.platform.application.PlatformCapabilityRegistry;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

/** Exposes only stable state/reason codes; endpoints, bucket names, destinations, and secrets stay hidden. */
public final class PlatformCapabilitiesInfoContributor implements InfoContributor {
    private final PlatformCapabilityRegistry registry;

    public PlatformCapabilitiesInfoContributor(PlatformCapabilityRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void contribute(Info.Builder builder) {
        var capabilities = new LinkedHashMap<String, Map<String, String>>();
        for (var status : registry.statuses()) {
            capabilities.put(
                    status.capability().key(),
                    Map.of(
                            "availability",
                            status.availability().name().toLowerCase(Locale.ROOT),
                            "reasonCode",
                            status.reasonCode()));
        }
        builder.withDetail("platformCapabilities", capabilities);
    }
}
