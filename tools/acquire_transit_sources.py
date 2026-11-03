#!/usr/bin/env python3
"""Acquire the regional-railway and city-network timetables registered in ``tools/transit_packs.json``.

Run on a CI runner (``gtfs-assets-ci.yml``, input ``transit_packs``) with ``python3 -I`` from the repository root:
the development container cannot reach the publishers.  For every selected pack the tool downloads the registered
archive once (packs sharing a source share the download), converts NeTEx from the Italian National Access Point with
``tools/netex_to_gtfs.py``, and writes

* ``OUT/input/<asset file>``: the complete GTFS archive that ``tools/build_gtfs_packs.py`` filters;
* ``OUT/manifests/<directory>.json``: the source manifest of each asset directory (``gtfs-transit`` for every build,
  ``gtfs-personal`` for personal builds), in the format of ``app/src/main/assets/gtfs/manifest.json``;
* ``OUT/acquisition.json``: what was downloaded (URL, final URL, bytes, SHA-256, conversion counts).

Everything downloaded is untrusted data: it is parsed only by this repository's standard-library code and never
executed.  Downloads are HTTPS only, except a source registered with ``"transport": "http"``: its bytes must then
equal those of the HTTPS mirror named in ``corroborateWith`` (the same file, fetched over an authenticated channel),
or, without a mirror, the pack must be a personal-build pack whose registry entry says why no HTTPS copy exists.
"""
from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import netex_to_gtfs  # noqa: E402

REGISTRY = ROOT / "tools" / "transit_packs.json"
MAX_DOWNLOAD_BYTES = 400 * 1024 * 1024
USER_AGENT = "Binario-GTFS-build/0.20.4 (release tooling; read-only)"
DIRECTORIES = {"gtfs-transit", "gtfs-personal"}
FORMATS = {"gtfs", "netex"}
KINDS = {"rail", "urban"}
RETRYABLE_STATUS = {500, 502, 503, 504}
RETRY_DELAY_SECONDS = 20


def log_safe(value: object) -> str:
    """``value`` on one log line with control characters escaped (same rule as ``build_gtfs_packs.log_safe``)."""
    return "".join(
        character if character.isprintable() or character == " " else character.encode("unicode_escape").decode("ascii")
        for character in str(value)
    )


class AcquisitionError(RuntimeError):
    """A registered source could not be acquired completely and safely."""


def load_registry(path: Path = REGISTRY) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("schemaVersion") != 1 or not isinstance(payload.get("packs"), list):
        raise AcquisitionError("registro transit_packs.json non valido")
    seen: set[str] = set()
    for pack in payload["packs"]:
        source_id = pack.get("sourceId")
        if not isinstance(source_id, str) or not source_id or source_id in seen:
            raise AcquisitionError(f"sourceId mancante o duplicato: {source_id!r}")
        seen.add(source_id)
        if pack.get("directory") not in DIRECTORIES or pack.get("format") not in FORMATS or pack.get("kind") not in KINDS:
            raise AcquisitionError(f"{source_id}: directory, formato o tipo non validi")
        personal = pack.get("directory") == "gtfs-personal"
        if (pack.get("distribution") == "personal") != personal:
            raise AcquisitionError(f"{source_id}: distribution e directory incoerenti")
        url = pack.get("sourceUrl")
        if not isinstance(url, str):
            raise AcquisitionError(f"{source_id}: sourceUrl mancante")
        scheme = urllib.parse.urlparse(url).scheme
        transport = pack.get("transport", "https")
        if scheme != transport or transport not in {"https", "http"}:
            raise AcquisitionError(f"{source_id}: sourceUrl {url} non usa il trasporto dichiarato {transport}")
        if transport == "http":
            mirror = pack.get("corroborateWith")
            if mirror is None and not (personal and pack.get("httpReason")):
                raise AcquisitionError(f"{source_id}: HTTP richiede un mirror HTTPS o un pack personale con httpReason")
            if mirror is not None and urllib.parse.urlparse(mirror).scheme != "https":
                raise AcquisitionError(f"{source_id}: corroborateWith deve essere HTTPS")
        for field in ("licence", "attribution", "notes"):
            if not isinstance(pack.get(field), str) or not pack[field].strip():
                raise AcquisitionError(f"{source_id}: {field} mancante")
    return payload["packs"]


class HttpsOnlyRedirects(urllib.request.HTTPRedirectHandler):
    def __init__(self, allow_http: bool) -> None:
        self.allow_http = allow_http

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[no-untyped-def]
        scheme = urllib.parse.urlparse(newurl).scheme
        if scheme != "https" and not (self.allow_http and scheme == "http"):
            raise AcquisitionError(f"redirect rifiutato verso {newurl}")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(url: str, destination: Path, *, allow_http: bool = False, attempts: int = 6) -> dict[str, Any]:
    """Stream ``url`` to ``destination`` under the byte cap, resuming a dropped connection with a Range request."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    opener = urllib.request.build_opener(HttpsOnlyRedirects(allow_http))
    written = 0
    final_url = url
    for attempt in range(attempts):
        headers = {"User-Agent": USER_AGENT, "Accept": "*/*"}
        if written:
            headers["Range"] = f"bytes={written}-"
        request = urllib.request.Request(url, headers=headers)
        try:
            with opener.open(request, timeout=90) as response:
                final_url = response.geturl()
                scheme = urllib.parse.urlparse(final_url).scheme
                if scheme != "https" and not (allow_http and scheme == "http"):
                    raise AcquisitionError(f"URL finale non ammesso: {final_url}")
                if written and response.status != 206:
                    # The server ignores Range: download again from the first byte.
                    written = 0
                with destination.open("ab" if written else "wb") as output:
                    while True:
                        chunk = response.read(1 << 16)
                        if not chunk:
                            break
                        written += len(chunk)
                        if written > MAX_DOWNLOAD_BYTES:
                            raise AcquisitionError(f"download oltre {MAX_DOWNLOAD_BYTES} byte: {url}")
                        output.write(chunk)
            break
        except (http.client.IncompleteRead, ConnectionError, TimeoutError, urllib.error.URLError) as error:
            if isinstance(error, urllib.error.HTTPError):
                # A busy server (the access point answers 503 at times) is asked again a little later; any other
                # HTTP status is final.
                if error.code not in RETRYABLE_STATUS or attempt == attempts - 1:
                    raise AcquisitionError(f"download fallito {url}: {error}") from error
                print(f"  HTTP {error.code} da {url}: nuovo tentativo {attempt + 1}", flush=True)
                time.sleep(RETRY_DELAY_SECONDS * (attempt + 1))
                continue
            if attempt == attempts - 1 or not destination.exists():
                raise AcquisitionError(f"download fallito {url}: {error}") from error
            written = destination.stat().st_size
            print(f"  ripresa {attempt + 1} di {url} da {written} byte", flush=True)
    if written == 0:
        raise AcquisitionError(f"download vuoto: {url}")
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    return {"url": url, "finalUrl": final_url, "bytes": written, "sha256": digest}


def acquire(packs: list[dict[str, Any]], out: Path) -> dict[str, Any]:
    downloads = out / "downloads"
    inputs = out / "input"
    inputs.mkdir(parents=True, exist_ok=True)
    report: dict[str, Any] = {"packs": {}}
    by_url: dict[str, tuple[Path, dict[str, Any]]] = {}
    converted: dict[str, tuple[Path, dict[str, Any]]] = {}
    for pack in packs:
        source_id = pack["sourceId"]
        url = pack["sourceUrl"]
        print(f"ACQUIRE {source_id} {url}", flush=True)
        if url not in by_url:
            target = downloads / hashlib.sha256(url.encode()).hexdigest()[:16]
            record = download(url, target, allow_http=pack.get("transport") == "http")
            mirror = pack.get("corroborateWith")
            if mirror:
                copy = downloads / (target.name + ".mirror")
                mirrored = download(mirror, copy)
                if mirrored["sha256"] != record["sha256"]:
                    raise AcquisitionError(
                        f"{source_id}: la copia HTTP ({record['sha256']}) differisce dal mirror HTTPS {mirror} ({mirrored['sha256']})"
                    )
                record["corroboratedBy"] = {"url": mirror, "sha256": mirrored["sha256"]}
                copy.unlink()
            by_url[url] = (target, record)
        target, record = by_url[url]
        asset_name = f"{source_id}.zip"
        entry: dict[str, Any] = {"download": record}
        if pack["format"] == "netex":
            if url not in converted:
                gtfs = downloads / (target.name + ".gtfs.zip")
                conversion = netex_to_gtfs.convert(target, gtfs, source_label=pack.get("operator", source_id))
                converted[url] = (gtfs, conversion)
            gtfs, conversion = converted[url]
            entry["conversion"] = conversion
            shutil.copyfile(gtfs, inputs / asset_name)
        else:
            if not zipfile.is_zipfile(target):
                raise AcquisitionError(f"{source_id}: il download non è un archivio ZIP")
            shutil.copyfile(target, inputs / asset_name)
        report["packs"][source_id] = entry
        print(f"  {json.dumps(entry, ensure_ascii=False, sort_keys=True)}", flush=True)
    return report


def source_manifests(packs: list[dict[str, Any]], report: dict[str, Any], generated_at: str) -> dict[str, dict[str, Any]]:
    """One source manifest per asset directory, in the registry order, for tools/build_gtfs_packs.py."""
    manifests: dict[str, dict[str, Any]] = {}
    for pack in packs:
        directory = pack["directory"]
        manifest = manifests.setdefault(directory, {
            "schemaVersion": 4,
            "generatedAt": generated_at,
            "purpose": (
                "Offline timetable packs of regional railways and city networks"
                + (" (personal builds only)" if directory == "gtfs-personal" else "")
            ),
            "packs": [],
        })
        acquired = report["packs"].get(pack["sourceId"], {})
        download_record = acquired.get("download", {})
        entry = {
            "sourceId": pack["sourceId"],
            "asset": f"{directory}/{pack['sourceId']}.zip",
            "sourceUrl": pack["sourceUrl"],
            "accessMode": "OPEN_DIRECT" if pack.get("transport", "https") == "https" else "GATEWAY_ONLY",
            "attribution": pack["attribution"],
            "licence": pack["licence"],
            "licenceEvidence": pack.get("licenceEvidence", ""),
            "kind": pack["kind"],
            "distribution": pack["distribution"],
            "notes": pack["notes"],
            "acquisition": {
                "format": pack["format"],
                "transport": pack.get("transport", "https"),
                "downloadSha256": download_record.get("sha256"),
                "downloadBytes": download_record.get("bytes"),
                "finalUrl": download_record.get("finalUrl"),
                **({"corroboratedBy": download_record["corroboratedBy"]} if "corroboratedBy" in download_record else {}),
                **({"httpReason": pack["httpReason"]} if pack.get("httpReason") else {}),
                **({"converter": "tools/netex_to_gtfs.py", "conversion": acquired["conversion"]["counts"]} if "conversion" in acquired else {}),
            },
        }
        if pack.get("minValidDays") is not None:
            entry["minValidDays"] = pack["minValidDays"]
        manifest["packs"].append(entry)
    return manifests


NAP_ASSET_URL = "https://www.cciss.it/nap/mmtis/public/api/v1/download/blob/Asset/{asset}/checkedResource"


def validate_netex(asset: str, reference: Path, out: Path, start: str) -> dict[str, Any]:
    """Convert one NAP asset and compare it with a reference GTFS of the same operator (tools/compare_gtfs.py)."""
    import compare_gtfs
    from datetime import date

    download_record = download(NAP_ASSET_URL.format(asset=asset), out / f"nap-{asset}.xml.gz")
    conversion = netex_to_gtfs.convert(out / f"nap-{asset}.xml.gz", out / f"nap-{asset}.gtfs.zip", source_label=f"NAP asset {asset}")
    begin = date.fromisoformat(start)
    comparison = compare_gtfs.compare(
        compare_gtfs.Feed(out / f"nap-{asset}.gtfs.zip", begin, 42),
        compare_gtfs.Feed(reference, begin, 42),
    )
    return {"download": download_record, "conversion": conversion, "comparison": comparison}


def print_directory(directory: Path, nonce: str | None = None) -> None:
    """Every file under ``directory`` as base64 lines in the job log (artifact downloads are not reachable from the
    development container), framed by a random nonce that no earlier step can know (audit A7-02, P1)::

        BINARIO_BEGIN <nonce>
        BINARIO_FILE path size sha256
        BINARIO_B64 path chunk
        BINARIO_END path
        BINARIO_DONE <nonce> <files>

    ``tools/apply_ci_gtfs_refresh.py`` accepts exactly one such frame per job log, from this print step only."""
    import base64
    import secrets

    nonce = nonce or secrets.token_hex(16)
    files = sorted(item for item in directory.rglob("*") if item.is_file())
    print(f"BINARIO_BEGIN {nonce}", flush=True)
    for path in files:
        relative = path.relative_to(directory).as_posix()
        data = path.read_bytes()
        print(f"BINARIO_FILE {relative} {len(data)} {hashlib.sha256(data).hexdigest()}")
        encoded = base64.b64encode(data).decode("ascii")
        for start in range(0, len(encoded), 4000):
            print(f"BINARIO_B64 {relative} {encoded[start:start + 4000]}")
        print(f"BINARIO_END {relative}", flush=True)
    print(f"BINARIO_DONE {nonce} {len(files)}", flush=True)


def selection_from(text: str) -> tuple[bool, list[str] | None, list[str] | None]:
    """``validate-netex``, ``build=all`` or ``build=id1,id2`` (the packs of this registry) and ``refresh-core=all`` or
    ``refresh-core=id1,id2`` (the core packs of ``app/src/main/assets/gtfs/``, ``tools/refresh_core_gtfs.py``) from the
    workflow input; an empty list means every pack."""
    validate = False
    build: list[str] | None = None
    refresh_core: list[str] | None = None
    for token in text.replace(";", " ").split():
        if token == "validate-netex":
            validate = True
        elif token.startswith("build="):
            value = token.removeprefix("build=")
            build = [] if value == "all" else [item for item in value.split(",") if item]
        elif token.startswith("refresh-core="):
            value = token.removeprefix("refresh-core=")
            refresh_core = [] if value == "all" else [item for item in value.split(",") if item]
            if not value or (value != "all" and not refresh_core):
                raise AcquisitionError(f"refresh-core senza pack: {token!r}")
        else:
            raise AcquisitionError(f"token non riconosciuto: {token!r}")
    return validate, build, refresh_core


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--registry", type=Path, default=REGISTRY)
    parser.add_argument("--only", action="append", default=[], help="sourceId da acquisire (ripetibile); default: tutti")
    parser.add_argument("--generated-at")
    parser.add_argument("--validate-netex", metavar="ASSET", help="converte un asset NAP e lo confronta con --reference")
    parser.add_argument("--reference", type=Path)
    parser.add_argument("--print-dir", type=Path, help="stampa i file della directory in base64 nel log")
    parser.add_argument(
        "--selection",
        help="testo dell'input del workflow: 'validate-netex', 'build=all' o 'build=id,id', 'refresh-core=all' o 'refresh-core=id,id'",
    )
    args = parser.parse_args(argv)
    if args.print_dir:
        print_directory(args.print_dir)
        return 0
    if args.selection is not None:
        try:
            validate, build, refresh_core = selection_from(args.selection)
        except AcquisitionError as error:
            print("ERRORE selezione: " + log_safe(error), file=sys.stderr)
            return 1
        print(json.dumps({"validate": validate, "build": build, "refreshCore": refresh_core}))
        return 0
    if args.out is None or args.generated_at is None:
        parser.error("--out e --generated-at sono obbligatori")
    if args.validate_netex:
        if args.reference is None:
            parser.error("--validate-netex richiede --reference")
        args.out.mkdir(parents=True, exist_ok=True)
        result = validate_netex(args.validate_netex, args.reference, args.out, args.generated_at)
        print("NETEX_VALIDATION " + json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    try:
        packs = load_registry(args.registry)
        selected = [pack for pack in packs if not args.only or pack["sourceId"] in set(args.only)]
        unknown = set(args.only) - {pack["sourceId"] for pack in packs}
        if unknown:
            raise AcquisitionError(f"sourceId non registrati: {', '.join(sorted(unknown))}")
        args.out.mkdir(parents=True, exist_ok=True)
        report = acquire(selected, args.out)
        (args.out / "acquisition.json").write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        manifests = source_manifests(selected, report, args.generated_at)
        (args.out / "manifests").mkdir(exist_ok=True)
        for directory, manifest in manifests.items():
            (args.out / "manifests" / f"{directory}.json").write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
    except (AcquisitionError, netex_to_gtfs.ConversionError, OSError, zipfile.BadZipFile) as error:
        for line in str(error).split("\n"):
            print("ERRORE acquisizione: " + log_safe(line), file=sys.stderr)
        return 1
    print(json.dumps({"packs": len(selected), "directories": sorted(manifests)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
