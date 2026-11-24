#!/usr/bin/env python3
"""contracts/shared-rules/v1/remote-gtfs-v1.json and rfi-programmed-v1.json: well formed, read by the tests they name,
and, for the archives marked ``builder``, refused or accepted by the Python builder's archive reader exactly as the
iPhone's reader does (tools/build_ios_gtfs_schedule.py, ``BoundedGtfsArchive``)."""
from __future__ import annotations

import base64
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import build_ios_gtfs_schedule as builder  # noqa: E402
import generate_shared_code as shared_code  # noqa: E402

REMOTE = ROOT / "contracts/shared-rules/v1/remote-gtfs-v1.json"
RFI = ROOT / "contracts/shared-rules/v1/rfi-programmed-v1.json"
FAILURES = {
    "tooLarge", "malformed", "tooManyEntries", "unsafeName", "duplicateName", "entryTooLarge", "totalTooLarge",
    "ratioTooHigh", "unsupported", "corrupt", "missingFiles",
}


class RemoteGtfsRulesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.rules = json.loads(REMOTE.read_text(encoding="utf-8"))

    def test_the_cases_are_well_formed_and_their_readers_exist(self) -> None:
        for path in (REMOTE, RFI):
            rules = json.loads(path.read_text(encoding="utf-8"))
            with self.subTest(path=path.name):
                self.assertEqual("binario-shared-rules-v1", rules["schema"])
                for reader in rules["readers"].values():
                    self.assertTrue((ROOT / reader).is_file(), reader)
        for case in self.rules["archives"]:
            with self.subTest(case=case["name"]):
                self.assertIn(case["expected"], FAILURES | {"ok"})
                self.assertEqual(case["expected"] == "ok", "files" in case)
                self.assertTrue(base64.b64decode(case["zipBase64"], validate=True))
        for case in self.rules["stationMatches"]:
            ids = [station["id"] for station in case["stations"]]
            with self.subTest(case=case["name"]):
                self.assertTrue(set(case["named"]) <= set(ids))
                self.assertEqual(case["bound"] is None, not case["named"])
                if case["bound"] is not None:
                    self.assertIn(case["bound"], case["named"])

    def test_the_builder_reads_the_archives_it_shares_as_the_iphone_does(self) -> None:
        shared = [case for case in self.rules["archives"] if case["builder"]]
        self.assertGreaterEqual(len(shared), 6)
        with tempfile.TemporaryDirectory() as directory:
            for index, case in enumerate(shared):
                path = Path(directory) / f"case-{index}.zip"
                path.write_bytes(base64.b64decode(case["zipBase64"]))
                with self.subTest(case=case["name"]):
                    try:
                        with builder.BoundedGtfsArchive(path) as archive:
                            names = sorted(archive.names)
                    except builder.BuildError:
                        self.assertNotEqual("ok", case["expected"])
                    else:
                        self.assertEqual("ok", case["expected"])
                        self.assertEqual(case["files"], names)

    def test_the_timetable_cases_use_the_table_first_day(self) -> None:
        table = shared_code.table_values("remote-gtfs-v1.json")
        first = table["feeds"][0]["notBefore"]
        before = [case for case in self.rules["timetables"] if case["departure"][:10] < first]
        self.assertTrue(before)
        for case in before:
            self.assertEqual([], case["expected"], case["name"])


if __name__ == "__main__":
    unittest.main()
