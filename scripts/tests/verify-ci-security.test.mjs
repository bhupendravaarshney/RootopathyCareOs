import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  validateAuthorizationReasonProtectionTexts,
  validateBaseConfigText,
  validateComposeText,
  validateDependabotText,
  validateDockerfileText,
  validateFoundationDataScopeTexts,
  validateNginxText,
  validateProductionConfigText,
  validateProjectVerificationTexts,
  validateQualityTopologyText,
  validateReferenceAuthorityBoundaryTexts,
  validateResponsiveBrowserTexts,
  validateS3FixtureTexts,
  validateWorkflowText,
} from "../verify-ci-security.mjs";

const SHA = "a".repeat(40);
const repositorySource = (path) =>
  readFileSync(new URL(`../../${path}`, import.meta.url), "utf8");

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

test("keeps browser and product evidence independent and aggregates every lane", () => {
  const workflow = `jobs:
  frontend:
    timeout-minutes: 5
  browser:
    timeout-minutes: 5
  backend:
    timeout-minutes: 5
  compatibility:
    timeout-minutes: 5
  contracts:
    timeout-minutes: 5
  product-smoke:
    timeout-minutes: 5
    steps:
      - run: docker compose build --pull
      - run: docker compose up --detach --wait
      - run: curl http://localhost:8080/livez && curl http://localhost:8080/readyz && curl http://localhost:4173/
      - run: npm run test:live:audit
      - if: always()
        uses: actions/upload-artifact@immutable
      - if: always()
        run: docker compose down --volumes --remove-orphans
  quality-gate:
    if: always()
    needs: [frontend, browser, backend, compatibility, contracts, product-smoke]
    timeout-minutes: 5
    steps:
      - run: node scripts/generate-qa-evidence.mjs --output build/qa-evidence.json
      - uses: actions/upload-artifact@immutable
        with:
          name: qa-evidence-\${{ github.sha }}
          path: build/qa-evidence.json
      - env:
          FRONTEND: \${{ needs.frontend.result }}
          BROWSER: \${{ needs.browser.result }}
          BACKEND: \${{ needs.backend.result }}
          COMPATIBILITY: \${{ needs.compatibility.result }}
          CONTRACTS: \${{ needs.contracts.result }}
          PRODUCT_SMOKE: \${{ needs.product-smoke.result }}
        run: exit 1
`;
  assert.deepEqual(validateQualityTopologyText(workflow), []);

  assert.match(
    validateQualityTopologyText(
      workflow.replace(
        "  browser:\n    timeout-minutes: 5",
        "  browser:\n    needs: frontend\n    timeout-minutes: 5",
      ),
    ).join("\n"),
    /browser must run independently/,
  );
  assert.match(
    validateQualityTopologyText(
      workflow.replace(
        "needs: [frontend, browser, backend, compatibility, contracts, product-smoke]",
        "needs: [frontend, backend, compatibility, contracts, product-smoke]",
      ),
    ).join("\n"),
    /quality-gate must require browser/,
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
  assert.deepEqual(
    validateDockerfileText(
      "scratch.Dockerfile",
      `FROM example/build:1@sha256:${"e".repeat(64)} AS build
FROM scratch
USER 65532:65532
`,
    ),
    [],
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
  assert.equal(errors.length, 6);
  assert.ok(errors.some((error) => error.includes("github-actions")));
});

test("locks the repository-owned S3 fixture and fail-fast CI preflight", () => {
  const dockerfile = `# syntax=docker/dockerfile:1.20.0@sha256:26147acbda4f14c5add9946e2fd2ed543fc402884fd75146bd342a7f6271dc1d
FROM golang:1.24-alpine@sha256:${"a".repeat(64)} AS build
ARG MINIO_RELEASE=RELEASE.2025-09-07T16-13-09Z
ARG MINIO_COMMIT=07c3a429bfed433e49018cb0f78a52145d4bedeb
ARG MINIO_SOURCE_SHA256=c9598dcce3440977e79f787f2ba0e7e4d92c8d556bd51e7cef3785bafd6635f3
ADD --checksum=sha256:c9598dcce3440977e79f787f2ba0e7e4d92c8d556bd51e7cef3785bafd6635f3 https://example.invalid/source.tar.gz /tmp/source.tar.gz
FROM scratch
USER 65532:65532
`;
  const compose = `services:
  minio:
    build:
      context: ./test-fixtures/s3
      args:
        SOURCE_DATE_EPOCH: "1757261589"
`;
  const fixtureBuilder = `
export const FIXTURE_IMAGE = "careos-s3-test-fixture:minio-release-2025-09-07";
export const FIXTURE_PLATFORM = "linux/amd64";
export const FIXTURE_SOURCE_EPOCH = "1757261589";
export const FIXTURE_BUILDKIT_IMAGE = "moby/buildkit@sha256:040d34121c27906c4ff9ac152a30d52bf2c5d328d3bb748916bb3d2743c02528";
export const FIXTURE_MANIFEST_DIGEST = "sha256:9b225075e9847bde86fa19f8274353ae7c5c4018af813ebb5bb9ce12d329b744";
const environment = {
  BUILDX_GIT_INFO: "0",
  BUILDX_METADATA_PROVENANCE: "disabled",
  BUILDX_NO_DEFAULT_ATTESTATIONS: "1",
};
const args = ["buildx", "create", "docker-container", "build", "--provenance=false", "type=docker,oci-mediatypes=false,rewrite-timestamp=true", "--metadata-file"];
const preflight = ["run", "--rm", FIXTURE_IMAGE, "--version"];
`;
  const integrationTest = `
    @Tag("compatibility")
    "careos-s3-test-fixture:minio-release-2025-09-07";
    "sha256:9b225075e9847bde86fa19f8274353ae7c5c4018af813ebb5bb9ce12d329b744";
    "../build/s3-fixture-metadata.json";
    container.withImagePullPolicy(ignored -> false);
  `;
  const clamAvTest = '@Tag("compatibility")';
  const quality = `
sh mvnw -B -ntp clean verify -DexcludedGroups=compatibility
node scripts/build-s3-test-fixture.mjs
sh mvnw -B -ntp test -Dgroups=compatibility
`;
  const fixture = {
    clamAvTest,
    compose,
    dockerfile,
    fixtureBuilder,
    integrationTest,
    quality,
  };
  assert.deepEqual(validateS3FixtureTexts(fixture), []);

  assert.match(
    validateS3FixtureTexts({
      ...fixture,
      integrationTest: integrationTest.replace(
        ".withImagePullPolicy(ignored -> false)",
        "",
      ),
    }).join("\n"),
    /local-only/,
  );
  assert.match(
    validateS3FixtureTexts({
      ...fixture,
      fixtureBuilder: fixtureBuilder.replace("--metadata-file", "--iidfile"),
    }).join("\n"),
    /manifest digest metadata/,
  );
  assert.match(
    validateS3FixtureTexts({
      ...fixture,
      quality: quality.replace("node scripts/build-s3-test-fixture.mjs\n", ""),
    }).join("\n"),
    /preflight is incomplete/,
  );
  assert.match(
    validateS3FixtureTexts({
      ...fixture,
      compose: "services:\n  minio:\n    image: quay.io/minio/minio:latest\n",
    }).join("\n"),
    /legacy external MinIO image.*repository-owned/s,
  );
});

test("locks local reference authority behind a deployment-owned capability", () => {
  const sources = {
    bootstrap: repositorySource(
      "deploy/postgres/init/001-create-runtime-role.sh",
    ),
    compose: repositorySource("compose.yaml"),
    migration: repositorySource(
      "backend/src/main/resources/db/migration/V115__local_reference_authority_capability.sql",
    ),
    operations: repositorySource(
      "backend/src/main/java/com/rootopathy/careos/tenancy/infrastructure/PostgresTenantAuthorizationOperations.java",
    ),
    productionGuard: repositorySource(
      "backend/src/main/java/com/rootopathy/careos/config/ProductionConfigurationGuard.java",
    ),
    productionTest: repositorySource(
      "backend/src/test/java/com/rootopathy/careos/config/SecurityConfigTest.java",
    ),
    tenantTest: repositorySource(
      "backend/src/test/java/com/rootopathy/careos/tenancy/infrastructure/TenantRlsIntegrationTest.java",
    ),
    testInit: repositorySource("backend/src/test/resources/db/test-init.sql"),
  };
  assert.deepEqual(validateReferenceAuthorityBoundaryTexts(sources), []);

  assert.match(
    validateReferenceAuthorityBoundaryTexts({
      ...sources,
      migration: sources.migration.replace(
        "runtime_role.rolname=session_user",
        "runtime_role.rolname=current_user",
      ),
    }).join("\n"),
    /session-user binding/,
  );
});

test("locks authorization reasons out of logs, telemetry, and proxy evidence", () => {
  const sources = {
    apiTest: repositorySource(
      "backend/src/test/java/com/rootopathy/careos/shared/api/ApiContractTest.java",
    ),
    controller: repositorySource(
      "backend/src/main/java/com/rootopathy/careos/workforce/api/WorkforceController.java",
    ),
    filter: repositorySource(
      "backend/src/main/java/com/rootopathy/careos/shared/api/AuthorizationReasonFilter.java",
    ),
    identityTest: repositorySource(
      "backend/src/test/java/com/rootopathy/careos/identity/api/IdentitySecurityIntegrationTest.java",
    ),
    nginx: repositorySource("frontend/nginx-main.conf"),
    telemetry: repositorySource(
      "backend/src/main/java/com/rootopathy/careos/shared/api/RequestTelemetryFilter.java",
    ),
  };
  assert.deepEqual(validateAuthorizationReasonProtectionTexts(sources), []);

  assert.match(
    validateAuthorizationReasonProtectionTexts({
      ...sources,
      nginx: `${sources.nginx}\nlog_format unsafe '$http_x_authorization_reason';`,
    }).join("\n"),
    /must not be logged/,
  );
  assert.match(
    validateAuthorizationReasonProtectionTexts({
      ...sources,
      telemetry: `${sources.telemetry}\nrequest.getHeader("X-Authorization-Reason");`,
    }).join("\n"),
    /must not become log or metric fields/,
  );
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

test("keeps both local whole-project QA runners aligned with the complete gate", () => {
  const shell = readFileSync(
    new URL("../verify-project.sh", import.meta.url),
    "utf8",
  );
  const powershell = readFileSync(
    new URL("../verify-project.ps1", import.meta.url),
    "utf8",
  );

  assert.deepEqual(validateProjectVerificationTexts({ shell, powershell }), []);

  const weakened = validateProjectVerificationTexts({
    shell: shell.replace(/^npm run test:e2e\s*$/m, ""),
    powershell,
  }).join("\n");
  assert.match(weakened, /missing full-project QA gate full browser matrix/);
});
