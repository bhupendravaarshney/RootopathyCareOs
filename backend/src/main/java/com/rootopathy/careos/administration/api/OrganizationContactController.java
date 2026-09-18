package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.OrganizationContactService;
import com.rootopathy.careos.administration.application.OrganizationContactService.AddressCreateCommand;
import com.rootopathy.careos.administration.application.OrganizationContactService.AddressDraftCommand;
import com.rootopathy.careos.administration.application.OrganizationContactService.AddressEndCommand;
import com.rootopathy.careos.administration.application.OrganizationContactService.AddressSupersedeCommand;
import com.rootopathy.careos.administration.application.OrganizationContactService.ContactCreateCommand;
import com.rootopathy.careos.administration.application.OrganizationContactService.ContactDraftCommand;
import com.rootopathy.careos.administration.application.OrganizationContactService.ContactEndCommand;
import com.rootopathy.careos.administration.application.OrganizationContactService.ContactSupersedeCommand;
import com.rootopathy.careos.administration.application.OrganizationContactService.ContactVerifyCommand;
import com.rootopathy.careos.administration.application.OrganizationContactService.ReadCommand;
import com.rootopathy.careos.administration.domain.OrganizationContactCollection;
import com.rootopathy.careos.shared.api.ApiProblemException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import com.rootopathy.careos.shared.domain.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class OrganizationContactController {
    private final OrganizationContactService contacts;

    public OrganizationContactController(OrganizationContactService contacts) {
        this.contacts = contacts;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/contacts")
    ResponseEntity<OrganizationContactCollection> directory(
            @PathVariable UUID organizationId,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(contacts.directory(new ReadCommand(
                        organizationId, actor.id(), CorrelationIdFilter.from(request))));
    }

    @PostMapping("/api/v1/organizations/{organizationId}/addresses")
    ResponseEntity<String> createAddress(
            @PathVariable UUID organizationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AddressWriteRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var result = contacts.createAddress(new AddressCreateCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                idempotencyKey,
                body.draft(),
                body.reason()));
        return mutationResponse(result)
                .location(addressLocation(organizationId, result.recordId()))
                .body(result.outcome().response().bodyJson());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/addresses/{addressId}/supersessions")
    ResponseEntity<String> supersedeAddress(
            @PathVariable UUID organizationId,
            @PathVariable UUID addressId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AddressWriteRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var result = contacts.supersedeAddress(new AddressSupersedeCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                addressId,
                ifMatch,
                idempotencyKey,
                body.draft(),
                body.reason()));
        return mutationResponse(result)
                .location(addressLocation(organizationId, result.recordId()))
                .body(result.outcome().response().bodyJson());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/addresses/{addressId}/endings")
    ResponseEntity<String> endAddress(
            @PathVariable UUID organizationId,
            @PathVariable UUID addressId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReasonRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var result = contacts.endAddress(new AddressEndCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                addressId,
                ifMatch,
                idempotencyKey,
                body.reason()));
        return mutationResponse(result).body(result.outcome().response().bodyJson());
    }

    @PostMapping("/api/v1/organizations/{organizationId}/contacts")
    ResponseEntity<String> createContact(
            @PathVariable UUID organizationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ContactWriteRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var result = contacts.createContact(new ContactCreateCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                idempotencyKey,
                body.draft(),
                body.reason()));
        return mutationResponse(result)
                .location(contactLocation(organizationId, result.recordId()))
                .body(result.outcome().response().bodyJson());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/contacts/{contactId}/verifications")
    ResponseEntity<String> verifyContact(
            @PathVariable UUID organizationId,
            @PathVariable UUID contactId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReasonRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var result = contacts.verifyContact(new ContactVerifyCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                contactId,
                ifMatch,
                idempotencyKey,
                body.reason()));
        return mutationResponse(result).body(result.outcome().response().bodyJson());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/contacts/{contactId}/supersessions")
    ResponseEntity<String> supersedeContact(
            @PathVariable UUID organizationId,
            @PathVariable UUID contactId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ContactWriteRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var result = contacts.supersedeContact(new ContactSupersedeCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                contactId,
                ifMatch,
                idempotencyKey,
                body.draft(),
                body.reason()));
        return mutationResponse(result)
                .location(contactLocation(organizationId, result.recordId()))
                .body(result.outcome().response().bodyJson());
    }

    @PostMapping(
            "/api/v1/organizations/{organizationId}/contacts/{contactId}/endings")
    ResponseEntity<String> endContact(
            @PathVariable UUID organizationId,
            @PathVariable UUID contactId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReasonRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var actor = requireActor(authentication);
        var result = contacts.endContact(new ContactEndCommand(
                organizationId,
                actor.id(),
                CorrelationIdFilter.from(request),
                contactId,
                ifMatch,
                idempotencyKey,
                body.reason()));
        return mutationResponse(result).body(result.outcome().response().bodyJson());
    }

    private static ResponseEntity.BodyBuilder mutationResponse(
            OrganizationContactService.MutationOutcome result) {
        return ResponseEntity.status(result.outcome().response().statusCode())
                .cacheControl(CacheControl.noStore())
                .eTag(result.entityTag())
                .contentType(MediaType.parseMediaType(result.outcome().response().mediaType()));
    }

    private static URI addressLocation(UUID organizationId, UUID addressId) {
        return URI.create(
                "/api/v1/organizations/" + organizationId + "/addresses/" + addressId);
    }

    private static URI contactLocation(UUID organizationId, UUID contactId) {
        return URI.create(
                "/api/v1/organizations/" + organizationId + "/contacts/" + contactId);
    }

    private static AuthenticatedActor requireActor(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedActor actor)) {
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "authentication-required",
                    "Authentication required",
                    "A valid authenticated session is required.");
        }
        return actor;
    }

    public record AddressWriteRequest(
            String addressType,
            List<String> addressLines,
            String locality,
            String region,
            String postcode,
            String countryCode,
            String validationStatus,
            String validationSource,
            Boolean isPrimary,
            String effectiveFrom,
            String effectiveTo,
            String reason) {
        AddressDraftCommand draft() {
            return new AddressDraftCommand(
                    addressType,
                    addressLines,
                    locality,
                    region,
                    postcode,
                    countryCode,
                    validationStatus,
                    validationSource,
                    isPrimary,
                    effectiveFrom,
                    effectiveTo);
        }
    }

    public record ContactWriteRequest(
            String channel,
            String purpose,
            String value,
            Boolean isPrimary,
            Boolean isPreferred,
            String effectiveFrom,
            String effectiveTo,
            String reason) {
        ContactDraftCommand draft() {
            return new ContactDraftCommand(
                    channel,
                    purpose,
                    value,
                    isPrimary,
                    isPreferred,
                    effectiveFrom,
                    effectiveTo);
        }
    }

    public record ReasonRequest(String reason) {}
}
