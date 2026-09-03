import { beforeEach, describe, expect, it, vi } from "vitest";

const storage = new Map<string, string>();
vi.mock("@react-native-async-storage/async-storage", () => ({ default: {
  getItem: vi.fn(async (key: string) => storage.get(key) ?? null),
  setItem: vi.fn(async (key: string, value: string) => { storage.set(key, value); }),
  removeItem: vi.fn(async (key: string) => { storage.delete(key); }),
} }));

import { addExplicitRecurringLocalPlannedRoutes, addLocalPlannedRoute, loadLocalPlannedRoutes, setLocalPlannedRouteCompletion } from "@/lib/local-routes";

describe("ricorrenze e completamento persistiti", () => {
  beforeEach(() => storage.clear());

  it("aggiunge solo copie ricorrenti non duplicate e senza completamenti ereditati", async () => {
    const route = await addLocalPlannedRoute({ origin: "Roma", destination: "Milano", travelDate: "2030-01-04", travelTime: "08:15", plannedReminderMinutes: 15 }, 10);
    expect(route).not.toBeNull();
    await expect(addExplicitRecurringLocalPlannedRoutes(route!.id, { weekdays: [1], occurrences: 2 }, 20)).resolves.toBe(2);
    await expect(addExplicitRecurringLocalPlannedRoutes(route!.id, { weekdays: [1], occurrences: 2 }, 30)).resolves.toBe(0);
    const saved = await loadLocalPlannedRoutes();
    expect(saved).toHaveLength(3);
    expect(saved.filter(item => item.id !== route!.id).every(item => item.completedAtUtcMs === null && item.checklist?.every(check => !check.completed))).toBe(true);
  });

  it("persiste e ripristina esclusivamente lo stato manuale di completamento", async () => {
    const route = await addLocalPlannedRoute({ origin: "Roma", destination: "Milano", travelDate: "2030-01-04", travelTime: "08:15", plannedReminderMinutes: 15 }, 10);
    await expect(setLocalPlannedRouteCompletion(route!.id, true, 99)).resolves.toBe(true);
    expect((await loadLocalPlannedRoutes())[0]?.completedAtUtcMs).toBe(99);
    await setLocalPlannedRouteCompletion(route!.id, false);
    expect((await loadLocalPlannedRoutes())[0]?.completedAtUtcMs).toBeNull();
  });
});
