package com.rootopathy.careos.config;

import com.rootopathy.careos.prototype.application.PrototypeRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PrototypeRegistry prototypeRegistry() {
        return new PrototypeRegistry();
    }
}
