#!/usr/bin/env python3
"""Regenerate everything derived from the foreign timetable packs, in one command.

    python3 -I tools/build_foreign_packs.py            # regenerate, then verify
    python3 -I tools/build_foreign_packs.py --check    # verify only: the generated files are up to date

In order (docs/data-sources/GTFS_PACK_REFRESH.md, «Paesi confinanti»):

1. the licence gate on ``app/src/main/assets/gtfs/``: a foreign pack whose licence is confirmed is bundled as the
   border subset of its full pack in ``data/gtfs-foreign/android/``, any other foreign pack whole
   (``build_gtfs_packs.apply_border_zones``);
2. the iPhone base index and, for each border pack, its per-country index in ``data/gtfs-foreign/ios/``: a rebuild
   whose content equals the committed file (``verify_ios_gtfs_rebuild``: SQLite page layout differs between hosts)
   keeps the committed bytes, so an unchanged pack keeps its pin;
3. the pin file ``app/src/main/assets/gtfs/foreign_packs.json`` and its byte-identical iPhone copy
   ``ios/Resources/binario_gtfs_foreign_packs_v1.json``;
4. the append-only ledger ``data/gtfs-foreign/published.json``: every new pin is added, nothing is removed (with
   ``--ledger-base <ledger of the base branch>``, entries added on this branch and no longer pinned are dropped);
5. ``build_gtfs_packs.py --check --integrity-only``, ``verify_foreign_packs.py``, ``verify_ios_gtfs_schedule.py``
   (base and per-country).

The border zone is the frontalieri corridors of ``foreign_pack_rules.CORRIDORS``: change them there and run this
command.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import build_gtfs_packs as packs  # noqa: E402
import build_ios_gtfs_schedule as ios_builder  # noqa: E402
import foreign_pack_rules as rules  # noqa: E402
import verify_foreign_packs as pins_verifier  # noqa: E402
import verify_ios_gtfs_rebuild as ios_rebuild  # noqa: E402
import verify_ios_gtfs_schedule as ios_verifier  # noqa: E402

ASSET_DIR = Path("app/src/main/assets/gtfs")
BASE_INDEX = Path("ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib")
BASE_METADATA = Path("ios/Resources/binario_gtfs_schedule_v1.metadata.json")
SOURCE_POLICY = Path("ios/Resources/binario_gtfs_source_policy_v1.json")


class GenerationError(RuntimeError):
    pass


def manifest_of(root: Path) -> dict[str, Any]:
    return packs.read_manifest(root / ASSET_DIR / "manifest.json")


def border_entries(manifest: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    return [entry for entry in manifest["packs"] if isinstance(entry.get(packs.FULL_PACK_FIELD), Mapping)]


def _replace_if_changed(
    root: Path,
    canonical: Path,
    canonical_metadata: Path,
    build: Any,
    *,
    foreign_full: str | None,
) -> bool:
    """Rebuild next to the canonical index; keep the committed bytes when the content is the same. True if replaced."""
    stem = f".foreign-rebuild-{foreign_full or 'base'}"
    rebuilt = canonical.parent / f"{stem}.sqlite.zlib"
    rebuilt_metadata = canonical.parent / f"{stem}.metadata.json"
    try:
        build(rebuilt, rebuilt_metadata)
        if canonical.is_file() and canonical_metadata.is_file():
            try:
                ios_rebuild.compare_rebuild(
                    canonical, canonical_metadata, rebuilt, rebuilt_metadata,
                    source_policy_path=root / SOURCE_POLICY, foreign_full=foreign_full,
                )
                return False
            except (ios_verifier.VerificationError, KeyError, TypeError):
                pass
        # The metadata records its inputs relative to its own directory: the rebuild sat next to the canonical file.
        os.replace(rebuilt, canonical)
        os.replace(rebuilt_metadata, canonical_metadata)
        return True
    finally:
        rebuilt.unlink(missing_ok=True)
        rebuilt_metadata.unlink(missing_ok=True)


def rebuild_ios_indexes(root: Path) -> list[str]:
    """The base index and one per-country index per border pack; stale per-country files are removed."""
    changed: list[str] = []
    manifest_path = root / ASSET_DIR / "manifest.json"
    catalogue = root / rules.CATALOGUE_PATH
    full_dir = root / rules.FULL_PACK_DIR
    if _replace_if_changed(
        root, root / BASE_INDEX, root / BASE_METADATA,
        lambda output, metadata: ios_builder.build_database(
            manifest_path, catalogue, output, metadata, source_policy_path=root / SOURCE_POLICY, full_pack_dir=full_dir,
        ),
        foreign_full=None,
    ):
        changed.append(str(BASE_INDEX))
    ios_dir = root / rules.IOS_FULL_DIR
    wanted: set[str] = set()
    for entry in border_entries(manifest_of(root)):
        source_id = str(entry["sourceId"])
        output, metadata = ios_dir / f"{source_id}.sqlite.zlib", ios_dir / f"{source_id}.metadata.json"
        wanted.update({output.name, metadata.name})
        ios_dir.mkdir(parents=True, exist_ok=True)
        if _replace_if_changed(
            root, output, metadata,
            lambda out, meta, source_id=source_id: ios_builder.build_foreign_full_database(
                manifest_path, catalogue, source_id, out, meta, source_policy_path=root / SOURCE_POLICY, full_pack_dir=full_dir,
            ),
            foreign_full=source_id,
        ):
            changed.append(f"{rules.IOS_FULL_DIR}/{output.name}")
    if ios_dir.is_dir():
        for path in sorted(ios_dir.iterdir()):
            if path.name not in wanted:
                path.unlink()
                changed.append(f"{rules.IOS_FULL_DIR}/{path.name} (rimosso)")
        if not any(ios_dir.iterdir()):
            ios_dir.rmdir()
    return changed


def release(platform: str, source_id: str, sha256: str, size: int) -> dict[str, Any]:
    tag = rules.tag_for(platform, source_id, sha256)
    asset = rules.asset_for(platform, source_id)
    return {"tag": tag, "asset": asset, "url": rules.url_for(tag, asset), "sizeBytes": size, "sha256": sha256}


def pins_payload(root: Path) -> dict[str, Any]:
    """The pin file (schema 1, design §2.2 with decision D2) derived from the manifest, the full packs, the
    per-country iPhone indexes and the catalogue."""
    manifest = manifest_of(root)
    catalogue_path = root / rules.CATALOGUE_PATH
    matcher = None
    entries = {entry["sourceId"]: entry for entry in manifest["packs"]}
    result: list[dict[str, Any]] = []
    for source_id in sorted(rules.FOREIGN_PACK_COUNTRIES):
        entry = entries.get(source_id)
        if entry is None:
            raise GenerationError(f"{source_id}: assente dal manifest GTFS")
        full = entry.get(packs.FULL_PACK_FIELD)
        pack: dict[str, Any] = {
            "sourceId": source_id,
            "country": rules.FOREIGN_PACK_COUNTRIES[source_id],
            "borderOnly": isinstance(full, Mapping),
        }
        dated = full if isinstance(full, Mapping) else entry
        pack.update(
            lastServiceDate=dated["lastServiceDate"],
            fullServiceUntil=dated["fullServiceUntil"],
            sourceArchiveSha256=entry["sourceArchiveSha256"],
        )
        if not isinstance(full, Mapping):
            pack.update(servedStationIds=[], zoneStationIds=[], android=None, ios=None)
            result.append(pack)
            continue
        if matcher is None:
            matcher, _ = ios_builder.load_catalog(catalogue_path)
        full_archive = root / rules.FULL_PACK_DIR / packs.safe_asset_name(str(entry["asset"]))
        served, zone = ios_builder.foreign_station_coverage(full_archive, matcher, source_id)
        metadata_path = root / rules.IOS_FULL_DIR / f"{source_id}.metadata.json"
        try:
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise GenerationError(f"{source_id}: metadati dell'indice iPhone per paese non leggibili: {error}") from error
        android = release("android", source_id, str(full["sha256"]), int(full["sizeBytes"]))
        android["contentSha256"] = full["contentSha256"]
        ios = release("ios", source_id, metadata["compression"]["sha256"], int(metadata["compression"]["sizeBytes"]))
        ios["database"] = {key: metadata["database"][key] for key in ("sha256", "sizeBytes", "userVersion", "applicationId")}
        ios["inputFingerprintSha256"] = metadata["inputs"]["fingerprintSha256"]
        pack.update(servedStationIds=sorted(served), zoneStationIds=sorted(zone), android=android, ios=ios)
        result.append(pack)
    return {
        "schemaVersion": rules.PINS_SCHEMA_VERSION,
        "generatedAt": manifest["generatedAt"],
        "repository": rules.REPOSITORY,
        "borderZone": {
            "rule": rules.BORDER_ZONE_RULE,
            "corridors": rules.corridor_names(),
            "italianStations": rules.ITALIAN_STATION_RULE,
            "catalogueSha256": rules.sha256_file(catalogue_path),
            "tripRule": rules.TRIP_RULE,
        },
        "packs": result,
    }


def updated_ledger(
    ledger: Mapping[str, Any] | None, pins: Mapping[str, Any], base: Mapping[str, Any] | None = None
) -> dict[str, Any]:
    """The ledger with every pin not yet recorded appended (first pinned on the pins' generatedAt).

    With ``base`` (the ledger of the base branch), the entries added after it that are no longer pinned are dropped
    first: they were pinned only on this branch, so no release workflow ever publishes them."""
    artifacts = list((ledger or {}).get("artifacts", []))
    base_artifacts = list((base or {}).get("artifacts", []))
    if base is not None and artifacts[: len(base_artifacts)] == base_artifacts:
        pinned = {release["tag"] for _, _, release in pins_verifier.pinned_releases(pins)}
        artifacts = base_artifacts + [entry for entry in artifacts[len(base_artifacts):]
                                      if isinstance(entry, Mapping) and entry.get("tag") in pinned]
    known = {entry.get("tag") for entry in artifacts if isinstance(entry, Mapping)}
    for platform, source_id, pinned in pins_verifier.pinned_releases(pins):
        if pinned["tag"] not in known:
            artifacts.append(pins_verifier.ledger_entry(platform, source_id, pinned, str(pins["generatedAt"])))
            known.add(pinned["tag"])
    return {"schemaVersion": rules.LEDGER_SCHEMA_VERSION, "repository": rules.REPOSITORY, "artifacts": artifacts}


def ledger_bytes(ledger: Mapping[str, Any]) -> bytes:
    """One artifact per line, so that a removed entry shows as a removed line in a diff."""
    lines = [json.dumps(entry, ensure_ascii=False, sort_keys=True) for entry in ledger["artifacts"]]
    body = ",\n    ".join(lines)
    return (
        "{\n"
        f'  "schemaVersion": {json.dumps(ledger["schemaVersion"])},\n'
        f'  "repository": {json.dumps(ledger["repository"])},\n'
        f'  "artifacts": [{chr(10) + "    " + body + chr(10) + "  " if lines else ""}]\n'
        "}\n"
    ).encode("utf-8")


def read_ledger(root: Path) -> dict[str, Any] | None:
    path = root / rules.LEDGER_PATH
    if not path.is_file():
        return None
    ledger = json.loads(path.read_text(encoding="utf-8"))
    problems = pins_verifier.ledger_problems(ledger)
    if problems:
        raise GenerationError("registro non valido: " + "; ".join(problems))
    return ledger


def write_if_changed(path: Path, data: bytes) -> bool:
    if path.is_file() and path.read_bytes() == data:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_bytes(data)
    os.replace(temporary, path)
    return True


def read_ledger_base(path: Path | None) -> dict[str, Any] | None:
    if path is None:
        return None
    base = json.loads(path.read_text(encoding="utf-8"))
    problems = pins_verifier.ledger_problems(base)
    if problems:
        raise GenerationError("registro di base non valido: " + "; ".join(problems))
    return base


def write_pins_and_ledger(root: Path, *, ledger_base: Path | None = None) -> list[str]:
    pins = pins_payload(root)
    problems = pins_verifier.pins_problems(pins)
    if problems:
        raise GenerationError("pin generati non validi: " + "; ".join(problems))
    data = rules.canonical_json_bytes(pins)
    changed = [relative for relative in (rules.ANDROID_PINS_PATH, rules.IOS_PINS_PATH) if write_if_changed(root / relative, data)]
    ledger = updated_ledger(read_ledger(root), pins, read_ledger_base(ledger_base))
    if (ledger["artifacts"] or (root / rules.LEDGER_PATH).is_file()) and write_if_changed(root / rules.LEDGER_PATH, ledger_bytes(ledger)):
        changed.append(rules.LEDGER_PATH)
    return changed


def check(root: Path, *, ledger_base: Path | None = None) -> list[str]:
    """Offline checks of every generated file (no rebuild of the iPhone indexes, which differ between hosts)."""
    problems: list[str] = []
    try:
        packs.verify_assets(root / ASSET_DIR, as_of=None, min_valid_days=None,
                            full_pack_dir=root / rules.FULL_PACK_DIR, catalogue_path=root / rules.CATALOGUE_PATH)
    except packs.BuildError as error:
        problems.append(str(error))
    try:
        expected = rules.canonical_json_bytes(pins_payload(root))
        for relative in (rules.ANDROID_PINS_PATH, rules.IOS_PINS_PATH):
            path = root / relative
            if not path.is_file() or path.read_bytes() != expected:
                problems.append(f"{relative} non è quello generato (eseguire tools/build_foreign_packs.py)")
        ledger = read_ledger(root)
        pins = json.loads(expected)
        if pins_verifier.pinned_releases(pins) and (ledger is None or ledger != updated_ledger(ledger, pins)):
            problems.append(f"{rules.LEDGER_PATH} non registra ogni pin (eseguire tools/build_foreign_packs.py)")
        elif ledger is not None and (root / rules.LEDGER_PATH).read_bytes() != ledger_bytes(ledger):
            problems.append(f"{rules.LEDGER_PATH} non è nel formato generato")
    except (GenerationError, packs.BuildError, ios_builder.BuildError, OSError, ValueError, KeyError) as error:
        problems.append(f"pin non generabili: {error}")
    problems += pins_verifier.repository_problems(root, ledger_base=ledger_base)
    source_policy = root / SOURCE_POLICY
    targets: list[tuple[Path, Path, str | None]] = [(root / BASE_INDEX, root / BASE_METADATA, None)]
    for entry in border_entries(manifest_of(root)):
        source_id = str(entry["sourceId"])
        ios_dir = root / rules.IOS_FULL_DIR
        targets.append((ios_dir / f"{source_id}.sqlite.zlib", ios_dir / f"{source_id}.metadata.json", source_id))
    for output, metadata, source_id in targets:
        try:
            ios_verifier.verify(output, metadata, max_compressed_bytes=ios_verifier.DEFAULT_MAX_COMPRESSED_BYTES,
                                max_database_bytes=ios_verifier.DEFAULT_MAX_DATABASE_BYTES,
                                source_policy_path=source_policy, foreign_full=source_id)
        except ios_verifier.VerificationError as error:
            problems.append(f"{output.relative_to(root)}: {error}")
    return problems


def corridor_notices(root: Path) -> list[str]:
    """Corridor stations naming no stop of their full pack and listed pairs without a trip: not errors (a line closed
    for works, a seasonal service), but a renamed station shows here first."""
    notices: list[str] = []
    catalogue = None
    for entry in border_entries(manifest_of(root)):
        source_id = str(entry["sourceId"])
        name = packs.safe_asset_name(str(entry["asset"]))
        catalogue = catalogue or packs.load_zone_catalogue(root / rules.CATALOGUE_PATH)
        report = packs.corridor_report(source_id, root / ASSET_DIR / name, root / rules.FULL_PACK_DIR / name, catalogue)
        notices += [f"{source_id}: stazione senza fermate nel pack completo: {item}" for item in report.unresolved]
        notices += [f"{source_id}: nessuna corsa diretta {label}" for label, full, _ in report.pairs if full == 0]
    return notices


def regenerate(root: Path, *, ledger_base: Path | None = None) -> dict[str, Any]:
    border = packs.apply_border_zones(root / ASSET_DIR, full_pack_dir=root / rules.FULL_PACK_DIR,
                                      catalogue_path=root / rules.CATALOGUE_PATH)
    changed = rebuild_ios_indexes(root)
    changed += write_pins_and_ledger(root, ledger_base=ledger_base)
    return {"packs": border, "changed": changed, "notices": corridor_notices(root)}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="verifica soltanto che i file generati siano aggiornati")
    parser.add_argument("--ledger-base", type=Path,
                        help="registro del ramo di base: append-only; rigenerando, toglie le voci aggiunte nel ramo e non più fissate")
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    root = args.root.resolve()
    try:
        if not args.check:
            summary = regenerate(root, ledger_base=args.ledger_base)
            header = ("Pack", "Solo zona", "Completo (B)", "Zona (B)", "Corse tenute")
            print("  ".join(header))
            for row in summary["packs"]:
                print("  ".join(str(value if value is not None else "-") for value in (
                    row["sourceId"], "sì" if row["borderOnly"] else "no", row["fullBytes"], row["zoneBytes"],
                    f"{row['tripsKept']}/{row['tripsInFull']}" if row["borderOnly"] else None,
                )))
            for item in summary["changed"]:
                print(f"Aggiornato: {item}")
            for notice in summary["notices"]:
                print(f"Avviso corridoi: {notice}")
    except (GenerationError, packs.BuildError, ios_builder.BuildError, ios_verifier.VerificationError, OSError, ValueError) as error:
        packs.print_error_lines("ERRORE pack esteri: ", error)
        return 1
    problems = check(root, ledger_base=args.ledger_base)
    if problems:
        for problem in problems:
            packs.print_error_lines("ERRORE pack esteri: ", problem)
        return 1
    print("Foreign packs: generated files verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
