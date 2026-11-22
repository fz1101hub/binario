#!/usr/bin/env python3
"""Publish every pinned foreign-pack file as a GitHub pre-release (CI only: job ``publish`` of
``.github/workflows/gtfs-foreign-release.yml``, with the job token in ``GH_TOKEN``).

    python3 -I tools/publish_foreign_packs.py --target "$GITHUB_SHA"
    python3 -I tools/publish_foreign_packs.py --target <sha> --dry-run    # what would be created, nothing written

For each pin of ``app/src/main/assets/gtfs/foreign_packs.json`` (Android full ZIP, iPhone per-country index):

* the committed file in ``data/gtfs-foreign/`` must have the pinned size and SHA-256, or nothing at all is published;
* ``gh release view <tag>`` finds the release: it must be a published pre-release holding the one asset, whose bytes
  (``gh release download``) have the pinned SHA-256. Then nothing is done. Any difference fails the run: a release is
  never edited, re-uploaded, replaced or deleted (tags are content-addressed, so the same bytes keep the same tag);
* ``release not found``: ``gh release create <tag> <file> --target <sha> --prerelease --latest=false`` with a title and
  notes naming source, attribution and licence from the manifest, the SHA-256 and the commit.

Only the ``gh`` CLI talks to GitHub. No retries: the first failure of a command fails that file.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import foreign_pack_rules as rules  # noqa: E402
import verify_foreign_packs as pins_verifier  # noqa: E402

MANIFEST_PATH = "app/src/main/assets/gtfs/manifest.json"
COUNTRY_NAMES: Mapping[str, str] = {"CH": "Svizzera", "AT": "Austria", "FR": "Francia"}
PLATFORM_LABELS: Mapping[str, str] = {"android": "Android", "ios": "iPhone"}
LOCAL_DIRS: Mapping[str, str] = {"android": rules.FULL_PACK_DIR, "ios": rules.IOS_FULL_DIR}
DISCLAIMER = "Dati di terzi, non un prodotto ufficiale di Trenitalia, RFI, Italo, SBB, ÖBB o SNCF."
COMMIT = re.compile(r"[0-9a-f]{40}")
NOT_FOUND = "release not found"

Runner = Callable[[Sequence[str]], subprocess.CompletedProcess]


class PublishError(RuntimeError):
    pass


def gh(arguments: Sequence[str]) -> subprocess.CompletedProcess:
    return subprocess.run(["gh", *arguments], capture_output=True, text=True, check=False)


def sha256_file(path: Path) -> str:
    return rules.sha256_file(path)


def release_title(platform: str, pack: Mapping[str, Any]) -> str:
    return (f"Binario · orari completi · {pack['country']} ({pack['sourceId']}) · {PLATFORM_LABELS[platform]} · "
            f"fino al {pack['lastServiceDate']}")


def release_notes(platform: str, pack: Mapping[str, Any], release: Mapping[str, Any], entry: Mapping[str, Any], target: str) -> str:
    kind = "pack GTFS completo per Android" if platform == "android" else "indice orari per iPhone (SQLite compresso zlib)"
    return "\n".join([
        f"Orari ferroviari completi di {COUNTRY_NAMES.get(str(pack['country']), pack['country'])} per l'app Binario: "
        f"{kind}, scaricato dall'app solo su richiesta di chi la usa.",
        "",
        f"- File: `{release['asset']}`, {release['sizeBytes']} byte",
        f"- SHA-256: `{release['sha256']}`",
        f"- Orario valido fino al: {pack['lastServiceDate']}",
        f"- Fonte: {entry.get('sourceUrl')}",
        f"- Attribuzione: {entry.get('attribution')}",
        f"- Licenza: {entry.get('licence')}",
        f"- Commit: {target}",
        "",
        DISCLAIMER,
        "",
    ])


def _json(completed: subprocess.CompletedProcess, what: str) -> Any:
    try:
        return json.loads(completed.stdout)
    except (TypeError, json.JSONDecodeError) as error:
        raise PublishError(f"{what}: risposta di gh non leggibile") from error


def existing_release(tag: str, run: Runner) -> Mapping[str, Any] | None:
    completed = run(["release", "view", tag, "--repo", rules.REPOSITORY, "--json", "tagName,isDraft,isPrerelease,assets"])
    if completed.returncode != 0:
        if NOT_FOUND in (completed.stderr or "").lower():
            return None
        raise PublishError(f"{tag}: gh release view non riuscito ({(completed.stderr or '').strip()[:200]})")
    return _json(completed, tag)


def check_existing(tag: str, release: Mapping[str, Any], found: Mapping[str, Any], run: Runner) -> None:
    """An existing release must hold exactly the pinned bytes; it is never changed."""
    if found.get("tagName") != tag or found.get("isDraft") is not False or found.get("isPrerelease") is not True:
        raise PublishError(f"{tag}: la release esiste ma non è una pre-release pubblicata: va controllata a mano, mai modificata")
    assets = [asset for asset in found.get("assets") or [] if isinstance(asset, Mapping)]
    names = sorted(str(asset.get("name")) for asset in assets)
    if names != [release["asset"]]:
        raise PublishError(f"{tag}: la release ha i file {names}, non solo {release['asset']}: mai modificata")
    with tempfile.TemporaryDirectory(prefix="binario-release-") as directory:
        completed = run(["release", "download", tag, "--repo", rules.REPOSITORY, "--pattern", str(release["asset"]),
                         "--dir", directory])
        downloaded = Path(directory) / str(release["asset"])
        if completed.returncode != 0 or not downloaded.is_file():
            raise PublishError(f"{tag}: download dell'asset esistente non riuscito")
        size, digest = downloaded.stat().st_size, sha256_file(downloaded)
    if size != release["sizeBytes"] or digest != release["sha256"]:
        raise PublishError(f"{tag}: la release esistente ha un altro contenuto (SHA-256 {digest}, {size} byte): "
                           "non viene mai sovrascritta; controllare il tag e il pin")


def publish(
    root: Path,
    target: str,
    *,
    run: Runner = gh,
    dry_run: bool = False,
    out: Any = None,
) -> int:
    """Publish the missing releases of the current pins; returns the number of failures."""
    out = out or sys.stdout
    if not COMMIT.fullmatch(target):
        raise PublishError("--target deve essere uno SHA di commit di 40 caratteri esadecimali")
    repository = os.environ.get("GITHUB_REPOSITORY")
    if repository and repository != rules.REPOSITORY:
        raise PublishError(f"repository {repository}: le release si pubblicano solo in {rules.REPOSITORY}")
    problems = pins_verifier.repository_problems(root)
    if problems:
        raise PublishError("pin non coerenti con i file: " + "; ".join(problems))
    pins = json.loads((root / rules.ANDROID_PINS_PATH).read_text(encoding="utf-8"))
    manifest = json.loads((root / MANIFEST_PATH).read_text(encoding="utf-8"))
    entries = {entry["sourceId"]: entry for entry in manifest["packs"]}
    packs = {pack["sourceId"]: pack for pack in pins["packs"]}
    planned = []
    for platform, source_id, release in pins_verifier.pinned_releases(pins):
        local = root / LOCAL_DIRS[platform] / str(release["asset"])
        if not local.is_file() or local.stat().st_size != release["sizeBytes"] or sha256_file(local) != release["sha256"]:
            raise PublishError(f"{release['tag']}: {local.relative_to(root)} non ha la dimensione e lo SHA-256 del pin")
        planned.append((platform, source_id, release, local))
    if not planned:
        print("Nessun pack estero fissato: nessuna release da pubblicare.", file=out)
        return 0
    failures = 0
    for platform, source_id, release, local in planned:
        tag = str(release["tag"])
        try:
            found = existing_release(tag, run)
            if found is not None:
                check_existing(tag, release, found, run)
                print(f"già pubblicata, stessi byte: {tag}", file=out)
                continue
            if dry_run:
                print(f"da creare: {tag} ({release['asset']}, {release['sizeBytes']} byte)", file=out)
                continue
            pack = packs[source_id]
            with tempfile.TemporaryDirectory(prefix="binario-notes-") as directory:
                notes = Path(directory) / "notes.md"
                notes.write_text(release_notes(platform, pack, release, entries[source_id], target), encoding="utf-8")
                completed = run(["release", "create", tag, str(local), "--repo", rules.REPOSITORY, "--target", target,
                                 "--prerelease", "--latest=false", "--title", release_title(platform, pack),
                                 "--notes-file", str(notes)])
            if completed.returncode != 0:
                raise PublishError(f"{tag}: gh release create non riuscito ({(completed.stderr or '').strip()[:200]})")
            created = existing_release(tag, run)
            if created is None:
                raise PublishError(f"{tag}: release creata ma non trovata")
            check_existing(tag, release, created, run)
            print(f"pubblicata: {tag} ({release['asset']}, {release['sizeBytes']} byte)", file=out)
        except PublishError as error:
            failures += 1
            print(f"ERRORE {error}", file=out)
    return failures


def main(argv: Sequence[str] | None = None, *, run: Runner = gh) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--target", required=True, help="commit del push (GITHUB_SHA): il tag nasce lì")
    parser.add_argument("--dry-run", action="store_true", help="mostra le release da creare senza crearle")
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    try:
        failures = publish(args.root.resolve(), args.target, run=run, dry_run=args.dry_run)
    except (PublishError, OSError, ValueError, KeyError) as error:
        print(f"ERRORE pubblicazione: {error}", file=sys.stderr)
        return 1
    if failures:
        print(f"Pubblicazione non riuscita per {failures} file.", file=sys.stderr)
        return 1
    print("Foreign pack releases: every pin is published.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
