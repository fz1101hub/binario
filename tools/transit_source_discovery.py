#!/usr/bin/env python3
"""Read-only discovery of public transit timetable sources (GTFS, NeTEx) for Binario.

Run on a CI runner (the development container cannot reach most open-data hosts), always with
``python3 -I`` from the repository root.  Everything fetched is *untrusted data*: it is written to
``--out`` and parsed only by this file (standard library); nothing downloaded is executed.

Probes are tokens ``KIND:URL`` (``page``, ``json``, ``gtfs``, ``head``, ``jsgrep``, ``netexinfo``)
plus the named catalogue scans ``mdb`` (Mobility Database catalogue, Italian rows) and ``atlas``
(Transitland Atlas DMFR records for Italy).  The report is printed and saved as ``report.txt``.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
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

URBAN_TYPES = {"0", "1", "5", "6", "7", "12"} | {str(v) for v in range(400, 1800)}
RAIL_TYPES = {"2"} | {str(v) for v in range(100, 200)}

DEFAULT_PROBES = [
    "mdb",
    "atlas",
]

_report_path: Path | None = None


def emit(line: str = "") -> None:
    """Print and append to the report at once, so a run stopped by its time limit still leaves the report."""
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
                return status, final, headers, None, f"resume non supportato (HTTP {status})"
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
    emit("  " + json.dumps(payload.get("props", payload), ensure_ascii=False)[:excerpt])


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


def summarize_netex(path: Path) -> None:
    import xml.etree.ElementTree as ET

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
            for event, element in ET.iterparse(stream, events=("end",)):
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
                    samples[tag] = ET.tostring(element, encoding="unicode")[:1800]
                if tag in {"ServiceJourney", "StopPlace", "Line", "DayType", "UicOperatingPeriod", "DayTypeAssignment",
                           "ScheduledStopPoint", "PassengerStopAssignment", "ServiceJourneyPattern", "Quay", "Operator"}:
                    element.clear()
        top = ", ".join(f"{tag}:{count}" for tag, count in counts.most_common(60))
        emit(f"  file {name} elements {top}")
        for tag, sample in samples.items():
            flat = re.sub(r"\s+", " ", sample)
            emit(f"  sample {tag} {flat}")


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
        elif kind in {"page", "json", "gtfs", "head", "jsgrep", "netexinfo", "nextdata"} and url.startswith(("http://", "https://")):
            {
                "nextdata": lambda: probe_nextdata(url),
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
