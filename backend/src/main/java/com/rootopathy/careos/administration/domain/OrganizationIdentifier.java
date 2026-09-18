package com.rootopathy.careos.administration.domain;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record OrganizationIdentifier(
        UUID identifierId,
        String identifierType,
        String typeDisplayName,
        String assigningAuthority,
        String value,
        String jurisdictionCountryCode,
        String verificationStatus,
        String evidenceReference,
        boolean isPrimary,
        LocalDate issueDate,
        LocalDate expiryDate,
        Instant effectiveFrom,
        Instant effectiveTo,
        UUID supersedesId,
        String status,
        List<String> availableActions,
        long lockVersion,
        Instant createdAt,
        Instant updatedAt) {
    private static final Set<String> STATUSES =
            Set.of("draft", "verified", "active", "expired", "revoked", "superseded");
    private static final Set<String> VERIFICATION_STATUSES = Set.of("unverified", "verified");
    private static final Set<String> ACTIONS = Set.of("edit", "verify", "revoke", "supersede");
    private static final Pattern REGISTRY_KEY =
            Pattern.compile("[a-z][a-z0-9]*([._:-][a-z0-9]+)*");

    public OrganizationIdentifier {
        Objects.requireNonNull(identifierId, "identifierId");
        identifierType = requireText(identifierType, "identifierType", 1, 80);
        if (!REGISTRY_KEY.matcher(identifierType).matches()) {
            throw new IllegalArgumentException("identifierType must be a registry key");
        }
        typeDisplayName = requireText(typeDisplayName, "typeDisplayName", 2, 120);
        assigningAuthority = requireText(assigningAuthority, "assigningAuthority", 2, 160);
        value = requireText(value, "value", 1, 128);
        if (jurisdictionCountryCode != null
                && !jurisdictionCountryCode.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("jurisdictionCountryCode must be an ISO country code");
        }
        verificationStatus = requireMember(
                verificationStatus, VERIFICATION_STATUSES, "verificationStatus");
        if (evidenceReference != null) {
            evidenceReference = requireText(evidenceReference, "evidenceReference", 1, 160);
        }
        status = requireMember(status, STATUSES, "status");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        }
        if (identifierId.equals(supersedesId)) {
            throw new IllegalArgumentException("supersedesId must identify an earlier record");
        }
        if (expiryDate != null && issueDate != null && expiryDate.isBefore(issueDate)) {
            throw new IllegalArgumentException("expiryDate must not precede issueDate");
        }
        if (lockVersion < 0) {
            throw new IllegalArgumentException("lockVersion must not be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        if (("draft".equals(status)) != "unverified".equals(verificationStatus)) {
            throw new IllegalArgumentException("status and verificationStatus are inconsistent");
        }
        if ("draft".equals(status) && supersedesId != null) {
            throw new IllegalArgumentException("a draft cannot supersede another identifier");
        }
        availableActions = List.copyOf(Objects.requireNonNull(availableActions, "availableActions"));
        if (availableActions.size() != Set.copyOf(availableActions).size()
                || !ACTIONS.containsAll(availableActions)) {
            throw new IllegalArgumentException("availableActions contains an invalid action");
        }
        if ((!"draft".equals(status)
                        && (availableActions.contains("edit") || availableActions.contains("verify")))
                || (!Set.of("verified", "active").contains(status)
                        && (availableActions.contains("revoke")
                                || availableActions.contains("supersede")))) {
            throw new IllegalArgumentException("availableActions conflicts with lifecycle status");
        }
    }

    private static String requireText(String value, String name, int minimum, int maximum) {
        var checked = Objects.requireNonNull(value, name);
        var length = checked.codePointCount(0, checked.length());
        if (checked.isBlank()
                || !checked.equals(checked.strip())
                || !Normalizer.isNormalized(checked, Normalizer.Form.NFC)
                || checked.codePoints().anyMatch(Character::isISOControl)
                || length < minimum
                || length > maximum) {
            throw new IllegalArgumentException(name + " has an invalid format or length");
        }
        return checked;
    }

    private static String requireMember(String value, Set<String> allowed, String name) {
        var checked = requireText(value, name, 1, 24);
        if (!allowed.contains(checked)) {
            throw new IllegalArgumentException(name + " is not approved");
        }
        return checked;
    }
}
