#!/usr/bin/env python3
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIREMENTS = ROOT / "requirements-ci.txt"
HASH_LOCKED_WORKFLOWS = (
    ROOT / ".github" / "workflows" / "gtfs-assets-ci.yml",
    ROOT / ".github" / "workflows" / "apple-watch-ci.yml",
)
PY_YAML_6_0_3_HASHES = {
    "ba1cc08a7ccde2d2ec775841541641e4548226580ab850948cbfda66a1befcdc": "manylinux x86_64",
    "fc09d0aa354569bc501d4e787133afc08552722d3ab34836a80547331bb5d4a0": "macOS arm64",
}


class RequirementsCiTest(unittest.TestCase):
    def test_pyyaml_is_binary_only_and_hash_locked_for_ci_platforms(self) -> None:
        text = REQUIREMENTS.read_text(encoding="utf-8")
        self.assertIn("--only-binary=PyYAML", text)
        self.assertRegex(text, r"(?m)^PyYAML==6\.0\.3(?:\s|\\)")
        stanza = re.search(r"(?ms)^PyYAML==6\.0\.3.*?(?=^\S|\Z)", text)
        self.assertIsNotNone(stanza)
        found = set(re.findall(r"--hash=sha256:([0-9a-f]{64})", stanza.group(0)))
        self.assertEqual(set(PY_YAML_6_0_3_HASHES), found)

    def test_workflows_install_the_lock_with_require_hashes(self) -> None:
        command = "python3 -m pip install --require-hashes -r requirements-ci.txt"
        for workflow in HASH_LOCKED_WORKFLOWS:
            self.assertIn(command, workflow.read_text(encoding="utf-8"), workflow.name)


if __name__ == "__main__":
    unittest.main()
