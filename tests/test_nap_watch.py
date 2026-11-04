#!/usr/bin/env python3
"""Offline tests for tools/nap_watch.py with fixture pages shaped like the NAP catalogue (Next.js ``__NEXT_DATA__``
as read by the transit-discovery run of 2 November 2026).  Nothing here is fetched from the network."""
from __future__ import annotations

import contextlib
import copy
import io
import json
import re
import shutil
import sys
import tempfile
import unittest
import urllib.request
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import nap_watch as nap  # noqa: E402

FIXTURES = ROOT / "tests/fixtures/nap"
PAGES = {
    nap.CATALOGUE + "Organisation/1077484": "Organisation-1077484.html",
    nap.CATALOGUE + "Organisation/1813930": "Organisation-1813930.html",
    nap.CATALOGUE + "Organisation": "Organisation.html",
    nap.CATALOGUE + "Organisation?orderBy=NAME_DESC": "Organisation-NAME_DESC.html",
}


def fixture(url: str) -> bytes:
    return (FIXTURES / PAGES[url]).read_bytes()


def next_data(body: bytes) -> dict[str, Any]:
    return json.loads(nap.NEXT_DATA.search(body.decode("utf-8")).group(1))


def with_next_data(body: bytes, change: Callable[[dict[str, Any]], None]) -> bytes:
    """``body`` with its ``__NEXT_DATA__`` changed by ``change``, escaped as Next.js does (``<``, ``>``, ``&``)."""
    data = next_data(body)
    change(data)
    text = body.decode("utf-8")
    match = nap.NEXT_DATA.search(text)
    encoded = json.dumps(data, ensure_ascii=False).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    return (text[:match.start(1)] + encoded + text[match.end(1):]).encode("utf-8")


def new_dataset(identifier: int, name: str, description: str = "", data_format: str = "NeTEx") -> dict[str, Any]:
    return {"objectId": identifier, "parentId": 1077484, "type": "Dataset", "name": name, "attributes": [
        {"type": "MLString", "name": "description", "value": {"it": description, "en": ""}},
        {"type": "Enumeration", "name": "formatDataEx", "value": {"value": data_format, "translations": {"it": data_format}}},
        {"type": "EnumerationArray", "name": "serviceType", "value": [{"value": "dynamic_information_service"}]},
        # Not searched: an e-mail or a coverage that mentions delays says nothing about the data.
        {"type": "String", "name": "dataOwnerEName", "value": "ritardi@example.invalid"},
    ]}


class Pages:
    """Fixture pages, optionally changed per URL; records the URLs asked."""

    def __init__(self, **changes: Callable[[bytes], bytes]):
        """``Organisation_1077484`` changes ``Organisation/1077484``, ``Organisation__NAME_DESC`` the descending list."""
        self.changes = {}
        for key, value in changes.items():
            page, _, order = key.partition("__")
            self.changes[nap.CATALOGUE + page.replace("_", "/") + (f"?orderBy={order}" if order else "")] = value
        self.asked: list[str] = []

    def __call__(self, url: str) -> bytes:
        self.asked.append(url)
        body = fixture(url)
        change = self.changes.get(url)
        return change(body) if change else body


def add_children(*children: dict[str, Any]) -> Callable[[bytes], bytes]:
    return lambda body: with_next_data(body, lambda data: data["props"]["pageProps"]["entityData"]["children"].extend(children))


def run_watch(get: Callable[[str], bytes], baseline: nap.Baseline | None = None, **options: Any) -> nap.Report:
    return nap.watch(baseline or nap.load_baseline(nap.DEFAULT_BASELINE), get, sleep=lambda _: None, **options)


class BaselineTest(unittest.TestCase):
    def test_the_committed_baseline_has_only_the_netex_timetables_of_trenitalia_and_italo(self) -> None:
        baseline = nap.load_baseline(nap.DEFAULT_BASELINE)
        watched = {organisation.id: organisation for organisation in baseline.organisations.values() if organisation.watch}
        self.assertEqual({1077484, 1813930}, set(watched))
        names = [dataset.name for organisation in watched.values() for dataset in organisation.datasets.values()]
        self.assertEqual(3, len(names))
        self.assertTrue(all(re.fullmatch(r"OAP (Trenitalia|Italo) - NeTEx Livello [12]", name) for name in names), names)
        # Every organisation the two list pages show is known, so a plain run reports nothing.
        listed = {item["objectId"] for name in ("Organisation.html", "Organisation-NAME_DESC.html")
                  for item in next_data((FIXTURES / name).read_bytes())["props"]["pageProps"]["searchResults"]}
        self.assertLessEqual(listed, set(baseline.organisations))

    def test_invalid_baselines_are_refused(self) -> None:
        original = json.loads(nap.DEFAULT_BASELINE.read_text(encoding="utf-8"))
        temp = Path(tempfile.mkdtemp(prefix="nap-baseline-"))
        self.addCleanup(shutil.rmtree, temp, ignore_errors=True)

        def changed(change: Callable[[dict[str, Any]], None]) -> Path:
            payload = copy.deepcopy(original)
            change(payload)
            path = temp / "baseline.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            return path

        cases = {
            "schema": lambda p: p.update(schema="other"),
            "catalogue": lambda p: p.update(catalogue="https://evil.example/"),
            "verifiedAt": lambda p: p.update(verifiedAt="2 novembre"),
            "extra key": lambda p: p.update(extra=1),
            "repeated organisation": lambda p: p["organisations"].append(copy.deepcopy(p["organisations"][0])),
            "watch without datasets": lambda p: p["organisations"][0].pop("datasets"),
            "datasets without watch": lambda p: p["organisations"][2].update(datasets=[]),
            "watch not a boolean": lambda p: p["organisations"][0].update(watch=1),
            "repeated dataset": lambda p: p["organisations"][1]["datasets"].append(dict(p["organisations"][1]["datasets"][0])),
            "dataset id": lambda p: p["organisations"][0]["datasets"][0].update(id="1077621"),
            "organisation id": lambda p: p["organisations"][0].update(id=True),
            "name with spaces": lambda p: p["organisations"][0].update(name=" OAP Trenitalia"),
            "nothing watched": lambda p: [organisation.pop("datasets", None) or organisation.pop("watch", None)
                                          for organisation in p["organisations"]],
        }
        for label, change in cases.items():
            with self.subTest(label=label):
                with self.assertRaises(nap.BaselineError):
                    nap.load_baseline(changed(change))
        (temp / "broken.json").write_text("{", encoding="utf-8")
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            self.assertEqual(1, nap.main(["--baseline", str(temp / "broken.json"), "--check-baseline"]))
            self.assertEqual(0, nap.main(["--check-baseline"]))
        self.assertIn("ERRORE: baseline non leggibile", errors.getvalue())
        self.assertIn("20 organizzazioni, 2 osservate", output.getvalue())


class WatchTest(unittest.TestCase):
    def test_the_current_catalogue_reports_nothing(self) -> None:
        pages = Pages()
        report = run_watch(pages)
        self.assertEqual(sorted(PAGES), sorted(pages.asked))
        self.assertEqual([("Organisation/1077484", "ok"), ("Organisation/1813930", "ok"), ("Organisation", "ok"),
                          ("Organisation?orderBy=NAME_DESC", "ok")], report.pages)
        self.assertFalse(report.changed)
        self.assertEqual([], nap.annotations(report))
        self.assertEqual(20, len(report.listed))
        # The NeTEx timetables are filed as a "dynamic information service": that alone is not news.
        self.assertEqual([], nap.interesting(report.current[1077484].datasets[1077621].text))
        text = nap.summary(report, nap.load_baseline(nap.DEFAULT_BASELINE), "2026-11-09")
        self.assertIn("Nessuna novità", text)
        self.assertNotIn("```json", text)

    def test_a_delay_dataset_is_reported_and_marked(self) -> None:
        pages = Pages(Organisation_1077484=add_children(
            new_dataset(1999001, "OAP Trenitalia - SIRI", "Situazione della circolazione: ritardi e soppressioni in tempo reale", "SIRI"),
            new_dataset(1999002, "OAP Trenitalia - NeTEx Livello 2", "Orari programmati, profilo italiano livello 2"),
        ))
        report = run_watch(pages)
        self.assertEqual([1999001, 1999002], [dataset.id for _, dataset in report.new_datasets])
        siri, level2 = (dataset for _, dataset in report.new_datasets)
        self.assertEqual(["circolazione", "ritard", "siri", "soppress", "tempo reale"], nap.interesting(siri.text))
        self.assertEqual([], nap.interesting(level2.text))
        lines = nap.annotations(report)
        self.assertEqual(2, len(lines))
        self.assertTrue(all(line.startswith("::warning title=NAP: fornitori ferroviari::") for line in lines))
        self.assertIn("nuovo dataset di OAP Trenitalia: 1999001 «OAP Trenitalia - SIRI», possibili dati di ritardi o tempo reale", lines[0])
        self.assertIn("https://www.cciss.it/nap/mmtis/public/it/catalog/Dataset/1999001", lines[0])
        self.assertNotIn("possibili", lines[1])
        text = nap.summary(report, nap.load_baseline(nap.DEFAULT_BASELINE), "2026-11-09")
        self.assertIn("**Nuovi dataset**", text)
        self.assertIn("**possibili dati di ritardi o tempo reale**", text)
        # The updated baseline for review: valid, with the two new datasets and nothing else changed.
        proposal = json.loads(text.split("```json\n", 1)[1].split("\n```", 1)[0])
        temp = Path(tempfile.mkdtemp(prefix="nap-proposal-"))
        self.addCleanup(shutil.rmtree, temp, ignore_errors=True)
        (temp / "baseline.json").write_text(json.dumps(proposal), encoding="utf-8")
        updated = nap.load_baseline(temp / "baseline.json")
        self.assertEqual("2026-11-09", updated.verified_at)
        self.assertEqual({1077621, 1999001, 1999002}, set(updated.organisations[1077484].datasets))
        self.assertFalse(run_watch(pages, updated).changed)

    def test_new_organisations_renamed_and_removed_datasets(self) -> None:
        def list_with_rfi(body: bytes) -> bytes:
            def change(data: dict[str, Any]) -> None:
                results = data["props"]["pageProps"]["searchResults"]
                results.insert(2, {"objectId": 2100000, "parentId": None, "type": "Organisation", "name": "OAP RFI - Circolazione"})
                results.pop()
            return with_next_data(body, change)

        def rename_and_drop(body: bytes) -> bytes:
            def change(data: dict[str, Any]) -> None:
                children = data["props"]["pageProps"]["entityData"]["children"]
                children[0]["name"] = "OAP Italo - NeTEx Livello 1 (2027)"
                del children[1]
            return with_next_data(body, change)

        report = run_watch(Pages(Organisation=list_with_rfi, Organisation_1813930=rename_and_drop))
        self.assertEqual([(2100000, "OAP RFI - Circolazione")], [(item.id, item.name) for item in report.new_organisations])
        self.assertEqual([(1813935, "OAP Italo - NeTEx Livello 1", "OAP Italo - NeTEx Livello 1 (2027)")],
                         [(old.id, old.name, new.name) for _, old, new in report.renamed_datasets])
        self.assertEqual([1813963], [dataset.id for _, dataset in report.removed_datasets])
        lines = nap.annotations(report)
        self.assertEqual(3, len(lines))
        self.assertIn("nuova organizzazione nel catalogo: 2100000 «OAP RFI - Circolazione» (circolazione)", lines[0])
        self.assertIn("rinominato", lines[1])
        self.assertIn("non più tra quelli di OAP Italo", lines[2])

    def test_text_from_the_pages_cannot_become_a_command_markdown_or_html(self) -> None:
        hostile = "SIRI\n::error::forged %0A::add-mask::x | `code` <script>alert(1)</script> [link](https://evil.example) \x1b[31m"
        report = run_watch(Pages(Organisation_1077484=add_children(new_dataset(1999003, hostile))))
        lines = nap.annotations(report)
        self.assertEqual(1, len(lines))
        line = lines[0]
        self.assertNotIn("\n", line)
        self.assertNotIn("\r", line)
        self.assertNotIn("\x1b", line)
        self.assertEqual(1, line.count("::warning"))
        self.assertIn("%250A", line)  # a literal %0A stays text once GitHub unescapes the data
        self.assertTrue(line.startswith("::warning title=NAP: fornitori ferroviari::"))
        text = nap.summary(report, nap.load_baseline(nap.DEFAULT_BASELINE), "2026-11-09")
        self.assertNotIn("<script>", text)
        self.assertIn("&lt;script&gt;", text)
        dataset_line = next(line for line in text.splitlines() if "1999003" in line and line.startswith("- "))
        self.assertNotIn("`", dataset_line)
        self.assertNotIn(" | ", dataset_line)
        self.assertNotIn("[link]", dataset_line)  # no link of its own: only the catalogue link built from the id
        self.assertIn("\\[link\\](https://evil.example)", dataset_line)
        fenced = text.split("```json\n", 1)[1].split("\n```", 1)[0]
        self.assertNotIn("`", fenced)
        self.assertNotIn("<", fenced)
        self.assertEqual(hostile.strip().split("\n")[0], json.loads(fenced)["organisations"][0]["datasets"][1]["name"][:4])
        for log_line in nap.log_lines(report):
            self.assertTrue(log_line.isprintable(), log_line)

    def test_unreadable_pages_are_one_warning_never_a_failure(self) -> None:
        def raising(label: str) -> Callable[[bytes], bytes]:
            def change(_: bytes) -> bytes:
                raise nap.PageError(label)
            return change

        broken = {
            "Organisation_1077484": lambda body: body.replace(b"__NEXT_DATA__", b"__OTHER__"),
            "Organisation_1813930": lambda body: with_next_data(body, lambda data: data["props"]["pageProps"]["entityData"].update(objectId=1)),
            "Organisation": raising("HTTP 503"),
            "Organisation__NAME_DESC": lambda body: with_next_data(body, lambda data: data["props"]["pageProps"].update(statusCode=500)),
        }
        slept: list[float] = []
        report = nap.watch(nap.load_baseline(nap.DEFAULT_BASELINE), Pages(**broken), sleep=slept.append)
        self.assertEqual([("Organisation/1077484", "__NEXT_DATA__ assente"),
                          ("Organisation/1813930", "la pagina non descrive l'organizzazione chiesta"),
                          ("Organisation", "HTTP 503"), ("Organisation?orderBy=NAME_DESC", "pagina di errore del catalogo")],
                         report.pages)
        self.assertEqual(7, len(slept))  # two attempts per page, a pause before each request but the first
        self.assertFalse(report.changed)
        lines = nap.annotations(report)
        self.assertEqual(1, len(lines))
        self.assertIn("pagine del NAP non lette: Organisation/1077484 (__NEXT_DATA__ assente)", lines[0])
        self.assertIn("Nessuna pagina letta: confronto non eseguito.", nap.summary(report, nap.load_baseline(nap.DEFAULT_BASELINE), "2026-11-09"))
        self.assertEqual("nessuna pagina letta", nap.log_lines(report)[-1])
        partly = run_watch(Pages(Organisation=raising("HTTP 503")))
        self.assertIn("Nessuna novità: nelle pagine lette nulla è cambiato.",
                      nap.summary(partly, nap.load_baseline(nap.DEFAULT_BASELINE), "2026-11-09"))
        for body, expected in ((b"\xff\xfe", "non UTF-8"), (b'<script id="__NEXT_DATA__" type="application/json">{"a": NaN}</script>', "non è JSON"),
                               (b'<script id="__NEXT_DATA__" type="application/json">[]</script>', "pageProps assente")):
            with self.subTest(expected=expected), self.assertRaisesRegex(nap.PageError, expected):
                nap.organisation_page(body, 1077484)
        with self.assertRaisesRegex(nap.PageError, "elenco delle organizzazioni assente"):
            nap.organisation_list(fixture(nap.CATALOGUE + "Organisation/1077484"))

    def test_a_second_attempt_and_the_deadline(self) -> None:
        attempts: dict[str, int] = {}

        def flaky(url: str) -> bytes:
            attempts[url] = attempts.get(url, 0) + 1
            if attempts[url] == 1:
                raise nap.PageError("rete")
            return fixture(url)

        report = run_watch(flaky)
        self.assertEqual(["ok"] * 4, [status for _, status in report.pages])
        self.assertEqual({url: 2 for url in PAGES}, attempts)
        ticks = iter([0.0, 0.0, 300.0])
        late = nap.watch(nap.load_baseline(nap.DEFAULT_BASELINE), Pages(), clock=lambda: next(ticks, 300.0), sleep=lambda _: None)
        self.assertEqual("ok", late.pages[0][1])
        self.assertEqual(["tempo massimo superato"] * 3, [status for _, status in late.pages[1:]])

    def test_annotations_are_capped(self) -> None:
        children = [new_dataset(1999100 + index, f"OAP Trenitalia - dataset {index}") for index in range(14)]
        lines = nap.annotations(run_watch(Pages(Organisation_1077484=add_children(*children))))
        self.assertEqual(nap.MAX_ANNOTATIONS, len(lines))
        self.assertIn("altre 5 novità nel riepilogo del job", lines[-1])


class FetchTest(unittest.TestCase):
    def test_only_https_pages_of_the_nap_are_asked(self) -> None:
        for url in ("http://www.cciss.it/nap/mmtis/public/it/catalog/Organisation", "https://evil.example/nap/",
                    "https://www.cciss.it.evil.example/nap/", "file:///etc/passwd"):
            with self.subTest(url=url), self.assertRaisesRegex(nap.PageError, "fuori dal NAP"):
                nap.fetch(url)
        request = urllib.request.Request(nap.CATALOGUE + "Organisation")
        handler = nap._SameSiteRedirects()
        for target in ("https://evil.example/", "http://www.cciss.it/nap/x", "https://www.cciss.it/nap/\x00x"):
            with self.subTest(target=target), self.assertRaisesRegex(nap.PageError, "reindirizzamento"):
                handler.redirect_request(request, None, 302, "Found", {}, target)
        self.assertIsNotNone(handler.redirect_request(request, None, 302, "Found", {}, nap.CATALOGUE + "Organisation?x=1"))
        self.assertTrue(nap.USER_AGENT.startswith("Binario-nap-watch/"))
        self.assertNotIn("mozilla", nap.USER_AGENT.lower())


class CommandLineTest(unittest.TestCase):
    def test_summary_annotations_and_log(self) -> None:
        temp = Path(tempfile.mkdtemp(prefix="nap-cli-"))
        self.addCleanup(shutil.rmtree, temp, ignore_errors=True)
        summary, notes = temp / "summary.md", temp / "annotations.txt"
        summary.write_text("### Earlier step\n", encoding="utf-8")
        output = io.StringIO()
        pages = Pages(Organisation_1077484=add_children(new_dataset(1999004, "OAP Trenitalia - GTFS-RT", "", "GTFS-RT")))
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, nap.main(["--summary", str(summary), "--annotations", str(notes)], get=pages, sleep=lambda _: None))
        self.assertTrue(summary.read_text(encoding="utf-8").startswith("### Earlier step\n### NAP: dataset dei fornitori ferroviari"))
        lines = notes.read_text(encoding="utf-8").splitlines()
        self.assertEqual(1, len(lines))
        self.assertIn("(gtfs-rt)", lines[0])
        self.assertIn("nuovo dataset 1077484/1999004: OAP Trenitalia - GTFS-RT [gtfs-rt]", output.getvalue())
        self.assertIn("novità rispetto alla baseline", output.getvalue())
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, nap.main(["--annotations", str(notes)], get=Pages(), sleep=lambda _: None))
        self.assertEqual("", notes.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
