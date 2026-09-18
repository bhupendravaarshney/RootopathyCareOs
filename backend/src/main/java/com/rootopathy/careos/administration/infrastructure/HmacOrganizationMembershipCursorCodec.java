package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.OrganizationAdministrationException;
import com.rootopathy.careos.administration.application.OrganizationMembershipCursorCodec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class HmacOrganizationMembershipCursorCodec
        implements OrganizationMembershipCursorCodec {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String OPERATION = "access.membership.read";
    private static final Duration CURSOR_TTL = Duration.ofMinutes(15);
    private static final Duration MAXIMUM_FUTURE_SKEW = Duration.ofSeconds(5);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final int SIGNATURE_BYTES = 32;

    private final byte[] signingKey;
    private final Clock clock;

    public HmacOrganizationMembershipCursorCodec(
            @Value("${careos.security.token-pepper}") String signingKey, Clock clock) {
        this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @Override
    public String encode(CursorBinding binding, CursorPosition position) {
        var expiresAt = position.asOf().plus(CURSOR_TTL);
        var payload = String.join(
                        "|",
                        "1",
                        binding.organizationId().toString(),
                        OPERATION,
                        binding.filterDigest(),
                        Integer.toString(binding.limit()),
                        Long.toString(position.asOf().toEpochMilli()),
                        Long.toString(position.effectiveFrom().toEpochMilli()),
                        position.membershipId().toString(),
                        Long.toString(expiresAt.toEpochMilli()))
                .getBytes(StandardCharsets.UTF_8);
        var signature = sign(payload);
        var combined = Arrays.copyOf(payload, payload.length + signature.length);
        System.arraycopy(signature, 0, combined, payload.length, signature.length);
        return ENCODER.encodeToString(combined);
    }

    @Override
    public CursorPosition decode(String cursor, CursorBinding binding) {
        try {
            var combined = DECODER.decode(cursor);
            if (combined.length <= SIGNATURE_BYTES) {
                throw invalid();
            }
            var payload = Arrays.copyOf(combined, combined.length - SIGNATURE_BYTES);
            var suppliedSignature = Arrays.copyOfRange(
                    combined, combined.length - SIGNATURE_BYTES, combined.length);
            if (!MessageDigest.isEqual(sign(payload), suppliedSignature)) {
                throw invalid();
            }
            var fields = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 9
                    || !"1".equals(fields[0])
                    || !binding.organizationId().equals(UUID.fromString(fields[1]))
                    || !OPERATION.equals(fields[2])
                    || !binding.filterDigest().equals(fields[3])
                    || binding.limit() != Integer.parseInt(fields[4])) {
                throw invalid();
            }
            var asOf = Instant.ofEpochMilli(Long.parseLong(fields[5]));
            var effectiveFrom = Instant.ofEpochMilli(Long.parseLong(fields[6]));
            var membershipId = UUID.fromString(fields[7]);
            var expiresAt = Instant.ofEpochMilli(Long.parseLong(fields[8]));
            var now = clock.instant();
            if (!expiresAt.equals(asOf.plus(CURSOR_TTL))
                    || now.isAfter(expiresAt)
                    || asOf.isAfter(now.plus(MAXIMUM_FUTURE_SKEW))) {
                throw invalid();
            }
            return new CursorPosition(asOf, effectiveFrom, membershipId);
        } catch (OrganizationAdministrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] value) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            return mac.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Membership cursor signing is unavailable", exception);
        }
    }

    private static OrganizationAdministrationException invalid() {
        return new OrganizationAdministrationException(
                OrganizationAdministrationException.Reason.MEMBERSHIP_LIST_INVALID,
                "The membership cursor is invalid, expired, or does not match these filters.");
    }
}
