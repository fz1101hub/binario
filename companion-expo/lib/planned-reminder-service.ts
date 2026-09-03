import AsyncStorage from "@react-native-async-storage/async-storage";
import * as Notifications from "expo-notifications";
import type { LocalPlannedRoute } from "@/lib/local-route-contract";

const storageKey = "binario.planned-route-reminders.v1";
type ReminderMap = Record<string, string>;
async function loadMap(): Promise<ReminderMap> { try { const value: unknown = JSON.parse((await AsyncStorage.getItem(storageKey)) ?? "{}"); return value && typeof value === "object" ? value as ReminderMap : {}; } catch { return {}; } }
async function saveMap(value: ReminderMap) { await AsyncStorage.setItem(storageKey, JSON.stringify(value)); }
export async function listScheduledRouteIds() { return Object.keys(await loadMap()).map(Number).filter(Number.isSafeInteger); }
export async function cancelPlannedRouteReminder(routeId: number) { const map = await loadMap(); const id = map[String(routeId)]; if (!id) return false; try { await Notifications.cancelScheduledNotificationAsync(id); } catch { /* Lo stato locale viene comunque rimosso. */ } delete map[String(routeId)]; await saveMap(map); return true; }
export async function schedulePlannedRouteReminder(route: LocalPlannedRoute): Promise<"scheduled" | "permission-denied" | "invalid"> {
  if (route.plannedReminderMinutes === null) return "invalid";
  const fireAt = new Date(`${route.travelDate}T${route.travelTime}:00`).getTime() - route.plannedReminderMinutes * 60_000;
  if (!Number.isFinite(fireAt) || fireAt <= Date.now()) return "invalid";
  const current = await Notifications.getPermissionsAsync(); const permission = current.granted ? current : await Notifications.requestPermissionsAsync();
  if (!permission.granted) return "permission-denied";
  await cancelPlannedRouteReminder(route.id);
  const id = await Notifications.scheduleNotificationAsync({ content: { title: "Binario · tratta pianificata", body: `${route.name} alle ${route.travelTime}. Orario programmato: non realtime.`, data: { routeId: route.id } }, trigger: { type: Notifications.SchedulableTriggerInputTypes.DATE, date: new Date(fireAt) } });
  const map = await loadMap(); map[String(route.id)] = id; await saveMap(map); return "scheduled";
}
