#!/usr/bin/env python3
"""Keep the Play listing texts within Console limits and complete for every app language."""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SUBMISSION = ROOT / "docs" / "store" / "PLAY_SUBMISSION.md"
LANGUAGES = ("it", "en", "de", "fr", "es")
LIMITS = {
    "Titolo": 30,
    "Descrizione breve": 80,
    "Descrizione completa": 4000,
    "Note di rilascio": 500,
}
BLOCK = re.compile(
    r"^#### (Titolo|Descrizione breve|Descrizione completa|Note di rilascio) \((it|en|de|fr|es)\)\n\n```text\n(.*?)\n```$",
    re.MULTILINE | re.DOTALL,
)


class PlayStoreDocsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.texts = {(kind, language): text for kind, language, text in BLOCK.findall(SUBMISSION.read_text(encoding="utf-8"))}

    def test_every_listing_text_exists_in_all_five_languages_within_console_limits(self) -> None:
        for kind, limit in LIMITS.items():
            for language in LANGUAGES:
                with self.subTest(kind=kind, language=language):
                    text = self.texts.get((kind, language))
                    self.assertIsNotNone(text, f"{kind} ({language}) assente")
                    self.assertTrue(text.strip(), f"{kind} ({language}) vuoto")
                    self.assertLessEqual(len(text), limit, f"{kind} ({language}) supera {limit} caratteri: {len(text)}")
        self.assertEqual(len(LIMITS) * len(LANGUAGES), len(self.texts))

    def test_listing_texts_describe_scheduled_data_and_the_phone_dependent_companion(self) -> None:
        scheduled_markers = {
            "it": ("orari programmati", "non inventa"),
            "en": ("scheduled", "never invents"),
            "de": ("Planzeiten", "erfindet keine"),
            "fr": ("programmés", "n'invente"),
            "es": ("programados", "no inventa"),
        }
        for language, markers in scheduled_markers.items():
            full = self.texts[("Descrizione completa", language)]
            for marker in markers:
                with self.subTest(language=language, marker=marker):
                    self.assertIn(marker, full)
            self.assertIn("Wear OS 3", full)
            self.assertIn("Android 8.0", full)
            self.assertEqual("Binario", self.texts[("Titolo", language)])

    def test_submission_checklist_links_the_verified_release_documents(self) -> None:
        text = SUBMISSION.read_text(encoding="utf-8")
        for fragment in (
            "[PLAY_DATA_SAFETY.md](PLAY_DATA_SAFETY.md)",
            "[RELEASE_BUILD.md](../RELEASE_BUILD.md)",
            "[store/play/README.md](../../store/play/README.md)",
            "sign_release=true",
            "13 novembre 2023",
            "12 tester",
            "Fattori di forma",
            "## 5. Pubblicazione Wear OS su Google Play",
            "`wear-release.aab` nel canale *Wear OS*",
            "`1000000 + codice telefono`",
            "rapporto 1:1",
            "com.google.android.wearable.standalone=false",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, text)


if __name__ == "__main__":
    unittest.main()
