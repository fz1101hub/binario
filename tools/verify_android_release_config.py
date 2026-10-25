#!/usr/bin/env python3
"""Fail fast if Android release-engineering invariants regress."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def verify(read_file=read) -> None:
    def require(path: str, *needles: str) -> str:
        text = read_file(path)
        missing = [needle for needle in needles if needle not in text]
        if missing:
            raise AssertionError(f"{path}: missing {', '.join(missing)}")
        return text

    properties = require(
        "gradle.properties",
        "BINARIO_APP_VERSION_CODE=",
        "BINARIO_WEAR_VERSION_CODE=",
        "BINARIO_VERSION_NAME=",
    )
    if "BINARIO_RELEASE_" in properties:
        raise AssertionError("gradle.properties must not contain release-signing inputs")
    parsed_properties = {
        key.strip(): value.strip()
        for line in properties.splitlines()
        if "=" in line and not line.lstrip().startswith("#")
        for key, value in [line.split("=", 1)]
    }
    app_version_code = int(parsed_properties["BINARIO_APP_VERSION_CODE"])
    wear_version_code = int(parsed_properties["BINARIO_WEAR_VERSION_CODE"])
    if not (0 < app_version_code <= 2_100_000_000 and 0 < wear_version_code <= 2_100_000_000):
        raise AssertionError("phone and Wear versionCode values must be positive Play-compatible integers")
    if app_version_code == wear_version_code:
        raise AssertionError("phone and Wear versionCode values must remain distinct")

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

    require("app/build.gradle.kts", 'applicationId = "it.binario.app"')
    require("wear/build.gradle.kts", 'applicationId = "it.binario.app"')
    require(
        "wear/src/main/AndroidManifest.xml",
        'android:name="android.hardware.type.watch" android:required="true"',
        'android:name="com.google.android.wearable.standalone" android:value="false"',
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
        ":app:lintRelease",
        ":wear:lintRelease",
        "python3 -m unittest tests.test_wear_compose_gradle_dependency_suppressions",
    )


def main() -> int:
    verify()
    print("Android release configuration: PASS")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, OSError, ValueError) as error:
        print(f"Android release configuration: FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
