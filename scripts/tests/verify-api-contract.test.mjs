import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { verifyApiContract } from "../verify-api-contract.mjs";

const source = JSON.parse(
  readFileSync(
    new URL("../../contracts/openapi/careos-foundation.json", import.meta.url),
    "utf8",
  ),
);

function changed(mutator) {
  const contract = structuredClone(source);
  mutator(contract);
  return contract;
}

test("accepts the checked foundation contract", () => {
  assert.equal(verifyApiContract(source).status, "PASS");
});

test("rejects an ambiguous protected tenant path", () => {
  const contract = changed((candidate) => {
    candidate["x-careos-conventions"].tenantPathPrefix = "/api/v1";
  });

  assert.throws(
    () => verifyApiContract(contract),
    /organization tenant path prefix/,
  );
});

test("rejects filters that do not fail closed", () => {
  const contract = changed((candidate) => {
    candidate["x-careos-conventions"].filtering.unknownParameters = "ignore";
  });

  assert.throws(() => verifyApiContract(contract), /reject unknown keys/);
});

test("rejects automatic mutation retries", () => {
  const contract = changed((candidate) => {
    candidate["x-careos-conventions"].retry.automaticMutationRetry = true;
  });

  assert.throws(() => verifyApiContract(contract), /disabled for mutations/);
});

test("rejects session polling that would defeat idle expiry", () => {
  const contract = changed((candidate) => {
    candidate["x-careos-conventions"].sessionLifecycle.backgroundPolling = true;
  });

  assert.throws(
    () => verifyApiContract(contract),
    /deadline without background polling/,
  );
});

test("rejects a weakened concurrency precondition", () => {
  const contract = changed((candidate) => {
    candidate.components.parameters.IfMatch.required = false;
  });

  assert.throws(
    () => verifyApiContract(contract),
    /required strong entity-tag format/,
  );
});

test("rejects readiness catalogue drift", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.ReadinessGate.properties.key.enum[0] =
      "organization.profile.approximate";
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact approved ordered keys/,
  );
});

test("rejects organization profile contract drift", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.OrganizationProfileUpdateRequest.properties.organizationType.enum =
      ["provider", "network"];
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact approved mutable profile contract/,
  );
});

test("rejects dangling local references", () => {
  const contract = changed((candidate) => {
    candidate.components.responses.InternalError.content[
      "application/problem+json"
    ].schema.$ref = "#/components/schemas/RemovedProblem";
  });

  assert.throws(() => verifyApiContract(contract), /Unresolved reference/);
});
