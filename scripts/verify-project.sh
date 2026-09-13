#!/usr/bin/env sh
set -eu

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

node "$root_dir/scripts/verify-prototype-register.mjs"
node "$root_dir/scripts/verify-api-contract.mjs"

cd "$root_dir/frontend"
npm ci
npm run typecheck
npm run lint
npm run format:check
npm test
npm run build

cd "$root_dir/backend"
sh mvnw -B -ntp verify
