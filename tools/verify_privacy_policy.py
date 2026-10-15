#!/usr/bin/env python3
"""Fail fast if privacy and source-disclosure inventory or release links drift."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

REQUIRED_DISCLOSURES: tuple[tuple[str, str, tuple[str, ...]], ...] = (
    (
        "PRIVACY.md",
        "PRIVACY.md",
        (
            "## App Android",
            "## Companion Wear OS",
            "## Companion Expo",
            "## Alexa Companion",
            "Posizione effimera",
            "Feed ferroviari esterni, European Sleeper/JBB e nomi di stazione",
            "Backup, conservazione e cancellazione",
            "Revoca, cancellazione e conservazione",
            "riconoscitore vocale di sistema",
            "titolare del trattamento",
            "URL pubblico",
            "European Sleeper/JBB",
            "`jbb.ghsq.de`",
            "community best-effort",
            "tier primario fallisce",
            "metadati di rete della richiesta sono visibili",
        ),
    ),
    (
        "Community fallback data-source policy",
        "docs/data-sources/SOURCE_POLICY.md",
        (
            "European Sleeper / JBB: fallback di comunità",
            "`jbb.ghsq.de`",
            "community best-effort",
            "tier primario",
            "HTTPS `GET`",
            "metadati di rete ordinari",
        ),
    ),
    ("README.md", "README.md", ("[PRIVACY.md](PRIVACY.md)",)),
    ("CHANGELOG.md", "CHANGELOG.md", ("[l'informativa privacy verificabile](PRIVACY.md)",)),
    (
        "docs/PIANO_STABILIZZAZIONE_v0.20.4.md",
        "docs/PIANO_STABILIZZAZIONE_v0.20.4.md",
        ("[PRIVACY.md](../PRIVACY.md)", "URL pubblico verificato"),
    ),
    (
        "Android in-app disclosure",
        "app/src/main/res/values/strings.xml",
        (
            "Le ricerche RFI inviano alla fonte l’identificativo pubblico della stazione di partenza",
            "Disattivare la condivisione elimina il DataItem e la cache locale del watch alla ricezione della revoca.",
            "non esiste ancora un link pubblico nell’app",
        ),
    ),
    (
        "Expo in-app disclosure rendering",
        "companion-expo/app/(tabs)/settings.tsx",
        (
            't("settings.privacyDescription")',
            't("settings.privacyDisclosure")',
        ),
    ),
    (
        "Expo Italian privacy catalog",
        "companion-expo/lib/i18n.ts",
        (
            "senza salvarla o inviarla",
            "Binario non registra audio",
            "non esegue abbinamento o sincronizzazione remota",
            "non viene mostrato un link inventato",
        ),
    ),
)


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require_all(label: str, text: str, fragments: tuple[str, ...]) -> list[str]:
    return [f"{label}: missing {fragment!r}" for fragment in fragments if fragment not in text]


def verification_errors(read_file=read) -> list[str]:
    errors: list[str] = []
    for label, relative_path, fragments in REQUIRED_DISCLOSURES:
        errors += require_all(label, read_file(relative_path), fragments)
    return errors


def main() -> int:
    errors = verification_errors()
    if errors:
        print("Privacy-policy static verification failed:", file=sys.stderr)
        print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
        return 1
    print("Privacy-policy static verification passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
