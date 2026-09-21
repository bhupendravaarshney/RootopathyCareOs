import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  APPROVED_PACKAGE_SHA256,
  APPROVED_STATUS,
  CANDIDATE_PACKAGE_SHA256,
  requireApprovedModule2Inputs,
  verifyModule2InputGate,
} from "../verify-module-2-inputs.mjs";

const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
const checkedGate = JSON.parse(
  readFileSync(new URL("../../contracts/module-2-input-gate.json", import.meta.url), "utf8"),
);
const verificationTime = new Date("2026-09-22T00:00:00.000Z");

function cloneGate() {
  return structuredClone(checkedGate);
}

function verify(gate) {
  return verifyModule2InputGate(gate, {
    rootDirectory: repositoryRoot,
    now: verificationTime,
  });
}

test("accepts the complete checksum-bound Module 2 production package", () => {
  const result = verify(cloneGate());
  assert.equal(result.status, APPROVED_STATUS);
  assert.equal(result.artifactsVerified, 8);
  assert.equal(result.screensCovered, 29);
  assert.equal(result.tablesCovered, 44);
  assert.equal(result.packageSha256, APPROVED_PACKAGE_SHA256);
  assert.equal(result.implementationAuthorized, true);
  assert.equal(requireApprovedModule2Inputs(result), result);
});

test("rejects approval status without all eight exact artifacts", () => {
  const gate = cloneGate();
  gate.artifacts.pop();
  assert.throws(() => verify(gate), /APPROVED is missing required artifacts/);
});

test("requires all artifact kinds in canonical order", () => {
  const gate = cloneGate();
  [gate.artifacts[0], gate.artifacts[1]] = [gate.artifacts[1], gate.artifacts[0]];
  assert.throws(() => verify(gate), /kind must be screen-mockups/);
});

test("rejects artifact checksum drift", () => {
  const gate = cloneGate();
  gate.artifacts[0].sha256 = "0".repeat(64);
  assert.throws(() => verify(gate), /sha256 does not match file bytes/);
});

test("binds the exact candidate package and accepted predecessor", () => {
  const candidate = cloneGate();
  candidate.basis.candidatePackageSha256 = "0".repeat(64);
  assert.throws(() => verify(candidate), /candidatePackageSha256/);

  const predecessor = cloneGate();
  predecessor.basis.acceptedModule1Commit = "0".repeat(40);
  assert.throws(() => verify(predecessor), /acceptedModule1Commit/);

  assert.match(CANDIDATE_PACKAGE_SHA256, /^[0-9a-f]{64}$/);
});

test("binds all 29 screens and the exact 44-table baseline", () => {
  const screens = cloneGate();
  screens.approval.screens.pop();
  assert.throws(() => verify(screens), /M2-01 through M2-29/);

  const tables = cloneGate();
  tables.approval.tableCount = 43;
  assert.throws(() => verify(tables), /tableCount must be 44/);
});

test("rejects a changed approved package digest", () => {
  const gate = cloneGate();
  gate.approval.packageSha256 = "0".repeat(64);
  assert.throws(() => verify(gate), /does not bind the exact approved artifact package/);
});

test("requires separate checksum-matched approval evidence", () => {
  const path = cloneGate();
  path.approval.evidencePath = path.artifacts[1].path;
  assert.throws(() => verify(path), /approval evidence must be distinct/);

  const digest = cloneGate();
  digest.approval.evidenceSha256 = "0".repeat(64);
  assert.throws(() => verify(digest), /sha256 does not match file bytes/);
});

test("rejects approval timestamps in the future", () => {
  const gate = cloneGate();
  gate.approval.approvedAt = "2026-09-23T00:00:00.000Z";
  assert.throws(() => verify(gate), /must not be in the future/);
});

test("required approval mode rejects a blocked result", () => {
  assert.throws(
    () =>
      requireApprovedModule2Inputs({
        status: "BLOCKED_INPUT",
        implementationAuthorized: false,
      }),
    /not approved/,
  );
});
