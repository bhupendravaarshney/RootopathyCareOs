import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  CANDIDATE_STATUS,
  validateCandidateContent,
  verifyModule1CandidateInputs,
} from "../verify-module-1-candidate-inputs.mjs";

const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
const checkedManifest = JSON.parse(
  readFileSync(
    new URL("../../contracts/module-1-candidate-inputs.json", import.meta.url),
    "utf8",
  ),
);

function cloneManifest() {
  return structuredClone(checkedManifest);
}

test("accepts the complete candidate package without authorizing implementation", () => {
  const result = verifyModule1CandidateInputs(cloneManifest(), {
    rootDirectory: repositoryRoot,
  });

  assert.equal(result.status, CANDIDATE_STATUS);
  assert.equal(result.artifactsVerified, 8);
  assert.match(result.candidatePackageSha256, /^[0-9a-f]{64}$/);
  assert.equal(result.implementationAuthorized, false);
});

test("rejects any attempt to label the candidate package approved", () => {
  const candidate = cloneManifest();
  candidate.status = "APPROVED";

  assert.throws(
    () =>
      verifyModule1CandidateInputs(candidate, {
        rootDirectory: repositoryRoot,
      }),
    /status must be CANDIDATE_FOR_APPROVAL/,
  );
});

test("requires all eight canonical artifacts in exact order and version", () => {
  const missing = cloneManifest();
  missing.artifacts.pop();
  assert.throws(
    () =>
      verifyModule1CandidateInputs(missing, { rootDirectory: repositoryRoot }),
    /exactly 8 artifacts/,
  );

  const reordered = cloneManifest();
  [reordered.artifacts[0], reordered.artifacts[1]] = [
    reordered.artifacts[1],
    reordered.artifacts[0],
  ];
  assert.throws(
    () =>
      verifyModule1CandidateInputs(reordered, {
        rootDirectory: repositoryRoot,
      }),
    /kind must be screen-mockups/,
  );

  const versionDrift = cloneManifest();
  versionDrift.artifacts[0].version = "m1-candidate-2";
  assert.throws(
    () =>
      verifyModule1CandidateInputs(versionDrift, {
        rootDirectory: repositoryRoot,
      }),
    /version must match candidateVersion/,
  );
});

test("keeps candidates outside draft and production approval directories", () => {
  for (const path of [
    "docs/module-1-review-drafts/01-screen-mockups.md",
    "approved-inputs/module-1/01-screen-mockups.html",
  ]) {
    const candidate = cloneManifest();
    candidate.artifacts[0].path = path;
    assert.throws(
      () =>
        verifyModule1CandidateInputs(candidate, {
          rootDirectory: repositoryRoot,
        }),
      /path must stay in the candidate directory/,
    );
  }
});

test("rejects a missing candidate artifact", () => {
  const candidate = cloneManifest();
  candidate.artifacts[1].path =
    "candidate-inputs/module-1/missing-design-system.md";

  assert.throws(
    () =>
      verifyModule1CandidateInputs(candidate, {
        rootDirectory: repositoryRoot,
      }),
    /path is missing/,
  );
});

test("requires candidate metadata, decision, verification, and approval-boundary sections", () => {
  const source = readFileSync(
    new URL(
      "../../candidate-inputs/module-1/02-design-system.md",
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

test("requires all 23 Module 1 screens in the interactive mockup", () => {
  const source = readFileSync(
    new URL(
      "../../candidate-inputs/module-1/01-screen-mockups.html",
      import.meta.url,
    ),
    "utf8",
  );

  assert.throws(
    () =>
      validateCandidateContent(
        "screen-mockups",
        source.replaceAll("M1-23", "M1-XX"),
      ),
    /screen coverage is missing: M1-23/,
  );
});

test("keeps the interactive mockup self-contained and non-persistent", () => {
  const source = readFileSync(
    new URL(
      "../../candidate-inputs/module-1/01-screen-mockups.html",
      import.meta.url,
    ),
    "utf8",
  );

  for (const unsafe of [
    '<script src="https://example.test/review.js"></script>',
    "fetch('/api/v1/organizations')",
    "localStorage.setItem('approval', 'true')",
  ]) {
    assert.throws(
      () => validateCandidateContent("screen-mockups", `${source}\n${unsafe}`),
      /must not use/,
    );
  }
});
