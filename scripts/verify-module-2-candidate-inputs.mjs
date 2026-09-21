import { createHash } from "node:crypto";
import { existsSync, lstatSync, readFileSync, realpathSync } from "node:fs";
import { relative as relativePath, resolve as resolvePath, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export const CANDIDATE_STATUS = "CANDIDATE_FOR_APPROVAL";
export const CANDIDATE_VERSION = "m2-candidate-1";
export const ACCEPTED_M1_COMMIT = "2ba6c9b3b567d0371c8523e6f18945ec33138ae4";
export const BUILD_SPECIFICATION_PATH =
  "docs/CareOS_Complete_Build_Specification_Java_Spring_Boot_React_Node_Edition.pdf";
export const BUILD_SPECIFICATION_SHA256 =
  "2c8f9f020c7c1a21678c795df57fd8be1b659f2141ab7c8747b5dec973db0bfe";

export const MODULE_2_SCREENS = Object.freeze(
  Array.from({ length: 29 }, (_, index) => `M2-${String(index + 1).padStart(2, "0")}`),
);

export const MODULE_2_TABLES = Object.freeze([
  "person_profiles",
  "person_profile_aliases",
  "person_contacts",
  "person_addresses",
  "organization_person_links",
  "person_match_keys",
  "person_merge_requests",
  "workforce_members",
  "workforce_identifiers",
  "employment_engagements",
  "practitioner_profiles",
  "professional_registrations",
  "qualifications",
  "practitioner_specialties",
  "practitioner_credentials",
  "credential_documents",
  "credential_scan_attempts",
  "credential_verifications",
  "scope_definitions",
  "scope_requirements",
  "scopes_of_practice",
  "scope_activities",
  "scope_restrictions",
  "workforce_assignments",
  "practitioner_service_assignments",
  "availability_profiles",
  "availability_periods",
  "availability_exceptions",
  "access_assignment_scopes",
  "workforce_readiness_runs",
  "workforce_readiness_results",
  "workforce_activation_requests",
  "workforce_offboarding_requests",
  "workforce_lifecycle_transitions",
  "workforce_configuration_snapshots",
  "workforce_configuration_change_requests",
  "workforce_configuration_change_items",
  "workforce_export_jobs",
  "credential_legal_holds",
  "practitioner_eligibility_evidence",
  "workforce_notification_deliveries",
  "workforce_registry_definitions",
  "workforce_registry_entries",
  "workforce_registry_versions",
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
const BASIS_KEYS = Object.freeze(["acceptedModule1Commit", "buildSpecification"]);
const SPECIFICATION_KEYS = Object.freeze(["path", "sha256"]);
const ARTIFACT_KEYS = Object.freeze(["kind", "version", "path"]);
const VERSION = /^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$/;
const CANDIDATE_PATH =
  /^candidate-inputs\/module-2\/[A-Za-z0-9][A-Za-z0-9._/-]{0,479}\.(?:md|html)$/;
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
    "filedropzone",
    "evidenceviewer",
    "approvalpanel",
    "clinical scope ≠ application access",
  ],
  "data-dictionary-and-validation": [
    "organization_id",
    "lock_version",
    "uuidv7",
    "forced rls",
    "44-table",
    "no second authentication",
    "validation catalogue",
  ],
  "lifecycle-and-transition-matrix": [
    "maker/checker",
    "idempotency",
    "credential document",
    "offboarding",
    "audit",
    "outbox",
  ],
  "authorization-policy": [
    "deny-by-default",
    "canonical m1",
    "clinical scope",
    "application access",
    "recent-authentication",
    "mfa",
    "service identities",
  ],
  "audit-and-event-registry": [
    "required payload",
    "outbox",
    "consumer",
    "version 1",
    "prohibited sensitive",
  ],
  "readiness-and-activation-policy": [
    "server-calculated",
    "15 minutes",
    "30 minutes",
    "no readiness or eligibility override",
    "result digest",
    "non_clinical",
  ],
  "history-and-export-policy": [
    "purpose",
    "csv cells",
    "retained",
    "redaction",
    "cursor",
    "legal hold",
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
  return relative === "" || (!relative.startsWith(`..${sep}`) && relative !== "..");
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function validateScreenMockupContent(content) {
  assert(
    content.includes('<meta name="careos:artifact-kind" content="screen-mockups" />'),
    "screen-mockups: artifact metadata is missing or mismatched",
  );
  assert(
    content.includes(`<meta name="careos:status" content="${CANDIDATE_STATUS}" />`),
    `screen-mockups: status must remain ${CANDIDATE_STATUS}`,
  );
  assert(
    content.includes(`<meta name="careos:candidate-version" content="${CANDIDATE_VERSION}" />`),
    "screen-mockups: candidate version metadata is missing",
  );
  assert(
    content.includes("CANDIDATE FOR APPROVAL") && content.includes("Not approved"),
    "screen-mockups: visible non-approval boundary is missing",
  );
  for (const width of ["1440", "1024", "768", "390", "320"]) {
    assert(content.includes(width), `screen-mockups: responsive width is missing: ${width}`);
  }
  for (const fragment of [
    'id="state-select"',
    'class="record-cards"',
    'id="review-dialog"',
    'aria-live="polite"',
    "prefers-reduced-motion",
    "No parallel RBAC",
    "No readiness override",
  ]) {
    assert(content.includes(fragment), `screen-mockups: required interaction contract is missing: ${fragment}`);
  }
  for (const screen of MODULE_2_SCREENS) {
    assert(content.includes(screen), `screen-mockups: screen coverage is missing: ${screen}`);
  }
  for (const [pattern, label] of PROHIBITED_MOCKUP_CAPABILITIES) {
    assert(!pattern.test(content), `screen-mockups: self-contained review artifact must not use ${label}`);
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
    assert(content.includes(heading), `${kind}: required section is missing: ${heading}`);
  }
  const normalized = content.toLocaleLowerCase("en");
  for (const topic of REQUIRED_TOPICS[kind] ?? []) {
    assert(
      normalized.includes(topic.toLocaleLowerCase("en")),
      `${kind}: required candidate topic is missing: ${topic}`,
    );
  }
  if (kind === "data-dictionary-and-validation") {
    for (const table of MODULE_2_TABLES) {
      assert(content.includes(`\`${table}\``), `data-dictionary-and-validation: table is missing: ${table}`);
    }
  }
}

export function verifyModule2CandidateInputs(manifest, options = {}) {
  const root = resolvePath(options.rootDirectory ?? process.cwd());
  const candidateRoot = resolvePath(root, "candidate-inputs/module-2");
  assertPlainObject(manifest, "candidate manifest");
  assertExactKeys(manifest, TOP_LEVEL_KEYS, "candidate manifest");
  assert(manifest.schemaVersion === 1, "schemaVersion must be 1");
  assert(manifest.module === "M2", "module must be M2");
  assert(
    manifest.status === CANDIDATE_STATUS,
    `candidate status must be ${CANDIDATE_STATUS}`,
  );
  assert(VERSION.test(manifest.candidateVersion), "candidateVersion is invalid");
  assert(manifest.candidateVersion === CANDIDATE_VERSION, `candidateVersion must be ${CANDIDATE_VERSION}`);

  assertPlainObject(manifest.basis, "basis");
  assertExactKeys(manifest.basis, BASIS_KEYS, "basis");
  assert(
    manifest.basis.acceptedModule1Commit === ACCEPTED_M1_COMMIT,
    `basis.acceptedModule1Commit must be ${ACCEPTED_M1_COMMIT}`,
  );
  assertPlainObject(manifest.basis.buildSpecification, "basis.buildSpecification");
  assertExactKeys(manifest.basis.buildSpecification, SPECIFICATION_KEYS, "basis.buildSpecification");
  assert(
    manifest.basis.buildSpecification.path === BUILD_SPECIFICATION_PATH,
    `build specification path must be ${BUILD_SPECIFICATION_PATH}`,
  );
  assert(
    manifest.basis.buildSpecification.sha256 === BUILD_SPECIFICATION_SHA256,
    "build specification declared SHA-256 is incorrect",
  );
  const specificationPath = resolvePath(root, BUILD_SPECIFICATION_PATH);
  assert(existsSync(specificationPath), "build specification is missing");
  const specification = readFileSync(specificationPath);
  assert(sha256(specification) === BUILD_SPECIFICATION_SHA256, "build specification bytes do not match the bound SHA-256");

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
    assert(artifact.kind === expectedKind, `${label}.kind must be ${expectedKind}`);
    assert(VERSION.test(artifact.version), `${label}.version is invalid`);
    assert(artifact.version === manifest.candidateVersion, `${label}.version must match candidateVersion`);
    assert(CANDIDATE_PATH.test(artifact.path), `${label}.path must stay in the Module 2 candidate directory`);
    assert(!paths.has(artifact.path), `${label}.path is duplicated`);
    paths.add(artifact.path);

    const candidate = resolvePath(root, artifact.path);
    assert(isWithinRoot(candidateRoot, candidate), `${label}.path escapes the candidate directory`);
    assert(existsSync(candidate), `${label}.path is missing: ${artifact.path}`);
    const stat = lstatSync(candidate);
    assert(!stat.isSymbolicLink(), `${label}.path must not be a symbolic link`);
    assert(stat.isFile(), `${label}.path must be a regular file`);
    const realCandidate = realpathSync(candidate);
    const realCandidateRoot = realpathSync(candidateRoot);
    assert(isWithinRoot(realCandidateRoot, realCandidate), `${label}.path resolves outside the candidate directory`);

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
    contract: "contracts/module-2-candidate-inputs.json",
    module: "M2",
    status: CANDIDATE_STATUS,
    candidateVersion: manifest.candidateVersion,
    acceptedModule1Commit: ACCEPTED_M1_COMMIT,
    buildSpecificationSha256: BUILD_SPECIFICATION_SHA256,
    screensCovered: MODULE_2_SCREENS.length,
    tablesCovered: MODULE_2_TABLES.length,
    artifactsVerified: descriptors.length,
    candidatePackageSha256,
    implementationAuthorized: false,
  };
}

function parseArguments(arguments_) {
  if (arguments_.length === 0) return {};
  assert(arguments_.length === 2 && arguments_[0] === "--manifest", "usage: --manifest PATH");
  return { manifest: arguments_[1] };
}

const invokedModule = process.argv[1] ? pathToFileURL(resolvePath(process.argv[1])).href : undefined;

if (import.meta.url === invokedModule) {
  try {
    const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));
    const arguments_ = parseArguments(process.argv.slice(2));
    const manifestPath = resolvePath(
      repositoryRoot,
      arguments_.manifest ?? "contracts/module-2-candidate-inputs.json",
    );
    const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
    console.log(
      JSON.stringify(verifyModule2CandidateInputs(manifest, { rootDirectory: repositoryRoot }), null, 2),
    );
  } catch (error) {
    console.error(`Module 2 candidate inputs: FAIL\n${error.message}`);
    process.exitCode = 1;
  }
}
