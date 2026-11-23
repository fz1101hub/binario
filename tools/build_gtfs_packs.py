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

    # Add or refresh only the pinned national packs from reviewed upstream ZIPs;
    # every other published pack and manifest entry is kept byte for byte.
    python3 tools/build_gtfs_packs.py --only trenitalia-gtfs --only italo-gtfs \
      --source-archive trenitalia-gtfs=/path/gtfs-trenitalia.zip \
      --source-archive italo-gtfs=/path/gtfs-italo.zip --generated-at 2026-11-02

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
import statistics
import sys
import tempfile
import urllib.parse
import urllib.request
import uuid
import zipfile
from collections import defaultdict
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path
from typing import Any, BinaryIO, Callable, Iterable, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import foreign_pack_rules as foreign  # noqa: E402  (border corridors, licence gate: one place for every tool)

DEFAULT_ASSET_DIR = ROOT / "app/src/main/assets/gtfs"
# Full packs of the foreign packs bundled as their border subset (licence confirmed): not shipped in the app,
# published as GitHub pre-releases and downloaded on request (docs/data-sources/GTFS_PACK_REFRESH.md).
DEFAULT_FULL_PACK_DIR = ROOT / foreign.FULL_PACK_DIR
DEFAULT_CATALOGUE = ROOT / foreign.CATALOGUE_PATH
DEFAULT_MAX_DOWNLOAD_BYTES = 200 * 1024 * 1024
DOWNLOAD_CHUNK_SIZE = 64 * 1024
MAX_ARCHIVE_ENTRIES = 128
MAX_ARCHIVE_UNCOMPRESSED_BYTES = 1024 * 1024 * 1024
MAX_ARCHIVE_MEMBER_BYTES = 512 * 1024 * 1024
MAX_COMPRESSION_RATIO = 200
# Rows read from one source table (only the rows a filter keeps are retained, see MAX_RETAINED_*). City feeds are
# mostly buses: Rome's stop_times.txt has 5.1 million rows, of which the metro and tram pack keeps a fraction.
MAX_TABLE_ROWS = 8_000_000
MAX_RETAINED_TABLE_ROWS = 750_000
MAX_RETAINED_BUILD_ROWS = 1_000_000
# ACTV's stop_times.txt has 33 columns (operator extensions next to the GTFS ones).
MAX_CSV_COLUMNS = 48
MAX_CSV_FIELD_UTF8_BYTES = 16 * 1024
MAX_CSV_PHYSICAL_LINE_UTF8_BYTES = 256 * 1024
MAX_RETAINED_BUILD_CELLS = 6_000_000
MAX_RETAINED_BUILD_UTF8_BYTES = 128 * 1024 * 1024
TOOL_VERSION = 2
RELEASE_MANIFEST_VERSION = 4
WEEKDAY_COLUMNS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
# A timetable period keeps its service days at most a week apart and lasts at least a week;
# a lone exception date months later or a calendar row that ends on a day the service skips
# is not coverage the app can promise.
MAX_SERVICE_GAP_DAYS = 7
MIN_COVERAGE_SPAN_DAYS = 7
# The end of full coverage (fullServiceUntil): the last day running at least this share of the median trips of the same
# weekday in the first four weeks of the pack.  Trenitalia's feed reaches December 2027 but holds about 60 % of
# today's trains after the timetable change of 13 December 2026 (audit2 tools-data-2); a holiday's thinner service
# only moves the end when it is the last day.
FULL_SERVICE_SHARE = 0.8
FULL_SERVICE_REFERENCE_DAYS = 28
# A build keeps the trips running on a day from its generatedAt minus this many days (a run of the evening before can
# still be under way after midnight, and the apps read yesterday's runs); a trip whose service can never run again is
# dropped (audit2 tools-data improvement 1: 90 % of HŽPP, 82 % of STA, 58 % of Trenord, 41 % of ÖBB).  The day is
# recorded in the pack's filter manifest (tripsRunFrom).
EXPIRED_TRIP_GRACE_DAYS = 2
TRIPS_RUN_FROM_FIELD = "tripsRunFrom"
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

# Operator-native national feeds whose own scheduled coach lines (route_type 3)
# belong to the operator's published timetable: Trenitalia's regional and
# connecting buses and ItaloBus.  They are kept as scheduled coaches next to the
# trains; every other source stays rail-only, so a refresh can never turn an
# urban bus network into railway data.
SCHEDULED_COACH_ROUTE_TYPES = frozenset({"3"})
SCHEDULED_COACH_SOURCES = frozenset({"trenitalia-gtfs", "italo-gtfs"})

# Feeds that also publish the operator's combined itineraries as trips (Italo's NeTEx products, converted by
# deryclem/italo-gtfs): train A, a change, train B becomes a trip per leg under train A's number ("…A-B…_rail2" runs
# train B), or one trip for the whole itinerary.  The build keeps one trip per physical run of a service day, the
# train under its own number (see drop_itinerary_copies).  Never applied to feeds whose trips at the same minutes are
# coupled portions, each a train of its own (Trenord, SNCF, ÖBB, the Swiss feed).
ITINERARY_COPY_SOURCES = frozenset({"italo-gtfs"})
# A copy may have kept a dwell its train lost (or the other way round): departures may differ by this much.
MAX_DWELL_SKEW_MINUTES = 20

# Exceptional source-specific selectors are reviewed identifiers, not fuzzy
# keywords.  A changed upstream ID fails the build and requires review.
EXCLUSIVE_ROUTE_IDS: Mapping[str, frozenset[str]] = {
    # Genova Manin - Casella; AMT renumbered the route from FGC-164 to FGC-191 (reviewed 2 November 2026).
    "amt-genova-casella-gtfs": frozenset({"FGC-191"}),
    # TFT labels its two railway lines as route_type=3 (bus): see ROUTE_TYPE_OVERRIDES.
    "toscana-tft-gtfs": frozenset({"1028056267", "2272465657"}),
}
# route_type corrected at build for reviewed route ids: TFT publishes its railway lines Arezzo - Stia and Arezzo -
# Sinalunga as buses (3), which the apps would show as rail replacement buses and leave out of the train searches.
ROUTE_TYPE_OVERRIDES: Mapping[str, Mapping[str, str]] = {
    "toscana-tft-gtfs": {"1028056267": "2", "2272465657": "2"},
}
# A thin tail: a calendar kept alive by a trickle of trips long after the timetable's last full day, the last day running
# at least THIN_TAIL_FULL_SHARE of the busiest day's trips (:func:`thin_tail`).  Running more than THIN_TAIL_DAYS past it,
# the pack overstates its validity: the weekly refresh refuses it (tools/apply_ci_gtfs_refresh.py).  The reviewed sources
# below are clipped at that day by the build instead, recorded in the filter manifest (serviceClippedAfter): EAV's feed of
# November 2026 runs its full timetable to 31 December 2027, then at most one trip a day to 31 December 2030.
THIN_TAIL_FULL_SHARE = 0.25
THIN_TAIL_DAYS = 31
THIN_TAIL_CLIP_SOURCES = frozenset({"eav-gtfs"})
SERVICE_CLIPPED_AFTER_FIELD = "serviceClippedAfter"

# City networks (assets under gtfs-transit/ and gtfs-personal/): metro (1, 400-499), tram (0, 900-999), funiculars,
# cable cars and lifts (5, 6, 7, 1300-1499) and monorail (12). Never a bus: a city feed is mostly buses, which
# Binario does not plan with. Each source names the agencies left out because another pack already carries their
# lines (Naples' Linea 2 is a Trenitalia service of trenitalia-gtfs).
URBAN_ROUTE_TYPES = frozenset({
    "0", "1", "5", "6", "7", "12",
    *(str(value) for value in range(400, 500)),
    *(str(value) for value in range(900, 1000)),
    *(str(value) for value in range(1300, 1500)),
})
URBAN_SOURCES: Mapping[str, frozenset[str]] = {
    "milano-atm-gtfs": frozenset(),
    "roma-mobilita-gtfs": frozenset(),
    "napoli-anm-gtfs": frozenset({"FS"}),
    "firenze-gest-gtfs": frozenset(),
    "venezia-actv-gtfs": frozenset(),
    "genova-amt-gtfs": frozenset(),
    "arst-metro-gtfs": frozenset(),
    "gtt-gtfs": frozenset(),
    # Candidate of tools/transit_packs.json: the Marconi Express people mover (Bologna Centrale - airport), kept
    # whatever urban route_type TPER gives it (metro, monorail, cable or funicular types); never a bus.
    "marconi-express-gtfs": frozenset(),
}
# One letter per mode for the stop search badges of the city packs ("M" metro, "T" tram, "F" funicular or lift,
# "R" railway), written to binario_stop_modes.txt next to the GTFS tables.
STOP_MODES_MEMBER = "binario_stop_modes.txt"
ASSET_DIRECTORIES = ("gtfs/", "gtfs-transit/", "gtfs-personal/")


def mode_letter(route_type: str) -> str:
    value = int(route_type) if route_type.strip().isdigit() else -1
    if value in (1, 12) or 400 <= value < 500:
        return "M"
    if value == 0 or 900 <= value < 1000:
        return "T"
    if value in (5, 6, 7) or 1300 <= value < 1500:
        return "F"
    return "R"

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


def log_safe(value: object) -> str:
    """``value`` on one line, control characters escaped: text read from an archive or a server never starts a log
    line of its own, so it cannot become a workflow command or a ``BINARIO_*`` block in a CI log (audit A7-02)."""
    return "".join(
        character if character.isprintable() or character == " " else character.encode("unicode_escape").decode("ascii")
        for character in str(value)
    )


def print_error_lines(prefix: str, message: object, *, file: Any = None) -> None:
    """Every line of ``message`` behind ``prefix``, escaped: a multi-problem report stays readable and no line of it
    can start with text taken from the data."""
    for line in str(message).split("\n"):
        print(prefix + log_safe(line), file=file if file is not None else sys.stderr)


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
    full_service_until: date
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
            raise BuildError(f"{source_id}: SHA-256 canonico non valido per {name!r}")
        normalized[name] = digest
    return normalized


def canonical_content_sha256(member_digests: Mapping[str, str]) -> str:
    """Hash the sorted member-digest map so the release manifest can attest content."""
    canonical = json.dumps(dict(member_digests), sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    return hashlib.sha256(canonical.encode("ascii")).hexdigest()


def safe_asset_name(asset: str) -> str:
    """Return the filename for a manifest asset and reject path traversal."""
    normalized = asset.replace("\\", "/")
    directory = next((prefix for prefix in ASSET_DIRECTORIES if normalized.startswith(prefix)), None)
    if directory is None:
        raise BuildError(f"asset fuori da {', '.join(ASSET_DIRECTORIES)}: {asset}")
    name = normalized.removeprefix(directory)
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
        if "upstream" in pack:
            validate_upstream_pin(source_id, pack)
    return manifest


def _is_hex(value: object, length: int) -> bool:
    return isinstance(value, str) and len(value) == length and all(ch in "0123456789abcdef" for ch in value)


def validate_upstream_pin(source_id: str, pack: Mapping[str, Any]) -> None:
    """A pinned upstream names one immutable archive: a Git commit, a path and its SHA-256.

    The registered sourceUrl must be the raw URL of exactly that commit and path, so
    ``--refresh`` can only ever download the reviewed archive, and the build rejects
    any input whose digest differs from ``archiveSha256``.
    """
    upstream = pack.get("upstream")
    if not isinstance(upstream, dict):
        raise BuildError(f"{source_id}: upstream non valido")
    if upstream.get("kind") != "github-commit":
        raise BuildError(f"{source_id}: tipo di upstream non supportato: {upstream.get('kind')!r}")
    repository = upstream.get("repository")
    path = upstream.get("path")
    commit = upstream.get("commit")
    if not isinstance(repository, str) or not repository.startswith("https://github.com/"):
        raise BuildError(f"{source_id}: repository upstream non valido")
    slug = repository.removeprefix("https://github.com/")
    if slug.count("/") != 1 or not all(part and part not in {".", ".."} for part in slug.split("/")):
        raise BuildError(f"{source_id}: repository upstream non valido")
    if not isinstance(path, str) or not path or path.startswith("/") or ".." in path.split("/"):
        raise BuildError(f"{source_id}: percorso upstream non valido")
    if not _is_hex(commit, 40):
        raise BuildError(f"{source_id}: commit upstream non valido")
    if not _is_hex(upstream.get("archiveSha256"), 64):
        raise BuildError(f"{source_id}: archiveSha256 upstream non valido")
    expected_url = f"https://raw.githubusercontent.com/{slug}/{commit}/{path}"
    if pack.get("sourceUrl") != expected_url:
        raise BuildError(f"{source_id}: sourceUrl non corrisponde al commit upstream fissato ({expected_url})")


def require_pinned_archive(pack: Mapping[str, Any], source_archive_sha256: str) -> None:
    upstream = pack.get("upstream")
    if isinstance(upstream, dict) and upstream.get("archiveSha256") != source_archive_sha256:
        raise BuildError(
            f"{pack.get('sourceId')}: archivio sorgente {source_archive_sha256} diverso da quello fissato "
            f"{upstream.get('archiveSha256')}"
        )


def filter_manifest_entry(archive: zipfile.ZipFile) -> str | None:
    """The filter manifest this tool writes: only the exact top-level member counts, never a nested copy."""
    return FILTER_MANIFEST_MEMBER if FILTER_MANIFEST_MEMBER in archive.namelist() else None


def find_entry(archive: zipfile.ZipFile, basename: str) -> str | None:
    names = [name for name in archive.namelist() if not name.endswith("/")]
    exact = [name for name in names if name == basename]
    nested = [name for name in names if name.endswith("/" + basename)]
    matches = exact + nested
    if len(matches) > 1:
        raise BuildError(f"GTFS ambiguo: piu tabelle {basename}")
    return matches[0] if matches else None


def validate_archive_limits(archive: zipfile.ZipFile, *, omit_geometry: bool = False) -> None:
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
        # Timetable packs never open shapes.txt. Keep the total-size, entry and
        # compression-ratio guards even for that unused geometry member.
        ignored_shape = omit_geometry and entry.filename.rsplit("/", 1)[-1] == "shapes.txt"
        if entry.file_size > MAX_ARCHIVE_MEMBER_BYTES and not ignored_shape:
            raise BuildError(
                f"membro GTFS oltre limite: {entry.filename!r} {entry.file_size} > {MAX_ARCHIVE_MEMBER_BYTES} bytes"
            )
        ratio = entry.file_size / max(entry.compress_size, 1)
        if ratio > MAX_COMPRESSION_RATIO:
            raise BuildError(
                f"rapporto di compressione GTFS sospetto: {entry.filename!r} {ratio:.1f} > {MAX_COMPRESSION_RATIO}"
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
                if not required:
                    return None
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
                        f"campo CSV multilinea non supportato dal runtime in {basename}, colonna {multiline_field!r}"
                    )
                oversized_field = next(
                    (key for key, value in normalized.items() if len(value.encode("utf-8")) > MAX_CSV_FIELD_UTF8_BYTES),
                    None,
                )
                if oversized_field is not None:
                    raise BuildError(
                        f"campo oltre limite UTF-8 in {basename}, colonna {oversized_field!r}: {MAX_CSV_FIELD_UTF8_BYTES} bytes"
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
        raise BuildError(f"tabella ZIP/CSV non valida in {basename}: {log_safe(error)}") from error


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
        raise BuildError(f"data GTFS non valida ({context!r}): {value!r}")
    try:
        return date(int(value[0:4]), int(value[4:6]), int(value[6:8]))
    except ValueError as error:
        raise BuildError(f"data GTFS non valida ({context!r}): {value!r}") from error


def service_days(calendar: CsvTable, calendar_dates: CsvTable, source_id: str) -> dict[str, set[date]]:
    """The dates each service_id runs on, after weekday flags and exceptions."""
    require_columns(calendar, "calendar.txt", "service_id", "start_date", "end_date", *WEEKDAY_COLUMNS)
    require_columns(calendar_dates, "calendar_dates.txt", "service_id", "date", "exception_type")
    added: dict[str, set[date]] = {}
    removed: dict[str, set[date]] = {}
    for row in calendar_dates.rows:
        value = row.get("date", "").strip()
        if not value:
            continue
        exceptions = added if row.get("exception_type", "").strip() == "1" else removed
        exceptions.setdefault(row.get("service_id", ""), set()).add(parse_gtfs_date(value, f"{source_id}:calendar_dates.txt"))
    days: dict[str, set[date]] = {}
    for row in calendar.rows:
        start_value = row.get("start_date", "").strip()
        end_value = row.get("end_date", "").strip()
        if not start_value or not end_value:
            continue
        current = parse_gtfs_date(start_value, f"{source_id}:calendar.txt")
        end = parse_gtfs_date(end_value, f"{source_id}:calendar.txt")
        runs = tuple(row.get(column, "").strip() == "1" for column in WEEKDAY_COLUMNS)
        service_id = row.get("service_id", "")
        skipped = removed.get(service_id, set())
        active = days.setdefault(service_id, set())
        while current <= end:
            if runs[current.weekday()] and current not in skipped:
                active.add(current)
            current += timedelta(days=1)
    for service_id, dates in added.items():
        days.setdefault(service_id, set()).update(dates)
    return days


def service_dates(calendar: CsvTable, calendar_dates: CsvTable, source_id: str) -> set[date]:
    """Every date on which at least one service runs, after weekday flags and exceptions."""
    active: set[date] = set()
    for dates in service_days(calendar, calendar_dates, source_id).values():
        active.update(dates)
    return active


def last_service_date(calendar: CsvTable, calendar_dates: CsvTable, source_id: str) -> date:
    """Last day of continuous coverage: the end of the latest real timetable period."""
    dates = sorted(service_dates(calendar, calendar_dates, source_id))
    if not dates:
        raise BuildError(f"impossibile derivare lastServiceDate per {source_id}")
    periods: list[tuple[date, date]] = []
    first = dates[0]
    for previous, current in zip(dates, dates[1:]):
        if (current - previous).days > MAX_SERVICE_GAP_DAYS:
            periods.append((first, previous))
            first = current
    periods.append((first, dates[-1]))
    for start, end in reversed(periods):
        if (end - start).days + 1 >= MIN_COVERAGE_SPAN_DAYS:
            return end
    return dates[-1]


def trips_by_day(trip_rows: Iterable[Mapping[str, str]], days_by_service: Mapping[str, set[date]]) -> dict[date, int]:
    """How many trips run on each day."""
    per_service: dict[str, int] = {}
    for row in trip_rows:
        service_id = row.get("service_id", "")
        per_service[service_id] = per_service.get(service_id, 0) + 1
    counts: dict[date, int] = {}
    for service_id, trips in per_service.items():
        for day in days_by_service.get(service_id, ()):
            counts[day] = counts.get(day, 0) + trips
    return counts


def full_service_until(
    trip_rows: Iterable[Mapping[str, str]],
    calendar: CsvTable,
    calendar_dates: CsvTable,
    source_id: str,
    last: date,
    runs_from: date | None = None,
) -> date:
    """The last day, up to ``last``, with at least FULL_SERVICE_SHARE of the median trips of its weekday over the first
    FULL_SERVICE_REFERENCE_DAYS days of the pack (its first day with a trip, from ``runs_from``, the pack's tripsRunFrom,
    when given: the days before it lost their expired trips; days past its last are not counted)."""
    counts = trips_by_day(trip_rows, service_days(calendar, calendar_dates, source_id))
    running = sorted(day for day, trips in counts.items() if trips > 0)
    if not running:
        return last
    end = running[-1]
    start = next((day for day in running if runs_from is None or day >= runs_from), running[0])
    reference = [start + timedelta(days=offset) for offset in range(FULL_SERVICE_REFERENCE_DAYS)]
    medians = {
        weekday: statistics.median([counts.get(day, 0) for day in reference if day <= end and day.weekday() == weekday] or [0])
        for weekday in range(7)
    }
    full = [
        day for day in running
        if day <= last and medians[day.weekday()] > 0 and counts[day] >= FULL_SERVICE_SHARE * medians[day.weekday()]
    ]
    return max(full) if full else min(start, last)


def expired_trips(trip_rows: Iterable[Mapping[str, str]], days_by_service: Mapping[str, set[date]], runs_from: date) -> set[str]:
    """The trips whose service runs on no day from ``runs_from`` on: they can never run again."""
    alive = {service_id for service_id, days in days_by_service.items() if days and max(days) >= runs_from}
    return {row.get("trip_id", "") for row in trip_rows if row.get("service_id", "") not in alive}


def daily_trip_counts(trip_rows: Iterable[Mapping[str, str]], calendar: CsvTable, calendar_dates: CsvTable) -> dict[date, int]:
    """Trips running on each service day (calendar.txt by weekday, then calendar_dates.txt exceptions): the measure of
    :func:`thin_tail`, shared by the build's clip and the weekly refresh's check."""
    per_service: dict[str, int] = {}
    for trip in trip_rows:
        per_service[trip.get("service_id", "")] = per_service.get(trip.get("service_id", ""), 0) + 1
    patterns: dict[str, tuple[date, date, tuple[bool, ...]]] = {}
    grouped: dict[tuple[date, date, tuple[bool, ...]], int] = {}
    for row in calendar.rows:
        service = row.get("service_id", "")
        pattern = (
            parse_gtfs_date(row.get("start_date", ""), service),
            parse_gtfs_date(row.get("end_date", ""), service),
            tuple(row.get(column, "0").strip() == "1" for column in WEEKDAY_COLUMNS),
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
    for row in calendar_dates.rows:
        service = row.get("service_id", "")
        count = per_service.get(service, 0)
        if not count:
            continue
        day = parse_gtfs_date(row.get("date", ""), service)
        pattern = patterns.get(service)
        scheduled = pattern is not None and pattern[0] <= day <= pattern[1] and pattern[2][day.weekday()]
        if row.get("exception_type", "").strip() == "1" and not scheduled:
            days[day] = days.get(day, 0) + count
        elif row.get("exception_type", "").strip() == "2" and scheduled:
            days[day] = days.get(day, 0) - count
    return {day: count for day, count in days.items() if count > 0}


@dataclass(frozen=True)
class ThinTail:
    full_end: date  # the last day with at least THIN_TAIL_FULL_SHARE of the peak
    peak: int
    tail: int  # the most trips of a day after full_end
    last: date


def thin_tail(days: Mapping[date, int], last: date) -> ThinTail | None:
    """The thin tail of a pack whose trips per day (:func:`daily_trip_counts`) run to ``last``, its lastServiceDate:
    more than THIN_TAIL_DAYS after the last day with at least THIN_TAIL_FULL_SHARE of the busiest day's trips; else
    None.  One definition for the build's clip (THIN_TAIL_CLIP_SOURCES) and the weekly refresh's refusal."""
    running = {day: count for day, count in days.items() if day <= last}
    if not running:
        return None
    peak = max(running.values())
    full_end = max(day for day, count in running.items() if count >= peak * THIN_TAIL_FULL_SHARE)
    if (last - full_end).days <= THIN_TAIL_DAYS:
        return None
    return ThinTail(full_end, peak, max((count for day, count in running.items() if day > full_end), default=0), last)


def clip_service_after(calendar: CsvTable, calendar_dates: CsvTable, cut: date, source_id: str) -> tuple[CsvTable, CsvTable]:
    """calendar.txt ending on ``cut`` at the latest (a row starting after it goes) and calendar_dates.txt without the
    days after it."""
    end = cut.strftime("%Y%m%d")
    rows = []
    for row in calendar.rows:
        if parse_gtfs_date(row.get("start_date", "").strip(), f"{source_id}:calendar.txt") > cut:
            continue
        runs_after = parse_gtfs_date(row.get("end_date", "").strip(), f"{source_id}:calendar.txt") > cut
        rows.append({**row, "end_date": end} if runs_after else row)
    dates = tuple(
        row for row in calendar_dates.rows
        if parse_gtfs_date(row.get("date", "").strip(), f"{source_id}:calendar_dates.txt") <= cut
    )
    return CsvTable(calendar.header, tuple(rows)), CsvTable(calendar_dates.header, dates)


def published_service_clip(archive: zipfile.ZipFile, source_id: str, provenance: str | None) -> date | None:
    """The serviceClippedAfter of the published pack the input *is* (``provenance``, see :func:`published_provenance`),
    so that filtering it again keeps the clip; None for any other input."""
    entry = filter_manifest_entry(archive) if provenance is not None else None
    if entry is None:
        return None
    try:
        value = json.loads(archive.read(entry).decode("utf-8")).get(SERVICE_CLIPPED_AFTER_FIELD)
        return None if value is None else date.fromisoformat(str(value))
    except (UnicodeDecodeError, json.JSONDecodeError, AttributeError, ValueError) as error:
        raise BuildError(f"{source_id}: {SERVICE_CLIPPED_AFTER_FIELD} non valido") from error


def verify_service_clip(
    source_id: str,
    value: object,
    trip_rows: Sequence[Mapping[str, str]],
    calendar: CsvTable,
    calendar_dates: CsvTable,
    runs_from: date | None,
) -> None:
    """A pack declaring serviceClippedAfter is a reviewed source with no service day after it, every trip still runs
    (from its tripsRunFrom) and the day is a full one (:func:`thin_tail`)."""
    if source_id not in THIN_TAIL_CLIP_SOURCES:
        raise BuildError(f"{source_id}: {SERVICE_CLIPPED_AFTER_FIELD} su una fonte non rivista (THIN_TAIL_CLIP_SOURCES)")
    try:
        cut = date.fromisoformat(str(value))
    except ValueError as error:
        raise BuildError(f"{source_id}: {SERVICE_CLIPPED_AFTER_FIELD} non valido") from error
    later = [
        row.get("service_id", "") for row in calendar.rows
        if parse_gtfs_date(row.get("end_date", "").strip(), f"{source_id}:calendar.txt") > cut
    ] + [
        row.get("service_id", "") for row in calendar_dates.rows
        if parse_gtfs_date(row.get("date", "").strip(), f"{source_id}:calendar_dates.txt") > cut
    ]
    if later:
        raise BuildError(f"{source_id}: {SERVICE_CLIPPED_AFTER_FIELD} {cut} ma il calendario prosegue dopo (servizio {log_safe(later[0])})")
    dead = expired_trips(trip_rows, service_days(calendar, calendar_dates, source_id), runs_from or date.min)
    if dead:
        raise BuildError(f"{source_id}: {SERVICE_CLIPPED_AFTER_FIELD} {cut} con {len(dead)} trip che non circolano più")
    days = daily_trip_counts(trip_rows, calendar, calendar_dates)
    if not days or days.get(cut, 0) < max(days.values()) * THIN_TAIL_FULL_SHARE:
        raise BuildError(f"{source_id}: {SERVICE_CLIPPED_AFTER_FIELD} {cut} non è un giorno di orario completo")


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
        overrides = ROUTE_TYPE_OVERRIDES.get(source_id, {})
        if overrides:
            # The published route_type of these reviewed routes is wrong (a railway line published as a bus).
            selected = tuple(
                {**row, "route_type": overrides[row.get("route_id", "")]} if row.get("route_id", "") in overrides else row
                for row in selected
            )
            metadata["routeTypeOverrides"] = dict(sorted(overrides.items()))
    elif source_id in URBAN_SOURCES:
        excluded = URBAN_SOURCES[source_id]
        selected = tuple(
            row for row in routes.rows
            if row.get("route_type", "").strip() in URBAN_ROUTE_TYPES and row.get("agency_id", "").strip() not in excluded
        )
        metadata = {
            "kind": "gtfs-urban-route-types",
            "description": (
                "Rete urbana: metropolitana (1, 400-499), tram (0, 900-999), funicolari, cremagliere e ascensori "
                "(5, 6, 7, 1300-1499) e monorotaia (12); mai autobus."
            ),
            "routeTypes": sorted(URBAN_ROUTE_TYPES, key=int),
            **({"excludedAgencies": sorted(excluded)} if excluded else {}),
        }
    elif source_id in SCHEDULED_COACH_SOURCES:
        allowed = RAIL_ROUTE_TYPES | SCHEDULED_COACH_ROUTE_TYPES
        selected = tuple(row for row in routes.rows if row.get("route_type", "").strip() in allowed)
        metadata = {
            "kind": "gtfs-rail-and-operator-coach-route-types",
            "description": (
                "route_type GTFS ferroviari standard ed estesi (2, 100-199) e autobus di linea "
                "dell'operatore (3), presentati come bus programmati e mai come treni."
            ),
            "routeTypes": sorted(allowed, key=int),
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


def gtfs_minutes(value: str) -> int | None:
    """Minutes of an ``H:MM:SS`` GTFS time (past 24:00 for a run after midnight), or ``None`` when empty or malformed."""
    parts = value.strip().split(":")
    if len(parts) != 3 or not all(part.isdigit() for part in parts):
        return None
    return int(parts[0]) * 60 + int(parts[1])


@dataclass
class TripRun:
    """One train trip in stop order (:func:`drop_itinerary_copies`); ``rows`` index its stop_times rows."""

    trip_id: str
    order: int
    number: str
    service_id: str
    rows: list[int]
    stops: list[str]
    arrivals: list[int]
    departures: list[int]
    own: bool = False

    def start(self) -> tuple[int, str]:
        return self.departures[0], self.stops[0]

    def reach(self, first: int, other: "TripRun", offset: int) -> int:
        """The last stop of this trip, from ``first``, that ``other`` runs from ``offset`` on: the same stops in the
        same order, reached at the same minutes after ``first``, each left within MAX_DWELL_SKEW_MINUTES of the other
        trip but the last (a copy may have lost a dwell, or kept one its train lost).  ``first`` when none.  At a stop
        inside this trip (``first`` > 0, the change of an itinerary) the departure is not compared: an itinerary
        published as one trip keeps the first train's time there."""
        last = first
        while (
            last + 1 < len(self.stops)
            and offset + last + 1 - first < len(other.stops)
            and self.stops[last + 1] == other.stops[offset + last + 1 - first]
            and self.arrivals[last + 1] == other.arrivals[offset + last + 1 - first]
            and (
                (last == first and first > 0)
                or abs(self.departures[last] - other.departures[offset + last - first]) <= MAX_DWELL_SKEW_MINUTES
            )
        ):
            last += 1
        return last


# One piece of a trip found in another trip: (that trip, first and last position of the piece, offset there).
CopyPiece = tuple[TripRun, int, int, int]


def train_route_ids(routes: Iterable[Mapping[str, str]]) -> set[str]:
    return {row.get("route_id", "") for row in routes if row.get("route_type", "").strip() in RAIL_ROUTE_TYPES}


def trip_runs(
    trip_rows: Sequence[Mapping[str, str]],
    stop_time_rows: Sequence[Mapping[str, str]],
    train_route_ids: set[str],
) -> list[TripRun]:
    """The train trips with two timed stops at least, in trips.txt order; a trip with an untimed stop is left out."""
    rows_by_trip: dict[str, list[int]] = {}
    for index, row in enumerate(stop_time_rows):
        rows_by_trip.setdefault(row.get("trip_id", ""), []).append(index)
    runs: list[TripRun] = []
    for order, trip in enumerate(trip_rows):
        trip_id = trip.get("trip_id", "")
        if trip.get("route_id", "") not in train_route_ids:
            continue
        indices = rows_by_trip.get(trip_id, [])
        try:
            indices = sorted(indices, key=lambda index: int(stop_time_rows[index].get("stop_sequence", "").strip()))
        except ValueError:
            continue
        arrivals: list[int] = []
        departures: list[int] = []
        for index in indices:
            row = stop_time_rows[index]
            arrival = gtfs_minutes(row.get("arrival_time", ""))
            departure = gtfs_minutes(row.get("departure_time", ""))
            arrival = departure if arrival is None else arrival
            departure = arrival if departure is None else departure
            if arrival is None or departure is None:
                break
            arrivals.append(arrival)
            departures.append(departure)
        if len(arrivals) != len(indices) or len(indices) < 2:
            continue
        runs.append(TripRun(
            trip_id, order, trip.get("trip_short_name", "").strip(), trip.get("service_id", ""), indices,
            [stop_time_rows[index].get("stop_id", "") for index in indices], arrivals, departures,
        ))
    return runs


def copies_of_day(runs: Sequence[TripRun]) -> dict[str, list[CopyPiece]]:
    """The trips of one service day that repeat another train's run, each with the pieces of it found in the trips
    kept: a copy lies whole inside a trip kept before it; a combined itinerary (train A, a change, train B) is covered
    by two or more trains, one after the other.  Kept before: the trip starting where the first train of its number
    starts that day (the train under its own number), then the longer one, then the one named after its own train
    alone (:func:`named_trains`), then the one listed first; two trains that both start their own number are coupled
    portions and never merged."""
    first_start: dict[str, tuple[int, str]] = {}
    for run in runs:
        start = run.start()
        if run.number not in first_start or start < first_start[run.number]:
            first_start[run.number] = start
    for run in runs:
        run.own = first_start[run.number] == run.start()

    def kept_before(kept: TripRun, copy: TripRun) -> bool:
        if kept.own != copy.own:
            return kept.own
        if kept.own and kept.number != copy.number:
            return False
        if len(kept.stops) != len(copy.stops):
            return len(kept.stops) > len(copy.stops)
        # The same run under two numbers, neither starting its own: the trip named after its own train alone.
        alone = (named_trains(kept.trip_id) == [kept.number], named_trains(copy.trip_id) == [copy.number])
        if alone[0] != alone[1]:
            return alone[0]
        return kept.order < copy.order

    hops: dict[tuple[str, str, int], list[tuple[int, int]]] = {}
    for index, run in enumerate(runs):
        for position in range(1, len(run.stops)):
            hops.setdefault((run.stops[position - 1], run.stops[position], run.arrivals[position]), []).append((index, position - 1))

    def sharing(run: TripRun, position: int) -> list[tuple[int, int]]:
        return hops.get((run.stops[position], run.stops[position + 1], run.arrivals[position + 1]), [])

    copies: dict[int, list[CopyPiece]] = {}
    # An itinerary published as one trip: two or more trains under their own numbers run it piece after piece, and
    # two numbers at least are involved (its own and theirs).  Found first, so that a train inside it (the first train, up to the change) is not taken
    # for a copy of the longer itinerary under the same number.
    for index, run in enumerate(runs):
        pieces: list[CopyPiece] = []
        first = 0
        last = len(run.stops) - 1
        while first < last:
            best: CopyPiece | None = None
            for other_index, offset in sharing(run, first):
                other = runs[other_index]
                if other_index == index or not other.own:
                    continue
                reached = run.reach(first, other, offset)
                if reached > first and (best is None or reached > best[2]):
                    best = (other, first, reached, offset)
            if best is None:
                break
            pieces.append(best)
            first = best[2]
        if first == last and len(pieces) >= 2 and len({piece[0].number for piece in pieces} | {run.number}) >= 2:
            copies[index] = pieces
    for index, run in enumerate(runs):
        if index in copies:
            continue
        last = len(run.stops) - 1
        for other_index, offset in sharing(run, 0):
            other = runs[other_index]
            if (
                other_index != index and other_index not in copies and kept_before(other, run)
                and run.reach(0, other, offset) == last
            ):
                copies[index] = [(other, 0, last, offset)]
                break
    return {runs[index].trip_id: pieces for index, pieces in sorted(copies.items())}


def drop_itinerary_copies(
    trip_rows: Sequence[Mapping[str, str]],
    stop_time_rows: Sequence[Mapping[str, str]],
    train_route_ids: set[str],
    days_by_service: Mapping[str, set[date]],
) -> tuple[set[str], tuple[dict[str, str], ...], dict[str, int]]:
    """The trips that repeat another train's run on every day they run (:func:`copies_of_day`), the stop_times with
    the departures they carry, and counts for the filter manifest.

    A copy often keeps a dwell its train lost (Italo's 9939 leaves Roma Termini at 17:30, as the itineraries boarding it
    there say, while its own trip says 17:19, its arrival): before a copy goes, the train kept takes the latest
    departure of the two at each stop they share, when it still leaves before its next arrival.  A trip that is a copy
    on some of its days only stays (the app keeps one trip per run of each day).  Repeated until nothing changes, so
    a check of the published pack finds no copy left."""
    rows = [dict(row) for row in stop_time_rows]
    # The itineraries published as one trip go first: the converter lists only some stops of the second train, so no
    # train runs them piece after piece, and an itinerary is not a train (its number is the first train's).  Left in,
    # it would also seem to run the piece of a train it contains.
    itineraries = {
        trip.get("trip_id", "") for trip in trip_rows
        if trip.get("route_id", "") in train_route_ids
        and is_combined_itinerary(trip.get("trip_id", ""), trip.get("trip_short_name", ""))
    }
    dropped: set[str] = set(itineraries)
    departures_taken = 0
    partial = 0
    for _ in range(8):
        runs = [run for run in trip_runs(trip_rows, rows, train_route_ids) if run.trip_id not in dropped]
        runs_by_day: dict[date, list[TripRun]] = {}
        for run in runs:
            for day in days_by_service.get(run.service_id, ()):
                runs_by_day.setdefault(day, []).append(run)
        # Days with the same trips are one analysis.
        found_by_set: dict[tuple[str, ...], dict[str, list[CopyPiece]]] = {}
        copy_days: dict[str, int] = {}
        pieces_by_trip: dict[str, list[CopyPiece]] = {}
        for day in sorted(runs_by_day):
            key = tuple(run.trip_id for run in runs_by_day[day])
            if key not in found_by_set:
                found_by_set[key] = copies_of_day(runs_by_day[day])
            for trip_id, pieces in found_by_set[key].items():
                copy_days[trip_id] = copy_days.get(trip_id, 0) + 1
                pieces_by_trip.setdefault(trip_id, []).extend(pieces)
        newly = {
            run.trip_id for run in runs
            if copy_days.get(run.trip_id, 0) and copy_days[run.trip_id] == len(days_by_service.get(run.service_id, ()))
        }
        partial = sum(1 for trip_id in copy_days if trip_id not in newly)
        if not newly:
            break
        by_id = {run.trip_id: run for run in runs}
        for trip_id in sorted(newly):
            copy = by_id[trip_id]
            for kept, first, last, offset in pieces_by_trip[trip_id]:
                if kept.trip_id in newly:
                    continue
                # At the change of an itinerary the departure is the first train's, not this one's.
                for position in range(first + (1 if first > 0 else 0), last):
                    at = offset + position - first
                    if copy.departures[position] <= kept.departures[at] or copy.departures[position] > kept.arrivals[at + 1]:
                        continue
                    source = rows[copy.rows[position]]
                    rows[kept.rows[at]]["departure_time"] = source.get("departure_time", "").strip() or source.get("arrival_time", "")
                    kept.departures[at] = copy.departures[position]
                    departures_taken += 1
        dropped |= newly
    else:
        raise BuildError("copie di itinerario: nessun punto fisso dopo 8 passaggi")
    kept_rows = tuple(row for row in rows if row.get("trip_id", "") not in dropped)
    return dropped, kept_rows, {
        "droppedCopies": len(dropped) - len(itineraries),
        "droppedItineraries": len(itineraries),
        "departuresTaken": departures_taken,
        "partialCopiesKept": partial,
    }


def named_trains(trip_id: str) -> list[str]:
    """The train numbers a trip id names.  deryclem/italo-gtfs names each trip after its NeTEx product:
    ``IT::ServiceJourney:<train>-<counters>`` for a train, ``<A>-<B>-<counters>`` for an itinerary of train A then
    train B, with ``_rail1``, ``_rail2``… or ``_bus`` for one leg of it; counters have fewer than four digits."""
    numbers: list[str] = []
    for group in trip_id.rsplit(":", 1)[-1].split("_", 1)[0].split("-"):
        if len(group) < 4 or not group.isdigit():
            break
        numbers.append(group)
    return numbers


def is_combined_itinerary(trip_id: str, number: str) -> bool:
    """A whole itinerary in one trip (:func:`named_trains`): two train numbers at least, the first one its
    trip_short_name, and no leg suffix."""
    numbers = named_trains(trip_id)
    return "_" not in trip_id.rsplit(":", 1)[-1] and len(numbers) >= 2 and numbers[0] == number.strip()


def gtfs_seconds(value: str) -> int | None:
    """Seconds of an ``H:MM:SS`` GTFS time, or ``None`` when empty or malformed."""
    parts = value.strip().split(":")
    if len(parts) != 3 or not all(part.isdigit() for part in parts):
        return None
    return int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])


def gtfs_time(seconds: int) -> str:
    return f"{seconds // 3600:02d}:{seconds // 60 % 60:02d}:{seconds % 60:02d}"


# frequencies.txt runs a trip every headway_secs; Genova's lift every 60 s from 06:10 to 21:30 is 920 runs a direction.
MAX_FREQUENCY_RUNS = 5_000


def expand_frequencies(
    trip_rows: Sequence[Mapping[str, str]],
    stop_time_rows: Sequence[Mapping[str, str]],
    frequency_rows: Sequence[Mapping[str, str]],
    source_id: str,
) -> tuple[tuple[dict[str, str], ...], tuple[dict[str, str], ...], int]:
    """Each run of a frequency-based trip as a trip of its own (``<trip_id>@HHMMSS``, its first departure), its stop
    times shifted by the run's start; the template trip goes.  Runs start at start_time, every headway_secs, before
    end_time (GTFS).  A run is not a vehicle working, so its block_id is left empty."""
    calls: dict[str, list[Mapping[str, str]]] = {}
    for row in stop_time_rows:
        calls.setdefault(row.get("trip_id", ""), []).append(row)
    starts: dict[str, list[int]] = {}
    for row in frequency_rows:
        trip_id = row.get("trip_id", "")
        start, end = gtfs_seconds(row.get("start_time", "")), gtfs_seconds(row.get("end_time", ""))
        headway = row.get("headway_secs", "").strip()
        if start is None or end is None or not headway.isdigit() or int(headway) <= 0 or end < start:
            raise BuildError(f"{source_id}: frequencies.txt non valido per {log_safe(trip_id)}")
        starts.setdefault(trip_id, []).extend(range(start, end, int(headway)))
    if sum(len(values) for values in starts.values()) > MAX_FREQUENCY_RUNS:
        raise BuildError(f"{source_id}: frequencies.txt oltre {MAX_FREQUENCY_RUNS} corse")
    trips: list[dict[str, str]] = []
    expanded: dict[str, list[dict[str, str]]] = {}
    for trip in trip_rows:
        trip_id = trip.get("trip_id", "")
        if trip_id not in starts:
            trips.append(dict(trip))
            continue
        template = sorted(calls.get(trip_id, []), key=lambda row: int(row.get("stop_sequence", "0").strip() or 0))
        times = [
            (gtfs_seconds(row.get("arrival_time", "")), gtfs_seconds(row.get("departure_time", "")))
            for row in template
        ]
        origin = times[0][1] if times and times[0][1] is not None else (times[0][0] if times else None)
        if origin is None or any(arrival is None and departure is None for arrival, departure in times):
            raise BuildError(f"{source_id}: trip a frequenza senza orari: {log_safe(trip_id)}")
        for start in sorted(set(starts[trip_id])):
            run_id = f"{trip_id}@{gtfs_time(start).replace(':', '')}"
            trips.append({**trip, "trip_id": run_id, **({"block_id": ""} if "block_id" in trip else {})})
            shift = start - origin
            expanded.setdefault(trip_id, []).extend(
                {
                    **row,
                    "trip_id": run_id,
                    "arrival_time": gtfs_time(arrival + shift) if arrival is not None else "",
                    "departure_time": gtfs_time(departure + shift) if departure is not None else "",
                }
                for row, (arrival, departure) in zip(template, times)
            )
    stop_times: list[dict[str, str]] = []
    for row in stop_time_rows:
        trip_id = row.get("trip_id", "")
        if trip_id not in starts:
            stop_times.append(dict(row))
        elif trip_id in expanded:
            stop_times.extend(expanded.pop(trip_id))
    return tuple(trips), tuple(stop_times), sum(len(set(values)) for values in starts.values())


def stop_modes_table(
    routes: Sequence[Mapping[str, str]],
    trips: Sequence[Mapping[str, str]],
    stop_times: Sequence[Mapping[str, str]],
    stops: Sequence[Mapping[str, str]],
) -> CsvTable:
    """The modes serving each stop of a city pack (and its parent station), for the "M"/"T" search badges."""
    letter_by_route = {row.get("route_id", ""): mode_letter(row.get("route_type", "")) for row in routes}
    letter_by_trip = {row.get("trip_id", ""): letter_by_route.get(row.get("route_id", ""), "R") for row in trips}
    parents = {row.get("stop_id", ""): row.get("parent_station", "").strip() for row in stops}
    modes: dict[str, set[str]] = {}
    for row in stop_times:
        letter = letter_by_trip.get(row.get("trip_id", ""))
        stop = row.get("stop_id", "")
        if not letter or not stop:
            continue
        modes.setdefault(stop, set()).add(letter)
        parent = parents.get(stop, "")
        if parent:
            modes.setdefault(parent, set()).add(letter)
    order = "MTFR"
    return CsvTable(
        ("stop_id", "modes"),
        tuple({"stop_id": stop, "modes": "".join(sorted(letters, key=order.index))} for stop, letters in sorted(modes.items())),
    )


def published_provenance(entries: Iterable[Mapping[str, Any] | None], input_hash: str) -> str | None:
    """The recorded upstream digest when the input *is* an already published pack, else ``None``.

    A rebuild from the embedded assets (the convenient default, or ``--input-dir`` pointing at a published
    directory) must keep the original source digest: the input is then a filtered ZIP, not an upstream download.
    That case is recognised only by the bytes on disk: the input's SHA-256 equals the ``sha256`` of the same source
    in a published release manifest.  Any other input, whatever it carries inside, is its own provenance, so an
    archive cannot choose the digest that the upstream pin is checked against.
    """
    for entry in entries:
        if not isinstance(entry, Mapping) or entry.get("sha256") != input_hash:
            continue
        recorded = entry.get("sourceArchiveSha256")
        if _is_hex(recorded, 64):
            return str(recorded)
    return None


def original_archive_sha256(archive: zipfile.ZipFile, source_id: str, input_hash: str, provenance: str | None) -> str:
    """The upstream digest recorded for this build: ``input_hash`` unless the input is a published pack.

    For a published pack (``provenance`` from :func:`published_provenance`) its own top-level filter manifest must
    agree with the release manifest; an input without one is not a filtered pack and keeps ``input_hash``.  A nested
    ``*/binario_filter_manifest.json`` is never read.
    """
    if provenance is None:
        return input_hash
    entry = filter_manifest_entry(archive)
    if entry is None:
        return input_hash
    try:
        payload = json.loads(archive.read(entry).decode("utf-8"))
    except (KeyError, UnicodeDecodeError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        raise BuildError(f"{source_id}: {FILTER_MANIFEST_MEMBER} non valido") from error
    if not isinstance(payload, dict) or payload.get("sourceId") != source_id or payload.get("sourceArchiveSha256") != provenance:
        raise BuildError(f"{source_id}: provenienza del pack pubblicato incoerente con il manifest di release")
    return provenance


def filter_archive(
    source: Path,
    destination: Path,
    source_id: str,
    *,
    published_entries: Iterable[Mapping[str, Any] | None] = (),
    trips_run_from: date | None = None,
) -> FilterResult:
    """Filter one complete GTFS archive and write a deterministic ZIP.

    ``published_entries`` are the release-manifest entries of this source the input may be a byte-identical copy of
    (see :func:`published_provenance`); without a match the input's own SHA-256 is its provenance.  With
    ``trips_run_from`` the trips that can never run again from that day are dropped (:func:`expired_trips`).
    """
    if not source.is_file() or not zipfile.is_zipfile(source):
        raise BuildError(f"{source_id}: archivio ZIP GTFS non valido: {source}")
    input_hash = sha256_file(source)
    with zipfile.ZipFile(source) as archive:
        validate_archive_limits(archive, omit_geometry=True)
        provenance = published_provenance(published_entries, input_hash)
        source_hash = original_archive_sha256(archive, source_id, input_hash, provenance)
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

        # Rules that drop or change trips are named in the filter manifest; their counts go to the log, so that filtering
        # a published pack again gives the same bytes.
        trip_rules: list[str] = []
        frequencies = read_table(
            archive,
            "frequencies.txt",
            predicate=lambda row: row.get("trip_id", "") in trip_ids,
            budget=budget,
        )
        if frequencies is not None:
            require_columns(frequencies, "frequencies.txt", "trip_id", "start_time", "end_time", "headway_secs")
        if frequencies is not None and frequencies.rows:
            # No reader of the packs knows frequencies.txt: each run becomes a trip of its own.  Not named in tripRules:
            # the expanded pack has no frequencies.txt left, and filtering it again must give the same bytes.
            kept_trips, kept_stop_times, runs = expand_frequencies(kept_trips, kept_stop_times, frequencies.rows, source_id)
            print(f"{source_id}: frequencies.txt espanso in {runs} corse", file=sys.stderr)
            trip_ids = {row["trip_id"] for row in kept_trips if row.get("trip_id")}
            frequencies = None

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
        require_columns(calendar, "calendar.txt", "service_id", "end_date")
        require_columns(calendar_dates, "calendar_dates.txt", "service_id", "date", "exception_type")

        if source_id in ITINERARY_COPY_SOURCES:
            dropped, kept_stop_times, counts = drop_itinerary_copies(
                kept_trips, kept_stop_times, train_route_ids(kept_routes), service_days(calendar, calendar_dates, source_id),
            )
            trip_rules.append("itinerary-copies")
            print(f"{source_id}: copie di itinerario {log_safe(json.dumps(counts, sort_keys=True))}", file=sys.stderr)
            kept_trips = tuple(row for row in kept_trips if row.get("trip_id", "") not in dropped)
            trip_ids = {row["trip_id"] for row in kept_trips if row.get("trip_id")}
            service_ids = {row["service_id"] for row in kept_trips if row.get("service_id")}

        run_metadata: dict[str, str] = {}
        if trips_run_from is not None:
            expired = expired_trips(kept_trips, service_days(calendar, calendar_dates, source_id), trips_run_from)
            if expired:
                print(f"{source_id}: {len(expired)} trip che non circolano più dal {trips_run_from} rimossi", file=sys.stderr)
                kept_trips = tuple(row for row in kept_trips if row.get("trip_id", "") not in expired)
                kept_stop_times = tuple(row for row in kept_stop_times if row.get("trip_id", "") not in expired)
                trip_ids = {row["trip_id"] for row in kept_trips if row.get("trip_id")}
                service_ids = {row["service_id"] for row in kept_trips if row.get("service_id")}
            run_metadata[TRIPS_RUN_FROM_FIELD] = trips_run_from.isoformat()

        if source_id in THIN_TAIL_CLIP_SOURCES:
            # Measured on what the pack would publish (the calendar of the kept trips), as the weekly refresh measures it.
            kept_days = CsvTable(calendar.header, tuple(row for row in calendar.rows if row.get("service_id", "") in service_ids))
            kept_exceptions = CsvTable(
                calendar_dates.header, tuple(row for row in calendar_dates.rows if row.get("service_id", "") in service_ids),
            )
            tail = thin_tail(
                daily_trip_counts(kept_trips, kept_days, kept_exceptions),
                last_service_date(kept_days, kept_exceptions, source_id),
            )
            cut = tail.full_end if tail is not None else published_service_clip(archive, source_id, provenance)
            if cut is not None:
                if trips_run_from is not None and cut < trips_run_from:
                    raise BuildError(
                        f"{source_id}: orario completo fino al {cut}, prima del {trips_run_from} ({TRIPS_RUN_FROM_FIELD}): "
                        "tagliata la coda sottile non resterebbe nessuna corsa"
                    )
                calendar, calendar_dates = clip_service_after(calendar, calendar_dates, cut, source_id)
                clipped = expired_trips(kept_trips, service_days(calendar, calendar_dates, source_id), trips_run_from or date.min)
                if tail is not None:
                    print(
                        f"{source_id}: orario completo fino al {cut} (picco {tail.peak} corse al giorno), poi non più di "
                        f"{tail.tail} al giorno fino al {tail.last}: servizio tagliato, {len(clipped)} trip rimossi",
                        file=sys.stderr,
                    )
                if clipped:
                    kept_trips = tuple(row for row in kept_trips if row.get("trip_id", "") not in clipped)
                    kept_stop_times = tuple(row for row in kept_stop_times if row.get("trip_id", "") not in clipped)
                    if frequencies is not None:
                        frequencies = CsvTable(
                            frequencies.header, tuple(row for row in frequencies.rows if row.get("trip_id", "") not in clipped),
                        )
                    trip_ids = {row["trip_id"] for row in kept_trips if row.get("trip_id")}
                    service_ids = {row["service_id"] for row in kept_trips if row.get("service_id")}
                run_metadata[SERVICE_CLIPPED_AFTER_FIELD] = cut.isoformat()

        stop_ids = {row["stop_id"] for row in kept_stop_times if row.get("stop_id")}
        if not stop_ids:
            raise BuildError(f"{source_id}: i trip filtrati non hanno stop_times")

        stops = read_table(archive, "stops.txt", required=True, budget=budget)
        agency = read_table(archive, "agency.txt", required=True, budget=budget)
        assert agency and stops
        require_columns(agency, "agency.txt", "agency_name")
        require_columns(stops, "stops.txt", "stop_id")

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
        full_end = full_service_until(
            kept_trips, filtered_calendar, filtered_calendar_dates, source_id, coverage_end, trips_run_from,
        )

        tables: dict[str, CsvTable] = {
            "agency.txt": CsvTable(agency.header, kept_agency),
            "stops.txt": CsvTable(stops.header, kept_stops),
            "routes.txt": CsvTable(routes.header, kept_routes),
            "trips.txt": CsvTable(
                tuple(key for key in trips.header if key != "shape_id"),
                tuple({key: value for key, value in row.items() if key != "shape_id"} for row in kept_trips),
            ),
            "stop_times.txt": CsvTable(stop_times.header, kept_stop_times),
            "calendar.txt": filtered_calendar,
            "calendar_dates.txt": filtered_calendar_dates,
        }

        if frequencies is not None:
            # A feed's empty frequencies.txt (a header only) is kept as published.
            tables["frequencies.txt"] = frequencies

        # The app resolves schedules and stop coordinates, not route geometry.
        # Do not inflate hundreds of MB of shape points just to discard bus
        # shapes. Remove shape_id references together with the optional table.

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

        if source_id in URBAN_SOURCES:
            tables[STOP_MODES_MEMBER] = stop_modes_table(kept_routes, kept_trips, kept_stop_times, kept_stops)

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
        "omittedOptionalTables": ["shapes.txt"],
        **({"tripRules": trip_rules} if trip_rules else {}),
        **run_metadata,
        MEMBER_DIGEST_FIELD: {name: canonical_csv_sha256(table) for name, table in sorted(tables.items())},
    }
    write_deterministic_zip(destination, tables, metadata)
    return FilterResult(
        source_id,
        coverage_end,
        full_end,
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


def manifest_for_build(
    source_manifest: Mapping[str, Any],
    results: Mapping[str, FilterResult],
    generated_at: str,
    kept_entries: Mapping[str, Mapping[str, Any]] | None = None,
) -> dict[str, Any]:
    try:
        date.fromisoformat(generated_at)
    except ValueError as error:
        raise BuildError(f"generatedAt non ISO-8601: {generated_at}") from error
    kept = kept_entries or {}
    packs: list[dict[str, Any]] = []
    for original in source_manifest["packs"]:
        source_id = original["sourceId"]
        if source_id in kept:
            # Not rebuilt (--only): the published entry and ZIP stay byte for byte.
            packs.append(dict(kept[source_id]))
            continue
        result = results[source_id]
        require_pinned_archive(original, result.source_archive_sha256)
        pack = dict(original)
        name = safe_asset_name(pack["asset"])
        pack["sha256"] = result.source_archive_sha256  # overwritten after ZIP write below
        pack["lastServiceDate"] = result.last_service_date.isoformat()
        # Right after lastServiceDate in the manifest (an older entry has no such key yet).
        ordered: dict[str, Any] = {}
        for key, value in pack.items():
            if key != "fullServiceUntil":
                ordered[key] = value
            if key == "lastServiceDate":
                ordered["fullServiceUntil"] = result.full_service_until.isoformat()
        pack = ordered
        pack["filter"] = result.filter_metadata["policy"]
        pack["sourceArchiveSha256"] = result.source_archive_sha256
        pack["contentSha256"] = result.content_sha256
        # The asset keeps its directory (gtfs/, gtfs-transit/ or gtfs-personal/, checked by safe_asset_name).
        pack["asset"] = f"{pack['asset'].replace(chr(92), '/').split('/', 1)[0]}/{name}"
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
    only: Iterable[str] | None = None,
    full_pack_dir: Path | None = None,
    catalogue_path: Path | None = None,
) -> dict[str, Any]:
    """Build the registered packs and atomically replace ``output_dir``.

    With ``only``, just those sources are filtered again, from ``input_overrides``
    or ``input_dir`` (never from the pack already published in ``output_dir``);
    every other pack must already be published in ``output_dir`` and is copied
    with its manifest entry unchanged, so adding a source never rewrites the bytes
    of the others.

    A foreign pack whose licence is confirmed (:func:`foreign_pack_rules.is_border_pack`) is filtered from its full
    pack in ``full_pack_dir`` (or an override, or a whole pack in ``input_dir``) into ``full_pack_dir``, and
    ``output_dir`` receives its border subset (:func:`derive_border_subset`).
    """
    full_pack_dir = full_pack_dir or DEFAULT_FULL_PACK_DIR
    catalogue_path = catalogue_path or DEFAULT_CATALOGUE
    manifest_path = manifest_path or input_dir / "manifest.json"
    source_manifest = read_manifest(manifest_path)
    generated_at = generated_at or source_manifest.get("generatedAt")
    if not isinstance(generated_at, str):
        raise BuildError("specificare --generated-at quando il manifest non lo contiene")
    overrides = input_overrides or {}
    published_assets: dict[str, Path] = {}
    published_manifest_path = output_dir / "manifest.json"
    published_by_id: dict[str, Mapping[str, Any]] = {}
    if published_manifest_path.is_file():
        published_by_id = {pack["sourceId"]: pack for pack in read_manifest(published_manifest_path)["packs"]}
    if (overrides or only) and output_dir != input_dir and published_by_id:
        published_assets = {
            source_id: output_dir / safe_asset_name(pack["asset"])
            for source_id, pack in published_by_id.items()
        }
    selected = set(only or ())
    registered = {pack["sourceId"] for pack in source_manifest["packs"]}
    if selected - registered:
        raise BuildError(f"--only con sourceId non registrato: {', '.join(sorted(selected - registered))}")
    kept_entries: dict[str, Mapping[str, Any]] = {}
    if selected:
        if not published_manifest_path.is_file():
            raise BuildError("--only richiede un manifest gia pubblicato nella directory di output")
        published_entries = {pack["sourceId"]: pack for pack in read_manifest(published_manifest_path)["packs"]}
        for pack in source_manifest["packs"]:
            source_id = pack["sourceId"]
            if source_id in selected:
                continue
            entry = published_entries.get(source_id)
            if entry is None or safe_asset_name(entry["asset"]) != safe_asset_name(pack["asset"]):
                raise BuildError(f"{source_id}: non pubblicato in {output_dir}; ricostruirlo o includerlo in --only")
            kept_entries[source_id] = entry
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".gtfs-build-", dir=output_dir.parent))
    border_ids = {pack["sourceId"] for pack in source_manifest["packs"] if foreign.is_border_pack(pack)}
    staging_full: Path | None = None
    if border_ids:
        full_pack_dir.parent.mkdir(parents=True, exist_ok=True)
        staging_full = Path(tempfile.mkdtemp(prefix=".gtfs-full-build-", dir=full_pack_dir.parent))
    rebuilt_full: set[str] = set()
    try:
        # The pin file of the foreign packs lives next to the packs; tools/build_foreign_packs.py regenerates it.
        pins = output_dir / Path(foreign.ANDROID_PINS_PATH).name
        if pins.is_file():
            shutil.copyfile(pins, staging / pins.name)
        results: dict[str, FilterResult] = {}
        for pack in source_manifest["packs"]:
            source_id = pack["sourceId"]
            name = safe_asset_name(pack["asset"])
            border = source_id in border_ids
            if source_id in kept_entries:
                shutil.copyfile(output_dir / name, staging / name)
                if border and staging_full is not None and (full_pack_dir / name).is_file():
                    shutil.copyfile(full_pack_dir / name, staging_full / name)
                continue
            # A source named by --only is rebuilt from its --source-archive or from --input-dir, never from the pack
            # already published in output_dir: that would report a refresh while ignoring the new archive.
            published = None if source_id in selected else published_assets.get(source_id)
            entries: list[Mapping[str, Any] | None] = [pack, published_by_id.get(source_id)]
            if border:
                # Never from a published subset: from the full pack kept next to the assets (its fullPack digest names
                # its bytes), else from a whole pack (a pack whose licence was just confirmed).
                for entry in (pack, published_by_id.get(source_id)):
                    full = entry.get(FULL_PACK_FIELD) if isinstance(entry, Mapping) else None
                    if isinstance(full, Mapping):
                        entries.append({"sha256": full.get("sha256"), "sourceArchiveSha256": entry.get("sourceArchiveSha256")})
                candidates = [overrides.get(source_id), full_pack_dir / name, published, input_dir / name]
                source = next(
                    (candidate for candidate in candidates
                     if candidate is not None and candidate.is_file() and (candidate == overrides.get(source_id) or asset_border_zone(candidate) is None)),
                    None,
                )
                if source is None:
                    raise BuildError(f"{source_id}: nessun pack completo da cui derivare il sottoinsieme di confine")
            else:
                source = overrides.get(source_id) or published or input_dir / name
            # The registry entry and the published entry name the bytes of a published pack; only a byte-identical
            # input keeps their recorded upstream digest (A7-01).
            target = staging_full / name if border and staging_full is not None else staging / name
            results[source_id] = filter_archive(
                source, target, source_id, published_entries=entries,
                trips_run_from=date.fromisoformat(generated_at) - timedelta(days=EXPIRED_TRIP_GRACE_DAYS),
            )
            if border:
                rebuilt_full.add(name)
        output_manifest = manifest_for_build(source_manifest, results, generated_at, kept_entries)
        zone_catalogue = load_zone_catalogue(catalogue_path) if rebuilt_full else None
        for index, pack in enumerate(output_manifest["packs"]):
            if pack["sourceId"] in kept_entries:
                continue
            name = safe_asset_name(pack["asset"])
            if name in rebuilt_full and staging_full is not None and zone_catalogue is not None:
                derive_border_subset(staging_full / name, staging / name, pack["sourceId"], zone_catalogue)
                output_manifest["packs"][index] = border_manifest_entry(pack, staging / name, staging_full / name)
                continue
            output_manifest["packs"][index] = pack = whole_manifest_entry(pack)
            pack["sha256"] = sha256_file(staging / name)
        write_json(staging / "manifest.json", output_manifest)
        write_sha256sums(staging / "SHA256SUMS", output_manifest, staging)
        if staging_full is not None:
            write_full_pack_sums(staging_full, output_manifest)
        verify_assets(staging, as_of=None, min_valid_days=None, full_pack_dir=staging_full, catalogue_path=catalogue_path)
        _atomic_replace_directory_locked(staging, output_dir)
        if staging_full is not None:
            # The full packs are not part of the app: they follow the published assets (a failure here leaves the
            # previous full pack, which --check then reports against the new manifest).
            full_pack_dir.mkdir(parents=True, exist_ok=True)
            for name in sorted(rebuilt_full):
                os.replace(staging_full / name, full_pack_dir / name)
            write_full_pack_sums(full_pack_dir, output_manifest)
        return output_manifest
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    finally:
        if staging_full is not None:
            shutil.rmtree(staging_full, ignore_errors=True)


def build_packs(
    input_dir: Path,
    output_dir: Path,
    *,
    manifest_path: Path | None = None,
    generated_at: str | None = None,
    input_overrides: Mapping[str, Path] | None = None,
    only: Iterable[str] | None = None,
    full_pack_dir: Path | None = None,
    catalogue_path: Path | None = None,
) -> dict[str, Any]:
    """Serialize the complete base-read/build/publish transaction for ``output_dir``."""
    with publication_lock(output_dir):
        return _build_packs_locked(
            input_dir,
            output_dir,
            manifest_path=manifest_path,
            generated_at=generated_at,
            input_overrides=input_overrides,
            only=only,
            full_pack_dir=full_pack_dir,
            catalogue_path=catalogue_path,
        )


def apply_border_zones(
    asset_dir: Path,
    *,
    full_pack_dir: Path | None = None,
    catalogue_path: Path | None = None,
) -> list[dict[str, Any]]:
    """Bring the foreign packs of ``asset_dir`` in line with the licence gate, in place and atomically.

    A foreign pack whose licence is confirmed is bundled as the border subset of its full pack in ``full_pack_dir``
    (the first time, its whole pack moves there); a subset whose content is already the derived one keeps its bytes.
    A foreign pack whose licence is not confirmed is bundled whole again.  Returns one row per foreign pack for the
    report (sizes, trips kept).  Other packs and their manifest entries are untouched.
    """
    full_pack_dir = full_pack_dir or DEFAULT_FULL_PACK_DIR
    catalogue_path = catalogue_path or DEFAULT_CATALOGUE
    report: list[dict[str, Any]] = []
    with publication_lock(asset_dir):
        manifest = read_manifest(asset_dir / "manifest.json")
        zone_catalogue = load_zone_catalogue(catalogue_path)
        staging = Path(tempfile.mkdtemp(prefix=".gtfs-border-", dir=asset_dir.parent))
        full_pack_dir.mkdir(parents=True, exist_ok=True)
        staging_full = Path(tempfile.mkdtemp(prefix=".gtfs-full-", dir=full_pack_dir.parent))
        moved: set[str] = set()
        restored: set[str] = set()
        try:
            for item in asset_dir.iterdir():
                if item.is_file():
                    shutil.copyfile(item, staging / item.name)
            for index, entry in enumerate(manifest["packs"]):
                source_id = str(entry["sourceId"])
                if source_id not in foreign.FOREIGN_PACK_COUNTRIES:
                    continue
                name = safe_asset_name(str(entry["asset"]))
                asset, full = staging / name, full_pack_dir / name
                if foreign.is_border_pack(entry):
                    if full.is_file():
                        shutil.copyfile(full, staging_full / name)
                    elif asset.is_file() and asset_border_zone(asset) is None:
                        shutil.copyfile(asset, staging_full / name)
                        moved.add(name)
                    else:
                        raise BuildError(f"{source_id}: pack completo assente in {full_pack_dir}")
                    derived = staging / f".{name}.derived"
                    derive_border_subset(staging_full / name, derived, source_id, zone_catalogue)
                    if asset.is_file() and archive_member_bytes(asset) == archive_member_bytes(derived):
                        derived.unlink()
                    else:
                        os.replace(derived, asset)
                    manifest["packs"][index] = border_manifest_entry(entry, asset, staging_full / name)
                    zone = asset_border_zone(asset)
                    report.append({
                        "sourceId": source_id, "borderOnly": True, "fullBytes": (staging_full / name).stat().st_size,
                        "zoneBytes": asset.stat().st_size, "tripsKept": zone["tripsKept"], "tripsInFull": zone["tripsInFull"],
                    })
                else:
                    if FULL_PACK_FIELD in entry or asset_border_zone(asset) is not None:
                        if not full.is_file():
                            raise BuildError(f"{source_id}: pack completo assente in {full_pack_dir}: impossibile rimetterlo intero")
                        shutil.copyfile(full, asset)
                        restored.add(name)
                    whole = whole_manifest_entry(entry)
                    whole.update(
                        sha256=sha256_file(asset),
                        lastServiceDate=service_end_from_asset(asset, source_id).isoformat(),
                        fullServiceUntil=full_service_end_from_asset(asset, source_id).isoformat(),
                        contentSha256=verify_filter_from_asset(asset, source_id),
                    )
                    manifest["packs"][index] = {key: whole[key] for key in entry if key in whole} | {
                        key: value for key, value in whole.items() if key not in entry
                    }
                    report.append({"sourceId": source_id, "borderOnly": False, "fullBytes": asset.stat().st_size,
                                   "zoneBytes": None, "tripsKept": None, "tripsInFull": None})
            write_json(staging / "manifest.json", manifest)
            write_sha256sums(staging / "SHA256SUMS", manifest, staging)
            write_full_pack_sums(staging_full, manifest)
            verify_assets(staging, as_of=None, min_valid_days=None, full_pack_dir=staging_full, catalogue_path=catalogue_path)
            _atomic_replace_directory_locked(staging, asset_dir)
            for name in sorted(moved):
                os.replace(staging_full / name, full_pack_dir / name)
            for name in sorted(restored):
                (full_pack_dir / name).unlink(missing_ok=True)
            write_full_pack_sums(full_pack_dir, manifest)
            if not any(full_pack_dir.iterdir()):
                full_pack_dir.rmdir()
        except BaseException:
            shutil.rmtree(staging, ignore_errors=True)
            raise
        finally:
            shutil.rmtree(staging_full, ignore_errors=True)
    return report


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


def full_service_end_from_asset(path: Path, source_id: str) -> date:
    """fullServiceUntil derived from a built pack, as the build derives it (see :func:`full_service_until`)."""
    if not zipfile.is_zipfile(path):
        raise BuildError(f"asset non ZIP: {path.name}")
    with zipfile.ZipFile(path) as archive:
        validate_archive_limits(archive)
        calendar = read_table(archive, "calendar.txt") or empty_calendar()
        calendar_dates = read_table(archive, "calendar_dates.txt") or empty_calendar_dates()
        trips = read_table(archive, "trips.txt", required=True)
        assert trips is not None
        runs_from = trips_run_from_asset(archive, source_id)
    return full_service_until(
        trips.rows, calendar, calendar_dates, source_id, last_service_date(calendar, calendar_dates, source_id), runs_from,
    )


def trips_run_from_asset(archive: zipfile.ZipFile, source_id: str) -> date | None:
    """The tripsRunFrom a built pack declares in its filter manifest, or None (built before the rule)."""
    entry = filter_manifest_entry(archive)
    if entry is None:
        return None
    try:
        value = json.loads(archive.read(entry).decode("utf-8")).get(TRIPS_RUN_FROM_FIELD)
        return None if value is None else date.fromisoformat(str(value))
    except (UnicodeDecodeError, json.JSONDecodeError, AttributeError, ValueError) as error:
        raise BuildError(f"{source_id}: {TRIPS_RUN_FROM_FIELD} non valido") from error


def release_end(pack: Mapping[str, Any]) -> date:
    """The day a pack's release freshness is judged on: its fullServiceUntil when declared (never after its
    lastServiceDate), else its lastServiceDate."""
    last = date.fromisoformat(str(pack["lastServiceDate"]))
    full = pack.get("fullServiceUntil")
    return min(last, date.fromisoformat(str(full))) if full is not None else last


def verify_filter_from_asset(path: Path, source_id: str) -> str:
    """Require a release ZIP to prove filter policy and canonical content identity."""
    with zipfile.ZipFile(path) as archive:
        validate_archive_limits(archive)
        entry = filter_manifest_entry(archive)
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
            raise BuildError(f"{source_id}: digest canonici member incoerenti: {', '.join(log_safe(name) for name in mismatched[:3])}")
        routes = read_table(archive, "routes.txt", required=True)
        assert routes is not None
        selected, policy = selected_routes(source_id, routes)
        actual_ids = {row.get("route_id", "") for row in routes.rows}
        expected_ids = {row.get("route_id", "") for row in selected}
        if actual_ids != expected_ids or metadata.get("policy") != policy:
            raise BuildError(f"{source_id}: route o criterio filtro incoerente")
        overrides = ROUTE_TYPE_OVERRIDES.get(source_id, {})
        wrong_types = sorted(
            row.get("route_id", "") for row in routes.rows
            if row.get("route_id", "") in overrides and row.get("route_type", "").strip() != overrides[row.get("route_id", "")]
        )
        if wrong_types:
            raise BuildError(f"{source_id}: route_type non corretto per {', '.join(log_safe(route) for route in wrong_types)}")
        expected_counts = metadata.get("counts")
        if not isinstance(expected_counts, dict) or expected_counts.get("routes") != len(routes.rows):
            raise BuildError(f"{source_id}: conteggi filtro incoerenti")
        frequencies = read_table(archive, "frequencies.txt")
        if frequencies is not None and frequencies.rows:
            raise BuildError(f"{source_id}: frequencies.txt con corse non espanse (nessun lettore dei pack lo legge)")
        tables: dict[str, CsvTable] = {}
        for table_name, count_key in (("trips.txt", "trips"), ("stop_times.txt", "stopTimes"), ("stops.txt", "stops")):
            table = read_table(archive, table_name, required=True)
            assert table is not None
            if expected_counts.get(count_key) != len(table.rows):
                raise BuildError(f"{source_id}: conteggio {count_key} incoerente")
            tables[table_name] = table
        if source_id in ITINERARY_COPY_SOURCES:
            calendar = read_table(archive, "calendar.txt") or empty_calendar()
            calendar_dates = read_table(archive, "calendar_dates.txt") or empty_calendar_dates()
            left, _, _ = drop_itinerary_copies(
                tables["trips.txt"].rows, tables["stop_times.txt"].rows, train_route_ids(routes.rows),
                service_days(calendar, calendar_dates, source_id),
            )
            if left:
                raise BuildError(
                    f"{source_id}: {len(left)} trip ripetono la corsa di un altro treno in ogni giorno di servizio "
                    f"(copie di itinerario), es. {log_safe(sorted(left)[0])}"
                )
        runs_from = trips_run_from_asset(archive, source_id)
        if runs_from is not None:
            calendar = read_table(archive, "calendar.txt") or empty_calendar()
            calendar_dates = read_table(archive, "calendar_dates.txt") or empty_calendar_dates()
            expired = expired_trips(tables["trips.txt"].rows, service_days(calendar, calendar_dates, source_id), runs_from)
            if expired:
                raise BuildError(f"{source_id}: {len(expired)} trip non circolano più dal {runs_from} ({TRIPS_RUN_FROM_FIELD})")
        if metadata.get(SERVICE_CLIPPED_AFTER_FIELD) is not None:
            verify_service_clip(
                source_id, metadata[SERVICE_CLIPPED_AFTER_FIELD], tables["trips.txt"].rows,
                read_table(archive, "calendar.txt") or empty_calendar(),
                read_table(archive, "calendar_dates.txt") or empty_calendar_dates(), runs_from,
            )
        return canonical_content_sha256(declared_digests)


BORDER_ZONE_FIELD = "borderZone"
FULL_PACK_FIELD = "fullPack"
FULL_PACK_KEYS = frozenset({"path", "sha256", "sizeBytes", "contentSha256", "lastServiceDate", "fullServiceUntil"})
# Members a border subset derives from its full pack (design §1.1, rule 4); any other member fails closed.
BORDER_SUBSET_TABLES = frozenset({
    "agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt", "calendar.txt", "calendar_dates.txt",
    "transfers.txt", "pathways.txt", *COPY_METADATA_TABLES,
})


@dataclass(frozen=True)
class BorderSubset:
    metadata: dict[str, Any]
    content_sha256: str


def _column_values(rows: Iterable[Mapping[str, str]], column: str) -> set[str]:
    return {row.get(column, "") for row in rows}


def load_zone_catalogue(catalogue_path: Path) -> foreign.ZoneCatalogue:
    """The station catalogue with the one catalogue matcher of the tools (design §1.1, rule 5)."""
    import build_ios_gtfs_schedule as ios

    try:
        matcher, _ = ios.load_catalog(catalogue_path)
    except ios.BuildError as error:
        raise BuildError(f"catalogo stazioni non leggibile: {log_safe(error)}") from error
    return foreign.ZoneCatalogue(matcher, sha256_file(catalogue_path))


def pack_zone_of(stops: CsvTable, source_id: str, catalogue: foreign.ZoneCatalogue) -> foreign.PackZone:
    """The zone stops of a pack's stops.txt: Italian stations and the stations of the pack's corridors."""
    import build_ios_gtfs_schedule as ios

    require_columns(stops, "stops.txt", "stop_id", "stop_name")
    try:
        return ios.foreign_zone({row.get("stop_id", ""): row for row in stops.rows}, catalogue.matcher, source_id)
    except ios.BuildError as error:
        raise BuildError(f"{source_id}: {log_safe(error)}") from error


def derive_border_subset(
    full_pack: Path,
    destination: Path,
    source_id: str,
    catalogue: foreign.ZoneCatalogue,
) -> BorderSubset:
    """Write the border subset of a filtered foreign pack (design §1.1).

    A stop is in the zone when it is a stop of an Italian station (matched to a catalogue station whose
    trenitaliaLocationId starts with 83, or with Italy's country code in its stop_id) or of a station of one of the
    pack's frontalieri corridors (``foreign_pack_rules.CORRIDORS``).  Every trip calling at least once at a zone stop
    is kept whole, with every stop_times row; the other tables keep the rows those trips reference (stops with their
    parents, transfers and pathways between kept stops); feed metadata is copied.  The writer is the deterministic
    one of the build, and the filter manifest records the rule and its inputs in ``borderZone``.
    """
    if not full_pack.is_file() or not zipfile.is_zipfile(full_pack):
        raise BuildError(f"{source_id}: pack completo assente o non ZIP: {full_pack}")
    full_sha256 = sha256_file(full_pack)
    full_content_sha256 = verify_filter_from_asset(full_pack, source_id)
    with zipfile.ZipFile(full_pack) as archive:
        validate_archive_limits(archive)
        entry = filter_manifest_entry(archive)
        assert entry is not None  # verify_filter_from_asset requires it
        full_metadata = json.loads(archive.read(entry).decode("utf-8"))
        if BORDER_ZONE_FIELD in full_metadata:
            raise BuildError(f"{source_id}: {full_pack.name} è già un sottoinsieme di confine, non il pack completo")
        members = sorted(info.filename for info in archive.infolist() if not info.is_dir() and info.filename != entry)
        unknown = sorted(set(members) - BORDER_SUBSET_TABLES)
        if unknown:
            raise BuildError(f"{source_id}: tabelle non previste per il sottoinsieme di confine: {', '.join(map(log_safe, unknown))}")
        stops = read_table(archive, "stops.txt", required=True)
        assert stops is not None
        zone = pack_zone_of(stops, source_id, catalogue).stops
        trips_in_full: set[str] = set()
        kept_trip_ids: set[str] = set()

        def scan(row: Mapping[str, str]) -> bool:
            trips_in_full.add(row.get("trip_id", ""))
            if row.get("stop_id", "") in zone:
                kept_trip_ids.add(row.get("trip_id", ""))
            return False  # nothing retained: the kept rows are read below

        read_table(archive, "stop_times.txt", required=True, predicate=scan)
        if not kept_trip_ids:
            raise BuildError(f"{source_id}: nessuna corsa ferma in una stazione italiana o di un corridoio")
        tables: dict[str, CsvTable] = {}
        trips = read_table(archive, "trips.txt", required=True, predicate=lambda row: row.get("trip_id", "") in kept_trip_ids)
        stop_times = read_table(archive, "stop_times.txt", required=True, predicate=lambda row: row.get("trip_id", "") in kept_trip_ids)
        assert trips is not None and stop_times is not None
        route_ids = _column_values(trips.rows, "route_id")
        service_ids = _column_values(trips.rows, "service_id")
        routes = read_table(archive, "routes.txt", required=True, predicate=lambda row: row.get("route_id", "") in route_ids)
        agency = read_table(archive, "agency.txt", required=True)
        assert routes is not None and agency is not None
        if "agency_id" in routes.header and "agency_id" in agency.header:
            agency_ids = _column_values(routes.rows, "agency_id")
            agency = CsvTable(agency.header, tuple(row for row in agency.rows if row.get("agency_id", "") in agency_ids))
        by_stop = {row.get("stop_id", ""): row for row in stops.rows}
        kept_stops = _column_values(stop_times.rows, "stop_id")
        pending = list(kept_stops)
        while pending:  # platforms keep their station, boarding areas their platform
            parent = by_stop.get(pending.pop(), {}).get("parent_station", "")
            if parent and parent not in kept_stops:
                kept_stops.add(parent)
                pending.append(parent)
        tables.update({
            "agency.txt": agency,
            "routes.txt": routes,
            "trips.txt": trips,
            "stop_times.txt": stop_times,
            "stops.txt": CsvTable(stops.header, tuple(row for row in stops.rows if row.get("stop_id", "") in kept_stops)),
        })
        for name in ("calendar.txt", "calendar_dates.txt"):
            table = read_table(archive, name, predicate=lambda row: row.get("service_id", "") in service_ids)
            if table is not None:
                tables[name] = table

        def between_kept(row: Mapping[str, str]) -> bool:
            return (
                row.get("from_stop_id", "") in kept_stops and row.get("to_stop_id", "") in kept_stops
                and all(not row.get(key) or row[key] in kept_trip_ids for key in ("from_trip_id", "to_trip_id"))
                and all(not row.get(key) or row[key] in route_ids for key in ("from_route_id", "to_route_id"))
            )

        for name in ("transfers.txt", "pathways.txt"):
            table = read_table(archive, name, predicate=between_kept)
            if table is not None:
                tables[name] = table
        for name in COPY_METADATA_TABLES:
            table = read_table(archive, name)
            if table is not None:
                tables[name] = table
    calendar = tables.get("calendar.txt") or empty_calendar()
    calendar_dates = tables.get("calendar_dates.txt") or empty_calendar_dates()
    metadata = dict(full_metadata)
    metadata["counts"] = {
        "agency": len(tables["agency.txt"].rows),
        "routes": len(tables["routes.txt"].rows),
        "trips": len(tables["trips.txt"].rows),
        "stopTimes": len(tables["stop_times.txt"].rows),
        "stops": len(tables["stops.txt"].rows),
    }
    metadata["lastServiceDate"] = last_service_date(calendar, calendar_dates, source_id).isoformat()
    metadata[BORDER_ZONE_FIELD] = {
        "rule": foreign.BORDER_ZONE_RULE,
        "corridors": [corridor.name for corridor in foreign.CORRIDORS.get(source_id, ())],
        "italianStations": foreign.ITALIAN_STATION_RULE,
        "catalogueSha256": catalogue.sha256,
        "fullPackSha256": full_sha256,
        "fullContentSha256": full_content_sha256,
        "zoneStops": len(zone),
        "tripsKept": len(tables["trips.txt"].rows),
        "tripsInFull": len(trips_in_full),
        "tripRule": foreign.TRIP_RULE,
    }
    metadata[MEMBER_DIGEST_FIELD] = {name: canonical_csv_sha256(table) for name, table in sorted(tables.items())}
    write_deterministic_zip(destination, tables, metadata)
    return BorderSubset(metadata, canonical_content_sha256(metadata[MEMBER_DIGEST_FIELD]))


def archive_member_bytes(path: Path) -> dict[str, bytes]:
    """Uncompressed members of a ZIP: the content two ZIPs with different DEFLATE bytes share."""
    with zipfile.ZipFile(path) as archive:
        validate_archive_limits(archive)
        return {info.filename: archive.read(info) for info in archive.infolist() if not info.is_dir()}


def full_pack_entry(full_pack: Path, source_id: str) -> dict[str, Any]:
    """The ``fullPack`` manifest field of a border pack, derived from its full ZIP."""
    return {
        "path": f"{foreign.FULL_PACK_DIR}/{full_pack.name}",
        "sha256": sha256_file(full_pack),
        "sizeBytes": full_pack.stat().st_size,
        "contentSha256": verify_filter_from_asset(full_pack, source_id),
        "lastServiceDate": service_end_from_asset(full_pack, source_id).isoformat(),
        "fullServiceUntil": full_service_end_from_asset(full_pack, source_id).isoformat(),
    }


def border_manifest_entry(entry: Mapping[str, Any], subset: Path, full_pack: Path) -> dict[str, Any]:
    """A manifest entry for a border pack: ``sha256``, ``contentSha256`` and dates of the bundled subset, plus
    ``fullPack`` and ``filter.borderZone`` (design §1.5)."""
    source_id = str(entry["sourceId"])
    updated: dict[str, Any] = {}
    for key, value in entry.items():
        if key == FULL_PACK_FIELD:
            continue
        updated[key] = value
    updated["sha256"] = sha256_file(subset)
    updated["lastServiceDate"] = service_end_from_asset(subset, source_id).isoformat()
    updated["fullServiceUntil"] = full_service_end_from_asset(subset, source_id).isoformat()
    updated["contentSha256"] = verify_filter_from_asset(subset, source_id)
    policy = {key: value for key, value in dict(entry.get("filter") or {}).items() if key != BORDER_ZONE_FIELD}
    updated["filter"] = {**policy, BORDER_ZONE_FIELD: {"rule": foreign.BORDER_ZONE_RULE}}
    updated[FULL_PACK_FIELD] = full_pack_entry(full_pack, source_id)
    return updated


def whole_manifest_entry(entry: Mapping[str, Any]) -> dict[str, Any]:
    """A manifest entry without the border-subset fields (a pack bundled whole)."""
    updated = {key: value for key, value in entry.items() if key != FULL_PACK_FIELD}
    if isinstance(updated.get("filter"), Mapping):
        updated["filter"] = {key: value for key, value in updated["filter"].items() if key != BORDER_ZONE_FIELD}
    return updated


def write_full_pack_sums(full_pack_dir: Path, manifest: Mapping[str, Any]) -> None:
    names = sorted(
        (safe_asset_name(pack["asset"]), pack[FULL_PACK_FIELD]["sha256"])
        for pack in manifest["packs"] if isinstance(pack.get(FULL_PACK_FIELD), Mapping)
    )
    if names:
        full_pack_dir.mkdir(parents=True, exist_ok=True)
        (full_pack_dir / "SHA256SUMS").write_text("".join(f"{sha}  {name}\n" for name, sha in names), encoding="utf-8")
    elif (full_pack_dir / "SHA256SUMS").exists():
        (full_pack_dir / "SHA256SUMS").unlink()


def border_pair_problems(source_id: str, subset: Path, full_pack: Path, catalogue_path: Path) -> list[str]:
    """The required journeys of a border pack keep, in the subset, every trip of the full pack (catalogue ids)."""
    pairs = foreign.REQUIRED_BORDER_PAIRS.get(source_id, ())
    if not pairs:
        return []
    import build_ios_gtfs_schedule as ios  # the one catalogue matcher (design §1.1, rule 5)

    try:
        matcher, _ = ios.load_catalog(catalogue_path)
        ids = [(first, second) for first, second, _ in pairs]
        in_full = ios.pair_trip_counts(full_pack, matcher, source_id, ids)
        in_subset = ios.pair_trip_counts(subset, matcher, source_id, ids)
    except ios.BuildError as error:
        return [f"{source_id}: coppie obbligatorie non verificabili: {log_safe(error)}"]
    problems = []
    for first, second, label in pairs:
        full_count, subset_count = in_full[(first, second)], in_subset[(first, second)]
        if full_count == 0 or subset_count != full_count:
            problems.append(f"{source_id}: {label} ({first}–{second}) ha {subset_count} corse nel sottoinsieme "
                            f"e {full_count} nel pack completo")
    return problems


@dataclass(frozen=True)
class CorridorReport:
    """The corridors of a pack measured on its full pack and its subset (``tools/build_foreign_packs.py`` prints it)."""

    pairs: tuple[tuple[str, int, int], ...]  # (label, trips in the full pack, trips in the subset)
    gaps: tuple[str, ...]  # «corridor: stop name (stop_id)» between an end and a station, not in the zone
    unresolved: tuple[str, ...]  # «corridor: station» naming no stop of the full pack
    zone_stops: int


def _calls_by_trip(archive: zipfile.ZipFile, relevant: frozenset[str], ordered_for: Callable[[set[str]], bool] | None = None,
                   ) -> tuple[dict[str, set[str]], dict[str, list[str]]]:
    """Per trip, the stops of ``relevant`` it calls at; with ``ordered_for``, the ordered calls of the trips it accepts."""
    called: dict[str, set[str]] = defaultdict(set)

    def scan(row: Mapping[str, str]) -> bool:
        if row.get("stop_id", "") in relevant:
            called[row.get("trip_id", "")].add(row.get("stop_id", ""))
        return False

    read_table(archive, "stop_times.txt", required=True, predicate=scan)
    sequences: dict[str, list[tuple[int, str]]] = defaultdict(list)
    if ordered_for is not None:
        wanted = {trip_id for trip_id, stops in called.items() if ordered_for(stops)}

        def collect(row: Mapping[str, str]) -> bool:
            trip_id = row.get("trip_id", "")
            if trip_id in wanted:
                try:
                    sequences[trip_id].append((int(row.get("stop_sequence", "")), row.get("stop_id", "")))
                except ValueError:
                    raise BuildError(f"stop_sequence non numerica nella corsa {log_safe(trip_id)}") from None
            return False

        read_table(archive, "stop_times.txt", required=True, predicate=collect)
    return dict(called), {trip_id: [stop_id for _, stop_id in sorted(calls)] for trip_id, calls in sequences.items()}


def corridor_report(source_id: str, subset: Path, full_pack: Path, catalogue: foreign.ZoneCatalogue) -> CorridorReport:
    """Every pair a corridor lists (its Italian end and each of its stations) measured on the full pack and the
    subset, the stops between an end and a station that are not zone stops, and the stations naming no stop."""
    with zipfile.ZipFile(full_pack) as archive:
        validate_archive_limits(archive)
        stops = read_table(archive, "stops.txt", required=True)
        assert stops is not None
        zone = pack_zone_of(stops, source_id, catalogue)
        pairs = foreign.corridor_pairs(source_id, zone)
        ends = frozenset().union(*zone.ends.values()) if zone.ends else frozenset()
        relevant = frozenset().union(ends, *(stations for _, _, stations in pairs))
        full_calls, sequences = _calls_by_trip(archive, relevant, ordered_for=lambda called: bool(called & ends))
    with zipfile.ZipFile(subset) as archive:
        validate_archive_limits(archive)
        subset_calls, _ = _calls_by_trip(archive, relevant)
    names = {row.get("stop_id", ""): row.get("stop_name", "") for row in stops.rows}
    gaps = sorted(f"{corridor}: {names.get(stop_id, '')} ({stop_id})"
                  for corridor, stop_id in foreign.stretch_gaps(source_id, zone, sequences.values()))
    measured = tuple(
        (label, foreign.pair_trip_count(full_calls.values(), end, stations),
         foreign.pair_trip_count(subset_calls.values(), end, stations))
        for label, end, stations in pairs
    )
    return CorridorReport(measured, tuple(gaps), tuple(zone.unresolved()), len(zone.stops))


def corridor_problems(source_id: str, subset: Path, full_pack: Path, catalogue: foreign.ZoneCatalogue) -> list[str]:
    """``--check`` of the corridors of a border pack: every listed pair keeps in the subset every trip of the full
    pack between its ends, and no stop between a corridor's Italian end and one of its stations is left out of the
    zone (a new or renamed station: add it to ``foreign_pack_rules.CORRIDORS``).  A station naming no stop (a line
    closed for works, a renamed stop) is only reported by the generator: its trips, if any, stay whole elsewhere."""
    try:
        report = corridor_report(source_id, subset, full_pack, catalogue)
    except BuildError as error:
        return [f"{source_id}: corridoi non verificabili: {error}"]
    problems = [f"{source_id}: {label} ha {kept} corse nel sottoinsieme e {full} nel pack completo"
                for label, full, kept in report.pairs if kept != full]
    problems += [f"{source_id}: fermata fuori zona tra l'estremo italiano e una stazione del corridoio {gap} "
                 "(aggiungerla a foreign_pack_rules.CORRIDORS)" for gap in report.gaps]
    return problems


def verify_border_pack(
    pack: Mapping[str, Any],
    asset: Path,
    *,
    full_pack_dir: Path,
    catalogue: foreign.ZoneCatalogue | None,
    catalogue_path: Path,
    as_of: date | None,
    min_valid_days: int | None,
) -> list[str]:
    """``--check`` of a border pack: its full pack, the re-derived subset, the required pairs (design §2.5)."""
    source_id = str(pack["sourceId"])
    name = safe_asset_name(str(pack["asset"]))
    full = pack.get(FULL_PACK_FIELD)
    if not isinstance(full, Mapping) or set(full) != FULL_PACK_KEYS:
        return [f"{source_id}: {FULL_PACK_FIELD} deve avere le chiavi {sorted(FULL_PACK_KEYS)}"]
    problems: list[str] = []
    if full["path"] != f"{foreign.FULL_PACK_DIR}/{name}":
        problems.append(f"{source_id}: {FULL_PACK_FIELD}.path {full['path']!r} diverso da {foreign.FULL_PACK_DIR}/{name}")
    if (pack.get("filter") or {}).get(BORDER_ZONE_FIELD) != {"rule": foreign.BORDER_ZONE_RULE}:
        problems.append(f"{source_id}: filter.{BORDER_ZONE_FIELD} diverso da rule {foreign.BORDER_ZONE_RULE} (rigenerare)")
    full_pack = full_pack_dir / name
    if not full_pack.is_file():
        return problems + [f"{source_id}: pack completo assente: {full_pack}"]
    try:
        actual = full_pack_entry(full_pack, source_id)
    except BuildError as error:
        return problems + [str(error)]
    for key in sorted(FULL_PACK_KEYS - {"path"}):
        if full.get(key) != actual[key]:
            problems.append(f"{source_id}: {FULL_PACK_FIELD}.{key} {full.get(key)} diverso dal pack completo {actual[key]}")
    if as_of is not None and min_valid_days is not None:
        days = pack_min_valid_days(pack, min_valid_days)
        end = min(date.fromisoformat(actual["lastServiceDate"]), date.fromisoformat(actual["fullServiceUntil"]))
        if not is_release_fresh(end, as_of, days):
            problems.append(f"pack completo scaduto o in scadenza entro {days} giorni: {source_id} termina {end}")
    if catalogue is None:
        return problems + [f"{source_id}: catalogo stazioni non leggibile, sottoinsieme non verificabile"]
    with tempfile.TemporaryDirectory(prefix=".border-check-") as folder:
        derived = Path(folder) / name
        try:
            derive_border_subset(full_pack, derived, source_id, catalogue)
            if archive_member_bytes(derived) != archive_member_bytes(asset):
                problems.append(
                    f"{source_id}: {name} non è il sottoinsieme derivato da {foreign.FULL_PACK_DIR}/{name} con i "
                    f"corridoi ({foreign.BORDER_ZONE_RULE}) e il catalogo attuale (rigenerare: tools/build_foreign_packs.py)"
                )
        except BuildError as error:
            problems.append(str(error))
    problems += border_pair_problems(source_id, asset, full_pack, catalogue_path)
    problems += corridor_problems(source_id, asset, full_pack, catalogue)
    return problems


DEFAULT_MIN_VALID_DAYS = 30


def pack_min_valid_days(pack: Mapping[str, Any], default: int) -> int:
    """The release window of a pack: its own minValidDays when declared (a city feed published a few weeks at a time),
    never longer than ``default``; else ``default``.  Shared by every freshness check (audit A7-06)."""
    declared = pack.get("minValidDays")
    if declared is None:
        return default
    if not isinstance(declared, int) or isinstance(declared, bool) or declared < 1:
        raise BuildError(f"{pack.get('sourceId')}: minValidDays non valido: {declared!r}")
    return min(declared, default)


def release_freshness_cutoff(as_of: date, window_days: int) -> date:
    """The first service day a pack must still run on: ``as_of`` plus its window."""
    return as_of + timedelta(days=window_days)


def is_release_fresh(last_service_date: date, as_of: date, window_days: int) -> bool:
    """The one release-freshness rule of the repository: the pack runs at least ``window_days`` days past ``as_of``
    (BRANCH_AND_RELEASE_POLICY.md: «almeno 30 giorni oltre la data del gate»).  ``build_gtfs_packs.py --check``,
    ``verify_embedded_assets.py`` and the refresh report all use it, on the date derived from the calendar."""
    return last_service_date >= release_freshness_cutoff(as_of, window_days)


def asset_border_zone(path: Path) -> Any:
    """The ``borderZone`` of a pack's filter manifest (None for a pack bundled whole)."""
    with zipfile.ZipFile(path) as archive:
        entry = filter_manifest_entry(archive)
        if entry is None:
            return None
        try:
            return json.loads(archive.read(entry).decode("utf-8")).get(BORDER_ZONE_FIELD)
        except (UnicodeDecodeError, json.JSONDecodeError, AttributeError):
            return None


def verify_assets(
    asset_dir: Path,
    *,
    as_of: date | None,
    min_valid_days: int | None,
    full_pack_dir: Path | None = None,
    catalogue_path: Path | None = None,
) -> None:
    """Check digest, manifest date derivation, and optional release freshness.

    A foreign pack whose licence is confirmed is bundled as its border subset (``fullPack`` in the manifest): its
    full pack in ``full_pack_dir`` must match ``fullPack``, the subset must be the one derived from it with the
    catalogue at ``catalogue_path``, and the required journeys must keep every trip (:func:`verify_border_pack`).
    """
    full_pack_dir = full_pack_dir or DEFAULT_FULL_PACK_DIR
    catalogue_path = catalogue_path or DEFAULT_CATALOGUE
    manifest = read_manifest(asset_dir / "manifest.json")
    packs = manifest["packs"]
    zone_catalogue: foreign.ZoneCatalogue | None = None
    if any(FULL_PACK_FIELD in pack for pack in packs):
        try:
            zone_catalogue = load_zone_catalogue(catalogue_path)
        except (OSError, ValueError, UnicodeDecodeError, BuildError) as error:
            print_error_lines("ERRORE GTFS: ", f"catalogo stazioni non leggibile: {error}")
    border_sums: dict[str, str] = {}
    subset_bytes = 0
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
        if asset_dir.name + "/" in ASSET_DIRECTORIES and not str(pack["asset"]).startswith(asset_dir.name + "/"):
            problems.append(f"{source_id}: asset {pack['asset']} fuori dalla directory {asset_dir.name}/ del manifest")
        path = asset_dir / name
        if not path.is_file():
            continue
        upstream = pack.get("upstream")
        if isinstance(upstream, dict) and upstream.get("archiveSha256") != pack.get("sourceArchiveSha256"):
            problems.append(
                f"archivio sorgente non fissato per {name}: {pack.get('sourceArchiveSha256')} != {upstream.get('archiveSha256')}"
            )
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
            actual_full_end = full_service_end_from_asset(path, source_id)
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
        if pack.get("fullServiceUntil") != actual_full_end.isoformat():
            problems.append(f"fullServiceUntil incoerente per {name}: {pack.get('fullServiceUntil')} != {actual_full_end}")
        # Licence gate (D2): a foreign pack is reduced to its border subset exactly when its licence is confirmed.
        if source_id in foreign.FOREIGN_PACK_COUNTRIES and foreign.is_border_pack(pack) != (FULL_PACK_FIELD in pack):
            problems.append(
                f"{source_id}: licenza {'confermata' if foreign.licence_confirmed(pack) else 'da confermare'} ma "
                f"{'senza' if FULL_PACK_FIELD not in pack else 'con'} {FULL_PACK_FIELD}: rigenerare con tools/build_foreign_packs.py"
            )
        elif FULL_PACK_FIELD in pack and source_id not in foreign.FOREIGN_PACK_COUNTRIES:
            problems.append(f"{source_id}: {FULL_PACK_FIELD} ammesso solo per i pack esteri")
        if (asset_border_zone(path) is not None) != (FULL_PACK_FIELD in pack):
            problems.append(f"{source_id}: {name} {'è' if FULL_PACK_FIELD not in pack else 'non è'} un sottoinsieme di confine "
                            f"ma il manifest {'non ha' if FULL_PACK_FIELD not in pack else 'ha'} {FULL_PACK_FIELD}")
        if FULL_PACK_FIELD in pack:
            subset_bytes += path.stat().st_size
            if isinstance(pack[FULL_PACK_FIELD], Mapping):
                border_sums[name] = str(pack[FULL_PACK_FIELD].get("sha256"))
            problems += verify_border_pack(
                pack, path, full_pack_dir=full_pack_dir, catalogue=zone_catalogue, catalogue_path=catalogue_path,
                as_of=as_of, min_valid_days=min_valid_days,
            )
        if as_of is not None and min_valid_days is not None:
            # A city feed published a few weeks ahead declares its own shorter release threshold (minValidDays,
            # documented in GTFS_PACK_REFRESH.md with its weekly refresh); no pack can ask for a longer one here.
            # Judged on the end of full coverage: a thin tail of trips is not a timetable the app can promise.
            days = pack_min_valid_days(pack, min_valid_days)
            release = min(actual_end, actual_full_end)
            if not is_release_fresh(release, as_of, days):
                problems.append(
                    f"pack scaduto o in scadenza entro {days} giorni: "
                    f"{source_id} termina {actual_end}, copertura piena fino al {actual_full_end} "
                    f"(richiesto almeno fino al {release_freshness_cutoff(as_of, days)})"
                )
    if border_sums:
        if subset_bytes > foreign.SUBSET_SIZE_BUDGET_BYTES:
            problems.append(f"sottoinsiemi di confine oltre il budget: {subset_bytes} > {foreign.SUBSET_SIZE_BUDGET_BYTES} byte")
        try:
            full_sums = parse_sha256sums(full_pack_dir / "SHA256SUMS")
        except BuildError as error:
            problems.append(f"{foreign.FULL_PACK_DIR}: {error}")
        else:
            if full_sums != border_sums:
                problems.append(f"{foreign.FULL_PACK_DIR}/SHA256SUMS diverso dai fullPack del manifest")
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
    parser.add_argument(
        "--source-archive",
        action="append",
        default=[],
        metavar="SOURCE_ID=PATH",
        help="archivio GTFS completo gia acquisito per una sourceId (ripetibile); un upstream fissato ne verifica lo SHA-256",
    )
    parser.add_argument(
        "--only",
        action="append",
        default=[],
        metavar="SOURCE_ID",
        help="ricostruisce solo queste sourceId; gli altri pack pubblicati restano invariati byte per byte (ripetibile)",
    )
    parser.add_argument("--max-download-bytes", type=int, default=DEFAULT_MAX_DOWNLOAD_BYTES, help="cap hard per ogni download")
    parser.add_argument("--check", action="store_true", help="verifica asset, hash e validita release senza rete")
    parser.add_argument("--as-of", type=parse_date, default=date.today(), help="data di riferimento per --check")
    parser.add_argument("--min-valid-days", type=int, default=DEFAULT_MIN_VALID_DAYS, help="giorni minimi residui richiesti da --check")
    parser.add_argument(
        "--integrity-only",
        action="store_true",
        help="con --check verifica struttura, filtri, hash e date dichiarate senza applicare la soglia freshness release",
    )
    parser.add_argument("--probe", action="store_true", help="HEAD read-only delle URL registrate, senza download")
    parser.add_argument(
        "--border-zones",
        action="store_true",
        help="applica la regola di licenza ai pack esteri di --output-dir: sottoinsieme di confine o pack intero",
    )
    parser.add_argument(
        "--full-pack-dir",
        type=Path,
        default=DEFAULT_FULL_PACK_DIR,
        help=f"pack completi dei pack esteri ridotti alla zona di confine (default: {foreign.FULL_PACK_DIR})",
    )
    parser.add_argument(
        "--catalogue",
        type=Path,
        default=DEFAULT_CATALOGUE,
        help=f"catalogo stazioni delle ancore della zona di confine (default: {foreign.CATALOGUE_PATH})",
    )
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
        if args.border_zones:
            if args.refresh or args.check or args.only or args.source_archive:
                parser.error("--border-zones non si combina con build, --check o --refresh")
            for row in apply_border_zones(args.output_dir, full_pack_dir=args.full_pack_dir, catalogue_path=args.catalogue):
                print(json.dumps(row, ensure_ascii=False, sort_keys=True))
            return 0
        if args.check:
            verify_assets(
                args.input_dir,
                as_of=None if args.integrity_only else args.as_of,
                min_valid_days=None if args.integrity_only else args.min_valid_days,
                full_pack_dir=args.full_pack_dir,
                catalogue_path=args.catalogue,
            )
            print(f"GTFS asset verification passed: {args.input_dir}")
            return 0
        overrides: dict[str, Path] = {}
        for item in args.source_archive:
            source_id, separator, location = item.partition("=")
            if not separator or not source_id or not location:
                parser.error(f"--source-archive richiede SOURCE_ID=PATH: {item!r}")
            if source_id in overrides:
                parser.error(f"--source-archive duplicato per {source_id}")
            overrides[source_id] = Path(location)
        download_dir: Path | None = None
        try:
            if args.refresh:
                downloaded, download_dir = refresh_overrides(
                    manifest,
                    set(args.source_id),
                    max_download_bytes=args.max_download_bytes,
                )
                overrides.update(downloaded)
            output = build_packs(
                args.input_dir,
                args.output_dir,
                manifest_path=manifest_path,
                generated_at=args.generated_at,
                input_overrides=overrides,
                only=args.only,
                full_pack_dir=args.full_pack_dir,
                catalogue_path=args.catalogue,
            )
        finally:
            if download_dir is not None:
                shutil.rmtree(download_dir, ignore_errors=True)
        print(json.dumps({"packs": len(output["packs"]), "outputDir": str(args.output_dir), "generatedAt": output["generatedAt"]}, ensure_ascii=False))
        return 0
    except (BuildError, csv.Error, zipfile.BadZipFile, http.client.HTTPException) as error:
        print_error_lines("ERRORE GTFS: ", error)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
