import { createHash } from "node:crypto";
import { existsSync, lstatSync, readFileSync, realpathSync } from "node:fs";
import {
  relative as relativePath,
  resolve as resolvePath,
  sep,
} from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import {
  MODULE_1_SCREENS,
  REQUIRED_ARTIFACT_KINDS,
} from "./verify-module-1-inputs.mjs";

export const CANDIDATE_STATUS = "CANDIDATE_FOR_APPROVAL";

const TOP_LEVEL_KEYS = Object.freeze([
  "schemaVersion",
  "module",
  "status",
  "candidateVersion",
  "basis",
  "artifacts",
]);
const ARTIFACT_KEYS = Object.freeze(["kind", "version", "path"]);
const VERSION = /^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$/;
const CANDIDATE_PATH =
  /^candidate-inputs\/module-1\/[A-Za-z0-9][A-Za-z0-9._/-]{0,479}\.(?:md|html)$/;
const REQUIRED_HEADINGS = Object.freeze([
  "## Decision baseline",
  "## Verification and acceptance",
  "## Approval boundary",
]);
const REQUIRED_TOPICS = Object.freeze({
  "design-system": [
    "tokens",
    "wcag 2.2 aa",
    "1440",
    "1024",
    "768",
    "390",
    "320",
  ],
  "data-dictionary-and-validation": [
    "organization_id",
    "lock_version",
    "uuidv7",
    "sensitivity",
    "validation catalogue",
  ],
  "lifecycle-and-transition-matrix": [
    "maker/checker",
    "effective ranges",
    "idempotency",
    "concurrency",
    "audit/outbox",
  ],
  "authorization-policy": [
    "deny-by-default",
    "final-owner",
    "recent authentication",
    "mfa",
    "service identities",
  ],
  "audit-and-event-registry": [
    "required payload",
    "outbox",
    "consumer",
    "retained",
    "version 1",
  ],
  "readiness-and-activation-policy": [
    "server-calculated",
    "15 minutes",
    "no readiness override",
    "evidence",
    "result digest",
  ],
  "history-and-export-policy": [
    "purpose",
    "csv formula injection",
    "retained",
    "redaction",
    "cursor",
  ],
});
const PROHIBITED_MOCKUP_CAPABILITIES = Object.freeze([
  [/<(?:script|img|iframe)[^>]+src=["']https?:/i, "remote source"],
  [/<link[^>]+href=["']https?:/i, "remote stylesheet"],
  [/\bfetch\s*\(/, "network fetch"],
  [/\bXMLHttpRequest\b/, "XMLHttpRequest"],
  [/\bWebSocket\b/, "WebSocket"],
  [/\bnavigator\.sendBeacon\b/, "sendBeacon"],
  [/\b(?:localStorage|sessionStorage|indexedDB)\b/, "browser persistence"],
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
    relative === "" || (!relative.startsWith(`..${sep}`) && relative !== "..")
  );
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function validateScreenMockupContent(content) {
  assert(
    content.includes(
      '<meta name="careos:artifact-kind" content="screen-mockups" />',
    ),
    "screen-mockups: artifact metadata is missing or mismatched",
  );
  assert(
    content.includes(
      `<meta name="careos:status" content="${CANDIDATE_STATUS}" />`,
    ),
    `screen-mockups: status must remain ${CANDIDATE_STATUS}`,
  );
  assert(
    content.includes(
      '<meta name="careos:candidate-version" content="m1-candidate-1" />',
    ),
    "screen-mockups: candidate version metadata is missing",
  );
  assert(
    content.includes("CANDIDATE FOR APPROVAL") &&
      content.includes("Not approved"),
    "screen-mockups: visible non-approval boundary is missing",
  );
  for (const width of ["1440", "1024", "768", "390", "320"]) {
    assert(
      content.includes(width),
      `screen-mockups: responsive width is missing: ${width}`,
    );
  }
  for (const fragment of [
    'id="state-select"',
    'class="record-cards"',
    'id="review-dialog"',
    'aria-live="polite"',
    "prefers-reduced-motion",
  ]) {
    assert(
      content.includes(fragment),
      `screen-mockups: required interaction contract is missing: ${fragment}`,
    );
  }
  for (const screen of MODULE_1_SCREENS) {
    assert(
      content.includes(screen),
      `screen-mockups: screen coverage is missing: ${screen}`,
    );
  }
  for (const [pattern, label] of PROHIBITED_MOCKUP_CAPABILITIES) {
    assert(
      !pattern.test(content),
      `screen-mockups: self-contained review artifact must not use ${label}`,
    );
  }
}

export function validateCandidateContent(kind, content) {
  if (kind === "screen-mockups") {
    validateScreenMockupContent(content);
    return;
  }
  assert(
    content.includes(`**Artifact kind:** \`${kind}\``),
    `${kind}: artifact metadata is missing or mismatched`,
  );
  assert(
    content.includes(`**Status:** \`${CANDIDATE_STATUS}\``),
    `${kind}: status must remain ${CANDIDATE_STATUS}`,
  );
  assert(
    content.includes("**Candidate version:** `m1-candidate-1`"),
    `${kind}: candidate version metadata is missing`,
  );
  assert(
    content.includes("**Approval:** Not granted"),
    `${kind}: non-approval boundary is missing`,
  );
  for (const heading of REQUIRED_HEADINGS) {
    assert(
      content.includes(heading),
      `${kind}: required section is missing: ${heading}`,
    );
  }
  const normalized = content.toLocaleLowerCase("en");
  for (const topic of REQUIRED_TOPICS[kind] ?? []) {
    assert(
      normalized.includes(topic.toLocaleLowerCase("en")),
      `${kind}: required candidate topic is missing: ${topic}`,
    );
  }
}

export function verifyModule1CandidateInputs(manifest, options = {}) {
  const root = resolvePath(options.rootDirectory ?? process.cwd());
  const candidateRoot = resolvePath(root, "candidate-inputs/module-1");
  assertPlainObject(manifest, "candidate manifest");
  assertExactKeys(manifest, TOP_LEVEL_KEYS, "candidate manifest");
  assert(manifest.schemaVersion === 1, "schemaVersion must be 1");
  assert(manifest.module === "M1", "module must be M1");
  assert(
    manifest.status === CANDIDATE_STATUS,
    `candidate status must be ${CANDIDATE_STATUS}`,
  );
  assert(
    VERSION.test(manifest.candidateVersion),
    "candidateVersion is invalid",
  );
  assert(manifest.basis === "review-draft-1", "basis must be review-draft-1");
  assert(Array.isArray(manifest.artifacts), "artifacts must be an array");
  assert(
    manifest.artifacts.length === REQUIRED_ARTIFACT_KINDS.length,
    `candidate manifest must contain exactly ${REQUIRED_ARTIFACT_KINDS.length} artifacts`,
  );

  const paths = new Set();
  const descriptors = [];
  for (const [index, artifact] of manifest.artifacts.entries()) {
    const label = `artifacts[${index}]`;
    assertPlainObject(artifact, label);
    assertExactKeys(artifact, ARTIFACT_KEYS, label);
    const expectedKind = REQUIRED_ARTIFACT_KINDS[index];
    assert(
      artifact.kind === expectedKind,
      `${label}.kind must be ${expectedKind}`,
    );
    assert(VERSION.test(artifact.version), `${label}.version is invalid`);
    assert(
      artifact.version === manifest.candidateVersion,
      `${label}.version must match candidateVersion`,
    );
    assert(
      CANDIDATE_PATH.test(artifact.path),
      `${label}.path must stay in the candidate directory`,
    );
    assert(!paths.has(artifact.path), `${label}.path is duplicated`);
    paths.add(artifact.path);

    const candidate = resolvePath(root, artifact.path);
    assert(
      isWithinRoot(candidateRoot, candidate),
      `${label}.path escapes the candidate directory`,
    );
    assert(existsSync(candidate), `${label}.path is missing: ${artifact.path}`);
    const stat = lstatSync(candidate);
    assert(!stat.isSymbolicLink(), `${label}.path must not be a symbolic link`);
    assert(stat.isFile(), `${label}.path must be a regular file`);
    const realCandidate = realpathSync(candidate);
    const realCandidateRoot = realpathSync(candidateRoot);
    assert(
      isWithinRoot(realCandidateRoot, realCandidate),
      `${label}.path resolves outside the candidate directory`,
    );

    const content = readFileSync(realCandidate, "utf8");
    validateCandidateContent(artifact.kind, content);
    descriptors.push({
      kind: artifact.kind,
      version: artifact.version,
      path: artifact.path,
      sha256: sha256(content),
    });
  }

  const candidatePackageSha256 = sha256(`${JSON.stringify(descriptors)}\n`);
  return {
    contract: "contracts/module-1-candidate-inputs.json",
    module: "M1",
    status: CANDIDATE_STATUS,
    candidateVersion: manifest.candidateVersion,
    basis: manifest.basis,
    artifactsVerified: descriptors.length,
    candidatePackageSha256,
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
      arguments_.manifest ?? "contracts/module-1-candidate-inputs.json",
    );
    const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
    console.log(
      JSON.stringify(
        verifyModule1CandidateInputs(manifest, {
          rootDirectory: repositoryRoot,
        }),
        null,
        2,
      ),
    );
  } catch (error) {
    console.error(`Module 1 candidate inputs: FAIL\n${error.message}`);
    process.exitCode = 1;
  }
}
