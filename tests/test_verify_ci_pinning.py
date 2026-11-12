#!/usr/bin/env python3
from __future__ import annotations

import copy
import importlib.util
import json
import re
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
        sign_condition = ("    if: ${{ github.event_name == 'workflow_dispatch' && inputs.sign_release && (github.ref == "
                          "'refs/heads/main' || startsWith(github.ref, 'refs/heads/stabilization/')) }}\n")
        mutations = {
            # Mutate the sign_release input itself, wherever it sits among the dispatch inputs.
            "input booleano sign_release": re.sub(
                r"(      sign_release:\n(?:        .*\n)*?        type: )boolean",
                r"\1string",
                original,
                count=1,
            ),
            "non è limitato a un dispatch esplicito con sign_release": original.replace(sign_condition, "", 1),
            # Audit ci-docs-1: any branch could be signed with the repository secrets.
            "su main o stabilization/*": original.replace(
                sign_condition, "    if: ${{ github.event_name == 'workflow_dispatch' && inputs.sign_release }}\n", 1),
            "con sign_release su main o": original.replace(
                sign_condition, sign_condition.replace("startsWith(github.ref, 'refs/heads/stabilization/')",
                                                       "startsWith(github.ref, 'refs/heads/')"), 1),
            "environment protetto release": original.replace("    environment: release\n", "", 1),
            "environment protetto release ": original.replace("    environment: release\n", "    environment: production\n", 1),
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

    def test_release_freshness_gate_runs_only_on_release_paths(self) -> None:
        # Audit A7-06 / P2: data age must not hide tests, lint and builds; release paths keep the 30-day gate.
        condition = "        if: ${{ github.event_name == 'pull_request' && startsWith(github.base_ref, 'stabilization/') }}\n"
        android = ROOT / ".github/workflows/android-ci.yml"
        text = android.read_text(encoding="utf-8")
        gate = "      - name: Gate embedded GTFS freshness for at least 30 days\n"
        ungated = text.replace(gate + condition, gate, 1)
        self.assertNotEqual(text, ungated)
        self.assertTrue(any("android: il gate di freshness GTFS deve valere solo sui percorsi di release" in problem for problem in policy.workflow_problems(android, ungated, LOCK)))
        release_gate = gate + "        run: python3 tools/verify_embedded_assets.py --min-valid-days 30\n\n      - name: Gate punctuality statistics coverage for a release\n        run:"
        without_release_gate = text.replace(release_gate, "      - name: Gate punctuality statistics coverage for a release\n        run:", 1)
        self.assertNotEqual(text, without_release_gate)
        problems = policy.workflow_problems(android, without_release_gate, LOCK)
        self.assertTrue(any("release-sign: gate di freshness GTFS a 30 giorni assente" in problem for problem in problems), problems)
        gtfs = ROOT / ".github/workflows/gtfs-assets-ci.yml"
        text = gtfs.read_text(encoding="utf-8")
        early = text.replace(
            "        run: python3 -I tools/acquire_transit_sources.py --print-dir",
            "        run: python3 tools/build_gtfs_packs.py --check --min-valid-days 30 && python3 -I tools/acquire_transit_sources.py --print-dir",
            1,
        )
        self.assertNotEqual(text, early)
        self.assertTrue(any("fuori dai percorsi di release" in problem for problem in policy.workflow_problems(gtfs, early, LOCK)))
        # Second audit, ci-tests-build-1: the iPhone archive step applied the gate to every pull request and push, so
        # from 30 days before the end of a timetable year the whole job would be red whatever the change.
        apple = ROOT / ".github/workflows/apple-watch-ci.yml"
        text = apple.read_text(encoding="utf-8")
        gate = "      - name: Gate GTFS freshness of the iOS timetable index for at least 30 days\n"
        condition = (
            "        if: ${{ github.event_name == 'workflow_dispatch' || "
            "(github.event_name == 'pull_request' && startsWith(github.base_ref, 'stabilization/')) }}\n"
        )
        self.assertEqual([], [problem for problem in policy.workflow_problems(apple, text, LOCK) if "freshness" in problem])
        ungated = text.replace(gate + condition, gate, 1)
        self.assertNotEqual(text, ungated)
        self.assertTrue(any("ios: il gate di freshness GTFS deve valere solo sui percorsi di release" in problem for problem in policy.workflow_problems(apple, ungated, LOCK)))
        inside_archive = text.replace(
            '          test "$failures" -eq 0\n',
            '          python3 ../tools/build_gtfs_packs.py --check --input-dir ../app/src/main/assets/gtfs --min-valid-days 30\n          test "$failures" -eq 0\n',
            1,
        )
        self.assertNotEqual(text, inside_archive)
        self.assertTrue(any("ios: il gate di freshness GTFS deve valere solo sui percorsi di release" in problem for problem in policy.workflow_problems(apple, inside_archive, LOCK)))
        watch_job = text.replace(
            "          python3 tools/verify_ci_pinning.py\n",
            "          python3 tools/verify_ci_pinning.py\n          python3 tools/build_gtfs_packs.py --check --min-valid-days 30\n",
            1,
        )
        self.assertNotEqual(text, watch_job)
        self.assertTrue(any("watchos: gate di freshness GTFS fuori dai percorsi di release" in problem for problem in policy.workflow_problems(apple, watch_job, LOCK)))

    def test_shared_inputs_trigger_the_ci_of_every_product_that_reads_them(self) -> None:
        # Second audit, ci-tests-build-6: the statistics and the station catalogue feed Alexa and the iPhone app, the
        # store assets and iPhone resources the offline tests.
        cases = (
            ("alexa-companion-ci.yml", '      - "app/src/main/assets/punctuality/**"\n', "non copre input condivisi"),
            ("alexa-companion-ci.yml", '      - "app/src/main/assets/stations/curated_it_rail_stations.json"\n', "non copre input condivisi"),
            ("apple-watch-ci.yml", '      - "app/src/main/assets/punctuality/**"\n', "non copre la catena XcodeGen"),
            ("apple-watch-ci.yml", '      - "tools/build_gtfs_packs.py"\n', "non copre la catena XcodeGen"),
            ("gtfs-assets-ci.yml", '      - "store/**"\n', "non copre input condivisi"),
            ("gtfs-assets-ci.yml", '      - "ios/Resources/**"\n', "non copre input condivisi"),
        )
        for name, line, expected in cases:
            with self.subTest(name=name, line=line):
                path = ROOT / ".github/workflows" / name
                text = path.read_text(encoding="utf-8")
                self.assertEqual([], policy.workflow_problems(path, text, LOCK))
                mutated = text.replace(line, "", 1)
                self.assertNotEqual(text, mutated)
                self.assertTrue(any(expected in problem for problem in policy.workflow_problems(path, mutated, LOCK)))

    def test_a_dispatch_of_android_ci_has_its_own_concurrency_group(self) -> None:
        # Second audit, ci-tests-build-9: push and dispatch on main shared one group with cancel-in-progress, so a merge
        # cancelled a release-signing run.
        android = ROOT / ".github/workflows/android-ci.yml"
        text = android.read_text(encoding="utf-8")
        self.assertEqual([], policy.workflow_problems(android, text, LOCK))
        shared = text.replace("-${{ github.event_name == 'workflow_dispatch' && github.run_id || 'checks' }}\n", "\n", 1)
        self.assertNotEqual(text, shared)
        self.assertTrue(any("concurrency deve separare i dispatch" in problem
                            for problem in policy.workflow_problems(android, shared, LOCK)))

    def test_punctuality_release_gate_runs_on_every_release_path_and_nowhere_else(self) -> None:
        # Second audit, tools-data-5: statistics covering 6 days of a declared 30 would have shipped.
        command = "        run: python3 -I tools/build_punctuality_stats.py --release-check app/src/main/assets/punctuality/stats_v1.json.gz\n"
        android = ROOT / ".github/workflows/android-ci.yml"
        text = android.read_text(encoding="utf-8")
        self.assertEqual([], policy.workflow_problems(android, text, LOCK))
        signing = "      - name: Gate punctuality statistics coverage for a release\n" + command + "\n      - name: Decode"
        without = text.replace(signing, "      - name: Decode", 1)
        self.assertNotEqual(text, without)
        self.assertTrue(any("release-sign: gate di rilascio delle statistiche di puntualità assente" in problem
                            for problem in policy.workflow_problems(android, without, LOCK)))
        condition = "        if: ${{ github.event_name == 'pull_request' && startsWith(github.base_ref, 'stabilization/') }}\n"
        step = "      - name: Gate punctuality statistics coverage for a release\n"
        ungated = text.replace(step + condition, step, 1)
        self.assertNotEqual(text, ungated)
        self.assertTrue(any("android: il gate di rilascio delle statistiche deve essere uno step a sé" in problem
                            for problem in policy.workflow_problems(android, ungated, LOCK)))
        gtfs = ROOT / ".github/workflows/gtfs-assets-ci.yml"
        text = gtfs.read_text(encoding="utf-8")
        added = text.replace(
            "        run: python3 -I tools/acquire_transit_sources.py --print-dir",
            "        run: python3 -I tools/build_punctuality_stats.py --release-check x && python3 -I tools/acquire_transit_sources.py --print-dir",
            1,
        )
        self.assertNotEqual(text, added)
        self.assertTrue(any("gate di rilascio delle statistiche fuori dai percorsi di release" in problem
                            for problem in policy.workflow_problems(gtfs, added, LOCK)))
        apple = ROOT / ".github/workflows/apple-watch-ci.yml"
        self.assertEqual([], policy.workflow_problems(apple, apple.read_text(encoding="utf-8"), LOCK))

    def test_permissions_run_expressions_credentials_and_push_policy_are_gated(self) -> None:
        # Audit A7-10 / proposal P4.
        gtfs = ROOT / ".github/workflows/gtfs-assets-ci.yml"
        live = ROOT / ".github/workflows/live-sources.yml"
        android = ROOT / ".github/workflows/android-ci.yml"
        texts = {path: path.read_text(encoding="utf-8") for path in (gtfs, live, android)}
        for path, text in texts.items():
            self.assertEqual([], policy.workflow_problems(path, text, LOCK), path.name)
        top = "permissions:\n  contents: read\n"
        discovery = '          python3 -I tools/transit_source_discovery.py --out "$RUNNER_TEMP/discovery" --tokens-env DISCOVERY_TOKENS\n'
        core_checkout = "  core-packs:\n"
        mutations = [
            (gtfs, "permissions di primo livello assente", texts[gtfs].replace(top, "", 1)),
            (gtfs, "mappa di scope", texts[gtfs].replace(top, "permissions: write-all\n", 1)),
            (gtfs, "permesso contents: 'write' non revisionato",
             texts[gtfs].replace("    timeout-minutes: 60\n", "    timeout-minutes: 60\n    permissions:\n      contents: write\n", 1)),
            (gtfs, "interpola 'inputs.transit_discovery'",
             texts[gtfs].replace(discovery, discovery + '          echo "${{ inputs.transit_discovery }}"\n', 1)),
            (gtfs, "interpola 'github.event.pull_request.title'",
             texts[gtfs].replace(discovery, discovery + '          # ${{ github.event.pull_request.title }}\n', 1)),
            (gtfs, "senza ::stop-commands::", texts[gtfs].replace('          echo "::stop-commands::$marker"\n', "", 1)),
            (gtfs, "persist-credentials: false",
             texts[gtfs][:texts[gtfs].index(core_checkout)] + texts[gtfs][texts[gtfs].index(core_checkout):].replace(
                 "        with:\n          persist-credentials: false\n", "", 1)),
            (live, "persist-credentials: false", texts[live].replace("        with:\n          persist-credentials: false\n", "", 1)),
            (live, "push branches", texts[live].replace("  workflow_dispatch:\n", "  push:\n    branches: [\"fix/x\"]\n  workflow_dispatch:\n", 1)),
            (android, "release-sign step 2 non può ripristinare una cache",
             texts[android].replace("          # No cache: restored transforms", "          cache: gradle\n          # No cache: restored transforms", 1)),
        ]
        for path, expected, mutated in mutations:
            with self.subTest(workflow=path.name, expected=expected):
                self.assertNotEqual(texts[path], mutated)
                problems = policy.workflow_problems(path, mutated, LOCK)
                self.assertTrue(any(expected in problem for problem in problems), problems)
        apple = ROOT / ".github/workflows/apple-watch-ci.yml"
        self.assertEqual([], policy.workflow_problems(apple, apple.read_text(encoding="utf-8"), LOCK))

    def test_untrusted_triggers_head_checkouts_and_secrets_outside_release_sign_are_rejected(self) -> None:
        # Audit ci-docs-9: the classic fork pull request secret leak passed the guard with no problem at all.
        leak = (
            "name: Evil\non:\n  pull_request_target:\n    branches: [main]\npermissions:\n  contents: read\n"
            "jobs:\n  build:\n    runs-on: ubuntu-24.04\n    steps:\n"
            f"      - uses: {CHECKOUT}\n        with:\n          persist-credentials: false\n"
            "          ref: ${{ github.event.pull_request.head.sha }}\n"
            "      - name: Build\n        env:\n          KEY: ${{ secrets.BINARIO_RELEASE_KEYSTORE_BASE64 }}\n"
            "        run: ./gradlew build\n"
        )
        problems = policy.workflow_problems(Path("evil.yml"), leak, LOCK)
        for expected in ("trigger pull_request_target non revisionato", "checkout con ref",
                         "secret fuori dagli step revisionati di release-sign"):
            with self.subTest(expected=expected):
                self.assertTrue(any(expected in problem for problem in problems), problems)
        for trigger in ("workflow_run", "issue_comment", "pull_request_review_comment", "repository_dispatch"):
            with self.subTest(trigger=trigger):
                mutated = leak.replace("pull_request_target", trigger, 1)
                problems = policy.workflow_problems(Path("evil.yml"), mutated, LOCK)
                self.assertTrue(any(f"trigger {trigger} non revisionato" in problem for problem in problems), problems)
        android = ROOT / ".github/workflows/android-ci.yml"
        text = android.read_text(encoding="utf-8")
        mutations = {
            # A secret in another job of the signing workflow, in a with: input, or inherited by a reusable workflow.
            "secret fuori dagli step revisionati di release-sign": text.replace(
                "      - name: Enable Gradle wrapper\n        run: chmod +x gradlew\n",
                "      - name: Enable Gradle wrapper\n        env:\n          X: ${{ secrets.BINARIO_RELEASE_KEY_PASSWORD }}\n"
                "        run: chmod +x gradlew\n", 1),
            "secret fuori dagli step revisionati di release-sign ": text.replace(
                "          BINARIO_RELEASE_KEY_ALIAS: ${{ secrets.BINARIO_RELEASE_KEY_ALIAS }}\n",
                "          BINARIO_RELEASE_KEY_ALIAS: ${{ secrets.OTHER_TOKEN }}\n", 1),
            "checkout con ref": text.replace(
                "      - name: Checkout\n        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7\n        with:\n",
                "      - name: Checkout\n        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7\n        with:\n"
                "          ref: ${{ inputs.ref }}\n", 1),
        }
        for expected, mutated in mutations.items():
            with self.subTest(expected=expected):
                self.assertNotEqual(text, mutated)
                problems = policy.workflow_problems(android, mutated, LOCK)
                self.assertTrue(any(expected.strip() in problem for problem in problems), problems)

    def test_document_gates_run_when_their_documents_change(self) -> None:
        # Audit A7-11: store-doc tests and the SOURCE_POLICY privacy gate ran on no change to those documents.
        workflow_path = ROOT / ".github/workflows/gtfs-assets-ci.yml"
        original = workflow_path.read_text(encoding="utf-8")
        for path in ('      - "docs/**"\n', '      - "docs/data-sources/SOURCE_POLICY.md"\n'):
            with self.subTest(path=path):
                mutated = original.replace(path, "", 1)
                self.assertNotEqual(original, mutated)
                problems = policy.workflow_problems(workflow_path, mutated, LOCK)
                self.assertTrue(any("non copre input condivisi" in problem for problem in problems), problems)

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

    def test_ios_release_archive_gate_and_watch_tests_are_locked(self) -> None:
        workflow_path = ROOT / ".github/workflows/apple-watch-ci.yml"
        original = workflow_path.read_text(encoding="utf-8")
        self.assertEqual([], policy.workflow_problems(workflow_path, original, LOCK))

        debug_archive = original.replace("            -configuration Release \\\n", "", 1)
        self.assertNotEqual(original, debug_archive)
        problems = policy.workflow_problems(workflow_path, debug_archive, LOCK)
        self.assertTrue(any(":ios: contenuto step run diverso" in problem for problem in problems))
        self.assertTrue(any("archivio Release" in problem for problem in problems))

        unchecked_icon = original.replace(
            '            [ "$value" = "AppIcon" ] || { plutil -p "$bundle/Info.plist" | grep -i icon || true; fail "${bundle##*/}: CFBundleIconName is $value, expected AppIcon"; }\n', "", 1
        )
        self.assertNotEqual(original, unchecked_icon)
        self.assertTrue(any("archivio Release" in problem for problem in policy.workflow_problems(workflow_path, unchecked_icon, LOCK)))

        without_watch_tests = original.replace(
            '            -scheme BinarioWatch \\\n            -destination "id=${{ steps.ios-simulator.outputs.watch_udid }}" \\\n',
            '            -scheme Binario \\\n            -destination "id=${{ steps.ios-simulator.outputs.udid }}" \\\n',
            1,
        )
        self.assertNotEqual(original, without_watch_tests)
        self.assertTrue(any("test Watch sul progetto integrato assente" in problem for problem in policy.workflow_problems(workflow_path, without_watch_tests, LOCK)))

        upload_start = original.index("      - name: Upload unsigned Release archive\n")
        without_upload = original[:upload_start]
        self.assertTrue(any(":ios: step action diversi" in problem for problem in policy.workflow_problems(workflow_path, without_upload, LOCK)))

        long_retention = original.replace("          retention-days: 14\n          if-no-files-found: error\n", "          retention-days: 90\n          if-no-files-found: error\n", 1)
        self.assertNotEqual(original, long_retention)
        problems = policy.workflow_problems(workflow_path, long_retention, LOCK)
        self.assertTrue(any("retention-days" in problem for problem in problems))
        self.assertTrue(any(":ios: step action diversi" in problem for problem in problems))

    def test_punctuality_workflow_is_scheduled_read_only_and_prints_only_on_request(self) -> None:
        workflow_path = ROOT / ".github/workflows/punctuality.yml"
        original = workflow_path.read_text(encoding="utf-8")
        self.assertEqual([], policy.workflow_problems(workflow_path, original, LOCK))
        collect = '          python3 -I tools/collect_punctuality.py --state "$RUNNER_TEMP/punctuality-raw" \\\n'
        print_if = "        if: ${{ github.event_name == 'workflow_dispatch' && inputs.print_stats }}\n"
        build, printing = "      - name: Build the statistics\n", "      - name: Print the punctuality statistics\n"
        guard, collect_step = "      # A miss or a failed restore", "      - name: Collect ViaggiaTreno observations\n"
        save_if = "        if: ${{ !cancelled() && steps.collect.outputs.saved == 'true' }}\n"
        upload = (
            "      - name: Upload\n        uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9\n"
            "        with:\n          name: raw\n          path: x\n          retention-days: 1\n"
            "          if-no-files-found: error\n          overwrite: true\n\n"
        )
        mutations = {
            "persist-credentials: false": original.replace("        with:\n          persist-credentials: false\n", "", 1),
            "senza ::stop-commands::": original.replace('          echo "::stop-commands::$marker"\n', "", 1),
            "interpola 'inputs.print_stats'": original.replace(collect, collect.replace("--state", "--state ${{ inputs.print_stats }}"), 1),
            "permissions deve essere esattamente contents: read": original.replace("  contents: read\n", "  contents: read\n  actions: read\n", 1),
            "permesso contents: 'write'": original.replace("  contents: read\n", "  contents: write\n", 1),
            "solo con un dispatch e print_stats": original.replace(print_if, "", 1),
            "input booleano print_stats": original.replace("        default: false\n", "        default: true\n", 1),
            "al massimo 26 run al giorno": original.replace('"41 */2 * * *"', '"41 * * * *"', 1),
            "minuto fisso": original.replace('"47 21,22 * * *"', '"*/5 22 * * *"', 1),
            "da una a tre cron": original.replace('    - cron: "47 21,22 * * *"\n', '    - cron: "47 21,22 * * *"\n    - cron: "5 3 * * *"\n', 1),
            "trigger ammessi soltanto": original.replace("  workflow_dispatch:\n", "  push:\n    branches: [main, \"stabilization/**\"]\n  workflow_dispatch:\n", 1),
            "cancel-in-progress: false": original.replace("  cancel-in-progress: false\n", "  cancel-in-progress: true\n", 1),
            "gruppo fisso": original.replace("  group: punctuality-statistics\n", "  group: punctuality-${{ github.run_id }}\n", 1),
            "10 minuti sotto timeout-minutes": original.replace("--deadline-seconds 2700", "--deadline-seconds 3300", 1),
            "oltre 2 richieste": original.replace("--rate 2 ", "--rate 5 ", 1),
            "prefisso punctuality-raw-": original.replace("          restore-keys: punctuality-raw-\n", "          restore-keys: raw-\n", 1),
            "chiave e percorso": original.replace(
                "          key: punctuality-raw-${{ github.run_id }}-${{ github.run_attempt }}\n\n      - name: Build",
                "          key: punctuality-raw-fixed\n\n      - name: Build", 1),
            "nessun artifact": original.replace("      - name: Build the statistics\n", upload + "      - name: Build the statistics\n", 1),
            "verificare il file con --check": original.replace(
                '          python3 -I tools/build_punctuality_stats.py --check "$RUNNER_TEMP/punctuality/stats_v1.json.gz"\n', "", 1),
            "ordine richiesto": original[:original.index(build)] + original[original.index(printing):] + "\n" + original[original.index(build):original.index(printing)],
            "esattamente uno step di raccolta": original.replace("      - name: Python 3\n", "      - name: Early collect\n        run: python3 -I tools/collect_punctuality.py --x\n\n      - name: Python 3\n", 1),
            # Audit data-tools-1: a missed restore must not save an empty cache over 35 days of observations.
            "senza cache ripristinata": original[:original.index(guard)] + original[original.index(collect_step):],
            "senza cache ripristinata ": original.replace("        id: restore\n", "", 1),
            "si salva solo quando la raccolta ha scritto": original.replace(save_if, "        if: ${{ !cancelled() }}\n", 1),
            "si salva solo quando la raccolta ha scritto ": original.replace(
                'if [ "$status" -eq 0 ] || [ "$status" -eq 3 ]', 'if [ "$status" -ne 99 ]', 1),
            "input booleano bootstrap": original.replace(
                "        type: boolean\n        default: false\n\npermissions:", "        type: boolean\n        default: true\n\npermissions:", 1),
            "ammessi soltanto print_stats, bootstrap": original.replace(
                "      bootstrap:\n", "      extra:\n        type: string\n      bootstrap:\n", 1),
            "bootstrap solo come BOOTSTRAP": original.replace(
                "          BOOTSTRAP: ${{ github.event_name == 'workflow_dispatch' && inputs.bootstrap }}\n        run: |\n          # The collector",
                "          BOOTSTRAP: ${{ inputs.bootstrap || github.event_name == 'schedule' }}\n        run: |\n          # The collector", 1),
        }
        for expected, mutated in mutations.items():
            with self.subTest(expected=expected):
                self.assertNotEqual(original, mutated)
                problems = policy.workflow_problems(workflow_path, mutated, LOCK)
                self.assertTrue(any(expected in problem for problem in problems), problems)
        live = ROOT / ".github/workflows/live-sources.yml"
        live_text = live.read_text(encoding="utf-8")
        elsewhere = live_text.replace("      - name: Functional source checks\n",
                                      "      - name: Extra\n        run: python3 -I tools/collect_punctuality.py --state x\n"
                                      "      - name: Functional source checks\n", 1)
        self.assertNotEqual(live_text, elsewhere)
        self.assertTrue(any("solo da punctuality.yml" in problem for problem in policy.workflow_problems(live, elsewhere, LOCK)))

    def test_the_weekly_nap_watch_reads_untrusted_pages_safely(self) -> None:
        workflow_path = ROOT / ".github/workflows/gtfs-assets-ci.yml"
        original = workflow_path.read_text(encoding="utf-8")
        self.assertEqual([], policy.workflow_problems(workflow_path, original, LOCK))
        job = yaml.safe_load(original)["jobs"]["freshness-watch"]
        self.assertEqual("${{ github.event_name == 'schedule' }}", job["if"])
        watch = next(step for step in job["steps"] if "tools/nap_watch.py" in step.get("run", ""))
        run = watch["run"]
        # Commands resume before the escaped annotations are printed, never while the tool reads the pages.
        self.assertLess(run.index("::stop-commands::"), run.index("tools/nap_watch.py"))
        self.assertLess(run.index("tools/nap_watch.py"), run.index('echo "::$marker::"\n'))
        self.assertLess(run.index('echo "::$marker::"\n'), run.index("cat "))
        start = original.index("  freshness-watch:\n")
        end = original.index("  transit-discovery:\n")
        section = original[start:end]
        mutations = {
            "senza ::stop-commands::": section.replace('          echo "::stop-commands::$marker"\n', "", 1),
            "persist-credentials: false": section.replace("        with:\n          persist-credentials: false\n", "", 1),
            "interpola 'inputs.transit_discovery'": section.replace(
                "--baseline tools/nap_watch_baseline.json", "--baseline ${{ inputs.transit_discovery }}", 1),
        }
        for expected, mutated_section in mutations.items():
            with self.subTest(expected=expected):
                self.assertNotEqual(section, mutated_section)
                mutated = original[:start] + mutated_section + original[end:]
                problems = policy.workflow_problems(workflow_path, mutated, LOCK)
                self.assertTrue(any(expected in problem and "freshness-watch" in problem for problem in problems), problems)

    def test_the_punctuality_switch_is_proved_on_every_android_run(self) -> None:
        workflow_path = ROOT / ".github/workflows/android-ci.yml"
        original = workflow_path.read_text(encoding="utf-8")
        self.assertEqual([], policy.workflow_problems(workflow_path, original, LOCK))
        name = "      - name: Verify the punctuality switch leaves the statistics out\n"
        start = original.index(name)
        end = original.index("      - name: First reproducibility rebuild\n")
        signed = "-Pbinario.personalSources=false :app:bundleRelease :wear:bundleRelease"
        mutations = {
            "serve uno step android che costruisca": original[:start] + original[end:],
            "deve girare sempre": original.replace(name, name + "        if: ${{ github.event_name != 'pull_request' }}\n", 1),
            "release del telefono": original.replace(
                "            :app:assembleRelease -x :app:lintVitalRelease\n", "            :app:assembleDebug\n", 1),
            "va verificata con --punctuality-stats absent": original.replace(
                "--punctuality-stats absent \\\n", "--punctuality-stats present \\\n", 1),
            "controllo ordinario dei bundle": original.replace(
                "verify_android_app_bundles.py --punctuality-stats auto |", "verify_android_app_bundles.py --punctuality-stats absent |", 1),
            "release-sign verifica --punctuality-stats absent": original.replace(
                signed, "-Pbinario.punctualityStats=false " + signed, 1),
        }
        for expected, mutated in mutations.items():
            with self.subTest(expected=expected):
                self.assertNotEqual(original, mutated)
                problems = policy.workflow_problems(workflow_path, mutated, LOCK)
                self.assertTrue(any(expected in problem for problem in problems), problems)
        # A withdrawal release: the switch off in release-sign, checked for absence, is accepted.
        withdrawal = original.replace(signed, "-Pbinario.punctualityStats=false " + signed, 1).replace(
            "python3 tools/verify_android_app_bundles.py --signed |",
            "python3 tools/verify_android_app_bundles.py --signed --punctuality-stats absent |", 1)
        self.assertNotEqual(original, withdrawal)
        self.assertEqual([], policy.workflow_problems(workflow_path, withdrawal, LOCK))

    def test_flow_style_mutable_inputs_are_rejected(self) -> None:
        text = """
        permissions: {contents: read}
        jobs: {build: {runs-on: ubuntu-latest, services: {db: {image: mariadb:latest}}, steps: [{uses: actions/checkout@v4}]}}
        """
        problems = policy.workflow_problems(Path("unsafe.yml"), text, LOCK)
        self.assertEqual(3, len(problems))
        self.assertTrue(any("action" in problem for problem in problems))
        self.assertTrue(any("immagine" in problem for problem in problems))
        self.assertTrue(any("runner" in problem for problem in problems))

    def test_reviewed_refs_are_allowed(self) -> None:
        text = f"""
        permissions: {{contents: read}}
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
        permissions: {{contents: read}}
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
