#!/usr/bin/env bash
# android-check.sh -- SDK-free compile + JVM unit-test harness for the Binario Android modules (app, wear).
#
#   REPO=<checkout path> android-check.sh [ui|app|wear|all|prepare]      (default: all)
#
#   ui      compile every Kotlin file of app/src/main/java (+ generated R/BuildConfig) with the Compose compiler
#   app     ui + compile app/src/test/java against it (-Xfriend-paths) and run the JUnit 4 tests (cwd = app/)
#   wear    compile wear/src/main/java and wear/src/test/java, run the Wear tests (cwd = wear/)
#   all     ui + app + wear
#   prepare only build the shared pieces (JUnit runner, stub jars); used by bootstrap.sh
#
# Output goes to <kt>/out/<sha1 of REPO path>/android/ so several checkouts can run concurrently.
# Exit status is non-zero on compile errors or test failures.
set -uo pipefail

KT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_IN="${REPO:-/home/user/binario}"
REPO="$(cd "$REPO_IN" 2>/dev/null && pwd)" || { echo "REPO=$REPO_IN is not a directory" >&2; exit 2; }
MODE="${1:-all}"
JAVA="${JAVA:-/usr/bin/java}"
unset JAVA_TOOL_OPTIONS   # container proxy settings; the harness works offline and the "Picked up" line pollutes logs
GLIB="${GRADLE_LIB:-/opt/gradle-8.14.3/lib}"
PYTHON="${PYTHON:-python3}"

REPO_SHA="$(printf '%s' "$REPO" | sha1sum | cut -c1-40)"
OUT="$KT/out/$REPO_SHA/android"
BUILD="$KT/build"
mkdir -p "$OUT" "$BUILD"

need() { [ -s "$KT/$1" ] || { echo "missing $KT/$1 -- run $KT/bootstrap.sh (fetch_deps.py) first" >&2; exit 2; }; }
need cp-app.txt; need cp-wear.txt; need cp-app-test.txt; need cp-wear-test.txt; need platform.txt; need compose-plugin.txt
for j in kotlin-compiler-embeddable-2.0.21.jar kotlin-stdlib-2.0.21.jar kotlinx-coroutines-core-jvm-1.6.4.jar; do
  [ -f "$GLIB/$j" ] || { echo "missing $GLIB/$j (set GRADLE_LIB)" >&2; exit 2; }
done

PLATFORM="$(head -n1 "$KT/platform.txt")"
COMPOSE_PLUGIN="$(head -n1 "$KT/compose-plugin.txt")"
joincp() { paste -sd: "$1"; }
CP_APP="$(joincp "$KT/cp-app.txt")"
CP_WEAR="$(joincp "$KT/cp-wear.txt")"
CP_APP_TEST="$(joincp "$KT/cp-app-test.txt")"      # org.json first, then junit/hamcrest/coroutines-test
CP_WEAR_TEST="$(joincp "$KT/cp-wear-test.txt")"
JUNIT_JAR="$(grep -m1 '/junit-' "$KT/cp-app-test.txt")"
STUBS_COMMON="$BUILD/stubs-common.jar"
STUBS_WEAR="$BUILD/stubs-wear.jar"
RUNNER="$BUILD/runner"

KOTLINC_CP="$GLIB/kotlin-compiler-embeddable-2.0.21.jar:$GLIB/kotlin-stdlib-2.0.21.jar:$GLIB/kotlin-reflect-2.0.21.jar:$GLIB/kotlin-script-runtime-2.0.21.jar:$GLIB/kotlin-daemon-embeddable-2.0.21.jar:$GLIB/trove4j-1.0.20200330.jar:$GLIB/annotations-24.0.1.jar:$GLIB/kotlinx-coroutines-core-jvm-1.6.4.jar"

# kotlinc <log> <args...>   (Kotlin 2.0.21 embeddable compiler, settings mirror the Gradle build: jvmTarget 17, K2)
kotlinc() {
  local log="$1"; shift
  "$JAVA" -Xmx3g -Xss8m -Dfile.encoding=UTF-8 -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -language-version 2.0 -api-version 2.0 -Xjvm-default=all \
    -Xplugin="$COMPOSE_PLUGIN" "$@" >"$log" 2>&1
}
count_errors() { grep -c -E '(^|[^a-z])error: ' "$1" 2>/dev/null || true; }
show_errors() { grep -E '(: error: |^error: |^exception: |^Caused by)' "$1" | head -n "${2:-12}"; }
compile_report() {  # <label> <rc> <log>
  local n; n="$(count_errors "$3")"
  echo "$1 compile rc=$2 errors=$n (log $3)"
  if [ "$2" -ne 0 ] || [ "$n" -ne 0 ]; then show_errors "$3"; return 1; fi
  return 0
}
newer_than() {  # <dir> <target>: any *.kt/*.java under dir newer than target?
  [ ! -s "$2" ] || [ -n "$(find "$1" -type f \( -name '*.kt' -o -name '*.java' \) -newer "$2" -print -quit)" ]
}

prepare() {
  # shared pieces: serialised with flock so concurrent checkouts do not race on them
  exec 9>"$BUILD/.lock"; flock 9
  if newer_than "$KT/runner" "$RUNNER/RunAll.class"; then
    rm -rf "$RUNNER"; mkdir -p "$RUNNER"
    "${JAVAC:-javac}" --release 17 -cp "$JUNIT_JAR" -d "$RUNNER" "$KT/runner/RunAll.java" >"$BUILD/runner.log" 2>&1 \
      || { echo "runner compile failed (see $BUILD/runner.log)" >&2; exit 2; }
  fi
  if newer_than "$KT/stubs/common" "$STUBS_COMMON"; then
    rm -f "$STUBS_COMMON"
    kotlinc "$BUILD/stubs-common.log" -module-name harness_stubs -classpath "$CP_APP:$PLATFORM" -d "$STUBS_COMMON" \
      $(find "$KT/stubs/common" -name '*.kt' | sort)
    compile_report "stubs-common" $? "$BUILD/stubs-common.log" || exit 2
  fi
  if newer_than "$KT/stubs/wear" "$STUBS_WEAR"; then
    rm -f "$STUBS_WEAR"
    kotlinc "$BUILD/stubs-wear.log" -module-name harness_stubs_wear -classpath "$CP_WEAR:$STUBS_COMMON:$PLATFORM" -d "$STUBS_WEAR" \
      $(find "$KT/stubs/wear" -name '*.kt' | sort)
    compile_report "stubs-wear" $? "$BUILD/stubs-wear.log" || exit 2
  fi
  flock -u 9
}

# compile_main <module> <classpath-without-platform>
compile_main() {
  local module="$1" cp="$2" dir="$OUT/$1"
  "$PYTHON" -I "$KT/gen_r.py" --repo "$REPO" --module "$module" --out "$OUT/gen/$module" || return 2
  rm -rf "$dir/classes"; mkdir -p "$dir/classes"
  { find "$REPO/$module/src/main/java" -name '*.kt'; find "$OUT/gen/$module" -name '*.kt'; } | sort >"$dir/main-sources.txt"
  kotlinc "$dir/compile-main.log" -module-name "$module" -classpath "$cp:$PLATFORM" -d "$dir/classes" @"$dir/main-sources.txt"
  local rc=$?
  compile_report "${3:-$module}" $rc "$dir/compile-main.log"
}

# compile_tests <module> <classpath-without-platform>  (friend path = main classes => internal members visible)
compile_tests() {
  local module="$1" cp="$2" dir="$OUT/$1"
  rm -rf "$dir/test-classes"; mkdir -p "$dir/test-classes"
  find "$REPO/$module/src/test/java" -name '*.kt' | sort >"$dir/test-sources.txt"
  kotlinc "$dir/compile-test.log" -module-name "${module}_test" -Xfriend-paths="$dir/classes" \
    -classpath "$dir/classes:$cp:$PLATFORM" -d "$dir/test-classes" @"$dir/test-sources.txt"
  compile_report "$module tests" $? "$dir/compile-test.log"
}

# run_tests <module> <runtime classpath without platform>
run_tests() {
  local module="$1" cp="$2" dir="$OUT/$1"
  local res="$REPO/$module/src/test/resources:$REPO/contracts/wear-snapshot/v1"
  ( cd "$REPO/$module" && "$JAVA" -Xmx2g -Duser.timezone=UTC -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8 \
      -cp "$dir/test-classes:$res:$cp:$PLATFORM:$RUNNER" RunAll "dir:$dir/test-classes" ) >"$dir/test-run.log" 2>"$dir/test-run.err"
  local rc=$?
  grep -E '^(FAIL|TOTAL)' "$dir/test-run.log" | head -n 60
  local total; total="$(grep -m1 '^TOTAL' "$dir/test-run.log" | cut -f2-)"
  echo "$module tests rc=$rc ${total:-<no TOTAL line>} (log $dir/test-run.log, stderr $dir/test-run.err)"
  if [ -s "$dir/test-run.err" ]; then echo "  stderr: $(head -n 3 "$dir/test-run.err" | tr '\n' ' ')"; fi
  return $rc
}

FAILED=0
echo "repo=$REPO mode=$MODE out=$OUT"
prepare
case "$MODE" in
  prepare) ;;
  ui)
    compile_main app "$CP_APP:$STUBS_COMMON" ui || FAILED=1 ;;
  app)
    compile_main app "$CP_APP:$STUBS_COMMON" ui || FAILED=1
    if [ $FAILED -eq 0 ]; then
      compile_tests app "$CP_APP_TEST:$CP_APP:$STUBS_COMMON" || FAILED=1
    fi
    if [ $FAILED -eq 0 ]; then
      run_tests app "$OUT/app/classes:$CP_APP_TEST:$CP_APP:$STUBS_COMMON" || FAILED=1
    fi ;;
  wear)
    compile_main wear "$CP_WEAR:$STUBS_COMMON:$STUBS_WEAR" || FAILED=1
    if [ $FAILED -eq 0 ]; then
      compile_tests wear "$CP_WEAR_TEST:$CP_WEAR:$STUBS_COMMON:$STUBS_WEAR" || FAILED=1
    fi
    if [ $FAILED -eq 0 ]; then
      run_tests wear "$OUT/wear/classes:$CP_WEAR_TEST:$CP_WEAR:$STUBS_COMMON:$STUBS_WEAR" || FAILED=1
    fi ;;
  all)
    "$0" app; a=$?
    "$0" wear; w=$?
    [ $a -eq 0 ] && [ $w -eq 0 ] || FAILED=1
    echo "all: app rc=$a wear rc=$w" ;;
  *) echo "usage: REPO=<checkout> $0 [ui|app|wear|all|prepare]" >&2; exit 2 ;;
esac
exit $FAILED
