package com.rootopathy.careos.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {
    private static final long UNIX_MILLIS = 1_757_937_600_123L;

    @Test
    void encodesTheUnixTimestampAndRfcVersionAndVariant() {
        var generator = new UuidV7Generator(() -> UNIX_MILLIS, new Random(7));

        var generated = generator.next();

        assertThat(generated.version()).isEqualTo(7);
        assertThat(generated.variant()).isEqualTo(2);
        assertThat(UuidV7Generator.unixTimestampMillis(generated)).isEqualTo(UNIX_MILLIS);
    }

    @Test
    void remainsUniqueAndStrictlyOrderedInsideOneMillisecond() {
        var generator = new UuidV7Generator(() -> UNIX_MILLIS, new Random(11));
        var generated = new HashSet<UUID>();
        var previous = generator.next();
        generated.add(previous);

        for (var index = 0; index < 10_000; index++) {
            var current = generator.next();
            assertThat(current).isGreaterThan(previous);
            assertThat(generated.add(current)).isTrue();
            previous = current;
        }
    }

    @Test
    void preservesOrderingWhenTheWallClockMovesBackwards() {
        var clockIndex = new AtomicInteger();
        var values = new long[] {UNIX_MILLIS, UNIX_MILLIS - 1_000, UNIX_MILLIS + 1};
        var generator = new UuidV7Generator(
                () -> values[Math.min(clockIndex.getAndIncrement(), values.length - 1)],
                new Random(13));

        var first = generator.next();
        var afterRollback = generator.next();
        var afterRecovery = generator.next();

        assertThat(afterRollback).isGreaterThan(first);
        assertThat(UuidV7Generator.unixTimestampMillis(afterRollback)).isEqualTo(UNIX_MILLIS);
        assertThat(afterRecovery).isGreaterThan(afterRollback);
        assertThat(UuidV7Generator.unixTimestampMillis(afterRecovery))
                .isEqualTo(UNIX_MILLIS + 1);
    }

    @Test
    void rejectsUnsupportedTimestampsAndNonVersionSevenValues() {
        var invalidClock = new UuidV7Generator(() -> -1, new Random(17));

        assertThatThrownBy(invalidClock::next)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UUIDv7 timestamp range");
        assertThatThrownBy(() -> UuidV7Generator.unixTimestampMillis(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUIDv7");
    }
}
