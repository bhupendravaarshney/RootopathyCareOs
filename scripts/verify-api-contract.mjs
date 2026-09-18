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
]);

const idempotentOperations = new Set([
  "updateOrganizationProfile",
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
