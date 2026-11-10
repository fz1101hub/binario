/**
 * Planned journeys are Italian trains: their date and time are the wall clock in Italy, whatever time
 * zone the phone is in (a tourist may plan Roma → Firenze while the phone is still on London time).
 * Every instant derived from a saved travelDate/travelTime (reminder, countdown, expiry, widget, watch,
 * calendar) goes through this module instead of the device-local `Date` constructor.
 */
export const travelTimeZone = "Europe/Rome";

export type WallClock = { year: number; month: number; day: number; hour: number; minute: number };

const minuteMs = 60_000;
const formatters = new Map<string, Intl.DateTimeFormat | null>();

function formatterFor(timeZone: string): Intl.DateTimeFormat | null {
  if (!formatters.has(timeZone)) {
    let formatter: Intl.DateTimeFormat | null = null;
    try {
      formatter = new Intl.DateTimeFormat("en-US", { timeZone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
    } catch {
      // Unknown zone name, or a runtime without time-zone support in Intl.
    }
    formatters.set(timeZone, formatter);
  }
  return formatters.get(timeZone) ?? null;
}

function isWallClock(value: WallClock) {
  return Object.values(value).every(Number.isInteger) && value.month >= 1 && value.month <= 12 && value.day >= 1 && value.day <= 31 && value.hour >= 0 && value.hour <= 23 && value.minute >= 0 && value.minute <= 59;
}

/** Wall clock of an instant in an IANA zone through Intl, or null when Intl cannot answer. */
export function wallClockInTimeZone(epochMs: number, timeZone: string): WallClock | null {
  const formatter = formatterFor(timeZone);
  if (!formatter || !Number.isFinite(epochMs)) return null;
  try {
    const parts = formatter.formatToParts(new Date(epochMs));
    const field = (type: Intl.DateTimeFormatPartTypes) => Number(parts.find(part => part.type === type)?.value);
    // Some engines print midnight as "24" even with h23.
    const wallClock = { year: field("year"), month: field("month"), day: field("day"), hour: field("hour") % 24, minute: field("minute") };
    return isWallClock(wallClock) ? wallClock : null;
  } catch {
    return null;
  }
}

/** 01:00 UTC on the last Sunday of a month: when Italian summer time starts (March) or ends (October). */
function lastSundayAtOneUtc(year: number, monthIndex: number) {
  const lastDay = new Date(Date.UTC(year, monthIndex + 1, 0));
  return Date.UTC(year, monthIndex, lastDay.getUTCDate() - lastDay.getUTCDay(), 1);
}

/**
 * The EU rule Italy has followed since 1996 (CET, UTC+1; CEST, UTC+2 from the last Sunday of March to the
 * last Sunday of October, switching at 01:00 UTC). Used only when the runtime's Intl has no time zones.
 */
export function italianOffsetMinutesByEuRule(epochMs: number) {
  const year = new Date(epochMs).getUTCFullYear();
  return epochMs >= lastSundayAtOneUtc(year, 2) && epochMs < lastSundayAtOneUtc(year, 9) ? 120 : 60;
}

function wallClockFromUtcFields(epochMs: number): WallClock {
  const date = new Date(epochMs);
  return { year: date.getUTCFullYear(), month: date.getUTCMonth() + 1, day: date.getUTCDate(), hour: date.getUTCHours(), minute: date.getUTCMinutes() };
}

export function italianWallClock(epochMs: number, intl: (epochMs: number, timeZone: string) => WallClock | null = wallClockInTimeZone): WallClock {
  return intl(epochMs, travelTimeZone) ?? wallClockFromUtcFields(epochMs + italianOffsetMinutesByEuRule(epochMs) * minuteMs);
}

function sameWallClock(left: WallClock, right: WallClock) {
  return left.year === right.year && left.month === right.month && left.day === right.day && left.hour === right.hour && left.minute === right.minute;
}

function utcOfWallClock(wallClock: WallClock) {
  return Date.UTC(wallClock.year, wallClock.month - 1, wallClock.day, wallClock.hour, wallClock.minute);
}

/**
 * Instant of an Italian wall-clock time. Italy is UTC+1 or UTC+2, so the time is one of two candidates;
 * in the autumn hour that occurs twice the earlier one (summer time) is chosen. A time skipped by the
 * spring change (02:00–02:59) has no instant: `strict` returns null, otherwise it is read with the
 * winter offset, as the JavaScript Date constructor does (02:30 becomes 03:30 summer time).
 */
export function italianWallClockToEpoch(wallClock: WallClock, options: { strict?: boolean; intl?: (epochMs: number, timeZone: string) => WallClock | null } = {}): number | null {
  if (!isWallClock(wallClock)) return null;
  const asUtc = utcOfWallClock(wallClock);
  if (!sameWallClock(wallClockFromUtcFields(asUtc), wallClock)) return null;
  const match = [asUtc - 120 * minuteMs, asUtc - 60 * minuteMs].find(candidate => sameWallClock(italianWallClock(candidate, options.intl), wallClock));
  if (match !== undefined) return match;
  return options.strict ? null : asUtc - 60 * minuteMs;
}

/** Strict "YYYY-MM-DD" + "HH:MM" reading; null for a malformed or impossible calendar date or time. */
export function parseTravelWallClock(travelDate: string, travelTime: string): WallClock | null {
  const date = typeof travelDate === "string" ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(travelDate) : null;
  const time = typeof travelTime === "string" ? /^([01]\d|2[0-3]):([0-5]\d)$/.exec(travelTime) : null;
  if (!date || !time) return null;
  const wallClock = { year: Number(date[1]), month: Number(date[2]), day: Number(date[3]), hour: Number(time[1]), minute: Number(time[2]) };
  return sameWallClock(wallClockFromUtcFields(utcOfWallClock(wallClock)), wallClock) ? wallClock : null;
}

/** Instant of a saved journey's Italian date and time; see `italianWallClockToEpoch` for `strict`. */
export function travelEpochMs(travelDate: string, travelTime: string, options: { strict?: boolean } = {}): number | null {
  const wallClock = parseTravelWallClock(travelDate, travelTime);
  return wallClock ? italianWallClockToEpoch(wallClock, options) : null;
}

/**
 * Instant of a wall-clock time in any IANA zone (an imported calendar's TZID). Null when Intl does not
 * know the zone or the time does not exist there.
 */
export function zonedWallClockToEpoch(wallClock: WallClock, timeZone: string): number | null {
  if (!isWallClock(wallClock)) return null;
  const asUtc = utcOfWallClock(wallClock);
  let candidate = asUtc;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const seen = wallClockInTimeZone(candidate, timeZone);
    if (!seen) return null;
    const next = asUtc - (utcOfWallClock(seen) - candidate);
    if (next === candidate) break;
    candidate = next;
  }
  const seen = wallClockInTimeZone(candidate, timeZone);
  return seen && sameWallClock(seen, wallClock) ? candidate : null;
}

function pad(value: number) { return String(value).padStart(2, "0"); }

export function formatTravelDate(wallClock: WallClock) { return `${wallClock.year}-${pad(wallClock.month)}-${pad(wallClock.day)}`; }
export function formatTravelTime(wallClock: WallClock) { return `${pad(wallClock.hour)}:${pad(wallClock.minute)}`; }
