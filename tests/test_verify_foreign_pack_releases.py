#!/usr/bin/env python3
"""Online release checks and publishing of the foreign-pack files, with a fake HTTP server and a fake gh: no test
touches the network or GitHub (design §2.3–2.5)."""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, Callable, Mapping
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import foreign_pack_rules as rules  # noqa: E402
import publish_foreign_packs as publisher  # noqa: E402
import verify_foreign_pack_releases as releases  # noqa: E402

BODY = b"binario foreign pack bytes " * 100
SHA = hashlib.sha256(BODY).hexdigest()
COMMIT = "0123456789abcdef0123456789abcdef01234567"


def artifact(body: bytes = BODY, source_id: str = "oebb-gtfs", platform: str = "android") -> releases.Artifact:
    sha = hashlib.sha256(body).hexdigest()
    tag = rules.tag_for(platform, source_id, sha)
    asset = rules.asset_for(platform, source_id)
    return releases.Artifact(platform, source_id, tag, asset, rules.url_for(tag, asset), len(body), sha)


class FakeResponse:
    def __init__(self, status: int, headers: Mapping[str, str] | None = None, body: bytes = b"") -> None:
        self.status = status
        self.headers = {key.lower(): value for key, value in (headers or {}).items()}
        self._body = io.BytesIO(body)
        self.read_bytes = 0
        self.closed = False

    def read(self, size: int) -> bytes:
        chunk = self._body.read(size)
        self.read_bytes += len(chunk)
        return chunk

    def close(self) -> None:
        self.closed = True


class FakeHttp:
    """URL -> response factory; records every requested URL."""

    def __init__(self, routes: Mapping[str, Callable[[], FakeResponse]]) -> None:
        self.routes = dict(routes)
        self.requested: list[str] = []
        self.responses: list[FakeResponse] = []

    def __call__(self, url: str) -> FakeResponse:
        self.requested.append(url)
        if url not in self.routes:
            raise AssertionError(f"unexpected request {url}")
        response = self.routes[url]()
        self.responses.append(response)
        return response


def redirect(location: str, status: int = 302) -> Callable[[], FakeResponse]:
    return lambda: FakeResponse(status, {"Location": location})


def ok(body: bytes = BODY, *, length: bool = True) -> Callable[[], FakeResponse]:
    return lambda: FakeResponse(200, {"Content-Length": str(len(body))} if length else {}, body)


ASSET_HOST = "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc?sp=r"


class VerifyReleasesTest(unittest.TestCase):
    def check(self, routes: Mapping[str, Callable[[], FakeResponse]], item: releases.Artifact | None = None) -> tuple[str | None, FakeHttp]:
        item = item or artifact()
        http = FakeHttp(routes)
        return releases.check_artifact(item, http), http

    def test_a_release_download_through_an_allowed_redirect_host_is_verified(self) -> None:
        item = artifact()
        for host in rules.REDIRECT_HOSTS:
            with self.subTest(host=host):
                location = f"https://{host}/asset?token=x"
                result, http = self.check({item.url: redirect(location), location: ok()}, item)
                self.assertEqual(host, result)
                self.assertEqual([item.url, location], http.requested)
                self.assertTrue(all(response.closed for response in http.responses))

    def test_a_foreign_redirect_host_is_refused_without_contacting_it(self) -> None:
        item = artifact()
        for location in ("https://evil.example/oebb-gtfs.zip", "http://release-assets.githubusercontent.com/x",
                         "https://user@objects.githubusercontent.com/x", "https://objects.githubusercontent.com:8443/x",
                         "https://github.com/fz1101hub/binario/releases/download/other/oebb-gtfs.zip"):
            with self.subTest(location=location):
                with self.assertRaisesRegex(releases.ReleaseCheckError, "host non ammesso"):
                    self.check({item.url: redirect(location)}, item)

    def test_an_untrusted_location_never_reaches_the_log_as_text(self) -> None:
        item = artifact()
        http = FakeHttp({item.url: redirect("https://::error title=x::y/z")})
        output = io.StringIO()
        self.assertEqual(1, releases.verify([item], http, out=output))
        self.assertNotIn("::error", output.getvalue())

    def test_size_and_digest_mismatches_fail(self) -> None:
        item = artifact()
        cases = {
            "Content-Length": ok(BODY + b"x"),
            "più di": ok(BODY + b"x", length=False),
            "byte invece di": ok(BODY[:-1], length=False),
            "SHA-256": ok(BODY[:-1] + b"?"),
        }
        for expected, response in cases.items():
            with self.subTest(expected=expected):
                with self.assertRaisesRegex(releases.ReleaseCheckError, expected):
                    self.check({item.url: redirect(ASSET_HOST), ASSET_HOST: response}, item)

    def test_an_oversized_body_is_not_read_to_the_end(self) -> None:
        item = artifact()
        huge = BODY * 1000
        http = FakeHttp({item.url: redirect(ASSET_HOST), ASSET_HOST: ok(huge, length=False)})
        with self.assertRaises(releases.ReleaseCheckError):
            releases.check_artifact(item, http)
        self.assertLessEqual(http.responses[-1].read_bytes, item.size + releases.CHUNK_BYTES)

    def test_the_first_403_or_429_stops_every_further_request(self) -> None:
        first, second = artifact(), artifact(b"other", "oebb-gtfs", "ios")
        for status, at_redirect in ((403, False), (429, False), (403, True), (429, True)):
            with self.subTest(status=status, at_redirect=at_redirect):
                routes = ({first.url: redirect(ASSET_HOST), ASSET_HOST: lambda: FakeResponse(status)} if at_redirect
                          else {first.url: lambda: FakeResponse(status)})
                http = FakeHttp({**routes, second.url: redirect(ASSET_HOST)})
                output = io.StringIO()
                self.assertEqual(2, releases.verify([first, second], http, out=output))
                self.assertNotIn(second.url, http.requested)
                self.assertIn("interrotto", output.getvalue())
                self.assertEqual(1, output.getvalue().count("ERRORE"))

    def test_other_errors_are_reported_and_the_next_file_is_still_checked(self) -> None:
        first, second = artifact(), artifact(b"second", "oebb-gtfs", "ios")
        second_host = "https://objects.githubusercontent.com/second"
        http = FakeHttp({first.url: lambda: FakeResponse(404), second.url: redirect(second_host), second_host: ok(b"second")})
        output = io.StringIO()
        self.assertEqual(1, releases.verify([first, second], http, out=output))
        self.assertIn(f"ok {second.tag}/", output.getvalue())

    def test_redirect_loops_and_urls_outside_the_prefix_are_refused(self) -> None:
        item = artifact()
        loop = "https://objects.githubusercontent.com/loop"
        with self.assertRaisesRegex(releases.ReleaseCheckError, "redirect"):
            self.check({item.url: redirect(loop), loop: redirect(loop)}, item)
        moved = releases.Artifact(item.platform, item.source_id, item.tag, item.asset,
                                  "https://example.test/" + item.asset, item.size, item.sha256)
        http = FakeHttp({})
        with self.assertRaisesRegex(releases.ReleaseCheckError, "URL diversa"):
            releases.check_artifact(moved, http)
        self.assertEqual([], http.requested)

    def test_the_real_fetch_is_anonymous_honest_and_does_not_follow_redirects(self) -> None:
        captured: dict[str, Any] = {}

        class Opener:
            def open(self, request: Any, timeout: float) -> Any:
                captured["request"], captured["timeout"] = request, timeout
                raise releases.urllib.error.HTTPError(request.full_url, 302, "Found", {"Location": ASSET_HOST}, io.BytesIO())

        def build_opener(*handlers: Any) -> Opener:
            captured["handlers"] = handlers
            return Opener()

        with mock.patch.dict(os.environ, {"GH_TOKEN": "secret", "GITHUB_TOKEN": "secret"}), \
                mock.patch.object(releases.urllib.request, "build_opener", build_opener):
            response = releases.anonymous_fetch(artifact().url)
        request = captured["request"]
        self.assertEqual(302, response.status)
        self.assertEqual(ASSET_HOST, response.headers["location"])
        self.assertIsNone(request.get_header("Authorization"))
        self.assertTrue(request.get_header("User-agent").startswith("Binario-release-check/"))
        self.assertNotIn("Mozilla", request.get_header("User-agent"))
        self.assertTrue(any(isinstance(handler, releases._NoRedirect) for handler in captured["handlers"]))

    def test_cli_lists_offline_and_checks_the_repository_pins_online(self) -> None:
        def refuse(url: str) -> FakeResponse:
            raise AssertionError("no request without --online")

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, releases.main(["--pins"], fetch=refuse))
            self.assertEqual(0, releases.main(["--ledger"], fetch=refuse))
        pins = json.loads((ROOT / rules.ANDROID_PINS_PATH).read_text(encoding="utf-8"))
        routes: dict[str, Callable[[], FakeResponse]] = {}
        for pack in pins["packs"]:
            for platform, directory in (("android", rules.FULL_PACK_DIR), ("ios", rules.IOS_FULL_DIR)):
                release = pack.get(platform)
                if release:
                    location = f"https://release-assets.githubusercontent.com/{release['tag']}"
                    routes[release["url"]] = redirect(location)
                    routes[location] = ok((ROOT / directory / release["asset"]).read_bytes())
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(0, releases.main(["--online", "--pins"], fetch=FakeHttp(routes)))
            self.assertEqual(0, releases.main(["--online", "--ledger"], fetch=FakeHttp(routes)))
            self.assertEqual(1, releases.main(["--online", "--pins"], fetch=FakeHttp({url: redirect("https://evil.example/x")
                                                                                     for url in routes})))


class FakeGh:
    """gh release view / download / create against an in-memory set of releases."""

    def __init__(self, existing: Mapping[str, tuple[Mapping[str, Any], bytes]] | None = None, view_error: str | None = None) -> None:
        self.releases = {tag: (dict(view), data) for tag, (view, data) in (existing or {}).items()}
        self.calls: list[list[str]] = []
        self.notes: dict[str, str] = {}
        self.view_error = view_error

    def __call__(self, arguments: Any) -> subprocess.CompletedProcess:
        arguments = list(arguments)
        self.calls.append(arguments)
        command, tag = arguments[:2], arguments[2]
        if command == ["release", "view"]:
            if self.view_error:
                return subprocess.CompletedProcess(arguments, 1, "", self.view_error)
            if tag not in self.releases:
                return subprocess.CompletedProcess(arguments, 1, "", "release not found\n")
            return subprocess.CompletedProcess(arguments, 0, json.dumps(self.releases[tag][0]), "")
        if command == ["release", "download"]:
            directory = Path(arguments[arguments.index("--dir") + 1])
            (directory / arguments[arguments.index("--pattern") + 1]).write_bytes(self.releases[tag][1])
            return subprocess.CompletedProcess(arguments, 0, "", "")
        if command == ["release", "create"]:
            local = Path(arguments[3])
            self.notes[tag] = Path(arguments[arguments.index("--notes-file") + 1]).read_text(encoding="utf-8")
            view = {"tagName": tag, "isDraft": False, "isPrerelease": True,
                    "assets": [{"name": local.name, "size": local.stat().st_size}]}
            self.releases[tag] = (view, local.read_bytes())
            return subprocess.CompletedProcess(arguments, 0, "", "")
        raise AssertionError(f"gh {arguments} is never used")

    def verbs(self) -> list[str]:
        return [call[1] for call in self.calls]


def pinned(root: Path = ROOT) -> list[tuple[str, Mapping[str, Any], Path]]:
    pins = json.loads((root / rules.ANDROID_PINS_PATH).read_text(encoding="utf-8"))
    result = []
    for pack in pins["packs"]:
        for platform in rules.PLATFORMS:
            release = pack.get(platform)
            if release:
                result.append((platform, release, root / publisher.LOCAL_DIRS[platform] / release["asset"]))
    return result


class PublishTest(unittest.TestCase):
    def setUp(self) -> None:
        environment = mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": rules.REPOSITORY})
        environment.start()
        self.addCleanup(environment.stop)
        self.assertTrue(pinned(), "the repository pins at least one pack (ÖBB)")

    def publish(self, gh: FakeGh, *arguments: str) -> tuple[int, str]:
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            code = publisher.main(["--target", COMMIT, *arguments], run=gh)
        return code, output.getvalue()

    def test_missing_releases_are_created_as_pre_releases_and_a_second_run_changes_nothing(self) -> None:
        gh = FakeGh()
        code, output = self.publish(gh)
        self.assertEqual(0, code, output)
        creates = [call for call in gh.calls if call[1] == "create"]
        self.assertEqual(len(pinned()), len(creates))
        manifest = json.loads((ROOT / "app/src/main/assets/gtfs/manifest.json").read_text(encoding="utf-8"))
        entries = {entry["sourceId"]: entry for entry in manifest["packs"]}
        for platform, release, local in pinned():
            source_id = release["tag"].removeprefix(rules.TAG_PREFIXES[platform]).rsplit("-", 1)[0]
            call = next(call for call in creates if call[2] == release["tag"])
            self.assertEqual(str(local), call[3])
            self.assertEqual(release["asset"], local.name)
            for flag in ("--prerelease", "--latest=false"):
                self.assertIn(flag, call)
            self.assertEqual(COMMIT, call[call.index("--target") + 1])
            self.assertEqual(rules.REPOSITORY, call[call.index("--repo") + 1])
            country = rules.FOREIGN_PACK_COUNTRIES[source_id]
            self.assertIn(f"Binario · orari completi · {country} ({source_id})", call[call.index("--title") + 1])
            # The licence line and the attribution come from the manifest (an SNCF share-alike licence would show).
            notes = gh.notes[release["tag"]]
            for text in (release["sha256"], COMMIT, entries[source_id]["licence"], entries[source_id]["attribution"], publisher.DISCLAIMER):
                self.assertIn(text, notes)
        self.assertIn("CC BY 4.0", gh.notes[next(release["tag"] for _, release, _ in pinned() if "oebb-gtfs" in release["tag"])])
        again = FakeGh({tag: value for tag, value in gh.releases.items()})
        code, output = self.publish(again)
        self.assertEqual(0, code, output)
        self.assertNotIn("create", again.verbs())
        self.assertIn("già pubblicata", output)

    def test_an_existing_release_with_other_bytes_fails_and_is_never_overwritten(self) -> None:
        platform, release, local = pinned()[0]
        view = {"tagName": release["tag"], "isDraft": False, "isPrerelease": True,
                "assets": [{"name": release["asset"], "size": release["sizeBytes"]}]}
        tampered = bytearray(local.read_bytes())
        tampered[-1] ^= 0xFF
        gh = FakeGh({release["tag"]: (view, bytes(tampered))})
        code, output = self.publish(gh)
        self.assertEqual(1, code)
        self.assertIn("non viene mai sovrascritta", output)
        self.assertNotIn(release["tag"], [call[2] for call in gh.calls if call[1] == "create"])
        self.assertFalse(set(gh.verbs()) - {"view", "download", "create"})
        self.assertFalse(any("--clobber" in call for call in gh.calls))

    def test_an_existing_release_that_is_not_a_published_pre_release_or_has_other_files_fails(self) -> None:
        platform, release, local = pinned()[0]
        good = {"tagName": release["tag"], "isDraft": False, "isPrerelease": True,
                "assets": [{"name": release["asset"], "size": release["sizeBytes"]}]}
        for change in ({"isPrerelease": False}, {"isDraft": True},
                       {"assets": [{"name": release["asset"]}, {"name": "extra.zip"}]}, {"assets": []}):
            with self.subTest(change=change):
                gh = FakeGh({release["tag"]: ({**good, **change}, local.read_bytes())})
                code, output = self.publish(gh)
                self.assertEqual(1, code)
                self.assertIn("mai modificata", output)
                self.assertNotIn(release["tag"], [call[2] for call in gh.calls if call[1] == "create"])

    def test_a_gh_error_other_than_not_found_never_creates(self) -> None:
        gh = FakeGh(view_error="HTTP 403: API rate limit exceeded")
        code, output = self.publish(gh)
        self.assertEqual(1, code)
        self.assertNotIn("create", gh.verbs())

    def test_dry_run_creates_nothing(self) -> None:
        gh = FakeGh()
        code, output = self.publish(gh, "--dry-run")
        self.assertEqual(0, code, output)
        self.assertEqual({"view"}, set(gh.verbs()))
        self.assertIn("da creare", output)

    def test_bad_target_fork_and_files_that_differ_from_their_pins_publish_nothing(self) -> None:
        gh = FakeGh()
        output = io.StringIO()
        with contextlib.redirect_stderr(output):
            self.assertEqual(1, publisher.main(["--target", "main"], run=gh))
            with mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": "someone/binario"}):
                self.assertEqual(1, publisher.main(["--target", COMMIT], run=gh))
        self.assertEqual([], gh.calls)
        root = Path(tempfile.mkdtemp(prefix="publish-foreign-"))
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        for relative in (rules.ANDROID_PINS_PATH, publisher.MANIFEST_PATH, rules.FULL_PACK_DIR, rules.IOS_FULL_DIR):
            source, target = ROOT / relative, root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            (shutil.copytree if source.is_dir() else shutil.copyfile)(source, target)
        with mock.patch.object(publisher.pins_verifier, "repository_problems", return_value=[]):
            self.assertEqual(0, publisher.publish(root, COMMIT, run=FakeGh(), out=io.StringIO()))
            _, release, local = pinned(root)[0]
            local.write_bytes(local.read_bytes()[:-1])
            with self.assertRaisesRegex(publisher.PublishError, "dimensione e lo SHA-256"):
                publisher.publish(root, COMMIT, run=gh)
            # No pinned pack (every licence unconfirmed): nothing to publish, gh never called.
            pins = json.loads((root / rules.ANDROID_PINS_PATH).read_text(encoding="utf-8"))
            for pack in pins["packs"]:
                pack.update(android=None, ios=None)
            (root / rules.ANDROID_PINS_PATH).write_text(json.dumps(pins), encoding="utf-8")
            self.assertEqual(0, publisher.publish(root, COMMIT, run=gh, out=io.StringIO()))
        self.assertEqual([], gh.calls)
        with mock.patch.object(publisher.pins_verifier, "repository_problems", return_value=["pin diverso"]):
            with self.assertRaisesRegex(publisher.PublishError, "pin diverso"):
                publisher.publish(ROOT, COMMIT, run=gh)
        self.assertEqual([], gh.calls)


if __name__ == "__main__":
    unittest.main()
