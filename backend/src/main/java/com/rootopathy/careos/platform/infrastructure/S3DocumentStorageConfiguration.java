package com.rootopathy.careos.platform.infrastructure;

import com.rootopathy.careos.platform.application.DefaultDocumentAccessOperations;
import com.rootopathy.careos.platform.application.DefaultDocumentPromotionOperations;
import com.rootopathy.careos.platform.application.DefaultDocumentRetentionOperations;
import com.rootopathy.careos.platform.application.DocumentAccessOperations;
import com.rootopathy.careos.platform.application.DocumentEvidenceOperations;
import com.rootopathy.careos.platform.application.DocumentPromotionOperations;
import com.rootopathy.careos.platform.application.DocumentRetentionOperations;
import io.minio.MinioClient;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    S3DocumentStorageProperties.class,
    DocumentPromotionProperties.class,
    SignedDocumentAccessProperties.class,
    DocumentRetentionProperties.class
})
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

    @Bean
    @ConditionalOnProperty(
            prefix = "careos.documents.promotion",
            name = "enabled",
            havingValue = "true")
    S3DocumentPromotionAdapter s3DocumentPromotionAdapter(
            S3DocumentStorageProperties storageProperties,
            DocumentPromotionProperties promotionProperties,
            ObjectProvider<Clock> clockProvider) {
        var policy = promotionProperties.policy();
        storageProperties.validateForPromotionActivation();
        var client = MinioClient.builder()
                .endpoint(storageProperties.getEndpoint().toString())
                .credentials(storageProperties.getAccessKey(), storageProperties.getSecretKey())
                .build();
        client.setAppInfo("careos-clean-promotion", "0.1.0");
        var adapter = new S3DocumentPromotionAdapter(
                client,
                storageProperties.getQuarantineBucket(),
                storageProperties.getCleanBucket(),
                storageProperties.getMaximumUploadBytes(),
                storageProperties.isCreateBucketIfMissing(),
                policy,
                clockProvider.getIfAvailable(Clock::systemUTC));
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

    @Bean
    @ConditionalOnProperty(
            prefix = "careos.documents.promotion",
            name = "enabled",
            havingValue = "true")
    DocumentPromotionOperations documentPromotionOperations(
            S3DocumentPromotionAdapter promotion,
            DocumentEvidenceOperations evidence,
            DocumentPromotionProperties properties,
            ObjectProvider<Clock> clockProvider) {
        return new DefaultDocumentPromotionOperations(
                promotion,
                evidence,
                properties.policy(),
                clockProvider.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "careos.documents.signed-access",
            name = "enabled",
            havingValue = "true")
    S3SignedDocumentAccessAdapter s3SignedDocumentAccessAdapter(
            S3DocumentStorageProperties storageProperties,
            SignedDocumentAccessProperties accessProperties,
            ObjectProvider<Clock> clockProvider) {
        var policy = accessProperties.policy();
        storageProperties.validateForPromotionActivation();
        var client = MinioClient.builder()
                .endpoint(storageProperties.getEndpoint().toString())
                .credentials(storageProperties.getAccessKey(), storageProperties.getSecretKey())
                .build();
        client.setAppInfo("careos-signed-document-access", "0.1.0");
        var adapter = new S3SignedDocumentAccessAdapter(
                client,
                storageProperties.getEndpoint(),
                storageProperties.getCleanBucket(),
                storageProperties.getMaximumUploadBytes(),
                storageProperties.isCreateBucketIfMissing(),
                policy,
                clockProvider.getIfAvailable(Clock::systemUTC));
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

    @Bean
    @ConditionalOnProperty(
            prefix = "careos.documents.signed-access",
            name = "enabled",
            havingValue = "true")
    DocumentAccessOperations documentAccessOperations(
            S3SignedDocumentAccessAdapter access,
            DocumentEvidenceOperations evidence,
            SignedDocumentAccessProperties properties,
            ObjectProvider<Clock> clockProvider) {
        return new DefaultDocumentAccessOperations(
                access,
                evidence,
                properties.policy(),
                clockProvider.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "careos.documents.retention",
            name = "enabled",
            havingValue = "true")
    S3DocumentRetentionAdapter s3DocumentRetentionAdapter(
            S3DocumentStorageProperties storageProperties,
            DocumentRetentionProperties retentionProperties,
            ObjectProvider<Clock> clockProvider) {
        var policy = retentionProperties.policy();
        storageProperties.validateForRetentionActivation();
        var client = MinioClient.builder()
                .endpoint(storageProperties.getEndpoint().toString())
                .credentials(storageProperties.getAccessKey(), storageProperties.getSecretKey())
                .build();
        client.setAppInfo("careos-document-retention", "0.1.0");
        var adapter = new S3DocumentRetentionAdapter(
                client,
                storageProperties.getCleanBucket(),
                storageProperties.getMaximumUploadBytes(),
                policy,
                clockProvider.getIfAvailable(Clock::systemUTC));
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

    @Bean
    @ConditionalOnProperty(
            prefix = "careos.documents.retention",
            name = "enabled",
            havingValue = "true")
    DocumentRetentionOperations documentRetentionOperations(
            S3DocumentRetentionAdapter retention,
            DocumentEvidenceOperations evidence,
            DocumentRetentionProperties properties,
            ObjectProvider<Clock> clockProvider) {
        return new DefaultDocumentRetentionOperations(
                retention,
                evidence,
                properties.policy(),
                clockProvider.getIfAvailable(Clock::systemUTC));
    }
}
