package com.rootopathy.careos.administration.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootopathy.careos.administration.application.EvidenceCursorCodec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HmacEvidenceCursorCodecTest {
    private static final Instant NOW = Instant.parse("2026-09-26T08:00:00Z");

    @Test
    void rejectsNonCanonicalBase64UrlThatDecodesToTheSignedBytes() {
        var codec = new HmacEvidenceCursorCodec(
                "test-only-cursor-key-with-at-least-thirty-two-bytes",
                Clock.fixed(NOW, ZoneOffset.UTC));
        var binding = new EvidenceCursorCodec.Binding(
                UUID.fromString("01900000-0000-7000-8000-000000000001"),
                "audit",
                "filter-digest",
                25);
        var position = new EvidenceCursorCodec.Position(
                NOW,
                NOW.minusSeconds(30),
                UUID.fromString("01900000-0000-7000-8000-000000000002"));
        var cursor = codec.encode(binding, position);
        var nonCanonicalCursor = equivalentNonCanonicalEncoding(cursor);

        assertThat(codec.decode(cursor, binding)).isEqualTo(position);
        assertThatThrownBy(() -> codec.decode(nonCanonicalCursor, binding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cursor is invalid, expired, or does not match these filters");
    }

    private static String equivalentNonCanonicalEncoding(String cursor) {
        var decoded = Base64.getUrlDecoder().decode(cursor);
        var alphabet =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        for (var candidate : alphabet.toCharArray()) {
            if (candidate == cursor.charAt(cursor.length() - 1)) {
                continue;
            }
            var replacement = cursor.substring(0, cursor.length() - 1) + candidate;
            if (java.util.Arrays.equals(decoded, Base64.getUrlDecoder().decode(replacement))) {
                return replacement;
            }
        }
        throw new AssertionError("test cursor did not expose a non-canonical Base64URL form");
    }
}
