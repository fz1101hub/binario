import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createLocalPlannedRoute } from "../lib/local-route-contract";

const storageKey = "binario.planned-route-reminders.v1";
const state = vi.hoisted(() => ({
  storage: new Map<string, string>(),
  scheduled: new Map<string, any>(),
}));

const storageMocks = vi.hoisted(() => ({
  getItem: vi.fn(),
  setItem: vi.fn(),
  removeItem: vi.fn(),
}));

const routeMocks = vi.hoisted(() => ({
  loadLocalPlannedRoutes: vi.fn(),
}));

const notificationMocks = vi.hoisted(() => ({
  getPermissionsAsync: vi.fn(),
  requestPermissionsAsync: vi.fn(),
  getAllScheduledNotificationsAsync: vi.fn(),
  scheduleNotificationAsync: vi.fn(),
  cancelScheduledNotificationAsync: vi.fn(),
}));

vi.mock("@react-native-async-storage/async-storage", () => ({
  default: storageMocks,
}));

vi.mock("expo-notifications", () => ({
  SchedulableTriggerInputTypes: { DATE: "date" },
  ...notificationMocks,
}));

vi.mock("../lib/local-routes", () => routeMocks);

import {
  cancelPlannedRouteReminder,
  consumeLegacyReminderMigrationNotice,
  listScheduledRouteIds,
  schedulePlannedRouteReminder,
} from "../lib/planned-reminder-service";

const route = createLocalPlannedRoute(
  {
    origin: "Roma Termini",
    destination: "Milano Centrale",
    travelDate: "2035-01-02",
    travelTime: "09:30",
    plannedReminderMinutes: 15,
  },
  42,
  1,
)!;
const secondRoute = createLocalPlannedRoute(
  {
    origin: "Torino Porta Nuova",
    destination: "Genova Piazza Principe",
    travelDate: "2035-01-03",
    travelTime: "10:15",
    plannedReminderMinutes: 10,
  },
  43,
  2,
)!;

beforeEach(() => {
  state.storage.clear();
  state.scheduled.clear();
  vi.clearAllMocks();
  storageMocks.getItem.mockImplementation(
    async (key: string) => state.storage.get(key) ?? null,
  );
  storageMocks.setItem.mockImplementation(async (key: string, value: string) => {
    state.storage.set(key, value);
  });
  storageMocks.removeItem.mockImplementation(async (key: string) => {
    state.storage.delete(key);
  });
  notificationMocks.getPermissionsAsync.mockResolvedValue({ granted: true });
  notificationMocks.requestPermissionsAsync.mockResolvedValue({
    granted: true,
  });
  notificationMocks.getAllScheduledNotificationsAsync.mockImplementation(
    async () => [...state.scheduled.values()],
  );
  notificationMocks.scheduleNotificationAsync.mockImplementation(
    async (request: any) => {
      state.scheduled.set(request.identifier, {
        identifier: request.identifier,
        content: request.content,
        trigger: request.trigger,
      });
      return request.identifier;
    },
  );
  notificationMocks.cancelScheduledNotificationAsync.mockImplementation(
    async (identifier: string) => {
      state.scheduled.delete(identifier);
    },
  );
  routeMocks.loadLocalPlannedRoutes.mockResolvedValue([route, secondRoute]);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("planned reminder native reconciliation", () => {
  it("preserves both native reminders when different routes are scheduled together", async () => {
    await Promise.all([
      schedulePlannedRouteReminder(route),
      schedulePlannedRouteReminder(secondRoute),
    ]);

    expect((await listScheduledRouteIds()).sort()).toEqual([42, 43]);
  });

  it("replaces a concurrent duplicate through the deterministic native identifier", async () => {
    await Promise.all([
      schedulePlannedRouteReminder(route),
      schedulePlannedRouteReminder(route),
    ]);

    expect(notificationMocks.scheduleNotificationAsync).toHaveBeenCalledTimes(
      2,
    );
    expect(state.scheduled.size).toBe(1);
    expect(await listScheduledRouteIds()).toEqual([42]);
  });

  it("orders a cancellation requested while scheduling after the native write", async () => {
    const [scheduled, cancelled] = await Promise.all([
      schedulePlannedRouteReminder(route),
      cancelPlannedRouteReminder(route.id),
    ]);

    expect(scheduled).toBe("scheduled");
    expect(cancelled).toBe(true);
    expect(await listScheduledRouteIds()).toEqual([]);
  });

  it("propagates legacy storage read failure before touching the native scheduler", async () => {
    storageMocks.getItem.mockRejectedValueOnce(
      new Error("storage unavailable"),
    );

    await expect(schedulePlannedRouteReminder(route)).rejects.toThrow(
      "storage unavailable",
    );
    expect(notificationMocks.scheduleNotificationAsync).not.toHaveBeenCalled();
  });

  it("keeps a legacy migration retryable when native cancellation fails", async () => {
    state.storage.set(
      storageKey,
      JSON.stringify({ 42: "legacy-notification" }),
    );
    state.scheduled.set("legacy-notification", {
      identifier: "legacy-notification",
      content: { title: "legacy", data: { routeId: 42 } },
      trigger: null,
    });
    notificationMocks.cancelScheduledNotificationAsync.mockRejectedValueOnce(
      new Error("native cancellation failed"),
    );

    await expect(listScheduledRouteIds()).rejects.toThrow(
      "native cancellation failed",
    );
    expect(state.storage.has(storageKey)).toBe(true);
  });

  it("recreates a legacy reminder under its canonical id before deleting the old one", async () => {
    state.storage.set(storageKey, JSON.stringify({ 42: 123 }));
    state.scheduled.set("legacy-notification", {
      identifier: "legacy-notification",
      content: {
        title: "Binario · tratta pianificata",
        data: { routeId: 42 },
      },
      trigger: null,
    });

    expect(await listScheduledRouteIds()).toEqual([42]);
    expect(state.storage.has(storageKey)).toBe(false);
    expect([...state.scheduled.keys()]).toEqual(["binario_planned_route_42"]);
    expect(
      notificationMocks.scheduleNotificationAsync.mock.invocationCallOrder[0] ?? 0,
    ).toBeLessThan(
      notificationMocks.cancelScheduledNotificationAsync.mock
        .invocationCallOrder[0] ?? Number.MAX_SAFE_INTEGER,
    );
  });

  it("keeps an orphan legacy reminder and exposes a one-time warning", async () => {
    routeMocks.loadLocalPlannedRoutes.mockResolvedValue([]);
    state.storage.set(storageKey, JSON.stringify({ 99: "legacy-orphan" }));
    state.scheduled.set("legacy-orphan", {
      identifier: "legacy-orphan",
      content: {
        title: "Binario · tratta pianificata",
        data: { routeId: 99 },
      },
      trigger: null,
    });

    expect(await listScheduledRouteIds()).toEqual([99]);
    expect(state.scheduled.has("legacy-orphan")).toBe(true);
    expect(await consumeLegacyReminderMigrationNotice()).toMatch(
      /mantenuto.*notifiche di sistema/i,
    );
    expect(await consumeLegacyReminderMigrationNotice()).toBeNull();
  });

  it("removes a random legacy identifier even when the storage marker is missing", async () => {
    state.scheduled.set("legacy-random-id", {
      identifier: "legacy-random-id",
      content: {
        title: "Binario · tratta pianificata",
        data: { routeId: 42 },
      },
      trigger: null,
    });

    await schedulePlannedRouteReminder(route);

    expect([...state.scheduled.keys()]).toEqual(["binario_planned_route_42"]);
  });

  it("rechecks the deadline after waiting inside the mutation queue", async () => {
    const fireAt =
      new Date(`${route.travelDate}T${route.travelTime}:00`).getTime() -
      route.plannedReminderMinutes! * 60_000;
    vi.spyOn(Date, "now")
      .mockReturnValueOnce(fireAt - 1_000)
      .mockReturnValue(fireAt + 1);

    await expect(schedulePlannedRouteReminder(route)).resolves.toBe("invalid");
    expect(notificationMocks.scheduleNotificationAsync).not.toHaveBeenCalled();
  });

  it("rejects malformed runtime routes before storage or native effects", async () => {
    for (const malformed of [
      { ...route, id: -1 },
      { ...route, id: 1.5 },
      { ...route, plannedReminderMinutes: -60 },
      { ...route, plannedReminderMinutes: "15" },
      { ...route, travelDate: "2035-02-30" },
    ]) {
      await expect(
        schedulePlannedRouteReminder(malformed as typeof route),
      ).resolves.toBe("invalid");
    }
    expect(storageMocks.getItem).not.toHaveBeenCalled();
    expect(notificationMocks.scheduleNotificationAsync).not.toHaveBeenCalled();
  });

  it("derives notification text from validated stations instead of runtime name", async () => {
    await expect(
      schedulePlannedRouteReminder({
        ...route,
        name: 123,
      } as unknown as typeof route),
    ).resolves.toBe("scheduled");
    expect(
      notificationMocks.scheduleNotificationAsync.mock.calls[0]?.[0].content
        .body,
    ).toContain("Roma Termini → Milano Centrale");
  });

  it("rejects a civil time normalized across a daylight-saving gap", async () => {
    const previousTimezone = process.env.TZ;
    process.env.TZ = "Europe/Rome";
    try {
      const gapRoute = createLocalPlannedRoute(
        {
          origin: "Roma Termini",
          destination: "Milano Centrale",
          travelDate: "2027-03-28",
          travelTime: "02:30",
          plannedReminderMinutes: 15,
        },
        44,
        3,
      )!;
      await expect(schedulePlannedRouteReminder(gapRoute)).resolves.toBe(
        "invalid",
      );
    } finally {
      process.env.TZ = previousTimezone;
    }
  });

  it("retains the previous native reminder when replacement scheduling fails", async () => {
    await schedulePlannedRouteReminder(route);
    notificationMocks.scheduleNotificationAsync.mockRejectedValueOnce(
      new Error("native scheduling failed"),
    );

    await expect(schedulePlannedRouteReminder(route)).rejects.toThrow(
      "native scheduling failed",
    );
    expect(await listScheduledRouteIds()).toEqual([42]);
  });

  it("retries legacy cleanup when removing the migration marker fails", async () => {
    state.storage.set(
      storageKey,
      JSON.stringify({ 42: "legacy-notification" }),
    );
    state.scheduled.set("legacy-notification", {
      identifier: "legacy-notification",
      content: { title: "legacy", data: { routeId: 42 } },
      trigger: null,
    });
    storageMocks.removeItem.mockRejectedValueOnce(new Error("remove failed"));

    await expect(listScheduledRouteIds()).rejects.toThrow("remove failed");
    expect(state.storage.has(storageKey)).toBe(true);
    expect([...state.scheduled.keys()]).toEqual(["binario_planned_route_42"]);

    expect(await listScheduledRouteIds()).toEqual([42]);
    expect(state.storage.has(storageKey)).toBe(false);
  });
});
