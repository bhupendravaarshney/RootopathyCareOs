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

test("rejects a missing Module 2 workforce operation", () => {
  const contract = changed((candidate) => {
    delete candidate.paths[
      "/api/v1/organizations/{organizationId}/workforce/screens/{screenId}"
    ].get;
  });

  assert.throws(
    () => verifyApiContract(contract),
    /Missing GET .*workforce\/screens\/\{screenId\}/,
  );
});

test("rejects an operation outside the checked registry", () => {
  const contract = changed((candidate) => {
    candidate.paths["/api/public/unregistered"] = {
      get: structuredClone(candidate.paths["/api/public/system-summary"].get),
    };
  });

  assert.throws(
    () => verifyApiContract(contract),
    /operations must exactly match the checked registry.*unregistered/,
  );
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

test("rejects organization identifier lifecycle drift", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.OrganizationIdentifier.properties.status.enum =
      ["draft", "verified", "active", "deleted"];
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact approved governed fields, lifecycle, and live actions/,
  );
});

test("rejects arbitrary international settings format patterns", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.InternationalSettingsScheduleRequest.properties.formatPattern = {
      type: "string",
    };
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact approved future settings contract/,
  );
});

test("rejects raw governance escalation values in response projections", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.GovernanceResponsibility.properties.escalationEmail = {
      type: "string",
    };
  });
  assert.throws(
    () => verifyApiContract(contract),
    /approved confidential effective projection/,
  );
});

test("rejects organization identifier supersession revision drift", () => {
  const contract = changed((candidate) => {
    delete candidate.components.schemas
      .OrganizationIdentifierSupersessionRequest.properties.replacementEtag;
  });

  assert.throws(() => verifyApiContract(contract), /replacement revisions/);
});

test("rejects a raw confidential contact value in the response projection", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.OrganizationContact.properties.value = {
      type: "string",
    };
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact approved masked effective projection/,
  );
});

test("rejects Module 4 screen-range drift", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.SchedulingScreen.properties.screenId.pattern =
      "^P4-[0-9]{2}$";
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact bounded P4 projection/,
  );
});

test("rejects unbounded Module 4 action fields", () => {
  const contract = changed((candidate) => {
    delete candidate.components.schemas.SchedulingActionRequest.properties
      .fields.maxProperties;
  });

  assert.throws(
    () => verifyApiContract(contract),
    /identifiers, reasons, and dynamic fields bounded/,
  );
});

test("rejects Module 5 screen-range drift", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.EncounterScreen.properties.screenId.pattern =
      "^P5-[0-9]{2}$";
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact bounded P5 projection/,
  );
});

test("rejects unbounded Module 5 clinical fields", () => {
  const contract = changed((candidate) => {
    delete candidate.components.schemas.EncounterActionRequest.properties
      .fields.additionalProperties.maxLength;
  });

  assert.throws(
    () => verifyApiContract(contract),
    /clinical fields bounded/,
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
