import { createHash } from "node:crypto";
import {
  closeSync,
  existsSync,
  lstatSync,
  openSync,
  readFileSync,
  readSync,
  realpathSync,
} from "node:fs";
import {
  isAbsolute,
  relative as relativePath,
  resolve as resolvePath,
  sep,
} from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export const REQUIRED_ARTIFACT_KINDS = Object.freeze([
  "screen-mockups",
  "design-system",
  "data-dictionary-and-validation",
  "lifecycle-and-transition-matrix",
  "authorization-policy",
  "audit-and-event-registry",
  "readiness-and-activation-policy",
  "history-and-export-policy",
]);

export const MODULE_1_SCREENS = Object.freeze(
  Array.from(
    { length: 23 },
    (_, index) => `M1-${String(index + 1).padStart(2, "0")}`,
  ),
);

const MANIFEST_SCHEMA = "./module-1-input-gate.schema.json";
const SHA_256 = /^[0-9a-f]{64}$/;
const VERSION = /^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$/;
const ARTIFACT_PATH =
  /^approved-inputs\/module-1\/[A-Za-z0-9][A-Za-z0-9._/-]{0,479}$/;
const RECORD_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/;
const APPROVER = /^[\p{L}\p{N}][\p{L}\p{N} .,'()@_-]{2,199}$/u;
const PLACEHOLDER =
  /\b(?:example|placeholder|replace\s+me|tbd|todo|unknown)\b/i;
const UTC_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/;
const TOP_LEVEL_KEYS = Object.freeze([
  "$schema",
  "schemaVersion",
  "module",
  "status",
  "requiredArtifacts",
  "artifacts",
  "approval",
]);
const ARTIFACT_KEYS = Object.freeze(["kind", "version", "path", "sha256"]);
const APPROVAL_KEYS = Object.freeze([
  "decision",
  "recordId",
  "approvedBy",
  "approvedAt",
  "screens",
  "packageSha256",
  "evidencePath",
  "evidenceSha256",
]);

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
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
  return (
    relative !== ".." &&
    !relative.startsWith(`..${sep}`) &&
    !isAbsolute(relative)
  );
}

function hashFile(path) {
  const hash = createHash("sha256");
  const buffer = Buffer.allocUnsafe(64 * 1024);
  const descriptor = openSync(path, "r");
  try {
    let bytesRead;
    do {
      bytesRead = readSync(descriptor, buffer, 0, buffer.length, null);
      if (bytesRead > 0) {
        hash.update(buffer.subarray(0, bytesRead));
      }
    } while (bytesRead > 0);
  } finally {
    closeSync(descriptor);
  }
  return hash.digest("hex");
}

export function calculatePackageSha256(artifacts) {
  const canonicalArtifacts = artifacts
    .map(({ kind, version, path, sha256 }) => ({
      kind,
      version,
      path,
      sha256,
    }))
    .sort((left, right) =>
      left.kind < right.kind ? -1 : left.kind > right.kind ? 1 : 0,
    );
  return createHash("sha256")
    .update(JSON.stringify(canonicalArtifacts), "utf8")
    .digest("hex");
}

function validateCheckedFile(path, sha256, label, root) {
  const pathSegments = path.split("/");
  assert(
    ARTIFACT_PATH.test(path) &&
      !pathSegments.some(
        (segment) => segment === "" || segment === "." || segment === "..",
      ),
    `${label}.path must stay under approved-inputs/module-1`,
  );
  assert(SHA_256.test(sha256), `${label}.sha256 must be lowercase SHA-256`);

  const resolved = resolvePath(root, ...pathSegments);
  assert(
    isWithinRoot(root, resolved),
    `${label}.path escapes the repository root`,
  );
  const canonicalRelative = relativePath(root, resolved).split(sep).join("/");
  assert(
    canonicalRelative === path,
    `${label}.path must use its canonical repository-relative form`,
  );
  assert(existsSync(resolved), `${label}.path does not exist: ${path}`);
  const details = lstatSync(resolved);
  assert(
    !details.isSymbolicLink(),
    `${label}.path must not be a symbolic link`,
  );
  assert(details.isFile(), `${label}.path must reference a regular file`);
  const real = realpathSync(resolved);
  assert(
    isWithinRoot(root, real),
    `${label}.path resolves outside the repository root`,
  );
  assert(hashFile(real) === sha256, `${label}.sha256 does not match ${path}`);
  return real;
}

function validateArtifact(artifact, index, root) {
  const label = `artifacts[${index}]`;
  assertPlainObject(artifact, label);
  assertExactKeys(artifact, ARTIFACT_KEYS, label);
  assert(
    REQUIRED_ARTIFACT_KINDS.includes(artifact.kind),
    `${label}.kind is not a required Module 1 artifact kind`,
  );
  assert(VERSION.test(artifact.version), `${label}.version is invalid`);
  return validateCheckedFile(artifact.path, artifact.sha256, label, root);
}

function canonicalUtcTimestamp(timestamp) {
  const fraction =
    timestamp.match(/\.(\d{1,3})Z$/)?.[1]?.padEnd(3, "0") ?? "000";
  return `${timestamp.slice(0, 19)}.${fraction}Z`;
}

function validateApproval(
  approval,
  packageSha256,
  now,
  root,
  artifactPaths,
  artifactRealPaths,
) {
  assertPlainObject(approval, "approval");
  assertExactKeys(approval, APPROVAL_KEYS, "approval");
  assert(
    approval.decision === "APPROVED",
    "approval.decision must be APPROVED",
  );
  assert(RECORD_ID.test(approval.recordId), "approval.recordId is invalid");
  assert(
    APPROVER.test(approval.approvedBy) &&
      !PLACEHOLDER.test(approval.approvedBy),
    "approval.approvedBy must identify a non-placeholder approver",
  );
  assert(
    UTC_TIMESTAMP.test(approval.approvedAt),
    "approval.approvedAt must be an RFC 3339 UTC timestamp",
  );
  const approvedAt = Date.parse(approval.approvedAt);
  assert(
    Number.isFinite(approvedAt) &&
      new Date(approvedAt).toISOString() ===
        canonicalUtcTimestamp(approval.approvedAt),
    "approval.approvedAt must be a real timestamp",
  );
  assert(
    approvedAt <= now.getTime() + 5 * 60 * 1000,
    "approval.approvedAt must not be in the future",
  );
  assert(
    JSON.stringify(approval.screens) === JSON.stringify(MODULE_1_SCREENS),
    "approval.screens must contain M1-01 through M1-23 in order",
  );
  assert(
    SHA_256.test(approval.packageSha256) &&
      approval.packageSha256 === packageSha256,
    "approval.packageSha256 does not bind the exact artifact package",
  );
  const evidenceRealPath = validateCheckedFile(
    approval.evidencePath,
    approval.evidenceSha256,
    "approval.evidence",
    root,
  );
  assert(
    !artifactPaths.has(approval.evidencePath) &&
      !artifactRealPaths.has(evidenceRealPath),
    "approval evidence must be distinct from every input artifact",
  );
}

export function verifyModule1InputGate(
  gate,
  {
    rootDirectory = fileURLToPath(new URL("../", import.meta.url)),
    now = new Date(),
  } = {},
) {
  assertPlainObject(gate, "Module 1 input gate");
  assertExactKeys(gate, TOP_LEVEL_KEYS, "Module 1 input gate");
  assert(
    gate.$schema === MANIFEST_SCHEMA,
    `Unexpected $schema: ${gate.$schema}`,
  );
  assert(gate.schemaVersion === 1, "schemaVersion must be 1");
  assert(gate.module === "M1", "module must be M1");
  assert(
    gate.status === "BLOCKED_INPUT" || gate.status === "APPROVED",
    "status must be BLOCKED_INPUT or APPROVED",
  );
  assert(
    JSON.stringify(gate.requiredArtifacts) ===
      JSON.stringify(REQUIRED_ARTIFACT_KINDS),
    "requiredArtifacts must contain the canonical eight artifact kinds in order",
  );
  assert(Array.isArray(gate.artifacts), "artifacts must be an array");
  assert(
    gate.artifacts.length <= REQUIRED_ARTIFACT_KINDS.length,
    "artifacts contains more entries than the required package",
  );

  const root = realpathSync(resolvePath(rootDirectory));
  const kinds = new Set();
  const paths = new Set();
  const realPaths = new Set();
  gate.artifacts.forEach((artifact, index) => {
    const real = validateArtifact(artifact, index, root);
    assert(
      !kinds.has(artifact.kind),
      `Duplicate artifact kind: ${artifact.kind}`,
    );
    assert(
      !paths.has(artifact.path),
      `Duplicate artifact path: ${artifact.path}`,
    );
    assert(!realPaths.has(real), `Duplicate artifact file: ${artifact.path}`);
    kinds.add(artifact.kind);
    paths.add(artifact.path);
    realPaths.add(real);
  });

  const missingArtifacts = REQUIRED_ARTIFACT_KINDS.filter(
    (kind) => !kinds.has(kind),
  );
  const packageSha256 =
    gate.artifacts.length === REQUIRED_ARTIFACT_KINDS.length
      ? calculatePackageSha256(gate.artifacts)
      : null;

  if (gate.status === "BLOCKED_INPUT") {
    assert(
      gate.approval === null,
      "BLOCKED_INPUT must not contain an approval record",
    );
  } else {
    assert(
      missingArtifacts.length === 0,
      `APPROVED is missing required artifacts: ${missingArtifacts.join(", ")}`,
    );
    validateApproval(gate.approval, packageSha256, now, root, paths, realPaths);
  }

  return {
    contract: "contracts/module-1-input-gate.json",
    module: "M1",
    status: gate.status,
    artifactsPresent: gate.artifacts.length,
    artifactsRequired: REQUIRED_ARTIFACT_KINDS.length,
    missingArtifacts,
    packageSha256,
    approval: gate.approval === null ? "MISSING" : "APPROVED",
    implementationAuthorized: gate.status === "APPROVED",
  };
}

export function requireApprovedModule1Inputs(result) {
  assert(
    result?.implementationAuthorized === true,
    "Module 1 implementation remains BLOCKED_INPUT until the complete package is approved",
  );
  return result;
}

function parseArguments(arguments_) {
  let manifest;
  let requireApproved = false;
  for (let index = 0; index < arguments_.length; index += 1) {
    const argument = arguments_[index];
    if (argument === "--require-approved") {
      requireApproved = true;
    } else if (argument === "--manifest") {
      index += 1;
      assert(arguments_[index], "--manifest requires a path");
      manifest = arguments_[index];
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }
  return { manifest, requireApproved };
}

const invokedModule = process.argv[1]
  ? pathToFileURL(resolvePath(process.argv[1])).href
  : undefined;

if (import.meta.url === invokedModule) {
  try {
    const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));
    const arguments_ = parseArguments(process.argv.slice(2));
    const manifestPath = resolvePath(
      repositoryRoot,
      arguments_.manifest ?? "contracts/module-1-input-gate.json",
    );
    const gate = JSON.parse(readFileSync(manifestPath, "utf8"));
    const result = verifyModule1InputGate(gate, {
      rootDirectory: repositoryRoot,
    });
    console.log(JSON.stringify(result, null, 2));
    if (arguments_.requireApproved) {
      requireApprovedModule1Inputs(result);
    }
  } catch (error) {
    console.error(`Module 1 input gate: FAIL\n${error.message}`);
    process.exitCode = 1;
  }
}
