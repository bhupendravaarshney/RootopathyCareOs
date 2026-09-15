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
  "issueInvitation",
  "revokeInvitation",
  "requestMfaAdministrativeReset",
  "approveMfaAdministrativeReset",
  "executeMfaAdministrativeReset",
]);

const idempotentOperations = new Set([
  "issueInvitation",
  "revokeInvitation",
  "requestMfaAdministrativeReset",
  "approveMfaAdministrativeReset",
  "executeMfaAdministrativeReset",
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

    if (method === "post" && path.startsWith("/api/v1/")) {
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
