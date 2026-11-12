#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "verify_android_release_config",
    ROOT / "tools" / "verify_android_release_config.py",
)
assert SPEC and SPEC.loader
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)


def replacing(relative: str, old: str, new: str = ""):
    def read_file(path: str) -> str:
        text = POLICY.read(path)
        if path == relative:
            assert old in text, old
            return text.replace(old, new, 1)
        return text

    return read_file


class VerifyAndroidReleaseConfigTest(unittest.TestCase):
    def test_repository_release_configuration_passes(self) -> None:
        POLICY.verify()

    def test_ci_must_run_the_wear_dependency_suppression_test(self) -> None:
        without_test = replacing(
            ".github/workflows/android-ci.yml",
            "          python3 -m unittest tests.test_wear_compose_gradle_dependency_suppressions\n",
        )
        with self.assertRaisesRegex(AssertionError, "test_wear_compose_gradle_dependency_suppressions"):
            POLICY.verify(without_test)

    def test_ci_must_run_the_string_resource_test(self) -> None:
        without_test = replacing(
            ".github/workflows/android-ci.yml",
            "        run: python3 -m unittest tests.test_android_string_resources\n",
        )
        with self.assertRaisesRegex(AssertionError, "test_android_string_resources"):
            POLICY.verify(without_test)

    def test_wear_version_code_is_derived_from_the_phone_code_in_a_disjoint_range(self) -> None:
        # Read the current line instead of hard-coding the number: every release bumps it.
        current = re.search(r"^BINARIO_APP_VERSION_CODE=\d+\n", POLICY.read("gradle.properties"), re.MULTILINE).group(0)
        stale_wear_property = replacing(
            "gradle.properties",
            current,
            current + current.replace("BINARIO_APP_", "BINARIO_WEAR_"),
        )
        with self.assertRaisesRegex(AssertionError, "BINARIO_WEAR_VERSION_CODE"):
            POLICY.verify(stale_wear_property)

        shared_override_without_offset = replacing(
            "wear/build.gradle.kts",
            "    .toInt() + wearVersionCodeOffset\n",
            "    .toInt()\n",
        )
        with self.assertRaisesRegex(AssertionError, "wearVersionCodeOffset"):
            POLICY.verify(shared_override_without_offset)

        phone_code_inside_wear_range = replacing(
            "gradle.properties",
            current,
            f"BINARIO_APP_VERSION_CODE={POLICY.WEAR_VERSION_CODE_OFFSET}\n",
        )
        with self.assertRaisesRegex(AssertionError, "below the Wear range"):
            POLICY.verify(phone_code_inside_wear_range)

    def test_ci_must_lint_both_release_variants(self) -> None:
        without_wear_lint = replacing(
            ".github/workflows/android-ci.yml",
            "          :app:lintRelease :wear:lintRelease\n",
            "          :app:lintRelease\n",
        )
        with self.assertRaisesRegex(AssertionError, ":wear:lintRelease"):
            POLICY.verify(without_wear_lint)


class StoreSourcesTest(unittest.TestCase):
    """Audit A7-05: the store value of the best-effort realtime sources is explicit and matches the documents."""

    def test_repository_store_sources_pass(self) -> None:
        POLICY.verify_store_sources()
        self.assertEqual("true", POLICY.STORE_PERSONAL_REALTIME_SOURCES)

    def test_implicit_or_different_store_values_are_refused(self) -> None:
        flag = "-PBINARIO_PERSONAL_REALTIME_SOURCES=true "
        for mutated, message in (
            (replacing(".github/workflows/android-ci.yml", flag), "exactly once"),
            (replacing(".github/workflows/android-ci.yml", flag, flag.replace("true", "false")), "exactly once"),
            (replacing(".github/workflows/android-ci.yml", "-Pbinario.personalSources=false "), "personalSources=false"),
            (replacing("app/build.gradle.kts",
                       'getByName("release") {\n            buildConfigField("boolean", "PERSONAL_REALTIME_SOURCES",\n                providers.gradleProperty("BINARIO_PERSONAL_REALTIME_SOURCES").orElse("true")',
                       'getByName("release") {\n            buildConfigField("boolean", "PERSONAL_REALTIME_SOURCES",\n                providers.gradleProperty("BINARIO_PERSONAL_REALTIME_SOURCES").orElse("false")'),
             "release default"),
            (replacing("docs/store/PLAY_DATA_SAFETY.md", "`-PBINARIO_PERSONAL_REALTIME_SOURCES=true`", "`true`"), "PLAY_DATA_SAFETY"),
        ):
            with self.subTest(message=message), self.assertRaisesRegex(AssertionError, message):
                POLICY.verify_store_sources(mutated)


if __name__ == "__main__":
    unittest.main()
