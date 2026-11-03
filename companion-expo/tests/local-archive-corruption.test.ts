import { beforeEach, describe, expect, it, vi } from "vitest";

const storage = new Map<string, string>();
vi.mock("@react-native-async-storage/async-storage", () => ({
  default: {
    getItem: vi.fn(async (key: string) => storage.get(key) ?? null),
    setItem: vi.fn(async (key: string, value: string) => { storage.set(key, value); }),
    removeItem: vi.fn(async (key: string) => { storage.delete(key); }),
  },
}));

import { addLocalJourneyItinerary, loadLocalJourneyItineraries } from "@/lib/local-itineraries";
import { loadLocalJourneyTemplates, saveLocalJourneyTemplate, type LocalJourneyTemplate } from "@/lib/local-journey-templates";
import { addLocalPlannedRoute, loadLocalPlannedRoutes } from "@/lib/local-routes";

const route = (id: number, origin: string, destination: string) => ({
  id,
  name: `${origin} → ${destination}`,
  origin,
  originStationCode: null,
  destination,
  destinationStationCode: null,
  travelDate: "2030-01-04",
  travelTime: "08:15",
  plannedReminderMinutes: 15,
  source: "device" as const,
  createdAtUtcMs: id,
  completedAtUtcMs: null,
});

const itinerary = (id: string, createdAtUtcMs: number) => ({
  schemaVersion: 1,
  id,
  createdAtUtcMs,
  label: id,
  routeIds: [createdAtUtcMs, createdAtUtcMs + 1],
});

const template = (id: string, origin: string, destination: string): LocalJourneyTemplate => ({
  id,
  label: id,
  origin,
  originStationCode: origin.toUpperCase(),
  destination,
  destinationStationCode: destination.toUpperCase(),
  travelTime: "08:15",
  plannedReminderMinutes: 15,
  checklist: [],
});

describe("archivi locali parzialmente corrotti", () => {
  beforeEach(() => storage.clear());

  it("recupera due tratte valide, ignora quella malformata e non sovrascrive il payload", async () => {
    const key = "binario-local-planned-routes-v1";
    const raw = JSON.stringify([route(10, "Roma", "Milano"), { ...route(11, "Torino", "Genova"), origin: 42 }, route(12, "Bologna", "Firenze")]);
    storage.set(key, raw);

    await expect(loadLocalPlannedRoutes()).resolves.toHaveLength(2);
    await expect(addLocalPlannedRoute({ origin: "Napoli", destination: "Bari", travelDate: "2030-01-05", travelTime: "09:30", plannedReminderMinutes: null }, 20)).resolves.toBeNull();
    expect(storage.get(key)).toBe(raw);
  });

  it("recupera due itinerari validi, ignora quello malformato e non sovrascrive il payload", async () => {
    const key = "binario-local-itineraries-v1";
    const raw = JSON.stringify([itinerary("primo", 10), { ...itinerary("rotto", 20), routeIds: "non-array" }, itinerary("secondo", 30)]);
    storage.set(key, raw);

    await expect(loadLocalJourneyItineraries()).resolves.toHaveLength(2);
    await expect(addLocalJourneyItinerary({ label: "Nuovo", routeIds: [40, 41] }, 40)).resolves.toBeNull();
    expect(storage.get(key)).toBe(raw);
  });

  it("recupera due modelli validi, ignora quello malformato e non sovrascrive il payload", async () => {
    const key = "binario.local-journey-templates.v1";
    const first = template("primo", "Roma", "Milano");
    const second = template("secondo", "Torino", "Genova");
    const raw = JSON.stringify([first, { ...template("rotto", "Napoli", "Bari"), label: { value: "non-stringa" } }, second]);
    storage.set(key, raw);

    await expect(loadLocalJourneyTemplates()).resolves.toHaveLength(2);
    await expect(saveLocalJourneyTemplate(template("nuovo", "Bologna", "Firenze"))).resolves.toEqual([first, second]);
    expect(storage.get(key)).toBe(raw);
  });
});
