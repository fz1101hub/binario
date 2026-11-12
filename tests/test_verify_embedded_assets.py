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
            "licence": "CC BY 4.0", "licenceEvidence": "https://example.test/licence",
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


class StationCatalogPositionsTest(unittest.TestCase):
    """Audit2 tools-data-3: positions in the served area, location ids at their Trenitalia stop, manifest counts."""

    def station(self, station_id: str, latitude: float | None, longitude: float | None, location: str | None = None,
                code: str | None = None) -> dict[str, object]:
        return {"id": station_id, "name": station_id, "railwayCode": code, "trenitaliaLocationId": location,
                "aliases": [], "latitude": latitude, "longitude": longitude, "confidence": "Alta"}

    def test_wrong_positions_codes_and_counts_are_reported(self) -> None:
        stops = {"830002049": [(45.760075, 10.996781)], "830008014": [(41.941186, 12.517655)]}
        stations = [
            self.station("ALA", 45.759017, 10.996775, "830002049", "S02049"),       # 0.1 km from its stop
            self.station("ROMA", 41.941331, 12.517532, "830008014", "S08014"),
            self.station("UNKNOWN", None, None),                                     # unknown stays unknown
            self.station("PARIS", 48.844722, 2.373611),                               # a city its trains reach
        ]
        counts = {"stationCount": 4, "railwayCodes": 2, "uniqueTrenitaliaLocationIds": 2}
        self.assertEqual([], VERIFIER.catalog_problems("c", stations, counts, stops))
        wrong = [
            self.station("FINLAND", 63.103787, 27.820372),
            self.station("SEA", 32.22576, 15.51398),
            self.station("FAR", 45.759017, 10.996775, "830008014", "S02049"),         # Rome's id on the Brennero line
            self.station("TWICE", 41.941331, 12.517532, "830008014"),
            self.station("HALF", 45.0, None),
        ]
        problems = VERIFIER.catalog_problems("c", wrong, {"stationCount": 4, "railwayCodes": 1, "uniqueTrenitaliaLocationIds": 2}, stops)
        self.assertEqual(7, len(problems), problems)
        for fragment in ("FINLAND lies outside the served area", "SEA lies outside the served area",
                         "FAR is 442 km from the Trenitalia stop with its location id 830008014",
                         "TWICE repeats the location id 830008014 of FAR", "HALF has an invalid position",
                         "uniqueTrenitaliaLocationIds mismatch: manifest=2, asset=1", "stationCount"):
            self.assertTrue(any(fragment in problem for problem in problems), (fragment, problems))

    def test_the_shipped_catalogue_passes(self) -> None:
        catalog = json.loads((ROOT / "app/src/main/assets/stations/curated_it_rail_stations.json").read_text(encoding="utf-8"))
        manifest = json.loads((ROOT / "app/src/main/assets/stations/manifest.json").read_text(encoding="utf-8"))["catalogs"][0]
        stops = VERIFIER.trenitalia_stop_positions(ROOT / "app/src/main/assets/gtfs/trenitalia-gtfs.zip")
        self.assertEqual([], VERIFIER.catalog_problems("v4", catalog["stations"], manifest, stops))
        stations = {station["id"]: station for station in catalog["stations"]}
        self.assertEqual(("Ala", "830002049"), (stations["S02049"]["name"], stations["S02049"]["trenitaliaLocationId"]))
        self.assertEqual("Ercolano - Miglio D'Oro", stations["NC001521"]["name"])


class LicenceFieldsTest(unittest.TestCase):
    """Audit A7-03 / P3: every pack carries licensor, licence and evidence; undeclared ones stay personal."""

    def check(self, directory: str, **fields: object) -> tuple[list[str], list[str]]:
        pack = {"attribution": "Operator", "licence": "CC BY 4.0", "licenceEvidence": "https://example.test", **fields}
        pack = {key: value for key, value in pack.items() if value is not None or key == "licence" and "licence" in fields}
        failures: list[str] = []
        warnings: list[str] = []
        VERIFIER.verify_licence(directory, pack, "x", failures, warnings)
        return failures, warnings

    def test_licence_rules(self) -> None:
        self.assertEqual(([], []), self.check("gtfs"))
        failures, _ = self.check("gtfs", licenceEvidence=None)
        self.assertTrue(failures and "licenceEvidence" in failures[0])
        failures, _ = self.check("gtfs-transit", licence=None)
        self.assertTrue(failures)
        failures, warnings = self.check("gtfs", licence="Da confermare dal titolare")
        self.assertEqual([], failures)
        self.assertTrue(warnings and "confirmed by the owner" in warnings[0])
        failures, _ = self.check("gtfs-transit", licence="Non dichiarata dall'operatore")
        self.assertTrue(failures and "undeclared" in failures[0])
        self.assertEqual(([], []), self.check("gtfs-personal", licence="Non dichiarata dall'operatore"))

    def test_the_shipped_manifests_record_a_licence_for_every_pack(self) -> None:
        failures: list[str] = []
        warnings: list[str] = []
        for asset_root, directory, _ in VERIFIER.GTFS_DIRECTORIES:
            path = asset_root / directory / "manifest.json"
            if not path.is_file():
                continue
            for pack in json.loads(path.read_text(encoding="utf-8"))["packs"]:
                VERIFIER.verify_licence(directory, pack, pack["sourceId"], failures, warnings)
        self.assertEqual([], failures)


class StationCatalogProvenanceTest(unittest.TestCase):
    """Audit A7-09: the station catalogue's origin and licence are recorded; until the owner does, a warning."""

    def test_unconfirmed_provenance_is_a_warning_not_a_failure(self) -> None:
        failures: list[str] = []
        warnings: list[str] = []
        VERIFIER.verify_stations(failures, warnings)
        self.assertEqual([], failures)
        manifest = json.loads((VERIFIER.STATION_ROOT / "manifest.json").read_text(encoding="utf-8"))["catalogs"][0]
        for field in ("provenance", "licence"):
            with self.subTest(field=field):
                self.assertIn(field, manifest)
                if VERIFIER.OWNER_TO_CONFIRM in manifest[field].lower():
                    self.assertTrue(any(f"{field} to be confirmed by the owner" in warning for warning in warnings))


if __name__ == "__main__":
    unittest.main()
