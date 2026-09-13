package com.rootopathy.careos.platform.infrastructure;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3DocumentStorageProperties.class)
public class S3DocumentStorageConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "careos.storage.s3", name = "enabled", havingValue = "true")
    S3PrivateDocumentStorageAdapter s3PrivateDocumentStorageAdapter(
            S3DocumentStorageProperties properties) {
        properties.validateForActivation();
        var client = MinioClient.builder()
                .endpoint(properties.getEndpoint().toString())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        client.setAppInfo("careos-private-quarantine", "0.1.0");
        var adapter = new S3PrivateDocumentStorageAdapter(
                client,
                properties.getQuarantineBucket(),
                properties.getMaximumUploadBytes(),
                properties.isCreateBucketIfMissing());
        try {
            adapter.initialize();
            return adapter;
        } catch (RuntimeException exception) {
            try {
                adapter.close();
            } catch (RuntimeException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }
}
