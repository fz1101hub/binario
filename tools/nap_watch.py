#!/usr/bin/env python3
"""Weekly watch of the rail operators on the Italian National Access Point (NAP, CCISS catalogue).

Binario's punctuality statistics are computed from ViaggiaTreno because no operator publishes delays, cancellations
or observed times as open data: on the NAP Trenitalia and Italo publish only their NeTEx timetables ("NeTEx Livello
1/2").  This tool reads, once a week from the ``freshness-watch`` job of ``.github/workflows/gtfs-assets-ci.yml``:

- the catalogue page of each organisation marked ``watch`` in ``tools/nap_watch_baseline.json`` (Trenitalia
  1077484, Italo 1813930): ``.../catalog/Organisation/<id>``, whose Next.js ``__NEXT_DATA__`` holds the organisation
  and its datasets (``props.pageProps.entityData`` and its ``children``);
- the organisation list in both orders (``.../catalog/Organisation`` and ``?orderBy=NAME_DESC``; the catalogue shows
  ten entries per page, so a new "OAP ..." or "RFI ..." supplier appears in one of the two);

and compares them with the committed baseline.  Every new dataset of a watched organisation, every dataset gone or
renamed and every organisation not in the baseline is listed in the job summary and as a ``::warning`` line in
``--annotations``; a dataset whose name, description, format or tags mention delays, cancellations, SIRI,
GTFS-Realtime or observed/historical data is marked as such.  An unreadable page is a warning too, never a failure:
the tool exits with 0 unless the baseline itself is invalid.

Every page is untrusted data: HTTPS to ``www.cciss.it/nap/`` only (redirects too), size and time caps, strict JSON,
identity checks; nothing is executed.  Text from the pages reaches the log escaped (``log_safe``), the summary
escaped for Markdown and the annotations escaped as workflow-command data (``%``, CR, LF), and the workflow prints
the annotations only after the tool has run with workflow commands stopped.

    python3 -I tools/nap_watch.py --summary "$GITHUB_STEP_SUMMARY" --annotations "$RUNNER_TEMP/nap-watch.txt"
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BASELINE = ROOT / "tools/nap_watch_baseline.json"
CATALOGUE = "https://www.cciss.it/nap/mmtis/public/it/catalog/"
ALLOWED_PREFIX = "https://www.cciss.it/nap/"
LIST_PAGES = ("Organisation", "Organisation?orderBy=NAME_DESC")
USER_AGENT = "Binario-nap-watch/1.0 (personal timetable app; read-only)"
SCHEMA = "binario-nap-watch-v1"
MAX_PAGE_BYTES = 4 * 1024 * 1024
TIMEOUT_SECONDS = 30.0
ATTEMPTS = 2
PAUSE_SECONDS = 2.0
DEADLINE_SECONDS = 240.0
MAX_ANNOTATIONS = 10
MAX_TEXT = 160
NEXT_DATA = re.compile(r'<script id="__NEXT_DATA__" type="application/json"[^>]*>(.*?)</script>', re.S)
# What would let Binario stop estimating from ViaggiaTreno: delays, cancellations, realtime or observed data.  Not
# "dynamic": the NeTEx timetables themselves are filed as "dynamic_information_service".
INTERESTING = re.compile(
    r"ritard|delay|cancel|soppress|\bSIRI\b|SIRI[-_ ]|GTFS[-_ ]?R(?:T\b|ealtime)|real[-_ ]?time|tempo reale|osservat|observ"
    r"|storic|historic|puntualit|punctual|perturbaz|disruption|circolazione",
    re.I,
)
# Dataset attributes whose text is searched (the rest are emails, enumerations shared by every dataset, coverage).
SCANNED_ATTRIBUTES = frozenset({"description", "formatDataEx", "format", "tags", "categoryType", "dataCategory",
                                "netexCategory", "keywords"})


class BaselineError(ValueError):
    """``tools/nap_watch_baseline.json`` does not follow ``binario-nap-watch-v1``."""


def log_safe(value: object) -> str:
    """``value`` on one line with control characters escaped (same rule as the other tools)."""
    return "".join(
        character if character.isprintable() or character == " " else character.encode("unicode_escape").decode("ascii")
        for character in str(value)
    )


def short(value: object) -> str:
    text = log_safe(value)
    return text if len(text) <= MAX_TEXT else text[:MAX_TEXT - 1] + "…"


def markdown(value: object) -> str:
    """Text from a page inside a Markdown table cell or list item: no HTML, link, code span or column break."""
    text = short(value)
    for character, escaped in (("\\", "\\\\"), ("|", "\\|"), ("`", "'"), ("<", "&lt;"), (">", "&gt;"), ("[", "\\["),
                               ("]", "\\]"), ("*", "\\*"), ("_", "\\_")):
        text = text.replace(character, escaped)
    return text


def command_data(value: str) -> str:
    """``value`` as the data of a workflow command (the escaping of @actions/core): it cannot end the line."""
    return log_safe(value).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def warning(message: str) -> str:
    return f"::warning title=NAP: fornitori ferroviari::{command_data(message)}"


# --- Baseline -----------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Dataset:
    id: int
    name: str
    text: str = ""  # name and scanned attributes, for INTERESTING


@dataclass
class Organisation:
    id: int
    name: str
    watch: bool = False
    datasets: dict[int, Dataset] = field(default_factory=dict)


@dataclass
class Baseline:
    verified_at: str
    organisations: dict[int, Organisation]


def _identifier(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and 0 < value < 10 ** 12


def _name(value: object) -> bool:
    return isinstance(value, str) and 0 < len(value) <= 300 and value == value.strip()


def load_baseline(path: Path) -> Baseline:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise BaselineError(f"baseline non leggibile: {path}") from error
    if not isinstance(payload, dict) or set(payload) != {"schema", "verifiedAt", "catalogue", "note", "organisations"}:
        raise BaselineError("chiavi della baseline diverse da schema, verifiedAt, catalogue, note, organisations")
    if payload["schema"] != SCHEMA or payload["catalogue"] != CATALOGUE:
        raise BaselineError(f"schema diverso da {SCHEMA} o catalogo diverso da {CATALOGUE}")
    try:
        date.fromisoformat(payload["verifiedAt"])
    except (TypeError, ValueError) as error:
        raise BaselineError("verifiedAt non è una data AAAA-MM-GG") from error
    organisations: dict[int, Organisation] = {}
    entries = payload["organisations"]
    if not isinstance(entries, list) or not entries:
        raise BaselineError("organisations non è un elenco")
    for entry in entries:
        if not isinstance(entry, dict) or not {"id", "name"} <= set(entry) <= {"id", "name", "watch", "datasets"}:
            raise BaselineError("organizzazione diversa da {id, name[, watch, datasets]}")
        if not _identifier(entry["id"]) or not _name(entry["name"]) or entry["id"] in organisations:
            raise BaselineError(f"organizzazione con id o nome non valido o ripetuto: {log_safe(entry.get('id'))}")
        watch = entry.get("watch", False)
        datasets = entry.get("datasets")
        if watch is not (datasets is not None) or watch not in (True, False):
            raise BaselineError(f"organizzazione {entry['id']}: watch true richiede datasets, e solo allora")
        organisation = Organisation(entry["id"], entry["name"], watch)
        for dataset in datasets or []:
            if not isinstance(dataset, dict) or set(dataset) != {"id", "name"} or not _identifier(dataset["id"]) \
                    or not _name(dataset["name"]) or dataset["id"] in organisation.datasets:
                raise BaselineError(f"organizzazione {entry['id']}: dataset diverso da {{id, name}} o ripetuto")
            organisation.datasets[dataset["id"]] = Dataset(dataset["id"], dataset["name"])
        organisations[organisation.id] = organisation
    if not any(organisation.watch for organisation in organisations.values()):
        raise BaselineError("nessuna organizzazione con watch true")
    return Baseline(payload["verifiedAt"], organisations)


def baseline_json(baseline: Baseline, current: Mapping[int, Organisation], seen: Iterable[Organisation],
                  verified_at: str, note: str) -> str:
    """The baseline updated with what the pages show now (for the summary, so the owner can commit it after review)."""
    organisations: dict[int, dict[str, Any]] = {}
    for organisation in [*baseline.organisations.values(), *seen]:
        organisations.setdefault(organisation.id, {"id": organisation.id, "name": organisation.name})
    for organisation in baseline.organisations.values():
        if organisation.watch:
            shown = current.get(organisation.id, organisation)
            organisations[organisation.id] = {
                "id": organisation.id, "name": shown.name, "watch": True,
                "datasets": [{"id": dataset.id, "name": dataset.name} for dataset in sorted(shown.datasets.values(), key=lambda item: item.id)],
            }
    payload = {"schema": SCHEMA, "verifiedAt": verified_at, "catalogue": CATALOGUE, "note": note,
               "organisations": list(organisations.values())}
    # ASCII escapes for anything else: the block cannot close the Markdown code fence or carry HTML.
    text = json.dumps(payload, ensure_ascii=True, indent=2)
    return text.replace("`", "\\u0060").replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")


# --- Pages --------------------------------------------------------------------------------------------------------


class PageError(RuntimeError):
    """A catalogue page that cannot be used; the message is a fixed label, never text from the page."""


class _SameSiteRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):  # type: ignore[override]  # noqa: ANN001
        if not newurl.startswith(ALLOWED_PREFIX) or any(ord(character) < 33 for character in newurl):
            raise PageError("reindirizzamento fuori dal NAP")
        return super().redirect_request(request, fp, code, msg, headers, newurl)


_OPENER = urllib.request.build_opener(_SameSiteRedirects())


def fetch(url: str) -> bytes:
    """One page over HTTPS from the NAP, at most ``MAX_PAGE_BYTES`` within ``TIMEOUT_SECONDS``."""
    if not url.startswith(ALLOWED_PREFIX):
        raise PageError("indirizzo fuori dal NAP")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "text/html"})
    started = time.monotonic()
    try:
        with _OPENER.open(request, timeout=TIMEOUT_SECONDS) as response:
            if response.status != 200:
                raise PageError(f"HTTP {int(response.status)}")
            chunks, size = [], 0
            while True:
                chunk = response.read(64 * 1024)
                if not chunk:
                    return b"".join(chunks)
                size += len(chunk)
                if size > MAX_PAGE_BYTES:
                    raise PageError("pagina oltre il limite di dimensione")
                if time.monotonic() - started > 2 * TIMEOUT_SECONDS:
                    raise PageError("lettura troppo lenta")
                chunks.append(chunk)
    except urllib.error.HTTPError as error:
        error.close()
        raise PageError(f"HTTP {int(error.code)}") from None
    except PageError:
        raise
    except (urllib.error.URLError, OSError, ValueError) as error:
        raise PageError("timeout" if isinstance(getattr(error, "reason", error), TimeoutError) else "rete") from None


def _reject_constant(name: str) -> Any:
    raise ValueError(f"costante JSON non ammessa: {name}")


def page_props(body: bytes) -> Mapping[str, Any]:
    """``props.pageProps`` of a Next.js page."""
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError:
        raise PageError("pagina non UTF-8") from None
    match = NEXT_DATA.search(text)
    if not match:
        raise PageError("__NEXT_DATA__ assente")
    try:
        payload = json.loads(match.group(1), parse_constant=_reject_constant)
    except (ValueError, RecursionError):
        raise PageError("__NEXT_DATA__ non è JSON valido") from None
    props = payload.get("props") if isinstance(payload, dict) else None
    page = props.get("pageProps") if isinstance(props, dict) else None
    if not isinstance(page, dict):
        raise PageError("pageProps assente")
    if page.get("statusCode") not in (None, 200):
        raise PageError("pagina di errore del catalogo")
    return page


def _texts(value: object, depth: int = 0) -> Iterable[str]:
    """The strings of a catalogue value (a string, ``{"value", "translations"}``, ``{"it", "en"}`` or a list)."""
    if depth > 4:
        return
    if isinstance(value, str):
        yield value
    elif isinstance(value, list):
        for item in value[:50]:
            yield from _texts(item, depth + 1)
    elif isinstance(value, dict):
        for key in ("value", "it", "en", "translations"):
            if key in value:
                yield from _texts(value[key], depth + 1)


def dataset_of(item: Mapping[str, Any]) -> Dataset | None:
    identifier, name = item.get("objectId"), item.get("name")
    if item.get("type") != "Dataset" or not _identifier(identifier) or not isinstance(name, str) or not name.strip():
        return None
    texts = [name]
    for attribute in item.get("attributes") or []:
        if isinstance(attribute, dict) and attribute.get("name") in SCANNED_ATTRIBUTES:
            texts += _texts(attribute.get("value"))
    return Dataset(identifier, " ".join(name.split()), " | ".join(texts)[:4000])


def organisation_page(body: bytes, identifier: int) -> Organisation:
    """The organisation and its datasets from its catalogue page."""
    entity = page_props(body).get("entityData")
    if not isinstance(entity, dict) or entity.get("type") != "Organisation" or entity.get("objectId") != identifier:
        raise PageError("la pagina non descrive l'organizzazione chiesta")
    name = entity.get("name")
    children = entity.get("children")
    if not isinstance(name, str) or not name.strip() or not isinstance(children, list):
        raise PageError("organizzazione senza nome o senza elenco dei dataset")
    organisation = Organisation(identifier, " ".join(name.split()), True)
    for child in children:
        dataset = dataset_of(child) if isinstance(child, dict) else None
        if dataset is not None:
            organisation.datasets[dataset.id] = dataset
    return organisation


def organisation_list(body: bytes) -> list[Organisation]:
    """The organisations of a catalogue list page."""
    results = page_props(body).get("searchResults")
    if not isinstance(results, list):
        raise PageError("elenco delle organizzazioni assente")
    organisations = []
    for item in results:
        if isinstance(item, dict) and item.get("type") == "Organisation" and _identifier(item.get("objectId")) \
                and isinstance(item.get("name"), str) and item["name"].strip():
            organisations.append(Organisation(item["objectId"], " ".join(item["name"].split())))
    if results and not organisations:
        raise PageError("elenco senza organizzazioni riconoscibili")
    return organisations


# --- Comparison ---------------------------------------------------------------------------------------------------


@dataclass
class Report:
    pages: list[tuple[str, str]] = field(default_factory=list)  # (page, "ok" or fixed error label)
    new_datasets: list[tuple[Organisation, Dataset]] = field(default_factory=list)
    removed_datasets: list[tuple[Organisation, Dataset]] = field(default_factory=list)
    renamed_datasets: list[tuple[Organisation, Dataset, Dataset]] = field(default_factory=list)
    new_organisations: list[Organisation] = field(default_factory=list)
    current: dict[int, Organisation] = field(default_factory=dict)
    listed: list[Organisation] = field(default_factory=list)

    @property
    def changed(self) -> bool:
        return bool(self.new_datasets or self.removed_datasets or self.renamed_datasets or self.new_organisations)

    @property
    def failed(self) -> list[tuple[str, str]]:
        return [(page, status) for page, status in self.pages if status != "ok"]


def interesting(text: str) -> list[str]:
    return sorted({match.group(0).lower() for match in INTERESTING.finditer(text)})


def watch(baseline: Baseline, get: Callable[[str], bytes], *, clock: Callable[[], float] = time.monotonic,
          sleep: Callable[[float], None] = time.sleep, deadline: float = DEADLINE_SECONDS) -> Report:
    report = Report()
    started = clock()

    def read(page: str, parse: Callable[[bytes], Any]) -> Any:
        problem = "non letta"
        for attempt in range(ATTEMPTS):
            if clock() - started > deadline:
                problem = "tempo massimo superato"
                break
            if attempt or report.pages:
                sleep(PAUSE_SECONDS)
            try:
                result = parse(get(CATALOGUE + page))
            except PageError as error:
                problem = str(error)
                continue
            report.pages.append((page, "ok"))
            return result
        report.pages.append((page, problem))
        return None

    for organisation in baseline.organisations.values():
        if not organisation.watch:
            continue
        current = read(f"Organisation/{organisation.id}", lambda body, known=organisation: organisation_page(body, known.id))
        if current is None:
            continue
        report.current[organisation.id] = current
        for identifier, dataset in sorted(current.datasets.items()):
            known = organisation.datasets.get(identifier)
            if known is None:
                report.new_datasets.append((current, dataset))
            elif known.name != dataset.name:
                report.renamed_datasets.append((current, known, dataset))
        for identifier, dataset in sorted(organisation.datasets.items()):
            if identifier not in current.datasets:
                report.removed_datasets.append((current, dataset))
    seen: set[int] = set()
    for page in LIST_PAGES:
        listed = read(page, organisation_list)
        for item in listed or []:
            if item.id in seen:
                continue
            seen.add(item.id)
            report.listed.append(item)
            if item.id not in baseline.organisations:
                report.new_organisations.append(item)
    return report


# --- Output -------------------------------------------------------------------------------------------------------


def link(kind: str, identifier: int) -> str:
    return f"{CATALOGUE}{kind}/{identifier}"


def annotations(report: Report) -> list[str]:
    lines = []
    for organisation, dataset in report.new_datasets:
        marks = interesting(dataset.text)
        lines.append(warning(
            f"nuovo dataset di {short(organisation.name)}: {dataset.id} «{short(dataset.name)}»"
            + (f", possibili dati di ritardi o tempo reale ({', '.join(marks)})" if marks else "")
            + f" {link('Dataset', dataset.id)}"))
    for organisation in report.new_organisations:
        marks = interesting(organisation.name)
        lines.append(warning(f"nuova organizzazione nel catalogo: {organisation.id} «{short(organisation.name)}»"
                             + (f" ({', '.join(marks)})" if marks else "") + f" {link('Organisation', organisation.id)}"))
    for organisation, old, new in report.renamed_datasets:
        lines.append(warning(f"dataset {old.id} di {short(organisation.name)} rinominato: «{short(old.name)}» → «{short(new.name)}»"))
    for organisation, dataset in report.removed_datasets:
        lines.append(warning(f"dataset {dataset.id} «{short(dataset.name)}» non più tra quelli di {short(organisation.name)}"))
    if report.failed:
        lines.append(warning("pagine del NAP non lette: " + ", ".join(f"{page} ({status})" for page, status in report.failed)))
    if len(lines) > MAX_ANNOTATIONS:
        lines = lines[:MAX_ANNOTATIONS - 1] + [warning(f"altre {len(lines) - MAX_ANNOTATIONS + 1} novità nel riepilogo del job")]
    return lines


def summary(report: Report, baseline: Baseline, today: str) -> str:
    lines = ["### NAP: dataset dei fornitori ferroviari", "",
             f"Confronto con `tools/nap_watch_baseline.json` (verificata il {baseline.verified_at}).", "",
             "| Pagina | Esito |", "|---|---|"]
    lines += [f"| {markdown(page)} | {markdown(status)} |" for page, status in report.pages]
    lines.append("")
    if report.new_datasets:
        lines.append("**Nuovi dataset**")
        for organisation, dataset in report.new_datasets:
            marks = interesting(dataset.text)
            lines.append(f"- {markdown(organisation.name)}: [{dataset.id}]({link('Dataset', dataset.id)}) {markdown(dataset.name)}"
                         + (f" — **possibili dati di ritardi o tempo reale** ({markdown(', '.join(marks))})" if marks else ""))
        lines.append("")
    if report.new_organisations:
        lines.append("**Nuove organizzazioni nel catalogo**")
        lines += [f"- [{organisation.id}]({link('Organisation', organisation.id)}) {markdown(organisation.name)}"
                  for organisation in report.new_organisations]
        lines.append("")
    if report.renamed_datasets:
        lines.append("**Dataset rinominati**")
        lines += [f"- {old.id}: {markdown(old.name)} → {markdown(new.name)}" for _, old, new in report.renamed_datasets]
        lines.append("")
    if report.removed_datasets:
        lines.append("**Dataset non più elencati**")
        lines += [f"- {markdown(organisation.name)}: {dataset.id} {markdown(dataset.name)}" for organisation, dataset in report.removed_datasets]
        lines.append("")
    if not report.changed:
        if len(report.failed) == len(report.pages):
            lines.append("Nessuna pagina letta: confronto non eseguito.")
        else:
            lines.append("Nessuna novità: " + ("i dataset e le organizzazioni lette coincidono con la baseline."
                                                if not report.failed else "nelle pagine lette nulla è cambiato."))
        lines.append("")
    else:
        note = "Dataset dei fornitori ferroviari sul NAP osservati da tools/nap_watch.py; aggiornare dopo la verifica."
        lines += ["<details><summary>Baseline aggiornata da verificare e committare</summary>", "", "```json",
                  baseline_json(baseline, report.current, report.listed, today, note), "```", "", "</details>", ""]
    return "\n".join(lines) + "\n"


def log_lines(report: Report) -> list[str]:
    lines = [f"{page}: {status}" for page, status in report.pages]
    lines += [f"nuovo dataset {organisation.id}/{dataset.id}: {short(dataset.name)}"
              + (f" [{', '.join(interesting(dataset.text))}]" if interesting(dataset.text) else "")
              for organisation, dataset in report.new_datasets]
    lines += [f"nuova organizzazione {organisation.id}: {short(organisation.name)}" for organisation in report.new_organisations]
    lines += [f"dataset rinominato {old.id}: {short(old.name)} -> {short(new.name)}" for _, old, new in report.renamed_datasets]
    lines += [f"dataset non più elencato {dataset.id}: {short(dataset.name)}" for _, dataset in report.removed_datasets]
    if report.changed:
        lines.append("novità rispetto alla baseline")
    else:
        lines.append("nessuna pagina letta" if len(report.failed) == len(report.pages) else "nessuna novità rispetto alla baseline")
    return [log_safe(line) for line in lines]


def main(argv: Sequence[str] | None = None, *, get: Callable[[str], bytes] = fetch,
         sleep: Callable[[float], None] = time.sleep) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE, help="baseline dei dataset noti")
    parser.add_argument("--summary", type=Path, help="file Markdown a cui aggiungere il riepilogo (GITHUB_STEP_SUMMARY)")
    parser.add_argument("--annotations", type=Path, help="file in cui scrivere le righe ::warning (una per novità)")
    parser.add_argument("--check-baseline", action="store_true", help="verifica solo la baseline, senza rete")
    args = parser.parse_args(argv)
    try:
        baseline = load_baseline(args.baseline)
    except BaselineError as error:
        print(f"ERRORE: {log_safe(error)}", file=sys.stderr)
        return 1
    if args.check_baseline:
        watched = sum(1 for organisation in baseline.organisations.values() if organisation.watch)
        print(f"Baseline valida: {len(baseline.organisations)} organizzazioni, {watched} osservate, verificata il {baseline.verified_at}")
        return 0
    report = watch(baseline, get, sleep=sleep)
    for line in log_lines(report):
        print(line)
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as stream:
            stream.write(summary(report, baseline, date.today().isoformat()))
    if args.annotations:
        args.annotations.write_text("".join(line + "\n" for line in annotations(report)), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
