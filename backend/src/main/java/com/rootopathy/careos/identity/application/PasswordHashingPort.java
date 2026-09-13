package com.rootopathy.careos.identity.application;

public interface PasswordHashingPort {
    String hash(String rawPassword);

    boolean matches(String rawPassword, String encodedPassword);
}
