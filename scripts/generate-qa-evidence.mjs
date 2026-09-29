import { mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { FIXTURE_MANIFEST_DIGEST } from "./build-s3-test-fixture.mjs";

const HTTP_METHODS = new Set([
  "delete",
  "get",
  "head",
  "options",
  "patch",
  "post",
  "put",
  "trace",
]);

function integer(value) {
  if (value === undefined || value === null || value === "") return null;
  const parsed = Number.parseInt(String(value), 10);
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null;
}

function status(value) {
  if (!value) return "NOT_EVALUATED";
  return String(value).toLowerCase() === "success"
    ? "PASS"
    : String(value).toUpperCase();
}

export function countVitestList(document) {
  if (!Array.isArray(document)) throw new Error("Vitest list must be an array");
  return document.length;
}

export function countPlaywrightList(document) {
  let tests = 0;
  const visit = (suite) => {
    for (const spec of suite.specs ?? []) tests += (spec.tests ?? []).length;
    for (const child of suite.suites ?? []) visit(child);
  };
  for (const suite of document.suites ?? []) visit(suite);
  return tests;
}

export function summarizeSurefireXmlTexts(texts) {
  const totals = { errors: 0, failures: 0, skipped: 0, tests: 0 };
  for (const text of texts) {
    const suite = text.match(/<testsuite\b[^>]*>/)?.[0];
    if (!suite) throw new Error("Surefire report does not contain a testsuite");
    for (const key of Object.keys(totals)) {
      const value = suite.match(new RegExp(`\\b${key}="(\\d+)"`))?.[1];
      if (value === undefined) throw new Error(`Surefire report omits ${key}`);
      totals[key] += Number.parseInt(value, 10);
    }
  }
  return totals;
}

function currentSchema(root) {
  const migrations = readdirSync(
    join(root, "backend", "src", "main", "resources", "db", "migration"),
  );
  const versions = migrations
    .map((name) => Number.parseInt(name.match(/^V(\d+)__/)?.[1] ?? "", 10))
    .filter(Number.isSafeInteger);
  if (versions.length === 0) throw new Error("No Flyway migrations found");
  return `V${Math.max(...versions)}`;
}

function openApiFacts(root) {
  const document = JSON.parse(
    readFileSync(
      join(root, "contracts", "openapi", "careos-foundation.json"),
      "utf8",
    ),
  );
  let operations = 0;
  for (const pathItem of Object.values(document.paths ?? {})) {
    for (const method of Object.keys(pathItem ?? {})) {
      if (HTTP_METHODS.has(method.toLowerCase())) operations += 1;
    }
  }
  const screens =
    document.components?.schemas?.SystemSummary?.properties?.screenCount?.const;
  if (!Number.isSafeInteger(screens)) {
    throw new Error("OpenAPI SystemSummary.screenCount const is missing");
  }
  return { openapi: document.openapi, operations, screens };
}

function gitCommit(root, environment) {
  if (/^[0-9a-f]{40}$/i.test(environment.GITHUB_SHA ?? "")) {
    return environment.GITHUB_SHA.toLowerCase();
  }
  const head = readFileSync(join(root, ".git", "HEAD"), "utf8").trim();
  if (/^[0-9a-f]{40}$/i.test(head)) return head.toLowerCase();
  const reference = head.match(/^ref:\s+(.+)$/)?.[1];
  if (!reference) throw new Error("Git HEAD is not a commit or reference");
  try {
    return readFileSync(join(root, ".git", reference), "utf8").trim();
  } catch {
    const packed = readFileSync(join(root, ".git", "packed-refs"), "utf8");
    const match = packed
      .split(/\r?\n/)
      .find((line) => line.endsWith(` ${reference}`));
    if (!match) throw new Error(`Unable to resolve Git reference ${reference}`);
    return match.split(" ")[0];
  }
}

function workingTreeDirty(root, environment) {
  if (environment.QA_EVIDENCE_WORKTREE_DIRTY !== undefined) {
    return environment.QA_EVIDENCE_WORKTREE_DIRTY === "true";
  }
  return (
    execFileSync("git", ["status", "--porcelain", "--untracked-files=normal"], {
      cwd: root,
      encoding: "utf8",
    }).trim().length > 0
  );
}

export function repositoryReleaseGate({
  origin,
  qualityPassed,
  securityStatus,
  treeDirty,
}) {
  if (!qualityPassed) return "FAIL";
  if (origin !== "hosted-ci" || treeDirty || securityStatus === "NOT_EVALUATED") {
    return "NOT_EVALUATED";
  }
  return securityStatus === "PASS" ? "PASS" : "FAIL";
}

export function buildEvidence({
  environment = process.env,
  now = new Date(),
  root = resolve(fileURLToPath(new URL("..", import.meta.url))),
} = {}) {
  const facts = openApiFacts(root);
  const commit = gitCommit(root, environment);
  const origin =
    environment.QA_EVIDENCE_ORIGIN ??
    (environment.GITHUB_ACTIONS === "true" ? "hosted-ci" : "local");
  const treeDirty = workingTreeDirty(root, environment);
  const coreTests = integer(environment.BACKEND_CORE_TESTS);
  const compatibilityTests = integer(environment.COMPATIBILITY_TESTS);
  const requiredResults = [
    environment.FRONTEND_RESULT,
    environment.BROWSER_RESULT,
    environment.BACKEND_RESULT,
    environment.COMPATIBILITY_RESULT,
    environment.CONTRACTS_RESULT,
    environment.PRODUCT_SMOKE_RESULT,
  ];
  const qualityPassed = requiredResults.every(
    (result) => String(result).toLowerCase() === "success",
  );
  const securityStatus = status(environment.SECURITY_WORKFLOW_RESULT);
  return {
    evidenceVersion: 1,
    commit,
    source: {
      commit,
      workingTree: treeDirty ? "DIRTY" : "CLEAN",
      commitBound: origin === "hosted-ci" && !treeDirty,
    },
    generatedAt: now.toISOString(),
    environment: origin,
    schema: currentSchema(root),
    openapi: facts.openapi,
    screens: facts.screens,
    operations: facts.operations,
    backend: {
      tests:
        coreTests === null || compatibilityTests === null
          ? null
          : coreTests + compatibilityTests,
      coreTests,
      compatibilityTests,
      status: status(environment.BACKEND_RESULT),
    },
    frontend: {
      tests: integer(environment.FRONTEND_TESTS),
      status: status(environment.FRONTEND_RESULT),
    },
    browser: {
      tests: integer(environment.BROWSER_TESTS),
      viewports: [1440, 1024, 768, 390, 320],
      status: status(environment.BROWSER_RESULT),
    },
    compatibility: {
      fixtureImage: `careos-s3-test-fixture@${FIXTURE_MANIFEST_DIGEST}`,
      tests: compatibilityTests,
      status: status(environment.COMPATIBILITY_RESULT),
    },
    contracts: { status: status(environment.CONTRACTS_RESULT) },
    productSmoke: {
      artifact:
        environment.PRODUCT_SMOKE_ARTIFACT ??
        `authenticated-product-audit-${commit}`,
      status: status(environment.PRODUCT_SMOKE_RESULT),
    },
    securityWorkflow: {
      status: securityStatus,
      note:
        environment.SECURITY_WORKFLOW_RESULT === undefined
          ? "Separate workflow; not evaluated by this quality run."
          : undefined,
    },
    imageEvidence: environment.IMAGE_EVIDENCE_ID ?? null,
    sbomEvidence: environment.SBOM_EVIDENCE_ID ?? null,
    qualityGate: qualityPassed ? "PASS" : "FAIL",
    repositoryReleaseGate: repositoryReleaseGate({
      origin,
      qualityPassed,
      securityStatus,
      treeDirty,
    }),
    productionAcceptance: "NOT_GRANTED",
  };
}

function printGitHubOutputs(values) {
  for (const [key, value] of Object.entries(values)) {
    process.stdout.write(`${key}=${value}\n`);
  }
}

function main() {
  const [command, input] = process.argv.slice(2);
  if (command === "--count-vitest") {
    printGitHubOutputs({
      tests: countVitestList(JSON.parse(readFileSync(input, "utf8"))),
    });
    return;
  }
  if (command === "--count-playwright") {
    printGitHubOutputs({
      tests: countPlaywrightList(JSON.parse(readFileSync(input, "utf8"))),
    });
    return;
  }
  if (command === "--count-surefire") {
    const reports = readdirSync(input)
      .filter((name) => /^TEST-.*\.xml$/.test(name))
      .map((name) => readFileSync(join(input, name), "utf8"));
    printGitHubOutputs(summarizeSurefireXmlTexts(reports));
    return;
  }
  const output = command === "--output" ? input : "build/qa-evidence.json";
  const evidence = buildEvidence();
  mkdirSync(dirname(output), { recursive: true });
  writeFileSync(output, `${JSON.stringify(evidence, null, 2)}\n`);
  process.stdout.write(`${JSON.stringify(evidence, null, 2)}\n`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) main();
