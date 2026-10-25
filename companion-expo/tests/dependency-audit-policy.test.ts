import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  allowedUnpatchedAdvisories,
  isBlockingHighSeverityAdvisory,
  isTemporarilyAllowedAdvisory,
} from "../scripts/dependency-audit-policy.mjs";

function advisoryFor(exception: (typeof allowedUnpatchedAdvisories)[number], overrides: Record<string, unknown> = {}) {
  return {
    severity: "high",
    github_advisory_id: exception.id,
    module_name: exception.module,
    patched_versions: "<0.0.0",
    findings: [{ version: exception.version, paths: [exception.path] }],
    ...overrides,
  };
}

describe("dependency audit policy", () => {
  it.each(["high", "critical"])("blocks every %s advisory", severity => {
    const advisory = { severity };
    expect(isBlockingHighSeverityAdvisory(advisory)).toBe(true);
  });

  it.each(["low", "moderate", undefined])("does not turn %s advisories into release blockers", severity => {
    const advisory = { severity };
    expect(isBlockingHighSeverityAdvisory(advisory)).toBe(false);
  });

  it.each(allowedUnpatchedAdvisories.map(exception => [exception.id, exception] as const))(
    "allows %s only while no fix exists and only on its single tooling path",
    (_id, exception) => {
      expect(isBlockingHighSeverityAdvisory(advisoryFor(exception))).toBe(false);
      expect(isTemporarilyAllowedAdvisory(advisoryFor(exception))).toBe(true);
      expect(isBlockingHighSeverityAdvisory(advisoryFor(exception, { severity: "critical" }))).toBe(false);
      for (const changed of [
        { patched_versions: ">=9.9.9" },
        { module_name: "other-module" },
        { github_advisory_id: "GHSA-0000-0000-0000" },
        { findings: [{ version: "0.0.1", paths: [exception.path] }] },
        { findings: [{ version: exception.version, paths: [".>react-native>" + exception.module] }] },
        { findings: [{ version: exception.version, paths: [exception.path, ".>other>" + exception.module] }] },
        { findings: [{ version: exception.version, paths: [exception.path] }, { version: exception.version, paths: [exception.path] }] },
        { findings: [] },
      ]) {
        expect(isBlockingHighSeverityAdvisory(advisoryFor(exception, changed))).toBe(true);
      }
    },
  );

  it("keeps the exception list closed", () => {
    expect(allowedUnpatchedAdvisories.map(exception => exception.id).sort()).toEqual([
      "GHSA-86w9-cpqp-85rv",
      "GHSA-vfj7-8cjw-p6xm",
    ]);
  });

  it("pins the patched transitive releases used to clear fixable advisories", () => {
    const here = dirname(fileURLToPath(import.meta.url));
    const workspace = readFileSync(resolve(here, "../pnpm-workspace.yaml"), "utf8");
    for (const pin of ["compression: 1.8.2", "source-map-js: 1.2.2", "tinypool: 2.1.2"]) {
      expect(workspace).toContain(`  ${pin}`);
    }
  });

  it("locks every installed brace-expansion major to both advisory patches", () => {
    const here = dirname(fileURLToPath(import.meta.url));
    const workspace = readFileSync(resolve(here, "../pnpm-workspace.yaml"), "utf8");
    const lock = readFileSync(resolve(here, "../pnpm-lock.yaml"), "utf8");
    for (const [major, version] of [["1", "1.1.21"], ["2", "2.1.7"], ["5", "5.0.12"]]) {
      expect(workspace).toContain(`brace-expansion@^${major}.0.0: ${version}`);
      expect(lock).toContain(`  brace-expansion@${version}:`);
    }
    const packages = lock.split("\npackages:\n")[1]?.split("\nsnapshots:\n")[0] ?? "";
    expect([...packages.matchAll(/^  brace-expansion@([^:]+):/gm)].map(match => match[1]).sort())
      .toEqual(["1.1.21", "2.1.7", "5.0.12"]);
  });
});
