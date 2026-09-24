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


if __name__ == "__main__":
    unittest.main()
