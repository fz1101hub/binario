import { spawnSync } from "node:child_process";
import {
  isBlockingHighSeverityAdvisory,
  isTemporarilyAllowedAdvisory,
} from "./dependency-audit-policy.mjs";

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
const blocking = advisories.filter(isBlockingHighSeverityAdvisory);

if (blocking.length > 0) {
  for (const advisory of blocking) {
    console.error(
      `[${advisory.severity}] ${advisory.github_advisory_id} ${advisory.module_name}: ${advisory.title}`,
    );
  }
  process.exitCode = 1;
} else {
  const allowed = advisories.filter(isTemporarilyAllowedAdvisory);
  for (const advisory of allowed) {
    console.warn(
      `[allowlist temporanea] ${advisory.github_advisory_id} ${advisory.module_name}: nessuna versione corretta disponibile; Expo/Metro elabora soltanto asset versionati e generati dal progetto`,
    );
  }
  console.log("verify-dependency-audit: nessun advisory high/critical correggibile.");
}
