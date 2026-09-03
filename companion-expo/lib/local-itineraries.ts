import AsyncStorage from "@react-native-async-storage/async-storage";
import { normalizeLocalJourneyItineraryDraft, type LocalJourneyItineraryDraft } from "@/lib/local-journey-itineraries";

const storageKey = "binario-local-itineraries-v1";
const schemaVersion = 1 as const;
const maxLocalItineraries = 12;

export type SavedLocalJourneyItinerary = LocalJourneyItineraryDraft & {
  schemaVersion: typeof schemaVersion;
  id: string;
  createdAtUtcMs: number;
};

function isSavedItinerary(value: unknown): value is SavedLocalJourneyItinerary {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<SavedLocalJourneyItinerary>;
  return candidate.schemaVersion === schemaVersion && typeof candidate.id === "string" && candidate.id.length > 0 && typeof candidate.createdAtUtcMs === "number" && Number.isSafeInteger(candidate.createdAtUtcMs) && candidate.createdAtUtcMs > 0 && normalizeLocalJourneyItineraryDraft({ label: candidate.label ?? "", routeIds: candidate.routeIds ?? [] }) !== null;
}

export async function loadLocalJourneyItineraries(): Promise<SavedLocalJourneyItinerary[]> {
  try {
    const parsed: unknown = JSON.parse((await AsyncStorage.getItem(storageKey)) ?? "[]");
    return Array.isArray(parsed) ? parsed.filter(isSavedItinerary).sort((left, right) => right.createdAtUtcMs - left.createdAtUtcMs).slice(0, maxLocalItineraries) : [];
  } catch { return []; }
}

async function save(itineraries: SavedLocalJourneyItinerary[]) {
  await AsyncStorage.setItem(storageKey, JSON.stringify([...itineraries].sort((left, right) => right.createdAtUtcMs - left.createdAtUtcMs).slice(0, maxLocalItineraries)));
}

export async function addLocalJourneyItinerary(draft: LocalJourneyItineraryDraft, now = Date.now()): Promise<SavedLocalJourneyItinerary | null> {
  const normalized = normalizeLocalJourneyItineraryDraft(draft);
  if (!normalized || !Number.isSafeInteger(now) || now < 1) return null;
  const itineraries = await loadLocalJourneyItineraries();
  if (itineraries.some(item => item.routeIds.length === normalized.routeIds.length && item.routeIds.every((routeId, index) => routeId === normalized.routeIds[index]))) return null;
  const itinerary = { ...normalized, schemaVersion, id: `itinerary-${now}-${Math.random().toString(36).slice(2, 8)}`, createdAtUtcMs: now };
  await save([itinerary, ...itineraries]);
  return itinerary;
}

export async function removeLocalJourneyItinerary(id: string) {
  const itineraries = await loadLocalJourneyItineraries();
  const next = itineraries.filter(itinerary => itinerary.id !== id);
  if (next.length === itineraries.length) return false;
  await save(next);
  return true;
}

/** Rimuove dal gruppo solo tratte non più presenti; i gruppi sotto due tappe vengono eliminati. */
export async function reconcileLocalJourneyItineraries(routeIds: readonly number[]) {
  const available = new Set(routeIds);
  const itineraries = await loadLocalJourneyItineraries();
  const next = itineraries.map(itinerary => ({ ...itinerary, routeIds: itinerary.routeIds.filter(id => available.has(id)) })).filter(itinerary => itinerary.routeIds.length >= 2);
  const changed = next.length !== itineraries.length || next.some((item, index) => item.routeIds.length !== itineraries[index]?.routeIds.length || item.routeIds.some((routeId, routeIndex) => routeId !== itineraries[index]?.routeIds[routeIndex]));
  if (changed) await save(next);
  return next;
}

export async function clearLocalJourneyItineraries() { await AsyncStorage.removeItem(storageKey); }
