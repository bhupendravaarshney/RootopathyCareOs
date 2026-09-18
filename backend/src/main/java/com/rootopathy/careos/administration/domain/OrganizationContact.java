package com.rootopathy.careos.administration.domain;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record OrganizationContact(
        UUID contactId,
        String channel,
        String purpose,
        String purposeDisplayName,
        String maskedValue,
        String verificationStatus,
        boolean isPrimary,
        boolean isPreferred,
        Instant effectiveFrom,
        Instant effectiveTo,
        UUID supersedesId,
        String status,
        List<String> availableActions,
        long lockVersion,
        Instant createdAt,
        Instant updatedAt) {
    private static final Set<String> CHANNELS = Set.of("email", "phone", "web");
    private static final Set<String> VERIFICATION_STATUSES = Set.of("unverified", "verified");
    private static final Set<String> STATUSES =
            Set.of("scheduled", "active", "ended", "superseded");
    private static final Set<String> ACTIONS = Set.of("verify", "supersede", "end");
    private static final Pattern REGISTRY_KEY =
            Pattern.compile("[a-z][a-z0-9]*([._:-][a-z0-9]+)*");

    public OrganizationContact {
        Objects.requireNonNull(contactId, "contactId");
        channel = requireMember(channel, CHANNELS, "channel");
        purpose = requireText(purpose, "purpose", 1, 80);
        if (!REGISTRY_KEY.matcher(purpose).matches()) {
            throw new IllegalArgumentException("purpose must be a registry key");
        }
        purposeDisplayName = requireText(purposeDisplayName, "purposeDisplayName", 2, 120);
        maskedValue = requireText(maskedValue, "maskedValue", 1, 2048);
        verificationStatus = requireMember(
                verificationStatus, VERIFICATION_STATUSES, "verificationStatus");
        if (isPreferred && !isPrimary) {
            throw new IllegalArgumentException("a preferred contact must be primary");
        }
        status = requireMember(status, STATUSES, "status");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        }
        if (contactId.equals(supersedesId)) {
            throw new IllegalArgumentException("supersedesId must identify an earlier contact");
        }
        if (lockVersion < 0) {
            throw new IllegalArgumentException("lockVersion must not be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        availableActions = List.copyOf(Objects.requireNonNull(availableActions, "availableActions"));
        if (availableActions.size() != Set.copyOf(availableActions).size()
                || !ACTIONS.containsAll(availableActions)) {
            throw new IllegalArgumentException("availableActions contains an invalid action");
        }
        if ((availableActions.contains("verify")
                        && (!"unverified".equals(verificationStatus)
                                || !Set.of("scheduled", "active").contains(status)))
                || (availableActions.contains("end") && !"active".equals(status))
                || (availableActions.contains("supersede")
                        && !Set.of("scheduled", "active").contains(status))) {
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
