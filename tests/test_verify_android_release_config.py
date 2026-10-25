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
