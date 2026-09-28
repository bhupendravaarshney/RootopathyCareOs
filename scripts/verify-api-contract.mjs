import { readFileSync } from "node:fs";
import { resolve as resolvePath } from "node:path";
import { pathToFileURL } from "node:url";

export const expectedOperations = [
  ["get", "/api/public/prototype-screens", "listPrototypeScreens"],
  ["get", "/api/public/system-summary", "getSystemSummary"],
  ["get", "/api/v1/auth/csrf", "issueCsrfToken"],
  ["get", "/api/v1/auth/session", "getAuthenticationSession"],
  ["post", "/api/v1/auth/login", "login"],
  ["post", "/api/v1/auth/logout", "logout"],
  ["post", "/api/v1/auth/password-reset-requests", "requestPasswordReset"],
  ["post", "/api/v1/auth/password-resets", "completePasswordReset"],
  ["post", "/api/v1/auth/invitation-acceptances", "acceptInvitation"],
  ["post", "/api/v1/auth/mfa/enrollments", "startMfaEnrollment"],
  ["post", "/api/v1/auth/mfa/enrollments/verification", "verifyMfaEnrollment"],
  ["post", "/api/v1/auth/mfa/challenges", "completeMfaChallenge"],
  ["post", "/api/v1/auth/recent-authentications", "verifyRecentAuthentication"],
  ["post", "/api/v1/auth/mfa/recovery-codes", "regenerateRecoveryCodes"],
  ["get", "/api/v1/organizations", "listSelectableOrganizations"],
  ["post", "/api/v1/auth/organization-selections", "selectOrganization"],
  [
    "get",
    "/api/v1/organizations/{organizationId}/memberships",
    "listOrganizationMemberships",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/setup-readiness",
    "getAdministrationReadiness",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/profile",
    "getOrganizationProfile",
  ],
  [
    "put",
    "/api/v1/organizations/{organizationId}/profile",
    "updateOrganizationProfile",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/facilities",
    "getFacilityDirectory",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/facilities",
    "createFacilityDraft",
  ],
  [
    "put",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}",
    "updateFacilityDraft",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/submissions",
    "submitFacilityDraft",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/units",
    "getOrganizationUnitDirectory",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/units",
    "createOrganizationUnitDraft",
  ],
  [
    "put",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/units/{unitId}",
    "updateOrganizationUnitDraft",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/units/{unitId}/reparentings",
    "reparentOrganizationUnit",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/units/{unitId}/activations",
    "activateOrganizationUnit",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/units/{unitId}/suspensions",
    "suspendOrganizationUnit",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/units/{unitId}/reactivations",
    "reactivateOrganizationUnit",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/units/{unitId}/closures",
    "closeOrganizationUnit",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations",
    "getServiceLocationDirectory",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations",
    "createServiceLocationDraft",
  ],
  [
    "put",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}",
    "updateServiceLocationDraft",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}/reparentings",
    "reparentServiceLocation",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}/activations",
    "activateServiceLocation",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}/suspensions",
    "suspendServiceLocation",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}/reactivations",
    "reactivateServiceLocation",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/locations/{locationId}/closures",
    "closeServiceLocation",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/governance-responsibilities",
    "getOrganizationGovernanceDirectory",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/governance-responsibilities",
    "createOrganizationGovernanceResponsibility",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/governance-responsibilities/{responsibilityId}/supersessions",
    "supersedeOrganizationGovernanceResponsibility",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/governance-responsibilities/{responsibilityId}/endings",
    "endOrganizationGovernanceResponsibility",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/international-settings",
    "getOrganizationInternationalSettings",
  ],
  [
    "put",
    "/api/v1/organizations/{organizationId}/international-settings",
    "scheduleOrganizationInternationalSettings",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/identifiers",
    "listOrganizationIdentifiers",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/identifiers",
    "createOrganizationIdentifier",
  ],
  [
    "put",
    "/api/v1/organizations/{organizationId}/identifiers/{identifierId}",
    "updateOrganizationIdentifier",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/identifiers/{identifierId}/verifications",
    "verifyOrganizationIdentifier",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/identifiers/{identifierId}/revocations",
    "revokeOrganizationIdentifier",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/identifiers/{identifierId}/supersessions",
    "supersedeOrganizationIdentifier",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/addresses",
    "createOrganizationAddress",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/addresses/{addressId}/supersessions",
    "supersedeOrganizationAddress",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/addresses/{addressId}/endings",
    "endOrganizationAddress",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/contacts",
    "listOrganizationContacts",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/contacts",
    "createOrganizationContact",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/contacts/{contactId}/verifications",
    "verifyOrganizationContact",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/contacts/{contactId}/supersessions",
    "supersedeOrganizationContact",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/contacts/{contactId}/endings",
    "endOrganizationContact",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/invitations",
    "issueInvitation",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/invitations/{invitationId}/revocations",
    "revokeInvitation",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/users/{targetUserId}/mfa-reset-requests",
    "requestMfaAdministrativeReset",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/users/{targetUserId}/mfa-reset-requests/{approvalId}/approvals",
    "approveMfaAdministrativeReset",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/users/{targetUserId}/mfa-reset-requests/{approvalId}/executions",
    "executeMfaAdministrativeReset",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/memberships/{membershipId}/change-requests",
    "requestOrganizationMembershipChange",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/memberships/{membershipId}/change-requests/{approvalId}/approvals",
    "approveOrganizationMembershipChange",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/memberships/{membershipId}/change-requests/{approvalId}/executions",
    "executeOrganizationMembershipChange",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/memberships/{membershipId}/owner-transfer-requests",
    "requestOrganizationOwnerTransfer",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/memberships/{membershipId}/owner-transfer-requests/{approvalId}/approvals",
    "approveOrganizationOwnerTransfer",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/memberships/{membershipId}/owner-transfer-requests/{approvalId}/executions",
    "executeOrganizationOwnerTransfer",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/facilities/{facilityId}/{action}",
    "transitionFacilityLifecycle",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/operating-hours",
    "getOperatingHoursOverview",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/operating-hours/{targetType}/{targetId}",
    "getOperatingHoursDirectory",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/operating-hours/{targetType}/{targetId}/batches",
    "replaceOperatingHoursBatch",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/operating-hours/{targetType}/{targetId}/batches/{batchId}/cancellations",
    "cancelOperatingHoursBatch",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/services",
    "getServiceCatalogue",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/services",
    "createServiceDefinition",
  ],
  [
    "put",
    "/api/v1/organizations/{organizationId}/services/{serviceId}",
    "updateServiceDefinition",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/services/{serviceId}/activations",
    "activateServiceDefinition",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/services/{serviceId}/retirements",
    "retireServiceDefinition",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/service-assignments",
    "getServiceAssignmentDirectory",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/service-assignments",
    "createServiceAssignment",
  ],
  [
    "put",
    "/api/v1/organizations/{organizationId}/service-assignments/{assignmentId}",
    "updateServiceAssignment",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/service-assignments/{assignmentId}/{action}",
    "transitionServiceAssignment",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/identifier-schemes",
    "getIdentifierSchemeDirectory",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/identifier-schemes",
    "createIdentifierScheme",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/identifier-schemes/{schemeId}/versions",
    "createIdentifierSchemeVersion",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/configuration-activations",
    "getConfigurationActivationDirectory",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/configuration-validations",
    "validateConfiguration",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/configurations/{configurationId}/submissions",
    "submitConfiguration",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/configurations/{configurationId}/decisions",
    "decideConfiguration",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/configurations/{configurationId}/activations",
    "activateConfiguration",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/configuration-history",
    "getConfigurationHistory",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/audit-evidence",
    "getAuditEvidence",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/audit-evidence/{eventId}/accesses",
    "accessAuditEvidenceDetail",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/evidence-exports",
    "getEvidenceExportDirectory",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/evidence-exports",
    "requestEvidenceExport",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/evidence-exports/{exportId}/decisions",
    "decideEvidenceExport",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/evidence-exports/{exportId}/accesses",
    "accessEvidenceExport",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/workforce/screens/{screenId}",
    "getWorkforceScreen",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/workforce/screens/{screenId}/actions/{actionKey}",
    "performWorkforceAction",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/workforce/screens/{screenId}/actions/{actionKey}/impact-preview",
    "previewWorkforceImpact",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/patients/screens/{screenId}",
    "getPatientRegistryScreen",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/patients/screens/{screenId}/actions/{actionKey}",
    "performPatientRegistryAction",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/patients/screens/{screenId}/actions/{actionKey}/impact-preview",
    "previewPatientRegistryImpact",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/scheduling/screens/{screenId}",
    "getSchedulingScreen",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/scheduling/screens/{screenId}/actions/{actionKey}",
    "performSchedulingAction",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/encounters/screens/{screenId}",
    "getEncounterScreen",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/encounters/screens/{screenId}/actions/{actionKey}",
    "performEncounterAction",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/assessments/screens/{screenId}",
    "getAssessmentScreen",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/assessments/screens/{screenId}/actions/{actionKey}",
    "performAssessmentAction",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/documents/screens/{screenId}",
    "getDocumentScreen",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/documents/screens/{screenId}/actions/{actionKey}",
    "performDocumentAction",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/ai/screens/{screenId}",
    "getAiScreen",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/ai/screens/{screenId}/actions/{actionKey}",
    "performAiAction",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/care-plans/screens/{screenId}",
    "getCarePlanScreen",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/care-plans/screens/{screenId}/actions/{actionKey}",
    "performCarePlanAction",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/followups/screens/{screenId}",
    "getFollowupScreen",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/followups/screens/{screenId}/actions/{actionKey}",
    "performFollowupAction",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/billing/screens/{screenId}",
    "getBillingScreen",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/billing/screens/{screenId}/actions/{actionKey}",
    "performBillingAction",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/reporting/screens/{screenId}",
    "getReportingScreen",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/reporting/screens/{screenId}/actions/{actionKey}",
    "performReportingAction",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/documents",
    "uploadDocument",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/documents/{documentId}/accesses",
    "createDocumentAccess",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/documents/{documentId}/versions/{documentVersionId}/accesses/{accessIntentId}",
    "openDocumentAccess",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/workforce/evidence/{evidenceId}/accesses",
    "accessWorkforceEvidence",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/workforce/exports/{exportId}/accesses",
    "accessWorkforceExport",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/workforce/exports/{exportId}/download",
    "downloadWorkforceExport",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/workforce/credentials/{credentialId}/documents/{documentId}/accesses",
    "accessWorkforceCredentialDocument",
  ],
  [
    "get",
    "/api/v1/organizations/{organizationId}/workforce/credentials/{credentialId}/documents/{documentId}/accesses/{accessIntentId}",
    "openWorkforceCredentialDocumentAccess",
  ],
  [
    "post",
    "/api/v1/organizations/{organizationId}/workforce/credentials/{credentialId}/documents",
    "uploadWorkforceCredentialDocument",
  ],
];

const approvedReadinessGateKeys = [
  "organization.profile.complete",
  "organization.identifier.primary_verified",
  "organization.contact.coverage",
  "organization.governance.coverage",
  "access.final_owner",
  "access.mfa_enforced",
  "network.facility.minimum",
  "network.hierarchy.valid",
  "network.hours.valid",
  "service.catalogue.active",
  "service.assignment.valid",
  "identifier.scheme.active",
  "configuration.integrity",
  "governance.registry.active",
  "platform.dependencies.ready",
];

const approvedOrganizationTypes = [
  "care_provider",
  "care_network",
  "administrative",
];
const approvedOrganizationLifecycles = [
  "draft",
  "under_review",
  "active",
  "suspended",
  "closed",
];

const sessionProtectedOperations = new Set([
  "logout",
  "startMfaEnrollment",
  "verifyMfaEnrollment",
  "completeMfaChallenge",
  "verifyRecentAuthentication",
  "regenerateRecoveryCodes",
  "listSelectableOrganizations",
  "selectOrganization",
  "listOrganizationMemberships",
  "getAdministrationReadiness",
  "getOrganizationProfile",
  "updateOrganizationProfile",
  "getFacilityDirectory",
  "createFacilityDraft",
  "getOrganizationInternationalSettings",
  "scheduleOrganizationInternationalSettings",
  "getOrganizationGovernanceDirectory",
  "createOrganizationGovernanceResponsibility",
  "supersedeOrganizationGovernanceResponsibility",
  "endOrganizationGovernanceResponsibility",
  "listOrganizationIdentifiers",
  "createOrganizationIdentifier",
  "updateOrganizationIdentifier",
  "verifyOrganizationIdentifier",
  "revokeOrganizationIdentifier",
  "supersedeOrganizationIdentifier",
  "createOrganizationAddress",
  "supersedeOrganizationAddress",
  "endOrganizationAddress",
  "listOrganizationContacts",
  "createOrganizationContact",
  "verifyOrganizationContact",
  "supersedeOrganizationContact",
  "endOrganizationContact",
  "issueInvitation",
  "revokeInvitation",
  "requestMfaAdministrativeReset",
  "approveMfaAdministrativeReset",
  "executeMfaAdministrativeReset",
  "requestOrganizationMembershipChange",
  "approveOrganizationMembershipChange",
  "executeOrganizationMembershipChange",
  "requestOrganizationOwnerTransfer",
  "approveOrganizationOwnerTransfer",
  "executeOrganizationOwnerTransfer",
  "getWorkforceScreen",
  "performWorkforceAction",
  "previewWorkforceImpact",
  "getPatientRegistryScreen",
  "performPatientRegistryAction",
  "previewPatientRegistryImpact",
  "getSchedulingScreen",
  "performSchedulingAction",
  "getEncounterScreen",
  "performEncounterAction",
  "getAssessmentScreen",
  "performAssessmentAction",
  "getDocumentScreen",
  "performDocumentAction",
  "uploadDocument",
  "createDocumentAccess",
  "openDocumentAccess",
  "getAiScreen",
  "performAiAction",
  "getCarePlanScreen",
  "performCarePlanAction",
  "getFollowupScreen",
  "performFollowupAction",
  "getBillingScreen",
  "performBillingAction",
  "getReportingScreen",
  "performReportingAction",
  "accessWorkforceEvidence",
  "accessWorkforceExport",
  "downloadWorkforceExport",
  "accessWorkforceCredentialDocument",
  "openWorkforceCredentialDocumentAccess",
  "uploadWorkforceCredentialDocument",
]);

const idempotentOperations = new Set([
  "updateOrganizationProfile",
  "createFacilityDraft",
  "scheduleOrganizationInternationalSettings",
  "createOrganizationGovernanceResponsibility",
  "supersedeOrganizationGovernanceResponsibility",
  "endOrganizationGovernanceResponsibility",
  "createOrganizationIdentifier",
  "updateOrganizationIdentifier",
  "verifyOrganizationIdentifier",
  "revokeOrganizationIdentifier",
  "supersedeOrganizationIdentifier",
  "createOrganizationAddress",
  "supersedeOrganizationAddress",
  "endOrganizationAddress",
  "createOrganizationContact",
  "verifyOrganizationContact",
  "supersedeOrganizationContact",
  "endOrganizationContact",
  "issueInvitation",
  "revokeInvitation",
  "requestMfaAdministrativeReset",
  "approveMfaAdministrativeReset",
  "executeMfaAdministrativeReset",
  "requestOrganizationMembershipChange",
  "approveOrganizationMembershipChange",
  "executeOrganizationMembershipChange",
  "requestOrganizationOwnerTransfer",
  "approveOrganizationOwnerTransfer",
  "executeOrganizationOwnerTransfer",
  "performWorkforceAction",
  "performPatientRegistryAction",
  "performSchedulingAction",
  "performEncounterAction",
  "performAssessmentAction",
  "performDocumentAction",
  "uploadDocument",
  "createDocumentAccess",
  "performAiAction",
  "performCarePlanAction",
  "performFollowupAction",
  "performBillingAction",
  "performReportingAction",
  "accessWorkforceEvidence",
  "accessWorkforceExport",
  "accessWorkforceCredentialDocument",
  "uploadWorkforceCredentialDocument",
]);

export function verifyApiContract(contract) {
  function assert(condition, message) {
    if (!condition) {
      throw new Error(message);
    }
  }

  function resolveReference(reference) {
    assert(
      typeof reference === "string" && reference.startsWith("#/"),
      `Only local references are allowed: ${reference}`,
    );
    return reference
      .slice(2)
      .split("/")
      .map((token) => token.replaceAll("~1", "/").replaceAll("~0", "~"))
      .reduce((value, token) => value?.[token], contract);
  }

  function resolved(value) {
    return value?.$ref ? resolveReference(value.$ref) : value;
  }

  function verifyReferences(value, location = "contract") {
    if (Array.isArray(value)) {
      value.forEach((item, index) =>
        verifyReferences(item, `${location}[${index}]`),
      );
      return;
    }
    if (value === null || typeof value !== "object") {
      return;
    }
    if (value.$ref) {
      assert(
        resolveReference(value.$ref),
        `Unresolved reference at ${location}: ${value.$ref}`,
      );
    }
    Object.entries(value).forEach(([key, child]) =>
      verifyReferences(child, `${location}.${key}`),
    );
  }

  assert(
    contract.openapi === "3.1.0",
    "The foundation contract must use OpenAPI 3.1.0",
  );

  for (const [method, path, operationId] of expectedOperations) {
    const operation = contract.paths?.[path]?.[method];
    const operationLabel = `${method.toUpperCase()} ${path}`;
    assert(operation, `Missing ${operationLabel}`);
    assert(
      operation.operationId === operationId,
      `Unexpected operationId for ${operationLabel}`,
    );
    for (const [status, responseReference] of Object.entries(
      operation.responses ?? {},
    )) {
      const response = resolved(responseReference);
      assert(response, `Unresolved ${status} response for ${operationLabel}`);
      assert(
        response.headers?.["X-Correlation-Id"],
        `${operationLabel} response ${status} must declare X-Correlation-Id`,
      );
    }

    if (
      ["post", "put", "patch", "delete"].includes(method) &&
      path.startsWith("/api/v1/")
    ) {
      const origin = operation.parameters
        ?.map(resolved)
        .find((parameter) => parameter.name === "Origin");
      assert(
        origin?.required,
        `${operationLabel} must require the browser Origin contract`,
      );
      assert(
        operation.security?.some(
          (requirement) => requirement.csrfCookie && requirement.csrfHeader,
        ),
        `${operationLabel} must require the CSRF cookie/header pair`,
      );
    }

    if (sessionProtectedOperations.has(operationId)) {
      assert(
        operation.security?.some((requirement) => requirement.sessionCookie),
        `${operationLabel} must require the server-side session cookie`,
      );
    }
    if (idempotentOperations.has(operationId)) {
      const idempotencyParameter = operation.parameters
        ?.map(resolved)
        .find((parameter) => parameter.name === "Idempotency-Key");
      assert(
        idempotencyParameter?.required,
        `${operationLabel} must require the shared Idempotency-Key contract`,
      );
    }
  }

  const expectedOperationKeys = new Set(
    expectedOperations.map(
      ([method, path]) => `${method.toUpperCase()} ${path}`,
    ),
  );
  const actualOperationKeys = Object.entries(contract.paths ?? {}).flatMap(
    ([path, pathItem]) =>
      ["get", "post", "put", "patch", "delete", "options", "head", "trace"]
        .filter((method) => pathItem[method])
        .map((method) => `${method.toUpperCase()} ${path}`),
  );
  const unexpectedOperations = actualOperationKeys.filter(
    (operation) => !expectedOperationKeys.has(operation),
  );
  assert(
    actualOperationKeys.length === expectedOperationKeys.size &&
      unexpectedOperations.length === 0,
    `OpenAPI operations must exactly match the checked registry; unexpected operations: ${unexpectedOperations.join(", ") || "none"}`,
  );

  const prototypeScreen = contract.components?.schemas?.PrototypeScreen;
  const systemSummary = contract.components?.schemas?.SystemSummary;
  assert(
    prototypeScreen?.properties?.id?.pattern ===
      "^(M1|M2|P3|P4|P5|COS|P7|P8|P9|P10|P11|P12)-[0-9]{2}$" &&
      JSON.stringify(prototypeScreen?.properties?.module?.enum) ===
        JSON.stringify([
          "M1",
          "M2",
          "M3",
          "M4",
          "M5",
          "COS",
          "M7",
          "M8",
          "M9",
          "M10",
          "M11",
          "M12",
        ]) &&
      systemSummary?.properties?.screenCount?.const === 185,
    "Prototype catalogue must expose the exact 185-screen M1 through M12 registry",
  );

  assert(
    !contract.components?.securitySchemes?.basicAuthentication,
    "HTTP Basic must not return to the browser identity contract",
  );
  assert(
    contract.components?.securitySchemes?.sessionCookie?.name ===
      "CAREOS_SESSION",
    "The identity contract must declare the CAREOS_SESSION cookie",
  );

  const problem = contract.components?.schemas?.Problem;
  const requiredProblemFields = [
    "type",
    "title",
    "status",
    "detail",
    "instance",
    "code",
    "correlationId",
  ];
  assert(problem, "Missing Problem schema");
  assert(
    requiredProblemFields.every((field) => problem.required?.includes(field)),
    "Problem schema is missing required RFC 9457 or CareOS fields",
  );
  assert(
    contract.components?.responses?.InternalError?.content?.[
      "application/problem+json"
    ],
    "Problem responses must use application/problem+json",
  );

  const conventions = contract["x-careos-conventions"];
  assert(conventions, "Missing x-careos-conventions policy");
  assert(
    conventions.tenantPathPrefix === "/api/v1/organizations/{organizationId}",
    "Protected business routes must use the organization tenant path prefix",
  );
  assert(
    conventions.organizationParameter ===
      "#/components/parameters/OrganizationId",
    "The tenant convention must reference OrganizationId",
  );

  const organizationId = contract.components?.parameters?.OrganizationId;
  assert(
    organizationId?.in === "path" &&
      organizationId.required === true &&
      organizationId.schema?.format === "uuid",
    "OrganizationId must be a required UUID path parameter",
  );

  assert(
    conventions.pagination?.strategy === "cursor" &&
      conventions.pagination.clientTreatment === "opaque" &&
      conventions.pagination.cursorParameter ===
        "#/components/parameters/Cursor" &&
      conventions.pagination.limitParameter ===
        "#/components/parameters/Limit" &&
      conventions.pagination.metadataSchema ===
        "#/components/schemas/CursorPageMetadata",
    "Pagination must use the shared opaque cursor contract",
  );
  assert(
    contract.components?.parameters?.Limit?.schema?.maximum === 100 &&
      contract.components?.parameters?.Limit?.schema?.default === 25,
    "Cursor page limits must default to 25 and be capped at 100",
  );
  assert(
    ["limit", "hasMore", "nextCursor"].every((field) =>
      contract.components?.schemas?.CursorPageMetadata?.required?.includes(
        field,
      ),
    ),
    "CursorPageMetadata is missing required paging fields",
  );

  assert(
    conventions.filtering?.mode === "operation-allow-list" &&
      conventions.filtering.unknownParameters === "reject",
    "Filters must be operation-specific allowlists that reject unknown keys",
  );

  const ifMatch = contract.components?.parameters?.IfMatch;
  const etag = contract.components?.headers?.ETag;
  assert(
    conventions.optimisticConcurrency?.responseHeader ===
      "#/components/headers/ETag" &&
      conventions.optimisticConcurrency.requestParameter ===
        "#/components/parameters/IfMatch" &&
      conventions.optimisticConcurrency.missingStatus === 428 &&
      conventions.optimisticConcurrency.mismatchStatus === 412 &&
      conventions.optimisticConcurrency.strongEtagsOnly === true,
    "Optimistic concurrency must require strong ETags, 428, and 412",
  );
  assert(
    ifMatch?.name === "If-Match" &&
      ifMatch.required === true &&
      ifMatch.schema?.pattern === etag?.schema?.pattern,
    "If-Match and ETag must share the required strong entity-tag format",
  );
  assert(
    contract.components?.responses?.PreconditionFailed?.content?.[
      "application/problem+json"
    ] && contract.components?.responses?.PreconditionRequired,
    "Reusable 412 and 428 problem responses are required",
  );

  const idempotencyKey = contract.components?.parameters?.IdempotencyKey;
  assert(
    conventions.idempotency?.scope === "protected-tenant-mutations" &&
      conventions.idempotency.requestParameter ===
        "#/components/parameters/IdempotencyKey" &&
      conventions.idempotency.requiredForCallerRetriedMutations === true &&
      conventions.idempotency.keyReuseRequiresEquivalentRequest === true,
    "Caller-retried protected mutations must use scoped idempotency keys",
  );
  assert(
    idempotencyKey?.name === "Idempotency-Key" &&
      idempotencyKey.required === true &&
      idempotencyKey.schema?.minLength >= 16 &&
      idempotencyKey.schema?.maxLength <= 128,
    "Idempotency-Key must be a bounded required header",
  );

  assert(
    conventions.retry?.policy === "caller-controlled" &&
      JSON.stringify(conventions.retry.retryableMethods) ===
        JSON.stringify(["GET", "HEAD"]) &&
      JSON.stringify(conventions.retry.statusCodes) ===
        JSON.stringify([429, 503]) &&
      conventions.retry.responseHeader === "#/components/headers/RetryAfter" &&
      conventions.retry.maximumDelaySeconds === 86400 &&
      conventions.retry.automaticMutationRetry === false,
    "Retries must be bounded, caller-controlled, and disabled for mutations",
  );
  assert(
    contract.components?.headers?.RetryAfter?.schema?.minimum === 1 &&
      contract.components?.headers?.RetryAfter?.schema?.maximum === 86400 &&
      contract.components?.responses?.ServiceUnavailable?.headers?.[
        "Retry-After"
      ],
    "Retry-After must be bounded and declared for service unavailability",
  );

  const sessionExpiryHeader = contract.components?.headers?.SessionExpiresIn;
  assert(
    conventions.sessionLifecycle?.responseHeader ===
      "#/components/headers/SessionExpiresIn" &&
      conventions.sessionLifecycle.refreshOnAuthenticatedRequest === true &&
      conventions.sessionLifecycle.backgroundPolling === false &&
      conventions.sessionLifecycle.clientDeadlineTreatment ===
        "lock-without-refresh",
    "Browser sessions must publish a deadline without background polling",
  );
  assert(
    sessionExpiryHeader?.schema?.type === "integer" &&
      sessionExpiryHeader.schema.minimum === 0 &&
      sessionExpiryHeader.schema.maximum === 2147483647,
    "The session-expiry signal must be a bounded non-negative duration",
  );

  const readiness = contract.components?.schemas?.AdministrationReadiness;
  const readinessGate = contract.components?.schemas?.ReadinessGate;
  assert(
    readiness?.["x-careos-freshness-seconds"] === 900 &&
      readiness?.properties?.catalogueVersion?.const === "m1-readiness-v1" &&
      readiness?.properties?.totalGates?.minimum === 15 &&
      readiness?.properties?.totalGates?.maximum === 15 &&
      readiness?.properties?.gates?.minItems === 15 &&
      readiness?.properties?.gates?.maxItems === 15 &&
      [
        "organizationRevision",
        "evaluatedAt",
        "expiresAt",
        "blockedGates",
        "warningGates",
        "notApplicableGates",
      ].every((field) => readiness?.required?.includes(field)),
    "Administration readiness must bind the approved catalogue and 15-minute freshness contract",
  );
  assert(
    JSON.stringify(readinessGate?.properties?.key?.enum) ===
      JSON.stringify(approvedReadinessGateKeys) &&
      readinessGate?.properties?.version?.const === "m1-readiness-v1" &&
      JSON.stringify(readinessGate?.properties?.outcome?.enum) ===
        JSON.stringify(["complete", "warning", "blocked", "not_applicable"]) &&
      readinessGate?.properties?.evidenceReferences?.maxItems === 4 &&
      readinessGate?.properties?.href?.pattern === "^#/M1-[0-9]{2}$",
    "ReadinessGate must expose the exact approved ordered keys, outcomes, evidence bound, and deep links",
  );

  const organizationProfile = contract.components?.schemas?.OrganizationProfile;
  const organizationProfileUpdate =
    contract.components?.schemas?.OrganizationProfileUpdateRequest;
  const exactProfileFields = [
    "organizationId",
    "legalName",
    "displayName",
    "tradingName",
    "organizationType",
    "countryCode",
    "timezone",
    "locale",
    "lifecycleStatus",
    "editable",
    "lockVersion",
    "updatedAt",
  ];
  assert(
    exactProfileFields.every((field) =>
      organizationProfile?.required?.includes(field),
    ) &&
      organizationProfile?.additionalProperties === false &&
      organizationProfile?.properties?.legalName?.minLength === 2 &&
      organizationProfile?.properties?.legalName?.maxLength === 200 &&
      organizationProfile?.properties?.displayName?.minLength === 2 &&
      organizationProfile?.properties?.displayName?.maxLength === 120 &&
      organizationProfile?.properties?.tradingName?.maxLength === 160 &&
      JSON.stringify(
        organizationProfile?.properties?.organizationType?.enum,
      ) === JSON.stringify([...approvedOrganizationTypes, null]) &&
      organizationProfile?.properties?.locale?.pattern ===
        "^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$" &&
      JSON.stringify(organizationProfile?.properties?.lifecycleStatus?.enum) ===
        JSON.stringify(approvedOrganizationLifecycles) &&
      organizationProfile?.properties?.editable?.type === "boolean",
    "OrganizationProfile must expose the exact approved identity fields, bounds, lifecycle, and live edit projection",
  );
  assert(
    [
      "legalName",
      "displayName",
      "tradingName",
      "organizationType",
      "countryCode",
      "timezone",
      "locale",
      "reason",
    ].every((field) => organizationProfileUpdate?.required?.includes(field)) &&
      organizationProfileUpdate?.additionalProperties === false &&
      JSON.stringify(
        organizationProfileUpdate?.properties?.organizationType?.enum,
      ) === JSON.stringify(approvedOrganizationTypes) &&
      organizationProfileUpdate?.properties?.tradingName?.type?.includes(
        "null",
      ) &&
      organizationProfileUpdate?.properties?.locale?.pattern ===
        "^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$" &&
      organizationProfileUpdate?.properties?.reason?.minLength === 10 &&
      organizationProfileUpdate?.properties?.reason?.maxLength === 500,
    "OrganizationProfileUpdateRequest must require the exact approved mutable profile contract",
  );

  const organizationUnitDirectory =
    contract.components?.schemas?.OrganizationUnitDirectory;
  assert(
    organizationUnitDirectory?.additionalProperties === false &&
      [
        "organizationId",
        "facilityId",
        "canManage",
        "canManageLifecycle",
        "units",
        "evaluatedAt",
      ].every((field) =>
        organizationUnitDirectory?.required?.includes(field),
      ) &&
      organizationUnitDirectory?.properties?.canManage?.type === "boolean" &&
      organizationUnitDirectory?.properties?.canManageLifecycle?.type ===
        "boolean",
    "OrganizationUnitDirectory must project draft and high-assurance lifecycle authority separately",
  );
  const serviceLocation = contract.components?.schemas?.ServiceLocation;
  const serviceLocationDirectory =
    contract.components?.schemas?.ServiceLocationDirectory;
  const serviceLocationCreate =
    contract.components?.schemas?.ServiceLocationCreateRequest;
  const serviceLocationUpdate =
    contract.components?.schemas?.ServiceLocationUpdateRequest;
  const serviceLocationReparent =
    contract.components?.schemas?.ServiceLocationReparentRequest;
  assert(
    serviceLocation?.additionalProperties === false &&
      JSON.stringify(serviceLocation?.properties?.locationType?.enum) ===
        JSON.stringify(["physical", "virtual"]) &&
      serviceLocation?.properties?.capacity?.minimum === 1 &&
      serviceLocation?.properties?.capacity?.maximum === 100000 &&
      serviceLocationDirectory?.additionalProperties === false &&
      [
        "organizationId",
        "facilityId",
        "canManage",
        "canManageLifecycle",
        "locations",
        "evaluatedAt",
      ].every((field) => serviceLocationDirectory?.required?.includes(field)) &&
      serviceLocationCreate?.additionalProperties === false &&
      ["locationCode", "locationType", "name", "effectiveFrom", "reason"].every(
        (field) => serviceLocationCreate?.required?.includes(field),
      ) &&
      serviceLocationCreate?.properties?.reason?.minLength === 10 &&
      serviceLocationCreate?.properties?.reason?.maxLength === 500 &&
      serviceLocationUpdate?.additionalProperties === false &&
      serviceLocationUpdate?.properties?.parentId === undefined &&
      serviceLocationReparent?.additionalProperties === false &&
      ["parentId", "effectiveFrom", "reason"].every((field) =>
        serviceLocationReparent?.required?.includes(field),
      ),
    "Service-location contracts must preserve physical/virtual, capacity, effective-range, and governed-write bounds",
  );

  const internationalSettings =
    contract.components?.schemas?.OrganizationInternationalSettings;
  const internationalVersion =
    contract.components?.schemas?.InternationalSettingsVersion;
  const internationalPreview =
    contract.components?.schemas?.InternationalSettingsFormatPreview;
  const internationalSchedule =
    contract.components?.schemas?.InternationalSettingsScheduleRequest;
  const approvedWeekStarts = [
    "MONDAY",
    "TUESDAY",
    "WEDNESDAY",
    "THURSDAY",
    "FRIDAY",
    "SATURDAY",
    "SUNDAY",
  ];
  assert(
    internationalSettings?.additionalProperties === false &&
      [
        "organizationId",
        "editable",
        "canSchedule",
        "lockVersion",
        "evaluatedAt",
        "weekStarts",
        "impactRules",
        "versions",
      ].every((field) => internationalSettings?.required?.includes(field)) &&
      internationalSettings?.properties?.versions?.minItems === 1 &&
      internationalSettings?.properties?.versions?.maxItems === 2 &&
      JSON.stringify(
        internationalSettings?.properties?.weekStarts?.items?.enum,
      ) === JSON.stringify(approvedWeekStarts) &&
      internationalSettings?.properties?.impactRules?.minItems === 6 &&
      internationalSettings?.properties?.impactRules?.maxItems === 6,
    "OrganizationInternationalSettings must preserve the exact versioned settings and impact projection",
  );
  assert(
    internationalVersion?.additionalProperties === false &&
      internationalVersion?.properties?.formatPattern === undefined &&
      internationalVersion?.properties?.dateFormat === undefined &&
      JSON.stringify(internationalVersion?.properties?.lifecycle?.enum) ===
        JSON.stringify(["default", "active", "scheduled", "superseded"]) &&
      JSON.stringify(internationalVersion?.properties?.weekStart?.enum) ===
        JSON.stringify(approvedWeekStarts) &&
      internationalPreview?.additionalProperties === false &&
      internationalPreview?.properties?.localeLibraryDerived?.const === true,
    "International settings versions must use immutable lifecycle values and locale-library formats only",
  );
  assert(
    internationalSchedule?.additionalProperties === false &&
      [
        "countryCode",
        "timezone",
        "locale",
        "language",
        "currencyCode",
        "weekStart",
        "effectiveFrom",
        "reason",
      ].every((field) => internationalSchedule?.required?.includes(field)) &&
      internationalSchedule?.properties?.reason?.minLength === 10 &&
      internationalSchedule?.properties?.reason?.maxLength === 500 &&
      internationalSchedule?.properties?.formatPattern === undefined,
    "InternationalSettingsScheduleRequest must require the exact approved future settings contract",
  );

  const governance = contract.components?.schemas?.GovernanceResponsibility;
  const governanceDirectory =
    contract.components?.schemas?.OrganizationGovernanceDirectory;
  const governanceWrite =
    contract.components?.schemas?.GovernanceResponsibilityWriteRequest;
  assert(
    governance?.additionalProperties === false &&
      governance?.properties?.escalationEmail === undefined &&
      governance?.properties?.escalationPhone === undefined &&
      JSON.stringify(governance?.properties?.responsibilityType?.enum) ===
        JSON.stringify(["clinical", "privacy", "security", "billing"]) &&
      JSON.stringify(governance?.properties?.status?.enum) ===
        JSON.stringify(["scheduled", "active", "ended", "superseded"]) &&
      JSON.stringify(governance?.properties?.availableActions?.items?.enum) ===
        JSON.stringify(["supersede", "end"]),
    "GovernanceResponsibility must expose only the approved confidential effective projection",
  );
  assert(
    governanceDirectory?.additionalProperties === false &&
      governanceDirectory?.properties?.responsibilityTypes?.minItems === 4 &&
      governanceDirectory?.properties?.responsibilityTypes?.maxItems === 4 &&
      governanceWrite?.additionalProperties === false &&
      governanceWrite?.required?.includes("membershipId") &&
      governanceWrite?.required?.includes("externalContactId") &&
      governanceWrite?.properties?.reason?.minLength === 10,
    "Governance directory and mutation requests must preserve exact coverage and linkage fields",
  );

  const identifierId = contract.components?.parameters?.IdentifierId;
  assert(
    identifierId?.in === "path" &&
      identifierId.required === true &&
      identifierId.schema?.format === "uuid",
    "IdentifierId must be a required UUID path parameter",
  );

  const organizationIdentifier =
    contract.components?.schemas?.OrganizationIdentifier;
  const organizationIdentifierCollection =
    contract.components?.schemas?.OrganizationIdentifierCollection;
  const organizationIdentifierType =
    contract.components?.schemas?.OrganizationIdentifierType;
  const organizationIdentifierWrite =
    contract.components?.schemas?.OrganizationIdentifierWriteRequest;
  const organizationIdentifierVerification =
    contract.components?.schemas?.OrganizationIdentifierVerificationRequest;
  const organizationIdentifierSupersession =
    contract.components?.schemas?.OrganizationIdentifierSupersessionRequest;
  const exactIdentifierFields = [
    "identifierId",
    "identifierType",
    "typeDisplayName",
    "assigningAuthority",
    "value",
    "jurisdictionCountryCode",
    "verificationStatus",
    "evidenceReference",
    "isPrimary",
    "issueDate",
    "expiryDate",
    "effectiveFrom",
    "effectiveTo",
    "supersedesId",
    "status",
    "availableActions",
    "lockVersion",
    "createdAt",
    "updatedAt",
  ];
  assert(
    organizationIdentifier?.additionalProperties === false &&
      exactIdentifierFields.every((field) =>
        organizationIdentifier?.required?.includes(field),
      ) &&
      organizationIdentifier?.properties?.assigningAuthority?.minLength === 2 &&
      organizationIdentifier?.properties?.assigningAuthority?.maxLength ===
        160 &&
      organizationIdentifier?.properties?.value?.minLength === 1 &&
      organizationIdentifier?.properties?.value?.maxLength === 128 &&
      JSON.stringify(organizationIdentifier?.properties?.status?.enum) ===
        JSON.stringify([
          "draft",
          "verified",
          "active",
          "expired",
          "revoked",
          "superseded",
        ]) &&
      JSON.stringify(
        organizationIdentifier?.properties?.availableActions?.items?.enum,
      ) === JSON.stringify(["edit", "verify", "revoke", "supersede"]) &&
      organizationIdentifier?.properties?.availableActions?.uniqueItems ===
        true,
    "OrganizationIdentifier must expose the exact approved governed fields, lifecycle, and live actions",
  );
  assert(
    organizationIdentifierCollection?.additionalProperties === false &&
      ["organizationId", "canCreate", "types", "items"].every((field) =>
        organizationIdentifierCollection?.required?.includes(field),
      ) &&
      organizationIdentifierType?.properties?.key?.pattern ===
        "^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$" &&
      organizationIdentifierType?.properties?.primaryRequired?.type ===
        "boolean",
    "OrganizationIdentifierCollection must bind live create authority and migration-owned type metadata",
  );
  assert(
    [
      "identifierType",
      "assigningAuthority",
      "value",
      "jurisdictionCountryCode",
      "isPrimary",
      "issueDate",
      "expiryDate",
      "effectiveFrom",
      "effectiveTo",
      "reason",
    ].every((field) =>
      organizationIdentifierWrite?.required?.includes(field),
    ) &&
      organizationIdentifierWrite?.additionalProperties === false &&
      organizationIdentifierWrite?.properties?.reason?.minLength === 10 &&
      organizationIdentifierWrite?.properties?.reason?.maxLength === 500 &&
      organizationIdentifierWrite?.properties?.effectiveFrom?.format ===
        "date-time" &&
      organizationIdentifierVerification?.additionalProperties === false &&
      organizationIdentifierVerification?.required?.includes(
        "evidenceReference",
      ) &&
      organizationIdentifierVerification?.properties?.evidenceReference
        ?.maxLength === 160 &&
      organizationIdentifierSupersession?.additionalProperties === false &&
      ["replacementId", "replacementEtag", "reason"].every((field) =>
        organizationIdentifierSupersession?.required?.includes(field),
      ) &&
      organizationIdentifierSupersession?.properties?.replacementId?.format ===
        "uuid" &&
      organizationIdentifierSupersession?.properties?.replacementEtag
        ?.pattern ===
        '^\\"organization-identifier:[0-9a-fA-F-]{36}:[0-9]{1,19}\\"$',
    "Organization identifier mutation requests must preserve exact values, ranges, reasons, verification evidence, and replacement revisions",
  );

  for (const [name, parameter] of [
    ["AddressId", contract.components?.parameters?.AddressId],
    ["ContactId", contract.components?.parameters?.ContactId],
  ]) {
    assert(
      parameter?.in === "path" &&
        parameter.required === true &&
        parameter.schema?.format === "uuid",
      `${name} must be a required UUID path parameter`,
    );
  }

  const organizationAddress = contract.components?.schemas?.OrganizationAddress;
  const organizationContact = contract.components?.schemas?.OrganizationContact;
  const organizationContactPurpose =
    contract.components?.schemas?.OrganizationContactPurpose;
  const organizationContactCollection =
    contract.components?.schemas?.OrganizationContactCollection;
  const organizationAddressWrite =
    contract.components?.schemas?.OrganizationAddressWriteRequest;
  const organizationContactWrite =
    contract.components?.schemas?.OrganizationContactWriteRequest;
  const organizationContactReason =
    contract.components?.schemas?.OrganizationContactReasonRequest;
  const exactAddressFields = [
    "addressId",
    "addressType",
    "addressLines",
    "locality",
    "region",
    "postcode",
    "countryCode",
    "validationStatus",
    "validationSource",
    "isPrimary",
    "effectiveFrom",
    "effectiveTo",
    "supersedesId",
    "status",
    "availableActions",
    "lockVersion",
    "createdAt",
    "updatedAt",
  ];
  const exactContactFields = [
    "contactId",
    "channel",
    "purpose",
    "purposeDisplayName",
    "maskedValue",
    "verificationStatus",
    "isPrimary",
    "isPreferred",
    "effectiveFrom",
    "effectiveTo",
    "supersedesId",
    "status",
    "availableActions",
    "lockVersion",
    "createdAt",
    "updatedAt",
  ];
  assert(
    organizationAddress?.additionalProperties === false &&
      exactAddressFields.length === organizationAddress?.required?.length &&
      exactAddressFields.every((field) =>
        organizationAddress?.required?.includes(field),
      ) &&
      JSON.stringify(organizationAddress?.properties?.addressType?.enum) ===
        JSON.stringify(["registered", "postal", "service", "billing"]) &&
      organizationAddress?.properties?.addressLines?.minItems === 1 &&
      organizationAddress?.properties?.addressLines?.maxItems === 4 &&
      JSON.stringify(organizationAddress?.properties?.status?.enum) ===
        JSON.stringify(["scheduled", "active", "ended", "superseded"]) &&
      JSON.stringify(
        organizationAddress?.properties?.availableActions?.items?.enum,
      ) === JSON.stringify(["supersede", "end"]),
    "OrganizationAddress must expose the exact approved structured, effective, historical projection",
  );
  assert(
    organizationContact?.additionalProperties === false &&
      exactContactFields.length === organizationContact?.required?.length &&
      exactContactFields.every((field) =>
        organizationContact?.required?.includes(field),
      ) &&
      organizationContact?.properties?.value === undefined &&
      organizationContact?.properties?.valueNormalized === undefined &&
      organizationContact?.properties?.maskedValue?.maxLength === 2048 &&
      JSON.stringify(organizationContact?.properties?.channel?.enum) ===
        JSON.stringify(["email", "phone", "web"]) &&
      JSON.stringify(organizationContact?.properties?.status?.enum) ===
        JSON.stringify(["scheduled", "active", "ended", "superseded"]) &&
      JSON.stringify(
        organizationContact?.properties?.availableActions?.items?.enum,
      ) === JSON.stringify(["verify", "supersede", "end"]),
    "OrganizationContact must expose only the exact approved masked effective projection",
  );
  assert(
    organizationContactCollection?.additionalProperties === false &&
      [
        "organizationId",
        "canCreate",
        "addressTypes",
        "purposes",
        "addresses",
        "contacts",
      ].every((field) =>
        organizationContactCollection?.required?.includes(field),
      ) &&
      JSON.stringify(
        organizationContactCollection?.properties?.addressTypes?.prefixItems?.map(
          (item) => item.const,
        ),
      ) === JSON.stringify(["registered", "postal", "service", "billing"]) &&
      organizationContactPurpose?.properties?.key?.pattern ===
        "^[a-z][a-z0-9]*([._:-][a-z0-9]+)*$" &&
      organizationContactPurpose?.properties?.publicProjectionAllowed?.type ===
        "boolean",
    "OrganizationContactCollection must bind exact address types, migration-owned purposes, and live create authority",
  );
  assert(
    [
      "addressType",
      "addressLines",
      "locality",
      "region",
      "postcode",
      "countryCode",
      "validationStatus",
      "validationSource",
      "isPrimary",
      "effectiveFrom",
      "effectiveTo",
      "reason",
    ].every((field) => organizationAddressWrite?.required?.includes(field)) &&
      organizationAddressWrite?.additionalProperties === false &&
      organizationAddressWrite?.properties?.addressLines?.maxItems === 4 &&
      organizationAddressWrite?.properties?.reason?.minLength === 10 &&
      organizationAddressWrite?.properties?.reason?.maxLength === 500 &&
      [
        "channel",
        "purpose",
        "value",
        "isPrimary",
        "isPreferred",
        "effectiveFrom",
        "effectiveTo",
        "reason",
      ].every((field) => organizationContactWrite?.required?.includes(field)) &&
      organizationContactWrite?.additionalProperties === false &&
      organizationContactWrite?.properties?.value?.maxLength === 2048 &&
      organizationContactWrite?.properties?.reason?.minLength === 10 &&
      organizationContactReason?.additionalProperties === false &&
      organizationContactReason?.required?.length === 1 &&
      organizationContactReason?.required?.[0] === "reason" &&
      organizationContactReason?.properties?.reason?.maxLength === 500,
    "Address and contact mutations must require exact effective values, bounded reasons, and no response-shaped confidential field",
  );

  const schedulingRead =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/scheduling/screens/{screenId}"
    ]?.get;
  const schedulingAction =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/scheduling/screens/{screenId}/actions/{actionKey}"
    ]?.post;
  const schedulingScreen = contract.components?.schemas?.SchedulingScreen;
  const schedulingRow = contract.components?.schemas?.SchedulingRow;
  const schedulingRequest =
    contract.components?.schemas?.SchedulingActionRequest;
  const schedulingReadParameters = (schedulingRead?.parameters ?? []).map(
    resolved,
  );
  assert(
    schedulingScreen?.additionalProperties === false &&
      schedulingScreen?.properties?.screenId?.pattern ===
        "^P4-(0[1-9]|1[0-5])$" &&
      schedulingScreen?.properties?.rows?.items?.$ref ===
        "#/components/schemas/SchedulingRow" &&
      schedulingScreen?.properties?.pageSize?.maximum === 100 &&
      schedulingScreen?.required?.length === 12,
    "SchedulingScreen must expose the exact bounded P4 projection",
  );
  assert(
    schedulingRow?.additionalProperties === false &&
      schedulingRow?.properties?.id?.format === "uuid" &&
      schedulingRow?.properties?.patientId?.format === "uuid" &&
      schedulingRow?.properties?.appointmentId?.format === "uuid" &&
      schedulingRow?.properties?.revision?.minimum === 0 &&
      schedulingRow?.properties?.etag?.pattern?.startsWith('^\\"m4:P4-') &&
      schedulingRow?.properties?.allowedActionKeys?.uniqueItems === true &&
      schedulingRow?.properties?.values?.additionalProperties?.type ===
        "string",
    "SchedulingRow must bind strong M4 revisions and minimum-necessary string projections",
  );
  assert(
    schedulingRequest?.additionalProperties === false &&
      schedulingRequest?.required?.length === 1 &&
      schedulingRequest?.required?.[0] === "fields" &&
      ["targetId", "patientId", "appointmentId", "requestId"].every(
        (field) => schedulingRequest?.properties?.[field]?.format === "uuid",
      ) &&
      schedulingRequest?.properties?.reason?.maxLength === 500 &&
      schedulingRequest?.properties?.fields?.maxProperties === 64 &&
      schedulingRequest?.properties?.fields?.additionalProperties?.maxLength ===
        2000,
    "SchedulingActionRequest must keep identifiers, reasons, and dynamic fields bounded",
  );
  assert(
    ["patientId", "appointmentId", "requestId"].every((name) =>
      schedulingReadParameters.some(
        (parameter) =>
          parameter?.name === name &&
          parameter?.in === "query" &&
          parameter?.schema?.format === "uuid",
      ),
    ) &&
      schedulingReadParameters.some(
        (parameter) =>
          parameter?.name === "limit" && parameter?.schema?.maximum === 100,
      ) &&
      schedulingRead?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/SchedulingScreen" &&
      schedulingRead?.responses?.["503"]?.$ref ===
        "#/components/responses/ServiceUnavailable",
    "Scheduling reads must bind optional workflow context and fail closed on unavailable dependencies",
  );
  assert(
    schedulingAction?.requestBody?.content?.["application/json"]?.schema
      ?.$ref === "#/components/schemas/SchedulingActionRequest" &&
      schedulingAction?.responses?.["200"]?.content?.["application/json"]
        ?.schema?.$ref === "#/components/schemas/SchedulingScreen" &&
      schedulingAction?.responses?.["201"]?.content?.["application/json"]
        ?.schema?.$ref === "#/components/schemas/SchedulingScreen" &&
      schedulingAction?.responses?.["409"]?.$ref ===
        "#/components/responses/Conflict" &&
      schedulingAction?.responses?.["412"]?.$ref ===
        "#/components/responses/PreconditionFailed" &&
      schedulingAction?.responses?.["428"]?.$ref ===
        "#/components/responses/PreconditionRequired",
    "Scheduling mutations must use the checked action contract and revision-conflict responses",
  );

  const encounterRead =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/encounters/screens/{screenId}"
    ]?.get;
  const encounterAction =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/encounters/screens/{screenId}/actions/{actionKey}"
    ]?.post;
  const encounterScreen = contract.components?.schemas?.EncounterScreen;
  const encounterRow = contract.components?.schemas?.EncounterRow;
  const encounterRequest = contract.components?.schemas?.EncounterActionRequest;
  const encounterReadParameters = (encounterRead?.parameters ?? []).map(
    resolved,
  );
  assert(
    encounterScreen?.additionalProperties === false &&
      encounterScreen?.properties?.screenId?.pattern ===
        "^P5-(0[1-9]|1[0-2])$" &&
      encounterScreen?.properties?.rows?.items?.$ref ===
        "#/components/schemas/EncounterRow" &&
      encounterScreen?.properties?.pageSize?.maximum === 100 &&
      encounterScreen?.required?.length === 12,
    "EncounterScreen must expose the exact bounded P5 projection",
  );
  assert(
    encounterRow?.additionalProperties === false &&
      encounterRow?.properties?.id?.format === "uuid" &&
      ["patientId", "episodeId", "encounterId", "appointmentId"].every(
        (field) => encounterRow?.properties?.[field]?.format === "uuid",
      ) &&
      encounterRow?.properties?.revision?.minimum === 0 &&
      encounterRow?.properties?.etag?.pattern?.startsWith('^\\"m5:P5-') &&
      encounterRow?.properties?.allowedActionKeys?.uniqueItems === true &&
      encounterRow?.properties?.values?.additionalProperties?.type === "string",
    "EncounterRow must bind strong M5 revisions and minimum-necessary string projections",
  );
  assert(
    encounterRequest?.additionalProperties === false &&
      encounterRequest?.required?.length === 1 &&
      encounterRequest?.required?.[0] === "fields" &&
      [
        "targetId",
        "patientId",
        "episodeId",
        "encounterId",
        "appointmentId",
      ].every(
        (field) => encounterRequest?.properties?.[field]?.format === "uuid",
      ) &&
      encounterRequest?.properties?.reason?.maxLength === 500 &&
      encounterRequest?.properties?.fields?.maxProperties === 64 &&
      encounterRequest?.properties?.fields?.additionalProperties?.maxLength ===
        20000,
    "EncounterActionRequest must keep identifiers, reasons, and clinical fields bounded",
  );
  assert(
    ["patientId", "episodeId", "encounterId", "appointmentId"].every((name) =>
      encounterReadParameters.some(
        (parameter) =>
          parameter?.name === name &&
          parameter?.in === "query" &&
          parameter?.schema?.format === "uuid",
      ),
    ) &&
      encounterReadParameters.some(
        (parameter) =>
          parameter?.name === "limit" && parameter?.schema?.maximum === 100,
      ) &&
      encounterRead?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/EncounterScreen" &&
      encounterRead?.responses?.["503"]?.$ref ===
        "#/components/responses/ServiceUnavailable",
    "Encounter reads must bind optional clinical context and fail closed on unavailable dependencies",
  );
  assert(
    encounterAction?.requestBody?.content?.["application/json"]?.schema
      ?.$ref === "#/components/schemas/EncounterActionRequest" &&
      encounterAction?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/EncounterScreen" &&
      encounterAction?.responses?.["201"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/EncounterScreen" &&
      encounterAction?.responses?.["409"]?.$ref ===
        "#/components/responses/Conflict" &&
      encounterAction?.responses?.["412"]?.$ref ===
        "#/components/responses/PreconditionFailed" &&
      encounterAction?.responses?.["428"]?.$ref ===
        "#/components/responses/PreconditionRequired",
    "Encounter mutations must use the checked action contract and revision-conflict responses",
  );

  const assessmentRead =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/assessments/screens/{screenId}"
    ]?.get;
  const assessmentAction =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/assessments/screens/{screenId}/actions/{actionKey}"
    ]?.post;
  const assessmentScreen = contract.components?.schemas?.AssessmentScreen;
  const assessmentRow = contract.components?.schemas?.AssessmentRow;
  const assessmentRequest =
    contract.components?.schemas?.AssessmentActionRequest;
  const assessmentReadParameters = (assessmentRead?.parameters ?? []).map(
    resolved,
  );
  assert(
    assessmentScreen?.additionalProperties === false &&
      assessmentScreen?.properties?.screenId?.pattern ===
        "^COS-(0[1-9]|1[0-9]|2[0-7])$" &&
      assessmentScreen?.properties?.rows?.items?.$ref ===
        "#/components/schemas/AssessmentRow" &&
      assessmentScreen?.properties?.pageSize?.maximum === 100 &&
      assessmentScreen?.required?.length === 12,
    "AssessmentScreen must expose the exact bounded COS-01 through COS-27 projection",
  );
  assert(
    assessmentRow?.additionalProperties === false &&
      assessmentRow?.properties?.id?.format === "uuid" &&
      ["patientId", "encounterId", "assessmentSessionId"].every(
        (field) => assessmentRow?.properties?.[field]?.format === "uuid",
      ) &&
      assessmentRow?.properties?.revision?.minimum === 0 &&
      assessmentRow?.properties?.etag?.pattern?.startsWith('^\\"m6:COS-') &&
      assessmentRow?.properties?.allowedActionKeys?.uniqueItems === true &&
      assessmentRow?.properties?.values?.additionalProperties?.type ===
        "string",
    "AssessmentRow must bind strong M6 revisions and minimum-necessary clinical projections",
  );
  assert(
    assessmentRequest?.additionalProperties === false &&
      assessmentRequest?.required?.length === 1 &&
      assessmentRequest?.required?.[0] === "fields" &&
      ["targetId", "patientId", "encounterId", "assessmentSessionId"].every(
        (field) => assessmentRequest?.properties?.[field]?.format === "uuid",
      ) &&
      assessmentRequest?.properties?.reason?.maxLength === 500 &&
      assessmentRequest?.properties?.fields?.maxProperties === 64 &&
      assessmentRequest?.properties?.fields?.additionalProperties?.maxLength ===
        20000,
    "AssessmentActionRequest must keep identifiers, reasons, and clinical assessment fields bounded",
  );
  assert(
    ["patientId", "encounterId", "assessmentSessionId"].every((name) =>
      assessmentReadParameters.some(
        (parameter) =>
          parameter?.name === name &&
          parameter?.in === "query" &&
          parameter?.schema?.format === "uuid",
      ),
    ) &&
      assessmentReadParameters.some(
        (parameter) =>
          parameter?.name === "limit" && parameter?.schema?.maximum === 100,
      ) &&
      assessmentRead?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/AssessmentScreen" &&
      assessmentRead?.responses?.["503"]?.$ref ===
        "#/components/responses/ServiceUnavailable",
    "Assessment reads must bind optional clinical context and fail closed on unavailable dependencies",
  );
  assert(
    assessmentAction?.requestBody?.content?.["application/json"]?.schema
      ?.$ref === "#/components/schemas/AssessmentActionRequest" &&
      assessmentAction?.responses?.["200"]?.content?.["application/json"]
        ?.schema?.$ref === "#/components/schemas/AssessmentScreen" &&
      assessmentAction?.responses?.["201"]?.content?.["application/json"]
        ?.schema?.$ref === "#/components/schemas/AssessmentScreen" &&
      assessmentAction?.responses?.["409"]?.$ref ===
        "#/components/responses/Conflict" &&
      assessmentAction?.responses?.["412"]?.$ref ===
        "#/components/responses/PreconditionFailed" &&
      assessmentAction?.responses?.["428"]?.$ref ===
        "#/components/responses/PreconditionRequired",
    "Assessment mutations must use the checked action contract and revision-conflict responses",
  );

  const documentRead =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/documents/screens/{screenId}"
    ]?.get;
  const documentAction =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/documents/screens/{screenId}/actions/{actionKey}"
    ]?.post;
  const documentUpload =
    contract.paths?.["/api/v1/organizations/{organizationId}/documents"]?.post;
  const documentAccess =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/documents/{documentId}/accesses"
    ]?.post;
  const documentOpen =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/documents/{documentId}/versions/{documentVersionId}/accesses/{accessIntentId}"
    ]?.get;
  const documentScreen = contract.components?.schemas?.DocumentScreen;
  const documentRow = contract.components?.schemas?.DocumentRow;
  const documentRequest = contract.components?.schemas?.DocumentActionRequest;
  const documentMetadata = contract.components?.schemas?.DocumentUploadMetadata;
  const documentAccessRequest =
    contract.components?.schemas?.DocumentAccessRequest;
  const documentAccessResponse =
    contract.components?.schemas?.DocumentAccessResponse;
  const documentReadParameters = (documentRead?.parameters ?? []).map(resolved);
  assert(
    documentScreen?.additionalProperties === false &&
      documentScreen?.properties?.screenId?.pattern === "^P7-(0[1-9]|1[01])$" &&
      documentScreen?.properties?.rows?.items?.$ref ===
        "#/components/schemas/DocumentRow" &&
      documentScreen?.properties?.pageSize?.maximum === 100 &&
      documentScreen?.required?.length === 12,
    "DocumentScreen must expose the exact bounded P7-01 through P7-11 projection",
  );
  assert(
    documentRow?.additionalProperties === false &&
      documentRow?.properties?.id?.format === "uuid" &&
      [
        "patientId",
        "documentId",
        "documentVersionId",
        "diagnosticReportId",
        "resultFlagId",
      ].every((field) => documentRow?.properties?.[field]?.format === "uuid") &&
      documentRow?.properties?.revision?.minimum === 0 &&
      documentRow?.properties?.etag?.pattern?.startsWith('^\\"m7:P7-') &&
      documentRow?.properties?.allowedActionKeys?.uniqueItems === true &&
      documentRow?.properties?.values?.additionalProperties?.type === "string",
    "DocumentRow must bind strong M7 revisions and minimum-necessary document/result projections",
  );
  assert(
    documentRequest?.additionalProperties === false &&
      documentRequest?.required?.length === 1 &&
      documentRequest?.required?.[0] === "fields" &&
      [
        "targetId",
        "patientId",
        "documentId",
        "documentVersionId",
        "diagnosticReportId",
      ].every(
        (field) => documentRequest?.properties?.[field]?.format === "uuid",
      ) &&
      documentRequest?.properties?.reason?.maxLength === 500 &&
      documentRequest?.properties?.fields?.maxProperties === 64 &&
      documentRequest?.properties?.fields?.additionalProperties?.maxLength ===
        20000,
    "DocumentActionRequest must keep identifiers, reasons, and result fields bounded",
  );
  assert(
    documentMetadata?.additionalProperties === false &&
      documentMetadata?.required?.length === 6 &&
      [
        "patientId",
        "encounterId",
        "assessmentSessionId",
        "replacementDocumentId",
      ].every(
        (field) => documentMetadata?.properties?.[field]?.format === "uuid",
      ) &&
      documentMetadata?.properties?.sha256?.pattern === "^[0-9a-f]{64}$" &&
      documentMetadata?.properties?.reason?.minLength === 10 &&
      documentMetadata?.properties?.reason?.maxLength === 500,
    "Document upload metadata must bind exact patient/version context, digest, and reason",
  );
  assert(
    ["patientId", "documentId", "diagnosticReportId"].every((name) =>
      documentReadParameters.some(
        (parameter) =>
          parameter?.name === name &&
          parameter?.in === "query" &&
          parameter?.schema?.format === "uuid",
      ),
    ) &&
      documentReadParameters.some(
        (parameter) =>
          parameter?.name === "limit" && parameter?.schema?.maximum === 100,
      ) &&
      documentRead?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/DocumentScreen" &&
      documentRead?.responses?.["503"]?.$ref ===
        "#/components/responses/ServiceUnavailable",
    "Document reads must bind optional clinical context and fail closed on unavailable dependencies",
  );
  assert(
    documentAction?.requestBody?.content?.["application/json"]?.schema?.$ref ===
      "#/components/schemas/DocumentActionRequest" &&
      documentAction?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/DocumentScreen" &&
      documentAction?.responses?.["201"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/DocumentScreen" &&
      documentAction?.responses?.["409"]?.$ref ===
        "#/components/responses/Conflict" &&
      documentAction?.responses?.["412"]?.$ref ===
        "#/components/responses/PreconditionFailed" &&
      documentAction?.responses?.["428"]?.$ref ===
        "#/components/responses/PreconditionRequired",
    "Document mutations must use the checked action contract and revision-conflict responses",
  );
  const uploadForm =
    documentUpload?.requestBody?.content?.["multipart/form-data"]?.schema;
  assert(
    uploadForm?.additionalProperties === false &&
      uploadForm?.required?.length === 2 &&
      uploadForm?.properties?.metadata?.$ref ===
        "#/components/schemas/DocumentUploadMetadata" &&
      uploadForm?.properties?.file?.format === "binary" &&
      documentUpload?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/DocumentScreen" &&
      documentUpload?.responses?.["201"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/DocumentScreen" &&
      documentUpload?.responses?.["503"]?.$ref ===
        "#/components/responses/ServiceUnavailable",
    "Document upload must remain a bounded quarantine-only multipart boundary",
  );
  assert(
    documentAccessRequest?.additionalProperties === false &&
      documentAccessRequest?.required?.length === 2 &&
      documentAccessRequest?.properties?.documentVersionId?.format === "uuid" &&
      documentAccessRequest?.properties?.purposeKey?.enum?.length === 4 &&
      documentAccessResponse?.additionalProperties === false &&
      documentAccessResponse?.properties?.accessPath?.pattern?.startsWith(
        "^/api/v1/",
      ) &&
      documentAccessResponse?.properties?.byteCount?.maximum === 20971520 &&
      documentAccessResponse?.properties?.sha256?.pattern ===
        "^[0-9a-f]{64}$" &&
      documentAccess?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/DocumentAccessResponse" &&
      documentOpen?.responses?.["303"]?.headers?.Location?.schema?.format ===
        "uri",
    "Document access must expose only an actor-bound relative intent before a no-store redirect",
  );

  const aiRead =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/ai/screens/{screenId}"
    ]?.get;
  const aiAction =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/ai/screens/{screenId}/actions/{actionKey}"
    ]?.post;
  const aiScreen = contract.components?.schemas?.AiScreen;
  const aiRow = contract.components?.schemas?.AiRow;
  const aiRequest = contract.components?.schemas?.AiActionRequest;
  const aiReadParameters = (aiRead?.parameters ?? []).map(resolved);
  assert(
    aiScreen?.additionalProperties === false &&
      aiScreen?.properties?.screenId?.pattern === "^P8-(0[1-9]|10)$" &&
      aiScreen?.properties?.rows?.items?.$ref ===
        "#/components/schemas/AiRow" &&
      aiScreen?.properties?.pageSize?.maximum === 100 &&
      aiScreen?.required?.length === 12,
    "AiScreen must expose the exact bounded P8-01 through P8-10 projection",
  );
  assert(
    aiRow?.additionalProperties === false &&
      [
        "id",
        "patientId",
        "encounterId",
        "inputManifestId",
        "outputId",
        "outputVersionId",
        "safetyFlagId",
      ].every((field) => aiRow?.properties?.[field]?.format === "uuid") &&
      aiRow?.properties?.revision?.minimum === 0 &&
      aiRow?.properties?.etag?.pattern?.startsWith('^\\"m8:P8-') &&
      aiRow?.properties?.allowedActionKeys?.uniqueItems === true &&
      aiRow?.properties?.values?.additionalProperties?.type === "string",
    "AiRow must bind strong M8 revisions and minimum-necessary governance projections",
  );
  assert(
    aiRequest?.additionalProperties === false &&
      JSON.stringify(aiRequest?.required) ===
        JSON.stringify(["reason", "fields"]) &&
      aiRequest?.properties?.targetId?.format === "uuid" &&
      aiRequest?.properties?.reason?.minLength === 10 &&
      aiRequest?.properties?.reason?.maxLength === 500 &&
      aiRequest?.properties?.fields?.maxProperties === 32 &&
      aiRequest?.properties?.fields?.additionalProperties?.maxLength === 20000,
    "AiActionRequest must keep session targets, reasons, and provider fields bounded",
  );
  assert(
    ["patientId", "encounterId", "aiSessionId"].every((name) =>
      aiReadParameters.some(
        (parameter) =>
          parameter?.name === name &&
          parameter?.in === "query" &&
          parameter?.schema?.format === "uuid",
      ),
    ) &&
      aiReadParameters.some(
        (parameter) =>
          parameter?.name === "limit" && parameter?.schema?.maximum === 100,
      ) &&
      aiRead?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/AiScreen" &&
      aiRead?.responses?.["503"]?.$ref ===
        "#/components/responses/ServiceUnavailable",
    "AI reads must bind optional clinical context and fail closed on unavailable policy",
  );
  assert(
    aiAction?.requestBody?.content?.["application/json"]?.schema?.$ref ===
      "#/components/schemas/AiActionRequest" &&
      aiAction?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/AiScreen" &&
      aiAction?.responses?.["201"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/AiScreen" &&
      aiAction?.responses?.["409"]?.$ref ===
        "#/components/responses/Conflict" &&
      aiAction?.responses?.["412"]?.$ref ===
        "#/components/responses/PreconditionFailed" &&
      aiAction?.responses?.["428"]?.$ref ===
        "#/components/responses/PreconditionRequired" &&
      aiAction?.responses?.["503"]?.$ref ===
        "#/components/responses/ServiceUnavailable",
    "AI mutations must use the checked action contract and fail-closed revision responses",
  );

  const carePlanRead =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/care-plans/screens/{screenId}"
    ]?.get;
  const carePlanAction =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/care-plans/screens/{screenId}/actions/{actionKey}"
    ]?.post;
  const carePlanScreen = contract.components?.schemas?.CarePlanScreen;
  const carePlanRow = contract.components?.schemas?.CarePlanRow;
  const carePlanRequest = contract.components?.schemas?.CarePlanActionRequest;
  const carePlanReadParameters = (carePlanRead?.parameters ?? []).map(resolved);
  assert(
    carePlanScreen?.additionalProperties === false &&
      carePlanScreen?.properties?.screenId?.pattern ===
        "^P9-(0[1-9]|1[0-2])$" &&
      carePlanScreen?.properties?.rows?.items?.$ref ===
        "#/components/schemas/CarePlanRow" &&
      carePlanScreen?.properties?.pageSize?.maximum === 100 &&
      carePlanScreen?.required?.length === 12,
    "CarePlanScreen must expose the exact bounded P9-01 through P9-12 projection",
  );
  assert(
    carePlanRow?.additionalProperties === false &&
      [
        "id",
        "patientId",
        "encounterId",
        "carePlanVersionId",
        "interventionId",
        "clinicalTaskId",
      ].every((field) => carePlanRow?.properties?.[field]?.format === "uuid") &&
      carePlanRow?.properties?.revision?.minimum === 0 &&
      carePlanRow?.properties?.etag?.pattern?.startsWith('^\\"m9:P9-') &&
      carePlanRow?.properties?.allowedActionKeys?.uniqueItems === true &&
      carePlanRow?.properties?.values?.additionalProperties?.type === "string",
    "CarePlanRow must bind strong M9 revisions and minimum-necessary plan projections",
  );
  assert(
    carePlanRequest?.additionalProperties === false &&
      JSON.stringify(carePlanRequest?.required) ===
        JSON.stringify(["reason", "fields"]) &&
      carePlanRequest?.properties?.targetId?.format === "uuid" &&
      carePlanRequest?.properties?.reason?.minLength === 10 &&
      carePlanRequest?.properties?.reason?.maxLength === 500 &&
      carePlanRequest?.properties?.fields?.maxProperties === 32 &&
      carePlanRequest?.properties?.fields?.additionalProperties?.maxLength ===
        20000,
    "CarePlanActionRequest must keep plan targets, reasons, and clinical fields bounded",
  );
  assert(
    ["patientId", "encounterId", "carePlanId"].every((name) =>
      carePlanReadParameters.some(
        (parameter) =>
          parameter?.name === name &&
          parameter?.in === "query" &&
          parameter?.schema?.format === "uuid",
      ),
    ) &&
      carePlanReadParameters.some(
        (parameter) =>
          parameter?.name === "limit" && parameter?.schema?.maximum === 100,
      ) &&
      carePlanRead?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/CarePlanScreen" &&
      carePlanRead?.responses?.["503"]?.$ref ===
        "#/components/responses/ServiceUnavailable",
    "Care-plan reads must bind optional patient, encounter, and exact plan context",
  );
  assert(
    carePlanAction?.requestBody?.content?.["application/json"]?.schema?.$ref ===
      "#/components/schemas/CarePlanActionRequest" &&
      carePlanAction?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/CarePlanScreen" &&
      carePlanAction?.responses?.["201"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/CarePlanScreen" &&
      carePlanAction?.responses?.["409"]?.$ref ===
        "#/components/responses/Conflict" &&
      carePlanAction?.responses?.["412"]?.$ref ===
        "#/components/responses/PreconditionFailed" &&
      carePlanAction?.responses?.["428"]?.$ref ===
        "#/components/responses/PreconditionRequired" &&
      carePlanAction?.responses?.["503"]?.$ref ===
        "#/components/responses/ServiceUnavailable",
    "Care-plan mutations must use the checked action contract and fail-closed revision responses",
  );
  const followupRead =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/followups/screens/{screenId}"
    ]?.get;
  const followupAction =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/followups/screens/{screenId}/actions/{actionKey}"
    ]?.post;
  const followupScreen = contract.components?.schemas?.FollowupScreen;
  const followupRow = contract.components?.schemas?.FollowupRow;
  const followupRequest = contract.components?.schemas?.FollowupActionRequest;
  const followupReadParameters = (followupRead?.parameters ?? []).map(resolved);
  assert(
    followupScreen?.additionalProperties === false &&
      followupScreen?.properties?.screenId?.pattern === "^P10-0[1-9]$" &&
      followupScreen?.properties?.rows?.items?.$ref ===
        "#/components/schemas/FollowupRow" &&
      followupScreen?.properties?.pageSize?.maximum === 100 &&
      followupScreen?.required?.length === 12,
    "FollowupScreen must expose the exact bounded P10-01 through P10-09 projection",
  );
  assert(
    followupRow?.additionalProperties === false &&
      [
        "id",
        "patientId",
        "encounterId",
        "carePlanId",
        "carePlanVersionId",
        "followupPlanId",
        "followupEventId",
        "outcomeDefinitionId",
        "outcomeMeasurementId",
        "escalationEventId",
        "clinicalTaskId",
      ].every((field) => followupRow?.properties?.[field]?.format === "uuid") &&
      followupRow?.properties?.revision?.minimum === 0 &&
      followupRow?.properties?.etag?.pattern?.startsWith('^\\"m10:P10-') &&
      followupRow?.properties?.allowedActionKeys?.uniqueItems === true &&
      followupRow?.properties?.values?.additionalProperties?.type === "string",
    "FollowupRow must bind strong M10 revisions and minimum-necessary outcome projections",
  );
  assert(
    followupRequest?.additionalProperties === false &&
      JSON.stringify(followupRequest?.required) ===
        JSON.stringify(["reason", "fields"]) &&
      followupRequest?.properties?.targetId?.format === "uuid" &&
      followupRequest?.properties?.reason?.minLength === 10 &&
      followupRequest?.properties?.reason?.maxLength === 500 &&
      followupRequest?.properties?.fields?.maxProperties === 32 &&
      followupRequest?.properties?.fields?.additionalProperties?.maxLength ===
        20000,
    "FollowupActionRequest must keep outcome targets, reasons, and measurement fields bounded",
  );
  assert(
    ["patientId", "encounterId", "followupPlanId"].every((name) =>
      followupReadParameters.some(
        (parameter) =>
          parameter?.name === name &&
          parameter?.in === "query" &&
          parameter?.schema?.format === "uuid",
      ),
    ) &&
      followupReadParameters.some(
        (parameter) =>
          parameter?.name === "limit" && parameter?.schema?.maximum === 100,
      ) &&
      followupRead?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/FollowupScreen" &&
      followupRead?.responses?.["503"]?.$ref ===
        "#/components/responses/ServiceUnavailable",
    "Follow-up reads must bind optional patient, encounter, and exact monitoring-plan context",
  );
  assert(
    followupAction?.requestBody?.content?.["application/json"]?.schema?.$ref ===
      "#/components/schemas/FollowupActionRequest" &&
      followupAction?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/FollowupScreen" &&
      followupAction?.responses?.["201"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/FollowupScreen" &&
      followupAction?.responses?.["409"]?.$ref ===
        "#/components/responses/Conflict" &&
      followupAction?.responses?.["412"]?.$ref ===
        "#/components/responses/PreconditionFailed" &&
      followupAction?.responses?.["428"]?.$ref ===
        "#/components/responses/PreconditionRequired" &&
      followupAction?.responses?.["503"]?.$ref ===
        "#/components/responses/ServiceUnavailable",
    "Follow-up mutations must use the checked action contract and fail-closed revision responses",
  );
  const billingRead =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/billing/screens/{screenId}"
    ]?.get;
  const billingAction =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/billing/screens/{screenId}/actions/{actionKey}"
    ]?.post;
  const billingScreen = contract.components?.schemas?.BillingScreen;
  const billingRow = contract.components?.schemas?.BillingRow;
  const billingRequest = contract.components?.schemas?.BillingActionRequest;
  const billingReadParameters = (billingRead?.parameters ?? []).map(resolved);
  assert(
    billingScreen?.additionalProperties === false &&
      billingScreen?.properties?.screenId?.pattern === "^P11-(0[1-9]|1[01])$" &&
      billingScreen?.properties?.rows?.items?.$ref ===
        "#/components/schemas/BillingRow" &&
      billingScreen?.properties?.pageSize?.maximum === 100 &&
      billingScreen?.required?.length === 12,
    "BillingScreen must expose the exact bounded P11-01 through P11-11 projection",
  );
  assert(
    billingRow?.additionalProperties === false &&
      [
        "id",
        "patientId",
        "appointmentId",
        "encounterId",
        "priceBookId",
        "packageId",
        "estimateId",
        "invoiceId",
        "paymentIntentId",
        "paymentId",
        "refundId",
        "adjustmentId",
        "claimId",
        "remittanceId",
        "reconciliationId",
        "financialExportId",
      ].every((field) => billingRow?.properties?.[field]?.format === "uuid") &&
      billingRow?.properties?.revision?.minimum === 0 &&
      billingRow?.properties?.etag?.pattern?.startsWith('^\\"m11:P11-') &&
      billingRow?.properties?.allowedActionKeys?.uniqueItems === true &&
      billingRow?.properties?.values?.additionalProperties?.type === "string",
    "BillingRow must bind strong M11 revisions and minimum-necessary financial projections",
  );
  assert(
    billingRequest?.additionalProperties === false &&
      JSON.stringify(billingRequest?.required) ===
        JSON.stringify(["reason", "fields"]) &&
      billingRequest?.properties?.targetId?.format === "uuid" &&
      billingRequest?.properties?.reason?.minLength === 10 &&
      billingRequest?.properties?.reason?.maxLength === 500 &&
      billingRequest?.properties?.fields?.maxProperties === 32 &&
      billingRequest?.properties?.fields?.additionalProperties?.maxLength ===
        20000,
    "BillingActionRequest must keep financial targets, reasons, and evidence fields bounded",
  );
  assert(
    ["patientId", "invoiceId"].every((name) =>
      billingReadParameters.some(
        (parameter) =>
          parameter?.name === name &&
          parameter?.in === "query" &&
          parameter?.schema?.format === "uuid",
      ),
    ) &&
      billingReadParameters.some(
        (parameter) =>
          parameter?.name === "limit" && parameter?.schema?.maximum === 100,
      ) &&
      billingRead?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/BillingScreen" &&
      billingRead?.responses?.["503"]?.$ref ===
        "#/components/responses/ServiceUnavailable",
    "Billing reads must bind optional patient and exact invoice context",
  );
  assert(
    billingAction?.requestBody?.content?.["application/json"]?.schema?.$ref ===
      "#/components/schemas/BillingActionRequest" &&
      billingAction?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/BillingScreen" &&
      billingAction?.responses?.["201"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/BillingScreen" &&
      billingAction?.responses?.["409"]?.$ref ===
        "#/components/responses/Conflict" &&
      billingAction?.responses?.["412"]?.$ref ===
        "#/components/responses/PreconditionFailed" &&
      billingAction?.responses?.["428"]?.$ref ===
        "#/components/responses/PreconditionRequired" &&
      billingAction?.responses?.["503"]?.$ref ===
        "#/components/responses/ServiceUnavailable",
    "Billing mutations must use the checked action contract and fail-closed revision responses",
  );
  const reportingRead =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/reporting/screens/{screenId}"
    ]?.get;
  const reportingAction =
    contract.paths?.[
      "/api/v1/organizations/{organizationId}/reporting/screens/{screenId}/actions/{actionKey}"
    ]?.post;
  const reportingScreen = contract.components?.schemas?.ReportingScreen;
  const reportingRow = contract.components?.schemas?.ReportingRow;
  const reportingRequest = contract.components?.schemas?.ReportingActionRequest;
  const reportingReadParameters = (reportingRead?.parameters ?? []).map(
    resolved,
  );
  assert(
    reportingScreen?.additionalProperties === false &&
      reportingScreen?.properties?.screenId?.pattern === "^P12-(0[1-9]|10)$" &&
      reportingScreen?.properties?.rows?.items?.$ref ===
        "#/components/schemas/ReportingRow" &&
      reportingScreen?.properties?.pageSize?.maximum === 100 &&
      reportingScreen?.required?.length === 12,
    "ReportingScreen must expose the exact bounded P12-01 through P12-10 projection",
  );
  assert(
    reportingRow?.additionalProperties === false &&
      ["id", "reportRunId", "reportScheduleId", "reportExportId"].every(
        (field) => reportingRow?.properties?.[field]?.format === "uuid",
      ) &&
      reportingRow?.properties?.revision?.minimum === 0 &&
      reportingRow?.properties?.etag?.pattern?.startsWith('^\\"m12:P12-') &&
      reportingRow?.properties?.allowedActionKeys?.uniqueItems === true &&
      reportingRow?.properties?.values?.additionalProperties?.type ===
        "string" &&
      reportingRow?.properties?.patientId === undefined &&
      reportingRow?.properties?.clinicalNarrative === undefined,
    "ReportingRow must bind strong M12 revisions and aggregate-only identifiers",
  );
  assert(
    reportingRequest?.additionalProperties === false &&
      JSON.stringify(reportingRequest?.required) ===
        JSON.stringify(["reason", "fields"]) &&
      reportingRequest?.properties?.targetId?.format === "uuid" &&
      reportingRequest?.properties?.reason?.minLength === 10 &&
      reportingRequest?.properties?.reason?.maxLength === 500 &&
      reportingRequest?.properties?.fields?.maxProperties === 32 &&
      reportingRequest?.properties?.fields?.additionalProperties?.maxLength ===
        20000,
    "ReportingActionRequest must keep targets, reasons, and parameters bounded",
  );
  assert(
    reportingReadParameters.some(
      (parameter) =>
        parameter?.name === "reportRunId" &&
        parameter?.in === "query" &&
        parameter?.schema?.format === "uuid",
    ) &&
      reportingReadParameters.some(
        (parameter) =>
          parameter?.name === "limit" && parameter?.schema?.maximum === 100,
      ) &&
      reportingRead?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/ReportingScreen" &&
      reportingRead?.responses?.["503"]?.$ref ===
        "#/components/responses/ServiceUnavailable",
    "Reporting reads must bind optional exact-run context and bounded pagination",
  );
  assert(
    reportingAction?.requestBody?.content?.["application/json"]?.schema
      ?.$ref === "#/components/schemas/ReportingActionRequest" &&
      reportingAction?.responses?.["200"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/ReportingScreen" &&
      reportingAction?.responses?.["201"]?.content?.["application/json"]?.schema
        ?.$ref === "#/components/schemas/ReportingScreen" &&
      reportingAction?.responses?.["409"]?.$ref ===
        "#/components/responses/Conflict" &&
      reportingAction?.responses?.["412"]?.$ref ===
        "#/components/responses/PreconditionFailed" &&
      reportingAction?.responses?.["428"]?.$ref ===
        "#/components/responses/PreconditionRequired" &&
      reportingAction?.responses?.["503"]?.$ref ===
        "#/components/responses/ServiceUnavailable",
    "Reporting mutations must use the checked action contract and fail-closed revision responses",
  );
  for (const response of [
    contract.paths?.["/api/v1/auth/session"]?.get?.responses?.["200"],
    contract.components?.responses?.AuthenticatedSession,
    contract.components?.responses?.MfaPendingSession,
  ]) {
    assert(
      resolved(response)?.headers?.["X-CareOS-Session-Expires-In"]?.$ref ===
        "#/components/headers/SessionExpiresIn",
      "Authenticated session responses must declare the session-expiry signal",
    );
  }

  const tenantPrefix = `${conventions.tenantPathPrefix}/`;
  for (const [path, pathItem] of Object.entries(contract.paths ?? {})) {
    if (!path.startsWith(tenantPrefix)) {
      continue;
    }
    for (const method of ["get", "post", "put", "patch", "delete"]) {
      const operation = pathItem[method];
      if (!operation) {
        continue;
      }
      const parameters = [
        ...(pathItem.parameters ?? []),
        ...(operation.parameters ?? []),
      ].map(resolved);
      assert(
        parameters.some(
          (parameter) =>
            parameter.name === "organizationId" && parameter.in === "path",
        ),
        `${method.toUpperCase()} ${path} must declare OrganizationId`,
      );
    }
  }

  verifyReferences(contract);

  return {
    contract: "contracts/openapi/careos-foundation.json",
    openapi: contract.openapi,
    operations: expectedOperations.length,
    conventions: 7,
    status: "PASS",
  };
}

const invokedModule = process.argv[1]
  ? pathToFileURL(resolvePath(process.argv[1])).href
  : undefined;

if (import.meta.url === invokedModule) {
  const contract = JSON.parse(
    readFileSync(
      new URL("../contracts/openapi/careos-foundation.json", import.meta.url),
      "utf8",
    ),
  );
  console.log(JSON.stringify(verifyApiContract(contract), null, 2));
}
