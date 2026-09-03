import { defaultJourneyExtension, normalizeJourneyExtension, type LocalJourneyExtension } from "./localJourneyToolkit";

export type LocalGuestRoute = {
  id: string;
  origin: string;
  destination: string;
  originStationCode: string | null;
  destinationStationCode: string | null;
  travelDate: string;
  travelTime: string;
} & LocalJourneyExtension;

const MAX_LOCAL_GUEST_ROUTES = 40;
export const LOCAL_GUEST_ROUTES_STORAGE_KEY = "binario-local-routes-v1";

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string";
}

function isLocalGuestRouteBase(value: unknown): value is Omit<LocalGuestRoute, keyof LocalJourneyExtension> {
  if (!value || typeof value !== "object") return false;
  const route = value as Omit<LocalGuestRoute, keyof LocalJourneyExtension>;
  return typeof route.id === "string" && typeof route.origin === "string" && typeof route.destination === "string" && typeof route.travelDate === "string" && typeof route.travelTime === "string" && isNullableString(route.originStationCode) && isNullableString(route.destinationStationCode);
}

function normalizeLocalGuestRoute(value: unknown, fallbackTimestamp: string): LocalGuestRoute | null {
  if (!isLocalGuestRouteBase(value)) return null;
  return { ...value, ...normalizeJourneyExtension(value, fallbackTimestamp) };
}

export function parseLocalGuestRoutes(raw: string | null): LocalGuestRoute[] {
  try {
    const parsed: unknown = JSON.parse(raw ?? "[]");
    const fallbackTimestamp = new Date().toISOString();
    return Array.isArray(parsed) ? parsed.map(item => normalizeLocalGuestRoute(item, fallbackTimestamp)).filter((route): route is LocalGuestRoute => route !== null).slice(0, MAX_LOCAL_GUEST_ROUTES) : [];
  } catch { return []; }
}

export function prependLocalGuestRoute(routes: LocalGuestRoute[], route: LocalGuestRoute): LocalGuestRoute[] {
  return [route, ...routes.filter(item => !(item.origin === route.origin && item.destination === route.destination && item.travelDate === route.travelDate && item.travelTime === route.travelTime))].slice(0, MAX_LOCAL_GUEST_ROUTES);
}
