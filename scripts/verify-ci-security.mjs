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
      "node scripts/verify-module-2-candidate-inputs.mjs",
      "node --test scripts/tests/verify-module-2-candidate-inputs.test.mjs",
      "node scripts/verify-module-2-inputs.mjs --require-approved",
      "node --test scripts/tests/verify-module-2-inputs.test.mjs",
      "node scripts/verify-module-3-candidate-inputs.mjs",
      "node --test scripts/tests/verify-module-3-candidate-inputs.test.mjs",
      "node scripts/verify-module-3-inputs.mjs --require-approved",
      "node --test scripts/tests/verify-module-3-inputs.test.mjs",
    ];
    for (const command of requiredModuleInputCommands) {
      if (
        !text.split(/\r?\n/).some((line) => line.trim() === `- run: ${command}`)
      ) {
        errors.push(
          `${name}: contracts job must run the required module input command: ${command}`,
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
    const isScratchStage = /^FROM\s+scratch(?:\s+AS\s+\S+)?\s*$/i.test(
      lines[index].trim(),
    );
    if (!isScratchStage && !IMAGE_DIGEST.test(`${lines[index]} `)) {
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
    ["docker", "/test-fixtures/s3"],
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

export function validateS3FixtureTexts({
  clamAvTest,
  compose,
  dockerfile,
  fixtureBuilder,
  integrationTest,
  quality,
}) {
  const errors = [];
  const fixtureImage = "careos-s3-test-fixture:minio-release-2025-09-07";
  const fixtureDigest =
    "sha256:bb6f358423eec8c666f70d24dbab12a0b9467b5071f2bb30ee64767d3dce82d1";
  const sourceEpoch = "1757261589";
  const release = "RELEASE.2025-09-07T16-13-09Z";
  const commit = "07c3a429bfed433e49018cb0f78a52145d4bedeb";
  const sourceSha =
    "c9598dcce3440977e79f787f2ba0e7e4d92c8d556bd51e7cef3785bafd6635f3";

  const dockerfileRequirements = [
    [
      new RegExp(`ARG MINIO_RELEASE=${release.replaceAll(".", "\\.")}`),
      "the immutable upstream release",
    ],
    [new RegExp(`ARG MINIO_COMMIT=${commit}`), "the verified source commit"],
    [
      new RegExp(`ARG MINIO_SOURCE_SHA256=${sourceSha}`),
      "the verified source archive checksum",
    ],
    [
      new RegExp(`ADD --checksum=sha256:${sourceSha}`),
      "a checksum-enforced source fetch",
    ],
    [/^FROM scratch\s*$/m, "a minimal scratch runtime"],
    [/^USER 65532:65532\s*$/m, "a numeric non-root runtime identity"],
  ];
  for (const [pattern, description] of dockerfileRequirements) {
    if (!pattern.test(dockerfile)) {
      errors.push(`test-fixtures/s3/Dockerfile: missing ${description}`);
    }
  }

  for (const [text, name] of [
    [compose, "compose.yaml"],
    [integrationTest, "S3PrivateDocumentStorageIntegrationTest.java"],
    [quality, "quality.yml"],
  ]) {
    if (/quay\.io\/minio\/minio/i.test(text)) {
      errors.push(`${name}: legacy external MinIO image is forbidden`);
    }
  }

  if (
    !/^  minio:\s*\r?\n(?:^ {4,}[^\r\n]*(?:\r?\n|$))*?^    build:\s*\r?\n(?:^ {6,}[^\r\n]*(?:\r?\n|$))*?^      context:\s*\.\/test-fixtures\/s3\s*$/m.test(
      compose,
    )
  ) {
    errors.push(
      "compose.yaml: minio must build the repository-owned S3 compatibility fixture",
    );
  }
  if (!compose.includes(`SOURCE_DATE_EPOCH: "${sourceEpoch}"`)) {
    errors.push("compose.yaml: S3 fixture source epoch is not fixed");
  }
  if (!integrationTest.includes(fixtureImage)) {
    errors.push(
      "S3PrivateDocumentStorageIntegrationTest.java: fixture image tag is missing",
    );
  }
  if (
    !integrationTest.includes(fixtureDigest) ||
    !integrationTest.includes("s3-fixture-metadata.json")
  ) {
    errors.push(
      "S3PrivateDocumentStorageIntegrationTest.java: verified fixture digest evidence is missing",
    );
  }
  if (!/\.withImagePullPolicy\(ignored -> false\)/.test(integrationTest)) {
    errors.push(
      "S3PrivateDocumentStorageIntegrationTest.java: fixture must be local-only after preflight",
    );
  }
  if (!/@Tag\("compatibility"\)/.test(integrationTest)) {
    errors.push(
      "S3PrivateDocumentStorageIntegrationTest.java: compatibility classification is missing",
    );
  }
  if (!/@Tag\("compatibility"\)/.test(clamAvTest ?? "")) {
    errors.push(
      "ClamAvMalwareScannerIntegrationTest.java: compatibility classification is missing",
    );
  }

  for (const [required, description] of [
    [fixtureImage, "the isolated local image tag"],
    [fixtureDigest, "the expected immutable manifest digest"],
    [sourceEpoch, "the fixed source epoch"],
    ['FIXTURE_PLATFORM = "linux/amd64"', "the fixed build platform"],
    ['"--provenance=false"', "disabled non-reproducible provenance"],
    ['"buildx"', "the BuildKit digest-producing build"],
    ['"--metadata-file"', "manifest digest metadata"],
    ['"run", "--rm", FIXTURE_IMAGE, "--version"', "a fixture startup preflight"],
  ]) {
    if (!fixtureBuilder.includes(required)) {
      errors.push(
        `scripts/build-s3-test-fixture.mjs: missing ${description}`,
      );
    }
  }

  const buildCommand = "node scripts/build-s3-test-fixture.mjs";
  const buildIndex = quality.indexOf(buildCommand);
  const compatibilityIndex = quality.indexOf(
    "sh mvnw -B -ntp test -Dgroups=compatibility",
  );
  if (buildIndex < 0) {
    errors.push(
      "quality.yml: S3 compatibility fixture preflight is incomplete",
    );
  } else if (
    compatibilityIndex < 0 ||
    buildIndex > compatibilityIndex
  ) {
    errors.push(
      "quality.yml: S3 fixture build/start preflight must run before Maven verification",
    );
  }
  if (
    !quality.includes(
      "sh mvnw -B -ntp clean verify -DexcludedGroups=compatibility",
    )
  ) {
    errors.push("quality.yml: core backend classification is missing");
  }

  return errors;
}

export function validateQualityTopologyText(text) {
  const errors = [];
  const jobsOffset = text.search(/^jobs:\s*$/m);
  const jobsText = jobsOffset >= 0 ? text.slice(jobsOffset) : "";
  const jobMatches = [...jobsText.matchAll(/^  ([a-zA-Z0-9_-]+):\s*$/gm)];
  const jobBlock = (name) => {
    const index = jobMatches.findIndex((match) => match[1] === name);
    if (index < 0) return "";
    return jobsText.slice(
      jobMatches[index].index,
      jobMatches[index + 1]?.index ?? jobsText.length,
    );
  };

  const requiredLanes = [
    "frontend",
    "browser",
    "backend",
    "compatibility",
    "contracts",
    "product-smoke",
  ];
  for (const name of requiredLanes) {
    if (!jobBlock(name)) {
      errors.push(`quality.yml: required independent job ${name} is missing`);
    }
  }
  const browser = jobBlock("browser");
  if (/^    needs:/m.test(browser)) {
    errors.push(
      "quality.yml: browser must run independently of frontend and other quality lanes",
    );
  }
  const productSmoke = jobBlock("product-smoke");
  if (/^    needs:/m.test(productSmoke)) {
    errors.push(
      "quality.yml: product-smoke must run independently of other quality lanes",
    );
  }
  for (const [marker, description] of [
    ["docker compose build --pull", "an isolated Compose build"],
    ["docker compose up --detach --wait", "an isolated Compose start"],
    ["http://localhost:8080/livez", "the backend liveness probe"],
    ["http://localhost:8080/readyz", "the backend readiness probe"],
    ["http://localhost:4173/", "the frontend HTTP probe"],
    ["npm run test:live:audit", "the authenticated live audit"],
    ["actions/upload-artifact@", "commit-bound audit artifacts"],
    ["docker compose down --volumes --remove-orphans", "volume teardown"],
  ]) {
    if (!productSmoke.includes(marker)) {
      errors.push(`quality.yml: product-smoke must retain ${description}`);
    }
  }
  if (
    (productSmoke.match(/^\s*(?:-\s*)?if:\s*always\(\)\s*$/gm) ?? []).length < 2
  ) {
    errors.push(
      "quality.yml: product-smoke artifact upload and teardown must run always",
    );
  }

  const gate = jobBlock("quality-gate");
  if (!gate) {
    errors.push("quality.yml: final quality-gate job is missing");
    return errors;
  }
  if (!/^    if:\s*always\(\)\s*$/m.test(gate)) {
    errors.push("quality.yml: quality-gate must evaluate after failed lanes");
  }
  const needs = gate.match(/^    needs:\s*\[([^\]]+)\]\s*$/m)?.[1] ?? "";
  const neededJobs = new Set(
    needs
      .split(",")
      .map((entry) => entry.trim())
      .filter(Boolean),
  );
  for (const name of requiredLanes) {
    if (!neededJobs.has(name)) {
      errors.push(`quality.yml: quality-gate must require ${name}`);
    }
    if (!gate.includes(`needs.${name}.result`)) {
      errors.push(`quality.yml: quality-gate must inspect the ${name} result`);
    }
  }
  if (!/\bexit 1\b/.test(gate)) {
    errors.push(
      "quality.yml: quality-gate must fail when a required lane fails",
    );
  }
  for (const marker of [
    "node scripts/generate-qa-evidence.mjs --output build/qa-evidence.json",
    "qa-evidence-${{ github.sha }}",
    "build/qa-evidence.json",
  ]) {
    if (!gate.includes(marker)) {
      errors.push(`quality.yml: quality-gate must retain ${marker}`);
    }
  }
  return errors;
}

export function validateReferenceAuthorityBoundaryTexts({
  bootstrap,
  compose,
  migration,
  operations,
  productionGuard,
  productionTest,
  tenantTest,
  testInit,
}) {
  const errors = [];
  const required = [
    [
      migration,
      /current_setting\('app\.reference_authorization_policy_enabled', true\)/,
      "V115: reference request context",
    ],
    [
      migration,
      /capability\.rolname='careos_local_reference_authority'/,
      "V115: deployment-owned capability role",
    ],
    [
      migration,
      /runtime_role\.rolname=session_user/,
      "V115: session-user binding",
    ],
    [migration, /NOT capability\.rolcanlogin/, "V115: non-login capability"],
    [migration, /NOT capability\.rolbypassrls/, "V115: RLS bypass denial"],
    [migration, /NOT membership\.admin_option/, "V115: admin-option denial"],
    [
      migration,
      /NOT membership\.inherit_option/,
      "V115: inherited-authority denial",
    ],
    [migration, /NOT membership\.set_option/, "V115: SET ROLE denial"],
    [
      bootstrap,
      /CAREOS_DB_LOCAL_REFERENCE_AUTHORITY:-false/,
      "database bootstrap: fail-closed default",
    ],
    [
      bootstrap,
      /WITH ADMIN FALSE, INHERIT FALSE, SET FALSE/,
      "database bootstrap: non-escalating membership",
    ],
    [
      compose,
      /CAREOS_DB_LOCAL_REFERENCE_AUTHORITY:\s*"true"/,
      "Compose: explicit local capability activation",
    ],
    [
      testInit,
      /GRANT careos_local_reference_authority TO careos_app\s+WITH ADMIN FALSE, INHERIT FALSE, SET FALSE;/,
      "test bootstrap: non-escalating capability",
    ],
    [
      operations,
      /SELECT careos_reference_authorization_enabled\(\)/,
      "runtime authorization: database capability check",
    ],
    [
      productionGuard,
      /provisional reference authorization policy must be disabled/,
      "production guard: reference-policy rejection",
    ],
    [
      productionTest,
      /rejectsTheProvisionalAuthorizationPolicyInProduction/,
      "production guard test",
    ],
  ];
  for (const [text, pattern, description] of required) {
    if (!pattern.test(text)) {
      errors.push(`${description} is missing`);
    }
  }

  for (const [method, description] of [
    [
      "deniesLocalBootstrapWorkforceAccessWhenTheReferenceFlagIsMissingOrFalse",
      "missing/false reference flag attack test",
    ],
    [
      "keepsActivePolicyAndReferencePolicyTenantAndPermissionBoundariesDistinct",
      "tenant, permission, and production-role separation test",
    ],
    [
      "runtimeRoleCannotManufactureReferenceAuthorityBySettingTheCustomGuc",
      "runtime GUC escalation attack test",
    ],
  ]) {
    if (!tenantTest.includes(method)) {
      errors.push(`TenantRlsIntegrationTest.java: ${description} is missing`);
    }
  }
  return errors;
}

export function validateAuthorizationReasonProtectionTexts({
  apiTest,
  controller,
  filter,
  identityTest,
  nginx,
  telemetry,
}) {
  const errors = [];
  const required = [
    [
      filter,
      /@Order\(Ordered\.HIGHEST_PRECEDENCE \+ 1\)/,
      "early capture order",
    ],
    [
      filter,
      /Normalizer\.normalize\(rawReason\.strip\(\), Normalizer\.Form\.NFC\)/,
      "NFC normalization",
    ],
    [filter, /length >= 10\s*&& length <= 500/s, "bounded reason length"],
    [
      filter,
      /noneMatch\(Character::isISOControl\)/,
      "control-character rejection",
    ],
    [filter, /class SensitiveHeaderHidingRequest/, "downstream header removal"],
    [filter, /return "\[REDACTED\]";/, "safe captured-value rendering"],
    [
      controller,
      /AuthorizationReasonFilter\.from\(request\)/,
      "governed controller access",
    ],
    [
      apiTest,
      /doesNotEmitAuthorizationReasonIntoApplicationLogsOrHttpTelemetry/,
      "application log and trace leak test",
    ],
    [
      identityTest,
      /Patient Alice Example metrics leak sentinel 654/,
      "Prometheus leak sentinel test",
    ],
  ];
  for (const [text, pattern, description] of required) {
    if (!pattern.test(text)) {
      errors.push(`authorization reason protection: ${description} is missing`);
    }
  }
  if (/getHeader\s*\(/.test(telemetry)) {
    errors.push(
      "RequestTelemetryFilter.java: request headers must not become log or metric fields",
    );
  }
  if (/\$http_x_authorization_reason|\$request_body/i.test(nginx)) {
    errors.push(
      "frontend/nginx-main.conf: authorization reasons or request bodies must not be logged",
    );
  }
  if (/@RequestHeader\([^\n]*X-Authorization-Reason/i.test(controller)) {
    errors.push(
      "WorkforceController.java: raw authorization reason header bypasses the redaction filter",
    );
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

export function validateProjectVerificationTexts({ shell, powershell }) {
  const errors = [];
  const scripts = [
    ["scripts/verify-project.sh", shell],
    ["scripts/verify-project.ps1", powershell],
  ];
  const requiredMarkers = [
    ["Node 24.15 runtime floor", "24.15.0"],
    ["scanner-overlay Compose validation", "compose.scanner.yaml"],
    ["prototype registry", "verify-prototype-register.mjs"],
    ["API verifier", "verify-api-contract.mjs"],
    ["API negative tests", "verify-api-contract.test.mjs"],
    ["QA evidence tests", "generate-qa-evidence.test.mjs"],
    ["Module 1 approved inputs", "verify-module-1-inputs.mjs"],
    ["Module 1 review drafts", "verify-module-1-review-drafts.mjs"],
    ["Module 1 candidate inputs", "verify-module-1-candidate-inputs.mjs"],
    [
      "Module 1 facility-scope candidate",
      "verify-module-1-facility-scope-candidate.mjs",
    ],
    ["Module 2 candidate inputs", "verify-module-2-candidate-inputs.mjs"],
    ["Module 2 approved inputs", "verify-module-2-inputs.mjs"],
    ["Module 3 candidate inputs", "verify-module-3-candidate-inputs.mjs"],
    ["Module 3 approved inputs", "verify-module-3-inputs.mjs"],
    ["CI security verifier", "verify-ci-security.mjs"],
    ["CI security negative tests", "verify-ci-security.test.mjs"],
    ["npm vulnerability audit", "--audit-level=high"],
    ["generated API drift", "api:check"],
    ["frontend architecture", "architecture:check"],
    ["strict typecheck", "typecheck"],
    ["frontend lint", "lint"],
    ["frontend formatting", "format:check"],
    ["frontend production build", "build"],
    ["full browser matrix", "test:e2e"],
  ];

  for (const [name, text] of scripts) {
    for (const [label, marker] of requiredMarkers) {
      if (!text.includes(marker)) {
        errors.push(`${name}: missing full-project QA gate ${label}`);
      }
    }
    if ((text.match(/--require-approved/g) ?? []).length < 3) {
      errors.push(
        `${name}: all three approved module inputs must use --require-approved`,
      );
    }
  }

  if (!/^npm test\s*$/m.test(shell)) {
    errors.push("scripts/verify-project.sh: missing frontend unit-test gate");
  }
  if (!/^sh mvnw -B -ntp clean verify\s*$/m.test(shell)) {
    errors.push("scripts/verify-project.sh: backend gate must be clean verify");
  }
  if (!/"npm\.cmd"\s+@\("test"\)/.test(powershell)) {
    errors.push("scripts/verify-project.ps1: missing frontend unit-test gate");
  }
  if (
    !/"\.\\mvnw\.cmd"\s+@\("-B", "-ntp", "clean", "verify"\)/.test(powershell)
  ) {
    errors.push(
      "scripts/verify-project.ps1: backend gate must be clean verify",
    );
  }

  return errors;
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
  errors.push(...validateQualityTopologyText(quality));
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
    "name: careos-image-sboms-${{ github.sha }}",
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
    "node --test scripts/tests/generate-qa-evidence.test.mjs",
    "node --test scripts/tests/build-s3-test-fixture.test.mjs",
    "node scripts/verify-ci-security.mjs",
    "node --test scripts/tests/verify-ci-security.test.mjs",
  ]) {
    if (!quality.includes(requirement)) {
      errors.push(`quality.yml: missing security-contract gate ${requirement}`);
    }
  }

  for (const dockerfile of [
    "backend/Dockerfile",
    "frontend/Dockerfile",
    "test-fixtures/s3/Dockerfile",
  ]) {
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
    ...validateS3FixtureTexts({
      clamAvTest: requiredFile(
        root,
        "backend/src/test/java/com/rootopathy/careos/platform/infrastructure/ClamAvMalwareScannerIntegrationTest.java",
        errors,
      ),
      compose: requiredFile(root, "compose.yaml", errors),
      dockerfile: requiredFile(root, "test-fixtures/s3/Dockerfile", errors),
      fixtureBuilder: requiredFile(
        root,
        "scripts/build-s3-test-fixture.mjs",
        errors,
      ),
      integrationTest: requiredFile(
        root,
        "backend/src/test/java/com/rootopathy/careos/platform/infrastructure/S3PrivateDocumentStorageIntegrationTest.java",
        errors,
      ),
      quality,
    }),
  );
  errors.push(
    ...validateReferenceAuthorityBoundaryTexts({
      bootstrap: requiredFile(
        root,
        "deploy/postgres/init/001-create-runtime-role.sh",
        errors,
      ),
      compose: requiredFile(root, "compose.yaml", errors),
      migration: requiredFile(
        root,
        "backend/src/main/resources/db/migration/V115__local_reference_authority_capability.sql",
        errors,
      ),
      operations: requiredFile(
        root,
        "backend/src/main/java/com/rootopathy/careos/tenancy/infrastructure/PostgresTenantAuthorizationOperations.java",
        errors,
      ),
      productionGuard: requiredFile(
        root,
        "backend/src/main/java/com/rootopathy/careos/config/ProductionConfigurationGuard.java",
        errors,
      ),
      productionTest: requiredFile(
        root,
        "backend/src/test/java/com/rootopathy/careos/config/SecurityConfigTest.java",
        errors,
      ),
      tenantTest: requiredFile(
        root,
        "backend/src/test/java/com/rootopathy/careos/tenancy/infrastructure/TenantRlsIntegrationTest.java",
        errors,
      ),
      testInit: requiredFile(
        root,
        "backend/src/test/resources/db/test-init.sql",
        errors,
      ),
    }),
  );
  errors.push(
    ...validateAuthorizationReasonProtectionTexts({
      apiTest: requiredFile(
        root,
        "backend/src/test/java/com/rootopathy/careos/shared/api/ApiContractTest.java",
        errors,
      ),
      controller: requiredFile(
        root,
        "backend/src/main/java/com/rootopathy/careos/workforce/api/WorkforceController.java",
        errors,
      ),
      filter: requiredFile(
        root,
        "backend/src/main/java/com/rootopathy/careos/shared/api/AuthorizationReasonFilter.java",
        errors,
      ),
      identityTest: requiredFile(
        root,
        "backend/src/test/java/com/rootopathy/careos/identity/api/IdentitySecurityIntegrationTest.java",
        errors,
      ),
      nginx: requiredFile(root, "frontend/nginx-main.conf", errors),
      telemetry: requiredFile(
        root,
        "backend/src/main/java/com/rootopathy/careos/shared/api/RequestTelemetryFilter.java",
        errors,
      ),
    }),
  );
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
  errors.push(
    ...validateProjectVerificationTexts({
      shell: requiredFile(root, "scripts/verify-project.sh", errors),
      powershell: requiredFile(root, "scripts/verify-project.ps1", errors),
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
