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

  it("rimuove comunque l’archivio quando non esistono promemoria", async () => {
    const clearRoutes = vi.fn();
    await expect(clearLocalJourneyArchive({ listScheduledRouteIds: async () => [], cancelReminder: async () => undefined, clearRoutes })).resolves.toEqual({ clearedReminders: 0 });
    expect(clearRoutes).toHaveBeenCalledOnce();
  });
});

describe("eliminazione di una tratta locale", () => {
  it("annulla il promemoria programmato prima di rimuovere la tratta", async () => {
    const calls: string[] = [];
    const reminders = new Map([[4, "notification-4"]]);
    const cancelReminder = vi.fn(async (id: number) => { calls.push(`cancel:${id}`); return reminders.delete(id); });
    const removeRoute = vi.fn(async (id: number) => { calls.push(`remove:${id}`); return true; });
    await expect(deleteLocalPlannedRoute({ routeId: 4, cancelReminder, removeRoute })).resolves.toEqual({ removed: true, cancelledReminder: true });
    expect(calls).toEqual(["cancel:4", "remove:4"]);
    expect(reminders.size).toBe(0);
  });

  it("rimuove comunque la tratta quando non esiste alcun promemoria", async () => {
    const cancelReminder = vi.fn(async () => false);
    const removeRoute = vi.fn(async () => true);
    await expect(deleteLocalPlannedRoute({ routeId: 8, cancelReminder, removeRoute })).resolves.toEqual({ removed: true, cancelledReminder: false });
    expect(cancelReminder).toHaveBeenCalledWith(8);
    expect(removeRoute).toHaveBeenCalledWith(8);
  });
});
