package com.rootopathy.careos.identity.infrastructure.security;

import com.rootopathy.careos.identity.application.TotpPort;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public final class JdkTotpAdapter implements TotpPort {
    private static final char[] BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int TIME_STEP_SECONDS = 30;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String newSecret() {
        var bytes = new byte[20];
        secureRandom.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    @Override
    public boolean verify(String secret, String code, Instant now) {
        if (code == null || !code.matches("[0-9]{6}")) {
            return false;
        }
        var counter = now.getEpochSecond() / TIME_STEP_SECONDS;
        for (var offset = -1; offset <= 1; offset++) {
            var expected = codeForCounter(secret, counter + offset);
            if (MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String provisioningUri(String issuer, String accountName, String secret) {
        var encodedIssuer = encodeUriComponent(issuer);
        var label = encodedIssuer + ":" + encodeUriComponent(accountName);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + encodedIssuer
                + "&algorithm=SHA1&digits=6&period=30";
    }

    private static String codeForCounter(String secret, long counter) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
            var digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            var offset = digest[digest.length - 1] & 0x0f;
            var binary = ((digest[offset] & 0x7f) << 24)
                    | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8)
                    | (digest[offset + 3] & 0xff);
            return "%06d".formatted(binary % 1_000_000);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Required TOTP algorithm is unavailable", exception);
        }
    }

    private static String encodeBase32(byte[] value) {
        var result = new StringBuilder((value.length * 8 + 4) / 5);
        var buffer = 0;
        var bitsRemaining = 0;
        for (byte current : value) {
            buffer = (buffer << 8) | (current & 0xff);
            bitsRemaining += 8;
            while (bitsRemaining >= 5) {
                result.append(BASE32_ALPHABET[(buffer >> (bitsRemaining - 5)) & 0x1f]);
                bitsRemaining -= 5;
            }
        }
        if (bitsRemaining > 0) {
            result.append(BASE32_ALPHABET[(buffer << (5 - bitsRemaining)) & 0x1f]);
        }
        return result.toString();
    }

    private static byte[] decodeBase32(String value) {
        var normalized = value.replace("=", "").toUpperCase(java.util.Locale.ROOT);
        var result = new byte[normalized.length() * 5 / 8];
        var buffer = 0;
        var bitsRemaining = 0;
        var outputIndex = 0;
        for (var current : normalized.toCharArray()) {
            var decoded = decodeBase32Character(current);
            if (decoded < 0) {
                throw new IllegalArgumentException("Invalid Base32 data");
            }
            buffer = (buffer << 5) | decoded;
            bitsRemaining += 5;
            if (bitsRemaining >= 8) {
                result[outputIndex++] = (byte) ((buffer >> (bitsRemaining - 8)) & 0xff);
                bitsRemaining -= 8;
            }
        }
        return result;
    }

    private static int decodeBase32Character(char value) {
        if (value >= 'A' && value <= 'Z') {
            return value - 'A';
        }
        if (value >= '2' && value <= '7') {
            return value - '2' + 26;
        }
        return -1;
    }

    private static String encodeUriComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
