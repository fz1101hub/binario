import { describe, expect, it, vi } from "vitest";
import { clearLocalJourneyArchive, deleteLocalPlannedRoute } from "../lib/local-journey-privacy";

describe("cancellazione archivio viaggio locale", () => {
  it("annulla promemoria e rimuove tratte e itinerari locali", async () => {
    const calls: string[] = [];
    const listScheduledRouteIds = vi.fn().mockResolvedValue([4, 8]);
    const cancelReminder = vi.fn(async (id: number) => { calls.push(`cancel:${id}`); });
    const clearRoutes = vi.fn(async () => { calls.push("clear"); });
    const clearItineraries = vi.fn(async () => { calls.push("clear-itineraries"); });
    await expect(clearLocalJourneyArchive({ listScheduledRouteIds, cancelReminder, clearRoutes, clearItineraries })).resolves.toEqual({ clearedReminders: 2 });
    expect(calls).toEqual(["cancel:4", "cancel:8", "clear", "clear-itineraries"]);
  });

  it("rimuove comunque archivio e itinerari quando non esistono promemoria", async () => {
    const clearRoutes = vi.fn();
    const clearItineraries = vi.fn();
    await expect(clearLocalJourneyArchive({ listScheduledRouteIds: async () => [], cancelReminder: async () => undefined, clearRoutes, clearItineraries })).resolves.toEqual({ clearedReminders: 0 });
    expect(clearRoutes).toHaveBeenCalledOnce();
    expect(clearItineraries).toHaveBeenCalledOnce();
  });

  it("rifiuta una cancellazione senza clearItineraries, per tsc e a runtime, senza toccare l’archivio", async () => {
    const cancelReminder = vi.fn();
    const clearRoutes = vi.fn();
    const incomplete = { listScheduledRouteIds: async () => [4], cancelReminder, clearRoutes };
    // @ts-expect-error clearItineraries è una dipendenza obbligatoria: ometterla deve fallire in `pnpm run check`
    await expect(clearLocalJourneyArchive(incomplete)).rejects.toThrow(TypeError);
    expect(cancelReminder).not.toHaveBeenCalled();
    expect(clearRoutes).not.toHaveBeenCalled();
  });
});

describe("eliminazione di una tratta locale", () => {
  const dependencies = (overrides: Partial<Parameters<typeof deleteLocalPlannedRoute>[0]> = {}) => ({
    routeId: 4,
    cancelReminder: vi.fn(async () => false),
    removeRoute: vi.fn(async () => true),
    listRemainingRouteIds: vi.fn(async () => [8, 15]),
    reconcileItineraries: vi.fn(async () => []),
    ...overrides,
  });

  it("annulla il promemoria programmato prima di rimuovere la tratta, poi riconcilia gli itinerari con le tratte rimaste", async () => {
    const calls: string[] = [];
    const reminders = new Map([[4, "notification-4"]]);
    const deps = dependencies({
      cancelReminder: vi.fn(async (id: number) => { calls.push(`cancel:${id}`); return reminders.delete(id); }),
      removeRoute: vi.fn(async (id: number) => { calls.push(`remove:${id}`); return true; }),
      reconcileItineraries: vi.fn(async (ids: readonly number[]) => { calls.push(`reconcile:${ids.join(",")}`); return []; }),
    });
    await expect(deleteLocalPlannedRoute(deps)).resolves.toEqual({ removed: true, cancelledReminder: true });
    expect(calls).toEqual(["cancel:4", "remove:4", "reconcile:8,15"]);
    expect(reminders.size).toBe(0);
  });

  it("rimuove comunque la tratta quando non esiste alcun promemoria", async () => {
    const deps = dependencies({ routeId: 8 });
    await expect(deleteLocalPlannedRoute(deps)).resolves.toEqual({ removed: true, cancelledReminder: false });
    expect(deps.cancelReminder).toHaveBeenCalledWith(8);
    expect(deps.removeRoute).toHaveBeenCalledWith(8);
    expect(deps.reconcileItineraries).toHaveBeenCalledWith([8, 15]);
  });

  it("non riconcilia gli itinerari se la tratta non era presente nell’archivio", async () => {
    const deps = dependencies({ removeRoute: vi.fn(async () => false) });
    await expect(deleteLocalPlannedRoute(deps)).resolves.toEqual({ removed: false, cancelledReminder: false });
    expect(deps.reconcileItineraries).not.toHaveBeenCalled();
  });
});
