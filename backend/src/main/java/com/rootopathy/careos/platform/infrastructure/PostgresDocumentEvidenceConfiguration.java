package com.rootopathy.careos.platform.infrastructure;

import com.rootopathy.careos.platform.application.DefaultDocumentSecurityOperations;
import com.rootopathy.careos.platform.application.DocumentEvidenceOperations;
import com.rootopathy.careos.platform.application.DocumentSecurityOperations;
import com.rootopathy.careos.platform.application.MalwareScannerPort;
import com.rootopathy.careos.platform.application.PrivateDocumentStoragePort;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class PostgresDocumentEvidenceConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    DocumentEvidenceOperations postgresDocumentEvidenceOperations(JdbcTemplate jdbcTemplate) {
        var adapter = new PostgresDocumentEvidenceAdapter(jdbcTemplate);
        adapter.initialize();
        return adapter;
    }

    @Bean
    DocumentSecurityOperations documentSecurityOperations(
            PrivateDocumentStoragePort storage,
            MalwareScannerPort scanner,
            DocumentEvidenceOperations evidence) {
        return new DefaultDocumentSecurityOperations(storage, scanner, evidence);
    }
}
