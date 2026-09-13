package com.rootopathy.careos.platform.infrastructure;

import com.rootopathy.careos.platform.application.CapabilityProbe;
import com.rootopathy.careos.platform.application.DocumentPromotionPort;
import com.rootopathy.careos.platform.application.DocumentRetentionPort;
import com.rootopathy.careos.platform.application.DurableNotificationPort;
import com.rootopathy.careos.platform.application.JobQueuePort;
import com.rootopathy.careos.platform.application.MalwareScannerPort;
import com.rootopathy.careos.platform.application.PlatformCapabilityRegistry;
import com.rootopathy.careos.platform.application.PrivateDocumentStoragePort;
import com.rootopathy.careos.platform.application.SchedulerExecutionPort;
import com.rootopathy.careos.platform.application.SignedDocumentAccessPort;
import com.rootopathy.careos.platform.application.WorkerExecutionPort;
import com.rootopathy.careos.platform.infrastructure.UnavailablePlatformAdapters.DocumentPromotion;
import com.rootopathy.careos.platform.infrastructure.UnavailablePlatformAdapters.DocumentRetention;
import com.rootopathy.careos.platform.infrastructure.UnavailablePlatformAdapters.DurableNotificationDelivery;
import com.rootopathy.careos.platform.infrastructure.UnavailablePlatformAdapters.MalwareScanner;
import com.rootopathy.careos.platform.infrastructure.UnavailablePlatformAdapters.PrivateDocumentStorage;
import com.rootopathy.careos.platform.infrastructure.UnavailablePlatformAdapters.RedisJobQueue;
import com.rootopathy.careos.platform.infrastructure.UnavailablePlatformAdapters.SchedulerExecution;
import com.rootopathy.careos.platform.infrastructure.UnavailablePlatformAdapters.SignedDocumentAccess;
import com.rootopathy.careos.platform.infrastructure.UnavailablePlatformAdapters.WorkerExecution;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PlatformCapabilityConfiguration {
    @Bean
    @ConditionalOnMissingBean(PrivateDocumentStoragePort.class)
    @ConditionalOnProperty(
            prefix = "careos.storage.s3",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    PrivateDocumentStorage unavailablePrivateDocumentStorage() {
        return new PrivateDocumentStorage();
    }

    @Bean
    @ConditionalOnMissingBean(MalwareScannerPort.class)
    @ConditionalOnProperty(
            prefix = "careos.scanner.clamav",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    MalwareScanner unavailableMalwareScanner() {
        return new MalwareScanner();
    }

    @Bean
    @ConditionalOnMissingBean(DocumentPromotionPort.class)
    DocumentPromotion unavailableDocumentPromotion() {
        return new DocumentPromotion();
    }

    @Bean
    @ConditionalOnMissingBean(SignedDocumentAccessPort.class)
    SignedDocumentAccess unavailableSignedDocumentAccess() {
        return new SignedDocumentAccess();
    }

    @Bean
    @ConditionalOnMissingBean(DocumentRetentionPort.class)
    DocumentRetention unavailableDocumentRetention() {
        return new DocumentRetention();
    }

    @Bean
    @ConditionalOnMissingBean(DurableNotificationPort.class)
    @ConditionalOnProperty(
            prefix = "careos.notifications.postgres",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    DurableNotificationDelivery unavailableDurableNotificationDelivery() {
        return new DurableNotificationDelivery();
    }

    @Bean
    @ConditionalOnMissingBean(JobQueuePort.class)
    @ConditionalOnProperty(
            prefix = "careos.jobs.redis",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    RedisJobQueue unavailableRedisJobQueue() {
        return new RedisJobQueue();
    }

    @Bean
    @ConditionalOnMissingBean(WorkerExecutionPort.class)
    WorkerExecution unavailableWorkerExecution() {
        return new WorkerExecution();
    }

    @Bean
    @ConditionalOnMissingBean(SchedulerExecutionPort.class)
    SchedulerExecution unavailableSchedulerExecution() {
        return new SchedulerExecution();
    }

    @Bean
    PlatformCapabilityRegistry platformCapabilityRegistry(List<CapabilityProbe> probes) {
        return new PlatformCapabilityRegistry(probes);
    }

    @Bean
    PlatformCapabilitiesInfoContributor platformCapabilitiesInfoContributor(
            PlatformCapabilityRegistry registry) {
        return new PlatformCapabilitiesInfoContributor(registry);
    }
}
