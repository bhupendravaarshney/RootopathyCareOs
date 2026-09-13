package com.rootopathy.careos.platform.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisJobQueueProperties.class)
public class RedisDurableJobQueueConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "careos.jobs.redis", name = "enabled", havingValue = "true")
    RedisDurableJobQueueAdapter redisDurableJobQueueAdapter(
            StringRedisTemplate redis,
            RedisJobQueueProperties properties,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        var adapter = new RedisDurableJobQueueAdapter(
                redis, properties, meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new));
        adapter.initialize();
        return adapter;
    }
}
