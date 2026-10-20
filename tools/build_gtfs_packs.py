#!/usr/bin/env python3
"""Build, verify and diagnose Binario embedded GTFS packs.

The script is deliberately an *offline release tool*: the application never
fetches GTFS.  A build consumes a registered manifest and complete ZIP inputs,
filters every pack to the rail services Binario can present, and atomically
publishes ZIPs plus ``manifest.json`` and ``SHA256SUMS``.

``packs[].sha256`` and ``SHA256SUMS`` remain distribution-integrity hashes of
the complete ZIP bytes.  Because DEFLATE implementations can legitimately
produce different bytes, each embedded ``binario_filter_manifest.json`` also
attests the SHA-256 of every uncompressed data member.  Those canonical member
digests prove content identity across byte-different ZIP rebuilds.  The filter
manifest itself is excluded from that map because it carries the map.

Examples::

    # Rebuild content-identically from already acquired archives.
    python3 tools/build_gtfs_packs.py \
      --input-dir app/src/main/assets/gtfs \
      --output-dir /tmp/binario-gtfs \
      --generated-at 2026-09-23

    # Verify checked-in artifacts (used by CI).
    python3 tools/build_gtfs_packs.py --check --as-of 2026-09-23

    # Download one OPEN_DIRECT registered source with a byte limit, then build.
    python3 tools/build_gtfs_packs.py --refresh --source-id toscana-tft-gtfs \
      --output-dir /tmp/binario-gtfs --generated-at 2026-09-23

HTTP/GATEWAY_ONLY sources are intentionally not fetched.  They require an
approved HTTPS gateway and a complete replacement archive; the tool never
silently falls back to an old or partial download.
"""
from __future__ import annotations

import argparse
import csv
import fcntl
import hashlib
import http.client
import io
import json
import os
import shutil
import sys
import tempfile
import urllib.parse
import urllib.request
import uuid
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path
from typing import Any, BinaryIO, Callable, Iterable, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ASSET_DIR = ROOT / "app/src/main/assets/gtfs"
DEFAULT_MAX_DOWNLOAD_BYTES = 200 * 1024 * 1024
DOWNLOAD_CHUNK_SIZE = 64 * 1024
MAX_ARCHIVE_ENTRIES = 128
MAX_ARCHIVE_UNCOMPRESSED_BYTES = 1024 * 1024 * 1024
MAX_ARCHIVE_MEMBER_BYTES = 512 * 1024 * 1024
MAX_COMPRESSION_RATIO = 200
MAX_TABLE_ROWS = 2_000_000
MAX_RETAINED_TABLE_ROWS = 750_000
MAX_RETAINED_BUILD_ROWS = 1_000_000
MAX_CSV_COLUMNS = 32
MAX_CSV_FIELD_UTF8_BYTES = 16 * 1024
MAX_CSV_PHYSICAL_LINE_UTF8_BYTES = 256 * 1024
MAX_RETAINED_BUILD_CELLS = 6_000_000
MAX_RETAINED_BUILD_UTF8_BYTES = 128 * 1024 * 1024
TOOL_VERSION = 2
RELEASE_MANIFEST_VERSION = 4
FILTER_MANIFEST_MEMBER = "binario_filter_manifest.json"
MEMBER_DIGEST_FIELD = "memberSha256"

# This is emitted in the release manifest, rather than a global document, so
# the checked-in artifact explains the distinct distribution/content claims.
RELEASE_INTEGRITY = {
    "zipSha256": {
        "field": "packs[].sha256 and SHA256SUMS",
        "scope": "complete ZIP byte stream (distribution integrity)",
    },
    "contentSha256": {
        "field": f"{FILTER_MANIFEST_MEMBER}.{MEMBER_DIGEST_FIELD}",
        "scope": (
            "uncompressed non-manifest ZIP members; identical maps prove content identity "
            "when DEFLATE produces different ZIP bytes"
        ),
    },
}

# GTFS extended route types 100..199 are railway service types.  This is kept
# explicit rather than inferred from names so a feed refresh cannot accidentally
# turn an urban bus line into a railway service.
RAIL_ROUTE_TYPES = frozenset({"2", *(str(value) for value in range(100, 200))})

# Exceptional source-specific selectors are reviewed identifiers, not fuzzy
# keywords.  A changed upstream ID fails the build and requires review.
EXCLUSIVE_ROUTE_IDS: Mapping[str, frozenset[str]] = {
    "amt-genova-casella-gtfs": frozenset({"FGC-164"}),
    # TFT labels its two railway lines as route_type=3 (bus).
    "toscana-tft-gtfs": frozenset({"1028056267", "2272465657"}),
}

CORE_TABLES = (
    "agency.txt",
    "stops.txt",
    "routes.txt",
    "trips.txt",
    "stop_times.txt",
    "calendar.txt",
    "calendar_dates.txt",
)
COPY_METADATA_TABLES = ("feed_info.txt", "translations.txt", "attributions.txt")


class BuildError(RuntimeError):
    """Raised for an unsafe, incomplete, or inconsistent release input."""


class DownloadError(BuildError):
    """Raised when a registered source cannot be acquired safely."""


def require_https_url(url: str, *, context: str) -> None:
    try:
        parsed = urllib.parse.urlparse(url)
        hostname = parsed.hostname
        parsed.port
    except ValueError as error:
        raise DownloadError(f"{context}: URL non valido ({url})") from error
    if any(ord(character) < 32 for character in url):
        raise DownloadError(f"{context}: URL con caratteri di controllo")
    if parsed.scheme.lower() != "https" or not parsed.netloc or not hostname:
        raise DownloadError(f"{context}: e richiesto HTTPS ({url})")


class HttpsOnlyRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Reject every redirect hop that would leave authenticated HTTPS transport."""

    def redirect_request(
        self,
        req: urllib.request.Request,
        fp: BinaryIO,
        code: int,
        msg: str,
        headers: Mapping[str, str],
        newurl: str,
    ) -> urllib.request.Request | None:
        require_https_url(newurl, context="redirect rifiutato")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


@dataclass(frozen=True)
class CsvTable:
    header: tuple[str, ...]
    rows: tuple[dict[str, str], ...]


@dataclass(frozen=True)
class FilterResult:
    source_id: str
    last_service_date: date
    filter_metadata: dict[str, Any]
    source_archive_sha256: str
    content_sha256: str


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


class Utf8DigestSink:
    """Minimal text sink used to hash the exact canonical CSV serialization."""

    def __init__(self) -> None:
        self._digest = hashlib.sha256()

    def write(self, value: str) -> int:
        encoded = value.encode("utf-8")
        self._digest.update(encoded)
        return len(value)

    def hexdigest(self) -> str:
        return self._digest.hexdigest()


def write_canonical_csv(target: Any, table: CsvTable) -> None:
    """Emit the canonical CSV bytes used both for ZIP output and its digest."""
    writer = csv.DictWriter(target, fieldnames=table.header, extrasaction="ignore", lineterminator="\n")
    writer.writeheader()
    for row in table.rows:
        writer.writerow({field: row.get(field, "") for field in table.header})


def canonical_csv_sha256(table: CsvTable) -> str:
    sink = Utf8DigestSink()
    write_canonical_csv(sink, table)
    return sink.hexdigest()


def sha256_archive_member(archive: zipfile.ZipFile, entry: zipfile.ZipInfo) -> str:
    """Hash decompressed member bytes after archive limits have been checked."""
    digest = hashlib.sha256()
    with archive.open(entry) as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def canonical_member_digests(archive: zipfile.ZipFile, manifest_entry: str) -> dict[str, str]:
    """Return canonical content hashes for every payload member except its attestation."""
    files = [entry for entry in archive.infolist() if not entry.is_dir()]
    names = [entry.filename for entry in files]
    if len(names) != len(set(names)):
        raise BuildError("archivio GTFS con member duplicati")
    if manifest_entry not in names:
        raise BuildError("archivio GTFS senza manifest filtro da escludere dai digest")
    return {
        entry.filename: sha256_archive_member(archive, entry)
        for entry in sorted(files, key=lambda candidate: candidate.filename)
        if entry.filename != manifest_entry
    }


def declared_member_digests(metadata: Mapping[str, Any], source_id: str) -> dict[str, str]:
    """Validate the canonical-digest contract before comparing archive content."""
    declared = metadata.get(MEMBER_DIGEST_FIELD)
    if not isinstance(declared, dict) or not declared:
        raise BuildError(f"{source_id}: digest canonici member mancanti")
    normalized: dict[str, str] = {}
    for name, digest in declared.items():
        if not isinstance(name, str) or not name or name == FILTER_MANIFEST_MEMBER:
            raise BuildError(f"{source_id}: nome member nel digest non valido: {name!r}")
        if (
            not isinstance(digest, str)
            or len(digest) != 64
            or any(character not in "0123456789abcdef" for character in digest)
        ):
            raise BuildError(f"{source_id}: SHA-256 canonico non valido per {name}")
        normalized[name] = digest
    return normalized


def canonical_content_sha256(member_digests: Mapping[str, str]) -> str:
    """Hash the sorted member-digest map so the release manifest can attest content."""
    canonical = json.dumps(dict(member_digests), sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    return hashlib.sha256(canonical.encode("ascii")).hexdigest()


def safe_asset_name(asset: str) -> str:
    """Return the filename for a manifest asset and reject path traversal."""
    normalized = asset.replace("\\", "/")
    if not normalized.startswith("gtfs/"):
        raise BuildError(f"asset fuori da gtfs/: {asset}")
    name = normalized.removeprefix("gtfs/")
    if not name or Path(name).name != name or not name.endswith(".zip"):
        raise BuildError(f"nome asset non valido: {asset}")
    return name


def read_manifest(path: Path) -> dict[str, Any]:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BuildError(f"manifest non leggibile {path}: {error}") from error
    packs = manifest.get("packs")
    if not isinstance(packs, list) or not packs:
        raise BuildError("manifest senza packs")
    seen_ids: set[str] = set()
    seen_assets: set[str] = set()
    for pack in packs:
        if not isinstance(pack, dict):
            raise BuildError("pack manifest non oggetto")
        source_id = pack.get("sourceId")
        if not isinstance(source_id, str) or not source_id:
            raise BuildError("pack senza sourceId")
        if source_id in seen_ids:
            raise BuildError(f"sourceId duplicato: {source_id}")
        seen_ids.add(source_id)
        name = safe_asset_name(str(pack.get("asset", "")))
        if name in seen_assets:
            raise BuildError(f"asset duplicato: {name}")
        seen_assets.add(name)
        if not isinstance(pack.get("sourceUrl"), str):
            raise BuildError(f"sourceUrl mancante per {source_id}")
    return manifest


def find_entry(archive: zipfile.ZipFile, basename: str) -> str | None:
    names = [name for name in archive.namelist() if not name.endswith("/")]
    exact = [name for name in names if name == basename]
    nested = [name for name in names if name.endswith("/" + basename)]
    matches = exact + nested
    if len(matches) > 1:
        raise BuildError(f"GTFS ambiguo: piu tabelle {basename}")
    return matches[0] if matches else None


def validate_archive_limits(archive: zipfile.ZipFile) -> None:
    entries = archive.infolist()
    if len(entries) > MAX_ARCHIVE_ENTRIES:
        raise BuildError(f"archivio GTFS con troppe entry: {len(entries)} > {MAX_ARCHIVE_ENTRIES}")
    files = [entry for entry in entries if not entry.is_dir()]
    total = sum(entry.file_size for entry in files)
    if total > MAX_ARCHIVE_UNCOMPRESSED_BYTES:
        raise BuildError(
            f"archivio GTFS decompresso oltre limite: {total} > {MAX_ARCHIVE_UNCOMPRESSED_BYTES} bytes"
        )
    for entry in files:
        if entry.file_size > MAX_ARCHIVE_MEMBER_BYTES:
            raise BuildError(
                f"membro GTFS oltre limite: {entry.filename} {entry.file_size} > {MAX_ARCHIVE_MEMBER_BYTES} bytes"
            )
        ratio = entry.file_size / max(entry.compress_size, 1)
        if ratio > MAX_COMPRESSION_RATIO:
            raise BuildError(
                f"rapporto di compressione GTFS sospetto: {entry.filename} {ratio:.1f} > {MAX_COMPRESSION_RATIO}"
            )


@dataclass
class RetentionBudget:
    retained_rows: int = 0
    retained_cells: int = 0
    retained_utf8_bytes: int = 0

    def retain(self, basename: str, row: Mapping[str, str]) -> None:
        self.retained_rows += 1
        self.retained_cells += len(row)
        self.retained_utf8_bytes += sum(len(value.encode("utf-8")) for value in row.values())
        if self.retained_rows > MAX_RETAINED_BUILD_ROWS:
            raise BuildError(
                f"build GTFS oltre budget righe trattenute: {self.retained_rows} > {MAX_RETAINED_BUILD_ROWS}"
            )
        if self.retained_cells > MAX_RETAINED_BUILD_CELLS:
            raise BuildError(
                f"build GTFS oltre budget celle trattenute: {self.retained_cells} > {MAX_RETAINED_BUILD_CELLS}"
            )
        if self.retained_utf8_bytes > MAX_RETAINED_BUILD_UTF8_BYTES:
            raise BuildError(
                "build GTFS oltre budget byte UTF-8 trattenuti: "
                f"{self.retained_utf8_bytes} > {MAX_RETAINED_BUILD_UTF8_BYTES}"
            )


def bounded_csv_lines(text: io.TextIOBase, basename: str) -> Iterable[str]:
    while True:
        line = text.readline(MAX_CSV_PHYSICAL_LINE_UTF8_BYTES + 1)
        if not line:
            return
        if len(line) > MAX_CSV_PHYSICAL_LINE_UTF8_BYTES and not line.endswith(("\n", "\r")):
            raise BuildError(
                f"riga fisica CSV oltre limite in {basename}: {MAX_CSV_PHYSICAL_LINE_UTF8_BYTES} caratteri"
            )
        if len(line.encode("utf-8")) > MAX_CSV_PHYSICAL_LINE_UTF8_BYTES:
            raise BuildError(
                f"riga fisica CSV oltre limite UTF-8 in {basename}: {MAX_CSV_PHYSICAL_LINE_UTF8_BYTES} bytes"
            )
        yield line


def read_table(
    archive: zipfile.ZipFile,
    basename: str,
    *,
    required: bool = False,
    predicate: Callable[[Mapping[str, str]], bool] | None = None,
    budget: RetentionBudget | None = None,
) -> CsvTable | None:
    entry = find_entry(archive, basename)
    if entry is None:
        if required:
            raise BuildError(f"GTFS senza tabella obbligatoria: {basename}")
        return None
    try:
        with archive.open(entry) as raw:
            text = io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")
            csv.field_size_limit(MAX_CSV_FIELD_UTF8_BYTES)
            reader = csv.DictReader(bounded_csv_lines(text, basename))
            if reader.fieldnames is None:
                raise BuildError(f"tabella vuota: {basename}")
            header = tuple(name.strip() for name in reader.fieldnames)
            if not header or any(not name for name in header):
                raise BuildError(f"header non valido in {basename}")
            if len(set(header)) != len(header):
                raise BuildError(f"header con colonne duplicate in {basename}")
            if len(header) > MAX_CSV_COLUMNS:
                raise BuildError(f"troppe colonne in {basename}: {len(header)} > {MAX_CSV_COLUMNS}")
            rows_list: list[dict[str, str]] = []
            for index, row in enumerate(reader, 1):
                if index > MAX_TABLE_ROWS:
                    raise BuildError(f"tabella oltre limite righe: {basename} > {MAX_TABLE_ROWS}")
                if None in row:
                    raise BuildError(f"riga con campi oltre l'header in {basename}")
                normalized = {key: value or "" for key, value in row.items() if key is not None}
                multiline_field = next(
                    (key for key, value in normalized.items() if "\n" in value or "\r" in value),
                    None,
                )
                if multiline_field is not None:
                    raise BuildError(
                        f"campo CSV multilinea non supportato dal runtime in {basename}.{multiline_field}"
                    )
                oversized_field = next(
                    (key for key, value in normalized.items() if len(value.encode("utf-8")) > MAX_CSV_FIELD_UTF8_BYTES),
                    None,
                )
                if oversized_field is not None:
                    raise BuildError(
                        f"campo oltre limite UTF-8 in {basename}.{oversized_field}: {MAX_CSV_FIELD_UTF8_BYTES} bytes"
                    )
                if predicate is not None and not predicate(normalized):
                    continue
                if len(rows_list) >= MAX_RETAINED_TABLE_ROWS:
                    raise BuildError(
                        f"tabella oltre budget righe trattenute: {basename} > {MAX_RETAINED_TABLE_ROWS}"
                    )
                if budget is not None:
                    budget.retain(basename, normalized)
                rows_list.append(normalized)
            rows = tuple(rows_list)
            return CsvTable(header, rows)
    except UnicodeDecodeError as error:
        raise BuildError(f"codifica CSV non valida in {basename}") from error
    except (csv.Error, zipfile.BadZipFile) as error:
        raise BuildError(f"tabella ZIP/CSV non valida in {basename}: {error}") from error


def require_columns(table: CsvTable, name: str, *columns: str) -> None:
    missing = set(columns) - set(table.header)
    if missing:
        raise BuildError(f"{name} senza colonne: {', '.join(sorted(missing))}")


def empty_calendar() -> CsvTable:
    return CsvTable(
        ("service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "start_date", "end_date"),
        (),
    )


def empty_calendar_dates() -> CsvTable:
    return CsvTable(("service_id", "date", "exception_type"), ())


def parse_gtfs_date(value: str, context: str) -> date:
    if len(value) != 8 or not value.isdigit():
        raise BuildError(f"data GTFS non valida ({context}): {value!r}")
    try:
        return date(int(value[0:4]), int(value[4:6]), int(value[6:8]))
    except ValueError as error:
        raise BuildError(f"data GTFS non valida ({context}): {value!r}") from error


def last_service_date(calendar: CsvTable, calendar_dates: CsvTable, source_id: str) -> date:
    values: list[date] = []
    require_columns(calendar, "calendar.txt", "end_date")
    require_columns(calendar_dates, "calendar_dates.txt", "date", "exception_type")
    for row in calendar.rows:
        value = row.get("end_date", "").strip()
        if value:
            values.append(parse_gtfs_date(value, f"{source_id}:calendar.txt"))
    for row in calendar_dates.rows:
        if row.get("exception_type", "").strip() == "1":
            value = row.get("date", "").strip()
            if value:
                values.append(parse_gtfs_date(value, f"{source_id}:calendar_dates.txt"))
    if not values:
        raise BuildError(f"impossibile derivare lastServiceDate per {source_id}")
    return max(values)


def selected_routes(source_id: str, routes: CsvTable) -> tuple[tuple[dict[str, str], ...], dict[str, Any]]:
    require_columns(routes, "routes.txt", "route_id", "route_type")
    explicit = EXCLUSIVE_ROUTE_IDS.get(source_id)
    if explicit is not None:
        selected = tuple(row for row in routes.rows if row.get("route_id", "") in explicit)
        found = {row.get("route_id", "") for row in selected}
        missing = explicit - found
        if missing:
            raise BuildError(f"{source_id}: route selezionata assente: {', '.join(sorted(missing))}")
        metadata = {
            "kind": "explicit-route-ids",
            "description": "Selettore revisionato specifico della fonte; nessun matching per nome.",
            "routeIds": sorted(explicit),
        }
    else:
        selected = tuple(row for row in routes.rows if row.get("route_type", "").strip() in RAIL_ROUTE_TYPES)
        metadata = {
            "kind": "gtfs-rail-route-types",
            "description": "route_type GTFS ferroviari standard ed estesi (2, 100-199).",
            "routeTypes": sorted(RAIL_ROUTE_TYPES, key=int),
        }
    if not selected:
        raise BuildError(f"{source_id}: nessuna route ammessa dal filtro")
    return selected, metadata


def original_archive_sha256(archive: zipfile.ZipFile, source_id: str, fallback: str) -> str:
    """Preserve provenance when a filtered release pack is rebuilt.

    The usual release invocation has a distinct raw input directory.  The
    convenient default also permits rebuilding from the embedded assets
    themselves; in that case this function keeps the original source digest so
    a byte-different DEFLATE rebuild still has stable upstream provenance rather
    than treating the first filtered ZIP as a new upstream download.  Canonical
    member hashes, not compressed ZIP bytes, prove that content identity.
    """
    entry = find_entry(archive, "binario_filter_manifest.json")
    if entry is None:
        return fallback
    try:
        payload = json.loads(archive.read(entry).decode("utf-8"))
    except (KeyError, UnicodeDecodeError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        raise BuildError(f"{source_id}: binario_filter_manifest.json non valido") from error
    value = payload.get("sourceArchiveSha256")
    if payload.get("sourceId") == source_id and isinstance(value, str) and len(value) == 64 and all(
        character in "0123456789abcdef" for character in value
    ):
        return value
    return fallback


def filter_archive(source: Path, destination: Path, source_id: str) -> FilterResult:
    """Filter one complete GTFS archive and write a deterministic ZIP."""
    if not source.is_file() or not zipfile.is_zipfile(source):
        raise BuildError(f"{source_id}: archivio ZIP GTFS non valido: {source}")
    input_hash = sha256_file(source)
    with zipfile.ZipFile(source) as archive:
        validate_archive_limits(archive)
        source_hash = original_archive_sha256(archive, source_id, input_hash)
        budget = RetentionBudget()
        routes = read_table(archive, "routes.txt", required=True, budget=budget)
        assert routes
        kept_routes, policy = selected_routes(source_id, routes)
        route_ids = {row["route_id"] for row in kept_routes if row.get("route_id")}
        trips = read_table(
            archive,
            "trips.txt",
            required=True,
            predicate=lambda row: row.get("route_id", "") in route_ids,
            budget=budget,
        )
        assert trips
        require_columns(trips, "trips.txt", "route_id", "trip_id", "service_id")
        kept_trips = trips.rows
        trip_ids = {row["trip_id"] for row in kept_trips if row.get("trip_id")}
        service_ids = {row["service_id"] for row in kept_trips if row.get("service_id")}
        if not trip_ids:
            raise BuildError(f"{source_id}: le route filtrate non hanno trip")

        stop_times = read_table(
            archive,
            "stop_times.txt",
            required=True,
            predicate=lambda row: row.get("trip_id", "") in trip_ids,
            budget=budget,
        )
        assert stop_times
        require_columns(stop_times, "stop_times.txt", "trip_id", "stop_id")
        kept_stop_times = stop_times.rows
        stop_ids = {row["stop_id"] for row in kept_stop_times if row.get("stop_id")}
        if not stop_ids:
            raise BuildError(f"{source_id}: i trip filtrati non hanno stop_times")

        stops = read_table(archive, "stops.txt", required=True, budget=budget)
        agency = read_table(archive, "agency.txt", required=True, budget=budget)
        calendar = read_table(
            archive,
            "calendar.txt",
            predicate=lambda row: row.get("service_id", "") in service_ids,
            budget=budget,
        ) or empty_calendar()
        calendar_dates = read_table(
            archive,
            "calendar_dates.txt",
            predicate=lambda row: row.get("service_id", "") in service_ids,
            budget=budget,
        ) or empty_calendar_dates()
        assert agency and stops
        require_columns(agency, "agency.txt", "agency_name")
        require_columns(stops, "stops.txt", "stop_id")
        require_columns(calendar, "calendar.txt", "service_id", "end_date")
        require_columns(calendar_dates, "calendar_dates.txt", "service_id", "date", "exception_type")

        # Retain all parent stations of a referenced platform so stop hierarchy
        # remains valid after filtering.
        stops_by_id = {row.get("stop_id", ""): row for row in stops.rows if row.get("stop_id")}
        if not stop_ids <= stops_by_id.keys():
            unknown = sorted(stop_ids - stops_by_id.keys())
            raise BuildError(f"{source_id}: stop_times riferisce stop assenti: {unknown[:3]}")
        if "parent_station" in stops.header:
            pending = list(stop_ids)
            while pending:
                current = pending.pop()
                parent = stops_by_id[current].get("parent_station", "").strip()
                if parent and parent not in stop_ids:
                    # A few producers reference a synthetic parent that is not
                    # included in stops.txt.  The child stop remains a valid
                    # GTFS stop; do not manufacture a parent record that could
                    # masquerade as source data.
                    if parent in stops_by_id:
                        stop_ids.add(parent)
                        pending.append(parent)
        kept_stops = tuple(row for row in stops.rows if row.get("stop_id", "") in stop_ids)

        selected_agencies = {row.get("agency_id", "").strip() for row in kept_routes if row.get("agency_id", "").strip()}
        if selected_agencies and "agency_id" in agency.header:
            kept_agency = tuple(row for row in agency.rows if row.get("agency_id", "").strip() in selected_agencies)
            if not kept_agency:
                raise BuildError(f"{source_id}: agency_id delle route non presente in agency.txt")
        else:
            kept_agency = agency.rows
        if not kept_agency:
            raise BuildError(f"{source_id}: nessuna agenzia dopo filtro")

        kept_calendar = tuple(row for row in calendar.rows if row.get("service_id", "") in service_ids)
        kept_calendar_dates = tuple(row for row in calendar_dates.rows if row.get("service_id", "") in service_ids)
        filtered_calendar = CsvTable(calendar.header, kept_calendar)
        filtered_calendar_dates = CsvTable(calendar_dates.header, kept_calendar_dates)
        coverage_end = last_service_date(filtered_calendar, filtered_calendar_dates, source_id)

        tables: dict[str, CsvTable] = {
            "agency.txt": CsvTable(agency.header, kept_agency),
            "stops.txt": CsvTable(stops.header, kept_stops),
            "routes.txt": CsvTable(routes.header, kept_routes),
            "trips.txt": CsvTable(trips.header, kept_trips),
            "stop_times.txt": CsvTable(stop_times.header, kept_stop_times),
            "calendar.txt": filtered_calendar,
            "calendar_dates.txt": filtered_calendar_dates,
        }

        frequencies = read_table(
            archive,
            "frequencies.txt",
            predicate=lambda row: row.get("trip_id", "") in trip_ids,
            budget=budget,
        )
        if frequencies is not None:
            require_columns(frequencies, "frequencies.txt", "trip_id")
            tables["frequencies.txt"] = frequencies

        shape_ids = {row.get("shape_id", "") for row in kept_trips if row.get("shape_id", "")}
        shapes = read_table(
            archive,
            "shapes.txt",
            predicate=lambda row: row.get("shape_id", "") in shape_ids,
            budget=budget,
        )
        if shapes is not None:
            require_columns(shapes, "shapes.txt", "shape_id")
            tables["shapes.txt"] = shapes

        transfers = read_table(
            archive,
            "transfers.txt",
            predicate=lambda row: row.get("from_stop_id", "") in stop_ids
            and row.get("to_stop_id", "") in stop_ids,
            budget=budget,
        )
        if transfers is not None:
            require_columns(transfers, "transfers.txt", "from_stop_id", "to_stop_id")
            tables["transfers.txt"] = transfers

        pathways = read_table(
            archive,
            "pathways.txt",
            predicate=lambda row: row.get("from_stop_id", "") in stop_ids
            and row.get("to_stop_id", "") in stop_ids,
            budget=budget,
        )
        if pathways is not None:
            require_columns(pathways, "pathways.txt", "from_stop_id", "to_stop_id")
            tables["pathways.txt"] = pathways

        for table_name in COPY_METADATA_TABLES:
            table = read_table(archive, table_name, budget=budget)
            if table is not None:
                tables[table_name] = table

    counts = {
        "agency": len(tables["agency.txt"].rows),
        "routes": len(kept_routes),
        "trips": len(kept_trips),
        "stopTimes": len(kept_stop_times),
        "stops": len(kept_stops),
    }
    metadata = {
        "filterVersion": TOOL_VERSION,
        "sourceId": source_id,
        "sourceArchiveSha256": source_hash,
        "policy": policy,
        "counts": counts,
        "lastServiceDate": coverage_end.isoformat(),
        MEMBER_DIGEST_FIELD: {name: canonical_csv_sha256(table) for name, table in sorted(tables.items())},
    }
    write_deterministic_zip(destination, tables, metadata)
    return FilterResult(
        source_id,
        coverage_end,
        metadata,
        source_hash,
        canonical_content_sha256(metadata[MEMBER_DIGEST_FIELD]),
    )


def write_zip_entry(archive: zipfile.ZipFile, name: str, data: bytes) -> None:
    # Fixed metadata and insertion order minimize local ZIP variation. DEFLATE
    # bytes may nevertheless differ between zlib implementations; member
    # digests are the cross-environment reproducibility contract.
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def write_csv_entry(archive: zipfile.ZipFile, name: str, table: CsvTable) -> None:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    with archive.open(info, "w", force_zip64=True) as raw:
        text = io.TextIOWrapper(raw, encoding="utf-8", newline="", write_through=True)
        write_canonical_csv(text, table)
        text.detach()


def write_deterministic_zip(destination: Path, tables: Mapping[str, CsvTable], metadata: Mapping[str, Any]) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9, strict_timestamps=True) as archive:
        for name in sorted(tables):
            write_csv_entry(archive, name, tables[name])
        write_zip_entry(
            archive,
            "binario_filter_manifest.json",
            (json.dumps(metadata, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8"),
        )


def manifest_for_build(source_manifest: Mapping[str, Any], results: Mapping[str, FilterResult], generated_at: str) -> dict[str, Any]:
    try:
        date.fromisoformat(generated_at)
    except ValueError as error:
        raise BuildError(f"generatedAt non ISO-8601: {generated_at}") from error
    packs: list[dict[str, Any]] = []
    for original in source_manifest["packs"]:
        source_id = original["sourceId"]
        result = results[source_id]
        pack = dict(original)
        name = safe_asset_name(pack["asset"])
        pack["sha256"] = result.source_archive_sha256  # overwritten after ZIP write below
        pack["lastServiceDate"] = result.last_service_date.isoformat()
        pack["filter"] = result.filter_metadata["policy"]
        pack["sourceArchiveSha256"] = result.source_archive_sha256
        pack["contentSha256"] = result.content_sha256
        pack["asset"] = f"gtfs/{name}"
        packs.append(pack)
    return {
        "schemaVersion": RELEASE_MANIFEST_VERSION,
        "generatedAt": generated_at,
        "purpose": source_manifest.get("purpose", "Offline GTFS packs for Binario"),
        "refreshCadence": "every-timetable-change",
        "filteringVersion": TOOL_VERSION,
        "integrity": RELEASE_INTEGRITY,
        "packs": packs,
    }


def write_json(path: Path, payload: Mapping[str, Any]) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_sha256sums(path: Path, manifest: Mapping[str, Any], directory: Path) -> None:
    lines = []
    for pack in manifest["packs"]:
        name = safe_asset_name(pack["asset"])
        lines.append(f"{pack['sha256']}  {name}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _atomic_replace_directory_locked(staging: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        os.replace(staging, target)
        return
    backup = target.parent / f".{target.name}.previous-{uuid.uuid4().hex}"
    os.replace(target, backup)
    try:
        os.replace(staging, target)
    except BaseException:
        os.replace(backup, target)
        raise
    try:
        shutil.rmtree(backup)
    except OSError as error:
        print(f"warning: pubblicazione completata; backup da rimuovere manualmente {backup}: {error}", file=sys.stderr)


@contextmanager
def publication_lock(target: Path) -> Iterable[None]:
    lock_name = hashlib.sha256(str(target.resolve()).encode("utf-8")).hexdigest()
    lock_path = Path(tempfile.gettempdir()) / f"binario-gtfs-publish-{lock_name}.lock"
    with lock_path.open("a+b") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def atomic_replace_directory(staging: Path, target: Path) -> None:
    """Serialize publishers, then publish completely or retain the previous release."""
    with publication_lock(target):
        _atomic_replace_directory_locked(staging, target)


def _build_packs_locked(
    input_dir: Path,
    output_dir: Path,
    *,
    manifest_path: Path | None = None,
    generated_at: str | None = None,
    input_overrides: Mapping[str, Path] | None = None,
) -> dict[str, Any]:
    """Build all registered packs and atomically replace ``output_dir``."""
    manifest_path = manifest_path or input_dir / "manifest.json"
    source_manifest = read_manifest(manifest_path)
    generated_at = generated_at or source_manifest.get("generatedAt")
    if not isinstance(generated_at, str):
        raise BuildError("specificare --generated-at quando il manifest non lo contiene")
    overrides = input_overrides or {}
    published_assets: dict[str, Path] = {}
    published_manifest_path = output_dir / "manifest.json"
    if overrides and output_dir != input_dir and published_manifest_path.is_file():
        published_manifest = read_manifest(published_manifest_path)
        published_assets = {
            pack["sourceId"]: output_dir / safe_asset_name(pack["asset"])
            for pack in published_manifest["packs"]
        }
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".gtfs-build-", dir=output_dir.parent))
    try:
        results: dict[str, FilterResult] = {}
        for pack in source_manifest["packs"]:
            source_id = pack["sourceId"]
            name = safe_asset_name(pack["asset"])
            source = overrides.get(source_id) or published_assets.get(source_id) or input_dir / name
            results[source_id] = filter_archive(source, staging / name, source_id)
        output_manifest = manifest_for_build(source_manifest, results, generated_at)
        for pack in output_manifest["packs"]:
            name = safe_asset_name(pack["asset"])
            pack["sha256"] = sha256_file(staging / name)
        write_json(staging / "manifest.json", output_manifest)
        write_sha256sums(staging / "SHA256SUMS", output_manifest, staging)
        verify_assets(staging, as_of=None, min_valid_days=None)
        _atomic_replace_directory_locked(staging, output_dir)
        return output_manifest
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def build_packs(
    input_dir: Path,
    output_dir: Path,
    *,
    manifest_path: Path | None = None,
    generated_at: str | None = None,
    input_overrides: Mapping[str, Path] | None = None,
) -> dict[str, Any]:
    """Serialize the complete base-read/build/publish transaction for ``output_dir``."""
    with publication_lock(output_dir):
        return _build_packs_locked(
            input_dir,
            output_dir,
            manifest_path=manifest_path,
            generated_at=generated_at,
            input_overrides=input_overrides,
        )


def parse_sha256sums(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise BuildError(f"SHA256SUMS non leggibile: {error}") from error
    values: dict[str, str] = {}
    for line in lines:
        if not line.strip():
            continue
        pieces = line.split(maxsplit=1)
        if len(pieces) != 2 or len(pieces[0]) != 64 or any(ch not in "0123456789abcdef" for ch in pieces[0]):
            raise BuildError(f"riga SHA256SUMS non valida: {line!r}")
        name = pieces[1].lstrip("*").strip()
        if not name or Path(name).name != name or name in values:
            raise BuildError(f"nome SHA256SUMS non valido o duplicato: {name!r}")
        values[name] = pieces[0]
    return values


def service_end_from_asset(path: Path, source_id: str) -> date:
    if not zipfile.is_zipfile(path):
        raise BuildError(f"asset non ZIP: {path.name}")
    with zipfile.ZipFile(path) as archive:
        validate_archive_limits(archive)
        calendar = read_table(archive, "calendar.txt") or empty_calendar()
        calendar_dates = read_table(archive, "calendar_dates.txt") or empty_calendar_dates()
    return last_service_date(calendar, calendar_dates, source_id)


def verify_filter_from_asset(path: Path, source_id: str) -> str:
    """Require a release ZIP to prove filter policy and canonical content identity."""
    with zipfile.ZipFile(path) as archive:
        validate_archive_limits(archive)
        entry = find_entry(archive, FILTER_MANIFEST_MEMBER)
        if entry is None:
            raise BuildError(f"{source_id}: asset non filtrato ({FILTER_MANIFEST_MEMBER} mancante)")
        try:
            metadata = json.loads(archive.read(entry).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise BuildError(f"{source_id}: metadati filtro non validi") from error
        if metadata.get("filterVersion") != TOOL_VERSION or metadata.get("sourceId") != source_id:
            raise BuildError(f"{source_id}: metadati filtro non corrispondenti alla pipeline")
        declared_digests = declared_member_digests(metadata, source_id)
        actual_digests = canonical_member_digests(archive, entry)
        if declared_digests != actual_digests:
            mismatched = sorted(
                name
                for name in set(declared_digests) | set(actual_digests)
                if declared_digests.get(name) != actual_digests.get(name)
            )
            raise BuildError(f"{source_id}: digest canonici member incoerenti: {', '.join(mismatched[:3])}")
        routes = read_table(archive, "routes.txt", required=True)
        assert routes is not None
        selected, policy = selected_routes(source_id, routes)
        actual_ids = {row.get("route_id", "") for row in routes.rows}
        expected_ids = {row.get("route_id", "") for row in selected}
        if actual_ids != expected_ids or metadata.get("policy") != policy:
            raise BuildError(f"{source_id}: route o criterio filtro incoerente")
        expected_counts = metadata.get("counts")
        if not isinstance(expected_counts, dict) or expected_counts.get("routes") != len(routes.rows):
            raise BuildError(f"{source_id}: conteggi filtro incoerenti")
        for table_name, count_key in (("trips.txt", "trips"), ("stop_times.txt", "stopTimes"), ("stops.txt", "stops")):
            table = read_table(archive, table_name, required=True)
            assert table is not None
            if expected_counts.get(count_key) != len(table.rows):
                raise BuildError(f"{source_id}: conteggio {count_key} incoerente")
        return canonical_content_sha256(declared_digests)


def verify_assets(
    asset_dir: Path,
    *,
    as_of: date | None,
    min_valid_days: int | None,
) -> None:
    """Check digest, manifest date derivation, and optional release freshness."""
    manifest = read_manifest(asset_dir / "manifest.json")
    packs = manifest["packs"]
    expected_names = {safe_asset_name(pack["asset"]) for pack in packs}
    actual_names = {path.name for path in asset_dir.glob("*.zip") if path.is_file()}
    problems: list[str] = []
    if actual_names != expected_names:
        problems.append(f"asset ZIP != manifest: disco={sorted(actual_names)} manifest={sorted(expected_names)}")
    sums = parse_sha256sums(asset_dir / "SHA256SUMS")
    if set(sums) != expected_names:
        problems.append(f"SHA256SUMS != manifest: sums={sorted(sums)} manifest={sorted(expected_names)}")
    for pack in packs:
        source_id = pack["sourceId"]
        name = safe_asset_name(pack["asset"])
        path = asset_dir / name
        if not path.is_file():
            continue
        actual_hash = sha256_file(path)
        if pack.get("sha256") != actual_hash:
            problems.append(f"hash manifest incoerente per {name}: {pack.get('sha256')} != {actual_hash}")
        if sums.get(name) != actual_hash:
            problems.append(f"hash SHA256SUMS incoerente per {name}: {sums.get(name)} != {actual_hash}")
        try:
            declared_end = date.fromisoformat(str(pack["lastServiceDate"]))
        except (KeyError, ValueError) as error:
            problems.append(f"lastServiceDate non valida per {name}: {error}")
            continue
        try:
            actual_end = service_end_from_asset(path, source_id)
        except BuildError as error:
            problems.append(str(error))
            continue
        try:
            actual_content_hash = verify_filter_from_asset(path, source_id)
        except BuildError as error:
            problems.append(str(error))
        else:
            declared_content_hash = pack.get("contentSha256")
            if declared_content_hash != actual_content_hash:
                problems.append(
                    f"digest contenuto incoerente per {name}: {declared_content_hash} != {actual_content_hash}"
                )
        if declared_end != actual_end:
            problems.append(f"lastServiceDate incoerente per {name}: {declared_end} != {actual_end}")
        if as_of is not None and min_valid_days is not None:
            threshold = as_of + timedelta(days=min_valid_days)
            if actual_end <= threshold:
                problems.append(
                    f"pack scaduto o in scadenza entro {min_valid_days} giorni: "
                    f"{source_id} termina {actual_end} (soglia {threshold})"
                )
    if problems:
        raise BuildError("\n".join(problems))


def download_to_file(
    url: str,
    destination: Path,
    *,
    max_bytes: int = DEFAULT_MAX_DOWNLOAD_BYTES,
    timeout_seconds: int = 45,
    urlopen: Callable[..., BinaryIO] | None = None,
) -> int:
    """Download HTTPS only, enforcing the cap both before and during streaming."""
    if max_bytes <= 0:
        raise ValueError("max_bytes deve essere positivo")
    require_https_url(url, context="download rifiutato")
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "Binario-GTFS-build/0.20.4 (release tooling; read-only)", "Accept": "application/zip,application/octet-stream"},
    )
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.{uuid.uuid4().hex}.part")
    written = 0
    try:
        open_url = urlopen or urllib.request.build_opener(HttpsOnlyRedirectHandler()).open
        with open_url(request, timeout=timeout_seconds) as response:
            final_url = response.geturl() if hasattr(response, "geturl") else url
            require_https_url(final_url, context="URL finale rifiutato")
            declared = response.headers.get("Content-Length")
            if declared and declared.isdigit() and int(declared) > max_bytes:
                raise DownloadError(f"download oltre limite: {declared} > {max_bytes} bytes")
            with temporary.open("wb") as output:
                while True:
                    chunk = response.read(DOWNLOAD_CHUNK_SIZE)
                    if not chunk:
                        break
                    written += len(chunk)
                    if written > max_bytes:
                        raise DownloadError(f"download oltre limite streaming: {written} > {max_bytes} bytes")
                    output.write(chunk)
        if written == 0:
            raise DownloadError("download vuoto")
        if not zipfile.is_zipfile(temporary):
            raise DownloadError("download non e un archivio ZIP")
        os.replace(temporary, destination)
        return written
    except (OSError, http.client.HTTPException) as error:
        raise DownloadError(f"download fallito: {error}") from error
    finally:
        temporary.unlink(missing_ok=True)


def refresh_overrides(
    manifest: Mapping[str, Any],
    source_ids: set[str],
    *,
    max_download_bytes: int,
) -> tuple[dict[str, Path], Path]:
    known = {pack["sourceId"] for pack in manifest["packs"]}
    unknown = source_ids - known
    if unknown:
        raise BuildError(f"source-id sconosciuto: {', '.join(sorted(unknown))}")
    selected = [pack for pack in manifest["packs"] if not source_ids or pack["sourceId"] in source_ids]
    download_dir = Path(tempfile.mkdtemp(prefix="binario-gtfs-download-"))
    overrides: dict[str, Path] = {}
    try:
        for pack in selected:
            source_id = pack["sourceId"]
            access_mode = pack.get("accessMode")
            url = pack["sourceUrl"]
            if access_mode != "OPEN_DIRECT":
                raise DownloadError(
                    f"{source_id}: fonte {access_mode} non scaricabile direttamente; "
                    "serve un archivio completo acquisito tramite canale approvato"
                )
            require_https_url(url, context=f"{source_id}: download rifiutato")
            target = download_dir / safe_asset_name(pack["asset"])
            download_to_file(url, target, max_bytes=max_download_bytes)
            overrides[source_id] = target
        return overrides, download_dir
    except BaseException:
        shutil.rmtree(download_dir, ignore_errors=True)
        raise


def probe_sources(manifest: Mapping[str, Any], timeout_seconds: int = 20) -> list[dict[str, str]]:
    """Read-only HEAD diagnostics; never treats HTTP sources as refreshable."""
    rows: list[dict[str, str]] = []
    opener = urllib.request.build_opener(HttpsOnlyRedirectHandler())
    for pack in manifest["packs"]:
        source_id = pack["sourceId"]
        url = pack["sourceUrl"]
        if pack.get("accessMode") != "OPEN_DIRECT":
            rows.append({"sourceId": source_id, "status": "BLOCKED", "detail": "requires approved HTTPS gateway", "url": url})
            continue
        try:
            require_https_url(url, context="probe rifiutato")
            request = urllib.request.Request(
                url,
                method="HEAD",
                headers={"User-Agent": "Binario-GTFS-build/0.20.4 (read-only diagnostic)"},
            )
            with opener.open(request, timeout=timeout_seconds) as response:
                require_https_url(response.geturl(), context="URL finale probe rifiutato")
                rows.append(
                    {
                        "sourceId": source_id,
                        "status": str(response.status),
                        "detail": f"content-length={response.headers.get('Content-Length', '?')}; content-type={response.headers.get('Content-Type', '?')}",
                        "url": url,
                    }
                )
        except (OSError, ValueError, http.client.HTTPException, DownloadError) as error:
            rows.append({"sourceId": source_id, "status": "ERROR", "detail": type(error).__name__, "url": url})
    return rows


def parse_date(value: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"data ISO-8601 richiesta: {value}") from error


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, default=DEFAULT_ASSET_DIR, help="directory con manifest e ZIP input")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_ASSET_DIR, help="directory asset pubblicata atomicamente")
    parser.add_argument("--manifest", type=Path, help="registro sorgenti (default: INPUT_DIR/manifest.json)")
    parser.add_argument("--generated-at", help="data ISO da scrivere nel manifest; default: data gia registrata")
    parser.add_argument("--refresh", action="store_true", help="scarica ZIP solo da source HTTPS OPEN_DIRECT registrate")
    parser.add_argument("--source-id", action="append", default=[], help="limita --refresh alle sourceId indicate (ripetibile)")
    parser.add_argument("--max-download-bytes", type=int, default=DEFAULT_MAX_DOWNLOAD_BYTES, help="cap hard per ogni download")
    parser.add_argument("--check", action="store_true", help="verifica asset, hash e validita release senza rete")
    parser.add_argument("--as-of", type=parse_date, default=date.today(), help="data di riferimento per --check")
    parser.add_argument("--min-valid-days", type=int, default=30, help="giorni minimi residui richiesti da --check")
    parser.add_argument(
        "--integrity-only",
        action="store_true",
        help="con --check verifica struttura, filtri, hash e date dichiarate senza applicare la soglia freshness release",
    )
    parser.add_argument("--probe", action="store_true", help="HEAD read-only delle URL registrate, senza download")
    args = parser.parse_args(argv)

    if args.min_valid_days < 0:
        parser.error("--min-valid-days non puo essere negativo")
    if args.check and (args.refresh or args.probe):
        parser.error("--check non puo essere combinato con --refresh o --probe")
    if args.integrity_only and not args.check:
        parser.error("--integrity-only richiede --check")
    if args.probe and args.refresh:
        parser.error("--probe non puo essere combinato con --refresh")

    try:
        manifest_path = args.manifest or args.input_dir / "manifest.json"
        manifest = read_manifest(manifest_path)
        if args.probe:
            for row in probe_sources(manifest):
                print("\t".join((row["sourceId"], row["status"], row["detail"], row["url"])))
            return 0
        if args.check:
            verify_assets(
                args.input_dir,
                as_of=None if args.integrity_only else args.as_of,
                min_valid_days=None if args.integrity_only else args.min_valid_days,
            )
            print(f"GTFS asset verification passed: {args.input_dir}")
            return 0
        overrides: dict[str, Path] = {}
        download_dir: Path | None = None
        try:
            if args.refresh:
                overrides, download_dir = refresh_overrides(
                    manifest,
                    set(args.source_id),
                    max_download_bytes=args.max_download_bytes,
                )
            output = build_packs(
                args.input_dir,
                args.output_dir,
                manifest_path=manifest_path,
                generated_at=args.generated_at,
                input_overrides=overrides,
            )
        finally:
            if download_dir is not None:
                shutil.rmtree(download_dir, ignore_errors=True)
        print(json.dumps({"packs": len(output["packs"]), "outputDir": str(args.output_dir), "generatedAt": output["generatedAt"]}, ensure_ascii=False))
        return 0
    except (BuildError, csv.Error, zipfile.BadZipFile, http.client.HTTPException) as error:
        print(f"ERRORE GTFS: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
