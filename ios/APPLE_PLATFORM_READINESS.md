# Binario Apple Platform MVP: implementation and readiness

**Status:** the repository contains a production-oriented first iPhone MVP, a reusable pure Swift 6 core, and a coherent iPhone–Apple Watch XcodeGen project. The official Swift 6.4 Linux toolchain executes the package tests locally; Linux static checks validate the source graph and contracts. This environment cannot run XcodeGen's macOS binary, Xcode, iOS/watchOS simulators, signing, provisioning, or physical Watch Connectivity delivery, so those outcomes are deliberately not claimed.

## What is implemented

The iPhone application is a native SwiftUI app for iOS 17 and later. Its bundle identifier is `it.binario.ios`, its marketing version is `0.1.0`, and its build number is `1`. The app includes a journey planner, canonical station suggestions, local saved routes, settings, privacy disclosures, data-source disclosures, empty states, Dynamic Type-compatible native controls, and accessibility labels. Italian is the development language, with complete English, German, French, and Spanish resources. Station and provider names are never translated.

`BinarioKit` is a Swift 6 package with no third-party runtime dependency; it links only the platform SQLite and zlib libraries. It decodes the canonical 5,239-station repository asset in place instead of copying it, off the main actor, and exposes explicit loading, available, and unavailable states. The package validates that the station asset is identity-only and non-operational. A deterministic builder derives a compressed, verified SQLite schedule index from the ten checked-in GTFS packs. A reviewed policy locks each source ID to its HTTPS URL and `OPEN_DIRECT` access mode and records the licence shown in the Settings source list (`null` where the owner has not confirmed it yet); the manifest remains responsible for each refreshable archive SHA-256, and both policy and manifest enter the artifact fingerprint. Settings lists every pack of the index with attribution, last service day, licence and source page. Only complete rail trips touching a geographically verified canonical station identity are retained. Child/platform inheritance is rejected when explicit child coordinates contradict the parent identity; IANA timezones and monotonic stop times are validated by both builder and independent verifier. Large stop-time filtering uses disk-backed SQLite staging plus explicit retained-row and materialized-text budgets. At first launch the app validates metadata and SHA-256, performs bounded zlib decompression to a serialized atomic local cache excluded from backup, checks the SQLite schema and input fingerprint, then enables `SQLiteGTFSJourneyRepository`; every failure falls back to `FailClosedJourneyRepository`. Results are explicitly `PROGRAMMED` static data with their real feed attribution and manifest observation date. The provider never invents delays, platforms, cancellations, realtime status, or unverified station identity.

Saved routes are strictly validated and serialized only to the iPhone's local `UserDefaults` domain. Corrupt but syntactically valid JSON, duplicate route IDs, identical endpoints, and oversized fields fail closed. The app adds no account, cloud synchronization, analytics, advertising, location, microphone, background mode, background networking, credentials, cleartext endpoint, or signing identity. The 32-route bound is inherited from the main product's saved-route constraint.

## Apple Watch integration

Watch sharing is local, persisted, and **off by default**. The iOS app activates Watch Connectivity during bootstrap, keeps only the latest validated pending operation until activation completes, and publishes the existing Wear snapshot v1 contract under `binario_snapshot_json`. `WearSnapshotEncoder` rejects unsupported versions, non-iOS producers, invalid timestamps, mismatched or excessive time-to-live values, more than two services, invalid field lengths, out-of-range delays, incoherent unavailable states, and payloads over 8,192 bytes before the session is touched. Disabling sharing replaces any pending snapshot with the explicit transport-level `binario_snapshot_clear` signal; the Watch gives that revocation precedence, clears shared storage, and reloads widgets. Publication errors and unsupported sessions are surfaced without claiming physical delivery. A successful GTFS query publishes at most two services marked `PROGRAMMED` with static-data disclosure and real provenance; a failed query publishes a short-lived, service-free `UNAVAILABLE` snapshot.

`ios/project.yml` owns the integrated Apple graph in Swift language mode 6: the iPhone app embeds a modern single-target `BinarioWatch` application (`it.binario.ios.watchkitapp`); the watch target embeds its WidgetKit extension (`it.binario.ios.watchkitapp.widgets`); `WKWatchOnly` is disabled; and `WKCompanionAppBundleIdentifier` is `it.binario.ios`. Marketing version and build number are shared by all embedded products. The iPhone, Watch app, and Watch widget each carry a privacy manifest appropriate to their `UserDefaults` scope (`CA92.1` for app-only preferences and `1C8F.1` for the app-group cache). The previous standalone `apple-watch/project.yml` retains its independent `it.binario.watch` identity; it has not been silently reinterpreted as an integrated build. Bundle identity and WidgetKit syntax now have local static guards, **not** an Xcode archive or installation proof.

## Verification available in the repository

From Linux, run:

```bash
python3 -m unittest -v tests/test_verify_ios_project.py
python3 -m unittest -v tests/test_verify_apple_watch_project.py
python3 -m unittest -v tests/test_ios_gtfs_schedule.py
python3 tools/build_ios_gtfs_schedule.py
python3 tools/verify_ios_gtfs_schedule.py
```

On any host with a Swift 6-compatible toolchain, run:

```bash
cd ios/BinarioKit
swift test
```

On macOS with the repository-pinned Xcode 16.4 and XcodeGen 2.46.0, install XcodeGen through `tools/install_xcodegen.py`, then run:

```bash
cd ios
python3 Scripts/verify-deterministic-generation.py --xcodegen /absolute/path/to/xcodegen
xcodegen generate --spec project.yml
xcodebuild -project Binario.xcodeproj -scheme Binario \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  CODE_SIGNING_ALLOWED=NO test
xcodebuild -project Binario.xcodeproj -scheme BinarioWatch \
  -destination 'platform=watchOS Simulator,name=Apple Watch Series 10 (46mm)' \
  CODE_SIGNING_ALLOWED=NO test
```

The initial Linux evidence for this tranche was 101 Python tests and 34 Swift package tests, plus a semantic AppModel typecheck through a local Combine compatibility shim and syntax parsing of all iPhone/Watch sources. The checked-in production GTFS asset contains 29,479 trips and 364,120 stop-times; its 7,371,419-byte zlib artifact and 24,494,080-byte database verify against their recorded SHA-256 values. Repeated builds on the same Linux toolchain were byte-deterministic; **the macOS rebuild produced different database page bytes** while reporting the same row counts and input fingerprint. Row-by-row equivalence on macOS has **not yet been established**. The CI rail now verifies the committed asset's exact SHA-256, then verifies and compares every table/row, schema, metadata and fingerprint from an independent macOS rebuild instead of accepting an unverified byte mismatch. This new gate still needs a successful macOS execution on its exact SHA.

Simulator names vary by installed runtimes. The pinned `Apple Watch CI` workflow selects available device identifiers and contains separate bounded jobs for the standalone Watch project and the integrated iPhone project. The `ios` job also runs `BinarioWatchTests` on the integrated project, then archives an unsigned Release build (`xcodebuild archive -configuration Release -destination generic/platform=iOS CODE_SIGNING_ALLOWED=NO`) and checks what App Store Connect would receive: the embedded `Watch/BinarioWatch.app` and `PlugIns/BinarioWatchWidgets.appex`, a `PrivacyInfo.xcprivacy` in all three bundles, `CFBundleIconName` on both apps and `ITSAppUsesNonExemptEncryption` on every executable; the `.xcarchive` is kept as a 14-day artifact for inspection. A signed export still needs App Store Connect API key secrets and an `ExportOptions.plist`, or Xcode Organizer on a Mac. Its commands, Xcode/XcodeGen versions, actions, runner and shell-step hashes are covered by `.github/ci-inputs-lock.json`. GitHub assigned a macOS runner to [run #36877491851](https://github.com/fz1101hub/binario/actions/runs/36877491851): both jobs executed steps, then failed (watchOS `TEST_HOST` absent; iOS binary SQLite diff at the earlier byte-identity gate), so it is **not** green CI. Later workflow fixes require a new remote run.

## Required release follow-up

A macOS verifier must confirm that XcodeGen 2.46.0 accepts the modern watchOS `application` target, that Xcode embeds the generated Watch app in the iPhone bundle, and that both iOS and watchOS test suites pass. The macOS runner has reached the jobs but did **not** reach a successful app test; do not treat the existing run as simulator validation. Building the standalone Watch target explicitly before testing is an unverified attempt to diagnose/correct its `TEST_HOST` error; if the next run still fails, inspect the generated project/test-host path instead of suppressing tests.

Next, provision `it.binario.ios`, `it.binario.ios.watchkitapp`, `it.binario.ios.watchkitapp.widgets`, and `group.it.binario.watch` for the integrated project. The separate standalone Watch project continues to use `it.binario.watch` and `it.binario.watch.widgets`. `ios/project.yml` sets `CODE_SIGN_STYLE: Automatic` and `DEVELOPMENT_TEAM: ${BINARIO_APPLE_TEAM_ID}` for every target: export the Team ID of the Apple Developer account (`BINARIO_APPLE_TEAM_ID=XXXXXXXXXX`) before `xcodegen generate` or `Scripts/generate-project.sh`, and Xcode's automatic signing registers the three App IDs and the App Groups capability on the two Watch App IDs. With the variable unset the generated project stays unsigned, which is what the simulator-only CI (`CODE_SIGNING_ALLOWED=NO`) requires. No Team ID, certificate or profile is checked in; the identifiers must still be confirmed in the actual Apple Developer account before any signing or upload.

Finally, install a development build on a paired iPhone and Apple Watch. Verify a covered iPhone search produces a `PROGRAMMED` application context with no realtime claims, the Watch accepts at most two services, and the widget reloads from shared storage. Verify an uncovered search produces an `UNAVAILABLE` context with no services. Repeat with the phone unreachable, after snapshot expiry, and after disabling Watch sharing. Record logs or a test protocol as hardware evidence; this repository does not claim delivery proof.

Before TestFlight or App Store submission, provide approved legal privacy text and support contact details, create reviewed store metadata and screenshots in all intended locales, complete Apple's privacy questionnaire from the implemented capability set, run accessibility review at the largest Dynamic Type sizes and with VoiceOver, archive with distribution signing, and conduct TestFlight testing on supported iPhone/Watch combinations.

## References

[1]: ../contracts/wear-snapshot/v1/README.md "Binario Wear snapshot v1 contract"
[2]: ../docs/data-sources/CURATED_STATION_CATALOG.md "Binario canonical curated station catalogue"
