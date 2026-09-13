package com.rootopathy.careos.identity.infrastructure.security;

import com.rootopathy.careos.identity.application.SecurityTokenPort;
import com.rootopathy.careos.identity.infrastructure.config.IdentitySecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public final class JdkSecurityTokenAdapter implements SecurityTokenPort {
    private static final char[] RECOVERY_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] pepper;

    public JdkSecurityTokenAdapter(IdentitySecurityProperties properties) {
        this.pepper = properties.tokenPepper().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String newOpaqueToken() {
        var bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String newRecoveryCode() {
        var code = new StringBuilder(14);
        for (var index = 0; index < 12; index++) {
            if (index > 0 && index % 4 == 0) {
                code.append('-');
            }
            code.append(RECOVERY_ALPHABET[secureRandom.nextInt(RECOVERY_ALPHABET.length)]);
        }
        return code.toString();
    }

    @Override
    public String digest(String value) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(pepper, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Required token digest algorithm is unavailable", exception);
        }
    }
}
