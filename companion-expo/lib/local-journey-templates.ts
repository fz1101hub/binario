import AsyncStorage from "@react-native-async-storage/async-storage";
import type { LocalJourneyChecklistItem } from "@/lib/local-route-contract";

export type LocalJourneyTemplate = { id: string; label: string; origin: string; originStationCode: string; destination: string; destinationStationCode: string; travelTime: string; plannedReminderMinutes: number | null; checklist: LocalJourneyChecklistItem[] };
const storageKey = "binario.local-journey-templates.v1";
const maxTemplates = 12;
const normalize = (value: string, max: number) => value.trim().replace(/\s+/g, " ").slice(0, max).trim();

export function createLocalJourneyTemplate(value: LocalJourneyTemplate): LocalJourneyTemplate | null {
  const template = { ...value, id: normalize(value.id, 100), label: normalize(value.label, 80), origin: normalize(value.origin, 96), destination: normalize(value.destination, 96), originStationCode: normalize(value.originStationCode, 64), destinationStationCode: normalize(value.destinationStationCode, 64) };
  return template.id && template.label && template.origin.length >= 2 && template.destination.length >= 2 && template.origin !== template.destination && template.originStationCode && template.destinationStationCode && /^([01]\d|2[0-3]):[0-5]\d$/.test(template.travelTime) && (template.plannedReminderMinutes === null || (Number.isInteger(template.plannedReminderMinutes) && template.plannedReminderMinutes >= 0 && template.plannedReminderMinutes <= 1_440)) ? template : null;
}
export async function loadLocalJourneyTemplates(): Promise<LocalJourneyTemplate[]> {
  try { const values: unknown = JSON.parse((await AsyncStorage.getItem(storageKey)) ?? "[]"); return Array.isArray(values) ? values.map(item => createLocalJourneyTemplate(item as LocalJourneyTemplate)).filter((item): item is LocalJourneyTemplate => Boolean(item)).slice(0, maxTemplates) : []; } catch { return []; }
}
export async function saveLocalJourneyTemplate(template: LocalJourneyTemplate) {
  const valid = createLocalJourneyTemplate(template); if (!valid) return loadLocalJourneyTemplates();
  const current = await loadLocalJourneyTemplates();
  const next = [valid, ...current.filter(item => item.id !== valid.id)].slice(0, maxTemplates);
  await AsyncStorage.setItem(storageKey, JSON.stringify(next)); return next;
}
export async function removeLocalJourneyTemplate(id: string) { const next = (await loadLocalJourneyTemplates()).filter(item => item.id !== id); await AsyncStorage.setItem(storageKey, JSON.stringify(next)); return next; }
