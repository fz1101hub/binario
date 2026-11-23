#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import re
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "verify_privacy_policy",
    ROOT / "tools" / "verify_privacy_policy.py",
)
assert SPEC and SPEC.loader
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)

# Neighbouring countries' full timetables (foreign packs).  The pins asset, the Kotlin and Swift host constants and
# the in-app line come from the other work packages, so these tests build them as fixtures.
PINS = "app/src/main/assets/gtfs/foreign_packs.json"
STRINGS = "app/src/main/res/values/strings.xml"
RELEASE_PREFIX = "https://github.com/fz1101hub/binario/releases/download/"
FOREIGN_DOWNLOAD_HOSTS = {"github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"}
FOREIGN_PRIVACY_FRAGMENT = "GitHub vede l’indirizzo IP e quale paese scarichi"
FOREIGN_PRIVACY_TEXT = (
    "Orari completi dei paesi confinanti: si scaricano solo se tocchi «Scarica». "
    f"{FOREIGN_PRIVACY_FRAGMENT}; il file resta sul telefono, fuori dal backup, e si elimina da Impostazioni."
)
FOREIGN_PRIVACY_ELEMENT = re.compile(r'\s*<string name="settings_privacy_foreign_packs"[^>]*>.*?</string>', re.DOTALL)


def with_settings_line(text: str, value: str | None) -> str:
    """``text`` (strings.xml) with the in-app line replaced by ``value``, or removed when ``value`` is None."""
    text = FOREIGN_PRIVACY_ELEMENT.sub("", text)
    if value is None:
        return text
    return text.replace("</resources>", f'    <string name="settings_privacy_foreign_packs">{value}</string>\n</resources>', 1)


def sample_pins(pinned: tuple[str, ...] = ("oebb-gtfs",)) -> dict:
    """A pins file after the licence gate: every pack listed, only the ``pinned`` ones with download pins."""
    packs = []
    for source_id, country in (("ch-otd-gtfs", "CH"), ("oebb-gtfs", "AT"), ("sncf-gtfs", "FR")):
        android = ios = None
        if source_id in pinned:
            digest = "0123456789ab" + "c" * 52
            full_tag, ios_tag = f"gtfs-full-{source_id}-{digest[:12]}", f"gtfs-ios-{source_id}-{digest[:12]}"
            android = {"tag": full_tag, "asset": f"{source_id}.zip", "url": f"{RELEASE_PREFIX}{full_tag}/{source_id}.zip",
                       "sizeBytes": 1024, "sha256": digest, "contentSha256": digest}
            ios = {"tag": ios_tag, "asset": f"{source_id}.sqlite.zlib",
                   "url": f"{RELEASE_PREFIX}{ios_tag}/{source_id}.sqlite.zlib", "sizeBytes": 2048, "sha256": digest}
        packs.append({"sourceId": source_id, "country": country, "borderOnly": source_id in pinned,
                      "servedStationIds": [], "zoneStationIds": [], "android": android, "ios": ios})
    return {"schemaVersion": 1, "repository": "fz1101hub/binario", "borderZone": {"rule": "frontalieri-corridors-v1"}, "packs": packs}


def android_fixture_tree(root: Path, pins: dict | None) -> Path:
    """The required host sources plus the downloader's host constants.  The Kotlin file has no github.com URL on
    purpose: there, the host can only come from the pins asset."""
    files = {
        "app/src/main/java/it/binario/app/data/gtfs/ForeignPackPins.kt": (
            'const val GITHUB_RELEASE_DOWNLOAD_HOST = "release-assets.githubusercontent.com"\n'
            'const val GITHUB_LEGACY_RELEASE_DOWNLOAD_HOST = "objects.githubusercontent.com"\n'
        ),
        "app/src/main/assets/gtfs/manifest.json": '{"packs": []}\n',
        "app/src/main/assets/stations/manifest.json": "{}\n",
    }
    if pins is not None:
        files[PINS] = json.dumps(pins, indent=2) + "\n"
    for relative, text in files.items():
        (root / relative).parent.mkdir(parents=True, exist_ok=True)
        (root / relative).write_text(text, encoding="utf-8")
    return root


def repository_text(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def with_foreign_privacy_line(relative: str) -> str:
    """The repository, with the in-app line the Android work writes when this tree does not have it yet."""
    text = repository_text(relative)
    if relative == STRINGS and POLICY.string_resource(text, "settings_privacy_foreign_packs") is None:
        return with_settings_line(text, FOREIGN_PRIVACY_TEXT)
    return text


class VerifyPrivacyPolicyTest(unittest.TestCase):
    def test_repository_disclosures_are_current(self) -> None:
        self.assertEqual([], POLICY.verification_errors())

    def test_android_disclosure_is_checked_in_string_resources(self) -> None:
        required_fragment = (
            "Disattivare la condivisione elimina il DataItem e la cache locale del watch "
            "alla ricezione della revoca."
        )

        def without_current_wear_disclosure(relative: str) -> str:
            text = (ROOT / relative).read_text(encoding="utf-8")
            if relative == "app/src/main/res/values/strings.xml":
                return text.replace(required_fragment, "", 1)
            return text

        errors = POLICY.verification_errors(without_current_wear_disclosure)
        self.assertIn(f"Android in-app disclosure: missing {required_fragment!r}", errors)

    def test_jbb_community_fallback_disclosures_are_checked(self) -> None:
        privacy_fragment = "tier primario fallisce"
        source_policy_fragment = "metadati di rete ordinari"

        def without_jbb_disclosures(relative: str) -> str:
            text = (ROOT / relative).read_text(encoding="utf-8")
            if relative == "PRIVACY.md":
                return text.replace(privacy_fragment, "", 1)
            if relative == "docs/data-sources/SOURCE_POLICY.md":
                return text.replace(source_policy_fragment, "", 1)
            return text

        errors = POLICY.verification_errors(without_jbb_disclosures)
        self.assertIn(f"PRIVACY.md: missing {privacy_fragment!r}", errors)
        self.assertIn(
            f"Community fallback data-source policy: missing {source_policy_fragment!r}",
            errors,
        )

    def test_wear_choice_row_describes_the_implemented_watch_side_revocation(self) -> None:
        # The "Scelte dell'utente" row must not fall back to "delete data or uninstall": the
        # phone deletes the Data Item and the watch clears its cache when the revocation lands.
        current_row = (
            "| Interrompere Wear | Disattivare la condivisione nell'app Android: il telefono richiede "
            "la cancellazione del Data Item"
        )
        stale_row = (
            "| Interrompere Wear | Disabilitare la condivisione per nuovi invii; per cancellare la copia "
            "già ricevuta, cancellare dati o disinstallare l'app Wear."
        )

        def with_stale_row(relative: str) -> str:
            text = (ROOT / relative).read_text(encoding="utf-8")
            if relative == "PRIVACY.md":
                self.assertIn(current_row, text)
                return text.replace(current_row, stale_row, 1)
            return text

        self.assertIn(f"PRIVACY.md: missing {current_row!r}", POLICY.verification_errors(with_stale_row))

    def test_iphone_and_apple_watch_disclosures_are_checked(self) -> None:
        # App Store Connect needs a policy that covers the app being submitted: the iOS section
        # must name the UserDefaults keys, the Watch App Group and the revocation signal.
        for fragment in (
            "## App iPhone e Apple Watch",
            "`it.binario.ios.saved-routes.v1`",
            "`group.it.binario.watch`",
            "### English summary (iPhone and Apple Watch)",
        ):
            with self.subTest(fragment=fragment):
                def without_fragment(relative: str, fragment: str = fragment) -> str:
                    text = (ROOT / relative).read_text(encoding="utf-8")
                    # Several fragments recur in the English summary: drop every occurrence.
                    return text.replace(fragment, "") if relative == "PRIVACY.md" else text

                self.assertIn(f"PRIVACY.md: missing {fragment!r}", POLICY.verification_errors(without_fragment))

    def test_code_host_inventory_covers_runtime_endpoints_and_skips_xml_namespaces(self) -> None:
        hosts = POLICY.code_hosts()
        self.assertTrue(
            {"prm.rfi.it", "jbb.ghsq.de", "www.rfi.it", "gtfs.api.opendatahub.com", "proxy.transport.data.gouv.fr"} <= hosts
        )
        self.assertFalse({"apache.org", "xml.org", "javax.xml.xmlconstants", "schemas.android.com"} & hosts)
        self.assertFalse(any(":" in host or host != host.lower() for host in hosts))

    def test_hosts_built_in_code_are_found_too(self) -> None:
        # Audit ci-docs-8: Android asks viaggiatreno.it through URL("https", "viaggiatreno.it", url.file), with no
        # literal URL, and the iPhone names its hosts in constants.
        self.assertIn("viaggiatreno.it", POLICY.code_hosts())
        samples = {
            'target = URL("https", "alt.example.org", url.file)': "alt.example.org",
            'const val MIRROR_HOST = "mirror.example.org"': "mirror.example.org",
            'static let viaggiaTrenoApexHost = "apex.example.org"': "apex.example.org",
            'alternate.host = "other.example.org"': "other.example.org",
            'private let feedHost: String = "feed.example.org"': "feed.example.org",
        }
        for line, host in samples.items():
            with self.subTest(line=line):
                self.assertEqual({host}, POLICY.hosts_in(line))
        for line in ('val label = "transport.opendata.ch"', 'if (url.host == "www.example.org")', 'host = "localhost"'):
            with self.subTest(line=line):
                self.assertEqual(set(), POLICY.hosts_in(line))

    def test_every_iphone_host_must_be_disclosed_in_the_privacy_policy(self) -> None:
        hosts = POLICY.ios_code_hosts()
        self.assertTrue({"www.viaggiatreno.it", "viaggiatreno.it", "iechub.rfi.it", "italoinviaggio.italotreno.com"} <= hosts)
        self.assertEqual([], POLICY.verification_errors(ios_hosts=hosts))
        errors = POLICY.verification_errors(ios_hosts=hosts | {"telemetry.example.invalid"})
        self.assertIn("PRIVACY.md: iPhone host `telemetry.example.invalid` not disclosed", errors)

    def test_every_host_in_app_code_must_be_classified_in_the_data_safety_table(self) -> None:
        hosts = POLICY.code_hosts()
        self.assertEqual([], POLICY.verification_errors(hosts=hosts))
        errors = POLICY.verification_errors(hosts=hosts | {"telemetry.example.invalid"})
        self.assertIn("Play Data safety host table: missing `telemetry.example.invalid`", errors)
        errors = POLICY.verification_errors(hosts=hosts - {"prm.rfi.it"})
        self.assertIn("Play Data safety host table: stale `prm.rfi.it` no longer in app code", errors)

        def without_jbb_row(relative: str) -> str:
            text = (ROOT / relative).read_text(encoding="utf-8")
            if relative == POLICY.DATA_SAFETY_PATH:
                return text.replace("| `jbb.ghsq.de` |", "| jbb.ghsq.de |", 1)
            return text

        errors = POLICY.verification_errors(without_jbb_row, hosts=hosts)
        self.assertIn("Play Data safety host table: missing `jbb.ghsq.de`", errors)

    def test_expo_privacy_disclosure_requires_rendering_and_catalog_copy(self) -> None:
        render_fragment = 't("settings.privacyDisclosure")'
        catalog_fragment = "Binario non registra audio"

        def without_expo_disclosure(relative: str) -> str:
            text = (ROOT / relative).read_text(encoding="utf-8")
            if relative == "companion-expo/app/(tabs)/settings.tsx":
                return text.replace(render_fragment, "", 1)
            if relative == "companion-expo/lib/i18n.ts":
                return text.replace(catalog_fragment, "", 1)
            return text

        errors = POLICY.verification_errors(without_expo_disclosure)
        self.assertIn(
            f"Expo in-app disclosure rendering: missing {render_fragment!r}",
            errors,
        )
        self.assertIn(
            f"Expo Italian privacy catalog: missing {catalog_fragment!r}",
            errors,
        )

    def test_foreign_pack_pins_are_a_required_host_source(self) -> None:
        with tempfile.TemporaryDirectory(prefix="privacy-hosts-") as temporary:
            root = android_fixture_tree(Path(temporary), sample_pins())
            self.assertEqual(FOREIGN_DOWNLOAD_HOSTS, POLICY.code_hosts(root))
            self.assertEqual([], POLICY.missing_host_sources(root))

        # Licence gate with no pack pinned: the file names no URL and is still present.
        with tempfile.TemporaryDirectory(prefix="privacy-hosts-") as temporary:
            root = android_fixture_tree(Path(temporary), sample_pins(pinned=()))
            self.assertEqual(FOREIGN_DOWNLOAD_HOSTS - {"github.com"}, POLICY.code_hosts(root))
            self.assertEqual([], POLICY.missing_host_sources(root))

        # A missing pins file is an error, not a crash and not a silently smaller inventory.
        with tempfile.TemporaryDirectory(prefix="privacy-hosts-") as temporary:
            root = android_fixture_tree(Path(temporary), None)
            self.assertEqual(FOREIGN_DOWNLOAD_HOSTS - {"github.com"}, POLICY.code_hosts(root))
            self.assertEqual([f"{PINS}: required host source file missing"], POLICY.missing_host_sources(root))

    def test_foreign_download_hosts_are_classified_in_the_data_safety_table(self) -> None:
        # With the downloader's constants in the app code (as after the Android work is merged) the table matches.
        data_safety = repository_text(POLICY.DATA_SAFETY_PATH)
        self.assertEqual([], POLICY.host_inventory_errors(data_safety, POLICY.code_hosts() | FOREIGN_DOWNLOAD_HOSTS))
        for row in ("| `github.com` | A, E, F |", "| `release-assets.githubusercontent.com` | A |",
                    "| `objects.githubusercontent.com` | A |"):
            with self.subTest(row=row):
                self.assertIn(row, data_safety)

    def test_foreign_download_hosts_named_by_the_iphone_are_disclosed(self) -> None:
        swift = (
            f'static let releaseDownloadPrefix = "{RELEASE_PREFIX}"\n'
            'static let releaseAssetsHost = "release-assets.githubusercontent.com"\n'
            'static let legacyReleaseAssetsHost = "objects.githubusercontent.com"\n'
        )
        hosts = POLICY.hosts_in(swift)
        self.assertEqual(FOREIGN_DOWNLOAD_HOSTS, hosts)
        self.assertEqual([], POLICY.ios_host_errors(repository_text("PRIVACY.md"), hosts))

    def test_foreign_pack_in_app_line_is_checked_inside_its_own_string(self) -> None:
        self.assertEqual([], POLICY.string_disclosure_errors(with_foreign_privacy_line))
        self.assertFalse(any(error.startswith("Android in-app disclosure")
                             for error in POLICY.verification_errors(with_foreign_privacy_line)))

        def without_line(relative: str) -> str:
            text = repository_text(relative)
            return with_settings_line(text, None) if relative == STRINGS else text

        missing = "Android in-app disclosure: missing string 'settings_privacy_foreign_packs'"
        self.assertEqual([missing], POLICY.string_disclosure_errors(without_line))
        self.assertIn(missing, POLICY.verification_errors(without_line))

        # The same words in another string (such as the download dialog's) do not count for the settings line.
        def words_in_another_string(relative: str) -> str:
            text = repository_text(relative)
            if relative != STRINGS:
                return text
            text = with_settings_line(text, "Orari completi dei paesi confinanti: si scaricano solo se tocchi «Scarica».")
            return text.replace(
                "</resources>", f'    <string name="fixture_other_string">{FOREIGN_PRIVACY_FRAGMENT}.</string>\n</resources>', 1)

        self.assertEqual(
            [f"Android in-app disclosure: settings_privacy_foreign_packs missing {FOREIGN_PRIVACY_FRAGMENT!r}"],
            POLICY.string_disclosure_errors(words_in_another_string),
        )

    def test_foreign_pack_document_disclosures_are_checked(self) -> None:
        cases = {
            "PRIVACY.md": (
                "### Orari completi dei paesi confinanti (download su richiesta)",
                "### Orari completi dei paesi confinanti sull'iPhone",
                "solo dopo il tocco su «Scarica»",
                "`release-assets.githubusercontent.com`",
                "**quale paese**",
                "impronta SHA-256 coincide con quella fissata nella versione dell'app",
                "Nessun aggiornamento automatico",
                "GitHub sees the IP address, time, User-Agent and which country is downloaded",
            ),
            POLICY.DATA_SAFETY_PATH: (
                "### Orari completi dei paesi confinanti: perché la risposta resta «nessun dato raccolto»",
            ),
            "docs/store/APP_STORE_SUBMISSION.md": (
                "La seconda è il download degli **orari completi di un paese confinante**",
            ),
            "docs/data-sources/SOURCE_POLICY.md": (
                "solo se la sua `licence` nel manifest è confermata",
                "«Dati di terzi, non un prodotto ufficiale di Trenitalia, RFI, Italo, SBB, ÖBB o SNCF»",
            ),
        }
        labels = {relative: label for label, relative, _ in POLICY.REQUIRED_DISCLOSURES}
        for relative, fragments in cases.items():
            for fragment in fragments:
                with self.subTest(file=relative, fragment=fragment):
                    def without_fragment(path: str, relative: str = relative, fragment: str = fragment) -> str:
                        text = repository_text(path)
                        return text.replace(fragment, "") if path == relative else text

                    self.assertIn(fragment, repository_text(relative))
                    self.assertIn(f"{labels[relative]}: missing {fragment!r}", POLICY.verification_errors(without_fragment))


if __name__ == "__main__":
    unittest.main()
