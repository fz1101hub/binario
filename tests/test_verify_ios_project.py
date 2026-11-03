"""Static invariants for the native Binario iOS XcodeGen project."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import plistlib
import re
import struct
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
IOS = ROOT / "ios"
CATALOG = ROOT / "app/src/main/assets/stations/curated_it_rail_stations.json"
CATALOG_MANIFEST = ROOT / "app/src/main/assets/stations/manifest.json"
LANGUAGES = {
    "it", "en", "de", "fr", "es",
    "ar", "hi", "id", "ja", "ko", "my", "pt-BR", "ru", "th", "tr", "vi", "zh-Hans",
}
STRINGS_ENTRY = re.compile(r'^"((?:[^"\\]|\\.)+)"\s*=\s*"((?:[^"\\]|\\.)*)";$')
SWIFT_REQUIRED_KEY = re.compile(r'"((?:action|app|tab|planner|catalog|schedule|result|saved|settings|watch|accessibility)\.[a-z0-9_.]+)"')
PLACEHOLDER = re.compile(r'%(?:\d+\$)?(?:hh|h|ll|l)?[@diufFeEgGxXoscpaA]')


def read_strings(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("//"):
            continue
        match = STRINGS_ENTRY.fullmatch(line)
        if not match:
            raise AssertionError(f"{path}:{line_number}: malformed .strings entry")
        key, value = match.groups()
        if key in values:
            raise AssertionError(f"{path}:{line_number}: duplicate key {key}")
        values[key] = value
    return values


def privacy_manifest(path: Path) -> dict:
    with path.open("rb") as source:
        return plistlib.load(source)


def colorset_values(catalog: Path, name: str) -> dict[str, str]:
    """Hex value per appearance ("any" or "dark") of an asset-catalogue colour set."""
    contents = json.loads((catalog / f"{name}.colorset" / "Contents.json").read_text(encoding="utf-8"))
    values: dict[str, str] = {}
    for entry in contents["colors"]:
        appearance = next(
            (item["value"] for item in entry.get("appearances", []) if item["appearance"] == "luminosity"),
            "any",
        )
        components = entry["color"]["components"]
        values[appearance] = "#" + "".join(
            f"{int(components[channel], 0):02X}" for channel in ("red", "green", "blue")
        )
        assert components["alpha"] == "1.000", (name, appearance)
    return values


def contrast(first: str, second: str) -> float:
    def luminance(colour: str) -> float:
        channels = [int(colour[index:index + 2], 16) / 255 for index in (1, 3, 5)]
        linear = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in channels]
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]

    lighter, darker = sorted((luminance(first), luminance(second)), reverse=True)
    return (lighter + 0.05) / (darker + 0.05)


class IOSProjectTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.project = yaml.safe_load((IOS / "project.yml").read_text(encoding="utf-8"))
        cls.targets = cls.project["targets"]

    def test_app_identity_version_and_swift6_phone_deployment_are_explicit(self) -> None:
        app = self.targets["Binario"]
        settings = app["settings"]["base"]
        global_settings = self.project["settings"]["base"]

        self.assertEqual("application", app["type"])
        self.assertEqual("iOS", app["platform"])
        self.assertEqual("17.0", app["deploymentTarget"])
        self.assertEqual("it.binario.ios", settings["PRODUCT_BUNDLE_IDENTIFIER"])
        self.assertEqual(6.0, global_settings["SWIFT_VERSION"])
        self.assertEqual("0.1.0", global_settings["MARKETING_VERSION"])
        self.assertEqual(1, global_settings["CURRENT_PROJECT_VERSION"])
        # Signing comes from the environment at generation time: no team or identity is
        # committed, but an archive needs one team on every target.
        self.assertEqual("Automatic", global_settings["CODE_SIGN_STYLE"])
        self.assertEqual("${BINARIO_APPLE_TEAM_ID}", global_settings["DEVELOPMENT_TEAM"])
        self.assertNotIn("CODE_SIGN_IDENTITY", global_settings)
        self.assertIn('export BINARIO_APPLE_TEAM_ID="${BINARIO_APPLE_TEAM_ID:-}"', (IOS / "Scripts/generate-project.sh").read_text(encoding="utf-8"))
        self.assertIn("BINARIO_APPLE_TEAM_ID", (IOS / "APPLE_PLATFORM_READINESS.md").read_text(encoding="utf-8"))
        self.assertEqual("1", settings["TARGETED_DEVICE_FAMILY"])
        self.assertIs(False, settings["SUPPORTS_MACCATALYST"])
        self.assertIs(False, settings["SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD"])
        self.assertIs(False, settings["SUPPORTS_XR_DESIGNED_FOR_IPHONE_IPAD"])
        # The development region comes from the project, not from INFOPLIST_KEY_ overrides that
        # would conflict with XcodeGen's default "en"; the .lproj folders declare the languages.
        self.assertEqual("it", self.project["options"]["developmentLanguage"])
        self.assertNotIn("INFOPLIST_KEY_CFBundleDevelopmentRegion", settings)
        self.assertNotIn("INFOPLIST_KEY_CFBundleLocalizations", settings)
        self.assertEqual(LANGUAGES, {path.name.removesuffix(".lproj") for path in (IOS / "Resources").glob("*.lproj")})
        # Every executable declares export compliance, or App Store Connect holds each build.
        self.assertIs(False, settings["INFOPLIST_KEY_ITSAppUsesNonExemptEncryption"])
        self.assertIs(False, self.targets["BinarioWatch"]["settings"]["base"]["INFOPLIST_KEY_ITSAppUsesNonExemptEncryption"])
        widget_plist = plistlib.loads((ROOT / "apple-watch/BinarioWatchWidgets/Info.plist").read_bytes())
        self.assertIs(False, widget_plist["ITSAppUsesNonExemptEncryption"])

    def test_iphone_test_bundles_have_unique_nonempty_product_names(self) -> None:
        names = [self.targets[target]["settings"]["base"]["PRODUCT_NAME"]
                 for target in ("BinarioAppTests", "BinarioKitTests")]
        self.assertEqual(["BinarioAppTests", "BinarioKitTests"], names)
        self.assertEqual(len(names), len(set(names)))
        hosted = self.targets["BinarioAppTests"]["settings"]["base"]
        self.assertEqual("$(BUILT_PRODUCTS_DIR)/Binario.app/Binario", hosted["TEST_HOST"])
        self.assertEqual("$(TEST_HOST)", hosted["BUNDLE_LOADER"])
        self.assertNotIn("TEST_HOST", self.targets["BinarioKitTests"]["settings"]["base"])

    def test_sqlite_and_zlib_pkg_config_only_apply_to_linux_host(self) -> None:
        manifest = (IOS / "BinarioKit/Package.swift").read_text(encoding="utf-8")
        self.assertIn("#if os(Linux)", manifest)
        self.assertIn('let sqlitePkgConfig: String? = "sqlite3"', manifest)
        self.assertIn('let zlibPkgConfig: String? = "zlib"', manifest)
        self.assertIn("let sqlitePkgConfig: String? = nil", manifest)
        self.assertIn("let zlibPkgConfig: String? = nil", manifest)
        self.assertIn("pkgConfig: sqlitePkgConfig", manifest)
        self.assertIn("pkgConfig: zlibPkgConfig", manifest)
        self.assertNotIn('pkgConfig: "sqlite3"', manifest)
        self.assertNotIn('pkgConfig: "zlib"', manifest)

    def test_debug_targets_match_the_active_simulator_architecture(self) -> None:
        for name in (
            "Binario",
            "BinarioAppTests",
            "BinarioKitTests",
            "BinarioWatch",
            "BinarioWatchTests",
            "BinarioWatchWidgets",
        ):
            with self.subTest(target=name):
                settings = self.targets[name]["settings"]
                self.assertTrue(settings["configs"]["debug"]["ONLY_ACTIVE_ARCH"])
                self.assertEqual("-Onone", settings["configs"]["debug"]["SWIFT_OPTIMIZATION_LEVEL"])
                self.assertNotIn("ONLY_ACTIVE_ARCH", settings["base"])
                self.assertNotIn("SWIFT_OPTIMIZATION_LEVEL", settings["base"])
                self.assertNotIn("release", settings["configs"])

    def test_hosted_test_bundles_import_debug_builds_compiled_for_testing(self) -> None:
        # BinarioAppTests and BinarioWatchTests use `@testable import`; a host app built
        # without ENABLE_TESTABILITY makes xcodebuild fail with "was not compiled for testing".
        for name in ("Binario", "BinarioWatch"):
            with self.subTest(target=name):
                settings = self.targets[name]["settings"]
                self.assertTrue(settings["configs"]["debug"]["ENABLE_TESTABILITY"])
                self.assertNotIn("ENABLE_TESTABILITY", settings["base"])

    def test_watch_sharing_binding_uses_explicit_actor_isolated_setter_closure(self) -> None:
        settings = (IOS / "BinarioApp/SettingsView.swift").read_text(encoding="utf-8")
        self.assertIn("set: { enabled in model.setWatchSharingEnabled(enabled) }", settings)
        self.assertNotIn("set: model.setWatchSharingEnabled", settings)

    def test_watch_connectivity_activation_mapping_has_explicit_returns(self) -> None:
        adapter = (IOS / "BinarioApp/WCSessionAdapter.swift").read_text(encoding="utf-8")
        for state in ("notActivated", "inactive", "activated"):
            self.assertIn(f"case .{state}: return .{state}", adapter)
        self.assertIn("@unknown default: return .failed", adapter)

    def test_unpaired_iphone_reports_a_neutral_watch_status_and_off_reads_as_off(self) -> None:
        adapter = (IOS / "BinarioApp/WCSessionAdapter.swift").read_text(encoding="utf-8")
        snapshot = (IOS / "BinarioKit/Sources/BinarioKit/WearSnapshotV1.swift").read_text(encoding="utf-8")
        model = (IOS / "BinarioApp/AppModel.swift").read_text(encoding="utf-8")
        settings = (IOS / "BinarioApp/SettingsView.swift").read_text(encoding="utf-8")
        # An iPhone without a paired Watch used to show "Aggiornamento non riuscito" under a
        # toggle that was off; the pairing state is checked before any context is sent.
        self.assertIn("isSupported && session.isPaired", adapter)
        self.assertIn("isSupported && session.isWatchAppInstalled", adapter)
        self.assertIn("var isPaired: Bool { get }", snapshot)
        self.assertIn("guard session.isPaired, session.isWatchAppInstalled else {", snapshot.split("case let .publish(operation):", 1)[1])
        self.assertIn("case notPaired", snapshot)
        self.assertIn('case .notPaired: "watch.status.not_paired"', model)
        self.assertIn("watchSharingEnabled ? watchSyncStatus : .disabled", model)
        self.assertIn("model.displayedWatchSyncStatus.localizationKey", settings)
        self.assertNotIn("model.watchSyncStatus.localizationKey", settings)
        self.assertIn("testUnpairedIPhoneReadsOffWhenSharingIsOffAndNotPairedWhenOn", (IOS / "BinarioAppTests/BinarioAppTests.swift").read_text(encoding="utf-8"))
        self.assertIn("testUnpairedPhoneReportsNotPairedAndKeepsTheOperationPending", (IOS / "BinarioKit/Tests/BinarioKitTests/WearSnapshotV1Tests.swift").read_text(encoding="utf-8"))

    def test_generated_targets_explicitly_support_phone_and_watch_simulators(self) -> None:
        platforms = {
            "Binario": ("iOS", "iphoneos", "iphoneos iphonesimulator"),
            "BinarioAppTests": ("iOS", "iphoneos", "iphoneos iphonesimulator"),
            "BinarioKitTests": ("iOS", "iphoneos", "iphoneos iphonesimulator"),
            "BinarioWatch": ("watchOS", "watchos", "watchos watchsimulator"),
            "BinarioWatchTests": ("watchOS", "watchos", "watchos watchsimulator"),
            "BinarioWatchWidgets": ("watchOS", "watchos", "watchos watchsimulator"),
        }
        for name, (platform, sdk, supported) in platforms.items():
            with self.subTest(target=name):
                target = self.targets[name]
                settings = target["settings"]["base"]
                self.assertEqual(platform, target["platform"])
                self.assertEqual(sdk, settings["SDKROOT"])
                self.assertEqual(supported, settings["SUPPORTED_PLATFORMS"])

    def test_app_uses_local_package_and_authoritative_catalog_without_copy(self) -> None:
        app = self.targets["Binario"]
        resources = [
            entry["path"] for entry in app["sources"]
            if isinstance(entry, dict) and entry.get("buildPhase") == "resources"
        ]

        self.assertEqual("BinarioKit", next(iter(self.project["packages"])))
        self.assertEqual("BinarioKit", next(dep["package"] for dep in app["dependencies"] if "package" in dep))
        self.assertIn("Resources", resources)
        self.assertIn("../app/src/main/assets/stations/curated_it_rail_stations.json", resources)
        self.assertFalse((IOS / "Resources/curated_it_rail_stations.json").exists())
        self.assertTrue((IOS / "Resources/binario_gtfs_schedule_v1.sqlite.zlib").is_file())
        self.assertTrue((IOS / "Resources/binario_gtfs_schedule_v1.metadata.json").is_file())

        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        self.assertEqual(5_239, catalog["stationCount"])
        self.assertFalse(catalog["operationalData"])

    def test_authoritative_catalog_matches_its_checked_in_manifest(self) -> None:
        payload = CATALOG.read_bytes()
        catalog = json.loads(payload)
        manifest = json.loads(CATALOG_MANIFEST.read_text(encoding="utf-8"))["catalogs"][0]

        self.assertEqual(catalog["stationCount"], manifest["stationCount"])
        self.assertEqual(catalog["sourceDatabaseSha256"], manifest["sourceDatabaseSha256"])
        self.assertEqual(catalog["operationalData"], manifest["operationalData"])
        self.assertEqual(hashlib.sha256(payload).hexdigest(), manifest["assetSha256"])

    def test_watch_is_modern_associated_versioned_and_embedded(self) -> None:
        app_dependencies = self.targets["Binario"]["dependencies"]
        watch = self.targets["BinarioWatch"]
        widget = self.targets["BinarioWatchWidgets"]
        settings = watch["settings"]["base"]
        global_settings = self.project["settings"]["base"]

        self.assertIn({"target": "BinarioWatch", "embed": True}, app_dependencies)
        self.assertEqual("application", watch["type"])
        self.assertEqual("watchOS", watch["platform"])
        for name in ("BinarioWatch", "BinarioWatchTests", "BinarioWatchWidgets"):
            self.assertEqual(name, self.targets[name]["settings"]["base"]["PRODUCT_NAME"])
        watch_test_settings = self.targets["BinarioWatchTests"]["settings"]["base"]
        self.assertEqual("$(BUILT_PRODUCTS_DIR)/BinarioWatch.app/BinarioWatch", watch_test_settings["TEST_HOST"])
        self.assertEqual("$(TEST_HOST)", watch_test_settings["BUNDLE_LOADER"])
        self.assertTrue(settings["INFOPLIST_KEY_WKApplication"])
        self.assertFalse(settings["INFOPLIST_KEY_WKWatchOnly"])
        ios_id = self.targets["Binario"]["settings"]["base"]["PRODUCT_BUNDLE_IDENTIFIER"]
        self.assertEqual(ios_id, settings["INFOPLIST_KEY_WKCompanionAppBundleIdentifier"])
        self.assertEqual(f"{ios_id}.watchkitapp", settings["PRODUCT_BUNDLE_IDENTIFIER"])
        self.assertEqual("AppIcon", settings["ASSETCATALOG_COMPILER_APPICON_NAME"])
        self.assertIn({"target": "BinarioWatchWidgets", "embed": True}, watch["dependencies"])
        # Embedded products are not installed on their own: a second top-level app in the
        # Release archive makes it a generic archive that App Store Connect cannot receive.
        for name in ("BinarioWatch", "BinarioWatchWidgets"):
            with self.subTest(target=name):
                self.assertIs(True, self.targets[name]["settings"]["base"]["SKIP_INSTALL"])
        self.assertNotIn("SKIP_INSTALL", self.targets["Binario"]["settings"]["base"])
        self.assertEqual("app-extension", widget["type"])
        widget_settings = widget["settings"]["base"]
        self.assertEqual(f"{ios_id}.watchkitapp.widgets", widget_settings["PRODUCT_BUNDLE_IDENTIFIER"])
        self.assertFalse(widget_settings["GENERATE_INFOPLIST_FILE"])
        self.assertEqual("../apple-watch/BinarioWatchWidgets/Info.plist", widget_settings["INFOPLIST_FILE"])
        self.assertEqual("0.1.0", global_settings["MARKETING_VERSION"])
        self.assertEqual(1, global_settings["CURRENT_PROJECT_VERSION"])

    def test_iphone_and_watch_app_icons_are_opaque_1024_catalog_entries(self) -> None:
        # App Store Connect refuses an upload whose iPhone or embedded Watch app lacks a
        # 1024 pt icon, and refuses icons that carry an alpha channel.
        self.assertEqual("AppIcon", self.targets["Binario"]["settings"]["base"]["ASSETCATALOG_COMPILER_APPICON_NAME"])
        watch_sources = [entry["path"] for entry in self.targets["BinarioWatch"]["sources"] if isinstance(entry, dict)]
        self.assertIn("../apple-watch/BinarioWatch", watch_sources)
        for catalog, platform in (
            (IOS / "Resources/Assets.xcassets", "ios"),
            (ROOT / "apple-watch/BinarioWatch/Assets.xcassets", "watchos"),
        ):
            with self.subTest(platform=platform):
                self.assertEqual({"info": {"author": "xcode", "version": 1}}, json.loads((catalog / "Contents.json").read_text(encoding="utf-8")))
                icon_set = catalog / "AppIcon.appiconset"
                contents = json.loads((icon_set / "Contents.json").read_text(encoding="utf-8"))
                self.assertEqual(
                    [{"filename": "AppIcon-1024.png", "idiom": "universal", "platform": platform, "size": "1024x1024"}],
                    contents["images"],
                )
                header = (icon_set / "AppIcon-1024.png").read_bytes()[:26]
                self.assertTrue(header.startswith(b"\x89PNG\r\n\x1a\n"))
                width, height, bit_depth, colour_type = struct.unpack(">IIBB", header[16:26])
                self.assertEqual((1024, 1024, 8, 2), (width, height, bit_depth, colour_type), "expected an 8-bit RGB PNG without alpha")

    def test_localizations_match_canonical_keys_usage_and_placeholders(self) -> None:
        key_source = (IOS / "BinarioKit/Sources/BinarioKit/LocalizationKeys.swift").read_text(encoding="utf-8")
        canonical = set(SWIFT_REQUIRED_KEY.findall(key_source))
        self.assertTrue(canonical)

        localized: dict[str, dict[str, str]] = {}
        for language in sorted(LANGUAGES):
            values = read_strings(IOS / "Resources" / f"{language}.lproj" / "Localizable.strings")
            self.assertEqual(canonical, set(values), language)
            self.assertTrue(all(value.strip() for value in values.values()))
            localized[language] = values

        italian = localized["it"]
        for language, values in localized.items():
            for key in canonical:
                self.assertEqual(
                    PLACEHOLDER.findall(italian[key]),
                    PLACEHOLDER.findall(values[key]),
                    f"{language}:{key}",
                )

        used: set[str] = set()
        for path in (IOS / "BinarioApp").glob("*.swift"):
            used.update(SWIFT_REQUIRED_KEY.findall(path.read_text(encoding="utf-8")))
        self.assertTrue(used <= canonical, sorted(used - canonical))
        intentionally_dynamic = {
            "settings.about.version_unavailable",
            "watch.status.disabled", "watch.status.activating", "watch.status.pending",
            "watch.status.context_updated", "watch.status.cleared",
            "watch.status.unsupported", "watch.status.not_paired", "watch.status.failed",
        }
        self.assertEqual(set(), canonical - used - intentionally_dynamic)

    def test_privacy_manifests_match_each_executable_userdefaults_scope(self) -> None:
        ios_privacy = privacy_manifest(IOS / "Resources/PrivacyInfo.xcprivacy")
        self.assertFalse(ios_privacy["NSPrivacyTracking"])
        self.assertEqual([], ios_privacy["NSPrivacyTrackingDomains"])
        self.assertEqual([], ios_privacy["NSPrivacyCollectedDataTypes"])
        self.assertEqual([{
            "NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryUserDefaults",
            "NSPrivacyAccessedAPITypeReasons": ["CA92.1"],
        }], ios_privacy["NSPrivacyAccessedAPITypes"])

        for relative in [
            "apple-watch/BinarioWatch/PrivacyInfo.xcprivacy",
            "apple-watch/BinarioWatchWidgets/PrivacyInfo.xcprivacy",
        ]:
            manifest = privacy_manifest(ROOT / relative)
            self.assertEqual([{
                "NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryUserDefaults",
                "NSPrivacyAccessedAPITypeReasons": ["1C8F.1"],
            }], manifest["NSPrivacyAccessedAPITypes"])

    def test_no_forbidden_capability_or_network_declaration_is_present(self) -> None:
        text = "\n".join(
            path.read_text(encoding="utf-8")
            for path in IOS.rglob("*")
            if path.is_file() and ".build" not in path.parts and path.suffix in {".swift", ".yml", ".strings", ".md"}
        )
        forbidden = [
            "NSLocationWhenInUseUsageDescription", "NSMicrophoneUsageDescription",
            "NSAppTransportSecurity", "http://", "CloudKit", "Firebase",
            "Mixpanel", "Amplitude", "BGTaskSchedulerPermittedIdentifiers",
        ]
        for value in forbidden:
            self.assertNotIn(value, text)
        self.assertNotRegex(text, re.compile(r"AKIA[0-9A-Z]{16}"))

    def test_bundled_gtfs_fallback_bounded_snapshot_and_explicit_clear_are_wired(self) -> None:
        repository = (IOS / "BinarioKit/Sources/BinarioKit/JourneyRepository.swift").read_text(encoding="utf-8")
        gtfs = (IOS / "BinarioKit/Sources/BinarioKit/SQLiteGTFSJourneyRepository.swift").read_text(encoding="utf-8")
        installer = (IOS / "BinarioKit/Sources/BinarioKit/BundledGTFSScheduleInstaller.swift").read_text(encoding="utf-8")
        snapshot = (IOS / "BinarioKit/Sources/BinarioKit/WearSnapshotV1.swift").read_text(encoding="utf-8")
        model = (IOS / "BinarioApp/AppModel.swift").read_text(encoding="utf-8")
        planner = (IOS / "BinarioApp/PlannerView.swift").read_text(encoding="utf-8")
        model_tests = (IOS / "BinarioAppTests/BinarioAppTests.swift").read_text(encoding="utf-8")
        receiver = (ROOT / "apple-watch/BinarioWatch/PhoneConnectivity.swift").read_text(encoding="utf-8")
        builder = (ROOT / "tools/build_ios_gtfs_schedule.py").read_text(encoding="utf-8")
        verifier = (ROOT / "tools/verify_ios_gtfs_schedule.py").read_text(encoding="utf-8")
        source_policy = IOS / "Resources/binario_gtfs_source_policy_v1.json"

        self.assertIn(".unavailable(.noAuthorizedProvider)", repository)
        self.assertIn("case noJourney", repository)
        self.assertIn("case providerInvalid", repository)
        self.assertIn("SQLiteGTFSJourneyRepository", model)
        self.assertIn("BundledGTFSScheduleInstaller", model)
        self.assertIn('metadata["input_fingerprint_sha256"]', gtfs)
        self.assertIn("maximumDatabaseBytes", installer)
        self.assertIn("NSLock", installer)
        self.assertIn("replaceItemAt", installer)
        self.assertIn("isExcludedFromBackup = true", installer)
        self.assertIn("maximumEncodedBytes = 8_192", snapshot)
        self.assertIn("maximumServices = 2", snapshot)
        self.assertIn("provenanceProducer == .iosApp", snapshot)
        self.assertIn("WearSnapshotV1.unavailable", model)
        self.assertIn("WearSnapshotV1(", model)
        self.assertIn("prefix(WearSnapshotV1.maximumServices)", model)
        self.assertIn("private var searchRequestID = 0", model)
        self.assertIn("guard requestID == searchRequestID else { return }", model)
        self.assertIn("testOlderSearchResponseCannotReplaceNewerResult", model_tests)
        self.assertIn("func invalidateSearchResult()", model)
        self.assertIn("model.invalidateSearchResult()", planner)
        self.assertIn("private var searchRequestID = 0", planner)
        self.assertIn("guard requestID == searchRequestID else { return }", planner)
        self.assertIn("revokeWatchSnapshot()", model.split("func invalidateSearchResult()", 1)[1].split("func setWatchSharingEnabled", 1)[0])
        self.assertIn("testInputInvalidationDiscardsPendingSearchResponse", model_tests)
        self.assertIn("testInputInvalidationClearsPreviouslyPublishedWatchSnapshot", model_tests)
        self.assertIn("testNoJourneyAndInvalidProviderHaveDistinctState", model_tests)
        self.assertIn('case .noJourney: "result.unavailable.title"', planner)
        self.assertIn('case .scheduleExpired: "result.expired.title"', planner)
        # The expiry is checked for the whole index before stations are resolved, so a query
        # after the last service day is "expired" even for stations the index never served.
        load_body = gtfs.split("private static func load(", 1)[1]
        self.assertLess(load_body.index("indexEnded(before: query.departure"), load_body.index("try resolve(query.origin"))
        self.assertIn("WHERE EXISTS (SELECT 1 FROM trip AS t WHERE t.pack_id = p.id)", gtfs)
        self.assertIn("testQueriesAfterTheIndexEndsReportExpiryEvenForStationsItNeverServed", (IOS / "BinarioKit/Tests/BinarioKitTests/SQLiteGTFSJourneyRepositoryTests.swift").read_text(encoding="utf-8"))
        self.assertIn("var firstPackExpiry: Date?", model)
        self.assertIn('LabeledContent("settings.data.valid_until")', (IOS / "BinarioApp/SettingsView.swift").read_text(encoding="utf-8"))
        self.assertTrue(source_policy.is_file())
        self.assertIn("source_policy_path", builder)
        self.assertIn("source_policy_path", verifier)
        self.assertIn("reviewed GTFS source policy is absent from the input fingerprint", verifier)
        self.assertIn("geographically_verified_parent_identity", builder)
        self.assertIn("MAX_PARENT_INHERITANCE_DISTANCE_KM", builder)
        self.assertIn("CREATE TEMP TABLE ios_gtfs_staged_stop_time", builder)
        self.assertIn("MAX_RETAINED_STOP_TIMES", builder)
        self.assertIn("invalid IANA timezone", builder)
        self.assertIn("stop_times regress along a trip", verifier)
        self.assertIn("invalid agency IANA timezone", verifier)
        self.assertIn('contextKey = "binario_snapshot_json"', snapshot)
        self.assertIn('clearContextKey = "binario_snapshot_clear"', snapshot)
        self.assertIn('clearKey = "binario_snapshot_clear"', receiver)
        self.assertIn("store.clear()", receiver)

    def test_watch_receives_only_trains_still_to_come_from_a_planner_that_follows_now(self) -> None:
        model = (IOS / "BinarioApp/AppModel.swift").read_text(encoding="utf-8")
        planner = (IOS / "BinarioApp/PlannerView.swift").read_text(encoding="utf-8")
        model_tests = (IOS / "BinarioAppTests/BinarioAppTests.swift").read_text(encoding="utf-8")
        publish = model.split("private func publishAvailableSnapshot(", 1)[1].split("static func isCurrent(", 1)[0]
        self.assertIn("Self.upcomingDepartures(in: journey, now: nowDate).prefix(WearSnapshotV1.maximumServices)", publish)
        self.assertIn("Self.watchExpiry(for: journey, departures: departures, now: nowDate)", publish)
        self.assertNotIn("journey.services.prefix(", model)
        self.assertIn("offset >= -5 * 60 && offset <= 15 * 60", model)
        # Only the user's edits invalidate a result: the planner moves its own time to now.
        self.assertNotIn(".onChange(of: departure)", planner)
        self.assertIn("if !departureEdited { departure = Date() }", planner)
        self.assertIn("if phase == .active { followCurrentTime() }", planner)
        self.assertIn("testDepartedTrainsAreDroppedAndTheFollowingOnesListed", model_tests)
        self.assertIn("testOnlyTrainsStillToComeArePublishedToTheWatch", model_tests)

    def test_planner_picks_times_in_the_timetable_zone_and_says_so(self) -> None:
        model = (IOS / "BinarioApp/AppModel.swift").read_text(encoding="utf-8")
        planner = (IOS / "BinarioApp/PlannerView.swift").read_text(encoding="utf-8")
        self.assertIn('static let timetableTimeZone = TimeZone(identifier: "Europe/Rome")', model)
        self.assertIn(".environment(\\.timeZone, AppModel.timetableTimeZone)", planner)
        self.assertIn('Text("planner.departure.italian_time")', planner)
        self.assertIn("AppModel.needsTimetableZoneCaption(at: departure)", planner)

    def test_watch_route_label_and_refresh_request_match_the_watch(self) -> None:
        snapshot = (IOS / "BinarioKit/Sources/BinarioKit/WearSnapshotV1.swift").read_text(encoding="utf-8")
        model = (IOS / "BinarioApp/AppModel.swift").read_text(encoding="utf-8")
        adapter = (IOS / "BinarioApp/WCSessionAdapter.swift").read_text(encoding="utf-8")
        receiver = (ROOT / "apple-watch/BinarioWatch/PhoneConnectivity.swift").read_text(encoding="utf-8")
        kit_tests = (IOS / "BinarioKit/Tests/BinarioKitTests/WearSnapshotV1Tests.swift").read_text(encoding="utf-8")
        # The route title is the station names; the identifier only tells namesakes apart.
        self.assertIn('let suffix = disambiguate ? identifierSeparator + normalizedRouteId : ""', snapshot)
        self.assertIn("catalog.hasNamesake(origin) || catalog.hasNamesake(destination)", model)
        self.assertNotIn("WatchRouteLabelFormatter.format(origin: query.origin, destination: query.destination)", model)
        # A Watch installed or paired after the search still gets the held snapshot.
        self.assertIn("nonisolated func sessionWatchStateDidChange(_ session: WCSession)", adapter)
        self.assertIn("public mutating func watchStateDidChange() -> WatchSyncDirective", snapshot)
        self.assertIn("republishLatestSearch()", model.split("func setWatchSharingEnabled", 1)[1].split("func save(query:", 1)[0])
        # "Aggiorna" on the Watch: same message key on both sides, acknowledged at once.
        self.assertIn('public static let messageKey = "binario_refresh_request"', snapshot)
        self.assertIn('static let requestKey = "binario_refresh_request"', receiver)
        self.assertIn("didReceiveMessage message: [String: Any],", adapter)
        self.assertIn("replyHandler([WatchRefreshRequest.messageKey: isRefreshRequest])", adapter)
        self.assertIn("Task { await self.refreshWatchSnapshot() }", model)
        self.assertIn("testWatchStateChangeDeliversTheOperationHeldForAMissingWatch", kit_tests)
        self.assertIn("testRouteFormatterShowsNamesOnlyAndTheRouteIdOnlyToDisambiguate", kit_tests)

    def test_saved_routes_open_the_planner_and_search_from_now(self) -> None:
        saved = (IOS / "BinarioApp/SavedRoutesView.swift").read_text(encoding="utf-8")
        root = (IOS / "BinarioApp/RootView.swift").read_text(encoding="utf-8")
        planner = (IOS / "BinarioApp/PlannerView.swift").read_text(encoding="utf-8")
        self.assertIn("model.openPlanner(for: route)", saved)
        self.assertIn('.accessibilityHint(Text("accessibility.open_saved_route"))', saved)
        self.assertIn("TabView(selection: $model.selectedTab)", root)
        for tab in ("planner", "saved", "settings"):
            self.assertIn(f".tag(AppModel.Tab.{tab})", root)
        self.assertIn(".task(id: model.plannerRequest?.id) { applyPlannerRequest() }", planner)
        apply = planner.split("private func applyPlannerRequest()", 1)[1].split("private func ", 1)[0]
        self.assertIn("departureEdited = false", apply)
        self.assertIn("search()", apply)

    def test_settings_data_rows_describe_the_loaded_catalog_and_index(self) -> None:
        settings = (IOS / "BinarioApp/SettingsView.swift").read_text(encoding="utf-8")
        # A literal catalogue name and a literal feed count drift at every data refresh.
        self.assertNotIn("stazioni-ferroviarie-curato-v4", settings)
        self.assertIn("case let .available(catalog): catalog.sourceDatabaseName", settings)
        self.assertIn('format: String(localized: "settings.data.provider_programmed")', settings)
        self.assertIn("model.includedPacks.count", settings)
        for language in sorted(LANGUAGES):
            with self.subTest(language=language):
                values = read_strings(IOS / "Resources" / f"{language}.lproj" / "Localizable.strings")
                self.assertEqual(["%lld"], PLACEHOLDER.findall(values["settings.data.provider_programmed"]))
                self.assertNotRegex(values["settings.data.provider_programmed"], r"\d")
                self.assertNotIn("app.name", values)
                self.assertNotIn("planner.route_accessibility", values)

    def test_planner_marks_stations_without_timetable_and_names_the_included_feeds(self) -> None:
        gtfs = (IOS / "BinarioKit/Sources/BinarioKit/SQLiteGTFSJourneyRepository.swift").read_text(encoding="utf-8")
        model = (IOS / "BinarioApp/AppModel.swift").read_text(encoding="utf-8")
        picker = (IOS / "BinarioApp/StationPickerField.swift").read_text(encoding="utf-8")
        planner = (IOS / "BinarioApp/PlannerView.swift").read_text(encoding="utf-8")
        model_tests = (IOS / "BinarioAppTests/BinarioAppTests.swift").read_text(encoding="utf-8")

        # The planner offers the whole catalogue while the index serves a fraction of it: the
        # covered set comes from the index, uncovered stations are flagged and sorted last.
        self.assertIn("public func coveredCatalogStationIDs() throws -> Set<String>", gtfs)
        self.assertIn("let coveredStationIDs = try? repository.coveredCatalogStationIDs()", model)
        self.assertIn("func hasTimetable(_ station: Station) -> Bool?", model)
        self.assertIn("stations.filter { coveredStationIDs.contains($0.id) }", model)
        self.assertIn("model.hasTimetable(station) == false", picker)
        self.assertIn('Text("planner.station.no_timetable")', picker)
        self.assertIn('String(localized: "planner.sources.footer")', planner)
        self.assertIn("model.includedFeedAttributions", planner)
        self.assertIn("testStationsWithoutTimetableAreMarkedAndListedAfterCoveredOnes", model_tests)
        self.assertIn("testCoveredCatalogStationIDsListEveryBoundStopOnce", (IOS / "BinarioKit/Tests/BinarioKitTests/SQLiteGTFSJourneyRepositoryTests.swift").read_text(encoding="utf-8"))
        # The intro no longer implies national coverage.
        italian = read_strings(IOS / "Resources/it.lproj/Localizable.strings")
        english = read_strings(IOS / "Resources/en.lproj/Localizable.strings")
        self.assertIn("Trenitalia e Italo non sono inclusi", italian["planner.intro.body"])
        self.assertIn("Trenitalia and Italo are not included", english["planner.intro.body"])

    def test_settings_source_list_is_backed_by_the_reviewed_policy_licences(self) -> None:
        policy = json.loads((IOS / "Resources/binario_gtfs_source_policy_v1.json").read_text(encoding="utf-8"))
        metadata = json.loads((IOS / "Resources/binario_gtfs_schedule_v1.metadata.json").read_text(encoding="utf-8"))
        settings = (IOS / "BinarioApp/SettingsView.swift").read_text(encoding="utf-8")
        model = (IOS / "BinarioApp/AppModel.swift").read_text(encoding="utf-8")
        gtfs = (IOS / "BinarioKit/Sources/BinarioKit/SQLiteGTFSJourneyRepository.swift").read_text(encoding="utf-8")
        verifier = (ROOT / "tools/verify_ios_gtfs_schedule.py").read_text(encoding="utf-8")

        # Every pack of the index has a policy entry with a licence key (null until confirmed).
        self.assertEqual(set(metadata["packRowCounts"]), {source["sourceId"] for source in policy["sources"]})
        for source in policy["sources"]:
            with self.subTest(source=source["sourceId"]):
                self.assertEqual({"sourceId", "sourceUrl", "accessMode", "licence"}, set(source))
                self.assertTrue(source["licence"] is None or source["licence"].strip())
        self.assertEqual("CC BY 4.0", next(source["licence"] for source in policy["sources"] if source["sourceId"] == "oebb-gtfs"))
        self.assertIn("verify_source_policy(source_policy_path, pack_source_ids(raw_path))", verifier)
        # The Settings list reads attribution and validity from the index and licence and URL
        # from the policy bundled with it.
        self.assertIn("public func sourcePacks() throws -> [GTFSSourcePack]", gtfs)
        self.assertIn("repository.sourcePacks()", model)
        self.assertIn('bundle.url(forResource: "binario_gtfs_source_policy_v1", withExtension: "json")', model)
        self.assertIn('Section("settings.sources.title")', settings)
        self.assertIn("ForEach(model.schedulePacks, id: \\.sourceID)", settings)
        self.assertIn("model.sourcePolicy?.source(id: pack.sourceID)", settings)
        self.assertIn("settings.sources.licence", settings)

    def test_bundled_schedule_fast_path_validates_the_cache_without_rehashing_it(self) -> None:
        installer = (IOS / "BinarioKit/Sources/BinarioKit/BundledGTFSScheduleInstaller.swift").read_text(encoding="utf-8")
        tests = (IOS / "BinarioKit/Tests/BinarioKitTests/SQLiteGTFSJourneyRepositoryTests.swift").read_text(encoding="utf-8")
        fast_path = installer.split("if let cachedSize", 1)[1].split("return destination", 1)[0]
        # Every launch used to run pure-Swift SHA-256 over the whole cached database while the
        # planner stayed disabled; size, digest-named file and SQLite validation are enough.
        self.assertNotIn("hashFile", installer)
        self.assertIn("cachedSize == metadata.database.sizeBytes", fast_path)
        self.assertIn("SQLiteGTFSJourneyRepository.validatesDatabase(", fast_path)
        self.assertIn("inputFingerprint: metadata.inputs.fingerprintSha256", fast_path)
        self.assertIn("sha256Hex(database) == metadata.database.sha256", installer)
        self.assertIn("testBundledInstallerFastPathDoesNotTrustASameSizeCorruptCache", tests)

    def test_binario_2_colour_tokens_are_catalogue_backed_and_tint_the_apps(self) -> None:
        # design/BINARIO_2_DESIGN_SYSTEM.md: one definition per target, light and dark on iPhone.
        catalog = IOS / "Resources/Assets.xcassets"
        expected = {
            "AccentColor": {"any": "#2E7D32", "dark": "#9BDD9E"},
            "OnAccentColor": {"any": "#FFFFFF", "dark": "#0D1710"},
            "BrandGreen": {"any": "#388E3C", "dark": "#9BDD9E"},
            "StatusError": {"any": "#B3261E", "dark": "#FFB4AB"},
        }
        for name, values in expected.items():
            with self.subTest(colour=name):
                self.assertEqual(values, colorset_values(catalog, name))
        # The accent tints text-sized controls (tabs, links, toggles): AA on both system backgrounds,
        # and the paired label colour stays AA on the prominent accent fill.
        for appearance, background in (("any", "#FFFFFF"), ("dark", "#000000")):
            with self.subTest(appearance=appearance):
                accent = expected["AccentColor"][appearance]
                self.assertGreaterEqual(contrast(accent, background), 4.5)
                self.assertGreaterEqual(contrast(expected["OnAccentColor"][appearance], accent), 4.5)
                self.assertGreaterEqual(contrast(expected["StatusError"][appearance], background), 4.5)
                # The reference green is decorative only: 3:1 for graphics, never small white text.
                self.assertGreaterEqual(contrast(expected["BrandGreen"][appearance], background), 3.0)
        self.assertLess(contrast("#FFFFFF", expected["BrandGreen"]["any"]), 4.5)

        for target in ("Binario", "BinarioWatch"):
            with self.subTest(target=target):
                self.assertEqual("AccentColor", self.targets[target]["settings"]["base"]["ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME"])

        sources = {path.name: path.read_text(encoding="utf-8") for path in (IOS / "BinarioApp").glob("*.swift")}
        for name, source in sources.items():
            with self.subTest(source=name):
                self.assertNotRegex(source, r"(?:UI)?Color\(\s*red:")
                self.assertNotRegex(source, r"\.foregroundStyle\(\.(?:red|green|blue|orange|yellow)\)")
                for colour in re.findall(r'Color\("([A-Za-z]+)"\)', source):
                    self.assertTrue((catalog / f"{colour}.colorset").is_dir(), colour)
        planner = sources["PlannerView.swift"]
        self.assertIn("BinarioPalette.onAccent", planner)
        self.assertIn(".foregroundStyle(BinarioPalette.statusError)", planner)

    def test_generated_project_is_not_checked_in(self) -> None:
        self.assertFalse((IOS / "Binario.xcodeproj").exists())


if __name__ == "__main__":
    unittest.main()
