import AsyncStorage from "@react-native-async-storage/async-storage";
import type { LocalJourneyChecklistItem } from "@/lib/local-route-contract";

export type LocalJourneyTemplate = { id: string; label: string; origin: string; originStationCode: string; destination: string; destinationStationCode: string; travelTime: string; plannedReminderMinutes: number | null; checklist: LocalJourneyChecklistItem[] };
const storageKey = "binario.local-journey-templates.v1";
const maxTemplates = 12;
const normalize = (value: unknown, max: number) => typeof value === "string" ? value.trim().replace(/\s+/g, " ").slice(0, max).trim() : "";
type LocalJourneyTemplatesLoadResult = { templates: LocalJourneyTemplate[]; writable: boolean };

export function createLocalJourneyTemplate(value: LocalJourneyTemplate): LocalJourneyTemplate | null {
  if (!value || typeof value !== "object") return null;
  const template = { ...value, id: normalize(value.id, 100), label: normalize(value.label, 80), origin: normalize(value.origin, 96), destination: normalize(value.destination, 96), originStationCode: normalize(value.originStationCode, 64), destinationStationCode: normalize(value.destinationStationCode, 64) };
  return template.id && template.label && template.origin.length >= 2 && template.destination.length >= 2 && template.origin !== template.destination && template.originStationCode && template.destinationStationCode && typeof template.travelTime === "string" && /^([01]\d|2[0-3]):[0-5]\d$/.test(template.travelTime) && (template.plannedReminderMinutes === null || (Number.isInteger(template.plannedReminderMinutes) && template.plannedReminderMinutes >= 0 && template.plannedReminderMinutes <= 1_440)) && Array.isArray(template.checklist) ? template : null;
}
async function load(): Promise<LocalJourneyTemplatesLoadResult> {
  try {
    const raw = await AsyncStorage.getItem(storageKey);
    if (raw === null) return { templates: [], writable: true };
    const values: unknown = JSON.parse(raw);
    if (!Array.isArray(values)) return { templates: [], writable: false };
    const normalized = values.map(item => createLocalJourneyTemplate(item as LocalJourneyTemplate));
    return { templates: normalized.filter((item): item is LocalJourneyTemplate => item !== null).slice(0, maxTemplates), writable: normalized.every(item => item !== null) };
  } catch { return { templates: [], writable: false }; }
}
export async function loadLocalJourneyTemplates(): Promise<LocalJourneyTemplate[]> { return (await load()).templates; }
export async function saveLocalJourneyTemplate(template: LocalJourneyTemplate) {
  const valid = createLocalJourneyTemplate(template); if (!valid) return loadLocalJourneyTemplates();
  const { templates: current, writable } = await load();
  if (!writable) return current;
  const next = [valid, ...current.filter(item => item.id !== valid.id)].slice(0, maxTemplates);
  await AsyncStorage.setItem(storageKey, JSON.stringify(next)); return next;
}
export async function removeLocalJourneyTemplate(id: string) { const { templates, writable } = await load(); if (!writable) return templates; const next = templates.filter(item => item.id !== id); await AsyncStorage.setItem(storageKey, JSON.stringify(next)); return next; }
