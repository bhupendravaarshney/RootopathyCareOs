package com.rootopathy.careos.governance.infrastructure;

import com.rootopathy.careos.governance.application.ConsumerInboxOperations;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class PostgresConsumerInboxConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    ConsumerInboxOperations postgresConsumerInboxOperations(JdbcTemplate jdbcTemplate) {
        var adapter = new PostgresConsumerInboxOperations(jdbcTemplate);
        adapter.initialize();
        return adapter;
    }
}
