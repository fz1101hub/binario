/**
 * Test fixtures only (imported by *.test.ts, never by the server): statistics documents in the
 * `stats_v1.json.gz` format, built from day-by-day delays so the expected counts are easy to read.
 */

export const BUCKETS = [0, 2, 5, 10, 15, 20, 30, 60] as const;

export type FixtureStop = [string, number, number, number[], ...unknown[]];

/**
 * A stop observed with these delays in minutes (one per day): samples, mean (early counts as 0,
 * one decimal) and cumulative counts as the pipeline writes them. `extra` are the elements after
 * the cumulative counts: suppressed days, then optionally max delay, scheduled minute, platform
 * and weekdays.
 */
export function stopFromDelays(code: string, delays: readonly number[], extra: readonly unknown[] = [0]): FixtureStop {
  const samples = delays.length;
  const total = delays.reduce((sum, delay) => sum + Math.max(0, delay), 0);
  const mean = samples ? Math.round((total / samples) * 10) / 10 : 0;
  return [code, samples, mean, BUCKETS.map(limit => delays.filter(delay => delay <= limit).length), ...extra];
}

export function fixtureTrain(
  number: string,
  origin: string,
  category: string,
  days: number,
  cancelled: number,
  stops: readonly FixtureStop[]
) {
  return { c: category, d: days, n: number, o: origin, s: stops, x: cancelled };
}

export function statsDocument(trains: readonly unknown[], fields: Record<string, unknown> = {}) {
  return JSON.stringify({
    buckets: BUCKETS,
    generatedAt: "2026-11-10T04:00:00Z",
    schema: "binario-punctuality-v1",
    source: "ViaggiaTreno, elaborazione Binario (non ufficiale)",
    trains,
    windowDays: 30,
    windowEnd: "2026-11-09",
    windowStart: "2026-10-11",
    ...fields,
  });
}

export const ROMA_TERMINI = "S08409";
export const TIVOLI = "S08508";
export const GUIDONIA = "S08502";
export const MILANO_CENTRALE = "S01700";
export const FIRENZE_SMN = "S06421";

/** 27 arrivals at the terminus: 22 within 5 minutes, mean 5.8 minutes. */
export const FR_9558_TERMINUS_DELAYS = [0, 0, 0, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 7, 9, 12, 20, 40];

/**
 * A small network: FR 9558 Roma Termini → Milano (and a rarer 9558 from Firenze), three regional
 * trains Roma Termini → Tivoli (30 arrivals at Tivoli, 28 within 15 minutes, mean 4.8), one in the
 * other direction, a train cancelled every day and one with too few days (to Guidonia).
 */
export function networkDocument(fields: Record<string, unknown> = {}) {
  return statsDocument(
    [
      fixtureTrain("9558", ROMA_TERMINI, "FR", 28, 1, [
        stopFromDelays(ROMA_TERMINI, Array(27).fill(0), [0, 0, 420, ["12", 20, 27], null]),
        stopFromDelays(FIRENZE_SMN, Array(27).fill(2), [0, 2, 515, null, null]),
        stopFromDelays(MILANO_CENTRALE, FR_9558_TERMINUS_DELAYS, [1, 40, 610, ["21", 15, 25], null]),
      ]),
      fixtureTrain("9558", FIRENZE_SMN, "FR", 6, 0, [
        stopFromDelays(FIRENZE_SMN, [0, 0, 0, 0, 0, 0]),
        stopFromDelays(MILANO_CENTRALE, [30, 30, 30, 30, 30, 30]),
      ]),
      fixtureTrain("2301", ROMA_TERMINI, "REG", 10, 0, [
        stopFromDelays(ROMA_TERMINI, Array(10).fill(0)),
        stopFromDelays(GUIDONIA, Array(10).fill(1)),
        stopFromDelays(TIVOLI, [0, 0, 1, 2, 3, 5, 8, 10, 14, 16]),
      ]),
      fixtureTrain("2303", ROMA_TERMINI, "REG", 10, 0, [
        stopFromDelays(ROMA_TERMINI, Array(10).fill(0)),
        stopFromDelays(TIVOLI, [0, 0, 0, 0, 0, 0, 0, 0, 0, 30]),
      ]),
      fixtureTrain("2305", ROMA_TERMINI, "REG", 10, 0, [
        stopFromDelays(ROMA_TERMINI, Array(10).fill(0)),
        stopFromDelays(TIVOLI, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]),
      ]),
      fixtureTrain("2302", TIVOLI, "REG", 10, 0, [
        stopFromDelays(TIVOLI, Array(10).fill(0)),
        stopFromDelays(ROMA_TERMINI, Array(10).fill(60)),
      ]),
      fixtureTrain("7777", ROMA_TERMINI, "IC", 7, 7, []),
      fixtureTrain("1234", ROMA_TERMINI, "REG", 3, 0, [
        stopFromDelays(ROMA_TERMINI, [0, 0, 0]),
        stopFromDelays(GUIDONIA, [0, 0, 0]),
      ]),
    ],
    fields
  );
}

/** Catalogue entries in the shape of `curated_it_rail_stations.json`. */
export function catalogDocument() {
  return JSON.stringify({
    schemaVersion: 1,
    kind: "curated-rail-station-catalog",
    stations: [
      { id: ROMA_TERMINI, name: "Roma Termini", railwayCode: ROMA_TERMINI, aliases: ["Rome Termini"] },
      { id: TIVOLI, name: "Tivoli", railwayCode: TIVOLI, aliases: [] },
      { id: GUIDONIA, name: "Guidonia Montecelio S. Angelo", railwayCode: GUIDONIA, aliases: [] },
      { id: MILANO_CENTRALE, name: "Milano Centrale", railwayCode: MILANO_CENTRALE, aliases: ["Mailand"] },
      { id: FIRENZE_SMN, name: "Firenze S. M. Novella", railwayCode: FIRENZE_SMN, aliases: ["Firenze Santa Maria Novella"] },
      { id: "N00018", name: "Abbiate Guazzone", railwayCode: "N00018", aliases: [] },
    ],
  });
}

/** Seven `[samples, le5, le15]` triples, Monday … Sunday, of a stop whose delays were all 0. */
export function weekdaysOnTime(samplesPerDay: readonly number[]) {
  return samplesPerDay.map(count => [count, count, count]);
}

/**
 * A train from Roma Termini to Tivoli leaving Roma Termini at `scheduled` (minutes of the day, or
 * null when the file has none) and arriving at Tivoli with `tivoliDelays`; `weekdays` are the
 * triples at Roma Termini (null below 14 samples).
 */
export function departure(
  number: string,
  category: string,
  scheduled: number | null,
  tivoliDelays: readonly number[],
  weekdays: unknown = null
) {
  const days = tivoliDelays.length;
  return fixtureTrain(number, ROMA_TERMINI, category, days, 0, [
    stopFromDelays(ROMA_TERMINI, Array(days).fill(0), [0, 0, scheduled, null, weekdays]),
    stopFromDelays(TIVOLI, tivoliDelays, [0, Math.max(0, ...tivoliDelays), scheduled === null ? null : scheduled + 40, null, null]),
  ]);
}

/** `onTime` arrivals 3 minutes late, the others 25 minutes late: late for every category. */
export function arrivals(samples: number, onTime: number) {
  return [...Array(onTime).fill(3), ...Array(samples - onTime).fill(25)];
}

/**
 * Departures from Roma Termini to Tivoli around a Monday morning at 07:30: the 2157 at 8:12 (9 of
 * 10 on time), 2161 at 9:12 (8 of 10), 2165 at 10:12 (7 of 10) and 2169 at 10:20 (6 of 10); left
 * out are the 2151 (7:00, gone), 2171 (11:00, beyond three hours), 2159 (no scheduled time), 2163
 * (4 samples) and the weekend-only 2167 (8:30, every day on time on Saturdays and Sundays).
 */
export function departuresDocument() {
  return statsDocument([
    departure("2151", "REG", 420, arrivals(10, 10)),
    departure("2157", "REG", 492, arrivals(10, 9)),
    departure("2159", "REG", null, arrivals(10, 10)),
    departure("2161", "REG", 552, arrivals(10, 8)),
    departure("2163", "REG", 500, arrivals(4, 4)),
    departure("2165", "REG", 612, arrivals(10, 7)),
    departure("2167", "REG", 510, arrivals(16, 16), weekdaysOnTime([0, 0, 0, 0, 0, 8, 8])),
    departure("2169", "REG", 620, arrivals(10, 6)),
    departure("2171", "REG", 660, arrivals(10, 10)),
  ]);
}

/** Monday 9 November 2026 at 07:30 in Rome. */
export const MONDAY_MORNING = "2026-11-09T06:30:00Z";
