"""Static invariants for the self-contained, watch-only XcodeGen project."""

from __future__ import annotations

from pathlib import Path
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

    def test_refresh_reads_storage_before_requesting_its_widget_timeline(self) -> None:
        source = (APPLE_WATCH / "BinarioWatch" / "RefreshBinarioIntent.swift").read_text(encoding="utf-8")
        widget_source = (APPLE_WATCH / "BinarioWatchWidgets" / "BinarioWatchWidgets.swift").read_text(encoding="utf-8")

        self.assertIn("import WidgetKit", source)
        action_body = source.split("enum RefreshBinarioAction", 1)[1].split("struct RefreshBinarioIntent", 1)[0]
        self.assertLess(action_body.index("_ = loadSnapshot()"), action_body.index("reloadTimeline(BinarioWatchWidgetConfiguration.kind)"))
        self.assertIn("WidgetCenter.shared.reloadTimelines(ofKind: kind)", source)
        self.assertIn("let kind = BinarioWatchWidgetConfiguration.kind", widget_source)


if __name__ == "__main__":
    unittest.main()
