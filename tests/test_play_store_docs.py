#!/usr/bin/env python3
"""Keep the Play listing texts within Console limits and complete for every app language."""
from __future__ import annotations

import json
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SUBMISSION = ROOT / "docs" / "store" / "PLAY_SUBMISSION.md"
LOCALE_CONFIG = ROOT / "app" / "src" / "main" / "res" / "xml" / "locale_config.xml"
# The listing is translated into these languages; the app itself has every language of locale_config.xml.
LANGUAGES = ("it", "en", "de", "fr", "es")
ANDROID_NAMESPACE = "{http://schemas.android.com/apk/res/android}"


def app_languages() -> tuple[str, ...]:
    """The app's languages as BCP-47 tags (Android declares Indonesian with its legacy code "in")."""
    root = ET.parse(LOCALE_CONFIG).getroot()
    tags = [element.get(ANDROID_NAMESPACE + "name", "") for element in root.iter("locale")]
    return tuple("id" if tag == "in" else tag for tag in tags)


def bundled_networks() -> int:
    """GTFS packs of every build: the core directory and gtfs-transit/ (gtfs-personal/ never reaches Play)."""
    return sum(
        len(json.loads((ROOT / "app/src/main/assets" / directory / "manifest.json").read_text(encoding="utf-8"))["packs"])
        for directory in ("gtfs", "gtfs-transit")
    )
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

    def test_language_claims_follow_the_locale_config(self) -> None:
        # Audit A7-05: the listing claimed five languages and the test locked that claim in place.
        languages = app_languages()
        self.assertIn("it", languages)
        self.assertTrue(set(LANGUAGES) <= set(languages))
        text = SUBMISSION.read_text(encoding="utf-8")
        section = text.split("## 2. Testi del listing", 1)[1].split("#### Titolo (it)", 1)[0]
        self.assertIn(f"L'app è in {len(languages)} lingue", section)
        for tag in languages:
            with self.subTest(tag=tag):
                self.assertIn(f"`{tag}`", section)
        phrases = {
            "it": f"Disponibile in {len(languages)} lingue:",
            "en": f"Available in {len(languages)} languages:",
            "de": f"Verfügbar in {len(languages)} Sprachen:",
            "fr": f"Disponible en {len(languages)} langues :",
            "es": f"Disponible en {len(languages)} idiomas:",
        }
        for language, phrase in phrases.items():
            with self.subTest(language=language):
                self.assertIn(phrase, self.texts[("Descrizione completa", language)])

    def test_coverage_and_privacy_lines_match_the_bundled_packs_and_the_data_safety_answers(self) -> None:
        networks = bundled_networks()
        coverage = {"it": f"per {networks} reti", "en": f"for {networks} networks", "de": f"für {networks} Netze",
                    "fr": f"pour {networks} réseaux", "es": f"para {networks} redes"}
        # PLAY_DATA_SAFETY.md: station codes, date/time and train numbers to the railway sources, ViaggiaTreno in
        # cleartext HTTP, rounded arrival-station coordinates to Open-Meteo, map tiles from OpenFreeMap.
        privacy = {
            "it": ("codici delle stazioni", "numero del treno", "HTTP non cifrato", "Open-Meteo", "OpenFreeMap"),
            "en": ("station codes", "train number", "unencrypted HTTP", "Open-Meteo", "OpenFreeMap"),
            "de": ("Bahnhofscodes", "Zugnummer", "unverschlüsselt über HTTP", "Open-Meteo", "OpenFreeMap"),
            "fr": ("codes des gares", "numéro de train", "HTTP non chiffré", "Open-Meteo", "OpenFreeMap"),
            "es": ("códigos de estación", "número de tren", "HTTP sin cifrar", "Open-Meteo", "OpenFreeMap"),
        }
        for language in LANGUAGES:
            full = self.texts[("Descrizione completa", language)]
            with self.subTest(language=language):
                self.assertIn(coverage[language], full)
                for marker in privacy[language]:
                    self.assertIn(marker, full)
                self.assertNotIn("identificativo pubblico della stazione richiesta", full)

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
