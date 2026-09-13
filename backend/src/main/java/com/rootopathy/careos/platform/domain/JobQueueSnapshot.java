package com.rootopathy.careos.platform.domain;

/** Payload-free tenant queue telemetry. Counts include scheduled work in readyJobs. */
public record JobQueueSnapshot(
        long readyJobs, long leasedJobs, long deadLetteredJobs, long retainedCompletedJobs) {
    public JobQueueSnapshot {
        if (readyJobs < 0 || leasedJobs < 0 || deadLetteredJobs < 0 || retainedCompletedJobs < 0) {
            throw new IllegalArgumentException("queue counts must not be negative");
        }
    }
}
