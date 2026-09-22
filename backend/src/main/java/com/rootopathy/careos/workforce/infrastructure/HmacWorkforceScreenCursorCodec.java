package com.rootopathy.careos.workforce.infrastructure;

import com.rootopathy.careos.workforce.application.WorkforceScreenCursorCodec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class HmacWorkforceScreenCursorCodec implements WorkforceScreenCursorCodec {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final byte[] key;
    private final Clock clock;

    public HmacWorkforceScreenCursorCodec(
            @Value("${careos.security.token-pepper}") String key,
            Clock clock) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @Override
    public String encode(Binding binding, Position position) {
        var expiresAt = position.asOf().plusSeconds(900);
        var payload = String.join(
                        "|",
                        "m2-screen-cursor-v2",
                        binding.organizationId().toString(),
                        binding.actorId().toString(),
                        binding.screenId(),
                        nullable(binding.memberId()),
                        digestNullable(binding.search()),
                        digestNullable(binding.status()),
                        Integer.toString(binding.pageSize()),
                        Long.toString(position.asOf().toEpochMilli()),
                        Integer.toString(position.offset()),
                        requiredDigest(position.snapshotDigest()),
                        Long.toString(expiresAt.toEpochMilli()))
                .getBytes(StandardCharsets.UTF_8);
        var signature = sign(payload);
        var token = Arrays.copyOf(payload, payload.length + signature.length);
        System.arraycopy(signature, 0, token, payload.length, signature.length);
        return ENCODER.encodeToString(token);
    }

    @Override
    public Position decode(String cursor, Binding binding) {
        try {
            var token = DECODER.decode(cursor);
            if (token.length <= 32) throw invalid();
            var payload = Arrays.copyOf(token, token.length - 32);
            var signature = Arrays.copyOfRange(token, token.length - 32, token.length);
            if (!MessageDigest.isEqual(signature, sign(payload))) throw invalid();
            var fields = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 12
                    || !fields[0].equals("m2-screen-cursor-v2")
                    || !fields[1].equals(binding.organizationId().toString())
                    || !fields[2].equals(binding.actorId().toString())
                    || !fields[3].equals(binding.screenId())
                    || !fields[4].equals(nullable(binding.memberId()))
                    || !fields[5].equals(digestNullable(binding.search()))
                    || !fields[6].equals(digestNullable(binding.status()))
                    || Integer.parseInt(fields[7]) != binding.pageSize()) {
                throw invalid();
            }
            var asOf = Instant.ofEpochMilli(Long.parseLong(fields[8]));
            var offset = Integer.parseInt(fields[9]);
            var snapshotDigest = requiredDigest(fields[10]);
            var expiresAt = Instant.ofEpochMilli(Long.parseLong(fields[11]));
            if (!expiresAt.equals(asOf.plusSeconds(900))
                    || !expiresAt.isAfter(clock.instant())
                    || asOf.isAfter(clock.instant().plusSeconds(5))
                    || offset < 1
                    || offset > 10_000) {
                throw invalid();
            }
            return new Position(asOf, offset, snapshotDigest);
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            mac.update("careos-workforce-screen-cursor\0".getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Workforce screen cursor signing is unavailable.", exception);
        }
    }

    private static String nullable(Object value) {
        return value == null ? "-" : value.toString();
    }

    private static String digestNullable(String value) {
        if (value == null) return "-";
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String requiredDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "The workforce page cursor is invalid, expired, or bound to different filters.");
    }
}
