package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.EvidenceCursorCodec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class HmacEvidenceCursorCodec implements EvidenceCursorCodec {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final int SIGNATURE_BYTES = 32;

    private final byte[] key;
    private final Clock clock;

    public HmacEvidenceCursorCodec(
            @Value("${careos.security.token-pepper}") String key, Clock clock) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @Override
    public String encode(Binding binding, Position position) {
        var expiry = position.asOf().plusSeconds(900);
        var payload = String.join(
                        "|",
                        "1",
                        binding.organizationId().toString(),
                        binding.projection(),
                        binding.filterDigest(),
                        Integer.toString(binding.limit()),
                        Long.toString(position.asOf().toEpochMilli()),
                        Long.toString(position.occurredAt().toEpochMilli()),
                        position.id().toString(),
                        Long.toString(expiry.toEpochMilli()))
                .getBytes(StandardCharsets.UTF_8);
        var signature = sign(payload);
        var combined = Arrays.copyOf(payload, payload.length + signature.length);
        System.arraycopy(signature, 0, combined, payload.length, signature.length);
        return ENCODER.encodeToString(combined);
    }

    @Override
    public Position decode(String cursor, Binding binding) {
        try {
            var combined = DECODER.decode(cursor);
            if (!ENCODER.encodeToString(combined).equals(cursor)) {
                throw invalid();
            }
            if (combined.length <= SIGNATURE_BYTES) {
                throw invalid();
            }
            var payload = Arrays.copyOf(combined, combined.length - SIGNATURE_BYTES);
            var signature = Arrays.copyOfRange(
                    combined, combined.length - SIGNATURE_BYTES, combined.length);
            if (!MessageDigest.isEqual(signature, sign(payload))) {
                throw invalid();
            }
            var fields = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 9
                    || !"1".equals(fields[0])
                    || !binding.organizationId().equals(UUID.fromString(fields[1]))
                    || !binding.projection().equals(fields[2])
                    || !binding.filterDigest().equals(fields[3])
                    || binding.limit() != Integer.parseInt(fields[4])) {
                throw invalid();
            }
            var asOf = Instant.ofEpochMilli(Long.parseLong(fields[5]));
            var occurredAt = Instant.ofEpochMilli(Long.parseLong(fields[6]));
            var id = UUID.fromString(fields[7]);
            var expiry = Instant.ofEpochMilli(Long.parseLong(fields[8]));
            if (!expiry.equals(asOf.plusSeconds(900))
                    || clock.instant().isAfter(expiry)
                    || asOf.isAfter(clock.instant().plusSeconds(5))) {
                throw invalid();
            }
            return new Position(asOf, occurredAt, id);
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("evidence cursor signing unavailable", exception);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "cursor is invalid, expired, or does not match these filters");
    }
}
