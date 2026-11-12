#!/usr/bin/env python3
"""Fail when GitHub Actions execute inputs outside the reviewed CI lock."""
from __future__ import annotations

import hashlib
import json
import re
import sys
from datetime import date
from pathlib import Path
from typing import Any, Mapping

import yaml

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW_DIR = ROOT / ".github" / "workflows"
LOCK_PATH = ROOT / ".github" / "ci-inputs-lock.json"
SHA_REF = re.compile(r"^[^\s@]+@[0-9a-f]{40}$")
OCI_DIGEST = re.compile(r"^[^\s@]+@sha256:[0-9a-f]{64}$")
HEX_64 = re.compile(r"^[0-9a-f]{64}$")
APPROVED_GITHUB_RUNNERS = {"ubuntu-24.04", "macos-15"}
MAX_ARTIFACT_RETENTION_DAYS = 14


class WorkflowLoader(yaml.SafeLoader):
    """SafeLoader with YAML 1.2-style on/off handling for GitHub workflows."""


WorkflowLoader.yaml_implicit_resolvers = {
    key: list(resolvers)
    for key, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
}
for initial in "OoYyNn":
    WorkflowLoader.yaml_implicit_resolvers[initial] = [
        resolver
        for resolver in WorkflowLoader.yaml_implicit_resolvers.get(initial, [])
        if resolver[0] != "tag:yaml.org,2002:bool"
    ]


def _mapping(value: object) -> Mapping[str, Any] | None:
    return value if isinstance(value, Mapping) else None


def load_ci_lock(path: Path = LOCK_PATH) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 3:
        raise ValueError("schemaVersion lock CI non supportata")
    try:
        date.fromisoformat(data["verifiedAt"])
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError("verifiedAt lock CI assente o non ISO") from error

    trigger_policy = _mapping(data.get("workflowTriggerPolicy"))
    if (
        not trigger_policy
        or set(trigger_policy) != {"pushBranches"}
        or trigger_policy.get("pushBranches") != ["main", "stabilization/**"]
    ):
        raise ValueError("policy trigger push CI non valida")

    action_entries = data.get("githubActions", [])
    actions = [entry.get("reference") for entry in action_entries if isinstance(entry, Mapping)]
    if not actions or len(actions) != len(action_entries):
        raise ValueError("lock GitHub Actions assente o non valido")
    if any(not isinstance(item, str) or not SHA_REF.fullmatch(item) for item in actions):
        raise ValueError("riferimento GitHub Action non valido")
    if any(
        not isinstance(entry.get("release"), str)
        or not re.fullmatch(r"v\d+(?:\.\d+){0,2}", entry["release"])
        for entry in action_entries
    ):
        raise ValueError("release GitHub Action assente o non valida")

    image_entries = data.get("containerImages", [])
    images = [entry.get("reference") for entry in image_entries if isinstance(entry, Mapping)]
    if len(images) != len(image_entries) or any(
        not isinstance(item, str) or not OCI_DIGEST.fullmatch(item) for item in images
    ):
        raise ValueError("lock immagini OCI non valido")

    runner_entries = data.get("runners", [])
    if not runner_entries:
        raise ValueError("lock runner assente")
    for entry in runner_entries:
        if (
            not isinstance(entry, Mapping)
            or not isinstance(entry.get("label"), str)
            or entry.get("label") not in APPROVED_GITHUB_RUNNERS
            or entry.get("kind") != "github-hosted-versioned-label"
            or entry.get("immutable") is not False
        ):
            raise ValueError("runner deve essere una label GitHub versionata dichiarata mutabile")

    runtime_entries = data.get("runtimes", [])
    for entry in runtime_entries:
        if (
            not isinstance(entry, Mapping)
            or not isinstance(entry.get("action"), str)
            or not isinstance(entry.get("input"), str)
            or not isinstance(entry.get("value"), str)
            or not entry.get("value")
            or not isinstance(entry.get("additionalInputs", {}), Mapping)
        ):
            raise ValueError("runtime lock non valido")

    tools = {
        entry.get("name"): entry
        for entry in data.get("tools", [])
        if isinstance(entry, Mapping)
    }
    xcode = tools.get("Xcode")
    xcodegen = tools.get("XcodeGen")
    if not isinstance(xcode, Mapping) or not all(
        isinstance(xcode.get(field), str) and xcode[field]
        for field in ("version", "build", "source")
    ):
        raise ValueError("lock Xcode non valido")
    if (
        not isinstance(xcodegen, Mapping)
        or not all(isinstance(xcodegen.get(field), str) and xcodegen[field] for field in ("version", "url", "sha256"))
        or not HEX_64.fullmatch(xcodegen["sha256"])
        or xcodegen["url"]
        != f"https://github.com/yonaskolb/XcodeGen/releases/download/{xcodegen['version']}/xcodegen.zip"
    ):
        raise ValueError("lock XcodeGen non valido")
    expected_apple_runs = {
        "appleWatchRunSteps": {
            "Verify CI trust chain",
            "Select pinned Xcode",
            "Install XcodeGen",
            "Generate Xcode project",
            "Select an available watchOS simulator",
            "Build and test Apple Watch",
        },
        "appleIosRunSteps": {
            "Verify iOS trust chain",
            "Select pinned Xcode for iOS",
            "Install XcodeGen for iOS",
            "Test BinarioKit with Swift 6",
            "Verify and generate integrated iOS project",
            "Select an available iOS simulator",
            "Build and test integrated iPhone and Watch app",
            "Archive Release build and check the App Store bundle layout",
            "Gate GTFS freshness of the iOS timetable index for at least 30 days",
            "Gate punctuality statistics coverage of the iPhone app",
            "Report GTFS freshness without failing",
        },
    }
    for lock_key, expected_run_names in expected_apple_runs.items():
        run_steps = data.get(lock_key, [])
        if (
            not isinstance(run_steps, list)
            or len(run_steps) != len(expected_run_names)
            or {entry.get("name") for entry in run_steps if isinstance(entry, Mapping)} != expected_run_names
            or any(
                not isinstance(entry, Mapping)
                or not set(entry) <= {"name", "sha256", "workingDirectory", "id", "if"}
                or not isinstance(entry.get("sha256"), str)
                or not HEX_64.fullmatch(entry["sha256"])
                or ("workingDirectory" in entry and not isinstance(entry["workingDirectory"], str))
                or ("id" in entry and not isinstance(entry["id"], str))
                or ("if" in entry and not isinstance(entry["if"], str))
                for entry in run_steps
            )
        ):
            raise ValueError(f"lock step run Apple non valido: {lock_key}")
    return data


def _action_name(reference: str) -> str:
    return reference.split("@", 1)[0]


def _check_action(
    value: object,
    *,
    location: str,
    allowed_actions: set[str],
    problems: list[str],
) -> str | None:
    if not isinstance(value, str):
        problems.append(f"{location}: uses deve essere una stringa")
        return None
    if value.startswith("./"):
        problems.append(f"{location}: action locale non consentita senza analisi ricorsiva: {value}")
        return None
    if not SHA_REF.fullmatch(value):
        problems.append(f"{location}: action non fissata a SHA: {value}")
        return None
    if value not in allowed_actions:
        problems.append(f"{location}: action non presente nel lock revisionato: {value}")
        return None
    return _action_name(value)


def _check_image(
    value: object,
    *,
    location: str,
    allowed_images: set[str],
    problems: list[str],
) -> None:
    if not isinstance(value, str):
        problems.append(f"{location}: immagine OCI deve essere una stringa")
    elif not OCI_DIGEST.fullmatch(value):
        problems.append(f"{location}: immagine non fissata a digest: {value}")
    elif value not in allowed_images:
        problems.append(f"{location}: immagine non presente nel lock revisionato: {value}")


def _check_runtime_inputs(
    action_name: str,
    step: Mapping[str, Any],
    *,
    location: str,
    runtime_locks: Mapping[str, Mapping[str, Any]],
    problems: list[str],
) -> None:
    expected = runtime_locks.get(action_name)
    if expected is None:
        return
    inputs = _mapping(step.get("with")) or {}
    required = {
        expected["input"]: expected["value"],
        **dict(expected.get("additionalInputs", {})),
    }
    for key, value in required.items():
        if inputs.get(key) != value:
            problems.append(f"{location}.with.{key}: valore runtime fuori lock: {inputs.get(key)!r} != {value!r}")


def _commands(value: object) -> str:
    if not isinstance(value, str):
        return ""
    return "\n".join(line for line in value.splitlines() if not line.lstrip().startswith("#"))


def _check_upload_artifact(step: Mapping[str, Any], *, location: str, problems: list[str]) -> None:
    inputs = _mapping(step.get("with")) or {}
    retention = inputs.get("retention-days")
    if not isinstance(retention, int) or not 0 < retention <= MAX_ARTIFACT_RETENTION_DAYS:
        problems.append(f"{location}.with.retention-days: artifact deve scadere entro {MAX_ARTIFACT_RETENTION_DAYS} giorni")
    if inputs.get("if-no-files-found") != "error":
        problems.append(f"{location}.with.if-no-files-found: upload artifact deve fallire senza file")
    # upload-artifact v4+ rejects a second artifact with the same name in one run:
    # without overwrite a "Re-run failed jobs" of the same run fails on the upload.
    if inputs.get("overwrite") is not True:
        problems.append(f"{location}.with.overwrite: upload artifact deve sovrascrivere l'artifact omonimo di un re-run")


# The upload-key signing job (audit ci-docs-1): a dispatch with sign_release on a release branch only (the push policy of
# the lock: main and stabilization/**), through the protected environment that holds the signing secrets.
RELEASE_SIGN_CONDITION = (
    "${{ github.event_name == 'workflow_dispatch' && inputs.sign_release && "
    "(github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/heads/stabilization/')) }}"
)
RELEASE_SIGN_ENVIRONMENT = "release"


def _check_release_signing(
    path: Path,
    workflow: Mapping[str, Any],
    problems: list[str],
) -> None:
    triggers = _mapping(workflow.get("on")) or {}
    workflow_dispatch = _mapping(triggers.get("workflow_dispatch")) or {}
    dispatch_inputs = _mapping(workflow_dispatch.get("inputs")) or {}
    sign_input = _mapping(dispatch_inputs.get("sign_release"))
    if (
        not sign_input
        or sign_input.get("type") != "boolean"
        or sign_input.get("required") is not False
        or sign_input.get("default") is not False
    ):
        problems.append(f"{path.name}: input booleano sign_release fail-closed assente")

    jobs = _mapping(workflow.get("jobs")) or {}
    job = _mapping(jobs.get("release-sign"))
    if not job:
        problems.append(f"{path.name}: job release-sign assente")
        return
    if job.get("needs") not in ("android", ["android"]):
        problems.append(f"{path.name}: release-sign deve dipendere dal gate unsigned del job android")
    if job.get("if") != RELEASE_SIGN_CONDITION:
        problems.append(f"{path.name}: release-sign non è limitato a un dispatch esplicito con sign_release "
                        "su main o stabilization/*")
    if job.get("environment") != RELEASE_SIGN_ENVIRONMENT:
        problems.append(f"{path.name}: release-sign deve usare l'environment protetto {RELEASE_SIGN_ENVIRONMENT} "
                        "(secret di firma, revisore e rami ammessi)")
    steps = job.get("steps")
    step_list = [step for raw_step in (steps if isinstance(steps, list) else []) for step in [_mapping(raw_step)] if step]
    for index, step in enumerate(step_list):
        if "continue-on-error" in step:
            problems.append(f"{path.name}: release-sign step {index} non può usare continue-on-error")
        if "cache" in (_mapping(step.get("with")) or {}) or _action_name(str(step.get("uses", ""))) == "actions/cache":
            # Restored transforms and build outputs are not covered by dependency verification.
            problems.append(f"{path.name}: release-sign step {index} non può ripristinare una cache")
        if "secrets." in str(step.get("run", "")):
            problems.append(f"{path.name}: release-sign step {index} interpola un secret nello script invece di env")
    commands = "\n".join(_commands(step.get("run")) for step in step_list)
    if 'base64 --decode > "$RUNNER_TEMP/' not in commands:
        problems.append(f"{path.name}: keystore di upload non decodificato in RUNNER_TEMP")
    if "python3 tools/verify_android_app_bundles.py --signed" not in commands:
        problems.append(f"{path.name}: verifica degli AAB firmati assente")
    if ":app:bundleRelease :wear:bundleRelease" not in commands:
        problems.append(f"{path.name}: release-sign deve firmare telefono e Wear insieme")
    # The store build states its source flags instead of inheriting Gradle defaults (audit A7-05); the value of the
    # realtime flag is pinned against the store documents by tools/verify_android_release_config.py.
    if len(re.findall(r"-PBINARIO_PERSONAL_REALTIME_SOURCES=(?:true|false)\b", commands)) != 1:
        problems.append(f"{path.name}: release-sign deve passare esplicitamente -PBINARIO_PERSONAL_REALTIME_SOURCES")
    if "-Pbinario.personalSources=false" not in commands or "-Pbinario.personalSources=true" in commands:
        problems.append(f"{path.name}: release-sign deve escludere i pack personali con -Pbinario.personalSources=false")


PUNCTUALITY_SWITCH_OFF = "-Pbinario.punctualityStats=false"
PUNCTUALITY_ABSENT = "--punctuality-stats absent"


def _check_punctuality_switch(path: Path, workflow: Mapping[str, Any], problems: list[str]) -> None:
    """The withdrawal switch of the punctuality statistics is proved on every run of the android job: one step builds
    the release with the switch off and checks that its APK has no assets/punctuality/; the ordinary bundle check never
    expects them absent; a signed release built with the switch off is checked for their absence (and only then)."""
    jobs = _mapping(workflow.get("jobs")) or {}

    def steps(job_name: str) -> list[Mapping[str, Any]]:
        raw = (_mapping(jobs.get(job_name)) or {}).get("steps")
        return [step for item in (raw if isinstance(raw, list) else []) for step in [_mapping(item)] if step]

    android = steps("android")
    switched = [step for step in android if PUNCTUALITY_SWITCH_OFF in _commands(step.get("run"))]
    if len(switched) != 1:
        problems.append(f"{path.name}: serve uno step android che costruisca con {PUNCTUALITY_SWITCH_OFF} ({len(switched)} trovati)")
    else:
        step = switched[0]
        command = _commands(step.get("run"))
        if "if" in step or "continue-on-error" in step:
            problems.append(f"{path.name}: la verifica di {PUNCTUALITY_SWITCH_OFF} deve girare sempre e poter fallire")
        if ":app:assembleRelease" not in command and ":app:bundleRelease" not in command:
            problems.append(f"{path.name}: con {PUNCTUALITY_SWITCH_OFF} va costruita la release del telefono")
        if not re.search(r"tools/verify_android_app_bundles\.py[^\n]*" + re.escape(PUNCTUALITY_ABSENT), command.replace("\\\n", " ")):
            problems.append(f"{path.name}: la build con {PUNCTUALITY_SWITCH_OFF} va verificata con {PUNCTUALITY_ABSENT}")
    for step in android:
        if step in switched:
            continue
        for line in _commands(step.get("run")).splitlines():
            if "tools/verify_android_app_bundles.py" in line and PUNCTUALITY_ABSENT in line:
                problems.append(f"{path.name}: il controllo ordinario dei bundle non può attendere le statistiche assenti")
    signing = "\n".join(_commands(step.get("run")) for step in steps("release-sign"))
    if signing and (PUNCTUALITY_SWITCH_OFF in signing) != (PUNCTUALITY_ABSENT in signing):
        problems.append(f"{path.name}: release-sign verifica {PUNCTUALITY_ABSENT} esattamente quando firma con {PUNCTUALITY_SWITCH_OFF}")


STABILIZATION_PULL_REQUEST = "${{ github.event_name == 'pull_request' && startsWith(github.base_ref, 'stabilization/') }}"
# Where the 30-day release-freshness gate must run (audit A7-06, proposal P2): (workflow, job) -> step condition, None
# for a job that only runs on a release path. Anywhere else the gate would hide correctness signal behind data age.
# The iPhone archive of a dispatch is the one the owner may sign and upload: there the gate holds as well.
IOS_RELEASE_ARCHIVE = (
    "${{ github.event_name == 'workflow_dispatch' || "
    "(github.event_name == 'pull_request' && startsWith(github.base_ref, 'stabilization/')) }}"
)
FRESHNESS_GATES: Mapping[tuple[str, str], str | None] = {
    ("android-ci.yml", "android"): STABILIZATION_PULL_REQUEST,
    ("android-ci.yml", "release-sign"): None,
    ("gtfs-assets-ci.yml", "gtfs-assets"): STABILIZATION_PULL_REQUEST,
    ("apple-watch-ci.yml", "ios"): IOS_RELEASE_ARCHIVE,
}


def _freshness_gate_lines(command: str) -> list[str]:
    return [
        line for line in command.splitlines()
        if ("verify_embedded_assets.py" in line or "build_gtfs_packs.py --check" in line)
        and "--min-valid-days" in line and "--integrity-only" not in line
    ]


def _check_freshness_gates(path: Path, workflow: Mapping[str, Any], problems: list[str]) -> None:
    """The release-freshness gate runs only on release paths and is present on each of them."""
    jobs = _mapping(workflow.get("jobs")) or {}
    found: set[tuple[str, str]] = set()
    for job_name, raw_job in jobs.items():
        job = _mapping(raw_job) or {}
        steps = job.get("steps")
        for raw_step in steps if isinstance(steps, list) else []:
            step = _mapping(raw_step) or {}
            if not _freshness_gate_lines(_commands(step.get("run"))):
                continue
            key = (path.name, str(job_name))
            if key not in FRESHNESS_GATES:
                problems.append(f"{path.name}:{job_name}: gate di freshness GTFS fuori dai percorsi di release")
                continue
            expected = FRESHNESS_GATES.get(key)
            if expected is not None and step.get("if") != expected:
                problems.append(f"{path.name}:{job_name}: il gate di freshness GTFS deve valere solo sui percorsi di release (PR di stabilizzazione)")
            found.add(key)
    for key in FRESHNESS_GATES:
        if key[0] == path.name and key not in found:
            problems.append(f"{path.name}:{key[1]}: gate di freshness GTFS a 30 giorni assente")


# The release gate of the punctuality statistics (second audit, tools-data-5) follows the same paths: every build that
# may be signed and uploaded checks the covered days and the age of the window; no other run does.
PUNCTUALITY_RELEASE_GATE = "tools/build_punctuality_stats.py --release-check"
PUNCTUALITY_RELEASE_GATES: Mapping[tuple[str, str], str | None] = {
    ("android-ci.yml", "android"): STABILIZATION_PULL_REQUEST,
    ("android-ci.yml", "release-sign"): None,
    ("apple-watch-ci.yml", "ios"): IOS_RELEASE_ARCHIVE,
}


def _check_punctuality_release_gates(path: Path, workflow: Mapping[str, Any], problems: list[str]) -> None:
    """The punctuality release gate runs only on release paths, in a step of its own, and is on each of them."""
    jobs = _mapping(workflow.get("jobs")) or {}
    found: set[tuple[str, str]] = set()
    for job_name, raw_job in jobs.items():
        steps = (_mapping(raw_job) or {}).get("steps")
        for raw_step in steps if isinstance(steps, list) else []:
            step = _mapping(raw_step) or {}
            command = _commands(step.get("run"))
            if PUNCTUALITY_RELEASE_GATE not in command:
                continue
            key = (path.name, str(job_name))
            if key not in PUNCTUALITY_RELEASE_GATES:
                problems.append(f"{path.name}:{job_name}: gate di rilascio delle statistiche fuori dai percorsi di release")
                continue
            if step.get("if") != PUNCTUALITY_RELEASE_GATES[key] or "continue-on-error" in step or len(command.splitlines()) != 1:
                problems.append(f"{path.name}:{job_name}: il gate di rilascio delle statistiche deve essere uno step a sé, "
                                "solo sui percorsi di release e senza continue-on-error")
            found.add(key)
    for key in PUNCTUALITY_RELEASE_GATES:
        if key[0] == path.name and key not in found:
            problems.append(f"{path.name}:{key[1]}: gate di rilascio delle statistiche di puntualità assente")


# Write scopes a workflow or job may hold after review: (workflow, job or "*" for the top level, scope). None today:
# no workflow pushes, comments or publishes (audit A7-10, proposal P4).
REVIEWED_WRITE_SCOPES: frozenset[tuple[str, str, str]] = frozenset()
# Expressions a run: script may interpolate. Anything an outsider can shape (dispatch inputs, event payloads, step
# outputs, the head ref) goes through env: instead; the Apple jobs interpolate simulator UDIDs the runner reports.
UNTRUSTED_RUN_EXPRESSION = re.compile(r"\b(?:inputs\.|github\.event\.|github\.head_ref\b|steps\.[\w-]+\.outputs\.|needs\.[\w-]+\.outputs\.)")
ALLOWED_RUN_EXPRESSIONS: Mapping[str, frozenset[str]] = {
    "apple-watch-ci.yml": frozenset({
        "steps.simulator.outputs.udid",
        "steps.ios-simulator.outputs.udid",
        "steps.ios-simulator.outputs.watch_udid",
    }),
}
# Jobs that download and parse third-party data: their checkout must not leave the job token in .git/config.
THIRD_PARTY_DATA_JOBS: frozenset[tuple[str, str]] = frozenset({
    ("android-ci.yml", "android"),  # dispatch-only live route checks against ViaggiaTreno and RFI
    ("gtfs-assets-ci.yml", "transit-discovery"),
    ("gtfs-assets-ci.yml", "transit-packs"),
    ("gtfs-assets-ci.yml", "core-packs"),
    ("live-sources.yml", "live"),
    ("punctuality.yml", "punctuality"),  # ViaggiaTreno andamentoTreno for the punctuality statistics
})
# Commands that mark a job as downloading third-party data even when it is not listed above.
THIRD_PARTY_DATA_MARKERS = (
    "tools/transit_source_discovery.py",
    "tools/acquire_transit_sources.py --out",
    "tools/acquire_transit_sources.py --validate-netex",
    "tools/refresh_core_gtfs.py --selection",
    "tools/build_gtfs_packs.py --refresh",
    "LiveProviderNetworkTest",
    "tools/collect_punctuality.py",
    "tools/nap_watch.py",
)
# Steps that print text read from downloads: they must run with workflow commands stopped (audit A7-02).
UNTRUSTED_LOG_MARKERS = (
    "tools/transit_source_discovery.py",
    "tools/acquire_transit_sources.py --out",
    "tools/acquire_transit_sources.py --validate-netex",
    "tools/collect_punctuality.py",
    "tools/nap_watch.py",
)


# Events a workflow may run on (audit ci-docs-9). pull_request_target, workflow_run and the comment or dispatch events
# run with the base repository's token and secrets on input an outsider controls; none is used, so any is refused.
REVIEWED_TRIGGERS = frozenset({"pull_request", "push", "workflow_dispatch", "schedule"})
# The only secrets a workflow may read: the upload-key signing material, as step env of the release-sign job.
REVIEWED_SECRET_JOBS: frozenset[tuple[str, str]] = frozenset({("android-ci.yml", "release-sign")})
RELEASE_SIGN_SECRETS = frozenset({
    "BINARIO_RELEASE_KEYSTORE_BASE64",
    "BINARIO_RELEASE_STORE_PASSWORD",
    "BINARIO_RELEASE_KEY_ALIAS",
    "BINARIO_RELEASE_KEY_PASSWORD",
})
SECRET_EXPRESSION = re.compile(r"\$\{\{(?:(?!\}\}).)*\bsecrets\b", re.DOTALL)


def _check_triggers_checkouts_and_secrets(path: Path, workflow: Mapping[str, Any], problems: list[str]) -> None:
    """Only reviewed triggers; no checkout of another ref or repository; secrets only where reviewed, never next to
    an expression an outsider can shape."""
    triggers = workflow.get("on")
    names = [triggers] if isinstance(triggers, str) else list(triggers) if isinstance(triggers, (list, Mapping)) else []
    for name in names:
        if str(name) not in REVIEWED_TRIGGERS:
            problems.append(f"{path.name}: trigger {name} non revisionato (gira con token e secret del repository)")
    jobs = _mapping(workflow.get("jobs")) or {}
    for job_name, raw_job in jobs.items():
        job = _mapping(raw_job) or {}
        location = f"{path.name}:{job_name}"
        if "secrets" in job:
            problems.append(f"{location}: secrets passati a un workflow riutilizzabile, non revisionati")
        steps = [step for raw in (job.get("steps") if isinstance(job.get("steps"), list) else []) for step in [_mapping(raw)] if step]
        for index, step in enumerate(steps):
            if _action_name(str(step.get("uses", ""))) == "actions/checkout":
                inputs = _mapping(step.get("with")) or {}
                if "ref" in inputs or "repository" in inputs:
                    problems.append(f"{location}: step {index} checkout con ref o repository non revisionato")
        uses_secrets = False

        def visit(node: object, where: tuple[str, ...]) -> None:
            nonlocal uses_secrets
            if isinstance(node, Mapping):
                for key, value in node.items():
                    visit(value, where + (str(key),))
            elif isinstance(node, list):
                for item_index, item in enumerate(node):
                    visit(item, where + (str(item_index),))
            elif isinstance(node, str) and SECRET_EXPRESSION.search(node):
                uses_secrets = True
                reviewed = (
                    (path.name, str(job_name)) in REVIEWED_SECRET_JOBS and len(where) == 4 and where[0] == "steps"
                    and where[2] == "env" and where[3] in RELEASE_SIGN_SECRETS
                    and node.strip() == "${{ secrets." + where[3] + " }}"
                )
                if not reviewed:
                    problems.append(f"{location}: secret fuori dagli step revisionati di release-sign ({'.'.join(where)})")

        visit(job, ())
        if not uses_secrets:
            continue
        for scope, values in [("env", job.get("env"))] + [
            (f"step {index} {key}", step.get(key)) for index, step in enumerate(steps) for key in ("env", "with")
        ]:
            for value in (_mapping(values) or {}).values():
                for expression in re.findall(r"\$\{\{(.*?)\}\}", str(value), flags=re.DOTALL):
                    if UNTRUSTED_RUN_EXPRESSION.search(expression):
                        problems.append(f"{location}: {scope} interpola {expression.strip()!r} in un job con secret")
    for key in ("env", "defaults"):
        if SECRET_EXPRESSION.search(str(workflow.get(key, ""))):
            problems.append(f"{path.name}: secret fuori dagli step revisionati di release-sign ({key} globale)")


def _check_permissions(path: Path, workflow: Mapping[str, Any], problems: list[str]) -> None:
    """An explicit top-level permissions mapping, and only read/none scopes unless a write scope was reviewed."""

    def scopes(owner: str, value: object) -> None:
        if not isinstance(value, Mapping):
            problems.append(f"{path.name}:{owner}: permissions deve essere una mappa di scope (mai read-all/write-all)")
            return
        for scope, level in value.items():
            if level in ("read", "none"):
                continue
            if level == "write" and (path.name, owner, str(scope)) in REVIEWED_WRITE_SCOPES:
                continue
            problems.append(f"{path.name}:{owner}: permesso {scope}: {level!r} non revisionato")

    if "permissions" not in workflow:
        problems.append(f"{path.name}: blocco permissions di primo livello assente")
    else:
        scopes("*", workflow.get("permissions"))
    jobs = _mapping(workflow.get("jobs")) or {}
    for job_name, raw_job in jobs.items():
        job = _mapping(raw_job) or {}
        if "permissions" in job:
            scopes(str(job_name), job.get("permissions"))


def _check_run_hygiene(path: Path, workflow: Mapping[str, Any], problems: list[str]) -> None:
    """No untrusted expression inside run:, no persisted token where third-party data is read, and workflow commands
    stopped around the steps that print downloaded text."""
    allowed = ALLOWED_RUN_EXPRESSIONS.get(path.name, frozenset())
    jobs = _mapping(workflow.get("jobs")) or {}
    for job_name, raw_job in jobs.items():
        job = _mapping(raw_job) or {}
        steps = [step for raw in (job.get("steps") if isinstance(job.get("steps"), list) else []) for step in [_mapping(raw)] if step]
        location = f"{path.name}:{job_name}"
        commands = ""
        for index, step in enumerate(steps):
            run = step.get("run")
            if not isinstance(run, str):
                continue
            commands += "\n" + run
            # GitHub expands expressions before the shell runs, comments included: the raw script is checked.
            for expression in re.findall(r"\$\{\{(.*?)\}\}", run, flags=re.DOTALL):
                expression = expression.strip()
                if UNTRUSTED_RUN_EXPRESSION.search(expression) and expression not in allowed:
                    problems.append(f"{location}: step {index} interpola {expression!r} in run: (usare env:)")
            if any(marker in run for marker in UNTRUSTED_LOG_MARKERS) and (
                "::stop-commands::" not in run or "trap " not in run
            ):
                problems.append(f"{location}: step {index} stampa dati scaricati senza ::stop-commands:: con trap")
        downloads = (path.name, str(job_name)) in THIRD_PARTY_DATA_JOBS or any(
            marker in commands for marker in THIRD_PARTY_DATA_MARKERS
        )
        if not downloads:
            continue
        for index, step in enumerate(steps):
            if _action_name(str(step.get("uses", ""))) != "actions/checkout":
                continue
            inputs = _mapping(step.get("with")) or {}
            if inputs.get("persist-credentials") is not False:
                problems.append(f"{location}: checkout senza persist-credentials: false in un job che scarica dati di terzi")


# The punctuality statistics (docs/data-sources/PUNCTUALITY_STATS.md): ViaggiaTreno is asked only from this workflow,
# on a schedule of at most about one run an hour or a dispatch, read-only, one run at a time, with the raw observations kept in the Actions
# cache under one prefix and only the aggregated file printed, and only when the dispatch asks for it.
PUNCTUALITY_WORKFLOW = "punctuality.yml"
PUNCTUALITY_CACHE_PREFIX = "punctuality-raw-"
PUNCTUALITY_PRINT_CONDITION = "${{ github.event_name == 'workflow_dispatch' && inputs.print_stats }}"
# The raw observations cannot be collected again (ViaggiaTreno forgets past days): a run that restored no cache stops
# before collecting unless a dispatch asks to bootstrap, and the cache is saved only when the collector wrote a state
# (exit status 0, or 3 for a run stopped by a refusal or implausible answers) - never an empty or thinner one.
PUNCTUALITY_DISPATCH_INPUTS = ("print_stats", "bootstrap")
PUNCTUALITY_BOOTSTRAP = "${{ github.event_name == 'workflow_dispatch' && inputs.bootstrap }}"
PUNCTUALITY_RESTORE_MATCH = "${{ steps.restore.outputs.cache-matched-key }}"
PUNCTUALITY_SAVE_CONDITION = "${{ !cancelled() && steps.collect.outputs.saved == 'true' }}"
PUNCTUALITY_ACTIONS = frozenset({"actions/checkout", "actions/setup-python", "actions/cache/restore", "actions/cache/save"})
# Time the job needs besides the collection itself: checkout, pack loading, cache save, aggregation and print.
PUNCTUALITY_DEADLINE_MARGIN_SECONDS = 600
# Each train is asked once whatever the number of runs; more runs only cover the gaps of the runs GitHub skips (4 of
# 12 on 8 November 2026) and the evening before ViaggiaTreno forgets the day. About one an hour at most.
PUNCTUALITY_MAX_DAILY_RUNS = 26


def _daily_runs(cron: object) -> int | None:
    """Runs a day of ``M H * * *`` with a fixed minute and hours ``*/2``, ``1-23/2`` or a list; None otherwise."""
    if not isinstance(cron, str):
        return None
    match = re.fullmatch(r"([1-5]?\d) (\S+) \* \* \*", cron)
    if not match:
        return None
    hours = match.group(2)
    if hours == "*/2":
        return 12
    if hours == "1-23/2":
        return 12
    values = hours.split(",")
    if not all(re.fullmatch(r"1?\d|2[0-3]", value) for value in values) or len(set(values)) != len(values):
        return None
    return len(values)


def _workflow_commands(jobs: Mapping[str, Any]) -> str:
    commands = []
    for raw_job in jobs.values():
        steps = (_mapping(raw_job) or {}).get("steps")
        for raw_step in steps if isinstance(steps, list) else []:
            commands.append(_commands((_mapping(raw_step) or {}).get("run")))
    return "\n".join(commands)


def _check_punctuality(path: Path, workflow: Mapping[str, Any], problems: list[str]) -> None:
    name = path.name
    triggers = _mapping(workflow.get("on")) or {}
    if set(triggers) != {"schedule", "workflow_dispatch"}:
        problems.append(f"{name}: trigger ammessi soltanto schedule e workflow_dispatch")
    schedule = triggers.get("schedule")
    crons = [entry.get("cron") for entry in schedule if isinstance(entry, Mapping)] if isinstance(schedule, list) else []
    runs = [_daily_runs(cron) for cron in crons]
    if not 1 <= len(crons) <= 3 or None in runs or sum(runs) > PUNCTUALITY_MAX_DAILY_RUNS:
        problems.append(f"{name}: schedule da una a tre cron con minuto fisso, al massimo {PUNCTUALITY_MAX_DAILY_RUNS} run al giorno")
    dispatch_inputs = _mapping((_mapping(triggers.get("workflow_dispatch")) or {}).get("inputs")) or {}
    if set(dispatch_inputs) != set(PUNCTUALITY_DISPATCH_INPUTS):
        problems.append(f"{name}: input del dispatch ammessi soltanto {', '.join(PUNCTUALITY_DISPATCH_INPUTS)}")
    for input_name in PUNCTUALITY_DISPATCH_INPUTS:
        declared = _mapping(dispatch_inputs.get(input_name))
        if (
            not declared or declared.get("type") != "boolean"
            or declared.get("required") is not False or declared.get("default") is not False
        ):
            problems.append(f"{name}: serve l'input booleano {input_name}, fail-closed (predefinito false)")
    if workflow.get("permissions") != {"contents": "read"}:
        problems.append(f"{name}: permissions deve essere esattamente contents: read")
    concurrency = _mapping(workflow.get("concurrency")) or {}
    group = concurrency.get("group")
    if not isinstance(group, str) or not group or "${{" in group or concurrency.get("cancel-in-progress") is not False:
        problems.append(f"{name}: concurrency deve avere un gruppo fisso e cancel-in-progress: false (run in coda)")
    jobs = _mapping(workflow.get("jobs")) or {}
    job = _mapping(jobs.get("punctuality"))
    if set(jobs) != {"punctuality"} or not job:
        problems.append(f"{name}: serve il solo job punctuality")
        return
    if "permissions" in job or "env" in job or "container" in job or "services" in job:
        problems.append(f"{name}: il job non può ridefinire permessi, env, container o servizi")
    timeout = job.get("timeout-minutes")
    if not isinstance(timeout, int) or not 0 < timeout <= 60:
        problems.append(f"{name}: timeout-minutes deve essere fra 1 e 60")
        timeout = 60
    steps = [step for raw in (job.get("steps") if isinstance(job.get("steps"), list) else []) for step in [_mapping(raw)] if step]

    def located(label: str, *, action: str | None = None, marker: str | None = None) -> int | None:
        found = [
            index for index, step in enumerate(steps)
            if (action is not None and _action_name(str(step.get("uses", ""))) == action)
            or (marker is not None and marker in _commands(step.get("run")))
        ]
        if len(found) != 1:
            problems.append(f"{name}: serve esattamente uno step {label} ({len(found)} trovati)")
            return None
        return found[0]

    restore = located("actions/cache/restore", action="actions/cache/restore")
    collect = located("di raccolta", marker="tools/collect_punctuality.py")
    save = located("actions/cache/save", action="actions/cache/save")
    build = located("di aggregazione", marker="tools/build_punctuality_stats.py --state")
    printing = located("di stampa", marker="tools/build_punctuality_stats.py --print")
    for index, step in enumerate(steps):
        action = _action_name(str(step.get("uses", ""))) if "uses" in step else None
        if action is not None and action not in PUNCTUALITY_ACTIONS:
            problems.append(f"{name}: step {index} usa {action}: nessun artifact o altra action ammessi")
        if "continue-on-error" in step:
            problems.append(f"{name}: step {index} non può usare continue-on-error")
    order = [restore, collect, save, build, printing]
    if None not in order and order != sorted(order):
        problems.append(f"{name}: ordine richiesto: ripristino cache, raccolta, salvataggio cache, aggregazione, stampa")
    if printing is not None:
        step = steps[printing]
        if step.get("if") != PUNCTUALITY_PRINT_CONDITION or printing != len(steps) - 1:
            problems.append(f"{name}: la stampa deve essere l'ultimo step, solo con un dispatch e print_stats")
    if build is not None and "tools/build_punctuality_stats.py --check" not in _commands(steps[build].get("run")):
        problems.append(f"{name}: l'aggregazione deve verificare il file con --check")
    for index, step in enumerate(steps):
        for variable, value in (_mapping(step.get("env")) or {}).items():
            if "inputs.bootstrap" in str(value) and (variable != "BOOTSTRAP" or value != PUNCTUALITY_BOOTSTRAP):
                problems.append(f"{name}: step {index}: bootstrap solo come BOOTSTRAP: {PUNCTUALITY_BOOTSTRAP}")
    if restore is not None and collect is not None:
        guards = [
            step for step in steps[restore + 1:collect]
            if (_mapping(step.get("env")) or {}).get("MATCHED_KEY") == PUNCTUALITY_RESTORE_MATCH
            and (_mapping(step.get("env")) or {}).get("BOOTSTRAP") == PUNCTUALITY_BOOTSTRAP
            and '[ -z "$MATCHED_KEY" ] && [ "$BOOTSTRAP" != "true" ]' in _commands(step.get("run"))
            and "exit 1" in _commands(step.get("run")) and "if" not in step
        ]
        if steps[restore].get("id") != "restore" or len(guards) != 1:
            problems.append(f"{name}: fra ripristino e raccolta serve lo step che ferma la run senza cache ripristinata "
                            "(cache-matched-key vuota e nessun bootstrap)")
    if collect is not None and save is not None:
        command = _commands(steps[collect].get("run"))
        if (
            steps[collect].get("id") != "collect" or steps[save].get("if") != PUNCTUALITY_SAVE_CONDITION
            or 'if [ "$status" -eq 0 ] || [ "$status" -eq 3 ]; then echo "saved=true" >> "$GITHUB_OUTPUT"; fi' not in command
        ):
            problems.append(f"{name}: la cache si salva solo quando la raccolta ha scritto uno stato ({PUNCTUALITY_SAVE_CONDITION})")
    if restore is not None and save is not None:
        restored = _mapping(steps[restore].get("with")) or {}
        saved = _mapping(steps[save].get("with")) or {}
        key = restored.get("key")
        if (
            restored.get("restore-keys") != PUNCTUALITY_CACHE_PREFIX or not isinstance(key, str)
            or not key.startswith(PUNCTUALITY_CACHE_PREFIX) or "${{ github.run_id }}" not in key
        ):
            problems.append(f"{name}: cache da ripristinare per prefisso {PUNCTUALITY_CACHE_PREFIX} con chiave unica per run")
        if saved.get("key") != key or saved.get("path") != restored.get("path") or not restored.get("path"):
            problems.append(f"{name}: la cache salvata deve avere chiave e percorso di quella ripristinata")
    if collect is not None:
        command = _commands(steps[collect].get("run"))
        deadline = re.search(r"--deadline-seconds (\d+)", command)
        rate = re.search(r"--rate (\S+)", command)
        if not deadline or int(deadline.group(1)) + PUNCTUALITY_DEADLINE_MARGIN_SECONDS > timeout * 60:
            problems.append(f"{name}: --deadline-seconds esplicito e almeno 10 minuti sotto timeout-minutes")
        if rate and not re.fullmatch(r"(?:[01](?:\.\d+)?|2(?:\.0+)?)", rate.group(1)):
            problems.append(f"{name}: --rate oltre 2 richieste al secondo")


def _check_push_branches(path: Path, workflow: Mapping[str, Any], lock: Mapping[str, Any], problems: list[str]) -> None:
    """Every push trigger follows the release branch policy of the lock (audit A7-10)."""
    triggers = workflow.get("on")
    if isinstance(triggers, str):
        triggers = {triggers: None}
    elif isinstance(triggers, list):
        triggers = {str(item): None for item in triggers}
    triggers = _mapping(triggers) or {}
    if "push" not in triggers:
        return
    push = _mapping(triggers.get("push"))
    expected = lock["workflowTriggerPolicy"]["pushBranches"]
    if push is None or push.get("branches") != expected or any(key in push for key in ("branches-ignore", "tags", "tags-ignore")):
        problems.append(f"{path.name}: push branches deve coincidere con la policy release nel lock")


def _check_apple_paths(
    path: Path,
    workflow: Mapping[str, Any],
    lock: Mapping[str, Any],
    problems: list[str],
) -> None:
    required_paths = {
        "ios/**",
        "apple-watch/**",
        "app/src/main/assets/gtfs/**",
        "app/src/main/assets/stations/curated_it_rail_stations.json",
        "app/src/main/assets/punctuality/**",
        "contracts/wear-snapshot/**",
        # The dictation cases shared with Android (RouteDictationTests).
        "contracts/voice-input/**",
        "tools/build_ios_gtfs_schedule.py",
        "tools/build_gtfs_packs.py",
        "tools/refresh_core_gtfs.py",
        "tools/verify_ios_gtfs_schedule.py",
        "tools/verify_ios_gtfs_rebuild.py",
        "tools/install_xcodegen.py",
        "tools/verify_ci_pinning.py",
        "tests/test_ios_gtfs_schedule.py",
        "tests/test_install_xcodegen.py",
        "tests/test_verify_ios_project.py",
        "tests/test_verify_apple_watch_project.py",
        "tests/test_verify_ci_pinning.py",
        "tests/test_requirements_ci.py",
        "requirements-ci.txt",
        ".github/ci-inputs-lock.json",
    }
    triggers = _mapping(workflow.get("on")) or {}
    if workflow.get("env") is not None or workflow.get("defaults") is not None:
        problems.append(f"{path.name}: env/defaults globali non ammessi nel rail Apple")
    for event_name in ("pull_request", "push"):
        event = _mapping(triggers.get(event_name)) or {}
        paths = event.get("paths")
        actual_paths = {value for value in paths if isinstance(value, str)} if isinstance(paths, list) else set()
        missing = required_paths - actual_paths
        if missing:
            problems.append(
                f"{path.name}: {event_name}.paths non copre la catena XcodeGen: {sorted(missing)}"
            )
    apple_push = _mapping(triggers.get("push")) or {}
    expected_push_branches = lock["workflowTriggerPolicy"]["pushBranches"]
    if apple_push.get("branches") != expected_push_branches:
        problems.append(f"{path.name}: push branches deve coincidere con la policy release nel lock")


def _check_linux_trigger_budget(
    path: Path,
    workflow: Mapping[str, Any],
    lock: Mapping[str, Any],
    problems: list[str],
) -> None:
    if path.name not in {
        "alexa-companion-ci.yml",
        "android-ci.yml",
        "companion-ci.yml",
        "gtfs-assets-ci.yml",
    }:
        return
    triggers = _mapping(workflow.get("on")) or {}
    pull_request = _mapping(triggers.get("pull_request")) or {}
    push = _mapping(triggers.get("push")) or {}
    pull_paths = pull_request.get("paths")
    push_paths = push.get("paths")
    branches = push.get("branches")
    if not isinstance(pull_paths, list) or not pull_paths:
        problems.append(f"{path.name}: pull_request deve avere filtri paths non vuoti")
    if not isinstance(push_paths, list) or not push_paths:
        problems.append(f"{path.name}: push deve avere filtri paths non vuoti")
    expected_push_branches = lock["workflowTriggerPolicy"]["pushBranches"]
    if branches != expected_push_branches:
        problems.append(f"{path.name}: push branches deve coincidere con la policy release nel lock")
    required_paths = {
        # contracts/voice-input: the dictation cases shared with the iPhone (PlannerVoiceDictationFormsTest).
        "android-ci.yml": {"app/**", "wear/**", "contracts/wear-snapshot/**", "contracts/voice-input/**"},
        # The document gates of the offline tests must run when their documents change (audit A7-11).
        "gtfs-assets-ci.yml": {
            "docs/**",
            "docs/data-sources/SOURCE_POLICY.md",
            "PRIVACY.md",
            "app/src/main/res/xml/locale_config.xml",
            "tools/**",
            "tests/**",
            # Read by the offline tests (second audit, ci-tests-build-6).
            "store/**",
            "ios/Resources/**",
            "companion-expo/assets/brand/**",
        },
        # The skill bundles the punctuality statistics and the station catalogue (ci-tests-build-6).
        "alexa-companion-ci.yml": {
            "alexa-companion/**",
            "app/src/main/assets/punctuality/**",
            "app/src/main/assets/stations/curated_it_rail_stations.json",
        },
        "companion-ci.yml": {
            "companion-expo/**",
            "contracts/wear-snapshot/**",
            "tools/verify_android_app_bundles.py",
            "tests/test_verify_android_app_bundles.py",
        },
    }.get(path.name, set())
    for event_name, paths in (("pull_request", pull_paths), ("push", push_paths)):
        actual = {value for value in paths if isinstance(value, str)} if isinstance(paths, list) else set()
        missing = required_paths - actual
        if missing:
            problems.append(f"{path.name}: {event_name}.paths non copre input condivisi: {sorted(missing)}")

    if path.name == "android-ci.yml":
        # A merge to main must not cancel a release-signing dispatch, nor a dispatch the post-merge run (ci-tests-build-9).
        group = str((_mapping(workflow.get("concurrency")) or {}).get("group", ""))
        if "github.event_name == 'workflow_dispatch' && github.run_id" not in group:
            problems.append(f"{path.name}: il gruppo di concurrency deve separare i dispatch (github.run_id), "
                            "o un merge annulla la firma di release")

    if path.name in {"android-ci.yml", "companion-ci.yml"}:
        workflow_dispatch = _mapping(triggers.get("workflow_dispatch")) or {}
        dispatch_inputs = _mapping(workflow_dispatch.get("inputs")) or {}
        reproducibility_input = _mapping(dispatch_inputs.get("verify_reproducibility"))
        if (
            not reproducibility_input
            or reproducibility_input.get("type") != "boolean"
            or reproducibility_input.get("required") is not False
            or reproducibility_input.get("default") is not False
        ):
            problems.append(f"{path.name}: input booleano verify_reproducibility fail-closed assente")

        job_name = "android" if path.name == "android-ci.yml" else "companion"
        jobs = _mapping(workflow.get("jobs")) or {}
        job = _mapping(jobs.get(job_name)) or {}
        minimum_timeout = 60 if path.name == "android-ci.yml" else 90
        timeout = job.get("timeout-minutes")
        if not isinstance(timeout, int) or timeout < minimum_timeout:
            problems.append(f"{path.name}: timeout insufficiente per due full rebuild")

        steps = job.get("steps")
        step_list = steps if isinstance(steps, list) else []
        steps_by_name = {
            step.get("name"): step
            for raw_step in step_list
            for step in [_mapping(raw_step)] if step and isinstance(step.get("name"), str)
        }
        prefix = "Companion " if path.name == "companion-ci.yml" else ""
        required_step_names = (
            f"First {prefix}reproducibility rebuild",
            f"Capture {prefix}reproducibility references",
            f"Second {prefix}reproducibility rebuild",
            f"Verify reproducible {prefix}App Bundles" if prefix else "Verify reproducible Google Play bundles",
        )
        expected_condition = "${{ (github.event_name == 'workflow_dispatch' && inputs.verify_reproducibility) || (github.event_name == 'pull_request' && startsWith(github.base_ref, 'stabilization/')) }}"
        gated_steps: list[Mapping[str, Any]] = []
        for step_name in required_step_names:
            step = steps_by_name.get(step_name)
            if not step:
                problems.append(f"{path.name}: step manuale di riproducibilità assente: {step_name}")
                continue
            gated_steps.append(step)
            if step.get("if") != expected_condition:
                problems.append(f"{path.name}: {step_name} non è limitato a PR release o dispatch esplicito")
            is_rebuild = step_name.startswith("First ") or step_name.startswith("Second ")
            expected_keys = {"name", "if", "run"}
            if path.name == "companion-ci.yml" and is_rebuild:
                expected_keys.add("working-directory")
                if step.get("working-directory") != "companion-expo/android":
                    problems.append(f"{path.name}: {step_name} usa una directory inattesa")
            if set(step) != expected_keys:
                problems.append(f"{path.name}: {step_name} contiene chiavi non revisionate")

        gated_commands = "\n".join(_commands(step.get("run")) for step in gated_steps)
        if gated_commands.count("--rerun-tasks") != 2:
            problems.append(f"{path.name}: servono esattamente due full rebuild con --rerun-tasks")
        if gated_commands.count(":app:bundleRelease") != 2 or gated_commands.count(":wear:bundleRelease") != 2:
            problems.append(f"{path.name}: entrambi i rebuild devono produrre telefono e Wear")
        if "--reference-phone" not in gated_commands or "--reference-wear" not in gated_commands:
            problems.append(f"{path.name}: confronto AAB con riferimenti assente")
        if gated_commands.count("$RUNNER_TEMP") < 4:
            problems.append(f"{path.name}: riferimenti AAB non confinati in RUNNER_TEMP")
        if path.name == "companion-ci.yml" and "--profile companion" not in gated_commands:
            problems.append(f"{path.name}: confronto riproducibilità non usa il profilo Companion")

    if path.name == "companion-ci.yml":
        jobs = _mapping(workflow.get("jobs")) or {}
        companion = _mapping(jobs.get("companion")) or {}
        steps = companion.get("steps")
        companion_step_list = steps if isinstance(steps, list) else []
        companion_steps = {
            step.get("name"): step
            for raw_step in companion_step_list
            for step in [_mapping(raw_step)] if step and isinstance(step.get("name"), str)
        }
        commands = "\n".join(_commands(step.get("run")) for step in companion_steps.values())
        if "python3 ../tools/verify_privacy_policy.py" not in commands:
            problems.append(f"{path.name}: gate privacy Companion assente")
        ordinary_build = _commands(
            (companion_steps.get("Android companion and Wear lint/build") or {}).get("run")
        )
        ordinary_verify = _commands(
            (companion_steps.get("Verify unsigned Companion App Bundles") or {}).get("run")
        )
        if (
            ":app:bundleRelease" not in ordinary_build
            or ":wear:bundleRelease" not in ordinary_build
            or "python3 ../tools/verify_android_app_bundles.py --profile companion" not in ordinary_verify
        ):
            problems.append(f"{path.name}: gate App Bundle Companion assente")

    if path.name == "alexa-companion-ci.yml":
        jobs = _mapping(workflow.get("jobs")) or {}
        alexa = _mapping(jobs.get("alexa-companion")) or {}
        steps = alexa.get("steps")
        commands = ""
        if isinstance(steps, list):
            commands = "\n".join(
                _commands(step.get("run"))
                for raw_step in steps
                for step in [_mapping(raw_step)] if step
            )
        if "pnpm run db:generate" not in commands or "git status --porcelain --untracked-files=all -- drizzle" not in commands:
            problems.append(f"{path.name}: gate drift Drizzle fail-closed assente")


def _check_apple_toolchain(
    path: Path,
    jobs: Mapping[str, Any],
    lock: Mapping[str, Any],
    problems: list[str],
) -> None:
    tools = {
        tool.get("name"): tool
        for tool in lock.get("tools", [])
        if isinstance(tool, Mapping) and isinstance(tool.get("name"), str)
    }
    xcode = tools.get("Xcode", {})
    xcodegen = tools.get("XcodeGen", {})
    expected_env = {
        "XCODE_VERSION": xcode.get("version"),
        "XCODE_BUILD": xcode.get("build"),
        "XCODEGEN_VERSION": xcodegen.get("version"),
        "XCODEGEN_URL": xcodegen.get("url"),
        "XCODEGEN_SHA256": xcodegen.get("sha256"),
    }
    action_references = {
        _action_name(entry["reference"]): entry["reference"]
        for entry in lock.get("githubActions", [])
        if isinstance(entry, Mapping) and isinstance(entry.get("reference"), str)
    }
    expected_action_steps = [
        {"uses": action_references.get("actions/checkout")},
        {
            "name": "Python 3.12.10",
            "uses": action_references.get("actions/setup-python"),
            "with": {"python-version": "3.12.10"},
        },
    ]
    # The ios job keeps the unsigned Release archive for inspection; the generic
    # upload-artifact rules (retention, fail without files, overwrite) apply to it as well.
    ios_upload_step = {
        "name": "Upload unsigned Release archive",
        "uses": action_references.get("actions/upload-artifact"),
        "with": {
            "name": "binario-ios-unsigned-xcarchive",
            "path": "${{ runner.temp }}/Binario.xcarchive",
            "retention-days": 14,
            "if-no-files-found": "error",
            "overwrite": True,
        },
    }
    for job_name, lock_key in (
        ("watchos", "appleWatchRunSteps"),
        ("ios", "appleIosRunSteps"),
    ):
        job = _mapping(jobs.get(job_name))
        if not job:
            problems.append(f"{path.name}: job {job_name} assente")
            continue
        env = _mapping(job.get("env")) or {}
        for key, expected in expected_env.items():
            if not isinstance(expected, str) or env.get(key) != expected:
                problems.append(f"{path.name}:{job_name}: {key} non coincide con il lock revisionato")
        if set(env) != set(expected_env):
            problems.append(f"{path.name}:{job_name}: env deve contenere soltanto le chiavi revisionate")
        if set(job) != {"runs-on", "timeout-minutes", "env", "steps"}:
            problems.append(f"{path.name}:{job_name}: job contiene chiavi non revisionate")

        steps = job.get("steps")
        if not isinstance(steps, list):
            problems.append(f"{path.name}:{job_name}: steps non validi")
            continue
        actual_action_steps = [dict(step) for step in steps if isinstance(step, Mapping) and "uses" in step]
        expected_job_action_steps = expected_action_steps + ([ios_upload_step] if job_name == "ios" else [])
        if actual_action_steps != expected_job_action_steps:
            problems.append(f"{path.name}:{job_name}: step action diversi dalla sequenza revisionata")

        locked_run_entries = [entry for entry in lock.get(lock_key, []) if isinstance(entry, Mapping)]
        actual_run_steps = [step for step in steps if isinstance(step, Mapping) and "run" in step]
        if len(actual_run_steps) != len(locked_run_entries):
            problems.append(f"{path.name}:{job_name}: cardinalità step run diversa dal lock revisionato")
        runs: dict[str, str] = {}
        for index, (step, locked) in enumerate(zip(actual_run_steps, locked_run_entries)):
            expected_keys = {"name", "run"}
            if "workingDirectory" in locked:
                expected_keys.add("working-directory")
            if "id" in locked:
                expected_keys.add("id")
            if "if" in locked:
                expected_keys.add("if")
            if set(step) != expected_keys:
                problems.append(f"{path.name}:{job_name}: run step {index} contiene chiavi non revisionate")
            name = step.get("name")
            command = _commands(step.get("run"))
            if name != locked.get("name"):
                problems.append(f"{path.name}:{job_name}: ordine/nome step run diverso dal lock")
            if step.get("working-directory") != locked.get("workingDirectory"):
                problems.append(f"{path.name}:{job_name}: working-directory diversa dal lock")
            if step.get("id") != locked.get("id"):
                problems.append(f"{path.name}:{job_name}: id step diverso dal lock")
            if step.get("if") != locked.get("if"):
                problems.append(f"{path.name}:{job_name}: condizione step diversa dal lock")
            if hashlib.sha256(command.encode("utf-8")).hexdigest() != locked.get("sha256"):
                problems.append(f"{path.name}:{job_name}: contenuto step run diverso dal lock")
            if isinstance(name, str):
                runs[name] = command

        suffix = " for iOS" if job_name == "ios" else ""
        select_xcode = runs.get(f"Select pinned Xcode{suffix}", "")
        if "/Applications/Xcode_${XCODE_VERSION}.app/Contents/Developer" not in select_xcode:
            problems.append(f"{path.name}:{job_name}: selezione Xcode versionata assente")
        if '"Xcode $XCODE_VERSION"' not in select_xcode or '"Build version $XCODE_BUILD"' not in select_xcode:
            problems.append(f"{path.name}:{job_name}: verifica versione/build Xcode assente")

        trust_name = "Verify iOS trust chain" if job_name == "ios" else "Verify CI trust chain"
        trust_chain = runs.get(trust_name, "")
        required_test = "tests/test_verify_ios_project.py" if job_name == "ios" else "tests/test_verify_apple_watch_project.py"
        if required_test not in trust_chain:
            problems.append(f"{path.name}:{job_name}: test statico progetto Apple assente")

        install_name = "Install XcodeGen for iOS" if job_name == "ios" else "Install XcodeGen"
        install = runs.get(install_name, "")
        expected_install_lines = [
            'destination="$RUNNER_TEMP/xcodegen-bin/xcodegen"',
            'python3 "$GITHUB_WORKSPACE/tools/install_xcodegen.py"',
            '--version "$XCODEGEN_VERSION"',
            '--url "$XCODEGEN_URL"',
            '--sha256 "$XCODEGEN_SHA256"',
            '--destination "$destination"',
            'echo "$(dirname "$destination")" >> "$GITHUB_PATH"',
            '"$destination" --version',
        ]
        actual_install_lines = [
            line.strip().removesuffix("\\").rstrip()
            for line in install.splitlines()
            if line.strip()
        ]
        if actual_install_lines != expected_install_lines:
            problems.append(f"{path.name}:{job_name}: step install XcodeGen diverso dal comando revisionato")

        if job_name == "ios":
            if ("python3 tools/verify_ios_gtfs_schedule.py" not in trust_chain
                or "python3 tools/verify_ios_gtfs_rebuild.py" not in trust_chain
                or "git diff --exit-code -- ios/Resources/binario_gtfs_schedule_v1" in trust_chain):
                problems.append(f"{path.name}: confronto GTFS cross-platform verificato assente")
            if "swift test --package-path ios/BinarioKit" not in runs.get("Test BinarioKit with Swift 6", ""):
                problems.append(f"{path.name}: test Swift 6 BinarioKit assente")
            generation = runs.get("Verify and generate integrated iOS project", "")
            if "verify-deterministic-generation.py" not in generation or "xcodegen generate --spec project.yml" not in generation:
                problems.append(f"{path.name}: generazione iOS deterministica assente")
            build = runs.get("Build and test integrated iPhone and Watch app", "")
            if "-project Binario.xcodeproj" not in build or "-scheme Binario" not in build:
                problems.append(f"{path.name}: build/test iPhone integrato assente")
            if "-scheme BinarioWatch" not in build or "steps.ios-simulator.outputs.watch_udid" not in build:
                problems.append(f"{path.name}: test Watch sul progetto integrato assente")
            # The simulator tests never exercise what App Store Connect receives: the Release
            # archive of the generated plists, the embedded Watch app and widget and the
            # privacy manifests must be produced and inspected, unsigned, on every run.
            archive = runs.get("Archive Release build and check the App Store bundle layout", "")
            archive_markers = (
                "-configuration Release",
                '-destination "generic/platform=iOS"',
                '-archivePath "$RUNNER_TEMP/Binario.xcarchive"',
                "CODE_SIGNING_ALLOWED=NO",
                "Products/Applications/Binario.app",
                "Watch/BinarioWatch.app",
                "PlugIns/BinarioWatchWidgets.appex",
                "PrivacyInfo.xcprivacy",
                "plutil -extract ITSAppUsesNonExemptEncryption raw",
                "CFBundleIcons.CFBundlePrimaryIcon.CFBundleIconName",
                '[ "$value" = "AppIcon" ]',
                '[ "$top_level" = "Binario.app" ]',
                "WKCompanionAppBundleIdentifier",
                '[ "$value" = "it.binario.ios" ]',
            )
            # Each check records a failure instead of exiting, so the step must end on the tally.
            if any(marker not in archive for marker in archive_markers) or not archive.rstrip().endswith('test "$failures" -eq 0'):
                problems.append(f"{path.name}: archivio Release non firmato con verifica del bundle App Store assente")


def workflow_problems(
    path: Path,
    text: str,
    lock: Mapping[str, Any] | None = None,
) -> list[str]:
    problems: list[str] = []
    lock = lock or load_ci_lock()
    allowed_actions = {
        entry["reference"]
        for entry in lock.get("githubActions", [])
        if isinstance(entry, Mapping) and isinstance(entry.get("reference"), str)
    }
    allowed_images = {
        entry["reference"]
        for entry in lock.get("containerImages", [])
        if isinstance(entry, Mapping) and isinstance(entry.get("reference"), str)
    }
    allowed_runners = {
        entry["label"]
        for entry in lock.get("runners", [])
        if isinstance(entry, Mapping) and isinstance(entry.get("label"), str)
    }
    runtime_locks = {
        entry["action"]: entry
        for entry in lock.get("runtimes", [])
        if isinstance(entry, Mapping) and isinstance(entry.get("action"), str)
    }

    try:
        document = yaml.load(text, Loader=WorkflowLoader)
    except yaml.YAMLError as error:
        return [f"{path.name}: YAML non valido: {error}"]
    root = _mapping(document)
    jobs = _mapping(root.get("jobs")) if root else None
    if not jobs:
        return [f"{path.name}: jobs assente o non valido"]

    for job_name, raw_job in jobs.items():
        location = f"{path.name}:jobs.{job_name}"
        job = _mapping(raw_job)
        if not job:
            problems.append(f"{location}: job non valido")
            continue
        if "uses" in job:
            _check_action(job["uses"], location=f"{location}.uses", allowed_actions=allowed_actions, problems=problems)
        runner = job.get("runs-on")
        if not isinstance(runner, str) or runner not in allowed_runners:
            problems.append(f"{location}.runs-on: runner non versionato o fuori lock: {runner}")

        if "container" in job:
            container = job["container"]
            container_map = _mapping(container)
            image = container_map.get("image") if container_map else container
            _check_image(image, location=f"{location}.container", allowed_images=allowed_images, problems=problems)
        services = _mapping(job.get("services")) or {}
        for service_name, raw_service in services.items():
            service = _mapping(raw_service)
            image = service.get("image") if service else None
            _check_image(
                image,
                location=f"{location}.services.{service_name}.image",
                allowed_images=allowed_images,
                problems=problems,
            )
        steps = job.get("steps", [])
        if not isinstance(steps, list):
            problems.append(f"{location}.steps: lista non valida")
            continue
        for index, raw_step in enumerate(steps):
            step = _mapping(raw_step)
            if step and "uses" in step:
                action_name = _check_action(
                    step["uses"],
                    location=f"{location}.steps[{index}].uses",
                    allowed_actions=allowed_actions,
                    problems=problems,
                )
                if action_name:
                    _check_runtime_inputs(
                        action_name,
                        step,
                        location=f"{location}.steps[{index}]",
                        runtime_locks=runtime_locks,
                        problems=problems,
                    )
                if action_name == "actions/upload-artifact":
                    _check_upload_artifact(step, location=f"{location}.steps[{index}]", problems=problems)

    _check_linux_trigger_budget(path, root or {}, lock, problems)
    _check_freshness_gates(path, root or {}, problems)
    _check_punctuality_release_gates(path, root or {}, problems)
    _check_permissions(path, root or {}, problems)
    _check_run_hygiene(path, root or {}, problems)
    _check_triggers_checkouts_and_secrets(path, root or {}, problems)
    if path.name not in {"alexa-companion-ci.yml", "android-ci.yml", "companion-ci.yml", "gtfs-assets-ci.yml", "apple-watch-ci.yml"}:
        # The named workflows check the same policy with their own messages above.
        _check_push_branches(path, root or {}, lock, problems)
    if path.name == "android-ci.yml":
        _check_release_signing(path, root or {}, problems)
        _check_punctuality_switch(path, root or {}, problems)
    if path.name == PUNCTUALITY_WORKFLOW:
        _check_punctuality(path, root or {}, problems)
    elif "tools/collect_punctuality.py" in _workflow_commands(jobs):
        problems.append(f"{path.name}: ViaggiaTreno per le statistiche di puntualità si interroga solo da {PUNCTUALITY_WORKFLOW}")
    if path.name == "apple-watch-ci.yml":
        _check_apple_paths(path, root or {}, lock, problems)
        _check_apple_toolchain(path, jobs, lock, problems)
    return problems


def verify_repository() -> list[str]:
    try:
        lock = load_ci_lock()
    except (OSError, ValueError, json.JSONDecodeError) as error:
        return [f"{LOCK_PATH.name}: {error}"]
    workflow_paths = sorted({*WORKFLOW_DIR.glob("*.yml"), *WORKFLOW_DIR.glob("*.yaml")})
    problems: list[str] = []
    for path in workflow_paths:
        problems.extend(workflow_problems(path, path.read_text(encoding="utf-8"), lock))
    return problems


def main() -> int:
    problems = verify_repository()
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    print("CI pinning verification passed: semantic YAML matches the reviewed CI input lock.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
