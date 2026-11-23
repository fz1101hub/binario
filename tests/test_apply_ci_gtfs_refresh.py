#!/usr/bin/env python3
"""Offline tests for tools/apply_ci_gtfs_refresh.py with small synthetic job logs."""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import shutil
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from typing import Any
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tests"))

import acquire_transit_sources as acquire  # noqa: E402
import apply_ci_gtfs_refresh as apply  # noqa: E402
import build_gtfs_packs as builder  # noqa: E402
import foreign_pack_rules as foreign  # noqa: E402
import refresh_core_gtfs as refresh  # noqa: E402
from test_refresh_core_gtfs import (  # noqa: E402
    NEW_COMMIT, OLD_COMMIT, FakeDownloads, candidate, core_directory, gtfs_zip, pinned_url, trenitalia_france_zip,
)

EMBEDDED_KT = '''object EmbeddedGtfsPacks {
    private val corePacks = listOf(
        EmbeddedGtfsPack(SourceId("plain-gtfs"), "gtfs/plain-gtfs.zip", LocalDate.of(2027, 12, 31)),
        EmbeddedGtfsPack(SourceId("oebb-gtfs"), "gtfs/oebb-gtfs.zip", LocalDate.of(2027, 12, 31)),
        EmbeddedGtfsPack(
            SourceId("pinned-gtfs"), "gtfs/pinned-gtfs.zip", LocalDate.of(2027, 12, 31),
            busRoutesAreScheduledCoaches = true
        )
    )
}
'''
TRANSIT_KT = '''internal object TransitGtfsPacks {
    val all: List<EmbeddedGtfsPack> = listOf(
        EmbeddedGtfsPack(
            SourceId("broken-gtfs"),
            "gtfs/broken-gtfs.zip",
            LocalDate.of(2027, 12, 31)
        )
    )
}
'''


def job_log(directory: Path) -> str:
    """``directory`` printed as the jobs print it, with the runner's BOM, timestamps and unrelated lines."""
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        acquire.print_directory(directory)
    lines = ["﻿2026-11-03T08:00:00.0000000Z ##[group]Run python3 -I tools/acquire_transit_sources.py --print-dir"]
    lines += [f"2026-11-03T08:00:01.{index:07d}Z {line}" for index, line in enumerate(output.getvalue().splitlines())]
    lines.append("2026-11-03T08:00:02.0000000Z ##[endgroup]")
    return "\n".join(lines) + "\n"


class ParseLogTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="apply-parse-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        (self.temp / "printed/gtfs").mkdir(parents=True)
        (self.temp / "printed/gtfs/manifest.json").write_text('{"packs": []}\n', encoding="utf-8")
        (self.temp / "printed/gtfs/demo.zip").write_bytes(bytes(range(256)) * 40)
        self.log = job_log(self.temp / "printed")

    def test_files_are_rebuilt_and_verified(self) -> None:
        files = apply.parse_printed_files(self.log)
        self.assertEqual({"gtfs/manifest.json", "gtfs/demo.zip"}, set(files))
        self.assertEqual(bytes(range(256)) * 40, files["gtfs/demo.zip"])

    def test_tampered_truncated_or_misplaced_files_are_refused(self) -> None:
        lines = self.log.splitlines()
        chunk = next(index for index, line in enumerate(lines) if "BINARIO_B64 gtfs/demo.zip" in line)
        tampered = list(lines)
        tampered[chunk] = tampered[chunk][:-8] + ("A" if tampered[chunk][-8] != "A" else "B") + tampered[chunk][-7:]
        with self.assertRaisesRegex(apply.ApplyError, "SHA-256|byte"):
            apply.parse_printed_files("\n".join(tampered))
        truncated = [line for line in lines if "BINARIO_END gtfs/demo.zip" not in line]
        with self.assertRaisesRegex(apply.ApplyError, "incompleti"):
            apply.parse_printed_files("\n".join(truncated))
        for path in ("../gtfs/demo.zip", "gtfs/run.sh", "gtfs/sub/demo.zip", "other/manifest.json"):
            with self.subTest(path=path), self.assertRaisesRegex(apply.ApplyError, "non ammesso"):
                apply.split_by_directory({path: b"x", "gtfs/manifest.json": b"{}"})
        with self.assertRaisesRegex(apply.ApplyError, "manifest"):
            apply.split_by_directory({"gtfs/demo.zip": b"x"})

    def test_only_the_single_nonce_frame_is_read(self) -> None:
        # Audit A7-02: a feed field printed in an earlier step's error message carried BINARIO_* lines.
        injected = (
            "2026-11-03T07:59:59.0000000Z ERRORE GTFS: campo CSV multilinea in stops.txt.x\n"
            "2026-11-03T07:59:59.0000000Z BINARIO_FILE gtfs/manifest.json 2 "
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a\n"
            "2026-11-03T07:59:59.0000000Z BINARIO_B64 gtfs/manifest.json e30=\n"
            "2026-11-03T07:59:59.0000000Z BINARIO_END gtfs/manifest.json\n"
        )
        with self.assertRaisesRegex(apply.ApplyError, "fuori dal blocco"):
            apply.parse_printed_files(injected + self.log)
        forged_frame = job_log(self.temp / "printed").replace("2026-11-03T08:00", "2026-11-03T07:59")
        with self.assertRaisesRegex(apply.ApplyError, "più di un blocco"):
            apply.parse_printed_files(forged_frame + self.log)
        # A line separator inside one runner line is not a line break for the runner, nor here.
        hidden = "2026-11-03T07:59:59.0000000Z x\u2028BINARIO_FILE gtfs/manifest.json 2 " + "0" * 64 + "\n"
        self.assertEqual({"gtfs/manifest.json", "gtfs/demo.zip"}, set(apply.parse_printed_files(hidden + self.log)))
        unframed = "\n".join(line for line in self.log.splitlines() if "BINARIO_BEGIN" not in line and "BINARIO_DONE" not in line)
        with self.assertRaisesRegex(apply.ApplyError, "fuori dal blocco"):
            apply.parse_printed_files(unframed)
        window = (apply._log_time("2026-11-03T08:00:01"), apply._log_time("2026-11-03T08:00:01"))
        self.assertEqual(2, len(apply.parse_printed_files(self.log, window=window)))
        late = (apply._log_time("2026-11-03T08:00:05"), apply._log_time("2026-11-03T08:00:09"))
        with self.assertRaisesRegex(apply.ApplyError, "fuori dallo step di stampa"):
            apply.parse_printed_files(self.log, window=late)

    def test_personal_packs_are_left_out_of_the_print_unless_asked(self) -> None:
        (self.temp / "printed/gtfs-personal").mkdir()
        (self.temp / "printed/gtfs-personal/manifest.json").write_text('{"packs": []}\n', encoding="utf-8")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            acquire.print_directory(self.temp / "printed", exclude=frozenset({"gtfs-personal"}))
        files = apply.parse_printed_files(output.getvalue())
        self.assertEqual({"gtfs/manifest.json", "gtfs/demo.zip"}, set(files))
        self.assertIn("Non stampata: gtfs-personal/", output.getvalue())
        self.assertIn("gtfs-personal/manifest.json", apply.parse_printed_files(job_log(self.temp / "printed")))

    def run_api(self, run: dict[str, Any], jobs: list[dict[str, Any]], calls: list[str]):
        def api(path: str) -> bytes:
            calls.append(path)
            if path.endswith("/jobs?per_page=100"):
                return json.dumps({"jobs": jobs}).encode()
            if path.endswith("/logs"):
                return self.log.encode()
            if "/actions/jobs/" in path:
                return json.dumps(next(job for job in jobs if path.endswith(f"/jobs/{job['id']}"))).encode()
            return json.dumps(run).encode()
        return api

    @staticmethod
    def steps(print_name: str, print_conclusion: str = "success", after: str = "success") -> list[dict[str, Any]]:
        return [
            {"number": 1, "name": "Set up job", "conclusion": "success", "started_at": "2026-11-03T07:58:00Z", "completed_at": "2026-11-03T07:58:01Z"},
            {"number": 2, "name": "Build", "conclusion": "success", "started_at": "2026-11-03T07:58:01Z", "completed_at": "2026-11-03T08:00:00Z"},
            {"number": 3, "name": print_name, "conclusion": print_conclusion, "started_at": "2026-11-03T08:00:00Z", "completed_at": "2026-11-03T08:00:02Z"},
            {"number": 4, "name": "Fail when a core source could not be refreshed", "conclusion": after,
             "started_at": "2026-11-03T08:00:02Z", "completed_at": "2026-11-03T08:00:03Z"},
        ]

    def test_a_run_reads_the_print_steps_of_the_core_and_transit_jobs(self) -> None:
        jobs = [
            {"id": 1, "run_id": 99, "name": "core-packs", "conclusion": "failure", "steps": self.steps("Print the changed core packs", after="failure")},
            {"id": 2, "run_id": 99, "name": "transit-packs", "conclusion": "success", "steps": self.steps("Print the built packs")},
            {"id": 3, "run_id": 99, "name": "gtfs-assets", "conclusion": "skipped", "steps": []},
        ]
        run = {"id": 99, "path": ".github/workflows/gtfs-assets-ci.yml", "event": "workflow_dispatch",
               "head_branch": "data/refresh", "head_sha": "c" * 40}
        git = apply.LocalGit("data/refresh", "d" * 40, lambda sha: sha in {"c" * 40, "d" * 40})
        calls: list[str] = []
        logs = apply.fetch_logs("owner/repo", "99", [], api=self.run_api(run, jobs, calls), git=git)
        self.assertEqual(["1", "2"], [job for job, _, _ in logs])
        self.assertEqual("repos/owner/repo/actions/jobs/2/logs", calls[-1])
        self.assertEqual(2, len(apply.merge_printed((text, window) for _, text, window in logs)))
        # --job reads the job, then binds its run the same way.
        logs = apply.fetch_logs("owner/repo", None, ["2"], api=self.run_api(run, jobs, []), git=git)
        self.assertEqual(["2"], [job for job, _, _ in logs])

        refusals = {
            "workflow": dict(run, path=".github/workflows/android-ci.yml"),
            "workflow_dispatch": dict(run, event="push"),
            "branch": dict(run, head_branch="main"),
            "antenato": dict(run, head_sha="e" * 40),
        }
        for message, bad_run in refusals.items():
            with self.subTest(message=message), self.assertRaisesRegex(apply.ApplyError, message):
                apply.fetch_logs("owner/repo", "99", [], api=self.run_api(bad_run, jobs, []), git=git)
        failed_build = [dict(jobs[1], steps=[dict(step, conclusion="failure") if step["name"] == "Build" else step
                                             for step in jobs[1]["steps"]])]
        with self.assertRaisesRegex(apply.ApplyError, "prima della stampa"):
            apply.fetch_logs("owner/repo", "99", [], api=self.run_api(run, failed_build, []), git=git)
        skipped = [dict(jobs[1], steps=self.steps("Print the built packs", print_conclusion="skipped"))]
        with self.assertRaisesRegex(apply.ApplyError, "nessuno step di stampa"):
            apply.fetch_logs("owner/repo", "99", [], api=self.run_api(run, skipped, []), git=git)


class ApplyTest(unittest.TestCase):
    """The core-packs job output (tools/refresh_core_gtfs.py) applied to a small repository copy."""

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="apply-test-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        self.root = self.temp / "repo"
        published, self.archives = core_directory(self.temp / "fixture")
        self.assets = self.root / "app/src/main/assets/gtfs"
        self.assets.parent.mkdir(parents=True)
        shutil.copytree(published, self.assets)
        kotlin = self.root / "app/src/main/java/it/binario/app/data/gtfs"
        kotlin.mkdir(parents=True)
        (kotlin / "EmbeddedGtfsPacks.kt").write_text(EMBEDDED_KT, encoding="utf-8")
        (kotlin / "TransitGtfsPacks.kt").write_text(TRANSIT_KT, encoding="utf-8")
        policy = self.root / "ios/Resources/binario_gtfs_source_policy_v1.json"
        policy.parent.mkdir(parents=True)
        policy.write_text(json.dumps({"androidOnlySources": [{"sourceId": "pinned-gtfs", "sourceUrl": pinned_url(OLD_COMMIT)}]}, indent=2) + "\n", encoding="utf-8")
        catalog = self.root / "app/src/main/java/it/binario/app/data/sources/RailSourceCatalog.kt"
        catalog.parent.mkdir(parents=True)
        catalog.write_text(f'val uri = uri("{pinned_url(OLD_COMMIT)}")\n', encoding="utf-8")
        self.before = {path.name: path.read_bytes() for path in self.assets.iterdir()}

    def job(self, plain_trips: int = 4, full_pack_dir: Path | None = None) -> Path:
        """Run the core refresh as the job does and save its printed log."""
        sources = self.temp / "upstream"
        sources.mkdir(exist_ok=True)
        payloads = {
            "https://example.test/plain-gtfs.zip": gtfs_zip(sources / "plain.zip", trips=plain_trips, end="20281231"),
            pinned_url(NEW_COMMIT): gtfs_zip(sources / "pinned.zip", feed_version="2026-11-01"),
        }
        out = self.temp / "out"
        with contextlib.redirect_stdout(io.StringIO()):
            refresh.refresh([], self.assets, out, "2026-11-03", fetch=FakeDownloads(payloads, {"https://example.test/broken-gtfs.zip"}),
                            resolve=lambda upstream: {"commit": NEW_COMMIT, "commitDate": "2026-11-02", "resolvedBy": "github-api"},
                            full_pack_dir=full_pack_dir or self.temp / "no-full-packs")
        log = self.temp / "core-packs.log"
        log.write_text(job_log(out / "publish"), encoding="utf-8")
        return log

    def apply(self, *arguments: str) -> tuple[int, str]:
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            code = apply.main([*arguments, "--root", str(self.root), "--no-checks"])
        return code, output.getvalue()

    def test_changed_packs_pins_and_dates_are_applied_and_the_rest_stays_byte_for_byte(self) -> None:
        code, output = self.apply("--log", str(self.job()))
        self.assertEqual(0, code, output)
        staging = self.temp / "out/staging/gtfs"
        for name in ("manifest.json", "SHA256SUMS", "plain-gtfs.zip", "pinned-gtfs.zip"):
            self.assertEqual((staging / name).read_bytes(), (self.assets / name).read_bytes(), name)
        for name in ("oebb-gtfs.zip", "broken-gtfs.zip"):
            self.assertEqual(self.before[name], (self.assets / name).read_bytes(), name)
        builder.verify_assets(self.assets, as_of=None, min_valid_days=None)
        embedded = (self.root / "app/src/main/java/it/binario/app/data/gtfs/EmbeddedGtfsPacks.kt").read_text(encoding="utf-8")
        self.assertIn('SourceId("plain-gtfs"), "gtfs/plain-gtfs.zip", LocalDate.of(2028, 12, 31)', embedded)
        self.assertIn('SourceId("oebb-gtfs"), "gtfs/oebb-gtfs.zip", LocalDate.of(2027, 12, 31)', embedded)
        for relative in apply.PIN_MIRRORS:
            text = (self.root / relative).read_text(encoding="utf-8")
            self.assertIn(pinned_url(NEW_COMMIT), text)
            self.assertNotIn(pinned_url(OLD_COMMIT), text)
        self.assertRegex(output, r"gtfs\s+plain-gtfs\s+2027-12-31 -> 2028-12-31\s+4\s+sì")
        self.assertRegex(output, r"broken-gtfs .*NON AGGIORNATO dal job: AcquisitionError")
        self.assertRegex(output, r"oebb-gtfs .*manuale")
        self.assertIn(f"pinned-gtfs (changed): commit {NEW_COMMIT} del 2026-11-02", output)
        self.assertIn("Demo operator", output)

    def test_a_pack_shrinking_by_more_than_half_needs_an_explicit_accept(self) -> None:
        log = self.job(plain_trips=1)
        code, output = self.apply("--log", str(log))
        self.assertEqual(1, code)
        self.assertIn("RIFIUTATO: sospetto", output)
        self.assertEqual(self.before["plain-gtfs.zip"], (self.assets / "plain-gtfs.zip").read_bytes())
        builder.verify_assets(self.assets, as_of=None, min_valid_days=None)
        code, output = self.apply("--log", str(log), "--accept", "plain-gtfs")
        self.assertEqual(0, code, output)
        self.assertNotEqual(self.before["plain-gtfs.zip"], (self.assets / "plain-gtfs.zip").read_bytes())

    def test_dry_run_and_skip_write_nothing_for_the_pack(self) -> None:
        log = self.job()
        code, _ = self.apply("--log", str(log), "--dry-run")
        self.assertEqual(0, code)
        self.assertEqual(self.before, {path.name: path.read_bytes() for path in self.assets.iterdir()})
        code, output = self.apply("--log", str(log), "--skip", "pinned-gtfs")
        self.assertEqual(0, code, output)
        self.assertEqual(self.before["pinned-gtfs.zip"], (self.assets / "pinned-gtfs.zip").read_bytes())
        self.assertIn(pinned_url(OLD_COMMIT), (self.root / apply.PIN_MIRRORS[0]).read_text(encoding="utf-8"))

    def test_a_partial_print_keeps_unprinted_and_content_identical_packs(self) -> None:
        """A transit-style print: a subset of the packs, one re-zipped with other bytes but the same timetable."""
        self.job()
        printed = self.temp / "partial/gtfs"
        printed.mkdir(parents=True)
        staging = self.temp / "out/staging/gtfs"
        manifest = json.loads((staging / "manifest.json").read_text(encoding="utf-8"))
        shutil.copyfile(staging / "plain-gtfs.zip", printed / "plain-gtfs.zip")
        with zipfile.ZipFile(self.assets / "broken-gtfs.zip") as source, zipfile.ZipFile(printed / "broken-gtfs.zip", "w", compression=zipfile.ZIP_STORED) as copy:
            for entry in source.infolist():
                copy.writestr(entry.filename, source.read(entry.filename))
        entries: list[dict[str, Any]] = []
        for pack in manifest["packs"]:
            if pack["sourceId"] == "plain-gtfs":
                entries.append(pack)
            elif pack["sourceId"] == "broken-gtfs":
                entries.append({**pack, "sha256": hashlib.sha256((printed / "broken-gtfs.zip").read_bytes()).hexdigest(),
                                "attribution": "Reviewed new attribution"})
        (printed / "manifest.json").write_text(json.dumps({**manifest, "packs": entries}, indent=2) + "\n", encoding="utf-8")
        log = self.temp / "partial.log"
        log.write_text(job_log(self.temp / "partial"), encoding="utf-8")
        code, output = self.apply("--log", str(log))
        self.assertEqual(0, code, output)
        result = {pack["sourceId"]: pack for pack in json.loads((self.assets / "manifest.json").read_text(encoding="utf-8"))["packs"]}
        self.assertEqual(self.before["broken-gtfs.zip"], (self.assets / "broken-gtfs.zip").read_bytes())
        self.assertEqual("Reviewed new attribution", result["broken-gtfs"]["attribution"])
        self.assertEqual(hashlib.sha256(self.before["broken-gtfs.zip"]).hexdigest(), result["broken-gtfs"]["sha256"])
        self.assertEqual(self.before["pinned-gtfs.zip"], (self.assets / "pinned-gtfs.zip").read_bytes())
        self.assertEqual("2028-12-31", result["plain-gtfs"]["lastServiceDate"])
        self.assertRegex(output, r"pinned-gtfs .*non ricostruito in questa run")
        builder.verify_assets(self.assets, as_of=None, min_valid_days=None)

    def test_a_coverage_kept_alive_by_a_trickle_of_trips_is_refused(self) -> None:
        """A feed that keeps one trip a day for years after its timetable ends (EAV, November 2026)."""
        source = self.temp / "trickle-source.zip"
        gtfs_zip(self.temp / "base.zip", end="20271231")
        with zipfile.ZipFile(self.temp / "base.zip") as base, zipfile.ZipFile(source, "w") as output:
            for name in base.namelist():
                data = base.read(name).decode("utf-8")
                if name == "trips.txt":
                    data += "R1,TAIL,tail,999\n"
                elif name == "stop_times.txt":
                    data += "tail,12:00:00,12:00:00,S1,1\ntail,13:00:00,13:00:00,S2,2\n"
                elif name == "calendar.txt":
                    data += "TAIL,1,1,1,1,1,1,1,20260101,20311231\n"
                output.writestr(name, data)
        trickle = builder.filter_archive(source, self.temp / "trickle.zip", "plain-gtfs")
        self.assertEqual("2031-12-31", trickle.last_service_date.isoformat())
        reason = apply.thin_tail((self.temp / "trickle.zip").read_bytes(), "2031-12-31")
        self.assertIn("orario completo fino al 2027-12-31 (picco 5 corse al giorno), poi non più di 1 al giorno fino al 2031-12-31", reason)
        self.assertIsNone(apply.thin_tail((self.assets / "plain-gtfs.zip").read_bytes(), "2027-12-31"))
        # Every shipped pack passes the guard.
        for directory in apply.DESTINATIONS.values():
            manifest = json.loads((ROOT / directory / "manifest.json").read_text(encoding="utf-8"))
            for pack in manifest["packs"]:
                data = (ROOT / directory / builder.safe_asset_name(pack["asset"])).read_bytes()
                with self.subTest(pack=pack["sourceId"]):
                    self.assertIsNone(apply.thin_tail(data, pack["lastServiceDate"]))

    def test_checks_rebuild_the_ios_index_next_to_the_canonical_one(self) -> None:
        commands: list[list[str]] = []

        def runner(command: list[str], **_: object) -> Any:
            commands.append(command)
            return type("Completed", (), {"returncode": 0, "stdout": "", "stderr": ""})()

        results = apply.run_checks(self.root, ios=True, runner=runner)
        self.assertTrue(all(ok for _, ok, _ in results))
        scripts = [command[1] for command in commands]
        self.assertEqual(2, scripts.count("tools/build_gtfs_packs.py"))  # only gtfs/ exists in this copy
        # The foreign-pack generator rebuilds the iPhone indexes, pins and ledger first; the checks follow.
        self.assertEqual("tools/build_foreign_packs.py", scripts[0])
        self.assertEqual(["tools/verify_ios_gtfs_schedule.py", "tools/build_ios_gtfs_schedule.py",
                          "tools/verify_ios_gtfs_rebuild.py"], scripts[-3:])
        self.assertIn("ios/Resources/.apply-rebuild.sqlite.zlib", commands[-1])
        apply.run_checks(self.root, ios=False, runner=runner)
        later = [command[1] for command in commands[len(scripts):]]
        self.assertNotIn("tools/build_ios_gtfs_schedule.py", later)
        self.assertNotIn("tools/build_foreign_packs.py", later)

    def test_a_border_pack_prints_its_full_pack_and_the_subset_is_derived_here(self) -> None:
        """Design §2.4: a foreign pack bundled as its border subset is printed whole; its full pack goes to
        data/gtfs-foreign/android and the subset is derived again where the refresh is applied."""
        full_dir = self.root / foreign.FULL_PACK_DIR
        catalogue = self.root / foreign.CATALOGUE_PATH
        catalogue.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / foreign.CATALOGUE_PATH, catalogue)
        corridor = foreign.Corridor("Alfa – Beta", "fixture", foreign.stop("Alfa", 45.0, 9.0), (foreign.stop("Beta", 45.1, 9.1),))
        with mock.patch.dict(foreign.FOREIGN_PACK_COUNTRIES, {"plain-gtfs": "XX"}), \
                mock.patch.dict(foreign.REQUIRED_BORDER_PAIRS, {}, clear=True), \
                mock.patch.dict(foreign.CORRIDORS, {"plain-gtfs": (corridor,)}):
            manifest = json.loads((self.assets / "manifest.json").read_text(encoding="utf-8"))
            next(pack for pack in manifest["packs"] if pack["sourceId"] == "plain-gtfs")["licence"] = "CC BY 4.0"
            builder.write_json(self.assets / "manifest.json", manifest)
            builder.apply_border_zones(self.assets, full_pack_dir=full_dir, catalogue_path=catalogue)
            log = self.job(full_pack_dir=full_dir)
            text = log.read_text(encoding="utf-8")
            self.assertIn("BINARIO_FILE gtfs-foreign-android/plain-gtfs.zip", text)
            self.assertNotIn("BINARIO_FILE gtfs/plain-gtfs.zip", text)
            # Without the printed full pack the border pack is refused and stays as it was.
            files = apply.merge_printed([(text, None)])
            files.pop("gtfs-foreign-android/plain-gtfs.zip")
            directories, _ = apply.split_by_directory(files)
            work = self.temp / "work"
            work.mkdir()
            merged = apply.merge_directory("gtfs", directories["gtfs"], self.assets, work, skip=set(), accept=set(),
                                           job_status={}, full_printed={}, full_dir=full_dir, catalogue_path=catalogue)
            row = next(row for row in merged.rows if row.source_id == "plain-gtfs")
            self.assertIn("pack completo non stampato", row.failure or "")
            code, output = self.apply("--log", str(log))
            self.assertEqual(0, code, output)
            printed_full = self.temp / "out/staging/gtfs-foreign-android/plain-gtfs.zip"
            self.assertEqual(printed_full.read_bytes(), (full_dir / "plain-gtfs.zip").read_bytes())
            self.assertIsNotNone(builder.asset_border_zone(self.assets / "plain-gtfs.zip"))
            entry = next(pack for pack in json.loads((self.assets / "manifest.json").read_text())["packs"] if pack["sourceId"] == "plain-gtfs")
            self.assertEqual(hashlib.sha256(printed_full.read_bytes()).hexdigest(), entry["fullPack"]["sha256"])
            self.assertEqual("data/gtfs-foreign/android/plain-gtfs.zip", entry["fullPack"]["path"])
            builder.verify_assets(self.assets, as_of=None, min_valid_days=None, full_pack_dir=full_dir, catalogue_path=catalogue)
            self.assertRegex(output, r"plain-gtfs\s+2027-12-31 -> 2028-12-31\s+4\s+sì\s+\d+\.\d\d\s+\d+\.\d\d\s+4/4")
            self.assertEqual([], [path.name for path in self.assets.iterdir() if path.name.startswith(("full", "subset"))])
            # Applied again, the same log changes nothing and the border pack still shows its sizes.
            before = {path: path.read_bytes() for path in (*self.assets.iterdir(), *full_dir.iterdir())}
            code, output = self.apply("--log", str(log))
            self.assertEqual(0, code, output)
            self.assertRegex(output, r"plain-gtfs\s+2028-12-31\s+4\s+no\s+\d+\.\d\d\s+\d+\.\d\d\s+4/4")
            self.assertEqual(before, {path: path.read_bytes() for path in (*self.assets.iterdir(), *full_dir.iterdir())})
            # A full pack printed for a pack the printed manifest does not bundle as a subset is a malformed print.
            files = apply.merge_printed([(text, None)])
            directories, _ = apply.split_by_directory(files)
            with self.assertRaisesRegex(apply.ApplyError, "pinned-gtfs.zip"):
                apply.merge_directory("gtfs", directories["gtfs"], self.assets, self.temp / "work-stray", skip=set(), accept=set(),
                                      job_status={}, full_printed={**directories[apply.FULL_PRINT_DIR], "pinned-gtfs.zip": b"PK"},
                                      full_dir=full_dir, catalogue_path=catalogue)

    def test_a_reviewed_timetable_switch_moves_source_url_and_landing_page_in_the_mirrors(self) -> None:
        current, landing = "https://example.test/plain-gtfs.zip", "https://example.test/plain-2026"
        following = {"from": "2026-11-01", "sourceUrl": "https://example.test/plain-2027/permalink",
                     "landingPage": "https://example.test/plain-2027"}
        manifest = json.loads((self.assets / "manifest.json").read_text(encoding="utf-8"))
        for pack in manifest["packs"]:
            if pack["sourceId"] == "plain-gtfs":
                pack["nextTimetable"] = following
        builder.write_json(self.assets / "manifest.json", manifest)
        policy = self.root / apply.PIN_MIRRORS[0]
        policy.write_text(json.dumps({"sources": [{"sourceId": "plain-gtfs", "sourceUrl": current, "landingPage": landing}]},
                                     indent=2) + "\n", encoding="utf-8")
        catalog = self.root / apply.PIN_MIRRORS[1]
        catalog.write_text(f'landingPage = uri("{landing}"),\ndownloadOrProbeUri = uri("{current}"),\n', encoding="utf-8")
        sources = self.temp / "upstream"
        sources.mkdir()
        payloads = {following["sourceUrl"]: gtfs_zip(sources / "next.zip", end="20281231")}
        out = self.temp / "out"
        with contextlib.redirect_stdout(io.StringIO()):
            report = refresh.refresh(["plain-gtfs"], self.assets, out, "2026-11-03", fetch=FakeDownloads(payloads),
                                     resolve=lambda upstream: {}, full_pack_dir=self.temp / "no-full-packs")
        self.assertEqual("changed", report["packs"]["plain-gtfs"]["status"])
        log = self.temp / "core-packs.log"
        log.write_text(job_log(out / "publish"), encoding="utf-8")
        code, output = self.apply("--log", str(log))
        self.assertEqual(0, code, output)
        entry = next(pack for pack in json.loads((self.assets / "manifest.json").read_text(encoding="utf-8"))["packs"]
                     if pack["sourceId"] == "plain-gtfs")
        self.assertEqual(following["sourceUrl"], entry["sourceUrl"])
        self.assertNotIn("nextTimetable", entry)
        for mirror in (policy, catalog):
            text = mirror.read_text(encoding="utf-8")
            self.assertIn(following["sourceUrl"], text)
            self.assertIn(following["landingPage"], text)
            self.assertNotIn(current, text)
            self.assertNotIn(f'"{landing}"', text)
        self.assertIn("orario successivo dal 2026-11-01", output)

    def test_a_source_url_changed_without_a_pin_or_a_reviewed_switch_is_refused(self) -> None:
        self.job()
        manifest = json.loads((self.temp / "out/staging/gtfs/manifest.json").read_text(encoding="utf-8"))
        old = json.loads((self.assets / "manifest.json").read_text(encoding="utf-8"))
        for pack in manifest["packs"]:
            if pack["sourceId"] == "plain-gtfs":
                pack["sourceUrl"] = "https://example.test/elsewhere.zip"
        merged = apply.MergedDirectory("gtfs", self.assets, [], self.temp, old, manifest)
        with self.assertRaisesRegex(apply.ApplyError, "plain-gtfs: sourceUrl cambiato"):
            apply.pin_updates(merged, {"packs": {}})
        # A switch to a URL other than the reviewed next one is refused as well.
        for pack in old["packs"]:
            if pack["sourceId"] == "plain-gtfs":
                pack["nextTimetable"] = {"from": "2026-11-01", "sourceUrl": "https://example.test/reviewed.zip",
                                         "landingPage": "https://example.test/reviewed"}
        report = {"packs": {"plain-gtfs": {"timetableSwitch": {"previousSourceUrl": "https://example.test/plain-gtfs.zip",
                                                                "sourceUrl": "https://example.test/elsewhere.zip"}}}}
        with self.assertRaisesRegex(apply.ApplyError, "nextTimetable rivisto"):
            apply.pin_updates(apply.MergedDirectory("gtfs", self.assets, [], self.temp, old, manifest), report)

    def test_a_named_candidate_is_added_with_its_kotlin_and_ios_entries(self) -> None:
        reviewed = candidate("new-gtfs")
        registry = self.root / apply.CANDIDATES
        registry.parent.mkdir(parents=True)
        registry.write_text(json.dumps({"schemaVersion": 1, "purpose": "fixture", "packs": [reviewed]}, indent=2) + "\n", encoding="utf-8")
        catalog = self.root / apply.CATALOG_KT
        catalog.write_text(catalog.read_text(encoding="utf-8")
                           + f'id = SourceId("new-gtfs"),\ndownloadOrProbeUri = uri("{reviewed["sourceUrl"]}"),\n', encoding="utf-8")
        sources = self.temp / "upstream"
        sources.mkdir()
        out = self.temp / "out"
        with contextlib.redirect_stdout(io.StringIO()):
            report = refresh.refresh(["new-gtfs"], self.assets, out, "2026-11-24",
                                     fetch=FakeDownloads({reviewed["sourceUrl"]: trenitalia_france_zip(sources / "tf.zip")}),
                                     resolve=lambda upstream: {}, full_pack_dir=self.temp / "no-full-packs",
                                     candidates=refresh.load_candidates(registry))
        self.assertEqual(["new-gtfs"], report["added"])
        log = self.temp / "core-packs.log"
        log.write_text(job_log(out / "publish"), encoding="utf-8")
        code, output = self.apply("--log", str(log))
        self.assertEqual(0, code, output)
        self.assertIn("pack nuovo dal registro dei candidati", output)
        self.assertRegex(output, r"gtfs\s+new-gtfs\s+2027-12-11\s+2\s+sì")
        manifest = json.loads((self.assets / "manifest.json").read_text(encoding="utf-8"))
        # Before the first pack after it in sourceId order, as the job printed it; the others keep their order.
        self.assertEqual(["new-gtfs", "plain-gtfs", "oebb-gtfs", "pinned-gtfs", "broken-gtfs"], [pack["sourceId"] for pack in manifest["packs"]])
        printed = json.loads((out / "staging/gtfs/manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(printed["packs"], manifest["packs"])
        entry = next(pack for pack in manifest["packs"] if pack["sourceId"] == "new-gtfs")
        self.assertEqual("Trenitalia France — dati del 2026-11-24", entry["attribution"])
        self.assertEqual(reviewed["licence"], entry["licence"])
        builder.verify_assets(self.assets, as_of=None, min_valid_days=None)
        for name, data in self.before.items():
            if name not in ("manifest.json", "SHA256SUMS"):
                self.assertEqual(data, (self.assets / name).read_bytes(), name)
        embedded = (self.root / apply.CORE_PACKS_KT).read_text(encoding="utf-8")
        self.assertIn('        EmbeddedGtfsPack(SourceId("new-gtfs"), "gtfs/new-gtfs.zip", LocalDate.of(2027, 12, 11)),\n'
                      '        EmbeddedGtfsPack(SourceId("plain-gtfs")', embedded)
        policy = json.loads((self.root / apply.IOS_POLICY).read_text(encoding="utf-8"))
        self.assertIn({"accessMode": "OPEN_DIRECT", "reason": reviewed["iosAndroidOnlyReason"], "sourceId": "new-gtfs",
                       "sourceUrl": reviewed["sourceUrl"]}, policy["androidOnlySources"])
        self.assertEqual([], json.loads(registry.read_text(encoding="utf-8"))["packs"])
        # Applied again, the pack is registered: nothing changes.
        before = {path: path.read_bytes() for path in (*self.assets.iterdir(), registry, self.root / apply.CORE_PACKS_KT)}
        code, output = self.apply("--log", str(log))
        self.assertEqual(0, code, output)
        self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_a_printed_new_pack_is_added_only_as_the_reviewed_candidate(self) -> None:
        reviewed = candidate("new-gtfs")
        printed = {**refresh.candidate_entry(reviewed), "attribution": "Trenitalia France — dati del 2026-11-24"}
        self.assertIsNone(apply.candidate_problem(reviewed, printed))
        self.assertIn("licence", apply.candidate_problem(reviewed, {**printed, "licence": "CC BY-SA"}) or "")
        self.assertIn("sourceUrl", apply.candidate_problem(reviewed, {**printed, "sourceUrl": "https://example.test/other"}) or "")
        self.assertIn("attribution", apply.candidate_problem(reviewed, {**printed, "attribution": "Someone else"}) or "")
        self.assertIn("fullPack", apply.candidate_problem(reviewed, {**printed, "fullPack": {}}) or "")
        # Without a registry entry a new pack stays refused, as before.
        self.job()
        manifest = json.loads((self.temp / "out/staging/gtfs/manifest.json").read_text(encoding="utf-8"))
        manifest["packs"].append({**printed, "asset": "gtfs/new-gtfs.zip", "sha256": "0" * 64, "lastServiceDate": "2027-12-11"})
        work = self.temp / "work"
        work.mkdir()
        merged = apply.merge_directory("gtfs", {"manifest.json": json.dumps(manifest).encode()}, self.assets, work,
                                       skip=set(), accept=set(), job_status={})
        row = next(row for row in merged.rows if row.source_id == "new-gtfs")
        self.assertIn("non registrato", row.failure or "")

    def test_the_shipped_kotlin_registries_match_the_manifests(self) -> None:
        self.assertEqual({}, apply.planned_edits(ROOT, [], []))


class AddNewPackTest(unittest.TestCase):
    """A candidate of tools/transit_packs.json built by the transit-packs job and added with --add-new."""

    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="apply-new-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        self.root = self.temp / "repo"
        sources = self.temp / "sources"
        sources.mkdir()
        gtfs_zip(sources / "old.zip")
        gtfs_zip(sources / "new.zip", end="20280630")
        base = {"directory": "gtfs-transit", "distribution": "all", "kind": "rail", "format": "gtfs", "licence": "CC BY 4.0",
                "attribution": "Demo", "notes": "fixture"}
        registry = self.root / "tools/transit_packs.json"
        registry.parent.mkdir(parents=True)
        registry.write_text(json.dumps({"schemaVersion": 1, "packs": [
            dict(base, sourceId="old-gtfs", sourceUrl="https://example.test/old.zip"),
            dict(base, sourceId="new-gtfs", sourceUrl="https://example.test/new.zip", candidate=True,
                 android={"catalogueNameSuffixes": ["NG"]}),
        ]}), encoding="utf-8")
        packs = acquire.load_registry(registry)

        def build(source_id: str, output: Path) -> None:
            selected = [pack for pack in packs if pack["sourceId"] == source_id]
            report = {"packs": {source_id: {"download": {"sha256": "0" * 64, "bytes": 1}}}}
            manifest = self.temp / f"{source_id}.json"
            manifest.write_text(json.dumps(acquire.source_manifests(selected, report, "2026-11-23")["gtfs-transit"]), encoding="utf-8")
            builder.build_packs(sources, output, manifest_path=manifest, generated_at="2026-11-23",
                                input_overrides={source_id: sources / f"{source_id.split('-')[0]}.zip"})

        self.transit = self.root / "app/src/main/assets/gtfs-transit"
        build("old-gtfs", self.transit)
        build("new-gtfs", self.temp / "printed/gtfs-transit")
        self.log = self.temp / "transit-packs.log"
        self.log.write_text(job_log(self.temp / "printed"), encoding="utf-8")
        kotlin = self.root / "app/src/main/java/it/binario/app/data/gtfs"
        kotlin.mkdir(parents=True)
        (kotlin / "EmbeddedGtfsPacks.kt").write_text("object EmbeddedGtfsPacks\n", encoding="utf-8")
        (kotlin / "TransitGtfsPacks.kt").write_text(TRANSIT_KT.replace('"broken-gtfs"', '"old-gtfs"').replace(
            '"gtfs/broken-gtfs.zip"', '"gtfs-transit/old-gtfs.zip"').replace("2027, 12, 31", "2027, 12, 31"), encoding="utf-8")
        self.catalogue = self.root / "app/src/main/java/it/binario/app/data/sources/RailSourceCatalog.kt"
        self.catalogue.parent.mkdir(parents=True)
        self.catalogue.write_text('SourceId("old-gtfs")\n', encoding="utf-8")
        self.before = {path.name: path.read_bytes() for path in self.transit.iterdir()}

    def apply(self, *arguments: str) -> tuple[int, str]:
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            code = apply.main(["--log", str(self.log), *arguments, "--root", str(self.root), "--no-checks"])
        return code, output.getvalue()

    def test_a_new_pack_needs_add_new_and_a_catalogue_entry(self) -> None:
        code, output = self.apply()
        self.assertEqual(1, code)
        self.assertIn("--add-new new-gtfs", output)
        self.assertEqual(self.before, {path.name: path.read_bytes() for path in self.transit.iterdir()})
        code, output = self.apply("--add-new", "old-gtfs")
        self.assertEqual(1, code)
        self.assertIn("non candidati", output)
        code, output = self.apply("--add-new", "new-gtfs")
        self.assertEqual(1, code)
        self.assertIn("senza voce in RailSourceCatalog.kt: new-gtfs", output)
        self.assertEqual(self.before, {path.name: path.read_bytes() for path in self.transit.iterdir()})

        self.catalogue.write_text('SourceId("old-gtfs")\nSourceId("new-gtfs")\n', encoding="utf-8")
        code, output = self.apply("--add-new", "new-gtfs")
        self.assertEqual(0, code, output)
        self.assertIn("pack nuovo aggiunto (--add-new)", output)
        manifest = json.loads((self.transit / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(["old-gtfs", "new-gtfs"], [pack["sourceId"] for pack in manifest["packs"]])
        self.assertEqual(self.before["old-gtfs.zip"], (self.transit / "old-gtfs.zip").read_bytes())
        builder.verify_assets(self.transit, as_of=None, min_valid_days=None)
        kotlin = (self.root / "app/src/main/java/it/binario/app/data/gtfs/TransitGtfsPacks.kt").read_text(encoding="utf-8")
        self.assertIn(
            '        ),\n        EmbeddedGtfsPack(\n            SourceId("new-gtfs"),\n            "gtfs-transit/new-gtfs.zip",\n'
            '            LocalDate.of(2028, 6, 30),\n            catalogueNameSuffixes = listOf("NG")\n        )\n    )\n}\n',
            kotlin,
        )
        # Applied again, the pack is known: nothing new to add.
        self.assertEqual({}, apply.planned_edits(self.root, [], []))

    def test_a_new_pack_comes_from_one_candidate_registry_only(self) -> None:
        self.catalogue.write_text('SourceId("old-gtfs")\nSourceId("new-gtfs")\n', encoding="utf-8")
        core = self.root / apply.CANDIDATES
        core.write_text(json.dumps({"schemaVersion": 1, "purpose": "fixture", "packs": [candidate("new-gtfs")]}), encoding="utf-8")
        code, output = self.apply("--add-new", "new-gtfs")
        self.assertEqual(1, code)
        self.assertIn("si aggiungono senza --add-new): new-gtfs", output)
        self.assertEqual(self.before, {path.name: path.read_bytes() for path in self.transit.iterdir()})
        # Called directly with the id in both maps, the merge refuses the pack too.
        work = self.temp / "work"
        work.mkdir()
        printed = {path.name: path.read_bytes() for path in (self.temp / "printed/gtfs-transit").iterdir()}
        registry = {pack["sourceId"]: pack for pack in acquire.load_registry(self.root / "tools/transit_packs.json")}
        merged = apply.merge_directory("gtfs-transit", printed, self.transit, work, skip=set(), accept=set(), job_status={},
                                       candidates={"new-gtfs": candidate("new-gtfs")}, add_new={"new-gtfs": registry["new-gtfs"]})
        row = next(row for row in merged.rows if row.source_id == "new-gtfs")
        self.assertIn("candidato sia di", row.failure or "")
        self.assertEqual(([], {}), (merged.added_candidates, merged.added_new))

    def test_the_shipped_candidate_registries_do_not_share_an_id(self) -> None:
        core = set(refresh.load_candidates(ROOT / apply.CANDIDATES))
        transit = {pack["sourceId"] for pack in acquire.load_registry()}
        self.assertEqual(set(), core & transit)
        self.assertTrue(core and any(pack.get("candidate") for pack in acquire.load_registry()))

    def test_an_urban_personal_candidate_becomes_the_kotlin_entry_of_the_city_packs(self) -> None:
        registration = {"kind": "URBAN", "areas": [{"name": "Palermo", "latitude": 38.109187, "longitude": 13.367515, "radiusKm": 12}],
                        "catalogueNameSuffixes": [], "personalOnly": True}
        self.assertEqual(
            "        EmbeddedGtfsPack(\n"
            '            SourceId("palermo-amat-tram-gtfs"),\n'
            '            "gtfs-personal/palermo-amat-tram-gtfs.zip",\n'
            "            LocalDate.of(2027, 1, 31),\n"
            "            kind = EmbeddedGtfsPackKind.URBAN,\n"
            '            areas = listOf(TransitArea("Palermo", 38.109187, 13.367515, 12.0)),\n'
            "            personalOnly = true\n"
            "        )",
            apply.kotlin_entry("palermo-amat-tram-gtfs", "gtfs-personal/palermo-amat-tram-gtfs.zip", "2027-01-31", registration),
        )
        with self.assertRaises(apply.ApplyError):
            apply.with_added_packs("object TransitGtfsPacks\n", ["entry"], Path("TransitGtfsPacks.kt"))
        shipped = (ROOT / apply.TRANSIT_KOTLIN).read_text(encoding="utf-8")
        self.assertIn("entry", apply.with_added_packs(shipped, ["entry"], apply.TRANSIT_KOTLIN))


if __name__ == "__main__":
    unittest.main()
