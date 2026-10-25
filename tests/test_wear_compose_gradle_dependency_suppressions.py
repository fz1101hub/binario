#!/usr/bin/env python3
"""Keep GradleDependency policy explicit for the app and exact for Wear artifacts."""
from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP_BUILD = ROOT / "app" / "build.gradle.kts"
WEAR_BUILD = ROOT / "wear" / "build.gradle.kts"
SUPPRESSION = "//noinspection GradleDependency"
APP_GLOBAL_POLICY = 'disable += "GradleDependency"'
EXPECTED_DEPENDENCIES = (
    'implementation(platform("androidx.compose:compose-bom:2026.06.01"))',
    'implementation("androidx.core:core-ktx:1.15.0")',
    'implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")',
    'implementation("androidx.wear.compose:compose-material3:1.6.2")',
    'implementation("androidx.wear.compose:compose-foundation:1.6.2")',
    'implementation("androidx.wear.compose:compose-ui-tooling:1.6.2")',
)
REQUIRED_RATIONALE = "AGP >= 9.1 and compileSdk >= 37"


class WearComposeGradleDependencySuppressionsTest(unittest.TestCase):
    def test_android_target_api_exception_is_narrow_and_lint_remains_strict(self) -> None:
        for build_file in (APP_BUILD, WEAR_BUILD):
            with self.subTest(module=build_file.parent.name):
                source = build_file.read_text(encoding="utf-8")
                self.assertIn('compileSdk = 36', source)
                self.assertIn('targetSdk = 36', source)
                self.assertIn('warningsAsErrors = true', source)
                self.assertIn('abortOnError = true', source)
                self.assertIn('checkReleaseBuilds = true', source)
                self.assertEqual(1, source.count('disable += "OldTargetApi"'))
                self.assertIn('Revisit with a tested API 37 migration', source)

    def test_only_toolchain_pinned_wear_compose_dependencies_are_suppressed(self) -> None:
        gradle_files = [
            path
            for path in sorted(ROOT.rglob("*.gradle.kts")) + sorted(ROOT.rglob("*.gradle"))
            if path.is_file()
        ]
        suppression_locations: list[tuple[Path, int]] = []
        app_policy_locations: list[tuple[Path, int]] = []
        for path in gradle_files:
            for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
                if "GradleDependency" not in line:
                    continue
                if path == APP_BUILD and line.strip() == APP_GLOBAL_POLICY:
                    app_policy_locations.append((path, line_number))
                    continue
                self.assertEqual(
                    SUPPRESSION,
                    line.strip(),
                    f"GradleDependency must not be disabled globally or used outside the approved suppressions: {path.relative_to(ROOT)}:{line_number}",
                )
                suppression_locations.append((path, line_number))

        self.assertEqual(1, len(app_policy_locations))
        self.assertEqual(len(EXPECTED_DEPENDENCIES), len(suppression_locations))
        self.assertEqual({WEAR_BUILD}, {path for path, _ in suppression_locations})

        lines = WEAR_BUILD.read_text(encoding="utf-8").splitlines()
        actual_dependencies = tuple(
            lines[line_number].strip()
            for _, line_number in suppression_locations
        )
        self.assertEqual(EXPECTED_DEPENDENCIES, actual_dependencies)

        for dependency, (_, line_number) in zip(EXPECTED_DEPENDENCIES, suppression_locations, strict=True):
            suppression_index = line_number - 1
            self.assertIn(REQUIRED_RATIONALE, lines[suppression_index - 1])
            self.assertIn("TODO: remove", lines[suppression_index - 1])
            self.assertEqual(dependency, lines[suppression_index + 1].strip())


if __name__ == "__main__":
    unittest.main()
