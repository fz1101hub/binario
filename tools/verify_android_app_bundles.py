#!/usr/bin/env python3
"""Validate unsigned Android App Bundles before signing or Play upload.

The phone bundle of the root profile also carries the punctuality statistics switch (``-Pbinario.punctualityStats``,
docs/data-sources/PUNCTUALITY_STATS.md): ``--punctuality-stats auto`` (default) requires the statistics when the
tree has ``app/src/main/assets/punctuality/stats_v1.json.gz`` and nothing otherwise; ``present`` always requires them;
``absent`` refuses anything under ``punctuality/``.  The Android Gradle plugin decompresses a ``.gz`` asset and drops
the extension, so the package carries ``base/assets/punctuality/stats_v1.json``, which must be the tree file
decompressed (a ``stats_v1.json.gz`` kept as it is must be identical to it).  ``--apk FILE`` checks
only that expectation on an APK (``assets/punctuality/``), for the build made with the switch off.

    python3 tools/verify_android_app_bundles.py
    python3 tools/verify_android_app_bundles.py --apk app/build/outputs/apk/release/app-release-unsigned.apk \\
        --punctuality-stats absent
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import re
import struct
import sys
import zlib
import zipfile
from pathlib import Path, PurePosixPath
from typing import Mapping

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BUNDLES = {
    "root": {
        "phone": ROOT / "app" / "build" / "outputs" / "bundle" / "release" / "app-release.aab",
        "wear": ROOT / "wear" / "build" / "outputs" / "bundle" / "release" / "wear-release.aab",
    },
    "companion": {
        "phone": ROOT / "companion-expo" / "android" / "app" / "build" / "outputs" / "bundle" / "release" / "app-release.aab",
        "wear": ROOT / "companion-expo" / "android" / "wear" / "build" / "outputs" / "bundle" / "release" / "wear-release.aab",
    },
}
MANIFEST_PATH = "base/manifest/AndroidManifest.xml"
MAX_BUNDLE_BYTES = 200 * 1024 * 1024
MAX_MEMBERS = 100_000
MAX_UNCOMPRESSED_BYTES = 1024 * 1024 * 1024
MAX_MANIFEST_BYTES = 4 * 1024 * 1024
REQUIRED_FILES = {"BundleConfig.pb", MANIFEST_PATH}
# Timetable packs whose licence allows personal use only (app/src/personal/assets, -Pbinario.personalSources):
# never in a bundle meant for Play.
PERSONAL_ASSET_PREFIX = "base/assets/gtfs-personal/"
# Unofficial network punctuality statistics: in every build unless -Pbinario.punctualityStats=false leaves the
# directory out (app/build.gradle.kts, ignoreAssetsPatterns "!punctuality").
PUNCTUALITY_DIRECTORY = "punctuality/"
PUNCTUALITY_ASSET = "punctuality/stats_v1.json.gz"
PUNCTUALITY_TREE_ASSET = ROOT / "app" / "src" / "main" / "assets" / PUNCTUALITY_ASSET
# The Android Gradle plugin decompresses a .gz asset while merging and drops the extension: the package carries the
# tree's statistics as plain JSON under this name (NetworkReliabilityRepository.ASSET_PATH), or, should a toolchain keep
# the file as it is, under PUNCTUALITY_ASSET.
PUNCTUALITY_PACKAGED_ASSET = "punctuality/stats_v1.json"
# The app reads at most this much decompressed JSON (NetworkReliabilityParser.Limits.maxJsonBytes).
PUNCTUALITY_MAX_JSON_BYTES = 64 * 1024 * 1024
PUNCTUALITY_EXPECTATIONS = ("auto", "present", "absent")
BUNDLE_ASSET_PREFIX = "base/assets/"
APK_ASSET_PREFIX = "assets/"
SIGNATURE_SUFFIXES = (".RSA", ".DSA", ".EC", ".SF")
SIGNATURE_BLOCK_SUFFIXES = (".RSA", ".DSA", ".EC")
JAR_MANIFEST_PATH = "META-INF/MANIFEST.MF"
NATIVE_LIBRARY = re.compile(r"^[^/]+/lib/([^/]+)/[^/]+\.so$")
# Devices with 16 KB pages run only 64-bit code: Play requires every PT_LOAD of
# these ABIs to be aligned to at least 16 KiB. 32-bit libraries are parsed but exempt.
PAGE_SIZE_16K_ABIS = {"arm64-v8a", "x86_64"}
MIN_LOAD_ALIGNMENT = 0x4000
MAX_ELF_HEADER_BYTES = 1024 * 1024
ELF_MAGIC = b"\x7fELF"
PT_LOAD = 1
# Header layouts after e_ident: (header format, header size, program header format, entry size).
ELF_LAYOUTS = {
    1: ("HHIIIIIHHHHHH", 52, "IIIIIIII", 32),
    2: ("HHIQQQIHHHHHH", 64, "IIQQQQQQ", 56),
}
ANDROID_PACKAGE = "it.binario.app"
# Mirrors wear/build.gradle.kts: the Wear versionCode is the phone code moved into its own range.
WEAR_VERSION_CODE_OFFSET = 1_000_000
ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
IDENTITY_NAMESPACES = {
    "package": "",
    "versionCode": ANDROID_NAMESPACE,
    "versionName": ANDROID_NAMESPACE,
}


class ProtoError(ValueError):
    """Raised when the AAPT protobuf manifest is malformed or unsupported."""


class ElfError(ValueError):
    """Raised when a native library header is malformed or unsupported."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _read_varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    for shift in range(0, 70, 7):
        if offset >= len(data):
            raise ProtoError("varint protobuf troncato")
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
    raise ProtoError("varint protobuf oltre 10 byte")


def _protobuf_fields(data: bytes) -> list[tuple[int, int, int | bytes]]:
    fields: list[tuple[int, int, int | bytes]] = []
    offset = 0
    while offset < len(data):
        key, offset = _read_varint(data, offset)
        field_number = key >> 3
        wire_type = key & 0x7
        if field_number == 0 or field_number > 0x1FFFFFFF:
            raise ProtoError(f"field number protobuf non valido: {field_number}")
        if wire_type == 0:
            value, offset = _read_varint(data, offset)
        elif wire_type == 1:
            end = offset + 8
            if end > len(data):
                raise ProtoError("fixed64 protobuf troncato")
            value = data[offset:end]
            offset = end
        elif wire_type == 2:
            length, offset = _read_varint(data, offset)
            end = offset + length
            if end > len(data):
                raise ProtoError("campo protobuf length-delimited troncato")
            value = data[offset:end]
            offset = end
        elif wire_type == 5:
            end = offset + 4
            if end > len(data):
                raise ProtoError("fixed32 protobuf troncato")
            value = data[offset:end]
            offset = end
        else:
            raise ProtoError(f"wire type protobuf non supportato: {wire_type}")
        fields.append((field_number, wire_type, value))
    return fields


def _single_bytes(
    fields: list[tuple[int, int, int | bytes]], field_number: int, context: str
) -> bytes:
    matches = [(wire, value) for number, wire, value in fields if number == field_number]
    if len(matches) != 1 or matches[0][0] != 2 or not isinstance(matches[0][1], bytes):
        raise ProtoError(f"{context}: atteso un solo campo {field_number} length-delimited")
    return matches[0][1]


def _decode_utf8(value: bytes, context: str) -> str:
    try:
        return value.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ProtoError(f"{context}: UTF-8 non valido") from error


def _optional_utf8(
    fields: list[tuple[int, int, int | bytes]], field_number: int, context: str
) -> str:
    matches = [(wire, value) for number, wire, value in fields if number == field_number]
    if not matches:
        return ""
    if len(matches) != 1 or matches[0][0] != 2 or not isinstance(matches[0][1], bytes):
        raise ProtoError(f"{context}: campo opzionale {field_number} non valido")
    return _decode_utf8(matches[0][1], context)


def parse_manifest_attributes(data: bytes) -> dict[str, str]:
    """Read root attributes from AAPT Resources.XmlNode without external tools."""
    node_fields = _protobuf_fields(data)
    if any(number == 2 for number, _, _ in node_fields):
        raise ProtoError("XmlNode contiene il membro oneof text invece del solo element")
    element = _single_bytes(node_fields, 1, "XmlNode.element")
    element_fields = _protobuf_fields(element)
    element_namespace = _optional_utf8(
        element_fields, 2, "XmlElement.namespace_uri"
    )
    if element_namespace:
        raise ProtoError(f"namespace root manifest inatteso: {element_namespace!r}")
    element_name = _decode_utf8(_single_bytes(element_fields, 3, "XmlElement.name"), "XmlElement.name")
    if element_name != "manifest":
        raise ProtoError(f"elemento root inatteso: {element_name!r}")

    attributes: dict[str, str] = {}
    for number, wire, raw_attribute in element_fields:
        if number != 4:
            continue
        if wire != 2 or not isinstance(raw_attribute, bytes):
            raise ProtoError("XmlElement.attribute: wire type non valido")
        attribute_fields = _protobuf_fields(raw_attribute)
        namespace = _optional_utf8(
            attribute_fields, 1, "XmlAttribute.namespace_uri"
        )
        name = _decode_utf8(
            _single_bytes(attribute_fields, 2, "XmlAttribute.name"), "XmlAttribute.name"
        )
        value = _decode_utf8(
            _single_bytes(attribute_fields, 3, f"XmlAttribute[{name}].value"),
            f"XmlAttribute[{name}].value",
        )
        if name in attributes:
            raise ProtoError(f"attributo manifest duplicato: {name}")
        expected_namespace = IDENTITY_NAMESPACES.get(name)
        if expected_namespace is not None and namespace != expected_namespace:
            raise ProtoError(
                f"namespace inatteso per {name}: {namespace!r}; atteso {expected_namespace!r}"
            )
        attributes[name] = value
    return attributes


def expected_manifests(profile: str = "root") -> dict[str, dict[str, str]]:
    if profile == "companion":
        identity = json.loads(
            (ROOT / "companion-expo" / "native" / "android" / "release-identity.json").read_text(
                encoding="utf-8"
            )
        )
        if (
            not isinstance(identity, dict)
            or set(identity) != {
                "schemaVersion",
                "applicationId",
                "versionName",
                "phoneVersionCode",
                "wearVersionCode",
            }
            or identity.get("schemaVersion") != 1
            or not isinstance(identity.get("applicationId"), str)
            or not isinstance(identity.get("versionName"), str)
            or type(identity.get("phoneVersionCode")) is not int
            or type(identity.get("wearVersionCode")) is not int
            or identity["applicationId"] != "im.binario.companion"
            or not re.fullmatch(r"\d+\.\d+\.\d+", identity["versionName"])
            or identity["phoneVersionCode"] <= 0
            or identity["wearVersionCode"] <= identity["phoneVersionCode"]
        ):
            raise ValueError("identità release Companion non valida")
        return {
            "phone": {
                "package": identity["applicationId"],
                "versionCode": str(identity["phoneVersionCode"]),
                "versionName": identity["versionName"],
            },
            "wear": {
                "package": identity["applicationId"],
                "versionCode": str(identity["wearVersionCode"]),
                "versionName": identity["versionName"],
            },
        }
    if profile != "root":
        raise ValueError(f"profilo AAB non supportato: {profile}")
    properties: dict[str, str] = {}
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if "=" not in line or line.lstrip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        properties[key.strip()] = value.strip()
    return {
        "phone": {
            "package": ANDROID_PACKAGE,
            "versionCode": properties["BINARIO_APP_VERSION_CODE"],
            "versionName": properties["BINARIO_VERSION_NAME"],
        },
        "wear": {
            "package": ANDROID_PACKAGE,
            "versionCode": str(WEAR_VERSION_CODE_OFFSET + int(properties["BINARIO_APP_VERSION_CODE"])),
            "versionName": properties["BINARIO_VERSION_NAME"],
        },
    }


def parse_load_alignments(stream) -> list[int]:
    """Read p_align of every PT_LOAD segment from an ELF program header table."""
    ident = stream.read(16)
    if len(ident) != 16 or ident[:4] != ELF_MAGIC:
        raise ElfError("magic ELF assente")
    elf_class, data_encoding, ident_version = ident[4], ident[5], ident[6]
    if ident_version != 1:
        raise ElfError(f"versione ELF non supportata: {ident_version}")
    if data_encoding not in (1, 2):
        raise ElfError(f"codifica ELF non supportata: {data_encoding}")
    order = "<" if data_encoding == 1 else ">"
    if elf_class not in ELF_LAYOUTS:
        raise ElfError(f"classe ELF non supportata: {elf_class}")
    header_format, header_size, phdr_format, phdr_size = ELF_LAYOUTS[elf_class]
    header = stream.read(header_size - 16)
    if len(header) != header_size - 16:
        raise ElfError("header ELF troncato")
    fields = struct.unpack(order + header_format, header)
    phoff, ehsize, phentsize, phnum = fields[4], fields[7], fields[8], fields[9]
    if ehsize != header_size or phentsize != phdr_size:
        raise ElfError("dimensioni header ELF incoerenti")
    if phnum == 0 or phnum >= 0xFFFF:
        raise ElfError(f"numero di program header non supportato: {phnum}")
    if phoff < header_size or phoff + phnum * phdr_size > MAX_ELF_HEADER_BYTES:
        raise ElfError("tabella program header ELF fuori dai limiti")
    skipped = phoff - header_size
    table = stream.read(skipped + phnum * phdr_size)[skipped:]
    if len(table) != phnum * phdr_size:
        raise ElfError("tabella program header ELF troncata")
    alignments = [
        entry[7]
        for index in range(phnum)
        for entry in [struct.unpack_from(order + phdr_format, table, index * phdr_size)]
        if entry[0] == PT_LOAD
    ]
    if not alignments:
        raise ElfError("nessun segmento PT_LOAD")
    return alignments


def verify_native_libraries(bundle: zipfile.ZipFile, members: list[zipfile.ZipInfo]) -> list[str]:
    problems: list[str] = []
    for member in members:
        abi = NATIVE_LIBRARY.fullmatch(member.filename).group(1)
        try:
            with bundle.open(member) as stream:
                alignments = parse_load_alignments(stream)
        except ElfError as error:
            problems.append(f"libreria nativa non valida {member.filename}: {error}")
            continue
        if abi not in PAGE_SIZE_16K_ABIS:
            continue
        misaligned = [align for align in alignments if align < MIN_LOAD_ALIGNMENT or align & (align - 1)]
        if misaligned:
            problems.append(
                f"segmento PT_LOAD non allineato a 16 KB in {member.filename}: "
                f"p_align {', '.join(hex(align) for align in misaligned)}"
            )
    return problems


def native_abis(path: Path) -> list[str]:
    with zipfile.ZipFile(path) as bundle:
        return sorted({match.group(1) for name in bundle.namelist() if (match := NATIVE_LIBRARY.fullmatch(name))})


def verify_manifest(data: bytes, expected: Mapping[str, str]) -> list[str]:
    try:
        attributes = parse_manifest_attributes(data)
    except ProtoError as error:
        return [f"manifest protobuf non valido: {error}"]
    problems: list[str] = []
    for name, expected_value in expected.items():
        actual = attributes.get(name)
        if actual != expected_value:
            problems.append(
                f"manifest {name} inatteso: {actual!r}; atteso {expected_value!r}"
            )
    return problems


def verify_jar_signature(names: set[str]) -> list[str]:
    """Require the jarsigner members Play expects on a signed App Bundle."""
    upper_names = {name.upper() for name in names}
    missing = []
    if JAR_MANIFEST_PATH not in upper_names:
        missing.append(JAR_MANIFEST_PATH)
    if not any(name.startswith("META-INF/") and name.endswith(".SF") for name in upper_names):
        missing.append("META-INF/*.SF")
    if not any(name.startswith("META-INF/") and name.endswith(SIGNATURE_BLOCK_SUFFIXES) for name in upper_names):
        missing.append("META-INF/*.RSA|DSA|EC")
    if missing:
        return [f"firma JAR assente nel bundle firmato: {', '.join(missing)}"]
    return []


def punctuality_problems(
    archive: zipfile.ZipFile,
    names: set[str],
    *,
    asset_prefix: str,
    expectation: str | None,
    tree_asset: Path | None = None,
) -> list[str]:
    """The punctuality statistics as ``expectation`` wants them (``None``: not checked); ``tree_asset`` defaults to
    the file of the source tree."""
    if expectation is None:
        return []
    tree_asset = tree_asset or PUNCTUALITY_TREE_ASSET
    if expectation not in PUNCTUALITY_EXPECTATIONS:
        return [f"attesa non valida per le statistiche di puntualità: {expectation!r}"]
    directory = asset_prefix + PUNCTUALITY_DIRECTORY
    packaged = sorted(name for name in names if name.startswith(directory))
    if expectation == "absent":
        if packaged:
            shown = ", ".join(packaged[:5]) + (f" e altri {len(packaged) - 5}" if len(packaged) > 5 else "")
            return [f"statistiche di puntualità nel pacchetto con -Pbinario.punctualityStats=false: {shown}"]
        return []
    in_tree = tree_asset.is_file()
    if expectation == "auto" and not in_tree:
        return []
    plain = asset_prefix + PUNCTUALITY_PACKAGED_ASSET
    kept = asset_prefix + PUNCTUALITY_ASSET
    if plain not in names and kept not in names:
        reason = f"presente in {tree_asset.relative_to(ROOT) if tree_asset.is_relative_to(ROOT) else tree_asset}" if in_tree else "richiesto"
        return [f"statistiche di puntualità assenti dal pacchetto: {plain} ({reason})"]
    if in_tree:
        tree_bytes = tree_asset.read_bytes()
        if plain in names:
            # The plain JSON must be the tree file decompressed, read within the app's own bound.
            try:
                with gzip.GzipFile(fileobj=io.BytesIO(tree_bytes)) as source:
                    expected = source.read(PUNCTUALITY_MAX_JSON_BYTES + 1)
            except (OSError, EOFError, zlib.error) as error:
                return [f"statistiche di puntualità dell'albero non leggibili come gzip: {error}"]
            if len(expected) > PUNCTUALITY_MAX_JSON_BYTES:
                return [f"statistiche di puntualità dell'albero oltre {PUNCTUALITY_MAX_JSON_BYTES} byte decompressi"]
            info = archive.getinfo(plain)
            if info.file_size != len(expected) or archive.read(info) != expected:
                return [f"statistiche di puntualità diverse dal file dell'albero: {plain}"]
        if kept in names:
            info = archive.getinfo(kept)
            if info.file_size != len(tree_bytes) or archive.read(info) != tree_bytes:
                return [f"statistiche di puntualità diverse dal file dell'albero: {kept}"]
    return []


def punctuality_detail(expectation: str | None, tree_asset: Path | None = None) -> str | None:
    """The PASS detail of a punctuality expectation that held."""
    if expectation is None:
        return None
    tree_asset = tree_asset or PUNCTUALITY_TREE_ASSET
    if expectation == "absent":
        return "statistiche di puntualità assenti come richiesto"
    if tree_asset.is_file():
        return "statistiche di puntualità presenti e identiche all'albero"
    return "statistiche di puntualità presenti" if expectation == "present" else "statistiche di puntualità non nell'albero: nessun requisito"


def verify_apk_punctuality(path: Path, expectation: str, tree_asset: Path | None = None) -> list[str]:
    """Only the punctuality expectation on an APK (a readable ZIP with safe member names)."""
    try:
        size = path.stat().st_size
    except OSError as error:
        return [f"APK non leggibile: {error}"]
    if size <= 0 or size > MAX_BUNDLE_BYTES:
        return [f"dimensione APK non ammessa: {size} byte"]
    problems: list[str] = []
    try:
        with zipfile.ZipFile(path) as apk:
            members = apk.infolist()
            if len(members) > MAX_MEMBERS:
                return [f"troppi membri ZIP: {len(members)}"]
            names = set()
            for member in members:
                posix = PurePosixPath(member.filename)
                if not member.filename or posix.is_absolute() or ".." in posix.parts or "\\" in member.filename:
                    problems.append(f"percorso ZIP non sicuro: {member.filename!r}")
                names.add(member.filename)
            if "AndroidManifest.xml" not in names or not any(name.endswith(".dex") for name in names):
                problems.append("APK senza AndroidManifest.xml o DEX")
            problems.extend(punctuality_problems(apk, names, asset_prefix=APK_ASSET_PREFIX, expectation=expectation,
                                                 tree_asset=tree_asset))
    except (OSError, zipfile.BadZipFile, RuntimeError, zlib.error) as error:
        problems.append(f"APK ZIP non valido: {error}")
    return problems


def verify_bundle(
    path: Path,
    expected_manifest: Mapping[str, str] | None = None,
    signed: bool = False,
    punctuality: str | None = None,
    tree_asset: Path | None = None,
) -> list[str]:
    problems: list[str] = []
    try:
        size = path.stat().st_size
    except OSError as error:
        return [f"bundle non leggibile: {error}"]
    if size <= 0 or size > MAX_BUNDLE_BYTES:
        return [f"dimensione bundle non ammessa: {size} byte"]

    try:
        with zipfile.ZipFile(path) as bundle:
            members = bundle.infolist()
            limits_exceeded = False
            if len(members) > MAX_MEMBERS:
                problems.append(f"troppi membri ZIP: {len(members)}")
                limits_exceeded = True
            names: set[str] = set()
            total_uncompressed = 0
            manifest_info: zipfile.ZipInfo | None = None
            native_libraries: list[zipfile.ZipInfo] = []
            for member in members:
                name = member.filename
                posix = PurePosixPath(name)
                if not name or posix.is_absolute() or ".." in posix.parts or "\\" in name:
                    problems.append(f"percorso ZIP non sicuro: {name!r}")
                if name in names:
                    problems.append(f"membro ZIP duplicato: {name}")
                names.add(name)
                if name == MANIFEST_PATH:
                    manifest_info = member
                if NATIVE_LIBRARY.fullmatch(name):
                    native_libraries.append(member)
                if name.startswith(PERSONAL_ASSET_PREFIX):
                    problems.append(f"pack orario solo per build personali nel bundle: {name}")
                upper_name = name.upper()
                if not signed and upper_name.startswith("META-INF/") and upper_name.endswith(SIGNATURE_SUFFIXES):
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
            if signed:
                problems.extend(verify_jar_signature(names))
            if not any(name.startswith("base/dex/") and name.endswith(".dex") for name in names):
                problems.append("DEX base assente")
            if manifest_info is not None and not limits_exceeded:
                if manifest_info.file_size > MAX_MANIFEST_BYTES:
                    problems.append("manifest protobuf oltre il limite")
                else:
                    manifest_data = bundle.read(manifest_info)
                    problems.extend(verify_manifest(manifest_data, expected_manifest or {}))
            if not limits_exceeded:
                problems.extend(verify_native_libraries(bundle, native_libraries))
                problems.extend(punctuality_problems(bundle, names, asset_prefix=BUNDLE_ASSET_PREFIX,
                                                     expectation=punctuality, tree_asset=tree_asset))
                bad_member = bundle.testzip()
                if bad_member:
                    problems.append(f"CRC ZIP non valido: {bad_member}")
    except (OSError, zipfile.BadZipFile, RuntimeError, zlib.error) as error:
        problems.append(f"App Bundle ZIP non valido: {error}")
    return problems


def verify_reproducible(path: Path, reference: Path) -> list[str]:
    if path.resolve() == reference.resolve():
        return ["riferimento di riproducibilità coincidente con il bundle corrente"]
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


def verify_reference_pair(phone: Path | None, wear: Path | None) -> list[str]:
    if (phone is None) != (wear is None):
        return ["i riferimenti di riproducibilità telefono e Wear devono essere forniti insieme"]
    return []


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", choices=sorted(DEFAULT_BUNDLES), default="root")
    parser.add_argument("--phone", type=Path)
    parser.add_argument("--wear", type=Path)
    parser.add_argument("--reference-phone", type=Path)
    parser.add_argument("--reference-wear", type=Path)
    parser.add_argument(
        "--signed",
        action="store_true",
        help="richiedi la firma JAR dell'upload key invece di rifiutarla (bundle pronti per Play)",
    )
    parser.add_argument(
        "--punctuality-stats",
        choices=PUNCTUALITY_EXPECTATIONS,
        help="statistiche di puntualità nel bundle telefono (profilo root, predefinito auto: richieste se l'albero ha "
             "il file) o nell'APK di --apk; absent per una build con -Pbinario.punctualityStats=false",
    )
    parser.add_argument("--apk", type=Path, help="verifica soltanto le statistiche di puntualità di questo APK")
    args = parser.parse_args(argv)
    if args.apk is not None:
        if args.punctuality_stats is None:
            parser.error("--apk richiede --punctuality-stats")
        problems = verify_apk_punctuality(args.apk, args.punctuality_stats)
        for problem in problems:
            print(f"Android APK: FAIL: {problem}", file=sys.stderr)
        if not problems:
            print(f"Android APK: PASS ({punctuality_detail(args.punctuality_stats)}, {args.apk.stat().st_size} bytes, "
                  f"sha256:{sha256_file(args.apk)}).")
        return 1 if problems else 0
    if args.profile != "root" and args.punctuality_stats is not None:
        parser.error("--punctuality-stats vale solo per il profilo root")
    punctuality = (args.punctuality_stats or "auto") if args.profile == "root" else None
    reference_problems = verify_reference_pair(args.reference_phone, args.reference_wear)
    if reference_problems:
        for problem in reference_problems:
            print(f"Android App Bundle: FAIL: {problem}", file=sys.stderr)
        return 1
    try:
        manifests = expected_manifests(args.profile)
    except (json.JSONDecodeError, KeyError, OSError, ValueError) as error:
        print(f"Android App Bundle: FAIL: configurazione versione non leggibile: {error}", file=sys.stderr)
        return 1

    failed = False
    for label, path, reference in (
        ("phone", args.phone or DEFAULT_BUNDLES[args.profile]["phone"], args.reference_phone),
        ("wear", args.wear or DEFAULT_BUNDLES[args.profile]["wear"], args.reference_wear),
    ):
        expectation = punctuality if label == "phone" else None
        problems = verify_bundle(path, manifests[label], signed=args.signed, punctuality=expectation)
        if reference is not None:
            problems.extend(verify_reproducible(path, reference))
        if problems:
            failed = True
            for problem in problems:
                print(f"Android App Bundle ({label}): FAIL: {problem}", file=sys.stderr)
        else:
            details = []
            if args.signed:
                details.append("firma JAR presente")
            if reference is not None:
                details.append("byte-riproducibile")
            details.extend((f"{path.stat().st_size} bytes", f"sha256:{sha256_file(path)}"))
            details.append(f"ABI native: {', '.join(native_abis(path)) or 'nessuna'}")
            if punctuality_detail(expectation):
                details.append(punctuality_detail(expectation))
            print(f"Android App Bundle ({label}): PASS ({', '.join(details)}).")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
