#!/usr/bin/env python3
"""Install one reviewed XcodeGen binary from its official release archive."""
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import tempfile
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from typing import BinaryIO, Callable

MAX_ARCHIVE_BYTES = 100 * 1024 * 1024
MAX_BINARY_BYTES = 50 * 1024 * 1024
CHUNK_SIZE = 64 * 1024


class InstallError(RuntimeError):
    pass


class HttpsRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        require_https(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def require_https(url: str) -> None:
    try:
        parsed = urllib.parse.urlparse(url)
    except ValueError as error:
        raise InstallError("URL XcodeGen non valido") from error
    if parsed.scheme != "https" or not parsed.netloc:
        raise InstallError("XcodeGen richiede HTTPS")


def official_url(version: str) -> str:
    if not version or any(character not in "0123456789." for character in version):
        raise InstallError("versione XcodeGen non valida")
    return f"https://github.com/yonaskolb/XcodeGen/releases/download/{version}/xcodegen.zip"


def download_archive(
    url: str,
    destination: Path,
    *,
    opener: Callable[..., BinaryIO] | None = None,
) -> None:
    require_https(url)
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "Binario-CI-XcodeGen-installer/1"},
    )
    open_url = opener or urllib.request.build_opener(HttpsRedirectHandler()).open
    written = 0
    with open_url(request, timeout=60) as response, destination.open("wb") as output:
        final_url = response.geturl() if hasattr(response, "geturl") else url
        require_https(final_url)
        declared = response.headers.get("Content-Length")
        if declared and declared.isdigit() and int(declared) > MAX_ARCHIVE_BYTES:
            raise InstallError("archivio XcodeGen oltre limite dichiarato")
        while True:
            chunk = response.read(CHUNK_SIZE)
            if not chunk:
                break
            written += len(chunk)
            if written > MAX_ARCHIVE_BYTES:
                raise InstallError("archivio XcodeGen oltre limite streaming")
            output.write(chunk)
    if written == 0:
        raise InstallError("archivio XcodeGen vuoto")


def install_xcodegen(
    version: str,
    url: str,
    expected_sha256: str,
    destination: Path,
    *,
    opener: Callable[..., BinaryIO] | None = None,
) -> Path:
    if url != official_url(version):
        raise InstallError("URL XcodeGen non coincide con la release ufficiale")
    if len(expected_sha256) != 64 or any(character not in "0123456789abcdef" for character in expected_sha256):
        raise InstallError("checksum XcodeGen non valido")
    destination = destination.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="binario-xcodegen-") as temporary:
        archive_path = Path(temporary) / "xcodegen.zip"
        download_archive(url, archive_path, opener=opener)
        actual_sha256 = hashlib.sha256(archive_path.read_bytes()).hexdigest()
        if actual_sha256 != expected_sha256:
            raise InstallError("checksum XcodeGen non corrispondente")
        try:
            with zipfile.ZipFile(archive_path) as archive:
                member = archive.getinfo("xcodegen/bin/xcodegen")
                if member.is_dir() or member.file_size <= 0 or member.file_size > MAX_BINARY_BYTES:
                    raise InstallError("binario XcodeGen assente o oltre limite")
                with archive.open(member) as source, destination.open("wb") as output:
                    shutil.copyfileobj(source, output, CHUNK_SIZE)
        except (KeyError, zipfile.BadZipFile) as error:
            raise InstallError("archivio XcodeGen non valido") from error
    os.chmod(destination, 0o755)
    return destination


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--destination", type=Path, required=True)
    arguments = parser.parse_args()
    install_xcodegen(
        arguments.version,
        arguments.url,
        arguments.sha256,
        arguments.destination,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
