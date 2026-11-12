#!/usr/bin/env python3
"""The 17 Android locales of the phone and the watch keep the keys, placeholders and plural quantities of the base.

A `%2$s` lost in one translation is a crash only that language's users see, and a missing plural quantity is a
wrong word only they read: neither is caught by a build in Italian or English. Run in Android CI
(`python3 -m unittest tests.test_android_string_resources`).
"""
from __future__ import annotations

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("app", "wear")
BASE = "values"
# The 16 translations next to the Italian base, and the CLDR plural categories each language uses (Android reads
# exactly these: a missing one falls back to `other`, an extra one is never read).
PLURAL_CATEGORIES = {
    "values": {"one", "many", "other"},
    "values-en": {"one", "other"},
    "values-de": {"one", "other"},
    "values-es": {"one", "many", "other"},
    "values-fr": {"one", "many", "other"},
    "values-ar": {"zero", "one", "two", "few", "many", "other"},
    "values-hi": {"one", "other"},
    "values-in": {"other"},
    "values-ja": {"other"},
    "values-ko": {"other"},
    "values-my": {"other"},
    "values-pt": {"one", "many", "other"},
    "values-ru": {"one", "few", "many", "other"},
    "values-th": {"other"},
    "values-tr": {"one", "other"},
    "values-vi": {"other"},
    "values-zh-rCN": {"other"},
}
LOCALES = tuple(name for name in PLURAL_CATEGORIES if name != BASE)
PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(]*)(\d+)?(?:\.\d+)?([a-zA-Z%])")


class Catalogue:
    def __init__(self) -> None:
        self.strings: dict[str, str] = {}
        self.untranslatable: set[str] = set()
        self.plurals: dict[str, dict[str, str]] = {}
        self.arrays: dict[str, list[str]] = {}
        self.duplicates: list[str] = []


def inner_text(element: ET.Element) -> str:
    """The text of a resource with its nested tags (`<xliff:g>`), where a placeholder can sit."""
    return (element.text or "") + "".join(ET.tostring(child, encoding="unicode") for child in element)


def load(directory: Path) -> Catalogue:
    catalogue = Catalogue()
    for path in sorted(directory.glob("*.xml")):
        for element in ET.parse(path).getroot():
            name = element.get("name")
            if name is None:
                continue
            if element.tag == "string":
                if name in catalogue.strings:
                    catalogue.duplicates.append(name)
                catalogue.strings[name] = inner_text(element)
                if element.get("translatable") == "false":
                    catalogue.untranslatable.add(name)
            elif element.tag == "plurals":
                if name in catalogue.plurals:
                    catalogue.duplicates.append(name)
                catalogue.plurals[name] = {item.get("quantity", ""): inner_text(item) for item in element}
            elif element.tag in ("string-array", "array"):
                if name in catalogue.arrays:
                    catalogue.duplicates.append(name)
                catalogue.arrays[name] = [inner_text(item) for item in element]
                if element.get("translatable") == "false":
                    catalogue.untranslatable.add(name)
    return catalogue


def placeholders(text: str) -> list[tuple[str, str]]:
    """The format arguments of a text as (position, conversion), `%%` and `%n` left out."""
    return sorted(
        (match.group(1) or "?", match.group(4))
        for match in PLACEHOLDER.finditer(text)
        if match.group(4) not in ("%", "n")
    )


def problems(locale: str, base: Catalogue, translated: Catalogue) -> list[str]:
    """Every way a translation differs from the base in keys, placeholders or plural quantities."""
    found: list[str] = [f"duplicate {name}" for name in translated.duplicates]
    translatable = set(base.strings) - base.untranslatable
    found += [f"missing string {name}" for name in sorted(translatable - set(translated.strings))]
    found += [f"extra string {name}" for name in sorted(set(translated.strings) - translatable)]
    found += [f"missing plurals {name}" for name in sorted(set(base.plurals) - set(translated.plurals))]
    found += [f"extra plurals {name}" for name in sorted(set(translated.plurals) - set(base.plurals))]
    translatable_arrays = set(base.arrays) - base.untranslatable
    found += [f"missing array {name}" for name in sorted(translatable_arrays - set(translated.arrays))]
    found += [f"extra array {name}" for name in sorted(set(translated.arrays) - translatable_arrays)]
    for name, text in sorted(translated.strings.items()):
        if name in translatable and placeholders(text) != placeholders(base.strings[name]):
            found.append(f"placeholders of {name}: {placeholders(text)} instead of {placeholders(base.strings[name])}")
    for name, items in sorted(translated.arrays.items()):
        if name in translatable_arrays:
            if len(items) != len(base.arrays[name]):
                found.append(f"array {name}: {len(items)} items instead of {len(base.arrays[name])}")
            for index, (text, base_text) in enumerate(zip(items, base.arrays[name])):
                if placeholders(text) != placeholders(base_text):
                    found.append(f"placeholders of {name}[{index}]: {placeholders(text)} instead of {placeholders(base_text)}")
    categories = PLURAL_CATEGORIES[locale]
    for name, quantities in sorted(translated.plurals.items()):
        if name not in base.plurals:
            continue
        if set(quantities) != categories:
            found.append(f"plurals {name}: quantities {sorted(quantities)} instead of {sorted(categories)}")
        found += plural_placeholder_problems(name, quantities, plural_placeholders(base.plurals[name]))
    return found


def plural_placeholders(quantities: dict[str, str]) -> set[tuple[str, str]]:
    return set().union(*(set(placeholders(text)) for text in quantities.values()))


def plural_placeholder_problems(name: str, quantities: dict[str, str], expected: set[tuple[str, str]]) -> list[str]:
    """Every quantity, `one`, `zero` and `two` included, keeps all the placeholders (docs/l10n/LOCALES.md)."""
    return [
        f"placeholders of {name}/{quantity}: {sorted(set(placeholders(text)))} instead of {sorted(expected)}"
        for quantity, text in sorted(quantities.items())
        if set(placeholders(text)) != expected
    ]


def base_problems(base: Catalogue) -> list[str]:
    found = [f"duplicate {name}" for name in base.duplicates]
    for name, quantities in sorted(base.plurals.items()):
        if set(quantities) != PLURAL_CATEGORIES[BASE]:
            found.append(f"plurals {name}: quantities {sorted(quantities)} instead of {sorted(PLURAL_CATEGORIES[BASE])}")
        found += plural_placeholder_problems(name, quantities, plural_placeholders(quantities))
    return found


def catalogue(strings: dict[str, str] | None = None, plurals: dict[str, dict[str, str]] | None = None) -> Catalogue:
    made = Catalogue()
    made.strings = dict(strings or {})
    made.plurals = dict(plurals or {})
    return made


class AndroidStringResourcesTest(unittest.TestCase):
    def test_every_locale_of_the_phone_and_the_watch_matches_its_base(self) -> None:
        for module in MODULES:
            resources = ROOT / module / "src" / "main" / "res"
            base = load(resources / BASE)
            self.assertTrue(base.strings, f"{module}: no base strings")
            with self.subTest(module=module, locale=BASE):
                self.assertEqual([], base_problems(base))
            for locale in LOCALES:
                with self.subTest(module=module, locale=locale):
                    directory = resources / locale
                    self.assertTrue(directory.is_dir(), f"{module}: {locale} missing")
                    self.assertEqual([], problems(locale, base, load(directory)))

    def test_a_moved_or_lost_placeholder_is_reported(self) -> None:
        base = catalogue({"route": "Da %1$s a %2$s", "untouched": "Binario"})
        self.assertEqual(
            ["placeholders of route: [('1', 's'), ('1', 's')] instead of [('1', 's'), ('2', 's')]"],
            problems("values-en", base, catalogue({"route": "From %1$s to %1$s", "untouched": "Platform"})),
        )
        self.assertEqual([], problems("values-en", base, catalogue({"route": "%2$s ← %1$s", "untouched": "Platform"})))
        # A literal percent sign is no argument.
        self.assertEqual([], placeholders("100%% puntuale"))

    def test_a_missing_or_extra_key_is_reported(self) -> None:
        base = catalogue({"a": "A", "b": "B"})
        base.untranslatable.add("b")
        self.assertEqual(
            ["missing string a", "extra string b", "extra string c"],
            problems("values-en", base, catalogue({"b": "B", "c": "C"})),
        )

    def test_plural_quantities_follow_the_language(self) -> None:
        base = catalogue(plurals={"minutes": {"one": "%1$d minuto", "many": "%1$d di minuti", "other": "%1$d minuti"}})
        russian = {"one": "%1$d минута", "few": "%1$d минуты", "other": "%1$d минуты"}
        self.assertEqual(
            ["plurals minutes: quantities ['few', 'one', 'other'] instead of ['few', 'many', 'one', 'other']"],
            problems("values-ru", base, catalogue(plurals={"minutes": russian})),
        )
        self.assertEqual([], problems("values-ru", base, catalogue(plurals={"minutes": {**russian, "many": "%1$d минут"}})))
        # No quantity drops the number, «one» included (docs/l10n/LOCALES.md).
        self.assertEqual(
            ["placeholders of minutes/one: [] instead of [('1', 'd')]"],
            problems("values-ru", base, catalogue(plurals={"minutes": {**russian, "one": "минута", "many": "%1$d минут"}})),
        )
        self.assertEqual(
            ["placeholders of minutes/one: [] instead of [('1', 'd')]"],
            base_problems(catalogue(plurals={"minutes": {**base.plurals["minutes"], "one": "un minuto"}})),
        )


if __name__ == "__main__":
    unittest.main()
