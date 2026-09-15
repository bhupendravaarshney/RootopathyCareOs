package com.rootopathy.careos.shared.domain;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/** Generates RFC 9562 UUIDv7 identifiers with process-local monotonic ordering. */
public final class UuidV7Generator {
    private static final long MAX_UNIX_MILLIS = 0x0000_FFFF_FFFF_FFFFL;
    private static final long RANDOM_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
    private static final int RANDOM_A_MASK = 0x0FFF;
    private static final UuidV7Generator SYSTEM =
            new UuidV7Generator(System::currentTimeMillis, new SecureRandom());

    private final LongSupplier unixMillis;
    private final RandomGenerator random;
    private long lastUnixMillis = -1;
    private int randomA;
    private long randomB;

    public UuidV7Generator() {
        this(System::currentTimeMillis, new SecureRandom());
    }

    UuidV7Generator(LongSupplier unixMillis, RandomGenerator random) {
        this.unixMillis = Objects.requireNonNull(unixMillis, "unixMillis");
        this.random = Objects.requireNonNull(random, "random");
    }

    public static UUID randomUuid() {
        return SYSTEM.next();
    }

    public synchronized UUID next() {
        var currentUnixMillis = unixMillis.getAsLong();
        requireSupportedTimestamp(currentUnixMillis);
        if (currentUnixMillis > lastUnixMillis) {
            lastUnixMillis = currentUnixMillis;
            reseedRandomBits();
        } else {
            incrementRandomBits();
        }
        return compose(lastUnixMillis, randomA, randomB);
    }

    static long unixTimestampMillis(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        if (uuid.version() != 7 || uuid.variant() != 2) {
            throw new IllegalArgumentException("uuid must be an RFC 9562 UUIDv7 value");
        }
        return uuid.getMostSignificantBits() >>> 16;
    }

    private void reseedRandomBits() {
        randomA = random.nextInt(RANDOM_A_MASK + 1);
        randomB = random.nextLong() & RANDOM_B_MASK;
    }

    private void incrementRandomBits() {
        if (randomB < RANDOM_B_MASK) {
            randomB++;
            return;
        }
        randomB = 0;
        if (randomA < RANDOM_A_MASK) {
            randomA++;
            return;
        }
        if (lastUnixMillis == MAX_UNIX_MILLIS) {
            throw new IllegalStateException("UUIDv7 timestamp and random space are exhausted");
        }
        lastUnixMillis++;
        reseedRandomBits();
    }

    private static UUID compose(long unixMillis, int randomA, long randomB) {
        var mostSignificantBits = (unixMillis << 16) | 0x7000L | (randomA & RANDOM_A_MASK);
        var leastSignificantBits = Long.MIN_VALUE | (randomB & RANDOM_B_MASK);
        return new UUID(mostSignificantBits, leastSignificantBits);
    }

    private static void requireSupportedTimestamp(long unixMillis) {
        if (unixMillis < 0 || unixMillis > MAX_UNIX_MILLIS) {
            throw new IllegalStateException("System time is outside the UUIDv7 timestamp range");
        }
    }
}
