package com.rootopathy.careos.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.DockerClient;
import com.rootopathy.careos.platform.application.DurableJobQueueException;
import com.rootopathy.careos.platform.application.JobQueuePort;
import com.rootopathy.careos.platform.application.PlatformCapabilityRegistry;
import com.rootopathy.careos.platform.domain.CapabilityAvailability;
import com.rootopathy.careos.platform.domain.DurableJob;
import com.rootopathy.careos.platform.domain.DurableJobClaim;
import com.rootopathy.careos.platform.domain.JobFailureDisposition;
import com.rootopathy.careos.platform.domain.JobQueueSnapshot;
import com.rootopathy.careos.platform.domain.PlatformCapability;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisDurableJobQueueIntegrationTest {
    private static final UUID ORGANIZATION_ONE =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_TWO =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String KEY_PREFIX = "careos:testjobs";

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(
                            "redis:8-alpine@sha256:becdda6c7f4b3fb42e42fd7f120bbf5c54c4caaaf16f26da24e4563d2c1f0576"))
                    .withCommand(
                            "redis-server", "--appendonly", "yes", "--appendfsync", "always")
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private SimpleMeterRegistry meterRegistry;

    @BeforeAll
    static void connectToRedis() {
        var standalone = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        var socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        var clientOptions = ClientOptions.builder().socketOptions(socketOptions).build();
        var client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(1))
                .shutdownTimeout(Duration.ZERO)
                .clientOptions(clientOptions)
                .build();
        connectionFactory = new LettuceConnectionFactory(standalone, client);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnectFromRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void resetRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    @Order(1)
    void atomicallyDeduplicatesClaimsAndAcknowledgesWithinOneTenant() {
        var adapter = adapter(properties(3));
        var job = dueJob("job:deduplicated");
        var organizationOne = context(ORGANIZATION_ONE, "redis-job-1");

        adapter.enqueue(organizationOne, job);
        adapter.enqueue(organizationOne, job);
        assertThat(adapter.snapshot(organizationOne)).isEqualTo(new JobQueueSnapshot(1, 0, 0, 0));
        assertThat(adapter.snapshot(context(ORGANIZATION_TWO, "redis-job-2")))
                .isEqualTo(new JobQueueSnapshot(0, 0, 0, 0));

        assertThatThrownBy(() -> adapter.enqueue(
                        organizationOne,
                        new DurableJob(
                                UUID.randomUUID(),
                                job.jobType(),
                                job.schemaVersion(),
                                job.referenceJson(),
                                job.deduplicationKey(),
                                job.notBefore())))
                .isInstanceOf(DurableJobQueueException.class)
                .extracting("reasonCode")
                .isEqualTo("job-deduplication-conflict");

        var claims = adapter.claim(organizationOne, 5);
        assertThat(claims).singleElement().satisfies(claim -> {
            assertThat(claim.organizationId()).isEqualTo(ORGANIZATION_ONE);
            assertThat(claim.job()).isEqualTo(job);
            assertThat(claim.attempt()).isEqualTo(1);
            assertThat(claim.leaseToken()).hasSize(64).doesNotContain(job.deduplicationKey());
        });
        assertThat(adapter.claim(organizationOne, 5)).isEmpty();
        assertThat(adapter.snapshot(organizationOne)).isEqualTo(new JobQueueSnapshot(0, 1, 0, 0));

        adapter.acknowledge(organizationOne, claims.getFirst());
        adapter.acknowledge(organizationOne, claims.getFirst());
        assertThat(adapter.snapshot(organizationOne)).isEqualTo(new JobQueueSnapshot(0, 0, 0, 1));
        assertThat(meter("enqueued")).isEqualTo(1);
        assertThat(meter("deduplicated")).isEqualTo(1);
        assertThat(meter("acknowledged")).isEqualTo(1);
        assertThat(meter("duplicate-acknowledgement")).isEqualTo(1);
    }

    @Test
    @Order(2)
    void concurrentProducersAndClaimersCreateExactlyOneLease() throws Exception {
        var adapter = adapter(properties(3));
        var context = context(ORGANIZATION_ONE, "redis-job-concurrent");
        var job = dueJob("job:concurrent");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var enqueueFutures = new ArrayList<java.util.concurrent.Future<?>>();
            for (var index = 0; index < 8; index++) {
                enqueueFutures.add(executor.submit(() -> adapter.enqueue(context, job)));
            }
            for (var future : enqueueFutures) {
                future.get();
            }
            assertThat(adapter.snapshot(context).readyJobs()).isEqualTo(1);

            var claimFutures = new ArrayList<java.util.concurrent.Future<List<DurableJobClaim>>>();
            for (var index = 0; index < 8; index++) {
                claimFutures.add(executor.submit(() -> adapter.claim(context, 1)));
            }
            var claims = new ArrayList<DurableJobClaim>();
            for (var future : claimFutures) {
                claims.addAll(future.get());
            }
            assertThat(claims).singleElement().extracting(DurableJobClaim::job).isEqualTo(job);
            adapter.acknowledge(context, claims.getFirst());
        }
    }

    @Test
    @Order(3)
    void retriesWithBackoffThenRetainsTerminalDeadLetter() {
        var adapter = adapter(properties(2));
        var context = context(ORGANIZATION_ONE, "redis-job-3");
        adapter.enqueue(context, dueJob("job:retry"));

        var firstClaim = adapter.claim(context, 1).getFirst();
        assertThat(adapter.recordFailure(context, firstClaim, "dependency.timeout"))
                .isEqualTo(JobFailureDisposition.RETRY_SCHEDULED);
        assertThat(adapter.claim(context, 1)).isEmpty();

        var secondClaim = awaitClaim(adapter, context);
        assertThat(secondClaim.attempt()).isEqualTo(2);
        assertThat(adapter.recordFailure(context, secondClaim, "dependency.unavailable"))
                .isEqualTo(JobFailureDisposition.DEAD_LETTERED);
        assertThat(adapter.recordFailure(context, secondClaim, "dependency.unavailable"))
                .isEqualTo(JobFailureDisposition.DEAD_LETTERED);
        assertThat(adapter.snapshot(context)).isEqualTo(new JobQueueSnapshot(0, 0, 1, 0));
        assertThat(meter("retry-scheduled")).isEqualTo(1);
        assertThat(meter("dead-lettered")).isEqualTo(1);
    }

    @Test
    @Order(4)
    void expiredLeaseCannotAcknowledgeAndIsRecoveredWithANewToken() throws Exception {
        var leaseProperties = properties(3);
        leaseProperties.setLeaseDuration(Duration.ofMillis(300));
        var adapter = adapter(leaseProperties);
        var context = context(ORGANIZATION_ONE, "redis-job-4");
        adapter.enqueue(context, dueJob("job:lease"));
        var abandoned = adapter.claim(context, 1).getFirst();

        Thread.sleep(360);
        assertThatThrownBy(() -> adapter.acknowledge(context, abandoned))
                .isInstanceOf(DurableJobQueueException.class)
                .extracting("reasonCode")
                .isEqualTo("job-lease-expired");
        assertThat(adapter.claim(context, 1)).isEmpty();

        var recovered = awaitClaim(adapter, context);
        assertThat(recovered.attempt()).isEqualTo(2);
        assertThat(recovered.leaseToken()).isNotEqualTo(abandoned.leaseToken());
        assertThatThrownBy(() -> adapter.acknowledge(context, abandoned))
                .isInstanceOf(DurableJobQueueException.class)
                .extracting("reasonCode")
                .isEqualTo("job-lease-not-owned");
        adapter.acknowledge(context, recovered);
        assertThat(meter("lease-recovered")).isEqualTo(1);
    }

    @Test
    @Order(5)
    void rejectsCrossTenantClaimsUnapprovedDefinitionsAndDistantSchedulesBeforeMutation() {
        var adapter = adapter(properties(3));
        var organizationOne = context(ORGANIZATION_ONE, "redis-job-5");
        var organizationTwo = context(ORGANIZATION_TWO, "redis-job-6");
        adapter.enqueue(organizationOne, dueJob("job:tenant"));
        var claim = adapter.claim(organizationOne, 1).getFirst();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.acknowledge(organizationTwo, claim))
                .withMessageContaining("tenant");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.enqueue(
                        organizationOne,
                        new DurableJob(
                                UUID.randomUUID(),
                                "unapproved.work",
                                1,
                                "{\"reference\":\"opaque\"}",
                                "job:unapproved",
                                Instant.now())))
                .withMessageContaining("not configured");
        assertThatThrownBy(() -> adapter.enqueue(
                        organizationOne,
                        new DurableJob(
                                UUID.randomUUID(),
                                "document.scan",
                                1,
                                "{\"reference\":\"opaque\"}",
                                "job:distant",
                                Instant.now().plus(Duration.ofHours(2)))))
                .isInstanceOf(DurableJobQueueException.class)
                .extracting("reasonCode")
                .isEqualTo("job-schedule-too-distant");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.claim(organizationOne, 11))
                .withMessageContaining("claim range");
    }

    @Test
    @Order(6)
    void malformedRetainedPayloadIsDeadLetteredWithoutDelivery() {
        var adapter = adapter(properties(3));
        var context = context(ORGANIZATION_ONE, "redis-job-7");
        var job = dueJob("job:tampered");
        adapter.enqueue(context, job);
        redis.opsForHash()
                .put(
                        KEY_PREFIX + ":{" + ORGANIZATION_ONE + "}:payloads",
                        job.jobId().toString(),
                        "tampered-record");

        assertThat(adapter.claim(context, 1)).isEmpty();
        assertThat(adapter.snapshot(context)).isEqualTo(new JobQueueSnapshot(0, 0, 1, 0));
        assertThat(redis.opsForHash()
                        .get(
                                KEY_PREFIX + ":{" + ORGANIZATION_ONE + "}:failure-reasons",
                                job.jobId().toString()))
                .isEqualTo("queue-record-invalid");
        assertThat(meter("invalid-record")).isEqualTo(1);
    }

    @Test
    @Order(7)
    void applicationRestartReclaimsPersistedQueueState() {
        var context = context(ORGANIZATION_ONE, "redis-job-8");
        var job = dueJob("job:restart");
        adapter(properties(3)).enqueue(context, job);

        var restartedAdapter = adapter(properties(3));
        assertThat(restartedAdapter.claim(context, 1))
                .singleElement()
                .extracting(DurableJobClaim::job)
                .isEqualTo(job);
    }

    @Test
    @Order(9)
    void dependencyTimeoutFailsClosedAndTheSameAdapterRecovers() {
        var adapter = adapter(properties(3));
        var context = context(ORGANIZATION_ONE, "redis-job-9");
        adapter.enqueue(context, dueJob("job:outage"));
        DockerClient docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertThatThrownBy(() -> adapter.snapshot(context))
                    .isInstanceOf(DurableJobQueueException.class)
                    .extracting("reasonCode")
                    .isEqualTo("redis-job-queue-unavailable");
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
            connectionFactory.resetConnection();
        }

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(adapter.snapshot(context).readyJobs()).isEqualTo(1));
        assertThat(meter("dependency-error")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(8)
    void springActivationReplacesOnlyTheRedisJobCapabilityAndValidatesConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        PlatformCapabilityConfiguration.class,
                        RedisDurableJobQueueConfiguration.class,
                        RedisTestConfiguration.class)
                .withPropertyValues(enabledProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JobQueuePort.class);
                    var registry = context.getBean(PlatformCapabilityRegistry.class);
                    assertThat(registry.status(PlatformCapability.REDIS_JOB_QUEUE).availability())
                            .isEqualTo(CapabilityAvailability.AVAILABLE);
                    assertThat(registry.statuses())
                            .filteredOn(status ->
                                    status.capability() != PlatformCapability.REDIS_JOB_QUEUE)
                            .allSatisfy(status -> assertThat(status.availability())
                                    .isEqualTo(CapabilityAvailability.UNAVAILABLE));
                });

        new ApplicationContextRunner()
                .withUserConfiguration(
                        PlatformCapabilityConfiguration.class,
                        RedisDurableJobQueueConfiguration.class,
                        RedisTestConfiguration.class)
                .withPropertyValues("careos.jobs.redis.enabled=true")
                .run(context -> assertThat(context).hasFailed());

        var invalid = properties(3);
        invalid.setKeyPrefix("careos:{unsafe}");
        assertThatIllegalStateException()
                .isThrownBy(invalid::validateForActivation)
                .withMessageContaining("key prefix");
        var duplicates = properties(3);
        duplicates.setAllowedJobDefinitions(List.of("document.scan@1", "document.scan@1"));
        assertThatIllegalStateException()
                .isThrownBy(duplicates::validateForActivation)
                .withMessageContaining("duplicates");
    }

    private RedisDurableJobQueueAdapter adapter(RedisJobQueueProperties properties) {
        var adapter = new RedisDurableJobQueueAdapter(redis, properties, meterRegistry);
        adapter.initialize();
        return adapter;
    }

    private double meter(String outcome) {
        return meterRegistry
                .get("careos.platform.redis.jobs")
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private static DurableJobClaim awaitClaim(
            RedisDurableJobQueueAdapter adapter, AuthorizedTenantContext context) {
        var claimed = new AtomicReference<DurableJobClaim>();
        await().atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> {
                    var claims = adapter.claim(context, 1);
                    assertThat(claims).hasSize(1);
                    claimed.set(claims.getFirst());
                });
        return claimed.get();
    }

    private static DurableJob dueJob(String deduplicationKey) {
        return new DurableJob(
                UUID.randomUUID(),
                "document.scan",
                1,
                "{\"documentId\":\"30000000-0000-0000-0000-000000000001\"}",
                deduplicationKey,
                Instant.now().minusSeconds(1));
    }

    private static AuthorizedTenantContext context(UUID organizationId, String correlationId) {
        return new AuthorizedTenantContext(
                organizationId, ACTOR_ID, "document.security", correlationId);
    }

    private static RedisJobQueueProperties properties(int maximumAttempts) {
        var properties = new RedisJobQueueProperties();
        properties.setEnabled(true);
        properties.setKeyPrefix(KEY_PREFIX);
        properties.setLeaseDuration(Duration.ofSeconds(3));
        properties.setMaximumAttempts(maximumAttempts);
        properties.setInitialRetryDelay(Duration.ofMillis(300));
        properties.setMaximumRetryDelay(Duration.ofMillis(600));
        properties.setTerminalRetention(Duration.ofMinutes(1));
        properties.setMaximumScheduleAhead(Duration.ofHours(1));
        properties.setMaximumClaimBatch(10);
        properties.setCleanupBatch(25);
        properties.setAllowedJobDefinitions(List.of("document.scan@1"));
        return properties;
    }

    private static String[] enabledProperties() {
        return new String[] {
            "careos.jobs.redis.enabled=true",
            "careos.jobs.redis.key-prefix=" + KEY_PREFIX,
            "careos.jobs.redis.lease-duration=1s",
            "careos.jobs.redis.maximum-attempts=3",
            "careos.jobs.redis.initial-retry-delay=100ms",
            "careos.jobs.redis.maximum-retry-delay=1s",
            "careos.jobs.redis.terminal-retention=1m",
            "careos.jobs.redis.maximum-schedule-ahead=1h",
            "careos.jobs.redis.maximum-claim-batch=10",
            "careos.jobs.redis.cleanup-batch=25",
            "careos.jobs.redis.allowed-job-definitions=document.scan@1"
        };
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RedisTestConfiguration {
        @Bean
        StringRedisTemplate redisTemplate() {
            return redis;
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
