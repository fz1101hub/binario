#!/usr/bin/env python3
"""Offline verification of the foreign-pack pins, their files and the release ledger.

Checks (docs/data-sources/GTFS_PACK_REFRESH.md, «Paesi confinanti»):

* the schema of ``app/src/main/assets/gtfs/foreign_packs.json`` (schema 1) and that the iPhone copy
  ``ios/Resources/binario_gtfs_foreign_packs_v1.json`` has the same bytes;
* tag, asset and URL rules: ``gtfs-full-<sourceId>-<sha12>`` / ``gtfs-ios-<sourceId>-<sha12>`` under
  ``https://github.com/fz1101hub/binario/releases/download/``;
* the licence gate: a pack is pinned (and bundled as a border subset) exactly when its manifest licence is confirmed;
* every pin equals its file in ``data/gtfs-foreign/`` (size, SHA-256, database digest, input fingerprint), dates and
  archive digest equal the manifest, ``zoneStationIds`` is a subset of ``servedStationIds`` and both equal the
  catalogue ids of the iPhone indexes;
* every pin is recorded in ``data/gtfs-foreign/published.json``, and with ``--ledger-base`` no earlier entry was removed
  or changed and every added entry is a current pin.

Usage::

    python3 -I tools/verify_foreign_packs.py                      # the repository
    python3 -I tools/verify_foreign_packs.py --ledger-base base.json
    python3 -I tools/verify_foreign_packs.py --schema-only --pins tests/fixtures/foreign_packs_v1.sample.json
"""
from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys
import tempfile
import zlib
from datetime import date
from pathlib import Path
from typing import Any, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import foreign_pack_rules as rules  # noqa: E402

APPLICATION_ID = int.from_bytes(b"BNGT", "big")
USER_VERSION = 2
MAX_PINS_BYTES = 1024 * 1024
MAX_DATABASE_BYTES = 256 * 1024 * 1024
PIN_KEYS = {
    "schemaVersion", "generatedAt", "repository", "borderZone", "packs",
}
BORDER_ZONE_KEYS = {"rule", "corridors", "italianStations", "catalogueSha256", "tripRule"}
PACK_KEYS = {
    "sourceId", "country", "borderOnly", "lastServiceDate", "fullServiceUntil", "sourceArchiveSha256",
    "servedStationIds", "zoneStationIds", "android", "ios",
}
ANDROID_KEYS = {"tag", "asset", "url", "sizeBytes", "sha256", "contentSha256"}
IOS_KEYS = {"tag", "asset", "url", "sizeBytes", "sha256", "database", "inputFingerprintSha256"}
DATABASE_KEYS = {"sha256", "sizeBytes", "userVersion", "applicationId"}
LEDGER_KEYS = {"schemaVersion", "repository", "artifacts"}
ARTIFACT_KEYS = {"platform", "sourceId", "tag", "asset", "url", "sizeBytes", "sha256", "firstPinnedOn"}


def _hex(value: object) -> bool:
    return isinstance(value, str) and rules.HEX64.fullmatch(value) is not None


def _iso_date(value: object) -> bool:
    if not isinstance(value, str):
        return False
    try:
        return date.fromisoformat(value).isoformat() == value
    except ValueError:
        return False


def _size(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def release_problems(where: str, platform: str, source_id: str, release: Mapping[str, Any]) -> list[str]:
    """Tag, asset and URL rules shared by a pin and a ledger entry."""
    problems: list[str] = []
    sha = release.get("sha256")
    if not _hex(sha):
        return [f"{where}: sha256 non valido"]
    tag = rules.tag_for(platform, source_id, str(sha))
    asset = rules.asset_for(platform, source_id)
    if release.get("tag") != tag:
        problems.append(f"{where}: tag {release.get('tag')!r} diverso da {tag!r}")
    if release.get("asset") != asset:
        problems.append(f"{where}: asset {release.get('asset')!r} diverso da {asset!r}")
    if release.get("url") != rules.url_for(tag, asset):
        problems.append(f"{where}: url diversa da {rules.url_for(tag, asset)!r}")
    if not _size(release.get("sizeBytes")):
        problems.append(f"{where}: sizeBytes non valido")
    return problems


def pins_problems(payload: object) -> list[str]:
    """Schema 1 of the pin file (design §2.2 with decision D2), without looking at any other file."""
    if not isinstance(payload, Mapping):
        return ["pin: la radice deve essere un oggetto"]
    problems: list[str] = []
    if set(payload) != PIN_KEYS:
        problems.append(f"pin: chiavi {sorted(payload)} diverse da {sorted(PIN_KEYS)}")
    if payload.get("schemaVersion") != rules.PINS_SCHEMA_VERSION:
        problems.append("pin: schemaVersion non supportata")
    if not _iso_date(payload.get("generatedAt")):
        problems.append("pin: generatedAt non è una data ISO")
    if payload.get("repository") != rules.REPOSITORY:
        problems.append(f"pin: repository diverso da {rules.REPOSITORY}")
    zone = payload.get("borderZone")
    if not isinstance(zone, Mapping) or set(zone) != BORDER_ZONE_KEYS:
        problems.append(f"pin: borderZone deve avere le chiavi {sorted(BORDER_ZONE_KEYS)}")
    else:
        if zone.get("rule") != rules.BORDER_ZONE_RULE:
            problems.append(f"pin: rule {zone.get('rule')!r} diversa da {rules.BORDER_ZONE_RULE} (rigenerare)")
        if zone.get("corridors") != rules.corridor_names():
            problems.append("pin: corridors diversi da foreign_pack_rules.CORRIDORS (rigenerare)")
        if zone.get("italianStations") != rules.ITALIAN_STATION_RULE or zone.get("tripRule") != rules.TRIP_RULE:
            problems.append("pin: regole della zona di confine diverse da quelle degli strumenti")
        if not _hex(zone.get("catalogueSha256")):
            problems.append("pin: catalogueSha256 non valido")
    packs = payload.get("packs")
    if not isinstance(packs, list):
        return problems + ["pin: packs deve essere una lista"]
    ids = [pack.get("sourceId") if isinstance(pack, Mapping) else None for pack in packs]
    if ids != sorted(rules.FOREIGN_PACK_COUNTRIES):
        problems.append(f"pin: packs {ids} diversi da {sorted(rules.FOREIGN_PACK_COUNTRIES)} (tutti e tre, in ordine)")
    for pack in packs:
        if not isinstance(pack, Mapping):
            problems.append("pin: pack non valido")
            continue
        source_id = str(pack.get("sourceId"))
        where = f"pin {source_id}"
        if set(pack) != PACK_KEYS:
            problems.append(f"{where}: chiavi {sorted(pack)} diverse da {sorted(PACK_KEYS)}")
            continue
        if pack["country"] != rules.FOREIGN_PACK_COUNTRIES.get(source_id):
            problems.append(f"{where}: country {pack['country']!r} non valido")
        if not _iso_date(pack["lastServiceDate"]) or not _iso_date(pack["fullServiceUntil"]):
            problems.append(f"{where}: date non ISO")
        elif pack["fullServiceUntil"] > pack["lastServiceDate"]:
            problems.append(f"{where}: fullServiceUntil dopo lastServiceDate")
        if not _hex(pack["sourceArchiveSha256"]):
            problems.append(f"{where}: sourceArchiveSha256 non valido")
        served, zone_ids = pack["servedStationIds"], pack["zoneStationIds"]
        lists_valid = True
        for name, values in (("servedStationIds", served), ("zoneStationIds", zone_ids)):
            if not isinstance(values, list) or any(not isinstance(item, str) or not item for item in values) \
                    or values != sorted(set(values)):
                problems.append(f"{where}: {name} deve essere una lista ordinata di id del catalogo senza doppioni")
                lists_valid = False
        border_only = pack["borderOnly"]
        if not isinstance(border_only, bool):
            problems.append(f"{where}: borderOnly deve essere booleano")
            continue
        if not border_only:
            # D2: a pack without a confirmed licence is bundled whole: no pin, no station lists.
            if pack["android"] is not None or pack["ios"] is not None:
                problems.append(f"{where}: un pack non borderOnly non ha pin di download")
            if lists_valid and (served or zone_ids):
                problems.append(f"{where}: un pack non borderOnly non ha liste di stazioni")
            continue
        if lists_valid:
            if not served:
                problems.append(f"{where}: servedStationIds vuoto")
            if not set(zone_ids) <= set(served):
                problems.append(f"{where}: zoneStationIds non incluso in servedStationIds")
        android, ios = pack["android"], pack["ios"]
        if not isinstance(android, Mapping) or set(android) != ANDROID_KEYS:
            problems.append(f"{where}: android deve avere le chiavi {sorted(ANDROID_KEYS)}")
        else:
            problems += release_problems(f"{where} android", "android", source_id, android)
            if not _hex(android.get("contentSha256")):
                problems.append(f"{where} android: contentSha256 non valido")
        if not isinstance(ios, Mapping) or set(ios) != IOS_KEYS:
            problems.append(f"{where}: ios deve avere le chiavi {sorted(IOS_KEYS)}")
        else:
            problems += release_problems(f"{where} ios", "ios", source_id, ios)
            database = ios.get("database")
            if not isinstance(database, Mapping) or set(database) != DATABASE_KEYS:
                problems.append(f"{where} ios: database deve avere le chiavi {sorted(DATABASE_KEYS)}")
            else:
                if not _hex(database.get("sha256")) or not _size(database.get("sizeBytes")):
                    problems.append(f"{where} ios: digest o dimensione del database non validi")
                if database.get("userVersion") != USER_VERSION or database.get("applicationId") != APPLICATION_ID:
                    problems.append(f"{where} ios: userVersion o applicationId diversi dall'indice iPhone")
            if not _hex(ios.get("inputFingerprintSha256")):
                problems.append(f"{where} ios: inputFingerprintSha256 non valido")
    return problems


def ledger_problems(payload: object) -> list[str]:
    if not isinstance(payload, Mapping) or set(payload) != LEDGER_KEYS:
        return [f"registro: chiavi diverse da {sorted(LEDGER_KEYS)}"]
    problems: list[str] = []
    if payload.get("schemaVersion") != rules.LEDGER_SCHEMA_VERSION or payload.get("repository") != rules.REPOSITORY:
        problems.append("registro: schemaVersion o repository non validi")
    artifacts = payload.get("artifacts")
    if not isinstance(artifacts, list):
        return problems + ["registro: artifacts deve essere una lista"]
    tags: set[str] = set()
    for index, artifact in enumerate(artifacts):
        where = f"registro #{index}"
        if not isinstance(artifact, Mapping) or set(artifact) != ARTIFACT_KEYS:
            problems.append(f"{where}: chiavi diverse da {sorted(ARTIFACT_KEYS)}")
            continue
        platform, source_id = artifact["platform"], artifact["sourceId"]
        if platform not in rules.PLATFORMS or source_id not in rules.FOREIGN_PACK_COUNTRIES:
            problems.append(f"{where}: piattaforma o sourceId non validi")
            continue
        problems += release_problems(where, platform, source_id, artifact)
        if not _iso_date(artifact["firstPinnedOn"]):
            problems.append(f"{where}: firstPinnedOn non è una data ISO")
        if artifact["tag"] in tags:
            problems.append(f"{where}: tag ripetuto {artifact['tag']}")
        tags.add(str(artifact["tag"]))
    return problems


def ledger_append_only_problems(base: object, current: object, pins: Mapping[str, Any] | None = None) -> list[str]:
    """Every entry of the base ledger is still there, unchanged and in the same place (append-only).

    With ``pins``, every entry added after the base must be a current pin: the release workflow publishes the pins
    of the pushed commit only, so an entry added on a branch and no longer pinned would never be published (and the
    weekly online check of the ledger would report it missing)."""
    base_artifacts = base.get("artifacts") if isinstance(base, Mapping) else None
    current_artifacts = current.get("artifacts") if isinstance(current, Mapping) else None
    if not isinstance(base_artifacts, list) or not isinstance(current_artifacts, list):
        return ["registro: base o attuale non leggibili"]
    if current_artifacts[: len(base_artifacts)] != base_artifacts:
        removed = [entry.get("tag") for entry in base_artifacts
                   if isinstance(entry, Mapping) and entry not in current_artifacts]
        return [f"registro: voci tolte o modificate rispetto alla base ({', '.join(map(str, removed)) or 'ordine'}); "
                "il registro si può solo estendere"]
    if pins is not None:
        pinned = {release.get("tag") for _, _, release in pinned_releases(pins)}
        stale = [str(entry.get("tag")) for entry in current_artifacts[len(base_artifacts):]
                 if isinstance(entry, Mapping) and entry.get("tag") not in pinned]
        if stale:
            return [f"registro: voci aggiunte rispetto alla base ma non più fissate ({', '.join(stale)}): non saranno mai "
                    "pubblicate; rigenerare con tools/build_foreign_packs.py --ledger-base <registro della base>"]
    return []


def pinned_releases(pins: Mapping[str, Any]) -> list[tuple[str, str, Mapping[str, Any]]]:
    """(platform, sourceId, release) of every pinned artifact, Android first."""
    releases = []
    for pack in pins.get("packs", []):
        for platform in rules.PLATFORMS:
            release = pack.get(platform)
            if isinstance(release, Mapping):
                releases.append((platform, str(pack["sourceId"]), release))
    return releases


def ledger_entry(platform: str, source_id: str, release: Mapping[str, Any], first_pinned_on: str) -> dict[str, Any]:
    return {
        "platform": platform,
        "sourceId": source_id,
        "tag": release["tag"],
        "asset": release["asset"],
        "url": release["url"],
        "sizeBytes": release["sizeBytes"],
        "sha256": release["sha256"],
        "firstPinnedOn": first_pinned_on,
    }


def ledger_coverage_problems(pins: Mapping[str, Any], ledger: Mapping[str, Any]) -> list[str]:
    by_tag = {entry.get("tag"): entry for entry in ledger.get("artifacts", []) if isinstance(entry, Mapping)}
    problems = []
    for platform, source_id, release in pinned_releases(pins):
        entry = by_tag.get(release.get("tag"))
        expected = ledger_entry(platform, source_id, release, "")
        if entry is None or {key: value for key, value in entry.items() if key != "firstPinnedOn"} != {
            key: value for key, value in expected.items() if key != "firstPinnedOn"
        }:
            problems.append(f"registro: il pin {release.get('tag')} manca o differisce in {rules.LEDGER_PATH}")
    return problems


def load_json(path: Path, label: str, problems: list[str], limit: int = MAX_PINS_BYTES) -> Any:
    try:
        if path.stat().st_size > limit:
            problems.append(f"{label}: oltre {limit} byte")
            return None
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        problems.append(f"{label}: non leggibile ({error})")
        return None


def catalogue_ids_of_pack(compressed: Path, source_id: str, maximum: int = MAX_DATABASE_BYTES) -> set[str]:
    """The catalogue ids carried by the stops of one pack in a zlib-compressed iPhone index."""
    fd, raw_name = tempfile.mkstemp(prefix="binario-foreign-", suffix=".sqlite")
    os.close(fd)
    raw = Path(raw_name)
    try:
        decompressor = zlib.decompressobj()
        size = 0
        with compressed.open("rb") as source, raw.open("wb") as target:
            while chunk := source.read(1024 * 1024):
                data = decompressor.decompress(chunk, maximum - size + 1)
                size += len(data)
                if size > maximum or decompressor.unconsumed_tail:
                    raise ValueError("database oltre il limite")
                target.write(data)
            target.write(decompressor.flush())
        connection = sqlite3.connect(f"file:{raw}?mode=ro&immutable=1", uri=True)
        try:
            return {
                str(row[0]) for row in connection.execute(
                    "SELECT DISTINCT s.catalog_station_id FROM stop s JOIN pack p ON p.id=s.pack_id "
                    "WHERE p.source_id=? AND s.catalog_station_id IS NOT NULL",
                    (source_id,),
                )
            }
        finally:
            connection.close()
    finally:
        raw.unlink(missing_ok=True)


def repository_problems(root: Path = ROOT, *, ledger_base: Path | None = None) -> list[str]:
    problems: list[str] = []
    android_pins = root / rules.ANDROID_PINS_PATH
    ios_pins = root / rules.IOS_PINS_PATH
    pins = load_json(android_pins, rules.ANDROID_PINS_PATH, problems)
    if pins is None:
        return problems
    problems += pins_problems(pins)
    if problems:
        return problems
    if not ios_pins.is_file() or ios_pins.read_bytes() != android_pins.read_bytes():
        problems.append(f"{rules.IOS_PINS_PATH} non ha gli stessi byte di {rules.ANDROID_PINS_PATH}")
    manifest = load_json(root / "app/src/main/assets/gtfs/manifest.json", "manifest GTFS", problems)
    catalogue = root / rules.CATALOGUE_PATH
    if manifest is None:
        return problems
    try:
        catalogue_sha256 = rules.sha256_file(catalogue)
    except OSError as error:
        return problems + [f"catalogo stazioni non leggibile: {error}"]
    if pins["borderZone"]["catalogueSha256"] != catalogue_sha256:
        problems.append("pin: catalogo stazioni cambiato dopo la generazione (rigenerare)")
    entries = {entry.get("sourceId"): entry for entry in manifest.get("packs", []) if isinstance(entry, Mapping)}
    android_dir, ios_dir = root / rules.FULL_PACK_DIR, root / rules.IOS_FULL_DIR
    expected_android: set[str] = set()
    expected_ios: set[str] = set()
    base_index = root / "ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib"
    for pack in pins["packs"]:
        source_id = pack["sourceId"]
        where = f"pin {source_id}"
        entry = entries.get(source_id)
        if entry is None:
            problems.append(f"{where}: assente dal manifest GTFS")
            continue
        if pack["borderOnly"] != rules.licence_confirmed(entry):
            problems.append(f"{where}: borderOnly {pack['borderOnly']} ma licenza "
                            f"{'confermata' if rules.licence_confirmed(entry) else 'da confermare'} (D2)")
        if pack["borderOnly"] != ("fullPack" in entry):
            problems.append(f"{where}: borderOnly diverso dalla presenza di fullPack nel manifest")
        if pack["sourceArchiveSha256"] != entry.get("sourceArchiveSha256"):
            problems.append(f"{where}: sourceArchiveSha256 diverso dal manifest")
        dated = entry.get("fullPack") if pack["borderOnly"] and isinstance(entry.get("fullPack"), Mapping) else entry
        for key in ("lastServiceDate", "fullServiceUntil"):
            if pack[key] != dated.get(key):
                problems.append(f"{where}: {key} {pack[key]} diverso dal manifest {dated.get(key)}")
        if not pack["borderOnly"]:
            continue
        android, ios = pack["android"], pack["ios"]
        full = entry.get("fullPack") or {}
        name = rules.asset_for("android", source_id)
        expected_android.add(name)
        path = android_dir / name
        if not path.is_file():
            problems.append(f"{where}: {rules.FULL_PACK_DIR}/{name} assente")
        elif rules.sha256_file(path) != android["sha256"] or path.stat().st_size != android["sizeBytes"]:
            problems.append(f"{where}: {rules.FULL_PACK_DIR}/{name} diverso dal pin Android")
        if (full.get("sha256"), full.get("sizeBytes"), full.get("contentSha256")) != (
                android["sha256"], android["sizeBytes"], android["contentSha256"]):
            problems.append(f"{where}: pin Android diverso da fullPack del manifest")
        ios_name = rules.asset_for("ios", source_id)
        expected_ios.update({ios_name, f"{source_id}.metadata.json"})
        ios_path = ios_dir / ios_name
        metadata = load_json(ios_dir / f"{source_id}.metadata.json", f"metadati iPhone {source_id}", problems, 4 * MAX_PINS_BYTES)
        if not ios_path.is_file():
            problems.append(f"{where}: {rules.IOS_FULL_DIR}/{ios_name} assente")
        elif rules.sha256_file(ios_path) != ios["sha256"] or ios_path.stat().st_size != ios["sizeBytes"]:
            problems.append(f"{where}: {rules.IOS_FULL_DIR}/{ios_name} diverso dal pin iPhone")
        if isinstance(metadata, Mapping):
            database = metadata.get("database") or {}
            compression = metadata.get("compression") or {}
            if metadata.get("kind") != "foreign-full" or metadata.get("sourceId") != source_id:
                problems.append(f"{where}: metadati iPhone non di un indice per paese di {source_id}")
            if {key: database.get(key) for key in DATABASE_KEYS} != dict(ios["database"]):
                problems.append(f"{where}: database del pin iPhone diverso dai metadati")
            if (compression.get("sha256"), compression.get("sizeBytes")) != (ios["sha256"], ios["sizeBytes"]):
                problems.append(f"{where}: compressione del pin iPhone diversa dai metadati")
            if (metadata.get("inputs") or {}).get("fingerprintSha256") != ios["inputFingerprintSha256"]:
                problems.append(f"{where}: impronta degli input diversa dai metadati")
        # The station lists are the catalogue ids of the two iPhone indexes: served in the per-country index, zone in
        # the base index built from the border subset (build_ios_gtfs_schedule.foreign_station_coverage).
        try:
            if ios_path.is_file() and catalogue_ids_of_pack(ios_path, source_id) != set(pack["servedStationIds"]):
                problems.append(f"{where}: servedStationIds diverso dagli id dell'indice iPhone per paese")
            if base_index.is_file() and catalogue_ids_of_pack(base_index, source_id) != set(pack["zoneStationIds"]):
                problems.append(f"{where}: zoneStationIds diverso dagli id dell'indice iPhone di base")
        except (OSError, ValueError, sqlite3.Error, zlib.error) as error:
            problems.append(f"{where}: indice iPhone non leggibile ({error})")
    for directory, expected, label in ((android_dir, expected_android | {"SHA256SUMS"} if expected_android else set(), rules.FULL_PACK_DIR),
                                       (ios_dir, expected_ios, rules.IOS_FULL_DIR)):
        actual = {path.name for path in directory.iterdir()} if directory.is_dir() else set()
        if actual != expected:
            problems.append(f"{label}: file {sorted(actual)} diversi da quelli dei pin {sorted(expected)}")
    ledger_path = root / rules.LEDGER_PATH
    ledger = load_json(ledger_path, rules.LEDGER_PATH, problems, 16 * MAX_PINS_BYTES) if ledger_path.is_file() else None
    if ledger is None:
        if pinned_releases(pins):
            problems.append(f"{rules.LEDGER_PATH} assente")
        return problems
    problems += ledger_problems(ledger)
    problems += ledger_coverage_problems(pins, ledger)
    if ledger_base is not None:
        base = load_json(ledger_base, "registro di base", problems, 16 * MAX_PINS_BYTES)
        if base is not None:
            problems += ledger_append_only_problems(base, ledger, pins)
    return problems


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pins", type=Path, help="file di pin da verificare (con --schema-only)")
    parser.add_argument("--schema-only", action="store_true", help="solo lo schema del file di pin, senza altri file")
    parser.add_argument("--ledger-base", type=Path, help="registro del ramo di base: nessuna voce può sparire")
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    if args.schema_only:
        if args.pins is None:
            parser.error("--schema-only richiede --pins")
        problems: list[str] = []
        payload = load_json(args.pins, str(args.pins), problems)
        problems += pins_problems(payload) if payload is not None else []
    else:
        if args.pins is not None:
            parser.error("--pins vale solo con --schema-only")
        problems = repository_problems(args.root, ledger_base=args.ledger_base)
    if problems:
        for problem in problems:
            print(f"ERRORE pin esteri: {problem}", file=sys.stderr)
        return 1
    print("Foreign pack pins verification passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
