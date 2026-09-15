package com.rootopathy.careos.tenancy.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceIdentityProperties.class)
class ServiceIdentityConfiguration {}
