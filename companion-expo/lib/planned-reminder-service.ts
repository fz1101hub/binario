import AsyncStorage from "@react-native-async-storage/async-storage";
import * as Notifications from "expo-notifications";
import {
  isLocalPlannedRoute,
  type LocalPlannedRoute,
} from "@/lib/local-route-contract";
import { loadLocalPlannedRoutes } from "@/lib/local-routes";

const legacyStorageKey = "binario.planned-route-reminders.v1";
const legacyMigrationNoticeKey = "binario.planned-route-reminders.v1-migration-notice";
const legacyMigrationNoticeAcknowledgedKey = "binario.planned-route-reminders.v1-migration-notice-acknowledged";
const notificationTitle = "Binario · tratta pianificata";
let reminderMutationQueue: Promise<void> = Promise.resolve();

function reminderIdentifier(routeId: number) {
  return `binario_planned_route_${routeId}`;
}

function serializeMutation<T>(operation: () => Promise<T>): Promise<T> {
  const result = reminderMutationQueue.then(operation, operation);
  reminderMutationQueue = result.then(
    () => undefined,
    () => undefined,
  );
  return result;
}

function routeIdFromRequest(request: Notifications.NotificationRequest) {
  const routeId = request.content.data?.routeId;
  return typeof routeId === "number" &&
    Number.isSafeInteger(routeId) &&
    routeId > 0
    ? routeId
    : null;
}

function legacyRouteIdsByIdentifier(raw: string): Map<string, number> {
  try {
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      return new Map();
    }
    return new Map(
      Object.entries(value).flatMap(([routeId, identifier]) => {
        const parsedRouteId = Number(routeId);
        return typeof identifier === "string" &&
          identifier.trim() !== "" &&
          Number.isSafeInteger(parsedRouteId) &&
          parsedRouteId > 0
          ? [[identifier, parsedRouteId]]
          : [];
      }),
    );
  } catch {
    return new Map();
  }
}

function localDepartureEpoch(route: LocalPlannedRoute): number | null {
  const dateMatch = /^(\d{4})-(\d{2})-(\d{2})$/.exec(route.travelDate);
  const timeMatch = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(route.travelTime);
  if (!dateMatch || !timeMatch) return null;
  const expected = {
    year: Number(dateMatch[1]),
    month: Number(dateMatch[2]),
    day: Number(dateMatch[3]),
    hour: Number(timeMatch[1]),
    minute: Number(timeMatch[2]),
  };
  const departure = new Date(
    expected.year,
    expected.month - 1,
    expected.day,
    expected.hour,
    expected.minute,
    0,
    0,
  );
  if (
    departure.getFullYear() !== expected.year ||
    departure.getMonth() !== expected.month - 1 ||
    departure.getDate() !== expected.day ||
    departure.getHours() !== expected.hour ||
    departure.getMinutes() !== expected.minute
  ) {
    return null;
  }
  return departure.getTime();
}

function reminderFireAt(route: LocalPlannedRoute): number | null {
  const departureAt = localDepartureEpoch(route);
  if (departureAt === null || route.plannedReminderMinutes === null) return null;
  const fireAt = departureAt - route.plannedReminderMinutes * 60_000;
  return Number.isFinite(fireAt) ? fireAt : null;
}

function reminderRequest(route: LocalPlannedRoute, fireAt: number) {
  return {
    identifier: reminderIdentifier(route.id),
    content: {
      title: notificationTitle,
      body: `${route.origin} → ${route.destination} alle ${route.travelTime}. Avviso locale approssimativo, non realtime.`,
      data: { routeId: route.id },
    },
    trigger: {
      type: Notifications.SchedulableTriggerInputTypes.DATE,
      date: new Date(fireAt),
    },
  } as const;
}

async function recordPreservedLegacyWarning(count: number) {
  if (count === 0) return;
  if (await AsyncStorage.getItem(legacyMigrationNoticeAcknowledgedKey)) return;
  const state = count === 1
    ? "promemoria precedente è stato mantenuto"
    : "promemoria precedenti sono stati mantenuti";
  await AsyncStorage.setItem(
    legacyMigrationNoticeKey,
    `${count} ${state} perché non è più possibile ${count === 1 ? "collegarlo" : "collegarli"} in sicurezza a una tratta futura salvata. Controlla le notifiche di sistema.`,
  );
  await AsyncStorage.setItem(legacyMigrationNoticeAcknowledgedKey, "1");
}

/**
 * V1 stored random native identifiers. A legacy notification is removed only after
 * the matching saved future route has been recreated under its canonical identifier.
 * Unknown, expired, or otherwise orphaned requests stay scheduled and are surfaced.
 */
async function migrateLegacyState(): Promise<void> {
  const raw = await AsyncStorage.getItem(legacyStorageKey);
  const storedRouteIds = raw === null
    ? new Map<string, number>()
    : legacyRouteIdsByIdentifier(raw);
  const scheduled = await Notifications.getAllScheduledNotificationsAsync();
  const legacyRequests = scheduled.filter((request) => {
    const routeId = routeIdFromRequest(request) ?? storedRouteIds.get(request.identifier);
    return storedRouteIds.has(request.identifier) ||
      (routeId !== undefined &&
        request.content.title === notificationTitle &&
        request.identifier !== reminderIdentifier(routeId));
  });
  if (legacyRequests.length === 0) {
    if (raw !== null) await AsyncStorage.removeItem(legacyStorageKey);
    return;
  }

  const routesById = new Map(
    (await loadLocalPlannedRoutes()).map((route) => [route.id, route]),
  );
  const canonicalIdentifiers = new Set(scheduled.map((request) => request.identifier));
  const preserved = new Set<string>();

  for (const request of legacyRequests) {
    const routeId = routeIdFromRequest(request) ?? storedRouteIds.get(request.identifier);
    if (routeId === undefined) {
      preserved.add(request.identifier);
      continue;
    }
    const canonicalIdentifier = reminderIdentifier(routeId);
    if (canonicalIdentifiers.has(canonicalIdentifier)) {
      await Notifications.cancelScheduledNotificationAsync(request.identifier);
      continue;
    }
    const route = routesById.get(routeId);
    const fireAt = route ? reminderFireAt(route) : null;
    if (!route || fireAt === null || fireAt <= Date.now()) {
      preserved.add(request.identifier);
      continue;
    }
    await Notifications.scheduleNotificationAsync(reminderRequest(route, fireAt));
    canonicalIdentifiers.add(canonicalIdentifier);
    await Notifications.cancelScheduledNotificationAsync(request.identifier);
  }

  if (raw !== null && ![...storedRouteIds.keys()].some((identifier) => preserved.has(identifier))) {
    await AsyncStorage.removeItem(legacyStorageKey);
  }
  await recordPreservedLegacyWarning(preserved.size);
}

async function scheduledRouteIds(): Promise<Set<number>> {
  const requests = await Notifications.getAllScheduledNotificationsAsync();
  return new Set(
    requests
      .filter((request) => request.content.title === notificationTitle)
      .map(routeIdFromRequest)
      .filter((routeId): routeId is number => routeId !== null),
  );
}

export function listScheduledRouteIds() {
  return serializeMutation(async () => {
    await migrateLegacyState();
    return [...(await scheduledRouteIds())];
  });
}

/** Returns and clears the one-time explanation for legacy reminders retained safely. */
export function consumeLegacyReminderMigrationNotice() {
  return serializeMutation(async () => {
    const notice = await AsyncStorage.getItem(legacyMigrationNoticeKey);
    if (notice !== null) await AsyncStorage.removeItem(legacyMigrationNoticeKey);
    return notice;
  });
}

export function cancelPlannedRouteReminder(routeId: number) {
  return serializeMutation(async () => {
    if (!Number.isSafeInteger(routeId) || routeId <= 0) return false;
    await migrateLegacyState();
    const identifiers = (
      await Notifications.getAllScheduledNotificationsAsync()
    )
      .filter(
        (request) =>
          request.content.title === notificationTitle &&
          routeIdFromRequest(request) === routeId,
      )
      .map((request) => request.identifier);
    const hadScheduledReminder = identifiers.length > 0;
    if (!identifiers.includes(reminderIdentifier(routeId))) {
      identifiers.push(reminderIdentifier(routeId));
    }
    for (const identifier of identifiers) {
      await Notifications.cancelScheduledNotificationAsync(identifier);
    }
    return hadScheduledReminder;
  });
}

export async function schedulePlannedRouteReminder(
  route: LocalPlannedRoute,
): Promise<"scheduled" | "permission-denied" | "invalid"> {
  let validRoute = false;
  try {
    validRoute = isLocalPlannedRoute(route);
  } catch {
    return "invalid";
  }
  const fireAt = validRoute ? reminderFireAt(route) : null;
  if (fireAt === null || fireAt <= Date.now()) return "invalid";

  return serializeMutation(async () => {
    await migrateLegacyState();
    const current = await Notifications.getPermissionsAsync();
    const permission = current.granted
      ? current
      : await Notifications.requestPermissionsAsync();
    if (!permission.granted) return "permission-denied" as const;
    if (fireAt <= Date.now()) return "invalid" as const;

    await Notifications.scheduleNotificationAsync(reminderRequest(route, fireAt));
    return "scheduled" as const;
  });
}
