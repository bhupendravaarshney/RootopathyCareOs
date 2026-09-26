import { createHash } from "node:crypto";
import { existsSync, lstatSync, readFileSync, realpathSync } from "node:fs";
import {
  relative as relativePath,
  resolve as resolvePath,
  sep,
} from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export const CANDIDATE_STATUS = "CANDIDATE_FOR_APPROVAL";
export const CANDIDATE_VERSION = "m3-candidate-1";
export const ACCEPTED_M2_RECORD_ID = "M2-COMPLETION-ACCEPTANCE-20260926-01";
export const ACCEPTED_M2_PATH = "docs/MODULE_2_COMPLETION_ACCEPTANCE.md";
export const ACCEPTED_M2_SHA256 =
  "677f2e78c6cc76f5d3bbd1b57ba615fc53676ae135957caa14356797edc820b8";
export const PRODUCT_DEFINITION_VERSION = "m3-product-definition-draft-2";
export const PRODUCT_DEFINITION_PATH = "docs/MODULE_3_PRODUCT_DEFINITION.md";
export const PRODUCT_DEFINITION_SHA256 =
  "c1eef920751af4547fa6b8c260b773edbfa266cfa84ac6a01302a44faa765bb5";
export const PRODUCT_ACCEPTANCE_RECORD_ID = "M3-PRODUCT-ACCEPTANCE-20260926-01";
export const PRODUCT_ACCEPTANCE_PATH =
  "docs/MODULE_3_PRODUCT_DEFINITION_ACCEPTANCE.md";
export const PRODUCT_ACCEPTANCE_SHA256 =
  "f3c6c866cec02441df006c34d6012f448ebaed505e724bce8f75fbbbc51bf144";
export const BUILD_SPECIFICATION_PATH =
  "docs/CareOS_Complete_Build_Specification_Java_Spring_Boot_React_Node_Edition.pdf";
export const BUILD_SPECIFICATION_SHA256 =
  "2c8f9f020c7c1a21678c795df57fd8be1b659f2141ab7c8747b5dec973db0bfe";

export const MODULE_3_SCREENS = Object.freeze(
  Array.from(
    { length: 16 },
    (_, index) => `P3-${String(index + 1).padStart(2, "0")}`,
  ),
);

export const MODULE_3_ENTITIES = Object.freeze([
  "patient_profiles",
  "patient_identifiers",
  "patient_contacts",
  "patient_addresses",
  "communication_preferences",
  "caregiver_relationships",
  "patient_consents",
  "privacy_restrictions",
  "patient_safety_flags",
  "patient_match_keys",
  "patient_duplicate_candidates",
  "patient_merge_requests",
  "patient_merge_decisions",
  "patient_registration_runs",
]);

export const DECISION_FAMILIES = Object.freeze([
  "Patient scope",
  "Demographic model",
  "Identity proofing and verification",
  "Identifier policy",
  "Duplicate policy",
  "Merge policy",
  "Caregiver and proxy authority",
  "Consent policy",
  "Privacy restrictions",
  "Safety flags",
  "Portal linkage and invitation",
  "Communication policy",
  "Retention, residency and legal hold",
  "Export and reporting",
  "FHIR and interoperability",
]);

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

const TOP_LEVEL_KEYS = Object.freeze([
  "schemaVersion",
  "module",
  "status",
  "candidateVersion",
  "basis",
  "artifacts",
]);
const BASIS_KEYS = Object.freeze([
  "acceptedModule2",
  "acceptedProductDefinition",
  "productAcceptance",
  "buildSpecification",
]);
const RECORD_KEYS = Object.freeze(["path", "recordId", "sha256"]);
const PRODUCT_KEYS = Object.freeze(["path", "version", "sha256"]);
const SPECIFICATION_KEYS = Object.freeze(["path", "sha256"]);
const ARTIFACT_KEYS = Object.freeze(["kind", "version", "path"]);
const VERSION = /^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$/;
const CANDIDATE_PATH =
  /^candidate-inputs\/module-3\/[A-Za-z0-9][A-Za-z0-9._/-]{0,479}\.(?:md|html)$/;
const REQUIRED_HEADINGS = Object.freeze([
  "## Decision baseline",
  "## Verification and acceptance",
  "## Approval boundary",
]);
const REQUIRED_TOPICS = Object.freeze({
  "design-system": [
    "wcag 2.2 aa",
    "1440",
    "1024",
    "768",
    "390",
    "320",
    "patient ≠ portal account",
    "relationship ≠ proxy authority",
    "duplicatecandidatecard",
    "mergecomparison",
    "no real patient data",
  ],
  "data-dictionary-and-validation": [
    "14 core entity families",
    "organization_id",
    "uuidv7",
    "forced rls",
    "partial",
    "provenance",
    "relationship alone grants nothing",
    "urgent registration",
  ],
  "lifecycle-and-transition-matrix": [
    "urgent temporary identity",
    "maker/checker",
    "constrained executor",
    "prospective",
    "no automatic patient merge",
    "no m3 break-glass",
    "patient.link",
  ],
  "authorization-policy": [
    "canonical m1",
    "deny-by-default",
    "relationship alone denies",
    "consent-required",
    "server-side",
    "recent-authentication",
    "service identities",
  ],
  "audit-and-event-registry": [
    "version 1",
    "transactional outbox",
    "prohibited sensitive content",
    "consumer",
    "raw audit",
    "patient.merge.executed",
  ],
  "readiness-and-activation-policy": [
    "patient-registration-v1",
    "15 minutes",
    "no readiness override",
    "capability activation gates",
    "urgent temporary identity",
    "fhir exchange",
  ],
  "history-and-export-policy": [
    "fhir is an external representation",
    "patient.link",
    "legal hold",
    "csv cells",
    "universal retention duration",
    "signed get access",
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
  return (
    relative === "" || (!relative.startsWith(`..${sep}`) && relative !== "..")
  );
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function readBoundFile(root, path, expectedSha256, label) {
  const resolved = resolvePath(root, path);
  assert(existsSync(resolved), `${label} is missing`);
  const stat = lstatSync(resolved);
  assert(
    stat.isFile() && !stat.isSymbolicLink(),
    `${label} must be a regular file`,
  );
  const content = readFileSync(resolved);
  assert(
    sha256(content) === expectedSha256,
    `${label} bytes do not match the bound SHA-256`,
  );
  return content;
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
      `<meta name="careos:candidate-version" content="${CANDIDATE_VERSION}" />`,
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
    'id="screen-nav"',
    'id="review-dialog"',
    'class="record-cards"',
    'aria-live="polite"',
    "prefers-reduced-motion",
    "No real patient data",
    "Organization-only identity",
    "Never auto-merge",
    "No M3 break-glass",
  ]) {
    assert(
      content.includes(fragment),
      `screen-mockups: required interaction contract is missing: ${fragment}`,
    );
  }
  for (const state of [
    "loading",
    "empty",
    "no-results",
    "denied",
    "validation",
    "stale",
    "conflict",
    "dependency-failure",
    "success",
  ]) {
    assert(
      content.includes(`value="${state}"`),
      `screen-mockups: required state is missing: ${state}`,
    );
  }
  for (const screen of MODULE_3_SCREENS) {
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
    content.includes(`**Candidate version:** \`${CANDIDATE_VERSION}\``),
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
  if (kind === "data-dictionary-and-validation") {
    for (const entity of MODULE_3_ENTITIES) {
      assert(
        content.includes(`\`${entity}\``),
        `data-dictionary-and-validation: entity is missing: ${entity}`,
      );
    }
  }
}

function verifyRecordBasis(root, actual, expected, label) {
  assertPlainObject(actual, label);
  assertExactKeys(actual, RECORD_KEYS, label);
  assert(
    actual.path === expected.path,
    `${label}.path must be ${expected.path}`,
  );
  assert(
    actual.recordId === expected.recordId,
    `${label}.recordId must be ${expected.recordId}`,
  );
  assert(actual.sha256 === expected.sha256, `${label}.sha256 is incorrect`);
  const content = readBoundFile(
    root,
    actual.path,
    actual.sha256,
    label,
  ).toString("utf8");
  assert(
    content.includes(expected.recordId),
    `${label} does not contain its bound record ID`,
  );
  return content;
}

export function verifyModule3CandidateInputs(manifest, options = {}) {
  const root = resolvePath(options.rootDirectory ?? process.cwd());
  const candidateRoot = resolvePath(root, "candidate-inputs/module-3");
  assertPlainObject(manifest, "candidate manifest");
  assertExactKeys(manifest, TOP_LEVEL_KEYS, "candidate manifest");
  assert(manifest.schemaVersion === 1, "schemaVersion must be 1");
  assert(manifest.module === "M3", "module must be M3");
  assert(
    manifest.status === CANDIDATE_STATUS,
    `candidate status must be ${CANDIDATE_STATUS}`,
  );
  assert(
    VERSION.test(manifest.candidateVersion),
    "candidateVersion is invalid",
  );
  assert(
    manifest.candidateVersion === CANDIDATE_VERSION,
    `candidateVersion must be ${CANDIDATE_VERSION}`,
  );

  assertPlainObject(manifest.basis, "basis");
  assertExactKeys(manifest.basis, BASIS_KEYS, "basis");
  verifyRecordBasis(
    root,
    manifest.basis.acceptedModule2,
    {
      path: ACCEPTED_M2_PATH,
      recordId: ACCEPTED_M2_RECORD_ID,
      sha256: ACCEPTED_M2_SHA256,
    },
    "basis.acceptedModule2",
  );

  assertPlainObject(
    manifest.basis.acceptedProductDefinition,
    "basis.acceptedProductDefinition",
  );
  assertExactKeys(
    manifest.basis.acceptedProductDefinition,
    PRODUCT_KEYS,
    "basis.acceptedProductDefinition",
  );
  assert(
    manifest.basis.acceptedProductDefinition.path === PRODUCT_DEFINITION_PATH,
    `basis.acceptedProductDefinition.path must be ${PRODUCT_DEFINITION_PATH}`,
  );
  assert(
    manifest.basis.acceptedProductDefinition.version ===
      PRODUCT_DEFINITION_VERSION,
    `basis.acceptedProductDefinition.version must be ${PRODUCT_DEFINITION_VERSION}`,
  );
  assert(
    manifest.basis.acceptedProductDefinition.sha256 ===
      PRODUCT_DEFINITION_SHA256,
    "basis.acceptedProductDefinition.sha256 is incorrect",
  );
  const productDefinition = readBoundFile(
    root,
    PRODUCT_DEFINITION_PATH,
    PRODUCT_DEFINITION_SHA256,
    "accepted product definition",
  ).toString("utf8");
  assert(
    productDefinition.includes(
      `**Draft version:** \`${PRODUCT_DEFINITION_VERSION}\``,
    ),
    "accepted product definition version marker is missing",
  );
  assert(
    productDefinition.includes("**Implementation authority:** `false`"),
    "accepted product definition must remain non-authorizing",
  );
  for (const [index, family] of DECISION_FAMILIES.entries()) {
    assert(
      productDefinition.includes(`### ${index + 1}. ${family}`),
      `accepted product definition decision is missing: ${family}`,
    );
  }

  const acceptance = verifyRecordBasis(
    root,
    manifest.basis.productAcceptance,
    {
      path: PRODUCT_ACCEPTANCE_PATH,
      recordId: PRODUCT_ACCEPTANCE_RECORD_ID,
      sha256: PRODUCT_ACCEPTANCE_SHA256,
    },
    "basis.productAcceptance",
  );
  assert(
    acceptance.includes(
      "**Decision:** `ACCEPT_RECOMMENDED_CHANGES_FOR_MOCKUP`",
    ),
    "product acceptance decision is missing",
  );
  assert(
    acceptance.includes("**Implementation authorization:** `false`"),
    "product acceptance must remain non-authorizing",
  );

  assertPlainObject(
    manifest.basis.buildSpecification,
    "basis.buildSpecification",
  );
  assertExactKeys(
    manifest.basis.buildSpecification,
    SPECIFICATION_KEYS,
    "basis.buildSpecification",
  );
  assert(
    manifest.basis.buildSpecification.path === BUILD_SPECIFICATION_PATH,
    `build specification path must be ${BUILD_SPECIFICATION_PATH}`,
  );
  assert(
    manifest.basis.buildSpecification.sha256 === BUILD_SPECIFICATION_SHA256,
    "build specification declared SHA-256 is incorrect",
  );
  readBoundFile(
    root,
    BUILD_SPECIFICATION_PATH,
    BUILD_SPECIFICATION_SHA256,
    "build specification",
  );

  const readmePath = resolvePath(candidateRoot, "README.md");
  assert(existsSync(readmePath), "Module 3 candidate README is missing");
  const readme = readFileSync(readmePath, "utf8");
  assert(
    readme.includes("not approved for implementation") &&
      readme.includes("implementationAuthorized: false"),
    "Module 3 candidate README must preserve the non-authorizing boundary",
  );

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
      `${label}.path must stay in the Module 3 candidate directory`,
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
    contract: "contracts/module-3-candidate-inputs.json",
    module: "M3",
    status: CANDIDATE_STATUS,
    candidateVersion: manifest.candidateVersion,
    acceptedModule2RecordId: ACCEPTED_M2_RECORD_ID,
    acceptedProductDefinitionSha256: PRODUCT_DEFINITION_SHA256,
    productAcceptanceRecordId: PRODUCT_ACCEPTANCE_RECORD_ID,
    buildSpecificationSha256: BUILD_SPECIFICATION_SHA256,
    screensCovered: MODULE_3_SCREENS.length,
    entitiesCovered: MODULE_3_ENTITIES.length,
    decisionFamiliesCovered: DECISION_FAMILIES.length,
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
      arguments_.manifest ?? "contracts/module-3-candidate-inputs.json",
    );
    const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
    console.log(
      JSON.stringify(
        verifyModule3CandidateInputs(manifest, {
          rootDirectory: repositoryRoot,
        }),
        null,
        2,
      ),
    );
  } catch (error) {
    console.error(`Module 3 candidate inputs: FAIL\n${error.message}`);
    process.exitCode = 1;
  }
}
