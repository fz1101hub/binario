import { describe, expect, it } from "vitest";
import { clearBrowserJourneyData, inspectBrowserJourneyData } from "../shared/localBrowserJourneyData";
import { LOCAL_GUEST_ROUTES_STORAGE_KEY } from "../shared/localGuestRoutes";
import { LOCAL_JOURNEY_ITINERARIES_STORAGE_KEY } from "../shared/localJourneyItineraries";
import { JOURNEY_ACCESSIBILITY_PREFERENCES_STORAGE_KEY } from "../shared/localJourneyToolkit";

function storage(values: Record<string, string | null>) {
  return {
    getItem: (key: string) => values[key] ?? null,
    removeItem: (key: string) => { delete values[key]; },
  } as Storage;
}

describe("dati viaggio nel browser", () => {
  it("conta e rimuove soltanto tratte, itinerari e preferenze locali", () => {
    const values: Record<string, string | null> = {
      [LOCAL_GUEST_ROUTES_STORAGE_KEY]: JSON.stringify([{ id: "a", origin: "Roma", destination: "Milano", originStationCode: "ROM", destinationStationCode: "MIL", travelDate: "2026-10-02", travelTime: "08:15" }]),
      [LOCAL_JOURNEY_ITINERARIES_STORAGE_KEY]: JSON.stringify([{ schemaVersion: 1, id: "it-1", label: "Roma Milano", routeIds: ["a", "b"], createdAt: "2026-09-03T10:00:00.000Z" }]),
      [JOURNEY_ACCESSIBILITY_PREFERENCES_STORAGE_KEY]: JSON.stringify({ largeText: true }),
      theme: "dark",
      "binario-high-contrast": "true",
    };
    expect(inspectBrowserJourneyData(storage(values))).toEqual({ localRoutes: 1, localItineraries: 1, hasJourneyPreferences: true });
    expect(clearBrowserJourneyData(storage(values))).toEqual({ localRoutes: 1, localItineraries: 1, hasJourneyPreferences: true, cleared: true });
    expect(values).not.toHaveProperty(LOCAL_GUEST_ROUTES_STORAGE_KEY);
    expect(values).not.toHaveProperty(LOCAL_JOURNEY_ITINERARIES_STORAGE_KEY);
    expect(values).not.toHaveProperty(JOURNEY_ACCESSIBILITY_PREFERENCES_STORAGE_KEY);
    expect(values.theme).toBe("dark");
    expect(values["binario-high-contrast"]).toBe("true");
  });

  it("mantiene una cancellazione idempotente con archivio vuoto", () => {
    expect(clearBrowserJourneyData(storage({}))).toEqual({ localRoutes: 0, localItineraries: 0, hasJourneyPreferences: false, cleared: false });
  });
});
