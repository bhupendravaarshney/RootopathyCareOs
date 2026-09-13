#!/usr/bin/env sh
set -eu
branch=$(git branch --show-current)
commit=$(git rev-parse HEAD)
cat > GIT_POSITION.md <<EOF
# Git position

- Branch: \`$branch\`
- Commit: \`$commit\`
- Release status: engineering foundation only; no production freeze tag
- Working tree at capture: inspect with \`git status --short\`
EOF
printf 'Recorded %s at %s\n' "$branch" "$commit"
