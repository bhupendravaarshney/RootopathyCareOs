import { createHash } from "node:crypto";
import { existsSync, lstatSync, readFileSync, realpathSync } from "node:fs";
import { relative as relativePath, resolve as resolvePath, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import {
  ACCEPTED_M1_COMMIT,
  BUILD_SPECIFICATION_PATH,
  BUILD_SPECIFICATION_SHA256,
  CANDIDATE_STATUS,
  CANDIDATE_VERSION,
  MODULE_2_SCREENS,
  MODULE_2_TABLES,
  REQUIRED_ARTIFACT_KINDS,
  validateCandidateContent,
  verifyModule2CandidateInputs,
} from "./verify-module-2-candidate-inputs.mjs";

export const APPROVED_STATUS = "APPROVED";
export const APPROVAL_RECORD_ID = "M2-APPROVAL-20260921-01";
export const APPROVED_PACKAGE_SHA256 =
  "624df2edc0024526040271911d43a1b33a12e723fefb3beb3e985264cef89521";
export const APPROVAL_EVIDENCE_SHA256 =
  "57676a269fee327d5a1f8c7458c901343c8b222af398025a98ca41c6b9aadbbe";
export const CANDIDATE_PACKAGE_SHA256 =
  "2e64bd4e1ac5192a9a4783abf58f4578b8bceda60c21c2e835a910e6760d0f8f";

const TOP_LEVEL_KEYS = Object.freeze([
  "$schema",
  "schemaVersion",
  "module",
  "status",
  "candidateVersion",
  "basis",
  "requiredArtifacts",
  "artifacts",
  "approval",
]);
const BASIS_KEYS = Object.freeze([
  "acceptedModule1Commit",
  "buildSpecification",
  "candidatePackageSha256",
]);
const SPECIFICATION_KEYS = Object.freeze(["path", "sha256"]);
const ARTIFACT_KEYS = Object.freeze(["kind", "version", "path", "sha256"]);
const APPROVAL_KEYS = Object.freeze([
  "decision",
  "recordId",
  "approvedBy",
  "approvedAt",
  "screens",
  "tableCount",
  "packageSha256",
  "evidencePath",
  "evidenceSha256",
]);
const SHA_256 = /^[0-9a-f]{64}$/;
const APPROVED_PATH =
  /^approved-inputs\/module-2\/[A-Za-z0-9][A-Za-z0-9._/-]{0,479}\.(?:md|html)$/;
const APPROVAL_PATH =
  /^approved-inputs\/module-2\/[A-Za-z0-9][A-Za-z0-9._/-]{0,479}\.md$/;
const RECORD_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/;
const UTC_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;
const PLACEHOLDER = /(?:unknown|placeholder|tbd|todo|example)/i;

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function assertPlainObject(value, label) {
  assert(
    value !== null &&
      typeof value === "object" &&
      !Array.isArray(value) &&
      Object.getPrototypeOf(value) === Object.prototype,
    `${label} must be a JSON object`,
  );
}

function assertExactKeys(value, keys, label) {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  assert(
    JSON.stringify(actual) === JSON.stringify(expected),
    `${label} keys must be exactly: ${expected.join(", ")}`,
  );
}

function isWithinRoot(root, candidate) {
  const relative = relativePath(root, candidate);
  return relative === "" || (!relative.startsWith(`..${sep}`) && relative !== "..");
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

export function calculatePackageSha256(artifacts) {
  return sha256(
    `${JSON.stringify(
      artifacts.map(({ kind, version, path, sha256: digest }) => ({
        kind,
        version,
        path,
        sha256: digest,
      })),
    )}\n`,
  );
}

function secureFile(root, allowedRoot, path, expectedSha256, label) {
  assert(typeof path === "string", `${label}.path must be a string`);
  const candidate = resolvePath(root, path);
  assert(isWithinRoot(allowedRoot, candidate), `${label}.path escapes its allowed directory`);
  assert(existsSync(candidate), `${label}.path is missing: ${path}`);
  const stat = lstatSync(candidate);
  assert(!stat.isSymbolicLink(), `${label}.path must not be a symbolic link`);
  assert(stat.isFile(), `${label}.path must be a regular file`);
  const realAllowedRoot = realpathSync(allowedRoot);
  const realCandidate = realpathSync(candidate);
  assert(isWithinRoot(realAllowedRoot, realCandidate), `${label}.path resolves outside its allowed directory`);
  const bytes = readFileSync(realCandidate);
  assert(SHA_256.test(expectedSha256), `${label}.sha256 is invalid`);
  assert(sha256(bytes) === expectedSha256, `${label}.sha256 does not match file bytes`);
  return bytes;
}

function validateBasis(basis, root) {
  assertPlainObject(basis, "basis");
  assertExactKeys(basis, BASIS_KEYS, "basis");
  assert(
    basis.acceptedModule1Commit === ACCEPTED_M1_COMMIT,
    `basis.acceptedModule1Commit must be ${ACCEPTED_M1_COMMIT}`,
  );
  assertPlainObject(basis.buildSpecification, "basis.buildSpecification");
  assertExactKeys(basis.buildSpecification, SPECIFICATION_KEYS, "basis.buildSpecification");
  assert(
    basis.buildSpecification.path === BUILD_SPECIFICATION_PATH,
    `build specification path must be ${BUILD_SPECIFICATION_PATH}`,
  );
  assert(
    basis.buildSpecification.sha256 === BUILD_SPECIFICATION_SHA256,
    "build specification declared SHA-256 is incorrect",
  );
  const specificationPath = resolvePath(root, BUILD_SPECIFICATION_PATH);
  assert(existsSync(specificationPath), "build specification is missing");
  assert(
    sha256(readFileSync(specificationPath)) === BUILD_SPECIFICATION_SHA256,
    "build specification bytes do not match the bound SHA-256",
  );
  assert(
    basis.candidatePackageSha256 === CANDIDATE_PACKAGE_SHA256,
    "basis.candidatePackageSha256 does not match the approved candidate",
  );
}

function validateApproval(approval, packageSha256, now, root, artifactPaths) {
  assertPlainObject(approval, "approval");
  assertExactKeys(approval, APPROVAL_KEYS, "approval");
  assert(approval.decision === APPROVED_STATUS, "approval.decision must be APPROVED");
  assert(RECORD_ID.test(approval.recordId), "approval.recordId is invalid");
  assert(approval.recordId === APPROVAL_RECORD_ID, `approval.recordId must be ${APPROVAL_RECORD_ID}`);
  assert(
    typeof approval.approvedBy === "string" &&
      approval.approvedBy.trim().length >= 3 &&
      approval.approvedBy.length <= 200 &&
      !PLACEHOLDER.test(approval.approvedBy),
    "approval.approvedBy must identify a non-placeholder approver",
  );
  assert(UTC_TIMESTAMP.test(approval.approvedAt), "approval.approvedAt must be an RFC 3339 UTC timestamp");
  const approvedAt = Date.parse(approval.approvedAt);
  assert(Number.isFinite(approvedAt), "approval.approvedAt must be a real timestamp");
  assert(approvedAt <= now.getTime(), "approval.approvedAt must not be in the future");
  assert(
    JSON.stringify(approval.screens) === JSON.stringify(MODULE_2_SCREENS),
    "approval.screens must contain M2-01 through M2-29 in order",
  );
  assert(approval.tableCount === MODULE_2_TABLES.length, "approval.tableCount must be 44");
  assert(
    approval.packageSha256 === packageSha256 && packageSha256 === APPROVED_PACKAGE_SHA256,
    "approval.packageSha256 does not bind the exact approved artifact package",
  );
  assert(APPROVAL_PATH.test(approval.evidencePath), "approval.evidencePath is invalid");
  assert(!artifactPaths.has(approval.evidencePath), "approval evidence must be distinct from every input artifact");
  const approvedRoot = resolvePath(root, "approved-inputs/module-2");
  const evidence = secureFile(
    root,
    approvedRoot,
    approval.evidencePath,
    approval.evidenceSha256,
    "approval.evidence",
  ).toString("utf8");
  assert(approval.evidenceSha256 === APPROVAL_EVIDENCE_SHA256, "approval evidence SHA-256 is not the approved value");
  for (const required of [
    "**Decision:** `APPROVED`",
    `**Record ID:** \`${APPROVAL_RECORD_ID}\``,
    CANDIDATE_PACKAGE_SHA256,
    APPROVED_PACKAGE_SHA256,
    ACCEPTED_M1_COMMIT,
    BUILD_SPECIFICATION_SHA256,
    "M2-01 through M2-29",
  ]) {
    assert(evidence.includes(required), `approval evidence is missing the binding: ${required}`);
  }
}

export function verifyModule2InputGate(gate, options = {}) {
  const root = resolvePath(options.rootDirectory ?? process.cwd());
  const approvedRoot = resolvePath(root, "approved-inputs/module-2");
  const now = options.now ?? new Date();
  assertPlainObject(gate, "input gate");
  assertExactKeys(gate, TOP_LEVEL_KEYS, "input gate");
  assert(gate.$schema === "./module-2-input-gate.schema.json", "$schema is invalid");
  assert(gate.schemaVersion === 1, "schemaVersion must be 1");
  assert(gate.module === "M2", "module must be M2");
  assert(gate.status === "BLOCKED_INPUT" || gate.status === APPROVED_STATUS, "status must be BLOCKED_INPUT or APPROVED");
  assert(gate.candidateVersion === CANDIDATE_VERSION, `candidateVersion must be ${CANDIDATE_VERSION}`);
  validateBasis(gate.basis, root);
  assert(
    JSON.stringify(gate.requiredArtifacts) === JSON.stringify(REQUIRED_ARTIFACT_KINDS),
    "requiredArtifacts must contain the eight canonical kinds in order",
  );
  assert(Array.isArray(gate.artifacts), "artifacts must be an array");
  assert(gate.artifacts.length <= REQUIRED_ARTIFACT_KINDS.length, "artifacts must contain at most eight entries");

  const artifactPaths = new Set();
  const artifacts = [];
  for (const [index, artifact] of gate.artifacts.entries()) {
    const label = `artifacts[${index}]`;
    assertPlainObject(artifact, label);
    assertExactKeys(artifact, ARTIFACT_KEYS, label);
    const expectedKind = REQUIRED_ARTIFACT_KINDS[index];
    assert(artifact.kind === expectedKind, `${label}.kind must be ${expectedKind}`);
    assert(artifact.version === CANDIDATE_VERSION, `${label}.version must be ${CANDIDATE_VERSION}`);
    assert(APPROVED_PATH.test(artifact.path), `${label}.path must stay in the approved Module 2 directory`);
    assert(!artifactPaths.has(artifact.path), `${label}.path is duplicated`);
    artifactPaths.add(artifact.path);
    const bytes = secureFile(root, approvedRoot, artifact.path, artifact.sha256, label);
    const content = bytes.toString("utf8");
    validateCandidateContent(artifact.kind, content);

    const candidatePath = artifact.path.replace("approved-inputs/", "candidate-inputs/");
    const candidateBytes = readFileSync(resolvePath(root, candidatePath));
    assert(
      sha256(candidateBytes) === artifact.sha256 && candidateBytes.equals(bytes),
      `${label} is not byte-identical to the approved candidate`,
    );
    artifacts.push(artifact);
  }

  const missingArtifacts = REQUIRED_ARTIFACT_KINDS.filter(
    (kind) => !artifacts.some((artifact) => artifact.kind === kind),
  );
  const packageSha256 = artifacts.length === REQUIRED_ARTIFACT_KINDS.length
    ? calculatePackageSha256(artifacts)
    : null;

  const candidateManifest = JSON.parse(
    readFileSync(resolvePath(root, "contracts/module-2-candidate-inputs.json"), "utf8"),
  );
  const candidateResult = verifyModule2CandidateInputs(candidateManifest, { rootDirectory: root });
  assert(
    candidateResult.status === CANDIDATE_STATUS &&
      candidateResult.candidatePackageSha256 === CANDIDATE_PACKAGE_SHA256,
    "the bound candidate package no longer verifies",
  );

  if (gate.status === "BLOCKED_INPUT") {
    assert(gate.approval === null, "BLOCKED_INPUT must not contain an approval record");
  } else {
    assert(missingArtifacts.length === 0, `APPROVED is missing required artifacts: ${missingArtifacts.join(", ")}`);
    validateApproval(gate.approval, packageSha256, now, root, artifactPaths);
  }

  return {
    contract: "contracts/module-2-input-gate.json",
    module: "M2",
    status: gate.status,
    candidateVersion: gate.candidateVersion,
    acceptedModule1Commit: ACCEPTED_M1_COMMIT,
    artifactsVerified: artifacts.length,
    artifactsRequired: REQUIRED_ARTIFACT_KINDS.length,
    screensCovered: MODULE_2_SCREENS.length,
    tablesCovered: MODULE_2_TABLES.length,
    packageSha256,
    approval: gate.approval === null ? "MISSING" : APPROVED_STATUS,
    implementationAuthorized: gate.status === APPROVED_STATUS,
  };
}

export function requireApprovedModule2Inputs(result) {
  assert(result.status === APPROVED_STATUS, "Module 2 implementation inputs are not approved");
  assert(result.implementationAuthorized, "Module 2 implementation is not authorized");
  return result;
}

function parseArguments(arguments_) {
  let manifest;
  let requireApproved = false;
  for (let index = 0; index < arguments_.length; index += 1) {
    const argument = arguments_[index];
    if (argument === "--require-approved") requireApproved = true;
    else if (argument === "--manifest" && arguments_[index + 1]) manifest = arguments_[++index];
    else throw new Error("usage: [--manifest PATH] [--require-approved]");
  }
  return { manifest, requireApproved };
}

const invokedModule = process.argv[1] ? pathToFileURL(resolvePath(process.argv[1])).href : undefined;

if (import.meta.url === invokedModule) {
  try {
    const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));
    const arguments_ = parseArguments(process.argv.slice(2));
    const manifestPath = resolvePath(
      repositoryRoot,
      arguments_.manifest ?? "contracts/module-2-input-gate.json",
    );
    const gate = JSON.parse(readFileSync(manifestPath, "utf8"));
    const result = verifyModule2InputGate(gate, { rootDirectory: repositoryRoot });
    if (arguments_.requireApproved) requireApprovedModule2Inputs(result);
    console.log(JSON.stringify(result, null, 2));
  } catch (error) {
    console.error(`Module 2 input gate: FAIL\n${error.message}`);
    process.exitCode = 1;
  }
}
