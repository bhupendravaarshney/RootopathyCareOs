#!/usr/bin/env sh
set -eu

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

node -e 'const [major, minor] = process.versions.node.split(".").map(Number); if (major !== 24 || minor < 15) { console.error(`CareOS QA requires Node >=24.15.0 <25; found ${process.versions.node}`); process.exit(1); }'

docker compose --env-file "$root_dir/.env.example" -f "$root_dir/compose.yaml" config --quiet
docker compose --env-file "$root_dir/.env.example" -f "$root_dir/compose.yaml" -f "$root_dir/compose.uat.yaml" config --quiet
docker compose --env-file "$root_dir/.env.example" -f "$root_dir/compose.yaml" -f "$root_dir/compose.scanner.yaml" config --quiet
node "$root_dir/scripts/build-s3-test-fixture.mjs"

node "$root_dir/scripts/verify-prototype-register.mjs"
node "$root_dir/scripts/verify-api-contract.mjs"
node --test "$root_dir/scripts/tests/verify-api-contract.test.mjs"
node --test "$root_dir/scripts/tests/generate-qa-evidence.test.mjs"
node --test "$root_dir/scripts/tests/build-s3-test-fixture.test.mjs"
node "$root_dir/scripts/verify-module-1-inputs.mjs" --require-approved
node --test "$root_dir/scripts/tests/verify-module-1-inputs.test.mjs"
node "$root_dir/scripts/verify-module-1-review-drafts.mjs"
node --test "$root_dir/scripts/tests/verify-module-1-review-drafts.test.mjs"
node "$root_dir/scripts/verify-module-1-candidate-inputs.mjs"
node --test "$root_dir/scripts/tests/verify-module-1-candidate-inputs.test.mjs"
node "$root_dir/scripts/verify-module-1-facility-scope-candidate.mjs"
node --test "$root_dir/scripts/tests/verify-module-1-facility-scope-candidate.test.mjs"
node "$root_dir/scripts/verify-module-2-candidate-inputs.mjs"
node --test "$root_dir/scripts/tests/verify-module-2-candidate-inputs.test.mjs"
node "$root_dir/scripts/verify-module-2-inputs.mjs" --require-approved
node --test "$root_dir/scripts/tests/verify-module-2-inputs.test.mjs"
node "$root_dir/scripts/verify-module-3-candidate-inputs.mjs"
node --test "$root_dir/scripts/tests/verify-module-3-candidate-inputs.test.mjs"
node "$root_dir/scripts/verify-module-3-inputs.mjs" --require-approved
node --test "$root_dir/scripts/tests/verify-module-3-inputs.test.mjs"
node "$root_dir/scripts/verify-ci-security.mjs"
node --test "$root_dir/scripts/tests/verify-ci-security.test.mjs"

cd "$root_dir/frontend"
npm ci
npm audit --audit-level=high
npm run api:check
npm run architecture:check
npm run typecheck
npm run lint
npm run format:check
npm test
npm run build
npm run test:e2e

cd "$root_dir/backend"
sh mvnw -B -ntp clean verify
