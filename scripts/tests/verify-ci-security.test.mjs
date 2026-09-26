import assert from "node:assert/strict";
import test from "node:test";

import {
  validateBaseConfigText,
  validateComposeText,
  validateDependabotText,
  validateDockerfileText,
  validateFoundationDataScopeTexts,
  validateNginxText,
  validateProductionConfigText,
  validateResponsiveBrowserTexts,
  validateWorkflowText,
} from "../verify-ci-security.mjs";

const SHA = "a".repeat(40);

test("accepts a least-privilege workflow with an immutable action", () => {
  const workflow = `name: test
on: push
permissions:
  contents: read
concurrency:
  group: test
  cancel-in-progress: true
jobs:
  verify:
    runs-on: ubuntu-24.04
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@${SHA} # v7.0.1
        with:
          persist-credentials: false
`;
  assert.deepEqual(validateWorkflowText("secure.yml", workflow), []);
});

test("requires every approved and candidate module-input check in the quality workflow", () => {
  const workflow = `name: quality
on: push
permissions:
  contents: read
concurrency:
  group: quality
  cancel-in-progress: true
jobs:
  contracts:
    runs-on: ubuntu-24.04
    timeout-minutes: 5
    steps:
      - run: node scripts/verify-module-1-inputs.mjs --require-approved
      - run: node --test scripts/tests/verify-module-1-inputs.test.mjs
      - run: node scripts/verify-module-1-review-drafts.mjs
      - run: node --test scripts/tests/verify-module-1-review-drafts.test.mjs
      - run: node scripts/verify-module-1-candidate-inputs.mjs
      - run: node --test scripts/tests/verify-module-1-candidate-inputs.test.mjs
      - run: node scripts/verify-module-1-facility-scope-candidate.mjs
      - run: node --test scripts/tests/verify-module-1-facility-scope-candidate.test.mjs
      - run: node scripts/verify-module-2-candidate-inputs.mjs
      - run: node --test scripts/tests/verify-module-2-candidate-inputs.test.mjs
      - run: node scripts/verify-module-2-inputs.mjs --require-approved
      - run: node --test scripts/tests/verify-module-2-inputs.test.mjs
      - run: node scripts/verify-module-3-candidate-inputs.mjs
      - run: node --test scripts/tests/verify-module-3-candidate-inputs.test.mjs
      - run: node scripts/verify-module-3-inputs.mjs --require-approved
      - run: node --test scripts/tests/verify-module-3-inputs.test.mjs
`;
  assert.deepEqual(validateWorkflowText("quality.yml", workflow), []);

  const weakened = workflow.replace(
    "      - run: node --test scripts/tests/verify-module-1-facility-scope-candidate.test.mjs\n",
    "",
  );
  assert.match(
    validateWorkflowText("quality.yml", weakened).join("\n"),
    /contracts job must run the required module input command/,
  );

  const module2Weakened = workflow.replace(
    "      - run: node --test scripts/tests/verify-module-2-candidate-inputs.test.mjs\n",
    "",
  );
  assert.match(
    validateWorkflowText("quality.yml", module2Weakened).join("\n"),
    /contracts job must run the required module input command/,
  );

  const module2ApprovalWeakened = workflow.replace(
    "      - run: node scripts/verify-module-2-inputs.mjs --require-approved\n",
    "",
  );
  assert.match(
    validateWorkflowText("quality.yml", module2ApprovalWeakened).join("\n"),
    /contracts job must run the required module input command/,
  );

  const module3Weakened = workflow.replace(
    "      - run: node --test scripts/tests/verify-module-3-candidate-inputs.test.mjs\n",
    "",
  );
  assert.match(
    validateWorkflowText("quality.yml", module3Weakened).join("\n"),
    /contracts job must run the required module input command/,
  );

  const module3ApprovalWeakened = workflow.replace(
    "      - run: node scripts/verify-module-3-inputs.mjs --require-approved\n",
    "",
  );
  assert.match(
    validateWorkflowText("quality.yml", module3ApprovalWeakened).join("\n"),
    /contracts job must run the required module input command/,
  );

  const approvalWeakened = workflow.replace(" --require-approved", "");
  assert.match(
    validateWorkflowText("quality.yml", approvalWeakened).join("\n"),
    /contracts job must run the required module input command/,
  );
});

test("rejects mutable action references", () => {
  const workflow = `name: test
on: push
permissions:
  contents: read
concurrency:
  group: test
  cancel-in-progress: true
jobs:
  verify:
    runs-on: ubuntu-24.04
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false
`;
  assert.match(
    validateWorkflowText("mutable.yml", workflow).join("\n"),
    /full 40-character/,
  );
});

test("rejects checkout credentials and jobs without time bounds", () => {
  const workflow = `name: test
on: push
permissions:
  contents: read
concurrency:
  group: test
  cancel-in-progress: true
jobs:
  verify:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@${SHA} # v7.0.1
`;
  const errors = validateWorkflowText("unsafe.yml", workflow).join("\n");
  assert.match(errors, /persist-credentials/);
  assert.match(errors, /timeout-minutes/);
});

test("rejects excessive permissions, mutable runner labels, and missing concurrency", () => {
  const workflow = `name: test
on: push
permissions:
  contents: read
  packages: write
jobs:
  verify:
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - run: true
`;
  const errors = validateWorkflowText("overprivileged.yml", workflow).join(
    "\n",
  );
  assert.match(errors, /only contents: read/);
  assert.match(errors, /concurrency cancellation/);
  assert.match(errors, /explicit rather than \*-latest/);
});

test("requires digest-pinned non-root final images", () => {
  const valid = `FROM example/build:1@sha256:${"b".repeat(64)} AS build
RUN true
FROM example/runtime:1@sha256:${"c".repeat(64)}
USER app
`;
  assert.deepEqual(validateDockerfileText("valid.Dockerfile", valid), []);
  assert.match(
    validateDockerfileText(
      "unsafe.Dockerfile",
      "FROM example/runtime:latest\n",
    ).join("\n"),
    /pinned by sha256.*non-root USER/s,
  );
  const rootReset = `FROM example/runtime:1@sha256:${"d".repeat(64)}
USER app
USER root:app
`;
  assert.match(
    validateDockerfileText("root-reset.Dockerfile", rootReset).join("\n"),
    /non-root/,
  );
});

test("requires strict frontend and production configuration security contracts", () => {
  const secure = `server {
  add_header Content-Security-Policy "default-src 'self'; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; script-src 'self'; style-src 'self'" always;
  add_header Cross-Origin-Opener-Policy "same-origin" always;
  add_header Cross-Origin-Resource-Policy "same-origin" always;
  add_header Permissions-Policy "camera=()" always;
  add_header Referrer-Policy "no-referrer" always;
  add_header Strict-Transport-Security "max-age=31536000" always;
  add_header X-Content-Type-Options "nosniff" always;
  add_header X-Frame-Options "DENY" always;
  add_header X-Permitted-Cross-Domain-Policies "none" always;
  add_header X-XSS-Protection "0" always;
  proxy_connect_timeout 3s;
  proxy_send_timeout 30s;
  proxy_read_timeout 30s;
  proxy_set_header Connection "";
}`;
  assert.deepEqual(validateNginxText("frontend/nginx.conf", secure), []);
  assert.deepEqual(
    validateNginxText("frontend/nginx-main.conf", "server_tokens off;"),
    [],
  );
  const production = `
on-profile: production
url: \${DB_URL}
username: \${DB_APP_USERNAME}
password: \${DB_APP_PASSWORD}
url: \${DB_MIGRATION_URL}
user: \${DB_MIGRATION_USERNAME}
password: \${DB_MIGRATION_PASSWORD}
host: \${REDIS_HOST}
username: \${REDIS_USERNAME}
password: \${REDIS_PASSWORD}
host: \${SMTP_HOST}
username: \${SMTP_USERNAME}
password: \${SMTP_PASSWORD}
allowed-origins: \${CAREOS_ALLOWED_ORIGINS}
application-base-url: \${CAREOS_BASE_URL}
mail-from: \${CAREOS_SECURITY_MAIL_FROM}
token-pepper: \${CAREOS_TOKEN_PEPPER}
mfa-encryption-key: \${CAREOS_MFA_ENCRYPTION_KEY}
enabled: true
auth: true
checkserveridentity: true
enable: true
required: true
secure: true
allow-http: false
create-bucket-if-missing: false
`;
  assert.deepEqual(
    validateProductionConfigText("application-production.yml", production),
    [],
  );
  const base = `
url: \${DB_URL}
username: \${DB_APP_USERNAME}
password: \${DB_APP_PASSWORD}
user: \${DB_MIGRATION_USERNAME}
password: \${DB_MIGRATION_PASSWORD}
host: \${REDIS_HOST}
host: \${SMTP_HOST}
allowed-origins: \${CAREOS_ALLOWED_ORIGINS}
application-base-url: \${CAREOS_BASE_URL}
mail-from: \${CAREOS_SECURITY_MAIL_FROM}
token-pepper: \${CAREOS_TOKEN_PEPPER}
mfa-encryption-key: \${CAREOS_MFA_ENCRYPTION_KEY}
`;
  assert.deepEqual(validateBaseConfigText("application.yml", base), []);
});

test("rejects unsafe CSP and local production configuration fallbacks", () => {
  const unsafe = `server {
  add_header Content-Security-Policy "default-src *; script-src 'self' 'unsafe-inline'" always;
}`;
  const errors = validateNginxText("frontend/nginx.conf", unsafe).join("\n");
  assert.match(errors, /strict same-origin directives/);
  assert.match(errors, /unsafe script\/style or wildcard/);
  assert.match(errors, /X-Frame-Options/);
  assert.match(errors, /bounded proxy connect timeout/);
  assert.match(
    validateNginxText("frontend/nginx-main.conf", "server_tokens on;").join(
      "\n",
    ),
    /version tokens/,
  );
  const productionErrors = validateProductionConfigText(
    "application-production.yml",
    "on-profile: local\npassword: ${DB_APP_PASSWORD:-careos-app-local-only}\nhttp://localhost",
  ).join("\n");
  assert.match(productionErrors, /activate only for production/);
  assert.match(productionErrors, /DB_APP_PASSWORD must be required/);
  assert.match(productionErrors, /local-only material/);
  const baseErrors = validateBaseConfigText(
    "application.yml",
    "password: ${DB_APP_PASSWORD:careos-app-local-only}\nbootstrap-admin:\n",
  ).join("\n");
  assert.match(baseErrors, /DB_APP_PASSWORD must be required/);
  assert.match(baseErrors, /local-only defaults/);
});

test("requires synthetic foundation data to remain local or test only", () => {
  const secure = {
    base: "foundationSyntheticDataEnabled: false",
    local: "foundationSyntheticDataEnabled: true",
    production: "foundationSyntheticDataEnabled: false",
    test: "foundationSyntheticDataEnabled: true",
    migration: `
      '\${foundationSyntheticDataEnabled}'::boolean;
      IF synthetic_data_enabled THEN
        RETURN;
      END IF;
      DELETE FROM facilities WHERE id = reference_facility_id;
      BEGIN
        DELETE FROM organizations WHERE id = reference_organization_id;
      EXCEPTION
        WHEN foreign_key_violation THEN RAISE;
      END;
    `,
  };
  assert.deepEqual(validateFoundationDataScopeTexts(secure), []);

  const unsafe = {
    ...secure,
    production:
      "foundationSyntheticDataEnabled: ${CAREOS_FOUNDATION_SYNTHETIC_DATA_ENABLED:true}",
    migration: secure.migration.replace(
      "WHEN foreign_key_violation THEN RAISE;",
      "WHEN OTHERS THEN NULL;",
    ),
  };
  const errors = validateFoundationDataScopeTexts(unsafe).join("\n");
  assert.match(errors, /production.*hard-disable/);
  assert.match(errors, /fail closed/);
});

test("rejects mutable images and under-hardened Compose application services", () => {
  const compose = `services:
  postgres:
    image: postgres:18-alpine@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    volumes:
      - careos-postgres:/var/lib/postgresql/data
  backend:
    image: example/backend:latest
`;
  const errors = validateComposeText("compose.yaml", compose).join("\n");
  assert.match(errors, /sha256 digest/);
  assert.match(errors, /read-only root filesystem/);
  assert.match(errors, /all capabilities dropped/);
  assert.match(errors, /PostgreSQL 18 must mount its data volume/);

  const hardened = `services:
  postgres:
    image: postgres:18-alpine@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    volumes:
      - careos-postgres:/var/lib/postgresql
  frontend:
    build:
      context: ./frontend
    read_only: true
    tmpfs:
      - /tmp:rw,noexec,nosuid,size=16m
    cap_drop:
      - ALL
    security_opt:
      - "no-new-privileges:true"
`;
  assert.deepEqual(validateComposeText("compose.yaml", hardened), []);
});

test("requires update coverage for every dependency ecosystem", () => {
  const incomplete = `version: 2
updates:
  - package-ecosystem: "npm"
    directory: "/frontend"
`;
  const errors = validateDependabotText(incomplete);
  assert.equal(errors.length, 5);
  assert.ok(errors.some((error) => error.includes("github-actions")));
});

test("does not confuse the Dependabot root directory with a nested directory", () => {
  const nestedDockerOnly = `version: 2
updates:
  - package-ecosystem: "docker"
    directory: "/backend"
`;
  const errors = validateDependabotText(nestedDockerOnly);
  assert.ok(
    errors.some(
      (error) => error === "dependabot.yml: missing docker updates for /",
    ),
  );
});

test("requires every responsive browser project and its overflow assertions", () => {
  const config = `projects: [
    { name: 'desktop-1440', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    { name: 'compact-1024', use: { ...devices['Desktop Chrome'], viewport: { width: 1024, height: 900 } } },
    { name: 'tablet-768', use: { ...devices['Desktop Chrome'], viewport: { width: 768, height: 1024 } } },
    { name: 'mobile-390', use: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } },
    { name: 'mobile-320', use: { viewport: { width: 320, height: 800 }, isMobile: true, hasTouch: true } },
  ]`;
  const suite = `
    document.documentElement.scrollWidth;
    document.body.scrollWidth;
    await expectNoDocumentHorizontalOverflow(page, id);
    await expectNoDocumentHorizontalOverflow(page, 'M1-04 organization selection');
    expect([1440, 1024, 768, 390, 320]).toContain(viewport.width);
    const usesDrawer = viewport.width <= 760;
  `;
  assert.deepEqual(validateResponsiveBrowserTexts({ config, suite }), []);

  const missingProject = validateResponsiveBrowserTexts({
    config: config.replace(/\s*\{ name: 'tablet-768'[^\n]+\n/, "\n"),
    suite,
  }).join("\n");
  assert.match(missingProject, /exactly one tablet-768 responsive project/);

  const weakenedSuite = validateResponsiveBrowserTexts({
    config,
    suite: suite.replace("document.body.scrollWidth;", ""),
  }).join("\n");
  assert.match(weakenedSuite, /body-width overflow assertion/);
});
