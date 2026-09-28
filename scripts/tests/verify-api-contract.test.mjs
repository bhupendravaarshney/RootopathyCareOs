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

test("rejects prototype catalogue count or module drift", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.SystemSummary.properties.screenCount.const = 122;
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact 195-screen M1 through M13 registry/,
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
    candidate.components.schemas.InternationalSettingsScheduleRequest.properties.formatPattern =
      {
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
    candidate.components.schemas.GovernanceResponsibility.properties.escalationEmail =
      {
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
    delete candidate.components.schemas.EncounterActionRequest.properties.fields
      .additionalProperties.maxLength;
  });

  assert.throws(() => verifyApiContract(contract), /clinical fields bounded/);
});

test("rejects Module 6 screen-range drift", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.AssessmentScreen.properties.screenId.pattern =
      "^COS-[0-9]{2}$";
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact bounded COS-01 through COS-27 projection/,
  );
});

test("rejects unbounded Module 6 clinical assessment fields", () => {
  const contract = changed((candidate) => {
    delete candidate.components.schemas.AssessmentActionRequest.properties
      .fields.additionalProperties.maxLength;
  });

  assert.throws(
    () => verifyApiContract(contract),
    /clinical assessment fields bounded/,
  );
});

test("rejects Module 7 screen-range drift", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.DocumentScreen.properties.screenId.pattern =
      "^P7-[0-9]{2}$";
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact bounded P7-01 through P7-11 projection/,
  );
});

test("rejects unbounded Module 7 result fields", () => {
  const contract = changed((candidate) => {
    delete candidate.components.schemas.DocumentActionRequest.properties.fields
      .additionalProperties.maxLength;
  });

  assert.throws(() => verifyApiContract(contract), /result fields bounded/);
});

test("rejects a bearer URL in the Module 7 access response", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.DocumentAccessResponse.properties.accessPath.pattern =
      "^https://";
  });

  assert.throws(
    () => verifyApiContract(contract),
    /actor-bound relative intent/,
  );
});

test("rejects Module 8 screen-range drift", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.AiScreen.properties.screenId.pattern =
      "^P8-[0-9]{2}$";
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact bounded P8-01 through P8-10 projection/,
  );
});

test("rejects unbounded Module 8 provider fields", () => {
  const contract = changed((candidate) => {
    delete candidate.components.schemas.AiActionRequest.properties.fields
      .additionalProperties.maxLength;
  });

  assert.throws(() => verifyApiContract(contract), /provider fields bounded/);
});

test("rejects Module 9 screen-range drift", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.CarePlanScreen.properties.screenId.pattern =
      "^P9-[0-9]{2}$";
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact bounded P9-01 through P9-12 projection/,
  );
});

test("rejects unbounded Module 9 clinical fields", () => {
  const contract = changed((candidate) => {
    delete candidate.components.schemas.CarePlanActionRequest.properties.fields
      .additionalProperties.maxLength;
  });

  assert.throws(() => verifyApiContract(contract), /clinical fields bounded/);
});

test("rejects Module 10 screen-range drift", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.FollowupScreen.properties.screenId.pattern =
      "^P10-[0-9]{2}$";
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact bounded P10-01 through P10-09 projection/,
  );
});

test("rejects unbounded Module 10 measurement fields", () => {
  const contract = changed((candidate) => {
    delete candidate.components.schemas.FollowupActionRequest.properties.fields
      .additionalProperties.maxLength;
  });

  assert.throws(
    () => verifyApiContract(contract),
    /measurement fields bounded/,
  );
});

test("rejects Module 11 screen-range drift", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.BillingScreen.properties.screenId.pattern =
      "^P11-[0-9]{2}$";
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact bounded P11-01 through P11-11 projection/,
  );
});

test("rejects unbounded Module 11 financial evidence fields", () => {
  const contract = changed((candidate) => {
    delete candidate.components.schemas.BillingActionRequest.properties.fields
      .additionalProperties.maxLength;
  });

  assert.throws(
    () => verifyApiContract(contract),
    /financial targets, reasons, and evidence fields bounded/,
  );
});

test("rejects Module 12 screen-range drift", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.ReportingScreen.properties.screenId.pattern =
      "^P12-[0-9]{2}$";
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact bounded P12-01 through P12-10 projection/,
  );
});

test("rejects source-record identifiers in Module 12 reporting rows", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.ReportingRow.properties.patientId = {
      type: ["string", "null"],
      format: "uuid",
    };
  });

  assert.throws(
    () => verifyApiContract(contract),
    /aggregate-only identifiers/,
  );
});

test("rejects Module 13 screen-range drift", () => {
  const contract = changed((candidate) => {
    candidate.components.schemas.IntegrationScreen.properties.screenId.pattern =
      "^P13-[0-9]{2}$";
  });

  assert.throws(
    () => verifyApiContract(contract),
    /exact bounded P13-01 through P13-10 projection/,
  );
});

test("rejects secret or payload fields in Module 13 integration actions", () => {
  const contract = changed((candidate) => {
    delete candidate.components.schemas.IntegrationActionRequest.properties
      .fields.propertyNames;
  });

  assert.throws(
    () => verifyApiContract(contract),
    /reject secret and payload field names/,
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
