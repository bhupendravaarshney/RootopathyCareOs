package com.rootopathy.careos.administration.domain;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record OrganizationAddress(
        UUID addressId,
        String addressType,
        List<String> addressLines,
        String locality,
        String region,
        String postcode,
        String countryCode,
        String validationStatus,
        String validationSource,
        boolean isPrimary,
        Instant effectiveFrom,
        Instant effectiveTo,
        UUID supersedesId,
        String status,
        List<String> availableActions,
        long lockVersion,
        Instant createdAt,
        Instant updatedAt) {
    private static final Set<String> TYPES =
            Set.of("registered", "postal", "service", "billing");
    private static final Set<String> VALIDATION_STATUSES =
            Set.of("unvalidated", "validated");
    private static final Set<String> STATUSES =
            Set.of("scheduled", "active", "ended", "superseded");
    private static final Set<String> ACTIONS = Set.of("supersede", "end");

    public OrganizationAddress {
        Objects.requireNonNull(addressId, "addressId");
        addressType = requireMember(addressType, TYPES, "addressType");
        addressLines = List.copyOf(Objects.requireNonNull(addressLines, "addressLines"));
        if (addressLines.isEmpty() || addressLines.size() > 4) {
            throw new IllegalArgumentException("addressLines must contain between one and four lines");
        }
        addressLines = addressLines.stream()
                .map(line -> requireText(line, "addressLines", 1, 120))
                .toList();
        locality = requireText(locality, "locality", 1, 100);
        region = requireText(region, "region", 1, 100);
        postcode = requireText(postcode, "postcode", 1, 24);
        countryCode = requireText(countryCode, "countryCode", 2, 2);
        if (!countryCode.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("countryCode must be an ISO country code");
        }
        validationStatus = requireMember(
                validationStatus, VALIDATION_STATUSES, "validationStatus");
        if ("validated".equals(validationStatus)) {
            validationSource = requireText(validationSource, "validationSource", 2, 160);
        } else if (validationSource != null) {
            throw new IllegalArgumentException(
                    "validationSource is only available for a validated address");
        }
        status = requireMember(status, STATUSES, "status");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        }
        if (addressId.equals(supersedesId)) {
            throw new IllegalArgumentException("supersedesId must identify an earlier address");
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
        if ((availableActions.contains("end") && !"active".equals(status))
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
