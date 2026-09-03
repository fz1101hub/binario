import { describe, expect, it, vi } from "vitest";
import { clearLocalJourneyArchive } from "../lib/local-journey-privacy";

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
