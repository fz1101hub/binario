"""Static invariants for the native Binario iOS XcodeGen project."""

from __future__ import annotations

import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import plistlib
import re
import sqlite3
import struct
import tempfile
import unittest
import xml.etree.ElementTree as ElementTree
import zlib

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
SWIFT_REQUIRED_KEY = re.compile(r'"((?:action|app|tab|planner|departures|catalog|schedule|result|saved|settings|watch|accessibility)\.[a-z0-9_.]+)"')
PLACEHOLDER = re.compile(r'%(?:\d+\$)?(?:hh|h|ll|l)?[@diufFeEgGxXoscpaA]')
# CLDR plural categories of each .lproj (docs/l10n/LOCALES.md and the batch rules).
PLURAL_CATEGORIES = {
    "it": {"one", "many", "other"}, "fr": {"one", "many", "other"}, "es": {"one", "many", "other"},
    "pt-BR": {"one", "many", "other"},
    "en": {"one", "other"}, "de": {"one", "other"}, "hi": {"one", "other"}, "tr": {"one", "other"},
    "ar": {"zero", "one", "two", "few", "many", "other"}, "ru": {"one", "few", "many", "other"},
    "id": {"other"}, "ja": {"other"}, "ko": {"other"}, "my": {"other"}, "th": {"other"}, "vi": {"other"},
    "zh-Hans": {"other"},
}
ANDROID_STATISTICS = ROOT / "app/src/main/assets/punctuality/stats_v1.json.gz"
IOS_STATISTICS = IOS / "Resources/binario_punctuality_stats_v1.json.gz"
INVISIBLE = re.compile("[\ufeff\u200b\u200c\u200d\u200e\u200f\u2060\u00ad]")
# The Android resource folder of each .lproj, for the texts shared with the Android app.
ANDROID_VALUES = {
    "it": "values", "en": "values-en", "de": "values-de", "es": "values-es", "fr": "values-fr",
    "ar": "values-ar", "hi": "values-hi", "id": "values-in", "ja": "values-ja", "ko": "values-ko",
    "my": "values-my", "pt-BR": "values-pt", "ru": "values-ru", "th": "values-th", "tr": "values-tr",
    "vi": "values-vi", "zh-Hans": "values-zh-rCN",
}


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
                # Positional placeholders may come in another order ("%2$lld 回中 %1$lld 回").
                self.assertEqual(
                    sorted(PLACEHOLDER.findall(italian[key])),
                    sorted(PLACEHOLDER.findall(values[key])),
                    f"{language}:{key}",
                )
                placeholders = PLACEHOLDER.findall(values[key])
                if len(placeholders) > 1 and any("$" not in placeholder for placeholder in placeholders):
                    self.fail(f"{language}:{key}: several placeholders must all be positional")
                self.assertIsNone(INVISIBLE.search(values[key]), f"{language}:{key}")
                words = values[key].split()
                for first, second in zip(words, words[1:]):
                    if first.isalpha():
                        self.assertNotEqual(first.lower(), second.lower(), f"{language}:{key}: repeated word")

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

    def test_iphone_texts_never_name_another_platform_or_a_widget(self) -> None:
        """App Review and the companion's rule (EXPO_PORT_PLAN R24, audit expo-6): no iPhone text names Android,
        Wear OS, Google Play or a widget the app does not have, in any of the 17 languages."""
        forbidden = re.compile(
            r"android|wear\s*os|google\s*play|widget|ウィジェット|위젯|小组件|小部件|виджет|วิดเจ็ต|विजेट|ودجت|ويدجت",
            re.IGNORECASE,
        )
        for language in sorted(LANGUAGES):
            folder = IOS / "Resources" / f"{language}.lproj"
            texts = list(read_strings(folder / "Localizable.strings").values())
            with (folder / "Localizable.stringsdict").open("rb") as source:
                for entry in plistlib.load(source).values():
                    texts.extend(form for category, form in entry["count"].items() if not category.startswith("NSString"))
            for text in texts:
                self.assertIsNone(forbidden.search(text), f"{language}: {text}")

    def test_agenda_reminders_are_local_notifications_without_a_push_entitlement(self) -> None:
        """EXPO_PORT_PLAN R25 and F30: the Agenda's reminders are local requests behind a protocol; no target
        declares the push entitlement or a background mode, nothing registers for remote notifications, and the
        permission is asked only when a journey with a reminder is saved, never by a reconciliation."""
        paths = [
            path for base in (IOS, ROOT / "apple-watch") for path in base.rglob("*")
            if path.is_file() and ".build" not in path.parts
            and path.suffix in {".swift", ".yml", ".plist", ".entitlements", ".xcprivacy"}
        ]
        for path in paths:
            text = path.read_text(encoding="utf-8")
            for forbidden in ("aps-environment", "registerForRemoteNotifications", "remote-notification", "UIBackgroundModes"):
                self.assertNotIn(forbidden, text, path)
        self.assertNotIn("entitlements", self.targets["Binario"])
        for name, target in self.targets.items():
            if "entitlements" in target:
                with (IOS / target["entitlements"]["path"]).open("rb") as source:
                    self.assertEqual({"com.apple.security.application-groups"}, set(plistlib.load(source)), name)

        adapter = (IOS / "BinarioApp/AgendaReminders.swift").read_text(encoding="utf-8")
        self.assertIn("final class SystemAgendaNotificationCenter: PlannedJourneyNotificationCenter", adapter)
        self.assertIn("UNTimeIntervalNotificationTrigger", adapter)
        # The words are the stored Italian time and the validated station names, never the free label.
        self.assertIn("plan.departureTime.hhmm, plan.origin, plan.destination", adapter)
        self.assertNotIn(".label", adapter)
        sources = [
            path for base in (IOS / "BinarioApp", IOS / "BinarioKit/Sources")
            for path in base.rglob("*.swift")
        ]
        asking = sorted(path.name for path in sources if "requestAuthorization(" in path.read_text(encoding="utf-8"))
        self.assertEqual(["AgendaReminders.swift"], asking)
        scheduler = (IOS / "BinarioKit/Sources/BinarioKit/PlannedJourneyReminders.swift").read_text(encoding="utf-8")
        self.assertEqual(1, scheduler.count("center.requestPermission()"))
        self.assertLess(scheduler.index("func scheduleAfterSaving"), scheduler.index("center.requestPermission()"))
        self.assertLess(scheduler.index("center.requestPermission()"), scheduler.index("func cancel(journeyID"))
        root_view = (IOS / "BinarioApp/RootView.swift").read_text(encoding="utf-8")
        self.assertIn("if phase == .active {\n                Task { await model.refreshReminders() }", root_view)
        model = (IOS / "BinarioApp/AppModel.swift").read_text(encoding="utf-8")
        for action in ("func deleteJourney(id: String)", "func completeJourney(id: String"):
            body = model.split(action, 1)[1].split("\n    }\n", 1)[0]
            self.assertLess(body.index("reminderScheduler?.cancel(journeyID: id)"), body.index("plannedJourneyStore."), action)
        self.assertIn("PlannedJourneyReminderTests", (IOS / "BinarioKit/Tests/BinarioKitTests/PlannedJourneyReminderTests.swift").read_text(encoding="utf-8"))

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

    def test_sources_use_no_required_reason_api_the_privacy_manifests_do_not_declare(self) -> None:
        """Apple refuses an upload whose binary uses a required-reason API missing from the privacy manifest
        (ITMS-91053). The manifests declare UserDefaults only, so the shipped Swift sources must not touch the
        other categories: system boot time, file timestamps, disk space, active keyboards."""
        categories = {
            "NSPrivacyAccessedAPICategorySystemBootTime": r"\bsystemUptime\b|\bmach_absolute_time\b",
            "NSPrivacyAccessedAPICategoryFileTimestamp": (
                r"\bcreationDate\b|\bmodificationDate\b|\bcontentModificationDate(Key)?\b|\bcreationDateKey\b"
                r"|\bfileModificationDate\b|\bgetattrlist(bulk)?\b|\b[fl]?stat\("
            ),
            "NSPrivacyAccessedAPICategoryDiskSpace": (
                r"\bvolumeAvailableCapacity\w*|\bvolumeTotalCapacity\w*|\bsystemFreeSize\b|\bsystemSize\b"
                r"|\bstatv?fs\("
            ),
            "NSPrivacyAccessedAPICategoryActiveKeyboards": r"\bactiveInputModes\b",
        }
        sources = [
            path for base in (IOS / "BinarioKit/Sources", IOS / "BinarioApp", ROOT / "apple-watch")
            for path in base.rglob("*.swift")
            if ".build" not in path.parts and "Tests" not in path.name and not any(part.endswith("Tests") for part in path.parts)
        ]
        self.assertGreater(len(sources), 40)
        declared = {
            entry["NSPrivacyAccessedAPIType"]
            for relative in ("ios/Resources/PrivacyInfo.xcprivacy", "apple-watch/BinarioWatch/PrivacyInfo.xcprivacy",
                             "apple-watch/BinarioWatchWidgets/PrivacyInfo.xcprivacy")
            for entry in privacy_manifest(ROOT / relative)["NSPrivacyAccessedAPITypes"]
        }
        problems = []
        for path in sources:
            # Comments may name an API to explain why it is not used.
            code = "\n".join(line.split("//", 1)[0] for line in path.read_text(encoding="utf-8").splitlines())
            for category, pattern in categories.items():
                if category not in declared and re.search(pattern, code):
                    problems.append(f"{path.relative_to(ROOT)}: {category}")
        self.assertEqual([], problems)

    def test_no_forbidden_capability_or_network_declaration_is_present(self) -> None:
        paths = [
            path for path in IOS.rglob("*")
            if path.is_file() and ".build" not in path.parts and path.suffix in {".swift", ".yml", ".strings", ".md"}
        ]
        text = "\n".join(path.read_text(encoding="utf-8") for path in paths)
        # NSAppTransportSecurity lives only in Configuration/Binario-Info.plist (pinned below).
        forbidden = [
            "NSLocationWhenInUseUsageDescription", "NSMicrophoneUsageDescription",
            "NSAppTransportSecurity", "NSAllowsArbitraryLoads", "CloudKit", "Firebase",
            "Mixpanel", "Amplitude", "BGTaskSchedulerPermittedIdentifiers",
        ]
        for value in forbidden:
            self.assertNotIn(value, text)
        self.assertNotRegex(text, re.compile(r"AKIA[0-9A-Z]{16}"))
        # Live rail data (owner decision, November 2026): every request is HTTPS. The one cleartext
        # address allowed is Android's hop to www.viaggiatreno.it; only the HTTP client's tests spell
        # out the cleartext redirects that the client refuses.
        refused_redirect_tests = IOS / "BinarioKit/Tests/BinarioKitTests/LiveSourceHTTPTests.swift"
        for path in paths:
            if path != refused_redirect_tests:
                self.assertNotRegex(path.read_text(encoding="utf-8"), r"http://(?!www\.viaggiatreno\.it/)", str(path))

    def test_the_only_cleartext_exception_is_androids_viaggiatreno_hop(self) -> None:
        # GENERATE_INFOPLIST_FILE cannot express a dictionary: the exception is a partial Info.plist
        # that Xcode merges with the INFOPLIST_KEY_ settings, outside every target's sources.
        settings = self.targets["Binario"]["settings"]["base"]
        self.assertIs(True, settings["GENERATE_INFOPLIST_FILE"])
        self.assertEqual("Configuration/Binario-Info.plist", settings["INFOPLIST_FILE"])
        info = plistlib.loads((IOS / "Configuration/Binario-Info.plist").read_bytes())
        self.assertEqual({"NSAppTransportSecurity": {"NSExceptionDomains": {"www.viaggiatreno.it": {
            "NSExceptionAllowsInsecureHTTPLoads": True, "NSIncludesSubdomains": False,
        }}}}, info)
        android = ElementTree.parse(ROOT / "app/src/main/res/xml/network_security_config.xml").getroot()
        self.assertEqual(
            [domain.text.strip() for domain in android.iter("domain") if domain.text],
            list(info["NSAppTransportSecurity"]["NSExceptionDomains"]),
        )
        for name, target in self.targets.items():
            for entry in target.get("sources", []):
                path = entry["path"] if isinstance(entry, dict) else entry
                self.assertFalse(path.startswith("Configuration"), name)
            self.assertNotIn("info", target, name)
        for path in list(IOS.rglob("*.plist")) + list((ROOT / "apple-watch").rglob("*.plist")):
            if path != IOS / "Configuration/Binario-Info.plist" and ".build" not in path.parts:
                self.assertNotIn(b"NSAppTransportSecurity", path.read_bytes(), str(path))
        # The client follows that hop only for the same resource, with an ephemeral session and an
        # honest User-Agent, and the system follows no redirect by itself.
        live = (IOS / "BinarioKit/Sources/BinarioKit/LiveSourceHTTP.swift").read_text(encoding="utf-8")
        for expected in [
            'static let viaggiaTrenoHost = "www.viaggiatreno.it"', "URLSessionConfiguration.ephemeral",
            "configuration.httpCookieStorage = nil", "configuration.urlCredentialStorage = nil",
            "configuration.urlCache = nil", "completionHandler(nil)",
            'public static let general = make(version: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)',
            'return "Binario/\\(cleaned.isEmpty ? "0" : cleaned) (iOS; rail timetable app)"',
        ]:
            self.assertIn(expected, live)
        sources = "\n".join(path.read_text(encoding="utf-8") for path in (IOS / "BinarioKit/Sources").rglob("*.swift"))
        for absent in ["URLSession.shared", "Mozilla", "AppleWebKit"]:
            self.assertNotIn(absent, sources)

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
                self.assertEqual({"sourceId", "sourceUrl", "accessMode", "licence", "landingPage"}, set(source))
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
        # The link opens the feed's public page, not the archive.
        self.assertIn("let page = source.landingPage ?? source.sourceURL", settings)

    def test_ios_source_policy_licences_and_pages_match_android(self) -> None:
        # Audit ci-docs-3: the iPhone showed no licence for ARST (CC BY 4.0) and EAV (IODL), which
        # Android records, and linked the raw archives. Each licence is the Android manifest's
        # (or the short form it gives in brackets, «CC BY» of «Creative Commons Attribution (CC
        # BY)»); null exactly while Android's is still to be confirmed by the owner; each link is
        # the landing page Android's source catalogue opens.
        policy = json.loads((IOS / "Resources/binario_gtfs_source_policy_v1.json").read_text(encoding="utf-8"))
        manifest = json.loads((ROOT / "app/src/main/assets/gtfs/manifest.json").read_text(encoding="utf-8"))
        android = {pack["sourceId"]: pack.get("licence") for pack in manifest["packs"]}
        catalogue = (ROOT / "app/src/main/java/it/binario/app/data/sources/RailSourceCatalog.kt").read_text(encoding="utf-8")
        pages = dict(re.findall(r'id = SourceId\("([^"]+)"\),.*?landingPage = uri\("([^"]+)"\)', catalogue, re.S))
        unconfirmed = "Da confermare dal titolare"
        for source in policy["sources"]:
            source_id = source["sourceId"]
            with self.subTest(source=source_id):
                self.assertIn(source_id, android)
                expected = android[source_id]
                if expected is None or expected == unconfirmed:
                    self.assertIsNone(source["licence"])
                else:
                    self.assertIsNotNone(source["licence"], f"Android records {expected!r}")
                    self.assertTrue(source["licence"] == expected or expected.endswith(f"({source['licence']})"),
                                    f"{source['licence']!r} is not {expected!r}")
                self.assertEqual(pages.get(source_id), source["landingPage"])
        self.assertEqual(5, sum(source["licence"] is None for source in policy["sources"]))

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

    def test_counted_texts_have_every_cldr_plural_form_in_every_language(self) -> None:
        key_source = (IOS / "BinarioKit/Sources/BinarioKit/LocalizationKeys.swift").read_text(encoding="utf-8")
        plural_block = key_source.split("public static let plural: Set<String> = [", 1)[1].split("]", 1)[0]
        plural_keys = set(SWIFT_REQUIRED_KEY.findall(plural_block))
        self.assertIn("result.reliability.on_time", plural_keys)
        self.assertTrue(plural_keys <= set(SWIFT_REQUIRED_KEY.findall(key_source.split("public static let plural", 1)[0])))
        for language in sorted(LANGUAGES):
            with self.subTest(language=language):
                strings = read_strings(IOS / "Resources" / f"{language}.lproj" / "Localizable.strings")
                with (IOS / "Resources" / f"{language}.lproj" / "Localizable.stringsdict").open("rb") as source:
                    stringsdict = plistlib.load(source)
                self.assertEqual(plural_keys, set(stringsdict))
                for key, entry in stringsdict.items():
                    # The counted number is argument 1, whatever the word order.
                    self.assertEqual({"NSStringLocalizedFormatKey": "%1$#@count@", "count": entry["count"]}, entry, key)
                    variable = dict(entry["count"])
                    self.assertEqual("NSStringPluralRuleType", variable.pop("NSStringFormatSpecTypeKey"), key)
                    self.assertEqual("lld", variable.pop("NSStringFormatValueTypeKey"), key)
                    self.assertEqual(PLURAL_CATEGORIES[language], set(variable), f"{language}:{key}")
                    reference = sorted(PLACEHOLDER.findall(variable["other"]))
                    self.assertIn("%1$lld", reference, f"{language}:{key}")
                    for category, form in variable.items():
                        self.assertEqual(reference, sorted(PLACEHOLDER.findall(form)), f"{language}:{key}:{category}")
                        self.assertNotIn("$#@", form, f"{language}:{key}:{category}")
                        self.assertIsNone(INVISIBLE.search(form), f"{language}:{key}:{category}")
                    # Localizable.strings keeps the "other" form for the key checks and as a fallback.
                    self.assertEqual(variable["other"].replace("\\", "\\\\").replace('"', '\\"'), strings[key], f"{language}:{key}")

    def test_bundled_statistics_are_the_android_asset_or_absent(self) -> None:
        # The iPhone app reads the same file as Android, from Resources (bundled like the GTFS
        # index); a build without it hides the feature, a stale copy must be synced.
        script = (IOS / "Scripts/sync-punctuality-stats.py").read_text(encoding="utf-8")
        self.assertIn('ANDROID_ASSET = REPOSITORY_ROOT / "app/src/main/assets/punctuality/stats_v1.json.gz"', script)
        self.assertIn('IOS_RESOURCE = REPOSITORY_ROOT / "ios/Resources/binario_punctuality_stats_v1.json.gz"', script)
        # The copy is generated before XcodeGen (locally and in the Apple CI), never committed.
        self.assertIn("ios/Resources/binario_punctuality_stats_v1.json.gz", (ROOT / ".gitignore").read_text(encoding="utf-8").splitlines())
        generate = (IOS / "Scripts/generate-project.sh").read_text(encoding="utf-8")
        self.assertLess(generate.index("Scripts/sync-punctuality-stats.py --remove"), generate.index("xcodegen generate --spec project.yml"))
        self.assertIn('if [ "${BINARIO_IOS_PUNCTUALITY_STATS:-true}" = "false" ]; then', generate)
        workflow = (ROOT / ".github/workflows/apple-watch-ci.yml").read_text(encoding="utf-8")
        self.assertLess(workflow.index("python3 -I ios/Scripts/sync-punctuality-stats.py"), workflow.index("ios/Scripts/verify-deterministic-generation.py"))
        if IOS_STATISTICS.exists():
            self.assertTrue(ANDROID_STATISTICS.is_file(), "iOS statistics without the Android asset")
            self.assertEqual(
                hashlib.sha256(ANDROID_STATISTICS.read_bytes()).hexdigest(),
                hashlib.sha256(IOS_STATISTICS.read_bytes()).hexdigest(),
                "ios/Resources/binario_punctuality_stats_v1.json.gz is stale: run ios/Scripts/sync-punctuality-stats.py",
            )
            with gzip.open(IOS_STATISTICS, "rb") as stream:
                self.assertEqual("binario-punctuality-v1", json.loads(stream.read())["schema"])
            self.assertLessEqual(IOS_STATISTICS.stat().st_size, 8 * 1024 * 1024)
        parser = (IOS / "BinarioKit/Sources/BinarioKit/NetworkReliabilityParser.swift").read_text(encoding="utf-8")
        model = (IOS / "BinarioApp/AppModel.swift").read_text(encoding="utf-8")
        self.assertIn('public static let resourceName = "binario_punctuality_stats_v1.json"', parser)
        self.assertIn('public static let resourceExtension = "gz"', parser)
        self.assertIn("inflateInit2_(&stream, MAX_WBITS + 16, ZLIB_VERSION", parser)
        self.assertIn("maximumCompressedBytes: Int = 8 * 1_024 * 1_024", parser)
        self.assertIn("maximumJSONBytes: Int = 64 * 1_024 * 1_024", parser)
        self.assertIn("forResource: NetworkReliabilityParser.resourceName", model)
        self.assertIn("NetworkReliabilityLoader.load(url: url)", model)
        tests = (IOS / "BinarioKit/Tests/BinarioKitTests/NetworkReliabilityTests.swift").read_text(encoding="utf-8")
        for name in (
            "testFiveSevenAndNineElementStopsAreReadAndNullsIgnored",
            "testAbsentCorruptOversizedAndOtherSchemaFilesHideTheFeature",
            "testGzipWrapperIsDecompressedWithinItsBound",
            "testOnTimeLimitsMinimumSamplesAndInterpolation",
            "testUsualPlatformAndWeekdayRemarksFollowTheSpecificationThresholds",
        ):
            self.assertIn(name, tests)

    def test_statistics_sync_script_copies_validates_and_removes(self) -> None:
        spec = importlib.util.spec_from_file_location("sync_punctuality_stats", IOS / "Scripts/sync-punctuality-stats.py")
        assert spec and spec.loader
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory(prefix="ios-stats-") as temporary:
            root = Path(temporary)
            asset = root / "stats_v1.json.gz"
            resource = root / "binario_punctuality_stats_v1.json.gz"
            self.assertEqual({"bundled": False, "reason": "no Android asset"}, module.sync(asset, resource))
            self.assertTrue(module.in_sync(asset, resource))
            asset.write_bytes(gzip.compress(json.dumps({"schema": "binario-punctuality-v1", "windowEnd": "2026-11-09", "trains": []}).encode()))
            self.assertFalse(resource.exists())
            self.assertTrue(module.in_sync(asset, resource), "an absent copy is a build without statistics")
            self.assertTrue(module.sync(asset, resource)["bundled"])
            self.assertEqual(asset.read_bytes(), resource.read_bytes())
            self.assertTrue(module.in_sync(asset, resource))
            asset.write_bytes(gzip.compress(json.dumps({"schema": "binario-punctuality-v1", "windowEnd": "2026-11-16", "trains": []}).encode()))
            self.assertFalse(module.in_sync(asset, resource))
            asset.write_bytes(gzip.compress(b'{"schema": "other", "trains": []}'))
            with self.assertRaises(module.StatisticsError):
                module.sync(asset, resource)
            asset.write_bytes(b"not gzip")
            with self.assertRaises(module.StatisticsError):
                module.sync(asset, resource)
            asset.unlink()
            module.sync(asset, resource)
            self.assertFalse(resource.exists())

    def test_reliability_rules_and_texts_follow_the_shared_specification(self) -> None:
        rules = (IOS / "BinarioKit/Sources/BinarioKit/NetworkReliability.swift").read_text(encoding="utf-8")
        for fragment in (
            "public static let minimumSamples = 5",
            "public static let highSpeedOnTimeMinutes = 5",
            "public static let otherOnTimeMinutes = 15",
            'private static let highSpeedCategories: Set<String> = ["FR", "FA", "FB", "EC", "ES", "ESC", "EN", "ITALO"]',
            "public static let minimumPlatformCount = 5",
            "public static let minimumPlatformSharePercent = 60",
            "public static let minimumWeekdaySamples = 4",
            "public static let minimumWeekdayGapPoints = 15",
            "public static let minimumAggregateSamples = 20",
            "public static let minimumBandSamples = 20",
            "public static let minimumBandGapPoints = 5",
            "let five = train.cumulative(at: index, limit: 5)",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, rules)
        for language in sorted(LANGUAGES):
            with self.subTest(language=language):
                values = read_strings(IOS / "Resources" / f"{language}.lproj" / "Localizable.strings")
                self.assertIn("ViaggiaTreno", values["result.reliability.attribution"])
                self.assertIn("Binario", values["result.reliability.attribution"])
        italian = read_strings(IOS / "Resources/it.lproj/Localizable.strings")
        self.assertEqual("Statistiche non ufficiali di Binario su dati ViaggiaTreno, aggiornate al %1$@", italian["result.reliability.attribution"])
        self.assertEqual("Senza ritardo", italian["result.detail.band_no_delay"])
        self.assertEqual("Più affidabili", italian["result.order.most_reliable"])
        with (IOS / "Resources/it.lproj/Localizable.stringsdict").open("rb") as source:
            forms = plistlib.load(source)
        self.assertEqual("In orario %1$lld volte su %2$lld", forms["result.reliability.on_time"]["count"]["other"])
        self.assertEqual("In orario %1$lld volta su %2$lld", forms["result.reliability.on_time"]["count"]["one"])
        self.assertEqual("%3$@: di solito al binario %4$@ (%1$lld volte su %2$lld)", forms["result.detail.usual_platform"]["count"]["other"])
        planner = (IOS / "BinarioApp/PlannerView.swift").read_text(encoding="utf-8")
        texts = (IOS / "BinarioApp/ReliabilityTexts.swift").read_text(encoding="utf-8")
        detail = (IOS / "BinarioApp/TrainDetailView.swift").read_text(encoding="utf-8")
        self.assertIn("reliability.mostReliableFirst(snapshot.services)", planner)
        self.assertIn("TrainDetailView(service: service, query: snapshot.query, boardLive: live)", planner)
        self.assertIn("RoutePunctualityView(route: route)", planner)
        self.assertIn('parts.joined(separator: " · ")', planner)
        self.assertIn("ReliabilityTexts.distributionRows(distribution)", detail)
        self.assertIn("detail.usualPlatform", detail)
        self.assertIn("detail.weekdayRemarks", detail)
        self.assertIn("ReliabilityTexts.maxDelay(maxDelay)", detail)
        # The Watch keeps the departure order: sorting is a view concern only.
        self.assertNotIn("mostReliableFirst", (IOS / "BinarioApp/AppModel.swift").read_text(encoding="utf-8"))
        self.assertIn('NSLocalizedString("result.reliability.on_time", comment: "")', texts)

    def test_more_punctual_alternative_is_the_android_rule_under_the_result_row(self) -> None:
        # «Più puntuale» (app/.../data/reliability/ReliableAlternatives.kt): the same thresholds,
        # shares compared exactly in integers, every listed train a candidate, gone a minute after
        # its departure, never cancelled, a live delay of the list moving the departure (Android's
        # ResultsAlternatives.stillToDepart).
        rule = (IOS / "BinarioKit/Sources/BinarioKit/NetworkReliabilityAlternatives.swift").read_text(encoding="utf-8")
        android = (ROOT / "app/src/main/java/it/binario/app/data/reliability/ReliableAlternatives.kt").read_text(encoding="utf-8")
        for android_fragment, fragment in (
            ("const val MAX_ON_TIME_PERCENT = 70", "public static let maximumOnTimePercent = 70"),
            ("const val MIN_GAP_POINTS = 20", "public static let minimumGapPoints = 20"),
            ("const val WINDOW_MINUTES = 30L", "public static let windowMinutes = 30"),
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(android_fragment, android)
                self.assertIn(fragment, rule)
        for android_fragment in (
            "it.key != train.key && it.stop!!.onTimeLimitMinutes == stop.onTimeLimitMinutes",
            "clearlyBetter(it.stop!!, stop) && clearlyMoreReliable(it.stop!!, stop)",
        ):
            with self.subTest(android=android_fragment):
                self.assertIn(android_fragment, android)
        for fragment in (
            "public static let departedGraceSeconds: TimeInterval = 60",
            "let onTimeScaled = Int64(stop.onTime) * 100",
            "let limitScaled = Int64(maximumOnTimePercent) * Int64(stop.samples)",
            "let gap = (betterScaled - worseScaled) * 100",
            "let needed = Int64(minimumGapPoints) * Int64(better.samples) * Int64(worse.samples)",
            "&& stop.samples >= NetworkReliability.minimumSamples",
            "!stop.cancelledEveryDay",
            "abs(otherDeparture.timeIntervalSince(departure)) <= window",
            # Same limit only, and the cancellations count, as on Android.
            "otherStop.onTimeLimitMinutes == stop.onTimeLimitMinutes",
            "clearlyMoreReliable(otherStop, than: stop)",
            "return gap >= Int64(minimumGapPoints) * betterDenominator * worseDenominator",
            "if live?.cancelled == true { return false }",
            "let delaySeconds = TimeInterval(max(live?.delayMinutes ?? 0, 0) * 60)",
            "return now < departure.addingTimeInterval(delaySeconds + departedGraceSeconds)",
            "stillToDepart: stillToDepart(departure, live: live[service.id], now: now)",
            "let calendar = NetworkReliabilityDay.romeCalendar",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, rule)
        self.assertNotIn("onTimeShare", rule, "shares are compared in integers, never as doubles")
        tests = (IOS / "BinarioKit/Tests/BinarioKitTests/ReliableAlternativesTests.swift").read_text(encoding="utf-8")
        for name in (
            "testATrainOnTimeLessThan70PercentGetsATrainAtLeast20PointsBetter",
            "testTheGapIsAtLeast20PointsCountedExactly",
            "testOnlyTrainsLeavingWithin30MinutesBeforeOrAfterCount",
            "testBothTrainsNeedAtLeast5SamplesAtTheDestination",
            "testTiesGoToTheClosestDepartureThenTheEarlierOneThenTheMorePunctualOne",
            "testTrainsAlreadyGoneAreNeverSuggestedAndGetNoSuggestion",
            "testEveryLateTrainOfTheListGetsItsOwnAlternativeNeverItself",
            "testATrainOftenCancelledIsNotOfferedForBeingPunctualOnTheDaysItRuns",
            "testOnlyTrainsWithTheSameOnTimeLimitAreComparedFiveMinutesForAFrecciarossaFifteenForARegional",
            "testATrenordRegionalOftenLateAtPaviaGetsTheNextOneByItsPublishedNumber",
            "testTheClosestTrainStillToDepartIsSuggestedNeverOneAlreadyGoneOrNeverRunning",
            "testATrainIsGoneAMinuteAfterItsScheduledDeparture",
            "testALiveSourceMovesTheDepartureOrCancelsTheTrain",
            "testTheListsLiveStateDecidesWhichTrainsCanStillBeTaken",
            "testListedTimesArePlacedFromTheQueryTimeOnAcrossMidnight",
            "testTheWindowIs30MinutesAndATrainAsLateGivesNothing",
        ):
            with self.subTest(test=name):
                self.assertIn(f"func {name}()", tests)

        # The line sits under the row of the late train, opens the suggested train's detail as its
        # own row does, reads as one sentence with «statistiche non ufficiali» and says what a tap
        # does; the clock moves every minute while the planner is on screen.
        planner = (IOS / "BinarioApp/PlannerView.swift").read_text(encoding="utf-8")
        texts = (IOS / "BinarioApp/ReliabilityTexts.swift").read_text(encoding="utf-8")
        for fragment in (
            "ReliableAlternatives.find(in: snapshot, reliability: measuredTrains, live: model.resultLive, now: now)",
            "TrainDetailView(service: alternative.service, query: snapshot.query)",
            "ReliableAlternativeLine(alternative: alternative)",
            ".accessibilityHint(Text(ReliabilityTexts.alternativeOpen(alternative)))",
            "let parts = ReliabilityTexts.alternativeParts(alternative)",
            ".accessibilityElement(children: .ignore)",
            "AvailableResultView(snapshot: snapshot, now: clock)",
            ".task { await followTheClock() }",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, planner)
        for key in ("result.alternative.train", "result.alternative.on_time", "result.alternative.open"):
            self.assertIn(f'NSLocalizedString("{key}", comment: "")', texts)
        self.assertIn("ReliableAlternatives.trainLabel(service)", texts)
        # The Watch lists the trains as they are: the suggestion is a view of the iPhone results.
        self.assertNotIn("ReliableAlternatives", (IOS / "BinarioApp/AppModel.swift").read_text(encoding="utf-8"))

        # The texts are Android's (reliability_alternative_*), with Apple placeholders.
        def apple(text: str) -> str:
            return re.sub(r"%(\d)\$d", r"%\1$lld", re.sub(r"%(\d)\$s", r"%\1$@", text))

        for language, folder in sorted(ANDROID_VALUES.items()):
            with self.subTest(language=language):
                resources = ElementTree.parse(ROOT / "app/src/main/res" / folder / "strings.xml").getroot()
                android_strings = {element.get("name"): element.text for element in resources.findall("string")}
                android_forms = {
                    item.get("quantity"): apple(item.text or "")
                    for element in resources.findall("plurals")
                    if element.get("name") == "reliability_alternative_on_time"
                    for item in element.findall("item")
                }
                values = read_strings(IOS / "Resources" / f"{language}.lproj" / "Localizable.strings")
                self.assertEqual(apple(android_strings["reliability_alternative_train"]), values["result.alternative.train"])
                self.assertEqual(apple(android_strings["reliability_alternative_open"]), values["result.alternative.open"])
                with (IOS / "Resources" / f"{language}.lproj" / "Localizable.stringsdict").open("rb") as source:
                    forms = dict(plistlib.load(source)["result.alternative.on_time"]["count"])
                forms.pop("NSStringFormatSpecTypeKey")
                forms.pop("NSStringFormatValueTypeKey")
                self.assertEqual(android_forms, forms)
        italian = read_strings(IOS / "Resources/it.lproj/Localizable.strings")
        self.assertEqual("Più puntuale: %1$@ delle %2$@", italian["result.alternative.train"])
        self.assertEqual("Mostra il treno delle %1$@", italian["result.alternative.open"])
        self.assertEqual("in orario %1$lld volte su %2$lld", italian["result.alternative.on_time"])

    def test_my_trains_are_androids_rules_kept_on_the_iphone_only(self) -> None:
        # «I miei treni» and «Affidabilità dei miei treni»: Android's limits and matching
        # (FavouriteTrains.kt, FavouriteTrainPunctuality.kt, ReliabilityMarks.favouriteStop and the
        # number of BinarioDeepLink.FavouriteTrain), stored in the iPhone's UserDefaults only.
        rules = (IOS / "BinarioKit/Sources/BinarioKit/FavouriteTrains.swift").read_text(encoding="utf-8")
        android_sources = ROOT / "app/src/main/java/it/binario/app"
        android = {
            "favourites": (android_sources / "FavouriteTrains.kt").read_text(encoding="utf-8"),
            "punctuality": (android_sources / "FavouriteTrainPunctuality.kt").read_text(encoding="utf-8"),
            "marks": (android_sources / "widget/ReliabilityMarks.kt").read_text(encoding="utf-8"),
            "link": (android_sources / "BinarioDeepLink.kt").read_text(encoding="utf-8"),
        }
        for source, android_fragment, fragment in (
            ("favourites", "const val MAX = 12", "public static let maximumTrains = 12"),
            ("punctuality", "const val MAX_ROWS = 8", "public static let maximumRows = 8"),
            ("link", 'Regex("^[A-Za-z0-9]{1,12}$")', "public static let maximumNumberKeyLength = 12"),
            (
                "marks",
                "NetworkReliability.matchTerminus(stats, number, originCode)?.let { return it }",
                "if let terminus = NetworkReliability.matchTerminus(stats, number: number, boardingCode: originCode) { return terminus }",
            ),
            (
                "marks",
                "if (originCode != null && NetworkReliability.isStationCode(originCode)) return null",
                "if NetworkReliability.isStationCode(originCode) { return nil }",
            ),
            (
                "marks",
                "stats.trainsNumbered(number).filter { it.stopCount > 1 }.singleOrNull()",
                "let running = stats.trains(numbered: number).filter { $0.stopCount > 1 }",
            ),
            ("marks", "NetworkReliability.at(it.train, it.alightingIndex)", "return NetworkReliability.at(found.train, index: found.alightingIndex)"),
            ("punctuality", "monitors.take(MAX_ROWS).map", "favourites.prefix(maximumRows).compactMap"),
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(android_fragment, android[source])
                self.assertIn(fragment, rules)
        for fragment in (
            'public static let storageKey = "it.binario.ios.favourite-trains.v1"',
            "let key = NetworkReliability.numberKey(number)",
            "public static let maximumNumberLength = 24",
            "_ = try JourneyQuery(origin: origin, destination: destination, departure: savedAt)",
            "guard trains.count < Self.maximumTrains else { throw FavouriteTrainValidationError.tooManyTrains }",
            "guard data.count <= maximumStoredBytes else { return [] }",
            "train = try? FavouriteTrain(from: decoder)",
            "\"\\(number.uppercased())@\\(originID)\"",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, rules)

        model = (IOS / "BinarioApp/AppModel.swift").read_text(encoding="utf-8")
        detail = (IOS / "BinarioApp/TrainDetailView.swift").read_text(encoding="utf-8")
        trains = (IOS / "BinarioApp/MyTrainsView.swift").read_text(encoding="utf-8")
        root = (IOS / "BinarioApp/RootView.swift").read_text(encoding="utf-8")
        for source, fragment in (
            (model, "self.favouriteTrains = favouriteTrainStore.load().trains"),
            (model, "FavouriteTrainsReliability.assess(favouriteTrains, stats: reliabilityStats)"),
            (model, "} catch FavouriteTrainValidationError.tooManyTrains {"),
            (detail, "switch model.toggleFavourite(service, query: query) {"),
            (detail, 'LocalizedStringKey(saved ? "result.favourite.remove" : "result.favourite.add")'),
            (detail, "MyTrainsTexts.limitError(FavouriteTrainCollection.maximumTrains)"),
            (root, "NavigationStack { MyTrainsView() }"),
            (root, ".tag(AppModel.Tab.trains)"),
            (trains, ".navigationDestination(for: FavouriteTrain.self)"),
            (trains, "TrainDetailView(service: train.service, query: query)"),
            (trains, "if let reliability = model.favouriteTrainsReliability {"),
            (trains, "Text(ReliabilityTexts.attribution(reliability.windowEnd))"),
            (trains, '.accessibilityLabel(Text(ReliabilityTexts.spoken(parts.joined(separator: ", "))))'),
            (trains, '.accessibilityAction(named: Text("result.favourite.remove"))'),
            (trains, 'NSLocalizedString("saved.trains.limit_error", comment: "")'),
            (trains, 'NSLocalizedString("saved.trains.reliability.description", comment: "")'),
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, source)
        # The Watch gets no favourite train: the snapshot contract is unchanged.
        snapshots = sorted((IOS / "BinarioKit/Sources/BinarioKit").glob("WearSnapshot*.swift"))
        self.assertTrue(snapshots)
        for path in snapshots:
            self.assertNotIn("favourite", path.read_text(encoding="utf-8").lower(), path.name)

        kit_tests = (IOS / "BinarioKit/Tests/BinarioKitTests/FavouriteTrainsTests.swift").read_text(encoding="utf-8")
        for name in (
            "testANumberIsAcceptedWhenAndroidAcceptsItsTrainNumberAndThePublishedFormIsKept",
            "testAFavouriteIsItsNumberBoardedAtItsOriginAndOpensAsItWasSaved",
            "testTextsDeparturesAndStationsAreValidated",
            "testTheListIsNewestFirstHoldsTwelveAndRemovingIsAlwaysPossible",
            "testTheStoreKeepsTheTrainsInItsOwnKeyAndReadsThemBack",
            "testAnUnreadableStoreReadsAsNoTrainAndAnInvalidRecordIsSkippedOnItsOwn",
            "testEachFavouriteIsMeasuredAtItsLastStopWithItsOwnLimitFromFiveSamples",
            "testOnlyTheFirstEightFavouritesAreConsideredInTheirOrder",
            "testAbsentStatisticsOrNoTrainMeasuredShowNothing",
        ):
            with self.subTest(test=name):
                self.assertIn(f"func {name}()", kit_tests)
        self.assertIn(
            "func testTheStarSavesATrainOnThisIPhoneRemovesItAndRefusesOneTooMany()",
            (IOS / "BinarioAppTests/BinarioAppTests.swift").read_text(encoding="utf-8"),
        )

        # Disclosed as stored on the iPhone only.
        privacy = (ROOT / "PRIVACY.md").read_text(encoding="utf-8")
        self.assertIn("`it.binario.ios.favourite-trains.v1`", privacy)
        self.assertIn("I treni salvati restano solo sull'iPhone", privacy)

        # The titles are Android's in every language; the limit is the one of the code.
        for language, folder in sorted(ANDROID_VALUES.items()):
            with self.subTest(language=language):
                resources = ElementTree.parse(ROOT / "app/src/main/res" / folder / "strings.xml").getroot()
                android_strings = {element.get("name"): element.text for element in resources.findall("string")}
                values = read_strings(IOS / "Resources" / f"{language}.lproj" / "Localizable.strings")
                self.assertEqual(android_strings["favourite_trains_title"], values["tab.trains"])
                self.assertEqual(android_strings["favourite_trains_title"], values["saved.trains.title"])
                self.assertEqual(android_strings["my_trains_reliability_title"], values["saved.trains.reliability.title"])
        with (IOS / "Resources/it.lproj/Localizable.stringsdict").open("rb") as source:
            forms = plistlib.load(source)
        self.assertEqual("Puoi salvare al massimo %1$lld treni.", forms["saved.trains.limit_error"]["count"]["other"])
        self.assertEqual("Puoi salvare al massimo %1$lld treno.", forms["saved.trains.limit_error"]["count"]["one"])

    def test_departures_board_reads_the_bundled_timetable_behind_a_provider(self) -> None:
        # «Partenze»: the trains leaving a station from the bundled index, behind a Sendable
        # provider protocol (the bundled timetable first, the fallback of a later live source),
        # with the planner's naming, bikes and bounds; rows can carry a live status, and the
        # «orario programmato» disclosure follows the rows of the scheduled source.
        board = (IOS / "BinarioKit/Sources/BinarioKit/StationBoard.swift").read_text(encoding="utf-8")
        gtfs = (IOS / "BinarioKit/Sources/BinarioKit/SQLiteGTFSJourneyRepository.swift").read_text(encoding="utf-8")
        for fragment in (
            "public protocol StationBoardProvider: Sendable {",
            "func departures(for query: StationBoardQuery) async -> StationBoardResult",
            "public struct FailClosedStationBoardProvider: StationBoardProvider {",
            "public struct StationBoardLiveStatus: Equatable, Sendable {",
            "public let live: StationBoardLiveStatus?",
            "public var isScheduled: Bool { live == nil }",
            "public static let maximumPageSize = 50",
            "self.limit = min(max(limit, 1), Self.maximumPageSize)",
            "try JourneyModelValidator.identity(station)",
            "ReliableAlternatives.stillToDepart(departure, now: now)",
            'public static let storageKey = "it.binario.ios.departures-station.v1"',
            "guard let data = defaults.data(forKey: Self.storageKey), data.count <= Self.maximumStoredBytes,",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, board)
        self.assertNotIn("URLSession", board)
        self.assertIn("static let boardNextServiceDayHorizon: TimeInterval = 4 * 60 * 60", gtfs)
        query = gtfs.split("// MARK: - «Partenze»", 1)[1]
        for fragment in (
            "extension SQLiteGTFSJourneyRepository: StationBoardProvider {",
            "if try indexEnded(before: query.from, timeZone: referenceTimeZone, database: database) {",
            "for offset in -1...maximumServiceDayLookback {",
            "latest: offset < 0 ? query.from.addingTimeInterval(boardNextServiceDayHorizon) : nil",
            "AND board_time.pickup_type = 0",
            "AND board_time.departure_seconds BETWEEN ? AND ?",
            "try statement.bind(Int64(maximumCandidateRows + 1), at: binding)",
            "guard rowsRead <= maximumCandidateRows else { throw SQLiteGTFSFailure.limitExceeded }",
            "serviceRuns(serviceDay: serviceDay, startDate: startDate, dayCount: dayCount,",
            "let number = trainNumber(trip: statement.text(at: 2), route: statement.text(at: 3), externalTripID: externalTripID)",
            "let category = trainCategory(route: statement.text(at: 3), longName: statement.text(at: 4), type: statement.int(at: 5))",
            "bikesAllowed: bikes(statement.int(at: 15))",
            "platform: nil,",
            "status: OperationalState.programmed.rawValue,",
            "!boardStops.contains(terminus.stopID),",
            "ORDER BY last_time.stop_sequence DESC",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, query)
        # Coaches and replacement buses follow the planner's rule: the index keeps only the packs'
        # rail routes, and neither query filters route types of its own.
        self.assertIn("RAIL_ROUTE_TYPES = frozenset({2, *range(100, 200)})", (ROOT / "tools/build_ios_gtfs_schedule.py").read_text(encoding="utf-8"))
        self.assertNotIn("route_type IN", gtfs)
        self.assertNotIn("route_type =", gtfs)
        # The planner rows and the board name trains with the same helpers.
        planner_query = gtfs.split("private static func queryCandidates(", 1)[1].split("private static func trainNumber(", 1)[0]
        self.assertIn("let number = trainNumber(trip: statement.text(at: 2), route: statement.text(at: 4), externalTripID: externalTripID)", planner_query)
        self.assertIn("let bikesAllowed = bikes(statement.int(at: 16))", planner_query)

        model = (IOS / "BinarioApp/AppModel.swift").read_text(encoding="utf-8")
        view = (IOS / "BinarioApp/DeparturesView.swift").read_text(encoding="utf-8")
        root = (IOS / "BinarioApp/RootView.swift").read_text(encoding="utf-8")
        for source, fragment in (
            (model, "private(set) var boardProvider: any StationBoardProvider"),
            (model, "boardProvider = installed.repository"),
            (model, "boardProvider = FailClosedStationBoardProvider()"),
            (model, "static let boardPageSize = 20"),
            (model, "static let maximumBoardDepartures = 200"),
            (model, "self.boardStation = boardStationStore.load()"),
            (model, "boardStationStore.save(station)"),
            (model, "guard requestID == boardRequestID, boardStation == station else { return }"),
            (model, "from: StationBoardQuery.minuteStart(of: now),"),
            (model, "after: last.cursor"),
            (model, "let remaining = listed.filter { $0.stillToLeave(at: now) }"),
            (model, "return try? JourneyQuery(origin: station, destination: terminus, departure: departure.departure)"),
            (view, 'StationPickerField(titleKey: "departures.station", selection: stationSelection)'),
            (view, "TrainDetailView(service: departure.service, query: query, boardLive: departure.live)"),
            (view, "Text(ReliableAlternatives.trainLabel(service))"),
            (view, "BikesLabel(allowed: bikesAllowed)"),
            (view, "if rows.contains(where: { $0.isScheduled }) {"),
            (view, 'Label("result.programmed.disclosure", systemImage: "clock")'),
            (view, ".accessibilityElement(children: .ignore)"),
            (view, ".accessibilityLabel(Text(DeparturesTexts.spoken(departure)))"),
            (view, 'NSLocalizedString("departures.later.loaded", comment: "")'),
            (view, ".task { await followTheClock() }"),
            (root, "NavigationStack { DeparturesView() }"),
            (root, ".tag(AppModel.Tab.departures)"),
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, source)
        # Second tab, as on Android (Pianifica, Partenze, Per te).
        self.assertLess(root.index("PlannerView()"), root.index("DeparturesView()"))
        self.assertLess(root.index("DeparturesView()"), root.index("SavedRoutesView()"))
        # Android's station board shows no reliability per row: neither does the iPhone's.
        for name in ("NetworkReliability", "resultReliability", "reliabilityStats", "ReliabilityTexts"):
            self.assertNotIn(name, view)
        # Nothing of the board reaches the Watch.
        for path in sorted((IOS / "BinarioKit/Sources/BinarioKit").glob("WearSnapshot*.swift")):
            self.assertNotIn("StationBoard", path.read_text(encoding="utf-8"), path.name)

        kit_tests = (IOS / "BinarioKit/Tests/BinarioKitTests/StationBoardTests.swift").read_text(encoding="utf-8")
        for name in (
            "testTheBoardListsTodaysNextTrainsInDepartureOrderNamedAsThePlannerNamesThem",
            "testTrainsGoneNotBoardingHereOrEndingHereAreNoDeparturesAndATrainCallingTwiceIsListedOnce",
            "testPagesFollowTheLastListedTrainWithoutGapsOrRepeats",
            "testYesterdaysRunsStillToLeaveAfterMidnightJoinTodaysServiceDay",
            "testTheServiceDayFollowsTheTimetableCalendarAcrossRomeDST",
            "testTomorrowsTrainsAfterTheNightAreNotListedAndTheFirstOneIsGivenWhenNoneIsLeft",
            "testTheFirstHoursOfTomorrowsServiceDayFollowTonightsLastTrains",
            "testUnservedStationsEndedTimetablesAndInvalidIndexesFailClosed",
            "testQueriesAreBoundedAndInvalidStationsAreRefused",
            "testARowIsScheduledUnlessALiveSourceObservedItAndLeavesAMinuteAfterItsDeparture",
            "testTheLastStationIsRememberedOnThisIPhoneOnlyWhenItIsValid",
        ):
            with self.subTest(test=name):
                self.assertIn(f"func {name}()", kit_tests)
        self.assertIn(
            "func testPartenzeRemembersTheStationReadsLaterPagesAndOpensTheTrainBoardedThere()",
            (IOS / "BinarioAppTests/BinarioAppTests.swift").read_text(encoding="utf-8"),
        )

        # The board reads at most maximumCandidateRows rows of a service day: every station of the
        # bundled index has fewer boarding rows per agency time zone, whatever the day.
        with tempfile.TemporaryDirectory(prefix="ios-board-") as temporary:
            database = Path(temporary) / "schedule.sqlite"
            database.write_bytes(zlib.decompress((IOS / "Resources/binario_gtfs_schedule_v1.sqlite.zlib").read_bytes()))
            connection = sqlite3.connect(f"file:{database}?mode=ro&immutable=1", uri=True)
            try:
                busiest = connection.execute(
                    """
                    SELECT max(rows) FROM (
                        SELECT count(*) AS rows FROM stop_time AS st
                        JOIN stop AS s ON s.id = st.stop_id
                        JOIN trip AS t ON t.id = st.trip_id JOIN route AS r ON r.id = t.route_id
                        JOIN agency AS a ON a.id = r.agency_id
                        WHERE s.catalog_station_id IS NOT NULL AND st.pickup_type = 0
                        GROUP BY s.catalog_station_id, a.timezone
                    )
                    """
                ).fetchone()[0]
            finally:
                connection.close()
        self.assertIn("private static let maximumCandidateRows = 4_096", gtfs)
        self.assertLessEqual(busiest, 4_096)

        # Disclosed as stored on the iPhone only.
        privacy = (ROOT / "PRIVACY.md").read_text(encoding="utf-8")
        self.assertIn("`it.binario.ios.departures-station.v1`", privacy)

        # The names are Android's in every language.
        def apple(text: str) -> str:
            return re.sub(r"%(\d)\$d", r"%\1$lld", re.sub(r"%(\d)\$s", r"%\1$@", text))

        for language, folder in sorted(ANDROID_VALUES.items()):
            with self.subTest(language=language):
                resources = ElementTree.parse(ROOT / "app/src/main/res" / folder / "strings.xml").getroot()
                android_strings = {element.get("name"): element.text for element in resources.findall("string")}
                values = read_strings(IOS / "Resources" / f"{language}.lproj" / "Localizable.strings")
                for key, android_name in (
                    ("tab.departures", "nav_departures"),
                    ("departures.title", "nav_departures"),
                    ("departures.station", "station_label"),
                    ("departures.list.title", "shortcut_next_departures"),
                    ("departures.later", "results_later"),
                    ("departures.no_more_today", "journey_no_more_today"),
                    ("departures.row.destination", "station_board_departure_to"),
                    ("departures.row.platform", "station_board_platform"),
                ):
                    self.assertEqual(apple(android_strings[android_name]), values[key], key)
        italian = read_strings(IOS / "Resources/it.lproj/Localizable.strings")
        self.assertEqual("Partenze", italian["tab.departures"])
        self.assertEqual("%1$@, treno %2$@ per %3$@, %4$@", italian["departures.row.spoken"])
        with (IOS / "Resources/it.lproj/Localizable.stringsdict").open("rb") as source:
            forms = plistlib.load(source)
        self.assertEqual("%1$lld treno in più", forms["departures.later.loaded"]["count"]["one"])
        self.assertEqual("%1$lld treni in più", forms["departures.later.loaded"]["count"]["other"])

    def test_live_data_is_asked_in_the_foreground_only_behind_a_switch_that_is_on_by_default(self) -> None:
        # Owner decision, November 2026: the iPhone shows Android's live data over the bundled
        # timetable, from one `LiveRailData.standard()`, only for the screen on display, never in the
        # background, and with «Dati in tempo reale» (on by default) off it makes no request at all.
        app = {path.name: path.read_text(encoding="utf-8") for path in (IOS / "BinarioApp").glob("*.swift")}
        model = app["AppModel.swift"]
        # The screens never build a request themselves: only BinarioKit's live core talks to the network.
        for name, source in app.items():
            with self.subTest(source=name):
                self.assertNotIn("URLSession", source)
                self.assertNotRegex(source, r"https?://")
        code = {name: "\n".join(line for line in source.splitlines() if not line.strip().startswith("//")) for name, source in app.items()}
        self.assertEqual(1, sum(source.count("LiveRailData.standard(") for source in code.values()))
        self.assertIn("liveData: LiveRailData.standard(),", model.split("static func bootstrap(", 1)[1].split("func loadCatalog(", 1)[0])
        # No background mode, task or schedule was added for it.
        for path in [IOS / "project.yml", IOS / "Configuration/Binario-Info.plist"]:
            text = path.read_text(encoding="utf-8")
            for absent in ("UIBackgroundModes", "BGTaskScheduler", "fetch", "remote-notification"):
                self.assertNotIn(absent, text, f"{path.name}: {absent}")
        for name, source in app.items():
            with self.subTest(source=name):
                self.assertNotIn("BGTaskScheduler", source)
                self.assertNotIn("registerForRemoteNotifications", source)

        # The switch: its own key, on unless the app wrote a boolean false, saved like the other stores.
        store = model.split("final class LiveDataPreferenceStore {", 1)[1].split("extension WatchSyncStatus", 1)[0]
        for fragment in (
            'static let storageKey = "it.binario.ios.live-data-enabled.v1"',
            "guard let stored = defaults.object(forKey: Self.storageKey) as? NSNumber,",
            "CFGetTypeID(stored) == CFBooleanGetTypeID() else { return true }",
            "defaults.set(enabled, forKey: Self.storageKey)",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, store)
        self.assertIn("set: { enabled in model.setLiveDataEnabled(enabled) }", app["SettingsView.swift"])
        switch = model.split("func setLiveDataEnabled(_ enabled: Bool) {", 1)[1].split("func liveDidBecomeActive()", 1)[0]
        self.assertIn("liveDataStore.save(enabled)", switch)
        self.assertIn("clearLiveData()", switch)
        # Every request goes through a guard of the switch and of the screen on display.
        for guard in (
            "guard boardLiveVisible, liveDataEnabled, let liveData, let identity = boardStation else { return }",
            "guard plannerLiveVisible, liveDataEnabled, let liveData, let snapshot = latestSnapshot else { return }",
            "guard liveDataEnabled, let liveData, let key = detailLiveKey else { return }",
            "guard favouritesLiveVisible, liveDataEnabled, let liveData else { return }",
        ):
            with self.subTest(guard=guard):
                self.assertIn(guard, model)
        self.assertEqual(3, model.count("await liveData."), "board, planner and the train status lookup")
        self.assertEqual(1, model.count("await liveData.trainStatus("))
        # Stale answers are dropped as the board's are.
        for fragment in (
            "guard requestID == boardLiveRequestID, boardStation == identity, liveDataEnabled else { return }",
            "guard requestID == resultLiveRequestID, searchID == searchRequestID, liveDataEnabled else { return }",
            "guard requestID == detailLiveRequestID, detailLiveKey == key, liveDataEnabled else { return }",
            "guard requestID == favouriteLiveRequestID, favouritesLiveVisible, liveDataEnabled else { return }",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, model)
        # Android's numbers: the first 8 favourites, the ±3 hour window, two runs 2 hours apart are two.
        punctuality = (ROOT / "app/src/main/java/it/binario/app/FavouriteTrainPunctuality.kt").read_text(encoding="utf-8")
        candidates = (ROOT / "app/src/main/java/it/binario/app/journeys/JourneyLiveLookup.kt").read_text(encoding="utf-8")
        self.assertIn("const val MAX_ROWS = 8", punctuality)
        self.assertIn("static let maximumLiveFavourites = 8", model)
        self.assertIn("val LOOKBACK: Duration = Duration.ofHours(3)", candidates)
        self.assertIn("val LOOKAHEAD: Duration = Duration.ofHours(3)", candidates)
        self.assertIn("val SAME_RUN_TOLERANCE: Duration = Duration.ofHours(2)", candidates)
        # A live row and a bundled train: Android's LiveRunMerge window, the same number and family.
        reconciler = (ROOT / "app/src/main/java/it/binario/app/data/repository/StationBoardRealtimeReconciler.kt").read_text(encoding="utf-8")
        overlay = (IOS / "BinarioKit/Sources/BinarioKit/LiveRailData.swift").read_text(encoding="utf-8")
        self.assertIn("private val RETIMING_WINDOW: Duration = Duration.ofHours(3)", reconciler)
        self.assertIn("RailOperator.TRENORD -> \"trenitalia-trenord\"", reconciler)
        for fragment in (
            "public static let timetableTolerance: TimeInterval = 3 * 60 * 60",
            "abs($0.scheduled.timeIntervalSince(scheduled)) <= timetableTolerance",
            "&& sameFamily(family, operatorFamily($0.operatorID))",
            'if folded.contains("trenitalia") || folded.contains("trenord") { return "trenitalia-trenord" }',
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, overlay)
        for fragment in (
            "static let liveLookback: TimeInterval = 3 * 3_600",
            "static let liveLookahead: TimeInterval = 3 * 3_600",
            "static let sameRunTolerance: TimeInterval = 2 * 3_600",
            "let number = NetworkReliability.numberKey(service.number)",
            "LiveOverlay.stationBoard(rows, with: live.board, at: live.at)",
            "let matched = LiveOverlay.departures(for: planned, in: board.departures)",
            "guard let run = candidates.run(for: request),",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, model)

        # Each screen asks only while it is on screen; the app cancels everything in the background.
        for name, appear, disappear in (
            ("DeparturesView.swift", ".onAppear { model.boardLiveDidAppear() }", ".onDisappear { model.boardLiveDidDisappear() }"),
            ("PlannerView.swift", ".onAppear { model.plannerLiveDidAppear() }", ".onDisappear { model.plannerLiveDidDisappear() }"),
            ("MyTrainsView.swift", ".onAppear { model.favouritesLiveDidAppear() }", ".onDisappear { model.favouritesLiveDidDisappear() }"),
            ("TrainDetailView.swift", "if let key { model.detailLiveDidAppear(key) }", "if let liveKey { model.detailLiveDidDisappear(liveKey) }"),
        ):
            with self.subTest(view=name):
                self.assertIn(appear, app[name])
                self.assertIn(disappear, app[name])
        for name in ("DeparturesView.swift", "TrainDetailView.swift", "MyTrainsView.swift"):
            with self.subTest(refreshable=name):
                self.assertIn(".refreshable {", app[name])
                self.assertIn('Label("action.refresh", systemImage: "arrow.clockwise")', app[name])
        self.assertIn("case .background: model.liveDidEnterBackground()", app["RootView.swift"])
        self.assertIn("case .active: model.liveDidBecomeActive()", app["RootView.swift"])
        # The rows keep the «orario programmato» disclosure for the trains without a live value.
        self.assertIn("if snapshot.services.contains(where: { model.resultLive[$0.id] == nil }) {", app["PlannerView.swift"])
        self.assertIn("if rows.contains(where: { $0.isScheduled }) {", app["DeparturesView.swift"])
        self.assertIn("if liveRun == nil, shownBoardLive == nil {", app["TrainDetailView.swift"])
        # The live state is part of each row's sentence.
        self.assertIn("parts += LiveTexts.spoken(live, includingPlatform: false)", app["DeparturesView.swift"])
        self.assertIn(".accessibilityLabel(Text(LiveTexts.spokenStop(stop)))", app["TrainDetailView.swift"])
        self.assertIn('.accessibilityLabel(Text(LiveTexts.spoken(status, includingPlatform: showsPlatform).joined(separator: ". ")))', app["LiveTexts.swift"])
        # The Watch keeps the programmed snapshot.
        publish = model.split("private func publishAvailableSnapshot(", 1)[1].split("static func isCurrent(", 1)[0]
        self.assertNotIn("Live", publish)

        model_tests = (IOS / "BinarioAppTests/BinarioAppTests.swift").read_text(encoding="utf-8")
        for name in (
            "testPartenzeLaysTheLiveBoardOverItsRowsOnlyOnScreenAndFallsBackWhenEverySourceFails",
            "testAnAnswerForTheStationLeftBehindNeverReachesTheNewOne",
            "testPlannerResultsTakeTheLiveBoardOfTheOriginWithinTheLiveWindow",
            "testTrainDetailShowsItsOwnRunAtTheBoardingStationAndNeverAnotherDays",
            "testMyTrainsAsksTheFirstEightFavouritesOnlyWhileOnScreen",
            "testTheLiveSwitchIsOnByDefaultPersistedValidatedAndOffAsksNothing",
        ):
            with self.subTest(test=name):
                self.assertIn(f"func {name}()", model_tests)
        self.assertIn(
            "func testThePartenzeLiveStatusNamesTheSourceOfEachValueAndABareLateLabel()",
            (IOS / "BinarioKit/Tests/BinarioKitTests/LiveRailDataTests.swift").read_text(encoding="utf-8"),
        )

        # Disclosed before it is wired: the sources, what each receives, the switch and its key.
        privacy = (ROOT / "PRIVACY.md").read_text(encoding="utf-8")
        live_privacy = privacy.split("### Dati in tempo reale", 1)[1].split("### Condivisione con Apple Watch", 1)[0]
        for fragment in (
            "`it.binario.ios.live-data-enabled.v1`", "`www.viaggiatreno.it`", "`iechub.rfi.it`",
            "`italoinviaggio.italotreno.com`", "solo con l'app in primo piano", "indirizzo IP",
            "Le risposte restano solo in memoria", "nulla viene scritto sul dispositivo", "l'app non fa più alcuna richiesta di rete",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, live_privacy)
        self.assertIn("Disattivare **Dati in tempo reale** in Impostazioni", privacy)
        store = (ROOT / "docs/store/APP_STORE_SUBMISSION.md").read_text(encoding="utf-8")
        notes = store.split("#### Note per la revisione", 1)[1].split("## 6.", 1)[0]
        for fragment in ("ViaggiaTreno", "RFI", "Italo", "\"Dati in tempo reale\"", "\"Real-time data\""):
            self.assertIn(fragment, notes)
        self.assertIn("**Dati non raccolti**", store)
        for absent in ("funziona interamente offline", "works entirely offline", "nessuna richiesta di rete.", "no network requests."):
            self.assertNotIn(absent, store)

        # The texts are Android's in every language where Android has them, with Apple placeholders.
        def apple(text: str) -> str:
            text = re.sub(r"%(\d)\$d", r"%\1$lld", re.sub(r"%(\d)\$s", r"%\1$@", text))
            return text.replace("%d", "%1$lld")

        shared = {
            "action.refresh": "action_refresh",
            "result.live.title": "data_chip_live",
            "result.live.unavailable": "data_status_unavailable",
            "result.live.source": "route_change_source",
            "result.live.paused": "data_status_source_paused",
            "result.live.expected_short": "expected_time_short",
            "result.live.expected": "expected_time",
            "result.live.scheduled": "map_stop_scheduled",
            "result.live.delay_short": "delay_minutes",
            "result.live.on_time": "run_status_on_time",
            "result.live.cancelled": "run_status_cancelled",
            "result.live.partially_cancelled": "run_status_partially_cancelled",
            "result.live.late_unpublished": "service_late_minutes_unpublished",
            "result.live.unverified": "operational_status_unverified",
            "result.live.expected_departure": "detail_info_expected",
            "result.live.platform": "detail_info_platform",
            "result.live.last_observation": "trains_widget_settings_info_last",
            "result.live.stops": "service_stops_title",
            "result.live.passed": "stop_position_passed",
            "result.live.skipped": "map_role_skipped",
        }
        for language, folder in sorted(ANDROID_VALUES.items()):
            with self.subTest(language=language):
                resources = ElementTree.parse(ROOT / "app/src/main/res" / folder / "strings.xml").getroot()
                android_strings = {element.get("name"): element.text for element in resources.findall("string")}
                android_forms = {
                    item.get("quantity"): apple(item.text or "")
                    for element in resources.findall("plurals")
                    if element.get("name") == "trains_widget_delay_accessibility"
                    for item in element.findall("item")
                }
                values = read_strings(IOS / "Resources" / f"{language}.lproj" / "Localizable.strings")
                for key, android_name in shared.items():
                    self.assertEqual(apple(android_strings[android_name]), values[key], key)
                with (IOS / "Resources" / f"{language}.lproj" / "Localizable.stringsdict").open("rb") as source:
                    forms = dict(plistlib.load(source)["result.live.delay"]["count"])
                forms.pop("NSStringFormatSpecTypeKey")
                forms.pop("NSStringFormatValueTypeKey")
                self.assertEqual(android_forms, forms)
        italian = read_strings(IOS / "Resources/it.lproj/Localizable.strings")
        self.assertEqual("Dati in tempo reale", italian["settings.live.toggle"])
        self.assertEqual("Dati in tempo reale da %1$@", italian["result.live.from_sources"])
        self.assertEqual("Fonte: %1$@", italian["result.live.source"])
        self.assertEqual("+%1$lld min", italian["result.live.delay_short"])
        self.assertEqual("In ritardo di %1$lld minuti", italian["result.live.delay"])

    def test_bikes_come_from_the_published_trip_value_and_italo_is_unknown(self) -> None:
        builder = (ROOT / "tools/build_ios_gtfs_schedule.py").read_text(encoding="utf-8")
        gtfs = (IOS / "BinarioKit/Sources/BinarioKit/SQLiteGTFSJourneyRepository.swift").read_text(encoding="utf-8")
        self.assertIn('BIKES_VALUE_NOT_SHOWN = frozenset({"italo-gtfs"})', builder)
        self.assertIn("bikes_allowed INTEGER CHECK(bikes_allowed IN (1, 2))", builder)
        self.assertIn('"bikes_allowed"]', gtfs)
        self.assertIn("public static let userVersion: Int32 = 2", gtfs)
        self.assertIn("BikesLabel(allowed: bikesAllowed)", (IOS / "BinarioApp/PlannerView.swift").read_text(encoding="utf-8"))
        self.assertIn("testBikesAllowedComesOnlyFromThePublishedTripValue", (IOS / "BinarioKit/Tests/BinarioKitTests/SQLiteGTFSJourneyRepositoryTests.swift").read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory(prefix="ios-bikes-") as temporary:
            database = Path(temporary) / "schedule.sqlite"
            database.write_bytes(zlib.decompress((IOS / "Resources/binario_gtfs_schedule_v1.sqlite.zlib").read_bytes()))
            connection = sqlite3.connect(f"file:{database}?mode=ro&immutable=1", uri=True)
            try:
                values = dict(connection.execute(
                    "SELECT p.source_id, group_concat(DISTINCT t.bikes_allowed) FROM trip t JOIN pack p ON p.id = t.pack_id GROUP BY p.source_id"
                ).fetchall())
            finally:
                connection.close()
        # STA publishes 1 and 2; feeds without the column (Trenord, ÖBB, SNCF) stay unknown.
        self.assertEqual({"1", "2"}, set(values["alto-adige-sta-gtfs"].split(",")))
        for source in ("lombardia-trenord-gtfs", "oebb-gtfs", "sncf-gtfs", "eav-gtfs"):
            self.assertIsNone(values[source], source)

    def test_airport_links_are_the_android_list_filtered_by_the_ios_index(self) -> None:
        swift = (IOS / "BinarioKit/Sources/BinarioKit/AirportRoutes.swift").read_text(encoding="utf-8")
        android = (ROOT / "app/src/main/java/it/binario/app/data/AirportRoutes.kt").read_text(encoding="utf-8")
        ios_routes = re.findall(r'AirportRoute\(cityStationID: "([^"]+)", airportStationID: "([^"]+)", packs: \[(\w+)\]\)', swift)
        android_routes = re.findall(r'AirportRoute\("curated:([^"]+)", "curated:([^"]+)", setOf\((\w+)\)\)', android)
        self.assertEqual([(city, airport) for city, airport, _ in android_routes], [(city, airport) for city, airport, _ in ios_routes])
        self.assertEqual([pack.lower() for *_, pack in android_routes], [pack.lower() for *_, pack in ios_routes])
        model = (IOS / "BinarioApp/AppModel.swift").read_text(encoding="utf-8")
        self.assertIn("AirportRoutes.links(", model)
        self.assertIn("packsWithTrips: Set(includedPacks.map(\\.sourceID))", model)
        # Every route the iPhone can offer has direct trains both ways in the bundled index.
        packs = {"trenitalia": "trenitalia-gtfs", "trenord": "lombardia-trenord-gtfs", "ferrotramviaria": "ferrotramviaria-bari-barletta"}
        with tempfile.TemporaryDirectory(prefix="ios-airports-") as temporary:
            database = Path(temporary) / "schedule.sqlite"
            database.write_bytes(zlib.decompress((IOS / "Resources/binario_gtfs_schedule_v1.sqlite.zlib").read_bytes()))
            connection = sqlite3.connect(f"file:{database}?mode=ro&immutable=1", uri=True)
            try:
                with_trips = {row[0] for row in connection.execute("SELECT p.source_id FROM pack p WHERE EXISTS (SELECT 1 FROM trip t WHERE t.pack_id = p.id)")}
                covered = {row[0] for row in connection.execute("SELECT DISTINCT catalog_station_id FROM stop WHERE catalog_station_id IS NOT NULL")}
                offered = []
                for city, airport, pack in ios_routes:
                    if packs[pack] not in with_trips or city not in covered or airport not in covered:
                        continue
                    offered.append((city, airport))
                    for origin, destination in ((city, airport), (airport, city)):
                        trips = connection.execute(
                            """
                            SELECT count(DISTINCT o.trip_id) FROM stop_time o
                            JOIN stop_time d ON d.trip_id = o.trip_id AND d.stop_sequence > o.stop_sequence
                            JOIN stop so ON so.id = o.stop_id JOIN stop sd ON sd.id = d.stop_id
                            WHERE so.catalog_station_id = ? AND sd.catalog_station_id = ? AND o.pickup_type = 0 AND d.drop_off_type = 0
                            """,
                            (origin, destination),
                        ).fetchone()[0]
                        self.assertGreater(trips, 0, (origin, destination))
            finally:
                connection.close()
        # Today the iPhone index carries Trenord only: Milano Centrale and Cadorna ↔ Malpensa.
        self.assertEqual([("S01700", "S01139"), ("S01066", "S01139")], offered)

    def test_watch_pin_is_persisted_respects_sharing_and_is_answered_in_the_background(self) -> None:
        model = (IOS / "BinarioApp/AppModel.swift").read_text(encoding="utf-8")
        saved = (IOS / "BinarioApp/SavedRoutesView.swift").read_text(encoding="utf-8")
        app = (IOS / "BinarioApp/BinarioApp.swift").read_text(encoding="utf-8")
        background = (IOS / "BinarioApp/BackgroundActivity.swift").read_text(encoding="utf-8")
        tests = (IOS / "BinarioAppTests/BinarioAppTests.swift").read_text(encoding="utf-8")
        self.assertIn('static let pinnedRouteStorageKey = "it.binario.ios.watch-pinned-route.v1"', model)
        pin = model.split("func publishPinnedRoute(", 1)[1].split("private func unpinWatchRoute()", 1)[0]
        self.assertIn("guard watchSharingEnabled, scheduleState == .available, watchRouteQuery == nil,", pin)
        self.assertIn("publishAvailableSnapshot(snapshot)", pin)
        refresh = model.split("func refreshWatchSnapshot(", 1)[1].split("func openPlanner(for", 1)[0]
        self.assertIn('backgroundActivity?.begin(name: "Binario Watch")', refresh)
        self.assertIn("await publishPinnedRoute(now: now)", refresh)
        self.assertIn("model.setWatchPin(route, pinned: !pinned)", saved)
        self.assertIn('"saved.watch.show"', saved)
        # The model (and with it the Watch Connectivity session) exists before any window, for
        # the background launches Watch Connectivity makes.
        self.assertIn("let model = AppModel.bootstrap()", app.split("init()", 1)[1])
        self.assertIn("UIApplication.shared.beginBackgroundTask(withName: name)", background)
        self.assertNotIn("BGTaskScheduler", background)
        for name in (
            "testPinnedRouteIsPersistedAndDroppedWithItsRoute",
            "testPinnedRouteIsPublishedOnlyWithSharingOnAndYieldsToASearchForNow",
            "testWatchRefreshWithoutASearchForNowPublishesThePinnedRoute",
        ):
            self.assertIn(name, tests)


if __name__ == "__main__":
    unittest.main()
