import { describe, expect, it } from "vitest";
import {
  isBlockingHighSeverityAdvisory,
  isTemporarilyAllowedAdvisory,
} from "../scripts/dependency-audit-policy.mjs";

const allowed = {
  severity: "high",
  github_advisory_id: "GHSA-w3rx-r6r6-pgpr",
  module_name: "image-size",
  patched_versions: "<0.0.0",
  findings: [{
    version: "1.2.1",
    paths: [".>expo>@expo/metro>metro>image-size"],
  }],
};

describe("dependency audit policy", () => {
  it.each([
    "GHSA-w3rx-r6r6-pgpr",
    "GHSA-5p2g-fcmc-qvqq",
  ])("allows only the exact unpatched Expo/Metro finding %s", githubAdvisoryId => {
    const advisory = { ...allowed, github_advisory_id: githubAdvisoryId };
    expect(isBlockingHighSeverityAdvisory(advisory)).toBe(false);
    expect(isTemporarilyAllowedAdvisory(advisory)).toBe(true);
  });

  it.each([
    ["advisory", { ...allowed, github_advisory_id: "GHSA-new-advisory" }],
    ["module", { ...allowed, module_name: "another-module" }],
    ["patch availability", { ...allowed, patched_versions: ">=1.2.2" }],
    ["version", { ...allowed, findings: [{ ...allowed.findings[0], version: "1.2.2" }] }],
    ["path", { ...allowed, findings: [{ ...allowed.findings[0], paths: [".>image-size"] }] }],
    ["additional path", { ...allowed, findings: [{ ...allowed.findings[0], paths: [...allowed.findings[0].paths, ".>other>image-size"] }] }],
    ["additional finding", { ...allowed, findings: [...allowed.findings, { version: "1.2.1", paths: allowed.findings[0].paths }] }],
    ["missing findings", { ...allowed, findings: [] }],
  ])("blocks a high advisory when %s changes", (_name, advisory) => {
    expect(isBlockingHighSeverityAdvisory(advisory)).toBe(true);
    expect(isTemporarilyAllowedAdvisory(advisory)).toBe(false);
  });

  it("does not turn low or moderate advisories into release blockers", () => {
    expect(isBlockingHighSeverityAdvisory({ ...allowed, severity: "moderate" })).toBe(false);
    expect(isTemporarilyAllowedAdvisory({ ...allowed, severity: "moderate" })).toBe(false);
  });
});
