package com.rootopathy.careos.platform.infrastructure;

import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("careos.storage.s3")
public final class S3DocumentStorageProperties {
    private static final long FIVE_TEBIBYTES = 5L * 1024 * 1024 * 1024 * 1024;
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]");

    private boolean enabled;
    private URI endpoint;
    private String accessKey;
    private String secretKey;
    private String quarantineBucket = "careos-document-quarantine";
    private long maximumUploadBytes = 50L * 1024 * 1024;
    private boolean allowHttp;
    private boolean createBucketIfMissing;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(URI endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getQuarantineBucket() {
        return quarantineBucket;
    }

    public void setQuarantineBucket(String quarantineBucket) {
        this.quarantineBucket = quarantineBucket;
    }

    public long getMaximumUploadBytes() {
        return maximumUploadBytes;
    }

    public void setMaximumUploadBytes(long maximumUploadBytes) {
        this.maximumUploadBytes = maximumUploadBytes;
    }

    public boolean isAllowHttp() {
        return allowHttp;
    }

    public void setAllowHttp(boolean allowHttp) {
        this.allowHttp = allowHttp;
    }

    public boolean isCreateBucketIfMissing() {
        return createBucketIfMissing;
    }

    public void setCreateBucketIfMissing(boolean createBucketIfMissing) {
        this.createBucketIfMissing = createBucketIfMissing;
    }

    public void validateForActivation() {
        if (!enabled) {
            throw new IllegalStateException("S3 quarantine storage is not enabled");
        }
        if (endpoint == null
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))) {
            throw new IllegalStateException("S3 endpoint must be an absolute origin without credentials or a path");
        }
        var scheme = endpoint.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || (allowHttp && "http".equalsIgnoreCase(scheme)))) {
            throw new IllegalStateException("S3 endpoint must use HTTPS unless the local HTTP override is explicit");
        }
        requireSecret(accessKey, "access key", 3);
        requireSecret(secretKey, "secret key", 8);
        if (quarantineBucket == null || !BUCKET.matcher(quarantineBucket).matches()) {
            throw new IllegalStateException("S3 quarantine bucket has an invalid format");
        }
        if (maximumUploadBytes < 1 || maximumUploadBytes > FIVE_TEBIBYTES) {
            throw new IllegalStateException("S3 maximum upload bytes is outside the supported range");
        }
    }

    @Override
    public String toString() {
        return "S3DocumentStorageProperties[enabled=" + enabled
                + ", endpoint=" + endpoint
                + ", accessKey=<redacted>, secretKey=<redacted>, quarantineBucket="
                + quarantineBucket
                + ", maximumUploadBytes=" + maximumUploadBytes
                + ", allowHttp=" + allowHttp
                + ", createBucketIfMissing=" + createBucketIfMissing + "]";
    }

    private static void requireSecret(String value, String name, int minimumLength) {
        if (value == null
                || value.length() < minimumLength
                || value.length() > 256
                || !value.equals(value.strip())) {
            throw new IllegalStateException("S3 " + name + " has an invalid format");
        }
    }
}
