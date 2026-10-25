#!/usr/bin/env python3
"""Fail fast if privacy and source-disclosure inventory or release links drift."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
DATA_SAFETY_PATH = "docs/store/PLAY_DATA_SAFETY.md"
HOST_SOURCE_GLOBS = ("app/src/main/java/**/*.kt", "wear/src/main/java/**/*.kt")
HOST_SOURCE_FILES = (
    "app/src/main/assets/gtfs/manifest.json",
    "app/src/main/assets/stations/manifest.json",
)
# XML parser feature and namespace URIs are not network endpoints.
NON_NETWORK_URI_PREFIXES = (
    "http://apache.org/xml/",
    "http://xml.org/sax/",
    "http://javax.xml.XMLConstants/",
)
URL_PATTERN = re.compile(r"(https?://([A-Za-z0-9.-]+))[^\s\"'<>)]*")
HOST_ROW = re.compile(r"^\| `([a-z0-9.-]+)` \|", re.MULTILINE)

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
            "[docs/store/PLAY_DATA_SAFETY.md](docs/store/PLAY_DATA_SAFETY.md)",
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
    (
        "Play Data safety answers",
        DATA_SAFETY_PATH,
        (
            "## Risposte proposte al modulo",
            "## Inventario degli host presenti nel codice Android",
            "ACCESS_COARSE_LOCATION",
            "POST_NOTIFICATIONS",
            "SCHEDULE_EXACT_ALARM",
            "Data Layer",
            "`prm.rfi.it`",
            "`jbb.ghsq.de`",
            "Nessun SDK",
            "URL pubblico",
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


def code_hosts(root: Path = ROOT) -> set[str]:
    """Every network host referenced by the Android sources and asset manifests."""
    paths = [path for pattern in HOST_SOURCE_GLOBS for path in root.glob(pattern)]
    paths += [root / relative for relative in HOST_SOURCE_FILES]
    hosts: set[str] = set()
    for path in paths:
        for match in URL_PATTERN.finditer(path.read_text(encoding="utf-8")):
            if not match.group(0).startswith(NON_NETWORK_URI_PREFIXES):
                hosts.add(match.group(2).lower())
    return hosts


def host_inventory_errors(data_safety: str, hosts: set[str]) -> list[str]:
    documented = set(HOST_ROW.findall(data_safety))
    errors = [f"Play Data safety host table: missing `{host}`" for host in sorted(hosts - documented)]
    errors += [f"Play Data safety host table: stale `{host}` no longer in app code" for host in sorted(documented - hosts)]
    return errors


def verification_errors(read_file=read, hosts: set[str] | None = None) -> list[str]:
    errors: list[str] = []
    for label, relative_path, fragments in REQUIRED_DISCLOSURES:
        errors += require_all(label, read_file(relative_path), fragments)
    errors += host_inventory_errors(read_file(DATA_SAFETY_PATH), code_hosts() if hosts is None else hosts)
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
