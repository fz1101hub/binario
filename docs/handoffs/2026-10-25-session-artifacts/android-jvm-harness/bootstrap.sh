#!/usr/bin/env bash
# bootstrap.sh -- recreate the harness state from scratch (the scratchpad can be wiped).
#
#   REPO=<checkout> bootstrap.sh [fetch_deps.py options, e.g. --offline --no-google]
#
# 1. fetch_deps.py : download + verify every jar (Maven Central; Google Maven is attempted but 301s to the
#                    blocked dl.google.com), extract classes.jar from aars, write cp-*.txt / platform.txt.
# 2. android-check.sh prepare : compile the JUnit runner and the two stub jars.
# Afterwards:  REPO=<checkout> android-check.sh [ui|app|wear|all]
set -euo pipefail
KT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="${REPO:-/home/user/binario}"
export REPO
mkdir -p "$KT/cache" "$KT/lib" "$KT/out" "$KT/build"
"${PYTHON:-python3}" -I "$KT/fetch_deps.py" --repo "$REPO" --kt "$KT" "$@"
"$KT/android-check.sh" prepare
echo "bootstrap done: $(wc -l <"$KT/cp-app.txt") app jars, $(wc -l <"$KT/cp-wear.txt") wear jars, platform $(cat "$KT/platform.txt")"
