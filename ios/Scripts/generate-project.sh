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

xcodegen generate --spec project.yml
