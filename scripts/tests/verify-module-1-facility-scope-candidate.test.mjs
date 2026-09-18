import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  FACILITY_SCOPE_CANDIDATE_STATUS,
  FACILITY_SCOPE_CANDIDATE_VERSION,
  validateFacilityScopeCandidateContent,
  verifyModule1FacilityScopeCandidate,
} from "../verify-module-1-facility-scope-candidate.mjs";

const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
const checkedManifest = JSON.parse(
  readFileSync(
    new URL(
      "../../contracts/module-1-facility-scope-candidate.json",
      import.meta.url,
    ),
    "utf8",
  ),
);
const checkedContent = readFileSync(
  new URL(
    "../../candidate-inputs/module-1-facility-scope/01-facility-scope-contract.md",
    import.meta.url,
  ),
  "utf8",
);

function cloneManifest() {
  return structuredClone(checkedManifest);
}

test("accepts the exact facility-scope candidate without authorizing implementation", () => {
  const result = verifyModule1FacilityScopeCandidate(cloneManifest(), {
    rootDirectory: repositoryRoot,
  });

  assert.equal(result.status, FACILITY_SCOPE_CANDIDATE_STATUS);
  assert.equal(result.candidateVersion, FACILITY_SCOPE_CANDIDATE_VERSION);
  assert.equal(result.artifactsVerified, 1);
  assert.match(result.candidatePackageSha256, /^[0-9a-f]{64}$/);
  assert.equal(result.implementationAuthorized, false);
});

test("rejects an approved status claim", () => {
  const candidate = cloneManifest();
  candidate.status = "APPROVED";

  assert.throws(
    () =>
      verifyModule1FacilityScopeCandidate(candidate, {
        rootDirectory: repositoryRoot,
      }),
    /status must be CANDIDATE_FOR_APPROVAL/,
  );
});

test("binds the candidate to the exact approved Module 1 release", () => {
  for (const [field, value, expected] of [
    ["registryVersion", "m1-candidate-2", /registryVersion/],
    ["approvalRecordId", "M1-APPROVAL-OTHER", /approvalRecordId/],
    ["approvedPackageSha256", "a".repeat(64), /approvedPackageSha256/],
  ]) {
    const candidate = cloneManifest();
    candidate.basis[field] = value;
    assert.throws(
      () =>
        verifyModule1FacilityScopeCandidate(candidate, {
          rootDirectory: repositoryRoot,
        }),
      expected,
    );
  }
});

test("requires the exact manifest shape and M1B candidate version", () => {
  const extra = cloneManifest();
  extra.approval = "APPROVED";
  assert.throws(
    () =>
      verifyModule1FacilityScopeCandidate(extra, {
        rootDirectory: repositoryRoot,
      }),
    /keys must be exactly/,
  );

  const versionDrift = cloneManifest();
  versionDrift.candidateVersion = "m1-facility-scope-candidate-2";
  assert.throws(
    () =>
      verifyModule1FacilityScopeCandidate(versionDrift, {
        rootDirectory: repositoryRoot,
      }),
    /candidateVersion must be m1-facility-scope-candidate-1/,
  );
});

test("requires one exact artifact descriptor in the isolated candidate directory", () => {
  const missing = cloneManifest();
  missing.artifacts = [];
  assert.throws(
    () =>
      verifyModule1FacilityScopeCandidate(missing, {
        rootDirectory: repositoryRoot,
      }),
    /exactly 1 artifact/,
  );

  const wrongPath = cloneManifest();
  wrongPath.artifacts[0].path =
    "approved-inputs/module-1/05-authorization-policy.md";
  assert.throws(
    () =>
      verifyModule1FacilityScopeCandidate(wrongPath, {
        rootDirectory: repositoryRoot,
      }),
    /path must be candidate-inputs\/module-1-facility-scope/,
  );

  const wrongVersion = cloneManifest();
  wrongVersion.artifacts[0].version = "m1-facility-scope-candidate-2";
  assert.throws(
    () =>
      verifyModule1FacilityScopeCandidate(wrongVersion, {
        rootDirectory: repositoryRoot,
      }),
    /version must match candidateVersion/,
  );
});

test("requires non-approval metadata and every contract section", () => {
  assert.throws(
    () =>
      validateFacilityScopeCandidateContent(
        checkedContent.replace(FACILITY_SCOPE_CANDIDATE_STATUS, "APPROVED"),
      ),
    /status must remain CANDIDATE_FOR_APPROVAL/,
  );
  assert.throws(
    () =>
      validateFacilityScopeCandidateContent(
        checkedContent.replace("## Evidence contract", "## Evidence"),
      ),
    /required section is missing: ## Evidence contract/,
  );
});

test("requires exact security decisions and forbids unresolved markers", () => {
  assert.throws(
    () =>
      validateFacilityScopeCandidateContent(
        checkedContent.replace(/forced RLS/gi, "row controls"),
      ),
    /required decision is missing: forced rls/,
  );
  assert.throws(
    () =>
      validateFacilityScopeCandidateContent(
        `${checkedContent}\nTODO choose a different lifecycle`,
      ),
    /unresolved decision marker is forbidden/,
  );
});
