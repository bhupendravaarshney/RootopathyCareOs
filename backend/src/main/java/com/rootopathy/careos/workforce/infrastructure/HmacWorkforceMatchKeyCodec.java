package com.rootopathy.careos.workforce.infrastructure;

import com.rootopathy.careos.workforce.application.WorkforceMatchKeyCodec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class HmacWorkforceMatchKeyCodec implements WorkforceMatchKeyCodec {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String KEY_VERSION = "m2-match-hmac-v1";
    private final byte[] key;

    public HmacWorkforceMatchKeyCodec(
            @Value("${careos.security.token-pepper}") String key) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String keyVersion() {
        return KEY_VERSION;
    }

    @Override
    public String digest(UUID organizationId, String keyType, String normalizedValue) {
        if (organizationId == null || keyType == null || normalizedValue == null) {
            throw new IllegalArgumentException("Match-key inputs are required.");
        }
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            mac.update("careos-workforce-person-match\0".getBytes(StandardCharsets.UTF_8));
            mac.update(organizationId.toString().getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(keyType.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(
                    mac.doFinal(normalizedValue.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Workforce person-match keying is unavailable.", exception);
        }
    }
}
