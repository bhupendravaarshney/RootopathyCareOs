package com.rootopathy.careos.governance.domain;

public record OutboxPublicationSummary(
        int claimed, int published, int rescheduled, int deadLettered, int leaseLost) {
    public OutboxPublicationSummary {
        if (claimed < 0 || published < 0 || rescheduled < 0 || deadLettered < 0 || leaseLost < 0) {
            throw new IllegalArgumentException("publication counts cannot be negative");
        }
        if (claimed != published + rescheduled + deadLettered + leaseLost) {
            throw new IllegalArgumentException("publication counts do not balance");
        }
    }
}
