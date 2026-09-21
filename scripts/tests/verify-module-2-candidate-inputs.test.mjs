import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  ACCEPTED_M1_COMMIT,
  CANDIDATE_STATUS,
  MODULE_2_TABLES,
  validateCandidateContent,
  verifyModule2CandidateInputs,
} from "../verify-module-2-candidate-inputs.mjs";

const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
const checkedManifest = JSON.parse(
  readFileSync(new URL("../../contracts/module-2-candidate-inputs.json", import.meta.url), "utf8"),
);

function cloneManifest() {
  return structuredClone(checkedManifest);
}

test("accepts the complete M2 candidate without authorizing implementation", () => {
  const result = verifyModule2CandidateInputs(cloneManifest(), { rootDirectory: repositoryRoot });
  assert.equal(result.status, CANDIDATE_STATUS);
  assert.equal(result.acceptedModule1Commit, ACCEPTED_M1_COMMIT);
  assert.equal(result.screensCovered, 29);
  assert.equal(result.tablesCovered, 44);
  assert.equal(result.artifactsVerified, 8);
  assert.match(result.candidatePackageSha256, /^[0-9a-f]{64}$/);
  assert.equal(result.implementationAuthorized, false);
});

test("rejects any attempt to label the candidate approved", () => {
  const candidate = cloneManifest();
  candidate.status = "APPROVED";
  assert.throws(
    () => verifyModule2CandidateInputs(candidate, { rootDirectory: repositoryRoot }),
    /status must be CANDIDATE_FOR_APPROVAL/,
  );
});

test("binds the exact accepted Module 1 commit and build specification", () => {
  const wrongCommit = cloneManifest();
  wrongCommit.basis.acceptedModule1Commit = "0".repeat(40);
  assert.throws(
    () => verifyModule2CandidateInputs(wrongCommit, { rootDirectory: repositoryRoot }),
    /acceptedModule1Commit must be/,
  );

  const wrongSpecification = cloneManifest();
  wrongSpecification.basis.buildSpecification.sha256 = "0".repeat(64);
  assert.throws(
    () => verifyModule2CandidateInputs(wrongSpecification, { rootDirectory: repositoryRoot }),
    /declared SHA-256 is incorrect/,
  );
});

test("requires all eight canonical artifacts in exact order and version", () => {
  const missing = cloneManifest();
  missing.artifacts.pop();
  assert.throws(
    () => verifyModule2CandidateInputs(missing, { rootDirectory: repositoryRoot }),
    /exactly 8 artifacts/,
  );

  const reordered = cloneManifest();
  [reordered.artifacts[0], reordered.artifacts[1]] = [reordered.artifacts[1], reordered.artifacts[0]];
  assert.throws(
    () => verifyModule2CandidateInputs(reordered, { rootDirectory: repositoryRoot }),
    /kind must be screen-mockups/,
  );

  const drift = cloneManifest();
  drift.artifacts[0].version = "m2-candidate-2";
  assert.throws(
    () => verifyModule2CandidateInputs(drift, { rootDirectory: repositoryRoot }),
    /version must match candidateVersion/,
  );
});

test("keeps every artifact inside the Module 2 candidate directory", () => {
  for (const path of [
    "candidate-inputs/module-1/01-screen-mockups.html",
    "approved-inputs/module-2/01-screen-mockups.html",
    "candidate-inputs/module-2/../module-1/01-screen-mockups.html",
  ]) {
    const candidate = cloneManifest();
    candidate.artifacts[0].path = path;
    assert.throws(
      () => verifyModule2CandidateInputs(candidate, { rootDirectory: repositoryRoot }),
      /path must stay in the Module 2 candidate directory/,
    );
  }
});

test("requires candidate metadata and approval boundary sections", () => {
  const source = readFileSync(
    new URL("../../candidate-inputs/module-2/02-design-system.md", import.meta.url),
    "utf8",
  );
  assert.throws(
    () => validateCandidateContent("design-system", source.replace(CANDIDATE_STATUS, "APPROVED")),
    /status must remain CANDIDATE_FOR_APPROVAL/,
  );
  assert.throws(
    () => validateCandidateContent("design-system", source.replace("## Approval boundary", "## Approved")),
    /required section is missing/,
  );
});

test("requires all 29 screens in the interactive mockup", () => {
  const source = readFileSync(
    new URL("../../candidate-inputs/module-2/01-screen-mockups.html", import.meta.url),
    "utf8",
  );
  assert.throws(
    () => validateCandidateContent("screen-mockups", source.replaceAll("M2-29", "M2-XX")),
    /screen coverage is missing: M2-29/,
  );
});

test("keeps the interactive mockup self-contained and non-persistent", () => {
  const source = readFileSync(
    new URL("../../candidate-inputs/module-2/01-screen-mockups.html", import.meta.url),
    "utf8",
  );
  for (const unsafe of [
    '<script src="https://example.test/review.js"></script>',
    "fetch('/api/v1/workforce')",
    "localStorage.setItem('approval', 'true')",
  ]) {
    assert.throws(
      () => validateCandidateContent("screen-mockups", `${source}\n${unsafe}`),
      /must not use/,
    );
  }
});

test("requires all 44 exact schema-baseline tables", () => {
  const source = readFileSync(
    new URL("../../candidate-inputs/module-2/03-data-dictionary-and-validation.md", import.meta.url),
    "utf8",
  );
  assert.equal(MODULE_2_TABLES.length, 44);
  assert.throws(
    () =>
      validateCandidateContent(
        "data-dictionary-and-validation",
        source.replaceAll("`workforce_registry_versions`", "`missing_table`"),
      ),
    /table is missing: workforce_registry_versions/,
  );
});

test("requires explicit independence of clinical scope and application access", () => {
  const source = readFileSync(
    new URL("../../candidate-inputs/module-2/05-authorization-policy.md", import.meta.url),
    "utf8",
  );
  assert.throws(
    () =>
      validateCandidateContent(
        "authorization-policy",
        source.replaceAll("clinical scope", "professional boundary"),
      ),
    /required candidate topic is missing: clinical scope/,
  );
});
