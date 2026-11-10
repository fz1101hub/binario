import * as DocumentPicker from "expo-document-picker";
import * as FileSystem from "expo-file-system/legacy";
import * as Sharing from "expo-sharing";
import type { LocalPlannedRoute } from "@/lib/local-route-contract";
import { createTranslator, type CompanionLocale } from "@/lib/i18n";
import { formatTravelDate, formatTravelTime, italianWallClock, travelEpochMs, travelTimeZone, zonedWallClockToEpoch, type WallClock } from "@/lib/travel-time-zone";

export type CalendarRouteDraft = Pick<LocalPlannedRoute, "origin" | "originStationCode" | "destination" | "destinationStationCode" | "travelDate" | "travelTime" | "plannedReminderMinutes">;

/** The cache file of the last calendar export; removed by «Cancella archivio locale». */
export const localJourneyIcsFileName = "binario-tratta.ics";

/** Zone names that keep Italian time all year. */
const italianTimeZoneIds = new Set(["europe/rome", "europe/vatican", "europe/san_marino"]);

/** RFC 5545 requires a VTIMEZONE for every TZID used: Italy's rule since 1996 (CET/CEST, EU dates). */
const italianVTimezone = [
  "BEGIN:VTIMEZONE",
  `TZID:${travelTimeZone}`,
  "BEGIN:DAYLIGHT",
  "TZOFFSETFROM:+0100",
  "TZOFFSETTO:+0200",
  "TZNAME:CEST",
  "DTSTART:19700329T020000",
  "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU",
  "END:DAYLIGHT",
  "BEGIN:STANDARD",
  "TZOFFSETFROM:+0200",
  "TZOFFSETTO:+0100",
  "TZNAME:CET",
  "DTSTART:19701025T030000",
  "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU",
  "END:STANDARD",
  "END:VTIMEZONE",
];

const escapeText = (value: string) => value.replace(/[\\;,]/g, "\\$&").replace(/\r?\n/g, "\\n");
const unescapeText = (value: string) => value.replace(/\\([\\;,nN])/g, (_match, character: string) => character === "n" || character === "N" ? " " : character);
const pad = (value: number) => String(value).padStart(2, "0");
const icsLocal = (wallClock: WallClock) => `${wallClock.year}${pad(wallClock.month)}${pad(wallClock.day)}T${pad(wallClock.hour)}${pad(wallClock.minute)}00`;
const icsUtc = (epochMs: number) => { const date = new Date(epochMs); return `${date.getUTCFullYear()}${pad(date.getUTCMonth() + 1)}${pad(date.getUTCDate())}T${pad(date.getUTCHours())}${pad(date.getUTCMinutes())}${pad(date.getUTCSeconds())}Z`; };

/** Folds a content line at 75 octets (RFC 5545 §3.1) without splitting a UTF-8 character. */
export function foldIcsLine(line: string) {
  const encoder = new TextEncoder();
  const pieces: string[] = [];
  let current = "";
  let currentBytes = 0;
  for (const character of line) {
    const bytes = encoder.encode(character).byteLength;
    const limit = pieces.length === 0 ? 75 : 74; // continuation lines start with one space
    if (currentBytes + bytes > limit) { pieces.push(current); current = ""; currentBytes = 0; }
    current += character;
    currentBytes += bytes;
  }
  pieces.push(current);
  return pieces.join("\r\n ");
}

/** The calendar file of one planned journey: Italian time with its VTIMEZONE, as RFC 5545 requires. */
export function buildLocalJourneyIcs(route: Pick<LocalPlannedRoute, "id" | "origin" | "destination" | "travelDate" | "travelTime">, locale?: CompanionLocale, now = Date.now()): string | null {
  const start = travelEpochMs(route.travelDate, route.travelTime);
  if (start === null) return null;
  const startWallClock = italianWallClock(start);
  const endWallClock = italianWallClock(start + 60 * 60_000);
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//Binario//Local journey//IT",
    "CALSCALE:GREGORIAN",
    ...italianVTimezone,
    "BEGIN:VEVENT",
    `UID:binario-${route.id}@local`,
    `DTSTAMP:${icsUtc(now)}`,
    `DTSTART;TZID=${travelTimeZone}:${icsLocal(startWallClock)}`,
    `DTEND;TZID=${travelTimeZone}:${icsLocal(endWallClock)}`,
    `SUMMARY:${escapeText(route.origin)} → ${escapeText(route.destination)}`,
    `LOCATION:${escapeText(route.origin)}`,
    `DESCRIPTION:${escapeText(createTranslator(locale)("snapshot.localNotice"))}`,
    "END:VEVENT",
    "END:VCALENDAR",
  ];
  return `${lines.map(foldIcsLine).join("\r\n")}\r\n`;
}

/** Italian date and time of an event start: UTC ("Z") and known TZIDs are converted; floating times are already Italian. */
function italianStart(dateText: string, timeText: string, parameters: string, utc: boolean): { travelDate: string; travelTime: string } | null {
  const wallClock: WallClock = { year: Number(dateText.slice(0, 4)), month: Number(dateText.slice(4, 6)), day: Number(dateText.slice(6, 8)), hour: Number(timeText.slice(0, 2)), minute: Number(timeText.slice(2, 4)) };
  let italian = wallClock;
  if (utc) {
    italian = italianWallClock(Date.UTC(wallClock.year, wallClock.month - 1, wallClock.day, wallClock.hour, wallClock.minute));
  } else {
    const timeZone = /;TZID=(?:"([^"]*)"|([^;:]*))/i.exec(parameters);
    const zone = (timeZone?.[1] ?? timeZone?.[2] ?? "").trim();
    if (zone && !italianTimeZoneIds.has(zone.toLowerCase())) {
      // A zone Intl does not know (e.g. a Windows name) is kept as written, like a floating time.
      const epochMs = zonedWallClockToEpoch(wallClock, zone);
      if (epochMs !== null) italian = italianWallClock(epochMs);
    }
  }
  const travelDate = formatTravelDate(italian);
  const travelTime = formatTravelTime(italian);
  return travelEpochMs(travelDate, travelTime, { strict: true }) === null ? null : { travelDate, travelTime };
}

/** The selection and React key of an imported event: one per journey, date and time. */
export function calendarDraftKey(draft: CalendarRouteDraft) {
  return [draft.origin, draft.destination, draft.travelDate, draft.travelTime].join("|");
}

/** Journeys of a calendar file whose SUMMARY is "origin → destination"; nothing is saved here. */
export function parseLocalJourneyIcs(text: string): CalendarRouteDraft[] {
  // RFC 5545 §3.1: a line break followed by a space or tab continues the previous line.
  const unfolded = text.replace(/\r\n?/g, "\n").replace(/\n[ \t]/g, "");
  const blocks = unfolded.split(/^BEGIN:VEVENT$/im).slice(1).map(item => item.split(/^END:VEVENT$/im)[0] ?? "");
  const drafts = new Map<string, CalendarRouteDraft>();
  for (const block of blocks) {
    const summary = unescapeText(/^SUMMARY(?:;[^:\n]*)?:(.*)$/im.exec(block)?.[1] ?? "");
    const start = /^DTSTART((?:;[^:\n]*)?):(\d{8})T(\d{4})(?:\d{2})?(Z?)\s*$/im.exec(block);
    const [origin, destination] = summary.split("→").map(item => item.trim());
    if (!start || !origin || !destination) continue;
    const when = italianStart(start[2]!, start[3]!, start[1] ?? "", start[4] === "Z");
    if (!when) continue;
    const draft: CalendarRouteDraft = { origin, originStationCode: null, destination, destinationStationCode: null, ...when, plannedReminderMinutes: null };
    drafts.set(calendarDraftKey(draft), draft);
  }
  return [...drafts.values()];
}

export async function shareLocalJourneyIcs(route: LocalPlannedRoute, locale?: CompanionLocale) {
  if (!(await Sharing.isAvailableAsync())) return false;
  const ics = buildLocalJourneyIcs(route, locale);
  if (ics === null) return false;
  const uri = `${FileSystem.cacheDirectory}${localJourneyIcsFileName}`;
  await FileSystem.writeAsStringAsync(uri, ics);
  await Sharing.shareAsync(uri, { mimeType: "text/calendar", dialogTitle: createTranslator(locale)("settings.calendarExportDialog") });
  return true;
}

export async function pickLocalJourneyIcs(): Promise<CalendarRouteDraft[]> {
  const result = await DocumentPicker.getDocumentAsync({ type: ["text/calendar", "text/plain"], copyToCacheDirectory: true });
  if (result.canceled || !result.assets[0]) return [];
  try { return parseLocalJourneyIcs(await FileSystem.readAsStringAsync(result.assets[0].uri)); } catch { return []; }
}
