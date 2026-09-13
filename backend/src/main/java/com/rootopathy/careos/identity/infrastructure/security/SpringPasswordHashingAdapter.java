package com.rootopathy.careos.identity.infrastructure.security;

import com.rootopathy.careos.identity.application.PasswordHashingPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public final class SpringPasswordHashingAdapter implements PasswordHashingPort {
    private final PasswordEncoder passwordEncoder;

    public SpringPasswordHashingAdapter(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public String hash(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }
}
