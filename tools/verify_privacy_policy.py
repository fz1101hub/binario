#!/usr/bin/env python3
"""Fail fast if the privacy-policy inventory or its required release links drift."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require_all(label: str, text: str, fragments: tuple[str, ...]) -> list[str]:
    return [f"{label}: missing {fragment!r}" for fragment in fragments if fragment not in text]


def main() -> int:
    policy = read("PRIVACY.md")
    errors: list[str] = []
    errors += require_all(
        "PRIVACY.md",
        policy,
        (
            "## App Android",
            "## Companion Wear OS",
            "## Companion Expo",
            "## Alexa Companion",
            "Posizione effimera",
            "Feed ferroviari esterni e nomi di stazione",
            "Backup, conservazione e cancellazione",
            "Revoca, cancellazione e conservazione",
            "riconoscitore vocale di sistema",
            "titolare del trattamento",
            "URL pubblico",
        ),
    )
    errors += require_all("README.md", read("README.md"), ("[PRIVACY.md](PRIVACY.md)",))
    errors += require_all("CHANGELOG.md", read("CHANGELOG.md"), ("[l'informativa privacy verificabile](PRIVACY.md)",))
    errors += require_all(
        "docs/PIANO_STABILIZZAZIONE_v0.20.4.md",
        read("docs/PIANO_STABILIZZAZIONE_v0.20.4.md"),
        ("[PRIVACY.md](../PRIVACY.md)", "URL pubblico verificato"),
    )
    errors += require_all(
        "Android in-app disclosure",
        read("app/src/main/java/it/binario/app/MainActivity.kt"),
        (
            "Le ricerche RFI inviano alla fonte l’identificativo pubblico della stazione di partenza",
            "non cancella la cache già sul watch",
            "non esiste ancora un link pubblico nell’app",
        ),
    )
    errors += require_all(
        "Expo in-app disclosure",
        read("companion-expo/app/(tabs)/settings.tsx"),
        (
            "senza salvarla o inviarla",
            "Binario non registra audio",
            "non esegue pairing o sincronizzazione remota",
            "non viene mostrato un link inventato nell’app",
        ),
    )
    if errors:
        print("Privacy-policy static verification failed:", file=sys.stderr)
        print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
        return 1
    print("Privacy-policy static verification passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
