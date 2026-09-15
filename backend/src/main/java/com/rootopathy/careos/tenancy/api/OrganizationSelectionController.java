package com.rootopathy.careos.tenancy.api;

import com.rootopathy.careos.shared.api.ApiProblemException;
import com.rootopathy.careos.shared.api.CorrelationIdFilter;
import com.rootopathy.careos.shared.domain.AuthenticatedActor;
import com.rootopathy.careos.tenancy.application.OrganizationSelectionService;
import com.rootopathy.careos.tenancy.domain.OrganizationAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public final class OrganizationSelectionController {
    public static final String SELECTED_ORGANIZATION_ID = "careos.selectedOrganizationId";

    private final OrganizationSelectionService organizationSelection;

    public OrganizationSelectionController(OrganizationSelectionService organizationSelection) {
        this.organizationSelection = organizationSelection;
    }

    @GetMapping("/organizations")
    ResponseEntity<List<OrganizationAccessResponse>> listOrganizations(
            Authentication authentication, HttpSession session, HttpServletRequest request) {
        var principal = requirePrincipal(authentication);
        var organizations = organizationSelection.listSelectableOrganizations(
                principal.id(), CorrelationIdFilter.from(request));
        var selectedId = selectedOrganizationId(session);
        var storedSelection = selectedId;
        if (storedSelection != null
                && organizations.stream().noneMatch(access -> access.organizationId().equals(storedSelection))) {
            session.removeAttribute(SELECTED_ORGANIZATION_ID);
            selectedId = null;
        }
        var currentSelection = selectedId;
        var response = organizations.stream()
                .map(access -> OrganizationAccessResponse.from(
                        access, access.organizationId().equals(currentSelection)))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @PostMapping("/auth/organization-selections")
    ResponseEntity<OrganizationAccessResponse> selectOrganization(
            @Valid @RequestBody OrganizationSelectionRequest body,
            Authentication authentication,
            HttpSession session,
            HttpServletRequest request) {
        var principal = requirePrincipal(authentication);
        var selected = organizationSelection
                .findSelectableOrganization(
                        principal.id(), body.organizationId(), CorrelationIdFilter.from(request))
                .orElseThrow(OrganizationSelectionController::organizationNotFound);
        session.setAttribute(SELECTED_ORGANIZATION_ID, selected.organizationId().toString());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(OrganizationAccessResponse.from(selected, true));
    }

    private static AuthenticatedActor requirePrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedActor principal)) {
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "authentication-required",
                    "Authentication required",
                    "A valid authenticated session is required.");
        }
        return principal;
    }

    private static UUID selectedOrganizationId(HttpSession session) {
        var value = session.getAttribute(SELECTED_ORGANIZATION_ID);
        if (!(value instanceof String selected)) {
            return null;
        }
        try {
            return UUID.fromString(selected);
        } catch (IllegalArgumentException exception) {
            session.removeAttribute(SELECTED_ORGANIZATION_ID);
            return null;
        }
    }

    private static ApiProblemException organizationNotFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "organization-not-found",
                "Organization not found",
                "The organization is unavailable or is not assigned to this account.");
    }

    public record OrganizationSelectionRequest(@NotNull UUID organizationId) {}

    public record OrganizationAccessResponse(
            UUID id, String displayName, String status, List<String> roleKeys, boolean selected) {
        private static OrganizationAccessResponse from(OrganizationAccess access, boolean selected) {
            return new OrganizationAccessResponse(
                    access.organizationId(),
                    access.displayName(),
                    access.status(),
                    access.roleKeys(),
                    selected);
        }
    }
}
