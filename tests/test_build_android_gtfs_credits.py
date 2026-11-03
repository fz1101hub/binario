#!/usr/bin/env python3
"""The generated Android credits match the GTFS manifests and credit every pack (audit A7-03, proposal P3)."""
from __future__ import annotations

import json
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import build_android_gtfs_credits as credits  # noqa: E402


class AndroidGtfsCreditsTest(unittest.TestCase):
    def test_the_committed_kotlin_file_is_generated_from_the_manifests(self) -> None:
        self.assertEqual(0, credits.main(["--check"]))

    def test_every_pack_of_every_manifest_is_credited_with_its_licensor(self) -> None:
        text = (ROOT / credits.OUTPUT).read_text(encoding="utf-8")
        credited = dict(re.findall(r'GtfsPackCredit\("([^"]+)", "((?:[^"\\]|\\.)*)"\)', text))
        for relative in credits.MANIFESTS:
            path = ROOT / relative
            if not path.is_file():
                continue
            for pack in json.loads(path.read_text(encoding="utf-8"))["packs"]:
                with self.subTest(pack=pack["sourceId"]):
                    self.assertIn(pack["sourceId"], credited)
                    self.assertTrue(credited[pack["sourceId"]].startswith(" ".join(pack["attribution"].split())[:12]))
        # The core packs the audit found missing from Settings › Info carry their licence.
        self.assertIn("CC BY 4.0", credited["oebb-gtfs"])
        self.assertIn("CC BY 4.0", credited["arst-gtfs"])
        self.assertIn("CC BY", credited["toscana-tft-gtfs"])
        self.assertIn("Italian Open Data License", credited["eav-gtfs"])

    def test_licence_is_shown_once_and_never_while_still_to_be_confirmed(self) -> None:
        self.assertEqual("X — CC0 1.0", credits.credit_text("X — CC0", "CC0 1.0"))
        self.assertEqual("X - CC BY 4.0", credits.credit_text("X - CC BY 4.0", "CC BY 4.0"))
        self.assertEqual("X — ODbL", credits.credit_text("X", "ODbL"))
        self.assertEqual("SNCF Open Data", credits.credit_text("SNCF Open Data", "Da confermare dal titolare"))
        self.assertEqual("X", credits.credit_text("X", None))
        with self.assertRaises(ValueError):
            credits.kotlin_string("a\nb")
        self.assertEqual('"a\\"b\\$c"', credits.kotlin_string('a"b$c'))


if __name__ == "__main__":
    unittest.main()
