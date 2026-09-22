package com.rootopathy.careos.administration.infrastructure;

import com.rootopathy.careos.administration.application.EvidenceExportArtifactStore;
import io.minio.MinioClient;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EvidenceExportStorageProperties.class)
public class EvidenceExportStorageConfiguration {
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(
      prefix = "careos.evidence-export.storage", name = "enabled", havingValue = "true")
  EvidenceExportArtifactStore evidenceExportArtifactStore(
      EvidenceExportStorageProperties properties, ObjectProvider<Clock> clocks) {
    properties.validate();
    var storageClient = MinioClient.builder()
        .endpoint(properties.getEndpoint().toString())
        .credentials(properties.getAccessKey(), properties.getSecretKey())
        .build();
    var signingClient = MinioClient.builder()
        .endpoint(properties.getPublicEndpoint().toString())
        .credentials(properties.getAccessKey(), properties.getSecretKey())
        .build();
    storageClient.setAppInfo("careos-evidence-export", "1.0.0");
    signingClient.setAppInfo("careos-evidence-export-signer", "1.0.0");
    var store = new S3EvidenceExportArtifactStore(
        storageClient,
        signingClient,
        properties.getBucket(),
        properties.isCreateBucketIfMissing(),
        clocks.getIfAvailable(Clock::systemUTC));
    store.initialize();
    return store;
  }

  @Bean
  @ConditionalOnMissingBean(EvidenceExportArtifactStore.class)
  EvidenceExportArtifactStore unavailableEvidenceExportArtifactStore() {
    return new EvidenceExportArtifactStore() {
      private IllegalStateException unavailable() {
        return new IllegalStateException("Evidence export artifact storage is unavailable");
      }
      public boolean available() { return false; }
      public StoredArtifact store(com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext c, java.util.UUID id, String type, String filename, byte[] content, String digest) { throw unavailable(); }
      public AccessGrant createReadGrant(com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext c, java.util.UUID id, String reference, String digest, String filename, java.time.Duration ttl) { throw unavailable(); }
      public ArtifactContent open(com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext c, java.util.UUID id, String reference, String digest, long bytes) { throw unavailable(); }
      public void delete(com.rootopathy.careos.tenancy.domain.AuthorizedTenantContext c, java.util.UUID id, String reference, String digest) { throw unavailable(); }
    };
  }
}
