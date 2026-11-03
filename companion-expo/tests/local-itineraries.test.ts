import { beforeEach, describe, expect, it, vi } from "vitest";

const storage = new Map<string, string>();
vi.mock("@react-native-async-storage/async-storage", () => ({
  default: {
    getItem: vi.fn(async (key: string) => storage.get(key) ?? null),
    setItem: vi.fn(async (key: string, value: string) => { storage.set(key, value); }),
    removeItem: vi.fn(async (key: string) => { storage.delete(key); }),
  },
}));

import { addLocalJourneyItinerary, loadLocalJourneyItineraries, reconcileLocalJourneyItineraries } from "@/lib/local-itineraries";

describe("archivio itinerari locali", () => {
  beforeEach(() => storage.clear());

  it("salva un gruppo valido una sola volta", async () => {
    await expect(addLocalJourneyItinerary({ label: "Roma Milano", routeIds: [4, 8] }, 100)).resolves.toMatchObject({ label: "Roma Milano", routeIds: [4, 8] });
    await expect(addLocalJourneyItinerary({ label: "Duplicato", routeIds: [4, 8] }, 101)).resolves.toBeNull();
    await expect(loadLocalJourneyItineraries()).resolves.toHaveLength(1);
  });

  it("rimuove un gruppo quando restano meno di due tratte disponibili", async () => {
    await addLocalJourneyItinerary({ label: "Cambio", routeIds: [4, 8] }, 100);
    await expect(reconcileLocalJourneyItineraries([4])).resolves.toEqual([]);
    await expect(loadLocalJourneyItineraries()).resolves.toEqual([]);
  });
});
