#!/usr/bin/env python3
"""Refresh the core embedded GTFS packs (``app/src/main/assets/gtfs/``) on a CI runner.

Run from the repository root with ``python3 -I`` by the ``core-packs`` job of ``gtfs-assets-ci.yml`` (input
``transit_packs`` with the token ``refresh-core=all`` or ``refresh-core=id1,id2``): the development container cannot
reach the publishers.  For every selected pack of the core manifest:

* ``oebb-gtfs`` is never downloaded: the ÖBB portal requires the owner to accept its terms of use.  The owner
  downloads the ZIP and rebuilds with ``build_gtfs_packs.py --only oebb-gtfs --source-archive oebb-gtfs=…``;
* a pack pinned to a GitHub commit (``upstream``: Trenitalia, Italo) is checked against the latest commit that touched
  the upstream archive (GitHub API, ``git ls-remote`` as fallback); the archive of that commit is downloaded and, when
  it differs from the pinned one, the pack is rebuilt with the *proposed* pin.  ``feed_info.txt`` and
  ``attributions.txt`` of the new archive are reported for review: adopting the pin stays a reviewed commit;
* every other ``OPEN_DIRECT`` pack is downloaded from its registered HTTPS ``sourceUrl``.

Downloads use ``acquire_transit_sources.download`` (HTTPS only, byte cap, resume, retries on 5xx).  An archive equal
to the one recorded in the manifest (``sourceArchiveSha256``) is not rebuilt.  Every changed source is built alone
with ``build_gtfs_packs.build_packs(only=[id])`` on a staging copy of the directory, so a failing source leaves its
previous pack and manifest entry in place and is reported; a rebuilt pack whose canonical content is identical to the
previous one (``contentSha256``) is put back byte for byte.  Everything downloaded is untrusted data read only by this
repository's standard-library code; nothing fetched is executed.

Outputs under ``--out``:

* ``staging/gtfs/``: the complete refreshed directory (verified with ``build_gtfs_packs.py --check``);
* ``publish/gtfs/``: ``manifest.json``, ``SHA256SUMS`` and only the changed ZIPs (empty when nothing changed);
* ``publish/core-refresh.json``: per-pack outcome, proposed upstream pins and their metadata.

``publish/`` is printed in the job log with ``acquire_transit_sources.py --print-dir`` and applied locally with
``tools/apply_ci_gtfs_refresh.py``.  ``--freshness`` writes the validity table of the packs (days left against each
pack's ``minValidDays``) for the job summary.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from datetime import date
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import acquire_transit_sources as acquire  # noqa: E402
import build_gtfs_packs as builder  # noqa: E402

CORE_DIR = ROOT / "app/src/main/assets/gtfs"
TRANSIT_DIR = ROOT / "app/src/main/assets/gtfs-transit"
PERSONAL_DIR = ROOT / "app/src/personal/assets/gtfs-personal"
REPORT_NAME = "core-refresh.json"
REPORT_VERSION = 1
# Publishers whose terms the owner accepts in person on their portal: never downloaded by automation.
MANUAL_SOURCES: Mapping[str, str] = {
    "oebb-gtfs": (
        "manuale: il portale ÖBB richiede che il titolare accetti le condizioni d'uso; caricare lo ZIP e ricostruire "
        "con build_gtfs_packs.py --only oebb-gtfs --source-archive oebb-gtfs=/percorso/GTFS.zip"
    ),
}
UPSTREAM_METADATA_MEMBERS = ("feed_info.txt", "attributions.txt")
MAX_UPSTREAM_METADATA_BYTES = 64 * 1024
GITHUB_API = "https://api.github.com"
MAX_API_RESPONSE_BYTES = 1024 * 1024
PINNED_NOTE = re.compile(r"Pinned to the upstream commit of \d{4}-\d{2}-\d{2}")
DEFAULT_MIN_VALID_DAYS = 30

Fetch = Callable[[str, Path], dict[str, Any]]
Resolve = Callable[[Mapping[str, Any]], dict[str, Any]]


class RefreshError(RuntimeError):
    """A source cannot be refreshed safely; its previous pack stays published."""


def _is_commit(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{40}", value) is not None


def resolve_upstream(
    upstream: Mapping[str, Any],
    *,
    token: str | None = None,
    opener: Any | None = None,
    run: Callable[..., Any] = subprocess.run,
    attempts: int = 3,
) -> dict[str, Any]:
    """The latest commit of the upstream repository that touched ``upstream.path``.

    The GitHub API names the commit and its date; the token, when given, is sent only to api.github.com and never on
    a redirect (``add_unredirected_header``).  Without the API, ``git ls-remote`` names the head commit (no date).
    """
    repository = str(upstream.get("repository", ""))
    slug = repository.removeprefix("https://github.com/")
    path = str(upstream.get("path", ""))
    if not repository.startswith("https://github.com/") or slug.count("/") != 1 or not path:
        raise RefreshError(f"upstream non valido: {repository} {path}")
    url = f"{GITHUB_API}/repos/{slug}/commits?" + urllib.parse.urlencode({"path": path, "per_page": "1"})
    opener = opener or urllib.request.build_opener(acquire.HttpsOnlyRedirects(False))
    api_error: Exception | None = None
    for attempt in range(attempts):
        request = urllib.request.Request(url, headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": acquire.USER_AGENT,
            "X-GitHub-Api-Version": "2022-11-28",
        })
        if token:
            request.add_unredirected_header("Authorization", f"Bearer {token}")
        try:
            with opener.open(request, timeout=30) as response:
                payload = json.loads(response.read(MAX_API_RESPONSE_BYTES + 1)[:MAX_API_RESPONSE_BYTES])
            first = payload[0]
            commit = first["sha"]
            when = str(first["commit"]["committer"]["date"])[:10]
            date.fromisoformat(when)
            if not _is_commit(commit):
                raise RefreshError(f"commit non valido dall'API: {commit!r}")
            return {"commit": commit, "commitDate": when, "resolvedBy": "github-api"}
        except (OSError, ValueError, KeyError, IndexError, TypeError, RefreshError, acquire.AcquisitionError) as error:
            api_error = error
            retryable = isinstance(error, urllib.error.HTTPError) and error.code in acquire.RETRYABLE_STATUS
            if not retryable or attempt == attempts - 1:
                break
            time.sleep(5 * (attempt + 1))
    try:
        result = run(
            ["git", "ls-remote", "--exit-code", f"{repository}.git", "HEAD"],
            capture_output=True, text=True, timeout=60, check=False,
            env={**os.environ, "GIT_TERMINAL_PROMPT": "0"},
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise RefreshError(f"commit upstream non risolto ({api_error}; git ls-remote: {error})") from error
    head = (result.stdout or "").split()
    if result.returncode != 0 or not head or not _is_commit(head[0]):
        raise RefreshError(f"commit upstream non risolto ({api_error}; git ls-remote rc={result.returncode})")
    return {"commit": head[0], "commitDate": None, "resolvedBy": "git-ls-remote", "apiError": str(api_error)}


def upstream_metadata(archive_path: Path) -> dict[str, str | None]:
    """``feed_info.txt`` and ``attributions.txt`` of an upstream archive as text, for the reviewer (bounded)."""
    texts: dict[str, str | None] = {}
    with zipfile.ZipFile(archive_path) as archive:
        builder.validate_archive_limits(archive, omit_geometry=True)
        for basename in UPSTREAM_METADATA_MEMBERS:
            entry = builder.find_entry(archive, basename)
            if entry is None:
                texts[basename] = None
                continue
            info = archive.getinfo(entry)
            if info.file_size > MAX_UPSTREAM_METADATA_BYTES:
                texts[basename] = f"(omesso: {info.file_size} byte, oltre {MAX_UPSTREAM_METADATA_BYTES})"
                continue
            texts[basename] = archive.read(entry).decode("utf-8-sig", errors="replace")
    return texts


def pack_counts(path: Path) -> dict[str, Any]:
    """Row counts recorded in a built pack (``binario_filter_manifest.json``).  Its ``lastServiceDate`` is not used:
    the manifest's value is the one ``--check`` derives again from the calendar."""
    with zipfile.ZipFile(path) as archive:
        entry = builder.filter_manifest_entry(archive)
        if entry is None:
            return {}
        metadata = json.loads(archive.read(entry).decode("utf-8"))
    counts = metadata.get("counts", {})
    return counts if isinstance(counts, dict) else {}


def entry_summary(pack: Mapping[str, Any], directory: Path) -> dict[str, Any]:
    name = builder.safe_asset_name(str(pack["asset"]))
    summary: dict[str, Any] = {
        "lastServiceDate": pack.get("lastServiceDate"),
        "sha256": pack.get("sha256"),
        "sourceArchiveSha256": pack.get("sourceArchiveSha256"),
        "contentSha256": pack.get("contentSha256"),
    }
    if (directory / name).is_file():
        summary["bytes"] = (directory / name).stat().st_size
        summary["counts"] = pack_counts(directory / name)
    if isinstance(pack.get("upstream"), dict):
        summary["upstreamCommit"] = pack["upstream"].get("commit")
    return summary


def proposed_source_manifest(
    manifest: Mapping[str, Any], source_id: str, proposed: Mapping[str, Any] | None
) -> dict[str, Any]:
    """The staging manifest with the proposed upstream pin of one pack (sourceUrl, commit, archive digest, note)."""
    payload = json.loads(json.dumps(manifest))
    if proposed is None:
        return payload
    for pack in payload["packs"]:
        if pack["sourceId"] != source_id:
            continue
        pack["upstream"] = {**pack["upstream"], "commit": proposed["commit"], "archiveSha256": proposed["archiveSha256"]}
        pack["sourceUrl"] = proposed["sourceUrl"]
        if proposed.get("commitDate") and isinstance(pack.get("notes"), str):
            pack["notes"] = PINNED_NOTE.sub(f"Pinned to the upstream commit of {proposed['commitDate']}", pack["notes"])
    return payload


def build_one(
    staging: Path,
    source_id: str,
    archive: Path,
    generated_at: str,
    proposed: Mapping[str, Any] | None,
    work: Path,
) -> tuple[str, dict[str, Any]]:
    """Rebuild one pack in ``staging``; returns ("changed" | "content-unchanged", new entry)."""
    manifest = json.loads((staging / "manifest.json").read_text(encoding="utf-8"))
    previous = next(pack for pack in manifest["packs"] if pack["sourceId"] == source_id)
    name = builder.safe_asset_name(previous["asset"])
    snapshot = {item: (staging / item).read_bytes() for item in ("manifest.json", "SHA256SUMS", name)}
    source_manifest = work / f"{source_id}.manifest.json"
    builder.write_json(source_manifest, proposed_source_manifest(manifest, source_id, proposed))
    output = builder.build_packs(
        staging, staging, manifest_path=source_manifest, generated_at=generated_at,
        input_overrides={source_id: archive}, only=[source_id],
    )
    rebuilt = next(pack for pack in output["packs"] if pack["sourceId"] == source_id)
    if rebuilt.get("contentSha256") == previous.get("contentSha256") and rebuilt.get("filter") == previous.get("filter"):
        # Same filtered timetable (a change upstream outside the kept rows): keep the published bytes and entry.
        for item, data in snapshot.items():
            (staging / item).write_bytes(data)
        return "content-unchanged", previous
    return "changed", rebuilt


def refresh(
    selection: Sequence[str],
    asset_dir: Path,
    out: Path,
    generated_at: str,
    *,
    fetch: Fetch | None = None,
    resolve: Resolve | None = None,
) -> dict[str, Any]:
    """Refresh the selected core packs (empty selection: all) into ``out``; never raises for a single source."""
    fetch = fetch or (lambda url, target: acquire.download(url, target))
    token = os.environ.get("GITHUB_TOKEN") or None
    resolve = resolve or (lambda upstream: resolve_upstream(upstream, token=token))
    manifest = builder.read_manifest(asset_dir / "manifest.json")
    registered = [pack["sourceId"] for pack in manifest["packs"]]
    unknown = sorted(set(selection) - set(registered))
    if unknown:
        raise RefreshError(f"refresh-core con sourceId non registrati in {asset_dir.name}/: {', '.join(unknown)}")
    staging = out / "staging" / asset_dir.name
    publish = out / "publish"
    downloads = out / "downloads"
    work = out / "work"
    if staging.exists():
        shutil.rmtree(staging)
    staging.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(asset_dir, staging)
    for directory in (downloads, work):
        directory.mkdir(parents=True, exist_ok=True)
    report: dict[str, Any] = {
        "schemaVersion": REPORT_VERSION,
        "generatedAt": generated_at,
        "directory": asset_dir.name,
        "selection": list(selection) or "all",
        "packs": {},
    }
    for pack in manifest["packs"]:
        source_id = pack["sourceId"]
        entry: dict[str, Any] = {"previous": entry_summary(pack, asset_dir)}
        report["packs"][source_id] = entry
        if selection and source_id not in selection:
            entry["status"] = "not-selected"
            continue
        if source_id in MANUAL_SOURCES:
            entry["status"] = "manual"
            entry["detail"] = MANUAL_SOURCES[source_id]
            print(f"MANUAL {source_id}: {MANUAL_SOURCES[source_id]}", flush=True)
            continue
        try:
            if pack.get("accessMode") != "OPEN_DIRECT":
                raise RefreshError(f"accessMode {pack.get('accessMode')}: serve un archivio acquisito da canale approvato")
            proposed: dict[str, Any] | None = None
            url = str(pack["sourceUrl"])
            upstream = pack.get("upstream")
            if isinstance(upstream, dict):
                resolved = resolve(upstream)
                slug = str(upstream["repository"]).removeprefix("https://github.com/")
                url = f"https://raw.githubusercontent.com/{slug}/{resolved['commit']}/{upstream['path']}"
                proposed = {
                    "repository": upstream["repository"],
                    "path": upstream["path"],
                    "commit": resolved["commit"],
                    "commitDate": resolved.get("commitDate"),
                    "resolvedBy": resolved.get("resolvedBy"),
                    "sourceUrl": url,
                    "previousCommit": upstream.get("commit"),
                }
            print(f"ACQUIRE {source_id} {url}", flush=True)
            target = downloads / f"{source_id}.zip"
            record = fetch(url, target)
            entry["download"] = record
            if not zipfile.is_zipfile(target):
                raise RefreshError("il download non è un archivio ZIP")
            if proposed is not None:
                proposed["archiveSha256"] = record["sha256"]
                entry["proposedUpstream"] = proposed
                entry["upstreamMetadata"] = upstream_metadata(target)
            if record["sha256"] == pack.get("sourceArchiveSha256"):
                entry["status"] = "unchanged"
                print(f"  {source_id}: archivio identico a quello pubblicato", flush=True)
                continue
            status, rebuilt = build_one(staging, source_id, target, generated_at, proposed, work)
            entry["status"] = status
            entry["new"] = entry_summary(rebuilt, staging)
            print(f"  {source_id}: {status} lastServiceDate {pack.get('lastServiceDate')} -> {rebuilt.get('lastServiceDate')}", flush=True)
        except Exception as error:  # noqa: BLE001 - one source failing must not stop the others
            entry["status"] = "failed"
            entry["error"] = f"{type(error).__name__}: {error}"
            print(f"  {source_id}: NON aggiornato ({entry['error']})", flush=True)
        finally:
            (downloads / f"{source_id}.zip").unlink(missing_ok=True)
    builder.verify_assets(staging, as_of=None, min_valid_days=None)
    if publish.exists():
        shutil.rmtree(publish)
    (publish / asset_dir.name).mkdir(parents=True)
    changed = [source_id for source_id, entry in report["packs"].items() if entry.get("status") == "changed"]
    if changed:
        final = builder.read_manifest(staging / "manifest.json")
        for pack in final["packs"]:
            if pack["sourceId"] in changed:
                name = builder.safe_asset_name(pack["asset"])
                shutil.copyfile(staging / name, publish / asset_dir.name / name)
        for item in ("manifest.json", "SHA256SUMS"):
            shutil.copyfile(staging / item, publish / asset_dir.name / item)
    report["changed"] = changed
    report["failed"] = [source_id for source_id, entry in report["packs"].items() if entry.get("status") == "failed"]
    (publish / REPORT_NAME).write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    shutil.rmtree(work, ignore_errors=True)
    return report


def _cell(value: object) -> str:
    text = "" if value is None else str(value)
    return text.replace("|", "\\|").replace("\r", " ").replace("\n", " ")[:300]


STATUS_LABELS = {
    "changed": "aggiornato",
    "content-unchanged": "invariato (orario filtrato identico)",
    "unchanged": "invariato (archivio identico)",
    "failed": "NON aggiornato",
    "manual": "manuale",
    "not-selected": "non selezionato",
}


def report_markdown(report: Mapping[str, Any]) -> str:
    lines = [
        f"### Refresh dei pack principali ({report['directory']}/, {report['generatedAt']})",
        "",
        "| Pack | Esito | lastServiceDate | Viaggi | Note |",
        "| --- | --- | --- | --- | --- |",
    ]
    for source_id, entry in report["packs"].items():
        previous = entry.get("previous", {})
        new = entry.get("new", previous)
        dates = previous.get("lastServiceDate")
        if new.get("lastServiceDate") != previous.get("lastServiceDate"):
            dates = f"{previous.get('lastServiceDate')} → {new.get('lastServiceDate')}"
        trips_before = previous.get("counts", {}).get("trips")
        trips_after = new.get("counts", {}).get("trips")
        trips = trips_before if trips_after == trips_before else f"{trips_before} → {trips_after}"
        note = entry.get("error") or entry.get("detail") or ""
        proposed = entry.get("proposedUpstream")
        if proposed:
            note = (f"commit {str(proposed['commit'])[:12]} ({proposed.get('commitDate') or 'data ignota'}), "
                    f"fissato {str(proposed.get('previousCommit'))[:12]}" + (f"; {note}" if note else ""))
        lines.append(
            f"| {_cell(source_id)} | {_cell(STATUS_LABELS.get(entry.get('status'), entry.get('status')))} | "
            f"{_cell(dates)} | {_cell(trips)} | {_cell(note)} |"
        )
    return "\n".join(lines) + "\n"


def print_upstream_review(report: Mapping[str, Any]) -> None:
    """The proposed upstream pins with feed_info.txt and attributions.txt of their archives (untrusted text: printed
    only while workflow commands are stopped, see main)."""
    reviews = {source_id: entry for source_id, entry in report["packs"].items() if entry.get("proposedUpstream")}
    for source_id, entry in reviews.items():
        proposed = entry["proposedUpstream"]
        block = {key: proposed.get(key) for key in ("commit", "commitDate", "archiveSha256", "sourceUrl", "previousCommit")}
        print(f"UPSTREAM {source_id} {entry.get('status')} {json.dumps(block, sort_keys=True)}")
        for name, text in entry.get("upstreamMetadata", {}).items():
            print(f"UPSTREAM {source_id} {name}:")
            for line in (text if text is not None else "(assente)").splitlines()[:200]:
                print(f"UPSTREAM {source_id} | {line}")


def freshness_rows(directories: Sequence[Path], as_of: date, default_min_valid_days: int = DEFAULT_MIN_VALID_DAYS) -> list[dict[str, Any]]:
    """Every pack of the given manifest directories; a later directory overrides an earlier one for the same asset."""
    rows: dict[str, dict[str, Any]] = {}
    for directory in directories:
        manifest_path = directory / "manifest.json"
        if not manifest_path.is_file():
            continue
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        for pack in manifest.get("packs", []):
            last = date.fromisoformat(str(pack["lastServiceDate"]))
            threshold = builder.pack_min_valid_days(pack, default_min_valid_days)
            days_left = (last - as_of).days
            source_id = pack["sourceId"]
            if source_id in MANUAL_SOURCES:
                state = "manuale" + (" - SOTTO SOGLIA" if days_left <= threshold else "")
            elif days_left < 0:
                state = "SCADUTO"
            elif days_left <= threshold:
                state = "SOTTO SOGLIA"
            else:
                state = "ok"
            rows[str(pack["asset"])] = {
                "sourceId": source_id,
                "asset": pack["asset"],
                "lastServiceDate": last.isoformat(),
                "daysLeft": days_left,
                "minValidDays": threshold,
                "state": state,
                "origin": str(directory),
            }
    return list(rows.values())


def freshness_markdown(rows: Sequence[Mapping[str, Any]], as_of: date) -> str:
    lines = [
        f"### Validità dei pack al {as_of.isoformat()}",
        "",
        "| Pack | Directory | lastServiceDate | Giorni residui | minValidDays | Stato |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for row in sorted(rows, key=lambda item: (item["daysLeft"], item["sourceId"])):
        directory = str(row["asset"]).split("/", 1)[0]
        lines.append(
            f"| {_cell(row['sourceId'])} | {_cell(directory)} | {row['lastServiceDate']} | {row['daysLeft']} | "
            f"{row['minValidDays']} | {_cell(row['state'])} |"
        )
    below = [row["sourceId"] for row in rows if row["state"] != "ok" and row["state"] != "manuale"]
    lines.append("")
    lines.append(f"Sotto soglia o scaduti: {', '.join(sorted(below)) if below else 'nessuno'}.")
    return "\n".join(lines) + "\n"


def append_summary(path: Path | None, text: str) -> None:
    print(text)
    if path is not None:
        with path.open("a", encoding="utf-8") as summary:
            summary.write(text + "\n")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--selection", help="testo dell'input transit_packs (token refresh-core=all o refresh-core=id,id)")
    parser.add_argument("--asset-dir", type=Path, default=CORE_DIR)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--generated-at")
    parser.add_argument("--summary", type=Path, help="file Markdown a cui aggiungere il riepilogo (GITHUB_STEP_SUMMARY)")
    parser.add_argument("--freshness", type=Path, nargs="+", metavar="DIR", help="directory con manifest.json da riportare")
    parser.add_argument("--as-of", type=date.fromisoformat, default=date.today())
    parser.add_argument("--fail-on-report", type=Path, metavar="REPORT", help="esce con 1 se il report elenca fonti non aggiornate")
    args = parser.parse_args(argv)

    if args.freshness:
        append_summary(args.summary, freshness_markdown(freshness_rows(args.freshness, args.as_of), args.as_of))
        return 0
    if args.fail_on_report:
        report = json.loads(args.fail_on_report.read_text(encoding="utf-8"))
        if report.get("failed"):
            print(f"Fonti non aggiornate (pack precedenti mantenuti): {', '.join(report['failed'])}", file=sys.stderr)
            return 1
        return 0
    if args.selection is None or args.out is None or args.generated_at is None:
        parser.error("--selection, --out e --generated-at sono obbligatori")
    # Error messages and the upstream feed_info/attributions carry text from downloaded archives: the runner must not
    # read any of it as a workflow command.
    marker = uuid.uuid4().hex
    print(f"::stop-commands::{marker}", flush=True)
    try:
        _, _, selection = acquire.selection_from(args.selection)
        if selection is None:
            print("Nessun token refresh-core nell'input: niente da aggiornare.")
            return 0
        report = refresh(selection, args.asset_dir, args.out, args.generated_at)
        print_upstream_review(report)
        append_summary(args.summary, report_markdown(report))
    except (RefreshError, builder.BuildError, acquire.AcquisitionError, OSError) as error:
        print(f"ERRORE refresh: {error}", file=sys.stderr)
        return 1
    finally:
        print(f"::{marker}::", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
