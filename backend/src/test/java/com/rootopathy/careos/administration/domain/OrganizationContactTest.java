package com.rootopathy.careos.administration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationContactTest {
    private static final UUID ADDRESS_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000901");
    private static final UUID CONTACT_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000902");
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final Instant EFFECTIVE_FROM = Instant.parse("2026-09-18T08:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-09-18T07:00:00Z");

    @Test
    void acceptsExactAddressAndMaskedContactProjectionsWithDefensiveCopies() {
        var lines = new ArrayList<>(List.of("42 Care Street", "Clinical District"));
        var addressActions = new ArrayList<>(List.of("supersede", "end"));
        var contactActions = new ArrayList<>(List.of("verify", "supersede", "end"));

        var address = address(
                lines, "validated", "National postal registry", "active", addressActions, null);
        var contact = contact(false, "unverified", "active", contactActions, null);
        lines.clear();
        addressActions.clear();
        contactActions.clear();

        assertThat(address.addressLines()).containsExactly("42 Care Street", "Clinical District");
        assertThat(address.availableActions()).containsExactly("supersede", "end");
        assertThat(contact.maskedValue()).isEqualTo("c***@***.example");
        assertThat(contact.availableActions()).containsExactly("verify", "supersede", "end");
    }

    @Test
    void rejectsInvalidValidationPreferenceRangesAndLifecycleActions() {
        assertThatThrownBy(() -> address(
                        List.of("42 Care Street"),
                        "validated",
                        null,
                        "active",
                        List.of("end"),
                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("validationSource");

        assertThatThrownBy(() -> contact(
                        true, "unverified", "active", List.of("verify"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preferred contact must be primary");

        assertThatThrownBy(() -> address(
                        List.of("42 Care Street"),
                        "unvalidated",
                        null,
                        "ended",
                        List.of("end"),
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lifecycle status");

        assertThatThrownBy(() -> contact(
                        false,
                        "unverified",
                        "active",
                        List.of(),
                        EFFECTIVE_FROM.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveTo");
    }

    @Test
    void rejectsDuplicateDirectoryIdsAndUnregisteredContactPurposes() {
        var purpose = new OrganizationContactPurpose("operational", "Operational contact", false);
        var address = address(
                List.of("42 Care Street"),
                "unvalidated",
                null,
                "active",
                List.of("supersede", "end"),
                null);
        var contact = contact(false, "verified", "active", List.of("supersede", "end"), null);

        assertThatThrownBy(() -> new OrganizationContactCollection(
                        ORGANIZATION_ID,
                        true,
                        List.of("registered", "postal", "service", "billing"),
                        List.of(purpose),
                        List.of(address, address),
                        List.of(contact)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("address IDs");

        var unregistered = new OrganizationContact(
                contact.contactId(),
                contact.channel(),
                "billing",
                "Billing contact",
                contact.maskedValue(),
                contact.verificationStatus(),
                contact.isPrimary(),
                contact.isPreferred(),
                contact.effectiveFrom(),
                contact.effectiveTo(),
                contact.supersedesId(),
                contact.status(),
                contact.availableActions(),
                contact.lockVersion(),
                contact.createdAt(),
                contact.updatedAt());
        assertThatThrownBy(() -> new OrganizationContactCollection(
                        ORGANIZATION_ID,
                        true,
                        List.of("registered", "postal", "service", "billing"),
                        List.of(purpose),
                        List.of(address),
                        List.of(unregistered)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active projected purpose");
    }

    private static OrganizationAddress address(
            List<String> lines,
            String validationStatus,
            String validationSource,
            String status,
            List<String> actions,
            Instant effectiveTo) {
        return new OrganizationAddress(
                ADDRESS_ID,
                "registered",
                lines,
                "Pune",
                "Maharashtra",
                "411001",
                "IN",
                validationStatus,
                validationSource,
                true,
                EFFECTIVE_FROM,
                effectiveTo,
                null,
                status,
                actions,
                0,
                CREATED_AT,
                CREATED_AT);
    }

    private static OrganizationContact contact(
            boolean preferred,
            String verificationStatus,
            String status,
            List<String> actions,
            Instant effectiveTo) {
        return new OrganizationContact(
                CONTACT_ID,
                "email",
                "operational",
                "Operational contact",
                "c***@***.example",
                verificationStatus,
                false,
                preferred,
                EFFECTIVE_FROM,
                effectiveTo,
                null,
                status,
                actions,
                0,
                CREATED_AT,
                CREATED_AT);
    }
}
