import { readFileSync } from "node:fs";

const contract = JSON.parse(
  readFileSync(
    new URL("../contracts/openapi/careos-foundation.json", import.meta.url),
    "utf8",
  ),
);

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function resolveReference(reference) {
  assert(
    reference.startsWith("#/"),
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

const expectedOperations = [
  ["get", "/api/public/prototype-screens", "listPrototypeScreens"],
  ["get", "/api/public/system-summary", "getSystemSummary"],
  ["get", "/api/v1/auth/csrf", "issueCsrfToken"],
  ["get", "/api/v1/auth/session", "getAuthenticationSession"],
  ["post", "/api/v1/auth/login", "login"],
  ["post", "/api/v1/auth/logout", "logout"],
  ["post", "/api/v1/auth/password-reset-requests", "requestPasswordReset"],
  ["post", "/api/v1/auth/password-resets", "completePasswordReset"],
  ["post", "/api/v1/auth/mfa/enrollments", "startMfaEnrollment"],
  ["post", "/api/v1/auth/mfa/enrollments/verification", "verifyMfaEnrollment"],
  ["post", "/api/v1/auth/mfa/challenges", "completeMfaChallenge"],
  ["post", "/api/v1/auth/recent-authentications", "verifyRecentAuthentication"],
  ["post", "/api/v1/auth/mfa/recovery-codes", "regenerateRecoveryCodes"],
  ["get", "/api/v1/organizations", "listSelectableOrganizations"],
  ["post", "/api/v1/auth/organization-selections", "selectOrganization"],
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
]);

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

  if (method === "post" && path.startsWith("/api/v1/auth/")) {
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

verifyReferences(contract);

console.log(
  JSON.stringify(
    {
      contract: "contracts/openapi/careos-foundation.json",
      openapi: contract.openapi,
      operations: expectedOperations.length,
      status: "PASS",
    },
    null,
    2,
  ),
);
