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

export const REVIEW_DRAFT_STATUS = "DRAFT_NOT_APPROVED";

const TOP_LEVEL_KEYS = Object.freeze([
  "schemaVersion",
  "module",
  "status",
  "draftVersion",
  "artifacts",
]);
const ARTIFACT_KEYS = Object.freeze(["kind", "version", "path"]);
const VERSION = /^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$/;
const DRAFT_PATH =
  /^docs\/module-1-review-drafts\/[A-Za-z0-9][A-Za-z0-9._/-]{0,479}\.md$/;
const REQUIRED_HEADINGS = Object.freeze([
  "## Proposed review baseline",
  "## Owner decisions required",
  "## Acceptance checklist",
]);
const REQUIRED_TOPICS = Object.freeze({
  "screen-mockups": ["1440", "1024", "768", "390", "320", "keyboard", "focus"],
  "design-system": [
    "tokens",
    "components",
    "responsive",
    "accessibility",
    "focus",
  ],
  "data-dictionary-and-validation": [
    "organization_id",
    "lock_version",
    "effective",
    "sensitivity",
    "retention",
  ],
  "lifecycle-and-transition-matrix": [
    "maker-checker",
    "effective",
    "idempotency",
    "concurrency",
    "audit/outbox",
  ],
  "authorization-policy": [
    "denial mode",
    "delegation",
    "recent authentication",
    "maker-checker",
    "final-owner",
  ],
  "audit-and-event-registry": [
    "required payload keys",
    "allowed payload keys",
    "outbox",
    "consumer",
    "retention",
  ],
  "readiness-and-activation-policy": [
    "server-calculated",
    "freshness",
    "override",
    "maker-checker",
    "evidence",
  ],
  "history-and-export-policy": [
    "purpose",
    "formula injection",
    "retention",
    "redaction",
    "cursor",
  ],
});

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

export function validateReviewDraftContent(kind, content) {
  assert(
    content.includes(`**Artifact kind:** \`${kind}\``),
    `${kind}: artifact metadata is missing or mismatched`,
  );
  assert(
    content.includes(`**Status:** \`${REVIEW_DRAFT_STATUS}\``),
    `${kind}: status must remain ${REVIEW_DRAFT_STATUS}`,
  );
  assert(
    content.includes("**Approval authority:** Owner decision required"),
    `${kind}: approval authority must remain owner-required`,
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
      `${kind}: required review topic is missing: ${topic}`,
    );
  }
  if (kind === "screen-mockups") {
    for (const screen of MODULE_1_SCREENS) {
      assert(
        content.includes(screen),
        `${kind}: screen coverage is missing: ${screen}`,
      );
    }
  }
}

export function verifyModule1ReviewDrafts(manifest, options = {}) {
  const root = resolvePath(options.rootDirectory ?? process.cwd());
  const draftRoot = resolvePath(root, "docs/module-1-review-drafts");
  assertPlainObject(manifest, "review draft manifest");
  assertExactKeys(manifest, TOP_LEVEL_KEYS, "review draft manifest");
  assert(manifest.schemaVersion === 1, "schemaVersion must be 1");
  assert(manifest.module === "M1", "module must be M1");
  assert(
    manifest.status === REVIEW_DRAFT_STATUS,
    `review draft status must be ${REVIEW_DRAFT_STATUS}`,
  );
  assert(VERSION.test(manifest.draftVersion), "draftVersion is invalid");
  assert(Array.isArray(manifest.artifacts), "artifacts must be an array");
  assert(
    manifest.artifacts.length === REQUIRED_ARTIFACT_KINDS.length,
    `review draft manifest must contain exactly ${REQUIRED_ARTIFACT_KINDS.length} artifacts`,
  );

  const paths = new Set();
  const kinds = [];
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
      DRAFT_PATH.test(artifact.path),
      `${label}.path must stay in the draft directory`,
    );
    assert(!paths.has(artifact.path), `${label}.path is duplicated`);
    paths.add(artifact.path);
    kinds.push(artifact.kind);

    const candidate = resolvePath(root, artifact.path);
    assert(
      isWithinRoot(draftRoot, candidate),
      `${label}.path escapes the draft directory`,
    );
    assert(existsSync(candidate), `${label}.path is missing: ${artifact.path}`);
    const stat = lstatSync(candidate);
    assert(!stat.isSymbolicLink(), `${label}.path must not be a symbolic link`);
    assert(stat.isFile(), `${label}.path must be a regular file`);
    const realCandidate = realpathSync(candidate);
    const realDraftRoot = realpathSync(draftRoot);
    assert(
      isWithinRoot(realDraftRoot, realCandidate),
      `${label}.path resolves outside the draft directory`,
    );

    const content = readFileSync(realCandidate, "utf8");
    validateReviewDraftContent(artifact.kind, content);
    descriptors.push({
      kind: artifact.kind,
      version: artifact.version,
      path: artifact.path,
      sha256: sha256(content),
    });
  }

  assert(
    JSON.stringify(kinds) === JSON.stringify(REQUIRED_ARTIFACT_KINDS),
    "review draft artifact order does not match the canonical catalogue",
  );
  const reviewPackageSha256 = sha256(`${JSON.stringify(descriptors)}\n`);

  return {
    contract: "contracts/module-1-review-drafts.json",
    module: "M1",
    status: REVIEW_DRAFT_STATUS,
    draftVersion: manifest.draftVersion,
    artifactsVerified: descriptors.length,
    reviewPackageSha256,
    implementationAuthorized: false,
  };
}

function parseArguments(arguments_) {
  if (arguments_.length === 0) {
    return {};
  }
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
      arguments_.manifest ?? "contracts/module-1-review-drafts.json",
    );
    const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
    console.log(
      JSON.stringify(
        verifyModule1ReviewDrafts(manifest, { rootDirectory: repositoryRoot }),
        null,
        2,
      ),
    );
  } catch (error) {
    console.error(`Module 1 review drafts: FAIL\n${error.message}`);
    process.exitCode = 1;
  }
}
