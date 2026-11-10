import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  update: vi.fn(),
  loadLocalPlannedRoutes: vi.fn(),
  listScheduledRouteIds: vi.fn(),
  deleteAsync: vi.fn(),
}));
vi.mock("react-native", () => ({ NativeModules: { BinarioWidgetBridge: { update: mocks.update } } }));
vi.mock("@/lib/local-routes", () => ({ loadLocalPlannedRoutes: mocks.loadLocalPlannedRoutes }));
vi.mock("@/lib/planned-reminder-service", () => ({ listScheduledRouteIds: mocks.listScheduledRouteIds }));
vi.mock("expo-file-system/legacy", () => ({ cacheDirectory: "file:///cache/", deleteAsync: mocks.deleteAsync }));

import { binarioWidgetBridgeModuleName } from "@/lib/native-module-names";
import { createLocalPlannedRoute } from "@/lib/local-route-contract";
import { deleteLocalJourneyCacheFiles } from "@/lib/local-journey-files";
import { nativeSurfaceStateFor, refreshNativeCompanionSurfaces } from "@/lib/native-surface-refresh";
import { travelEpochMs } from "@/lib/travel-time-zone";

const now = travelEpochMs("2030-01-02", "08:00")!;
const route = createLocalPlannedRoute({ origin: "Roma Termini", destination: "Milano Centrale", travelDate: "2030-01-02", travelTime: "18:00", plannedReminderMinutes: 15 }, 9, 1)!;

beforeEach(() => {
  vi.clearAllMocks();
  mocks.listScheduledRouteIds.mockResolvedValue([]);
});

describe("widget e orologio dopo ogni modifica", () => {
  it("usa il nome del modulo nativo dichiarato", () => {
    expect(binarioWidgetBridgeModuleName).toBe("BinarioWidgetBridge");
  });

  it("mostra la prossima tratta e, dopo eliminazione, completamento o cancellazione, nessuna tratta", () => {
    expect(nativeSurfaceStateFor([route], [9], now)).toMatchObject({ route: { id: 9 }, reminderScheduled: true, snapshot: { provenance: { dataNature: "scheduled" } } });
    expect(nativeSurfaceStateFor([], [], now)).toMatchObject({ route: null, reminderScheduled: false, snapshot: { provenance: { dataNature: "unavailable" } } });
    expect(nativeSurfaceStateFor([{ ...route, completedAtUtcMs: now }], [9], now).route).toBeNull();
  });

  it("dopo la prima partenza passa alla tratta successiva invece di restare vuoto", () => {
    const morning = createLocalPlannedRoute({ origin: "Roma Termini", destination: "Napoli Centrale", travelDate: "2030-01-02", travelTime: "08:00", plannedReminderMinutes: 15 }, 8, 1)!;
    expect(nativeSurfaceStateFor([route, morning], [], now - 60_000).route?.id).toBe(8);
    expect(nativeSurfaceStateFor([route, morning], [], now + 50).route?.id).toBe(9);
  });

  it("invia al widget lo stato non disponibile quando l’archivio è vuoto", async () => {
    mocks.loadLocalPlannedRoutes.mockResolvedValue([]);
    await refreshNativeCompanionSurfaces("it", now);
    expect(mocks.update).toHaveBeenCalledOnce();
    expect(mocks.update.mock.calls[0]?.[0]).toMatchObject({ widget: { status: "unavailable", plannedJourney: null }, watch: { provenanceDataNature: "UNAVAILABLE" } });
  });

  it("invia la prossima tratta con i minuti del promemoria programmato", async () => {
    mocks.loadLocalPlannedRoutes.mockResolvedValue([route]);
    mocks.listScheduledRouteIds.mockResolvedValue([9]);
    await refreshNativeCompanionSurfaces("it", now);
    expect(mocks.update.mock.calls[0]?.[0]).toMatchObject({ widget: { status: "available", plannedJourney: { title: route.name, reminderMinutes: 15 } } });
  });

  it("non fallisce se lo storage non risponde", async () => {
    mocks.loadLocalPlannedRoutes.mockRejectedValue(new Error("storage"));
    await expect(refreshNativeCompanionSurfaces("it", now)).resolves.toBeUndefined();
    expect(mocks.update).not.toHaveBeenCalled();
  });
});

describe("file della cache", () => {
  it("rimuove esportazioni e copie dei file scelti", async () => {
    await deleteLocalJourneyCacheFiles();
    expect(mocks.deleteAsync.mock.calls.map(call => call[0])).toEqual(["file:///cache/binario-tratta.ics", "file:///cache/binario-tratte.json", "file:///cache/DocumentPicker"]);
    for (const call of mocks.deleteAsync.mock.calls) expect(call[1]).toEqual({ idempotent: true });
  });
});
