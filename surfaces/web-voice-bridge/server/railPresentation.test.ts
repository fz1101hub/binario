import { describe, expect, it } from "vitest";
import { createPersonalRouteName, getRailDataStatus, swapEndpoints } from "../shared/railPresentation";

describe("rail presentation safeguards", () => {
  it("does not represent a missing provider as live data", () => {
    const status = getRailDataStatus({ hasRealtimeSource: false, hasScheduledSource: false });

    expect(status).toMatchObject({
      tone: "unavailable",
      label: "Dati realtime non disponibili",
    });
    expect(status.detail).not.toMatch(/live verificati/i);
  });

  it("keeps scheduled and live status semantically distinct", () => {
    expect(getRailDataStatus({ hasRealtimeSource: false, hasScheduledSource: true }).tone).toBe("scheduled");
    expect(getRailDataStatus({ hasRealtimeSource: true, hasScheduledSource: true }).tone).toBe("live");
  });

  it("builds a usable saved-route name and swaps only the endpoints", () => {
    expect(createPersonalRouteName(" Roma Termini ", " Tivoli ", "")).toBe("Roma Termini → Tivoli");
    expect(createPersonalRouteName("Roma Termini", "Tivoli", "Casa — lavoro")).toBe("Casa — lavoro");
    expect(swapEndpoints("Roma Termini", "Tivoli")).toEqual({ origin: "Tivoli", destination: "Roma Termini" });
  });
});
