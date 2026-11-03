#!/usr/bin/env python3
"""Read-only discovery of public transit timetable sources (GTFS, NeTEx) for Binario.

Run on a CI runner (the development container cannot reach most open-data hosts), always with
``python3 -I`` from the repository root.  Everything fetched is *untrusted data*: it is written to
``--out`` and parsed only by this file (standard library); nothing downloaded is executed.

Probes are tokens ``KIND:URL`` (``page``, ``json``, ``gtfs``, ``head``, ``jsgrep``, ``netexinfo``, ``nextdata``,
``nextjson``)
plus the named catalogue scans ``mdb`` (Mobility Database catalogue, Italian rows) and ``atlas``
(Transitland Atlas DMFR records for Italy).  The report is printed and saved as ``report.txt``.

Realtime and bike sharing: ``gtfsrt:URL`` decodes a GTFS-Realtime FeedMessage with the small protobuf
wire-format reader below (no protobuf library) and matches its trips, routes and stops against the
repository's city packs and any static feed named before it with ``rtstatic:URL``; it prints a few
entities of the best-matching pack as a base64 sample (``RTSAMPLE``) for test fixtures.  ``gbfs:URL``
reads a GBFS auto-discovery file, its system information (licence and terms fields) and stations or
vehicles, and counts those within 300 m of the curated railway stations.  ``ckan:URL`` lists the datasets of
a CKAN ``package_search`` URL (open-data portals) with licence and resources.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import base64
import io
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from collections import Counter
from pathlib import Path

USER_AGENT = "Binario-source-discovery/1.0 (personal timetable app; read-only)"
PAGE_LIMIT = 4 * 1024 * 1024
JSON_LIMIT = 16 * 1024 * 1024
ARCHIVE_LIMIT = 400 * 1024 * 1024
TIMEOUT = 60
NETEX_SUMMARY_SECONDS = 150
REALTIME_LIMIT = 64 * 1024 * 1024
NEAR_STATION_METERS = 300.0
ROOT = Path(__file__).resolve().parents[1]
CITY_PACK_GLOBS = ("app/src/main/assets/gtfs-transit/*.zip", "app/src/personal/assets/gtfs-personal/*.zip")
STATION_CATALOGUE = "app/src/main/assets/stations/curated_it_rail_stations.json"

URBAN_TYPES = {"0", "1", "5", "6", "7", "12"} | {str(v) for v in range(400, 1800)}
RAIL_TYPES = {"2"} | {str(v) for v in range(100, 200)}

DEFAULT_PROBES = [
    "mdb",
    "atlas",
]

_report_path: Path | None = None


def log_safe(value: object) -> str:
    """``value`` on one line with control characters escaped: fetched text never starts a log line (audit A7-02)."""
    return "".join(
        character if character.isprintable() or character == " " else character.encode("unicode_escape").decode("ascii")
        for character in str(value)
    )


def emit(line: str = "") -> None:
    """Print and append to the report at once, so a run stopped by its time limit still leaves the report.  Every
    line is escaped: member names, agency names and XML samples come from the downloads."""
    line = log_safe(line)
    print(line, flush=True)
    if _report_path is not None:
        with _report_path.open("a", encoding="utf-8") as report:
            report.write(line + "\n")


def fetch(url: str, *, limit: int, method: str = "GET", accept: str = "*/*", destination: Path | None = None):
    """Return (status, final_url, headers, body_or_path, error); a file download resumes after a dropped connection."""
    if destination is None or method != "GET":
        return _fetch_once(url, limit=limit, method=method, accept=accept, destination=destination)
    result = _fetch_once(url, limit=limit, method=method, accept=accept, destination=destination)
    attempts = 0
    while (
        result[4]
        and any(marker in result[4] for marker in ("IncompleteRead", "timed out", "Connection", "oltre 600 s"))
        and attempts < 20
        and destination.exists()
        and destination.stat().st_size > 0
    ):
        attempts += 1
        offset = destination.stat().st_size
        emit(f"  resume {attempts} from byte {offset}")
        result = _fetch_once(url, limit=limit, method=method, accept=accept, destination=destination, offset=offset)
    return result


def _fetch_once(url: str, *, limit: int, method: str, accept: str, destination: Path | None, offset: int = 0):
    headers = {"User-Agent": USER_AGENT, "Accept": accept}
    if offset:
        headers["Range"] = f"bytes={offset}-"
    request = urllib.request.Request(url, method=method, headers=headers)
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            status = response.status
            final = response.geturl()
            headers = dict(response.headers.items())
            if method == "HEAD":
                return status, final, headers, b"", None
            if offset and status != 206:
                # No Range support: start again from the first byte.
                offset = 0
            total = offset
            chunks: list[bytes] = []
            sink = destination.open("ab" if offset else "wb") if destination else None
            try:
                while True:
                    chunk = response.read(64 * 1024)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > limit:
                        return status, final, headers, None, f"oltre limite {limit} bytes"
                    if sink:
                        sink.write(chunk)
                    else:
                        chunks.append(chunk)
                    if time.monotonic() - started > 600:
                        return status, final, headers, None, "oltre 600 s"
            finally:
                if sink:
                    sink.close()
            return status, final, headers, (destination if destination else b"".join(chunks)), None
    except urllib.error.HTTPError as error:
        body = b""
        try:
            body = error.read(4096)
        except Exception:  # noqa: BLE001 - diagnostic only
            pass
        return error.code, url, dict(error.headers.items()) if error.headers else {}, body, f"HTTP {error.code}"
    except Exception as error:  # noqa: BLE001 - every failure is reported, none is fatal
        return None, url, {}, None, f"{type(error).__name__}: {str(error)[:200]}"


def header_summary(headers: dict) -> str:
    keys = ("Content-Type", "Content-Length", "Last-Modified", "ETag", "Content-Disposition")
    return "; ".join(f"{key}={headers[key]}" for key in keys if key in headers)


def probe_page(url: str, *, excerpt: int = 1500) -> None:
    status, final, headers, body, error = fetch(url, limit=PAGE_LIMIT, accept="text/html,application/json,*/*")
    emit(f"PAGE {url} status={status} final={final} {header_summary(headers)} error={error}")
    if not isinstance(body, bytes):
        return
    text = body.decode("utf-8", errors="replace")
    links = sorted(set(re.findall(
        r"""(?:https?:)?//?[^\s"'<>()]+?(?:\.zip|\.gtfs|\.xml|\.json|gtfs[^\s"'<>()]*|netex[^\s"'<>()]*|/download[^\s"'<>()]*)""",
        text,
        re.I,
    )))
    for link in links[:200]:
        emit(f"  link {link}")
    licence = sorted(set(re.findall(r"(?i)(cc[- ]?by[- ]?(?:sa|nc|nd)?[- ]?\d\.\d|iodl[- ]?\d\.\d|odbl|cc0|creative commons[^<\n]{0,80}|licenz[^<\n]{0,160}|non commercial[^<\n]{0,120}|uso non commerciale[^<\n]{0,120})", text)))
    for item in licence[:40]:
        emit(f"  licence-text {item.strip()}")
    if excerpt:
        compact = re.sub(r"\s+", " ", re.sub(r"<script.*?</script>|<style.*?</style>", " ", text, flags=re.S | re.I))
        compact = re.sub(r"<[^>]+>", " ", compact)
        compact = re.sub(r"\s+", " ", compact)
        emit(f"  excerpt {compact[:excerpt]}")


def probe_nextdata(url: str, *, excerpt: int = 40000) -> None:
    """A Next.js page (the NAP catalogue): print its embedded __NEXT_DATA__ JSON, which holds the page's records."""
    status, final, headers, body, error = fetch(url, limit=PAGE_LIMIT, accept="text/html")
    emit(f"NEXTDATA {url} status={status} final={final} error={error}")
    if not isinstance(body, bytes):
        return
    text = body.decode("utf-8", errors="replace")
    match = re.search(r'<script id="__NEXT_DATA__" type="application/json"[^>]*>(.*?)</script>', text, re.S)
    if not match:
        emit("  no __NEXT_DATA__")
        return
    try:
        payload = json.loads(match.group(1))
    except json.JSONDecodeError as problem:
        emit(f"  invalid __NEXT_DATA__: {problem}")
        return
    props = payload.get("props", payload)
    page = props.get("pageProps", {}) if isinstance(props, dict) else {}
    results = page.get("searchResults") if isinstance(page, dict) else None
    if isinstance(results, list):
        emit(f"  searchResults={len(results)} keys={sorted(page)}")
        for item in results:
            if isinstance(item, dict):
                emit(f"  result {item.get('objectId')} parent={item.get('parentId')} {item.get('type')} | {item.get('name')}")
        rest = {key: value for key, value in page.items() if key not in {"searchResults", "types", "__namespaces"}}
        emit("  other " + json.dumps(rest, ensure_ascii=False)[:8000])
        return
    entity = page.get("entityData") if isinstance(page, dict) else None
    if isinstance(entity, dict):
        emit(f"  entity {entity.get('objectId')} parent={entity.get('parentId')} {entity.get('type')} | {entity.get('name')}")
        for item in [entity, *(entity.get("children") or [])]:
            if not isinstance(item, dict):
                continue
            attributes = {attribute.get("name"): attribute.get("value") for attribute in item.get("attributes") or [] if isinstance(attribute, dict)}
            def plain(value):  # noqa: ANN001 - catalogue values are enumerations, strings or blobs
                if isinstance(value, dict):
                    return value.get("value") or value.get("fileName") or value.get("it") or value.get("en")
                if isinstance(value, list):
                    return ",".join(str(plain(entry)) for entry in value)
                return value
            blob = attributes.get("checkedResource") or attributes.get("resource")
            emit(
                f"  child {item.get('objectId')} {item.get('type')} | {item.get('name')} | licence={plain(attributes.get('licence'))} "
                f"| format={plain(attributes.get('format'))} | modes={plain(attributes.get('transportationModes'))} "
                f"| owner={plain(attributes.get('dataOwnerName'))} | file={blob.get('fileName') if isinstance(blob, dict) else ''} "
                f"| uploaded={blob.get('uploadDate') if isinstance(blob, dict) else ''} | valid={plain(attributes.get('validationResult'))} "
                f"| url={plain(attributes.get('accessUrl')) or plain(attributes.get('url')) or ''}"
            )
        return
    emit("  " + json.dumps(props, ensure_ascii=False)[:excerpt])


NEXTJSON_CHUNK = 3000
NEXTJSON_LIMIT = 300_000


def probe_nextjson(url: str, *, fetch_page=None) -> None:
    """A Next.js page in full: its ``pageProps`` (translations dropped) as JSON in numbered lines of
    ``NEXTJSON_CHUNK`` characters, up to ``NEXTJSON_LIMIT``, so catalogue enumerations, filters and paging that
    ``nextdata`` truncates can be read from the log and joined back."""
    status, final, headers, body, error = (fetch_page or (lambda u: fetch(u, limit=PAGE_LIMIT, accept="text/html")))(url)
    emit(f"NEXTJSON {url} status={status} final={final} error={error}")
    if not isinstance(body, bytes):
        return
    match = re.search(r'<script id="__NEXT_DATA__" type="application/json"[^>]*>(.*?)</script>', body.decode("utf-8", errors="replace"), re.S)
    if not match:
        emit("  no __NEXT_DATA__")
        return
    try:
        payload = json.loads(match.group(1))
    except json.JSONDecodeError as problem:
        emit(f"  invalid __NEXT_DATA__: {problem}")
        return
    props = payload.get("props", payload) if isinstance(payload, dict) else payload
    page = props.get("pageProps", props) if isinstance(props, dict) else props
    if isinstance(page, dict):
        page = {key: value for key, value in page.items() if key != "__namespaces"}
    text = json.dumps({"query": payload.get("query") if isinstance(payload, dict) else None, "pageProps": page}, ensure_ascii=False)
    shown = text[:NEXTJSON_LIMIT]
    parts = [shown[start:start + NEXTJSON_CHUNK] for start in range(0, len(shown), NEXTJSON_CHUNK)] or [""]
    emit(f"  length={len(text)} parts={len(parts)}" + (" truncated" if len(text) > NEXTJSON_LIMIT else ""))
    for index, part in enumerate(parts, 1):
        emit(f"  NJ {index:03d} {part}")


def probe_text(url: str, *, excerpt: int = 60000) -> None:
    """Print the start of a text resource (a script of the catalogue, a CSV), whitespace collapsed."""
    status, final, headers, body, error = fetch(url, limit=PAGE_LIMIT, accept="*/*")
    emit(f"TEXT {url} status={status} final={final} {header_summary(headers)} error={error}")
    if isinstance(body, bytes):
        emit("  " + re.sub(r"\s+", " ", body.decode("utf-8", errors="replace"))[:excerpt])


def probe_json(url: str, *, excerpt: int = 6000) -> object | None:
    status, final, headers, body, error = fetch(url, limit=JSON_LIMIT, accept="application/json")
    emit(f"JSON {url} status={status} final={final} {header_summary(headers)} error={error}")
    if not isinstance(body, bytes):
        return None
    try:
        payload = json.loads(body.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        emit(f"  non-json {body[:excerpt].decode('utf-8', errors='replace')}")
        return None
    emit("  " + json.dumps(payload, ensure_ascii=False)[:excerpt])
    return payload


def probe_head(url: str) -> None:
    status, final, headers, _, error = fetch(url, limit=0, method="HEAD")
    if status in (405, 403, None):
        status, final, headers, _, error = fetch(url, limit=1024, accept="*/*")
    emit(f"HEAD {url} status={status} final={final} {header_summary(headers)} error={error}")


def read_csv_member(archive: zipfile.ZipFile, name: str, limit_rows: int | None = None) -> tuple[list[str], list[dict]]:
    candidates = [entry for entry in archive.namelist() if entry.rsplit("/", 1)[-1] == name]
    if not candidates:
        return [], []
    with archive.open(candidates[0]) as raw:
        text = io.TextIOWrapper(raw, encoding="utf-8-sig", errors="replace", newline="")
        reader = csv.DictReader(text)
        rows = []
        for index, row in enumerate(reader):
            if limit_rows is not None and index >= limit_rows:
                break
            rows.append({(key or "").strip(): (value or "").strip() for key, value in row.items() if key})
        return [name.strip() for name in (reader.fieldnames or [])], rows


def count_rows(archive: zipfile.ZipFile, name: str) -> int:
    candidates = [entry for entry in archive.namelist() if entry.rsplit("/", 1)[-1] == name]
    if not candidates:
        return 0
    with archive.open(candidates[0]) as raw:
        return max(sum(1 for _ in raw) - 1, 0)


def summarize_gtfs(path: Path) -> None:
    if not zipfile.is_zipfile(path):
        head = path.read_bytes()[:300]
        emit(f"  not-a-zip head={head!r}")
        return
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        emit("  members " + ", ".join(f"{info.filename}:{info.file_size}" for info in infos[:60]))
        _, agencies = read_csv_member(archive, "agency.txt")
        for agency in agencies[:40]:
            emit(f"  agency {agency.get('agency_id','')} | {agency.get('agency_name','')} | {agency.get('agency_url','')}")
        _, feed_info = read_csv_member(archive, "feed_info.txt")
        for row in feed_info[:3]:
            emit(f"  feed_info {json.dumps(row, ensure_ascii=False)}")
        for extra in ("attributions.txt", "license.txt", "LICENSE.txt", "licence.txt"):
            _, rows = read_csv_member(archive, extra, 5)
            for row in rows:
                emit(f"  {extra} {json.dumps(row, ensure_ascii=False)[:400]}")
        _, routes = read_csv_member(archive, "routes.txt")
        types = Counter(route.get("route_type", "") for route in routes)
        emit(f"  routes={len(routes)} route_types={dict(sorted(types.items()))}")
        listed = 0
        for route in routes:
            route_type = route.get("route_type", "")
            if (route_type in URBAN_TYPES or route_type in RAIL_TYPES) and listed < 160:
                listed += 1
                emit(
                    f"  route type={route_type} id={route.get('route_id','')} agency={route.get('agency_id','')} "
                    f"short={route.get('route_short_name','')} long={route.get('route_long_name','')[:80]}"
                )
        _, calendar = read_csv_member(archive, "calendar.txt")
        starts = sorted(row.get("start_date", "") for row in calendar if row.get("start_date"))
        ends = sorted(row.get("end_date", "") for row in calendar if row.get("end_date"))
        if starts:
            emit(f"  calendar rows={len(calendar)} start={starts[0]} end={ends[-1] if ends else '?'}")
        _, dates = read_csv_member(archive, "calendar_dates.txt")
        days = sorted(row.get("date", "") for row in dates if row.get("date"))
        if days:
            emit(f"  calendar_dates rows={len(dates)} first={days[0]} last={days[-1]}")
        emit(
            f"  counts stops={count_rows(archive, 'stops.txt')} trips={count_rows(archive, 'trips.txt')} "
            f"stop_times={count_rows(archive, 'stop_times.txt')}"
        )


def probe_gtfs(url: str, out: Path, *, keep: bool) -> None:
    name = hashlib.sha256(url.encode()).hexdigest()[:12] + ".zip"
    folder = out / "archives"
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / name
    status, final, headers, body, error = fetch(url, limit=ARCHIVE_LIMIT, destination=target)
    size = target.stat().st_size if target.exists() else 0
    digest = hashlib.sha256(target.read_bytes()).hexdigest() if target.exists() else ""
    emit(f"GTFS {url} status={status} final={final} size={size} sha256={digest} {header_summary(headers)} error={error}")
    if error is None and target.exists():
        try:
            summarize_gtfs(target)
        except Exception as problem:  # noqa: BLE001 - untrusted archive
            emit(f"  summary-error {type(problem).__name__}: {problem}")
    if not keep and target.exists():
        target.unlink()
    elif target.exists():
        emit(f"  kept {target.relative_to(out)}")


def probe_jsgrep(url: str) -> None:
    status, final, headers, body, error = fetch(url, limit=PAGE_LIMIT, accept="text/html")
    emit(f"JSGREP {url} status={status} final={final} error={error}")
    if not isinstance(body, bytes):
        return
    text = body.decode("utf-8", errors="replace")
    scripts = re.findall(r"""<script[^>]+src=["']([^"']+)["']""", text, re.I)
    bases = re.findall(r"""<base[^>]+href=["']([^"']+)["']""", text, re.I)
    base = urllib.parse.urljoin(final, bases[0]) if bases else final
    emit(f"  base={base} scripts={scripts}")
    patterns: Counter[str] = Counter()
    for script in scripts[:12]:
        script_url = urllib.parse.urljoin(base, script)
        s_status, _, _, s_body, s_error = fetch(script_url, limit=12 * 1024 * 1024)
        emit(f"  script {script_url} status={s_status} bytes={len(s_body) if isinstance(s_body, bytes) else 0} error={s_error}")
        if not isinstance(s_body, bytes):
            continue
        source = s_body.decode("utf-8", errors="replace")
        for match in re.findall(r"""["'`]([^"'`\s]{0,80}(?:api/v\d|/api/|catalog|Dataset|Asset|download|blob|search)[^"'`\s]{0,120})["'`]""", source):
            patterns[match] += 1
    for pattern, count in sorted(patterns.items()):
        emit(f"  js-string {count} {pattern}")


def probe_netexinfo(url: str, out: Path, *, keep: bool) -> None:
    """Download a NeTEx archive (ZIP of XML) and list members, frames, operators and counts of key elements."""
    name = hashlib.sha256(url.encode()).hexdigest()[:12] + ".netex"
    folder = out / "archives"
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / name
    status, final, headers, body, error = fetch(url, limit=ARCHIVE_LIMIT, destination=target)
    size = target.stat().st_size if target.exists() else 0
    digest = hashlib.sha256(target.read_bytes()).hexdigest() if target.exists() else ""
    emit(f"NETEX {url} status={status} final={final} size={size} sha256={digest} {header_summary(headers)} error={error}")
    if error is None and target.exists():
        try:
            summarize_netex(target)
        except Exception as problem:  # noqa: BLE001 - untrusted archive
            emit(f"  summary-error {type(problem).__name__}: {problem}")
    if not keep and target.exists():
        target.unlink()


def probe_netexgtfs(url: str, out: Path) -> None:
    """Download a NeTEx asset, convert it with tools/netex_to_gtfs.py and summarize the GTFS (route types, dates)."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import netex_to_gtfs

    folder = out / "archives"
    folder.mkdir(parents=True, exist_ok=True)
    name = hashlib.sha256(url.encode()).hexdigest()[:12]
    target = folder / f"{name}.netex"
    status, final, headers, body, error = fetch(url, limit=ARCHIVE_LIMIT, destination=target)
    size = target.stat().st_size if target.exists() else 0
    emit(f"NETEXGTFS {url} status={status} size={size} error={error}")
    if error is not None or not target.exists():
        return
    try:
        report = netex_to_gtfs.convert(target, folder / f"{name}.gtfs.zip", source_label=name)
    except Exception as problem:  # noqa: BLE001 - untrusted input, report and go on
        emit(f"  conversion-error {type(problem).__name__}: {problem}")
        return
    emit(f"  conversion {json.dumps(report, ensure_ascii=False, sort_keys=True)}")
    summarize_gtfs(folder / f"{name}.gtfs.zip")
    target.unlink()


def summarize_netex(path: Path) -> None:
    import xml.etree.ElementTree as ET

    # The converter's guard (tools/netex_to_gtfs.py): DOCTYPE/ENTITY refused at parser level, decompressed bytes capped.
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from netex_to_gtfs import GuardedStream

    def local(tag: str) -> str:
        return tag.rsplit("}", 1)[-1]

    sources: list[tuple[str, object]] = []
    with path.open("rb") as probe:
        magic = probe.read(2)
    if magic == b"\x1f\x8b":
        import gzip

        emit("  gzip stream")
        sources.append((path.name, lambda: gzip.open(path, "rb")))
    elif zipfile.is_zipfile(path):
        archive = zipfile.ZipFile(path)
        members = [info for info in archive.infolist() if not info.is_dir()]
        emit("  members " + ", ".join(f"{info.filename}:{info.file_size}" for info in members[:40]) + (" ..." if len(members) > 40 else ""))
        for info in members[:3]:
            if info.filename.lower().endswith(".xml"):
                sources.append((info.filename, lambda info=info: archive.open(info)))
    else:
        head = path.read_bytes()[:200]
        emit(f"  not-a-zip head={head!r}")
        sources.append((path.name, lambda: path.open("rb")))
    for name, opener in sources:
        counts: Counter[str] = Counter()
        samples: dict[str, str] = {}
        started = time.monotonic()
        with opener() as stream:  # type: ignore[operator]
            guarded = io.BufferedReader(GuardedStream(stream), 1 << 20)
            for event, element in ET.iterparse(guarded, events=("end",)):
                if time.monotonic() - started > NETEX_SUMMARY_SECONDS:
                    emit(f"  file {name} summary stopped after {NETEX_SUMMARY_SECONDS} s (partial counts)")
                    break
                tag = local(element.tag)
                counts[tag] += 1
                if tag in {"Operator", "Line", "StopPlace", "ServiceJourney", "DayType", "UicOperatingPeriod", "OperatingPeriod",
                           "DayTypeAssignment", "ScheduledStopPoint", "PassengerStopAssignment", "ServiceJourneyPattern",
                           "TimetabledPassingTime", "Authority", "ResourceFrame", "ServiceFrame", "TimetableFrame",
                           "ServiceCalendarFrame", "SiteFrame", "CompositeFrame", "Quay", "StopPointInJourneyPattern",
                           "TypeOfProductCategory", "DestinationDisplay", "OperatingDay", "Codespace"} and tag not in samples:
                    if tag in {"ServiceJourney", "ServiceJourneyPattern"}:
                        shallow = ET.Element(element.tag, element.attrib)
                        shallow.extend(item for item in element if local(item.tag) not in {"passingTimes", "pointsInSequence"})
                        samples[tag] = ET.tostring(shallow, encoding="unicode")[:1800]
                    else:
                        samples[tag] = ET.tostring(element, encoding="unicode")[:1800]
                if tag in {"ServiceJourney", "StopPlace", "Line", "DayType", "UicOperatingPeriod", "DayTypeAssignment",
                           "ScheduledStopPoint", "PassengerStopAssignment", "ServiceJourneyPattern", "Quay", "Operator"}:
                    element.clear()
        top = ", ".join(f"{tag}:{count}" for tag, count in counts.most_common(60))
        emit(f"  file {name} elements {top}")
        for tag, sample in samples.items():
            flat = re.sub(r"\s+", " ", sample)
            emit(f"  sample {tag} {flat}")


def probe_ckan(url: str, *, fetch_json=None) -> None:
    """A CKAN `package_search` URL: each dataset with its licence, publisher and resources (format and URL)."""
    status, headers, payload, error = (fetch_json or _fetch_json)(url)
    emit(f"CKAN {url} status={status} {header_summary(headers)} error={error}")
    result = payload.get("result") if isinstance(payload, dict) else None
    if not isinstance(result, dict):
        return
    emit(f"  count={result.get('count')}")
    for dataset in result.get("results") or []:
        if not isinstance(dataset, dict):
            continue
        organization = dataset.get("organization") if isinstance(dataset.get("organization"), dict) else {}
        emit(
            f"  dataset {dataset.get('name')} | {str(dataset.get('title', ''))[:120]} | licence={dataset.get('license_id')} "
            f"{dataset.get('license_title')} | org={organization.get('title', '')} | modified={dataset.get('metadata_modified')}"
        )
        for resource in (dataset.get("resources") or [])[:12]:
            if isinstance(resource, dict):
                emit(f"    resource {resource.get('format')} | {str(resource.get('name', ''))[:80]} | {resource.get('url')}")


# --- GTFS-Realtime: protobuf wire format, standard library only --------------------------------------------------


class WireError(ValueError):
    """The bytes are not a well-formed protobuf message."""


def read_varint(data: bytes, position: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        if position >= len(data):
            raise WireError("varint troncato")
        byte = data[position]
        position += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, position
        shift += 7
        if shift > 63:
            raise WireError("varint troppo lungo")


def iter_fields(data: bytes):
    """Yield (field, wire_type, value, start, end) for each field; value is an int or the bytes of a nested field."""
    position = 0
    while position < len(data):
        start = position
        key, position = read_varint(data, position)
        field, wire = key >> 3, key & 7
        if field == 0:
            raise WireError("campo 0")
        if wire == 0:
            value, position = read_varint(data, position)
        elif wire == 1:
            if position + 8 > len(data):
                raise WireError("fixed64 troncato")
            value = int.from_bytes(data[position:position + 8], "little")
            position += 8
        elif wire == 2:
            length, position = read_varint(data, position)
            if position + length > len(data):
                raise WireError("campo troncato")
            value = data[position:position + length]
            position += length
        elif wire == 5:
            if position + 4 > len(data):
                raise WireError("fixed32 troncato")
            value = int.from_bytes(data[position:position + 4], "little")
            position += 4
        else:
            raise WireError(f"wire type {wire} non supportato")
        yield field, wire, value, start, position


def _signed32(value: int) -> int:
    value &= 0xFFFFFFFFFFFFFFFF
    return value - (1 << 64) if value >= 1 << 63 else value


def _text(value: object) -> str:
    return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else ""


def decode_trip_descriptor(data: bytes) -> dict:
    trip: dict = {}
    for field, _, value, _, _ in iter_fields(data):
        if field == 1:
            trip["trip_id"] = _text(value)
        elif field == 2:
            trip["start_time"] = _text(value)
        elif field == 3:
            trip["start_date"] = _text(value)
        elif field == 4:
            trip["schedule_relationship"] = value
        elif field == 5:
            trip["route_id"] = _text(value)
        elif field == 6:
            trip["direction_id"] = value
    return trip


def decode_stop_time_event(data: bytes) -> dict:
    event: dict = {}
    for field, _, value, _, _ in iter_fields(data):
        if field == 1:
            event["delay"] = _signed32(value)
        elif field == 2:
            event["time"] = _signed32(value)
        elif field == 3:
            event["uncertainty"] = _signed32(value)
    return event


def decode_stop_time_update(data: bytes) -> dict:
    update: dict = {}
    for field, _, value, _, _ in iter_fields(data):
        if field == 1:
            update["stop_sequence"] = value
        elif field == 2:
            update["arrival"] = decode_stop_time_event(value)
        elif field == 3:
            update["departure"] = decode_stop_time_event(value)
        elif field == 4:
            update["stop_id"] = _text(value)
        elif field == 5:
            update["schedule_relationship"] = value
    return update


def decode_translated(data: bytes) -> list[tuple[str, str]]:
    texts = []
    for field, _, value, _, _ in iter_fields(data):
        if field == 1 and isinstance(value, bytes):
            text = language = ""
            for inner, _, item, _, _ in iter_fields(value):
                if inner == 1:
                    text = _text(item)
                elif inner == 2:
                    language = _text(item)
            texts.append((language, text))
    return texts


def decode_entity(data: bytes) -> dict:
    entity: dict = {"kind": "empty"}
    for field, _, value, _, _ in iter_fields(data):
        if field == 1:
            entity["id"] = _text(value)
        elif field == 2:
            entity["is_deleted"] = bool(value)
        elif field == 3:
            entity["kind"] = "trip_update"
            trip_update: dict = {"stop_time_updates": []}
            for inner, _, item, _, _ in iter_fields(value):
                if inner == 1:
                    trip_update["trip"] = decode_trip_descriptor(item)
                elif inner == 2:
                    trip_update["stop_time_updates"].append(decode_stop_time_update(item))
                elif inner == 4:
                    trip_update["timestamp"] = item
                elif inner == 5:
                    trip_update["delay"] = _signed32(item)
            entity["trip_update"] = trip_update
        elif field == 4:
            entity["kind"] = "vehicle"
            vehicle: dict = {}
            for inner, _, item, _, _ in iter_fields(value):
                if inner == 1:
                    vehicle["trip"] = decode_trip_descriptor(item)
                elif inner == 2:
                    vehicle["position"] = True
                elif inner == 3:
                    vehicle["current_stop_sequence"] = item
                elif inner == 5:
                    vehicle["timestamp"] = item
                elif inner == 7:
                    vehicle["stop_id"] = _text(item)
            entity["vehicle"] = vehicle
        elif field == 5:
            entity["kind"] = "alert"
            alert: dict = {"informed": [], "header": [], "periods": 0}
            for inner, _, item, _, _ in iter_fields(value):
                if inner == 1:
                    alert["periods"] += 1
                elif inner == 5:
                    selector: dict = {}
                    for key, _, part, _, _ in iter_fields(item):
                        if key == 1:
                            selector["agency_id"] = _text(part)
                        elif key == 2:
                            selector["route_id"] = _text(part)
                        elif key == 3:
                            selector["route_type"] = part
                        elif key == 4:
                            selector["trip"] = decode_trip_descriptor(part)
                        elif key == 5:
                            selector["stop_id"] = _text(part)
                    alert["informed"].append(selector)
                elif inner == 6:
                    alert["cause"] = item
                elif inner == 7:
                    alert["effect"] = item
                elif inner == 10:
                    alert["header"] = decode_translated(item)
            entity["alert"] = alert
    return entity


def decode_feed(data: bytes) -> dict:
    """A FeedMessage as dictionaries; each entity keeps its raw field bytes so that a sample can be re-assembled."""
    feed: dict = {"header": {}, "header_raw": b"", "entities": []}
    for field, _, value, start, end in iter_fields(data):
        if field == 1 and isinstance(value, bytes):
            feed["header_raw"] = data[start:end]
            for inner, _, item, _, _ in iter_fields(value):
                if inner == 1:
                    feed["header"]["gtfs_realtime_version"] = _text(item)
                elif inner == 2:
                    feed["header"]["incrementality"] = item
                elif inner == 3:
                    feed["header"]["timestamp"] = item
        elif field == 2 and isinstance(value, bytes):
            entity = decode_entity(value)
            entity["raw"] = data[start:end]
            feed["entities"].append(entity)
    if "gtfs_realtime_version" not in feed["header"]:
        raise WireError("FeedHeader senza gtfs_realtime_version")
    return feed


TRIP_RELATIONSHIPS = {0: "SCHEDULED", 1: "ADDED", 2: "UNSCHEDULED", 3: "CANCELED", 5: "REPLACEMENT", 6: "DUPLICATED", 7: "DELETED", 8: "NEW"}
STOP_RELATIONSHIPS = {0: "SCHEDULED", 1: "SKIPPED", 2: "NO_DATA", 3: "UNSCHEDULED"}


class StaticReference:
    """Routes, trips and stops of one static GTFS, to match realtime identifiers against."""

    def __init__(self, label: str, routes: dict, trips: dict, stops: set):
        self.label = label
        self.routes = routes  # route_id -> (route_type, short name)
        self.trips = trips  # trip_id -> route_id
        self.stops = stops

    @classmethod
    def from_zip(cls, label: str, path: Path) -> "StaticReference":
        with zipfile.ZipFile(path) as archive:
            _, routes = read_csv_member(archive, "routes.txt")
            _, trips = read_csv_member(archive, "trips.txt")
            _, stops = read_csv_member(archive, "stops.txt")
        return cls(
            label,
            {row.get("route_id", ""): (row.get("route_type", ""), row.get("route_short_name", "")) for row in routes},
            {row.get("trip_id", ""): row.get("route_id", "") for row in trips},
            {row.get("stop_id", "") for row in stops},
        )


_static_references: list[StaticReference] = []
_city_packs_loaded = False


def load_city_packs(root: Path = ROOT) -> None:
    global _city_packs_loaded
    if _city_packs_loaded:
        return
    _city_packs_loaded = True
    for pattern in CITY_PACK_GLOBS:
        for path in sorted(root.glob(pattern)):
            try:
                _static_references.append(StaticReference.from_zip(f"pack:{path.stem}", path))
            except Exception as problem:  # noqa: BLE001 - a broken pack is reported, the probe goes on
                emit(f"  pack-error {path.name} {type(problem).__name__}: {problem}")


def probe_rtstatic(url: str, out: Path) -> None:
    folder = out / "archives"
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / (hashlib.sha256(url.encode()).hexdigest()[:12] + ".static.zip")
    status, final, headers, _, error = fetch(url, limit=ARCHIVE_LIMIT, destination=target)
    size = target.stat().st_size if target.exists() else 0
    emit(f"RTSTATIC {url} status={status} final={final} size={size} {header_summary(headers)} error={error}")
    if error is not None or not target.exists():
        return
    try:
        reference = StaticReference.from_zip(f"static:{url}", target)
    except Exception as problem:  # noqa: BLE001 - untrusted archive
        emit(f"  summary-error {type(problem).__name__}: {problem}")
        return
    finally:
        target.unlink()
    types = Counter(route_type for route_type, _ in reference.routes.values())
    emit(f"  routes={len(reference.routes)} trips={len(reference.trips)} stops={len(reference.stops)} route_types={dict(sorted(types.items()))}")
    _static_references.append(reference)


def realtime_route(entity: dict, reference: StaticReference | None) -> str:
    body = entity.get("trip_update") or entity.get("vehicle") or {}
    trip = body.get("trip", {})
    route = trip.get("route_id", "")
    if not route and reference is not None:
        route = reference.trips.get(trip.get("trip_id", ""), "")
    return route


def summarize_realtime(feed: dict, now: float, references: list[StaticReference]) -> list[str]:
    lines = []
    header = feed["header"]
    stamp = header.get("timestamp")
    age = f"{int(now - stamp)}s" if stamp else "?"
    lines.append(f"  header version={header.get('gtfs_realtime_version')} incrementality={header.get('incrementality', 0)} timestamp={stamp} age={age}")
    entities = feed["entities"]
    kinds = Counter(entity["kind"] for entity in entities)
    lines.append(f"  entities={len(entities)} kinds={dict(sorted(kinds.items()))} deleted={sum(1 for e in entities if e.get('is_deleted'))}")
    updates = [entity["trip_update"] for entity in entities if entity["kind"] == "trip_update"]
    if updates:
        relationships = Counter(TRIP_RELATIONSHIPS.get(u.get("trip", {}).get("schedule_relationship", 0), "?") for u in updates)
        stops = [stop for update in updates for stop in update["stop_time_updates"]]
        stop_relationships = Counter(STOP_RELATIONSHIPS.get(stop.get("schedule_relationship", 0), "?") for stop in stops)
        events = [stop.get(kind) for stop in stops for kind in ("arrival", "departure") if stop.get(kind)]
        dates = Counter(update.get("trip", {}).get("start_date", "") for update in updates)
        stamps = [update["timestamp"] for update in updates if update.get("timestamp")]
        lines.append(
            f"  trip_updates={len(updates)} trips={len({u.get('trip', {}).get('trip_id', '') for u in updates})} "
            f"relationships={dict(relationships)} stop_time_updates={len(stops)} stop_relationships={dict(stop_relationships)} "
            f"events_with_delay={sum(1 for e in events if 'delay' in e)} events_with_time={sum(1 for e in events if 'time' in e)} "
            f"with_stop_id={sum(1 for s in stops if s.get('stop_id'))} with_sequence={sum(1 for s in stops if 'stop_sequence' in s)} "
            f"trip_delay={sum(1 for u in updates if 'delay' in u)}"
        )
        lines.append(f"  start_dates={dict(dates.most_common(6))} update_age_min={int(now - max(stamps)) if stamps else '?'}s update_age_max={int(now - min(stamps)) if stamps else '?'}s")
        routes = Counter(u.get("trip", {}).get("route_id", "") for u in updates)
        lines.append(f"  descriptor_route_ids={len(routes)} top={dict(routes.most_common(40))}")
    vehicles = [entity["vehicle"] for entity in entities if entity["kind"] == "vehicle"]
    if vehicles:
        stamps = [vehicle["timestamp"] for vehicle in vehicles if vehicle.get("timestamp")]
        routes = Counter(v.get("trip", {}).get("route_id", "") for v in vehicles)
        lines.append(
            f"  vehicles={len(vehicles)} with_position={sum(1 for v in vehicles if v.get('position'))} with_trip={sum(1 for v in vehicles if v.get('trip', {}).get('trip_id'))} "
            f"age_min={int(now - max(stamps)) if stamps else '?'}s descriptor_route_ids={len(routes)} top={dict(routes.most_common(40))}"
        )
    alerts = [entity["alert"] for entity in entities if entity["kind"] == "alert"]
    if alerts:
        informed = Counter(selector.get("route_id", "") for alert in alerts for selector in alert["informed"])
        effects = Counter(alert.get("effect", 0) for alert in alerts)
        lines.append(f"  alerts={len(alerts)} effects={dict(effects)} informed_routes={dict(informed.most_common(40))}")
        for alert in alerts[:6]:
            text = next((t for language, t in alert["header"] if language in ("it", "")), alert["header"][0][1] if alert["header"] else "")
            lines.append(f"  alert-header {text[:160]!r} routes={[s.get('route_id') for s in alert['informed'] if s.get('route_id')][:8]}")
    for reference in references:
        trip_ids = {(e.get("trip_update") or e.get("vehicle") or {}).get("trip", {}).get("trip_id", "") for e in entities if e["kind"] in ("trip_update", "vehicle")}
        matched_trips = {trip for trip in trip_ids if trip in reference.trips}
        route_ids = [realtime_route(e, reference) for e in entities if e["kind"] in ("trip_update", "vehicle")]
        matched_routes = Counter(route for route in route_ids if route in reference.routes)
        alert_routes = {s.get("route_id") for alert in alerts for s in alert["informed"] if s.get("route_id") in reference.routes}
        if not matched_trips and not matched_routes and not alert_routes:
            continue
        stop_ids = [stop.get("stop_id") for update in updates for stop in update["stop_time_updates"] if stop.get("stop_id")]
        matched_stops = sum(1 for stop in stop_ids if stop in reference.stops)
        types = Counter(reference.routes[route][0] for route in matched_routes.elements())
        lines.append(
            f"  match {reference.label} trips={len(matched_trips)}/{len(trip_ids)} stop_ids={matched_stops}/{len(stop_ids)} "
            f"route_types={dict(sorted(types.items()))} alert_routes={sorted(alert_routes)[:20]}"
        )
        urban = sorted(
            ((route, count) for route, count in matched_routes.items() if reference.routes[route][0] in URBAN_TYPES - {"3"}),
            key=lambda item: (-item[1], item[0]),
        )
        for route, count in urban[:40]:
            route_type, short = reference.routes[route]
            lines.append(f"    route id={route} short={short} type={route_type} entities={count}")
    return lines


def realtime_sample(feed: dict, reference: StaticReference, per_route: int = 2, limit: int = 12) -> bytes:
    """Header plus a few entities of [reference]'s routes, as a valid FeedMessage: the raw fields are concatenated."""
    chosen: list[bytes] = []
    seen: Counter[str] = Counter()
    for entity in feed["entities"]:
        if len(chosen) >= limit:
            break
        if entity["kind"] == "alert":
            routes = {s.get("route_id") for s in entity["alert"]["informed"]}
            route = next((r for r in routes if r in reference.routes), "")
        else:
            route = realtime_route(entity, reference)
            trip = (entity.get("trip_update") or entity.get("vehicle") or {}).get("trip", {}).get("trip_id", "")
            if trip not in reference.trips and route not in reference.routes:
                continue
        if not route or route not in reference.routes:
            continue
        key = f"{entity['kind']}:{route}"
        if seen[key] >= per_route:
            continue
        seen[key] += 1
        chosen.append(entity["raw"])
    return feed["header_raw"] + b"".join(chosen) if chosen else b""


def describe_entity(entity: dict, limit: int = 6) -> str:
    if entity["kind"] == "trip_update":
        update = entity["trip_update"]
        stops = "; ".join(
            f"{s.get('stop_sequence', '')}/{s.get('stop_id', '')}/{STOP_RELATIONSHIPS.get(s.get('schedule_relationship', 0), '?')}"
            f" arr={s.get('arrival', {})} dep={s.get('departure', {})}" for s in update["stop_time_updates"][:limit]
        )
        return f"trip_update id={entity.get('id')} trip={update.get('trip', {})} ts={update.get('timestamp')} stus={len(update['stop_time_updates'])} [{stops}]"
    if entity["kind"] == "vehicle":
        return f"vehicle id={entity.get('id')} {entity['vehicle']}"
    if entity["kind"] == "alert":
        alert = entity["alert"]
        return f"alert id={entity.get('id')} effect={alert.get('effect')} informed={alert['informed'][:6]} header={alert['header'][:2]}"
    return f"entity id={entity.get('id')} kind={entity['kind']}"


def probe_gtfsrt(url: str, *, now: float | None = None) -> None:
    status, final, headers, body, error = fetch(url, limit=REALTIME_LIMIT, accept="application/x-protobuf, application/octet-stream")
    size = len(body) if isinstance(body, bytes) else 0
    emit(f"GTFSRT {url} status={status} final={final} size={size} {header_summary(headers)} error={error}")
    if error is not None or not isinstance(body, bytes):
        if isinstance(body, bytes) and body:
            emit(f"  body {body[:300]!r}")
        return
    try:
        feed = decode_feed(body)
    except WireError as problem:
        emit(f"  not-gtfs-rt {problem} head={body[:120]!r}")
        return
    load_city_packs()
    now = time.time() if now is None else now
    for line in summarize_realtime(feed, now, _static_references):
        emit(line)
    best = None
    best_score = 0
    for reference in _static_references:
        if not reference.label.startswith("pack:"):
            continue
        score = sum(
            1 for e in feed["entities"]
            if e["kind"] in ("trip_update", "vehicle") and (e.get("trip_update") or e.get("vehicle") or {}).get("trip", {}).get("trip_id", "") in reference.trips
        )
        if score > best_score:
            best, best_score = reference, score
    if best is None:
        return
    sample = realtime_sample(feed, best)
    if sample:
        sample_feed = decode_feed(sample)
        for entity in sample_feed["entities"]:
            emit(f"  sample {describe_entity(entity)}")
        emit(f"RTSAMPLE {best.label} {url} bytes={len(sample)} {base64.b64encode(sample).decode('ascii')}")


# --- GBFS ---------------------------------------------------------------------------------------------------------


def distance_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((phi2 - phi1) / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6_371_000.0 * math.asin(min(1.0, math.sqrt(a)))


class StationGrid:
    """Catalogue stations bucketed in cells of 0.01 degrees, to find those near a point without a full scan."""

    def __init__(self, stations: list[tuple[str, float, float]]):
        self.cells: dict[tuple[int, int], list[tuple[str, float, float]]] = {}
        for station in stations:
            self.cells.setdefault((int(math.floor(station[1] * 100)), int(math.floor(station[2] * 100))), []).append(station)

    @classmethod
    def load(cls, root: Path = ROOT) -> "StationGrid":
        try:
            payload = json.loads((root / STATION_CATALOGUE).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return cls([])
        stations = []
        for row in payload.get("stations", []):
            lat, lon = row.get("latitude"), row.get("longitude")
            if isinstance(lat, (int, float)) and isinstance(lon, (int, float)):
                stations.append((str(row.get("name", "")), float(lat), float(lon)))
        return cls(stations)

    def near(self, lat: float, lon: float, meters: float = NEAR_STATION_METERS) -> list[tuple[str, float]]:
        row, column = int(math.floor(lat * 100)), int(math.floor(lon * 100))
        found = []
        for dr in (-1, 0, 1):
            for dc in (-1, 0, 1):
                for name, s_lat, s_lon in self.cells.get((row + dr, column + dc), []):
                    distance = distance_meters(lat, lon, s_lat, s_lon)
                    if distance <= meters:
                        found.append((name, distance))
        return found


_station_grid: StationGrid | None = None


def station_grid() -> StationGrid:
    global _station_grid
    if _station_grid is None:
        _station_grid = StationGrid.load()
    return _station_grid


def gbfs_feed_urls(payload: dict) -> tuple[str, dict[str, str]]:
    """The version and the {name: url} map of a gbfs.json: v3 lists `data.feeds`, v1/v2 one list per language."""
    version = str(payload.get("version", "1.0"))
    data = payload.get("data")
    feeds: list = []
    if isinstance(data, dict) and isinstance(data.get("feeds"), list):
        feeds = data["feeds"]
    elif isinstance(data, dict):
        languages = [key for key in data if isinstance(data[key], dict)]
        for language in ("it", "en", *languages):
            if language in data and isinstance(data[language], dict) and isinstance(data[language].get("feeds"), list):
                feeds = data[language]["feeds"]
                break
    urls = {}
    for feed in feeds:
        if isinstance(feed, dict) and isinstance(feed.get("name"), str) and isinstance(feed.get("url"), str):
            urls[feed["name"]] = feed["url"]
    return version, urls


def gbfs_list(payload: object, key: str) -> list:
    data = payload.get("data") if isinstance(payload, dict) else None
    items = data.get(key) if isinstance(data, dict) else None
    return [item for item in items if isinstance(item, dict)] if isinstance(items, list) else []


def gbfs_coordinates(item: dict) -> tuple[float, float] | None:
    lat, lon = item.get("lat"), item.get("lon")
    if isinstance(lat, (int, float)) and isinstance(lon, (int, float)) and -90 <= lat <= 90 and -180 <= lon <= 180:
        return float(lat), float(lon)
    return None


def gbfs_name(item: dict) -> str:
    name = item.get("name")
    if isinstance(name, list):  # GBFS 3.0: localized strings
        return next((str(entry.get("text", "")) for entry in name if isinstance(entry, dict)), "")
    return str(name or "")


def summarize_near_catalogue(label: str, points: list[tuple[str, float, float]], grid: StationGrid) -> list[str]:
    stations: dict[str, float] = {}
    near_points = 0
    for _, lat, lon in points:
        found = grid.near(lat, lon)
        if found:
            near_points += 1
        for name, distance in found:
            stations[name] = min(distance, stations.get(name, distance))
    names = ", ".join(f"{name} {int(distance)}m" for name, distance in sorted(stations.items(), key=lambda item: item[1])[:30])
    return [f"  near-catalogue {label} points={near_points}/{len(points)} railway_stations={len(stations)} [{names}]"]


def probe_gbfs(url: str, *, fetch_json=None, grid: StationGrid | None = None) -> None:
    fetch_json = fetch_json or _fetch_json
    grid = grid or station_grid()
    status, headers, payload, error = fetch_json(url)
    emit(f"GBFS {url} status={status} {header_summary(headers)} error={error}")
    if not isinstance(payload, dict):
        return
    version, urls = gbfs_feed_urls(payload)
    emit(f"  version={version} ttl={payload.get('ttl')} last_updated={payload.get('last_updated')} feeds={sorted(urls)}")
    for name in ("system_information", "vehicle_types", "station_information", "station_status", "free_bike_status", "vehicle_status", "system_regions"):
        if name not in urls:
            continue
        f_status, f_headers, document, f_error = fetch_json(urls[name])
        age = ""
        if isinstance(document, dict) and isinstance(document.get("last_updated"), (int, float)):
            age = f" age={int(time.time() - document['last_updated'])}s"
        elif isinstance(document, dict) and isinstance(document.get("last_updated"), str):
            age = f" last_updated={document['last_updated']}"
        emit(f"  feed {name} status={f_status} ttl={document.get('ttl') if isinstance(document, dict) else None}{age} error={f_error}")
        if not isinstance(document, dict):
            continue
        if name == "system_information":
            emit("    " + json.dumps(document.get("data"), ensure_ascii=False)[:3000])
        elif name == "vehicle_types":
            for item in gbfs_list(document, "vehicle_types")[:12]:
                emit(
                    f"    type {item.get('vehicle_type_id')} form={item.get('form_factor')} propulsion={item.get('propulsion_type')} "
                    f"name={gbfs_name(item)!r} max_range={item.get('max_range_meters')}"
                )
        elif name == "station_information":
            stations = gbfs_list(document, "stations")
            points = [(str(s.get("station_id", "")), *c) for s in stations if (c := gbfs_coordinates(s))]
            emit(f"    stations={len(stations)} with_coordinates={len(points)} with_capacity={sum(1 for s in stations if 'capacity' in s)}")
            for item in stations[:2]:
                emit("    sample " + json.dumps(item, ensure_ascii=False)[:600])
            for line in summarize_near_catalogue("stations", points, grid):
                emit("  " + line)
        elif name == "station_status":
            stations = gbfs_list(document, "stations")
            emit(
                f"    stations={len(stations)} bikes={sum(int(s.get('num_bikes_available') or s.get('num_vehicles_available') or 0) for s in stations)} "
                f"with_docks={sum(1 for s in stations if 'num_docks_available' in s)} renting={sum(1 for s in stations if s.get('is_renting') in (True, 1))} "
                f"with_types={sum(1 for s in stations if s.get('vehicle_types_available'))}"
            )
            for item in stations[:2]:
                emit("    sample " + json.dumps(item, ensure_ascii=False)[:600])
        else:
            key = "bikes" if name == "free_bike_status" else "vehicles"
            vehicles = gbfs_list(document, key)
            points = [("", *c) for v in vehicles if (c := gbfs_coordinates(v))]
            types = Counter(str(v.get("vehicle_type_id", "")) for v in vehicles)
            emit(
                f"    {key}={len(vehicles)} with_coordinates={len(points)} types={dict(types.most_common(8))} "
                f"with_range={sum(1 for v in vehicles if 'current_range_meters' in v)} with_fuel={sum(1 for v in vehicles if 'current_fuel_percent' in v)} "
                f"disabled={sum(1 for v in vehicles if v.get('is_disabled') in (True, 1))} reserved={sum(1 for v in vehicles if v.get('is_reserved') in (True, 1))}"
            )
            for item in vehicles[:2]:
                sample = {k: v for k, v in item.items() if k not in ("bike_id", "vehicle_id", "rental_uris")}
                emit("    sample " + json.dumps(sample, ensure_ascii=False)[:600])
            for line in summarize_near_catalogue(key, points, grid):
                emit("  " + line)


def _fetch_json(url: str):
    status, _, headers, body, error = fetch(url, limit=JSON_LIMIT, accept="application/json")
    if not isinstance(body, bytes) or error is not None:
        return status, headers, None, error
    try:
        return status, headers, json.loads(body.decode("utf-8-sig")), None
    except (UnicodeDecodeError, json.JSONDecodeError) as problem:
        return status, headers, None, f"non-json {type(problem).__name__}"


def scan_mdb(out: Path) -> None:
    url = "https://files.mobilitydatabase.org/feeds_v2.csv"
    status, final, headers, body, error = fetch(url, limit=64 * 1024 * 1024, accept="text/csv")
    emit(f"MDB {url} status={status} error={error}")
    if not isinstance(body, bytes):
        return
    (out / "mdb_feeds_v2.csv").write_bytes(body)
    reader = csv.DictReader(io.StringIO(body.decode("utf-8", errors="replace")))
    emit(f"  columns {reader.fieldnames}")
    for row in reader:
        if row.get("location.country_code") != "IT":
            continue
        emit(
            "  mdb " + " | ".join(
                row.get(key, "") for key in (
                    "id", "data_type", "status", "is_official", "provider", "name", "location.subdivision_name",
                    "location.municipality", "urls.direct_download", "urls.latest", "urls.license", "features",
                    "redirect.id", "urls.authentication_type",
                )
            )
        )


def scan_atlas(out: Path) -> None:
    tree_url = "https://api.github.com/repos/transitland/transitland-atlas/git/trees/main?recursive=1"
    payload = None
    status, _, _, body, error = fetch(tree_url, limit=JSON_LIMIT, accept="application/vnd.github+json")
    emit(f"ATLAS {tree_url} status={status} error={error}")
    if isinstance(body, bytes):
        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            payload = None
    if not isinstance(payload, dict):
        return
    paths = [item["path"] for item in payload.get("tree", []) if str(item.get("path", "")).startswith("feeds/")]
    italian = [p for p in paths if re.search(r"\.it\.dmfr|italia|italy|trenitalia|cotral|tper|busitalia|trasport|gtt|atac|actv|anm|amt|arst|ferrovie|fal|ferrotram|circumetnea|regione|comune|provincia", p, re.I)]
    emit(f"  feeds={len(paths)} italian-candidates={len(italian)}")
    for path in italian[:250]:
        raw = f"https://raw.githubusercontent.com/transitland/transitland-atlas/main/{path}"
        r_status, _, _, r_body, r_error = fetch(raw, limit=4 * 1024 * 1024, accept="application/json")
        if not isinstance(r_body, bytes):
            emit(f"  atlas {path} status={r_status} error={r_error}")
            continue
        try:
            document = json.loads(r_body)
        except json.JSONDecodeError:
            continue
        for feed in document.get("feeds", []):
            urls = feed.get("urls", {})
            licence = feed.get("license", {})
            operators = ", ".join(op.get("name", "") for op in feed.get("operators", [])[:6])
            emit(
                f"  atlas {path} | {feed.get('id')} | {feed.get('spec')} | {urls.get('static_current', '')} | "
                f"{json.dumps(licence, ensure_ascii=False)} | {operators}"
            )


def run(tokens: list[str], out: Path, keep: bool) -> None:
    for token in tokens:
        token = token.strip()
        if not token:
            continue
        kind, _, url = token.partition(":")
        if token == "mdb":
            scan_mdb(out)
        elif token == "atlas":
            scan_atlas(out)
        elif kind in {"page", "json", "gtfs", "head", "jsgrep", "netexinfo", "nextdata", "nextjson", "text", "netexgtfs", "gtfsrt", "rtstatic", "gbfs", "ckan"} and url.startswith(("http://", "https://")):
            {
                "gtfsrt": lambda: probe_gtfsrt(url),
                "rtstatic": lambda: probe_rtstatic(url, out),
                "gbfs": lambda: probe_gbfs(url),
                "ckan": lambda: probe_ckan(url),
                "netexgtfs": lambda: probe_netexgtfs(url, out),
                "text": lambda: probe_text(url),
                "nextdata": lambda: probe_nextdata(url),
                "nextjson": lambda: probe_nextjson(url),
                "page": lambda: probe_page(url),
                "json": lambda: probe_json(url),
                "gtfs": lambda: probe_gtfs(url, out, keep=keep),
                "head": lambda: probe_head(url),
                "jsgrep": lambda: probe_jsgrep(url),
                "netexinfo": lambda: probe_netexinfo(url, out, keep=keep),
            }[kind]()
        else:
            emit(f"SKIP token non riconosciuto: {token!r}")
        emit()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--plan", type=Path, help="file di token (uno per riga, # commenti)")
    parser.add_argument("--keep", action="store_true", help="conserva gli archivi scaricati in OUT/archives")
    parser.add_argument("--tokens-env", help="variabile d'ambiente con token separati da spazi (input del workflow)")
    parser.add_argument("tokens", nargs="*")
    args = parser.parse_args(argv)
    args.out.mkdir(parents=True, exist_ok=True)
    tokens = list(args.tokens)
    if args.tokens_env:
        tokens += os.environ.get(args.tokens_env, "").split()
    if args.plan:
        tokens += [
            line.strip() for line in args.plan.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        ]
    if not tokens:
        tokens = list(DEFAULT_PROBES)
    global _report_path
    _report_path = args.out / "report.txt"
    _report_path.write_text("", encoding="utf-8")
    run(tokens, args.out, args.keep)
    return 0


if __name__ == "__main__":
    sys.exit(main())
