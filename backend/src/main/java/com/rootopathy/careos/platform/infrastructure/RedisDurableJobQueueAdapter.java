package com.rootopathy.careos.platform.infrastructure;

import com.rootopathy.careos.platform.application.CapabilityProbe;
import com.rootopathy.careos.platform.application.DurableJobQueueException;
import com.rootopathy.careos.platform.application.JobQueuePort;
import com.rootopathy.careos.platform.domain.CapabilityAvailability;
import com.rootopathy.careos.platform.domain.CapabilityStatus;
import com.rootopathy.careos.platform.domain.DurableJob;
import com.rootopathy.careos.platform.domain.DurableJobClaim;
import com.rootopathy.careos.platform.domain.JobFailureDisposition;
import com.rootopathy.careos.platform.domain.JobQueueSnapshot;
import com.rootopathy.careos.platform.domain.PlatformCapability;
import com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Policy-neutral Redis job transport. It owns queue state transitions but deliberately does not
 * authenticate workers, authorize job effects, execute work, or emit business governance events.
 */
public final class RedisDurableJobQueueAdapter implements JobQueuePort, CapabilityProbe {
    private static final String READY = "redis-job-queue-ready";
    private static final String NOT_INITIALIZED = "redis-job-queue-not-initialized";
    private static final String UNAVAILABLE = "redis-job-queue-unavailable";
    private static final String DEDUPLICATION_CONFLICT = "job-deduplication-conflict";
    private static final String ID_CONFLICT = "job-id-conflict";
    private static final String RECORD_INVALID = "redis-job-record-invalid";
    private static final String SCHEDULE_TOO_DISTANT = "job-schedule-too-distant";
    private static final String LEASE_NOT_OWNED = "job-lease-not-owned";
    private static final String LEASE_EXPIRED = "job-lease-expired";
    private static final String JOB_NOT_CLAIMED = "job-not-claimed";
    private static final Pattern FAILURE_REASON =
            Pattern.compile("[a-z][a-z0-9]*([.:-][a-z0-9]+)*");
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();
    private static final RedisScript<Long> READINESS = longScript("readiness.lua");
    private static final RedisScript<Long> CLEANUP = longScript("cleanup.lua");
    private static final RedisScript<Long> ENQUEUE = longScript("enqueue.lua");
    private static final RedisScript<List> CLAIM = listScript("claim.lua");
    private static final RedisScript<Long> ACKNOWLEDGE = longScript("acknowledge.lua");
    private static final RedisScript<Long> RECORD_FAILURE = longScript("record-failure.lua");
    private static final RedisScript<Long> DEAD_LETTER_CORRUPT =
            longScript("dead-letter-corrupt.lua");
    private static final RedisScript<List> SNAPSHOT = listScript("snapshot.lua");

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final long leaseMillis;
    private final int maximumAttempts;
    private final long initialRetryMillis;
    private final long maximumRetryMillis;
    private final long terminalRetentionMillis;
    private final long maximumScheduleAheadMillis;
    private final int maximumClaimBatch;
    private final int cleanupBatch;
    private final Set<String> allowedJobDefinitions;
    private final SecureRandom secureRandom = new SecureRandom();
    private final QueueMetrics metrics;
    private volatile boolean initialized;

    public RedisDurableJobQueueAdapter(
            StringRedisTemplate redis,
            RedisJobQueueProperties properties,
            MeterRegistry meterRegistry) {
        this.redis = Objects.requireNonNull(redis, "redis");
        Objects.requireNonNull(properties, "properties").validateForActivation();
        keyPrefix = properties.getKeyPrefix();
        leaseMillis = properties.getLeaseDuration().toMillis();
        maximumAttempts = properties.getMaximumAttempts();
        initialRetryMillis = properties.getInitialRetryDelay().toMillis();
        maximumRetryMillis = properties.getMaximumRetryDelay().toMillis();
        terminalRetentionMillis = properties.getTerminalRetention().toMillis();
        maximumScheduleAheadMillis = properties.getMaximumScheduleAhead().toMillis();
        maximumClaimBatch = properties.getMaximumClaimBatch();
        cleanupBatch = properties.getCleanupBatch();
        allowedJobDefinitions = properties.validatedAllowedJobDefinitions();
        metrics = new QueueMetrics(Objects.requireNonNull(meterRegistry, "meterRegistry"));
    }

    public synchronized void initialize() {
        try {
            var pong = redis.execute((RedisCallback<String>) connection ->
                    connection.commands().ping());
            if (!"PONG".equals(pong)) {
                throw new IllegalStateException("Redis job queue PING response is invalid");
            }
            var serverTime = redis.execute(READINESS, List.of());
            if (serverTime == null || serverTime < 1) {
                throw new IllegalStateException("Redis job queue scripting readiness check failed");
            }
            initialized = true;
        } catch (RedisConnectionFailureException exception) {
            throw new IllegalStateException("Redis job queue readiness check failed", exception);
        }
    }

    @Override
    public void enqueue(AuthorizedTenantContext context, DurableJob job) {
        requireInitialized();
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(job, "job");
        requireAllowed(job);
        var keys = TenantKeys.create(keyPrefix, context.organizationId());
        cleanup(keys);
        var payload = encode(job);
        var result = executeLong(
                ENQUEUE,
                keys,
                job.jobId().toString(),
                sha256(job.deduplicationKey()),
                payload,
                sha256(payload),
                Long.toString(job.notBefore().toEpochMilli()),
                Integer.toString(maximumAttempts),
                Long.toString(initialRetryMillis),
                Long.toString(maximumRetryMillis),
                Long.toString(terminalRetentionMillis),
                Long.toString(maximumScheduleAheadMillis));
        switch (result.intValue()) {
            case 1 -> metrics.enqueued.increment();
            case 0 -> metrics.deduplicated.increment();
            case -1 -> throw businessFailure(DEDUPLICATION_CONFLICT, metrics.conflicts);
            case -2 -> throw businessFailure(ID_CONFLICT, metrics.conflicts);
            case -3 -> throw businessFailure(SCHEDULE_TOO_DISTANT, metrics.rejected);
            case -4 -> throw businessFailure(RECORD_INVALID, metrics.invalidRecords);
            default -> throw businessFailure(RECORD_INVALID, metrics.invalidRecords);
        }
    }

    @Override
    public List<DurableJobClaim> claim(AuthorizedTenantContext context, int maximumJobs) {
        requireInitialized();
        Objects.requireNonNull(context, "context");
        if (maximumJobs < 1 || maximumJobs > maximumClaimBatch) {
            throw new IllegalArgumentException("maximumJobs is outside the configured claim range");
        }
        var keys = TenantKeys.create(keyPrefix, context.organizationId());
        cleanup(keys);
        var arguments = new ArrayList<String>(4 + maximumJobs);
        arguments.add(Integer.toString(maximumJobs));
        arguments.add(Long.toString(leaseMillis));
        arguments.add(Integer.toString(cleanupBatch));
        arguments.add(Long.toString(terminalRetentionMillis));
        for (var index = 0; index < maximumJobs; index++) {
            arguments.add(newLeaseToken());
        }
        var response = executeList(CLAIM, keys, arguments.toArray());
        if (response.size() < 3 || (response.size() - 3) % 6 != 0) {
            throw businessFailure(RECORD_INVALID, metrics.invalidRecords);
        }
        metrics.recoveredForRetry.increment(parseNonNegative(response.get(0)));
        metrics.deadLettered.increment(parseNonNegative(response.get(1)));
        metrics.invalidRecords.increment(parseNonNegative(response.get(2)));

        var claims = new ArrayList<DurableJobClaim>((response.size() - 3) / 6);
        for (var offset = 3; offset < response.size(); offset += 6) {
            var rawJobId = asString(response.get(offset));
            var leaseToken = asString(response.get(offset + 1));
            try {
                var jobId = UUID.fromString(rawJobId);
                var attempt = parsePositive(response.get(offset + 2));
                var leaseExpiresAt = Instant.ofEpochMilli(parsePositiveLong(response.get(offset + 3)));
                var payload = asString(response.get(offset + 4));
                var signature = asString(response.get(offset + 5));
                if (!MessageDigest.isEqual(
                        sha256(payload).getBytes(StandardCharsets.US_ASCII),
                        signature.getBytes(StandardCharsets.US_ASCII))) {
                    throw new IllegalArgumentException("job signature does not match");
                }
                var job = decode(payload);
                if (!jobId.equals(job.jobId())) {
                    throw new IllegalArgumentException("job identifier does not match");
                }
                requireAllowed(job);
                claims.add(new DurableJobClaim(
                        context.organizationId(), job, attempt, leaseToken, leaseExpiresAt));
            } catch (RuntimeException exception) {
                deadLetterCorrupt(keys, rawJobId, leaseToken);
                metrics.invalidRecords.increment();
            }
        }
        metrics.claimed.increment(claims.size());
        return List.copyOf(claims);
    }

    @Override
    public void acknowledge(AuthorizedTenantContext context, DurableJobClaim claim) {
        requireClaimTenant(context, claim);
        var keys = TenantKeys.create(keyPrefix, context.organizationId());
        cleanup(keys);
        var result = executeLong(
                ACKNOWLEDGE,
                keys,
                claim.job().jobId().toString(),
                claim.leaseToken(),
                Long.toString(terminalRetentionMillis));
        switch (result.intValue()) {
            case 1 -> metrics.acknowledged.increment();
            case 2 -> metrics.duplicateAcknowledgements.increment();
            case -1 -> throw businessFailure(LEASE_NOT_OWNED, metrics.rejected);
            case -2 -> throw businessFailure(LEASE_EXPIRED, metrics.rejected);
            case 0 -> throw businessFailure(JOB_NOT_CLAIMED, metrics.rejected);
            default -> throw businessFailure(RECORD_INVALID, metrics.invalidRecords);
        }
    }

    @Override
    public JobFailureDisposition recordFailure(
            AuthorizedTenantContext context, DurableJobClaim claim, String reasonCode) {
        requireClaimTenant(context, claim);
        requireFailureReason(reasonCode);
        var keys = TenantKeys.create(keyPrefix, context.organizationId());
        cleanup(keys);
        var result = executeLong(
                RECORD_FAILURE,
                keys,
                claim.job().jobId().toString(),
                claim.leaseToken(),
                reasonCode,
                Long.toString(terminalRetentionMillis));
        return switch (result.intValue()) {
            case 1 -> {
                metrics.retriesScheduled.increment();
                yield JobFailureDisposition.RETRY_SCHEDULED;
            }
            case 2 -> {
                metrics.deadLettered.increment();
                yield JobFailureDisposition.DEAD_LETTERED;
            }
            case 3 -> JobFailureDisposition.DEAD_LETTERED;
            case -1 -> throw businessFailure(LEASE_NOT_OWNED, metrics.rejected);
            case -2 -> throw businessFailure(LEASE_EXPIRED, metrics.rejected);
            case 0 -> throw businessFailure(JOB_NOT_CLAIMED, metrics.rejected);
            default -> throw businessFailure(RECORD_INVALID, metrics.invalidRecords);
        };
    }

    @Override
    public JobQueueSnapshot snapshot(AuthorizedTenantContext context) {
        requireInitialized();
        Objects.requireNonNull(context, "context");
        var keys = TenantKeys.create(keyPrefix, context.organizationId());
        cleanup(keys);
        var response = executeList(SNAPSHOT, keys);
        if (response.size() != 4) {
            throw businessFailure(RECORD_INVALID, metrics.invalidRecords);
        }
        return new JobQueueSnapshot(
                parseNonNegative(response.get(0)),
                parseNonNegative(response.get(1)),
                parseNonNegative(response.get(2)),
                parseNonNegative(response.get(3)));
    }

    @Override
    public CapabilityStatus status() {
        return initialized
                ? new CapabilityStatus(
                        PlatformCapability.REDIS_JOB_QUEUE, CapabilityAvailability.AVAILABLE, READY)
                : new CapabilityStatus(
                        PlatformCapability.REDIS_JOB_QUEUE,
                        CapabilityAvailability.UNAVAILABLE,
                        NOT_INITIALIZED);
    }

    private void cleanup(TenantKeys keys) {
        var purged = executeLong(CLEANUP, keys, Integer.toString(cleanupBatch));
        if (purged < 0) {
            throw businessFailure(RECORD_INVALID, metrics.invalidRecords);
        }
        metrics.purged.increment(purged);
    }

    private void deadLetterCorrupt(TenantKeys keys, String jobId, String leaseToken) {
        var result = executeLong(
                DEAD_LETTER_CORRUPT,
                keys,
                jobId,
                leaseToken,
                Long.toString(terminalRetentionMillis));
        if (result != 1 && result != 2) {
            throw businessFailure(RECORD_INVALID, metrics.invalidRecords);
        }
        if (result == 1) {
            metrics.deadLettered.increment();
        }
    }

    private Long executeLong(RedisScript<Long> script, TenantKeys keys, Object... arguments) {
        try {
            var result = redis.execute(script, keys.all(), arguments);
            if (result == null) {
                throw new DurableJobQueueException(RECORD_INVALID);
            }
            return result;
        } catch (DurableJobQueueException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            metrics.dependencyErrors.increment();
            throw new DurableJobQueueException(UNAVAILABLE, exception);
        }
    }

    @SuppressWarnings("rawtypes")
    private List executeList(RedisScript<List> script, TenantKeys keys, Object... arguments) {
        try {
            var result = redis.execute(script, keys.all(), arguments);
            if (result == null) {
                throw new DurableJobQueueException(RECORD_INVALID);
            }
            return result;
        } catch (DurableJobQueueException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            metrics.dependencyErrors.increment();
            throw new DurableJobQueueException(UNAVAILABLE, exception);
        }
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new DurableJobQueueException(NOT_INITIALIZED);
        }
    }

    private void requireClaimTenant(AuthorizedTenantContext context, DurableJobClaim claim) {
        requireInitialized();
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(claim, "claim");
        if (!context.organizationId().equals(claim.organizationId())) {
            throw new IllegalArgumentException("job claim tenant does not match authorized tenant");
        }
    }

    private void requireAllowed(DurableJob job) {
        if (!allowedJobDefinitions.contains(job.jobType() + "@" + job.schemaVersion())) {
            metrics.rejected.increment();
            throw new IllegalArgumentException("job type and schema version are not configured");
        }
    }

    private static void requireFailureReason(String reasonCode) {
        if (reasonCode == null
                || reasonCode.length() > 160
                || !FAILURE_REASON.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("reasonCode has an invalid format");
        }
    }

    private String newLeaseToken() {
        var bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String encode(DurableJob job) {
        return job.jobId()
                + "|" + base64(job.jobType())
                + "|" + job.schemaVersion()
                + "|" + base64(job.referenceJson())
                + "|" + base64(job.deduplicationKey())
                + "|" + base64(job.notBefore().toString());
    }

    private static DurableJob decode(String payload) {
        var fields = payload.split("\\|", -1);
        if (fields.length != 6) {
            throw new IllegalArgumentException("job payload field count is invalid");
        }
        var job = new DurableJob(
                UUID.fromString(fields[0]),
                unbase64(fields[1]),
                Integer.parseInt(fields[2]),
                unbase64(fields[3]),
                unbase64(fields[4]),
                Instant.parse(unbase64(fields[5])));
        if (!encode(job).equals(payload)) {
            throw new IllegalArgumentException("job payload is not canonical");
        }
        return job;
    }

    private static String base64(String value) {
        return BASE64_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unbase64(String value) {
        return new String(BASE64_DECODER.decode(value), StandardCharsets.UTF_8);
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static int parsePositive(Object value) {
        var parsed = Integer.parseInt(asString(value));
        if (parsed < 1) {
            throw new IllegalArgumentException("queue value must be positive");
        }
        return parsed;
    }

    private static long parsePositiveLong(Object value) {
        var parsed = Long.parseLong(asString(value));
        if (parsed < 1) {
            throw new IllegalArgumentException("queue value must be positive");
        }
        return parsed;
    }

    private static long parseNonNegative(Object value) {
        var parsed = Long.parseLong(asString(value));
        if (parsed < 0) {
            throw new IllegalArgumentException("queue value must not be negative");
        }
        return parsed;
    }

    private static String asString(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        throw new IllegalArgumentException("queue response value has an invalid type");
    }

    private static DurableJobQueueException businessFailure(String reason, Counter counter) {
        counter.increment();
        return new DurableJobQueueException(reason);
    }

    private static RedisScript<Long> longScript(String name) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource("redis/jobs/" + name));
        script.setResultType(Long.class);
        return script;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RedisScript<List> listScript(String name) {
        var script = new DefaultRedisScript<List>();
        script.setLocation(new ClassPathResource("redis/jobs/" + name));
        script.setResultType((Class) List.class);
        return script;
    }

    private record TenantKeys(List<String> all) {
        private static TenantKeys create(String prefix, UUID organizationId) {
            var tenantPrefix = prefix + ":{" + organizationId + "}:";
            return new TenantKeys(List.of(
                    tenantPrefix + "ready",
                    tenantPrefix + "processing",
                    tenantPrefix + "dead",
                    tenantPrefix + "completed",
                    tenantPrefix + "payloads",
                    tenantPrefix + "signatures",
                    tenantPrefix + "attempts",
                    tenantPrefix + "maximum-attempts",
                    tenantPrefix + "lease-tokens",
                    tenantPrefix + "deduplication-to-job",
                    tenantPrefix + "job-to-deduplication",
                    tenantPrefix + "failure-reasons",
                    tenantPrefix + "initial-retry-millis",
                    tenantPrefix + "maximum-retry-millis",
                    tenantPrefix + "terminal-retention-millis"));
        }
    }

    private record QueueMetrics(
            Counter enqueued,
            Counter deduplicated,
            Counter claimed,
            Counter acknowledged,
            Counter duplicateAcknowledgements,
            Counter retriesScheduled,
            Counter recoveredForRetry,
            Counter deadLettered,
            Counter invalidRecords,
            Counter conflicts,
            Counter rejected,
            Counter purged,
            Counter dependencyErrors) {
        private QueueMetrics(MeterRegistry registry) {
            this(Map.ofEntries(
                    Map.entry("enqueued", counter(registry, "enqueued")),
                    Map.entry("deduplicated", counter(registry, "deduplicated")),
                    Map.entry("claimed", counter(registry, "claimed")),
                    Map.entry("acknowledged", counter(registry, "acknowledged")),
                    Map.entry(
                            "duplicate-acknowledgement",
                            counter(registry, "duplicate-acknowledgement")),
                    Map.entry("retry-scheduled", counter(registry, "retry-scheduled")),
                    Map.entry("lease-recovered", counter(registry, "lease-recovered")),
                    Map.entry("dead-lettered", counter(registry, "dead-lettered")),
                    Map.entry("invalid-record", counter(registry, "invalid-record")),
                    Map.entry("conflict", counter(registry, "conflict")),
                    Map.entry("rejected", counter(registry, "rejected")),
                    Map.entry("purged", counter(registry, "purged")),
                    Map.entry("dependency-error", counter(registry, "dependency-error"))));
        }

        private QueueMetrics(Map<String, Counter> counters) {
            this(
                    counters.get("enqueued"),
                    counters.get("deduplicated"),
                    counters.get("claimed"),
                    counters.get("acknowledged"),
                    counters.get("duplicate-acknowledgement"),
                    counters.get("retry-scheduled"),
                    counters.get("lease-recovered"),
                    counters.get("dead-lettered"),
                    counters.get("invalid-record"),
                    counters.get("conflict"),
                    counters.get("rejected"),
                    counters.get("purged"),
                    counters.get("dependency-error"));
        }

        private static Counter counter(MeterRegistry registry, String outcome) {
            return Counter.builder("careos.platform.redis.jobs")
                    .description("CareOS durable Redis job queue lifecycle transitions")
                    .tag("outcome", outcome)
                    .register(registry);
        }
    }
}
