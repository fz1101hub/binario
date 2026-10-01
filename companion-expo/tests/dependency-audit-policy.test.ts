import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
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
    const workspace = readFileSync(new URL("../pnpm-workspace.yaml", import.meta.url), "utf8");
    const lock = readFileSync(new URL("../pnpm-lock.yaml", import.meta.url), "utf8");
    for (const [major, version] of [["1", "1.1.20"], ["2", "2.1.6"], ["5", "5.0.11"]]) {
      expect(workspace).toContain(`brace-expansion@^${major}.0.0: ${version}`);
      expect(lock).toContain(`  brace-expansion@${version}:`);
    }
    expect([...lock.matchAll(/^  brace-expansion@([^:]+):/gm)].map(match => match[1]).sort())
      .toEqual(["1.1.20", "2.1.6", "5.0.11"]);
  });
});
