import AsyncStorage from "@react-native-async-storage/async-storage";
import { createExplicitRecurringLocalRouteDrafts, createLocalPlannedRoute, isLocalPlannedRoute, setLocalJourneyChecklistItem, setLocalPlannedRouteCompleted, setLocalPlannedRouteLabel, setLocalPlannedRoutePinned, sortLocalPlannedRoutes, type ExplicitLocalJourneyRecurrence, type LocalJourneyChecklistItem, type LocalPlannedRoute, type LocalRouteDraft } from "@/lib/local-route-contract";
import type { ManualRouteTransferRoute } from "@/lib/manual-route-transfer-contract";

const storageKey = "binario-local-planned-routes-v1";
const maxLocalRoutes = 40;

export type { LocalPlannedRoute, LocalRouteDraft } from "@/lib/local-route-contract";

export async function loadLocalPlannedRoutes(): Promise<LocalPlannedRoute[]> {
  try {
    const parsed: unknown = JSON.parse((await AsyncStorage.getItem(storageKey)) ?? "[]");
    return Array.isArray(parsed) ? sortLocalPlannedRoutes(parsed.filter(isLocalPlannedRoute)).slice(0, maxLocalRoutes) : [];
  } catch { return []; }
}

async function save(routes: LocalPlannedRoute[]) { await AsyncStorage.setItem(storageKey, JSON.stringify(sortLocalPlannedRoutes(routes).slice(0, maxLocalRoutes))); }

export async function addLocalPlannedRoute(draft: LocalRouteDraft, now = Date.now()): Promise<LocalPlannedRoute | null> {
  const routes = await loadLocalPlannedRoutes();
  const route = createLocalPlannedRoute(draft, now, now);
  if (!route || routes.some(existing => existing.id === route.id)) return null;
  await save([route, ...routes]);
  return route;
}

export async function removeLocalPlannedRoute(routeId: number) {
  const routes = await loadLocalPlannedRoutes();
  const next = routes.filter(route => route.id !== routeId);
  if (next.length === routes.length) return false;
  await save(next);
  return true;
}

export async function setLocalPlannedRouteChecklistItem(routeId: number, itemId: LocalJourneyChecklistItem["id"], completed: boolean) {
  const routes = await loadLocalPlannedRoutes();
  const route = routes.find(item => item.id === routeId);
  if (!route) return false;
  await save(routes.map(item => item.id === routeId ? setLocalJourneyChecklistItem(item, itemId, completed) : item));
  return true;
}

/** Salva soltanto la decisione manuale dell’utente; non attesta l’arrivo né l’esecuzione del servizio ferroviario. */
export async function setLocalPlannedRouteCompletion(routeId: number, completed: boolean, now = Date.now()) {
  const routes = await loadLocalPlannedRoutes();
  if (!routes.some(route => route.id === routeId)) return false;
  await save(routes.map(route => route.id === routeId ? setLocalPlannedRouteCompleted(route, completed, now) : route));
  return true;
}

/** Etichetta libera ma circoscritta alla tratta locale: non entra nell’archivio esportato o in un provider. */
export async function setLocalPlannedRouteCustomLabel(routeId: number, label: string | null) {
  const routes = await loadLocalPlannedRoutes();
  if (!routes.some(route => route.id === routeId)) return false;
  await save(routes.map(route => route.id === routeId ? setLocalPlannedRouteLabel(route, label) : route));
  return true;
}

export async function setLocalPlannedRoutePriority(routeId: number, pinned: boolean) {
  const routes = await loadLocalPlannedRoutes();
  if (!routes.some(route => route.id === routeId)) return false;
  await save(routes.map(route => route.id === routeId ? setLocalPlannedRoutePinned(route, pinned) : route));
  return true;
}

/** Il riordino esplicito agisce sul solo archivio locale e non modifica orari ferroviari o promemoria. */
export async function moveLocalPlannedRoute(routeId: number, direction: "up" | "down") {
  const ordered = sortLocalPlannedRoutes(await loadLocalPlannedRoutes());
  const index = ordered.findIndex(route => route.id === routeId);
  const target = index + (direction === "up" ? -1 : 1);
  if (index < 0 || target < 0 || target >= ordered.length) return false;
  [ordered[index], ordered[target]] = [ordered[target], ordered[index]];
  await save(ordered.map((route, manualOrder) => ({ ...route, manualOrder })));
  return true;
}

type ComparablePlannedRoute = Pick<LocalRouteDraft, "origin" | "originStationCode" | "destination" | "destinationStationCode" | "travelDate" | "travelTime">;

function samePlannedRoute(left: LocalPlannedRoute, right: ComparablePlannedRoute) {
  const station = (code: string | null | undefined, label: string) => code?.trim().toUpperCase() || label.trim().toLocaleLowerCase("it");
  return station(left.originStationCode, left.origin) === station(right.originStationCode, right.origin) && station(left.destinationStationCode, left.destination) === station(right.destinationStationCode, right.destination) && left.travelDate === right.travelDate && left.travelTime === right.travelTime;
}

/** Copie pianificate soltanto dopo scelta di giorni e numero; nessun promemoria è creato in questa operazione. */
export async function addExplicitRecurringLocalPlannedRoutes(routeId: number, recurrence: ExplicitLocalJourneyRecurrence, now = Date.now()): Promise<number> {
  const current = await loadLocalPlannedRoutes();
  const source = current.find(route => route.id === routeId);
  if (!source) return 0;
  const drafts = createExplicitRecurringLocalRouteDrafts(source, recurrence);
  const next = [...current];
  let nextId = now;
  for (const draft of drafts) {
    if (next.length >= maxLocalRoutes || next.some(route => samePlannedRoute(route, draft))) continue;
    while (next.some(route => route.id === nextId)) nextId += 1;
    const copy = createLocalPlannedRoute(draft, nextId, now);
    if (!copy) continue;
    next.push(copy);
    nextId += 1;
  }
  const added = next.length - current.length;
  if (added > 0) await save(next);
  return added;
}

/** Importazione additiva richiamata solo dopo anteprima e selezione di un archivio Binario scelto dall’utente. */
export async function importSelectedManualPlannedRoutes(routes: readonly ManualRouteTransferRoute[], now = Date.now()): Promise<number> {
  const current = await loadLocalPlannedRoutes();
  const next = [...current];
  let nextId = now;
  for (const draft of routes) {
    if (next.some(local => samePlannedRoute(local, draft))) continue;
    while (next.some(local => local.id === nextId)) nextId += 1;
    const local = createLocalPlannedRoute(draft, nextId, now);
    if (!local || next.length >= maxLocalRoutes) continue;
    next.push(local);
    nextId += 1;
  }
  const imported = next.length - current.length;
  if (imported > 0) await save(next);
  return imported;
}

export async function clearLocalPlannedRoutes() { await AsyncStorage.removeItem(storageKey); }
