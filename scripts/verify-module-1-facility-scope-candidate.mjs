import { createHash } from "node:crypto";
import { existsSync, lstatSync, readFileSync, realpathSync } from "node:fs";
import {
  relative as relativePath,
  resolve as resolvePath,
  sep,
} from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export const FACILITY_SCOPE_CANDIDATE_STATUS = "CANDIDATE_FOR_APPROVAL";
export const FACILITY_SCOPE_CANDIDATE_VERSION =
  "m1-facility-scope-candidate-1";

const CONTRACT_PATH = "contracts/module-1-facility-scope-candidate.json";
const ARTIFACT_PATH =
  "candidate-inputs/module-1-facility-scope/01-facility-scope-contract.md";
const APPROVED_PACKAGE_SHA256 =
  "19aff5ce30516b7ee2101c093a8429d8a74394995ca90d486790bcc18a392946";
const TOP_LEVEL_KEYS = Object.freeze([
  "schemaVersion",
  "module",
  "slice",
  "status",
  "candidateVersion",
  "basis",
  "artifacts",
]);
const BASIS_KEYS = Object.freeze([
  "registryVersion",
  "approvalRecordId",
  "approvedPackageSha256",
]);
const ARTIFACT_KEYS = Object.freeze(["kind", "version", "path"]);
const REQUIRED_HEADINGS = Object.freeze([
  "## Decision baseline",
  "## Exact data contract",
  "## Authorization and transition contract",
  "## HTTP and projection contract",
  "## Evidence contract",
  "## Verification and acceptance",
  "## Approval boundary",
]);
const REQUIRED_TOPICS = Object.freeze([
  "scope_mode",
  "access_assignments",
  "membership_scope_change_facilities",
  "configuration_editor",
  "organization_viewer",
  "access.membership.change.request",
  "access.membership.change.approve",
  "access.membership.change",
  "identity.membership.changed",
  "maker/checker",
  "forced rls",
  "organization",
  "facilities",
  "implementation is not authorized",
]);
const UNRESOLVED_MARKER = /\b(?:TODO|TBD|FIXME|PLACEHOLDER)\b/i;

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
    relative === "" || (!relative.startsWith(`..${sep}`) && relative !== "..")
  );
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

export function validateFacilityScopeCandidateContent(content) {
  assert(
    content.includes("**Artifact kind:** `facility-scope-contract`"),
    "facility-scope-contract: artifact metadata is missing or mismatched",
  );
  assert(
    content.includes(`**Status:** \`${FACILITY_SCOPE_CANDIDATE_STATUS}\``),
    `facility-scope-contract: status must remain ${FACILITY_SCOPE_CANDIDATE_STATUS}`,
  );
  assert(
    content.includes(
      `**Candidate version:** \`${FACILITY_SCOPE_CANDIDATE_VERSION}\``,
    ),
    "facility-scope-contract: candidate version metadata is missing",
  );
  assert(
    content.includes("**Approval:** Not granted"),
    "facility-scope-contract: non-approval boundary is missing",
  );
  for (const heading of REQUIRED_HEADINGS) {
    assert(
      content.includes(heading),
      `facility-scope-contract: required section is missing: ${heading}`,
    );
  }
  const normalized = content.toLocaleLowerCase("en");
  for (const topic of REQUIRED_TOPICS) {
    assert(
      normalized.includes(topic.toLocaleLowerCase("en")),
      `facility-scope-contract: required decision is missing: ${topic}`,
    );
  }
  assert(
    !UNRESOLVED_MARKER.test(content),
    "facility-scope-contract: unresolved decision marker is forbidden",
  );
}

export function verifyModule1FacilityScopeCandidate(manifest, options = {}) {
  const root = resolvePath(options.rootDirectory ?? process.cwd());
  const candidateRoot = resolvePath(
    root,
    "candidate-inputs/module-1-facility-scope",
  );
  assertPlainObject(manifest, "facility-scope candidate manifest");
  assertExactKeys(
    manifest,
    TOP_LEVEL_KEYS,
    "facility-scope candidate manifest",
  );
  assert(manifest.schemaVersion === 1, "schemaVersion must be 1");
  assert(manifest.module === "M1", "module must be M1");
  assert(manifest.slice === "M1B", "slice must be M1B");
  assert(
    manifest.status === FACILITY_SCOPE_CANDIDATE_STATUS,
    `candidate status must be ${FACILITY_SCOPE_CANDIDATE_STATUS}`,
  );
  assert(
    manifest.candidateVersion === FACILITY_SCOPE_CANDIDATE_VERSION,
    `candidateVersion must be ${FACILITY_SCOPE_CANDIDATE_VERSION}`,
  );

  assertPlainObject(manifest.basis, "basis");
  assertExactKeys(manifest.basis, BASIS_KEYS, "basis");
  assert(
    manifest.basis.registryVersion === "m1-candidate-1",
    "basis.registryVersion must be m1-candidate-1",
  );
  assert(
    manifest.basis.approvalRecordId === "M1-APPROVAL-20260916-01",
    "basis.approvalRecordId must be M1-APPROVAL-20260916-01",
  );
  assert(
    manifest.basis.approvedPackageSha256 === APPROVED_PACKAGE_SHA256,
    "basis.approvedPackageSha256 does not match the approved package",
  );

  assert(Array.isArray(manifest.artifacts), "artifacts must be an array");
  assert(
    manifest.artifacts.length === 1,
    "facility-scope candidate manifest must contain exactly 1 artifact",
  );
  const artifact = manifest.artifacts[0];
  assertPlainObject(artifact, "artifacts[0]");
  assertExactKeys(artifact, ARTIFACT_KEYS, "artifacts[0]");
  assert(
    artifact.kind === "facility-scope-contract",
    "artifacts[0].kind must be facility-scope-contract",
  );
  assert(
    artifact.version === FACILITY_SCOPE_CANDIDATE_VERSION,
    "artifacts[0].version must match candidateVersion",
  );
  assert(
    artifact.path === ARTIFACT_PATH,
    `artifacts[0].path must be ${ARTIFACT_PATH}`,
  );

  const candidate = resolvePath(root, artifact.path);
  assert(
    isWithinRoot(candidateRoot, candidate),
    "artifacts[0].path escapes the facility-scope candidate directory",
  );
  assert(existsSync(candidate), `artifacts[0].path is missing: ${artifact.path}`);
  const stat = lstatSync(candidate);
  assert(!stat.isSymbolicLink(), "artifacts[0].path must not be a symbolic link");
  assert(stat.isFile(), "artifacts[0].path must be a regular file");
  const realCandidate = realpathSync(candidate);
  const realCandidateRoot = realpathSync(candidateRoot);
  assert(
    isWithinRoot(realCandidateRoot, realCandidate),
    "artifacts[0].path resolves outside the facility-scope candidate directory",
  );

  const content = readFileSync(realCandidate, "utf8");
  validateFacilityScopeCandidateContent(content);
  const descriptors = [
    {
      kind: artifact.kind,
      version: artifact.version,
      path: artifact.path,
      sha256: sha256(content),
    },
  ];
  const digestInput = {
    schemaVersion: manifest.schemaVersion,
    module: manifest.module,
    slice: manifest.slice,
    status: manifest.status,
    candidateVersion: manifest.candidateVersion,
    basis: manifest.basis,
    artifacts: descriptors,
  };

  return {
    contract: CONTRACT_PATH,
    module: manifest.module,
    slice: manifest.slice,
    status: manifest.status,
    candidateVersion: manifest.candidateVersion,
    basis: manifest.basis,
    artifactsVerified: descriptors.length,
    candidatePackageSha256: sha256(`${JSON.stringify(digestInput)}\n`),
    implementationAuthorized: false,
  };
}

function parseArguments(arguments_) {
  if (arguments_.length === 0) return {};
  assert(
    arguments_.length === 2 && arguments_[0] === "--manifest",
    "usage: --manifest PATH",
  );
  return { manifest: arguments_[1] };
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
      arguments_.manifest ?? CONTRACT_PATH,
    );
    const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
    console.log(
      JSON.stringify(
        verifyModule1FacilityScopeCandidate(manifest, {
          rootDirectory: repositoryRoot,
        }),
        null,
        2,
      ),
    );
  } catch (error) {
    console.error(`Module 1 facility-scope candidate: FAIL\n${error.message}`);
    process.exitCode = 1;
  }
}
