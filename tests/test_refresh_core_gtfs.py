#!/usr/bin/env python3
"""Offline tests for tools/refresh_core_gtfs.py (the core-packs job) and the refresh-core selection token."""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import re
import shutil
import sys
import tempfile
import unittest
import urllib.request
import zipfile
from datetime import date
from pathlib import Path
from typing import Any
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import acquire_transit_sources as acquire  # noqa: E402
import build_gtfs_packs as builder  # noqa: E402
import refresh_core_gtfs as refresh  # noqa: E402

OLD_COMMIT = "a" * 40
NEW_COMMIT = "b" * 40


def gtfs_zip(
    path: Path,
    *,
    trips: int = 4,
    end: str = "20271231",
    bus_end: str = "20271231",
    feed_version: str | None = None,
) -> bytes:
    """A small multimodal GTFS: ``trips`` rail trips (route_type 2) and one bus trip that every filter drops."""
    tables = {
        "agency.txt": "agency_id,agency_name,agency_url,agency_timezone\nA,Demo Rail,https://example.test,Europe/Rome\n",
        "stops.txt": "stop_id,stop_name,stop_lat,stop_lon\nS1,Alfa,45.0,9.0\nS2,Beta,45.1,9.1\nB1,Bus stop,45.2,9.2\n",
        "routes.txt": "route_id,agency_id,route_short_name,route_long_name,route_type\nR1,A,R,Alfa - Beta,2\nBUS,A,1,Bus,3\n",
        "trips.txt": "route_id,service_id,trip_id,trip_short_name\n"
        + "".join(f"R1,RAIL,t{index},{100 + index}\n" for index in range(trips))
        + "BUS,BUSSVC,bus,1\n",
        "stop_times.txt": "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n"
        + "".join(
            f"t{index},08:{index:02d}:00,08:{index:02d}:00,S1,1\nt{index},09:{index:02d}:00,09:{index:02d}:00,S2,2\n"
            for index in range(trips)
        )
        + "bus,10:00:00,10:00:00,B1,1\n",
        "calendar.txt": "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
        f"RAIL,1,1,1,1,1,1,1,20260101,{end}\nBUSSVC,1,1,1,1,1,1,1,20260101,{bus_end}\n",
    }
    if feed_version is not None:
        tables["feed_info.txt"] = (
            "feed_publisher_name,feed_publisher_url,feed_lang,feed_version\n"
            f"Demo converter,https://example.test,it,{feed_version}\n"
        )
        tables["attributions.txt"] = "attribution_id,organization_name,is_producer\nsrc,Demo operator,1\n"
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, text in tables.items():
            archive.writestr(name, text)
    return path.read_bytes()


def trenitalia_france_zip(path: Path) -> bytes:
    """A synthetic feed shaped like Trenitalia France's: two Frecciarossa Paris - Milano trips (route_type 2) and a coach
    (route_type 3) the rail filter drops.  Names, ids and coordinates are fixture values, not the publisher's data."""
    calls = (("PLY", "Paris Gare de Lyon", "08:00"), ("LPD", "Lyon Part-Dieu", "10:00"), ("MOD", "Modane", "13:00"),
             ("OUX", "Oulx", "13:40"), ("TPS", "Torino Porta Susa", "14:40"), ("MIC", "Milano Centrale", "15:50"))
    tables = {
        "agency.txt": "agency_id,agency_name,agency_url,agency_timezone\nTF,Trenitalia France,https://example.test,Europe/Paris\n",
        "stops.txt": "stop_id,stop_name,stop_lat,stop_lon\n" + "".join(
            f"{code},{name},{45.0 + index / 10:.1f},{6.0 + index / 10:.1f}\n" for index, (code, name, _) in enumerate(calls)),
        "routes.txt": "route_id,agency_id,route_short_name,route_long_name,route_type\nFR,TF,FR,Paris - Milano,2\nCAR,TF,CAR,Navette,3\n",
        "trips.txt": "route_id,service_id,trip_id,trip_short_name\nFR,DAILY,FR9241,9241\nFR,DAILY,FR9247,9247\nCAR,DAILY,CAR1,1\n",
        "stop_times.txt": "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" + "".join(
            f"{trip},{hour + shift:02d}{when[2:]}:00,{hour + shift:02d}{when[2:]}:00,{code},{index + 1}\n"
            for trip, shift in (("FR9241", 0), ("FR9247", 6))
            for index, (code, _, when) in enumerate(calls) for hour in (int(when[:2]),)
        ) + "CAR1,09:00:00,09:00:00,MOD,1\nCAR1,09:30:00,09:30:00,OUX,2\n",
        "calendar.txt": "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
        "DAILY,1,1,1,1,1,1,1,20261101,20271211\n",
    }
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, text in tables.items():
            archive.writestr(name, text)
    return path.read_bytes()


def candidate(source_id: str = "trenitalia-france-gtfs") -> dict[str, Any]:
    """A reviewed candidate as tools/core_pack_candidates.json records it (fixture URLs)."""
    return {
        "sourceId": source_id,
        "asset": f"gtfs/{source_id}.zip",
        "sourceUrl": f"https://example.test/{source_id}/resource",
        "accessMode": "OPEN_DIRECT",
        "attribution": "Trenitalia France",
        "attributionDated": True,
        "licence": "Licence Ouverte / Open Licence 2.0 (etalab-2.0)",
        "licenceEvidence": f"https://example.test/{source_id}",
        "notes": "fixture candidate",
        "landingPage": f"https://example.test/{source_id}",
        "iosAndroidOnlyReason": "fixture: Android-only",
    }


def pinned_url(commit: str) -> str:
    return f"https://raw.githubusercontent.com/example/pinned-gtfs/{commit}/gtfs.zip"


def core_directory(root: Path) -> tuple[Path, dict[str, bytes]]:
    """A published core-like directory of four packs, as build_gtfs_packs.py writes it."""
    sources = root / "sources"
    sources.mkdir(parents=True)
    archives = {
        "plain-gtfs": gtfs_zip(sources / "plain.zip"),
        "oebb-gtfs": gtfs_zip(sources / "oebb.zip"),
        "pinned-gtfs": gtfs_zip(sources / "pinned.zip", feed_version="2026-10-25"),
        "broken-gtfs": gtfs_zip(sources / "broken.zip"),
    }
    packs: list[dict[str, Any]] = []
    for source_id in archives:
        pack: dict[str, Any] = {
            "sourceId": source_id,
            "asset": f"gtfs/{source_id}.zip",
            "sourceUrl": f"https://example.test/{source_id}.zip",
            "accessMode": "OPEN_DIRECT",
            "attribution": f"{source_id} attribution",
            "notes": "fixture",
        }
        if source_id == "pinned-gtfs":
            pack["sourceUrl"] = pinned_url(OLD_COMMIT)
            pack["notes"] = "Converted weekly. Pinned to the upstream commit of 2026-10-25. Scheduled times only."
            pack["upstream"] = {
                "kind": "github-commit",
                "repository": "https://github.com/example/pinned-gtfs",
                "commit": OLD_COMMIT,
                "path": "gtfs.zip",
                "archiveSha256": hashlib.sha256(archives[source_id]).hexdigest(),
                "licence": "CC BY 4.0",
                "officialDataset": "https://example.test/dataset",
            }
        packs.append(pack)
    registry = root / "registry.json"
    registry.write_text(json.dumps({"generatedAt": "2026-10-01", "purpose": "fixture", "packs": packs}), encoding="utf-8")
    published = root / "gtfs"
    builder.build_packs(
        sources, published, manifest_path=registry, generated_at="2026-10-01",
        input_overrides={source_id: sources / f"{source_id.split('-')[0]}.zip" for source_id in archives},
    )
    return published, archives


class FakeDownloads:
    """Serves fixed bytes per URL like acquire_transit_sources.download, and records what was asked."""

    def __init__(self, payloads: dict[str, bytes], failing: set[str] | None = None) -> None:
        self.payloads = payloads
        self.failing = failing or set()
        self.urls: list[str] = []

    def __call__(self, url: str, target: Path) -> dict[str, Any]:
        self.urls.append(url)
        if url in self.failing:
            raise acquire.AcquisitionError(f"download fallito {url}: HTTP Error 404: Not Found")
        data = self.payloads[url]
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        return {"url": url, "finalUrl": url, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


class SelectionTest(unittest.TestCase):
    def test_refresh_core_token_next_to_the_transit_tokens(self) -> None:
        self.assertEqual((False, [], []), acquire.selection_from("build=all refresh-core=all"))
        self.assertEqual((True, None, ["sncf-gtfs", "oebb-gtfs"]), acquire.selection_from("validate-netex refresh-core=sncf-gtfs,oebb-gtfs"))
        self.assertEqual((False, ["milano-atm-gtfs"], None), acquire.selection_from("build=milano-atm-gtfs"))
        for text in ("refresh-core=", "refresh-core=,", "refresh-core", "refresh=all"):
            with self.subTest(text=text), self.assertRaises(acquire.AcquisitionError):
                acquire.selection_from(text)


class RefreshCoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="refresh-core-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        self.published, self.archives = core_directory(self.temp / "repo")
        self.before = {path.name: path.read_bytes() for path in self.published.iterdir()}
        self.manifest = json.loads((self.published / "manifest.json").read_text(encoding="utf-8"))

    def resolve(self, upstream: dict[str, Any]) -> dict[str, Any]:
        self.assertEqual("gtfs.zip", upstream["path"])
        return {"commit": NEW_COMMIT, "commitDate": "2026-11-02", "resolvedBy": "github-api"}

    def run_refresh(self, payloads: dict[str, bytes], *, selection: list[str] | None = None, failing: set[str] | None = None) -> tuple[dict[str, Any], FakeDownloads]:
        fetch = FakeDownloads(payloads, failing)
        with contextlib.redirect_stdout(io.StringIO()):
            report = refresh.refresh(selection or [], self.published, self.temp / "out", "2026-11-03", fetch=fetch, resolve=self.resolve)
        return report, fetch

    def test_changed_failed_manual_and_pinned_packs(self) -> None:
        sources = self.temp / "new"
        sources.mkdir()
        payloads = {
            "https://example.test/plain-gtfs.zip": gtfs_zip(sources / "plain.zip", end="20281231"),
            pinned_url(NEW_COMMIT): gtfs_zip(sources / "pinned.zip", feed_version="2026-11-01"),
        }
        report, fetch = self.run_refresh(payloads, failing={"https://example.test/broken-gtfs.zip"})
        status = {source_id: entry["status"] for source_id, entry in report["packs"].items()}
        self.assertEqual({"plain-gtfs": "changed", "oebb-gtfs": "manual", "pinned-gtfs": "changed", "broken-gtfs": "failed"}, status)
        # ÖBB is never downloaded; the pinned pack comes from the latest upstream commit, not the pinned URL.
        self.assertNotIn("https://example.test/oebb-gtfs.zip", fetch.urls)
        self.assertIn(pinned_url(NEW_COMMIT), fetch.urls)
        self.assertEqual(["plain-gtfs", "pinned-gtfs"], report["changed"])
        self.assertEqual(["broken-gtfs"], report["failed"])
        self.assertIn("404", report["packs"]["broken-gtfs"]["error"])

        staging = self.temp / "out/staging/gtfs"
        builder.verify_assets(staging, as_of=None, min_valid_days=None)
        packs = {pack["sourceId"]: pack for pack in json.loads((staging / "manifest.json").read_text(encoding="utf-8"))["packs"]}
        self.assertEqual("2028-12-31", packs["plain-gtfs"]["lastServiceDate"])
        # The failed and the manual pack keep their previous bytes and manifest entries.
        for source_id in ("broken-gtfs", "oebb-gtfs"):
            self.assertEqual(self.before[f"{source_id}.zip"], (staging / f"{source_id}.zip").read_bytes())
            self.assertEqual(next(p for p in self.manifest["packs"] if p["sourceId"] == source_id), packs[source_id])
        # The proposed pin: commit, archive digest and raw URL of that commit, with the note's date.
        proposed = report["packs"]["pinned-gtfs"]["proposedUpstream"]
        self.assertEqual(NEW_COMMIT, proposed["commit"])
        self.assertEqual(OLD_COMMIT, proposed["previousCommit"])
        self.assertEqual(hashlib.sha256(payloads[pinned_url(NEW_COMMIT)]).hexdigest(), proposed["archiveSha256"])
        self.assertEqual(NEW_COMMIT, packs["pinned-gtfs"]["upstream"]["commit"])
        self.assertEqual(pinned_url(NEW_COMMIT), packs["pinned-gtfs"]["sourceUrl"])
        self.assertIn("Pinned to the upstream commit of 2026-11-02.", packs["pinned-gtfs"]["notes"])
        self.assertIn("2026-11-01", report["packs"]["pinned-gtfs"]["upstreamMetadata"]["feed_info.txt"])
        self.assertIn("Demo operator", report["packs"]["pinned-gtfs"]["upstreamMetadata"]["attributions.txt"])

        publish = self.temp / "out/publish"
        self.assertEqual(
            {"SHA256SUMS", "manifest.json", "plain-gtfs.zip", "pinned-gtfs.zip"},
            {path.name for path in (publish / "gtfs").iterdir()},
        )
        self.assertEqual(report, json.loads((publish / "core-refresh.json").read_text(encoding="utf-8")))
        markdown = refresh.report_markdown(report)
        self.assertIn("| plain-gtfs | aggiornato | 2027-12-31 → 2028-12-31 |", markdown)
        self.assertIn("NON aggiornato", markdown)
        # The published directory of the checkout is never touched.
        self.assertEqual(self.before, {path.name: path.read_bytes() for path in self.published.iterdir()})

    def test_feed_text_in_the_job_summary_cannot_render_links_images_or_html(self) -> None:
        # A BuildError carries stop_ids from the feed (stop_times riferisce stop assenti: [...]): in the public job
        # summary it must stay text, whatever the publisher puts there.
        hostile = ("BuildError: stop_times riferisce stop assenti: ['![x](https://tracker.example/p.png)', "
                   "'[Download fix](https://evil.example)', '<img src=x onerror=alert(1)>', 'a|b', '`code`', "
                   "'https://bare.example/x', '**bold**']\nsecond line")
        report = {"directory": "gtfs", "generatedAt": "2026-11-10", "packs": {
            "plain-gtfs": {"status": "failed", "error": hostile, "previous": {"lastServiceDate": "2026-12-12"}},
            "[x](https://evil.example)": {"status": "<b>changed</b>", "previous": {}},
        }}
        lines = refresh.report_markdown(report).splitlines()
        row = next(line for line in lines if line.startswith("| plain-gtfs |"))
        note = row.split(" | ")[-1].removesuffix(" |")
        # The feed text is one code span: no link, image, HTML or emphasis is rendered inside it, and the row keeps
        # its five columns.
        self.assertTrue(note.startswith("`") and note.endswith("`") and note.count("`") == 2, note)
        self.assertEqual(6, len(re.findall(r"(?<!\\)\|", row)), row)
        self.assertNotIn("\n", row)
        other = next(line for line in lines if line.startswith("| \\[x\\](https://evil.example) |"))
        self.assertIn("&lt;b&gt;changed&lt;/b&gt;", other)

    def test_identical_archive_and_identical_filtered_content_keep_the_pack_byte_for_byte(self) -> None:
        sources = self.temp / "new"
        sources.mkdir()
        payloads = {
            # Same bytes as the published source archive: not rebuilt.
            "https://example.test/plain-gtfs.zip": self.archives["plain-gtfs"],
            # A new archive whose only change is a bus the rail filter drops.
            "https://example.test/broken-gtfs.zip": gtfs_zip(sources / "broken.zip", bus_end="20291231"),
            pinned_url(NEW_COMMIT): self.archives["pinned-gtfs"],
        }
        report, _ = self.run_refresh(payloads, selection=["plain-gtfs", "broken-gtfs", "pinned-gtfs"])
        status = {source_id: entry["status"] for source_id, entry in report["packs"].items()}
        self.assertEqual(
            {"plain-gtfs": "unchanged", "broken-gtfs": "content-unchanged", "pinned-gtfs": "unchanged", "oebb-gtfs": "not-selected"},
            status,
        )
        staging = self.temp / "out/staging/gtfs"
        self.assertEqual(self.before, {path.name: path.read_bytes() for path in staging.iterdir()})
        self.assertEqual([], report["changed"])
        self.assertEqual(["core-refresh.json", "gtfs"], sorted(path.name for path in (self.temp / "out/publish").iterdir()))
        self.assertEqual([], list((self.temp / "out/publish/gtfs").iterdir()))

    def test_a_rebuild_failure_keeps_the_previous_pack(self) -> None:
        sources = self.temp / "new"
        sources.mkdir()
        archive = sources / "no-rail.zip"
        with zipfile.ZipFile(archive, "w") as output:
            with zipfile.ZipFile(io.BytesIO(self.archives["plain-gtfs"])) as source:
                for name in source.namelist():
                    data = source.read(name)
                    if name == "routes.txt":
                        data = data.replace(b",2\n", b",3\n")
                    output.writestr(name, data)
        report, _ = self.run_refresh({"https://example.test/plain-gtfs.zip": archive.read_bytes()}, selection=["plain-gtfs"])
        self.assertEqual("failed", report["packs"]["plain-gtfs"]["status"])
        staging = self.temp / "out/staging/gtfs"
        self.assertEqual(self.before, {path.name: path.read_bytes() for path in staging.iterdir()})
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, refresh.main(["--fail-on-report", str(self.temp / "out/publish/core-refresh.json")]))

    def with_next_timetable(self, start: str) -> dict[str, str]:
        """plain-gtfs registered like the Swiss pack: this year's permalink, the next year's from ``start``."""
        following = {"from": start, "sourceUrl": "https://example.test/plain-2027/permalink",
                     "landingPage": "https://example.test/plain-2027"}
        for pack in self.manifest["packs"]:
            if pack["sourceId"] == "plain-gtfs":
                pack["nextTimetable"] = following
        builder.write_json(self.published / "manifest.json", self.manifest)
        builder.read_manifest(self.published / "manifest.json")
        self.before = {path.name: path.read_bytes() for path in self.published.iterdir()}
        return following

    def test_the_next_timetable_is_downloaded_from_its_reviewed_first_day(self) -> None:
        following = self.with_next_timetable("2026-12-13")
        sources = self.temp / "new"
        sources.mkdir()
        current = gtfs_zip(sources / "current.zip", end="20261212")
        payloads = {"https://example.test/plain-gtfs.zip": current, following["sourceUrl"]: gtfs_zip(sources / "next.zip", end="20271211")}
        # Before the switch: this year's source, and the entry keeps the next one for later.
        report, fetch = self.run_refresh(payloads, selection=["plain-gtfs"])
        self.assertEqual(["https://example.test/plain-gtfs.zip"], fetch.urls)
        self.assertNotIn("timetableSwitch", report["packs"]["plain-gtfs"])
        staged = {pack["sourceId"]: pack for pack in json.loads((self.temp / "out/staging/gtfs/manifest.json").read_text(encoding="utf-8"))["packs"]}
        self.assertEqual("2026-12-12", staged["plain-gtfs"]["lastServiceDate"])
        self.assertEqual(following, staged["plain-gtfs"]["nextTimetable"])
        # From the first day of the next timetable: its source, adopted in the entry, and the field is spent.
        fetch = FakeDownloads(payloads)
        with contextlib.redirect_stdout(io.StringIO()):
            report = refresh.refresh(["plain-gtfs"], self.published, self.temp / "out", "2026-12-13", fetch=fetch, resolve=self.resolve)
        self.assertEqual([following["sourceUrl"]], fetch.urls)
        self.assertEqual("changed", report["packs"]["plain-gtfs"]["status"])
        self.assertEqual(
            {"previousSourceUrl": "https://example.test/plain-gtfs.zip", **following},
            report["packs"]["plain-gtfs"]["timetableSwitch"],
        )
        staged = {pack["sourceId"]: pack for pack in json.loads((self.temp / "out/staging/gtfs/manifest.json").read_text(encoding="utf-8"))["packs"]}
        self.assertEqual(following["sourceUrl"], staged["plain-gtfs"]["sourceUrl"])
        self.assertNotIn("nextTimetable", staged["plain-gtfs"])
        self.assertEqual("2027-12-11", staged["plain-gtfs"]["lastServiceDate"])
        self.assertIn("orario successivo dal 2026-12-13", refresh.report_markdown(report))
        self.assertEqual(self.before, {path.name: path.read_bytes() for path in self.published.iterdir()})

    def test_a_permalink_names_the_file_it_resolved_to_in_the_summary(self) -> None:
        sources = self.temp / "new"
        sources.mkdir()

        def resolved(url: str, target: Path) -> dict[str, Any]:
            record = FakeDownloads({url: gtfs_zip(sources / "plain.zip", end="20281231")})(url, target)
            return {**record, "finalUrl": "https://example.test/download/gtfs_fp2026_2026-11-20.zip"}

        with contextlib.redirect_stdout(io.StringIO()):
            report = refresh.refresh(["plain-gtfs"], self.published, self.temp / "out", "2026-11-23", fetch=resolved, resolve=self.resolve)
        self.assertIn("da https://example.test/download/gtfs_fp2026_2026-11-20.zip", refresh.report_markdown(report))

    def test_a_next_timetable_must_be_reviewed_https_and_differ_from_the_current_source(self) -> None:
        base = {"sourceId": "x", "asset": "gtfs/x.zip", "sourceUrl": "https://example.test/x"}
        good = {"from": "2026-12-13", "sourceUrl": "https://example.test/y", "landingPage": "https://example.test/y-page"}
        builder.validate_next_timetable("x", {**base, "nextTimetable": good})
        for broken in (
            {**good, "from": "13.12.2026"},
            {**good, "sourceUrl": "http://example.test/y"},
            {**good, "sourceUrl": base["sourceUrl"]},
            {key: value for key, value in good.items() if key != "landingPage"},
            {**good, "extra": "x"},
        ):
            with self.subTest(broken=broken), self.assertRaises(builder.BuildError):
                builder.validate_next_timetable("x", {**base, "nextTimetable": broken})
        self.assertIsNone(builder.timetable_switch({**base, "nextTimetable": good}, date(2026, 12, 12)))
        self.assertEqual("https://example.test/y", builder.timetable_switch({**base, "nextTimetable": good}, date(2026, 12, 13))["sourceUrl"])
        self.assertIsNone(builder.timetable_switch(base, date(2027, 1, 1)))

    def test_a_candidate_is_built_only_when_named_and_joins_the_manifest_in_order(self) -> None:
        sources = self.temp / "new"
        sources.mkdir()
        candidates = {"trenitalia-france-gtfs": candidate()}
        url = candidates["trenitalia-france-gtfs"]["sourceUrl"]
        payloads = {url: trenitalia_france_zip(sources / "tf.zip")}
        # refresh-core=all refreshes the bundled packs only: adding a pack is never implicit.
        fetch = FakeDownloads({**payloads, "https://example.test/plain-gtfs.zip": self.archives["plain-gtfs"],
                               "https://example.test/broken-gtfs.zip": self.archives["broken-gtfs"],
                               pinned_url(NEW_COMMIT): self.archives["pinned-gtfs"]})
        with contextlib.redirect_stdout(io.StringIO()):
            report = refresh.refresh([], self.published, self.temp / "out", "2026-11-24", fetch=fetch, resolve=self.resolve, candidates=candidates)
        self.assertNotIn(url, fetch.urls)
        self.assertNotIn("trenitalia-france-gtfs", report["packs"])
        # Named, it is downloaded, built alone (rail only) and printed with the manifest that lists it.
        fetch = FakeDownloads(payloads)
        with contextlib.redirect_stdout(io.StringIO()):
            report = refresh.refresh(["trenitalia-france-gtfs"], self.published, self.temp / "out", "2026-11-24",
                                     fetch=fetch, resolve=self.resolve, candidates=candidates)
        self.assertEqual([url], fetch.urls)
        self.assertEqual("added", report["packs"]["trenitalia-france-gtfs"]["status"])
        self.assertEqual(["trenitalia-france-gtfs"], report["added"])
        self.assertEqual(["trenitalia-france-gtfs"], report["changed"])
        staging = self.temp / "out/staging/gtfs"
        builder.verify_assets(staging, as_of=None, min_valid_days=None)
        manifest = json.loads((staging / "manifest.json").read_text(encoding="utf-8"))
        # Before the first pack after it in sourceId order (none here: last); the others keep their order.
        self.assertEqual([pack["sourceId"] for pack in self.manifest["packs"]] + ["trenitalia-france-gtfs"],
                         [pack["sourceId"] for pack in manifest["packs"]])
        self.assertEqual(["a", "b", "c"], [pack["sourceId"] for pack in refresh.with_pack_in_order([{"sourceId": "a"}, {"sourceId": "c"}], {"sourceId": "b"})])
        entry = next(pack for pack in manifest["packs"] if pack["sourceId"] == "trenitalia-france-gtfs")
        self.assertEqual("Trenitalia France — dati del 2026-11-24", entry["attribution"])
        self.assertEqual("2027-12-11", entry["lastServiceDate"])
        self.assertNotIn("landingPage", entry)
        self.assertNotIn("iosAndroidOnlyReason", entry)
        self.assertEqual(2, refresh.pack_counts(staging / "trenitalia-france-gtfs.zip")["trips"])
        self.assertEqual(
            {"SHA256SUMS", "manifest.json", "trenitalia-france-gtfs.zip"},
            {path.name for path in (self.temp / "out/publish/gtfs").iterdir()},
        )
        self.assertIn("| trenitalia-france-gtfs | nuovo (candidato) | 2027-12-11 | 2 |", refresh.report_markdown(report))
        # The other packs keep their bytes; the checkout is never touched.
        for name, data in self.before.items():
            if name not in ("manifest.json", "SHA256SUMS"):
                self.assertEqual(data, (staging / name).read_bytes(), name)
        self.assertEqual(self.before, {path.name: path.read_bytes() for path in self.published.iterdir()})

    def test_an_identical_archive_is_filtered_again_when_the_published_pack_fails_the_current_rules(self) -> None:
        # Trenitalia France as merged in #111: UTC times (here a winter-only calendar), built before the time-zone rule,
        # so --check refuses it now.
        sources = self.temp / "tf"
        sources.mkdir()
        trenitalia_france_zip(sources / "tf.zip")
        with zipfile.ZipFile(sources / "tf-utc.zip", "w") as archive_out, zipfile.ZipFile(sources / "tf.zip") as archive_in:
            for name in archive_in.namelist():
                data = archive_in.read(name)
                if name == "agency.txt":
                    data = data.replace(b"Europe/Paris", b"UTC")
                if name == "calendar.txt":
                    data = data.replace(b"20271211", b"20261211")
                archive_out.writestr(name, data)
        utc = (sources / "tf-utc.zip").read_bytes()
        candidates = {"trenitalia-france-gtfs": candidate()}
        url = candidates["trenitalia-france-gtfs"]["sourceUrl"]
        with mock.patch.object(builder, "LOCAL_TIME_SOURCES", frozenset()), \
                mock.patch.object(builder, "TRAIN_NUMBER_ROUTE_ID_SOURCES", frozenset()), \
                mock.patch.object(builder, "verify_local_times", lambda *args: None), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            refresh.refresh(["trenitalia-france-gtfs"], self.published, self.temp / "old", "2026-11-23",
                            fetch=FakeDownloads({url: utc}), resolve=self.resolve, candidates=candidates)
        old = self.temp / "old/staging/gtfs"
        with self.assertRaisesRegex(builder.BuildError, "orari non portati in Europe/Rome"):
            builder.verify_filter_from_asset(old / "trenitalia-france-gtfs.zip", "trenitalia-france-gtfs")
        # The same archive again: rebuilt with the current rules, not kept as "unchanged".
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            report = refresh.refresh(["trenitalia-france-gtfs"], old, self.temp / "out", "2026-11-24",
                                     fetch=FakeDownloads({url: utc}), resolve=self.resolve, candidates={})
        self.assertEqual("changed", report["packs"]["trenitalia-france-gtfs"]["status"])
        staging = self.temp / "out/staging/gtfs"
        builder.verify_assets(staging, as_of=None, min_valid_days=None)
        with zipfile.ZipFile(staging / "trenitalia-france-gtfs.zip") as archive:
            metadata = json.loads(archive.read(builder.FILTER_MANIFEST_MEMBER))
            agency = archive.read("agency.txt").decode()
        self.assertEqual("UTC", metadata[builder.LOCAL_TIMES_FROM_FIELD])
        self.assertIn(builder.APP_TIMEZONE, agency)
        # A pack that passes the current rules is still kept as it is for the same archive.
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            again = refresh.refresh(["trenitalia-france-gtfs"], staging, self.temp / "again", "2026-11-25",
                                    fetch=FakeDownloads({url: utc}), resolve=self.resolve, candidates={})
        self.assertEqual("unchanged", again["packs"]["trenitalia-france-gtfs"]["status"])

    def test_an_identical_archive_is_filtered_again_when_the_published_pack_misses_a_reviewed_rule(self) -> None:
        # Trenitalia France as built before its route categories and out-of-order trips: it still passes --check, so CI
        # stays green, and the weekly refresh filters the same archive again with the rules.
        sources = self.temp / "tf"
        sources.mkdir()
        data = trenitalia_france_zip(sources / "tf.zip")
        candidates = {"trenitalia-france-gtfs": candidate()}
        url = candidates["trenitalia-france-gtfs"]["sourceUrl"]
        with mock.patch.object(builder, "ROUTE_CATEGORY_SOURCES", {}), mock.patch.object(builder, "OUT_OF_ORDER_SOURCES", frozenset()), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            refresh.refresh(["trenitalia-france-gtfs"], self.published, self.temp / "old", "2026-11-23",
                            fetch=FakeDownloads({url: data}), resolve=self.resolve, candidates=candidates)
        old = self.temp / "old/staging/gtfs"
        builder.verify_filter_from_asset(old / "trenitalia-france-gtfs.zip", "trenitalia-france-gtfs")
        self.assertEqual([builder.ROUTE_CATEGORY_RULE, builder.OUT_OF_ORDER_RULE],
                         builder.missing_reviewed_rules(old / "trenitalia-france-gtfs.zip", "trenitalia-france-gtfs"))
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            report = refresh.refresh(["trenitalia-france-gtfs"], old, self.temp / "out", "2026-11-24",
                                     fetch=FakeDownloads({url: data}), resolve=self.resolve, candidates={})
        self.assertEqual("changed", report["packs"]["trenitalia-france-gtfs"]["status"])
        staging = self.temp / "out/staging/gtfs"
        builder.verify_assets(staging, as_of=None, min_valid_days=None)
        with zipfile.ZipFile(staging / "trenitalia-france-gtfs.zip") as archive:
            metadata = json.loads(archive.read(builder.FILTER_MANIFEST_MEMBER))
            routes = archive.read("routes.txt").decode()
        self.assertEqual(0, metadata[builder.OUT_OF_ORDER_COUNT_FIELD])
        self.assertIn("FR,TF,FR,Frecciarossa,2", routes)
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            again = refresh.refresh(["trenitalia-france-gtfs"], staging, self.temp / "again", "2026-11-25",
                                    fetch=FakeDownloads({url: data}), resolve=self.resolve, candidates={})
        self.assertEqual("unchanged", again["packs"]["trenitalia-france-gtfs"]["status"])

    def test_the_candidate_registry_is_reviewed(self) -> None:
        shipped = refresh.load_candidates()
        for source_id, pack in shipped.items():
            self.assertTrue(pack["sourceUrl"].startswith("https://"), source_id)
            self.assertNotIn(source_id, [entry["sourceId"] for entry in json.loads((ROOT / "app/src/main/assets/gtfs/manifest.json").read_text(encoding="utf-8"))["packs"]])
        registry = self.temp / "candidates.json"
        for broken in (
            {**candidate(), "accessMode": "GATEWAY_ONLY"},
            {**candidate(), "sourceUrl": "http://example.test/x.zip"},
            {**candidate(), "licence": " "},
            {**candidate(), "asset": "gtfs-transit/x.zip"},
            {**candidate("sncf-gtfs")},
            {**candidate(), "attributionDated": "yes"},
        ):
            registry.write_text(json.dumps({"schemaVersion": 1, "packs": [broken]}), encoding="utf-8")
            with self.subTest(broken=broken), self.assertRaises((refresh.RefreshError, builder.BuildError)):
                refresh.load_candidates(registry)
        self.assertEqual({}, refresh.load_candidates(self.temp / "absent.json"))

    def test_an_unknown_pack_is_refused_before_any_download(self) -> None:
        fetch = FakeDownloads({})
        with self.assertRaisesRegex(refresh.RefreshError, "missing-gtfs"):
            refresh.refresh(["missing-gtfs"], self.published, self.temp / "out", "2026-11-03", fetch=fetch, resolve=self.resolve)
        self.assertEqual([], fetch.urls)


class WorkflowTest(unittest.TestCase):
    def test_the_refreshed_directory_is_checked_against_the_staged_full_packs(self) -> None:
        # A refreshed border pack names its new full pack in fullPack; the checkout's data/gtfs-foreign/android still
        # holds the previous one, so the job's --check must read the staged copy or it fails on every border refresh.
        workflow = (ROOT / ".github/workflows/gtfs-assets-ci.yml").read_text(encoding="utf-8")
        step = workflow.split("- name: Verify the refreshed core directory", 1)[1].split("- name:", 1)[0]
        self.assertIn('--input-dir "$RUNNER_TEMP/core/staging/gtfs"', step)
        self.assertIn(f'--full-pack-dir "$RUNNER_TEMP/core/staging/{refresh.FULL_PRINT_DIR}"', step)


class FreshnessTest(unittest.TestCase):
    def test_days_left_threshold_and_manual_pack(self) -> None:
        temp = Path(tempfile.mkdtemp(prefix="freshness-test-"))
        self.addCleanup(shutil.rmtree, temp, ignore_errors=True)
        committed, rebuilt = temp / "committed", temp / "rebuilt"
        for directory, packs in (
            (committed, [("gtfs/a.zip", "a", "2026-12-12", None), ("gtfs/oebb-gtfs.zip", "oebb-gtfs", "2026-12-12", None),
                         ("gtfs-transit/city.zip", "city", "2026-11-20", 14)]),
            (rebuilt, [("gtfs-transit/city.zip", "city", "2026-11-10", 14)]),
        ):
            directory.mkdir()
            entries = [{"sourceId": source_id, "asset": asset, "lastServiceDate": last, **({"minValidDays": days} if days else {})}
                       for asset, source_id, last, days in packs]
            (directory / "manifest.json").write_text(json.dumps({"packs": entries}), encoding="utf-8")
        rows = {row["sourceId"]: row for row in refresh.freshness_rows([committed, rebuilt, temp / "missing"], date(2026, 11, 3))}
        self.assertEqual((39, 30, "ok"), (rows["a"]["daysLeft"], rows["a"]["minValidDays"], rows["a"]["state"]))
        self.assertEqual("manuale", rows["oebb-gtfs"]["state"])
        # The rebuilt directory overrides the committed one for the same asset.
        self.assertEqual(("2026-11-10", 7, "SOTTO SOGLIA"), (rows["city"]["lastServiceDate"], rows["city"]["daysLeft"], rows["city"]["state"]))
        later = {row["sourceId"]: row for row in refresh.freshness_rows([committed], date(2026, 11, 20))}
        self.assertEqual("manuale - SOTTO SOGLIA", later["oebb-gtfs"]["state"])
        self.assertEqual("SOTTO SOGLIA", later["a"]["state"])
        markdown = refresh.freshness_markdown(list(rows.values()), date(2026, 11, 3))
        self.assertIn("| city | gtfs-transit | 2026-11-10 | 7 | 14 | SOTTO SOGLIA |", markdown)
        self.assertIn("Sotto soglia o scaduti: city.", markdown)
        # Audit2 tools-data-2: a calendar running on with a fraction of the trains is judged on its full coverage.
        thin = temp / "thin"
        thin.mkdir()
        (thin / "manifest.json").write_text(json.dumps({"packs": [{"sourceId": "t", "asset": "gtfs/t.zip",
            "lastServiceDate": "2027-12-11", "fullServiceUntil": "2026-12-12"}]}), encoding="utf-8")
        row = refresh.freshness_rows([thin], date(2026, 11, 13))[0]
        self.assertEqual(("2027-12-11 (piena fino al 2026-12-12)", 29, "SOTTO SOGLIA"), (row["lastServiceDate"], row["daysLeft"], row["state"]))

    def test_the_weekly_watch_warns_within_its_window_and_never_fails(self) -> None:
        temp = Path(tempfile.mkdtemp(prefix="freshness-watch-"))
        self.addCleanup(shutil.rmtree, temp, ignore_errors=True)
        entries = [{"sourceId": "a", "asset": "gtfs/a.zip", "lastServiceDate": "2026-12-12"},
                   {"sourceId": "b", "asset": "gtfs/b.zip", "lastServiceDate": "2027-06-30"},
                   {"sourceId": "city", "asset": "gtfs-transit/city.zip", "lastServiceDate": "2026-11-30", "minValidDays": 14}]
        (temp / "manifest.json").write_text(json.dumps({"packs": entries}), encoding="utf-8")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = refresh.main(["--freshness", str(temp), "--as-of", "2026-11-03", "--min-valid-days", "45", "--warn"])
        self.assertEqual(0, code)
        warnings = [line for line in output.getvalue().splitlines() if line.startswith("::warning")]
        # 39 days left is under the 45-day watch; the city pack keeps its own 14-day window (27 days left: ok).
        self.assertEqual(1, len(warnings), warnings)
        self.assertIn("a (gtfs/a.zip) termina 2026-12-12: 39 giorni residui, finestra 45", warnings[0])

    def test_shipped_packs_are_reported(self) -> None:
        rows = refresh.freshness_rows([refresh.CORE_DIR, refresh.TRANSIT_DIR, refresh.PERSONAL_DIR], date(2026, 11, 3))
        ids = {row["sourceId"] for row in rows}
        self.assertTrue({"trenitalia-gtfs", "italo-gtfs", "oebb-gtfs", "milano-atm-gtfs"} <= ids)


class ResolveUpstreamTest(unittest.TestCase):
    upstream = {"repository": "https://github.com/example/pinned-gtfs", "path": "gtfs.zip"}

    def test_github_api_names_the_last_commit_of_the_archive_and_keeps_the_token_off_redirects(self) -> None:
        seen: list[urllib.request.Request] = []

        class Response(io.BytesIO):
            def __enter__(self) -> "Response":
                return self

            def __exit__(self, *_: object) -> None:
                self.close()

        class Opener:
            def open(self, request: urllib.request.Request, timeout: int) -> Response:
                seen.append(request)
                return Response(json.dumps([{"sha": NEW_COMMIT, "commit": {"committer": {"date": "2026-11-02T03:04:05Z"}}}]).encode())

        result = refresh.resolve_upstream(self.upstream, token="secret", opener=Opener())
        self.assertEqual({"commit": NEW_COMMIT, "commitDate": "2026-11-02", "resolvedBy": "github-api"}, result)
        request = seen[0]
        self.assertEqual("https://api.github.com/repos/example/pinned-gtfs/commits?path=gtfs.zip&per_page=1", request.full_url)
        self.assertEqual("Bearer secret", request.unredirected_hdrs.get("Authorization"))
        self.assertNotIn("Authorization", request.headers)

    def test_git_ls_remote_is_the_fallback(self) -> None:
        class Failing:
            def open(self, request: urllib.request.Request, timeout: int) -> None:
                raise OSError("network unreachable")

        calls: list[list[str]] = []

        def run(command: list[str], **_: object) -> Any:
            calls.append(command)
            return type("Completed", (), {"returncode": 0, "stdout": f"{NEW_COMMIT}\tHEAD\n"})()

        result = refresh.resolve_upstream(self.upstream, opener=Failing(), run=run)
        self.assertEqual((NEW_COMMIT, None, "git-ls-remote"), (result["commit"], result["commitDate"], result["resolvedBy"]))
        self.assertEqual(["git", "ls-remote", "--exit-code", "https://github.com/example/pinned-gtfs.git", "HEAD"], calls[0])

        def broken(command: list[str], **_: object) -> Any:
            return type("Completed", (), {"returncode": 2, "stdout": ""})()

        with self.assertRaises(refresh.RefreshError):
            refresh.resolve_upstream(self.upstream, opener=Failing(), run=broken)


if __name__ == "__main__":
    unittest.main()
