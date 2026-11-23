#!/usr/bin/env python3
"""Apply the GTFS packs printed by a ``GTFS Asset Integrity`` dispatch to the repository.

The jobs ``transit-packs`` and ``core-packs`` of ``.github/workflows/gtfs-assets-ci.yml`` print every file they built
in the log as ``BINARIO_FILE path bytes sha256``, ``BINARIO_B64 path chunk``… and ``BINARIO_END path``, framed by
``BINARIO_BEGIN <nonce>`` and ``BINARIO_DONE <nonce> <files>`` (artifacts are not reachable from the development
container).  This helper:

1. reads the logs (``--run RUN_ID``: every such job of the run; ``--job JOB_ID``; or ``--log FILE`` saved before) and
   rebuilds each file, verifying its size and SHA-256.  Only one nonce frame per log is accepted and no ``BINARIO_*``
   line outside it; with ``--run``/``--job`` the run must be a ``workflow_dispatch`` of ``gtfs-assets-ci.yml`` on the
   current branch whose ``head_sha`` is ``HEAD`` or one of its ancestors, every step up to the print step must have
   succeeded, and the frame must lie inside the print step's time window (audit A7-02, P1);
2. merges each printed directory into ``app/src/main/assets/gtfs/``, ``gtfs-transit/`` or
   ``app/src/personal/assets/gtfs-personal/``; the printed full pack of a foreign pack bundled as its border subset
   (``gtfs-foreign-android/``) goes to ``data/gtfs-foreign/android/`` and its subset is derived again here
   (``build_gtfs_packs.derive_border_subset``); a pack whose canonical content (``contentSha256``) is unchanged keeps
   its published bytes and entry, a pack missing from the print (partial build) stays as it is, and a pack that would
   shrink by more than half (trips or stop times), end earlier than before, or claim a lastServiceDate more than a
   month after its last full-service day (a trickle of trips kept for years) is refused unless ``--accept ID``;
   A pack the core job printed as a reviewed candidate (``tools/core_pack_candidates.json``, its fields as registered)
   is added: manifest entry in sourceId order, ZIP, its ``EmbeddedGtfsPacks.kt`` entry, its iOS source policy entry
   (``androidOnlySources``), and the candidate leaves the registry; its ``RailSourceCatalog.kt`` entry must exist;
3. carries the reviewed Trenitalia/Italo pins (``core-refresh.json``) and the switch of a pack to its reviewed next
   timetable source (``nextTimetable``, Switzerland) into the iOS source policy and ``RailSourceCatalog.kt``, and
   aligns ``lastServiceDate`` in ``EmbeddedGtfsPacks.kt`` and ``TransitGtfsPacks.kt``;
4. runs ``build_gtfs_packs.py --check`` (integrity and the 30-day release gate) for each directory,
   ``verify_embedded_assets.py`` and, when the main manifest or the iOS policy changed, ``build_foreign_packs.py``
   (iPhone base and per-country indexes, foreign-pack pins, ledger and their checks), ``verify_ios_gtfs_schedule.py``
   and ``verify_ios_gtfs_rebuild.py``;
5. prints a table: pack, lastServiceDate old -> new, trips, changed, full and zone size and trips kept of a border
   pack, notes and failures.

Nothing is committed.  Exit status 1 when a pack was refused or a check failed.

    python3 tools/apply_ci_gtfs_refresh.py --run 41419618498
    python3 tools/apply_ci_gtfs_refresh.py --log core.log --log transit.log --dry-run
"""
from __future__ import annotations

import argparse
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
from datetime import date
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import build_android_gtfs_credits as android_credits  # noqa: E402
import build_gtfs_packs as builder  # noqa: E402
import ci_log_frames as frames  # noqa: E402
import foreign_pack_rules as foreign  # noqa: E402
import refresh_core_gtfs as core_refresh  # noqa: E402

DESTINATIONS: Mapping[str, Path] = {
    "gtfs": Path("app/src/main/assets/gtfs"),
    "gtfs-transit": Path("app/src/main/assets/gtfs-transit"),
    "gtfs-personal": Path("app/src/personal/assets/gtfs-personal"),
}
REPORTS = ("acquisition.json", "core-refresh.json")
# Printed full packs of the foreign packs bundled as their border subset (tools/refresh_core_gtfs.py FULL_PRINT_DIR).
FULL_PRINT_DIR = "gtfs-foreign-android"
KOTLIN_PACK_FILES = (
    Path("app/src/main/java/it/binario/app/data/gtfs/EmbeddedGtfsPacks.kt"),
    Path("app/src/main/java/it/binario/app/data/gtfs/TransitGtfsPacks.kt"),
)
# Files naming the pinned upstream URL of a core pack next to the manifest.
PIN_MIRRORS = (
    Path("ios/Resources/binario_gtfs_source_policy_v1.json"),
    Path("app/src/main/java/it/binario/app/data/sources/RailSourceCatalog.kt"),
)
# Reviewed packs not bundled yet (tools/refresh_core_gtfs.py): one printed by the core job is added here.
CANDIDATES = Path("tools/core_pack_candidates.json")
IOS_POLICY = Path("ios/Resources/binario_gtfs_source_policy_v1.json")
CORE_PACKS_KT = Path("app/src/main/java/it/binario/app/data/gtfs/EmbeddedGtfsPacks.kt")
CATALOG_KT = Path("app/src/main/java/it/binario/app/data/sources/RailSourceCatalog.kt")
IOS_INPUTS = (Path("app/src/main/assets/gtfs/manifest.json"), Path("ios/Resources/binario_gtfs_source_policy_v1.json"))
JOB_NAMES = ("core-packs", "transit-packs")
# The step of each job that prints its files; only its frame is read (.github/workflows/gtfs-assets-ci.yml).
PRINT_STEPS: Mapping[str, str] = {"core-packs": "Print the changed core packs", "transit-packs": "Print the built packs"}
WORKFLOW_PATH = ".github/workflows/gtfs-assets-ci.yml"
ASSET_FILE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\.zip")
KOTLIN_PACK = re.compile(
    r'(SourceId\("(?P<id>[^"]+)"\),\s*"(?P<asset>[^"]+)",\s*LocalDate\.of\()(?P<y>\d+), (?P<m>\d+), (?P<d>\d+)(\))'
)
SHRINK_LIMIT = 0.5
# A pack whose lastServiceDate lies more than a month after its last full day (a feed keeping one trip a day for years)
# overstates its validity: the definition and its constants are build_gtfs_packs.thin_tail, shared with the build, which
# clips the reviewed sources at that day instead (THIN_TAIL_CLIP_SOURCES).
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


ApplyError = frames.ApplyError


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
    full_bytes: int | None = None  # border pack: size of the full pack, of the bundled subset, trips kept / full
    zone_bytes: int | None = None
    trips_kept: str | None = None


# --- Logs (shared with tools/apply_ci_punctuality.py in tools/ci_log_frames.py) ---------------------------------


Window = frames.Window
LocalGit = frames.LocalGit
local_git = frames.local_git
gh_api = frames.gh_api
parse_printed_files = frames.parse_printed_files
merge_printed = frames.merge_printed
_log_time = frames.log_time
_api_time = frames.api_time


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
        if len(parts) == 2 and parts[0] == FULL_PRINT_DIR and ASSET_FILE.fullmatch(parts[1]):
            directories.setdefault(FULL_PRINT_DIR, {})[parts[1]] = data
            continue
        if (
            len(parts) != 2
            or parts[0] not in DESTINATIONS
            or not (parts[1] in ("manifest.json", "SHA256SUMS") or ASSET_FILE.fullmatch(parts[1]))
        ):
            raise ApplyError(f"percorso stampato non ammesso: {path!r}")
        directories.setdefault(parts[0], {})[parts[1]] = data
    for directory, contents in directories.items():
        if directory != FULL_PRINT_DIR and "manifest.json" not in contents:
            raise ApplyError(f"{directory}/: file stampati senza manifest.json")
    return directories, reports


def check_run(run: Mapping[str, Any], git: LocalGit) -> None:
    """The run is a dispatch of the GTFS workflow on this branch, at HEAD or an ancestor of it."""
    frames.check_run(run, git, WORKFLOW_PATH)


def print_window(job: Mapping[str, Any]) -> Window | None:
    """Start and end of the job's print step; ``None`` when that step was skipped (nothing printed).  Every step
    before it must have succeeded: a print after a failed step is not applied."""
    return frames.print_window(job, PRINT_STEPS)


def fetch_logs(
    repo: str,
    run_id: str | None,
    job_ids: Sequence[str],
    api: Callable[[str], bytes] | None = None,
    git: LocalGit | None = None,
) -> list[tuple[str, str, Window | None]]:
    """The log of every print job of the run (or of the named jobs) with its print-step window, after binding the
    run to this branch and commit."""
    return frames.fetch_logs(repo, run_id, job_ids, workflow_path=WORKFLOW_PATH, print_steps=PRINT_STEPS,
                             git=git or local_git(ROOT), api=api or gh_api)


def default_repo() -> str:
    return frames.default_repo(ROOT)


# --- Merge --------------------------------------------------------------------------------------------------------


def zip_metadata(data: bytes) -> dict[str, Any]:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entry = builder.filter_manifest_entry(archive)
        if entry is None:
            raise ApplyError("pack senza binario_filter_manifest.json")
        return json.loads(archive.read(entry).decode("utf-8"))


def daily_trips(data: bytes) -> dict[date, int]:
    """Trips running on each service day of a pack (build_gtfs_packs.daily_trip_counts)."""
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        calendar = builder.read_table(archive, "calendar.txt") or builder.empty_calendar()
        exceptions = builder.read_table(archive, "calendar_dates.txt") or builder.empty_calendar_dates()
        trips = builder.read_table(archive, "trips.txt", required=True)
    assert trips is not None
    return builder.daily_trip_counts(trips.rows, calendar, exceptions)


def thin_tail(data: bytes, last_service_date: object) -> str | None:
    """A lastServiceDate kept alive by a trickle of trips long after the timetable's last full day, or None."""
    try:
        last = date.fromisoformat(str(last_service_date))
    except ValueError:
        return None
    tail = builder.thin_tail(daily_trips(data), last)
    if tail is None:
        return None
    return (f"copertura gonfiata: orario completo fino al {tail.full_end.isoformat()} (picco {tail.peak} corse al giorno), "
            f"poi non più di {tail.tail} al giorno fino al {last.isoformat()}")


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
    staged_full: Path | None = None  # data/gtfs-foreign/android as it will be (border packs), None: unchanged
    full_target: Path | None = None
    added: list[str] = field(default_factory=list)  # candidates added to the manifest (tools/core_pack_candidates.json)


def candidate_problem(candidate: Mapping[str, Any], pack: Mapping[str, Any]) -> str | None:
    """Why a printed new entry is not the reviewed candidate (its fields as registered, a dated attribution aside)."""
    expected = core_refresh.candidate_entry(candidate)
    for key, value in expected.items():
        actual = pack.get(key)
        if key == "attribution" and expected.get(core_refresh.ATTRIBUTION_DATED_FIELD) is True and isinstance(actual, str):
            actual = core_refresh.DATED_ATTRIBUTION.sub("", actual)
        if actual != value:
            return f"{key} diverso dal candidato rivisto"
    if builder.FULL_PACK_FIELD in pack:
        return "un candidato è incluso intero (fullPack non ammesso)"
    return None


def merge_directory(
    name: str,
    printed: Mapping[str, bytes],
    target: Path,
    work: Path,
    *,
    skip: set[str],
    accept: set[str],
    job_status: Mapping[str, Mapping[str, Any]],
    full_printed: Mapping[str, bytes] | None = None,
    full_dir: Path | None = None,
    catalogue_path: Path | None = None,
    candidates: Mapping[str, Mapping[str, Any]] | None = None,
) -> MergedDirectory:
    """Merge one printed directory into its published copy, staged under ``work`` and verified.

    A printed entry with ``fullPack`` (a foreign pack bundled as its border subset) comes with its full pack in
    ``full_printed``: the full pack is checked against ``fullPack``, compared with the previous full pack in
    ``full_dir``, and the subset is derived again here; its content must be the one the job derived."""
    full_printed = full_printed or {}
    full_dir = full_dir or builder.DEFAULT_FULL_PACK_DIR
    catalogue_path = catalogue_path or builder.DEFAULT_CATALOGUE
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
        bundled = target / builder.safe_asset_name(pack["asset"])
        row.old_trips = row.new_trips = zip_metadata(bundled.read_bytes()).get("counts", {}).get("trips")
        if isinstance(pack.get(builder.FULL_PACK_FIELD), Mapping):
            # A border pack left as it is still shows its sizes and the trips its subset keeps.
            zone = builder.asset_border_zone(bundled) or {}
            row.full_bytes, row.zone_bytes = pack[builder.FULL_PACK_FIELD].get("sizeBytes"), bundled.stat().st_size
            if "tripsKept" in zone and "tripsInFull" in zone:
                row.trips_kept = f"{zone['tripsKept']}/{zone['tripsInFull']}"
        rows[pack["sourceId"]] = row
    replacements: dict[str, bytes] = {}
    full_replacements: dict[str, bytes] = {}
    manifest_changed = False
    printed_ids: set[str] = set()
    additions: list[dict[str, Any]] = []
    for pack in new_manifest.get("packs", []):
        source_id = str(pack.get("sourceId"))
        printed_ids.add(source_id)
        old = old_by_id.get(source_id)
        if old is None:
            row = rows[source_id] = Row(name, source_id, None, pack.get("lastServiceDate"))
            candidate = (candidates or {}).get(source_id)
            if candidate is None:
                row.failure = "pack nuovo, non registrato: aggiungerlo a mano a manifest e Kotlin"
                continue
            if source_id in skip:
                row.notes.append("candidato escluso con --skip")
                continue
            data = printed.get(builder.safe_asset_name(str(pack.get("asset"))))
            problem = candidate_problem(candidate, pack)
            if problem is None and data is None:
                problem = "ZIP non stampato nel log"
            if problem is None and hashlib.sha256(data).hexdigest() != pack.get("sha256"):
                problem = "SHA-256 dello ZIP diverso dal manifest stampato"
            if problem is not None:
                row.failure = f"candidato non applicato: {problem}"
                continue
            row.new_trips = zip_metadata(data).get("counts", {}).get("trips")
            row.changed = True
            row.notes.append(f"pack nuovo dal registro dei candidati ({CANDIDATES.as_posix()})")
            additions.append(dict(pack))
            replacements[builder.safe_asset_name(str(pack["asset"]))] = data
            continue
        row = rows[source_id]
        if pack.get("asset") != old.get("asset"):
            row.failure = f"asset diverso: {old.get('asset')} -> {pack.get('asset')}"
            continue
        same_source = all(pack.get(key) == old.get(key) for key in SOURCE_FIELDS)
        same_full = (pack.get("fullPack") or {}).get("contentSha256") == (old.get("fullPack") or {}).get("contentSha256")
        if same_source and pack.get("contentSha256") == old.get("contentSha256") and pack.get("filter") == old.get("filter") and same_full:
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
        full = pack.get(builder.FULL_PACK_FIELD)
        if isinstance(full, Mapping):
            # A border pack: the job printed its full pack, the subset is derived again here.
            data = full_printed.get(asset)
            if data is None:
                row.failure = f"pack completo non stampato nel log ({FULL_PRINT_DIR}/{asset})"
                continue
            if hashlib.sha256(data).hexdigest() != full.get("sha256"):
                row.failure = "SHA-256 del pack completo diverso da fullPack del manifest stampato"
                continue
            previous_full = full_dir / asset
            old_meta = zip_metadata(previous_full.read_bytes() if previous_full.is_file() else (target / asset).read_bytes())
            old_end = (old.get("fullPack") or old).get("lastServiceDate")
            new_end = full.get("lastServiceDate")
        else:
            data = printed.get(asset)
            if data is None:
                row.failure = "ZIP non stampato nel log"
                continue
            if hashlib.sha256(data).hexdigest() != pack.get("sha256"):
                row.failure = "SHA-256 dello ZIP diverso dal manifest stampato"
                continue
            old_meta = zip_metadata((target / asset).read_bytes())
            old_end, new_end = old.get("lastServiceDate"), pack.get("lastServiceDate")
        new_meta = zip_metadata(data)
        row.new_trips = new_meta.get("counts", {}).get("trips")
        reasons = suspicious(old_meta.get("counts", {}), new_meta.get("counts", {}), old_end, new_end, data)
        if reasons and source_id not in accept:
            row.failure = f"sospetto, non applicato (rivedere e rilanciare con --accept {source_id}): " + "; ".join(reasons)
            row.new_trips = row.old_trips
            continue
        if reasons:
            row.notes.append("accettato con --accept: " + "; ".join(reasons))
        entry = dict(pack)
        if isinstance(full, Mapping):
            # Full pack and subset under their published names: fullPack.path names data/gtfs-foreign/android/<asset>.
            derive_dir = work / f"{name}-border-{source_id}"
            full_copy, subset_path = derive_dir / "full" / asset, derive_dir / "subset" / asset
            for directory in (full_copy.parent, subset_path.parent):
                directory.mkdir(parents=True, exist_ok=True)
            full_copy.write_bytes(data)
            try:
                derived = builder.derive_border_subset(full_copy, subset_path, source_id, builder.load_zone_catalogue(catalogue_path))
                entry = builder.border_manifest_entry(pack, subset_path, full_copy)
            except (builder.BuildError, OSError, ValueError, zipfile.BadZipFile) as error:
                row.failure = f"sottoinsieme di confine non derivabile: {builder.log_safe(error)}"
                continue
            if derived.content_sha256 != pack.get("contentSha256"):
                row.failure = "sottoinsieme di confine derivato qui diverso da quello del job (catalogo o corridoi diversi?)"
                continue
            if entry[builder.FULL_PACK_FIELD] != dict(full):
                row.failure = "fullPack del manifest stampato diverso dal pack completo stampato"
                continue
            full_replacements[asset] = data
            subset = subset_path.read_bytes()
            border = derived.metadata[builder.BORDER_ZONE_FIELD]
            row.full_bytes, row.zone_bytes = len(data), len(subset)
            row.trips_kept = f"{border['tripsKept']}/{border['tripsInFull']}"
            row.new_trips = border["tripsKept"]
            data = subset
        merged[index[source_id]] = entry
        replacements[asset] = data
        row.new_date = entry.get("lastServiceDate")
        row.changed = True
    for addition in additions:
        # Where the core job put it (before the first pack after it in sourceId order).
        merged = core_refresh.with_pack_in_order(merged, addition)
    border_assets = {builder.safe_asset_name(pack["asset"]) for pack in new_manifest.get("packs", [])
                     if isinstance(pack.get(builder.FULL_PACK_FIELD), Mapping)}
    unexpected = sorted(set(full_printed) - border_assets)
    if unexpected:
        raise ApplyError(f"{FULL_PRINT_DIR}/: pack completi stampati senza fullPack nel manifest stampato: {', '.join(unexpected)}")
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
    staged_full = None
    if any(isinstance(pack.get(builder.FULL_PACK_FIELD), Mapping) for pack in result["packs"]):
        staged_full = work / f"{name}-{FULL_PRINT_DIR}"
        if full_dir.is_dir():
            shutil.copytree(full_dir, staged_full)
        staged_full.mkdir(parents=True, exist_ok=True)
        for asset, data in full_replacements.items():
            (staged_full / asset).write_bytes(data)
        builder.write_full_pack_sums(staged_full, result)
    try:
        builder.verify_assets(staged, as_of=None, min_valid_days=None, full_pack_dir=staged_full, catalogue_path=catalogue_path)
    except builder.BuildError as error:
        raise ApplyError(f"{name}/: la directory unita non supera il controllo di integrità:\n{error}") from error
    return MergedDirectory(name, target, ordered, staged, old_manifest, result,
                           staged_full if full_replacements else None, full_dir, [str(pack["sourceId"]) for pack in additions])


@dataclass(frozen=True)
class SourceUpdate:
    """A new sourceUrl adopted for a pack (and, on a timetable switch, its landing page), with the table note."""
    source_id: str
    old_url: str
    new_url: str
    note: str
    landing_page: str | None = None


def pin_updates(merged: MergedDirectory, report: Mapping[str, Any] | None) -> list[SourceUpdate]:
    """The new sourceUrl of each pack that changed it: an adopted upstream pin, checked against the printed blocks, or
    the switch to the reviewed ``nextTimetable`` of the published entry, checked against the job's report."""
    updates = []
    old_by_id = {pack["sourceId"]: pack for pack in merged.old_manifest["packs"]}
    for pack in merged.new_manifest["packs"]:
        old = old_by_id.get(pack["sourceId"])
        if old is None or pack.get("sourceUrl") == old.get("sourceUrl"):
            # A candidate added in this run has no previous source (its URL is the reviewed one).
            continue
        entry = ((report or {}).get("packs", {}).get(pack["sourceId"], {}) or {})
        if "upstream" not in pack:
            following = old.get(builder.NEXT_TIMETABLE_FIELD)
            switch = entry.get("timetableSwitch") or {}
            if (
                not isinstance(following, Mapping)
                or builder.NEXT_TIMETABLE_FIELD in pack
                or pack.get("sourceUrl") != following.get("sourceUrl")
                or switch.get("sourceUrl") != following.get("sourceUrl")
                or switch.get("previousSourceUrl") != old.get("sourceUrl")
                or switch.get("landingPage") != following.get("landingPage")
            ):
                raise ApplyError(f"{pack['sourceId']}: sourceUrl cambiato senza upstream fissato né nextTimetable rivisto")
            updates.append(SourceUpdate(
                pack["sourceId"], str(old["sourceUrl"]), str(pack["sourceUrl"]),
                f"orario successivo dal {following['from']}: {pack['sourceUrl']}", str(following["landingPage"]),
            ))
            continue
        proposed = entry.get("proposedUpstream")
        upstream = pack["upstream"]
        if (
            not proposed
            or proposed.get("commit") != upstream.get("commit")
            or proposed.get("archiveSha256") != upstream.get("archiveSha256")
            or proposed.get("sourceUrl") != pack.get("sourceUrl")
            or upstream.get("archiveSha256") != pack.get("sourceArchiveSha256")
        ):
            raise ApplyError(f"{pack['sourceId']}: pin upstream del manifest diverso dal blocco stampato in core-refresh.json")
        updates.append(SourceUpdate(
            pack["sourceId"], str(old["sourceUrl"]), str(pack["sourceUrl"]),
            f"pin upstream {str(old['upstream']['commit'])[:12]} -> {str(upstream['commit'])[:12]}",
        ))
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


def with_core_pack(text: str, source_id: str, asset: str) -> str:
    """EmbeddedGtfsPacks.kt with a plain entry for ``source_id`` in the core list, before the first entry after it in
    sourceId order (its date is set with the others, from the manifest)."""
    start = text.find("private val corePacks = listOf(")
    end = text.find("\n    )\n", start)
    if start < 0 or end < 0:
        raise ApplyError(f"{CORE_PACKS_KT}: elenco corePacks non trovato")
    entries = list(re.finditer(r'^        EmbeddedGtfsPack\(\s*SourceId\("([^"]+)"\)', text[start:end], re.MULTILINE))
    following = next((match for match in entries if match.group(1) > source_id), None)
    if following is None:
        raise ApplyError(f"{CORE_PACKS_KT}: nessuna voce dopo {source_id}: aggiungerla a mano")
    line = f'        EmbeddedGtfsPack(SourceId("{source_id}"), "{asset}", LocalDate.of(2000, 1, 1)),\n'
    position = start + following.start()
    return text[:position] + line + text[position:]


def candidate_edits(root: Path, added: Sequence[Mapping[str, Any]], candidates: Mapping[str, Mapping[str, Any]]) -> dict[Path, str]:
    """For each candidate added to the manifest: its EmbeddedGtfsPacks.kt entry, its iOS source policy entry
    (androidOnlySources with the reviewed reason) and the candidate registry without it.  The catalogue entry
    (RailSourceCatalog.kt, the pack's countries for PlannedPackOrder) is reviewed code and must already exist."""
    if not added:
        return {}
    kotlin_path, policy_path, registry_path = root / CORE_PACKS_KT, root / IOS_POLICY, root / CANDIDATES
    kotlin = kotlin_path.read_text(encoding="utf-8")
    catalog = (root / CATALOG_KT).read_text(encoding="utf-8")
    policy = json.loads(policy_path.read_text(encoding="utf-8"))
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    for pack in added:
        source_id = str(pack["sourceId"])
        candidate = candidates[source_id]
        if f'SourceId("{source_id}")' not in catalog or f'uri("{candidate["sourceUrl"]}")' not in catalog:
            raise ApplyError(f"{CATALOG_KT}: voce di catalogo di {source_id} con il suo sourceUrl assente")
        kotlin = with_core_pack(kotlin, source_id, str(pack["asset"]))
        listed = {item.get("sourceId") for key in ("sources", "androidOnlySources") for item in policy.get(key, [])}
        if source_id in listed:
            raise ApplyError(f"{IOS_POLICY}: {source_id} già presente")
        policy.setdefault("androidOnlySources", []).append({
            "accessMode": pack["accessMode"], "reason": candidate["iosAndroidOnlyReason"],
            "sourceId": source_id, "sourceUrl": pack["sourceUrl"],
        })
        policy["androidOnlySources"].sort(key=lambda item: str(item.get("sourceId")))
        registry["packs"] = [item for item in registry.get("packs", []) if item.get("sourceId") != source_id]
    return {
        kotlin_path: kotlin,
        policy_path: json.dumps(policy, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        registry_path: json.dumps(registry, ensure_ascii=False, indent=2) + "\n",
    }


def planned_edits(
    root: Path,
    directories: Sequence[MergedDirectory],
    pins: Sequence[SourceUpdate],
    candidates: Mapping[str, Mapping[str, Any]] | None = None,
) -> dict[Path, str]:
    """New text of the Kotlin registries (lastServiceDate of every pack, the entry of an added candidate), of the files
    mirroring a pack's sourceUrl (and landing page) and of the candidate registry."""
    manifests = {item.name: item.new_manifest for item in directories}
    added = [pack for item in directories for pack in item.new_manifest.get("packs", []) if pack["sourceId"] in item.added]
    edits = candidate_edits(root, added, candidates or {})
    dates: dict[str, tuple[str, str]] = {}
    for name, relative in DESTINATIONS.items():
        manifest_path = root / relative / "manifest.json"
        manifest = manifests.get(name) or (json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else None)
        for pack in (manifest or {}).get("packs", []):
            dates[pack["sourceId"]] = (pack["asset"], pack["lastServiceDate"])
    found: set[str] = set()
    for relative in KOTLIN_PACK_FILES:
        path = root / relative
        original = path.read_text(encoding="utf-8")
        text = edits.get(path, original)
        updated = updated_kotlin(text, dates, found)
        if updated != original:
            edits[path] = updated
    missing = sorted(set(dates) - found)
    if missing:
        raise ApplyError(f"pack senza voce in EmbeddedGtfsPacks.kt/TransitGtfsPacks.kt: {', '.join(missing)}")
    # The Android credits are generated from the manifests' attribution and licence (tools/build_android_gtfs_credits.py).
    credits_path = root / android_credits.OUTPUT
    if credits_path.is_file():
        credits = android_credits.render(android_credits.load_manifests(root, manifests))
        if credits != credits_path.read_text(encoding="utf-8"):
            edits[credits_path] = credits
    replacements = [(update.old_url, update.new_url) for update in pins]
    policy_path = root / PIN_MIRRORS[0]
    if any(update.landing_page for update in pins):
        landing = {item["sourceId"]: item.get("landingPage")
                   for item in json.loads(policy_path.read_text(encoding="utf-8")).get("sources", [])}
        for update in pins:
            old_page = landing.get(update.source_id)
            if update.landing_page and old_page and old_page != update.landing_page:
                replacements.append((old_page, update.landing_page))
    for relative in PIN_MIRRORS:
        if not replacements:
            break
        path = root / relative
        text = edits.get(path) or path.read_text(encoding="utf-8")
        for old_text, new_text in replacements:
            text = replace_once(text, old_text, new_text, relative)
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
    if ios:
        # Everything derived from the foreign packs and the main manifest, in one command: iPhone base and
        # per-country indexes (committed bytes kept when unchanged), pins (both copies), ledger, offline checks.
        commands.insert(0, [python, "tools/build_foreign_packs.py"])
    # The second build sits next to the canonical index, as in Apple Watch CI: its metadata records the input paths
    # relative to the output, so a rebuild elsewhere could never compare equal.
    rebuild_zlib = Path("ios/Resources/.apply-rebuild.sqlite.zlib")
    rebuild_metadata = Path("ios/Resources/.apply-rebuild.metadata.json")
    if ios:
        commands += [
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
            if ios and command[1] in ("tools/build_foreign_packs.py", "tools/build_ios_gtfs_schedule.py") and completed.returncode != 0:
                break
    finally:
        for path in (rebuild_zlib, rebuild_metadata):
            (root / path).unlink(missing_ok=True)
    return results


# --- Report -------------------------------------------------------------------------------------------------------


def table(rows: Sequence[Row]) -> str:
    header = ("Directory", "Pack", "lastServiceDate", "Viaggi", "Cambiato", "Completo MB", "Zona MB", "Corse tenute", "Note / errori")
    lines = [header]
    for row in rows:
        # A pack added in this run has no previous date or count: only the new ones.
        dates = str(row.old_date) if row.old_date == row.new_date else str(row.new_date) if row.old_date is None else f"{row.old_date} -> {row.new_date}"
        trips = str(row.old_trips) if row.old_trips == row.new_trips else str(row.new_trips) if row.old_trips is None else f"{row.old_trips} -> {row.new_trips}"
        notes = "; ".join(note for note in row.notes if note)
        if row.job_failure:
            notes = f"NON AGGIORNATO dal job: {row.job_failure}" + (f"; {notes}" if notes else "")
        if row.failure:
            notes = f"RIFIUTATO: {row.failure}" + (f"; {notes}" if notes else "")
        megabytes = [f"{value / 1_000_000:.2f}" if value is not None else "-" for value in (row.full_bytes, row.zone_bytes)]
        lines.append((row.directory, row.source_id, dates, trips, "sì" if row.changed else "no", *megabytes, row.trips_kept or "-", notes))
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
                blocks.extend(f"    {builder.log_safe(line)}" for line in (text if text is not None else "(assente)").splitlines()[:40])
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
            # A saved log carries no run or step metadata: only the single-frame rule applies.
            logs = [(str(path), path.read_text(encoding="utf-8", errors="replace"), None) for path in args.log]
            print("Log salvati: run, branch e step di stampa non verificabili (usare --run per il controllo completo).")
        else:
            logs = fetch_logs(args.repo or default_repo(), args.run, args.job or [], git=local_git(root))
            if args.save_logs:
                args.save_logs.mkdir(parents=True, exist_ok=True)
                for job, text, _ in logs:
                    (args.save_logs / f"job-{job}.log").write_text(text, encoding="utf-8")
        files = merge_printed((text, window) for _, text, window in logs)
        directories, reports = split_by_directory(files)
        full_printed = directories.pop(FULL_PRINT_DIR, {})
        if not directories and not reports:
            raise ApplyError("nessun file BINARIO_FILE nei log indicati")
        core_report = reports.get("core-refresh.json")
        job_status = (core_report or {}).get("packs", {})
        candidates = core_refresh.load_candidates(root / CANDIDATES)
        work = Path(tempfile.mkdtemp(prefix="binario-apply-gtfs-"))
        try:
            merged: list[MergedDirectory] = []
            for name in DESTINATIONS:
                if name in directories:
                    merged.append(merge_directory(
                        name, directories[name], root / DESTINATIONS[name], work,
                        skip=set(args.skip), accept=set(args.accept), job_status=job_status if name == "gtfs" else {},
                        full_printed=full_printed if name == "gtfs" else None, full_dir=root / foreign.FULL_PACK_DIR,
                        catalogue_path=root / foreign.CATALOGUE_PATH,
                        candidates=candidates if name == "gtfs" else None,
                    ))
                elif name == "gtfs" and core_report:
                    # The core job changed nothing: its outcome per pack is still reported.
                    target = root / DESTINATIONS[name]
                    printed = {"manifest.json": (target / "manifest.json").read_bytes()}
                    merged.append(merge_directory(name, printed, target, work, skip=set(), accept=set(), job_status=job_status))
            core = next((item for item in merged if item.name == "gtfs"), None)
            pins = pin_updates(core, core_report) if core and core.staged else []
            edits = planned_edits(root, merged, pins, candidates)
            rows = [row for item in merged for row in item.rows]
            if core is not None:
                for row in core.rows:
                    row.notes.extend(update.note for update in pins if update.source_id == row.source_id)
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
                if item.staged_full is not None and item.full_target is not None:
                    # Full packs go to data/gtfs-foreign/android, never to the app assets.
                    item.full_target.mkdir(parents=True, exist_ok=True)
                    for path in sorted(item.staged_full.iterdir()):
                        destination = item.full_target / path.name
                        if not destination.is_file() or destination.read_bytes() != path.read_bytes():
                            shutil.copyfile(path, destination)
            for path, text in edits.items():
                path.write_text(text, encoding="utf-8")
        finally:
            shutil.rmtree(work, ignore_errors=True)
    except (ApplyError, core_refresh.RefreshError, builder.BuildError, OSError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        builder.print_error_lines("ERRORE: ", error)
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
