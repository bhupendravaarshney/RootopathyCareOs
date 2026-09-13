package com.rootopathy.careos.platform.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Internal, closeable access to one immutable quarantine object. This type is an application
 * boundary and must never be returned from an HTTP controller.
 */
public final class QuarantinedDocumentContent implements AutoCloseable {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final InputStream stream;
    private final long expectedBytes;
    private final String expectedSha256;

    public QuarantinedDocumentContent(
            InputStream stream, long expectedBytes, String expectedSha256) {
        this.stream = Objects.requireNonNull(stream, "stream");
        if (expectedBytes < 1) {
            throw new IllegalArgumentException("expectedBytes must be positive");
        }
        if (expectedSha256 == null || !SHA_256.matcher(expectedSha256).matches()) {
            throw new IllegalArgumentException(
                    "expectedSha256 must be 64 lowercase hexadecimal characters");
        }
        this.expectedBytes = expectedBytes;
        this.expectedSha256 = expectedSha256;
    }

    public InputStream stream() {
        return stream;
    }

    public long expectedBytes() {
        return expectedBytes;
    }

    public String expectedSha256() {
        return expectedSha256;
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
