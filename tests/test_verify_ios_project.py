"""Static invariants for the native Binario iOS XcodeGen project."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import plistlib
import re
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
IOS = ROOT / "ios"
CATALOG = ROOT / "app/src/main/assets/stations/curated_it_rail_stations.json"
CATALOG_MANIFEST = ROOT / "app/src/main/assets/stations/manifest.json"
LANGUAGES = {"it", "en", "de", "fr", "es"}
STRINGS_ENTRY = re.compile(r'^"((?:[^"\\]|\\.)+)"\s*=\s*"((?:[^"\\]|\\.)*)";$')
SWIFT_REQUIRED_KEY = re.compile(r'"((?:action|app|tab|planner|catalog|schedule|result|saved|settings|watch|accessibility)\.[a-z0-9_.]+)"')
PLACEHOLDER = re.compile(r'%(?:\d+\$)?[@diufFeEgGxXoscpaA]')


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
        self.assertEqual("1", settings["TARGETED_DEVICE_FAMILY"])
        self.assertEqual(sorted(LANGUAGES), sorted(settings["INFOPLIST_KEY_CFBundleLocalizations"]))

    def test_iphone_test_bundles_have_unique_nonempty_product_names(self) -> None:
        names = [self.targets[target]["settings"]["base"]["PRODUCT_NAME"]
                 for target in ("BinarioAppTests", "BinarioKitTests")]
        self.assertEqual(["BinarioAppTests", "BinarioKitTests"], names)
        self.assertEqual(len(names), len(set(names)))

    def test_iphone_debug_targets_match_the_active_simulator_architecture(self) -> None:
        for name in ("Binario", "BinarioAppTests", "BinarioKitTests"):
            with self.subTest(target=name):
                settings = self.targets[name]["settings"]
                self.assertTrue(settings["configs"]["debug"]["ONLY_ACTIVE_ARCH"])
                self.assertNotIn("ONLY_ACTIVE_ARCH", settings["base"])
                self.assertNotIn("release", settings["configs"])
        app_settings = self.targets["Binario"]["settings"]
        self.assertTrue(app_settings["configs"]["debug"]["ENABLE_TESTABILITY"])
        self.assertNotIn("ENABLE_TESTABILITY", app_settings["base"])

    def test_watch_sharing_binding_uses_explicit_actor_isolated_setter_closure(self) -> None:
        settings = (IOS / "BinarioApp/SettingsView.swift").read_text(encoding="utf-8")
        self.assertIn("set: { enabled in model.setWatchSharingEnabled(enabled) }", settings)
        self.assertNotIn("set: model.setWatchSharingEnabled", settings)

    def test_watch_connectivity_activation_mapping_has_explicit_returns(self) -> None:
        adapter = (IOS / "BinarioApp/WCSessionAdapter.swift").read_text(encoding="utf-8")
        for state in ("notActivated", "inactive", "activated"):
            self.assertIn(f"case .{state}: return .{state}", adapter)
        self.assertIn("@unknown default: return .failed", adapter)

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
        self.assertIn({"target": "BinarioWatchWidgets", "embed": True}, watch["dependencies"])
        self.assertEqual("app-extension", widget["type"])
        widget_settings = widget["settings"]["base"]
        self.assertEqual(f"{ios_id}.watchkitapp.widgets", widget_settings["PRODUCT_BUNDLE_IDENTIFIER"])
        self.assertFalse(widget_settings["GENERATE_INFOPLIST_FILE"])
        self.assertEqual("../apple-watch/BinarioWatchWidgets/Info.plist", widget_settings["INFOPLIST_FILE"])
        self.assertEqual("0.1.0", global_settings["MARKETING_VERSION"])
        self.assertEqual(1, global_settings["CURRENT_PROJECT_VERSION"])

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
            "app.name",
            "planner.route_accessibility",
            "settings.about.version_unavailable",
            "watch.status.disabled", "watch.status.activating", "watch.status.pending",
            "watch.status.context_updated", "watch.status.cleared",
            "watch.status.unsupported", "watch.status.failed",
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
        self.assertIn("reason == .noJourney", planner)
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

    def test_generated_project_is_not_checked_in(self) -> None:
        self.assertFalse((IOS / "Binario.xcodeproj").exists())


if __name__ == "__main__":
    unittest.main()
