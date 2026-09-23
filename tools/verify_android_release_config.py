#!/usr/bin/env python3
"""Fail fast if Android release-engineering invariants regress."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def require(path: str, *needles: str) -> str:
    text = (ROOT / path).read_text(encoding="utf-8")
    missing = [needle for needle in needles if needle not in text]
    if missing:
        raise AssertionError(f"{path}: missing {', '.join(missing)}")
    return text


def main() -> int:
    properties = require(
        "gradle.properties",
        "BINARIO_APP_VERSION_CODE=",
        "BINARIO_WEAR_VERSION_CODE=",
        "BINARIO_VERSION_NAME=",
    )
    if "BINARIO_RELEASE_" in properties:
        raise AssertionError("gradle.properties must not contain release-signing inputs")

    for module, version_code_property in (
        ("app", "BINARIO_APP_VERSION_CODE"),
        ("wear", "BINARIO_WEAR_VERSION_CODE"),
    ):
        require(
            f"{module}/build.gradle.kts",
            version_code_property,
            "BINARIO_VERSION_NAME",
            "providers.environmentVariable(propertyName)",
            "isMinifyEnabled = true",
            "isShrinkResources = true",
            '"proguard-rules.pro"',
        )

    require(
        "app/proguard-rules.pro",
        "com.google.transit.realtime",
        "com.google.protobuf",
        "org.jsoup",
        "com.google.android.gms.wearable",
    )
    require("wear/proguard-rules.pro", "com.google.android.gms.wearable")
    require(
        "gradle/verification-metadata.xml",
        "<verify-metadata>true</verify-metadata>",
        '<sha256 value="',
    )
    require(
        ".github/workflows/android-ci.yml",
        "tools/verify_gradle_dependency_metadata.py",
        "tools/verify_android_app_bundles.py",
        "--dependency-verification=strict",
        ":app:assembleRelease",
        ":wear:assembleRelease",
        ":app:bundleRelease",
        ":wear:bundleRelease",
        ":app:lintVitalRelease",
        ":wear:lintVitalRelease",
    )
    print("Android release configuration: PASS")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError) as error:
        print(f"Android release configuration: FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
