"""Static invariants for the self-contained, watch-only XcodeGen project."""

from __future__ import annotations

import json
from pathlib import Path
import plistlib
import re
import struct
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
APPLE_WATCH = ROOT / "apple-watch"


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


class AppleWatchProjectTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.project = yaml.safe_load((APPLE_WATCH / "project.yml").read_text(encoding="utf-8"))
        cls.targets = cls.project["targets"]

    def test_watch_only_application_has_a_testable_scheme_and_embeds_widget(self) -> None:
        application = self.targets["BinarioWatch"]

        self.assertEqual(["BinarioWatchTests"], application["scheme"]["testTargets"])
        settings = application["settings"]["base"]
        self.assertTrue(settings["INFOPLIST_KEY_WKApplication"])
        self.assertTrue(settings["INFOPLIST_KEY_WKWatchOnly"])
        self.assertEqual(1, self.project["settings"]["base"]["CURRENT_PROJECT_VERSION"])
        self.assertEqual("0.1.0", self.project["settings"]["base"]["MARKETING_VERSION"])
        # The harness compiles the Watch sources in the same language mode as the store product.
        self.assertEqual(6.0, self.project["settings"]["base"]["SWIFT_VERSION"])
        self.assertIn("harness", (APPLE_WATCH / "project.yml").read_text(encoding="utf-8"))
        self.assertIn("Non va mai inviato ad App Store Connect", (APPLE_WATCH / "APPLE_WATCH_IMPLEMENTATION.md").read_text(encoding="utf-8"))
        self.assertIn("harness watch-only di CI e test", (ROOT / "README.md").read_text(encoding="utf-8"))
        self.assertEqual("$(CURRENT_PROJECT_VERSION)", settings["INFOPLIST_KEY_CFBundleVersion"])
        self.assertEqual("$(MARKETING_VERSION)", settings["INFOPLIST_KEY_CFBundleShortVersionString"])
        self.assertEqual({"debug": {"ENABLE_TESTABILITY": True}}, application["settings"]["configs"])
        self.assertNotIn("INFOPLIST_KEY_WKCompanionAppBundleIdentifier", settings)
        self.assertEqual("AppIcon", settings["ASSETCATALOG_COMPILER_APPICON_NAME"])
        self.assertIs(False, settings["INFOPLIST_KEY_ITSAppUsesNonExemptEncryption"])
        self.assertTrue(all(target["platform"] == "watchOS" for target in self.targets.values()))
        self.assertIn(
            {"target": "BinarioWatchWidgets", "embed": True},
            application["dependencies"],
        )

    def test_watch_targets_have_explicit_nonempty_build_products(self) -> None:
        for name, target in self.targets.items():
            with self.subTest(name=name):
                self.assertEqual(name, target["settings"]["base"]["PRODUCT_NAME"])

    def test_watch_app_icon_is_an_opaque_1024_watchos_catalog_entry_outside_the_widget(self) -> None:
        # The embedded Watch app must ship its own icon (ITMS-90713); the widget extension
        # does not carry one, so the catalogue lives under BinarioWatch/, not Resources/.
        self.assertIn("BinarioWatch", self.targets["BinarioWatch"]["sources"])
        self.assertNotIn("BinarioWatch", self.targets["BinarioWatchWidgets"]["sources"])
        catalog = APPLE_WATCH / "BinarioWatch" / "Assets.xcassets"
        self.assertEqual({"info": {"author": "xcode", "version": 1}}, json.loads((catalog / "Contents.json").read_text(encoding="utf-8")))
        contents = json.loads((catalog / "AppIcon.appiconset" / "Contents.json").read_text(encoding="utf-8"))
        self.assertEqual(
            [{"filename": "AppIcon-1024.png", "idiom": "universal", "platform": "watchos", "size": "1024x1024"}],
            contents["images"],
        )
        header = (catalog / "AppIcon.appiconset" / "AppIcon-1024.png").read_bytes()[:26]
        self.assertTrue(header.startswith(b"\x89PNG\r\n\x1a\n"))
        width, height, bit_depth, colour_type = struct.unpack(">IIBB", header[16:26])
        self.assertEqual((1024, 1024, 8, 2), (width, height, bit_depth, colour_type), "expected an 8-bit RGB PNG without alpha")

    def test_widget_has_the_explicit_widgetkit_extension_point(self) -> None:
        widget = self.targets["BinarioWatchWidgets"]

        self.assertEqual("app-extension", widget["type"])
        settings = widget["settings"]["base"]
        self.assertFalse(settings["GENERATE_INFOPLIST_FILE"])
        self.assertEqual("BinarioWatchWidgets/Info.plist", settings["INFOPLIST_FILE"])
        self.assertNotIn("INFOPLIST_KEY_NSExtension_NSExtensionPointIdentifier", settings)
        plist = plistlib.loads((APPLE_WATCH / settings["INFOPLIST_FILE"]).read_bytes())
        self.assertEqual(
            "com.apple.widgetkit-extension",
            plist["NSExtension"]["NSExtensionPointIdentifier"],
        )
        self.assertIs(False, plist["ITSAppUsesNonExemptEncryption"])

    def test_tests_import_the_application_module_instead_of_compiling_shared_twice(self) -> None:
        settings = self.targets["BinarioWatchTests"]["settings"]["base"]
        self.assertEqual("$(BUILT_PRODUCTS_DIR)/BinarioWatch.app/BinarioWatch", settings["TEST_HOST"])
        self.assertEqual("$(TEST_HOST)", settings["BUNDLE_LOADER"])
        test_sources = self.targets["BinarioWatchTests"]["sources"]
        paths = [source if isinstance(source, str) else source["path"] for source in test_sources]

        self.assertNotIn("Shared", paths)
        self.assertIn("BinarioWatchTests", paths)

    def test_string_catalog_is_included_in_each_watch_target_with_all_required_locales(self) -> None:
        catalog_path = APPLE_WATCH / "Resources" / "Localizable.xcstrings"
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
        # CLDR plural categories per catalogue language: Arabic and Russian need more than
        # one/other, the East and South-East Asian languages only "other".
        plural_categories = {
            "it": {"one", "other"}, "en": {"one", "other"}, "de": {"one", "other"},
            "fr": {"one", "other"}, "es": {"one", "other"},
            "ar": {"zero", "one", "two", "few", "many", "other"}, "hi": {"one", "other"},
            "id": {"other"}, "ja": {"other"}, "ko": {"other"}, "my": {"other"},
            "pt-BR": {"one", "other"}, "ru": {"one", "few", "many", "other"}, "th": {"other"},
            "tr": {"one", "other"}, "vi": {"other"}, "zh-Hans": {"other"},
        }
        required_locales = set(plural_categories)

        self.assertEqual("it", catalog["sourceLanguage"])
        self.assertEqual("1.0", catalog["version"])
        self.assertIn("Resources", self.targets["BinarioWatch"]["sources"])
        self.assertIn("Resources", self.targets["BinarioWatchWidgets"]["sources"])
        self.assertIn("watch.detail.delay %lld", catalog["strings"])

        for key, entry in catalog["strings"].items():
            with self.subTest(key=key):
                self.assertEqual(required_locales, set(entry["localizations"]))
                for locale, translation in entry["localizations"].items():
                    if "stringUnit" in translation:
                        self.assertEqual("translated", translation["stringUnit"]["state"], locale)
                        self.assertTrue(translation["stringUnit"]["value"].strip(), locale)
                    else:
                        plural = translation["variations"]["plural"]
                        self.assertEqual(plural_categories[locale], set(plural), locale)
                        for form in plural.values():
                            self.assertEqual("translated", form["stringUnit"]["state"], locale)
                            self.assertIn("%lld", form["stringUnit"]["value"], locale)

    def test_user_visible_italian_literals_are_catalog_backed(self) -> None:
        source_paths = [
            APPLE_WATCH / "BinarioWatch" / "WatchHomeView.swift",
            APPLE_WATCH / "BinarioWatch" / "RefreshBinarioIntent.swift",
            APPLE_WATCH / "BinarioWatchWidgets" / "BinarioWatchWidgets.swift",
        ]
        source = "\n".join(path.read_text(encoding="utf-8") for path in source_paths)
        model_source = (APPLE_WATCH / "Shared" / "WatchModels.swift").read_text(encoding="utf-8")
        hardcoded_literals = {
            '"Dati non disponibili"',
            '"Dati non disponibili in tempo reale"',
            '"Prossime corse"',
            '"Rileggi cache"',
            '"Aggiorna"',
            '"Richiesta inviata"',
            '"iPhone non raggiungibile"',
            '"Aggiorna Binario"',
            '"Andata"',
            '"Ritorno"',
            '"Qualità non disponibile"',
            '"Nessuna tratta attiva"',
            '"Nessun provider"',
        }

        for literal in hardcoded_literals:
            with self.subTest(literal=literal):
                self.assertNotIn(literal, source)

        for key in (
            "watch.accessibility.route",
            "watch.accessibility.service.withDelay",
            "watch.intent.refresh.title",
            "watch.widget.displayName",
            "watch.detail.delay",
            "watch.freshness.fresh",
            "watch.confidence.operatorPrimary",
        ):
            with self.subTest(key=key):
                self.assertIn(key, source + model_source)

        # These fallback values are intentionally serialized in the existing snapshot
        # contract. They are mapped to catalog-backed display properties before render.
        self.assertIn("displayProviderName", model_source)
        self.assertIn("displayRouteLabel", model_source)
        self.assertIn("displayNotice", model_source)

    def test_rendered_swiftui_text_uses_catalog_keys_not_direct_literals(self) -> None:
        home_source = (APPLE_WATCH / "BinarioWatch" / "WatchHomeView.swift").read_text(encoding="utf-8")
        widget_source = (APPLE_WATCH / "BinarioWatchWidgets" / "BinarioWatchWidgets.swift").read_text(encoding="utf-8")

        # A literal in these rendered text constructors must either be a String Catalog
        # lookup key or a dynamic service value. Product names and SF Symbols are not
        # included in this matcher.
        direct_literal = re.compile(
            r'(?:Text|Section|Button|LabeledContent)\(\s*"(?!watch\.|\\\()'
        )
        self.assertIsNone(direct_literal.search(home_source))
        self.assertIsNone(direct_literal.search(widget_source))
        self.assertIn('navigationTitle("Binario")', home_source)
        self.assertIn('systemImage: "train.side.front.car"', home_source)
        self.assertIn('systemImage: "arrow.clockwise"', home_source)

    def test_programmed_snapshots_are_presented_as_scheduled_with_catalog_backed_status(self) -> None:
        home_source = (APPLE_WATCH / "BinarioWatch" / "WatchHomeView.swift").read_text(encoding="utf-8")
        model_source = (APPLE_WATCH / "Shared" / "WatchModels.swift").read_text(encoding="utf-8")
        tests_source = (APPLE_WATCH / "BinarioWatchTests" / "WatchSnapshotTests.swift").read_text(encoding="utf-8")
        # Programmed snapshots arrive with freshness UNKNOWN and status "PROGRAMMED": the home
        # headline must not be the red realtime warning and the detail must not print the token.
        self.assertIn("var isScheduledOnly: Bool", model_source)
        self.assertIn('WatchLocalization.text("watch.freshness.scheduled")', model_source)
        self.assertIn('WatchLocalization.text("watch.serviceStatus.programmed")', model_source)
        self.assertIn("Text(store.snapshot.displayFreshnessLabel)", home_source)
        self.assertNotIn("Text(store.snapshot.freshness.label)", home_source)
        self.assertIn("if store.snapshot.isScheduledOnly { return .secondary }", home_source)
        self.assertIn("value: service.displayStatus)", home_source)
        self.assertNotIn("value: service.status)", home_source)
        self.assertIn("testProgrammedSnapshotIsPresentedAsScheduledNotAsMissingRealtime", tests_source)

    def test_every_widget_family_adopts_the_widget_container_background(self) -> None:
        # watchOS 10 shows "Please adopt containerBackground API" instead of a widget without one.
        source = (APPLE_WATCH / "BinarioWatchWidgets" / "BinarioWatchWidgets.swift").read_text(encoding="utf-8")
        body = source.split("var body: some View {", 1)[1].split("@ViewBuilder", 1)[0]
        self.assertIn(".containerBackground(for: .widget) { widgetBackground }", body)
        self.assertEqual(1, source.count(".containerBackground(for: .widget)"))
        background = source.split("private var widgetBackground: some View {", 1)[1].split("@ViewBuilder", 1)[0]
        self.assertIn("if family == .accessoryCircular {", background)
        self.assertIn("AccessoryWidgetBackground()", background)
        self.assertIn("Color.clear", background)
        # The background is drawn once, by the container, never inline in a family's content.
        self.assertEqual(1, source.count("AccessoryWidgetBackground()"))
        self.assertNotIn("ZStack", source)
        self.assertNotRegex(source, r"AccessoryWidgetBackground\s*\{")

    def test_widget_timeline_follows_each_departure_and_ends_at_the_expiry(self) -> None:
        widget = (APPLE_WATCH / "BinarioWatchWidgets" / "BinarioWatchWidgets.swift").read_text(encoding="utf-8")
        departures = (APPLE_WATCH / "Shared" / "WatchDepartures.swift").read_text(encoding="utf-8")
        tests = (APPLE_WATCH / "BinarioWatchTests" / "WatchSnapshotTests.swift").read_text(encoding="utf-8")
        timeline = widget.split("func getTimeline(", 1)[1].split("struct BinarioWatchWidget:", 1)[0]
        self.assertIn(".timeline(from: Date())", timeline)
        self.assertIn("policy: .atEnd", timeline)
        self.assertNotIn(".after(", widget)
        self.assertIn('Text("watch.countdown.departsIn \\(Text(departure, style: .relative))")', widget)
        self.assertIn('TimeZone(identifier: "Europe/Rome")', departures)
        self.assertIn("func revalidated(at date: Date) -> BinarioWatchSnapshot", departures)
        self.assertIn("testWidgetTimelineHasAnEntryPerDepartureAndEndsUnavailableAtExpiry", tests)
        self.assertIn("testRevalidationDropsTrainsThatLeftAndExpiresToUnavailable", tests)

    def test_watch_app_revalidates_on_activation_and_at_each_change(self) -> None:
        home = (APPLE_WATCH / "BinarioWatch" / "WatchHomeView.swift").read_text(encoding="utf-8")
        store = (APPLE_WATCH / "BinarioWatch" / "WatchSnapshotStore.swift").read_text(encoding="utf-8")
        tests = (APPLE_WATCH / "BinarioWatchTests" / "WatchSnapshotTests.swift").read_text(encoding="utf-8")
        self.assertIn("@Environment(\\.scenePhase) private var scenePhase", home)
        self.assertIn("if phase == .active { store.sceneDidBecomeActive() }", home)
        self.assertIn("snapshot = stored.revalidated(at: clock())", store)
        self.assertIn("stored.nextChange(after: now)", store)
        self.assertIn("Task.sleep(for: .seconds(delay))", store)
        self.assertIn("testStoreRevalidatesTheCachedSnapshotAgainstTheClock", tests)

    def test_refresh_button_asks_the_iphone_and_reports_the_outcome(self) -> None:
        home = (APPLE_WATCH / "BinarioWatch" / "WatchHomeView.swift").read_text(encoding="utf-8")
        connectivity = (APPLE_WATCH / "BinarioWatch" / "PhoneConnectivity.swift").read_text(encoding="utf-8")
        app = (APPLE_WATCH / "BinarioWatch" / "BinarioWatchApp.swift").read_text(encoding="utf-8")
        tests = (APPLE_WATCH / "BinarioWatchTests" / "WatchSnapshotTests.swift").read_text(encoding="utf-8")
        self.assertIn('WatchLocalization.text("watch.action.refresh")', home)
        self.assertIn("await store.requestPhoneRefresh()", home)
        self.assertIn('WatchLocalization.text("watch.refresh.sent")', home)
        self.assertIn('WatchLocalization.text("watch.refresh.unreachable")', home)
        self.assertIn('static let requestKey = "binario_refresh_request"', connectivity)
        self.assertIn("session.isReachable", connectivity)
        self.assertIn("WCSession.default.sendMessage(", connectivity)
        # The handlers run on a Watch Connectivity queue: they must not be main-actor closures.
        self.assertIn("nonisolated private static func send(", connectivity)
        self.assertIn("WatchSnapshotStore(refresher: WCSessionRefreshRequester())", app)
        self.assertIn("testRefreshTellsWhetherTheIPhoneReceivedTheRequest", tests)

    def test_every_watch_localization_key_exists_in_the_catalog(self) -> None:
        catalog = json.loads((APPLE_WATCH / "Resources" / "Localizable.xcstrings").read_text(encoding="utf-8"))
        catalog_bases = {key.split(" %", 1)[0] for key in catalog["strings"]}
        source_paths = [
            APPLE_WATCH / "BinarioWatch" / "WatchHomeView.swift",
            APPLE_WATCH / "BinarioWatch" / "RefreshBinarioIntent.swift",
            APPLE_WATCH / "BinarioWatchWidgets" / "BinarioWatchWidgets.swift",
            APPLE_WATCH / "Shared" / "WatchModels.swift",
            APPLE_WATCH / "Shared" / "WatchDepartures.swift",
            APPLE_WATCH / "BinarioWatch" / "WatchSnapshotStore.swift",
            APPLE_WATCH / "BinarioWatch" / "PhoneConnectivity.swift",
        ]
        referenced_bases = {
            match.group(1)
            for path in source_paths
            for match in re.finditer(r'"(watch\.[A-Za-z0-9.]+)', path.read_text(encoding="utf-8"))
        }

        self.assertTrue(referenced_bases)
        self.assertSetEqual(referenced_bases, referenced_bases & catalog_bases)

    def test_refresh_reads_storage_before_requesting_its_widget_timeline(self) -> None:
        source = (APPLE_WATCH / "BinarioWatch" / "RefreshBinarioIntent.swift").read_text(encoding="utf-8")
        widget_source = (APPLE_WATCH / "BinarioWatchWidgets" / "BinarioWatchWidgets.swift").read_text(encoding="utf-8")

        self.assertIn("import WidgetKit", source)
        action_body = source.split("enum RefreshBinarioAction", 1)[1].split("struct RefreshBinarioIntent", 1)[0]
        self.assertLess(action_body.index("_ = loadSnapshot()"), action_body.index("reloadTimeline(BinarioWatchWidgetConfiguration.kind)"))
        self.assertIn("WidgetCenter.shared.reloadTimelines(ofKind: kind)", source)
        self.assertIn("let kind = BinarioWatchWidgetConfiguration.kind", widget_source)

    def test_refresh_intent_metadata_is_get_only_for_swift6_concurrency(self) -> None:
        source = (APPLE_WATCH / "BinarioWatch" / "RefreshBinarioIntent.swift").read_text(encoding="utf-8")

        intent_body = source.split("struct RefreshBinarioIntent: AppIntent", 1)[1].split("func perform", 1)[0]
        self.assertRegex(intent_body, r"static\s+var\s+title\s*:\s*LocalizedStringResource\s*\{")
        self.assertRegex(intent_body, r"static\s+var\s+description\s*:\s*IntentDescription\s*\{")
        self.assertNotRegex(intent_body, r"static\s+var\s+title\s*:\s*LocalizedStringResource\s*=")
        self.assertNotRegex(intent_body, r"static\s+var\s+description(?:\s*:\s*IntentDescription)?\s*=")


    def test_binario_2_colour_tokens_are_catalogue_backed_and_tint_the_watch_app(self) -> None:
        # design/BINARIO_2_DESIGN_SYSTEM.md, dark column: the watch is always dark, one value per token.
        catalog = APPLE_WATCH / "BinarioWatch" / "Assets.xcassets"
        expected = {
            "AccentColor": "#9BDD9E",
            "BrandGreen": "#9BDD9E",
            "StatusWarning": "#FFC489",
            "StatusError": "#FFB4AB",
        }
        for name, value in expected.items():
            with self.subTest(colour=name):
                self.assertEqual({"any": value}, colorset_values(catalog, name))
                # Every token is used for text on the black watch background: AA.
                self.assertGreaterEqual(contrast(value, "#000000"), 4.5)
        self.assertEqual("AccentColor", self.targets["BinarioWatch"]["settings"]["base"]["ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME"])

        home = (APPLE_WATCH / "BinarioWatch" / "WatchHomeView.swift").read_text(encoding="utf-8")
        palette = (APPLE_WATCH / "BinarioWatch" / "BinarioPalette.swift").read_text(encoding="utf-8")
        widget = (APPLE_WATCH / "BinarioWatchWidgets" / "BinarioWatchWidgets.swift").read_text(encoding="utf-8")
        self.assertNotRegex(home, r"Color\(\s*red:")
        self.assertNotRegex(home, r"return \.(?:green|yellow|orange|red)\b")
        self.assertIn("case .fresh: return BinarioPalette.brandGreen", home)
        self.assertIn(".foregroundStyle(statusColor)", home)
        for colour in re.findall(r'Color\("([A-Za-z]+)"\)', palette):
            with self.subTest(reference=colour):
                self.assertIn(colour, expected)
        # The widget extension compiles Shared/ and Resources/ only, without this catalogue.
        self.assertNotIn("BinarioPalette", widget)
        self.assertNotIn('Color("', widget)


if __name__ == "__main__":
    unittest.main()
