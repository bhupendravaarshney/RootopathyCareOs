package com.rootopathy.careos.workforce.infrastructure;

import com.rootopathy.careos.workforce.application.WorkforceImpactTokenCodec;
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
public final class HmacWorkforceImpactTokenCodec implements WorkforceImpactTokenCodec {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final byte[] signingKey;
    private final Clock clock;

    public HmacWorkforceImpactTokenCodec(
            @Value("${careos.security.token-pepper}") String signingKey,
            Clock clock) {
        this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @Override
    public String encode(Binding binding, String impactDigest, Instant expiresAt) {
        var payload = String.join(
                        "|",
                        "m2-impact-v1",
                        binding.organizationId().toString(),
                        binding.actorId().toString(),
                        binding.screenId(),
                        binding.actionKey(),
                        binding.targetId().toString(),
                        Long.toString(binding.revision()),
                        binding.requestDigest(),
                        impactDigest,
                        Long.toString(expiresAt.toEpochMilli()))
                .getBytes(StandardCharsets.UTF_8);
        var signature = sign(payload);
        var token = Arrays.copyOf(payload, payload.length + signature.length);
        System.arraycopy(signature, 0, token, payload.length, signature.length);
        return ENCODER.encodeToString(token);
    }

    @Override
    public Decoded decode(String token, Binding binding) {
        try {
            var value = DECODER.decode(token);
            if (!ENCODER.encodeToString(value).equals(token)) throw invalid();
            if (value.length <= 32) throw invalid();
            var payload = Arrays.copyOf(value, value.length - 32);
            var signature = Arrays.copyOfRange(value, value.length - 32, value.length);
            if (!MessageDigest.isEqual(signature, sign(payload))) throw invalid();
            var fields = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 10
                    || !"m2-impact-v1".equals(fields[0])
                    || !binding.organizationId().toString().equals(fields[1])
                    || !binding.actorId().toString().equals(fields[2])
                    || !binding.screenId().equals(fields[3])
                    || !binding.actionKey().equals(fields[4])
                    || !binding.targetId().toString().equals(fields[5])
                    || binding.revision() != Long.parseLong(fields[6])
                    || !binding.requestDigest().equals(fields[7])
                    || !fields[8].matches("[0-9a-f]{64}")) {
                throw invalid();
            }
            var expiresAt = Instant.ofEpochMilli(Long.parseLong(fields[9]));
            var now = clock.instant();
            if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plusSeconds(605))) {
                throw invalid();
            }
            return new Decoded(fields[8], expiresAt);
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            mac.update("careos-workforce-impact\0".getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Workforce impact token signing is unavailable.", exception);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "The impact preview is invalid, expired, or does not match this action.");
    }
}
