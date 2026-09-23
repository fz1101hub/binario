#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "verify_android_app_bundles",
    ROOT / "tools" / "verify_android_app_bundles.py",
)
assert SPEC and SPEC.loader
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)


class VerifyAndroidAppBundlesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="aab-policy-")
        self.path = Path(self.temp.name) / "release.aab"

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_bundle(self, entries: dict[str, bytes]) -> None:
        with zipfile.ZipFile(self.path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, content in entries.items():
                archive.writestr(name, content)

    def test_accepts_minimal_well_formed_bundle(self) -> None:
        self.write_bundle(
            {
                "BundleConfig.pb": b"config",
                "base/manifest/AndroidManifest.xml": b"manifest",
                "base/dex/classes.dex": b"dex\n035\x00",
            }
        )
        self.assertEqual([], POLICY.verify_bundle(self.path))
        self.assertEqual(64, len(POLICY.sha256_file(self.path)))

    def test_rejects_missing_manifest_and_dex(self) -> None:
        self.write_bundle({"BundleConfig.pb": b"config"})
        problems = POLICY.verify_bundle(self.path)
        self.assertTrue(any("obbligatori assenti" in problem for problem in problems))
        self.assertTrue(any("DEX base assente" in problem for problem in problems))

    def test_rejects_jar_signature_in_unsigned_bundle(self) -> None:
        self.write_bundle(
            {
                "BundleConfig.pb": b"config",
                "base/manifest/AndroidManifest.xml": b"manifest",
                "base/dex/classes.dex": b"dex\n035\x00",
                "META-INF/BINARIO.SF": b"signature metadata",
                "META-INF/BINARIO.RSA": b"signature block",
            }
        )
        problems = POLICY.verify_bundle(self.path)
        self.assertEqual(2, sum("firma JAR inattesa" in problem for problem in problems))

    def test_reproducibility_requires_identical_bytes(self) -> None:
        reference = Path(self.temp.name) / "reference.aab"
        self.write_bundle(
            {
                "BundleConfig.pb": b"config",
                "base/manifest/AndroidManifest.xml": b"manifest",
                "base/dex/classes.dex": b"dex\n035\x00",
            }
        )
        reference.write_bytes(self.path.read_bytes())
        self.assertEqual([], POLICY.verify_reproducible(self.path, reference))

        with reference.open("ab") as stream:
            stream.write(b"different")
        problems = POLICY.verify_reproducible(self.path, reference)
        self.assertTrue(any("non byte-riproducibile" in problem for problem in problems))

    def test_rejects_traversal_and_duplicate_members(self) -> None:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.path, "w") as archive:
                archive.writestr("BundleConfig.pb", b"first")
                archive.writestr("BundleConfig.pb", b"second")
                archive.writestr("base/manifest/AndroidManifest.xml", b"manifest")
                archive.writestr("base/dex/classes.dex", b"dex")
                archive.writestr("../outside", b"bad")
        problems = POLICY.verify_bundle(self.path)
        self.assertTrue(any("duplicato" in problem for problem in problems))
        self.assertTrue(any("non sicuro" in problem for problem in problems))

    def test_rejects_missing_or_corrupt_archive(self) -> None:
        self.assertTrue(any("non leggibile" in problem for problem in POLICY.verify_bundle(self.path)))
        self.path.write_bytes(b"not-a-zip")
        self.assertTrue(any("ZIP non valido" in problem for problem in POLICY.verify_bundle(self.path)))

    def test_stops_before_crc_scan_after_uncompressed_limit(self) -> None:
        self.write_bundle(
            {
                "BundleConfig.pb": b"config",
                "base/manifest/AndroidManifest.xml": b"manifest",
                "base/dex/classes.dex": b"dex",
            }
        )
        with mock.patch.object(POLICY, "MAX_UNCOMPRESSED_BYTES", 1):
            with mock.patch.object(zipfile.ZipFile, "testzip", side_effect=AssertionError("must not scan")):
                problems = POLICY.verify_bundle(self.path)
        self.assertTrue(any("decompresso oltre" in problem for problem in problems))


if __name__ == "__main__":
    unittest.main()
