#!/usr/bin/env python3
from __future__ import annotations

import copy
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

import yaml

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

    def test_release_push_triggers_follow_the_semantic_lock_without_integration(self) -> None:
        expected = ["main", "stabilization/**"]
        self.assertEqual(expected, LOCK["workflowTriggerPolicy"]["pushBranches"])
        for workflow_name in (
            "alexa-companion-ci.yml",
            "android-ci.yml",
            "apple-watch-ci.yml",
            "companion-ci.yml",
            "gtfs-assets-ci.yml",
        ):
            with self.subTest(workflow_name=workflow_name):
                workflow_path = ROOT / ".github/workflows" / workflow_name
                original = workflow_path.read_text(encoding="utf-8")
                missing_release_push = original.replace('      - "stabilization/**"\n', "", 1)
                problems = policy.workflow_problems(workflow_path, missing_release_push, LOCK)
                self.assertTrue(any("push branches" in problem for problem in problems))

                with_integration_push = original.replace(
                    '      - "stabilization/**"\n',
                    '      - "stabilization/**"\n      - "integration/**"\n',
                    1,
                )
                problems = policy.workflow_problems(workflow_path, with_integration_push, LOCK)
                self.assertTrue(any("push branches" in problem for problem in problems))

    def test_every_offline_test_module_runs_in_a_workflow(self) -> None:
        # Path filters also name test files, so only `run` scripts count as executing a module.
        commands: list[str] = []
        for workflow_path in sorted((ROOT / ".github/workflows").glob("*.yml")):
            workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
            for job in (workflow.get("jobs") or {}).values():
                commands += [step["run"] for step in job.get("steps") or [] if isinstance(step.get("run"), str)]
        run_scripts = "\n".join(commands)
        for module in sorted((ROOT / "tests").glob("test_*.py")):
            with self.subTest(module=module.name):
                self.assertTrue(
                    f"tests/{module.name}" in run_scripts or f"tests.{module.stem}" in run_scripts,
                    f"{module.name} is not run by any workflow step",
                )

    def test_apple_paths_must_cover_xcodegen_trust_chain(self) -> None:
        workflow_path = ROOT / ".github/workflows/apple-watch-ci.yml"
        original = workflow_path.read_text(encoding="utf-8")
        for required in (
            '      - "ios/**"\n',
            '      - "tools/install_xcodegen.py"\n',
            '      - "tests/test_verify_ios_project.py"\n',
            '      - "tests/test_verify_apple_watch_project.py"\n',
        ):
            with self.subTest(required=required):
                problems = policy.workflow_problems(
                    workflow_path,
                    original.replace(required, "", 1),
                    LOCK,
                )
                self.assertTrue(any("paths non copre la catena XcodeGen" in problem for problem in problems))

    def test_expensive_linux_workflows_require_path_filters_and_bounded_branches(self) -> None:
        workflow_path = ROOT / ".github/workflows/companion-ci.yml"
        original = workflow_path.read_text(encoding="utf-8")
        without_pull_paths = original.replace(
            '  pull_request:\n    paths:\n      - "companion-expo/**"\n      - "contracts/wear-snapshot/**"\n      - "tools/verify_android_app_bundles.py"\n      - "tests/test_verify_android_app_bundles.py"\n      - "tools/verify_gradle_dependency_metadata.py"\n      - ".github/workflows/companion-ci.yml"\n',
            "  pull_request:\n",
            1,
        )
        problems = policy.workflow_problems(workflow_path, without_pull_paths, LOCK)
        self.assertTrue(any("pull_request deve avere filtri paths" in problem for problem in problems))

        with_integration_push = original.replace(
            '      - "stabilization/**"\n',
            '      - "stabilization/**"\n      - "integration/**"\n',
            1,
        )
        problems = policy.workflow_problems(workflow_path, with_integration_push, LOCK)
        self.assertTrue(any("push branches" in problem for problem in problems))

        without_privacy = original.replace(
            "      - name: Verify privacy disclosures\n        run: python3 ../tools/verify_privacy_policy.py\n",
            "",
            1,
        )
        problems = policy.workflow_problems(workflow_path, without_privacy, LOCK)
        self.assertTrue(any("gate privacy Companion assente" in problem for problem in problems))

        without_bundle_gate = original.replace(
            "          :app:bundleRelease :wear:bundleRelease\n",
            "",
            1,
        )
        problems = policy.workflow_problems(workflow_path, without_bundle_gate, LOCK)
        self.assertTrue(any("gate App Bundle Companion assente" in problem for problem in problems))

        android_path = ROOT / ".github/workflows/android-ci.yml"
        android_without_contract = android_path.read_text(encoding="utf-8").replace(
            '      - "contracts/wear-snapshot/**"\n',
            "",
            1,
        )
        problems = policy.workflow_problems(android_path, android_without_contract, LOCK)
        self.assertTrue(any("paths non copre input condivisi" in problem for problem in problems))

        alexa_path = ROOT / ".github/workflows/alexa-companion-ci.yml"
        alexa_without_drift_gate = alexa_path.read_text(encoding="utf-8").replace(
            "          pnpm run db:generate\n",
            "",
            1,
        )
        problems = policy.workflow_problems(alexa_path, alexa_without_drift_gate, LOCK)
        self.assertTrue(any("gate drift Drizzle" in problem for problem in problems))

    def test_reproducibility_gate_is_release_or_manual_and_fail_closed(self) -> None:
        for workflow_name in ("android-ci.yml", "companion-ci.yml"):
            workflow_path = ROOT / ".github/workflows" / workflow_name
            original = workflow_path.read_text(encoding="utf-8")

            with_string_input = original.replace("        type: boolean\n", "        type: string\n", 1)
            problems = policy.workflow_problems(workflow_path, with_string_input, LOCK)
            self.assertTrue(
                any("input booleano verify_reproducibility" in problem for problem in problems),
                workflow_name,
            )

            without_dispatch_guard = original.replace(
                "if: ${{ (github.event_name == 'workflow_dispatch' && inputs.verify_reproducibility) || (github.event_name == 'pull_request' && startsWith(github.base_ref, 'stabilization/')) }}",
                "if: ${{ always() }}",
                1,
            )
            problems = policy.workflow_problems(workflow_path, without_dispatch_guard, LOCK)
            self.assertTrue(any("non è limitato a PR release o dispatch esplicito" in problem for problem in problems), workflow_name)

            with_soft_fail = original.replace(
                "if: ${{ (github.event_name == 'workflow_dispatch' && inputs.verify_reproducibility) || (github.event_name == 'pull_request' && startsWith(github.base_ref, 'stabilization/')) }}",
                "if: ${{ (github.event_name == 'workflow_dispatch' && inputs.verify_reproducibility) || (github.event_name == 'pull_request' && startsWith(github.base_ref, 'stabilization/')) }}\n        continue-on-error: true",
                1,
            )
            problems = policy.workflow_problems(workflow_path, with_soft_fail, LOCK)
            self.assertTrue(any("contiene chiavi non revisionate" in problem for problem in problems), workflow_name)

            without_first_rerun = original.replace(" --rerun-tasks", "", 1)
            problems = policy.workflow_problems(workflow_path, without_first_rerun, LOCK)
            self.assertTrue(any("esattamente due full rebuild" in problem for problem in problems), workflow_name)

            without_wear_reference = original.replace("--reference-wear", "--reference-watch", 1)
            problems = policy.workflow_problems(workflow_path, without_wear_reference, LOCK)
            self.assertTrue(any("confronto AAB con riferimenti assente" in problem for problem in problems), workflow_name)

    def test_release_signing_job_is_dispatch_only_and_fail_closed(self) -> None:
        workflow_path = ROOT / ".github/workflows/android-ci.yml"
        original = workflow_path.read_text(encoding="utf-8")
        self.assertEqual([], policy.workflow_problems(workflow_path, original, LOCK))
        sign_condition = "    if: ${{ github.event_name == 'workflow_dispatch' && inputs.sign_release }}\n"
        mutations = {
            "input booleano sign_release": original.replace(
                "        type: boolean\n        default: false\n\nconcurrency:",
                "        type: string\n        default: false\n\nconcurrency:",
                1,
            ),
            "non è limitato a un dispatch esplicito con sign_release": original.replace(sign_condition, "", 1),
            "deve dipendere dal gate unsigned": original.replace("    needs: android\n", "", 1),
            "interpola un secret nello script": original.replace(
                '          test -n "$BINARIO_RELEASE_KEYSTORE_BASE64"\n',
                '          test -n "${{ secrets.BINARIO_RELEASE_KEYSTORE_BASE64 }}"\n',
                1,
            ),
            "keystore di upload non decodificato in RUNNER_TEMP": original.replace(
                'base64 --decode > "$RUNNER_TEMP/binario-upload.jks"',
                'base64 --decode > "$GITHUB_WORKSPACE/binario-upload.jks"',
                1,
            ),
            "verifica degli AAB firmati assente": original.replace(
                "python3 tools/verify_android_app_bundles.py --signed",
                "python3 tools/verify_android_app_bundles.py",
                1,
            ),
            "non può usare continue-on-error": original.replace(
                "      - name: Signed Google Play bundles\n",
                "      - name: Signed Google Play bundles\n        continue-on-error: true\n",
                1,
            ),
            "artifact deve scadere entro 14 giorni": original.replace("          retention-days: 14\n", "          retention-days: 90\n", 1),
            "upload artifact deve fallire senza file": original.replace("          if-no-files-found: error\n", "", 1),
            "deve sovrascrivere l'artifact omonimo": original.replace("          overwrite: true\n", "          overwrite: false\n", 1),
        }
        for expected, mutated in mutations.items():
            with self.subTest(expected=expected):
                self.assertNotEqual(original, mutated)
                problems = policy.workflow_problems(workflow_path, mutated, LOCK)
                self.assertTrue(any(expected in problem for problem in problems), problems)

    def test_apple_rejects_bash_env_and_run_step_mutations(self) -> None:
        workflow_path = ROOT / ".github/workflows/apple-watch-ci.yml"
        original = workflow_path.read_text(encoding="utf-8")
        with_bash_env = original.replace(
            '      XCODE_VERSION: "16.4"\n',
            '      XCODE_VERSION: "16.4"\n      BASH_ENV: /tmp/noop.sh\n',
            1,
        )
        problems = policy.workflow_problems(workflow_path, with_bash_env, LOCK)
        self.assertTrue(any(":watchos: env" in problem for problem in problems))

        mutated_run = original.replace("run: xcodegen generate", "run: true # xcodegen generate", 1)
        problems = policy.workflow_problems(workflow_path, mutated_run, LOCK)
        self.assertTrue(any("contenuto step run diverso" in problem for problem in problems))

        mutated_ios = original.replace(
            "run: swift test --package-path ios/BinarioKit",
            "run: echo skipped-ios-tests",
            1,
        )
        problems = policy.workflow_problems(workflow_path, mutated_ios, LOCK)
        self.assertTrue(any(":ios: contenuto step run diverso" in problem for problem in problems))
        self.assertTrue(any("test Swift 6 BinarioKit assente" in problem for problem in problems))

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
        self.assertTrue(any("install XcodeGen diverso" in problem for problem in problems))

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

    def test_lock_rejects_push_policy_without_the_release_branch(self) -> None:
        invalid = copy.deepcopy(LOCK)
        invalid["workflowTriggerPolicy"]["pushBranches"] = ["main", "integration/**"]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "lock.json"
            path.write_text(json.dumps(invalid), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "policy trigger push"):
                policy.load_ci_lock(path)

    def test_lock_requires_reviewed_ios_run_steps(self) -> None:
        invalid = copy.deepcopy(LOCK)
        invalid["appleIosRunSteps"][0]["sha256"] = "not-a-sha256"
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "lock.json"
            path.write_text(json.dumps(invalid), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "appleIosRunSteps"):
                policy.load_ci_lock(path)


if __name__ == "__main__":
    unittest.main()
