#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "verify_embedded_assets",
    ROOT / "tools" / "verify_embedded_assets.py",
)
assert SPEC and SPEC.loader
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class VerifyEmbeddedAssetsTest(unittest.TestCase):
    def test_default_freshness_window_is_thirty_days(self) -> None:
        self.assertEqual(30, VERIFIER.DEFAULT_MIN_VALID_DAYS)
        self.assertEqual(30, VERIFIER.parse_args([]).min_valid_days)

    def test_explicit_freshness_window_remains_supported(self) -> None:
        self.assertEqual(45, VERIFIER.parse_args(["--min-valid-days", "45"]).min_valid_days)


if __name__ == "__main__":
    unittest.main()
