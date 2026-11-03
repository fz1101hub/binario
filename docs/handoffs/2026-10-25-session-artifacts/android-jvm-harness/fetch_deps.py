#!/usr/bin/env python3
"""
fetch_deps.py -- deterministic, Gradle-free dependency fetcher for the SDK-less Android harness.

  python3 -I fetch_deps.py [--repo <checkout>] [--kt <harness dir>] [--offline] [--no-google]

What it does
  1. Parses <repo>/gradle/verification-metadata.xml (every component Gradle verified + sha256).
  2. Reads the direct dependencies of app/build.gradle.kts and wear/build.gradle.kts
     (versions of BOM-managed entries come from the metadata).
  3. For every direct dependency it tries the exact artifact file listed in the metadata
     from Google Maven (androidx.*, com.google.android.*) and Maven Central, verifying sha256.
     NOTE: in this container maven.google.com only answers "301 -> dl.google.com", and
     dl.google.com is blocked by the network policy, so every Google-Maven-only artifact is
     unavailable.  Such artifacts are replaced by Maven Central substitutes:
        - androidx.compose.*          -> org.jetbrains.compose.*:*-desktop   (JetBrains Compose Multiplatform)
        - androidx.lifecycle.*        -> org.jetbrains.androidx.lifecycle:*-desktop
        - androidx.annotation/collection -> org.jetbrains.compose.{annotation,collection}-internal:*-desktop
        - everything else (core-ktx, activity, work, play-services-wearable, androidx.wear.*,
          concurrent-futures)         -> hand written compile stubs in <kt>/stubs (see README.md).
  4. Walks the Maven POMs of the chosen Central artifacts (compile+runtime scope, highest version
     wins, KMP roots mapped to their -desktop/-jvm jar) so the transitive closure is complete.
  5. Downloads (cached under <kt>/cache, retried with backoff on 429/5xx), verifies sha256
     (metadata first, otherwise the repository's .sha256/.sha1 sidecar, then <kt>/deps-lock.json),
     extracts classes.jar from .aar files, and places jars under <kt>/lib/<group>/<name>-<version>.jar.
  6. Writes cp-app.txt, cp-wear.txt (main compile classpath, one jar per line),
     cp-app-test.txt, cp-wear-test.txt (test-only additions), platform.txt (android-all jar),
     compose-plugin.txt, deps-report.json (what was substituted / stubbed / skipped).
"""
import argparse
import hashlib
import json
import os
import random
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

KT = Path(__file__).resolve().parent
CENTRAL_HOSTS = ["https://repo1.maven.org/maven2/", "https://repo.maven.apache.org/maven2/"]
GOOGLE_HOST = "https://maven.google.com/"

# ---- pinned harness artifacts -------------------------------------------------------------
ANDROID_ALL = ("org.robolectric", "android-all", "16-robolectric-13921718")  # API 36 / Android 16
COMPOSE_PLUGIN = ("org.jetbrains.kotlin", "kotlin-compose-compiler-plugin-embeddable", "2.0.21")
KOTLIN = "2.0.21"
CMP = "1.7.3"            # Compose Multiplatform release built with Kotlin 2.0.21 (compose 1.7 / material3 1.3)
COLLECTION_DESKTOP = "1.6.0-beta02"  # org.jetbrains.compose.collection-internal:collection-desktop (androidx.collection 1.4)
JB_LIFECYCLE = None      # androidx.lifecycle is stubbed (see SUBSTITUTES); JetBrains lifecycle jars are dropped

GOOGLE_GROUP_PREFIXES = ("androidx.", "com.google.android.", "com.android.")

# Google-Maven-only direct dependencies -> Maven Central substitute (None = compile stub in <kt>/stubs)
SUBSTITUTES = {
    ("androidx.compose.ui", "ui"): ("org.jetbrains.compose.ui", "ui-desktop", CMP),
    ("androidx.compose.ui", "ui-tooling-preview"): ("org.jetbrains.compose.ui", "ui-tooling-preview-desktop", CMP),
    ("androidx.compose.ui", "ui-tooling"): None,  # debugImplementation only
    ("androidx.compose.material3", "material3"): ("org.jetbrains.compose.material3", "material3-desktop", CMP),
    ("androidx.compose.material", "material-icons-extended"): ("org.jetbrains.compose.material", "material-icons-extended-desktop", CMP),
    # lifecycle: JetBrains republished the core (lifecycle-runtime/viewmodel-desktop, common-jvm) only at 2.11.0
    # (built with Kotlin 2.2, unreadable by the 2.0.21 compiler) and the 2.8.x core is Google-Maven-only -> stubs.
    ("androidx.lifecycle", "lifecycle-runtime-ktx"): None,
    ("androidx.lifecycle", "lifecycle-runtime-compose"): None,
    ("androidx.lifecycle", "lifecycle-viewmodel-ktx"): None,
    ("androidx.lifecycle", "lifecycle-viewmodel-compose"): None,
    ("androidx.core", "core-ktx"): None,
    ("androidx.activity", "activity-compose"): None,
    ("androidx.work", "work-runtime-ktx"): None,
    ("com.google.android.gms", "play-services-wearable"): None,
    ("androidx.wear.compose", "compose-material3"): None,
    ("androidx.wear.compose", "compose-foundation"): None,
    ("androidx.wear.compose", "compose-ui-tooling"): None,
    ("androidx.wear.tiles", "tiles"): None,
    ("androidx.wear.protolayout", "protolayout"): None,
    ("androidx.wear.watchface", "watchface-complications-data-source-ktx"): None,
    ("androidx.concurrent", "concurrent-futures"): None,
}
# Extra Maven Central roots the stubs need at compile time
COROUTINES = "1.9.0"     # highest kotlinx-coroutines in the metadata (Gradle resolves the test runtime to it)
EXTRA_ROOTS = {
    "app": [("org.jetbrains.kotlin", "kotlin-stdlib", KOTLIN),
            ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", COROUTINES)],
    # every direct Wear dependency is Google-Maven-only; the Compose primitives it uses (foundation, runtime, ui)
    # would come transitively from wear-compose / activity-compose in Gradle, so they are explicit roots here
    "wear": [("org.jetbrains.kotlin", "kotlin-stdlib", KOTLIN),
             ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", COROUTINES),
             ("org.jetbrains.compose.ui", "ui-desktop", CMP),
             ("org.jetbrains.compose.foundation", "foundation-desktop", CMP),
             ("com.google.guava", "listenablefuture", "1.0")],  # ListenableFuture for the tile stubs
}
# test-only artifacts (never on the main compile classpath)
TEST_ONLY = {("junit", "junit"), ("org.hamcrest", "hamcrest-core"), ("org.json", "json"),
             ("org.jetbrains.kotlinx", "kotlinx-coroutines-test")}

# dependency coordinates found in POMs that we never put on the classpath
DROP = {("org.jetbrains.kotlin", "kotlin-stdlib-common"), ("org.jetbrains.kotlin", "kotlin-stdlib-jdk7"),
        ("org.jetbrains.kotlin", "kotlin-stdlib-jdk8"), ("org.jetbrains.kotlinx", "kotlinx-coroutines-bom"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-android"), ("com.google.guava", "guava"),
        ("org.jetbrains.compose.ui", "ui-uikit")}

# KMP root coordinate -> platform jar coordinate (POM walk)
def platform_alias(group, name, version):
    if (group, name) in DROP:
        return None
    # androidx.annotation / androidx.collection are Google-Maven-only; JetBrains' *-internal wrappers are KMP
    # metadata-only at 1.7.x. Annotations are stubbed; collection uses the only JVM wrapper JetBrains published
    # (collection-desktop 1.6.0-beta02 = androidx.collection 1.4, exactly what runtime-desktop 1.7.3 wants).
    if group in ("androidx.annotation", "org.jetbrains.compose.annotation-internal"):
        return None
    if group in ("androidx.collection", "org.jetbrains.compose.collection-internal"):
        return ("org.jetbrains.compose.collection-internal", "collection-desktop", COLLECTION_DESKTOP)
    if group in ("androidx.lifecycle", "org.jetbrains.androidx.lifecycle", "org.jetbrains.androidx.savedstate",
                 "org.jetbrains.androidx.core"):
        return None  # lifecycle/savedstate are provided by <kt>/stubs/common
    if group.startswith(GOOGLE_GROUP_PREFIXES):
        return None  # Google Maven only: not reachable, covered by stubs if needed
    if group == "org.jetbrains.skiko" and name == "skiko":
        return (group, "skiko-awt", version)
    if group == "org.jetbrains.kotlinx" and name in ("kotlinx-coroutines-core", "atomicfu", "kotlinx-datetime",
                                                   "kotlinx-serialization-core", "kotlinx-coroutines-test"):
        return (group, name + "-jvm", version)
    if group.startswith(("org.jetbrains.compose", "org.jetbrains.androidx")):
        if name.endswith(("-desktop", "-jvm")):
            return (group, name, version)
        if name == "lifecycle-common":
            return (group, "lifecycle-common-jvm", version)
        return (group, name + "-desktop", version)
    return (group, name, version)


# ---- helpers ------------------------------------------------------------------------------
def vkey(v):
    parts = re.split(r"[.\-_]", v)
    out = []
    for p in parts:
        if p.isdigit():
            out.append((1, int(p), ""))
        else:
            out.append((0, 0, p))
    return out


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sha1_of(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class Fetcher:
    """curl based downloader: cache, rate-limit backoff, redirect detection (no -L on purpose)."""

    def __init__(self, cache, log, offline=False, min_interval=0.8):
        self.cache = Path(cache)
        self.log = open(log, "a")
        self.offline = offline
        self.min_interval = min_interval
        self.last = 0.0
        self.google_blocked = None  # None = unknown, True = redirects to blocked dl.google.com
        self.central_i = 0

    def _log(self, msg):
        self.log.write(time.strftime("%H:%M:%S ") + msg + "\n")
        self.log.flush()

    def _curl(self, url, out):
        wait = self.min_interval - (time.time() - self.last)
        if wait > 0:
            time.sleep(wait)
        self.last = time.time()
        cmd = ["curl", "-sS", "--max-time", "600", "-o", str(out), "-w", "%{http_code} %{redirect_url}", url]
        p = subprocess.run(cmd, capture_output=True, text=True)
        code, _, redirect = (p.stdout.strip() + " ").partition(" ")
        self._log(f"GET {url} -> {code} {redirect.strip()} {p.stderr.strip()}")
        return (code if code else "000"), redirect.strip()

    def get(self, rel, dest, hosts):
        """Download <host>/<rel> into dest. Returns (ok, status)."""
        dest = Path(dest)
        if dest.exists() and dest.stat().st_size > 0:
            return True, "cached"
        if self.offline:
            return False, "offline"
        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp = dest.with_suffix(dest.suffix + ".part")
        hard_404 = 0
        for attempt in range(9):
            host = hosts[attempt % len(hosts)]
            if host == GOOGLE_HOST and self.google_blocked:
                if len(hosts) == 1:
                    return False, "google-blocked"
                continue
            code, redirect = self._curl(host + rel, tmp)
            if code == "200":
                tmp.replace(dest)
                return True, "downloaded"
            if code in ("301", "302", "307", "308"):
                if host == GOOGLE_HOST and "dl.google.com" in redirect:
                    if self.google_blocked is None:
                        probe_code, _ = self._curl(redirect, tmp)
                        self.google_blocked = probe_code in ("000", "403")
                        self._log(f"Google Maven redirects to dl.google.com, reachable={not self.google_blocked}")
                    if not self.google_blocked:
                        code2, _ = self._curl(redirect, tmp)
                        if code2 == "200":
                            tmp.replace(dest)
                            return True, "downloaded"
                    if len(hosts) == 1:
                        return False, "google-blocked"
                    continue
                code2, _ = self._curl(redirect, tmp)
                if code2 == "200":
                    tmp.replace(dest)
                    return True, "downloaded"
            if code == "404":
                # A real Maven Central 404 is an nginx HTML page ("404 Not Found", ~550 bytes); while rate
                # limiting, Central answers a 1-byte text/plain 404 instead -> treat that one as transient.
                body = tmp.read_bytes() if tmp.exists() else b""
                if b"404 Not Found" in body or len(body) > 64:
                    return False, "404"
                hard_404 += 1
                if hard_404 >= 6:
                    return False, "404-rate-limited"
                time.sleep(min(60, 5 * (2 ** hard_404)) + random.uniform(0, 3))
                continue
            if code == "429" or code.startswith("5") or code == "000":
                time.sleep(min(90, 10 * (2 ** attempt)) + random.uniform(0, 3))
                continue
            return False, code
        return False, "retries-exhausted"

    def central(self):
        return CENTRAL_HOSTS[self.central_i:] + CENTRAL_HOSTS[:self.central_i]


# ---- metadata + gradle parsing -------------------------------------------------------------
def parse_metadata(path):
    ns = {"v": "https://schema.gradle.org/dependency-verification"}
    root = ET.parse(path).getroot()
    comps = {}
    for c in root.iterfind(".//v:component", ns):
        key = (c.get("group"), c.get("name"), c.get("version"))
        arts = {}
        for a in c.iterfind("v:artifact", ns):
            sha = a.find("v:sha256", ns)
            arts[a.get("name")] = sha.get("value") if sha is not None else None
        comps[key] = arts
    return comps


def parse_gradle_deps(build_file):
    text = Path(build_file).read_text()
    text = re.sub(r"//.*", "", text)
    deps = []
    for m in re.finditer(r'\b(implementation|testImplementation|debugImplementation|androidTestImplementation)\s*\(\s*(platform\s*\(\s*)?"([^"]+)"', text):
        conf, platform, coord = m.group(1), m.group(2), m.group(3)
        if platform:
            continue
        parts = coord.split(":")
        deps.append((conf, parts[0], parts[1], parts[2] if len(parts) > 2 else None))
    return deps


def version_from_metadata(comps, group, name):
    versions = sorted({v for (g, n, v) in comps if g == group and n == name}, key=vkey)
    return versions[-1] if versions else None


# ---- POM walking -------------------------------------------------------------------------------
class Resolver:
    def __init__(self, fetcher, comps, report):
        self.f = fetcher
        self.comps = comps
        self.report = report
        self.selected = {}   # (g, a) -> version
        self.tried = set()   # (g, a, v) already attempted (success or failure)

    def pom_path(self, g, a, v):
        return self.f.cache / "central" / g.replace(".", "/") / a / v / f"{a}-{v}.pom"

    def fetch_pom(self, g, a, v):
        rel = f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.pom"
        dest = self.pom_path(g, a, v)
        ok, status = self.f.get(rel, dest, self.f.central())
        return dest if ok else None

    def parse_pom(self, path):
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        root = ET.parse(path).getroot()
        tag = lambda e: e.tag.split("}")[-1]
        def text(e, name):
            x = e.find("m:" + name, ns)
            if x is None:
                x = e.find(name)
            return x.text.strip() if x is not None and x.text else None
        props = {}
        p = root.find("m:properties", ns)
        if p is not None:
            for child in p:
                props[tag(child)] = (child.text or "").strip()
        gid = text(root, "groupId") or text(root.find("m:parent", ns), "groupId")
        ver = text(root, "version") or text(root.find("m:parent", ns), "version")
        props.setdefault("project.version", ver)
        props.setdefault("project.groupId", gid)
        packaging = text(root, "packaging") or "jar"
        managed = {}
        dm = root.find("m:dependencyManagement/m:dependencies", ns)
        if dm is not None:
            for d in dm.findall("m:dependency", ns):
                managed[(text(d, "groupId"), text(d, "artifactId"))] = text(d, "version")
        deps = []
        ds = root.find("m:dependencies", ns)
        if ds is not None:
            for d in ds.findall("m:dependency", ns):
                scope = text(d, "scope") or "compile"
                if scope not in ("compile", "runtime"):
                    continue
                if (text(d, "optional") or "false") == "true":
                    continue
                if (text(d, "type") or "jar") == "pom":
                    continue
                g, a, v = text(d, "groupId"), text(d, "artifactId"), text(d, "version")
                if v is None:
                    v = managed.get((g, a))
                if v is None:
                    continue
                for k, val in props.items():
                    if val is not None:
                        g = g.replace("${%s}" % k, val)
                        v = v.replace("${%s}" % k, val)
                v = v.strip("[]()").split(",")[0].strip()
                if not v or "${" in v:
                    continue
                deps.append((g, a, v))
        return packaging, deps

    def add(self, g, a, v, from_root=False):
        """Add coordinate (already platform-resolved) with highest-version-wins; walk its POM."""
        cur = self.selected.get((g, a))
        if cur is not None and vkey(cur) >= vkey(v):
            return
        if (g, a, v) in self.tried:
            return
        self.tried.add((g, a, v))
        self.selected[(g, a)] = v
        pom = self.fetch_pom(g, a, v)
        if pom is None:
            # maybe the -desktop guess was wrong, try -jvm, then the bare name
            for alt in ([a[:-8] + "-jvm", a[:-8]] if a.endswith("-desktop") else []):
                pom = self.fetch_pom(g, alt, v)
                if pom is not None:
                    del self.selected[(g, a)]
                    self.selected[(g, alt)] = v
                    a = alt
                    break
        if pom is None:
            self.report["unresolved"].append(f"{g}:{a}:{v}")
            del self.selected[(g, a)]
            return
        packaging, deps = self.parse_pom(pom)
        if packaging == "pom":
            self.report["pom_only"].append(f"{g}:{a}:{v}")
            del self.selected[(g, a)]
        for (dg, da, dv) in deps:
            alias = platform_alias(dg, da, dv)
            if alias is None:
                if dg.startswith(GOOGLE_GROUP_PREFIXES):
                    self.report["dropped_google_transitives"].add(f"{dg}:{da}:{dv}")
                continue
            self.add(*alias)


# ---- main ---------------------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default="/home/user/binario")
    ap.add_argument("--kt", default=str(KT))
    ap.add_argument("--offline", action="store_true", help="only use the cache")
    ap.add_argument("--no-google", action="store_true", help="do not even try maven.google.com")
    args = ap.parse_args()

    kt = Path(args.kt)
    repo = Path(args.repo)
    cache = kt / "cache"
    lib = kt / "lib"
    cache.mkdir(parents=True, exist_ok=True)
    lib.mkdir(parents=True, exist_ok=True)
    lock_path = kt / "deps-lock.json"
    lock = json.loads(lock_path.read_text()) if lock_path.exists() else {}
    report = {"substituted": {}, "stubbed": [], "skipped": [], "unresolved": [], "pom_only": [],
              "dropped_google_transitives": set(), "google_maven": None, "downloaded": [], "verified": {}}

    fetcher = Fetcher(cache, kt / "fetch.log", offline=args.offline)
    if args.no_google:
        fetcher.google_blocked = True
    comps = parse_metadata(repo / "gradle" / "verification-metadata.xml")
    print(f"metadata: {len(comps)} components")

    def binary_artifact(g, a, v):
        """exact binary file name listed in the metadata for this component, if any"""
        arts = comps.get((g, a, v), {})
        for ext in (".jar", ".aar"):
            for name, sha in arts.items():
                if name == f"{a}-{v}{ext}":
                    return name, sha
        return None, None

    def download_binary(g, a, v, sha_expected=None, hosts=None):
        """Download g:a:v binary (jar/aar) into cache, verify, install into lib. Returns lib jar path or None."""
        name, sha_meta = binary_artifact(g, a, v)
        candidates = [name] if name else [f"{a}-{v}.jar", f"{a}-{v}.aar"]
        hosts = hosts or (([GOOGLE_HOST] if g.startswith(GOOGLE_GROUP_PREFIXES) else []) + fetcher.central())
        for fname in candidates:
            rel = f"{g.replace('.', '/')}/{a}/{v}/{fname}"
            dest = cache / "artifacts" / g.replace(".", "/") / a / v / fname
            ok, status = fetcher.get(rel, dest, hosts)
            if not ok:
                if status == "google-blocked":
                    report["google_maven"] = "maven.google.com -> 301 dl.google.com (blocked by network policy)"
                continue
            # --- verification: metadata sha256 > repository sidecar > lock file
            actual = sha256_of(dest)
            expected = sha_meta or sha_expected or lock.get(f"{g}:{a}:{v}/{fname}")
            source = "metadata" if sha_meta else ("lock" if expected else None)
            if expected is None and not args.offline:
                for ext, fn in ((".sha256", sha256_of), (".sha1", sha1_of)):
                    side = dest.with_name(fname + ext)
                    ok2, _ = fetcher.get(rel + ext, side, [h for h in hosts if h != GOOGLE_HOST] or hosts)
                    if ok2:
                        want = side.read_text().split()[0].strip().lower()
                        if fn(dest) != want:
                            dest.unlink()
                            sys.exit(f"CHECKSUM MISMATCH ({ext}) for {g}:{a}:{v}/{fname}")
                        expected, source = actual, f"sidecar{ext}"
                        break
            if expected is not None and actual != expected:
                dest.unlink()
                sys.exit(f"CHECKSUM MISMATCH for {g}:{a}:{v}/{fname}: expected {expected} got {actual} ({source})")
            lock[f"{g}:{a}:{v}/{fname}"] = actual
            report["verified"][f"{g}:{a}:{v}/{fname}"] = source or "unverified"
            if status == "downloaded":
                report["downloaded"].append(f"{g}:{a}:{v}/{fname}")
            # --- install
            out = lib / g / f"{a}-{v}.jar"
            out.parent.mkdir(parents=True, exist_ok=True)
            if not out.exists():
                if fname.endswith(".aar"):
                    with zipfile.ZipFile(dest) as z:
                        with z.open("classes.jar") as src, open(out, "wb") as dst:
                            shutil.copyfileobj(src, dst)
                else:
                    shutil.copyfile(dest, out)
            return out
        return None

    # ---- platform + compiler plugin ----
    g, a, v = ANDROID_ALL
    platform_jar = download_binary(g, a, v)
    if platform_jar is None:
        sys.exit("cannot fetch the android-all platform jar")
    (kt / "platform.txt").write_text(str(platform_jar) + "\n")
    g, a, v = COMPOSE_PLUGIN
    plugin_jar = download_binary(g, a, v)
    if plugin_jar is None:
        sys.exit("cannot fetch the compose compiler plugin")
    (kt / "compose-plugin.txt").write_text(str(plugin_jar) + "\n")

    # ---- per module ----
    for module in ("app", "wear"):
        deps = parse_gradle_deps(repo / module / "build.gradle.kts")
        resolver = Resolver(fetcher, comps, report)
        test_resolver = Resolver(fetcher, comps, report)
        for (conf, dg, da, dv) in deps:
            if conf in ("debugImplementation", "androidTestImplementation"):
                report["skipped"].append(f"{module}: {conf} {dg}:{da}:{dv or ''}")
                continue
            dv = dv or version_from_metadata(comps, dg, da)
            if dv is None:
                report["unresolved"].append(f"{module}: {dg}:{da} (no version)")
                continue
            target = resolver if conf == "implementation" else test_resolver
            if dg.startswith(GOOGLE_GROUP_PREFIXES):
                # try the exact artifact first (works only if Google Maven becomes reachable)
                real = None
                if not fetcher.google_blocked:
                    name, sha = binary_artifact(dg, da, dv)
                    if name is None:  # KMP root: the binary is the -android aar
                        name, sha = binary_artifact(dg, da + "-android", dv)
                        if name:
                            real = download_binary(dg, da + "-android", dv, sha, [GOOGLE_HOST])
                    else:
                        real = download_binary(dg, da, dv, sha, [GOOGLE_HOST])
                if real is not None:
                    target.selected[(dg, da)] = dv
                    report["substituted"][f"{module}: {dg}:{da}:{dv}"] = "real artifact from Google Maven"
                    continue
                sub = SUBSTITUTES.get((dg, da), "missing")
                if sub == "missing":
                    report["unresolved"].append(f"{module}: {dg}:{da}:{dv} (no substitute rule)")
                elif sub is None:
                    report["stubbed"].append(f"{module}: {dg}:{da}:{dv}")
                else:
                    report["substituted"][f"{module}: {dg}:{da}:{dv}"] = ":".join(sub)
                    target.add(*sub)
            else:
                alias = platform_alias(dg, da, dv)
                if alias:
                    target.add(*alias)
        for root in EXTRA_ROOTS[module]:
            resolver.add(*root)

        # test classpath must not duplicate main jars; test-only coordinates stay off the main one
        main_sel = {k: v for k, v in resolver.selected.items() if k not in TEST_ONLY and (k[0], k[1].replace("-jvm", "")) not in TEST_ONLY}
        # highest version wins across main + test (Gradle semantics), shared libs stay on the main classpath
        for k, v in test_resolver.selected.items():
            if k in main_sel and vkey(v) > vkey(main_sel[k]):
                main_sel[k] = v
        test_sel = {k: v for k, v in test_resolver.selected.items() if k not in main_sel}

        def install(selection):
            jars = []
            for (g, a), v in sorted(selection.items()):
                jar = download_binary(g, a, v)
                if jar is None:
                    report["unresolved"].append(f"{module}: {g}:{a}:{v} (download failed)")
                else:
                    jars.append(str(jar))
            return jars

        main_jars = install(main_sel)
        test_jars = install(test_sel)
        # kotlin-stdlib first, org.json first on the test side (it must shadow android-all's org.json)
        main_jars.sort(key=lambda p: (0 if "kotlin-stdlib" in p else 1, p))
        test_jars.sort(key=lambda p: (0 if "/org.json/" in p else 1, p))
        (kt / f"cp-{module}.txt").write_text("\n".join(main_jars) + "\n")
        (kt / f"cp-{module}-test.txt").write_text("\n".join(test_jars) + "\n")
        print(f"{module}: {len(main_jars)} main jars, {len(test_jars)} test-only jars")

    lock_path.write_text(json.dumps(dict(sorted(lock.items())), indent=1) + "\n")
    report["dropped_google_transitives"] = sorted(report["dropped_google_transitives"])
    if report["google_maven"] is None:
        report["google_maven"] = ("reachable" if fetcher.google_blocked is False
                                  else "not attempted (every artifact was cached, or --offline/--no-google)")
    report["android_all"] = ":".join(ANDROID_ALL)
    report["compose_compiler_plugin"] = ":".join(COMPOSE_PLUGIN)
    report["compose_multiplatform"] = CMP
    report["lifecycle"] = "stubbed (androidx.lifecycle core is Google-Maven-only)"
    (kt / "deps-report.json").write_text(json.dumps(report, indent=1, sort_keys=True) + "\n")
    print("google maven:", report["google_maven"])
    print("substituted:", len(report["substituted"]), "stubbed:", len(report["stubbed"]),
          "unresolved:", len(report["unresolved"]), "downloaded now:", len(report["downloaded"]))
    for u in report["unresolved"]:
        print("  UNRESOLVED", u)
    return 1 if report["unresolved"] else 0


if __name__ == "__main__":
    sys.exit(main())
