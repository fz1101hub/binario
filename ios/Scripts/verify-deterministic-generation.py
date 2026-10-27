#!/usr/bin/env python3
"""Generate the iOS project twice and fail if normalized output differs."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile


IOS_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = IOS_ROOT.parent


def tree_digest(root: Path, workspace: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(candidate for candidate in root.rglob("*") if candidate.is_file()):
        relative = path.relative_to(root).as_posix()
        payload = path.read_bytes().replace(str(workspace).encode(), b"<WORKSPACE>")
        digest.update(relative.encode())
        digest.update(b"\0")
        digest.update(payload)
        digest.update(b"\0")
    return digest.hexdigest()


def generate(xcodegen: str, destination: Path) -> str:
    repository = destination / "repository"
    workspace = repository / "ios"
    shutil.copytree(IOS_ROOT, workspace, symlinks=True)
    shutil.copytree(REPOSITORY_ROOT / "apple-watch", repository / "apple-watch", symlinks=True)
    catalog_target = repository / "app/src/main/assets/stations"
    catalog_target.mkdir(parents=True)
    shutil.copy2(
        REPOSITORY_ROOT / "app/src/main/assets/stations/curated_it_rail_stations.json",
        catalog_target,
    )
    shutil.copytree(
        REPOSITORY_ROOT / "contracts",
        repository / "contracts",
    )
    subprocess.run(
        [xcodegen, "generate", "--spec", "project.yml"],
        cwd=workspace,
        check=True,
    )
    return tree_digest(workspace / "Binario.xcodeproj", workspace)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--xcodegen", default="xcodegen")
    arguments = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="binario-ios-generation-") as temporary:
        root = Path(temporary)
        first = generate(arguments.xcodegen, root / "first")
        second = generate(arguments.xcodegen, root / "second")
    if first != second:
        raise SystemExit(f"Non-deterministic XcodeGen output: {first} != {second}")
    print(f"Deterministic XcodeGen output: {first}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
