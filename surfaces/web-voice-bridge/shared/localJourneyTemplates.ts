import type { JourneyReminderMinutes, JourneyTemplateId } from "./localJourneyToolkit";

export const LOCAL_JOURNEY_TEMPLATES_STORAGE_KEY = "binario-journey-templates-v1";
export const MAX_LOCAL_JOURNEY_TEMPLATES = 12;

export type LocalJourneyTemplate = {
  schemaVersion: 1;
  id: string;
  label: string;
  origin: string;
  destination: string;
  originStationCode: string | null;
  destinationStationCode: string | null;
  travelTime: string;
  reminderMinutes: JourneyReminderMinutes | null;
  checklistTemplateIds: JourneyTemplateId[];
  createdAt: string;
};

const reminderValues: JourneyReminderMinutes[] = [5, 10, 15, 20, 30, 45, 60];
const checklistTemplateIds: JourneyTemplateId[] = ["commuter", "weekend", "bike", "accessibility"];

function isRecord(value: unknown): value is Record<string, unknown> { return Boolean(value) && typeof value === "object" && !Array.isArray(value); }
function isTime(value: unknown): value is string { return typeof value === "string" && /^([01]\d|2[0-3]):[0-5]\d$/.test(value); }
function isNullableString(value: unknown): value is string | null { return value === null || typeof value === "string"; }

export function normalizeLocalJourneyTemplate(value: unknown): LocalJourneyTemplate | null {
  if (!isRecord(value) || value.schemaVersion !== 1 || typeof value.id !== "string" || typeof value.label !== "string" || typeof value.origin !== "string" || typeof value.destination !== "string" || !isNullableString(value.originStationCode) || !isNullableString(value.destinationStationCode) || !isTime(value.travelTime) || typeof value.createdAt !== "string" || !Number.isFinite(Date.parse(value.createdAt))) return null;
  const origin = value.origin.trim().slice(0, 120);
  const destination = value.destination.trim().slice(0, 120);
  const label = value.label.trim().slice(0, 80);
  if (!origin || !destination || !label || origin.localeCompare(destination, "it", { sensitivity: "base" }) === 0) return null;
  return {
    schemaVersion: 1,
    id: value.id,
    label,
    origin,
    destination,
    originStationCode: value.originStationCode,
    destinationStationCode: value.destinationStationCode,
    travelTime: value.travelTime,
    reminderMinutes: value.reminderMinutes === null || reminderValues.includes(value.reminderMinutes as JourneyReminderMinutes) ? value.reminderMinutes as JourneyReminderMinutes | null : null,
    checklistTemplateIds: Array.isArray(value.checklistTemplateIds) ? Array.from(new Set(value.checklistTemplateIds.filter((id): id is JourneyTemplateId => checklistTemplateIds.includes(id as JourneyTemplateId)))) : [],
    createdAt: value.createdAt,
  };
}

export function parseLocalJourneyTemplates(raw: string | null): LocalJourneyTemplate[] {
  try {
    const parsed: unknown = JSON.parse(raw ?? "[]");
    return Array.isArray(parsed) ? parsed.map(normalizeLocalJourneyTemplate).filter((item): item is LocalJourneyTemplate => item !== null).slice(0, MAX_LOCAL_JOURNEY_TEMPLATES) : [];
  } catch { return []; }
}

export function prependLocalJourneyTemplate(templates: readonly LocalJourneyTemplate[], template: LocalJourneyTemplate): LocalJourneyTemplate[] {
  const normalized = normalizeLocalJourneyTemplate(template);
  if (!normalized) return [...templates];
  const semanticKey = `${normalized.originStationCode ?? normalized.origin.toLocaleLowerCase("it")}|${normalized.destinationStationCode ?? normalized.destination.toLocaleLowerCase("it")}|${normalized.travelTime}`;
  return [normalized, ...templates.filter(item => `${item.originStationCode ?? item.origin.toLocaleLowerCase("it")}|${item.destinationStationCode ?? item.destination.toLocaleLowerCase("it")}|${item.travelTime}` !== semanticKey)].slice(0, MAX_LOCAL_JOURNEY_TEMPLATES);
}
