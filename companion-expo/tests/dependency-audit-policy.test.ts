import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { isBlockingHighSeverityAdvisory } from "../scripts/dependency-audit-policy.mjs";

describe("dependency audit policy", () => {
  it.each(["high", "critical"])("blocks every %s advisory", severity => {
    const advisory = { severity };
    expect(isBlockingHighSeverityAdvisory(advisory)).toBe(true);
  });

  it.each(["low", "moderate", undefined])("does not turn %s advisories into release blockers", severity => {
    const advisory = { severity };
    expect(isBlockingHighSeverityAdvisory(advisory)).toBe(false);
  });

  it("locks every installed brace-expansion major to both advisory patches", () => {
    const here = dirname(fileURLToPath(import.meta.url));
    const workspace = readFileSync(resolve(here, "../pnpm-workspace.yaml"), "utf8");
    const lock = readFileSync(resolve(here, "../pnpm-lock.yaml"), "utf8");
    for (const [major, version] of [["1", "1.1.20"], ["2", "2.1.6"], ["5", "5.0.11"]]) {
      expect(workspace).toContain(`brace-expansion@^${major}.0.0: ${version}`);
      expect(lock).toContain(`  brace-expansion@${version}:`);
    }
    const packages = lock.split("\npackages:\n")[1]?.split("\nsnapshots:\n")[0] ?? "";
    expect([...packages.matchAll(/^  brace-expansion@([^:]+):/gm)].map(match => match[1]).sort())
      .toEqual(["1.1.20", "2.1.6", "5.0.11"]);
  });
});
