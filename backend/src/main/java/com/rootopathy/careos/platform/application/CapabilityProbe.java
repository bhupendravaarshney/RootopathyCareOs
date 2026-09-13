package com.rootopathy.careos.platform.application;

import com.rootopathy.careos.platform.domain.CapabilityStatus;

/** Every configured platform adapter must provide a non-secret readiness status. */
public interface CapabilityProbe {
    CapabilityStatus status();
}
