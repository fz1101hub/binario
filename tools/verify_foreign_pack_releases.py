#!/usr/bin/env python3
"""Online check that every pinned foreign-pack file is publicly downloadable, byte for byte.

    python3 -I tools/verify_foreign_pack_releases.py --online --pins     # the pins of foreign_packs.json
    python3 -I tools/verify_foreign_pack_releases.py --online --ledger   # every entry of published.json
    python3 -I tools/verify_foreign_pack_releases.py --pins              # offline: list what would be checked

Run by the release workflow after publishing, by ``release-sign`` (``android-ci.yml``) and by the dispatch archive of
``apple-watch-ci.yml`` before a build that could reach a store (a build whose pins are not public is never produced),
and weekly on the whole ledger (``gtfs-assets-ci.yml`` ``freshness-watch``). For each file: an anonymous HTTPS GET of
its pinned URL under ``https://github.com/fz1101hub/binario/releases/download/`` (no token is ever read or sent, an
honest User-Agent), following at most ``MAX_REDIRECTS`` redirects and only to GitHub's release download hosts
(``foreign_pack_rules.REDIRECT_HOSTS``); the body is read up to the pinned size and must have the pinned size and
SHA-256. The first 403 or 429 stops the whole run, without retries (docs/data-sources/GTFS_PACK_REFRESH.md).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping, Protocol, Sequence
from urllib.parse import urljoin, urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import foreign_pack_rules as rules  # noqa: E402
import verify_foreign_packs as pins_verifier  # noqa: E402

USER_AGENT = ("Binario-release-check/1.0 (automated read-only check of the open-source Binario app's own "
              "GitHub release files; https://github.com/fz1101hub/binario)")
MAX_REDIRECTS = 3
TIMEOUT_SECONDS = 60
CHUNK_BYTES = 64 * 1024
REDIRECT_STATUSES = frozenset({301, 302, 303, 307, 308})
STOP_STATUSES = frozenset({403, 429})
HOST_TEXT = re.compile(r"[a-z0-9.-]{1,253}")


class Response(Protocol):
    status: int
    headers: Mapping[str, str]

    def read(self, size: int) -> bytes: ...

    def close(self) -> None: ...


Fetch = Callable[[str], Response]


class ReleaseCheckError(RuntimeError):
    """This file is not what its pin says (size, digest, host, status): the others are still checked."""


class StopChecks(RuntimeError):
    """403 or 429: the server asks to stop, nothing more is requested in this run."""


@dataclass(frozen=True)
class Artifact:
    platform: str
    source_id: str
    tag: str
    asset: str
    url: str
    size: int
    sha256: str


def safe_host(host: object) -> str:
    """A host name as it may appear in a log line (the Location header is untrusted text)."""
    return host if isinstance(host, str) and HOST_TEXT.fullmatch(host) else "<host non valido>"


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args: Any, **kwargs: Any) -> None:  # every hop is checked by check_artifact
        return None


class _UrllibResponse:
    def __init__(self, raw: Any, status: int) -> None:
        self._raw = raw
        self.status = status
        self.headers = {key.lower(): value for key, value in raw.headers.items()}

    def read(self, size: int) -> bytes:
        return self._raw.read(size)

    def close(self) -> None:
        self._raw.close()


def anonymous_fetch(url: str) -> Response:
    """One GET without credentials and without following redirects."""
    opener = urllib.request.build_opener(_NoRedirect())
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/octet-stream"})
    try:
        raw = opener.open(request, timeout=TIMEOUT_SECONDS)
    except urllib.error.HTTPError as error:  # 3xx (not followed), 4xx and 5xx carry status and headers
        return _UrllibResponse(error, error.code)
    return _UrllibResponse(raw, raw.status)


def check_artifact(artifact: Artifact, fetch: Fetch) -> str | None:
    """Download one pinned file anonymously; returns the redirect host (None without redirect)."""
    expected_url = rules.url_for(artifact.tag, artifact.asset)
    if artifact.url != expected_url or not artifact.url.startswith(rules.RELEASE_DOWNLOAD_PREFIX):
        raise ReleaseCheckError(f"URL diversa da {expected_url}")
    current, redirect_host = artifact.url, None
    for hop in range(MAX_REDIRECTS + 1):
        parts = urlsplit(current)
        allowed = {rules.RELEASE_DOWNLOAD_HOST} if hop == 0 else set(rules.REDIRECT_HOSTS)
        if parts.scheme != "https" or parts.hostname not in allowed or parts.username or parts.password or parts.port not in (None, 443):
            raise ReleaseCheckError(f"redirect verso un host non ammesso ({parts.scheme}://{safe_host(parts.hostname)})")
        response = fetch(current)
        try:
            status = response.status
            if status in STOP_STATUSES:
                raise StopChecks(f"HTTP {status} da {safe_host(parts.hostname)}: controllo interrotto, nessun nuovo tentativo")
            if status in REDIRECT_STATUSES:
                location = response.headers.get("location")
                if not location:
                    raise ReleaseCheckError(f"HTTP {status} senza Location")
                current = urljoin(current, location)
                redirect_host = urlsplit(current).hostname
                continue
            if status != 200:
                raise ReleaseCheckError(f"HTTP {status} da {safe_host(parts.hostname)}")
            length = response.headers.get("content-length")
            if length is not None and length.strip() != str(artifact.size):
                shown = length.strip() if length.strip().isdigit() else "<non valido>"
                raise ReleaseCheckError(f"Content-Length {shown} diverso da {artifact.size} byte")
            digest, total = hashlib.sha256(), 0
            while True:
                chunk = response.read(CHUNK_BYTES)
                if not chunk:
                    break
                total += len(chunk)
                if total > artifact.size:
                    raise ReleaseCheckError(f"più di {artifact.size} byte")
                digest.update(chunk)
            if total != artifact.size:
                raise ReleaseCheckError(f"{total} byte invece di {artifact.size}")
            if digest.hexdigest() != artifact.sha256:
                raise ReleaseCheckError(f"SHA-256 {digest.hexdigest()} diverso da {artifact.sha256}")
            return redirect_host
        finally:
            response.close()
    raise ReleaseCheckError(f"più di {MAX_REDIRECTS} redirect")


def _artifact(platform: str, source_id: str, release: Mapping[str, Any]) -> Artifact:
    return Artifact(platform, source_id, str(release["tag"]), str(release["asset"]), str(release["url"]),
                    int(release["sizeBytes"]), str(release["sha256"]))


def pinned_artifacts(root: Path = ROOT) -> list[Artifact]:
    problems: list[str] = []
    pins = pins_verifier.load_json(root / rules.ANDROID_PINS_PATH, rules.ANDROID_PINS_PATH, problems)
    if pins is not None:
        problems += pins_verifier.pins_problems(pins)
    if problems:
        raise ReleaseCheckError("; ".join(problems))
    return [_artifact(*item) for item in pins_verifier.pinned_releases(pins)]


def ledger_artifacts(root: Path = ROOT) -> list[Artifact]:
    path = root / rules.LEDGER_PATH
    if not path.is_file():
        return []
    problems: list[str] = []
    ledger = pins_verifier.load_json(path, rules.LEDGER_PATH, problems, 16 * pins_verifier.MAX_PINS_BYTES)
    if ledger is not None:
        problems += pins_verifier.ledger_problems(ledger)
    if problems:
        raise ReleaseCheckError("; ".join(problems))
    return [_artifact(entry["platform"], entry["sourceId"], entry) for entry in ledger["artifacts"]]


def verify(artifacts: Sequence[Artifact], fetch: Fetch, *, out: Any = None) -> int:
    """Check each file; returns the number of failures (a stop counts the unchecked files as failures)."""
    out = out or sys.stdout
    failures = 0
    for index, artifact in enumerate(artifacts):
        label = f"{artifact.tag}/{artifact.asset}"
        try:
            host = check_artifact(artifact, fetch)
        except StopChecks as error:
            remaining = len(artifacts) - index
            print(f"ERRORE {label}: {error}; {remaining - 1} file non controllati", file=out)
            return failures + remaining
        except ReleaseCheckError as error:
            failures += 1
            print(f"ERRORE {label}: {error}", file=out)
        except (OSError, ValueError) as error:  # network errors: reported, never retried
            failures += 1
            print(f"ERRORE {label}: download non riuscito ({type(error).__name__})", file=out)
        else:
            print(f"ok {label}: {artifact.size} byte, SHA-256 uguale al pin, via {safe_host(host) if host else 'github.com'}", file=out)
    return failures


def main(argv: Sequence[str] | None = None, *, fetch: Fetch = anonymous_fetch) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    which = parser.add_mutually_exclusive_group(required=True)
    which.add_argument("--pins", action="store_true", help="i file fissati in foreign_packs.json")
    which.add_argument("--ledger", action="store_true", help="ogni voce del registro published.json")
    parser.add_argument("--online", action="store_true", help="scarica davvero ogni file (senza: solo l'elenco)")
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    try:
        artifacts = pinned_artifacts(args.root) if args.pins else ledger_artifacts(args.root)
    except (ReleaseCheckError, OSError, ValueError, KeyError) as error:
        print(f"ERRORE file pubblicati: {error}", file=sys.stderr)
        return 1
    if not artifacts:
        print("Nessun file fissato da verificare.")
        return 0
    if not args.online:
        for artifact in artifacts:
            print(f"da verificare con --online: {artifact.url} ({artifact.size} byte)")
        return 0
    failures = verify(artifacts, fetch)
    if failures:
        print(f"Verifica dei file pubblicati non superata: {failures} su {len(artifacts)}.", file=sys.stderr)
        return 1
    print(f"Foreign pack releases verified: {len(artifacts)} file(s) public and byte-identical to their pins.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
