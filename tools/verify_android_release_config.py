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
# What the signed Play bundle does with the best-effort realtime sources (BuildConfig.PERSONAL_REALTIME_SOURCES): the
# value release-sign passes, the release default of app/build.gradle.kts (so the unsigned bundles checked on every
# run are the same build) and what PLAY_DATA_SAFETY.md and the Play listing describe. Changing it is an owner decision.
STORE_PERSONAL_REALTIME_SOURCES = "true"


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def verify(read_file=read) -> None:
    def require(path: str, *needles: str, section=None) -> str:
        text = read_file(path)
        if section is not None:
            text = section(text)
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
    # The gate is the `android` job: the manual `dependency-metadata` helper job repeats the task list
    # without dependency verification, so it must not satisfy these checks.
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
        "python3 -m unittest tests.test_android_string_resources",
        section=lambda text: text.split("\n  dependency-metadata:\n", 1)[0],
    )


def verify_store_sources(read_file=read) -> None:
    """The store value of the realtime and personal sources is explicit in release-sign and matches the documents."""
    workflow = read_file(".github/workflows/android-ci.yml")
    if "\n  release-sign:\n" not in workflow:
        raise AssertionError(".github/workflows/android-ci.yml: release-sign job missing")
    release_sign = workflow.split("\n  release-sign:\n", 1)[1].split("\n  dependency-metadata:\n", 1)[0]
    flags = re.findall(r"-PBINARIO_PERSONAL_REALTIME_SOURCES=(\w+)", release_sign)
    if flags != [STORE_PERSONAL_REALTIME_SOURCES]:
        raise AssertionError(
            ".github/workflows/android-ci.yml: release-sign must pass "
            f"-PBINARIO_PERSONAL_REALTIME_SOURCES={STORE_PERSONAL_REALTIME_SOURCES} exactly once, found {flags}"
        )
    if "-Pbinario.personalSources=false" not in release_sign:
        raise AssertionError(".github/workflows/android-ci.yml: release-sign must pass -Pbinario.personalSources=false")
    build = read_file("app/build.gradle.kts")
    release = build.split('getByName("release") {', 1)[1].split("isMinifyEnabled", 1)[0]
    expected_default = f'providers.gradleProperty("BINARIO_PERSONAL_REALTIME_SOURCES").orElse("{STORE_PERSONAL_REALTIME_SOURCES}")'
    if expected_default not in release:
        raise AssertionError(
            "app/build.gradle.kts: the release default of PERSONAL_REALTIME_SOURCES must equal the store value "
            f"{STORE_PERSONAL_REALTIME_SOURCES}, so the unsigned bundles are the build that release-sign signs"
        )
    data_safety = read_file("docs/store/PLAY_DATA_SAFETY.md")
    if f"`-PBINARIO_PERSONAL_REALTIME_SOURCES={STORE_PERSONAL_REALTIME_SOURCES}`" not in data_safety:
        raise AssertionError(
            "docs/store/PLAY_DATA_SAFETY.md: must state the store value "
            f"`-PBINARIO_PERSONAL_REALTIME_SOURCES={STORE_PERSONAL_REALTIME_SOURCES}` that release-sign passes"
        )
    listing = read_file("docs/store/PLAY_SUBMISSION.md")
    if f"`-PBINARIO_PERSONAL_REALTIME_SOURCES={STORE_PERSONAL_REALTIME_SOURCES}`" not in listing:
        raise AssertionError("docs/store/PLAY_SUBMISSION.md: the listing section must name the store value it describes")


def main() -> int:
    verify()
    verify_store_sources()
    print("Android release configuration: PASS")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, OSError, ValueError) as error:
        print(f"Android release configuration: FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
