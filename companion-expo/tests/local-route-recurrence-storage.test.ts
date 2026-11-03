import { beforeEach, describe, expect, it, vi } from "vitest";

const storage = new Map<string, string>();
vi.mock("@react-native-async-storage/async-storage", () => ({ default: {
  getItem: vi.fn(async (key: string) => storage.get(key) ?? null),
  setItem: vi.fn(async (key: string, value: string) => { storage.set(key, value); }),
  removeItem: vi.fn(async (key: string) => { storage.delete(key); }),
} }));

import { addExplicitRecurringLocalPlannedRoutes, addLocalPlannedRoute, importSelectedManualPlannedRoutes, isLocalRoutesArchiveFull, loadLocalPlannedRoutes, setLocalPlannedRouteCompletion } from "@/lib/local-routes";

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

  it.each(["earlier", "later"])("rifiuta il 41esimo viaggio %s senza perdere dati", async position => {
    for (let index = 0; index < 40; index += 1) {
      const date = new Date(Date.UTC(2030, 1, 1 + index)).toISOString().slice(0, 10);
      expect(await addLocalPlannedRoute({ origin: "Roma", destination: "Milano", travelDate: date, travelTime: "08:15", plannedReminderMinutes: null }, 1_000 + index)).not.toBeNull();
    }
    expect(await isLocalRoutesArchiveFull()).toBe(true);
    const before = [...storage.values()][0];
    const date = position === "earlier" ? "2030-01-01" : "2030-05-01";
    expect(await addLocalPlannedRoute({ origin: "Milano", destination: "Roma", travelDate: date, travelTime: "09:20", plannedReminderMinutes: null }, 2_000)).toBeNull();
    expect([...storage.values()][0]).toBe(before);
    expect((await loadLocalPlannedRoutes()).map(route => route.id)).toHaveLength(40);
    expect((await loadLocalPlannedRoutes()).some(route => route.id === 2_000)).toBe(false);
  });

  it("serializza due salvataggi concorrenti sull'ultimo slot libero", async () => {
    for (let index = 0; index < 39; index += 1) {
      const date = new Date(Date.UTC(2030, 1, 1 + index)).toISOString().slice(0, 10);
      await addLocalPlannedRoute({ origin: "Roma", destination: "Milano", travelDate: date, travelTime: "08:15", plannedReminderMinutes: null }, 1_000 + index);
    }
    const [first, second] = await Promise.all([
      addLocalPlannedRoute({ origin: "Roma", destination: "Bari", travelDate: "2030-05-01", travelTime: "08:15", plannedReminderMinutes: null }, 2_001),
      addLocalPlannedRoute({ origin: "Roma", destination: "Torino", travelDate: "2030-05-02", travelTime: "08:15", plannedReminderMinutes: null }, 2_002),
    ]);
    expect([first, second].filter(Boolean)).toHaveLength(1);
    const routes = await loadLocalPlannedRoutes();
    expect(routes).toHaveLength(40);
    expect(routes.some(route => route.id === first?.id || route.id === second?.id)).toBe(true);
  });

  it("non perde update fra import manuale aggiunta e completamento concorrenti", async () => {
    const original = await addLocalPlannedRoute({ origin: "Roma", destination: "Milano", travelDate: "2030-01-04", travelTime: "08:15", plannedReminderMinutes: null }, 10);
    expect(original).not.toBeNull();
    const [imported, added, completed] = await Promise.all([
      importSelectedManualPlannedRoutes([{ origin: "Torino", destination: "Genova", travelDate: "2030-01-05", travelTime: "11:00", plannedReminderMinutes: null, checklist: [] }], 20),
      addLocalPlannedRoute({ origin: "Bari", destination: "Napoli", travelDate: "2030-01-06", travelTime: "12:00", plannedReminderMinutes: null }, 21),
      setLocalPlannedRouteCompletion(original!.id, true, 30),
    ]);
    expect(imported).toBe(1);
    expect(added).not.toBeNull();
    expect(completed).toBe(true);
    const routes = await loadLocalPlannedRoutes();
    expect(routes).toHaveLength(3);
    expect(routes.find(route => route.id === original!.id)?.completedAtUtcMs).toBe(30);
  });

  it("non sovrascrive una ricorrenza con un'aggiunta concorrente", async () => {
    const original = await addLocalPlannedRoute({ origin: "Roma", destination: "Milano", travelDate: "2030-01-04", travelTime: "08:15", plannedReminderMinutes: null }, 10);
    expect(original).not.toBeNull();
    const [recurrences, next] = await Promise.all([
      addExplicitRecurringLocalPlannedRoutes(original!.id, { weekdays: [1], occurrences: 2 }, 20),
      addLocalPlannedRoute({ origin: "Bari", destination: "Napoli", travelDate: "2030-01-06", travelTime: "12:00", plannedReminderMinutes: null }, 30),
    ]);
    expect(recurrences).toBe(2);
    expect(next).not.toBeNull();
    expect(await loadLocalPlannedRoutes()).toHaveLength(4);
  });
});
