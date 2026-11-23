/**
 * Calendar days and wall-clock times in an IANA time zone, without a date library: what the
 * iPhone's `SQLiteGTFSJourneyRepository` does with a Gregorian `Calendar` set to an agency's zone.
 * A calendar day is a day number (days since 1970-01-01, proleptic Gregorian), so day offsets
 * and the service bitmasks are plain integer arithmetic.
 */

const MS_PER_DAY = 86_400_000;

/** Days since 1970-01-01 of a Gregorian date (Howard Hinnant's algorithm). */
export function dayNumber(year: number, month: number, day: number): number {
  const y = month <= 2 ? year - 1 : year;
  const era = Math.floor(y / 400);
  const yoe = y - era * 400;
  const doy = Math.floor((153 * (month + (month > 2 ? -3 : 9)) + 2) / 5) + day - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146_097 + doe - 719_468;
}

/** The Gregorian date of a day number. */
export function civilDate(days: number): { year: number; month: number; day: number } {
  const z = days + 719_468;
  const era = Math.floor(z / 146_097);
  const doe = z - era * 146_097;
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36_524) - Math.floor(doe / 146_096)) / 365);
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100));
  const mp = Math.floor((5 * doy + 2) / 153);
  const day = doy - Math.floor((153 * mp + 2) / 5) + 1;
  const month = mp + (mp < 10 ? 3 : -9);
  return { year: era * 400 + yoe + (month <= 2 ? 1 : 0), month, day };
}

/** yyyymmdd, as the index stores service and pack dates. */
export function encodedDate(days: number): number {
  const { year, month, day } = civilDate(days);
  return year * 10_000 + month * 100 + day;
}

/** The day number of a yyyymmdd date, or null for one out of range (`date(from:)` of the iPhone). */
export function decodedDate(encoded: number): number | null {
  if (!Number.isInteger(encoded) || encoded < 1_000_0000 || encoded > 9_999_1231) return null;
  const year = Math.floor(encoded / 10_000);
  const month = Math.floor(encoded / 100) % 100;
  const day = encoded % 100;
  if (month < 1 || month > 12 || day < 1 || day > 31) return null;
  return dayNumber(year, month, day);
}

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(timeZone: string): Intl.DateTimeFormat {
  let value = formatters.get(timeZone);
  if (!value) {
    value = new Intl.DateTimeFormat("en-US", {
      timeZone,
      calendar: "gregory",
      numberingSystem: "latn",
      hourCycle: "h23",
      year: "numeric",
      month: "numeric",
      day: "numeric",
      hour: "numeric",
      minute: "numeric",
      second: "numeric",
    });
    formatters.set(timeZone, value);
  }
  return value;
}

/** True when the runtime knows the zone. */
export function isTimeZone(timeZone: string): boolean {
  try {
    formatter(timeZone);
    return true;
  } catch {
    return false;
  }
}

type WallClock = { day: number; hour: number; minute: number; second: number };

/** The wall clock of `instant` (epoch milliseconds) in `timeZone`. */
export function wallClock(instant: number, timeZone: string): WallClock {
  const parts: Record<string, number> = {};
  for (const part of formatter(timeZone).formatToParts(new Date(instant))) {
    if (part.type !== "literal") parts[part.type] = Number(part.value);
  }
  return {
    day: dayNumber(parts.year, parts.month, parts.day),
    hour: parts.hour,
    minute: parts.minute,
    second: parts.second,
  };
}

/** The calendar day of `instant` in `timeZone` (`calendar.startOfDay(for:)`). */
export function localDay(instant: number, timeZone: string): number {
  return wallClock(instant, timeZone).day;
}

/** Offset of `timeZone` from UTC at `instant`, in milliseconds (whole seconds). */
function offsetAt(instant: number, timeZone: string): number {
  const clock = wallClock(instant, timeZone);
  const asUtc = clock.day * MS_PER_DAY + ((clock.hour * 60 + clock.minute) * 60 + clock.second) * 1000;
  return asUtc - Math.floor(instant / 1000) * 1000;
}

/**
 * The instant of a wall-clock time of `day` in `timeZone`. Only noon is asked (`gtfsInstant`),
 * which no European zone skips or repeats.
 */
export function zonedInstant(day: number, hour: number, timeZone: string): number {
  const guess = day * MS_PER_DAY + hour * 3_600_000;
  const first = guess - offsetAt(guess, timeZone);
  const second = offsetAt(first, timeZone);
  return guess - second;
}

/**
 * GTFS time is elapsed time from local noon minus twelve hours: adding the seconds to that instant
 * crosses the 23- and 25-hour service days of daylight saving time correctly.
 */
export function gtfsInstant(serviceDay: number, seconds: number, timeZone: string): number {
  return zonedInstant(serviceDay, 12, timeZone) - 12 * 3_600_000 + seconds * 1000;
}

/** «HH:mm» of `instant` in `timeZone`. */
export function timeString(instant: number, timeZone: string): string {
  const { hour, minute } = wallClock(instant, timeZone);
  return `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
}
