#!/usr/bin/env python3
"""Fail fast if Android release-engineering invariants regress."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
# Mirrors wear/build.gradle.kts: Wear versionCode = offset + phone versionCode,
# so the two form factors never share a Play versionCode. With the phone code
# kept below the offset the derived Wear code is at most 1_999_999, far under
# the Play maximum of 2_100_000_000, so no upper-bound check is needed.
WEAR_VERSION_CODE_OFFSET = 1_000_000


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
        "BINARIO_VERSION_NAME=",
    )
    if "BINARIO_RELEASE_" in properties:
        raise AssertionError("gradle.properties must not contain release-signing inputs")
    if "BINARIO_WEAR_VERSION_CODE" in properties:
        raise AssertionError(
            "gradle.properties must not define BINARIO_WEAR_VERSION_CODE: Wear derives its code from BINARIO_APP_VERSION_CODE"
        )
    parsed_properties = {
        key.strip(): value.strip()
        for line in properties.splitlines()
        if "=" in line and not line.lstrip().startswith("#")
        for key, value in [line.split("=", 1)]
    }
    app_version_code = int(parsed_properties["BINARIO_APP_VERSION_CODE"])
    if not 0 < app_version_code < WEAR_VERSION_CODE_OFFSET:
        raise AssertionError(
            f"phone versionCode must be a positive integer below the Wear range (1..{WEAR_VERSION_CODE_OFFSET - 1})"
        )

    wear_build = require("wear/build.gradle.kts", f"val wearVersionCodeOffset = {WEAR_VERSION_CODE_OFFSET:_}")
    if "BINARIO_WEAR_VERSION_CODE" in wear_build:
        raise AssertionError("wear/build.gradle.kts must derive its versionCode from BINARIO_APP_VERSION_CODE")
    if not re.search(
        r'providers\.gradleProperty\("BINARIO_VERSION_CODE"\)\s*'
        r'\.orElse\(providers\.gradleProperty\("BINARIO_APP_VERSION_CODE"\)\)\s*'
        r"\.get\(\)\s*\.toInt\(\) \+ wearVersionCodeOffset\b",
        wear_build,
    ):
        raise AssertionError(
            "wear/build.gradle.kts must add wearVersionCodeOffset to the resolved BINARIO_VERSION_CODE/BINARIO_APP_VERSION_CODE"
        )

    for module in ("app", "wear"):
        require(
            f"{module}/build.gradle.kts",
            'providers.gradleProperty("BINARIO_VERSION_CODE")',
            'providers.gradleProperty("BINARIO_APP_VERSION_CODE")',
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
