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
import { addLocalPlannedRoute, archiveRefusalMessageKey, loadLocalPlannedRoutes, localRoutesArchiveStatus, removeLocalPlannedRoute } from "@/lib/local-routes";

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

describe("archivio delle tratte in sola lettura", () => {
  beforeEach(() => storage.clear());

  it("rifiuta eliminazione e aggiunta e lo dice invece di dare la colpa ai campi", async () => {
    storage.set("binario-local-planned-routes-v1", JSON.stringify([route(1, "Roma Termini", "Milano Centrale"), { legacy: true }]));
    expect(await localRoutesArchiveStatus()).toBe("read-only");
    await expect(removeLocalPlannedRoute(1)).resolves.toBe(false);
    await expect(addLocalPlannedRoute({ origin: "Roma Termini", destination: "Napoli Centrale", travelDate: "2030-01-05", travelTime: "09:00", plannedReminderMinutes: null })).resolves.toBeNull();
    expect(archiveRefusalMessageKey(await localRoutesArchiveStatus(), "planner.routeFieldsError")).toBe("common.archiveReadOnly");
    expect(archiveRefusalMessageKey(await localRoutesArchiveStatus(), "alerts.deleteError")).toBe("common.archiveReadOnly");
    // The unreadable record is kept untouched.
    expect(JSON.parse(storage.get("binario-local-planned-routes-v1")!)).toHaveLength(2);
  });

  it("distingue archivio pieno e archivio scrivibile", async () => {
    storage.set("binario-local-planned-routes-v1", JSON.stringify(Array.from({ length: 40 }, (_, index) => route(index + 1, `Origine ${index}`, `Destinazione ${index}`))));
    expect(await localRoutesArchiveStatus()).toBe("full");
    expect(archiveRefusalMessageKey("full", "alerts.duplicateRoute")).toBe("common.archiveFull");
    storage.clear();
    expect(await localRoutesArchiveStatus()).toBe("writable");
    expect(archiveRefusalMessageKey("writable", "alerts.duplicateRoute")).toBe("alerts.duplicateRoute");
  });
});
