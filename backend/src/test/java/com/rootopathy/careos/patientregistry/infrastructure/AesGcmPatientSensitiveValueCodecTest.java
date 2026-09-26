package com.rootopathy.careos.patientregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AesGcmPatientSensitiveValueCodecTest {
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String PEPPER =
            "test-only-patient-sensitive-pepper-at-least-thirty-two-bytes";

    @Test
    void encryptsWithRandomizedAuthenticatedCiphertextAndScopesDigests() {
        var codec = new AesGcmPatientSensitiveValueCodec(KEY, PEPPER);
        var normalized = "patient@example.invalid";

        var first = codec.encrypt(ORGANIZATION_ID, "contact:email", normalized);
        var second = codec.encrypt(ORGANIZATION_ID, "contact:email", normalized);
        var digest = codec.digest(ORGANIZATION_ID, "contact:email", normalized);

        assertThat(first).startsWith(new byte[] {'C', 'P', 'S', '1'}).isNotEqualTo(second);
        assertThat(new String(first, StandardCharsets.UTF_8)).doesNotContain(normalized);
        assertThat(digest).matches("[0-9a-f]{64}")
                .isEqualTo(codec.digest(ORGANIZATION_ID, "contact:email", normalized))
                .isNotEqualTo(codec.digest(
                        UUID.fromString("01900000-0000-7000-8000-000000000002"),
                        "contact:email",
                        normalized))
                .isNotEqualTo(codec.digest(ORGANIZATION_ID, "contact:phone", normalized));
    }

    @Test
    void rejectsInvalidKeyMaterialAndMissingScope() {
        assertThatThrownBy(() -> new AesGcmPatientSensitiveValueCodec("not-base64", PEPPER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid Base64");
        var codec = new AesGcmPatientSensitiveValueCodec(KEY, PEPPER);
        assertThatThrownBy(() -> codec.digest(ORGANIZATION_ID, " ", "value"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
