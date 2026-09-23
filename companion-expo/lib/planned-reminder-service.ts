import AsyncStorage from "@react-native-async-storage/async-storage";
import * as Notifications from "expo-notifications";
import {
  isLocalPlannedRoute,
  type LocalPlannedRoute,
} from "@/lib/local-route-contract";

const legacyStorageKey = "binario.planned-route-reminders.v1";
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

function legacyIdentifiers(raw: string): Set<string> {
  try {
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== "object" || Array.isArray(value))
      return new Set();
    return new Set(
      Object.values(value).filter(
        (identifier): identifier is string =>
          typeof identifier === "string" && identifier.trim() !== "",
      ),
    );
  } catch {
    return new Set();
  }
}

/**
 * Version 1 kept native identifiers in AsyncStorage. Migrate once by reconciling
 * both that record and Binario notification metadata, then use the native
 * scheduler as the sole source of truth.
 */
async function migrateLegacyState(): Promise<Set<number>> {
  const raw = await AsyncStorage.getItem(legacyStorageKey);
  const storedIdentifiers =
    raw === null ? new Set<string>() : legacyIdentifiers(raw);
  const migratedRouteIds = new Set<number>();
  const scheduled = await Notifications.getAllScheduledNotificationsAsync();
  for (const request of scheduled) {
    const routeId = routeIdFromRequest(request);
    const isLegacyBinarioReminder =
      storedIdentifiers.has(request.identifier) ||
      (routeId !== null &&
        request.content.title === notificationTitle &&
        request.identifier !== reminderIdentifier(routeId));
    if (!isLegacyBinarioReminder) continue;
    await Notifications.cancelScheduledNotificationAsync(request.identifier);
    if (routeId !== null) migratedRouteIds.add(routeId);
  }
  if (raw !== null) await AsyncStorage.removeItem(legacyStorageKey);
  return migratedRouteIds;
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
  )
    return null;
  return departure.getTime();
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

export function cancelPlannedRouteReminder(routeId: number) {
  return serializeMutation(async () => {
    if (!Number.isSafeInteger(routeId) || routeId <= 0) return false;
    const migrated = await migrateLegacyState();
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
    return migrated.has(routeId) || hadScheduledReminder;
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
  const departureAt = validRoute ? localDepartureEpoch(route) : null;
  if (departureAt === null || route.plannedReminderMinutes === null) {
    return "invalid";
  }
  const fireAt = departureAt - route.plannedReminderMinutes * 60_000;
  if (!Number.isFinite(fireAt) || fireAt <= Date.now()) return "invalid";

  return serializeMutation(async () => {
    await migrateLegacyState();
    const current = await Notifications.getPermissionsAsync();
    const permission = current.granted
      ? current
      : await Notifications.requestPermissionsAsync();
    if (!permission.granted) return "permission-denied" as const;
    if (fireAt <= Date.now()) return "invalid" as const;

    await Notifications.scheduleNotificationAsync({
      identifier: reminderIdentifier(route.id),
      content: {
        title: notificationTitle,
        body: `${route.origin} → ${route.destination} alle ${route.travelTime}. Orario programmato: non realtime.`,
        data: { routeId: route.id },
      },
      trigger: {
        type: Notifications.SchedulableTriggerInputTypes.DATE,
        date: new Date(fireAt),
      },
    });
    return "scheduled" as const;
  });
}
