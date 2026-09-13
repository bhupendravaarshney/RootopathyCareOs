package com.rootopathy.careos.platform.infrastructure;

import com.rootopathy.careos.platform.application.CapabilityProbe;
import com.rootopathy.careos.platform.application.DocumentPromotionPort;
import com.rootopathy.careos.platform.application.DocumentRetentionPort;
import com.rootopathy.careos.platform.application.DurableNotificationPort;
import com.rootopathy.careos.platform.application.JobQueuePort;
import com.rootopathy.careos.platform.application.MalwareScannerPort;
import com.rootopathy.careos.platform.application.PlatformCapabilityUnavailableException;
import com.rootopathy.careos.platform.application.PrivateDocumentStoragePort;
import com.rootopathy.careos.platform.application.SchedulerExecutionPort;
import com.rootopathy.careos.platform.application.SignedDocumentAccessPort;
import com.rootopathy.careos.platform.application.WorkerExecutionPort;
import com.rootopathy.careos.platform.domain.CapabilityAvailability;
import com.rootopathy.careos.platform.domain.CapabilityStatus;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.platform.domain.DocumentRetentionDirective;
import com.rootopathy.careos.platform.domain.DurableJob;
import com.rootopathy.careos.platform.domain.DurableJobClaim;
import com.rootopathy.careos.platform.domain.DurableNotification;
import com.rootopathy.careos.platform.domain.DurableNotificationClaim;
import com.rootopathy.careos.platform.domain.JobFailureDisposition;
import com.rootopathy.careos.platform.domain.JobQueueSnapshot;
import com.rootopathy.careos.platform.domain.MalwareScanResult;
import com.rootopathy.careos.platform.domain.NotificationFailureDisposition;
import com.rootopathy.careos.platform.domain.NotificationQueueSnapshot;
import com.rootopathy.careos.platform.domain.PlatformCapability;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Explicit defaults for infrastructure that has not been approved and configured. These adapters
 * never degrade to local disk, unscanned content, synchronous mail, in-memory queues, or web-node
 * scheduling.
 */
public final class UnavailablePlatformAdapters {
    private UnavailablePlatformAdapters() {}

    public static final class PrivateDocumentStorage extends UnavailableAdapter
            implements PrivateDocumentStoragePort {
        public PrivateDocumentStorage() {
            super(
                    PlatformCapability.PRIVATE_DOCUMENT_QUARANTINE,
                    "object-storage-adapter-not-configured");
        }

        @Override
        public DocumentObjectReference quarantine(
                AuthorizedTenantContext context,
                DocumentQuarantineRequest request,
                InputStream content) {
            throw unavailable();
        }
    }

    public static final class MalwareScanner extends UnavailableAdapter implements MalwareScannerPort {
        public MalwareScanner() {
            super(PlatformCapability.MALWARE_SCANNING, "malware-scanner-adapter-not-configured");
        }

        @Override
        public MalwareScanResult scan(
                AuthorizedTenantContext context, DocumentObjectReference document) {
            throw unavailable();
        }
    }

    public static final class DocumentPromotion extends UnavailableAdapter
            implements DocumentPromotionPort {
        public DocumentPromotion() {
            super(PlatformCapability.DOCUMENT_PROMOTION, "document-promotion-not-configured");
        }

        @Override
        public DocumentObjectReference promote(
                AuthorizedTenantContext context, MalwareScanResult cleanScanEvidence) {
            throw unavailable();
        }
    }

    public static final class SignedDocumentAccess extends UnavailableAdapter
            implements SignedDocumentAccessPort {
        public SignedDocumentAccess() {
            super(PlatformCapability.SIGNED_DOCUMENT_ACCESS, "signed-access-adapter-not-configured");
        }

        @Override
        public URI createReadUrl(
                AuthorizedTenantContext context,
                DocumentObjectReference document,
                Duration requestedTtl) {
            throw unavailable();
        }
    }

    public static final class DocumentRetention extends UnavailableAdapter
            implements DocumentRetentionPort {
        public DocumentRetention() {
            super(PlatformCapability.DOCUMENT_RETENTION, "retention-adapter-not-configured");
        }

        @Override
        public void apply(
                AuthorizedTenantContext context,
                DocumentObjectReference document,
                DocumentRetentionDirective directive) {
            throw unavailable();
        }
    }

    public static final class DurableNotificationDelivery extends UnavailableAdapter
            implements DurableNotificationPort {
        public DurableNotificationDelivery() {
            super(
                    PlatformCapability.DURABLE_NOTIFICATION_DELIVERY,
                    "durable-notification-adapter-not-configured");
        }

        @Override
        public void enqueue(AuthorizedTenantContext context, DurableNotification notification) {
            throw unavailable();
        }

        @Override
        public List<DurableNotificationClaim> claim(
                AuthorizedTenantContext context, int maximumNotifications) {
            throw unavailable();
        }

        @Override
        public void acknowledge(
                AuthorizedTenantContext context, DurableNotificationClaim claim) {
            throw unavailable();
        }

        @Override
        public NotificationFailureDisposition recordFailure(
                AuthorizedTenantContext context,
                DurableNotificationClaim claim,
                String reasonCode) {
            throw unavailable();
        }

        @Override
        public NotificationQueueSnapshot snapshot(AuthorizedTenantContext context) {
            throw unavailable();
        }
    }

    public static final class RedisJobQueue extends UnavailableAdapter implements JobQueuePort {
        public RedisJobQueue() {
            super(PlatformCapability.REDIS_JOB_QUEUE, "redis-job-adapter-not-configured");
        }

        @Override
        public void enqueue(AuthorizedTenantContext context, DurableJob job) {
            throw unavailable();
        }

        @Override
        public List<DurableJobClaim> claim(AuthorizedTenantContext context, int maximumJobs) {
            throw unavailable();
        }

        @Override
        public void acknowledge(AuthorizedTenantContext context, DurableJobClaim claim) {
            throw unavailable();
        }

        @Override
        public JobFailureDisposition recordFailure(
                AuthorizedTenantContext context, DurableJobClaim claim, String reasonCode) {
            throw unavailable();
        }

        @Override
        public JobQueueSnapshot snapshot(AuthorizedTenantContext context) {
            throw unavailable();
        }
    }

    public static final class WorkerExecution extends UnavailableAdapter
            implements WorkerExecutionPort {
        public WorkerExecution() {
            super(PlatformCapability.WORKER_EXECUTION, "worker-execution-not-configured");
        }

        @Override
        public int executeNext(AuthorizedTenantContext context, int maximumJobs) {
            throw unavailable();
        }
    }

    public static final class SchedulerExecution extends UnavailableAdapter
            implements SchedulerExecutionPort {
        public SchedulerExecution() {
            super(PlatformCapability.SCHEDULER_EXECUTION, "scheduler-execution-not-configured");
        }

        @Override
        public int dispatchDue(
                AuthorizedTenantContext context, Instant asOf, int maximumSchedules) {
            throw unavailable();
        }
    }

    private abstract static class UnavailableAdapter implements CapabilityProbe {
        private final CapabilityStatus status;

        private UnavailableAdapter(PlatformCapability capability, String reasonCode) {
            status = new CapabilityStatus(capability, CapabilityAvailability.UNAVAILABLE, reasonCode);
        }

        @Override
        public final CapabilityStatus status() {
            return status;
        }

        protected final PlatformCapabilityUnavailableException unavailable() {
            return new PlatformCapabilityUnavailableException(status.capability(), status.reasonCode());
        }
    }
}
