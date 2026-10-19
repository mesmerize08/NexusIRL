#!/usr/bin/env sh
# Fails if app or library code references services run by the upstream Moblin author
# (docs/PORT_PLAN.md §7 item 7, §8). Attribution in docs/NOTICE/README is allowed; code and
# resources are not. An opt-in relay preset, if the author ever agrees to one, must be added to
# ALLOWLIST explicitly.
set -eu

cd "$(dirname "$0")/.."

PATTERN='mys-lang\.org|moblin\.app|moblinremote\.com|eerimoq\.github\.io'
ALLOWLIST='^$'

matches=$(grep -rnIE "$PATTERN" \
    --include='*.kt' --include='*.kts' --include='*.java' --include='*.xml' \
    --include='*.json' --include='*.properties' --include='*.html' --include='*.js' --include='*.mjs' \
    --exclude-dir=upstream --exclude-dir=build --exclude-dir=.git --exclude-dir=.gradle \
    --exclude-dir=docs \
    . | grep -vE "$ALLOWLIST" || true)

if [ -n "$matches" ]; then
    echo "Author-owned service references found (see docs/PORT_PLAN.md §8):"
    echo "$matches"
    exit 1
fi
echo "Independence check passed: no author-owned service references."
