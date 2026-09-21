package com.rootopathy.careos.administration.infrastructure;

import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("careos.evidence-export.storage")
public class EvidenceExportStorageProperties {
  private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]");
  private boolean enabled;
  private URI endpoint;
  private URI publicEndpoint;
  private String accessKey;
  private String secretKey;
  private String bucket = "careos-evidence-exports";
  private boolean allowHttp;
  private boolean createBucketIfMissing;

  public void validate() {
    if (!enabled) throw new IllegalStateException("Evidence export storage is disabled");
    validateOrigin(endpoint, "endpoint");
    if (publicEndpoint != null) validateOrigin(publicEndpoint, "public endpoint");
    requireSecret(accessKey, "access key", 3);
    requireSecret(secretKey, "secret key", 8);
    if (bucket == null || !BUCKET.matcher(bucket).matches()) {
      throw new IllegalStateException("Evidence export bucket is invalid");
    }
  }

  private void validateOrigin(URI value, String field) {
    if (value == null
        || value.getHost() == null
        || value.getUserInfo() != null
        || value.getQuery() != null
        || value.getFragment() != null
        || !(value.getPath().isEmpty() || "/".equals(value.getPath()))) {
      throw new IllegalStateException("Evidence export " + field + " must be an absolute origin");
    }
    if (!("https".equalsIgnoreCase(value.getScheme())
        || (allowHttp && "http".equalsIgnoreCase(value.getScheme())))) {
      throw new IllegalStateException("Evidence export " + field + " requires HTTPS");
    }
  }

  private static void requireSecret(String value, String field, int minimum) {
    if (value == null || value.length() < minimum || value.length() > 256 || !value.equals(value.strip())) {
      throw new IllegalStateException("Evidence export " + field + " is invalid");
    }
  }

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public URI getEndpoint() { return endpoint; }
  public void setEndpoint(URI endpoint) { this.endpoint = endpoint; }
  public URI getPublicEndpoint() { return publicEndpoint == null ? endpoint : publicEndpoint; }
  public void setPublicEndpoint(URI publicEndpoint) { this.publicEndpoint = publicEndpoint; }
  public String getAccessKey() { return accessKey; }
  public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
  public String getSecretKey() { return secretKey; }
  public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
  public String getBucket() { return bucket; }
  public void setBucket(String bucket) { this.bucket = bucket; }
  public boolean isAllowHttp() { return allowHttp; }
  public void setAllowHttp(boolean allowHttp) { this.allowHttp = allowHttp; }
  public boolean isCreateBucketIfMissing() { return createBucketIfMissing; }
  public void setCreateBucketIfMissing(boolean createBucketIfMissing) { this.createBucketIfMissing = createBucketIfMissing; }
}
