import type { CompanionPlannedRoute } from "@/lib/planned-route-reminder";

export type LocalRouteDraft = {
  origin: string;
  originStationCode?: string | null;
  destination: string;
  destinationStationCode?: string | null;
  travelDate: string;
  travelTime: string;
  plannedReminderMinutes: number | null;
  checklist?: LocalJourneyChecklistItem[];
};

export type LocalJourneyChecklistItemId = "time-margin" | "documents" | "belongings" | "connection" | "bike" | "assistance";
export type LocalJourneyChecklistItem = { id: LocalJourneyChecklistItemId; completed: boolean };
export const localJourneyChecklistLabels: Record<LocalJourneyChecklistItem["id"], string> = {
  "time-margin": "Verifica il margine di anticipo",
  documents: "Prepara titolo di viaggio e documenti",
  belongings: "Controlla gli effetti personali",
  connection: "Verifica il tempo per la coincidenza",
  bike: "Verifica condizioni e preparazione della bici",
  assistance: "Conferma l’assistenza richiesta in stazione",
};
const checklistIds = Object.keys(localJourneyChecklistLabels) as LocalJourneyChecklistItem["id"][];
const defaultChecklistIds: LocalJourneyChecklistItemId[] = ["time-margin", "documents", "belongings"];

export type LocalJourneyChecklistPreset = { id: "pendolare" | "coincidenza" | "bici" | "assistenza"; label: string; itemIds: LocalJourneyChecklistItemId[] };
export const localJourneyChecklistPresets: readonly LocalJourneyChecklistPreset[] = [
  { id: "pendolare", label: "Pendolare", itemIds: ["time-margin", "documents", "belongings"] },
  { id: "coincidenza", label: "Coincidenza", itemIds: ["time-margin", "connection", "documents", "belongings"] },
  { id: "bici", label: "Bici sul treno", itemIds: ["time-margin", "documents", "bike", "belongings"] },
  { id: "assistenza", label: "Assistenza in stazione", itemIds: ["time-margin", "assistance", "documents", "belongings"] },
];

export type LocalPlannedRoute = CompanionPlannedRoute & {
  source: "device";
  createdAtUtcMs: number;
  completedAtUtcMs?: number | null;
  originStationCode: string | null;
  destinationStationCode: string | null;
  checklist?: LocalJourneyChecklistItem[];
  /** Metadati personali locali: non sono inviati a provider ferroviari, export o sync. */
  customLabel?: string | null;
  pinned?: boolean;
  manualOrder?: number | null;
};

export type ControlledLocalRouteDraft = Omit<LocalRouteDraft, "travelDate" | "travelTime" | "checklist"> & {
  travelDate: "";
  travelTime: "";
  checklist: LocalJourneyChecklistItem[];
};

export const localJourneyWeekdays = [
  { id: 1, label: "Lun" }, { id: 2, label: "Mar" }, { id: 3, label: "Mer" }, { id: 4, label: "Gio" }, { id: 5, label: "Ven" }, { id: 6, label: "Sab" }, { id: 0, label: "Dom" },
] as const;
export type LocalJourneyWeekday = typeof localJourneyWeekdays[number]["id"];
export type ExplicitLocalJourneyRecurrence = { weekdays: LocalJourneyWeekday[]; occurrences: number };
export const maxExplicitLocalJourneyOccurrences = 8;

function text(value: string, max: number) {
  return value.trim().replace(/\s+/g, " ").slice(0, max);
}

function isDate(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return date.getFullYear() === Number(match[1]) && date.getMonth() === Number(match[2]) - 1 && date.getDate() === Number(match[3]);
}

function normalizeChecklist(value: unknown): LocalJourneyChecklistItem[] {
  const existing = Array.isArray(value) ? value.filter((item): item is LocalJourneyChecklistItem => Boolean(item) && typeof item === "object" && checklistIds.includes((item as LocalJourneyChecklistItem).id) && typeof (item as LocalJourneyChecklistItem).completed === "boolean") : [];
  const included = existing.length ? checklistIds.filter(id => existing.some(item => item.id === id)) : defaultChecklistIds;
  return included.map(id => existing.find(item => item.id === id) ?? { id, completed: false });
}

export function createLocalPlannedRoute(draft: LocalRouteDraft, id: number, now = Date.now()): LocalPlannedRoute | null {
  const origin = text(draft.origin, 96);
  const destination = text(draft.destination, 96);
  const originStationCode = draft.originStationCode ? text(draft.originStationCode, 64) : null;
  const destinationStationCode = draft.destinationStationCode ? text(draft.destinationStationCode, 64) : null;
  const reminder = draft.plannedReminderMinutes === undefined ? 15 : draft.plannedReminderMinutes;
  const validReminder = reminder === null || (Number.isInteger(reminder) && reminder >= 0 && reminder <= 1_440);
  if (!Number.isSafeInteger(id) || id < 1 || origin.length < 2 || destination.length < 2 || origin === destination || !isDate(draft.travelDate) || !/^([01]\d|2[0-3]):[0-5]\d$/.test(draft.travelTime) || !validReminder || (originStationCode !== null && originStationCode.length < 1) || (destinationStationCode !== null && destinationStationCode.length < 1)) return null;
  return { id, name: `${origin} → ${destination}`, origin, originStationCode, destination, destinationStationCode, travelDate: draft.travelDate, travelTime: draft.travelTime, plannedReminderMinutes: reminder, checklist: normalizeChecklist(draft.checklist), source: "device", createdAtUtcMs: now, completedAtUtcMs: null, customLabel: null, pinned: false, manualOrder: null };
}

export function isLocalPlannedRoute(value: unknown): value is LocalPlannedRoute {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<LocalPlannedRoute>;
  const completion = candidate.completedAtUtcMs;
  const label = candidate.customLabel;
  const order = candidate.manualOrder;
  return candidate.source === "device" && typeof candidate.createdAtUtcMs === "number" && (completion === undefined || completion === null || (Number.isSafeInteger(completion) && completion >= 0)) && (label === undefined || label === null || (typeof label === "string" && text(label, 80).length > 0 && label.length <= 80)) && (candidate.pinned === undefined || typeof candidate.pinned === "boolean") && (order === undefined || order === null || (Number.isSafeInteger(order) && order >= 0 && order < 10_000)) && createLocalPlannedRoute({ origin: candidate.origin ?? "", originStationCode: candidate.originStationCode ?? null, destination: candidate.destination ?? "", destinationStationCode: candidate.destinationStationCode ?? null, travelDate: candidate.travelDate ?? "", travelTime: candidate.travelTime ?? "", plannedReminderMinutes: candidate.plannedReminderMinutes ?? null, checklist: candidate.checklist }, candidate.id ?? 0, candidate.createdAtUtcMs) !== null;
}

export function displayLocalPlannedRouteName(route: Pick<LocalPlannedRoute, "name" | "customLabel">) { return route.customLabel?.trim() || route.name; }

export function setLocalPlannedRouteLabel(route: LocalPlannedRoute, customLabel: string | null): LocalPlannedRoute {
  const normalized = customLabel === null ? null : text(customLabel, 80);
  return { ...route, customLabel: normalized || null };
}

export function setLocalPlannedRoutePinned(route: LocalPlannedRoute, pinned: boolean): LocalPlannedRoute { return { ...route, pinned }; }

export function remainingLocalJourneyChecklistItems(route: Pick<LocalPlannedRoute, "checklist">) {
  return normalizeChecklist(route.checklist).filter(item => !item.completed).length;
}

export function setLocalJourneyChecklistItem(route: LocalPlannedRoute, itemId: LocalJourneyChecklistItem["id"], completed: boolean): LocalPlannedRoute {
  return { ...route, checklist: normalizeChecklist(route.checklist).map(item => item.id === itemId ? { ...item, completed } : item) };
}

/** Il completamento descrive solo la scelta dell’utente, non l’esito o la circolazione effettiva del treno. */
export function setLocalPlannedRouteCompleted(route: LocalPlannedRoute, completed: boolean, now = Date.now()): LocalPlannedRoute {
  return { ...route, completedAtUtcMs: completed && Number.isSafeInteger(now) && now >= 0 ? now : null };
}

/** Genera al massimo otto copie future in giorni scelti dall’utente; non crea notifiche e non modifica la tratta di origine. */
export function createExplicitRecurringLocalRouteDrafts(route: Pick<LocalPlannedRoute, "origin" | "originStationCode" | "destination" | "destinationStationCode" | "travelDate" | "travelTime" | "plannedReminderMinutes" | "checklist">, recurrence: ExplicitLocalJourneyRecurrence): LocalRouteDraft[] {
  const weekdays = [...new Set(recurrence.weekdays)].filter((weekday): weekday is LocalJourneyWeekday => localJourneyWeekdays.some(item => item.id === weekday));
  if (!Number.isInteger(recurrence.occurrences) || recurrence.occurrences < 1 || recurrence.occurrences > maxExplicitLocalJourneyOccurrences || weekdays.length === 0 || !isDate(route.travelDate)) return [];
  const cursor = new Date(`${route.travelDate}T12:00:00`);
  const drafts: LocalRouteDraft[] = [];
  for (let inspected = 0; drafts.length < recurrence.occurrences && inspected < 366; inspected += 1) {
    cursor.setDate(cursor.getDate() + 1);
    if (!weekdays.includes(cursor.getDay() as LocalJourneyWeekday)) continue;
    const travelDate = `${cursor.getFullYear()}-${String(cursor.getMonth() + 1).padStart(2, "0")}-${String(cursor.getDate()).padStart(2, "0")}`;
    drafts.push({ origin: route.origin, originStationCode: route.originStationCode, destination: route.destination, destinationStationCode: route.destinationStationCode, travelDate, travelTime: route.travelTime, plannedReminderMinutes: route.plannedReminderMinutes, checklist: normalizeChecklist(route.checklist).map(item => ({ ...item, completed: false })) });
  }
  return drafts;
}

export function applyLocalJourneyChecklistPreset(route: Pick<LocalPlannedRoute, "checklist">, presetId: LocalJourneyChecklistPreset["id"]): LocalJourneyChecklistItem[] {
  const preset = localJourneyChecklistPresets.find(candidate => candidate.id === presetId);
  if (!preset) return normalizeChecklist(route.checklist);
  const current = normalizeChecklist(route.checklist);
  return checklistIds.filter(id => preset.itemIds.includes(id)).map(id => current.find(item => item.id === id) ?? { id, completed: false });
}

/** Ricava il filtro dalla checklist già locale e non salva un secondo tag di preparazione. */
export function getLocalJourneyPreparationPreset(route: Pick<LocalPlannedRoute, "checklist">): LocalJourneyChecklistPreset["id"] {
  const itemIds = normalizeChecklist(route.checklist).map(item => item.id);
  if (itemIds.includes("assistance")) return "assistenza";
  if (itemIds.includes("bike")) return "bici";
  if (itemIds.includes("connection")) return "coincidenza";
  return "pendolare";
}

function resetLocalRoutePlanning(route: Pick<LocalPlannedRoute, "origin" | "originStationCode" | "destination" | "destinationStationCode" | "plannedReminderMinutes" | "checklist">): ControlledLocalRouteDraft {
  return {
    origin: route.origin,
    originStationCode: route.originStationCode,
    destination: route.destination,
    destinationStationCode: route.destinationStationCode,
    travelDate: "",
    travelTime: "",
    plannedReminderMinutes: route.plannedReminderMinutes,
    checklist: normalizeChecklist(route.checklist).map(item => ({ ...item, completed: false })),
  };
}

/** Riporta nel planner soltanto i dati riutilizzabili; data e ora richiedono sempre una scelta nuova. */
export function createReplannedLocalRouteDraft(route: Pick<LocalPlannedRoute, "origin" | "originStationCode" | "destination" | "destinationStationCode" | "plannedReminderMinutes" | "checklist">): ControlledLocalRouteDraft {
  return resetLocalRoutePlanning(route);
}

/** Prepara il ritorno scambiando le stazioni, senza ereditare data, ora o completamenti della tratta originaria. */
export function createReturnLocalRouteDraft(route: Pick<LocalPlannedRoute, "origin" | "originStationCode" | "destination" | "destinationStationCode" | "plannedReminderMinutes" | "checklist">): ControlledLocalRouteDraft {
  return resetLocalRoutePlanning({
    origin: route.destination,
    originStationCode: route.destinationStationCode,
    destination: route.origin,
    destinationStationCode: route.originStationCode,
    plannedReminderMinutes: route.plannedReminderMinutes,
    checklist: route.checklist,
  });
}

export function sortLocalPlannedRoutes(routes: LocalPlannedRoute[]) {
  const order = (route: LocalPlannedRoute) => Number.isSafeInteger(route.manualOrder) && (route.manualOrder ?? -1) >= 0 ? route.manualOrder as number : Number.MAX_SAFE_INTEGER;
  return [...routes].sort((left, right) => Number(Boolean(right.pinned)) - Number(Boolean(left.pinned)) || order(left) - order(right) || `${left.travelDate}T${left.travelTime}`.localeCompare(`${right.travelDate}T${right.travelTime}`) || right.createdAtUtcMs - left.createdAtUtcMs);
}

export function localRouteScheduledAtUtcMs(route: Pick<LocalPlannedRoute, "travelDate" | "travelTime">): number | null {
  const scheduledAtUtcMs = new Date(`${route.travelDate}T${route.travelTime}:00`).getTime();
  return Number.isFinite(scheduledAtUtcMs) ? scheduledAtUtcMs : null;
}

export function selectNextLocalPlannedRoute(routes: LocalPlannedRoute[], now = Date.now()): LocalPlannedRoute | null {
  return sortLocalPlannedRoutes(routes).find(route => route.completedAtUtcMs == null && (localRouteScheduledAtUtcMs(route) ?? 0) >= now) ?? null;
}

export function localCountdownLabel(route: Pick<LocalPlannedRoute, "travelDate" | "travelTime">, now = Date.now()): string | null {
  const scheduledAt = localRouteScheduledAtUtcMs(route);
  if (scheduledAt === null || scheduledAt < now) return null;
  const minutes = Math.ceil((scheduledAt - now) / 60_000);
  if (minutes < 60) return `Tra ${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  return remainingMinutes === 0 ? `Tra ${hours} h` : `Tra ${hours} h ${remainingMinutes} min`;
}

export type LocalAgendaFilter = "upcoming" | "week" | "archive";

export function filterLocalJourneyAgenda(routes: LocalPlannedRoute[], filter: LocalAgendaFilter, now = Date.now()) {
  const sorted = sortLocalPlannedRoutes(routes);
  if (filter === "upcoming") return sorted.filter(route => route.completedAtUtcMs == null && (localRouteScheduledAtUtcMs(route) ?? -1) >= now);
  if (filter === "archive") return sorted.filter(route => route.completedAtUtcMs != null || (localRouteScheduledAtUtcMs(route) ?? Number.POSITIVE_INFINITY) < now).reverse();
  const start = new Date(now);
  start.setHours(0, 0, 0, 0);
  const end = new Date(start);
  end.setDate(end.getDate() + 7);
  return sorted.filter(route => {
    const scheduledAt = localRouteScheduledAtUtcMs(route);
    return route.completedAtUtcMs == null && scheduledAt !== null && scheduledAt >= start.getTime() && scheduledAt < end.getTime();
  });
}
