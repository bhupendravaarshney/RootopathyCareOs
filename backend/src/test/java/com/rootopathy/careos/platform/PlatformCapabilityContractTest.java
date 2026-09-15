package com.rootopathy.careos.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.platform.application.CapabilityProbe;
import com.rootopathy.careos.platform.application.PlatformCapabilityRegistry;
import com.rootopathy.careos.platform.application.PlatformCapabilityUnavailableException;
import com.rootopathy.careos.platform.domain.CapabilityAvailability;
import com.rootopathy.careos.platform.domain.DocumentObjectReference;
import com.rootopathy.careos.platform.domain.DocumentPromotionAuthorization;
import com.rootopathy.careos.platform.domain.DocumentQuarantineRequest;
import com.rootopathy.careos.platform.domain.DocumentRetentionDirective;
import com.rootopathy.careos.platform.domain.DocumentScanAttestation;
import com.rootopathy.careos.platform.domain.DurableJob;
import com.rootopathy.careos.platform.domain.DurableJobClaim;
import com.rootopathy.careos.platform.domain.DurableNotification;
import com.rootopathy.careos.platform.domain.DurableNotificationClaim;
import com.rootopathy.careos.platform.domain.MalwareScanResult;
import com.rootopathy.careos.platform.domain.MalwareScanVerdict;
import com.rootopathy.careos.platform.domain.PlatformCapability;
import com.rootopathy.careos.platform.infrastructure.PlatformCapabilityConfiguration;
import com.rootopathy.careos.platform.infrastructure.UnavailablePlatformAdapters;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PlatformCapabilityContractTest {
    private static final UUID ORGANIZATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID OBJECT_VERSION_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID RECIPIENT_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private static final String SHA_256 = "a".repeat(64);

    @Test
    void springRegistersExactlyOneExplicitDefaultForEveryCapability() {
        new ApplicationContextRunner()
                .withUserConfiguration(PlatformCapabilityConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PlatformCapabilityRegistry.class);
                    assertThat(context).getBeans(CapabilityProbe.class).hasSize(9);

                    var registry = context.getBean(PlatformCapabilityRegistry.class);
                    assertThat(registry.statuses())
                            .hasSize(PlatformCapability.values().length)
                            .allSatisfy(status -> {
                                assertThat(status.availability())
                                        .isEqualTo(CapabilityAvailability.UNAVAILABLE);
                                assertThat(status.reasonCode()).endsWith("not-configured");
                            });
                });
    }

    @Test
    void unavailableAdaptersRejectEveryOperationBeforeReadingContent() {
        var adapters = adapters();
        var context = new AuthorizedTenantContext(
                ORGANIZATION_ID, ACTOR_ID, "document.security", "platform-contract-1");
        var reference = new DocumentObjectReference(ORGANIZATION_ID, DOCUMENT_ID, OBJECT_VERSION_ID);
        var quarantineRequest =
                new DocumentQuarantineRequest(DOCUMENT_ID, OBJECT_VERSION_ID, 128, "application/pdf", SHA_256);
        var scan = new MalwareScanResult(
                reference, MalwareScanVerdict.CLEAN, "scanner", "20260913.1", SHA_256, NOW);
        var promotionAuthorization = new DocumentPromotionAuthorization(
                new DocumentScanAttestation(UUID.randomUUID(), scan, NOW),
                "foundation.synthetic",
                java.util.Set.of("scanner"),
                Duration.ofMinutes(5),
                Duration.ZERO,
                NOW);
        var contentRead = new AtomicBoolean();
        var content = new InputStream() {
            @Override
            public int read() throws IOException {
                contentRead.set(true);
                return -1;
            }
        };

        assertUnavailable(
                PlatformCapability.PRIVATE_DOCUMENT_QUARANTINE,
                () -> adapters.storage.quarantine(context, quarantineRequest, content));
        assertThat(contentRead).isFalse();
        assertUnavailable(
                PlatformCapability.MALWARE_SCANNING,
                () -> adapters.scanner.scan(context, reference));
        assertUnavailable(
                PlatformCapability.DOCUMENT_PROMOTION,
                () -> adapters.promotion.promote(context, promotionAuthorization));
        assertUnavailable(
                PlatformCapability.SIGNED_DOCUMENT_ACCESS,
                () -> adapters.signedAccess.createReadUrl(context, reference, Duration.ofMinutes(5)));
        assertUnavailable(
                PlatformCapability.DOCUMENT_RETENTION,
                () -> adapters.retention.apply(
                        context,
                        reference,
                        new DocumentRetentionDirective("credential.evidence", NOW.plusSeconds(3600), true)));
        assertUnavailable(
                PlatformCapability.DURABLE_NOTIFICATION_DELIVERY,
                () -> adapters.notifications.enqueue(
                        context,
                        new DurableNotification(
                                UUID.randomUUID(),
                                RECIPIENT_ID,
                                "security.notice",
                                1,
                                "{\"reference\":\"opaque\"}",
                                "notification:1",
                                NOW)));
        var notificationClaim = new DurableNotificationClaim(
                ORGANIZATION_ID,
                UUID.randomUUID(),
                RECIPIENT_ID,
                "security.notice",
                1,
                "{\"reference\":\"opaque\"}",
                1,
                "opaque-notification-lease",
                NOW.plusSeconds(30));
        assertUnavailable(
                PlatformCapability.DURABLE_NOTIFICATION_DELIVERY,
                () -> adapters.notifications.claim(context, 1));
        assertUnavailable(
                PlatformCapability.DURABLE_NOTIFICATION_DELIVERY,
                () -> adapters.notifications.acknowledge(context, notificationClaim));
        assertUnavailable(
                PlatformCapability.DURABLE_NOTIFICATION_DELIVERY,
                () -> adapters.notifications.recordFailure(
                        context, notificationClaim, "provider.timeout"));
        assertUnavailable(
                PlatformCapability.DURABLE_NOTIFICATION_DELIVERY,
                () -> adapters.notifications.snapshot(context));
        assertUnavailable(
                PlatformCapability.REDIS_JOB_QUEUE,
                () -> adapters.jobs.enqueue(
                        context,
                        new DurableJob(
                                UUID.randomUUID(),
                                "document.scan",
                                1,
                                "{\"documentId\":\"30000000-0000-0000-0000-000000000001\"}",
                                "job:1",
                                NOW)));
        var job = new DurableJob(
                UUID.randomUUID(),
                "document.scan",
                1,
                "{\"documentId\":\"30000000-0000-0000-0000-000000000001\"}",
                "job:2",
                NOW);
        var jobClaim = new DurableJobClaim(
                ORGANIZATION_ID, job, 1, "opaque-lease-token", NOW.plusSeconds(30));
        assertUnavailable(
                PlatformCapability.REDIS_JOB_QUEUE, () -> adapters.jobs.claim(context, 1));
        assertUnavailable(
                PlatformCapability.REDIS_JOB_QUEUE,
                () -> adapters.jobs.acknowledge(context, jobClaim));
        assertUnavailable(
                PlatformCapability.REDIS_JOB_QUEUE,
                () -> adapters.jobs.recordFailure(context, jobClaim, "dependency.unavailable"));
        assertUnavailable(
                PlatformCapability.REDIS_JOB_QUEUE, () -> adapters.jobs.snapshot(context));
        var unavailableClaim = new DurableJobClaim(
                ORGANIZATION_ID,
                new DurableJob(
                        UUID.randomUUID(),
                        "document.scan",
                        1,
                        "{\"documentId\":\"30000000-0000-0000-0000-000000000001\"}",
                        "job:2",
                        NOW),
                1,
                "lease-token",
                NOW.plusSeconds(30));
        assertUnavailable(
                PlatformCapability.REDIS_JOB_QUEUE, () -> adapters.jobs.claim(context, 1));
        assertUnavailable(
                PlatformCapability.REDIS_JOB_QUEUE,
                () -> adapters.jobs.acknowledge(context, unavailableClaim));
        assertUnavailable(
                PlatformCapability.REDIS_JOB_QUEUE,
                () -> adapters.jobs.recordFailure(context, unavailableClaim, "dependency.timeout"));
        assertUnavailable(
                PlatformCapability.REDIS_JOB_QUEUE, () -> adapters.jobs.snapshot(context));
        assertUnavailable(
                PlatformCapability.WORKER_EXECUTION,
                () -> adapters.worker.executeNext(context, 10));
        assertUnavailable(
                PlatformCapability.SCHEDULER_EXECUTION,
                () -> adapters.scheduler.dispatchDue(context, NOW, 10));
    }

    @Test
    void registryRejectsMissingAndDuplicateCapabilityProbes() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new PlatformCapabilityRegistry(List.of()))
                .withMessageContaining("No adapter registered")
                .withMessageContaining("private-document-quarantine")
                .withMessageContaining("scheduler-execution");

        var storage = new UnavailablePlatformAdapters.PrivateDocumentStorage();
        assertThatIllegalStateException()
                .isThrownBy(() -> new PlatformCapabilityRegistry(List.of(storage, storage)))
                .withMessageContaining("Multiple adapters registered")
                .withMessageContaining("private-document-quarantine");
    }

    @Test
    void registryRequireAvailableUsesTheStableCapabilityReason() {
        var registry = new PlatformCapabilityRegistry(adapters().probes());

        for (var capability : PlatformCapability.values()) {
            assertThatThrownBy(() -> registry.requireAvailable(capability))
                    .isInstanceOf(PlatformCapabilityUnavailableException.class)
                    .extracting("capability", "reasonCode")
                    .containsExactly(capability, registry.status(capability).reasonCode());
        }
    }

    @Test
    void boundaryValuesRejectAmbiguousDocumentAndQueueMetadata() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocumentQuarantineRequest(
                        DOCUMENT_ID, OBJECT_VERSION_ID, 0, "application/pdf", SHA_256))
                .withMessageContaining("declaredBytes");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocumentQuarantineRequest(
                        DOCUMENT_ID, OBJECT_VERSION_ID, 1, "application /pdf", SHA_256))
                .withMessageContaining("mediaType");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocumentQuarantineRequest(
                        DOCUMENT_ID, OBJECT_VERSION_ID, 1, "application/pdf", "A".repeat(64)))
                .withMessageContaining("sha256");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DurableJob(
                        UUID.randomUUID(), "document.scan", 1, "[]", "job:1", NOW))
                .withMessageContaining("JSON object");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DurableJob(
                        UUID.randomUUID(), "document.scan", 1, "{}", "job:1", Instant.MAX))
                .withMessageContaining("notBefore");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DurableJobClaim(
                        ORGANIZATION_ID,
                        new DurableJob(UUID.randomUUID(), "document.scan", 1, "{}", "job:3", NOW),
                        0,
                        "lease-token",
                        NOW))
                .withMessageContaining("attempt");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DurableNotification(
                        UUID.randomUUID(),
                        RECIPIENT_ID,
                        "Security.Notice",
                        1,
                        "{}",
                        "notification:1",
                        NOW))
                .withMessageContaining("templateKey");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DurableNotificationClaim(
                        ORGANIZATION_ID,
                        UUID.randomUUID(),
                        RECIPIENT_ID,
                        "security.notice",
                        1,
                        "{}",
                        0,
                        "lease-token",
                        NOW))
                .withMessageContaining("attempt");
    }

    private static void assertUnavailable(
            PlatformCapability expectedCapability, ThrowingCallable invocation) {
        assertThatThrownBy(invocation)
                .isInstanceOf(PlatformCapabilityUnavailableException.class)
                .extracting("capability")
                .isEqualTo(expectedCapability);
    }

    private static Adapters adapters() {
        return new Adapters(
                new UnavailablePlatformAdapters.PrivateDocumentStorage(),
                new UnavailablePlatformAdapters.MalwareScanner(),
                new UnavailablePlatformAdapters.DocumentPromotion(),
                new UnavailablePlatformAdapters.SignedDocumentAccess(),
                new UnavailablePlatformAdapters.DocumentRetention(),
                new UnavailablePlatformAdapters.DurableNotificationDelivery(),
                new UnavailablePlatformAdapters.RedisJobQueue(),
                new UnavailablePlatformAdapters.WorkerExecution(),
                new UnavailablePlatformAdapters.SchedulerExecution());
    }

    private record Adapters(
            UnavailablePlatformAdapters.PrivateDocumentStorage storage,
            UnavailablePlatformAdapters.MalwareScanner scanner,
            UnavailablePlatformAdapters.DocumentPromotion promotion,
            UnavailablePlatformAdapters.SignedDocumentAccess signedAccess,
            UnavailablePlatformAdapters.DocumentRetention retention,
            UnavailablePlatformAdapters.DurableNotificationDelivery notifications,
            UnavailablePlatformAdapters.RedisJobQueue jobs,
            UnavailablePlatformAdapters.WorkerExecution worker,
            UnavailablePlatformAdapters.SchedulerExecution scheduler) {
        private List<CapabilityProbe> probes() {
            return List.of(
                    storage,
                    scanner,
                    promotion,
                    signedAccess,
                    retention,
                    notifications,
                    jobs,
                    worker,
                    scheduler);
        }
    }
}
