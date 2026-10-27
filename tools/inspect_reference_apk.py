#!/usr/bin/env python3
"""Inspect a reference package without installing it or disclosing embedded secrets."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import tempfile
from urllib.parse import urlsplit
from urllib.request import urlopen
import zipfile

REFERENCE_COMMIT = "c2e905cf1139a2d860c43e16148bb078591c79d6"
REFERENCE_BLOB = "84007d65164c327aede305267bd7bca7d0ea5cee"
REFERENCE_SIZE = 44_435_213
REFERENCE_URL = f"https://raw.githubusercontent.com/fz1101hub/binario/{REFERENCE_COMMIT}/references/On_The_Rails_1.1.9.xapk"
MAX_MEMBER = 128 * 1024 * 1024
MAX_ARCHIVE = 300 * 1024 * 1024
RAIL_DOMAINS = ("trenitalia.com", "lefrecce.it", "viaggiatreno.it", "rfi.it", "italotreno.it", "italoinviaggio.it", "trenord.it", "opendatahub.com", "opentransportdata.swiss")
SAFE_SEGMENTS = frozenset("api info mobility status viaggiatreno resteasy rest train trains station stations partenze arrivi andamento autocompletastazione soluzioniviaggionew soluzioniviaggiotutti channels.mobile.bff trainmonitor gettrainschedule getstations gettrainstatus v1 v2".split())
URL = re.compile(r"https?://[^\s<>\"'\x00]+")
FEATURES = {
    "stop_reminders": re.compile(r"allarm|sveglia|stop.?alarm|stop.?reminder", re.I),
    "recurring_commute": re.compile(r"recurr|ricorrent|weekday|giorni.?settimana", re.I),
    "delay_distinction": re.compile(r"ritardo.?real|ritardo.?stimat|actual.?delay|predicted.?delay|lastTakeover", re.I),
    "journey_replanning": re.compile(r"riprogram|replan", re.I),
    "punctuality_history": re.compile(r"storico|puntualit|punctuality|delay.?history", re.I),
    "geofencing": re.compile(r"geofenc", re.I),
    "disruption_alerts": re.compile(r"infomobilit|smart.?caring|disruption", re.I),
}


def dex_strings(data: bytes):
    if len(data) < 112 or not data.startswith(b"dex\n"):
        raise ValueError("Unsupported or truncated DEX")
    count, offset = struct.unpack_from("<II", data, 56)
    if count > 2_000_000 or offset < 112 or offset + count * 4 > len(data):
        raise ValueError("Invalid DEX string table")
    for index in range(count):
        position = struct.unpack_from("<I", data, offset + index * 4)[0]
        for _ in range(5):
            if position >= len(data):
                raise ValueError("Invalid DEX string offset")
            value = data[position]
            position += 1
            if value < 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = data.find(b"\x00", position, min(position + 65_536, len(data)))
        if end < 0:
            raise ValueError("Unterminated or oversized DEX string")
        yield data[position:end].decode("utf-8", errors="replace")


def rail_reference(value: str):
    """Only operator hosts and a fixed vocabulary of path components may leave the scanner."""
    try:
        parsed = urlsplit(value)
        host = (parsed.hostname or "").lower()
    except ValueError:
        return None
    if not any(host == domain or host.endswith("." + domain) for domain in RAIL_DOMAINS):
        return None
    if parsed.username or parsed.password:
        return {"host": host, "scheme": parsed.scheme, "path": "/{redacted}"}
    segments = [part if part.lower() in SAFE_SEGMENTS else "{parameter}" for part in parsed.path.split("/") if part]
    return {"host": host, "scheme": parsed.scheme, "path": "/" + "/".join(segments)}


def checked_members(archive: zipfile.ZipFile):
    members = archive.infolist()
    if len(members) > 50_000 or sum(item.file_size for item in members) > MAX_ARCHIVE:
        raise ValueError("Archive exceeds inspection budget")
    if any(item.file_size > MAX_MEMBER for item in members):
        raise ValueError("Archive member exceeds inspection budget")
    names = [item.filename for item in members]
    if len(names) != len(set(names)):
        raise ValueError("Duplicate archive members are ambiguous")
    return members


def inspect_package(path: Path) -> dict:
    size = path.stat().st_size
    if size > MAX_MEMBER:
        raise ValueError("Package exceeds inspection budget")
    blob = hashlib.sha1(f"blob {size}\0".encode())
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            blob.update(chunk)
            digest.update(chunk)
    references: set[tuple[str, str, str]] = set()
    features: Counter = Counter()
    strings = dex_count = apk_count = 0
    metadata = {}

    def scan_apk(archive: zipfile.ZipFile):
        nonlocal strings, dex_count, apk_count
        apk_count += 1
        for member in checked_members(archive):
            if not re.fullmatch(r"classes(?:\d+)?\.dex", member.filename):
                continue
            dex_count += 1
            for value in dex_strings(archive.read(member)):
                strings += 1
                if strings > 2_000_000:
                    raise ValueError("Package exceeds string inspection budget")
                for name, pattern in FEATURES.items():
                    if pattern.search(value):
                        features[name] += 1
                for match in URL.finditer(value):
                    reference = rail_reference(match.group())
                    if reference:
                        references.add((reference["host"], reference["scheme"], reference["path"]))

    with zipfile.ZipFile(path) as outer:
        members = checked_members(outer)
        if any(re.fullmatch(r"classes(?:\d+)?\.dex", item.filename) for item in members):
            scan_apk(outer)
        else:
            if "manifest.json" in outer.namelist():
                if outer.getinfo("manifest.json").file_size > 65_536:
                    raise ValueError("Oversized XAPK manifest")
                manifest = json.loads(outer.read("manifest.json"))
                for key in ("package_name", "version_name", "version_code"):
                    value = str(manifest.get(key, ""))
                    if re.fullmatch(r"[a-zA-Z0-9._-]{1,128}", value):
                        metadata[key] = value
            apks = [item for item in members if item.filename.lower().endswith(".apk")]
            if not 1 <= len(apks) <= 32:
                raise ValueError("Missing or excessive APK splits")
            for member in apks:
                with zipfile.ZipFile(io.BytesIO(outer.read(member))) as apk:
                    scan_apk(apk)
    if not dex_count:
        raise ValueError("No inspectable DEX; no source conclusions can be drawn")
    return {
        "method": "Static DEX string inspection; package was not executed. References do not prove runtime use, current availability or permission to reuse.",
        "size": size, "git_blob_sha1": blob.hexdigest(), "sha256": digest.hexdigest(),
        "matches_reference_blob": blob.hexdigest() == REFERENCE_BLOB,
        "manifest_reported_metadata": metadata, "apk_splits": apk_count, "dex_files": dex_count,
        "strings_scanned": strings,
        "operator_references": [{"host": host, "scheme": scheme, "path": route} for host, scheme, route in sorted(references)],
        "feature_string_evidence": dict(sorted(features.items())),
        "secrets_policy": "Queries, fragments, userinfo, unknown path components and raw strings are excluded. No credentials are used or published.",
    }


def download_reference(destination: Path):
    total = 0
    with urlopen(REFERENCE_URL, timeout=30) as response, destination.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            total += len(chunk)
            if total > REFERENCE_SIZE:
                raise ValueError("Reference download exceeds expected size")
            output.write(chunk)
    if total != REFERENCE_SIZE:
        raise ValueError("Reference download size mismatch")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--input", type=Path)
    group.add_argument("--download", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="binario-reference-") as directory:
        path = args.input or Path(directory) / "reference.xapk"
        if args.download:
            download_reference(path)
        report = inspect_package(path)
        if args.download and not report["matches_reference_blob"]:
            raise ValueError("Reference Git blob checksum mismatch")
    output = json.dumps(report, ensure_ascii=False, indent=2)
    print(output)
    if args.output:
        args.output.write_text(output + "\n", encoding="utf-8")
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as summary:
            summary.write("\n## On The Rails reference inspection\n\n```json\n" + output + "\n```\n")


if __name__ == "__main__":
    main()
