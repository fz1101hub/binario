#!/usr/bin/env python3
"""Validate unsigned Android App Bundles before signing or Play upload."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
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
SIGNATURE_SUFFIXES = (".RSA", ".DSA", ".EC", ".SF")
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


def verify_bundle(path: Path, expected_manifest: Mapping[str, str] | None = None) -> list[str]:
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
            if manifest_info is not None and not limits_exceeded:
                if manifest_info.file_size > MAX_MANIFEST_BYTES:
                    problems.append("manifest protobuf oltre il limite")
                else:
                    manifest_data = bundle.read(manifest_info)
                    problems.extend(verify_manifest(manifest_data, expected_manifest or {}))
            if not limits_exceeded:
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
    args = parser.parse_args(argv)
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
        problems = verify_bundle(path, manifests[label])
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
