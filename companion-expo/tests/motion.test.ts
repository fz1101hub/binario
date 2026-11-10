import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import { isMotionReduced, screenAnimation } from "@/lib/motion";

describe("Riduci movimento", () => {
  it("toglie le transizioni quando lo chiede l’app o il sistema", () => {
    expect(screenAnimation(isMotionReduced(true, false))).toBe("none");
    expect(screenAnimation(isMotionReduced(false, true))).toBe("none");
    expect(screenAnimation(isMotionReduced(false, false))).toBe("default");
  });

  it("è applicato alle transizioni dello stack radice", () => {
    const layout = readFileSync(path.resolve(import.meta.dirname, "..", "app", "_layout.tsx"), "utf8");
    expect(layout).toContain("animation: screenAnimation(motionReduced)");
    const theme = readFileSync(path.resolve(import.meta.dirname, "..", "lib", "theme.tsx"), "utf8");
    expect(theme).toContain('AccessibilityInfo.addEventListener("reduceMotionChanged"');
  });
});
