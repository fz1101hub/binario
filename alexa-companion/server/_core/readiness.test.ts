import { describe, expect, it, vi } from "vitest";
import { checkReadiness, withinTimeout, type ReadinessProbes } from "./readiness";

const probes = (database: Awaited<ReturnType<ReadinessProbes["database"]>>, railData: Awaited<ReturnType<ReadinessProbes["railData"]>>, production = true): ReadinessProbes => ({
  database: async () => database,
  railData: async () => railData,
  production,
});

describe("readiness", () => {
  it("is ready with a database that answers and the skill's rail data", async () => {
    expect(await checkReadiness(probes("ok", "loaded"))).toEqual({ ready: true, database: "ok", railData: "loaded" });
    expect(await checkReadiness(probes("ok", "notNeeded"))).toEqual({ ready: true, database: "ok", railData: "notNeeded" });
  });

  it("is not ready with a database that does not answer, or the rail data missing from the deploy", async () => {
    expect((await checkReadiness(probes("unreachable", "loaded"))).ready).toBe(false);
    expect((await checkReadiness(probes("ok", "unavailable"))).ready).toBe(false);
  });

  it("needs a configured database in production only", async () => {
    expect((await checkReadiness(probes("notConfigured", "notNeeded", true))).ready).toBe(false);
    expect((await checkReadiness(probes("notConfigured", "notNeeded", false))).ready).toBe(true);
  });

  it("gives up on a database that does not answer in time", async () => {
    vi.useFakeTimers();
    const hanging = withinTimeout(new Promise(() => undefined), 3_000);
    const outcome = expect(hanging).rejects.toThrow("timeout");
    vi.advanceTimersByTime(3_000);
    await outcome;
    vi.useRealTimers();
    await expect(withinTimeout(Promise.resolve("ok"), 3_000)).resolves.toBe("ok");
  });
});
