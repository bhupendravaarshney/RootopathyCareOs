package com.rootopathy.careos.patientregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.patientregistry.application.PatientScreenCursorCodec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HmacPatientScreenCursorCodecTest {
    private static final Instant NOW = Instant.parse("2026-09-26T08:00:00Z");
    private static final String KEY = "test-only-patient-cursor-key-at-least-thirty-two-bytes";
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID ACTOR_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000002");

    @Test
    void bindsCursorToActorScopeFiltersAndSnapshotWithoutEmbeddingSearchText() {
        var codec = new HmacPatientScreenCursorCodec(KEY, Clock.fixed(NOW, ZoneOffset.UTC));
        var binding = binding(ACTOR_ID, "Synthetic Patient Search");
        var position = new PatientScreenCursorCodec.Position(NOW, 25, "a".repeat(64));

        var cursor = codec.encode(binding, position);

        assertThat(codec.decode(cursor, binding)).isEqualTo(position);
        var signedBytes = Base64.getUrlDecoder().decode(cursor);
        var signedPayload = new String(
                signedBytes, 0, signedBytes.length - 32, StandardCharsets.UTF_8);
        assertThat(signedPayload).doesNotContain("Synthetic Patient Search");
        assertThatThrownBy(() -> codec.decode(
                        cursor,
                        binding(
                                UUID.fromString("01900000-0000-7000-8000-000000000003"),
                                "Synthetic Patient Search")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(cursor, binding(ACTOR_ID, "different search")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(tamper(cursor), binding))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExpiredCursor() {
        var encoded = new HmacPatientScreenCursorCodec(KEY, Clock.fixed(NOW, ZoneOffset.UTC))
                .encode(binding(ACTOR_ID, "search"),
                        new PatientScreenCursorCodec.Position(NOW, 10, "b".repeat(64)));
        var expiredCodec = new HmacPatientScreenCursorCodec(
                KEY, Clock.fixed(NOW.plusSeconds(901), ZoneOffset.UTC));

        assertThatThrownBy(() -> expiredCodec.decode(encoded, binding(ACTOR_ID, "search")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid, expired");
    }

    private static PatientScreenCursorCodec.Binding binding(UUID actorId, String search) {
        return new PatientScreenCursorCodec.Binding(
                ORGANIZATION_ID,
                actorId,
                "P3-02",
                null,
                null,
                search,
                "active",
                25);
    }

    private static String tamper(String value) {
        var last = value.charAt(value.length() - 1);
        return value.substring(0, value.length() - 1) + (last == 'A' ? 'B' : 'A');
    }
}
