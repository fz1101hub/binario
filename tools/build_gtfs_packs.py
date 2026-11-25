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
import re
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
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, BinaryIO, Callable, Iterable, Mapping, Sequence
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

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
# Members named, largest first, when an archive exceeds its uncompressed limit (and in the log of a reviewed large archive).
ARCHIVE_LIMIT_REPORTED_MEMBERS = 4
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
# The large tables filter_archive reads row by row with a filter, retaining only the rows it keeps (MAX_RETAINED_*): the
# ones a reviewed large archive (REVIEWED_LARGE_ARCHIVES) may have past MAX_ARCHIVE_MEMBER_BYTES and MAX_TABLE_ROWS.  Every
# other member keeps the default limits (routes.txt and stops.txt, read reduced for a reviewed source too, and agency.txt).
STREAMED_TABLES = frozenset({
    "trips.txt", "stop_times.txt", "calendar.txt", "calendar_dates.txt", "frequencies.txt", "transfers.txt", "pathways.txt",
})


@dataclass(frozen=True)
class LargeArchiveReview:
    """The reviewed limits of a source whose complete archive is larger than the default ones (``reason`` says why).

    They replace, for that source only, MAX_ARCHIVE_UNCOMPRESSED_BYTES (``max_uncompressed_bytes``), and for the
    STREAMED_TABLES only MAX_ARCHIVE_MEMBER_BYTES (``max_streamed_member_bytes``) and MAX_TABLE_ROWS
    (``max_streamed_table_rows``); the download cap (``max_download_bytes``) replaces DEFAULT_MAX_DOWNLOAD_BYTES and the
    core refresh's acquire_transit_sources.MAX_DOWNLOAD_BYTES; ``max_retained_*`` replace the build totals
    MAX_RETAINED_BUILD_ROWS, _CELLS and _UTF8_BYTES.  The entry count, the compression ratio, the other members and
    MAX_RETAINED_TABLE_ROWS (which every reader of the published pack applies) keep their limits.  The build reads every
    table of such a source reduced to what the pack keeps (:func:`filter_archive`), and writes its calendar from
    tripsRunFrom only, each service in its smallest exact encoding (:func:`compact_calendars`, CALENDARS_FROM_FIELD):
    the pack has the same trips, stops and days from tripsRunFrom (tests/test_build_gtfs_packs.py,
    ReviewedLargeArchiveTest).  ``max_service_days`` caps the calendar held while read (one bit a service and a day).
    """

    reason: str
    max_download_bytes: int
    max_uncompressed_bytes: int
    max_streamed_member_bytes: int
    max_streamed_table_rows: int
    max_retained_rows: int | None = None
    max_retained_cells: int | None = None
    max_retained_utf8_bytes: int | None = None
    # The calendar held while read, as bits per service and day from tripsRunFrom (read_service_days).
    max_service_days: int = 100_000_000

    def budget(self) -> "RetentionBudget":
        """The retention budget of one build of the source (None: the default MAX_RETAINED_BUILD_*)."""
        return RetentionBudget(
            max_rows=self.max_retained_rows, max_cells=self.max_retained_cells, max_utf8_bytes=self.max_retained_utf8_bytes,
        )


# Reviewed 24 November 2026 (docs/data-sources/GTFS_PACK_REFRESH.md, «Archivio svizzero: limiti rivisti»).  Never applied
# to another source: a new entry is a reviewed change, and the test of this registry names its sources.
#
# ch-otd-gtfs, measured by the core-packs run 45512857331 (gtfs_fp2026_20261121.zip): 4,602,775,061 bytes decompressed,
# stop_times.txt 3,751,672,566, trips.txt 393,487,544, calendar_dates.txt 276,073,942, transfers.txt 162,090,787.  At the
# row widths of the published full pack (59.6, 89.8, 18.6 and 49.2 bytes) that is about 63 million stop times, 4.4
# million trips, 14.8 million exceptions and 3.3 million transfers: past MAX_TABLE_ROWS for calendar_dates.txt too.
# That run held every rail trip of the year with its calendar before dropping the expired ones and stopped at the
# 1,000,000 rows of MAX_RETAINED_BUILD_ROWS; the build now reads only what the pack keeps.  What it keeps from the 2027
# file (all of it runs from 13 December): the August 2025 file of the 2026 timetable had 24,019 rail trips (about
# 270,000 stop times at 11.2 calls a trip, 3.1 exceptions a trip in the published pack); the December 2025 file of the
# same series was 1.75 times larger (135 MB against 77.5 MB compressed), hence about 42,000 trips, 470,000 stop times,
# 130,000 exceptions, some 10,000 transfers and 5,000 stops: about 0.7 million rows, 4.2 million cells and 35 MB, within
# the defaults by less than half again, with no measure of the 2027 file before it is first built on 14 December.  The
# reviewed totals let stop_times.txt reach MAX_RETAINED_TABLE_ROWS (750,000 rows, the bound every reader of the
# published pack applies) with the other tables at twice the estimate: 1,500,000 rows and 9,000,000 cells; the bytes
# keep the default 128 MiB (about 60 MB at those rows, 57 bytes a stop time).  At about 600 bytes of memory a retained
# row (580 measured on stop_times rows) that is under 1 GB held, within a 7 GB runner.
#
# The next run, 45516917094, held 47,878 trips and 542,797 stop times and stopped at the 750,000 rows of
# calendar_dates.txt (5,562 services: the Swiss file lists most days of most services one by one, over 135 rows a
# service for its year).  The calendar is now held as one bit a service and a day from tripsRunFrom while read, and
# written from that day in the smallest exact encoding of each service.  On the published full pack (the August 2025
# file, 1,022 services, 60 source rows a service) that is 62,093 rows down to 1,054 from 23 November and 34,112 for its
# whole year (33 a service, 55 %): about 6,000 rows for the 5,562 services from 23 November, and for a full year of
# them about 190,000 rows at 33 a service, or about 410,000 if they are as dense as the run of 24 November suggests
# (over 135 source rows a service, recoded at the same 55 %): within the 500,000 rows of calendar_dates.txt that the
# Android reader and the iPhone index builder accept, but not by much in the second case.
# max_service_days, 100,000,000 bits (12.5 MB), counts the three bitmaps of a service (calendar days, added days, days
# with an exception): the rail services of a year (265,070 rail trips on 24 November at 8.6 trips a service, about
# 31,000) hold at most about 34 million.
REVIEWED_LARGE_ARCHIVES: Mapping[str, LargeArchiveReview] = {
    "ch-otd-gtfs": LargeArchiveReview(
        reason=(
            "Orario nazionale svizzero di tutti i mezzi, di cui il pack tiene solo i treni: l'archivio del permalink "
            "timetable-2026-gtfs2020 del 21 novembre 2026 si decomprime a 4,6 GB (limite predefinito 1 GiB) ed è "
            "stato scaricato entro i 400 MiB del refresh settimanale; l'archivio di un anno orario cresce fino al "
            "cambio orario (catalogo opendata.swiss: 278 MB compressi il 29 novembre 2025, 135 MB il 10 dicembre 2025 "
            "per l'anno successivo). Limiti rivisti: 8 GiB decompressi in tutto, circa il doppio dell'osservato; "
            "6 GiB e 120 milioni di righe per ogni tabella letta in streaming (circa 55 byte per riga di "
            "stop_times.txt); 768 MiB di download, quasi il doppio dei 400 MiB entro cui è stato scaricato l'archivio "
            "del 21 novembre (un download che si decomprime oltre gli 8 GiB è rifiutato dal limite complessivo); "
            "1,5 milioni di righe e 9 milioni di celle trattenute in tutto (i byte restano 128 MiB), perché "
            "stop_times.txt possa arrivare alle 750.000 righe di ogni tabella con le altre al doppio della stima del "
            "file 2027 (vedi sopra). "
            "L'archivio è letto sul posto e mai estratto: sul runner occupa disco solo il download."
        ),
        max_download_bytes=768 * 1024 * 1024,
        max_uncompressed_bytes=8 * 1024 * 1024 * 1024,
        max_streamed_member_bytes=6 * 1024 * 1024 * 1024,
        max_streamed_table_rows=120_000_000,
        max_retained_rows=1_500_000,
        max_retained_cells=9_000_000,
        max_service_days=100_000_000,
    ),
}


def large_archive_review(source_id: str | None) -> LargeArchiveReview | None:
    """The reviewed limits of ``source_id`` (REVIEWED_LARGE_ARCHIVES), None for every other source."""
    return None if source_id is None else REVIEWED_LARGE_ARCHIVES.get(source_id)


def source_download_limit(source_id: str, default: int) -> int:
    """The download cap of ``source_id``: its reviewed limit (REVIEWED_LARGE_ARCHIVES), else ``default``."""
    review = large_archive_review(source_id)
    return default if review is None else review.max_download_bytes


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
# A reviewed large archive (REVIEWED_LARGE_ARCHIVES) keeps its calendar from tripsRunFrom only, each service in its
# smallest exact encoding (compact_calendars): the day is recorded in the filter manifest under this field.
CALENDARS_FROM_FIELD = "calendarsFrom"
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

# The apps read every pack's times as local times in APP_TIMEZONE (Android and iPhone alike), so every agency_timezone
# of a pack must have its UTC offset on every day of the pack's calendar (checked by --check).  The reviewed sources below
# publish their times in another zone: the build moves each trip's stop times into APP_TIMEZONE by the offset difference of
# its service days and writes APP_TIMEZONE (:func:`to_app_time_zone`), named in tripRules (LOCAL_TIMES_RULE) with the zone
# of the source (LOCAL_TIMES_FROM_FIELD).  Trenitalia France's GTFS of November 2026 declares agency_timezone=UTC and its
# times are UTC (train 6656 leaves Lyon Perrache at 16:08 there, 17:08 in Italian and French time).
APP_TIMEZONE = "Europe/Rome"
LOCAL_TIME_SOURCES = frozenset({"trenitalia-france-gtfs"})
LOCAL_TIMES_RULE = "local-times"
LOCAL_TIMES_FROM_FIELD = "localTimesFrom"
# A moved time stays a GTFS time the apps index: from 00:00:00 to before 48:00:00 of the service day.
MAX_LOCAL_TIME_SECONDS = 48 * 3600
# Reviewed sources that publish each train as a route of its own whose route_id is the train number, with no
# trip_short_name (Trenitalia France: route 6656 is the Frecciarossa 6656, the number of the national pack and of the
# realtime feed): the build copies a plain number (digits only, at most six) into an empty trip_short_name, named in
# tripRules (TRAIN_NUMBERS_RULE).  Anything else stays as published.
TRAIN_NUMBER_ROUTE_ID_SOURCES = frozenset({"trenitalia-france-gtfs"})
TRAIN_NUMBERS_RULE = "train-numbers-from-route-id"
PLAIN_TRAIN_NUMBER = re.compile(r"[0-9]{1,6}")
# Reviewed sources whose routes name each train by its origin and destination in both route_short_name and
# route_long_name (Trenitalia France: "Paris-Gare-de-Lyon/Lyon-Perrache-Voyageurs"), which both apps show as the line
# of a row ("Paris-Gare-de-Lyon/Lyon-Perrache-Voyageurs 6647").  Every route of Trenitalia France's GTFS of November 2026
# is a Frecciarossa of its one agency, "Trenitalia" (series 61xx Paris – Marseille, 64xx and 66xx Paris – Lyon, 92xx
# Paris – Milano; the feed's publisher is "Trenitalia France", the company that runs them all): the build writes the category the national pack publishes for its Frecciarossa (route_short_name
# "0083_FR", which Android shows as "FR", route_long_name "Frecciarossa") without Trenitalia's company code, so the rows
# read "FR 9281" as the national ones do; named in tripRules (ROUTE_CATEGORY_RULE).  A kept route of another route_type
# or of another agency fails the build: its category is not known.
ROUTE_CATEGORY_SOURCES: Mapping[str, tuple[str, str, frozenset[str]]] = {
    "trenitalia-france-gtfs": ("FR", "Frecciarossa", frozenset({"Trenitalia", "Trenitalia France"})),
}
ROUTE_CATEGORY_RULE = "route-category"
ROUTE_CATEGORY_ROUTE_TYPE = "2"
# Reviewed sources that number a few trips out of their time order (along stop_sequence, an arrival before the previous
# stop's departure): such a trip is left out, never reordered, and counted in the filter manifest
# (OUT_OF_ORDER_COUNT_FIELD; a pack filtered again keeps the count of its source); named in tripRules
# (OUT_OF_ORDER_RULE).  More than MAX_OUT_OF_ORDER_TRIP_SHARE of the pack's trips fails the build.  Trenitalia France's
# GTFS of November 2026 numbers four weekend runs of train 9281 Oulx 5, Torino Porta Susa 24, Milano Centrale 38,
# Paris 39, while their times run from Paris at 07:30 to Milano at 14:07.  The iPhone index applies the same rule
# (build_ios_gtfs_schedule.OUT_OF_ORDER_TRIPS_LEFT_OUT), which also covers a pack built before this rule.
OUT_OF_ORDER_SOURCES = frozenset({"trenitalia-france-gtfs"})
OUT_OF_ORDER_RULE = "out-of-order-trips"
OUT_OF_ORDER_COUNT_FIELD = "outOfOrderTripsLeftOut"
MAX_OUT_OF_ORDER_TRIP_SHARE = 0.05

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
    # The Marconi Express people mover (Bologna Centrale - airport), kept whatever urban route_type TPER gives it
    # (monorail, 12, in the 2026 feed; metro, cable or funicular types too); never a bus.
    "marconi-express-gtfs": frozenset(),
    # Candidate: Palermo's AMAT trams; the buses of the same feed are left out.
    "palermo-amat-tram-gtfs": frozenset(),
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
        if NEXT_TIMETABLE_FIELD in pack:
            validate_next_timetable(source_id, pack)
    return manifest


# A publisher that puts each timetable year in a dataset of its own behind a stable permalink (Switzerland:
# timetable-2026-gtfs2020, timetable-2027-gtfs2020) is registered with the permalink of the current year in sourceUrl and
# the reviewed permalink of the next year here, with the first day the weekly refresh downloads it (the first day of
# that timetable, so the current year's last weeks are not lost; earlier only by a reviewed change of ``from``).  From
# that day tools/refresh_core_gtfs.py builds the pack from the next source and proposes the entry with its sourceUrl
# and without this field; tools/apply_ci_gtfs_refresh.py accepts that sourceUrl only as this reviewed one and carries
# it (and landingPage) into the iOS source policy and RailSourceCatalog.kt.  The owner adds the following year's next.
NEXT_TIMETABLE_FIELD = "nextTimetable"
NEXT_TIMETABLE_KEYS = frozenset({"from", "sourceUrl", "landingPage"})


def validate_next_timetable(source_id: str, pack: Mapping[str, Any]) -> None:
    following = pack.get(NEXT_TIMETABLE_FIELD)
    if not isinstance(following, dict) or set(following) != NEXT_TIMETABLE_KEYS:
        raise BuildError(f"{source_id}: {NEXT_TIMETABLE_FIELD} deve avere le chiavi {sorted(NEXT_TIMETABLE_KEYS)}")
    if "upstream" in pack:
        raise BuildError(f"{source_id}: {NEXT_TIMETABLE_FIELD} non si combina con un upstream fissato")
    try:
        date.fromisoformat(str(following["from"]))
    except ValueError as error:
        raise BuildError(f"{source_id}: {NEXT_TIMETABLE_FIELD}.from non ISO-8601: {following['from']!r}") from error
    for key in ("sourceUrl", "landingPage"):
        if not isinstance(following[key], str):
            raise BuildError(f"{source_id}: {NEXT_TIMETABLE_FIELD}.{key} non valido")
        require_https_url(following[key], context=f"{source_id} {NEXT_TIMETABLE_FIELD}.{key}")
    if following["sourceUrl"] == pack.get("sourceUrl"):
        raise BuildError(f"{source_id}: {NEXT_TIMETABLE_FIELD}.sourceUrl uguale a sourceUrl")


def timetable_switch(pack: Mapping[str, Any], day: date) -> dict[str, str] | None:
    """The reviewed switch to the next timetable's source when ``day`` is on or after its ``from``, else None."""
    following = pack.get(NEXT_TIMETABLE_FIELD)
    if not isinstance(following, Mapping) or day < date.fromisoformat(str(following["from"])):
        return None
    return {
        "from": str(following["from"]),
        "previousSourceUrl": str(pack["sourceUrl"]),
        "sourceUrl": str(following["sourceUrl"]),
        "landingPage": str(following["landingPage"]),
    }


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


def largest_members(archive: zipfile.ZipFile) -> str:
    """The largest members with their uncompressed sizes, largest first, on one line (names from the archive, hence
    log_safe): what grew, read from the log alone without fetching the source again."""
    files = sorted((entry for entry in archive.infolist() if not entry.is_dir()), key=lambda entry: entry.file_size, reverse=True)
    return ", ".join(f"{log_safe(entry.filename)} {entry.file_size}" for entry in files[:ARCHIVE_LIMIT_REPORTED_MEMBERS])


def validate_archive_limits(archive: zipfile.ZipFile, *, omit_geometry: bool = False, source_id: str | None = None) -> None:
    """Entry count, uncompressed size, member sizes and compression ratio of an archive before any member is read.

    With the ``source_id`` of a reviewed large archive (REVIEWED_LARGE_ARCHIVES) its reviewed limits replace the total
    and, for the STREAMED_TABLES, the member limit; entry count, ratio and the other members keep the defaults."""
    review = large_archive_review(source_id)
    total_limit = MAX_ARCHIVE_UNCOMPRESSED_BYTES if review is None else review.max_uncompressed_bytes
    reviewed = "" if review is None else f" (limite rivisto di {source_id}, REVIEWED_LARGE_ARCHIVES)"
    entries = archive.infolist()
    if len(entries) > MAX_ARCHIVE_ENTRIES:
        raise BuildError(f"archivio GTFS con troppe entry: {len(entries)} > {MAX_ARCHIVE_ENTRIES}")
    files = [entry for entry in entries if not entry.is_dir()]
    total = sum(entry.file_size for entry in files)
    if total > total_limit:
        raise BuildError(
            f"archivio GTFS decompresso oltre limite: {total} > {total_limit} bytes{reviewed} "
            f"(membri più grandi: {largest_members(archive)})"
        )
    for entry in files:
        # Timetable packs never open shapes.txt. Keep the total-size, entry and
        # compression-ratio guards even for that unused geometry member.
        basename = entry.filename.rsplit("/", 1)[-1]
        ignored_shape = omit_geometry and basename == "shapes.txt"
        streamed = review is not None and basename in STREAMED_TABLES
        member_limit = review.max_streamed_member_bytes if streamed and review is not None else MAX_ARCHIVE_MEMBER_BYTES
        if entry.file_size > member_limit and not ignored_shape:
            raise BuildError(
                f"membro GTFS oltre limite: {entry.filename!r} {entry.file_size} > {member_limit} bytes"
                + (reviewed if streamed else "")
            )
        ratio = entry.file_size / max(entry.compress_size, 1)
        if ratio > MAX_COMPRESSION_RATIO:
            raise BuildError(
                f"rapporto di compressione GTFS sospetto: {entry.filename!r} {ratio:.1f} > {MAX_COMPRESSION_RATIO}"
            )


@dataclass
class RetentionBudget:
    """What one build holds in memory, against MAX_RETAINED_BUILD_* (or the reviewed totals of a large archive,
    :meth:`LargeArchiveReview.budget`); an error that stops the build names the rows each table holds."""

    retained_rows: int = 0
    retained_cells: int = 0
    retained_utf8_bytes: int = 0
    max_rows: int | None = None
    max_cells: int | None = None
    max_utf8_bytes: int | None = None
    rows_by_table: dict[str, int] = field(default_factory=dict)

    def held(self) -> str:
        """The rows each table holds, largest first: where a budget went, read from the log alone."""
        tables = sorted(((count, name) for name, count in self.rows_by_table.items() if count), key=lambda item: (-item[0], item[1]))
        return "righe trattenute per tabella: " + (", ".join(f"{name} {count}" for count, name in tables) or "nessuna")

    def release(self, basename: str, rows: Iterable[Mapping[str, str]]) -> None:
        """Rows of ``basename`` retained earlier and dropped since (no reference to them is kept): the budget counts what
        is held."""
        for row in rows:
            self.retained_rows -= 1
            self.retained_cells -= len(row)
            self.retained_utf8_bytes -= sum(len(value.encode("utf-8")) for value in row.values())
            self.rows_by_table[basename] -= 1

    def retain(self, basename: str, row: Mapping[str, str]) -> None:
        self.retained_rows += 1
        self.retained_cells += len(row)
        self.retained_utf8_bytes += sum(len(value.encode("utf-8")) for value in row.values())
        self.rows_by_table[basename] = self.rows_by_table.get(basename, 0) + 1
        max_rows = MAX_RETAINED_BUILD_ROWS if self.max_rows is None else self.max_rows
        max_cells = MAX_RETAINED_BUILD_CELLS if self.max_cells is None else self.max_cells
        max_bytes = MAX_RETAINED_BUILD_UTF8_BYTES if self.max_utf8_bytes is None else self.max_utf8_bytes
        if self.retained_rows > max_rows:
            raise BuildError(f"build GTFS oltre budget righe trattenute: {self.retained_rows} > {max_rows} ({self.held()})")
        if self.retained_cells > max_cells:
            raise BuildError(f"build GTFS oltre budget celle trattenute: {self.retained_cells} > {max_cells} ({self.held()})")
        if self.retained_utf8_bytes > max_bytes:
            raise BuildError(
                f"build GTFS oltre budget byte UTF-8 trattenuti: {self.retained_utf8_bytes} > {max_bytes} ({self.held()})"
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
        # A character is at most 4 UTF-8 bytes: a short line is never encoded (the streamed tables have tens of
        # millions of lines).
        if len(line) * 4 > MAX_CSV_PHYSICAL_LINE_UTF8_BYTES and len(line.encode("utf-8")) > MAX_CSV_PHYSICAL_LINE_UTF8_BYTES:
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
    max_rows: int | None = None,
    counts: dict[str, int] | None = None,
) -> CsvTable | None:
    """The rows of one table, read one at a time from the archive (never the whole member): every row is checked, only
    the rows ``predicate`` keeps are retained (MAX_RETAINED_TABLE_ROWS, ``budget``).  ``max_rows`` replaces
    MAX_TABLE_ROWS, the rows read (a reviewed large archive, REVIEWED_LARGE_ARCHIVES); ``counts`` receives the rows read
    and kept."""
    row_limit = MAX_TABLE_ROWS if max_rows is None else max_rows
    field_limit = MAX_CSV_FIELD_UTF8_BYTES
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
            index = 0
            for index, row in enumerate(reader, 1):
                if index > row_limit:
                    raise BuildError(f"tabella oltre limite righe: {basename} > {row_limit}")
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
                # At most 4 UTF-8 bytes a character: only a long value is encoded.
                oversized_field = next(
                    (
                        key for key, value in normalized.items()
                        if len(value) * 4 > field_limit and len(value.encode("utf-8")) > field_limit
                    ),
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
                        + ("" if budget is None else f" ({budget.held()})")
                    )
                if budget is not None:
                    budget.retain(basename, normalized)
                rows_list.append(normalized)
            rows = tuple(rows_list)
            if counts is not None:
                counts.update(read=index, kept=len(rows))
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


def published_local_times_from(archive: zipfile.ZipFile, source_id: str, provenance: str | None) -> str | None:
    """The localTimesFrom of the published pack the input *is* (``provenance``), so that filtering it again keeps the
    zone the source was in; None for any other input."""
    entry = filter_manifest_entry(archive) if provenance is not None else None
    if entry is None:
        return None
    try:
        value = json.loads(archive.read(entry).decode("utf-8")).get(LOCAL_TIMES_FROM_FIELD)
    except (UnicodeDecodeError, json.JSONDecodeError, AttributeError) as error:
        raise BuildError(f"{source_id}: {LOCAL_TIMES_FROM_FIELD} non valido") from error
    return value if isinstance(value, str) and value else None


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


def agency_zone(source_id: str, agency_rows: Iterable[Mapping[str, str]]) -> tuple[str, ZoneInfo] | None:
    """The one agency_timezone of a pack (GTFS requires the same zone for every agency); None when none is given."""
    names = {row.get("agency_timezone", "").strip() for row in agency_rows} - {""}
    if not names:
        return None
    if len(names) > 1:
        raise BuildError(f"{source_id}: agency_timezone diversi in agency.txt: {', '.join(log_safe(name) for name in sorted(names))}")
    name = names.pop()
    try:
        return name, ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError) as error:
        raise BuildError(f"{source_id}: agency_timezone non valido: {log_safe(name)}") from error


def noon_offset_minutes(zone: ZoneInfo, day: date) -> int:
    """The UTC offset of ``zone`` at noon of ``day``: GTFS times count from noon minus twelve hours of the service day."""
    offset = datetime(day.year, day.month, day.day, 12, tzinfo=zone).utcoffset()
    assert offset is not None
    return int(offset.total_seconds()) // 60


def to_app_time_zone(
    source_id: str,
    agency_rows: Sequence[Mapping[str, str]],
    trip_rows: Sequence[Mapping[str, str]],
    stop_time_rows: Sequence[Mapping[str, str]],
    days_by_service: Mapping[str, set[date]],
) -> tuple[tuple[dict[str, str], ...], tuple[Mapping[str, str], ...], str, dict[str, int]]:
    """Agency and stop times of a LOCAL_TIME_SOURCES pack moved into APP_TIMEZONE, the zone it was in, and counts.

    A time on service day D in zone Z is APP_TIMEZONE's time plus (offset of APP_TIMEZONE - offset of Z) at noon of D,
    whatever the time: so every trip moves by one amount, that of its service days.  Refused: a pack without
    agency_timezone, a trip whose service days have different amounts (a calendar across a change of summer time), and
    a moved time outside 00:00:00 to 48:00:00.  Moving a pack already in APP_TIMEZONE changes nothing.
    """
    found = agency_zone(source_id, agency_rows)
    if found is None:
        raise BuildError(f"{source_id}: agency_timezone mancante: il fuso degli orari non è noto")
    name, zone = found
    app = ZoneInfo(APP_TIMEZONE)
    shift_by_trip: dict[str, int] = {}
    for row in trip_rows:
        trip_id = row.get("trip_id", "")
        shifts = {noon_offset_minutes(app, day) - noon_offset_minutes(zone, day) for day in days_by_service.get(row.get("service_id", ""), ())}
        if len(shifts) > 1:
            raise BuildError(
                f"{source_id}: il trip {log_safe(trip_id)} circola in giorni con scarti diversi fra {log_safe(name)} e "
                f"{APP_TIMEZONE} ({', '.join(f'{shift:+d} min' for shift in sorted(shifts))}): orari non convertibili"
            )
        shift_by_trip[trip_id] = shifts.pop() if shifts else 0
    moved: list[Mapping[str, str]] = []
    for row in stop_time_rows:
        shift = shift_by_trip.get(row.get("trip_id", ""), 0)
        if shift == 0:
            moved.append(row)
            continue
        updated = dict(row)
        for column in ("arrival_time", "departure_time"):
            value = row.get(column, "")
            if not value.strip():
                continue
            seconds = gtfs_seconds(value)
            if seconds is None:
                raise BuildError(f"{source_id}: {column} non leggibile nel trip {log_safe(row.get('trip_id', ''))}")
            local = seconds + shift * 60
            if not 0 <= local < MAX_LOCAL_TIME_SECONDS:
                raise BuildError(
                    f"{source_id}: {column} {log_safe(value)} del trip {log_safe(row.get('trip_id', ''))} in {APP_TIMEZONE} "
                    f"({shift:+d} min) uscirebbe dal giorno di servizio GTFS"
                )
            updated[column] = gtfs_time(local)
        moved.append(updated)
    counts: dict[str, int] = {}
    for shift in shift_by_trip.values():
        counts[f"{shift:+d}"] = counts.get(f"{shift:+d}", 0) + 1
    agency = tuple({**row, "agency_timezone": APP_TIMEZONE} for row in agency_rows)
    return agency, tuple(moved), name, counts


def train_numbers_from_route_ids(trip_rows: Sequence[Mapping[str, str]]) -> tuple[tuple[Mapping[str, str], ...], int]:
    """Trips of a TRAIN_NUMBER_ROUTE_ID_SOURCES pack with their route_id as trip_short_name where that is empty and the
    route_id is a plain train number (:data:`PLAIN_TRAIN_NUMBER`); how many were filled."""
    filled = 0
    rows: list[Mapping[str, str]] = []
    for row in trip_rows:
        route_id = row.get("route_id", "").strip()
        if not row.get("trip_short_name", "").strip() and PLAIN_TRAIN_NUMBER.fullmatch(route_id):
            rows.append({**row, "trip_short_name": route_id})
            filled += 1
        else:
            rows.append(row)
    return tuple(rows), filled


def route_categories(
    source_id: str, route_rows: Sequence[Mapping[str, str]], agency_rows: Sequence[Mapping[str, str]],
) -> tuple[dict[str, str], ...]:
    """Routes of a ROUTE_CATEGORY_SOURCES pack named by their category; a route of another route_type or agency fails."""
    short_name, long_name, agencies = ROUTE_CATEGORY_SOURCES[source_id]
    names = {row.get("agency_id", "").strip(): row.get("agency_name", "").strip() for row in agency_rows}
    single = next(iter(names.values())) if len(names) == 1 else None
    renamed: list[dict[str, str]] = []
    for row in route_rows:
        route_id = row.get("route_id", "")
        route_type = row.get("route_type", "").strip()
        if route_type != ROUTE_CATEGORY_ROUTE_TYPE:
            raise BuildError(
                f"{source_id}: la route {log_safe(route_id)} ha route_type {log_safe(route_type)}, non "
                f"{ROUTE_CATEGORY_ROUTE_TYPE}: categoria {long_name} non verificata ({ROUTE_CATEGORY_RULE})"
            )
        agency_id = row.get("agency_id", "").strip()
        agency = names.get(agency_id) if agency_id else single
        if agency not in agencies:
            raise BuildError(
                f"{source_id}: la route {log_safe(route_id)} è dell'agenzia {log_safe(str(agency))}, non rivista: "
                f"categoria {long_name} non verificata ({ROUTE_CATEGORY_RULE})"
            )
        renamed.append({**row, "route_short_name": short_name, "route_long_name": long_name})
    return tuple(renamed)


def out_of_order_trips(source_id: str, stop_time_rows: Sequence[Mapping[str, str]]) -> set[str]:
    """Trips with an arrival before the previous departure along stop_sequence (an empty time is not compared)."""
    calls: dict[str, list[tuple[int, int | None, int | None]]] = {}
    for row in stop_time_rows:
        trip_id = row.get("trip_id", "")
        try:
            sequence = int(row.get("stop_sequence", "").strip())
        except ValueError:
            raise BuildError(f"{source_id}: stop_sequence non numerica nella corsa {log_safe(trip_id)}") from None
        calls.setdefault(trip_id, []).append(
            (sequence, gtfs_seconds(row.get("arrival_time", "")), gtfs_seconds(row.get("departure_time", ""))),
        )
    found: set[str] = set()
    for trip_id, stops in calls.items():
        previous: int | None = None
        for _, arrival, departure in sorted(stops, key=lambda call: call[0]):
            if arrival is not None and previous is not None and arrival < previous:
                found.add(trip_id)
                break
            if departure is not None:
                previous = departure
    return found


def published_out_of_order_count(archive: zipfile.ZipFile, source_id: str, provenance: str | None) -> int:
    """The OUT_OF_ORDER_COUNT_FIELD of the published pack the input *is* (``provenance``): filtering it again keeps the
    count of the source (those trips are no longer in it); 0 for any other input."""
    entry = filter_manifest_entry(archive) if provenance is not None else None
    if entry is None:
        return 0
    try:
        value = json.loads(archive.read(entry).decode("utf-8")).get(OUT_OF_ORDER_COUNT_FIELD, 0)
    except (UnicodeDecodeError, json.JSONDecodeError, AttributeError) as error:
        raise BuildError(f"{source_id}: {OUT_OF_ORDER_COUNT_FIELD} non valido") from error
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise BuildError(f"{source_id}: {OUT_OF_ORDER_COUNT_FIELD} non valido")
    return value


def verify_route_categories(
    source_id: str, metadata: Mapping[str, Any], route_rows: Sequence[Mapping[str, str]], agency_rows: Sequence[Mapping[str, str]],
) -> None:
    """A pack declaring ROUTE_CATEGORY_RULE is of a reviewed source and every route carries its category.  A pack of a
    reviewed source built before the rule passes (it is consistent); the refresh rebuilds it (missing_reviewed_rules)."""
    if ROUTE_CATEGORY_RULE not in (metadata.get("tripRules") or []):
        return
    if source_id not in ROUTE_CATEGORY_SOURCES:
        raise BuildError(f"{source_id}: {ROUTE_CATEGORY_RULE} su una fonte non rivista (ROUTE_CATEGORY_SOURCES)")
    expected = route_categories(source_id, route_rows, agency_rows)
    if [dict(row) for row in route_rows] != list(expected):
        raise BuildError(f"{source_id}: route senza la categoria di {ROUTE_CATEGORY_RULE}: ricostruire il pack")


def verify_out_of_order(
    source_id: str, metadata: Mapping[str, Any], trip_count: int, stop_time_rows: Sequence[Mapping[str, str]],
) -> None:
    """A pack declaring OUT_OF_ORDER_RULE is of a reviewed source, records a count within the share and has no trip left
    out of its time order.  A pack of a reviewed source built before the rule passes; the refresh rebuilds it."""
    declared = OUT_OF_ORDER_RULE in (metadata.get("tripRules") or [])
    if not declared and OUT_OF_ORDER_COUNT_FIELD not in metadata:
        return
    if source_id not in OUT_OF_ORDER_SOURCES:
        raise BuildError(f"{source_id}: {OUT_OF_ORDER_RULE} su una fonte non rivista (OUT_OF_ORDER_SOURCES)")
    count = metadata.get(OUT_OF_ORDER_COUNT_FIELD)
    if not declared or not isinstance(count, int) or isinstance(count, bool) or count < 0:
        raise BuildError(f"{source_id}: {OUT_OF_ORDER_RULE} e {OUT_OF_ORDER_COUNT_FIELD} incoerenti")
    if count > MAX_OUT_OF_ORDER_TRIP_SHARE * (trip_count + count):
        raise BuildError(f"{source_id}: {count} trip lasciati fuori per l'ordine degli orari, oltre la quota ammessa")
    left = out_of_order_trips(source_id, stop_time_rows)
    if left:
        raise BuildError(f"{source_id}: {len(left)} trip con orari fuori ordine lungo stop_sequence, es. {log_safe(sorted(left)[0])}")


def missing_reviewed_rules(path: Path, source_id: str) -> list[str]:
    """The rules of this build for ``source_id`` that its published pack at ``path`` was built without (a rule added
    after it): the pack still passes --check, and the weekly refresh filters its source again."""
    expected = [rule for rule, sources in ((ROUTE_CATEGORY_RULE, ROUTE_CATEGORY_SOURCES), (OUT_OF_ORDER_RULE, OUT_OF_ORDER_SOURCES))
                if source_id in sources]
    if not expected:
        return []
    with zipfile.ZipFile(path) as archive:
        entry = filter_manifest_entry(archive)
        if entry is None:
            return expected
        try:
            declared = json.loads(archive.read(entry).decode("utf-8")).get("tripRules") or []
        except (UnicodeDecodeError, json.JSONDecodeError, AttributeError) as error:
            raise BuildError(f"{source_id}: metadati filtro non validi") from error
    return [rule for rule in expected if rule not in declared]


def calendar_span(calendar: CsvTable, calendar_dates: CsvTable, source_id: str) -> tuple[date, date] | None:
    """First and last day named by calendar.txt (start_date to end_date) and calendar_dates.txt; None without any."""
    days: list[date] = []
    for row in calendar.rows:
        for column in ("start_date", "end_date"):
            value = row.get(column, "").strip()
            if value:
                days.append(parse_gtfs_date(value, f"{source_id}:calendar.txt"))
    days.extend(parse_gtfs_date(row.get("date", "").strip(), f"{source_id}:calendar_dates.txt") for row in calendar_dates.rows)
    return (min(days), max(days)) if days else None


def verify_local_times(
    source_id: str,
    metadata: Mapping[str, Any],
    agency_rows: Sequence[Mapping[str, str]],
    calendar: CsvTable,
    calendar_dates: CsvTable,
) -> None:
    """Every agency_timezone of a pack has APP_TIMEZONE's offset on each day of its calendar (the apps read the times
    as local); a LOCAL_TIME_SOURCES pack declares the move (LOCAL_TIMES_RULE, LOCAL_TIMES_FROM_FIELD) and APP_TIMEZONE."""
    rules = metadata.get("tripRules") or []
    declared = LOCAL_TIMES_RULE in rules or LOCAL_TIMES_FROM_FIELD in metadata
    if declared and source_id not in LOCAL_TIME_SOURCES:
        raise BuildError(f"{source_id}: {LOCAL_TIMES_RULE} su una fonte non rivista (LOCAL_TIME_SOURCES)")
    found = agency_zone(source_id, agency_rows)
    if source_id in LOCAL_TIME_SOURCES:
        origin = metadata.get(LOCAL_TIMES_FROM_FIELD)
        if LOCAL_TIMES_RULE not in rules or not isinstance(origin, str) or not origin:
            raise BuildError(
                f"{source_id}: orari non portati in {APP_TIMEZONE} ({LOCAL_TIMES_RULE} e {LOCAL_TIMES_FROM_FIELD} mancanti): "
                "ricostruire il pack"
            )
        if found is None or found[0] != APP_TIMEZONE:
            raise BuildError(f"{source_id}: agency_timezone non è {APP_TIMEZONE} dopo {LOCAL_TIMES_RULE}")
    if found is None:
        return
    name, zone = found
    span = calendar_span(calendar, calendar_dates, source_id)
    if span is None:
        return
    app = ZoneInfo(APP_TIMEZONE)
    day = span[0]
    while day <= span[1]:
        if noon_offset_minutes(zone, day) != noon_offset_minutes(app, day):
            raise BuildError(
                f"{source_id}: agency_timezone {log_safe(name)} il {day} non ha lo scarto da UTC di {APP_TIMEZONE}: "
                "le app leggono gli orari come ora italiana (vedi LOCAL_TIME_SOURCES)"
            )
        day += timedelta(days=1)


def verify_calendars_from(source_id: str, value: object, calendar: CsvTable, calendar_dates: CsvTable) -> None:
    """A pack declaring CALENDARS_FROM_FIELD is of a reviewed large archive and names no calendar day before it."""
    if large_archive_review(source_id) is None:
        raise BuildError(f"{source_id}: {CALENDARS_FROM_FIELD} su una fonte non rivista (REVIEWED_LARGE_ARCHIVES)")
    try:
        first = date.fromisoformat(str(value))
    except ValueError as error:
        raise BuildError(f"{source_id}: {CALENDARS_FROM_FIELD} non valido") from error
    span = calendar_span(calendar, calendar_dates, source_id)
    if span is not None and span[0] < first:
        raise BuildError(f"{source_id}: calendario dal {span[0]}, prima del {first} ({CALENDARS_FROM_FIELD})")


def verify_train_numbers(source_id: str, metadata: Mapping[str, Any], trip_rows: Sequence[Mapping[str, str]]) -> None:
    """A TRAIN_NUMBER_ROUTE_ID_SOURCES pack declares TRAIN_NUMBERS_RULE and has no trip left with an empty
    trip_short_name and a plain-number route_id; no other pack declares it."""
    declared = TRAIN_NUMBERS_RULE in (metadata.get("tripRules") or [])
    if source_id not in TRAIN_NUMBER_ROUTE_ID_SOURCES:
        if declared:
            raise BuildError(f"{source_id}: {TRAIN_NUMBERS_RULE} su una fonte non rivista (TRAIN_NUMBER_ROUTE_ID_SOURCES)")
        return
    if not declared:
        raise BuildError(f"{source_id}: numeri treno non ricavati dal route_id ({TRAIN_NUMBERS_RULE} mancante): ricostruire il pack")
    _, filled = train_numbers_from_route_ids(trip_rows)
    if filled:
        raise BuildError(f"{source_id}: {filled} trip senza trip_short_name con un route_id numero treno")


def route_selector(source_id: str) -> Callable[[Mapping[str, str]], bool]:
    """The row filter of :func:`selected_routes` for ``source_id``: a reviewed large archive reads routes.txt with it."""
    explicit = EXCLUSIVE_ROUTE_IDS.get(source_id)
    if explicit is not None:
        return lambda row: row.get("route_id", "") in explicit
    if source_id in URBAN_SOURCES:
        excluded = URBAN_SOURCES[source_id]
        return lambda row: row.get("route_type", "").strip() in URBAN_ROUTE_TYPES and row.get("agency_id", "").strip() not in excluded
    allowed = RAIL_ROUTE_TYPES | SCHEDULED_COACH_ROUTE_TYPES if source_id in SCHEDULED_COACH_SOURCES else RAIL_ROUTE_TYPES
    return lambda row: row.get("route_type", "").strip() in allowed


def selected_routes(source_id: str, routes: CsvTable) -> tuple[tuple[dict[str, str], ...], dict[str, Any]]:
    require_columns(routes, "routes.txt", "route_id", "route_type")
    keep = route_selector(source_id)
    explicit = EXCLUSIVE_ROUTE_IDS.get(source_id)
    if explicit is not None:
        selected = tuple(row for row in routes.rows if keep(row))
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
        selected = tuple(row for row in routes.rows if keep(row))
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
        selected = tuple(row for row in routes.rows if keep(row))
        metadata = {
            "kind": "gtfs-rail-and-operator-coach-route-types",
            "description": (
                "route_type GTFS ferroviari standard ed estesi (2, 100-199) e autobus di linea "
                "dell'operatore (3), presentati come bus programmati e mai come treni."
            ),
            "routeTypes": sorted(allowed, key=int),
        }
    else:
        selected = tuple(row for row in routes.rows if keep(row))
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


def read_calendars(
    archive: zipfile.ZipFile, service_ids: set[str], budget: RetentionBudget, max_rows: int | None,
) -> tuple[CsvTable, CsvTable]:
    """calendar.txt and calendar_dates.txt of ``service_ids`` (empty tables when absent)."""
    calendar = read_table(
        archive,
        "calendar.txt",
        predicate=lambda row: row.get("service_id", "") in service_ids,
        budget=budget,
        max_rows=max_rows,
    ) or empty_calendar()
    calendar_dates = read_table(
        archive,
        "calendar_dates.txt",
        predicate=lambda row: row.get("service_id", "") in service_ids,
        budget=budget,
        max_rows=max_rows,
    ) or empty_calendar_dates()
    return calendar, calendar_dates


@dataclass
class ServiceDays:
    """The days each service runs from ``first`` on (:func:`service_days` from that day), as bitmaps: bit n is
    ``first`` + n days.  ``source_rows`` counts the rows of calendar.txt and calendar_dates.txt that give a service a
    day from ``first`` on (its encoding in the source, clipped there); ``irregular`` names the services whose encoding
    every reader would not read alike (more than one calendar row, a weekday flag other than 0 or 1, an exception other
    than 1 or 2, two exceptions on one day, a date with spaces); the headers are those of the source tables."""

    first: date
    days: dict[str, int]
    source_rows: dict[str, int]
    irregular: set[str]
    calendar_header: tuple[str, ...]
    calendar_dates_header: tuple[str, ...]
    exceptions_read: int


def read_service_days(
    archive: zipfile.ZipFile,
    source_id: str,
    service_ids: set[str],
    runs_from: date,
    max_rows: int | None,
    max_service_days: int,
) -> ServiceDays:
    """The days from ``runs_from`` on of the services ``service_ids`` (:class:`ServiceDays`), read without holding a row:
    the same days as :func:`service_days` (a calendar row by its weekday flags from its start_date to its end_date, minus
    the days of an exception other than 1, plus the days of an exception 1; an empty date is skipped).  A calendar row
    ending before ``runs_from`` and an exception before it give no such day.  The bitmaps of calendar days, added days
    and days with an exception together span at most ``max_service_days`` bits (services by days from ``runs_from``),
    else the build stops."""
    base = runs_from.toordinal()
    calendar_bits: dict[str, int] = {}
    added_bits: dict[str, int] = {}
    listed_bits: dict[str, int] = {}
    source_rows: dict[str, int] = {}
    calendar_rows: dict[str, int] = {}
    irregular: set[str] = set()
    held = [0]

    def reserve(table: dict[str, int], service_id: str, last: int) -> None:
        """Room for day ``last`` in the bitmap of ``service_id``, checked before the bitmap grows."""
        grown = max(0, last + 1 - table.get(service_id, 0).bit_length())
        if held[0] + grown > max_service_days:
            raise BuildError(
                f"{source_id}: calendario oltre {max_service_days} giorni di servizio tenuti (servizi per giorni dal "
                f"{runs_from}: LargeArchiveReview.max_service_days)"
            )
        held[0] += grown

    def calendar_row(row: Mapping[str, str]) -> bool:
        service_id = row.get("service_id", "")
        start_value, end_value = row.get("start_date", "").strip(), row.get("end_date", "").strip()
        if service_id not in service_ids or not start_value or not end_value:
            return False
        start = parse_gtfs_date(start_value, f"{source_id}:calendar.txt")
        end = parse_gtfs_date(end_value, f"{source_id}:calendar.txt")
        first, last = max(start.toordinal(), base) - base, end.toordinal() - base
        if first > last:
            return False
        source_rows[service_id] = source_rows.get(service_id, 0) + 1
        calendar_rows[service_id] = calendar_rows.get(service_id, 0) + 1
        if (
            calendar_rows[service_id] > 1 or any(row.get(column, "") not in ("0", "1") for column in WEEKDAY_COLUMNS)
            or row.get("start_date", "") != start_value or row.get("end_date", "") != end_value
        ):
            irregular.add(service_id)
        runs = tuple(row.get(column, "").strip() == "1" for column in WEEKDAY_COLUMNS)
        if any(runs):
            reserve(calendar_bits, service_id, last)
            bits = calendar_bits.get(service_id, 0)
            for offset in range(first, last + 1):
                if runs[(base + offset + 6) % 7]:  # weekday of ordinal n: (n + 6) % 7, Monday 0
                    bits |= 1 << offset
            calendar_bits[service_id] = bits
        return False

    def exception_row(row: Mapping[str, str]) -> bool:
        service_id = row.get("service_id", "")
        value = row.get("date", "").strip()
        if service_id not in service_ids or not value:
            return False
        offset = parse_gtfs_date(value, f"{source_id}:calendar_dates.txt").toordinal() - base
        if offset < 0:
            return False
        source_rows[service_id] = source_rows.get(service_id, 0) + 1
        reserve(listed_bits, service_id, offset)
        listed = listed_bits.get(service_id, 0)
        if listed >> offset & 1 or row.get("exception_type", "") not in ("1", "2") or row.get("date", "") != value:
            irregular.add(service_id)
        listed_bits[service_id] = listed | 1 << offset
        if row.get("exception_type", "").strip() == "1":
            reserve(added_bits, service_id, offset)
            added_bits[service_id] = added_bits.get(service_id, 0) | 1 << offset
        elif calendar_bits.get(service_id, 0) >> offset & 1:
            calendar_bits[service_id] ^= 1 << offset
        return False

    counts: dict[str, int] = {}
    calendar = read_table(archive, "calendar.txt", predicate=calendar_row, max_rows=max_rows) or empty_calendar()
    calendar_dates = read_table(
        archive, "calendar_dates.txt", predicate=exception_row, max_rows=max_rows, counts=counts,
    ) or empty_calendar_dates()
    require_columns(calendar, "calendar.txt", "service_id", "start_date", "end_date", *WEEKDAY_COLUMNS)
    require_columns(calendar_dates, "calendar_dates.txt", "service_id", "date", "exception_type")
    days = {service_id: calendar_bits.get(service_id, 0) | added_bits.get(service_id, 0) for service_id in service_ids}
    return ServiceDays(runs_from, days, source_rows, irregular, calendar.header, calendar_dates.header, counts.get("read", 0))


def running_services(
    archive: zipfile.ZipFile, source_id: str, service_ids: set[str], runs_from: date, max_rows: int | None,
    max_service_days: int = 1 << 40,
) -> set[str]:
    """The services among ``service_ids`` with a day from ``runs_from`` on: those :func:`expired_trips` keeps."""
    found = read_service_days(archive, source_id, service_ids, runs_from, max_rows, max_service_days)
    return {service_id for service_id, bits in found.days.items() if bits}


def recoded_service(service_id: str, bits: int, first: date) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    """The smallest of two exact encodings of the days ``bits`` (bit n: ``first`` + n days) of one service, as rows of
    calendar.txt and calendar_dates.txt: one calendar row from the first to the last day, with the weekdays that run on
    most of their dates in that range, and exceptions 1 and 2 for the other days; or exceptions 1 alone when that is no
    more rows.  Deterministic: a weekday running on exactly half its dates is left out; exceptions by date."""
    if not bits:
        return [], []
    low, high = (bits & -bits).bit_length() - 1, bits.bit_length() - 1
    base = first.toordinal()
    running = format(bits >> low, "b")[::-1]  # character n: day low + n
    on, total = [0] * 7, [0] * 7
    for index, flag in enumerate(running):
        weekday = (base + low + index + 6) % 7
        total[weekday] += 1
        on[weekday] += flag == "1"
    pattern = [2 * on[weekday] > total[weekday] for weekday in range(7)]
    differences = sum(total[weekday] - on[weekday] if pattern[weekday] else on[weekday] for weekday in range(7))
    day = lambda index: date.fromordinal(base + low + index).strftime("%Y%m%d")  # noqa: E731
    if 1 + differences < running.count("1"):
        calendar = [{
            "service_id": service_id,
            **{column: "1" if pattern[weekday] else "0" for weekday, column in enumerate(WEEKDAY_COLUMNS)},
            "start_date": day(0),
            "end_date": day(len(running) - 1),
        }]
        exceptions = [
            {"service_id": service_id, "date": day(index), "exception_type": "1" if flag == "1" else "2"}
            for index, flag in enumerate(running)
            if (flag == "1") != pattern[(base + low + index + 6) % 7]
        ]
        return calendar, exceptions
    return [], [
        {"service_id": service_id, "date": day(index), "exception_type": "1"} for index, flag in enumerate(running) if flag == "1"
    ]


def compact_calendars(
    archive: zipfile.ZipFile,
    source_id: str,
    found: ServiceDays,
    service_ids: set[str],
    budget: RetentionBudget,
    max_rows: int | None,
) -> tuple[CsvTable, CsvTable]:
    """calendar.txt and calendar_dates.txt of ``service_ids`` with the days each runs from ``found.first`` on (the
    earlier days no kept trip needs, :func:`expired_trips`): each service in the smaller of its encoding in the source,
    clipped at that day (calendar rows ending before it and earlier exceptions left out, start_date moved up to it), and
    :func:`recoded_service`; the source's encoding when no larger and read alike by every reader (not
    ``found.irregular``).  Services by service_id; the rows of a service as the source lists them, or as recoded."""
    recoded: dict[str, tuple[list[dict[str, str]], list[dict[str, str]]]] = {}
    kept_as_source: set[str] = set()
    for service_id in sorted(service_ids):
        rows = recoded_service(service_id, found.days.get(service_id, 0), found.first)
        if service_id not in found.irregular and found.source_rows.get(service_id, 0) <= len(rows[0]) + len(rows[1]):
            kept_as_source.add(service_id)
        else:
            recoded[service_id] = rows
    first = found.first.strftime("%Y%m%d")
    source_calendar: dict[str, list[dict[str, str]]] = {}
    source_dates: dict[str, list[dict[str, str]]] = {}
    if kept_as_source:
        def clipped_calendar_row(row: Mapping[str, str]) -> bool:
            start, end = row.get("start_date", "").strip(), row.get("end_date", "").strip()
            return (
                row.get("service_id", "") in kept_as_source and bool(start) and bool(end) and end >= first
                and max(start, first) <= end
            )

        def clipped_exception_row(row: Mapping[str, str]) -> bool:
            value = row.get("date", "").strip()
            return row.get("service_id", "") in kept_as_source and bool(value) and value >= first

        for row in (read_table(archive, "calendar.txt", predicate=clipped_calendar_row, budget=budget, max_rows=max_rows)
                    or empty_calendar()).rows:
            moved = {**row, "start_date": first} if row.get("start_date", "").strip() < first else row
            source_calendar.setdefault(row.get("service_id", ""), []).append(moved)
        for row in (read_table(archive, "calendar_dates.txt", predicate=clipped_exception_row, budget=budget, max_rows=max_rows)
                    or empty_calendar_dates()).rows:
            source_dates.setdefault(row.get("service_id", ""), []).append(row)
    calendar_rows: list[dict[str, str]] = []
    date_rows: list[dict[str, str]] = []
    for service_id in sorted(service_ids):
        source = service_id in kept_as_source
        for name, table, rows in (
            ("calendar.txt", calendar_rows, source_calendar.get(service_id, []) if source else recoded[service_id][0]),
            ("calendar_dates.txt", date_rows, source_dates.get(service_id, []) if source else recoded[service_id][1]),
        ):
            for row in rows:
                if len(table) >= MAX_RETAINED_TABLE_ROWS:
                    raise BuildError(f"tabella oltre budget righe trattenute: {name} > {MAX_RETAINED_TABLE_ROWS} ({budget.held()})")
                if not source:
                    budget.retain(name, row)  # the source's rows were retained as read
                table.append(row)
    print(
        f"{source_id}: calendar_dates.txt {found.exceptions_read} righe lette; {len(service_ids)} servizi tenuti dal "
        f"{found.first}, {len(recoded)} ricodificati: {len(calendar_rows)} righe di calendar.txt e {len(date_rows)} di "
        "calendar_dates.txt",
        file=sys.stderr,
    )
    return CsvTable(found.calendar_header, tuple(calendar_rows)), CsvTable(found.calendar_dates_header, tuple(date_rows))


def read_running_trips(
    archive: zipfile.ZipFile,
    source_id: str,
    route_ids: set[str],
    runs_from: date,
    budget: RetentionBudget,
    max_rows: int | None,
    with_route_trip_ids: bool,
    max_service_days: int,
) -> tuple[CsvTable, set[str], ServiceDays]:
    """trips.txt of the kept routes whose service runs from ``runs_from`` on, with the ids of every trip of those routes
    when ``with_route_trip_ids`` (for frequencies.txt) and the days of their services from ``runs_from`` on.  A first
    pass names the services of the trips of the kept routes and retains nothing; :func:`read_service_days` reads the
    calendar into bitmaps; the second pass retains the trips that still run.  A trip id with a row of a service that no
    longer runs goes whole, as in :func:`expired_trips`."""
    services: set[str] = set()
    route_trip_ids: set[str] = set()
    named: list[bool] = [False]

    def scan(row: Mapping[str, str]) -> bool:
        if row.get("route_id", "") in route_ids:
            if row.get("service_id"):
                services.add(row["service_id"])
            if row.get("trip_id"):
                named[0] = True
                if with_route_trip_ids:
                    route_trip_ids.add(row["trip_id"])
        return False

    table = read_table(archive, "trips.txt", required=True, predicate=scan, max_rows=max_rows)
    assert table is not None
    require_columns(table, "trips.txt", "route_id", "trip_id", "service_id")
    if not named[0]:
        raise BuildError(f"{source_id}: le route filtrate non hanno trip")
    found = read_service_days(archive, source_id, services, runs_from, max_rows, max_service_days)
    running = {service_id for service_id, bits in found.days.items() if bits}
    dead: set[str] = set()

    def keep(row: Mapping[str, str]) -> bool:
        if row.get("route_id", "") not in route_ids:
            return False
        if row.get("service_id", "") in running:
            return True
        dead.add(row.get("trip_id", ""))
        return False

    trips = read_table(archive, "trips.txt", required=True, predicate=keep, budget=budget, max_rows=max_rows)
    assert trips is not None
    shared = [row for row in trips.rows if row.get("trip_id", "") in dead]
    if shared:
        budget.release("trips.txt", shared)
        trips = CsvTable(trips.header, tuple(row for row in trips.rows if row.get("trip_id", "") not in dead))
    if dead:
        print(f"{source_id}: {len(dead)} trip che non circolano più dal {runs_from} rimossi", file=sys.stderr)
    return trips, route_trip_ids, found


def read_kept_stops(
    archive: zipfile.ZipFile, source_id: str, stop_ids: set[str], budget: RetentionBudget,
) -> tuple[CsvTable, set[str]]:
    """stops.txt reduced while read to the stops of ``stop_ids`` and their parent stations (with theirs), as
    filter_archive keeps them from the whole table: passes that retain nothing find each level of parents (a parent
    absent from stops.txt is left out), then one pass retains the rows kept.  Returns them and the kept stop ids."""
    kept = set(stop_ids)
    pending = set(stop_ids)
    first = True
    while pending:
        parents: dict[str, str] = {}

        def scan(row: Mapping[str, str]) -> bool:
            stop_id = row.get("stop_id", "")
            if stop_id in pending:
                # The last row of a repeated stop_id, as stops_by_id in filter_archive.
                parents[stop_id] = row.get("parent_station", "").strip()
            return False

        table = read_table(archive, "stops.txt", required=True, predicate=scan)
        assert table is not None
        if first:
            require_columns(table, "stops.txt", "stop_id")
            unknown = sorted(stop_ids - parents.keys())
            if unknown:
                raise BuildError(f"{source_id}: stop_times riferisce stop assenti: {unknown[:3]}")
            first = False
        kept |= parents.keys()
        if "parent_station" not in table.header:
            break
        pending = {parent for parent in parents.values() if parent and parent not in kept} - pending
    stops = read_table(archive, "stops.txt", required=True, predicate=lambda row: row.get("stop_id", "") in kept, budget=budget)
    assert stops is not None
    return stops, kept


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
    # A reviewed large archive (REVIEWED_LARGE_ARCHIVES): its own limits for the archive, the STREAMED_TABLES and the
    # build totals, and every table reduced while read to what the pack keeps: routes.txt to the kept routes, trips.txt
    # to the trips that still run from tripsRunFrom (never for the itinerary copies of ITINERARY_COPY_SOURCES, which
    # compare a trip with the others of each of its days), the calendar to their services, stops.txt to their stops.
    # The rules below then find nothing more to drop, and the pack is the one the default path builds.
    review = large_archive_review(source_id)
    streamed_rows = None if review is None else review.max_streamed_table_rows
    with zipfile.ZipFile(source) as archive:
        validate_archive_limits(archive, omit_geometry=True, source_id=source_id)
        if review is not None:
            total = sum(entry.file_size for entry in archive.infolist() if not entry.is_dir())
            print(
                f"{source_id}: archivio rivisto, {total} byte decompressi (limite {review.max_uncompressed_bytes}), "
                f"letto in streaming; membri più grandi: {largest_members(archive)}",
                file=sys.stderr,
            )
        provenance = published_provenance(published_entries, input_hash)
        source_hash = original_archive_sha256(archive, source_id, input_hash, provenance)
        budget = RetentionBudget() if review is None else review.budget()
        routes = read_table(
            archive, "routes.txt", required=True, budget=budget, predicate=None if review is None else route_selector(source_id),
        )
        assert routes
        kept_routes, policy = selected_routes(source_id, routes)
        route_ids = {row["route_id"] for row in kept_routes if row.get("route_id")}
        if review is not None and trips_run_from is not None and source_id not in ITINERARY_COPY_SOURCES:
            # frequencies.txt is read for every trip of the kept routes, as on the default path, so that its expansion
            # (and MAX_FREQUENCY_RUNS) does not depend on when the expired trips go.
            trips, route_trip_ids, found_days = read_running_trips(
                archive, source_id, route_ids, trips_run_from, budget, streamed_rows,
                with_route_trip_ids=find_entry(archive, "frequencies.txt") is not None,
                max_service_days=review.max_service_days,
            )
            kept_trips = trips.rows
            trip_ids = {row["trip_id"] for row in kept_trips if row.get("trip_id")}
            service_ids = {row["service_id"] for row in kept_trips if row.get("service_id")}
        else:
            trips = read_table(
                archive,
                "trips.txt",
                required=True,
                predicate=lambda row: row.get("route_id", "") in route_ids,
                budget=budget,
                max_rows=streamed_rows,
            )
            assert trips
            require_columns(trips, "trips.txt", "route_id", "trip_id", "service_id")
            kept_trips = trips.rows
            trip_ids = {row["trip_id"] for row in kept_trips if row.get("trip_id")}
            service_ids = {row["service_id"] for row in kept_trips if row.get("service_id")}
            if not trip_ids:
                raise BuildError(f"{source_id}: le route filtrate non hanno trip")
            route_trip_ids = trip_ids
            found_days = None

        stop_time_counts: dict[str, int] = {}
        stop_times = read_table(
            archive,
            "stop_times.txt",
            required=True,
            predicate=lambda row: row.get("trip_id", "") in trip_ids,
            budget=budget,
            max_rows=streamed_rows,
            counts=stop_time_counts,
        )
        assert stop_times
        require_columns(stop_times, "stop_times.txt", "trip_id", "stop_id")
        kept_stop_times = stop_times.rows
        if review is not None:
            print(
                f"{source_id}: stop_times.txt {stop_time_counts['read']} righe lette, {stop_time_counts['kept']} tenute",
                file=sys.stderr,
            )

        # Rules that drop or change trips are named in the filter manifest; their counts go to the log, so that filtering
        # a published pack again gives the same bytes.
        trip_rules: list[str] = []
        frequencies = read_table(
            archive,
            "frequencies.txt",
            predicate=lambda row: row.get("trip_id", "") in route_trip_ids,
            budget=budget,
            max_rows=streamed_rows,
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

        if found_days is not None:
            # The days the kept trips run from tripsRunFrom, each service in its smallest exact encoding: the Swiss
            # file lists most days of most services one by one (run 45516917094: over 750,000 rows for 5,562 services).
            calendar, calendar_dates = compact_calendars(archive, source_id, found_days, service_ids, budget, streamed_rows)
        else:
            calendar, calendar_dates = read_calendars(archive, service_ids, budget, streamed_rows)
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

        run_metadata: dict[str, Any] = {}
        if trips_run_from is not None:
            expired = expired_trips(kept_trips, service_days(calendar, calendar_dates, source_id), trips_run_from)
            if expired:
                print(f"{source_id}: {len(expired)} trip che non circolano più dal {trips_run_from} rimossi", file=sys.stderr)
                kept_trips = tuple(row for row in kept_trips if row.get("trip_id", "") not in expired)
                kept_stop_times = tuple(row for row in kept_stop_times if row.get("trip_id", "") not in expired)
                trip_ids = {row["trip_id"] for row in kept_trips if row.get("trip_id")}
                service_ids = {row["service_id"] for row in kept_trips if row.get("service_id")}
            run_metadata[TRIPS_RUN_FROM_FIELD] = trips_run_from.isoformat()
            if found_days is not None:
                run_metadata[CALENDARS_FROM_FIELD] = trips_run_from.isoformat()

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

        if source_id in OUT_OF_ORDER_SOURCES:
            out_of_order = out_of_order_trips(source_id, kept_stop_times)
            if len(out_of_order) > MAX_OUT_OF_ORDER_TRIP_SHARE * len(kept_trips):
                raise BuildError(
                    f"{source_id}: {len(out_of_order)} trip su {len(kept_trips)} con orari fuori ordine lungo stop_sequence, "
                    f"oltre la quota ammessa ({OUT_OF_ORDER_RULE})"
                )
            if out_of_order:
                kept_trips = tuple(row for row in kept_trips if row.get("trip_id", "") not in out_of_order)
                kept_stop_times = tuple(row for row in kept_stop_times if row.get("trip_id", "") not in out_of_order)
                trip_ids = {row["trip_id"] for row in kept_trips if row.get("trip_id")}
                service_ids = {row["service_id"] for row in kept_trips if row.get("service_id")}
            trip_rules.append(OUT_OF_ORDER_RULE)
            run_metadata[OUT_OF_ORDER_COUNT_FIELD] = published_out_of_order_count(archive, source_id, provenance) + len(out_of_order)
            print(f"{source_id}: {len(out_of_order)} trip con orari fuori ordine lungo stop_sequence lasciati fuori "
                  f"{log_safe(json.dumps(sorted(out_of_order)[:5]))}", file=sys.stderr)

        stop_ids = {row["stop_id"] for row in kept_stop_times if row.get("stop_id")}
        if not stop_ids:
            raise BuildError(f"{source_id}: i trip filtrati non hanno stop_times")

        if review is None:
            stops = read_table(archive, "stops.txt", required=True, budget=budget)
        else:
            stops, stop_ids = read_kept_stops(archive, source_id, stop_ids, budget)
            kept_stops = stops.rows
        agency = read_table(archive, "agency.txt", required=True, budget=budget)
        assert agency and stops
        require_columns(agency, "agency.txt", "agency_name")
        require_columns(stops, "stops.txt", "stop_id")

        if review is None:
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

        trips_header = tuple(key for key in trips.header if key != "shape_id")
        if source_id in LOCAL_TIME_SOURCES:
            kept_agency, kept_stop_times, zone_name, shifts = to_app_time_zone(
                source_id, kept_agency, kept_trips, kept_stop_times, service_days(calendar, calendar_dates, source_id),
            )
            trip_rules.append(LOCAL_TIMES_RULE)
            # Filtering the published pack again (already in APP_TIMEZONE) keeps the zone the source was in.
            run_metadata[LOCAL_TIMES_FROM_FIELD] = published_local_times_from(archive, source_id, provenance) or zone_name
            print(f"{source_id}: orari da {log_safe(zone_name)} a {APP_TIMEZONE}, trip per scarto in minuti "
                  f"{log_safe(json.dumps(shifts, sort_keys=True))}", file=sys.stderr)
        if source_id in TRAIN_NUMBER_ROUTE_ID_SOURCES:
            kept_trips, filled = train_numbers_from_route_ids(kept_trips)
            if "trip_short_name" not in trips_header:
                trips_header = (*trips_header, "trip_short_name")
            trip_rules.append(TRAIN_NUMBERS_RULE)
            print(f"{source_id}: {filled} trip_short_name ricavati dal route_id", file=sys.stderr)
        routes_header = routes.header
        if source_id in ROUTE_CATEGORY_SOURCES:
            kept_routes = route_categories(source_id, kept_routes, kept_agency)
            routes_header = (*routes.header, *(column for column in ("route_short_name", "route_long_name") if column not in routes.header))
            trip_rules.append(ROUTE_CATEGORY_RULE)
            print(f"{source_id}: {len(kept_routes)} route con la categoria {ROUTE_CATEGORY_SOURCES[source_id][1]}", file=sys.stderr)

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
            "routes.txt": CsvTable(routes_header, kept_routes),
            "trips.txt": CsvTable(
                trips_header,
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
            max_rows=streamed_rows,
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
            max_rows=streamed_rows,
        )
        if pathways is not None:
            require_columns(pathways, "pathways.txt", "from_stop_id", "to_stop_id")
            tables["pathways.txt"] = pathways

        for table_name in COPY_METADATA_TABLES:
            table = read_table(archive, table_name, budget=budget)
            if table is not None:
                tables[table_name] = table
        if review is not None:
            # What the reviewed totals hold room for, read from the log of each refresh.
            print(
                f"{source_id}: trattenute {budget.retained_rows} righe (limite {review.max_retained_rows or MAX_RETAINED_BUILD_ROWS}), "
                f"{budget.retained_cells} celle (limite {review.max_retained_cells or MAX_RETAINED_BUILD_CELLS}), "
                f"{budget.retained_utf8_bytes} byte (limite {review.max_retained_utf8_bytes or MAX_RETAINED_BUILD_UTF8_BYTES}); "
                f"{budget.held()}",
                file=sys.stderr,
            )

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
        calendar = read_table(archive, "calendar.txt") or empty_calendar()
        calendar_dates = read_table(archive, "calendar_dates.txt") or empty_calendar_dates()
        if CALENDARS_FROM_FIELD in metadata:
            verify_calendars_from(source_id, metadata[CALENDARS_FROM_FIELD], calendar, calendar_dates)
        agency = read_table(archive, "agency.txt", required=True)
        assert agency is not None
        verify_local_times(source_id, metadata, agency.rows, calendar, calendar_dates)
        verify_train_numbers(source_id, metadata, tables["trips.txt"].rows)
        verify_route_categories(source_id, metadata, routes.rows, agency.rows)
        verify_out_of_order(source_id, metadata, len(tables["trips.txt"].rows), tables["stop_times.txt"].rows)
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
    max_download_bytes: int | None,
) -> tuple[dict[str, Path], Path]:
    """Download the selected OPEN_DIRECT sources; each under ``max_download_bytes`` when given, else under its own cap
    (:func:`source_download_limit`: DEFAULT_MAX_DOWNLOAD_BYTES or the reviewed limit of a large archive)."""
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
            limit = source_download_limit(source_id, DEFAULT_MAX_DOWNLOAD_BYTES) if max_download_bytes is None else max_download_bytes
            download_to_file(url, target, max_bytes=limit)
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
    parser.add_argument(
        "--max-download-bytes",
        type=int,
        help=f"cap hard per ogni download (default: {DEFAULT_MAX_DOWNLOAD_BYTES}, o il limite rivisto di REVIEWED_LARGE_ARCHIVES)",
    )
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
