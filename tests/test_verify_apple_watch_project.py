"""Static invariants for the self-contained, watch-only XcodeGen project."""

from __future__ import annotations

import json
from pathlib import Path
import re
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
        self.assertNotIn("INFOPLIST_KEY_WKCompanionAppBundleIdentifier", settings)
        self.assertTrue(all(target["platform"] == "watchOS" for target in self.targets.values()))
        self.assertIn(
            {"target": "BinarioWatchWidgets", "embed": True},
            application["dependencies"],
        )

    def test_widget_has_the_explicit_widgetkit_extension_point(self) -> None:
        widget = self.targets["BinarioWatchWidgets"]

        self.assertEqual("app-extension", widget["type"])
        self.assertEqual(
            "com.apple.widgetkit-extension",
            widget["settings"]["base"]["INFOPLIST_KEY_NSExtension_NSExtensionPointIdentifier"],
        )

    def test_tests_import_the_application_module_instead_of_compiling_shared_twice(self) -> None:
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
