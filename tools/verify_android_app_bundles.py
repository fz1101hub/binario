#!/usr/bin/env python3
"""Validate unsigned Android App Bundles before signing or Play upload."""
from __future__ import annotations

import argparse
import hashlib
import sys
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BUNDLES = {
    "phone": ROOT / "app" / "build" / "outputs" / "bundle" / "release" / "app-release.aab",
    "wear": ROOT / "wear" / "build" / "outputs" / "bundle" / "release" / "wear-release.aab",
}
MAX_BUNDLE_BYTES = 200 * 1024 * 1024
MAX_MEMBERS = 100_000
MAX_UNCOMPRESSED_BYTES = 1024 * 1024 * 1024
REQUIRED_FILES = {"BundleConfig.pb", "base/manifest/AndroidManifest.xml"}
SIGNATURE_SUFFIXES = (".RSA", ".DSA", ".EC", ".SF")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_bundle(path: Path) -> list[str]:
    problems: list[str] = []
    try:
        size = path.stat().st_size
    except OSError as error:
        return [f"bundle non leggibile: {error}"]
    if size <= 0 or size > MAX_BUNDLE_BYTES:
        problems.append(f"dimensione bundle non ammessa: {size} byte")

    try:
        with zipfile.ZipFile(path) as bundle:
            members = bundle.infolist()
            limits_exceeded = False
            if len(members) > MAX_MEMBERS:
                problems.append(f"troppi membri ZIP: {len(members)}")
                limits_exceeded = True
            names: set[str] = set()
            total_uncompressed = 0
            for member in members:
                name = member.filename
                posix = PurePosixPath(name)
                if not name or posix.is_absolute() or ".." in posix.parts or "\\" in name:
                    problems.append(f"percorso ZIP non sicuro: {name!r}")
                if name in names:
                    problems.append(f"membro ZIP duplicato: {name}")
                names.add(name)
                upper_name = name.upper()
                if upper_name.startswith("META-INF/") and upper_name.endswith(SIGNATURE_SUFFIXES):
                    problems.append(f"firma JAR inattesa nel bundle unsigned: {name}")
                if member.flag_bits & 0x1:
                    problems.append(f"membro ZIP cifrato non ammesso: {name}")
                total_uncompressed += member.file_size
                if total_uncompressed > MAX_UNCOMPRESSED_BYTES:
                    problems.append("contenuto decompresso oltre il limite")
                    limits_exceeded = True
                    break
            missing = REQUIRED_FILES - names
            if missing:
                problems.append(f"file App Bundle obbligatori assenti: {sorted(missing)}")
            if not any(name.startswith("base/dex/") and name.endswith(".dex") for name in names):
                problems.append("DEX base assente")
            if not limits_exceeded:
                bad_member = bundle.testzip()
                if bad_member:
                    problems.append(f"CRC ZIP non valido: {bad_member}")
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        problems.append(f"App Bundle ZIP non valido: {error}")
    return problems


def verify_reproducible(path: Path, reference: Path) -> list[str]:
    try:
        current_digest = sha256_file(path)
        reference_digest = sha256_file(reference)
    except OSError as error:
        return [f"confronto riproducibilità non leggibile: {error}"]
    if current_digest != reference_digest:
        return [
            "build non byte-riproducibile: "
            f"sha256:{current_digest} != riferimento sha256:{reference_digest}"
        ]
    return []


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--phone", type=Path, default=DEFAULT_BUNDLES["phone"])
    parser.add_argument("--wear", type=Path, default=DEFAULT_BUNDLES["wear"])
    parser.add_argument("--reference-phone", type=Path)
    parser.add_argument("--reference-wear", type=Path)
    args = parser.parse_args(argv)
    failed = False
    for label, path, reference in (
        ("phone", args.phone, args.reference_phone),
        ("wear", args.wear, args.reference_wear),
    ):
        problems = verify_bundle(path)
        if reference is not None:
            problems.extend(verify_reproducible(path, reference))
        if problems:
            failed = True
            for problem in problems:
                print(f"Android App Bundle ({label}): FAIL: {problem}", file=sys.stderr)
        else:
            details = []
            if reference is not None:
                details.append("byte-riproducibile")
            details.extend((f"{path.stat().st_size} bytes", f"sha256:{sha256_file(path)}"))
            print(f"Android App Bundle ({label}): PASS ({', '.join(details)}).")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
