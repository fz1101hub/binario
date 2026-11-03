#!/usr/bin/env python3
"""Files printed in a GitHub Actions job log, and the checks that bind such a print to a reviewed run.

Artifacts are not reachable from the development container, so a dispatch prints the files it built in its job log,
framed by a random nonce that no earlier step can know (audit A7-02, P1)::

    BINARIO_BEGIN <nonce>
    BINARIO_FILE path size sha256
    BINARIO_B64 path chunk
    BINARIO_END path
    BINARIO_DONE <nonce> <files>

``print_files``/``print_directory`` write the frame (in the CI job); ``parse_printed_files`` reads it back (locally),
accepting exactly one frame per log and no ``BINARIO_*`` line outside it; ``fetch_logs`` reads the logs of a run after
checking that the run is a ``workflow_dispatch`` of the expected workflow on the local branch, at ``HEAD`` or one of its
ancestors, that every step up to the print step succeeded, and returns the print step's time window, inside which every
frame line must lie.  ``tools/apply_ci_gtfs_refresh.py`` and ``tools/apply_ci_punctuality.py`` share this module.
"""
from __future__ import annotations

import base64
import hashlib
import json
import re
import secrets
import subprocess
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence

LOG_LINE = re.compile(
    r"^﻿?(?:(?P<time>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.\d+)?Z )?"
    r"(?P<kind>BINARIO_BEGIN|BINARIO_FILE|BINARIO_B64|BINARIO_END|BINARIO_DONE) (?P<rest>.*)$"
)
NONCE = re.compile(r"[0-9a-f]{32}")
# Step times from the API are whole seconds; the frame may start or end within the same second.
WINDOW_SLACK = timedelta(seconds=1)
B64_LINE_CHARS = 4000

Window = tuple[datetime, datetime]


class ApplyError(RuntimeError):
    """The printed files cannot be applied safely; nothing is written."""


# --- Printing (CI job) --------------------------------------------------------------------------------------------


def print_files(files: Sequence[tuple[str, bytes]], nonce: str | None = None) -> None:
    """``files`` (published path, bytes) as one nonce frame on standard output."""
    nonce = nonce or secrets.token_hex(16)
    print(f"BINARIO_BEGIN {nonce}", flush=True)
    for relative, data in files:
        print(f"BINARIO_FILE {relative} {len(data)} {hashlib.sha256(data).hexdigest()}")
        encoded = base64.b64encode(data).decode("ascii")
        for start in range(0, len(encoded), B64_LINE_CHARS):
            print(f"BINARIO_B64 {relative} {encoded[start:start + B64_LINE_CHARS]}")
        print(f"BINARIO_END {relative}", flush=True)
    print(f"BINARIO_DONE {nonce} {len(files)}", flush=True)


def print_directory(directory: Path, nonce: str | None = None, exclude: frozenset[str] = frozenset()) -> None:
    """Every file under ``directory`` (paths relative to it) as one frame; ``exclude`` names top-level subdirectories
    left out."""
    paths = sorted(
        item for item in directory.rglob("*")
        if item.is_file() and item.relative_to(directory).parts[0] not in exclude
    )
    print_files([(path.relative_to(directory).as_posix(), path.read_bytes()) for path in paths], nonce)


# --- Reading (local) ----------------------------------------------------------------------------------------------


def log_time(value: str) -> datetime:
    return datetime.strptime(value[:19], "%Y-%m-%dT%H:%M:%S")


def parse_printed_files(text: str, *, window: Window | None = None) -> dict[str, bytes]:
    """The files of the single ``BINARIO_BEGIN``/``BINARIO_DONE`` frame of a job log, checked against their declared
    size and SHA-256.

    A second frame, or any ``BINARIO_*`` line outside the frame, refuses the whole log: text an earlier step printed
    from a downloaded feed cannot add or replace a file.  With ``window`` (the print step's start and end) every
    ``BINARIO_*`` line must carry a runner timestamp inside it.  Lines are split on ``\\n`` only, as the runner
    splits them: a U+2028 or U+0085 inside a line never starts a new one here.
    """
    open_files: dict[str, tuple[int, str, list[str]]] = {}
    files: dict[str, bytes] = {}
    nonce: str | None = None
    frames = 0
    for raw in text.split("\n"):
        match = LOG_LINE.match(raw.rstrip("\r"))
        if not match:
            continue
        kind, rest, stamp = match.group("kind"), match.group("rest"), match.group("time")
        if window is not None:
            if stamp is None:
                raise ApplyError(f"riga {kind} senza orario del runner: log non attribuibile allo step di stampa")
            moment = log_time(stamp)
            if not window[0] - WINDOW_SLACK <= moment <= window[1] + WINDOW_SLACK:
                raise ApplyError(f"riga {kind} delle {stamp} fuori dallo step di stampa: log rifiutato")
        if kind == "BINARIO_BEGIN":
            if nonce is not None or frames:
                raise ApplyError("più di un blocco BINARIO_BEGIN nel log: stampa non attribuibile, log rifiutato")
            if not NONCE.fullmatch(rest.strip()):
                raise ApplyError(f"BINARIO_BEGIN senza nonce valido: {rest[:80]!r}")
            nonce = rest.strip()
            continue
        if nonce is None:
            raise ApplyError(f"riga {kind} fuori dal blocco BINARIO_BEGIN/BINARIO_DONE: log rifiutato")
        if kind == "BINARIO_DONE":
            pieces = rest.split(" ")
            if len(pieces) != 2 or pieces[0] != nonce or not pieces[1].isdigit():
                raise ApplyError(f"BINARIO_DONE non corrisponde al BINARIO_BEGIN: {rest[:80]!r}")
            if open_files:
                raise ApplyError(f"file incompleti nel log (log troncato?): {', '.join(sorted(open_files))}")
            if int(pieces[1]) != len(files):
                raise ApplyError(f"BINARIO_DONE dichiara {pieces[1]} file, il blocco ne contiene {len(files)}")
            nonce = None
            frames += 1
        elif kind == "BINARIO_FILE":
            pieces = rest.split(" ")
            if len(pieces) != 3 or not pieces[1].isdigit() or not re.fullmatch(r"[0-9a-f]{64}", pieces[2]):
                raise ApplyError(f"riga BINARIO_FILE non valida: {rest[:120]!r}")
            path = pieces[0]
            if path in open_files or path in files:
                raise ApplyError(f"file stampato due volte: {path!r}")
            open_files[path] = (int(pieces[1]), pieces[2], [])
        elif kind == "BINARIO_B64":
            path, _, chunk = rest.partition(" ")
            if path not in open_files:
                raise ApplyError(f"blocco base64 fuori da un file: {path!r}")
            open_files[path][2].append(chunk.strip())
        else:
            path = rest.strip()
            if path not in open_files:
                raise ApplyError(f"BINARIO_END senza BINARIO_FILE: {path!r}")
            size, digest, chunks = open_files.pop(path)
            try:
                data = base64.b64decode("".join(chunks), validate=True)
            except ValueError as error:
                raise ApplyError(f"{path}: base64 non valido") from error
            if len(data) != size:
                raise ApplyError(f"{path}: {len(data)} byte invece di {size}")
            if hashlib.sha256(data).hexdigest() != digest:
                raise ApplyError(f"{path}: SHA-256 diverso da quello stampato")
            files[path] = data
    if nonce is not None or open_files:
        raise ApplyError("blocco BINARIO_BEGIN senza BINARIO_DONE (log troncato?)")
    return files


def merge_printed(logs: Iterable[tuple[str, Window | None]]) -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    for text, window in logs:
        for path, data in parse_printed_files(text, window=window).items():
            if path in files and files[path] != data:
                raise ApplyError(f"{path} stampato da due job con contenuti diversi")
            files[path] = data
    return files


# --- Run verification (local, through the GitHub API) -------------------------------------------------------------


def gh_api(path: str) -> bytes:
    try:
        return subprocess.run(["gh", "api", path], capture_output=True, check=True, timeout=600).stdout
    except (OSError, subprocess.SubprocessError) as error:
        detail = getattr(error, "stderr", b"") or b""
        raise ApplyError(f"gh api {path} fallito: {error} {detail.decode('utf-8', 'replace')[:300]}") from error


@dataclass(frozen=True)
class LocalGit:
    """The branch and commit the printed files are applied to."""

    branch: str
    head: str
    contains: Callable[[str], bool]  # True when the commit is HEAD or one of its ancestors


def local_git(root: Path) -> LocalGit:
    def git(*arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(["git", "-C", str(root), *arguments], capture_output=True, text=True, timeout=60)

    try:
        branch = git("rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
        head = git("rev-parse", "HEAD").stdout.strip()
    except (OSError, subprocess.SubprocessError) as error:
        raise ApplyError(f"git non disponibile in {root}: {error}") from error
    if not branch or branch == "HEAD" or not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ApplyError("serve una branch locale (HEAD non staccato) per verificare la run")
    return LocalGit(branch, head, lambda sha: git("merge-base", "--is-ancestor", sha, "HEAD").returncode == 0)


def check_run(run: Mapping[str, Any], git: LocalGit, workflow_path: str) -> None:
    """The run is a dispatch of ``workflow_path`` on this branch, at HEAD or an ancestor of it."""
    run_id = run.get("id")
    path = str(run.get("path") or "").split("@", 1)[0]
    if path != workflow_path:
        raise ApplyError(f"run {run_id}: workflow {path or '?'} invece di {workflow_path}")
    if run.get("event") != "workflow_dispatch":
        raise ApplyError(f"run {run_id}: evento {run.get('event')!r}, serve un workflow_dispatch")
    if run.get("head_branch") != git.branch:
        raise ApplyError(f"run {run_id}: branch {run.get('head_branch')!r}, quella locale è {git.branch!r}")
    sha = str(run.get("head_sha") or "")
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or not git.contains(sha):
        raise ApplyError(f"run {run_id}: commit {sha[:12] or '?'} non è HEAD né un suo antenato (git fetch?)")


def api_time(value: object, label: str) -> datetime:
    if not isinstance(value, str) or not re.match(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}", value):
        raise ApplyError(f"orario dello step non valido ({label}): {value!r}")
    return log_time(value)


def print_window(job: Mapping[str, Any], print_steps: Mapping[str, str]) -> Window | None:
    """Start and end of the job's print step (``print_steps``: job name -> step name); ``None`` when that step was
    skipped (nothing printed).  Every step before it must have succeeded: a print after a failed step is not applied."""
    name = job.get("name")
    expected = print_steps.get(str(name))
    if expected is None:
        raise ApplyError(f"job {job.get('id')}: {name!r} non è un job di stampa ({', '.join(print_steps)})")
    for step in sorted(job.get("steps") or [], key=lambda item: item.get("number", 0)):
        conclusion = step.get("conclusion")
        if step.get("name") == expected:
            if conclusion == "skipped":
                return None
            if conclusion != "success":
                raise ApplyError(f"job {name}: step di stampa con esito {conclusion!r}")
            return api_time(step.get("started_at"), expected), api_time(step.get("completed_at"), expected)
        if conclusion not in ("success", "skipped"):
            raise ApplyError(f"job {name}: step {step.get('name')!r} con esito {conclusion!r} prima della stampa")
    raise ApplyError(f"job {name}: step {expected!r} assente")


def fetch_logs(
    repo: str,
    run_id: str | None,
    job_ids: Sequence[str],
    *,
    workflow_path: str,
    print_steps: Mapping[str, str],
    git: LocalGit,
    api: Callable[[str], bytes] = gh_api,
) -> list[tuple[str, str, Window | None]]:
    """The log of every print job of the run (or of the named jobs) with its print-step window, after binding the
    run to this branch and commit."""
    jobs: list[Mapping[str, Any]] = []
    runs: dict[str, Mapping[str, Any]] = {}
    if run_id:
        listing = json.loads(api(f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100"))
        jobs += [job for job in listing.get("jobs", []) if job.get("name") in print_steps
                 and job.get("conclusion") in ("success", "failure")]
        if not jobs:
            raise ApplyError(f"nessun job {' o '.join(print_steps)} completato nella run {run_id}")
    for job_id in job_ids:
        jobs.append(json.loads(api(f"repos/{repo}/actions/jobs/{job_id}")))
    logs: list[tuple[str, str, Window | None]] = []
    for job in jobs:
        owner_run = str(job.get("run_id") or run_id or "")
        if not owner_run:
            raise ApplyError(f"job {job.get('id')}: run sconosciuta")
        if owner_run not in runs:
            runs[owner_run] = json.loads(api(f"repos/{repo}/actions/runs/{owner_run}"))
            check_run(runs[owner_run], git, workflow_path)
        window = print_window(job, print_steps)
        if window is None:
            continue
        job_id = str(job["id"])
        logs.append((job_id, api(f"repos/{repo}/actions/jobs/{job_id}/logs").decode("utf-8", "replace"), window))
    if not logs:
        raise ApplyError("nessuno step di stampa eseguito nei job indicati")
    return logs


def default_repo(root: Path) -> str:
    try:
        url = subprocess.run(["git", "-C", str(root), "remote", "get-url", "origin"], capture_output=True, text=True,
                             check=True, timeout=30).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        url = ""
    match = re.search(r"github\.com[/:]([^/]+)/([^/.]+?)(?:\.git)?$", url)
    if not match:
        raise ApplyError("repository GitHub non ricavabile da origin: usare --repo OWNER/NOME")
    return f"{match.group(1)}/{match.group(2)}"
