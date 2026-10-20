import type { LocalPlannedRoute } from "@/lib/local-route-contract";
import { createTranslator, type CompanionLocale } from "@/lib/i18n";

export const maxLocalItinerarySegments = 4;
export const maxGuidedTextImportCharacters = 4_000;

export type LocalJourneyItineraryDraft = {
  label: string;
  routeIds: number[];
};

export type LocalItineraryChangeSummary = {
  segments: LocalPlannedRoute[];
  nextSegment: LocalPlannedRoute | null;
  followingSegment: LocalPlannedRoute | null;
  remainingChecklistItems: number;
};

export type GuidedTextLocalRouteDraft = {
  origin: string;
  destination: string;
  travelDate: string;
  travelTime: string;
  plannedReminderMinutes: null;
};

function cleanText(value: string, maxLength: number) { return value.trim().replace(/\s+/g, " ").slice(0, maxLength).trim(); }

function validDate(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return date.getFullYear() === Number(match[1]) && date.getMonth() === Number(match[2]) - 1 && date.getDate() === Number(match[3]);
}

function validTime(value: string) { return /^([01]\d|2[0-3]):[0-5]\d$/.test(value); }

export function normalizeLocalJourneyItineraryDraft(value: LocalJourneyItineraryDraft, locale?: CompanionLocale): LocalJourneyItineraryDraft | null {
  if (!value || typeof value !== "object" || !Array.isArray(value.routeIds)) return null;
  const routeIds = Array.from(new Set(value.routeIds.filter(id => Number.isSafeInteger(id) && id > 0))).slice(0, maxLocalItinerarySegments);
  const label = cleanText(value.label, 96);
  return routeIds.length >= 2 ? { label: label || createTranslator(locale)("itinerary.defaultLabel"), routeIds } : null;
}

export function deriveLocalItineraryChangeSummary(routeIds: readonly number[], routes: readonly LocalPlannedRoute[], now = Date.now()): LocalItineraryChangeSummary {
  const byId = new Map(routes.map(route => [route.id, route]));
  const segments = routeIds.map(id => byId.get(id)).filter((route): route is LocalPlannedRoute => Boolean(route)).sort((left, right) => `${left.travelDate}T${left.travelTime}`.localeCompare(`${right.travelDate}T${right.travelTime}`));
  const nextIndex = segments.findIndex(route => {
    const scheduled = new Date(`${route.travelDate}T${route.travelTime}:00`).getTime();
    return Number.isFinite(scheduled) && scheduled >= now;
  });
  const nextSegment = nextIndex >= 0 ? segments[nextIndex]! : null;
  return { segments, nextSegment, followingSegment: nextIndex >= 0 ? segments[nextIndex + 1] ?? null : null, remainingChecklistItems: nextSegment?.checklist?.filter(item => !item.completed).length ?? 0 };
}

function parseDate(value: string) {
  const iso = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  const local = /^(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4})$/.exec(value);
  const normalized = iso ? `${iso[1]}-${iso[2]}-${iso[3]}` : local ? `${local[3]}-${local[2]!.padStart(2, "0")}-${local[1]!.padStart(2, "0")}` : null;
  return normalized && validDate(normalized) ? normalized : null;
}

function parseTime(value: string) {
  const match = /^(\d{1,2})[:.](\d{2})$/.exec(value);
  const normalized = match ? `${match[1]!.padStart(2, "0")}:${match[2]}` : null;
  return normalized && validTime(normalized) ? normalized : null;
}

function parseLine(raw: string): GuidedTextLocalRouteDraft | null {
  const value = cleanText(raw, 480);
  const dateMatch = value.match(/(\d{4}-\d{2}-\d{2}|\d{1,2}[/.\-]\d{1,2}[/.\-]\d{4})/);
  const timeMatch = value.match(/(?:\balle\s*|\bore\s*|@\s*|\s)(\d{1,2}[:.]\d{2})\s*$/i);
  if (!dateMatch || !timeMatch || dateMatch.index === undefined || timeMatch.index === undefined || timeMatch.index <= dateMatch.index) return null;
  const travelDate = parseDate(dateMatch[1]!);
  const travelTime = parseTime(timeMatch[1]!);
  if (!travelDate || !travelTime) return null;
  const routePart = value.slice(0, dateMatch.index).replace(/[·,;\-–—]+\s*$/, "").trim();
  const match = routePart.match(/^(.{2,96}?)\s*(?:→|->)\s*(.{2,96}?)$/) ?? routePart.match(/^da\s+(.{2,96}?)\s+(?:a|verso)\s+(.{2,96}?)$/i);
  if (!match) return null;
  const origin = cleanText(match[1]!, 96);
  const destination = cleanText(match[2]!, 96);
  if (origin.length < 2 || destination.length < 2 || origin.localeCompare(destination, "it", { sensitivity: "base" }) === 0) return null;
  return { origin, destination, travelDate, travelTime, plannedReminderMinutes: null };
}

/** Legge soltanto righe complete che l’utente incolla o scrive volontariamente; non interpreta link, file o contenuto parziale. */
export function parseGuidedTextLocalRouteImports(raw: string): GuidedTextLocalRouteDraft[] {
  if (typeof raw !== "string" || raw.length === 0 || raw.length > maxGuidedTextImportCharacters) return [];
  const results: GuidedTextLocalRouteDraft[] = [];
  for (const line of raw.split(/[\r\n;]+/)) {
    const draft = parseLine(line);
    if (!draft) continue;
    const key = `${draft.origin.toLocaleLowerCase("it")}|${draft.destination.toLocaleLowerCase("it")}|${draft.travelDate}|${draft.travelTime}`;
    if (results.some(candidate => `${candidate.origin.toLocaleLowerCase("it")}|${candidate.destination.toLocaleLowerCase("it")}|${candidate.travelDate}|${candidate.travelTime}` === key)) continue;
    results.push(draft);
    if (results.length === maxLocalItinerarySegments) break;
  }
  return results;
}
