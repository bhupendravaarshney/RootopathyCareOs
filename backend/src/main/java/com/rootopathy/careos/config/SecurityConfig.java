package com.rootopathy.careos.config;

import static com.rootopathy.careos.identity.infrastructure.security.CareOsAuthorities.AUTHENTICATED;
import static com.rootopathy.careos.identity.infrastructure.security.CareOsAuthorities.MFA_PENDING;

import com.rootopathy.careos.identity.api.BrowserOriginFilter;
import com.rootopathy.careos.identity.api.CareOsSpaCsrfTokenRequestHandler;
import com.rootopathy.careos.identity.api.SecurityAccessFailureHandler;
import com.rootopathy.careos.identity.api.SessionValidityFilter;
import com.rootopathy.careos.identity.infrastructure.config.IdentitySecurityProperties;
import com.rootopathy.careos.identity.infrastructure.security.PersistentUserDetailsService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties(IdentitySecurityProperties.class)
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            BrowserOriginFilter browserOriginFilter,
            SessionValidityFilter sessionValidityFilter,
            SecurityAccessFailureHandler accessFailureHandler,
            CareOsSpaCsrfTokenRequestHandler csrfTokenRequestHandler,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository)
            throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfTokenRequestHandler))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(accessFailureHandler)
                        .accessDeniedHandler(accessFailureHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/public/**", "/actuator/health", "/actuator/info")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/auth/session")
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/login",
                                "/api/v1/auth/password-reset-requests",
                                "/api/v1/auth/password-resets")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout")
                        .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/mfa/challenges")
                        .hasAuthority(MFA_PENDING)
                        .requestMatchers("/api/v1/**")
                        .hasAuthority(AUTHENTICATED)
                        .anyRequest()
                        .hasAuthority(AUTHENTICATED))
                .addFilterBefore(browserOriginFilter, CsrfFilter.class)
                .addFilterAfter(sessionValidityFilter, SecurityContextHolderFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(
            PersistentUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository(
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Strict").secure(secure));
        return repository;
    }

    @Bean
    DefaultCookieSerializerCustomizer careOsSessionCookie(
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        return serializer -> {
            serializer.setCookieName("CAREOS_SESSION");
            serializer.setUseHttpOnlyCookie(true);
            serializer.setUseSecureCookie(secure);
            serializer.setSameSite("Strict");
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(IdentitySecurityProperties properties) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Content-Type",
                "X-Correlation-Id",
                "X-CSRF-TOKEN",
                "X-XSRF-TOKEN",
                "X-Organization-Id",
                "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
