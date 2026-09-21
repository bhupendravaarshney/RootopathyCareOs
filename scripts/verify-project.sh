#!/usr/bin/env sh
set -eu

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

node "$root_dir/scripts/verify-prototype-register.mjs"
node "$root_dir/scripts/verify-api-contract.mjs"
node "$root_dir/scripts/verify-module-1-inputs.mjs"
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

cd "$root_dir/frontend"
npm ci
npm run typecheck
npm run lint
npm run format:check
npm test
npm run build

cd "$root_dir/backend"
sh mvnw -B -ntp verify
