#!/usr/bin/env python3
"""Apply the punctuality statistics printed by a ``Punctuality statistics`` dispatch to the repository.

A ``workflow_dispatch`` of ``.github/workflows/punctuality.yml`` with ``print_stats=true`` prints
``punctuality/stats_v1.json.gz`` in the log of its ``punctuality`` job, framed by ``BINARIO_BEGIN <nonce>`` and
``BINARIO_DONE <nonce> 1`` (``tools/ci_log_frames.py``, shared with ``tools/apply_ci_gtfs_refresh.py``).  This helper:

1. reads the log (``--run RUN_ID``, ``--job JOB_ID`` or ``--log FILE`` saved before); with ``--run``/``--job`` the run
   must be a ``workflow_dispatch`` of ``punctuality.yml`` on the current branch whose ``head_sha`` is ``HEAD`` or one
   of its ancestors, every step up to ``Print the punctuality statistics`` must have succeeded and the single frame
   must lie inside that step's time window;
2. requires exactly the one file ``punctuality/stats_v1.json.gz``, with its printed size and SHA-256, valid for
   ``tools/build_punctuality_stats.py --check`` and with at least one train; a window ending before the one already
   in the repository is refused unless ``--allow-older``, and more than 30 % fewer trains or samples (stop samples
   of all trains) than the file in the repository unless ``--allow-shrink`` (a collection restarted with
   ``bootstrap=true`` or a lost cache);
3. writes ``app/src/main/assets/punctuality/stats_v1.json.gz`` and prints the old and new window, trains and size.

Nothing is committed.  Exit status 1 when the print is refused.

    python3 tools/apply_ci_punctuality.py --run 41620000000
    python3 tools/apply_ci_punctuality.py --log punctuality.log --dry-run
"""
from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import build_punctuality_stats as stats  # noqa: E402
import ci_log_frames as frames  # noqa: E402

WORKFLOW_PATH = ".github/workflows/punctuality.yml"
PRINT_STEPS: Mapping[str, str] = {"punctuality": "Print the punctuality statistics"}
ApplyError = frames.ApplyError
# A week with holidays, a strike or a timetable change has fewer trains; a restarted collection has far fewer.
MAX_SHRINK_PERCENT = 30


def fetch_logs(repo: str, run_id: str | None, job_ids: Sequence[str], *, git: frames.LocalGit,
               api: Callable[[str], bytes] = frames.gh_api) -> list[tuple[str, str, frames.Window | None]]:
    return frames.fetch_logs(repo, run_id, job_ids, workflow_path=WORKFLOW_PATH, print_steps=PRINT_STEPS, git=git, api=api)


def printed_statistics(logs: Sequence[tuple[str, frames.Window | None]]) -> bytes:
    """The single statistics file of the logs, refused when anything else was printed."""
    files = frames.merge_printed(logs)
    if set(files) != {stats.PUBLISHED_PATH}:
        raise ApplyError(f"il log deve stampare soltanto {stats.PUBLISHED_PATH}, non {sorted(files) or 'nessun file'}")
    return files[stats.PUBLISHED_PATH]


def summary(payload: Mapping[str, Any] | None, data: bytes | None) -> str:
    if payload is None or data is None:
        return "assente"
    return f"{stats.describe(payload, len(data))}, sha256 {hashlib.sha256(data).hexdigest()[:16]}"


def volume(payload: Mapping[str, Any]) -> tuple[int, int]:
    """Trains and stop samples of a statistics file."""
    trains = payload["trains"]
    return len(trains), sum(stop[1] for train in trains for stop in train["s"])


def check_volume(old: Mapping[str, Any], new: Mapping[str, Any]) -> None:
    """Refuse ``new`` when it has more than MAX_SHRINK_PERCENT fewer trains or samples than ``old``."""
    for label, before, after in zip(("treni", "campioni"), volume(old), volume(new)):
        if after * 100 < before * (100 - MAX_SHRINK_PERCENT):
            raise ApplyError(f"il file stampato ha {after} {label} contro {before} nel repository "
                             f"(oltre il {MAX_SHRINK_PERCENT} % in meno: raccolta ripartita da zero o cache persa?); "
                             "usare --allow-shrink dopo una verifica")


def apply(data: bytes, root: Path, *, allow_older: bool, dry_run: bool, allow_shrink: bool = False) -> str:
    try:
        payload = stats.check_bytes(data)
    except stats.CheckError as error:
        raise ApplyError(f"{stats.PUBLISHED_PATH} non supera --check:\n{error}") from error
    if not payload["trains"]:
        raise ApplyError("statistiche senza treni: la raccolta non ha ancora dati, nulla da applicare")
    target = root / stats.ASSET_PATH
    old_data = target.read_bytes() if target.is_file() else None
    old_payload = None
    if old_data is not None:
        try:
            old_payload = stats.check_bytes(old_data)
        except stats.CheckError:
            old_payload = None
    lines = [f"Prima: {summary(old_payload, old_data) if old_payload else ('non valido' if old_data else 'assente')}",
             f"Dopo:  {summary(payload, data)}"]
    if old_payload and payload["windowEnd"] < old_payload["windowEnd"] and not allow_older:
        raise ApplyError("\n".join(lines) + "\nla finestra stampata termina prima di quella nel repository "
                                            "(usare --allow-older dopo una verifica)")
    if old_payload and not allow_shrink:
        try:
            check_volume(old_payload, payload)
        except ApplyError as error:
            raise ApplyError("\n".join(lines) + f"\n{error}") from error
    if old_data == data:
        lines.append("File identico: nessuna modifica.")
    elif dry_run:
        lines.append("--dry-run: nessun file scritto.")
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        lines.append(f"Aggiornato: {stats.ASSET_PATH.as_posix()}")
    return "\n".join(lines)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--run", help="id della run del dispatch con print_stats=true")
    source.add_argument("--job", action="append", help="id del job punctuality")
    source.add_argument("--log", type=Path, help="log del job già salvato (senza i controlli su run e step)")
    parser.add_argument("--repo", help="OWNER/NOME (default: da git remote origin)")
    parser.add_argument("--allow-older", action="store_true", help="accetta una finestra che termina prima di quella attuale")
    parser.add_argument("--allow-shrink", action="store_true",
                        help=f"accetta oltre il {MAX_SHRINK_PERCENT} %% di treni o campioni in meno rispetto al file attuale")
    parser.add_argument("--dry-run", action="store_true", help="verifica e mostra il confronto senza scrivere")
    parser.add_argument("--save-log", type=Path, metavar="FILE", help="salva qui il log scaricato")
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    root = args.root.resolve()
    try:
        if args.log:
            print("Log salvato: run, branch e step di stampa non verificabili (usare --run per il controllo completo).")
            logs = [(args.log.read_text(encoding="utf-8", errors="replace"), None)]
        else:
            fetched = fetch_logs(args.repo or frames.default_repo(root), args.run, args.job or [], git=frames.local_git(root))
            if args.save_log:
                args.save_log.write_text("\n".join(text for _, text, _ in fetched), encoding="utf-8")
            logs = [(text, window) for _, text, window in fetched]
        print(apply(printed_statistics(logs), root, allow_older=args.allow_older, dry_run=args.dry_run,
                    allow_shrink=args.allow_shrink))
    except (ApplyError, OSError) as error:
        for line in str(error).split("\n"):
            print(f"ERRORE: {stats.collector.log_safe(line)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
