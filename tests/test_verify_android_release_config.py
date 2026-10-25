#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
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

    def test_wear_version_code_is_derived_from_the_phone_code_in_a_disjoint_range(self) -> None:
        stale_wear_property = replacing(
            "gradle.properties",
            "BINARIO_APP_VERSION_CODE=35\n",
            "BINARIO_APP_VERSION_CODE=35\nBINARIO_WEAR_VERSION_CODE=35\n",
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
            "BINARIO_APP_VERSION_CODE=35\n",
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


if __name__ == "__main__":
    unittest.main()
