import { describe, expect, it } from "vitest";
import { createWindowCounter } from "./rateLimit";

describe("contatore a finestra per identità verificate", () => {
  it("conta per chiave e riparte alla scadenza della finestra", () => {
    let now = 0;
    const counter = createWindowCounter({ limit: 2, windowMs: 1_000, now: () => now });
    expect([counter.hit("a"), counter.hit("a"), counter.hit("a")]).toEqual([true, true, false]);
    expect(counter.hit("b")).toBe(true);
    expect(counter.retryAfterSeconds("a")).toBe(1);
    now = 1_000;
    expect(counter.hit("a")).toBe(true);
  });

  it("limita la memoria: elimina le finestre scadute e non sfratta quelle attive", () => {
    let now = 0;
    const counter = createWindowCounter({ limit: 1, windowMs: 1_000, maxKeys: 2, now: () => now });
    counter.hit("a");
    counter.hit("b");
    // Full of live windows: a new key passes uncounted, "a" keeps its exhausted window.
    expect(counter.hit("c")).toBe(true);
    expect(counter.hit("c")).toBe(true);
    expect(counter.hit("a")).toBe(false);
    now = 1_000;
    // Expired windows are pruned to make room.
    expect(counter.hit("c")).toBe(true);
    expect(counter.hit("c")).toBe(false);
  });
});
