#!/usr/bin/env python3
"""Every dated document under ``docs/`` says, right under its title, whether it is current.

A document with a date (``YYYY-MM-DD``) in its path describes the project as it was that day; read later, it looks
current unless it says otherwise (second audit, parity-docs-12).  Within its first lines it carries one of:

    > **Documento storico.** Descrive lo stato del progetto alla sua data e non viene aggiornato: ...
    > **Stato del documento:** in uso per ... (a dated document still in use)

Offline, standard library only.  Exit status 1 listing the documents without one.

    python3 tools/verify_docs_status.py
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Sequence

ROOT = Path(__file__).resolve().parents[1]
DATED = re.compile(r"\d{4}-\d{2}-\d{2}")
STATUS = re.compile(r"> \*\*(?:Documento storico|Stato del documento)\b")
HEAD_LINES = 12


def dated_documents(root: Path) -> list[Path]:
    docs = root / "docs"
    return sorted(path for path in docs.rglob("*.md") if DATED.search(path.relative_to(root).as_posix()))


def has_status(path: Path) -> bool:
    with path.open(encoding="utf-8") as stream:
        head = [line for _, line in zip(range(HEAD_LINES), stream)]
    return any(STATUS.match(line) for line in head)


def problems(root: Path = ROOT) -> list[str]:
    return [f"{path.relative_to(root).as_posix()}: documento datato senza riga di stato («> **Documento storico.**» "
            f"o «> **Stato del documento:**» nelle prime {HEAD_LINES} righe)"
            for path in dated_documents(root) if not has_status(path)]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    found = problems(args.root)
    for problem in found:
        print(f"ERRORE: {problem}", file=sys.stderr)
    if not found:
        print(f"Documenti datati con riga di stato: {len(dated_documents(args.root))}")
    return 1 if found else 0


if __name__ == "__main__":
    raise SystemExit(main())
