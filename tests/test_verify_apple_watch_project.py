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
        required_locales = {"it", "en", "de", "fr", "es"}

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
                        self.assertEqual({"one", "other"}, set(plural))
                        self.assertTrue(plural["one"]["stringUnit"]["value"].strip())
                        self.assertTrue(plural["other"]["stringUnit"]["value"].strip())

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

    def test_circular_widget_uses_background_without_an_unsupported_closure(self) -> None:
        source = (APPLE_WATCH / "BinarioWatchWidgets" / "BinarioWatchWidgets.swift").read_text(encoding="utf-8")
        circular = source.split("case .accessoryCircular:", 1)[1].split("case .accessoryInline:", 1)[0]
        self.assertIn("ZStack {", circular)
        self.assertIn("AccessoryWidgetBackground()", circular)
        self.assertNotRegex(circular, r"AccessoryWidgetBackground\s*\{")

    def test_every_watch_localization_key_exists_in_the_catalog(self) -> None:
        catalog = json.loads((APPLE_WATCH / "Resources" / "Localizable.xcstrings").read_text(encoding="utf-8"))
        catalog_bases = {key.split(" %", 1)[0] for key in catalog["strings"]}
        source_paths = [
            APPLE_WATCH / "BinarioWatch" / "WatchHomeView.swift",
            APPLE_WATCH / "BinarioWatch" / "RefreshBinarioIntent.swift",
            APPLE_WATCH / "BinarioWatchWidgets" / "BinarioWatchWidgets.swift",
            APPLE_WATCH / "Shared" / "WatchModels.swift",
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


if __name__ == "__main__":
    unittest.main()
