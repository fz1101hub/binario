#!/usr/bin/env python3
"""Offline tests for tools/apply_ci_punctuality.py with synthetic job logs (frame and run checks shared with the GTFS
helper through tools/ci_log_frames.py)."""
from __future__ import annotations

import contextlib
import copy
import io
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tests"))

import apply_ci_punctuality as apply  # noqa: E402
import build_punctuality_stats as stats  # noqa: E402
import ci_log_frames as frames  # noqa: E402
from test_build_punctuality_stats import legacy_payload, valid_payload  # noqa: E402


def job_log(data: bytes, minute: str = "08:00", *, checked: bool = True) -> str:
    """``data`` printed by the print step as the job does, with the runner's BOM, timestamps and other lines;
    ``checked=False`` prints a file the print step would refuse (a forged or older job)."""
    output = io.StringIO()
    temp = Path(tempfile.mkdtemp(prefix="apply-punctuality-log-"))
    try:
        path = temp / "stats_v1.json.gz"
        path.write_bytes(data)
        with contextlib.redirect_stdout(output):
            if checked:
                assert stats.main(["--print", str(path)]) == 0
            else:
                frames.print_files([(stats.PUBLISHED_PATH, data)])
    finally:
        shutil.rmtree(temp, ignore_errors=True)
    lines = [f"﻿2026-11-10T{minute}:00.0000000Z ##[group]Run python3 -I tools/build_punctuality_stats.py --print"]
    lines += [f"2026-11-10T{minute}:01.{index:07d}Z {line}" for index, line in enumerate(output.getvalue().splitlines())]
    lines.append(f"2026-11-10T{minute}:02.0000000Z ##[endgroup]")
    return "\n".join(lines) + "\n"


class ApplyPunctualityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="apply-punctuality-"))
        self.addCleanup(shutil.rmtree, self.temp, ignore_errors=True)
        self.root = self.temp / "repo"
        (self.root / "app/src/main/assets").mkdir(parents=True)
        self.data = stats.encode(valid_payload())
        self.log = job_log(self.data)
        self.target = self.root / stats.ASSET_PATH

    def run_main(self, *arguments: str) -> tuple[int, str]:
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            code = apply.main([*arguments, "--root", str(self.root)])
        return code, output.getvalue()

    def save(self, text: str, name: str = "job.log") -> str:
        path = self.temp / name
        path.write_text(text, encoding="utf-8")
        return str(path)

    def test_a_printed_file_is_verified_and_written(self) -> None:
        code, output = self.run_main("--log", self.save(self.log), "--dry-run")
        self.assertEqual(0, code, output)
        self.assertFalse(self.target.exists())
        code, output = self.run_main("--log", self.save(self.log))
        self.assertEqual(0, code, output)
        self.assertEqual(self.data, self.target.read_bytes())
        self.assertIn("Prima: assente", output)
        self.assertIn("Dopo:  2 treni, 3 fermate, finestra 2026-10-11 - 2026-11-09", output)
        code, output = self.run_main("--log", self.save(self.log))
        self.assertIn("File identico", output)

    def test_a_file_with_seven_element_stops_replaces_one_with_five(self) -> None:
        # An asset built before the extension (5-element stops) stays valid, so the older-window guard still applies to it.
        self.target.parent.mkdir(parents=True)
        legacy = stats.encode(legacy_payload())
        self.target.write_bytes(legacy)
        older = valid_payload()
        older.update(windowStart="2026-10-04", windowEnd="2026-11-02", generatedAt="2026-11-03T04:00:00Z")
        code, output = self.run_main("--log", self.save(job_log(stats.encode(older)), "older.log"))
        self.assertEqual(1, code)
        self.assertIn("termina prima", output)
        self.assertEqual(legacy, self.target.read_bytes())
        code, output = self.run_main("--log", self.save(self.log))
        self.assertEqual(0, code, output)
        self.assertIn("Prima: 2 treni, 3 fermate", output)
        self.assertEqual(self.data, self.target.read_bytes())
        self.assertEqual(7, len(stats.check_bytes(self.target.read_bytes())["trains"][1]["s"][0]))

    def test_only_one_frame_with_the_statistics_file_is_accepted(self) -> None:
        injected = ("2026-11-10T07:59:59.0000000Z BINARIO_FILE punctuality/stats_v1.json.gz 2 "
                    "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a\n")
        with self.assertRaisesRegex(apply.ApplyError, "fuori dal blocco"):
            apply.printed_statistics([(injected + self.log, None)])
        with self.assertRaisesRegex(apply.ApplyError, "più di un blocco"):
            apply.printed_statistics([(job_log(self.data, "07:59") + self.log, None)])
        other = io.StringIO()
        with contextlib.redirect_stdout(other):
            frames.print_files([(stats.PUBLISHED_PATH, self.data), ("punctuality/extra.json", b"{}")])
        with self.assertRaisesRegex(apply.ApplyError, "soltanto"):
            apply.printed_statistics([(other.getvalue(), None)])
        wrong_path = self.log.replace(stats.PUBLISHED_PATH, "gtfs/stats_v1.json.gz")
        with self.assertRaisesRegex(apply.ApplyError, "soltanto"):
            apply.printed_statistics([(wrong_path, None)])
        lines = self.log.splitlines()
        chunk = next(index for index, line in enumerate(lines) if " BINARIO_B64 " in line)
        lines[chunk] = lines[chunk][:-4] + ("AAAA" if not lines[chunk].endswith("AAAA") else "BBBB")
        with self.assertRaisesRegex(apply.ApplyError, "SHA-256|byte|base64"):
            apply.printed_statistics([("\n".join(lines), None)])
        window = (frames.log_time("2026-11-10T08:00:00"), frames.log_time("2026-11-10T08:00:02"))
        self.assertEqual(self.data, apply.printed_statistics([(self.log, window)]))
        late = (frames.log_time("2026-11-10T08:05:00"), frames.log_time("2026-11-10T08:06:00"))
        with self.assertRaisesRegex(apply.ApplyError, "fuori dallo step di stampa"):
            apply.printed_statistics([(self.log, late)])

    def test_invalid_empty_or_older_statistics_are_refused(self) -> None:
        broken = valid_payload()
        broken["trains"][1]["s"][1][3] = [9, 14, 13, 23, 25, 26, 26, 26]
        code, output = self.run_main("--log", self.save(job_log(stats.encode(broken), checked=False)))
        self.assertEqual(1, code)
        self.assertIn("non supera --check", output)
        self.assertIn("cumulati non monotoni", output)
        empty = dict(valid_payload(), trains=[])
        code, output = self.run_main("--log", self.save(job_log(stats.encode(empty))))
        self.assertEqual(1, code)
        self.assertIn("senza treni", output)
        self.assertFalse(self.target.exists())

        self.target.parent.mkdir(parents=True)
        self.target.write_bytes(self.data)
        older = copy.deepcopy(valid_payload())
        older.update(windowStart="2026-10-04", windowEnd="2026-11-02", generatedAt="2026-11-03T04:00:00Z")
        older_log = self.save(job_log(stats.encode(older)), "older.log")
        code, output = self.run_main("--log", older_log)
        self.assertEqual(1, code)
        self.assertIn("termina prima", output)
        self.assertEqual(self.data, self.target.read_bytes())
        code, output = self.run_main("--log", older_log, "--allow-older")
        self.assertEqual(0, code, output)
        self.assertEqual(stats.encode(older), self.target.read_bytes())

    @staticmethod
    def steps(print_conclusion: str = "success", collect: str = "success") -> list[dict[str, Any]]:
        names = [("Set up job", "success"), ("Collect ViaggiaTreno observations", collect),
                 ("Save the raw observations", "success"), ("Build the statistics", "success")]
        steps = [{"number": number, "name": name, "conclusion": conclusion, "started_at": "2026-11-10T07:50:00Z",
                  "completed_at": "2026-11-10T07:59:59Z"} for number, (name, conclusion) in enumerate(names, start=1)]
        steps.append({"number": 5, "name": "Print the punctuality statistics", "conclusion": print_conclusion,
                      "started_at": "2026-11-10T08:00:00Z", "completed_at": "2026-11-10T08:00:02Z"})
        return steps

    def api(self, run: dict[str, Any], jobs: list[dict[str, Any]], calls: list[str]):
        def respond(path: str) -> bytes:
            calls.append(path)
            if path.endswith("/jobs?per_page=100"):
                return json.dumps({"jobs": jobs}).encode()
            if path.endswith("/logs"):
                return self.log.encode()
            if "/actions/jobs/" in path:
                return json.dumps(next(job for job in jobs if path.endswith(f"/jobs/{job['id']}"))).encode()
            return json.dumps(run).encode()
        return respond

    def test_the_run_must_be_a_dispatch_of_the_punctuality_workflow_on_this_branch(self) -> None:
        run = {"id": 7, "path": ".github/workflows/punctuality.yml@refs/heads/data/refresh", "event": "workflow_dispatch",
               "head_branch": "data/refresh", "head_sha": "c" * 40}
        jobs = [{"id": 70, "run_id": 7, "name": "punctuality", "conclusion": "success", "steps": self.steps()}]
        git = frames.LocalGit("data/refresh", "d" * 40, lambda sha: sha in {"c" * 40, "d" * 40})
        calls: list[str] = []
        logs = apply.fetch_logs("owner/repo", "7", [], git=git, api=self.api(run, jobs, calls))
        self.assertEqual(["70"], [job for job, _, _ in logs])
        self.assertEqual("repos/owner/repo/actions/jobs/70/logs", calls[-1])
        self.assertEqual(self.data, apply.printed_statistics([(text, window) for _, text, window in logs]))
        self.assertEqual(1, len(apply.fetch_logs("owner/repo", None, ["70"], git=git, api=self.api(run, jobs, []))))
        refusals = {
            "invece di .github/workflows/punctuality.yml": dict(run, path=".github/workflows/gtfs-assets-ci.yml"),
            "workflow_dispatch": dict(run, event="schedule"),
            "branch": dict(run, head_branch="main"),
            "antenato": dict(run, head_sha="e" * 40),
        }
        for message, bad_run in refusals.items():
            with self.subTest(message=message), self.assertRaisesRegex(apply.ApplyError, message):
                apply.fetch_logs("owner/repo", "7", [], git=git, api=self.api(bad_run, jobs, []))
        failed = [dict(jobs[0], steps=self.steps(collect="failure"), conclusion="failure")]
        with self.assertRaisesRegex(apply.ApplyError, "prima della stampa"):
            apply.fetch_logs("owner/repo", "7", [], git=git, api=self.api(run, failed, []))
        not_printed = [dict(jobs[0], steps=self.steps(print_conclusion="skipped"))]
        with self.assertRaisesRegex(apply.ApplyError, "nessuno step di stampa"):
            apply.fetch_logs("owner/repo", "7", [], git=git, api=self.api(run, not_printed, []))
        other_job = [dict(jobs[0], name="gtfs-assets")]
        with self.assertRaisesRegex(apply.ApplyError, "nessun job punctuality"):
            apply.fetch_logs("owner/repo", "7", [], git=git, api=self.api(run, other_job, []))


if __name__ == "__main__":
    unittest.main()
