package com.rootopathy.careos.tenancy.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Exact maker-checker evidence bound to one subject and one idempotent execution. */
public record IndependentApproval(
        UUID approvalId, String subjectType, UUID subjectId, String idempotencyKey) {
    private static final Pattern SAFE_SUBJECT_TYPE =
            Pattern.compile("[a-z][a-z0-9]*(?:[._:-][a-z0-9]+)*");
    private static final Pattern SAFE_IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9._:-]{16,128}");

    public IndependentApproval {
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(subjectId, "subjectId");
        if (subjectType == null
                || subjectType.length() > 120
                || !SAFE_SUBJECT_TYPE.matcher(subjectType).matches()) {
            throw new IllegalArgumentException("subjectType has an invalid format");
        }
        if (idempotencyKey == null
                || !SAFE_IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("idempotencyKey has an invalid format");
        }
    }
}
