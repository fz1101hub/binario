export type PersonalRouteDraft = {
  origin: string;
  originStationCode?: string | null;
  destination: string;
  destinationStationCode?: string | null;
  name: string;
  isPinned?: boolean;
  travelDate?: string | null;
  travelTime?: string | null;
  travelProfile?: "commuter" | "occasional" | "accessibility";
  plannedReminderMinutes?: number | null;
  personalNote?: string | null;
  accessibilityNote?: string | null;
  personalTags?: string[];
  preDepartureChecklist?: PersonalChecklistItem[];
};

export type PersonalChecklistItem = { id: string; label: string; done: boolean };

function normalizeLabel(value: string, field: string): string {
  const normalized = value.trim().replace(/\s+/g, " ");
  if (normalized.length < 2 || normalized.length > 120) throw new Error(`${field} non valido`);
  return normalized;
}

function normalizeOptionalStationCode(value: string | null | undefined, field: string) {
  if (value === undefined || value === null || value.trim() === "") return null;
  const normalized = value.trim().toUpperCase();
  if (!/^[A-Z0-9_-]{1,64}$/.test(normalized)) throw new Error(`${field} non valido`);
  return normalized;
}

function normalizeOptionalTravelDate(value: string | null | undefined) {
  if (value === undefined || value === null || value.trim() === "") return null;
  const normalized = value.trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(normalized)) throw new Error("Data di riferimento non valida");
  const date = new Date(`${normalized}T00:00:00.000Z`);
  if (Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== normalized) throw new Error("Data di riferimento non valida");
  return normalized;
}

function normalizeOptionalTravelTime(value: string | null | undefined) {
  if (value === undefined || value === null || value.trim() === "") return null;
  const normalized = value.trim();
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(normalized)) throw new Error("Ora di riferimento non valida");
  return normalized;
}

function normalizeOptionalNote(value: string | null | undefined, field: string, maxLength: number) {
  if (value === undefined || value === null || value.trim() === "") return null;
  const normalized = value.trim().replace(/\r\n/g, "\n");
  if (normalized.length > maxLength) throw new Error(`${field} troppo lunga`);
  return normalized;
}

function normalizeTravelProfile(value: PersonalRouteDraft["travelProfile"]): "commuter" | "occasional" | "accessibility" {
  return value === "commuter" || value === "accessibility" ? value : "occasional";
}

function normalizeReminderMinutes(value: number | null | undefined) {
  if (value === undefined || value === null) return null;
  if (!Number.isInteger(value) || value < 0 || value > 1_440) throw new Error("Anticipo promemoria non valido");
  return value;
}

export function parsePersonalTagsJson(value: string | null | undefined): string[] {
  if (!value) return [];
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed) && parsed.every(item => typeof item === "string") ? parsed : [];
  } catch {
    return [];
  }
}

export function parsePersonalChecklistJson(value: string | null | undefined): PersonalChecklistItem[] {
  if (!value) return [];
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed) && parsed.every(item => item && typeof item === "object" && typeof (item as PersonalChecklistItem).id === "string" && typeof (item as PersonalChecklistItem).label === "string" && typeof (item as PersonalChecklistItem).done === "boolean") ? parsed as PersonalChecklistItem[] : [];
  } catch { return []; }
}

function normalizePersonalTags(value: string[] | undefined) {
  if (value === undefined) return "[]";
  if (!Array.isArray(value) || value.length > 5) throw new Error("Tag personali non validi");
  const tags = new Map<string, string>();
  for (const raw of value) {
    const tag = normalizeLabel(raw, "Tag personale");
    if (tag.length > 32) throw new Error("Tag personale non valido");
    const key = tag.toLocaleLowerCase("it");
    if (!tags.has(key)) tags.set(key, tag);
  }
  return JSON.stringify(Array.from(tags.values()));
}

function normalizePersonalChecklist(value: PersonalChecklistItem[] | undefined) {
  if (value === undefined) return "[]";
  if (!Array.isArray(value) || value.length > 8) throw new Error("Checklist personale non valida");
  const ids = new Set<string>();
  const items = value.map(item => {
    if (!item || !/^[a-z0-9_-]{1,40}$/i.test(item.id) || ids.has(item.id) || typeof item.done !== "boolean") throw new Error("Checklist personale non valida");
    ids.add(item.id);
    return { id: item.id, label: normalizeOptionalNote(item.label, "Elemento checklist", 140) ?? "", done: item.done };
  });
  if (items.some(item => item.label.length < 1)) throw new Error("Checklist personale non valida");
  return JSON.stringify(items);
}

export function normalizePersonalRouteDraft(input: PersonalRouteDraft) {
  const origin = normalizeLabel(input.origin, "Origine");
  const destination = normalizeLabel(input.destination, "Destinazione");
  if (origin.localeCompare(destination, "it", { sensitivity: "base" }) === 0) throw new Error("Origine e destinazione devono essere diverse");
  return {
    origin,
    originStationCode: normalizeOptionalStationCode(input.originStationCode, "Codice stazione di partenza"),
    destination,
    destinationStationCode: normalizeOptionalStationCode(input.destinationStationCode, "Codice stazione di arrivo"),
    name: normalizeLabel(input.name, "Nome tratta"),
    isPinned: input.isPinned ?? false,
    travelDate: normalizeOptionalTravelDate(input.travelDate),
    travelTime: normalizeOptionalTravelTime(input.travelTime),
    travelProfile: normalizeTravelProfile(input.travelProfile),
    plannedReminderMinutes: normalizeReminderMinutes(input.plannedReminderMinutes),
    personalNote: normalizeOptionalNote(input.personalNote, "Nota personale", 1_000),
    accessibilityNote: normalizeOptionalNote(input.accessibilityNote, "Nota accessibilità", 500),
    personalTagsJson: normalizePersonalTags(input.personalTags),
    preDepartureChecklistJson: normalizePersonalChecklist(input.preDepartureChecklist),
  };
}

export function personalRouteSyncFingerprint(route: Pick<PersonalRouteDraft, "origin" | "originStationCode" | "destination" | "destinationStationCode" | "travelDate" | "travelTime">): string {
  const origin = normalizeOptionalStationCode(route.originStationCode, "Codice stazione di partenza") ?? normalizeLabel(route.origin, "Origine").toLocaleLowerCase("it");
  const destination = normalizeOptionalStationCode(route.destinationStationCode, "Codice stazione di arrivo") ?? normalizeLabel(route.destination, "Destinazione").toLocaleLowerCase("it");
  return [origin, destination, normalizeOptionalTravelDate(route.travelDate) ?? "", normalizeOptionalTravelTime(route.travelTime) ?? ""].join("|");
}
