package com.rootopathy.careos.shared.api;

import java.util.Objects;
import org.springframework.http.HttpStatus;

public final class ApiProblemException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String title;

    public ApiProblemException(HttpStatus status, String code, String title, String safeDetail) {
        super(Objects.requireNonNull(safeDetail, "safeDetail"));
        this.status = Objects.requireNonNull(status, "status");
        this.code = requireText(code, "code");
        this.title = requireText(title, "title");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
