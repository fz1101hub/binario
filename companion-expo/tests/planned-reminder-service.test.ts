import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createLocalPlannedRoute } from "../lib/local-route-contract";
import { clearLocalJourneyArchive } from "../lib/local-journey-privacy";
import { createTranslator, supportedLocales } from "../lib/i18n";
import { travelEpochMs } from "../lib/travel-time-zone";

const storageKey = "binario.planned-route-reminders.v1";
const state = vi.hoisted(() => ({
  storage: new Map<string, string>(),
  scheduled: new Map<string, any>(),
  deviceLanguageTag: "it-IT",
}));

vi.mock("expo-localization", () => ({
  getLocales: () => [{ languageTag: state.deviceLanguageTag }],
  useLocales: () => [{ languageTag: state.deviceLanguageTag }],
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
  setNotificationChannelAsync: vi.fn(),
}));

vi.mock("@react-native-async-storage/async-storage", () => ({
  default: storageMocks,
}));

vi.mock("expo-notifications", () => ({
  SchedulableTriggerInputTypes: { DATE: "date" },
  AndroidImportance: { DEFAULT: 5 },
  ...notificationMocks,
}));

vi.mock("../lib/local-routes", () => routeMocks);

import {
  cancelPlannedRouteReminder,
  consumeLegacyReminderMigrationNotice,
  listScheduledRouteIds,
  scheduleReminderForSavedRoute,
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
  state.deviceLanguageTag = "it-IT";
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
      travelEpochMs(route.travelDate, route.travelTime)! -
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

  it("fires at the Italian departure time minus the lead, whatever the phone's time zone", async () => {
    const previousTimezone = process.env.TZ;
    const summerRoute = createLocalPlannedRoute(
      {
        origin: "Roma Termini",
        destination: "Firenze Santa Maria Novella",
        travelDate: "2035-07-12",
        travelTime: "09:00",
        plannedReminderMinutes: 15,
      },
      45,
      4,
    )!;
    try {
      for (const timeZone of ["Europe/London", "America/New_York", "Europe/Rome"]) {
        process.env.TZ = timeZone;
        state.scheduled.clear();
        await expect(schedulePlannedRouteReminder(summerRoute)).resolves.toBe("scheduled");
        // 09:00 in Italy in July is 07:00 UTC; 15 minutes before is 06:45 UTC.
        expect(state.scheduled.get("binario_planned_route_45")?.trigger.date.getTime(), timeZone).toBe(Date.UTC(2035, 6, 12, 6, 45));
      }
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

describe("promemoria riconosciuti in ogni lingua del dispositivo", () => {
  for (const languageTag of ["ja-JP", "ar-EG", "ru-RU", "pt-BR", "zh-Hans-CN", "hi-IN"]) {
    it(`elenca, annulla e cancella con l’archivio un promemoria scritto in ${languageTag}`, async () => {
      state.deviceLanguageTag = languageTag;
      await expect(schedulePlannedRouteReminder(route)).resolves.toBe("scheduled");
      const scheduledTitle = state.scheduled.get("binario_planned_route_42")?.content.title;
      expect(scheduledTitle).not.toBe(createTranslator("it")("reminder.notificationTitle"));

      expect(await listScheduledRouteIds()).toEqual([42]);
      await expect(clearLocalJourneyArchive({
        listScheduledRouteIds,
        cancelReminder: cancelPlannedRouteReminder,
        clearRoutes: async () => undefined,
        clearItineraries: async () => undefined,
        clearCachedFiles: async () => undefined,
        clearTemplates: async () => undefined,
        clearRecentStations: async () => undefined,
      })).resolves.toEqual({ clearedReminders: 1 });
      expect(state.scheduled.size).toBe(0);
    });
  }

  it("riconosce il promemoria di ogni lingua servita, anche dopo un cambio di lingua", async () => {
    for (const locale of supportedLocales) {
      state.scheduled.clear();
      state.deviceLanguageTag = locale;
      await schedulePlannedRouteReminder(route);
      state.deviceLanguageTag = "en-US";
      expect(await listScheduledRouteIds(), locale).toEqual([42]);
      await expect(cancelPlannedRouteReminder(42), locale).resolves.toBe(true);
      expect(state.scheduled.size, locale).toBe(0);
    }
  });

  it("riconosce il promemoria dall’identificativo canonico anche senza dati della tratta", async () => {
    state.scheduled.set("binario_planned_route_42", {
      identifier: "binario_planned_route_42",
      content: { title: "Binario · 計画した移動", data: {} },
      trigger: null,
    });
    state.scheduled.set("binario_planned_route_x", {
      identifier: "binario_planned_route_x",
      content: { title: "Binario", data: {} },
      trigger: null,
    });

    expect(await listScheduledRouteIds()).toEqual([42]);
    await expect(cancelPlannedRouteReminder(42)).resolves.toBe(true);
    expect([...state.scheduled.keys()]).toEqual(["binario_planned_route_x"]);
  });

  it("migra un promemoria precedente scritto in un’altra lingua", async () => {
    state.scheduled.set("legacy-random-ja", {
      identifier: "legacy-random-ja",
      content: { title: "Binario · 計画した移動", data: { routeId: 42 } },
      trigger: null,
    });

    expect(await listScheduledRouteIds()).toEqual([42]);
    expect([...state.scheduled.keys()]).toEqual(["binario_planned_route_42"]);
  });
});

describe("promemoria scelto nel planner", () => {
  it("lo programma al salvataggio della tratta", async () => {
    await expect(scheduleReminderForSavedRoute(route)).resolves.toBe("scheduled");
    expect(await listScheduledRouteIds()).toEqual([42]);
  });

  it("non programma nulla con «Nessuno»", async () => {
    await expect(scheduleReminderForSavedRoute({ ...route, plannedReminderMinutes: null })).resolves.toBe("none");
    expect(notificationMocks.scheduleNotificationAsync).not.toHaveBeenCalled();
    expect(notificationMocks.requestPermissionsAsync).not.toHaveBeenCalled();
  });

  it("riferisce permesso negato, orario passato ed errore nativo senza perdere la tratta", async () => {
    notificationMocks.getPermissionsAsync.mockResolvedValue({ granted: false });
    notificationMocks.requestPermissionsAsync.mockResolvedValue({ granted: false });
    await expect(scheduleReminderForSavedRoute(route)).resolves.toBe("permission-denied");
    await expect(scheduleReminderForSavedRoute({ ...route, travelDate: "2020-01-02" })).resolves.toBe("invalid");
    notificationMocks.getPermissionsAsync.mockResolvedValue({ granted: true });
    notificationMocks.scheduleNotificationAsync.mockRejectedValueOnce(new Error("native"));
    await expect(scheduleReminderForSavedRoute(route)).resolves.toBe("error");
    expect(state.scheduled.size).toBe(0);
  });
});

describe("canale Android dei promemoria", () => {
  it("programma ogni promemoria nel proprio canale, con nome e descrizione nella lingua del dispositivo", async () => {
    state.deviceLanguageTag = "ar-EG";
    await schedulePlannedRouteReminder(route);
    expect(notificationMocks.setNotificationChannelAsync).toHaveBeenCalledWith("binario-planned-route-reminders", {
      name: createTranslator("ar")("reminder.channelName"),
      description: createTranslator("ar")("reminder.channelDescription"),
      importance: 5,
    });
    expect(notificationMocks.setNotificationChannelAsync.mock.invocationCallOrder[0]).toBeLessThan(notificationMocks.scheduleNotificationAsync.mock.invocationCallOrder[0]!);
    expect(state.scheduled.get("binario_planned_route_42")?.trigger.channelId).toBe("binario-planned-route-reminders");
  });

  it("non usa il canale di riserva «Miscellaneous» neppure per i promemoria migrati", async () => {
    state.scheduled.set("legacy-random-id", { identifier: "legacy-random-id", content: { title: "Binario · tratta pianificata", data: { routeId: 42 } }, trigger: null });
    await listScheduledRouteIds();
    expect(state.scheduled.get("binario_planned_route_42")?.trigger.channelId).toBe("binario-planned-route-reminders");
  });
});
