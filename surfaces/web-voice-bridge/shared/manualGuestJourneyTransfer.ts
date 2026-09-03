import type { LocalGuestRoute } from "./localGuestRoutes";

export const MANUAL_GUEST_TRANSFER_FORMAT = "binario-journeys-manual-transfer";
export const MANUAL_GUEST_TRANSFER_SCHEMA_VERSION = 1;
export const MAX_MANUAL_GUEST_TRANSFER_ROUTES = 40;
export const MAX_MANUAL_GUEST_TRANSFER_BYTES = 1_000_000;

type ChecklistCode = "time-margin" | "documents" | "belongings" | "connection" | "bike" | "assistance";
export type ManualGuestTransferRoute = { origin: string; originStationCode: string | null; destination: string; destinationStationCode: string | null; travelDate: string; travelTime: string; plannedReminderMinutes: number | null; checklist: Array<{ id: ChecklistCode; completed: boolean }> };
export type ManualGuestTransferArchive = { format: typeof MANUAL_GUEST_TRANSFER_FORMAT; schemaVersion: typeof MANUAL_GUEST_TRANSFER_SCHEMA_VERSION; exportedAtUtcMs: number; routes: ManualGuestTransferRoute[] };
export type ManualGuestTransferPreview = { key: string; route: ManualGuestTransferRoute; state: "new" | "already_present" };

const checklistCodes: ChecklistCode[] = ["time-margin", "documents", "belongings", "connection", "bike", "assistance"];
const validReminder = (value: unknown): value is 5 | 10 | 15 | 20 | 30 | 45 | 60 => typeof value === "number" && [5, 10, 15, 20, 30, 45, 60].includes(value);
const cleanText = (value: unknown, max: number) => typeof value === "string" ? value.trim().replace(/\s+/g, " ").slice(0, max) : "";
const validDate = (value: unknown) => typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(`${value}T00:00:00Z`));
const validTime = (value: unknown) => typeof value === "string" && /^([01]\d|2[0-3]):[0-5]\d$/.test(value);

export function manualGuestTransferKey(route: Pick<ManualGuestTransferRoute, "origin" | "originStationCode" | "destination" | "destinationStationCode" | "travelDate" | "travelTime">) {
  const station = (code: string | null, name: string) => code?.trim().toLocaleUpperCase("it") || name.trim().toLocaleLowerCase("it");
  return `${station(route.originStationCode, route.origin)}|${station(route.destinationStationCode, route.destination)}|${route.travelDate}|${route.travelTime}`;
}

function normalizeRoute(value: unknown): ManualGuestTransferRoute | null {
  if (!value || typeof value !== "object") return null;
  const route = value as Partial<ManualGuestTransferRoute>;
  const origin = cleanText(route.origin, 96); const destination = cleanText(route.destination, 96);
  const originStationCode = route.originStationCode === null || route.originStationCode === undefined ? null : cleanText(route.originStationCode, 64);
  const destinationStationCode = route.destinationStationCode === null || route.destinationStationCode === undefined ? null : cleanText(route.destinationStationCode, 64);
  const checklist = Array.isArray(route.checklist) ? route.checklist.filter((item): item is { id: ChecklistCode; completed: boolean } => Boolean(item) && typeof item === "object" && checklistCodes.includes((item as { id?: unknown }).id as ChecklistCode) && typeof (item as { completed?: unknown }).completed === "boolean").slice(0, checklistCodes.length) : [];
  if (origin.length < 2 || destination.length < 2 || origin === destination || !validDate(route.travelDate) || !validTime(route.travelTime) || (originStationCode !== null && !originStationCode) || (destinationStationCode !== null && !destinationStationCode) || (route.plannedReminderMinutes !== null && route.plannedReminderMinutes !== undefined && !validReminder(route.plannedReminderMinutes))) return null;
  return { origin, originStationCode, destination, destinationStationCode, travelDate: route.travelDate!, travelTime: route.travelTime!, plannedReminderMinutes: route.plannedReminderMinutes ?? null, checklist };
}

function routeChecklist(route: LocalGuestRoute): Array<{ id: ChecklistCode; completed: boolean }> {
  const byTemplate: Record<string, ChecklistCode[]> = { commuter: ["time-margin", "documents", "belongings"], weekend: ["belongings", "documents"], bike: ["bike", "belongings"], accessibility: ["time-margin", "assistance", "documents"] };
  const codes = route.checklistTemplateIds.flatMap(template => byTemplate[template] ?? []).filter((code, index, list) => list.indexOf(code) === index);
  return codes.map((id, index) => ({ id, completed: Boolean(route.checklist[index]?.completed) }));
}

export function buildManualGuestTransferArchive(routes: readonly LocalGuestRoute[], now = Date.now()): ManualGuestTransferArchive {
  const normalized = routes.filter(route => !route.archivedAt).map(route => normalizeRoute({ origin: route.origin, originStationCode: route.originStationCode, destination: route.destination, destinationStationCode: route.destinationStationCode, travelDate: route.travelDate, travelTime: route.travelTime, plannedReminderMinutes: route.reminderMinutes, checklist: routeChecklist(route) })).filter((route): route is ManualGuestTransferRoute => route !== null);
  const unique = normalized.filter((route, index, list) => list.findIndex(candidate => manualGuestTransferKey(candidate) === manualGuestTransferKey(route)) === index).slice(0, MAX_MANUAL_GUEST_TRANSFER_ROUTES);
  return { format: MANUAL_GUEST_TRANSFER_FORMAT, schemaVersion: MANUAL_GUEST_TRANSFER_SCHEMA_VERSION, exportedAtUtcMs: Number.isSafeInteger(now) && now >= 0 ? now : Date.now(), routes: unique };
}

export function parseManualGuestTransferArchive(raw: string | null): ManualGuestTransferArchive | null {
  if (!raw || raw.length > MAX_MANUAL_GUEST_TRANSFER_BYTES) return null;
  try {
    const archive = JSON.parse(raw) as Partial<ManualGuestTransferArchive>;
    if (!archive || archive.format !== MANUAL_GUEST_TRANSFER_FORMAT || archive.schemaVersion !== MANUAL_GUEST_TRANSFER_SCHEMA_VERSION || !Number.isSafeInteger(archive.exportedAtUtcMs) || !Array.isArray(archive.routes) || archive.routes.length < 1 || archive.routes.length > MAX_MANUAL_GUEST_TRANSFER_ROUTES) return null;
    const routes = archive.routes.map(normalizeRoute);
    if (routes.some(route => route === null)) return null;
    return { format: MANUAL_GUEST_TRANSFER_FORMAT, schemaVersion: MANUAL_GUEST_TRANSFER_SCHEMA_VERSION, exportedAtUtcMs: archive.exportedAtUtcMs!, routes: routes as ManualGuestTransferRoute[] };
  } catch { return null; }
}

export function previewManualGuestTransfer(current: readonly LocalGuestRoute[], incoming: readonly ManualGuestTransferRoute[]): ManualGuestTransferPreview[] {
  const existing = new Set(current.map(route => manualGuestTransferKey(route)));
  return incoming.map(route => ({ key: manualGuestTransferKey(route), route, state: existing.has(manualGuestTransferKey(route)) ? "already_present" : "new" }));
}
