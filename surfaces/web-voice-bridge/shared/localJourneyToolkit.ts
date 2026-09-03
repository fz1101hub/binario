export const JOURNEY_SCHEMA_VERSION = 1 as const;
export const MAX_LOCAL_JOURNEYS = 40;
export const MAX_RECURRENCE_OCCURRENCES = 12;
export const JOURNEY_ACCESSIBILITY_PREFERENCES_STORAGE_KEY = "binario-journey-accessibility-v1";

export type JourneyReminderMinutes = 5 | 10 | 15 | 20 | 30 | 45 | 60;
export type JourneyTemplateId = "commuter" | "weekend" | "bike" | "accessibility";
export type JourneyWeekday = 0 | 1 | 2 | 3 | 4 | 5 | 6;

export type JourneyChecklistItem = {
  id: string;
  label: string;
  completed: boolean;
};

export type JourneyAccessibilityPreferences = {
  schemaVersion: typeof JOURNEY_SCHEMA_VERSION;
  minimumTransferMinutes: 5 | 10 | 15 | 20 | 30 | 45 | 60;
  defaultReminderMinutes: JourneyReminderMinutes | null;
  accessibilityChecklistByDefault: boolean;
};

export type JourneyRecurrence = {
  schemaVersion: typeof JOURNEY_SCHEMA_VERSION;
  weekdays: JourneyWeekday[];
  time: string;
  startsOn: string;
  endsOn: string | null;
  occurrences: number;
};

export type LocalJourneyExtension = {
  archivedAt: string | null;
  completedAt: string | null;
  completedOccurrenceDates: string[];
  reminderMinutes: JourneyReminderMinutes | null;
  recurrence: JourneyRecurrence | null;
  checklistTemplateIds: JourneyTemplateId[];
  checklist: JourneyChecklistItem[];
  createdAt: string;
};

export type JourneyRecord = {
  id: string;
  origin: string;
  destination: string;
  originStationCode: string | null;
  destinationStationCode: string | null;
  travelDate: string;
  travelTime: string;
} & LocalJourneyExtension;

export type ControlledJourneyDraft = Pick<JourneyRecord, "origin" | "destination" | "originStationCode" | "destinationStationCode"> & {
  travelDate: "";
  travelTime: "";
  checklistTemplateIds: JourneyTemplateId[];
  checklist: JourneyChecklistItem[];
};

export type AgendaFilter = "upcoming" | "archived" | "reminders" | "all";

export type AgendaEntry = JourneyRecord & {
  scheduledAt: string;
  sourceJourneyId: string;
  isGeneratedOccurrence: boolean;
};

export type LocalJourneyStats = {
  active: number;
  archived: number;
  completed: number;
  remindersConfigured: number;
  remindersCompleted: number;
  topDestinations: Array<{ destination: string; count: number }>;
};

const reminderValues: JourneyReminderMinutes[] = [5, 10, 15, 20, 30, 45, 60];
const templateIds: JourneyTemplateId[] = ["commuter", "weekend", "bike", "accessibility"];

export const journeyChecklistTemplates: Readonly<Record<JourneyTemplateId, readonly string[]>> = {
  commuter: ["Verifica il margine di anticipo", "Prepara titolo di viaggio e documenti", "Controlla gli effetti personali"],
  weekend: ["Prepara bagaglio essenziale", "Condividi il riepilogo del viaggio se lo desideri", "Controlla gli effetti personali"],
  bike: ["Verifica accesso e spazio bici con il vettore", "Prepara il necessario per la bici", "Controlla gli effetti personali"],
  accessibility: ["Imposta un margine di anticipo adeguato", "Prepara l’eventuale assistenza già concordata", "Controlla ausili e documenti personali"],
};

export function defaultJourneyAccessibilityPreferences(): JourneyAccessibilityPreferences {
  return { schemaVersion: JOURNEY_SCHEMA_VERSION, minimumTransferMinutes: 15, defaultReminderMinutes: null, accessibilityChecklistByDefault: false };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
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

function isTimestamp(value: unknown): value is string {
  return typeof value === "string" && Number.isFinite(Date.parse(value));
}

function isTemplateId(value: unknown): value is JourneyTemplateId {
  return typeof value === "string" && templateIds.includes(value as JourneyTemplateId);
}

function isWeekday(value: unknown): value is JourneyWeekday {
  return typeof value === "number" && Number.isInteger(value) && value >= 0 && value <= 6;
}

function isChecklistItem(value: unknown): value is JourneyChecklistItem {
  return isRecord(value) && typeof value.id === "string" && typeof value.label === "string" && typeof value.completed === "boolean";
}

export function isJourneyRecurrence(value: unknown): value is JourneyRecurrence {
  if (!isRecord(value)) return false;
  return value.schemaVersion === JOURNEY_SCHEMA_VERSION
    && Array.isArray(value.weekdays)
    && value.weekdays.length > 0
    && value.weekdays.length <= 7
    && value.weekdays.every(isWeekday)
    && new Set(value.weekdays).size === value.weekdays.length
    && isTime(value.time)
    && isDate(value.startsOn)
    && (value.endsOn === null || isDate(value.endsOn))
    && (value.endsOn === null || value.endsOn >= value.startsOn)
    && typeof value.occurrences === "number"
    && Number.isInteger(value.occurrences)
    && value.occurrences >= 1
    && value.occurrences <= MAX_RECURRENCE_OCCURRENCES;
}

export function defaultJourneyExtension(now = new Date().toISOString()): LocalJourneyExtension {
  return {
    archivedAt: null,
    completedAt: null,
    completedOccurrenceDates: [],
    reminderMinutes: null,
    recurrence: null,
    checklistTemplateIds: [],
    checklist: [],
    createdAt: now,
  };
}

export function normalizeJourneyExtension(value: unknown, fallbackTimestamp = new Date().toISOString()): LocalJourneyExtension {
  const defaults = defaultJourneyExtension(fallbackTimestamp);
  if (!isRecord(value)) return defaults;

  return {
    archivedAt: value.archivedAt === null || isTimestamp(value.archivedAt) ? value.archivedAt : null,
    completedAt: value.completedAt === null || isTimestamp(value.completedAt) ? value.completedAt : null,
    completedOccurrenceDates: Array.isArray(value.completedOccurrenceDates) ? Array.from(new Set(value.completedOccurrenceDates.filter(isDate))).slice(0, MAX_RECURRENCE_OCCURRENCES) : [],
    reminderMinutes: reminderValues.includes(value.reminderMinutes as JourneyReminderMinutes) ? value.reminderMinutes as JourneyReminderMinutes : null,
    recurrence: isJourneyRecurrence(value.recurrence) ? value.recurrence : null,
    checklistTemplateIds: Array.isArray(value.checklistTemplateIds) ? Array.from(new Set(value.checklistTemplateIds.filter(isTemplateId))) : [],
    checklist: Array.isArray(value.checklist) ? value.checklist.filter(isChecklistItem).slice(0, 24) : [],
    createdAt: isTimestamp(value.createdAt) ? value.createdAt : fallbackTimestamp,
  };
}

export function normalizeJourneyAccessibilityPreferences(value: unknown): JourneyAccessibilityPreferences {
  const defaults = defaultJourneyAccessibilityPreferences();
  if (!isRecord(value) || value.schemaVersion !== JOURNEY_SCHEMA_VERSION) return defaults;
  return {
    schemaVersion: JOURNEY_SCHEMA_VERSION,
    minimumTransferMinutes: reminderValues.includes(value.minimumTransferMinutes as JourneyReminderMinutes) ? value.minimumTransferMinutes as JourneyAccessibilityPreferences["minimumTransferMinutes"] : defaults.minimumTransferMinutes,
    defaultReminderMinutes: value.defaultReminderMinutes === null || reminderValues.includes(value.defaultReminderMinutes as JourneyReminderMinutes) ? value.defaultReminderMinutes as JourneyReminderMinutes | null : defaults.defaultReminderMinutes,
    accessibilityChecklistByDefault: typeof value.accessibilityChecklistByDefault === "boolean" ? value.accessibilityChecklistByDefault : defaults.accessibilityChecklistByDefault,
  };
}

export function parseJourneyAccessibilityPreferences(raw: string | null): JourneyAccessibilityPreferences {
  try {
    return normalizeJourneyAccessibilityPreferences(JSON.parse(raw ?? "null"));
  } catch {
    return defaultJourneyAccessibilityPreferences();
  }
}

/** Restituisce una proposta, mai un promemoria pianificato automaticamente. */
export function suggestedJourneyReminderMinutes(preferences: JourneyAccessibilityPreferences): JourneyReminderMinutes {
  return preferences.defaultReminderMinutes ?? preferences.minimumTransferMinutes;
}

function resetJourneyPlanning(route: Pick<JourneyRecord, "origin" | "destination" | "originStationCode" | "destinationStationCode" | "checklistTemplateIds">): ControlledJourneyDraft {
  const checklistTemplateIds = [...route.checklistTemplateIds];
  return {
    origin: route.origin,
    destination: route.destination,
    originStationCode: route.originStationCode,
    destinationStationCode: route.destinationStationCode,
    travelDate: "",
    travelTime: "",
    checklistTemplateIds,
    checklist: checklistFromTemplates(checklistTemplateIds),
  };
}

/** Riusa solo il percorso: data e ora restano una nuova scelta dell’utente. */
export function createReplannedJourneyDraft(route: Pick<JourneyRecord, "origin" | "destination" | "originStationCode" | "destinationStationCode" | "checklistTemplateIds">): ControlledJourneyDraft {
  return resetJourneyPlanning(route);
}

/** Crea una bozza di ritorno invertendo le stazioni, senza ereditare data, ora o stati della tratta originaria. */
export function createReturnJourneyDraft(route: Pick<JourneyRecord, "origin" | "destination" | "originStationCode" | "destinationStationCode" | "checklistTemplateIds">): ControlledJourneyDraft {
  return resetJourneyPlanning({
    origin: route.destination,
    destination: route.origin,
    originStationCode: route.destinationStationCode,
    destinationStationCode: route.originStationCode,
    checklistTemplateIds: route.checklistTemplateIds,
  });
}

export function checklistFromTemplates(templateIdsToApply: readonly JourneyTemplateId[]): JourneyChecklistItem[] {
  const labels = Array.from(new Set(templateIdsToApply.flatMap(templateId => journeyChecklistTemplates[templateId])));
  return labels.map((label, index) => ({ id: `template-${index + 1}`, label, completed: false }));
}

export function mergeChecklistTemplates(existing: readonly JourneyChecklistItem[], templateIdsToApply: readonly JourneyTemplateId[]): JourneyChecklistItem[] {
  const existingByLabel = new Map(existing.map(item => [item.label.toLocaleLowerCase("it"), item]));
  const fromTemplates = checklistFromTemplates(templateIdsToApply);
  const merged = fromTemplates.map(item => existingByLabel.get(item.label.toLocaleLowerCase("it")) ?? item);
  const customItems = existing.filter(item => !fromTemplates.some(templateItem => templateItem.label.toLocaleLowerCase("it") === item.label.toLocaleLowerCase("it")));
  return [...merged, ...customItems].slice(0, 24);
}

function localDateTime(date: string, time: string): Date | null {
  if (!isDate(date) || !isTime(time)) return null;
  const [year, month, day] = date.split("-").map(Number);
  const [hours, minutes] = time.split(":").map(Number);
  const local = new Date(year!, month! - 1, day!, hours!, minutes!, 0, 0);
  return Number.isNaN(local.getTime()) ? null : local;
}

function formatDate(date: Date): string {
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 10);
}

function dateAtStartOfDay(date: string): Date | null {
  return localDateTime(date, "00:00");
}

export function getScheduledAt(route: Pick<JourneyRecord, "travelDate" | "travelTime">): string | null {
  const scheduledAt = localDateTime(route.travelDate, route.travelTime);
  return scheduledAt ? scheduledAt.toISOString() : null;
}

export function generateJourneyOccurrences(route: JourneyRecord, from = new Date()): AgendaEntry[] {
  const startingDate = dateAtStartOfDay(route.recurrence?.startsOn ?? route.travelDate);
  if (!startingDate) return [];
  if (!route.recurrence) {
    const scheduledAt = getScheduledAt(route);
    return scheduledAt ? [{ ...route, scheduledAt, sourceJourneyId: route.id, isGeneratedOccurrence: false }] : [];
  }

  const endDate = route.recurrence.endsOn ? dateAtStartOfDay(route.recurrence.endsOn) : null;
  const cursor = new Date(Math.max(startingDate.getTime(), new Date(from.getFullYear(), from.getMonth(), from.getDate()).getTime()));
  const entries: AgendaEntry[] = [];

  while (entries.length < route.recurrence.occurrences && (!endDate || cursor <= endDate)) {
    if (route.recurrence.weekdays.includes(cursor.getDay() as JourneyWeekday)) {
      const occurrenceDate = formatDate(cursor);
      const scheduledAt = getScheduledAt({ travelDate: occurrenceDate, travelTime: route.recurrence.time });
      if (scheduledAt) entries.push({ ...route, travelDate: occurrenceDate, travelTime: route.recurrence.time, scheduledAt, sourceJourneyId: route.id, isGeneratedOccurrence: occurrenceDate !== route.travelDate || route.travelTime !== route.recurrence.time });
    }
    cursor.setDate(cursor.getDate() + 1);
  }
  return entries;
}

export function deriveAgenda(routes: readonly JourneyRecord[], filter: AgendaFilter, now = new Date()): AgendaEntry[] {
  const nowTime = now.getTime();
  const relevant = routes.filter(route => {
    if (filter === "archived") return Boolean(route.archivedAt);
    if (filter === "reminders") return !route.archivedAt && Boolean(route.reminderMinutes);
    if (filter === "upcoming") return !route.archivedAt && !route.completedAt;
    return true;
  });

  return relevant
    .flatMap(route => generateJourneyOccurrences(route, now))
    .filter(entry => filter !== "upcoming" || (new Date(entry.scheduledAt).getTime() >= nowTime && !entry.completedOccurrenceDates.includes(entry.travelDate)))
    .sort((left, right) => new Date(left.scheduledAt).getTime() - new Date(right.scheduledAt).getTime());
}

export function deriveLocalJourneyStats(routes: readonly JourneyRecord[]): LocalJourneyStats {
  const active = routes.filter(route => !route.archivedAt);
  const destinationCounts = new Map<string, number>();
  for (const route of routes) {
    const destination = route.destination.trim();
    if (destination) destinationCounts.set(destination, (destinationCounts.get(destination) ?? 0) + 1);
  }
  return {
    active: active.length,
    archived: routes.filter(route => Boolean(route.archivedAt)).length,
    completed: routes.reduce((count, route) => count + (route.completedAt ? 1 : 0) + route.completedOccurrenceDates.length, 0),
    remindersConfigured: active.filter(route => Boolean(route.reminderMinutes)).length,
    remindersCompleted: routes.reduce((count, route) => count + (route.reminderMinutes ? (route.completedAt ? 1 : 0) + route.completedOccurrenceDates.length : 0), 0),
    topDestinations: Array.from(destinationCounts.entries()).sort((left, right) => right[1] - left[1] || left[0].localeCompare(right[0], "it")).slice(0, 3).map(([destination, count]) => ({ destination, count })),
  };
}

function icsEscape(value: string): string {
  return value.replace(/\\/g, "\\\\").replace(/;/g, "\\;").replace(/,/g, "\\,").replace(/\r?\n/g, "\\n");
}

function icsStamp(date: Date): string {
  return date.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");
}

function icsLocalStamp(date: Date): string {
  return `${date.getFullYear()}${String(date.getMonth() + 1).padStart(2, "0")}${String(date.getDate()).padStart(2, "0")}T${String(date.getHours()).padStart(2, "0")}${String(date.getMinutes()).padStart(2, "0")}00`;
}

function icsUnescape(value: string): string {
  return value.replace(/\\n/gi, "\n").replace(/\\,/g, ",").replace(/\\;/g, ";").replace(/\\\\/g, "\\");
}

export type ImportedJourneyCalendarEntry = Pick<JourneyRecord, "origin" | "destination" | "travelDate" | "travelTime">;
export const MAX_IMPORTED_JOURNEY_CALENDAR_ENTRIES = 12;

export function journeyShareText(route: Pick<JourneyRecord, "origin" | "destination" | "travelDate" | "travelTime" | "reminderMinutes">): string {
  const reminder = route.reminderMinutes ? ` Promemoria personale: ${route.reminderMinutes} minuti prima.` : "";
  return `Binario — viaggio programmato: ${route.origin} → ${route.destination}, ${route.travelDate} alle ${route.travelTime}.${reminder} Stato ferroviario: non realtime.`;
}

export function journeyToIcs(route: Pick<JourneyRecord, "id" | "origin" | "destination" | "travelDate" | "travelTime" | "reminderMinutes">, createdAt = new Date()): string | null {
  const start = localDateTime(route.travelDate, route.travelTime);
  if (!start) return null;
  const end = new Date(start.getTime() + 30 * 60_000);
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//Binario//Pianificazione locale//IT",
    "CALSCALE:GREGORIAN",
    "BEGIN:VEVENT",
    `UID:binario-${icsEscape(route.id)}@local`,
    `DTSTAMP:${icsStamp(createdAt)}`,
    `DTSTART:${icsLocalStamp(start)}`,
    `DTEND:${icsLocalStamp(end)}`,
    `SUMMARY:${icsEscape(`${route.origin} → ${route.destination}`)}`,
    "DESCRIPTION:Viaggio programmato con Binario. Stato ferroviario non realtime.",
  ];
  if (route.reminderMinutes) {
    lines.push("BEGIN:VALARM", "ACTION:DISPLAY", `TRIGGER:-PT${route.reminderMinutes}M`, "DESCRIPTION:Promemoria Binario", "END:VALARM");
  }
  lines.push("END:VEVENT", "END:VCALENDAR", "");
  return lines.join("\r\n");
}

function parseJourneyIcsEvent(unfoldedLines: readonly string[]): ImportedJourneyCalendarEntry | null {
  const summary = unfoldedLines.find(line => line.startsWith("SUMMARY:"))?.slice("SUMMARY:".length);
  const start = unfoldedLines.find(line => /^DTSTART(?:;[^:]*)?:/.test(line))?.replace(/^DTSTART(?:;[^:]*)?:/, "");
  if (!summary || !start) return null;
  const match = icsUnescape(summary).match(/^(.{2,120}?)\s*(?:→|->)\s*(.{2,120}?)$/);
  const dateTime = start.match(/^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(?:\d{2})?(?:Z)?$/);
  if (!match || !dateTime) return null;
  const travelDate = `${dateTime[1]}-${dateTime[2]}-${dateTime[3]}`;
  const travelTime = `${dateTime[4]}:${dateTime[5]}`;
  if (!isDate(travelDate) || !isTime(travelTime)) return null;
  const origin = match[1]!.trim();
  const destination = match[2]!.trim();
  if (origin.localeCompare(destination, "it", { sensitivity: "base" }) === 0) return null;
  return { origin, destination, travelDate, travelTime };
}

/** Estrae al massimo dodici tratte complete da blocchi VEVENT, ignorando componenti calendario estranei. */
export function parseJourneyIcsEntries(raw: string): ImportedJourneyCalendarEntry[] {
  if (raw.length === 0 || raw.length > 32_000) return [];
  const unfoldedLines = raw.replace(/\r\n[ \t]/g, "").split(/\r?\n/);
  const eventBlocks: string[][] = [];
  let currentBlock: string[] | null = null;
  for (const line of unfoldedLines) {
    if (line === "BEGIN:VEVENT") {
      if (currentBlock) return [];
      currentBlock = [];
      continue;
    }
    if (line === "END:VEVENT") {
      if (!currentBlock) return [];
      eventBlocks.push(currentBlock);
      currentBlock = null;
      continue;
    }
    if (currentBlock) currentBlock.push(line);
  }
  if (currentBlock) return [];

  const entries: ImportedJourneyCalendarEntry[] = [];
  for (const block of eventBlocks) {
    const entry = parseJourneyIcsEvent(block);
    if (!entry || entries.some(candidate => candidate.origin === entry.origin && candidate.destination === entry.destination && candidate.travelDate === entry.travelDate && candidate.travelTime === entry.travelTime)) continue;
    entries.push(entry);
    if (entries.length === MAX_IMPORTED_JOURNEY_CALENDAR_ENTRIES) break;
  }
  return entries;
}

/** Compatibilità con i flussi che importano un solo evento: usa la prima tratta valida. */
export function parseJourneyIcs(raw: string): ImportedJourneyCalendarEntry | null {
  return parseJourneyIcsEntries(raw)[0] ?? null;
}
