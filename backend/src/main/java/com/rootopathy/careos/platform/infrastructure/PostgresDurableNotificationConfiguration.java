package com.rootopathy.careos.platform.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PostgresNotificationProperties.class)
public class PostgresDurableNotificationConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    @ConditionalOnProperty(
            prefix = "careos.notifications.postgres",
            name = "enabled",
            havingValue = "true")
    PostgresDurableNotificationAdapter postgresDurableNotificationAdapter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PostgresNotificationProperties properties,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        var adapter = new PostgresDurableNotificationAdapter(
                jdbcTemplate,
                objectMapper,
                properties,
                meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new));
        adapter.initialize();
        return adapter;
    }
}
