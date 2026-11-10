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
# Manifests of the regional-railway and city-network packs (every build) and of the personal-build packs: read when
# present, since a build may ship none of them.
OPTIONAL_HOST_SOURCE_FILES = (
    "app/src/main/assets/gtfs-transit/manifest.json",
    "app/src/personal/assets/gtfs-personal/manifest.json",
)
# XML parser feature and namespace URIs are not network endpoints.
NON_NETWORK_URI_PREFIXES = (
    "http://apache.org/xml/",
    "http://xml.org/sax/",
    "http://javax.xml.XMLConstants/",
)
URL_PATTERN = re.compile(r"(https?://([A-Za-z0-9.-]+))[^\s\"'<>)]*")
# Hosts the code builds without a literal URL (audit ci-docs-8): Kotlin's URL("https", "host", file), and a host
# assigned to a name ending in host (Kotlin `const val VIAGGIATRENO_HOST = "…"`, Swift `static let …Host = "…"`,
# `components.host = "…"`).  A comparison (`host == "…"`) is not an assignment.
DOMAIN = r"([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)"
CONSTRUCTED_HOST_PATTERNS = (
    re.compile(r"\bURL\(\s*\"https?\"\s*,\s*\"" + DOMAIN + r"\""),
    re.compile(r"\b\w*(?:host|Host|HOST)\s*(?::\s*String\??)?\s*=(?!=)\s*\"" + DOMAIN + r"\""),
)
# The iPhone and Apple Watch sources: every host they name must be disclosed, backticked, in PRIVACY.md.
IOS_HOST_SOURCE_GLOBS = ("ios/BinarioKit/Sources/**/*.swift", "ios/BinarioApp/**/*.swift", "apple-watch/**/*.swift")
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
            "## App iPhone e Apple Watch",
            "### Condivisione con Apple Watch",
            "### English summary (iPhone and Apple Watch)",
            "`it.binario.ios.saved-routes.v1`",
            "`it.binario.ios.watch-sharing-enabled.v1`",
            "`group.it.binario.watch`",
            "`binario_snapshot_clear`",
            "Data Not Collected",
            "Posizione effimera",
            "Feed ferroviari esterni, European Sleeper/JBB e nomi di stazione",
            "Backup, conservazione e cancellazione",
            "Revoca, cancellazione e conservazione",
            "| Interrompere Wear | Disattivare la condivisione nell'app Android: il telefono richiede la cancellazione del Data Item",
            "riconoscitore vocale di sistema",
            "titolare del trattamento",
            "URL pubblico",
            "European Sleeper/JBB",
            "`jbb.ghsq.de`",
            "community best-effort",
            "tier primario fallisce",
            "metadati di rete della richiesta sono visibili",
            "[docs/store/PLAY_DATA_SAFETY.md](docs/store/PLAY_DATA_SAFETY.md)",
            "### Avvisi, tabelloni svizzeri e meteo a destinazione",
            "coordinate della stazione di arrivo arrotondate a due decimali",
            "Anche le richieste di avvisi a ViaggiaTreno passano in HTTP non cifrato",
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
            "tranne `www.viaggiatreno.it` (senza sottodomini)",
            "valore predefinito anche in release",
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
            "Open-Meteo le coordinate arrotondate della stazione di arrivo",
            "in HTTP non cifrato come la sua API pubblica",
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


def hosts_in(text: str) -> set[str]:
    """The network hosts ``text`` names: in literal URLs (XML namespace URIs aside) or built by the code."""
    hosts = {
        match.group(2).lower() for match in URL_PATTERN.finditer(text)
        if not match.group(0).startswith(NON_NETWORK_URI_PREFIXES)
    }
    for pattern in CONSTRUCTED_HOST_PATTERNS:
        hosts.update(match.group(1).lower() for match in pattern.finditer(text))
    return hosts


def code_hosts(root: Path = ROOT) -> set[str]:
    """Every network host referenced by the Android sources and asset manifests."""
    paths = [path for pattern in HOST_SOURCE_GLOBS for path in root.glob(pattern)]
    paths += [root / relative for relative in HOST_SOURCE_FILES]
    paths += [root / relative for relative in OPTIONAL_HOST_SOURCE_FILES if (root / relative).is_file()]
    hosts: set[str] = set()
    for path in paths:
        hosts |= hosts_in(path.read_text(encoding="utf-8"))
    return hosts


def ios_code_hosts(root: Path = ROOT) -> set[str]:
    """Every network host named by the iPhone and Apple Watch sources."""
    hosts: set[str] = set()
    for pattern in IOS_HOST_SOURCE_GLOBS:
        for path in root.glob(pattern):
            hosts |= hosts_in(path.read_text(encoding="utf-8"))
    return hosts


def ios_host_errors(privacy: str, hosts: set[str]) -> list[str]:
    """An iPhone host absent from PRIVACY.md (as `host`).  The whole policy is read: the iPhone section is the
    natural place, but a host the Android section already discloses is not a new disclosure."""
    return [f"PRIVACY.md: iPhone host `{host}` not disclosed" for host in sorted(hosts) if f"`{host}`" not in privacy]


def host_inventory_errors(data_safety: str, hosts: set[str]) -> list[str]:
    documented = set(HOST_ROW.findall(data_safety))
    errors = [f"Play Data safety host table: missing `{host}`" for host in sorted(hosts - documented)]
    errors += [f"Play Data safety host table: stale `{host}` no longer in app code" for host in sorted(documented - hosts)]
    return errors


def verification_errors(read_file=read, hosts: set[str] | None = None, ios_hosts: set[str] | None = None) -> list[str]:
    errors: list[str] = []
    for label, relative_path, fragments in REQUIRED_DISCLOSURES:
        errors += require_all(label, read_file(relative_path), fragments)
    errors += host_inventory_errors(read_file(DATA_SAFETY_PATH), code_hosts() if hosts is None else hosts)
    errors += ios_host_errors(read_file("PRIVACY.md"), ios_code_hosts() if ios_hosts is None else ios_hosts)
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
