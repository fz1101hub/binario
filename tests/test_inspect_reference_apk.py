from pathlib import Path
import io
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from tools.inspect_reference_apk import checked_members, dex_strings, inspect_package, rail_reference


def dex(values):
    header = bytearray(112 + len(values) * 4)
    header[:8] = b"dex\n035\0"
    struct.pack_into("<II", header, 56, len(values), 112)
    payload = bytearray()
    for index, value in enumerate(values):
        struct.pack_into("<I", header, 112 + index * 4, len(header) + len(payload))
        encoded = value.encode()
        length = len(value)
        while length >= 128:
            payload.append((length & 127) | 128)
            length >>= 7
        payload.append(length)
        payload.extend(encoded + b"\0")
    return bytes(header + payload)


class ReferenceAPKTests(unittest.TestCase):
    def test_dex_table_is_read_without_executing_code(self):
        self.assertEqual(["train", "sveglia"], list(dex_strings(dex(["train", "sveglia"]))))

    def test_corrupt_offsets_are_rejected(self):
        broken = bytearray(dex(["train"]))
        struct.pack_into("<I", broken, 112, len(broken) + 99)
        with self.assertRaises(ValueError):
            list(dex_strings(bytes(broken)))

    def test_queries_unknown_paths_and_userinfo_do_not_escape(self):
        result = rail_reference("https://app.lefrecce.it/Channels.Mobile.BFF/api/info/mobility/status/secret-value?token=PRIVATE#secret")
        self.assertNotIn("PRIVATE", str(result))
        self.assertNotIn("secret-value", str(result))
        self.assertEqual("/{redacted}", rail_reference("https://user:password@app.lefrecce.it/api/info")["path"])
        self.assertIsNone(rail_reference("https://eviltrenitalia.com/api/info"))

    def test_archive_budget_is_checked_before_members_are_read(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr("large", b"123456")
        with zipfile.ZipFile(io.BytesIO(data.getvalue())) as archive, patch("tools.inspect_reference_apk.MAX_MEMBER", 5):
            with self.assertRaises(ValueError):
                checked_members(archive)

    def test_xapk_reports_only_normalized_evidence_and_checksums(self):
        apk = io.BytesIO()
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("classes.dex", dex(["https://www.viaggiatreno.it/resteasy/viaggiatreno/partenze?token=PRIVATE", "sveglia fermata"]))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "reference.xapk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("manifest.json", '{"package_name":"it.example.app","version_name":"1.1.9"}')
                archive.writestr("base.apk", apk.getvalue())
            result = inspect_package(path)
        self.assertEqual(1, result["dex_files"])
        self.assertEqual(1, result["feature_string_evidence"]["stop_reminders"])
        self.assertNotIn("PRIVATE", str(result))
        self.assertEqual(64, len(result["sha256"]))
        self.assertFalse(result["matches_reference_blob"])

    def test_an_archive_without_dex_is_not_presented_as_an_analysis(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "reference.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("manifest.json", "{}")
            with self.assertRaises(ValueError):
                inspect_package(path)
