export const LOCAL_JOURNEY_ITINERARIES_STORAGE_KEY = "binario-local-itineraries-v1";
export const LOCAL_JOURNEY_ITINERARY_SCHEMA_VERSION = 1 as const;
export const MAX_LOCAL_JOURNEY_ITINERARIES = 12;
export const MAX_ITINERARY_SEGMENTS = 4;
export const MAX_GUIDED_TEXT_IMPORT_CHARACTERS = 4_000;

export type LocalJourneyItinerary = {
  schemaVersion: typeof LOCAL_JOURNEY_ITINERARY_SCHEMA_VERSION;
  id: string;
  label: string;
  routeIds: string[];
  createdAt: string;
};

export type ItineraryRoute = {
  id: string;
  origin: string;
  destination: string;
  travelDate: string;
  travelTime: string;
  archivedAt?: string | null;
  completedAt?: string | null;
  checklist?: Array<{ completed: boolean }>;
};

export type ItineraryChangeSummary = {
  segments: ItineraryRoute[];
  nextSegment: ItineraryRoute | null;
  followingSegment: ItineraryRoute | null;
  remainingChecklistItems: number;
};

export type GuidedTextJourneyDraft = {
  origin: string;
  destination: string;
  travelDate: string;
  travelTime: string;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function isIsoTimestamp(value: unknown): value is string {
  return typeof value === "string" && Number.isFinite(Date.parse(value));
}

function isDate(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split("-").map(Number);
  const date = new Date(Date.UTC(year!, month! - 1, day!));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month! - 1 && date.getUTCDate() === day;
}

function isTime(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{2}:\d{2}$/.test(value)) return false;
  const [hours, minutes] = value.split(":").map(Number);
  return hours! >= 0 && hours! < 24 && minutes! >= 0 && minutes! < 60;
}

function normalizeText(value: unknown, maxLength: number) {
  return typeof value === "string" ? value.trim().replace(/\s+/g, " ").slice(0, maxLength) : "";
}

function normalizeRouteIds(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return Array.from(new Set(value.filter((id): id is string => typeof id === "string" && id.trim().length > 0).map(id => id.trim()))).slice(0, MAX_ITINERARY_SEGMENTS);
}

export function createLocalJourneyItinerary(value: Omit<LocalJourneyItinerary, "schemaVersion">): LocalJourneyItinerary | null {
  const id = normalizeText(value.id, 120);
  const routeIds = normalizeRouteIds(value.routeIds);
  const label = normalizeText(value.label, 96);
  if (!id || routeIds.length < 2 || !isIsoTimestamp(value.createdAt)) return null;
  return { schemaVersion: LOCAL_JOURNEY_ITINERARY_SCHEMA_VERSION, id, label: label || "Itinerario ferroviario", routeIds, createdAt: value.createdAt };
}

export function parseLocalJourneyItineraries(raw: string | null): LocalJourneyItinerary[] {
  try {
    const parsed: unknown = JSON.parse(raw ?? "[]");
    if (!Array.isArray(parsed)) return [];
    return parsed.map(item => {
      if (!isRecord(item) || item.schemaVersion !== LOCAL_JOURNEY_ITINERARY_SCHEMA_VERSION) return null;
      return createLocalJourneyItinerary({ id: item.id as string, label: item.label as string, routeIds: item.routeIds as string[], createdAt: item.createdAt as string });
    }).filter((item): item is LocalJourneyItinerary => item !== null).slice(0, MAX_LOCAL_JOURNEY_ITINERARIES);
  } catch {
    return [];
  }
}

export function itinerarySegments(itinerary: LocalJourneyItinerary, routes: readonly ItineraryRoute[]): ItineraryRoute[] {
  const byId = new Map(routes.filter(route => !route.archivedAt).map(route => [route.id, route]));
  return itinerary.routeIds.map(id => byId.get(id)).filter((route): route is ItineraryRoute => Boolean(route)).sort((left, right) => `${left.travelDate}T${left.travelTime}`.localeCompare(`${right.travelDate}T${right.travelTime}`));
}

export function deriveItineraryChangeSummary(itinerary: LocalJourneyItinerary, routes: readonly ItineraryRoute[], now = new Date()): ItineraryChangeSummary {
  const segments = itinerarySegments(itinerary, routes);
  const nowTime = now.getTime();
  const nextIndex = segments.findIndex(segment => {
    const scheduledAt = new Date(`${segment.travelDate}T${segment.travelTime}:00`).getTime();
    return Number.isFinite(scheduledAt) && scheduledAt >= nowTime && !segment.completedAt;
  });
  const nextSegment = nextIndex >= 0 ? segments[nextIndex]! : null;
  const followingSegment = nextIndex >= 0 ? segments[nextIndex + 1] ?? null : null;
  return {
    segments,
    nextSegment,
    followingSegment,
    remainingChecklistItems: nextSegment?.checklist?.filter(item => !item.completed).length ?? 0,
  };
}

function parseItalianDate(value: string): string | null {
  const iso = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  const local = /^(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4})$/.exec(value);
  const normalized = iso ? `${iso[1]}-${iso[2]}-${iso[3]}` : local ? `${local[3]}-${local[2]!.padStart(2, "0")}-${local[1]!.padStart(2, "0")}` : null;
  return normalized && isDate(normalized) ? normalized : null;
}

function parseTimeValue(value: string): string | null {
  const match = /^(\d{1,2})[:.](\d{2})$/.exec(value);
  if (!match) return null;
  const normalized = `${match[1]!.padStart(2, "0")}:${match[2]}`;
  return isTime(normalized) ? normalized : null;
}

function parseGuidedTextLine(raw: string): GuidedTextJourneyDraft | null {
  const value = normalizeText(raw, 480);
  const dateMatch = value.match(/(\d{4}-\d{2}-\d{2}|\d{1,2}[/.\-]\d{1,2}[/.\-]\d{4})/);
  const timeMatch = value.match(/(?:\balle\s*|\bore\s*|@\s*|\s)(\d{1,2}[:.]\d{2})\s*$/i);
  if (!dateMatch || !timeMatch || dateMatch.index === undefined || timeMatch.index === undefined || timeMatch.index <= dateMatch.index) return null;
  const travelDate = parseItalianDate(dateMatch[1]!);
  const travelTime = parseTimeValue(timeMatch[1]!);
  if (!travelDate || !travelTime) return null;
  const routePart = value.slice(0, dateMatch.index).replace(/[·,;\-–—]+\s*$/, "").trim();
  const arrow = routePart.match(/^(.{2,96}?)\s*(?:→|->)\s*(.{2,96}?)$/);
  const verbal = routePart.match(/^da\s+(.{2,96}?)\s+(?:a|verso)\s+(.{2,96}?)$/i);
  const match = arrow ?? verbal;
  if (!match) return null;
  const origin = normalizeText(match[1], 96);
  const destination = normalizeText(match[2], 96);
  if (origin.length < 2 || destination.length < 2 || origin.localeCompare(destination, "it", { sensitivity: "base" }) === 0) return null;
  return { origin, destination, travelDate, travelTime };
}

/** Accetta solo righe complete e strutturate: nessuna data, ora o stazione viene dedotta dal contesto. */
export function parseGuidedTextJourneyImports(raw: string): GuidedTextJourneyDraft[] {
  if (typeof raw !== "string" || raw.length === 0 || raw.length > MAX_GUIDED_TEXT_IMPORT_CHARACTERS) return [];
  const results: GuidedTextJourneyDraft[] = [];
  for (const line of raw.split(/[\r\n;]+/)) {
    const draft = parseGuidedTextLine(line);
    if (!draft) continue;
    const key = `${draft.origin.toLocaleLowerCase("it")}|${draft.destination.toLocaleLowerCase("it")}|${draft.travelDate}|${draft.travelTime}`;
    if (results.some(candidate => `${candidate.origin.toLocaleLowerCase("it")}|${candidate.destination.toLocaleLowerCase("it")}|${candidate.travelDate}|${candidate.travelTime}` === key)) continue;
    results.push(draft);
    if (results.length === MAX_ITINERARY_SEGMENTS) break;
  }
  return results;
}
