#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
command -v xcodegen >/dev/null 2>&1 || {
  echo "XcodeGen 2.46.0 is required. Use ../tools/install_xcodegen.py with the repository-pinned checksum." >&2
  exit 1
}

version="$(xcodegen --version)"
case "$version" in
  *"2.46.0"*) ;;
  *) echo "Expected XcodeGen 2.46.0, found: $version" >&2; exit 1 ;;
esac

# The Apple Developer Team ID for automatic signing of all three targets (project.yml
# DEVELOPMENT_TEAM). Unset or empty leaves the generated project unsigned, which is what the
# simulator-only CI wants; an archive for App Store Connect needs the real team here.
export BINARIO_APPLE_TEAM_ID="${BINARIO_APPLE_TEAM_ID:-}"
if [ -z "$BINARIO_APPLE_TEAM_ID" ]; then
  echo "BINARIO_APPLE_TEAM_ID is not set: generating an unsigned project (simulator builds only)." >&2
fi

xcodegen generate --spec project.yml
