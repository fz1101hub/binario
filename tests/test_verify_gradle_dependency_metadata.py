#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "verify_gradle_dependency_metadata",
    ROOT / "tools" / "verify_gradle_dependency_metadata.py",
)
assert SPEC and SPEC.loader
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)
REAL_METADATA = ROOT / "gradle" / "verification-metadata.xml"


class VerifyGradleDependencyMetadataTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="gradle-metadata-test-")
        self.path = Path(self.temp.name) / "verification-metadata.xml"
        self.wrapper_path = Path(self.temp.name) / "gradle-wrapper.properties"
        self.original = REAL_METADATA.read_text(encoding="utf-8")
        self.wrapper_original = POLICY.ROOT_WRAPPER.read_text(encoding="utf-8")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def verify_mutation(self, old: str, new: str) -> list[str]:
        mutated = self.original.replace(old, new, 1)
        self.assertNotEqual(self.original, mutated)
        self.path.write_text(mutated, encoding="utf-8")
        return POLICY.verify_metadata(self.path)

    def test_repository_metadata_is_strict_and_complete(self) -> None:
        self.assertEqual([], POLICY.verify_metadata())

    def test_remotely_resolved_bom_and_parent_metadata_have_reviewed_sha256(self) -> None:
        root = ET.fromstring(self.original)
        ns = {"v": "https://schema.gradle.org/dependency-verification"}
        expected = {
            ("com.google.guava", "guava-parent", "33.3.1-jre", "guava-parent-33.3.1-jre.pom"): "55441db27e8869dfefe053059bdf478bdc7e95585642bf391f0023345fd56287",
            ("org.junit", "junit-bom", "5.10.2", "junit-bom-5.10.2.module"): "de23b114b3e4119a8fe6eb17bed5a3852816698bace67071579d6d927ebb080a",
            ("org.jetbrains.kotlinx", "kotlinx-coroutines-bom", "1.6.4", "kotlinx-coroutines-bom-1.6.4.pom"): "ab2614855fba66aa8a42514dbe3d5a884315ffe1ed63f5932e710a8006245ce1",
        }
        for (group, name, version, artifact), digest in expected.items():
            with self.subTest(artifact=artifact):
                matches = [item for item in root.findall(".//v:component", ns)
                           if (item.get("group"), item.get("name"), item.get("version")) == (group, name, version)]
                self.assertEqual(1, len(matches))
                artifacts = [item for item in matches[0].findall("v:artifact", ns) if item.get("name") == artifact]
                self.assertEqual(1, len(artifacts))
                self.assertEqual(digest, artifacts[0].find("v:sha256", ns).get("value"))

    def test_repository_wrappers_pin_reviewed_distributions(self) -> None:
        for profile, (path, expected_url, expected_sha256) in POLICY.WRAPPER_EXPECTATIONS.items():
            with self.subTest(profile=profile):
                self.assertEqual(
                    [],
                    POLICY.verify_wrapper_checksum(
                        path,
                        expected_url=expected_url,
                        expected_sha256=expected_sha256,
                    ),
                )

    def test_rejects_missing_wrong_or_duplicate_wrapper_checksum(self) -> None:
        expected_url = POLICY.WRAPPER_EXPECTATIONS["root"][1]
        expected_sha256 = POLICY.WRAPPER_EXPECTATIONS["root"][2]

        for name, mutation in {
            "missing": self.wrapper_original.replace(
                f"distributionSha256Sum={expected_sha256}\n", ""
            ),
            "wrong": self.wrapper_original.replace(expected_sha256, "0" * 64),
            "duplicate": self.wrapper_original.replace(
                f"distributionSha256Sum={expected_sha256}\n",
                f"distributionSha256Sum={expected_sha256}\n"
                f"distributionSha256Sum={expected_sha256}\n",
            ),
        }.items():
            with self.subTest(name=name):
                self.wrapper_path.write_text(mutation, encoding="utf-8")
                problems = POLICY.verify_wrapper_checksum(
                    self.wrapper_path,
                    expected_url=expected_url,
                    expected_sha256=expected_sha256,
                )
                self.assertTrue(problems)

    def test_companion_metadata_is_strict_and_complete(self) -> None:
        self.assertEqual(
            [],
            POLICY.verify_metadata(
                POLICY.COMPANION_METADATA,
                min_components=1_000,
                min_artifacts=1_800,
                required_components=POLICY.COMPANION_REQUIRED_COMPONENTS,
            ),
        )

    def test_companion_included_build_metadata_has_reviewed_checksums(self) -> None:
        root = ET.parse(POLICY.COMPANION_METADATA).getroot()
        ns = {"v": "https://schema.gradle.org/dependency-verification"}
        expected = {
            ("org.jetbrains.kotlinx", "kotlinx-coroutines-bom", "1.8.0", "kotlinx-coroutines-bom-1.8.0.pom"): "1239e9dbe1397cd5971342956b2511bc3ace7b641842e4372a088dcfa8b9ad55",
            ("com.google.guava", "guava-parent", "33.3.1-jre", "guava-parent-33.3.1-jre.pom"): "55441db27e8869dfefe053059bdf478bdc7e95585642bf391f0023345fd56287",
            ("org.junit", "junit-bom", "5.10.2", "junit-bom-5.10.2.module"): "de23b114b3e4119a8fe6eb17bed5a3852816698bace67071579d6d927ebb080a",
            ("org.junit", "junit-bom", "5.13.1", "junit-bom-5.13.1.module"): "33c07ab9724790a6e5859ba07d69117ac530439724545a81c4179e3272c75de8",
        }
        for (group, name, version, artifact), digest in expected.items():
            with self.subTest(artifact=artifact):
                components = [item for item in root.findall(".//v:component", ns)
                              if (item.get("group"), item.get("name"), item.get("version")) == (group, name, version)]
                self.assertEqual(1, len(components))
                artifacts = [item for item in components[0].findall("v:artifact", ns) if item.get("name") == artifact]
                self.assertEqual(1, len(artifacts))
                self.assertEqual(digest, artifacts[0].find("v:sha256", ns).get("value"))

    def test_rejects_disabled_metadata_verification(self) -> None:
        problems = self.verify_mutation(
            "<verify-metadata>true</verify-metadata>",
            "<verify-metadata>false</verify-metadata>",
        )
        self.assertTrue(any("configuration" in problem for problem in problems))

    def test_rejects_trust_bypass(self) -> None:
        problems = self.verify_mutation(
            "<components>",
            '<trusted-artifacts><trust group="*"/></trusted-artifacts><components>',
        )
        self.assertTrue(any("trusted-artifacts" in problem for problem in problems))

    def test_rejects_missing_or_invalid_checksum(self) -> None:
        first_sha = '<sha256 value="c72301bc7307769cd6c3e0edb8da5837e7dea6fd89a5cdedfcfc87bd581850e1" origin="Generated by Gradle"/>'
        problems = self.verify_mutation(first_sha, "")
        self.assertTrue(any("un solo sha256" in problem for problem in problems))
        problems = self.verify_mutation(first_sha, '<sha256 value="not-a-digest"/>')
        self.assertTrue(any("sha256 non valido" in problem for problem in problems))

    def test_rejects_duplicate_component(self) -> None:
        component = """      <component group=\"androidx.activity\" name=\"activity\" version=\"1.10.0\">
         <artifact name=\"activity-1.10.0.aar\">
            <sha256 value=\"c72301bc7307769cd6c3e0edb8da5837e7dea6fd89a5cdedfcfc87bd581850e1\" origin=\"Generated by Gradle\"/>
         </artifact>
         <artifact name=\"activity-1.10.0.module\">
            <sha256 value=\"334315c325c3bad343fc5f70b7a5126e268ff0fea38b0f5dc068106a5286898f\" origin=\"Generated by Gradle\"/>
         </artifact>
      </component>"""
        problems = self.verify_mutation(component, component + "\n" + component)
        self.assertTrue(any("componente duplicato" in problem for problem in problems))


if __name__ == "__main__":
    unittest.main()
