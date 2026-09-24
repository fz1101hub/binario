#!/usr/bin/env python3
"""Validate fail-closed Gradle dependency and wrapper checksum locks."""
from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_METADATA = ROOT / "gradle" / "verification-metadata.xml"
COMPANION_METADATA = ROOT / "companion-expo" / "native" / "android" / "verification-metadata.xml"
ROOT_WRAPPER = ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"
COMPANION_WRAPPER = (
    ROOT
    / "companion-expo"
    / "android"
    / "gradle"
    / "wrapper"
    / "gradle-wrapper.properties"
)
NAMESPACE = "https://schema.gradle.org/dependency-verification"
NS = {"v": NAMESPACE}
HEX_64 = re.compile(r"^[0-9a-f]{64}$")
MIN_COMPONENTS = 500
MIN_ARTIFACTS = 900
REQUIRED_COMPONENTS = {
    ("com.android.tools.build", "gradle", "8.13.0"),
    ("org.jetbrains.kotlin", "kotlin-gradle-plugin", "2.0.21"),
    ("com.google.android.gms", "play-services-wearable", "20.0.1"),
    ("org.jsoup", "jsoup", "1.18.3"),
    ("org.mobilitydata", "gtfs-realtime-bindings", "0.0.8"),
}
COMPANION_REQUIRED_COMPONENTS = {
    ("com.android.tools.build", "gradle", "8.11.0"),
    ("org.jetbrains.kotlin", "kotlin-gradle-plugin", "2.1.20"),
    ("com.facebook.react", "react-android", "0.81.5"),
    ("com.google.android.gms", "play-services-wearable", "20.0.1"),
    ("androidx.wear.tiles", "tiles", "1.6.2"),
}
PROFILES = {
    "root": (DEFAULT_METADATA, MIN_COMPONENTS, MIN_ARTIFACTS, REQUIRED_COMPONENTS),
    "companion": (COMPANION_METADATA, 1_000, 1_800, COMPANION_REQUIRED_COMPONENTS),
}
WRAPPER_EXPECTATIONS = {
    "root": (
        ROOT_WRAPPER,
        r"https\://services.gradle.org/distributions/gradle-8.14.5-bin.zip",
        "6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854",
    ),
    "companion": (
        COMPANION_WRAPPER,
        r"https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip",
        "bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531",
    ),
}


def verify_wrapper_checksum(
    path: Path,
    *,
    expected_url: str,
    expected_sha256: str,
) -> list[str]:
    problems: list[str] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        return [f"wrapper Gradle non leggibile: {error}"]

    properties: dict[str, str] = {}
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")):
            continue
        key, separator, value = stripped.partition("=")
        if not separator:
            problems.append(f"riga wrapper non valida: {stripped}")
            continue
        key = key.strip()
        if key in properties:
            problems.append(f"proprietà wrapper duplicata: {key}")
        properties[key] = value.strip()

    if properties.get("distributionUrl") != expected_url:
        problems.append("distributionUrl wrapper non corrisponde alla versione revisionata")
    checksum = properties.get("distributionSha256Sum", "")
    if not HEX_64.fullmatch(checksum):
        problems.append("distributionSha256Sum wrapper assente o non valido")
    elif checksum != expected_sha256:
        problems.append("distributionSha256Sum wrapper non corrisponde al checksum revisionato")
    return problems


def verify_metadata(
    path: Path = DEFAULT_METADATA,
    *,
    min_components: int = MIN_COMPONENTS,
    min_artifacts: int = MIN_ARTIFACTS,
    required_components: set[tuple[str, str, str]] = REQUIRED_COMPONENTS,
) -> list[str]:
    problems: list[str] = []
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        return [f"metadata Gradle non leggibile: {error}"]

    if root.tag != f"{{{NAMESPACE}}}verification-metadata":
        problems.append("namespace/schema dependency-verification non valido")

    configuration = root.find("v:configuration", NS)
    if configuration is None:
        problems.append("configuration assente")
    else:
        expected = {"verify-metadata": "true", "verify-signatures": "false"}
        actual = {
            child.tag.rsplit("}", 1)[-1]: (child.text or "").strip()
            for child in configuration
        }
        if actual != expected:
            problems.append("configuration deve verificare metadata senza eccezioni o firme implicite")

    for forbidden in ("trusted-artifacts", "ignored-keys", "trusted-keys", "sha1", "md5"):
        if root.findall(f".//v:{forbidden}", NS):
            problems.append(f"elemento permissivo/debole non ammesso: {forbidden}")

    components = root.findall(".//v:component", NS)
    artifacts = root.findall(".//v:artifact", NS)
    if len(components) < min_components:
        problems.append(f"copertura componenti sospettamente bassa: {len(components)} < {min_components}")
    if len(artifacts) < min_artifacts:
        problems.append(f"copertura artifact sospettamente bassa: {len(artifacts)} < {min_artifacts}")

    coordinates: set[tuple[str, str, str]] = set()
    artifact_keys: set[tuple[str, str, str, str]] = set()
    for component in components:
        coordinate = tuple(component.get(field, "") for field in ("group", "name", "version"))
        if not all(coordinate):
            problems.append(f"coordinate component incomplete: {coordinate}")
        if coordinate in coordinates:
            problems.append(f"componente duplicato: {':'.join(coordinate)}")
        coordinates.add(coordinate)
        for artifact in component.findall("v:artifact", NS):
            name = artifact.get("name", "")
            artifact_key = (*coordinate, name)
            if not name or artifact_key in artifact_keys:
                problems.append(f"artifact assente o duplicato: {artifact_key}")
            artifact_keys.add(artifact_key)
            checksums = artifact.findall("v:sha256", NS)
            if len(checksums) != 1:
                problems.append(f"artifact deve avere un solo sha256: {artifact_key}")
                continue
            value = checksums[0].get("value", "")
            if not HEX_64.fullmatch(value):
                problems.append(f"sha256 non valido: {artifact_key}")

    missing_required = required_components - coordinates
    if missing_required:
        problems.append(f"componenti critici assenti: {sorted(missing_required)}")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", choices=sorted(PROFILES), default="root")
    parser.add_argument("--path", type=Path)
    args = parser.parse_args(argv)
    default_path, min_components, min_artifacts, required_components = PROFILES[args.profile]
    path = args.path or default_path
    wrapper_path, expected_url, expected_sha256 = WRAPPER_EXPECTATIONS[args.profile]
    problems = verify_metadata(
        path,
        min_components=min_components,
        min_artifacts=min_artifacts,
        required_components=required_components,
    )
    problems.extend(
        verify_wrapper_checksum(
            wrapper_path,
            expected_url=expected_url,
            expected_sha256=expected_sha256,
        )
    )
    if problems:
        for problem in problems:
            print(f"Gradle dependency verification ({args.profile}): FAIL: {problem}", file=sys.stderr)
        return 1
    root = ET.parse(path).getroot()
    print(
        f"Gradle dependency verification ({args.profile}): PASS "
        f"({len(root.findall('.//v:component', NS))} components, "
        f"{len(root.findall('.//v:artifact', NS))} artifacts, SHA-256 strict; "
        "wrapper distribution checksum pinned)."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
