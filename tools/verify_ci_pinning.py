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
    if data.get("schemaVersion") != 2:
        raise ValueError("schemaVersion lock CI non supportata")
    try:
        date.fromisoformat(data["verifiedAt"])
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError("verifiedAt lock CI assente o non ISO") from error

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
    run_steps = data.get("appleWatchRunSteps", [])
    expected_run_names = {
        "Verify CI trust chain",
        "Select pinned Xcode",
        "Install XcodeGen",
        "Generate Xcode project",
        "Select an available watchOS simulator",
        "Build and test Apple Watch",
    }
    if (
        not isinstance(run_steps, list)
        or len(run_steps) != len(expected_run_names)
        or {entry.get("name") for entry in run_steps if isinstance(entry, Mapping)} != expected_run_names
        or any(
            not isinstance(entry, Mapping)
            or not set(entry) <= {"name", "sha256", "workingDirectory", "id"}
            or not isinstance(entry.get("sha256"), str)
            or not HEX_64.fullmatch(entry["sha256"])
            or ("workingDirectory" in entry and not isinstance(entry["workingDirectory"], str))
            or ("id" in entry and not isinstance(entry["id"], str))
            for entry in run_steps
        )
    ):
        raise ValueError("lock step run Apple non valido")
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


def _check_apple_paths(path: Path, workflow: Mapping[str, Any], problems: list[str]) -> None:
    required_paths = {
        "tools/install_xcodegen.py",
        "tools/verify_ci_pinning.py",
        "tests/test_install_xcodegen.py",
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
    if apple_push.get("branches") != ["main"]:
        problems.append(f"{path.name}: push branches deve limitarsi a main")


def _check_linux_trigger_budget(path: Path, workflow: Mapping[str, Any], problems: list[str]) -> None:
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
    if branches != ["main"]:
        problems.append(f"{path.name}: push branches deve limitarsi a main per non duplicare pull_request")
    required_paths = {
        "android-ci.yml": {"app/**", "wear/**", "contracts/wear-snapshot/**"},
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
    job = _mapping(jobs.get("watchos"))
    if not job:
        problems.append(f"{path.name}: job watchos assente")
        return
    tools = {
        tool.get("name"): tool
        for tool in lock.get("tools", [])
        if isinstance(tool, Mapping) and isinstance(tool.get("name"), str)
    }
    xcode = tools.get("Xcode", {})
    xcodegen = tools.get("XcodeGen", {})
    env = _mapping(job.get("env")) or {}
    expected_env = {
        "XCODE_VERSION": xcode.get("version"),
        "XCODE_BUILD": xcode.get("build"),
        "XCODEGEN_VERSION": xcodegen.get("version"),
        "XCODEGEN_URL": xcodegen.get("url"),
        "XCODEGEN_SHA256": xcodegen.get("sha256"),
    }
    for key, expected in expected_env.items():
        if not isinstance(expected, str) or env.get(key) != expected:
            problems.append(f"{path.name}: {key} non coincide con il lock revisionato")
    if set(env) != set(expected_env):
        problems.append(f"{path.name}: env watchos deve contenere soltanto le chiavi revisionate")
    if set(job) != {"runs-on", "timeout-minutes", "env", "steps"}:
        problems.append(f"{path.name}: job watchos contiene chiavi non revisionate")

    steps = job.get("steps")
    if not isinstance(steps, list):
        problems.append(f"{path.name}: steps watchos non validi")
        return
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
    actual_action_steps = [dict(step) for step in steps if isinstance(step, Mapping) and "uses" in step]
    if actual_action_steps != expected_action_steps:
        problems.append(f"{path.name}: gli step action Apple non coincidono con la sequenza revisionata")

    locked_run_entries = [
        entry
        for entry in lock.get("appleWatchRunSteps", [])
        if isinstance(entry, Mapping)
    ]
    actual_run_steps = [step for step in steps if isinstance(step, Mapping) and "run" in step]
    if len(actual_run_steps) != len(locked_run_entries):
        problems.append(f"{path.name}: cardinalità step run Apple diversa dal lock revisionato")
    runs: dict[str, str] = {}
    for index, (step, locked) in enumerate(zip(actual_run_steps, locked_run_entries)):
        expected_keys = {"name", "run"}
        if "workingDirectory" in locked:
            expected_keys.add("working-directory")
        if "id" in locked:
            expected_keys.add("id")
        if set(step) != expected_keys:
            problems.append(f"{path.name}: watchos run step {index} contiene chiavi non revisionate")
        name = step.get("name")
        command = _commands(step.get("run"))
        if name != locked.get("name"):
            problems.append(f"{path.name}: ordine/nome step run Apple diverso dal lock")
        if step.get("working-directory") != locked.get("workingDirectory"):
            problems.append(f"{path.name}: working-directory step Apple diversa dal lock")
        if step.get("id") != locked.get("id"):
            problems.append(f"{path.name}: id step Apple diverso dal lock")
        if hashlib.sha256(command.encode("utf-8")).hexdigest() != locked.get("sha256"):
            problems.append(f"{path.name}: contenuto step run Apple diverso dal lock")
        if isinstance(name, str):
            runs[name] = command
    select_xcode = runs.get("Select pinned Xcode", "")
    if "/Applications/Xcode_${XCODE_VERSION}.app/Contents/Developer" not in select_xcode:
        problems.append(f"{path.name}: selezione Xcode versionata assente")
    if '"Xcode $XCODE_VERSION"' not in select_xcode or '"Build version $XCODE_BUILD"' not in select_xcode:
        problems.append(f"{path.name}: verifica versione/build Xcode assente")

    trust_chain = runs.get("Verify CI trust chain", "")
    if "tests/test_verify_apple_watch_project.py" not in trust_chain:
        problems.append(f"{path.name}: test statico progetto Apple assente dal rail macOS")

    install = runs.get("Install XcodeGen", "")
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
        problems.append(f"{path.name}: step Install XcodeGen diverso dal comando revisionato")


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

    _check_linux_trigger_budget(path, root or {}, problems)
    if path.name == "apple-watch-ci.yml":
        _check_apple_paths(path, root or {}, problems)
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
