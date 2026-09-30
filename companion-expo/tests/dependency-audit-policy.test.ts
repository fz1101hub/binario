import { describe, expect, it } from "vitest";
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
});
