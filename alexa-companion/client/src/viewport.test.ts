import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

describe("dashboard viewport", () => {
  it("lets the user zoom (WCAG 1.4.4): no maximum-scale or user-scalable=no", () => {
    const html = readFileSync(path.resolve(import.meta.dirname, "..", "index.html"), "utf8");
    const viewport = /<meta\s+name="viewport"\s+content="([^"]*)"/.exec(html)?.[1];
    expect(viewport).toBe("width=device-width, initial-scale=1.0");
    expect(viewport).not.toMatch(/maximum-scale|user-scalable/);
  });
});
