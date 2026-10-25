#!/usr/bin/env python3
"""Keep the App Store listing texts within App Store Connect limits and complete for every app language."""
from __future__ import annotations

import re
import sqlite3
import tempfile
import unittest
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SUBMISSION = ROOT / "docs" / "store" / "APP_STORE_SUBMISSION.md"
SCHEDULE = ROOT / "ios" / "Resources" / "binario_gtfs_schedule_v1.sqlite.zlib"
LANGUAGES = ("it", "en", "de", "fr", "es")
LIMITS = {
    "Nome": 30,
    "Sottotitolo": 30,
    "Testo promozionale": 170,
    "Descrizione": 4000,
    "Parole chiave": 100,
    "Novità": 4000,
}
BLOCK = re.compile(
    r"^#### (Nome|Sottotitolo|Testo promozionale|Descrizione|Parole chiave|Novità) \((it|en|de|fr|es)\)\n\n```text\n(.*?)\n```$",
    re.MULTILINE | re.DOTALL,
)
REVIEW_NOTES = re.compile(r"^#### Note per la revisione\n\n```text\n(.*?)\n```$", re.MULTILINE | re.DOTALL)
# Review routes named in the notes, as catalogue identities: they must stay answerable by the index.
REVIEW_ROUTES = {
    "Milano Centrale → Bergamo": ("S01700", "S01529"),
    "Bolzano Bozen → Merano Meran": ("S02026", "S02216"),
}


class AppStoreDocsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.document = SUBMISSION.read_text(encoding="utf-8")
        self.texts = {(kind, language): text for kind, language, text in BLOCK.findall(self.document)}

    def test_every_listing_text_exists_in_all_five_languages_within_connect_limits(self) -> None:
        for kind, limit in LIMITS.items():
            for language in LANGUAGES:
                with self.subTest(kind=kind, language=language):
                    text = self.texts.get((kind, language))
                    self.assertIsNotNone(text, f"{kind} ({language}) assente")
                    self.assertTrue(text.strip(), f"{kind} ({language}) vuoto")
                    self.assertLessEqual(len(text), limit, f"{kind} ({language}) supera {limit} caratteri: {len(text)}")
        self.assertEqual(len(LIMITS) * len(LANGUAGES), len(self.texts))
        for language in LANGUAGES:
            keywords = self.texts[("Parole chiave", language)]
            self.assertNotIn(", ", keywords, "le parole chiave sono separate da sola virgola")
            self.assertNotIn("\n", keywords)

    def test_listing_texts_describe_scheduled_data_and_name_the_excluded_operators(self) -> None:
        markers = {
            "it": ("orari programmati", "non inventa", "Trenitalia e Italo non sono inclusi"),
            "en": ("scheduled", "never invents", "Trenitalia and Italo are not included"),
            "de": ("Planzeiten", "erfindet keine", "Trenitalia und Italo sind nicht enthalten"),
            "fr": ("programmés", "n'invente", "Trenitalia et Italo ne sont pas inclus"),
            "es": ("programados", "no inventa", "Trenitalia e Italo no están incluidos"),
        }
        for language, fragments in markers.items():
            full = self.texts[("Descrizione", language)]
            for fragment in fragments:
                with self.subTest(language=language, fragment=fragment):
                    self.assertIn(fragment, full)
            self.assertIn("iOS 17", full)
            self.assertIn("watchOS 10", full)
            self.assertIn("Trenitalia", self.texts[("Testo promozionale", language)])
            self.assertEqual("Binario", self.texts[("Nome", language)])

    def test_submission_checklist_links_the_verified_documents_and_fixes_the_answers(self) -> None:
        for fragment in (
            "[APPLE_PLATFORM_READINESS.md](../../ios/APPLE_PLATFORM_READINESS.md)",
            "[PRIVACY.md](../../PRIVACY.md)",
            "[store/appstore/README.md](../../store/appstore/README.md)",
            "[APPLE_WATCH_IMPLEMENTATION.md](../../apple-watch/APPLE_WATCH_IMPLEMENTATION.md)",
            "BINARIO_APPLE_TEAM_ID",
            "ios/Scripts/generate-project.sh",
            "group.it.binario.watch",
            "ITSAppUsesNonExemptEncryption = NO",
            "Dati non raccolti",
            "Esito atteso: **4+**",
            "`Binario Orari`",
            "2026-12-12",
            "binario_gtfs_source_policy_v1.json",
            "## 6. Apple Watch nel listing",
            "non creare un record per `it.binario.watch`",
            "ITMS-90713",
            "tests/test_app_store_docs.py",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, self.document)

    def test_review_notes_name_covered_routes_that_the_bundled_index_can_answer(self) -> None:
        notes = REVIEW_NOTES.search(self.document)
        self.assertIsNotNone(notes, "note per la revisione assenti")
        self.assertIn("Trenitalia e Italo non sono inclusi", notes.group(1))
        self.assertIn("Trenitalia and Italo are not included", notes.group(1))
        for label in REVIEW_ROUTES:
            self.assertIn(label, notes.group(1))

        with tempfile.TemporaryDirectory(prefix="appstore-index-") as temporary:
            database = Path(temporary) / "schedule.sqlite"
            database.write_bytes(zlib.decompress(SCHEDULE.read_bytes()))
            connection = sqlite3.connect(f"file:{database}?mode=ro&immutable=1", uri=True)
            try:
                for label, (origin, destination) in REVIEW_ROUTES.items():
                    with self.subTest(route=label):
                        trips = connection.execute(
                            """
                            SELECT count(DISTINCT origin_time.trip_id)
                            FROM stop_time AS origin_time
                            JOIN stop_time AS destination_time
                              ON destination_time.trip_id = origin_time.trip_id
                             AND destination_time.stop_sequence > origin_time.stop_sequence
                            JOIN stop AS origin_stop ON origin_stop.id = origin_time.stop_id
                            JOIN stop AS destination_stop ON destination_stop.id = destination_time.stop_id
                            WHERE origin_stop.catalog_station_id = ? AND destination_stop.catalog_station_id = ?
                              AND origin_time.pickup_type = 0 AND destination_time.drop_off_type = 0
                            """,
                            (origin, destination),
                        ).fetchone()[0]
                        self.assertGreater(trips, 0, f"{label} non è coperta dall'indice incluso")
            finally:
                connection.close()


if __name__ == "__main__":
    unittest.main()
