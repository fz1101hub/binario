#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import struct
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


def encode_varint(value: int) -> bytes:
    result = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            result.append(byte | 0x80)
        else:
            result.append(byte)
            return bytes(result)


def proto_string(field_number: int, value: str | bytes) -> bytes:
    payload = value.encode("utf-8") if isinstance(value, str) else value
    return encode_varint((field_number << 3) | 2) + encode_varint(len(payload)) + payload


def manifest_proto(
    package: str = "it.binario.app",
    version_code: str = "35",
    version_name: str = "0.20.4",
    root_namespace: str = "",
) -> bytes:
    def attribute(name: str, value: str, namespace: str = "") -> bytes:
        fields = b""
        if namespace:
            fields += proto_string(1, namespace)
        fields += proto_string(2, name) + proto_string(3, value)
        return proto_string(4, fields)

    attributes = b"".join(
        attribute(name, value, namespace)
        for name, value, namespace in (
            ("package", package, ""),
            ("versionCode", version_code, POLICY.ANDROID_NAMESPACE),
            ("versionName", version_name, POLICY.ANDROID_NAMESPACE),
        )
    )
    element = (
        (proto_string(2, root_namespace) if root_namespace else b"")
        + proto_string(3, "manifest")
        + attributes
    )
    return proto_string(1, element)


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

    def valid_entries(self, manifest: bytes | None = None) -> dict[str, bytes]:
        return {
            "BundleConfig.pb": b"config",
            "base/manifest/AndroidManifest.xml": manifest or manifest_proto(),
            "base/dex/classes.dex": b"dex\n035\x00",
        }

    def test_accepts_minimal_well_formed_bundle(self) -> None:
        self.write_bundle(self.valid_entries())
        expected = {
            "package": "it.binario.app",
            "versionCode": "35",
            "versionName": "0.20.4",
        }
        self.assertEqual([], POLICY.verify_bundle(self.path, expected))
        self.assertEqual(64, len(POLICY.sha256_file(self.path)))

    def test_root_profile_moves_wear_version_code_into_its_own_range(self) -> None:
        expected = POLICY.expected_manifests("root")
        phone_code = int(expected["phone"]["versionCode"])
        self.assertEqual("it.binario.app", expected["phone"]["package"])
        self.assertEqual("it.binario.app", expected["wear"]["package"])
        self.assertEqual(expected["phone"]["versionName"], expected["wear"]["versionName"])
        self.assertTrue(0 < phone_code < POLICY.WEAR_VERSION_CODE_OFFSET)
        self.assertEqual(str(POLICY.WEAR_VERSION_CODE_OFFSET + phone_code), expected["wear"]["versionCode"])

    def test_companion_profile_uses_separate_canonical_identity(self) -> None:
        expected = POLICY.expected_manifests("companion")
        self.assertEqual(
            {
                "phone": {
                    "package": "im.binario.companion",
                    "versionCode": "3",
                    "versionName": "0.1.1",
                },
                "wear": {
                    "package": "im.binario.companion",
                    "versionCode": "4",
                    "versionName": "0.1.1",
                },
            },
            expected,
        )

    def test_rejects_manifest_identity_mismatch(self) -> None:
        self.write_bundle(self.valid_entries(manifest_proto("example.invalid", "99", "9.9.9")))
        expected = {
            "package": "it.binario.app",
            "versionCode": "35",
            "versionName": "0.20.4",
        }
        problems = POLICY.verify_bundle(self.path, expected)
        self.assertEqual(3, sum("manifest " in problem and "inatteso" in problem for problem in problems))

    def test_rejects_malformed_or_oversized_manifest_proto(self) -> None:
        duplicate_package = proto_string(2, "package") + proto_string(3, "it.binario.app")
        wrong_namespace_version = (
            proto_string(1, "urn:invalid")
            + proto_string(2, "versionCode")
            + proto_string(3, "35")
        )
        malformed_manifests = (
            b"not-a-protobuf-manifest",
            manifest_proto() + encode_varint(8) + b"\x01",
            manifest_proto() + proto_string(2, "oneof text wins"),
            manifest_proto(root_namespace="urn:invalid"),
            proto_string(
                1,
                proto_string(3, "manifest")
                + proto_string(4, duplicate_package)
                + proto_string(4, duplicate_package),
            ),
            proto_string(
                1,
                proto_string(3, "manifest")
                + proto_string(4, duplicate_package)
                + proto_string(4, wrong_namespace_version),
            ),
        )
        for malformed in malformed_manifests:
            with self.subTest(manifest=malformed[:20]):
                self.write_bundle(self.valid_entries(malformed))
                problems = POLICY.verify_bundle(self.path, {})
                self.assertTrue(any("manifest protobuf non valido" in problem for problem in problems))

        self.write_bundle(self.valid_entries())
        with mock.patch.object(POLICY, "MAX_MANIFEST_BYTES", 1):
            problems = POLICY.verify_bundle(self.path, {})
        self.assertTrue(any("manifest protobuf oltre" in problem for problem in problems))

    def test_rejects_missing_manifest_and_dex(self) -> None:
        self.write_bundle({"BundleConfig.pb": b"config"})
        problems = POLICY.verify_bundle(self.path)
        self.assertTrue(any("obbligatori assenti" in problem for problem in problems))
        self.assertTrue(any("DEX base assente" in problem for problem in problems))

    def test_rejects_jar_signature_in_unsigned_bundle(self) -> None:
        entries = self.valid_entries()
        entries.update(
            {
                "META-INF/BINARIO.SF": b"signature metadata",
                "META-INF/BINARIO.RSA": b"signature block",
            }
        )
        self.write_bundle(entries)
        problems = POLICY.verify_bundle(self.path)
        self.assertEqual(2, sum("firma JAR inattesa" in problem for problem in problems))

    def test_reproducibility_requires_identical_bytes(self) -> None:
        reference = Path(self.temp.name) / "reference.aab"
        self.write_bundle(self.valid_entries())
        reference.write_bytes(self.path.read_bytes())
        self.assertEqual([], POLICY.verify_reproducible(self.path, reference))
        self.assertTrue(POLICY.verify_reproducible(self.path, self.path))

        with reference.open("ab") as stream:
            stream.write(b"different")
        problems = POLICY.verify_reproducible(self.path, reference)
        self.assertTrue(any("non byte-riproducibile" in problem for problem in problems))

    def test_reproducibility_references_are_phone_wear_atomic(self) -> None:
        reference = Path(self.temp.name) / "reference.aab"

        self.assertEqual([], POLICY.verify_reference_pair(None, None))
        self.assertEqual([], POLICY.verify_reference_pair(reference, reference))
        self.assertTrue(POLICY.verify_reference_pair(reference, None))
        self.assertTrue(POLICY.verify_reference_pair(None, reference))

    def test_rejects_traversal_and_duplicate_members(self) -> None:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.path, "w") as archive:
                archive.writestr("BundleConfig.pb", b"first")
                archive.writestr("BundleConfig.pb", b"second")
                archive.writestr("base/manifest/AndroidManifest.xml", manifest_proto())
                archive.writestr("base/dex/classes.dex", b"dex")
                archive.writestr("../outside", b"bad")
        problems = POLICY.verify_bundle(self.path)
        self.assertTrue(any("duplicato" in problem for problem in problems))
        self.assertTrue(any("non sicuro" in problem for problem in problems))

    def test_rejects_missing_or_corrupt_archive(self) -> None:
        self.assertTrue(any("non leggibile" in problem for problem in POLICY.verify_bundle(self.path)))
        self.path.write_bytes(b"not-a-zip")
        self.assertTrue(any("ZIP non valido" in problem for problem in POLICY.verify_bundle(self.path)))

    def test_reports_corrupt_manifest_deflate_without_traceback(self) -> None:
        self.write_bundle(self.valid_entries())
        raw = bytearray(self.path.read_bytes())
        with zipfile.ZipFile(self.path) as archive:
            info = archive.getinfo(POLICY.MANIFEST_PATH)
        local_header = raw.find(b"PK\x03\x04")
        self.assertGreaterEqual(local_header, 0)
        name_length, extra_length = struct.unpack_from("<HH", raw, local_header + 26)
        data_start = local_header + 30 + name_length + extra_length
        raw[data_start : data_start + info.compress_size] = b"\xff" * info.compress_size
        self.path.write_bytes(raw)

        problems = POLICY.verify_bundle(self.path)
        self.assertTrue(any("App Bundle ZIP non valido" in problem for problem in problems))

    def test_stops_before_crc_scan_after_uncompressed_limit(self) -> None:
        self.write_bundle(self.valid_entries())
        with mock.patch.object(POLICY, "MAX_UNCOMPRESSED_BYTES", 1):
            with mock.patch.object(zipfile.ZipFile, "testzip", side_effect=AssertionError("must not scan")):
                problems = POLICY.verify_bundle(self.path)
        self.assertTrue(any("decompresso oltre" in problem for problem in problems))

        with mock.patch.object(POLICY, "MAX_BUNDLE_BYTES", 1):
            with mock.patch.object(zipfile.ZipFile, "testzip", side_effect=AssertionError("must not scan")):
                problems = POLICY.verify_bundle(self.path)
        self.assertTrue(any("dimensione bundle non ammessa" in problem for problem in problems))


if __name__ == "__main__":
    unittest.main()
