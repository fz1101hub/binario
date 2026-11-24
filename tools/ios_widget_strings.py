#!/usr/bin/env python3
"""The texts of the iPhone's home-screen widgets (ios/BinarioWidgets/Resources/<language>.lproj), in the 17 languages,
taken from the Android app's values*/strings.xml as Android shows them: the widget texts, the settings of the
configure screens (as parameter titles of the widget's edit sheet), the plurals. A text Android lacks in a language is
the default one, as Android falls back to values/. The weekday names are the JDK's CLDR names Android's configure
screen shows (java.time.DayOfWeek.getDisplayName, TextStyle.FULL); the empty «I miei treni» is the iPhone app's own
text (the iPhone saves a train with a star, Android with a heart).

    python3 tools/ios_widget_strings.py          # writes the 17 Localizable.strings and .stringsdict
    python3 tools/ios_widget_strings.py --check  # exits 1 when a file differs (tests/test_verify_ios_project.py)
"""

from __future__ import annotations

import argparse
import plistlib
import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_RES = ROOT / "app/src/main/res"
IOS_APP = ROOT / "ios/Resources"
OUTPUT = ROOT / "ios/BinarioWidgets/Resources"

# The Android resource folder of each .lproj (tests/test_verify_ios_project.py ANDROID_VALUES).
ANDROID_VALUES = {
    "it": "values", "en": "values-en", "de": "values-de", "es": "values-es", "fr": "values-fr",
    "ar": "values-ar", "hi": "values-hi", "id": "values-in", "ja": "values-ja", "ko": "values-ko",
    "my": "values-my", "pt-BR": "values-pt", "ru": "values-ru", "th": "values-th", "tr": "values-tr",
    "vi": "values-vi", "zh-Hans": "values-zh-rCN",
}

# java.time.DayOfWeek.getDisplayName(TextStyle.FULL, locale), Monday first (JDK 21, CLDR).
WEEKDAYS = {
    "it": ["lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica"],
    "en": ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"],
    "de": ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"],
    "fr": ["lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"],
    "es": ["lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"],
    "ar": ["الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد"],
    "hi": ["सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार", "रविवार"],
    "id": ["Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu"],
    "ja": ["月曜日", "火曜日", "水曜日", "木曜日", "金曜日", "土曜日", "日曜日"],
    "ko": ["월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"],
    "my": ["တနင်္လာ", "အင်္ဂါ", "ဗုဒ္ဓဟူး", "ကြာသပတေး", "သောကြာ", "စနေ", "တနင်္ဂနွေ"],
    "pt-BR": ["segunda-feira", "terça-feira", "quarta-feira", "quinta-feira", "sexta-feira", "sábado", "domingo"],
    "ru": ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"],
    "th": ["วันจันทร์", "วันอังคาร", "วันพุธ", "วันพฤหัสบดี", "วันศุกร์", "วันเสาร์", "วันอาทิตย์"],
    "tr": ["Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar"],
    "vi": ["Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật"],
    "zh-Hans": ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"],
}

GROUPS = {
    "high_speed": "widget_settings_type_high_speed", "intercity_night": "widget_settings_type_intercity",
    "regional_suburban": "widget_settings_type_regional", "italo": "widget_settings_type_italo",
    "replacement_bus": "widget_settings_type_bus", "international": "widget_settings_type_international",
}


def android(name: str) -> tuple:
    return ("android", name)


def upper(spec: tuple) -> tuple:
    return ("upper", spec)


def join(*parts: tuple | str) -> tuple:
    return ("join", parts)


def numbered(name: str, number: int) -> tuple:
    """An Android text with its one number filled in («Fascia %1$d» → «Fascia 1»)."""
    return ("numbered", name, number)


def bare(name: str) -> tuple:
    """An Android text without its one placeholder («Dalle %1$s» → «Dalle»), for a title that cannot be formatted."""
    return ("bare", name)


def plural(name: str, count: int) -> tuple:
    return ("plural", name, count)


# Key → source. The keys the extension reads; the parameter titles of the edit sheet are AppIntents' localized keys.
TEXTS: dict[str, tuple] = {
    # The three widgets.
    "widgets.route.name": android("widget_target_route"),
    "widgets.route.description": android("widget_description"),
    "widgets.station.name": android("station_widget_name"),
    "widgets.station.description": android("station_widget_description"),
    "widgets.trains.name": android("trains_widget_name"),
    "widgets.trains.description": android("trains_widget_description"),
    # The settings, as parameters of the edit sheet.
    "widgets.param.origin": android("widget_origin"),
    "widgets.param.destination": android("widget_destination"),
    "widgets.param.station": android("station_label"),
    "widgets.param.title": android("widget_display_name_label"),
    "widgets.param.refresh": android("widget_settings_auto_refresh_title"),
    "widgets.refresh.off": android("widget_settings_auto_refresh_off"),
    "widgets.refresh.windows": android("widget_settings_auto_refresh_windows"),
    "widgets.refresh.always": android("widget_settings_auto_refresh_always"),
    "widgets.param.interval": android("widget_settings_interval_title"),
    "widgets.param.time": android("widget_settings_windows_title"),
    "widgets.interval.15": plural("widget_settings_interval_minutes", 15),
    "widgets.interval.30": plural("widget_settings_interval_minutes", 30),
    "widgets.interval.60": plural("widget_settings_interval_minutes", 60),
    "widgets.param.days": android("widget_settings_days_title"),
    "widgets.days.weekdays": android("widget_settings_days_weekdays"),
    "widgets.days.every_day": android("widget_settings_days_every_day"),
    "widgets.days.custom": android("widget_settings_days_custom"),
    **{f"widgets.param.day.{index + 1}": join(android("widget_settings_days_custom"), ": ", ("weekday", index)) for index in range(7)},
    "widgets.param.window1.start": join(numbered("widget_settings_window_label", 1), " · ", bare("widget_settings_window_from")),
    "widgets.param.window1.end": join(numbered("widget_settings_window_label", 1), " · ", bare("widget_settings_window_to")),
    "widgets.param.window2": android("widget_settings_window_add"),
    "widgets.param.window2.start": join(numbered("widget_settings_window_label", 2), " · ", bare("widget_settings_window_from")),
    "widgets.param.window2.end": join(numbered("widget_settings_window_label", 2), " · ", bare("widget_settings_window_to")),
    "widgets.param.return": android("widget_settings_return_switch"),
    "widgets.param.return_from": bare("widget_settings_return_from"),
    "widgets.param.range": android("widget_settings_range_title"),
    "widgets.range.next": android("widget_settings_range_next"),
    "widgets.range.next_3h": android("widget_settings_range_3h"),
    "widgets.range.next_6h": android("widget_settings_range_6h"),
    "widgets.range.whole_day": android("widget_settings_range_day"),
    **{f"widgets.param.hide.{group}": join(android("widget_settings_exclude_title"), ": ", android(name)) for group, name in GROUPS.items()},
    "widgets.param.until_arrival": android("widget_settings_until_arrival"),
    "widgets.param.platform": android("widget_settings_show_platform"),
    "widgets.param.train_name": android("widget_settings_show_train_name"),
    "widgets.param.nearest": android("station_widget_settings_nearest"),
    "widgets.param.info": android("trains_widget_settings_info_title"),
    "widgets.info.next_station": android("trains_widget_settings_info_next"),
    "widgets.info.last_detection": android("trains_widget_settings_info_last"),
    "widgets.param.hide_not_running": android("trains_widget_settings_hide_not_running"),
    "widgets.param.sort": android("trains_widget_settings_sort_title"),
    "widgets.sort.departure": android("trains_widget_settings_sort_departure"),
    "widgets.sort.name": android("trains_widget_settings_sort_name"),
    # The buttons.
    "widgets.action.direction": android("widget_switch_direction"),
    "widgets.action.route_refresh": android("widget_refresh"),
    "widgets.action.station_refresh": android("station_widget_refresh"),
    "widgets.action.trains_refresh": android("trains_widget_refresh"),
    "widgets.action.previous": android("widget_previous_page"),
    "widgets.action.next": android("widget_next_page"),
    "widgets.action.trains_previous": android("trains_widget_previous_page"),
    "widgets.action.trains_next": android("trains_widget_next_page"),
    "widgets.column.time": android("widget_column_time"),
    "widgets.column.destination": android("widget_column_destination_train"),
    "widgets.column.platform": android("widget_column_platform"),
    "widgets.column.status": android("widget_column_status"),
    "widgets.symbol.refresh": android("widget_refresh_symbol"),
    "widgets.symbol.previous": android("widget_previous_symbol"),
    "widgets.symbol.next": android("widget_next_symbol"),
    "widgets.direction.outbound": upper(android("direction_outbound")),
    "widgets.direction.return": upper(android("direction_return")),
    # The rows and the header.
    "widgets.updated": android("widget_updated_header"),
    "widgets.updated.never": android("widget_updated_header_never"),
    "widgets.page": android("widget_page_label"),
    "widgets.status.on_time": android("widget_status_on_time"),
    "widgets.status.delayed": android("widget_status_delayed"),
    "widgets.status.cancelled": android("widget_status_cancelled"),
    "widgets.status.scheduled": android("widget_status_scheduled"),
    "widgets.status.late": android("station_widget_status_late"),
    "widgets.status.changed": android("station_widget_status_changed"),
    "widgets.status.early": android("trains_widget_status_early"),
    "widgets.platform": android("widget_platform_short"),
    "widgets.platform.changed": android("widget_platform_changed"),
    "widgets.in_transit": android("widget_in_transit"),
    "widgets.confidence.static": android("widget_confidence_static_only"),
    "widgets.freshness.fresh": android("widget_freshness_fresh"),
    "widgets.freshness.aging": android("widget_freshness_aging"),
    "widgets.freshness.stale": android("widget_freshness_stale"),
    "widgets.freshness.unavailable": android("widget_freshness_unavailable"),
    "widgets.unavailable": android("widget_unavailable_meta"),
    "widgets.target.route": upper(android("widget_target_route")),
    "widgets.route.empty": android("widget_route_empty"),
    "widgets.route.configure.title": android("widget_configuration_title"),
    "widgets.route.configure.subtitle": android("widget_configuration_subtitle"),
    "widgets.station.configure.title": android("station_widget_configuration_title"),
    "widgets.station.configure.subtitle": android("station_widget_configuration_subtitle"),
    "widgets.station.waiting": android("station_widget_waiting"),
    "widgets.station.empty": android("station_widget_empty"),
    "widgets.station.nearest": android("station_widget_nearest_action"),
    "widgets.station.nearest.accessibility": android("station_widget_nearest_accessibility"),
    "widgets.station.nearest.denied": android("station_widget_nearest_denied"),
    "widgets.station.nearest.unavailable": android("station_widget_nearest_unavailable"),
    "widgets.station.nearest.none": android("station_widget_nearest_none"),
    "widgets.trains.train_number": android("train_number_label"),
    "widgets.trains.category_number": android("service_category_number"),
    "widgets.trains.next_station": android("trains_widget_next_station"),
    "widgets.trains.last_detection": android("trains_widget_last_detection"),
    "widgets.trains.no_detection": android("trains_widget_no_detection"),
    "widgets.trains.departure_from": android("trains_widget_departure_from"),
    "widgets.trains.waiting": android("trains_widget_waiting"),
    "widgets.trains.stale": android("trains_widget_stale_data"),
    "widgets.trains.empty": ("ios", "saved.trains.empty.body"),
    "widgets.trains.none_running": android("trains_widget_none_running"),
    # What VoiceOver reads.
    "widgets.a11y.on_time": android("trains_widget_on_time_accessibility"),
    "widgets.a11y.cancelled": android("trains_widget_cancelled_accessibility"),
    "widgets.a11y.variation": android("trains_widget_variation_accessibility"),
    "widgets.a11y.scheduled": android("operational_status_unverified"),
    "widgets.a11y.platform": android("platform_label"),
    "widgets.a11y.platform_changed": android("platform_changed_accessible"),
    "widgets.a11y.platform_unavailable": android("platform_unavailable"),
}

# Key → Android plurals, as .stringsdict entries.
PLURALS: dict[str, str] = {
    "widgets.trains.count": "trains_widget_count",
    "widgets.trains.hidden": "trains_widget_hidden_count",
    "widgets.a11y.delay": "trains_widget_delay_accessibility",
    "widgets.a11y.early": "trains_widget_early_accessibility",
}


def plural_category(language: str, n: int) -> str:
    """The CLDR category of the integer `n` (the languages of docs/l10n/LOCALES.md)."""
    if language in {"id", "ja", "ko", "my", "th", "vi", "zh-Hans"}:
        return "other"
    if language == "ar":
        if n == 0:
            return "zero"
        if n == 1:
            return "one"
        if n == 2:
            return "two"
        if 3 <= n % 100 <= 10:
            return "few"
        return "many" if 11 <= n % 100 <= 99 else "other"
    if language == "ru":
        if n % 10 == 1 and n % 100 != 11:
            return "one"
        if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14:
            return "few"
        return "many"
    if language == "fr":
        return "one" if n in (0, 1) else "many" if n != 0 and n % 1_000_000 == 0 else "other"
    if language in {"it", "es", "pt-BR"}:
        return "one" if n == 1 else "many" if n != 0 and n % 1_000_000 == 0 else "other"
    if language == "hi":
        return "one" if n in (0, 1) else "other"
    return "one" if n == 1 else "other"


def apple(text: str) -> str:
    """Android placeholders as Apple's: %1$s → %1$@, %1$d → %1$lld, a lone %d or %s → %1$lld or %1$@."""
    text = re.sub(r"%(\d)\$s", r"%\1$@", text)
    text = re.sub(r"%(\d)\$d", r"%\1$lld", text)
    return text.replace("%d", "%1$lld").replace("%s", "%1$@")


class Android:
    def __init__(self) -> None:
        self.strings: dict[str, dict[str, str]] = {}
        self.plurals: dict[str, dict[str, dict[str, str]]] = {}
        for language, folder in ANDROID_VALUES.items():
            root = ElementTree.parse(ANDROID_RES / folder / "strings.xml").getroot()
            self.strings[language] = {element.get("name"): element.text or "" for element in root.findall("string")}
            self.plurals[language] = {
                element.get("name"): {item.get("quantity"): item.text or "" for item in element.findall("item")}
                for element in root.findall("plurals")
            }

    def string(self, language: str, name: str) -> str:
        value = self.strings[language].get(name)
        if value is None:
            value = self.strings["it"][name]
        return value

    def plural(self, language: str, name: str) -> dict[str, str]:
        forms = self.plurals[language].get(name) or self.plurals["it"][name]
        return forms


def resolve(spec: tuple | str, language: str, source: Android, ios: dict[str, str]) -> str:
    if isinstance(spec, str):
        return spec
    kind = spec[0]
    if kind == "android":
        return apple(source.string(language, spec[1]))
    if kind == "upper":
        return resolve(spec[1], language, source, ios).upper()
    if kind == "join":
        return "".join(resolve(part, language, source, ios) for part in spec[1])
    if kind == "numbered":
        return re.sub(r"%(?:1\$)?d", str(spec[2]), source.string(language, spec[1]))
    if kind == "bare":
        text = re.sub(r"%(?:1\$)?[sd]", "", source.string(language, spec[1]))
        return re.sub(r"\s+", " ", text).strip(" :：")
    if kind == "plural":
        forms = source.plural(language, spec[1])
        form = forms.get(plural_category(language, spec[2])) or forms["other"]
        return re.sub(r"%(?:1\$)?d", str(spec[2]), form)
    if kind == "weekday":
        return WEEKDAYS[language][spec[1]]
    if kind == "ios":
        return ios[spec[1]]
    raise ValueError(spec)


STRINGS_ENTRY = re.compile(r'^"((?:[^"\\]|\\.)+)"\s*=\s*"((?:[^"\\]|\\.)*)";$')


def read_strings(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = STRINGS_ENTRY.fullmatch(line.strip())
        if match:
            values[match.group(1)] = match.group(2)
    return values


def escape(value: str) -> str:
    # Android's escapes (\' \" \n) are the .strings ones; a bare double quote is escaped.
    return re.sub(r'(?<!\\)"', r'\\"', value)


def render(language: str, source: Android) -> tuple[str, bytes]:
    ios = read_strings(IOS_APP / f"{language}.lproj/Localizable.strings")
    lines = [f'"{key}" = "{escape(resolve(spec, language, source, ios))}";' for key, spec in TEXTS.items()]
    strings = "\n".join(lines) + "\n"
    entries = {}
    for key, name in PLURALS.items():
        forms = {category: apple(text) for category, text in source.plural(language, name).items()}
        entries[key] = {
            "NSStringLocalizedFormatKey": "%1$#@count@",
            "count": {"NSStringFormatSpecTypeKey": "NSStringPluralRuleType", "NSStringFormatValueTypeKey": "lld", **forms},
        }
    return strings, plistlib.dumps(entries, sort_keys=True)


def outputs() -> dict[Path, bytes]:
    source = Android()
    files: dict[Path, bytes] = {}
    for language in ANDROID_VALUES:
        strings, plurals = render(language, source)
        folder = OUTPUT / f"{language}.lproj"
        files[folder / "Localizable.strings"] = strings.encode("utf-8")
        files[folder / "Localizable.stringsdict"] = plurals
    return files


def stale() -> list[str]:
    return [str(path.relative_to(ROOT)) for path, data in outputs().items() if not path.is_file() or path.read_bytes() != data]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="writes nothing; exits 1 when a file differs")
    args = parser.parse_args(argv)
    if args.check:
        differing = stale()
        for path in differing:
            print(f"ERRORE: {path} non corrisponde ai testi Android: eseguire python3 tools/ios_widget_strings.py", file=sys.stderr)
        return 1 if differing else 0
    for path, data in outputs().items():
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.is_file() or path.read_bytes() != data:
            path.write_bytes(data)
            print(f"scritto {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
