#!/usr/bin/env python3
"""Tests for tools/verify_docs_status.py: every dated document under docs/ says whether it is current."""
from __future__ import annotations

import contextlib
import io
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import verify_docs_status as status  # noqa: E402


class VerifyDocsStatusTest(unittest.TestCase):
    def test_the_repository_documents_pass(self) -> None:
        self.assertEqual([], status.problems(ROOT))

    def test_a_dated_document_needs_a_status_line_under_its_title(self) -> None:
        root = Path(tempfile.mkdtemp(prefix="docs-status-"))
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        docs = root / "docs"
        (docs / "handoffs" / "2026-10-25-artifacts").mkdir(parents=True)
        (docs / "AUDIT_2026-09-05.md").write_text("# Audit\n\nRamo analizzato: x\n", encoding="utf-8")
        (docs / "handoffs" / "2026-10-25-artifacts" / "README.md").write_text("# Materiale\n", encoding="utf-8")
        (docs / "STATO_2026-09-23.md").write_text(
            "# Stato\n\n> **Documento storico.** Descrive lo stato alla sua data.\n", encoding="utf-8")
        (docs / "PROTOCOLLO_2026-09-03.md").write_text(
            "# Protocollo\n\n> **Stato del documento:** in uso.\n", encoding="utf-8")
        (docs / "GUIDA.md").write_text("# Guida corrente, senza data\n", encoding="utf-8")
        late = "# Tardi\n" + "\n" * 20 + "> **Documento storico.** troppo in basso\n"
        (docs / "TARDI_2026-01-01.md").write_text(late, encoding="utf-8")
        self.assertEqual(["docs/AUDIT_2026-09-05.md", "docs/TARDI_2026-01-01.md",
                          "docs/handoffs/2026-10-25-artifacts/README.md"],
                         [problem.split(":")[0] for problem in status.problems(root)])
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.assertEqual(1, status.main(["--root", str(root)]))
            self.assertEqual(0, status.main(["--root", str(ROOT)]))
        self.assertIn("Documento storico", output.getvalue())


if __name__ == "__main__":
    unittest.main()
