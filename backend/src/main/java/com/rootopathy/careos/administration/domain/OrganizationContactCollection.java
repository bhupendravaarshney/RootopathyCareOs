package com.rootopathy.careos.administration.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record OrganizationContactCollection(
        UUID organizationId,
        boolean canCreate,
        List<String> addressTypes,
        List<OrganizationContactPurpose> purposes,
        List<OrganizationAddress> addresses,
        List<OrganizationContact> contacts) {
    private static final List<String> APPROVED_ADDRESS_TYPES =
            List.of("registered", "postal", "service", "billing");

    public OrganizationContactCollection {
        Objects.requireNonNull(organizationId, "organizationId");
        addressTypes = List.copyOf(Objects.requireNonNull(addressTypes, "addressTypes"));
        purposes = List.copyOf(Objects.requireNonNull(purposes, "purposes"));
        addresses = List.copyOf(Objects.requireNonNull(addresses, "addresses"));
        contacts = List.copyOf(Objects.requireNonNull(contacts, "contacts"));
        if (!addressTypes.equals(APPROVED_ADDRESS_TYPES)) {
            throw new IllegalArgumentException("addressTypes must use the approved ordering");
        }
        if (purposes.size()
                != Set.copyOf(purposes.stream().map(OrganizationContactPurpose::key).toList())
                        .size()) {
            throw new IllegalArgumentException("contact purpose keys must be unique");
        }
        if (canCreate && purposes.isEmpty()) {
            throw new IllegalArgumentException("creation requires an active contact purpose");
        }
        if (addresses.size()
                != Set.copyOf(addresses.stream().map(OrganizationAddress::addressId).toList())
                        .size()) {
            throw new IllegalArgumentException("address IDs must be unique");
        }
        if (contacts.size()
                != Set.copyOf(contacts.stream().map(OrganizationContact::contactId).toList())
                        .size()) {
            throw new IllegalArgumentException("contact IDs must be unique");
        }
        var purposeKeys = Set.copyOf(
                purposes.stream().map(OrganizationContactPurpose::key).toList());
        if (contacts.stream().anyMatch(contact -> !purposeKeys.contains(contact.purpose()))) {
            throw new IllegalArgumentException("contacts must use an active projected purpose");
        }
    }
}
