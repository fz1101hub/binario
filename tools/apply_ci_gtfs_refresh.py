#!/usr/bin/env python3
"""Apply the GTFS packs printed by a ``GTFS Asset Integrity`` dispatch to the repository.

The jobs ``transit-packs`` and ``core-packs`` of ``.github/workflows/gtfs-assets-ci.yml`` print every file they built
in the log as ``BINARIO_FILE path bytes sha256``, ``BINARIO_B64 path chunk``… and ``BINARIO_END path`` (artifacts are
not reachable from the development container).  This helper:

1. reads the logs (``--run RUN_ID``: every such job of the run; ``--job JOB_ID``; or ``--log FILE`` saved before) and
   rebuilds each file, verifying its size and SHA-256;
2. merges each printed directory into ``app/src/main/assets/gtfs/``, ``gtfs-transit/`` or
   ``app/src/personal/assets/gtfs-personal/``: a pack whose canonical content (``contentSha256``) is unchanged keeps
   its published bytes and entry, a pack missing from the print (partial build) stays as it is, and a pack that would
   shrink by more than half (trips or stop times), end earlier than before, or claim a lastServiceDate more than a
   month after its last full-service day (a trickle of trips kept for years) is refused unless ``--accept ID``;
3. carries the reviewed Trenitalia/Italo pins (``core-refresh.json``) into the iOS source policy and
   ``RailSourceCatalog.kt``, and aligns ``lastServiceDate`` in ``EmbeddedGtfsPacks.kt`` and ``TransitGtfsPacks.kt``;
4. runs ``build_gtfs_packs.py --check`` (integrity and the 30-day release gate) for each directory,
   ``verify_embedded_assets.py`` and, when the main manifest or the iOS policy changed, rebuilds the iOS schedule and
   runs ``verify_ios_gtfs_schedule.py`` and ``verify_ios_gtfs_rebuild.py``;
5. prints a table: pack, lastServiceDate old -> new, trips, changed, notes and failures.

Nothing is committed.  Exit status 1 when a pack was refused or a check failed.

    python3 tools/apply_ci_gtfs_refresh.py --run 41419618498
    python3 tools/apply_ci_gtfs_refresh.py --log core.log --log transit.log --dry-run
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import build_gtfs_packs as builder  # noqa: E402

DESTINATIONS: Mapping[str, Path] = {
    "gtfs": Path("app/src/main/assets/gtfs"),
    "gtfs-transit": Path("app/src/main/assets/gtfs-transit"),
    "gtfs-personal": Path("app/src/personal/assets/gtfs-personal"),
}
REPORTS = ("acquisition.json", "core-refresh.json")
KOTLIN_PACK_FILES = (
    Path("app/src/main/java/it/binario/app/data/gtfs/EmbeddedGtfsPacks.kt"),
    Path("app/src/main/java/it/binario/app/data/gtfs/TransitGtfsPacks.kt"),
)
# Files naming the pinned upstream URL of a core pack next to the manifest.
PIN_MIRRORS = (
    Path("ios/Resources/binario_gtfs_source_policy_v1.json"),
    Path("app/src/main/java/it/binario/app/data/sources/RailSourceCatalog.kt"),
)
IOS_INPUTS = (Path("app/src/main/assets/gtfs/manifest.json"), Path("ios/Resources/binario_gtfs_source_policy_v1.json"))
JOB_NAMES = ("core-packs", "transit-packs")
LOG_LINE = re.compile(
    r"^﻿?(?:\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z )?(BINARIO_FILE|BINARIO_B64|BINARIO_END) (.*)$"
)
ASSET_FILE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\.zip")
KOTLIN_PACK = re.compile(
    r'(SourceId\("(?P<id>[^"]+)"\),\s*"(?P<asset>[^"]+)",\s*LocalDate\.of\()(?P<y>\d+), (?P<m>\d+), (?P<d>\d+)(\))'
)
SHRINK_LIMIT = 0.5
# Coverage is "full" on a day running at least this share of the busiest day's trips; a pack whose lastServiceDate lies
# more than THIN_TAIL_DAYS after its last full day (a feed keeping one trip a day for years) overstates its validity.
FULL_SERVICE_SHARE = 0.25
THIN_TAIL_DAYS = 31
# Entry fields naming where the archive comes from: a change is a new pack even when the timetable is identical.
SOURCE_FIELDS = ("sourceUrl", "accessMode", "upstream")
# Reviewed registry text (tools/transit_packs.json) carried into the manifest even when the timetable is identical.
DESCRIPTIVE_FIELDS = ("attribution", "licence", "licenceEvidence", "notes", "kind", "distribution", "minValidDays")

STATUS_NOTES = {
    "content-unchanged": "archivio nuovo, orario filtrato identico",
    "unchanged": "archivio identico",
    "manual": "manuale (ÖBB: caricare lo ZIP e usare --source-archive)",
    "not-selected": "non selezionato",
}


class ApplyError(RuntimeError):
    """The printed files cannot be applied safely; nothing is written."""


@dataclass
class Row:
    directory: str
    source_id: str
    old_date: str | None
    new_date: str | None
    old_trips: int | None = None
    new_trips: int | None = None
    changed: bool = False
    notes: list[str] = field(default_factory=list)
    failure: str | None = None  # refused here: the previous pack stays
    job_failure: str | None = None  # the CI job could not refresh the source: the previous pack stays


# --- Logs ---------------------------------------------------------------------------------------------------------


def parse_printed_files(text: str) -> dict[str, bytes]:
    """Every file printed in a job log, checked against its declared size and SHA-256."""
    open_files: dict[str, tuple[int, str, list[str]]] = {}
    files: dict[str, bytes] = {}
    for raw in text.splitlines():
        match = LOG_LINE.match(raw)
        if not match:
            continue
        kind, rest = match.groups()
        if kind == "BINARIO_FILE":
            pieces = rest.split(" ")
            if len(pieces) != 3 or not pieces[1].isdigit() or not re.fullmatch(r"[0-9a-f]{64}", pieces[2]):
                raise ApplyError(f"riga BINARIO_FILE non valida: {rest[:120]!r}")
            path = pieces[0]
            if path in open_files or path in files:
                raise ApplyError(f"file stampato due volte: {path}")
            open_files[path] = (int(pieces[1]), pieces[2], [])
        elif kind == "BINARIO_B64":
            path, _, chunk = rest.partition(" ")
            if path not in open_files:
                raise ApplyError(f"blocco base64 fuori da un file: {path}")
            open_files[path][2].append(chunk.strip())
        else:
            path = rest.strip()
            if path not in open_files:
                raise ApplyError(f"BINARIO_END senza BINARIO_FILE: {path}")
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
    if open_files:
        raise ApplyError(f"file incompleti nel log (log troncato?): {', '.join(sorted(open_files))}")
    return files


def merge_printed(logs: Iterable[str]) -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    for text in logs:
        for path, data in parse_printed_files(text).items():
            if path in files and files[path] != data:
                raise ApplyError(f"{path} stampato da due job con contenuti diversi")
            files[path] = data
    return files


def split_by_directory(files: Mapping[str, bytes]) -> tuple[dict[str, dict[str, bytes]], dict[str, Any]]:
    """Asset files per destination directory and the JSON reports; any other path is refused."""
    directories: dict[str, dict[str, bytes]] = {}
    reports: dict[str, Any] = {}
    for path, data in files.items():
        if path in REPORTS:
            try:
                reports[path] = json.loads(data.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise ApplyError(f"{path}: JSON non valido") from error
            continue
        parts = path.split("/")
        if (
            len(parts) != 2
            or parts[0] not in DESTINATIONS
            or not (parts[1] in ("manifest.json", "SHA256SUMS") or ASSET_FILE.fullmatch(parts[1]))
        ):
            raise ApplyError(f"percorso stampato non ammesso: {path!r}")
        directories.setdefault(parts[0], {})[parts[1]] = data
    for directory, contents in directories.items():
        if "manifest.json" not in contents:
            raise ApplyError(f"{directory}/: file stampati senza manifest.json")
    return directories, reports


def gh_api(path: str) -> bytes:
    try:
        return subprocess.run(["gh", "api", path], capture_output=True, check=True, timeout=600).stdout
    except (OSError, subprocess.SubprocessError) as error:
        detail = getattr(error, "stderr", b"") or b""
        raise ApplyError(f"gh api {path} fallito: {error} {detail.decode('utf-8', 'replace')[:300]}") from error


def fetch_logs(repo: str, run_id: str | None, job_ids: Sequence[str], api: Callable[[str], bytes] = gh_api) -> list[tuple[str, str]]:
    jobs = list(job_ids)
    if run_id:
        listing = json.loads(api(f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100"))
        for job in listing.get("jobs", []):
            if job.get("name") in JOB_NAMES and job.get("conclusion") in ("success", "failure"):
                jobs.append(str(job["id"]))
        if not jobs:
            raise ApplyError(f"nessun job {' o '.join(JOB_NAMES)} completato nella run {run_id}")
    return [(job, api(f"repos/{repo}/actions/jobs/{job}/logs").decode("utf-8", "replace")) for job in jobs]


def default_repo() -> str:
    try:
        url = subprocess.run(["git", "-C", str(ROOT), "remote", "get-url", "origin"], capture_output=True, text=True,
                             check=True, timeout=30).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        url = ""
    match = re.search(r"github\.com[/:]([^/]+)/([^/.]+?)(?:\.git)?$", url)
    if not match:
        raise ApplyError("repository GitHub non ricavabile da origin: usare --repo OWNER/NOME")
    return f"{match.group(1)}/{match.group(2)}"


# --- Merge --------------------------------------------------------------------------------------------------------


def zip_metadata(data: bytes) -> dict[str, Any]:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entry = builder.filter_manifest_entry(archive)
        if entry is None:
            raise ApplyError("pack senza binario_filter_manifest.json")
        return json.loads(archive.read(entry).decode("utf-8"))


def daily_trips(data: bytes) -> dict[date, int]:
    """Trips running on each service day of a pack (calendar.txt by weekday, then calendar_dates.txt exceptions)."""
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        calendar = builder.read_table(archive, "calendar.txt") or builder.empty_calendar()
        exceptions = builder.read_table(archive, "calendar_dates.txt") or builder.empty_calendar_dates()
        trips = builder.read_table(archive, "trips.txt", required=True)
    assert trips is not None
    per_service: dict[str, int] = {}
    for trip in trips.rows:
        per_service[trip.get("service_id", "")] = per_service.get(trip.get("service_id", ""), 0) + 1
    patterns: dict[str, tuple[date, date, tuple[bool, ...]]] = {}
    grouped: dict[tuple[date, date, tuple[bool, ...]], int] = {}
    for row in calendar.rows:
        service = row.get("service_id", "")
        pattern = (
            builder.parse_gtfs_date(row.get("start_date", ""), service),
            builder.parse_gtfs_date(row.get("end_date", ""), service),
            tuple(row.get(column, "0").strip() == "1" for column in builder.WEEKDAY_COLUMNS),
        )
        patterns[service] = pattern
        grouped[pattern] = grouped.get(pattern, 0) + per_service.get(service, 0)
    days: dict[date, int] = {}
    for (start, end, weekdays), count in grouped.items():
        day = start
        while count and day <= end:
            if weekdays[day.weekday()]:
                days[day] = days.get(day, 0) + count
            day += timedelta(days=1)
    for row in exceptions.rows:
        service = row.get("service_id", "")
        count = per_service.get(service, 0)
        if not count:
            continue
        day = builder.parse_gtfs_date(row.get("date", ""), service)
        pattern = patterns.get(service)
        scheduled = pattern is not None and pattern[0] <= day <= pattern[1] and pattern[2][day.weekday()]
        if row.get("exception_type", "").strip() == "1" and not scheduled:
            days[day] = days.get(day, 0) + count
        elif row.get("exception_type", "").strip() == "2" and scheduled:
            days[day] = days.get(day, 0) - count
    return {day: count for day, count in days.items() if count > 0}


def thin_tail(data: bytes, last_service_date: object) -> str | None:
    """A lastServiceDate kept alive by a trickle of trips long after the timetable's last full day, or None."""
    try:
        last = date.fromisoformat(str(last_service_date))
    except ValueError:
        return None
    days = {day: count for day, count in daily_trips(data).items() if day <= last}
    if not days:
        return None
    peak = max(days.values())
    full_end = max(day for day, count in days.items() if count >= peak * FULL_SERVICE_SHARE)
    if (last - full_end).days <= THIN_TAIL_DAYS:
        return None
    tail = max(count for day, count in days.items() if day > full_end)
    return (f"copertura gonfiata: orario completo fino al {full_end.isoformat()} (picco {peak} corse al giorno), "
            f"poi non più di {tail} al giorno fino al {last.isoformat()}")


def suspicious(
    old_counts: Mapping[str, Any],
    new_counts: Mapping[str, Any],
    old_end: object,
    new_end: object,
    new_data: bytes | None = None,
) -> list[str]:
    """Why a rebuilt pack needs a human look: it lost more than half of its trips or stop times, ends earlier, or its
    lastServiceDate rests on a thin tail of trips (``thin_tail``).

    Counts come from each ZIP's filter manifest; the dates from the manifests (the ZIP's own lastServiceDate may
    predate a later, stricter derivation that ``--check`` applies to the manifest)."""
    reasons = []
    for key in ("trips", "stopTimes"):
        before, after = old_counts.get(key), new_counts.get(key)
        if isinstance(before, int) and isinstance(after, int) and before > 0 and after < before * SHRINK_LIMIT:
            reasons.append(f"{key} {before} -> {after} (meno della metà)")
    if str(new_end or "") < str(old_end or ""):
        reasons.append(f"termina prima: {old_end} -> {new_end}")
    tail = thin_tail(new_data, new_end) if new_data is not None else None
    if tail:
        reasons.append(tail)
    return reasons


@dataclass
class MergedDirectory:
    name: str
    target: Path
    rows: list[Row]
    staged: Path | None  # None: nothing changes in this directory
    old_manifest: dict[str, Any]
    new_manifest: dict[str, Any]


def merge_directory(
    name: str,
    printed: Mapping[str, bytes],
    target: Path,
    work: Path,
    *,
    skip: set[str],
    accept: set[str],
    job_status: Mapping[str, Mapping[str, Any]],
) -> MergedDirectory:
    """Merge one printed directory into its published copy, staged under ``work`` and verified."""
    old_manifest = json.loads((target / "manifest.json").read_text(encoding="utf-8"))
    try:
        new_manifest = json.loads(printed["manifest.json"].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ApplyError(f"{name}/manifest.json stampato non valido") from error
    old_by_id = {pack["sourceId"]: pack for pack in old_manifest["packs"]}
    merged = [dict(pack) for pack in old_manifest["packs"]]
    index = {pack["sourceId"]: position for position, pack in enumerate(merged)}
    rows: dict[str, Row] = {}
    for pack in old_manifest["packs"]:
        row = Row(name, pack["sourceId"], pack.get("lastServiceDate"), pack.get("lastServiceDate"))
        row.old_trips = row.new_trips = zip_metadata((target / builder.safe_asset_name(pack["asset"])).read_bytes()).get("counts", {}).get("trips")
        rows[pack["sourceId"]] = row
    replacements: dict[str, bytes] = {}
    manifest_changed = False
    printed_ids: set[str] = set()
    for pack in new_manifest.get("packs", []):
        source_id = str(pack.get("sourceId"))
        printed_ids.add(source_id)
        old = old_by_id.get(source_id)
        if old is None:
            rows[source_id] = Row(name, source_id, None, pack.get("lastServiceDate"),
                                  failure="pack nuovo, non registrato: aggiungerlo a mano a manifest e Kotlin")
            continue
        row = rows[source_id]
        if pack.get("asset") != old.get("asset"):
            row.failure = f"asset diverso: {old.get('asset')} -> {pack.get('asset')}"
            continue
        same_source = all(pack.get(key) == old.get(key) for key in SOURCE_FIELDS)
        if same_source and pack.get("contentSha256") == old.get("contentSha256") and pack.get("filter") == old.get("filter"):
            # Same timetable: the published ZIP and its digests stay byte for byte; only reviewed registry text moves.
            entry = dict(old)
            for key in DESCRIPTIVE_FIELDS:
                if key in pack:
                    entry[key] = pack[key]
                else:
                    entry.pop(key, None)
            if entry != old:
                merged[index[source_id]] = entry
                manifest_changed = True
                row.notes.append("orario invariato, descrizione aggiornata dal registro")
            else:
                row.notes.append("orario invariato")
            continue
        if source_id in skip:
            row.notes.append("escluso con --skip")
            continue
        asset = builder.safe_asset_name(old["asset"])
        data = printed.get(asset)
        if data is None:
            row.failure = "ZIP non stampato nel log"
            continue
        if hashlib.sha256(data).hexdigest() != pack.get("sha256"):
            row.failure = "SHA-256 dello ZIP diverso dal manifest stampato"
            continue
        new_meta = zip_metadata(data)
        old_meta = zip_metadata((target / asset).read_bytes())
        row.new_trips = new_meta.get("counts", {}).get("trips")
        reasons = suspicious(
            old_meta.get("counts", {}), new_meta.get("counts", {}), old.get("lastServiceDate"), pack.get("lastServiceDate"), data
        )
        if reasons and source_id not in accept:
            row.failure = f"sospetto, non applicato (rivedere e rilanciare con --accept {source_id}): " + "; ".join(reasons)
            row.new_trips = row.old_trips
            continue
        if reasons:
            row.notes.append("accettato con --accept: " + "; ".join(reasons))
        merged[index[source_id]] = dict(pack)
        replacements[asset] = data
        row.new_date = pack.get("lastServiceDate")
        row.changed = True
    for source_id, row in rows.items():
        status = job_status.get(source_id, {}).get("status")
        if status == "failed":
            row.job_failure = f"{job_status[source_id].get('error')} (pack precedente mantenuto)"
            row.notes = [note for note in row.notes if note != "orario invariato"]
        elif status in STATUS_NOTES and not row.changed:
            row.notes = [STATUS_NOTES[status]]
        elif source_id not in printed_ids and source_id in old_by_id:
            row.notes.append("non ricostruito in questa run")
    ordered = list(rows.values())
    if not replacements and not manifest_changed:
        return MergedDirectory(name, target, ordered, None, old_manifest, old_manifest)
    result = {key: value for key, value in new_manifest.items() if key != "packs"}
    result["packs"] = merged
    staged = work / name
    shutil.copytree(target, staged)
    for asset, data in replacements.items():
        (staged / asset).write_bytes(data)
    builder.write_json(staged / "manifest.json", result)
    builder.write_sha256sums(staged / "SHA256SUMS", result, staged)
    try:
        builder.verify_assets(staged, as_of=None, min_valid_days=None)
    except builder.BuildError as error:
        raise ApplyError(f"{name}/: la directory unita non supera il controllo di integrità:\n{error}") from error
    return MergedDirectory(name, target, ordered, staged, old_manifest, result)


def pin_updates(merged: MergedDirectory, report: Mapping[str, Any] | None) -> list[tuple[str, str, str]]:
    """(sourceId, old sourceUrl, new sourceUrl) of the pinned packs adopted, checked against the printed blocks."""
    updates = []
    old_by_id = {pack["sourceId"]: pack for pack in merged.old_manifest["packs"]}
    for pack in merged.new_manifest["packs"]:
        old = old_by_id[pack["sourceId"]]
        if pack.get("sourceUrl") == old.get("sourceUrl"):
            continue
        if "upstream" not in pack:
            raise ApplyError(f"{pack['sourceId']}: sourceUrl cambiato senza upstream fissato")
        proposed = ((report or {}).get("packs", {}).get(pack["sourceId"], {}) or {}).get("proposedUpstream")
        upstream = pack["upstream"]
        if (
            not proposed
            or proposed.get("commit") != upstream.get("commit")
            or proposed.get("archiveSha256") != upstream.get("archiveSha256")
            or proposed.get("sourceUrl") != pack.get("sourceUrl")
            or upstream.get("archiveSha256") != pack.get("sourceArchiveSha256")
        ):
            raise ApplyError(f"{pack['sourceId']}: pin upstream del manifest diverso dal blocco stampato in core-refresh.json")
        updates.append((pack["sourceId"], str(old["sourceUrl"]), str(pack["sourceUrl"])))
    return updates


# --- Repository edits ---------------------------------------------------------------------------------------------


def updated_kotlin(text: str, dates: Mapping[str, tuple[str, str]], found: set[str]) -> str:
    """``LocalDate.of`` of each ``SourceId("id"), "asset", LocalDate.of(...)`` set to the manifest date."""

    def replace(match: re.Match[str]) -> str:
        source_id = match.group("id")
        if source_id not in dates:
            return match.group(0)
        asset, iso = dates[source_id]
        if match.group("asset") != asset:
            raise ApplyError(f"{source_id}: asset Kotlin {match.group('asset')} diverso dal manifest {asset}")
        found.add(source_id)
        day = date.fromisoformat(iso)
        return f"{match.group(1)}{day.year}, {day.month}, {day.day}{match.group(7)}"

    return KOTLIN_PACK.sub(replace, text)


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise ApplyError(f"{path}: {count} occorrenze di {old} invece di una")
    return text.replace(old, new)


def planned_edits(root: Path, directories: Sequence[MergedDirectory], pins: Sequence[tuple[str, str, str]]) -> dict[Path, str]:
    """New text of the Kotlin registries (lastServiceDate of every pack) and of the files mirroring a pinned URL."""
    manifests = {item.name: item.new_manifest for item in directories}
    dates: dict[str, tuple[str, str]] = {}
    for name, relative in DESTINATIONS.items():
        manifest_path = root / relative / "manifest.json"
        manifest = manifests.get(name) or (json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else None)
        for pack in (manifest or {}).get("packs", []):
            dates[pack["sourceId"]] = (pack["asset"], pack["lastServiceDate"])
    edits: dict[Path, str] = {}
    found: set[str] = set()
    for relative in KOTLIN_PACK_FILES:
        path = root / relative
        text = path.read_text(encoding="utf-8")
        updated = updated_kotlin(text, dates, found)
        if updated != text:
            edits[path] = updated
    missing = sorted(set(dates) - found)
    if missing:
        raise ApplyError(f"pack senza voce in EmbeddedGtfsPacks.kt/TransitGtfsPacks.kt: {', '.join(missing)}")
    for relative in PIN_MIRRORS:
        if not pins:
            break
        path = root / relative
        text = path.read_text(encoding="utf-8")
        for _, old_url, new_url in pins:
            text = replace_once(text, old_url, new_url, relative)
        edits[path] = text
    return edits


# --- Checks -------------------------------------------------------------------------------------------------------


def run_checks(root: Path, *, ios: bool, runner: Callable[..., Any] = subprocess.run) -> list[tuple[str, bool, str]]:
    python = sys.executable
    commands: list[list[str]] = []
    for relative in DESTINATIONS.values():
        directory = root / relative
        if (directory / "manifest.json").is_file():
            commands.append([python, "tools/build_gtfs_packs.py", "--check", "--integrity-only", "--input-dir", str(relative)])
            commands.append([python, "tools/build_gtfs_packs.py", "--check", "--min-valid-days", "30", "--input-dir", str(relative)])
    commands.append([python, "tools/verify_embedded_assets.py"])
    # The second build sits next to the canonical index, as in Apple Watch CI: its metadata records the input paths
    # relative to the output, so a rebuild elsewhere could never compare equal.
    rebuild_zlib = Path("ios/Resources/.apply-rebuild.sqlite.zlib")
    rebuild_metadata = Path("ios/Resources/.apply-rebuild.metadata.json")
    if ios:
        commands += [
            [python, "tools/build_ios_gtfs_schedule.py"],
            [python, "tools/verify_ios_gtfs_schedule.py"],
            [python, "tools/build_ios_gtfs_schedule.py", "--output", str(rebuild_zlib), "--metadata", str(rebuild_metadata)],
            [python, "tools/verify_ios_gtfs_rebuild.py", "--rebuild", str(rebuild_zlib), "--rebuild-metadata", str(rebuild_metadata)],
        ]
    results = []
    try:
        for command in commands:
            completed = runner(command, cwd=root, capture_output=True, text=True, check=False)
            output = ((completed.stdout or "") + (completed.stderr or "")).strip()
            results.append((" ".join(command[1:]), completed.returncode == 0, output[-600:]))
            if ios and command[1] == "tools/build_ios_gtfs_schedule.py" and completed.returncode != 0:
                break
    finally:
        for path in (rebuild_zlib, rebuild_metadata):
            (root / path).unlink(missing_ok=True)
    return results


# --- Report -------------------------------------------------------------------------------------------------------


def table(rows: Sequence[Row]) -> str:
    header = ("Directory", "Pack", "lastServiceDate", "Viaggi", "Cambiato", "Note / errori")
    lines = [header]
    for row in rows:
        dates = str(row.old_date) if row.old_date == row.new_date else f"{row.old_date} -> {row.new_date}"
        trips = str(row.old_trips) if row.old_trips == row.new_trips else f"{row.old_trips} -> {row.new_trips}"
        notes = "; ".join(note for note in row.notes if note)
        if row.job_failure:
            notes = f"NON AGGIORNATO dal job: {row.job_failure}" + (f"; {notes}" if notes else "")
        if row.failure:
            notes = f"RIFIUTATO: {row.failure}" + (f"; {notes}" if notes else "")
        lines.append((row.directory, row.source_id, dates, trips, "sì" if row.changed else "no", notes))
    widths = [max(len(line[column]) for line in lines) for column in range(len(header) - 1)]
    rendered = []
    for number, line in enumerate(lines):
        rendered.append("  ".join(cell.ljust(widths[column]) for column, cell in enumerate(line[:-1])) + "  " + line[-1])
        if number == 0:
            rendered.append("  ".join("-" * width for width in widths) + "  " + "-" * 13)
    return "\n".join(rendered)


def upstream_review(report: Mapping[str, Any] | None) -> str:
    if not report:
        return ""
    blocks = []
    for source_id, entry in report.get("packs", {}).items():
        proposed = entry.get("proposedUpstream")
        if not proposed:
            continue
        blocks.append(
            f"{source_id} ({entry.get('status')}): commit {proposed.get('commit')} del {proposed.get('commitDate') or '?'}"
            f" (fissato: {proposed.get('previousCommit')}), archiveSha256 {proposed.get('archiveSha256')}"
        )
        if entry.get("status") == "changed":
            for name, text in (entry.get("upstreamMetadata") or {}).items():
                blocks.append(f"  {name}:")
                blocks.extend(f"    {line}" for line in (text if text is not None else "(assente)").splitlines()[:40])
    return "\n".join(blocks)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--run", help="id della run del dispatch: legge i job core-packs e transit-packs")
    source.add_argument("--job", action="append", help="id di un job (ripetibile)")
    source.add_argument("--log", action="append", type=Path, help="log di un job già salvato (ripetibile)")
    parser.add_argument("--repo", help="OWNER/NOME (default: da git remote origin)")
    parser.add_argument("--skip", action="append", default=[], metavar="SOURCE_ID", help="non applicare questo pack")
    parser.add_argument("--accept", action="append", default=[], metavar="SOURCE_ID",
                        help="applica un pack segnalato come sospetto (dimezzato o con fine anticipata), dopo revisione")
    parser.add_argument("--save-logs", type=Path, metavar="DIR", help="salva qui i log scaricati")
    parser.add_argument("--dry-run", action="store_true", help="verifica e mostra la tabella senza scrivere nulla")
    parser.add_argument("--no-checks", action="store_true", help="non eseguire le verifiche dopo l'applicazione")
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    root = args.root.resolve()

    try:
        if args.log:
            logs = [(str(path), path.read_text(encoding="utf-8", errors="replace")) for path in args.log]
        else:
            logs = fetch_logs(args.repo or default_repo(), args.run, args.job or [])
            if args.save_logs:
                args.save_logs.mkdir(parents=True, exist_ok=True)
                for job, text in logs:
                    (args.save_logs / f"job-{job}.log").write_text(text, encoding="utf-8")
        files = merge_printed(text for _, text in logs)
        directories, reports = split_by_directory(files)
        if not directories and not reports:
            raise ApplyError("nessun file BINARIO_FILE nei log indicati")
        core_report = reports.get("core-refresh.json")
        job_status = (core_report or {}).get("packs", {})
        work = Path(tempfile.mkdtemp(prefix="binario-apply-gtfs-"))
        try:
            merged: list[MergedDirectory] = []
            for name in DESTINATIONS:
                if name in directories:
                    merged.append(merge_directory(
                        name, directories[name], root / DESTINATIONS[name], work,
                        skip=set(args.skip), accept=set(args.accept), job_status=job_status if name == "gtfs" else {},
                    ))
                elif name == "gtfs" and core_report:
                    # The core job changed nothing: its outcome per pack is still reported.
                    target = root / DESTINATIONS[name]
                    printed = {"manifest.json": (target / "manifest.json").read_bytes()}
                    merged.append(merge_directory(name, printed, target, work, skip=set(), accept=set(), job_status=job_status))
            core = next((item for item in merged if item.name == "gtfs"), None)
            pins = pin_updates(core, core_report) if core and core.staged else []
            edits = planned_edits(root, merged, pins)
            rows = [row for item in merged for row in item.rows]
            if core is not None:
                previous = {pack["sourceId"]: pack for pack in core.old_manifest["packs"]}
                current = {pack["sourceId"]: pack for pack in core.new_manifest["packs"]}
                for row in core.rows:
                    if any(source_id == row.source_id for source_id, _, _ in pins):
                        row.notes.append(
                            f"pin upstream {previous[row.source_id]['upstream']['commit'][:12]} -> "
                            f"{current[row.source_id]['upstream']['commit'][:12]}"
                        )
            print(table(rows))
            review = upstream_review(core_report)
            if review:
                print("\nUpstream Trenitalia/Italo (rivedere feed_info e attribuzioni prima del commit):\n" + review)
            if args.dry_run:
                print("\n--dry-run: nessun file scritto.")
                return 1 if any(row.failure for row in rows) else 0
            for item in merged:
                if item.staged is None:
                    continue
                for path in sorted(item.staged.iterdir()):
                    destination = item.target / path.name
                    if not destination.is_file() or destination.read_bytes() != path.read_bytes():
                        shutil.copyfile(path, destination)
            for path, text in edits.items():
                path.write_text(text, encoding="utf-8")
        finally:
            shutil.rmtree(work, ignore_errors=True)
    except (ApplyError, builder.BuildError, OSError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        print(f"ERRORE: {error}", file=sys.stderr)
        return 1

    changed = [item.name for item in merged if item.staged is not None]
    print(f"\nDirectory aggiornate: {', '.join(changed) if changed else 'nessuna'}")
    for path in edits:
        print(f"Aggiornato: {path.relative_to(root)}")
    refused = [row for row in rows if row.failure]
    checks_ok = True
    if not args.no_checks:
        ios = any(item.name == "gtfs" and item.staged is not None for item in merged) or bool(pins)
        print("\nVerifiche:")
        for label, ok, output in run_checks(root, ios=ios):
            print(f"  [{'ok' if ok else 'ERRORE'}] {label}")
            if not ok:
                checks_ok = False
                print("    " + output.replace("\n", "\n    "))
    return 0 if checks_ok and not refused else 1


if __name__ == "__main__":
    raise SystemExit(main())
