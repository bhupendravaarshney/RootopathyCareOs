package com.rootopathy.careos.identity.infrastructure.security;

import com.rootopathy.careos.identity.application.MfaSecretProtectionPort;
import com.rootopathy.careos.identity.infrastructure.config.IdentitySecurityProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public final class AesGcmMfaSecretProtectionAdapter implements MfaSecretProtectionPort {
    private static final String PREFIX = "v1:";
    private static final byte[] ASSOCIATED_DATA = "careos:mfa:totp:v1".getBytes(StandardCharsets.UTF_8);

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec key;

    public AesGcmMfaSecretProtectionAdapter(IdentitySecurityProperties properties) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(properties.mfaEncryptionKey()), "AES");
    }

    @Override
    public String protect(String plaintext) {
        try {
            var initializationVector = new byte[12];
            secureRandom.nextBytes(initializationVector);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, initializationVector));
            cipher.updateAAD(ASSOCIATED_DATA);
            var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            var payload = ByteBuffer.allocate(initializationVector.length + ciphertext.length)
                    .put(initializationVector)
                    .put(ciphertext)
                    .array();
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("MFA secret protection failed", exception);
        }
    }

    @Override
    public String reveal(String protectedValue) {
        if (protectedValue == null || !protectedValue.startsWith(PREFIX)) {
            throw new IllegalStateException("Unsupported MFA secret format");
        }
        try {
            var payload = Base64.getUrlDecoder().decode(protectedValue.substring(PREFIX.length()));
            if (payload.length < 29) {
                throw new IllegalStateException("Protected MFA secret is invalid");
            }
            var initializationVector = new byte[12];
            var ciphertext = new byte[payload.length - initializationVector.length];
            System.arraycopy(payload, 0, initializationVector, 0, initializationVector.length);
            System.arraycopy(payload, initializationVector.length, ciphertext, 0, ciphertext.length);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, initializationVector));
            cipher.updateAAD(ASSOCIATED_DATA);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Protected MFA secret could not be read", exception);
        }
    }
}
