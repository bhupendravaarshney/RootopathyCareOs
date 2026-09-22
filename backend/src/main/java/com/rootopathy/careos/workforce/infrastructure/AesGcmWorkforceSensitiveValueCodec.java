package com.rootopathy.careos.workforce.infrastructure;

import com.rootopathy.careos.workforce.application.WorkforceSensitiveValueCodec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class AesGcmWorkforceSensitiveValueCodec implements WorkforceSensitiveValueCodec {
    private static final byte[] MAGIC = {'C', 'W', 'S', '1'};
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec encryptionKey;
    private final byte[] digestKey;
    private final SecureRandom random = new SecureRandom();

    public AesGcmWorkforceSensitiveValueCodec(
            @Value("${careos.security.mfa-encryption-key}") String encryptionKey,
            @Value("${careos.security.token-pepper}") String digestKey) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encryptionKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "careos.security.mfa-encryption-key must be valid Base64", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException(
                    "careos.security.mfa-encryption-key must decode to exactly 32 bytes");
        }
        this.encryptionKey = new SecretKeySpec(
                derive(decoded, "careos-workforce-sensitive-encryption-v1"), "AES");
        this.digestKey = derive(
                digestKey.getBytes(StandardCharsets.UTF_8),
                "careos-workforce-sensitive-digest-v1");
    }

    @Override
    public byte[] encrypt(UUID organizationId, String field, String normalizedValue) {
        requireInputs(organizationId, field, normalizedValue);
        try {
            var iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(organizationId, field));
            var ciphertext = cipher.doFinal(normalizedValue.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(MAGIC.length + iv.length + ciphertext.length)
                    .put(MAGIC)
                    .put(iv)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Workforce sensitive-value encryption is unavailable.", exception);
        }
    }

    @Override
    public String digest(UUID organizationId, String field, String normalizedValue) {
        requireInputs(organizationId, field, normalizedValue);
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(digestKey, HMAC_ALGORITHM));
            mac.update(aad(organizationId, field));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(
                    mac.doFinal(normalizedValue.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Workforce sensitive-value keying is unavailable.", exception);
        }
    }

    private static byte[] aad(UUID organizationId, String field) {
        return ("careos-workforce-sensitive-v1\0" + organizationId + "\0" + field)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] derive(byte[] source, String context) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(context.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return digest.digest(source);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Workforce key derivation is unavailable.", exception);
        }
    }

    private static void requireInputs(
            UUID organizationId, String field, String normalizedValue) {
        if (organizationId == null
                || field == null
                || field.isBlank()
                || normalizedValue == null) {
            throw new IllegalArgumentException("Sensitive-value inputs are required.");
        }
    }
}
