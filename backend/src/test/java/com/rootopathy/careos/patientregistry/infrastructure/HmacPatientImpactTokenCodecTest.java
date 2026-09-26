package com.rootopathy.careos.patientregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.patientregistry.application.PatientImpactTokenCodec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HmacPatientImpactTokenCodecTest {
    private static final Instant NOW = Instant.parse("2026-09-26T08:00:00Z");
    private static final String KEY = "test-only-patient-impact-key-at-least-thirty-two-bytes";
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID ACTOR_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000002");
    private static final UUID TARGET_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000003");

    @Test
    void bindsImpactToActorActionRevisionAndCanonicalRequest() {
        var codec = new HmacPatientImpactTokenCodec(KEY, Clock.fixed(NOW, ZoneOffset.UTC));
        var binding = binding(ACTOR_ID, 7, "c".repeat(64));
        var impactDigest = "d".repeat(64);
        var expiry = NOW.plusSeconds(600);
        var token = codec.encode(binding, impactDigest, expiry);

        assertThat(codec.decode(token, binding))
                .isEqualTo(new PatientImpactTokenCodec.Decoded(impactDigest, expiry));
        assertThatThrownBy(() -> codec.decode(
                        token,
                        binding(
                                UUID.fromString("01900000-0000-7000-8000-000000000004"),
                                7,
                                "c".repeat(64))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(token, binding(ACTOR_ID, 8, "c".repeat(64))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(token, binding(ACTOR_ID, 7, "e".repeat(64))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(tamper(token), binding))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExpiredAndUnreasonablyLongLivedTokens() {
        var encoder = new HmacPatientImpactTokenCodec(KEY, Clock.fixed(NOW, ZoneOffset.UTC));
        var binding = binding(ACTOR_ID, 2, "a".repeat(64));
        var expired = encoder.encode(binding, "b".repeat(64), NOW.plusSeconds(1));
        var excessive = encoder.encode(binding, "b".repeat(64), NOW.plusSeconds(606));

        var afterExpiry = new HmacPatientImpactTokenCodec(
                KEY, Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));
        assertThatThrownBy(() -> afterExpiry.decode(expired, binding))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encoder.decode(excessive, binding))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PatientImpactTokenCodec.Binding binding(
            UUID actorId, long revision, String requestDigest) {
        return new PatientImpactTokenCodec.Binding(
                ORGANIZATION_ID,
                actorId,
                "P3-15",
                "execute-patient-merge",
                TARGET_ID,
                revision,
                requestDigest);
    }

    private static String tamper(String value) {
        var last = value.charAt(value.length() - 1);
        return value.substring(0, value.length() - 1) + (last == 'A' ? 'B' : 'A');
    }
}
