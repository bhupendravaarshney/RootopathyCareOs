import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  ACCEPTED_M2_RECORD_ID,
  CANDIDATE_STATUS,
  DECISION_FAMILIES,
  MODULE_3_ENTITIES,
  PRODUCT_ACCEPTANCE_RECORD_ID,
  PRODUCT_DEFINITION_SHA256,
  validateCandidateContent,
  verifyModule3CandidateInputs,
} from "../verify-module-3-candidate-inputs.mjs";

const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
const checkedManifest = JSON.parse(
  readFileSync(
    new URL("../../contracts/module-3-candidate-inputs.json", import.meta.url),
    "utf8",
  ),
);

function cloneManifest() {
  return structuredClone(checkedManifest);
}

test("accepts the complete M3 candidate without authorizing implementation", () => {
  const result = verifyModule3CandidateInputs(cloneManifest(), {
    rootDirectory: repositoryRoot,
  });
  assert.equal(result.status, CANDIDATE_STATUS);
  assert.equal(result.acceptedModule2RecordId, ACCEPTED_M2_RECORD_ID);
  assert.equal(
    result.acceptedProductDefinitionSha256,
    PRODUCT_DEFINITION_SHA256,
  );
  assert.equal(result.productAcceptanceRecordId, PRODUCT_ACCEPTANCE_RECORD_ID);
  assert.equal(result.screensCovered, 16);
  assert.equal(result.entitiesCovered, 14);
  assert.equal(result.decisionFamiliesCovered, 15);
  assert.equal(result.artifactsVerified, 8);
  assert.match(result.candidatePackageSha256, /^[0-9a-f]{64}$/);
  assert.equal(result.implementationAuthorized, false);
});

test("rejects any attempt to label the candidate approved", () => {
  const candidate = cloneManifest();
  candidate.status = "APPROVED";
  assert.throws(
    () =>
      verifyModule3CandidateInputs(candidate, {
        rootDirectory: repositoryRoot,
      }),
    /status must be CANDIDATE_FOR_APPROVAL/,
  );
});

test("binds the exact predecessor, product definition, acceptance, and build specification", () => {
  const wrongM2 = cloneManifest();
  wrongM2.basis.acceptedModule2.recordId = "M2-WRONG";
  assert.throws(
    () =>
      verifyModule3CandidateInputs(wrongM2, { rootDirectory: repositoryRoot }),
    /recordId must be/,
  );

  const wrongProduct = cloneManifest();
  wrongProduct.basis.acceptedProductDefinition.sha256 = "0".repeat(64);
  assert.throws(
    () =>
      verifyModule3CandidateInputs(wrongProduct, {
        rootDirectory: repositoryRoot,
      }),
    /acceptedProductDefinition.sha256 is incorrect/,
  );

  const wrongAcceptance = cloneManifest();
  wrongAcceptance.basis.productAcceptance.sha256 = "0".repeat(64);
  assert.throws(
    () =>
      verifyModule3CandidateInputs(wrongAcceptance, {
        rootDirectory: repositoryRoot,
      }),
    /productAcceptance.sha256 is incorrect/,
  );

  const wrongSpecification = cloneManifest();
  wrongSpecification.basis.buildSpecification.sha256 = "0".repeat(64);
  assert.throws(
    () =>
      verifyModule3CandidateInputs(wrongSpecification, {
        rootDirectory: repositoryRoot,
      }),
    /build specification declared SHA-256 is incorrect/,
  );
});

test("requires all eight canonical artifacts in exact order and version", () => {
  const missing = cloneManifest();
  missing.artifacts.pop();
  assert.throws(
    () =>
      verifyModule3CandidateInputs(missing, { rootDirectory: repositoryRoot }),
    /exactly 8 artifacts/,
  );

  const reordered = cloneManifest();
  [reordered.artifacts[0], reordered.artifacts[1]] = [
    reordered.artifacts[1],
    reordered.artifacts[0],
  ];
  assert.throws(
    () =>
      verifyModule3CandidateInputs(reordered, {
        rootDirectory: repositoryRoot,
      }),
    /kind must be screen-mockups/,
  );

  const drift = cloneManifest();
  drift.artifacts[0].version = "m3-candidate-2";
  assert.throws(
    () =>
      verifyModule3CandidateInputs(drift, { rootDirectory: repositoryRoot }),
    /version must match candidateVersion/,
  );
});

test("keeps every artifact inside the Module 3 candidate directory", () => {
  for (const path of [
    "candidate-inputs/module-2/01-screen-mockups.html",
    "approved-inputs/module-3/01-screen-mockups.html",
    "candidate-inputs/module-3/../module-2/01-screen-mockups.html",
  ]) {
    const candidate = cloneManifest();
    candidate.artifacts[0].path = path;
    assert.throws(
      () =>
        verifyModule3CandidateInputs(candidate, {
          rootDirectory: repositoryRoot,
        }),
      /path must stay in the Module 3 candidate directory/,
    );
  }
});

test("requires candidate metadata and approval boundary sections", () => {
  const source = readFileSync(
    new URL(
      "../../candidate-inputs/module-3/02-design-system.md",
      import.meta.url,
    ),
    "utf8",
  );
  assert.throws(
    () =>
      validateCandidateContent(
        "design-system",
        source.replace(CANDIDATE_STATUS, "APPROVED"),
      ),
    /status must remain CANDIDATE_FOR_APPROVAL/,
  );
  assert.throws(
    () =>
      validateCandidateContent(
        "design-system",
        source.replace("## Approval boundary", "## Approved"),
      ),
    /required section is missing/,
  );
});

test("requires all 16 screens and review states in the interactive mockup", () => {
  const source = readFileSync(
    new URL(
      "../../candidate-inputs/module-3/01-screen-mockups.html",
      import.meta.url,
    ),
    "utf8",
  );
  assert.throws(
    () =>
      validateCandidateContent(
        "screen-mockups",
        source.replaceAll("P3-16", "P3-XX"),
      ),
    /screen coverage is missing: P3-16/,
  );
  assert.throws(
    () =>
      validateCandidateContent(
        "screen-mockups",
        source.replace('value="dependency-failure"', 'value="missing-state"'),
      ),
    /required state is missing: dependency-failure/,
  );
});

test("keeps the interactive mockup self-contained and non-persistent", () => {
  const source = readFileSync(
    new URL(
      "../../candidate-inputs/module-3/01-screen-mockups.html",
      import.meta.url,
    ),
    "utf8",
  );
  for (const unsafe of [
    '<script src="https://example.test/review.js"></script>',
    "fetch('/api/v1/patients')",
    "localStorage.setItem('patient', 'test')",
  ]) {
    assert.throws(
      () => validateCandidateContent("screen-mockups", `${source}\n${unsafe}`),
      /must not use/,
    );
  }
});

test("requires all 14 exact core entities", () => {
  const source = readFileSync(
    new URL(
      "../../candidate-inputs/module-3/03-data-dictionary-and-validation.md",
      import.meta.url,
    ),
    "utf8",
  );
  assert.equal(MODULE_3_ENTITIES.length, 14);
  assert.throws(
    () =>
      validateCandidateContent(
        "data-dictionary-and-validation",
        source.replaceAll("`patient_registration_runs`", "`missing_entity`"),
      ),
    /entity is missing: patient_registration_runs/,
  );
});

test("binds all fifteen accepted product decision families", () => {
  assert.equal(DECISION_FAMILIES.length, 15);
  const product = readFileSync(
    new URL("../../docs/MODULE_3_PRODUCT_DEFINITION.md", import.meta.url),
    "utf8",
  );
  for (const [index, family] of DECISION_FAMILIES.entries()) {
    assert.ok(product.includes(`### ${index + 1}. ${family}`));
  }
});

test("requires explicit relationship-authority separation and profile-first FHIR", () => {
  const authorization = readFileSync(
    new URL(
      "../../candidate-inputs/module-3/05-authorization-policy.md",
      import.meta.url,
    ),
    "utf8",
  );
  assert.throws(
    () =>
      validateCandidateContent(
        "authorization-policy",
        authorization.replaceAll(
          "Relationship alone denies",
          "Relationship grants access",
        ),
      ),
    /required candidate topic is missing: relationship alone denies/,
  );

  const history = readFileSync(
    new URL(
      "../../candidate-inputs/module-3/08-history-and-export-policy.md",
      import.meta.url,
    ),
    "utf8",
  );
  assert.throws(
    () =>
      validateCandidateContent(
        "history-and-export-policy",
        history.replaceAll(
          "FHIR is an external representation",
          "FHIR is the internal schema",
        ),
      ),
    /required candidate topic is missing: fhir is an external representation/,
  );
});
