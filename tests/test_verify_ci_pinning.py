#!/usr/bin/env python3
from __future__ import annotations

import copy
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/verify_ci_pinning.py"
SPEC = importlib.util.spec_from_file_location("verify_ci_pinning", SCRIPT)
assert SPEC and SPEC.loader
policy = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = policy
SPEC.loader.exec_module(policy)
LOCK = policy.load_ci_lock()
CHECKOUT = "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1"
SETUP_NODE = "actions/setup-node@820762786026740c76f36085b0efc47a31fe5020"
PNPM_SETUP = "pnpm/action-setup@0977fd99725f1db4007ccb2928dbb4e90d06cc86"
MARIADB = "mariadb:11.4@sha256:70cc072b29b4a89ae07abb2d4da2c64678a7f2dfe092751bb51c87d67dc1338b"


class VerifyCiPinningTest(unittest.TestCase):
    def test_repository_workflows_are_fully_pinned(self) -> None:
        self.assertEqual([], policy.verify_repository())

    def test_apple_paths_must_cover_xcodegen_trust_chain(self) -> None:
        workflow_path = ROOT / ".github/workflows/apple-watch-ci.yml"
        text = workflow_path.read_text(encoding="utf-8").replace(
            '      - "tools/install_xcodegen.py"\n', "", 1
        )
        problems = policy.workflow_problems(workflow_path, text, LOCK)
        self.assertTrue(any("paths non copre la catena XcodeGen" in problem for problem in problems))

    def test_apple_rejects_bash_env_and_run_step_mutations(self) -> None:
        workflow_path = ROOT / ".github/workflows/apple-watch-ci.yml"
        original = workflow_path.read_text(encoding="utf-8")
        with_bash_env = original.replace(
            '      XCODE_VERSION: "16.4"\n',
            '      XCODE_VERSION: "16.4"\n      BASH_ENV: /tmp/noop.sh\n',
            1,
        )
        problems = policy.workflow_problems(workflow_path, with_bash_env, LOCK)
        self.assertTrue(any("env watchos" in problem for problem in problems))

        mutated_run = original.replace("run: xcodegen generate", "run: true # xcodegen generate", 1)
        problems = policy.workflow_problems(workflow_path, mutated_run, LOCK)
        self.assertTrue(any("step run Apple" in problem for problem in problems))

    def test_flow_style_mutable_inputs_are_rejected(self) -> None:
        text = """
        jobs: {build: {runs-on: ubuntu-latest, services: {db: {image: mariadb:latest}}, steps: [{uses: actions/checkout@v4}]}}
        """
        problems = policy.workflow_problems(Path("unsafe.yml"), text, LOCK)
        self.assertEqual(3, len(problems))
        self.assertTrue(any("action" in problem for problem in problems))
        self.assertTrue(any("immagine" in problem for problem in problems))
        self.assertTrue(any("runner" in problem for problem in problems))

    def test_reviewed_refs_are_allowed(self) -> None:
        text = f"""
        jobs:
          test:
            runs-on: ubuntu-24.04
            services:
              db:
                image: {MARIADB}
            steps:
              - uses: {CHECKOUT}
        """
        self.assertEqual([], policy.workflow_problems(Path("safe.yml"), text, LOCK))

    def test_local_action_is_rejected_fail_closed(self) -> None:
        text = """
        jobs:
          test:
            runs-on: ubuntu-24.04
            steps:
              - uses: ./.github/actions/unreviewed
        """
        problems = policy.workflow_problems(Path("local.yml"), text, LOCK)
        self.assertTrue(any("action locale non consentita" in problem for problem in problems))

    def test_unreviewed_well_formed_sha_and_digest_are_rejected(self) -> None:
        text = f"""
        jobs:
          test:
            runs-on: ubuntu-24.04
            container: alpine@sha256:{"0" * 64}
            steps:
              - uses: actions/checkout@{"0" * 40}
        """
        problems = policy.workflow_problems(Path("unreviewed.yml"), text, LOCK)
        self.assertEqual(2, len(problems))
        self.assertTrue(all("lock revisionato" in problem for problem in problems))

    def test_job_container_without_digest_is_rejected(self) -> None:
        text = """
        jobs:
          test:
            runs-on: ubuntu-24.04
            container:
              image: alpine:latest
            steps: []
        """
        problems = policy.workflow_problems(Path("container.yml"), text, LOCK)
        self.assertTrue(any("container" in problem and "digest" in problem for problem in problems))

    def test_yaml_anchor_cannot_hide_a_mutable_action(self) -> None:
        text = """
        action: &mutable_action
          uses: actions/checkout@v4
        jobs:
          test:
            runs-on: ubuntu-24.04
            steps:
              - <<: *mutable_action
        """
        problems = policy.workflow_problems(Path("anchor.yml"), text, LOCK)
        self.assertTrue(any("action non fissata" in problem for problem in problems))

    def test_runtime_version_must_match_patch_locked_value(self) -> None:
        text = f"""
        jobs:
          test:
            runs-on: ubuntu-24.04
            steps:
              - uses: {SETUP_NODE}
                with:
                  node-version: latest
        """
        problems = policy.workflow_problems(Path("runtime.yml"), text, LOCK)
        self.assertTrue(any("node-version" in problem and "fuori lock" in problem for problem in problems))

    def test_pnpm_version_must_match_patch_locked_value(self) -> None:
        text = f"""
        jobs:
          test:
            runs-on: ubuntu-24.04
            steps:
              - uses: {PNPM_SETUP}
                with:
                  version: latest
        """
        problems = policy.workflow_problems(Path("pnpm.yml"), text, LOCK)
        self.assertTrue(any("version" in problem and "fuori lock" in problem for problem in problems))

    def test_xcodegen_noop_or_alternative_install_is_rejected(self) -> None:
        text = f"""
        jobs:
          watchos:
            runs-on: macos-15
            env:
              XCODE_VERSION: "16.4"
              XCODE_BUILD: "16F6"
              XCODEGEN_VERSION: "2.46.0"
              XCODEGEN_URL: "https://github.com/yonaskolb/XcodeGen/releases/download/2.46.0/xcodegen.zip"
              XCODEGEN_SHA256: "4d9e34b62172d645eed6457cac13fc222569974098ef4ee9c3368bedf0196806"
            steps:
              - uses: {CHECKOUT}
              - name: Select pinned Xcode
                run: |
                  sudo xcode-select --switch "/Applications/Xcode_${{XCODE_VERSION}}.app/Contents/Developer"
                  test "$(xcodebuild -version | sed -n '1p')" = "Xcode $XCODE_VERSION"
                  test "$(xcodebuild -version | sed -n '2p')" = "Build version $XCODE_BUILD"
              - name: Install XcodeGen
                run: |
                  : 'python3 "$GITHUB_WORKSPACE/tools/install_xcodegen.py"'
                  curl https://example.invalid/unverified.zip
        """
        problems = policy.workflow_problems(Path("apple-watch-ci.yml"), text, LOCK)
        self.assertTrue(any("Install XcodeGen diverso" in problem for problem in problems))

    def test_self_hosted_cannot_be_mislabeled_as_github_runner(self) -> None:
        invalid = copy.deepcopy(LOCK)
        invalid["runners"][0]["label"] = "self-hosted"
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "lock.json"
            path.write_text(json.dumps(invalid), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "runner"):
                policy.load_ci_lock(path)

    def test_lock_rejects_contradictory_release_metadata(self) -> None:
        invalid = copy.deepcopy(LOCK)
        invalid["githubActions"][0]["release"] = "definitely-not-v7"
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "lock.json"
            path.write_text(json.dumps(invalid), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "release GitHub Action"):
                policy.load_ci_lock(path)


if __name__ == "__main__":
    unittest.main()
