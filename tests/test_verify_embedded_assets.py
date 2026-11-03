#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
import zipfile
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "verify_embedded_assets",
    ROOT / "tools" / "verify_embedded_assets.py",
)
assert SPEC and SPEC.loader
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)
builder = VERIFIER.builder
FIXTURE = ROOT / "tests/fixtures/gtfs"


class VerifyEmbeddedAssetsTest(unittest.TestCase):
    def test_default_freshness_window_is_thirty_days(self) -> None:
        self.assertEqual(30, VERIFIER.DEFAULT_MIN_VALID_DAYS)
        self.assertEqual(30, VERIFIER.parse_args([]).min_valid_days)
        self.assertEqual(30, builder.DEFAULT_MIN_VALID_DAYS)

    def test_explicit_freshness_window_remains_supported(self) -> None:
        self.assertEqual(45, VERIFIER.parse_args(["--min-valid-days", "45"]).min_valid_days)


class SharedFreshnessRuleTest(unittest.TestCase):
    """Audit A7-06: the two release gates disagreed by one day (<= against <)."""

    def test_a_pack_must_run_at_least_the_window_past_the_gate_date(self) -> None:
        end = date(2026, 12, 12)
        self.assertTrue(builder.is_release_fresh(end, date(2026, 11, 12), 30))  # exactly 30 days left
        self.assertFalse(builder.is_release_fresh(end, date(2026, 11, 13), 30))  # 29 days left
        self.assertEqual(date(2026, 12, 13), builder.release_freshness_cutoff(date(2026, 11, 13), 30))
        self.assertTrue(builder.is_release_fresh(end, end, 0))

    def test_a_pack_window_is_its_own_shorter_one_and_never_longer(self) -> None:
        self.assertEqual(30, builder.pack_min_valid_days({}, 30))
        self.assertEqual(14, builder.pack_min_valid_days({"minValidDays": 14}, 30))
        self.assertEqual(14, builder.pack_min_valid_days({"minValidDays": 14}, 45))
        self.assertEqual(30, builder.pack_min_valid_days({"minValidDays": 60}, 30))
        for invalid in (0, -1, True, "14", 1.5):
            with self.subTest(invalid=invalid), self.assertRaises(builder.BuildError):
                builder.pack_min_valid_days({"sourceId": "x", "minValidDays": invalid}, 30)


class GateAgreementTest(unittest.TestCase):
    """build_gtfs_packs.py --check and verify_embedded_assets.py give the same verdict on every day around the edge."""

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="freshness-gates-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        source = self.temp / "input"
        source.mkdir()
        with zipfile.ZipFile(source / "amt-genova-casella-gtfs.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for file in sorted(FIXTURE.iterdir()):
                archive.write(file, file.name)
        (source / "manifest.json").write_text(json.dumps({"generatedAt": "2026-01-01", "packs": [{
            "sourceId": "amt-genova-casella-gtfs", "asset": "gtfs/amt-genova-casella-gtfs.zip",
            "sourceUrl": "https://example.test/amt.zip", "accessMode": "OPEN_DIRECT", "attribution": "fixture",
        }]}), encoding="utf-8")
        self.assets = self.temp / "assets"
        self.manifest = builder.build_packs(source, self.assets / "gtfs", generated_at="2026-01-01")
        self.end = date.fromisoformat(self.manifest["packs"][0]["lastServiceDate"])

    def verdicts(self, today: date) -> tuple[bool, bool]:
        try:
            builder.verify_assets(self.assets / "gtfs", as_of=today, min_valid_days=30)
            built = True
        except builder.BuildError:
            built = False
        failures: list[str] = []
        VERIFIER.verify_gtfs_directory(self.assets, "gtfs", today, 30, failures)
        return built, not failures

    def test_both_gates_pass_with_exactly_thirty_days_left_and_fail_the_day_after(self) -> None:
        last_passing = self.end - timedelta(days=30)
        for offset, expected in ((-1, True), (0, True), (1, False), (2, False)):
            with self.subTest(offset=offset):
                self.assertEqual((expected, expected), self.verdicts(last_passing + timedelta(days=offset)))

    def test_the_verifier_uses_the_calendar_not_a_declared_date(self) -> None:
        manifest_path = self.assets / "gtfs" / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["packs"][0]["lastServiceDate"] = "2099-12-31"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        failures: list[str] = []
        VERIFIER.verify_gtfs_directory(self.assets, "gtfs", self.end, 30, failures)
        self.assertTrue(any("but its calendar ends" in failure for failure in failures), failures)
        self.assertTrue(any("expire" in failure for failure in failures), failures)


if __name__ == "__main__":
    unittest.main()
