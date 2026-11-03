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

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tests"))

import acquire_transit_sources as acquire  # noqa: E402
import apply_ci_gtfs_refresh as apply  # noqa: E402
import build_gtfs_packs as builder  # noqa: E402
import refresh_core_gtfs as refresh  # noqa: E402
from test_refresh_core_gtfs import NEW_COMMIT, OLD_COMMIT, FakeDownloads, core_directory, gtfs_zip, pinned_url  # noqa: E402

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

    def test_a_run_reads_the_core_and_transit_jobs(self) -> None:
        listing = {"jobs": [
            {"id": 1, "name": "core-packs", "conclusion": "failure"},
            {"id": 2, "name": "transit-packs", "conclusion": "success"},
            {"id": 3, "name": "gtfs-assets", "conclusion": "skipped"},
        ]}
        calls: list[str] = []

        def api(path: str) -> bytes:
            calls.append(path)
            return json.dumps(listing).encode() if path.endswith("/jobs?per_page=100") else self.log.encode()

        logs = apply.fetch_logs("owner/repo", "99", [], api=api)
        self.assertEqual(["1", "2"], [job for job, _ in logs])
        self.assertEqual("repos/owner/repo/actions/jobs/2/logs", calls[-1])


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

    def job(self, plain_trips: int = 4) -> Path:
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
                            resolve=lambda upstream: {"commit": NEW_COMMIT, "commitDate": "2026-11-02", "resolvedBy": "github-api"})
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

    def test_the_shipped_kotlin_registries_match_the_manifests(self) -> None:
        self.assertEqual({}, apply.planned_edits(ROOT, [], []))


if __name__ == "__main__":
    unittest.main()
