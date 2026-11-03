#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
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


if __name__ == "__main__":
    unittest.main()
