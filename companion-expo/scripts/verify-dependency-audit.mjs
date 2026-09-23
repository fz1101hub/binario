import { spawnSync } from "node:child_process";

const allowedUnpatchedAdvisories = new Map([
  ["GHSA-w3rx-r6r6-pgpr", "image-size"],
  ["GHSA-5p2g-fcmc-qvqq", "image-size"],
]);
const allowedImageSizeVersion = "1.2.1";
const allowedImageSizePath = ".>expo>@expo/metro>metro>image-size";

const audit = spawnSync("pnpm", ["audit", "--json"], {
  cwd: process.cwd(),
  encoding: "utf8",
  maxBuffer: 16 * 1024 * 1024,
});

if (!audit.stdout.trim()) {
  process.stderr.write(audit.stderr);
  throw new Error("pnpm audit non ha restituito un rapporto JSON");
}

const report = JSON.parse(audit.stdout);
const advisories = Object.values(report.advisories ?? {});
const blocking = advisories.filter(advisory => {
  if (advisory.severity !== "high" && advisory.severity !== "critical") return false;
  const allowedModule = allowedUnpatchedAdvisories.get(advisory.github_advisory_id);
  const findingsAreScoped = advisory.findings?.every(finding =>
    finding.version === allowedImageSizeVersion &&
    finding.paths?.every(path => path === allowedImageSizePath),
  );
  return allowedModule !== advisory.module_name ||
    advisory.patched_versions !== "<0.0.0" ||
    !findingsAreScoped;
});

if (blocking.length > 0) {
  for (const advisory of blocking) {
    console.error(
      `[${advisory.severity}] ${advisory.github_advisory_id} ${advisory.module_name}: ${advisory.title}`,
    );
  }
  process.exitCode = 1;
} else {
  const allowed = advisories.filter(advisory =>
    allowedUnpatchedAdvisories.has(advisory.github_advisory_id),
  );
  for (const advisory of allowed) {
    console.warn(
      `[allowlist temporanea] ${advisory.github_advisory_id} ${advisory.module_name}: nessuna versione corretta disponibile; Expo/Metro elabora soltanto asset versionati e generati dal progetto`,
    );
  }
  console.log("verify-dependency-audit: nessun advisory high/critical correggibile.");
}
