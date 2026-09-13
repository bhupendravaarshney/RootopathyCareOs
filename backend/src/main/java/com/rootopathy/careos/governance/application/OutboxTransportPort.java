package com.rootopathy.careos.governance.application;

import com.rootopathy.careos.governance.domain.OutboxEnvelope;

/** A destination-specific adapter must preserve the event id for downstream deduplication. */
public interface OutboxTransportPort {
    void publish(OutboxEnvelope event) throws OutboxTransportException;
}
