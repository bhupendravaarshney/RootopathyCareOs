import { readFileSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const FULL_COMMIT_SHA = /^[0-9a-f]{40}$/;
const IMAGE_DIGEST = /@sha256:[0-9a-f]{64}(?:\s|$)/i;

function requiredFile(root, relativePath, errors) {
  try {
    return readFileSync(join(root, relativePath), "utf8");
  } catch {
    errors.push(`${relativePath}: required file is missing or unreadable`);
    return "";
  }
}

export function validateWorkflowText(name, text) {
  const errors = [];
  const jobsOffset = text.search(/^jobs:\s*$/m);
  const header = jobsOffset >= 0 ? text.slice(0, jobsOffset) : text;

  if (jobsOffset < 0) {
    errors.push(`${name}: jobs section is missing`);
  }
  const permissions = header.match(
    /^permissions:\s*\r?\n((?:^[ \t]+[^\r\n]*(?:\r?\n|$))*)/m,
  );
  const permissionEntries = (permissions?.[1] ?? "")
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith("#"));
  if (
    permissionEntries.length !== 1 ||
    permissionEntries[0] !== "contents: read"
  ) {
    errors.push(
      `${name}: top-level permissions must contain only contents: read`,
    );
  }
  if (
    !/^concurrency:\s*$/m.test(header) ||
    !/^  cancel-in-progress:\s*true\s*$/m.test(header)
  ) {
    errors.push(`${name}: concurrency cancellation must be enabled`);
  }
  if (/^\s*pull_request_target\s*:/m.test(text)) {
    errors.push(
      `${name}: pull_request_target is forbidden for repository code workflows`,
    );
  }
  if (/^\s*runs-on:\s*[^#\r\n]*-latest\s*(?:#.*)?$/m.test(text)) {
    errors.push(
      `${name}: hosted runner versions must be explicit rather than *-latest`,
    );
  }

  if (name === "quality.yml") {
    const requiredModuleInputCommands = [
      "node scripts/verify-module-1-inputs.mjs --require-approved",
      "node --test scripts/tests/verify-module-1-inputs.test.mjs",
      "node scripts/verify-module-1-review-drafts.mjs",
      "node --test scripts/tests/verify-module-1-review-drafts.test.mjs",
      "node scripts/verify-module-1-candidate-inputs.mjs",
      "node --test scripts/tests/verify-module-1-candidate-inputs.test.mjs",
      "node scripts/verify-module-1-facility-scope-candidate.mjs",
      "node --test scripts/tests/verify-module-1-facility-scope-candidate.test.mjs",
    ];
    for (const command of requiredModuleInputCommands) {
      if (
        !text.split(/\r?\n/).some((line) => line.trim() === `- run: ${command}`)
      ) {
        errors.push(
          `${name}: contracts job must run the Module 1 input/review/candidate/facility-scope command: ${command}`,
        );
      }
    }
  }

  const lines = text.split(/\r?\n/);
  for (let index = 0; index < lines.length; index += 1) {
    const match = lines[index].match(
      /^\s*(?:-\s*)?uses:\s*([^@\s]+)@([^\s#]+)(?:\s+#\s*(.+))?\s*$/,
    );
    if (!match) {
      continue;
    }
    const [, action, reference, releaseComment] = match;
    if (!action.startsWith("./") && !FULL_COMMIT_SHA.test(reference)) {
      errors.push(
        `${name}:${index + 1}: ${action} must use a full 40-character commit SHA`,
      );
    }
    if (
      !action.startsWith("./") &&
      !/^v?\d+(?:\.\d+){1,2}(?:\b|$)/.test(releaseComment ?? "")
    ) {
      errors.push(
        `${name}:${index + 1}: ${action} must retain an auditable release comment`,
      );
    }
    if (action !== "actions/checkout") {
      continue;
    }

    const actionIndent = lines[index].match(/^\s*/)[0].length;
    let blockEnd = index + 1;
    while (blockEnd < lines.length) {
      const nextStep = lines[blockEnd].match(/^(\s*)-\s+/);
      if (nextStep && nextStep[1].length <= actionIndent) {
        break;
      }
      blockEnd += 1;
    }
    const block = lines.slice(index + 1, blockEnd).join("\n");
    if (!/^\s*persist-credentials:\s*false\s*$/m.test(block)) {
      errors.push(
        `${name}:${index + 1}: checkout must set persist-credentials to false`,
      );
    }
  }

  if (jobsOffset >= 0) {
    const jobsText = text.slice(jobsOffset);
    const jobMatches = [...jobsText.matchAll(/^  ([a-zA-Z0-9_-]+):\s*$/gm)];
    if (jobMatches.length === 0) {
      errors.push(`${name}: no jobs were found`);
    }
    for (let index = 0; index < jobMatches.length; index += 1) {
      const current = jobMatches[index];
      const start = current.index;
      const end = jobMatches[index + 1]?.index ?? jobsText.length;
      const job = jobsText.slice(start, end);
      if (!/^    timeout-minutes:\s*[1-9][0-9]*\s*$/m.test(job)) {
        errors.push(`${name}: job ${current[1]} must declare timeout-minutes`);
      }
    }
  }

  return errors;
}

export function validateDockerfileText(name, text) {
  const errors = [];
  const lines = text.split(/\r?\n/);
  const fromIndexes = [];

  for (let index = 0; index < lines.length; index += 1) {
    if (!/^FROM\s+/i.test(lines[index].trim())) {
      continue;
    }
    fromIndexes.push(index);
    if (!IMAGE_DIGEST.test(`${lines[index]} `)) {
      errors.push(
        `${name}:${index + 1}: every base image must be pinned by sha256 digest`,
      );
    }
  }

  if (fromIndexes.length === 0) {
    errors.push(`${name}: no FROM instruction found`);
    return errors;
  }

  const finalStage = lines.slice(fromIndexes.at(-1) + 1);
  const userLine = finalStage
    .filter((line) => /^USER\s+/i.test(line.trim()))
    .at(-1);
  if (
    !userLine ||
    /^USER\s+(?:root|0)(?::[^\s]+)?\s*$/i.test(userLine.trim())
  ) {
    errors.push(`${name}: final image stage must declare a non-root USER`);
  }
  return errors;
}

export function validateNginxText(name, text) {
  const errors = [];
  if (name === "frontend/nginx-main.conf") {
    if (!/^\s*server_tokens\s+off;\s*$/m.test(text)) {
      errors.push(`${name}: server version tokens must be disabled`);
    }
    return errors;
  }

  if (name !== "frontend/nginx.conf") {
    return errors;
  }

  const requiredHeaders = [
    "Content-Security-Policy",
    "Cross-Origin-Opener-Policy",
    "Cross-Origin-Resource-Policy",
    "Permissions-Policy",
    "Referrer-Policy",
    "Strict-Transport-Security",
    "X-Content-Type-Options",
    "X-Frame-Options",
    "X-Permitted-Cross-Domain-Policies",
    "X-XSS-Protection",
  ];
  for (const header of requiredHeaders) {
    const pattern = new RegExp(
      `^\\s*add_header\\s+${header}\\s+"[^"]+"\\s+always;\\s*$`,
      "m",
    );
    if (!pattern.test(text)) {
      errors.push(`${name}: ${header} must be set on every response`);
    }
  }

  const contentSecurityPolicy = text.match(
    /^\s*add_header\s+Content-Security-Policy\s+"([^"]+)"\s+always;\s*$/m,
  )?.[1];
  const requiredDirectives = [
    "default-src 'self'",
    "connect-src 'self'",
    "frame-ancestors 'none'",
    "object-src 'none'",
    "script-src 'self'",
    "style-src 'self'",
  ];
  if (
    !contentSecurityPolicy ||
    requiredDirectives.some(
      (directive) => !contentSecurityPolicy.includes(directive),
    )
  ) {
    errors.push(`${name}: CSP must retain the strict same-origin directives`);
  }
  if (
    /\bunsafe-(?:eval|inline)\b|(?:^|[ ;])\*/.test(contentSecurityPolicy ?? "")
  ) {
    errors.push(
      `${name}: CSP must not allow unsafe script/style or wildcard sources`,
    );
  }

  for (const [pattern, description] of [
    [/^\s*proxy_connect_timeout\s+3s;\s*$/m, "a bounded proxy connect timeout"],
    [/^\s*proxy_send_timeout\s+30s;\s*$/m, "a bounded proxy send timeout"],
    [/^\s*proxy_read_timeout\s+30s;\s*$/m, "a bounded proxy read timeout"],
    [
      /^\s*proxy_set_header\s+Connection\s+"";\s*$/m,
      "hop-by-hop header removal",
    ],
  ]) {
    if (!pattern.test(text)) {
      errors.push(`${name}: API proxy must declare ${description}`);
    }
  }
  return errors;
}

export function validateProductionConfigText(name, text) {
  const errors = [];
  const requiredEnvironmentValues = [
    "DB_URL",
    "DB_APP_USERNAME",
    "DB_APP_PASSWORD",
    "DB_MIGRATION_URL",
    "DB_MIGRATION_USERNAME",
    "DB_MIGRATION_PASSWORD",
    "REDIS_HOST",
    "REDIS_USERNAME",
    "REDIS_PASSWORD",
    "SMTP_HOST",
    "SMTP_USERNAME",
    "SMTP_PASSWORD",
    "CAREOS_ALLOWED_ORIGINS",
    "CAREOS_BASE_URL",
    "CAREOS_SECURITY_MAIL_FROM",
    "CAREOS_TOKEN_PEPPER",
    "CAREOS_MFA_ENCRYPTION_KEY",
  ];
  if (!/^\s*on-profile:\s*production\s*$/m.test(text)) {
    errors.push(`${name}: configuration must activate only for production`);
  }
  for (const environmentValue of requiredEnvironmentValues) {
    const pattern = new RegExp(
      `^\\s*[a-z][a-z0-9-]*:\\s*\\$\\{${environmentValue}\\}\\s*$`,
      "m",
    );
    if (!pattern.test(text)) {
      errors.push(
        `${name}: ${environmentValue} must be required without a fallback`,
      );
    }
  }

  const requiredControls = [
    [/^\s*enabled:\s*true\s*$/m, "Redis TLS"],
    [/^\s*auth:\s*true\s*$/m, "SMTP authentication"],
    [/^\s*checkserveridentity:\s*true\s*$/m, "SMTP identity verification"],
    [/^\s*enable:\s*true\s*$/m, "SMTP STARTTLS"],
    [/^\s*required:\s*true\s*$/m, "mandatory SMTP STARTTLS"],
    [/^\s*secure:\s*true\s*$/m, "secure session cookies"],
    [/^\s*allow-http:\s*false\s*$/m, "the S3 HTTP override denial"],
    [
      /^\s*create-bucket-if-missing:\s*false\s*$/m,
      "the S3 runtime bucket-creation denial",
    ],
  ];
  for (const [pattern, description] of requiredControls) {
    if (!pattern.test(text)) {
      errors.push(`${name}: missing ${description}`);
    }
  }
  if (
    /careos-(?:app|migrator)-(?:local|test)-only|CareOS-(?:Local|Test)-|careos-local(?:-change-me)?|http:\/\//.test(
      text,
    )
  ) {
    errors.push(
      `${name}: production configuration contains local-only material`,
    );
  }
  return errors;
}

export function validateBaseConfigText(name, text) {
  const errors = [];
  const requiredEnvironmentValues = [
    "DB_URL",
    "DB_APP_USERNAME",
    "DB_APP_PASSWORD",
    "DB_MIGRATION_USERNAME",
    "DB_MIGRATION_PASSWORD",
    "REDIS_HOST",
    "SMTP_HOST",
    "CAREOS_ALLOWED_ORIGINS",
    "CAREOS_BASE_URL",
    "CAREOS_SECURITY_MAIL_FROM",
    "CAREOS_TOKEN_PEPPER",
    "CAREOS_MFA_ENCRYPTION_KEY",
  ];
  for (const environmentValue of requiredEnvironmentValues) {
    const pattern = new RegExp(
      `^\\s*[a-z][a-zA-Z0-9-]*:\\s*\\$\\{${environmentValue}\\}\\s*$`,
      "m",
    );
    if (!pattern.test(text)) {
      errors.push(
        `${name}: ${environmentValue} must be required without a base fallback`,
      );
    }
  }
  if (
    /careos-(?:app|migrator)-(?:local|test)-only|CareOS-(?:Local|Test)-|CAREOS_ALLOWED_ORIGINS:http|CAREOS_BASE_URL:http|^\s*bootstrap-admin:\s*$/m.test(
      text,
    )
  ) {
    errors.push(
      `${name}: local-only defaults must live in application-local.yml`,
    );
  }
  return errors;
}

export function validateFoundationDataScopeTexts({
  base,
  local,
  production,
  test,
  migration,
}) {
  const errors = [];
  const requirements = [
    [
      base,
      /^\s*foundationSyntheticDataEnabled:\s*false\s*$/m,
      "application.yml must hard-disable synthetic foundation data",
    ],
    [
      local,
      /^\s*foundationSyntheticDataEnabled:\s*true\s*$/m,
      "application-local.yml must explicitly scope synthetic foundation data to local use",
    ],
    [
      production,
      /^\s*foundationSyntheticDataEnabled:\s*false\s*$/m,
      "application-production.yml must hard-disable synthetic foundation data",
    ],
    [
      test,
      /^\s*foundationSyntheticDataEnabled:\s*true\s*$/m,
      "application-test.yml must explicitly retain isolated test fixtures",
    ],
  ];
  for (const [text, pattern, message] of requirements) {
    if (!pattern.test(text)) {
      errors.push(message);
    }
  }

  const migrationControls = [
    [
      /\$\{foundationSyntheticDataEnabled\}/,
      "V19 must consume the scoped Flyway placeholder",
    ],
    [
      /IF\s+synthetic_data_enabled\s+THEN/i,
      "V19 must retain fixtures only through the explicit opt-in",
    ],
    [
      /DELETE\s+FROM\s+facilities/i,
      "V19 must remove the legacy synthetic facility when disabled",
    ],
    [
      /DELETE\s+FROM\s+organizations/i,
      "V19 must remove the legacy synthetic organization when disabled",
    ],
    [
      /WHEN\s+foreign_key_violation\s+THEN/i,
      "V19 must fail closed instead of cascading dependent tenant data",
    ],
  ];
  for (const [pattern, message] of migrationControls) {
    if (!pattern.test(migration)) {
      errors.push(message);
    }
  }
  return errors;
}

export function validateComposeText(name, text) {
  const errors = [];
  for (const [index, line] of text.split(/\r?\n/).entries()) {
    const match = line.match(/^\s*image:\s*["']?([^"'\s]+)["']?\s*(?:#.*)?$/);
    if (match && !IMAGE_DIGEST.test(`${match[1]} `)) {
      errors.push(
        `${name}:${index + 1}: service image must be pinned by sha256 digest`,
      );
    }
  }

  if (name !== "compose.yaml") {
    return errors;
  }

  const serviceMatches = [...text.matchAll(/^  ([a-zA-Z0-9_-]+):\s*$/gm)];
  const postgresIndex = serviceMatches.findIndex(
    (match) => match[1] === "postgres",
  );
  if (postgresIndex >= 0) {
    const start = serviceMatches[postgresIndex].index;
    const end = serviceMatches[postgresIndex + 1]?.index ?? text.length;
    const postgres = text.slice(start, end);
    const isPostgres18 =
      /^    image:\s*["']?postgres:18[^\s"']*@sha256:[0-9a-f]{64}["']?\s*(?:#.*)?$/im.test(
        postgres,
      );
    const mountsPostgres18Parent =
      /^      -\s*["']?[^"'\r\n:]+:\/var\/lib\/postgresql["']?\s*(?:#.*)?$/m.test(
        postgres,
      );
    if (isPostgres18 && !mountsPostgres18Parent) {
      errors.push(
        `${name}: PostgreSQL 18 must mount its data volume at /var/lib/postgresql`,
      );
    }
  }

  for (const serviceName of ["backend", "frontend"]) {
    const serviceIndex = serviceMatches.findIndex(
      (match) => match[1] === serviceName,
    );
    if (serviceIndex < 0) {
      continue;
    }
    const start = serviceMatches[serviceIndex].index;
    const end = serviceMatches[serviceIndex + 1]?.index ?? text.length;
    const service = text.slice(start, end);
    const requirements = [
      [/^    read_only:\s*true\s*$/m, "a read-only root filesystem"],
      [
        /^    tmpfs:\s*\r?\n(?:^ {6}[^\r\n]*(?:\r?\n|$))*^      -\s*["']?\/tmp:/m,
        "a writable /tmp tmpfs",
      ],
      [
        /^    cap_drop:\s*\r?\n(?:^ {6}[^\r\n]*(?:\r?\n|$))*^      -\s*["']?ALL["']?\s*$/m,
        "all capabilities dropped",
      ],
      [
        /^    security_opt:\s*\r?\n(?:^ {6}[^\r\n]*(?:\r?\n|$))*^      -\s*["']?no-new-privileges:true["']?\s*$/m,
        "no-new-privileges",
      ],
    ];
    for (const [pattern, description] of requirements) {
      if (!pattern.test(service)) {
        errors.push(`${name}: ${serviceName} must declare ${description}`);
      }
    }
  }
  return errors;
}

export function validateDependabotText(text) {
  const errors = [];
  const blocks = text.split(/\n(?=\s*-\s+package-ecosystem:)/);
  const required = [
    ["npm", "/frontend"],
    ["maven", "/backend"],
    ["docker", "/backend"],
    ["docker", "/frontend"],
    ["docker", "/"],
    ["github-actions", "/"],
  ];

  for (const [ecosystem, directory] of required) {
    const found = blocks.some((block) => {
      const packageMatch = block.match(
        /^\s*-?\s*package-ecosystem:\s*["']?([^"'\s#]+)["']?\s*(?:#.*)?$/m,
      );
      const directoryMatch = block.match(
        /^\s*directory:\s*["']?([^"'\s#]+)["']?\s*(?:#.*)?$/m,
      );
      return (
        packageMatch?.[1] === ecosystem && directoryMatch?.[1] === directory
      );
    });
    if (!found) {
      errors.push(
        `dependabot.yml: missing ${ecosystem} updates for ${directory}`,
      );
    }
  }
  return errors;
}

export function validateResponsiveBrowserTexts({ config, suite }) {
  const errors = [];
  const projects = [
    { height: 900, mobile: false, name: "desktop-1440", width: 1440 },
    { height: 900, mobile: false, name: "compact-1024", width: 1024 },
    { height: 1024, mobile: false, name: "tablet-768", width: 768 },
    { height: 844, mobile: true, name: "mobile-390", width: 390 },
    { height: 800, mobile: true, name: "mobile-320", width: 320 },
  ];
  const namedProjects = [...config.matchAll(/\bname:\s*["']([^"']+)["']/g)];

  for (const project of projects) {
    const matches = namedProjects.filter((match) => match[1] === project.name);
    if (matches.length !== 1) {
      errors.push(
        `frontend/playwright.config.ts: requires exactly one ${project.name} responsive project`,
      );
      continue;
    }
    const start = matches[0].index ?? 0;
    const next = namedProjects.find((match) => (match.index ?? 0) > start);
    const block = config.slice(start, next?.index ?? config.length);
    const viewport = new RegExp(
      `viewport:\\s*\\{\\s*width:\\s*${project.width}\\s*,\\s*height:\\s*${project.height}\\s*\\}`,
    );
    if (!viewport.test(block)) {
      errors.push(
        `frontend/playwright.config.ts: ${project.name} must use the exact ${project.width}x${project.height} viewport`,
      );
    }
    if (project.mobile) {
      if (
        !/\bisMobile:\s*true\b/.test(block) ||
        !/\bhasTouch:\s*true\b/.test(block)
      ) {
        errors.push(
          `frontend/playwright.config.ts: ${project.name} must retain mobile and touch semantics`,
        );
      }
    } else if (!/\.\.\.devices\[["']Desktop Chrome["']\]/.test(block)) {
      errors.push(
        `frontend/playwright.config.ts: ${project.name} must retain the desktop Chromium device contract`,
      );
    }
  }

  const suiteRequirements = [
    [
      "document.documentElement.scrollWidth",
      "document-width overflow assertion",
    ],
    ["document.body.scrollWidth", "body-width overflow assertion"],
    [
      "await expectNoDocumentHorizontalOverflow(page, id);",
      "registered-route overflow check",
    ],
    [
      "await expectNoDocumentHorizontalOverflow(page, 'M1-04 organization selection');",
      "identity-route overflow check",
    ],
    [
      "expect([1440, 1024, 768, 390, 320]).toContain(viewport.width);",
      "exact responsive width assertion",
    ],
    ["const usesDrawer = viewport.width <= 760;", "drawer boundary assertion"],
  ];
  for (const [fragment, label] of suiteRequirements) {
    if (!suite.includes(fragment)) {
      errors.push(`frontend/tests/e2e/prototypes.spec.ts: missing ${label}`);
    }
  }
  return errors;
}

function javaFiles(directory) {
  const files = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const target = join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...javaFiles(target));
    } else if (entry.name.endsWith(".java")) {
      files.push(target);
    }
  }
  return files;
}

export function validateRepository(rootDirectory) {
  const root = resolve(rootDirectory);
  const errors = [];
  const workflowDirectory = join(root, ".github", "workflows");
  let workflowNames = [];

  try {
    workflowNames = readdirSync(workflowDirectory).filter((name) =>
      /\.ya?ml$/i.test(name),
    );
  } catch {
    errors.push(
      ".github/workflows: workflow directory is missing or unreadable",
    );
  }
  for (const name of workflowNames) {
    const text = requiredFile(root, `.github/workflows/${name}`, errors);
    errors.push(...validateWorkflowText(name, text));
  }

  const quality = requiredFile(root, ".github/workflows/quality.yml", errors);
  const security = requiredFile(root, ".github/workflows/security.yml", errors);
  const securityRequirements = [
    "actions/dependency-review-action@",
    "github/codeql-action/init@",
    "github/codeql-action/analyze@",
    "aquasecurity/trivy-action@",
    "scan-type: fs",
    "scanners: vuln,secret,misconfig",
    "TRIVY_OFFLINE_SCAN: true",
    "sh mvnw -B -ntp -DskipTests dependency:go-offline",
    "docker build --pull --tag careos-backend:ci ./backend",
    "docker build --pull --tag careos-frontend:ci ./frontend",
    "format: cyclonedx",
    "image-ref: careos-backend:ci",
    "image-ref: careos-frontend:ci",
    "actions/upload-artifact@",
    "language: [java-kotlin, javascript-typescript]",
  ];
  for (const requirement of securityRequirements) {
    if (!security.includes(requirement)) {
      errors.push(`security.yml: missing required control ${requirement}`);
    }
  }
  for (const requirement of [
    "npm run api:check",
    "npm run architecture:check",
    "node --test scripts/tests/verify-api-contract.test.mjs",
    "node scripts/verify-ci-security.mjs",
    "node --test scripts/tests/verify-ci-security.test.mjs",
  ]) {
    if (!quality.includes(requirement)) {
      errors.push(`quality.yml: missing security-contract gate ${requirement}`);
    }
  }

  for (const dockerfile of ["backend/Dockerfile", "frontend/Dockerfile"]) {
    errors.push(
      ...validateDockerfileText(
        dockerfile,
        requiredFile(root, dockerfile, errors),
      ),
    );
  }
  for (const nginxConfig of [
    "frontend/nginx-main.conf",
    "frontend/nginx.conf",
  ]) {
    errors.push(
      ...validateNginxText(
        nginxConfig,
        requiredFile(root, nginxConfig, errors),
      ),
    );
  }
  const productionConfig =
    "backend/src/main/resources/application-production.yml";
  errors.push(
    ...validateProductionConfigText(
      productionConfig,
      requiredFile(root, productionConfig, errors),
    ),
  );
  const baseConfig = "backend/src/main/resources/application.yml";
  const baseConfigText = requiredFile(root, baseConfig, errors);
  errors.push(...validateBaseConfigText(baseConfig, baseConfigText));
  errors.push(
    ...validateFoundationDataScopeTexts({
      base: baseConfigText,
      local: requiredFile(
        root,
        "backend/src/main/resources/application-local.yml",
        errors,
      ),
      production: requiredFile(root, productionConfig, errors),
      test: requiredFile(
        root,
        "backend/src/test/resources/application-test.yml",
        errors,
      ),
      migration: requiredFile(
        root,
        "backend/src/main/resources/db/migration/V19__scope_synthetic_foundation_data.sql",
        errors,
      ),
    }),
  );
  for (const compose of ["compose.yaml", "compose.scanner.yaml"]) {
    errors.push(
      ...validateComposeText(compose, requiredFile(root, compose, errors)),
    );
  }
  errors.push(
    ...validateDependabotText(
      requiredFile(root, ".github/dependabot.yml", errors),
    ),
  );
  errors.push(
    ...validateResponsiveBrowserTexts({
      config: requiredFile(root, "frontend/playwright.config.ts", errors),
      suite: requiredFile(
        root,
        "frontend/tests/e2e/prototypes.spec.ts",
        errors,
      ),
    }),
  );

  const testRoot = join(root, "backend", "src", "test", "java");
  try {
    for (const file of javaFiles(testRoot)) {
      const text = readFileSync(file, "utf8");
      for (const match of text.matchAll(/(?:postgres|redis):[^"'\s)]+/g)) {
        if (!IMAGE_DIGEST.test(`${match[0]} `)) {
          errors.push(
            `${file}: Testcontainers image ${match[0]} must be digest-pinned`,
          );
        }
      }
    }
  } catch {
    errors.push(
      "backend/src/test/java: unable to validate Testcontainers image pins",
    );
  }

  return {
    root,
    workflows: workflowNames.length,
    errors,
  };
}

function main() {
  const rootFlag = process.argv.indexOf("--root");
  const root = rootFlag >= 0 ? process.argv[rootFlag + 1] : process.cwd();
  if (!root) {
    throw new Error("--root requires a directory");
  }

  const result = validateRepository(root);
  if (result.errors.length > 0) {
    console.error(JSON.stringify({ ...result, status: "FAIL" }, null, 2));
    process.exitCode = 1;
    return;
  }
  console.log(
    JSON.stringify(
      { root: result.root, workflows: result.workflows, status: "PASS" },
      null,
      2,
    ),
  );
}

if (
  process.argv[1] &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main();
}
