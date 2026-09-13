package com.rootopathy.careos.platform.domain;

public enum PlatformCapability {
    PRIVATE_DOCUMENT_QUARANTINE("private-document-quarantine"),
    MALWARE_SCANNING("malware-scanning"),
    DOCUMENT_PROMOTION("document-promotion"),
    SIGNED_DOCUMENT_ACCESS("signed-document-access"),
    DOCUMENT_RETENTION("document-retention"),
    DURABLE_NOTIFICATION_DELIVERY("durable-notification-delivery"),
    REDIS_JOB_QUEUE("redis-job-queue"),
    WORKER_EXECUTION("worker-execution"),
    SCHEDULER_EXECUTION("scheduler-execution");

    private final String key;

    PlatformCapability(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
