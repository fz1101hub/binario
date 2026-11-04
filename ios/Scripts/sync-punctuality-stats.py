#!/usr/bin/env python3
"""Bundle the network punctuality statistics in the iPhone app, as the Android app embeds them.

The weekly refresh writes `app/src/main/assets/punctuality/stats_v1.json.gz` (tools/apply_ci_punctuality.py).
The iPhone app reads the same bytes from `ios/Resources/binario_punctuality_stats_v1.json.gz`, which
XcodeGen bundles with the rest of `Resources` like the GTFS index. That copy is generated, never
committed (`.gitignore`): `ios/Scripts/generate-project.sh` and the Apple CI run this script before
XcodeGen, so the weekly refresh updates one file. It copies the Android asset after checking that it
is a bounded gzip of a `binario-punctuality-v1` document, and removes the copy when the asset is
absent, so a build without statistics simply has no file and hides the feature. `--remove`
(`BINARIO_IOS_PUNCTUALITY_STATS=false` for generate-project.sh) withdraws the statistics from the
iPhone app alone; `--check` only reports whether the copy is in sync (absent, or identical to the asset).
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import sys
import zlib

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
ANDROID_ASSET = REPOSITORY_ROOT / "app/src/main/assets/punctuality/stats_v1.json.gz"
IOS_RESOURCE = REPOSITORY_ROOT / "ios/Resources/binario_punctuality_stats_v1.json.gz"
SCHEMA = "binario-punctuality-v1"
# The bounds of the iOS reader (NetworkReliabilityParser.Limits).
MAXIMUM_COMPRESSED_BYTES = 8 * 1024 * 1024
MAXIMUM_JSON_BYTES = 64 * 1024 * 1024


class StatisticsError(RuntimeError):
    pass


def validate(path: Path) -> dict[str, object]:
    """The summary of a statistics file, or StatisticsError when the app would not read it."""
    size = path.stat().st_size
    if not 0 < size <= MAXIMUM_COMPRESSED_BYTES:
        raise StatisticsError(f"{path}: {size} bytes, outside 1..{MAXIMUM_COMPRESSED_BYTES}")
    try:
        with gzip.open(path, "rb") as stream:
            payload = stream.read(MAXIMUM_JSON_BYTES + 1)
    except (OSError, EOFError, zlib.error) as error:
        raise StatisticsError(f"{path}: not a gzip file: {error}") from error
    if len(payload) > MAXIMUM_JSON_BYTES:
        raise StatisticsError(f"{path}: more than {MAXIMUM_JSON_BYTES} bytes once decompressed")
    try:
        document = json.loads(payload.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise StatisticsError(f"{path}: not a JSON document: {error}") from error
    if not isinstance(document, dict) or document.get("schema") != SCHEMA:
        raise StatisticsError(f"{path}: schema is not {SCHEMA}")
    trains = document.get("trains")
    if not isinstance(trains, list):
        raise StatisticsError(f"{path}: no trains array")
    return {
        "sizeBytes": size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "windowEnd": document.get("windowEnd"),
        "trains": len(trains),
    }


def in_sync(asset: Path = ANDROID_ASSET, resource: Path = IOS_RESOURCE) -> bool:
    """True when the iPhone copy is absent or byte-identical to the Android asset."""
    if not resource.exists():
        return True
    return asset.is_file() and asset.read_bytes() == resource.read_bytes()


def sync(asset: Path = ANDROID_ASSET, resource: Path = IOS_RESOURCE) -> dict[str, object]:
    if not asset.is_file():
        resource.unlink(missing_ok=True)
        return {"bundled": False, "reason": "no Android asset"}
    summary = validate(asset)
    staged = resource.with_name(f".{resource.name}.tmp")
    shutil.copyfile(asset, staged)
    staged.replace(resource)
    return {"bundled": True, **summary}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--check", action="store_true", help="fail when the iPhone copy differs from the Android asset")
    group.add_argument("--remove", action="store_true", help="leave the statistics out of the iPhone app")
    arguments = parser.parse_args(argv)
    if arguments.check:
        if in_sync():
            print("iPhone punctuality statistics in sync")
            return 0
        print(f"{IOS_RESOURCE} differs from {ANDROID_ASSET}: run ios/Scripts/sync-punctuality-stats.py", file=sys.stderr)
        return 1
    if arguments.remove:
        IOS_RESOURCE.unlink(missing_ok=True)
        print(json.dumps({"bundled": False, "reason": "removed"}))
        return 0
    try:
        result = sync()
    except StatisticsError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
