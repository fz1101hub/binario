#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import importlib.util
import io
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/install_xcodegen.py"
SPEC = importlib.util.spec_from_file_location("install_xcodegen", SCRIPT)
assert SPEC and SPEC.loader
installer = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = installer
SPEC.loader.exec_module(installer)


class FakeResponse:
    def __init__(self, payload: bytes, url: str) -> None:
        self.stream = io.BytesIO(payload)
        self.url = url
        self.headers = {"Content-Length": str(len(payload))}

    def read(self, size: int = -1) -> bytes:
        return self.stream.read(size)

    def geturl(self) -> str:
        return self.url

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, *_: object) -> None:
        return None


def archive_payload() -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("xcodegen/bin/xcodegen", b"reviewed-binary")
    return output.getvalue()


class InstallXcodeGenTest(unittest.TestCase):
    def test_installs_only_reviewed_official_archive(self) -> None:
        payload = archive_payload()
        version = "2.46.0"
        url = installer.official_url(version)
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "xcodegen"
            installer.install_xcodegen(
                version,
                url,
                hashlib.sha256(payload).hexdigest(),
                destination,
                opener=lambda *_args, **_kwargs: FakeResponse(payload, url),
            )
            self.assertEqual(b"reviewed-binary", destination.read_bytes())
            self.assertTrue(destination.stat().st_mode & 0o100)

    def test_rejects_alternative_url_before_network(self) -> None:
        called = False

        def opener(*_args: object, **_kwargs: object) -> FakeResponse:
            nonlocal called
            called = True
            return FakeResponse(b"", "https://example.invalid/xcodegen.zip")

        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(installer.InstallError, "release ufficiale"):
                installer.install_xcodegen(
                    "2.46.0",
                    "https://example.invalid/xcodegen.zip",
                    "0" * 64,
                    Path(temporary) / "xcodegen",
                    opener=opener,
                )
        self.assertFalse(called)

    def test_rejects_checksum_mismatch_before_extraction(self) -> None:
        payload = archive_payload()
        version = "2.46.0"
        url = installer.official_url(version)
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "xcodegen"
            with self.assertRaisesRegex(installer.InstallError, "checksum"):
                installer.install_xcodegen(
                    version,
                    url,
                    "0" * 64,
                    destination,
                    opener=lambda *_args, **_kwargs: FakeResponse(payload, url),
                )
            self.assertFalse(destination.exists())


if __name__ == "__main__":
    unittest.main()
