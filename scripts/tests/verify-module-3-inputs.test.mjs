import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  APPROVED_PACKAGE_SHA256,
  APPROVED_STATUS,
  APPROVAL_RECORD_ID,
  CANDIDATE_PACKAGE_SHA256,
  requireApprovedModule3Inputs,
  verifyModule3InputGate,
} from "../verify-module-3-inputs.mjs";

const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
const checkedGate = JSON.parse(
  readFileSync(
    new URL("../../contracts/module-3-input-gate.json", import.meta.url),
    "utf8",
  ),
);
const verificationTime = new Date("2026-09-27T00:00:00.000Z");

function cloneGate() {
  return structuredClone(checkedGate);
}

function verify(gate) {
  return verifyModule3InputGate(gate, {
    rootDirectory: repositoryRoot,
    now: verificationTime,
  });
}

test("accepts the complete checksum-bound Module 3 implementation package", () => {
  const result = verify(cloneGate());
  assert.equal(result.status, APPROVED_STATUS);
  assert.equal(result.artifactsVerified, 8);
  assert.equal(result.screensCovered, 16);
  assert.equal(result.entitiesCovered, 14);
  assert.equal(result.decisionFamiliesCovered, 15);
  assert.equal(result.packageSha256, APPROVED_PACKAGE_SHA256);
  assert.equal(result.approvalRecordId, APPROVAL_RECORD_ID);
  assert.equal(result.implementationAuthorized, true);
  assert.equal(requireApprovedModule3Inputs(result), result);
});

test("rejects approval status without all eight exact artifacts", () => {
  const gate = cloneGate();
  gate.artifacts.pop();
  assert.throws(() => verify(gate), /APPROVED is missing required artifacts/);
});

test("requires all artifact kinds in canonical order", () => {
  const gate = cloneGate();
  [gate.artifacts[0], gate.artifacts[1]] = [
    gate.artifacts[1],
    gate.artifacts[0],
  ];
  assert.throws(() => verify(gate), /kind must be screen-mockups/);
});

test("rejects artifact checksum drift", () => {
  const gate = cloneGate();
  gate.artifacts[0].sha256 = "0".repeat(64);
  assert.throws(() => verify(gate), /sha256 does not match file bytes/);
});

test("keeps approved paths inside the Module 3 approved directory", () => {
  const gate = cloneGate();
  gate.artifacts[0].path =
    "approved-inputs/module-3/../module-2/01-screen-mockups.html";
  assert.throws(
    () => verify(gate),
    /path must stay in the approved Module 3 directory/,
  );
});

test("binds the exact candidate package and accepted sources", () => {
  const candidate = cloneGate();
  candidate.basis.candidatePackageSha256 = "0".repeat(64);
  assert.throws(() => verify(candidate), /candidatePackageSha256/);

  const predecessor = cloneGate();
  predecessor.basis.acceptedModule2.recordId = "M2-WRONG";
  assert.throws(() => verify(predecessor), /acceptedModule2.recordId/);

  const product = cloneGate();
  product.basis.acceptedProductDefinition.sha256 = "0".repeat(64);
  assert.throws(() => verify(product), /acceptedProductDefinition.sha256/);

  const acceptance = cloneGate();
  acceptance.basis.productAcceptance.sha256 = "0".repeat(64);
  assert.throws(() => verify(acceptance), /productAcceptance.sha256/);

  assert.match(CANDIDATE_PACKAGE_SHA256, /^[0-9a-f]{64}$/);
});

test("binds all 16 screens, 14 entities and 15 decisions", () => {
  const screens = cloneGate();
  screens.approval.screens.pop();
  assert.throws(() => verify(screens), /P3-01 through P3-16/);

  const entities = cloneGate();
  entities.approval.entityCount = 13;
  assert.throws(() => verify(entities), /entityCount must be 14/);

  const decisions = cloneGate();
  decisions.approval.decisionFamilyCount = 14;
  assert.throws(() => verify(decisions), /decisionFamilyCount must be 15/);
});

test("rejects a changed approved package digest", () => {
  const gate = cloneGate();
  gate.approval.packageSha256 = "0".repeat(64);
  assert.throws(
    () => verify(gate),
    /does not bind the exact approved artifact package/,
  );
});

test("requires separate checksum-matched approval evidence", () => {
  const path = cloneGate();
  path.approval.evidencePath = path.artifacts[1].path;
  assert.throws(() => verify(path), /approval evidence must be distinct/);

  const digest = cloneGate();
  digest.approval.evidenceSha256 = "0".repeat(64);
  assert.throws(() => verify(digest), /sha256 does not match file bytes/);
});

test("requires a real accountable approver", () => {
  const gate = cloneGate();
  gate.approval.approvedBy = "TBD";
  assert.throws(() => verify(gate), /non-placeholder approver/);
});

test("rejects approval timestamps in the future", () => {
  const gate = cloneGate();
  gate.approval.approvedAt = "2026-09-28T00:00:00.000Z";
  assert.throws(() => verify(gate), /must not be in the future/);
});

test("required approval mode rejects a blocked result", () => {
  assert.throws(
    () =>
      requireApprovedModule3Inputs({
        status: "BLOCKED_INPUT",
        implementationAuthorized: false,
      }),
    /not approved/,
  );
});
