# SDK-free verification harness for the Binario Android modules (`app`, `wear`)

Compiles the Kotlin/Compose sources of `app/` and `wear/` and runs their JVM unit tests **without an Android
SDK, without Gradle and without network access to dl.google.com**. Everything lives in this directory
(`<kt>` below); the repository is never modified.

```
REPO=<checkout> <kt>/android-check.sh [ui|app|wear|all|prepare]      # default: all
```

| mode   | what it does                                                                                              |
|--------|-----------------------------------------------------------------------------------------------------------|
| `ui`   | generates `R.kt`/`BuildConfig.kt`, compiles **every** file of `app/src/main/java` with the Compose compiler plugin; prints `ui compile rc=<n> errors=<m> (log <path>)` plus the first error lines |
| `app`  | `ui`, then compiles `app/src/test/java` against it (`-Xfriend-paths`, so `internal` members are visible) and runs the tests with `runner/RunAll.java` (JUnitCore) from `app/` as cwd |
| `wear` | same for `wear/`: `wear/src/main/java` (model, sync, tile, complication **and** the Compose-for-Wear activity) + `wear/src/test/java`, run from `wear/` as cwd |
| `all`  | `app` + `wear`; exit status is non-zero if anything failed                                                 |
| `prepare` | only (re)build the shared pieces: the JUnit runner and the two stub jars                               |

Output per checkout: `<kt>/out/<sha1 of the absolute REPO path>/android/{app,wear}/` with `compile-main.log`,
`compile-test.log`, `test-run.log` (one `FAIL\t<class>\t<test>\t<message> @ <first it.binario frame>` line per
failure and a final `TOTAL\trun=<n> fail=<n> ignored=<n>`), `test-run.err`, `classes/`, `test-classes/` and the
generated sources under `out/<sha>/android/gen/`. Several checkouts (git worktrees) can run concurrently; the
shared stub/runner build under `<kt>/build` is protected by `flock`.

Tests run with `-Duser.timezone=UTC -Duser.language=en -Duser.country=US`, with `src/test/resources` and
`contracts/wear-snapshot/v1` on the classpath, and with the module directory as cwd (tests read
`src/main/assets` and `src/main/res` relatively).

## Validated result (REPO=/home/user/binario)

Merged working tree (claude/git-repo-audit-cdcsm6 + 47 upstream commits), `android-check.sh all`:
```
ui compile rc=0 errors=0
app tests compile rc=0 errors=0
app tests rc=0 run=539 fail=0 ignored=0 classes=99
wear compile rc=0 errors=0
wear tests compile rc=0 errors=0
wear tests rc=0 run=13 fail=0 ignored=0 classes=4
all: app rc=0 wear rc=0
```
(Before the merge the same harness gave 369 app tests / 86 classes and 13 Wear tests / 4 classes, also green.
`all` takes about 2 min on 4 cores; the test runs themselves ~16 s and ~0.2 s.)

`BuildConfig.kt` mirrors the **debug** variant (what Gradle unit tests use): `buildConfigField`s declared in
`buildTypes { getByName("debug") { … } }` override `defaultConfig`, other build types are ignored, and provider
chains such as `providers.gradleProperty("BINARIO_PERSONAL_REALTIME_SOURCES").orElse("true").get()` are resolved
against `gradle.properties` (currently `PERSONAL_REALTIME_SOURCES = true`).

## Files

| file | purpose |
|------|---------|
| `bootstrap.sh` | recreates everything from scratch: `fetch_deps.py` + `android-check.sh prepare`. Pass `--offline` to rebuild only from `cache/`, `--no-google` to skip the (futile) Google Maven attempt |
| `fetch_deps.py` | reads `gradle/verification-metadata.xml` and the two `build.gradle.kts`, downloads/verifies/installs the jars, writes `cp-app.txt`, `cp-wear.txt`, `cp-app-test.txt`, `cp-wear-test.txt`, `platform.txt`, `compose-plugin.txt`, `deps-lock.json`, `deps-report.json`, `fetch.log` |
| `gen_r.py` | emits compile-only `R.kt` (every resource type found under `src/main/res`: values elements, file resources, `@+id/`) and `BuildConfig.kt` (DEBUG, APPLICATION_ID, BUILD_TYPE, VERSION_CODE/NAME from `gradle.properties` through the `providers.gradleProperty(...).orElse(...)` chain of the build file, every `buildConfigField`, e.g. `PERSONAL_REALTIME_SOURCES=false`); only `app` has `buildConfig = true` |
| `runner/RunAll.java` | JUnit 4 runner: scans `dir:<test-classes>` for classes with `@Test` methods, prints `FAIL`/`TOTAL` lines, exits 1 on failure |
| `stubs/common/*.kt`, `stubs/wear/*.kt` | compile stubs for the Google-Maven-only libraries (see below), compiled once into `build/stubs-common.jar` and `build/stubs-wear.jar` |
| `cache/` | raw downloads (`cache/artifacts/<group path>/...`, POMs under `cache/central/`); re-runs are offline-fast |
| `lib/<group>/<name>-<version>.jar` | installed jars (`classes.jar` would be extracted here from any `.aar`) |

### Re-running the dependency fetch

```
cd <kt>; python3 -I fetch_deps.py --repo <checkout>           # online: verifies against the metadata / sidecars / lock
python3 -I fetch_deps.py --repo <checkout> --offline            # from cache only
```
Maven Central rate-limits aggressively (HTTP 429, and a **1-byte `404`** while throttled); the fetcher spaces
requests, retries with exponential backoff and alternates `repo1.maven.org` / `repo.maven.apache.org`.
Checksums: sha256 from `verification-metadata.xml` when the exact artifact is listed there, otherwise the
repository's `.sha256`/`.sha1` sidecar on first download, and `deps-lock.json` on every later run (a mismatch
aborts). Exact artifact file names from the metadata are preferred; `.aar` files are unpacked to `classes.jar`.

## Artifacts used

* **Android platform**: `org.robolectric:android-all:16-robolectric-13921718` (Maven Central) = Android 16 /
  API 36 framework classes, used both as compile platform and at test runtime (last on the classpath; the Maven
  `org.json:json:20260814` jar comes first so `org.json` is the Maven implementation, not the framework's).
  It is the newest `android-all` for API 36 (`17-robolectric-15733970` exists for API 37 and was not used).
* **Kotlin**: the embeddable compiler shipped with Gradle 8.14.3 (`/opt/gradle-8.14.3/lib/kotlin-compiler-embeddable-2.0.21.jar`
  + stdlib/reflect/script-runtime/daemon/trove4j/annotations/kotlinx-coroutines-core-jvm-1.6.4 for the compiler
  itself), run with `-jvm-target 17 -language-version 2.0 -api-version 2.0`, module names `app`, `app_test`,
  `wear`, `wear_test`. The classpath stdlib is `org.jetbrains.kotlin:kotlin-stdlib:2.0.21` (sha256 from the metadata).
* **Compose compiler**: `org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:2.0.21` (Maven Central,
  sha256 from the metadata), passed as `-Xplugin` to every compilation (stubs included).
* **Maven Central libraries exactly as pinned by the metadata**: `org.jsoup:jsoup:1.18.3`,
  `org.mobilitydata:gtfs-realtime-bindings:0.0.8` (+ `com.google.protobuf:protobuf-java:3.16.1`),
  `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0`, `junit:junit:4.13.2`, `org.hamcrest:hamcrest-core:1.3`,
  `org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:1.9.0`, `org.json:json:20260814`, `org.jetbrains:annotations`,
  `com.google.guava:listenablefuture:1.0` (Wear tile `ListenableFuture`).
* **Compose**: Google Maven is not usable here (see below), so the AndroidX Compose artifacts (compose-bom
  2026.06.01 → compose 1.11.4, material3 1.4.0, icons 1.7.8) are replaced by the JetBrains Compose Multiplatform
  **desktop/JVM** jars from Maven Central, version **1.7.3** (the last CMP release built with Kotlin 2.0.21):
  `org.jetbrains.compose.{runtime,ui,foundation,animation,material,material3}:*-desktop:1.7.3`
  (incl. `material-icons-extended-desktop`, `ui-tooling-preview-desktop`), plus their transitive
  `org.jetbrains.skiko:skiko-awt:0.8.18`, `kotlinx-datetime-jvm:0.6.0`, `atomicfu-jvm:0.23.2`, and
  `org.jetbrains.compose.collection-internal:collection-desktop:1.6.0-beta02` (the only JVM republication of
  `androidx.collection` 1.4 on Central, which is what runtime-desktop 1.7.3 depends on).

## Why Google Maven artifacts are substituted

`https://maven.google.com/...` answers every artifact request with `301 → https://dl.google.com/dl/android/maven2/...`
and `dl.google.com` is rejected by the network policy (`CONNECT 403`). `fetch_deps.py` still tries it first and
records the outcome in `deps-report.json` (`google_maven`). Nothing from the groups `androidx.*` and
`com.google.android.*` can therefore be downloaded; those 19 direct dependencies are covered by:

| real dependency | harness replacement |
|-----------------|---------------------|
| `androidx.compose.ui:ui`, `ui-tooling-preview`, `material3`, `material-icons-extended` | JetBrains CMP 1.7.3 desktop jars (same `androidx.compose.*` packages) |
| `androidx.compose.ui` Android-only API: `LocalContext`, `LocalConfiguration`, `LocalResources`, `LocalView`, `stringResource`, `pluralStringResource` | `stubs/common/HarnessComposeAndroidLocals.kt`, `HarnessComposeStringResources.kt` |
| `androidx.annotation` (`@StringRes` …) | `stubs/common/HarnessAndroidxAnnotation.kt` (marker annotations) |
| `androidx.core:core-ktx` (`SharedPreferences.edit`, `ContextCompat`, `NotificationCompat`, `NotificationManagerCompat`, `WindowCompat`, `String.toUri`) | `stubs/common/HarnessAndroidxCore*.kt` (delegate to the framework where possible) |
| `androidx.activity:activity-compose` (`ComponentActivity`, `enableEdgeToEdge`, `SystemBarStyle`, `setContent`, `BackHandler`, `rememberLauncherForActivityResult`, `ActivityResultContracts`) | `stubs/common/HarnessAndroidxActivity*.kt` (`ComponentActivity` is a plain `android.app.Activity`; launchers are no-ops) |
| `androidx.lifecycle:*` (`ViewModel`, `AndroidViewModel`, `viewModelScope`, `ViewModelProvider.Factory`, `SavedStateHandle`, `createSavedStateHandle`, `viewModelFactory { initializer { } }`, `viewModel()`, `Lifecycle.State/Event`, `repeatOnLifecycle` (runs the block once), `compose.LocalLifecycleOwner`, `compose.LifecycleEventEffect` (never fires)) | `stubs/common/HarnessAndroidxLifecycle*.kt`. `viewModelScope` is `SupervisorJob() + Dispatchers.Main.immediate` like the real one (so `Dispatchers.setMain` in tests applies); `SavedStateHandle` is an in-memory map with `getStateFlow`. Reason: the 2.8.x lifecycle KMP core is Google-Maven-only and JetBrains republished the core only at 2.11.0 (Kotlin 2.2 metadata, unreadable by the 2.0.21 compiler) |
| `androidx.work:work-runtime-ktx` | `stubs/common/HarnessAndroidxWork.kt` (`CoroutineWorker`, request builders, `WorkManager` that records requests in memory) |
| `com.google.android.gms:play-services-wearable` (+ `gms.tasks`) | `stubs/common/HarnessGms*.kt`; every client call completes with a failed `Task`, i.e. like a phone without Play services (the app's `runWearSafely` path) |
| `androidx.wear.compose:compose-material3` (`Text`, `MaterialTheme`, `Card`, `Button`) | `stubs/wear/HarnessWearComposeMaterial3.kt` on top of CMP foundation |
| `androidx.wear.tiles`, `androidx.wear.protolayout` | `stubs/wear/HarnessWearTiles.kt`, `HarnessWearProtolayout.kt`; builders keep the real shapes, **`Layout.toByteArray()` is a deterministic UTF-8 structural dump, not the protobuf wire format** (the tile timeline test only compares two serialisations of the same layout) |
| `androidx.wear.watchface:watchface-complications-data-source-ktx` | `stubs/wear/HarnessWearComplications*.kt` |
| `androidx.concurrent:concurrent-futures` | `stubs/wear/HarnessConcurrentFutures.kt` (`CallbackToFutureAdapter` over a `CompletableFuture`) |
| `androidx.wear.compose:compose-foundation`, `compose-ui-tooling`, `ui-tooling` (debug), `androidTest*` | not needed by the sources / not part of a unit-test build; skipped |

All stubs are compile-time models of exactly the members the Binario sources use; they are **not** the
libraries, so a green harness proves "the code compiles against these API shapes and the JVM tests pass", not
Android runtime behaviour.

## Known limitations (vs. a real Gradle build)

* No AGP, no `aapt2`/resource linking, no manifest merging, no lint, no R8/ProGuard, no APK, no instrumented
  (`androidTest`) tests, no Compose UI tests. Resource ids in `R.kt` are synthetic (`0x7f<type><idx>`); string
  values are never resolved, so a wrong `@string/...` reference in XML is not detected (only Kotlin references
  to a missing `R.*` name are).
* Compose is **1.7.3 desktop (JetBrains)** instead of **1.11.4 Android (AndroidX)**; material3 **1.3** level
  instead of **1.4**; icons 1.7.3 instead of 1.7.8; `androidx.collection` 1.4 instead of 1.5. A source using an
  API introduced after compose 1.7 / material3 1.3 would fail here but build with Gradle (and vice versa for
  removed deprecated APIs). Android-specific Compose entry points are stubs (see table).
* Compose code is compiled but never executed: no `@Composable` runs during the tests (the project has no
  Robolectric/Compose UI unit tests), so stub behaviour of UI entry points is irrelevant to the results.
* Lifecycle/WorkManager/Play-services/Wear behaviour is simulated by stubs; the harness cannot catch
  misuse of those libraries that the type system does not express.
* `android-all` is the stock framework without Robolectric's shadows: framework classes with native methods
  (`Log`, `Parcel`, `Bundle`, `Looper`…) throw `UnsatisfiedLinkError` if a test reaches them. No current test
  does (369 + 13 green); such a failure would show up as `FAIL ... UnsatisfiedLinkError` in `test-run.log`.
* `-Xjvm-default=all` and K2 (`-language-version 2.0`) mirror the project's Kotlin 2.0.21 defaults; Gradle's
  incremental compilation, build cache and the Kotlin daemon are not used (each run is a full compile, ~1 min).
* Test discovery is by `@Test`/`@RunWith` on compiled classes (same set Gradle finds: 86 app classes, 4 Wear
  classes); `@Ignore` is counted in `ignored=`.
* Dependency resolution is a small POM walk (compile+runtime scope, highest version wins, KMP roots mapped to
  `-desktop`/`-jvm` jars) rather than Gradle module metadata; versions come from the verification metadata
  where available (`kotlinx-coroutines 1.9.0`, `protobuf-java 3.16.1`, …).

## Environment notes

* Java: `/usr/bin/java` is OpenJDK 21 in this container (the harness targets 17 via `-jvm-target 17` /
  `javac --release 17`; set `JAVA`/`JAVAC` to use another JDK). `JAVA_TOOL_OPTIONS` (container proxy) is
  unset for every JVM the harness starts.
* `GRADLE_LIB` (default `/opt/gradle-8.14.3/lib`) must contain the Kotlin 2.0.21 embeddable compiler jars.
* `python3 -I` is used for the generator/fetcher (they parse downloaded POMs and repository XML).
