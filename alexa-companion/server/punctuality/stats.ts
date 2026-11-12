/**
 * Binario's unofficial network punctuality statistics (`stats_v1.json.gz`, format in
 * docs/data-sources/PUNCTUALITY_STATS.md): how every observed train ran over the last 30 days,
 * aggregated from ViaggiaTreno observations by the repository pipeline. The same file the Android
 * app embeds; the skill answers from it and never asks ViaggiaTreno anything.
 *
 * The reader keeps only what the voice answers need (per stop: station code, samples, mean delay,
 * the samples within the train's on-time limit, the scheduled minute and the weekdays observed),
 * so about nine thousand trains stay a few megabytes. It is tolerant like the app's reader: a file that is malformed, of another schema or
 * over the bounds gives null (the skill says the statistics are not available), and a train whose
 * fields do not add up is left out.
 */

export const PUNCTUALITY_SCHEMA = "binario-punctuality-v1";
/** Below this many samples at the stop considered nothing is said (shared spec). */
export const MIN_SAMPLES = 5;
/** Below this many samples in all, a route says nothing (as the app's route aggregate). */
export const MIN_AGGREGATE_SAMPLES = 20;
export const HIGH_SPEED_ON_TIME_MINUTES = 5;
export const OTHER_ON_TIME_MINUTES = 15;

/** Categories measured at 5 minutes, as the operators and the app do; every other category at 15. */
const HIGH_SPEED_CATEGORIES: ReadonlySet<string> = new Set(["FR", "FA", "FB", "EC", "ES", "ESC", "EN", "ITALO"]);
const STATION_CODE = /^S[0-9]{5}$/;
const MAX_TEXT = 16;
const MAX_DELAY_MINUTES = 24 * 60;
const MAX_WINDOW_DAYS = 366;
const MINUTES_PER_DAY = 24 * 60;
/** Scheduled minutes are counted from the midnight of the run's departure day: at most three days, as the app reads them. */
const MAX_SCHEDULED_MINUTE = 3 * MINUTES_PER_DAY;
/** {@link PunctualityTrain.scheduledMinute} of a stop whose file gives no (valid) scheduled minute. */
export const NO_SCHEDULED_MINUTE = -1;
/** {@link PunctualityTrain.daysSinceSeen} of a train whose file gives no (valid) `l`. */
export const NOT_DATED = -1;
/**
 * A train not observed for more than this many days before the window's end is not proposed as
 * the most punctual: withdrawn, renumbered or retimed at a timetable change, its past record would
 * still rank it.
 */
export const MAX_DAYS_SINCE_SEEN = 7;
/** Bit set in {@link PunctualityTrain.weekdays} when the file lists the weekdays of the stop. */
const WEEKDAYS_KNOWN = 0x80;

export type PunctualityLimits = {
  /** The pipeline's own bounds (tools/build_punctuality_stats.py): 50,000 trains, 400 stops per train. */
  maxTrains: number;
  maxStopsPerTrain: number;
  maxBuckets: number;
  /** Samples and days per entry: far above any window. */
  maxCount: number;
};

export const DEFAULT_PUNCTUALITY_LIMITS: PunctualityLimits = {
  maxTrains: 50_000,
  maxStopsPerTrain: 400,
  maxBuckets: 16,
  maxCount: 10_000,
};

export function onTimeLimitMinutes(category: string): number {
  return HIGH_SPEED_CATEGORIES.has(category.trim().toUpperCase()) ? HIGH_SPEED_ON_TIME_MINUTES : OTHER_ON_TIME_MINUTES;
}

/**
 * The number a train is known by: the run after the line of a Trenord timetable number
 * («RE_11 - 2153» → «2153», as the app shows it), otherwise the number as published.
 */
export function publicTrainNumber(number: string): string {
  const separator = number.lastIndexOf(" - ");
  const run = separator >= 0 ? number.slice(separator + 3).trim() : "";
  return run || number.trim();
}

/** "9558" for "9558", "FR 9558", "09558" and "RE_11 - 9558"; a number without digits keeps its letters. */
export function trainNumberKey(number: string): string {
  const digits = publicTrainNumber(number).replace(/[^0-9]/g, "").replace(/^0+/, "");
  return digits || number.trim().replace(/\s+/g, "").toUpperCase();
}

export function isStationCode(code: unknown): code is string {
  return typeof code === "string" && STATION_CODE.test(code);
}

/**
 * One train (number and origin) with its stops in run order. `onTime[i]` counts the samples at
 * stop i with a delay of at most the train's own limit ({@link onTimeLimitMinutes}).
 */
export class PunctualityTrain {
  constructor(
    readonly number: string,
    readonly originCode: string,
    readonly category: string,
    /** Days the run is known (arrived or cancelled), cancellations included. */
    readonly daysObserved: number,
    readonly daysCancelled: number,
    readonly codes: readonly string[],
    readonly samples: Uint16Array,
    readonly onTime: Uint16Array,
    readonly meanTenths: Int16Array,
    /**
     * Scheduled arrival (departure at the origin) in minutes from the midnight of the run's
     * departure day, Europe/Rome, on the most recent observed day; may pass 1439 after midnight.
     * {@link NO_SCHEDULED_MINUTE} where the file gives none (older files, `null`).
     */
    readonly scheduledMinute: Int16Array = new Int16Array(codes.length).fill(NO_SCHEDULED_MINUTE),
    /**
     * Weekdays (of the run's departure day) with at least one sample at the stop: bit 0 Monday …
     * bit 6 Sunday, plus {@link WEEKDAYS_KNOWN}; 0 where the file has no weekdays (below 14 samples).
     */
    readonly weekdays: Uint8Array = new Uint8Array(codes.length),
    /**
     * Days between the train's most recent observed day and the window's end (0: seen on the last
     * day), the optional train field `l`; {@link NOT_DATED} in files without it.
     */
    readonly daysSinceSeen: number = NOT_DATED
  ) {}

  get stopCount() {
    return this.codes.length;
  }

  get onTimeLimitMinutes() {
    return onTimeLimitMinutes(this.category);
  }

  meanDelayMinutes(index: number) {
    return this.meanTenths[index] / 10;
  }

  /** Whether the file lists the weekdays of the stop (14 samples or more there). */
  hasWeekdays(index: number) {
    return (this.weekdays[index] & WEEKDAYS_KNOWN) !== 0;
  }

  /**
   * False only when the file lists the stop's weekdays and none of its samples fell on
   * `isoWeekday` (1 Monday … 7 Sunday): the train does not stop there on that day of the week.
   * Without the weekdays it says nothing: {@link PunctualityStats.mayRunOn} weighs the days observed.
   */
  mayRunOn(index: number, isoWeekday: number) {
    const mask = this.weekdays[index];
    return !(mask & WEEKDAYS_KNOWN) || (mask & (1 << (isoWeekday - 1))) !== 0;
  }
}

export class PunctualityStats {
  private readonly byNumber = new Map<string, PunctualityTrain[]>();
  private covered: number | undefined;

  constructor(
    readonly windowEnd: string,
    readonly windowDays: number,
    readonly trains: readonly PunctualityTrain[]
  ) {
    for (const train of trains) {
      const key = trainNumberKey(train.number);
      const list = this.byNumber.get(key);
      if (list) list.push(train);
      else this.byNumber.set(key, [train]);
    }
  }

  /**
   * The days the statistics really cover, never more than the window: in the first weeks of the
   * collection the window is not full yet.
   */
  get coveredDays(): number {
    if (this.covered === undefined) {
      let observed = 0;
      for (const train of this.trains) observed = Math.max(observed, train.daysObserved);
      this.covered = Math.max(1, Math.min(this.windowDays, observed || this.windowDays));
    }
    return this.covered;
  }

  /** How many of the {@link coveredDays}, the last days of the window up to `windowEnd`, fall on `isoWeekday`. */
  coveredDaysOn(isoWeekday: number): number {
    const endWeekday = ((new Date(`${this.windowEnd}T00:00:00Z`).getUTCDay() + 6) % 7) + 1;
    const latest = (((endWeekday - isoWeekday) % 7) + 7) % 7;
    return latest >= this.coveredDays ? 0 : Math.floor((this.coveredDays - 1 - latest) / 7) + 1;
  }

  /**
   * Whether `train` is known to stop at `stop` on the runs that depart on `isoWeekday`. With the
   * stop's weekdays in the file, as they say ({@link PunctualityTrain.mayRunOn}). Without them (the
   * pipeline leaves them out below 14 samples, so always for a train of the weekend or of the first
   * weeks of the collection) only when the days observed there prove it as the weekdays would: the
   * covered days missing (no sample at the stop and no cancellation) are fewer than the
   * `isoWeekday`s among them, so at least one of its runs fell on that weekday. A weekend-only
   * train (8 days of 30) is then never proposed on a Tuesday, and neither on a Saturday, since
   * nothing tells which days it runs; on a weekday the covered days do not include yet (a
   * collection restarted on a Monday has no Sunday) no train is, not even one seen every day.
   */
  mayRunOn(train: PunctualityTrain, stop: number, isoWeekday: number): boolean {
    if (train.hasWeekdays(stop)) return train.mayRunOn(stop, isoWeekday);
    const known = Math.min(train.daysObserved, train.samples[stop] + train.daysCancelled);
    const missing = Math.max(0, this.coveredDays - known);
    return missing < this.coveredDaysOn(isoWeekday);
  }

  /** Every train published with the number; the same number from another origin is another train. */
  trainsNumbered(number: string): readonly PunctualityTrain[] {
    return this.byNumber.get(trainNumberKey(number)) ?? [];
  }
}

/* ------------------------------------------------------------------------------------------ */
/* Queries                                                                                      */
/* ------------------------------------------------------------------------------------------ */

export type TrainPunctuality =
  | { kind: "unknown" }
  | { kind: "fewData" }
  /** Several trains with the number, from different origins, each with figures: nothing tells which one is meant. */
  | { kind: "sharedNumber" }
  | { kind: "cancelledEveryDay"; days: number }
  | {
      kind: "stats";
      samples: number;
      onTime: number;
      /** Whole percentage of the samples on time at the terminus. */
      percent: number;
      limitMinutes: number;
      /** The mean delay at the terminus in whole minutes; an early average is no delay. */
      meanDelayMinutes: number;
      days: number;
      cancelledDays: number;
    };

function percentOf(part: number, whole: number) {
  return whole > 0 ? Math.round((part * 100) / whole) : 0;
}

function shownDelay(minutes: number) {
  return Math.max(0, Math.round(minutes));
}

/**
 * How the train numbered `number` arrives at its terminus (the last stop of the file). The same
 * number from different origins is another train: when only one of them has {@link MIN_SAMPLES}
 * samples at its terminus it is that one (the others say nothing anyway); when several have, no
 * figure is given rather than another train's ({@link TrainPunctuality} `sharedNumber`), as the app
 * gives none without an origin to tell them apart. A train cancelled on every one of at least
 * {@link MIN_SAMPLES} days says so.
 */
export function trainPunctuality(stats: PunctualityStats, number: string): TrainPunctuality {
  const candidates = stats.trainsNumbered(number);
  if (!candidates.length) return { kind: "unknown" };
  const terminusSamples = (train: PunctualityTrain) => train.samples[train.stopCount - 1] ?? 0;
  const running = candidates
    .filter(train => train.stopCount > 0)
    .sort((left, right) => terminusSamples(right) - terminusSamples(left) || left.originCode.localeCompare(right.originCode));
  const best = running[0];
  if (running.filter(train => terminusSamples(train) >= MIN_SAMPLES).length > 1) return { kind: "sharedNumber" };
  if (best && terminusSamples(best) >= MIN_SAMPLES) {
    const last = best.stopCount - 1;
    const samples = best.samples[last];
    const onTime = best.onTime[last];
    return {
      kind: "stats",
      samples,
      onTime,
      percent: percentOf(onTime, samples),
      limitMinutes: best.onTimeLimitMinutes,
      meanDelayMinutes: shownDelay(best.meanDelayMinutes(last)),
      days: best.daysObserved,
      cancelledDays: best.daysCancelled,
    };
  }
  const cancelled = candidates
    .filter(train => train.stopCount === 0 && train.daysObserved >= MIN_SAMPLES)
    .sort((left, right) => right.daysObserved - left.daysObserved)[0];
  if (cancelled) return { kind: "cancelledEveryDay", days: cancelled.daysObserved };
  return { kind: "fewData" };
}

export type RoutePunctuality = {
  trains: number;
  samples: number;
  onTime: number;
  percent: number;
  meanDelayMinutes: number;
  /** The days the statistics cover ({@link PunctualityStats.coveredDays}). */
  days: number;
  /** The on-time limits of the trains counted, ascending: [5], [15] or [5, 15]. */
  limits: number[];
};

type RouteEnds = { origins: ReadonlySet<string>; destinations: ReadonlySet<string> };

/** The station codes of the two ends, or null without codes or when the ends share a code. */
function routeEnds(originCodes: Iterable<string>, destinationCodes: Iterable<string>): RouteEnds | null {
  const origins = new Set([...originCodes].filter(isStationCode));
  const destinations = new Set([...destinationCodes].filter(isStationCode));
  if (!origins.size || !destinations.size) return null;
  for (const code of origins) if (destinations.has(code)) return null;
  return { origins, destinations };
}

/**
 * Where `train` serves the route: its first stop at one of the origins and its first later stop
 * at one of the destinations, or null when it does not go from one to the other.
 */
function routeStops(train: PunctualityTrain, { origins, destinations }: RouteEnds): [number, number] | null {
  const boarding = train.codes.findIndex(code => origins.has(code));
  if (boarding < 0) return null;
  for (let index = boarding + 1; index < train.stopCount; index++)
    if (destinations.has(train.codes[index])) return [boarding, index];
  return null;
}

/**
 * Every train that stops at one of `originCodes` (its first such stop) and later at one of
 * `destinationCodes`, measured on arrival there against its own limit; the mean delay is weighted
 * by the samples. Several codes per end cover a station name shared by more than one code. Null
 * without codes, when the two ends share a code or below {@link MIN_AGGREGATE_SAMPLES} samples.
 */
export function routePunctuality(
  stats: PunctualityStats,
  originCodes: Iterable<string>,
  destinationCodes: Iterable<string>
): RoutePunctuality | null {
  const ends = routeEnds(originCodes, destinationCodes);
  if (!ends) return null;
  let trains = 0;
  let samples = 0;
  let onTime = 0;
  let delaySum = 0;
  const limits = new Set<number>();
  for (const train of stats.trains) {
    const stops = routeStops(train, ends);
    if (!stops) continue;
    const alighting = stops[1];
    const count = train.samples[alighting];
    if (count <= 0) continue;
    trains += 1;
    samples += count;
    onTime += train.onTime[alighting];
    delaySum += train.meanDelayMinutes(alighting) * count;
    limits.add(train.onTimeLimitMinutes);
  }
  if (samples < MIN_AGGREGATE_SAMPLES) return null;
  return {
    trains,
    samples,
    onTime,
    percent: percentOf(onTime, samples),
    meanDelayMinutes: shownDelay(delaySum / samples),
    days: stats.coveredDays,
    limits: [...limits].sort((left, right) => left - right),
  };
}

/** «In the next three hours»: the departures the most-punctual question looks at. */
export const DEPARTURE_WINDOW_MINUTES = 180;
/** The best train and up to two more. */
export const MOST_PUNCTUAL_LIMIT = 3;

/** A moment on the Italian railway clock (Europe/Rome). */
export type RailClock = {
  /** Minutes since local midnight, 0–1439. */
  minuteOfDay: number;
  /** ISO weekday of the local day: 1 Monday … 7 Sunday. */
  isoWeekday: number;
  /** The local day, `YYYY-MM-DD`: on a national holiday the railways run their Sunday service. */
  date: string;
};

const ISO_WEEKDAYS: Readonly<Record<string, number>> = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 };
const railClockFormat = new Intl.DateTimeFormat("en-US", {
  timeZone: "Europe/Rome",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  weekday: "short",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

/** The Europe/Rome minute of the day, weekday and day of `instant`, daylight saving included. */
export function railClock(instant: Date): RailClock {
  const parts = Object.fromEntries(railClockFormat.formatToParts(instant).map(part => [part.type, part.value]));
  return {
    minuteOfDay: (Number(parts.hour) % 24) * 60 + Number(parts.minute),
    isoWeekday: ISO_WEEKDAYS[parts.weekday] ?? 1,
    date: `${parts.year}-${parts.month}-${parts.day}`,
  };
}

/** `date` (`YYYY-MM-DD`) moved by `days` calendar days. */
function addDays(date: string, days: number): string {
  const instant = new Date(`${date}T00:00:00Z`);
  instant.setUTCDate(instant.getUTCDate() + days);
  return instant.toISOString().slice(0, 10);
}

/** Easter Sunday of `year` in the Gregorian calendar (anonymous algorithm), `YYYY-MM-DD`. */
function easterSunday(year: number): string {
  const a = year % 19;
  const b = Math.floor(year / 100);
  const c = year % 100;
  const d = Math.floor(b / 4);
  const e = b % 4;
  const f = Math.floor((b + 8) / 25);
  const g = Math.floor((b - f + 1) / 3);
  const h = (19 * a + b - d - g + 15) % 30;
  const i = Math.floor(c / 4);
  const k = c % 4;
  const l = (32 + 2 * e + 2 * i - h - k) % 7;
  const m = Math.floor((a + 11 * h + 22 * l) / 451);
  const month = Math.floor((h + l - 7 * m + 114) / 31);
  const day = ((h + l - 7 * m + 114) % 31) + 1;
  return `${year}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

const FIXED_HOLIDAYS: ReadonlySet<string> = new Set(["01-01", "01-06", "04-25", "05-01", "06-02", "08-15", "11-01", "12-08", "12-25", "12-26"]);

/**
 * Whether `date` (`YYYY-MM-DD`) is an Italian national holiday, on which the railways run their
 * Sunday service: the same calendar as the Android app's `ItalianRailServiceCalendar` (fixed
 * dates, Easter Sunday and Monday, and 4 October from 2026).
 */
export function isItalianHoliday(date: string): boolean {
  const year = Number(date.slice(0, 4));
  const monthDay = date.slice(5);
  if (FIXED_HOLIDAYS.has(monthDay)) return true;
  // San Francesco d'Assisi, national holiday again from 2026 (legge 151/2025).
  if (year >= 2026 && monthDay === "10-04") return true;
  const easter = easterSunday(year);
  return date === easter || date === addDays(easter, 1);
}

/**
 * A share said «in tenths» or as a percentage, never claiming «always» or «never» when it is
 * not: all or none on time only when every sample or no sample was.
 */
export function boundedShare(part: number, whole: number, scale: number): number {
  if (whole <= 0 || part <= 0) return 0;
  if (part >= whole) return scale;
  return Math.min(scale - 1, Math.max(1, Math.round((part * scale) / whole)));
}

/**
 * The days the train ran and arrived on time at the stop, of the days observed, as an exact
 * fraction: (days − cancelled) × on time ÷ (days × samples). With no day known, no cancellation
 * is counted (on time ÷ samples).
 */
export function daysOnTime(train: PunctualityTrain, stop: number): { part: number; whole: number } {
  const samples = train.samples[stop];
  const onTime = train.onTime[stop];
  if (train.daysObserved <= 0) return { part: onTime, whole: samples };
  const cancelled = Math.min(Math.max(train.daysCancelled, 0), train.daysObserved);
  return { part: (train.daysObserved - cancelled) * onTime, whole: train.daysObserved * samples };
}

export type PunctualDeparture = {
  /** The number as the app shows it ({@link publicTrainNumber}). */
  number: string;
  /** Usual departure from the route's origin: the scheduled minute there, 0–1439, of the latest observed day. */
  minuteOfDay: number;
  /** Minutes from now to that departure, 0 to {@link DEPARTURE_WINDOW_MINUTES} − 1. */
  minutesUntil: number;
  /** Arrivals at the route's destination: samples and how many within the train's own limit. */
  samples: number;
  onTime: number;
  /**
   * The days it ran and arrived on time, of the days observed, in tenths (0–10) and in percent,
   * bounded ({@link boundedShare}): a cancelled day counts as a day not on time.
   */
  tenths: number;
  percent: number;
  limitMinutes: number;
  meanDelayMinutes: number;
};

/**
 * «Qual è il treno più puntuale per casa lavoro?»: the trains of the statistics that stop at one
 * of `originCodes` and later at one of `destinationCodes` (as {@link routePunctuality}), whose
 * scheduled minute at that origin (`% 1440`) falls in the {@link DEPARTURE_WINDOW_MINUTES} after
 * `clock` (past midnight too) and that have at least {@link MIN_SAMPLES} arrivals at the
 * destination. A train without a scheduled minute there is left out, so is one the file says was last
 * seen more than {@link MAX_DAYS_SINCE_SEEN} days before the window's end, and so is one not known to run
 * on the day of that departure ({@link PunctualityStats.mayRunOn}: its weekdays at the origin or,
 * without them, its days observed there; a national holiday counts as a Sunday, {@link isItalianHoliday}).
 *
 * Ranked by the share of observed days on which the train ran and arrived on time at the
 * destination against its own limit ({@link daysOnTime}, so a train often cancelled is not first
 * for being punctual on the days it runs), then by on-time share of its arrivals, then by mean
 * delay there, then by the earlier departure; every share exact, in integers. At most `limit`
 * trains. Null without codes or when the ends share a code; empty when no such train leaves in
 * the window.
 */
export function mostPunctualDepartures(
  stats: PunctualityStats,
  originCodes: Iterable<string>,
  destinationCodes: Iterable<string>,
  clock: RailClock,
  limit: number = MOST_PUNCTUAL_LIMIT
): PunctualDeparture[] | null {
  const ends = routeEnds(originCodes, destinationCodes);
  if (!ends) return null;
  type Candidate = { train: PunctualityTrain; alighting: number; minuteOfDay: number; minutesUntil: number };
  const candidates: Candidate[] = [];
  for (const train of stats.trains) {
    const stops = routeStops(train, ends);
    if (!stops) continue;
    const [boarding, alighting] = stops;
    if (train.samples[alighting] < MIN_SAMPLES) continue;
    // Only files that date the trains can tell a train that stopped running.
    if (train.daysSinceSeen > MAX_DAYS_SINCE_SEEN) continue;
    const scheduled = train.scheduledMinute[boarding];
    if (scheduled < 0) continue;
    const minuteOfDay = scheduled % MINUTES_PER_DAY;
    const minutesUntil = (minuteOfDay - clock.minuteOfDay + MINUTES_PER_DAY) % MINUTES_PER_DAY;
    if (minutesUntil >= DEPARTURE_WINDOW_MINUTES) continue;
    // The weekdays are those of the run's departure day: a stop reached after midnight belongs to
    // the run of the day before. A national holiday runs the Sunday service.
    const daysAfterToday = clock.minuteOfDay + minutesUntil >= MINUTES_PER_DAY ? 1 : 0;
    const runDay = daysAfterToday - Math.floor(scheduled / MINUTES_PER_DAY);
    const runWeekday = isItalianHoliday(addDays(clock.date, runDay)) ? 7 : ((((clock.isoWeekday - 1 + runDay) % 7) + 7) % 7) + 1;
    if (!stats.mayRunOn(train, boarding, runWeekday)) continue;
    candidates.push({ train, alighting, minuteOfDay, minutesUntil });
  }
  candidates.sort((left, right) => {
    const leftSamples = left.train.samples[left.alighting];
    const rightSamples = right.train.samples[right.alighting];
    const leftDays = daysOnTime(left.train, left.alighting);
    const rightDays = daysOnTime(right.train, right.alighting);
    return (
      rightDays.part * leftDays.whole - leftDays.part * rightDays.whole ||
      right.train.onTime[right.alighting] * leftSamples - left.train.onTime[left.alighting] * rightSamples ||
      left.train.meanTenths[left.alighting] - right.train.meanTenths[right.alighting] ||
      left.minutesUntil - right.minutesUntil ||
      left.train.number.localeCompare(right.train.number) ||
      left.train.originCode.localeCompare(right.train.originCode)
    );
  });
  return candidates.slice(0, Math.max(0, limit)).map(({ train, alighting, minuteOfDay, minutesUntil }) => {
    const samples = train.samples[alighting];
    const onTime = train.onTime[alighting];
    const days = daysOnTime(train, alighting);
    return {
      number: publicTrainNumber(train.number),
      minuteOfDay,
      minutesUntil,
      samples,
      onTime,
      tenths: boundedShare(days.part, days.whole, 10),
      percent: boundedShare(days.part, days.whole, 100),
      limitMinutes: train.onTimeLimitMinutes,
      meanDelayMinutes: shownDelay(train.meanDelayMinutes(alighting)),
    };
  });
}

/* ------------------------------------------------------------------------------------------ */
/* Reader                                                                                       */
/* ------------------------------------------------------------------------------------------ */

const QUOTE = 0x22;
const BACKSLASH = 0x5c;
const OPEN_BRACE = 0x7b;
const CLOSE_BRACE = 0x7d;
const OPEN_BRACKET = 0x5b;
const CLOSE_BRACKET = 0x5d;
const COMMA = 0x2c;
const COLON = 0x3a;

function isWhitespace(code: number) {
  return code === 0x20 || code === 0x0a || code === 0x0d || code === 0x09;
}

function skipWhitespace(text: string, position: number) {
  while (position < text.length && isWhitespace(text.charCodeAt(position))) position++;
  return position;
}

function expectAt(text: string, position: number, code: number) {
  if (text.charCodeAt(position) !== code) throw new SyntaxError(`unexpected character at ${position}`);
  return position + 1;
}

function endOfString(text: string, start: number) {
  expectAt(text, start, QUOTE);
  for (let index = start + 1; index < text.length; index++) {
    const code = text.charCodeAt(index);
    if (code === BACKSLASH) index++;
    else if (code === QUOTE) return index + 1;
  }
  throw new SyntaxError("unterminated string");
}

/**
 * Index just past the JSON value that starts at `start`. Only the extent is found here, so a train
 * can be decoded on its own; JSON.parse then checks the value's syntax.
 */
function endOfValue(text: string, start: number) {
  const first = text.charCodeAt(start);
  if (first === QUOTE) return endOfString(text, start);
  if (first === OPEN_BRACE || first === OPEN_BRACKET) {
    let depth = 0;
    for (let index = start; index < text.length; index++) {
      const code = text.charCodeAt(index);
      if (code === QUOTE) index = endOfString(text, index) - 1;
      else if (code === OPEN_BRACE || code === OPEN_BRACKET) depth++;
      else if ((code === CLOSE_BRACE || code === CLOSE_BRACKET) && --depth === 0) return index + 1;
    }
    throw new SyntaxError("unterminated value");
  }
  let index = start;
  while (index < text.length) {
    const code = text.charCodeAt(index);
    if (code === COMMA || code === CLOSE_BRACE || code === CLOSE_BRACKET || isWhitespace(code)) break;
    index++;
  }
  if (index === start) throw new SyntaxError(`missing value at ${start}`);
  return index;
}

/** Calls `visit` with the source of every element of the JSON array at `start`; returns its end. */
function forEachElement(text: string, start: number, visit: (source: string) => void) {
  let position = skipWhitespace(text, expectAt(text, start, OPEN_BRACKET));
  if (text.charCodeAt(position) === CLOSE_BRACKET) return position + 1;
  for (;;) {
    position = skipWhitespace(text, position);
    const end = endOfValue(text, position);
    visit(text.slice(position, end));
    position = skipWhitespace(text, end);
    const code = text.charCodeAt(position);
    if (code === COMMA) position++;
    else if (code === CLOSE_BRACKET) return position + 1;
    else throw new SyntaxError(`unexpected character at ${position}`);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function intIn(value: unknown, min: number, max: number): number | undefined {
  return typeof value === "number" && Number.isInteger(value) && value >= min && value <= max ? value : undefined;
}

function shortText(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed && trimmed.length <= MAX_TEXT ? trimmed : undefined;
}

function isoDate(value: unknown): string | undefined {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined;
  const parsed = new Date(`${value}T00:00:00Z`);
  return Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value ? undefined : value;
}

/** The Europe/Rome calendar day of an instant, as the pipeline dates its window. */
function railDate(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const instant = new Date(value);
  if (Number.isNaN(instant.getTime())) return undefined;
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Rome", year: "numeric", month: "2-digit", day: "2-digit" }).format(instant);
}

function daysBetween(start: string, end: string) {
  return Math.round((Date.parse(`${end}T00:00:00Z`) - Date.parse(`${start}T00:00:00Z`)) / 86_400_000) + 1;
}

function validBuckets(value: unknown, max: number): number[] | undefined {
  if (!Array.isArray(value) || !value.length || value.length > max) return undefined;
  const buckets: number[] = [];
  for (const item of value) {
    const limit = intIn(item, 0, MAX_DELAY_MINUTES);
    if (limit === undefined || (buckets.length && limit <= buckets[buckets.length - 1])) return undefined;
    buckets.push(limit);
  }
  return buckets;
}

/**
 * Samples with a delay of at most `minutes`, interpolated linearly between the cumulative counts
 * of the two bucket limits around it: exact at a limit (5 and 15 are limits of the file), the
 * last count beyond the last limit and nothing below the first.
 */
export function countAtMost(cumulative: readonly number[], buckets: readonly number[], minutes: number): number {
  if (Number.isNaN(minutes) || minutes < buckets[0]) return 0;
  const last = buckets.length - 1;
  if (minutes >= buckets[last]) return cumulative[last];
  let bucket = 0;
  while (minutes >= buckets[bucket + 1]) bucket++;
  const low = cumulative[bucket];
  const high = cumulative[bucket + 1];
  return low + ((high - low) * (minutes - buckets[bucket])) / (buckets[bucket + 1] - buckets[bucket]);
}

type CompactStop = {
  code: string;
  samples: number;
  meanTenths: number;
  onTime: number;
  scheduledMinute: number;
  weekdays: number;
};

/**
 * The weekdays with samples of a stop's seven `[samples, le5, le15]` triples (Monday … Sunday), as
 * {@link PunctualityTrain.weekdays}; 0 for `null`, an absent element or one that does not add up
 * (the element is optional, so it never costs the train). The pipeline counts every sample of the
 * stop in its weekday; a file whose triples cover only part of the samples (the format allows it)
 * cannot say that a train never runs on a day, so it gives 0 too.
 */
function weekdayMask(value: unknown, samples: number): number {
  if (!Array.isArray(value) || value.length !== 7) return 0;
  let mask = WEEKDAYS_KNOWN;
  let total = 0;
  for (const [day, triple] of value.entries()) {
    if (!Array.isArray(triple) || triple.length !== 3) return 0;
    const [count, withinFive, withinFifteen] = triple as unknown[];
    const daySamples = intIn(count, 0, samples);
    const five = intIn(withinFive, 0, samples);
    const fifteen = intIn(withinFifteen, 0, samples);
    if (daySamples === undefined || five === undefined || fifteen === undefined || five > fifteen || fifteen > daySamples) return 0;
    total += daySamples;
    if (daySamples > 0) mask |= 1 << day;
  }
  return total === samples ? mask : 0;
}

function compactStop(
  value: unknown,
  buckets: readonly number[],
  limit: number,
  limits: PunctualityLimits,
  codes: Map<string, string>
): CompactStop | null {
  if (!Array.isArray(value) || value.length < 4) return null;
  // The max delay and the platform are not spoken; the scheduled minute (element 6) and the
  // weekdays (element 8) choose the trains of the most-punctual question.
  const [rawCode, rawSamples, mean, cumulative, suppressed, , rawScheduled, , rawWeekdays] = value as unknown[];
  const code = shortText(rawCode);
  const samples = intIn(rawSamples, 0, limits.maxCount);
  if (code === undefined || samples === undefined) return null;
  if (typeof mean !== "number" || !Number.isFinite(mean) || Math.abs(mean) > MAX_DELAY_MINUTES) return null;
  if (value.length > 4 && intIn(suppressed, 0, limits.maxCount) === undefined) return null;
  if (!Array.isArray(cumulative) || cumulative.length !== buckets.length) return null;
  let previous = 0;
  for (const count of cumulative) {
    if (intIn(count, previous, samples) === undefined) return null;
    previous = count as number;
  }
  const onTime = Math.min(samples, Math.max(0, Math.floor(countAtMost(cumulative as number[], buckets, limit) + 1e-9)));
  const interned = codes.get(code) ?? code;
  codes.set(interned, interned);
  return {
    code: interned,
    samples,
    meanTenths: Math.round(mean * 10),
    onTime,
    scheduledMinute: intIn(rawScheduled, 0, MAX_SCHEDULED_MINUTE - 1) ?? NO_SCHEDULED_MINUTE,
    weekdays: weekdayMask(rawWeekdays, samples),
  };
}

function compactTrain(
  value: unknown,
  buckets: readonly number[],
  limits: PunctualityLimits,
  codes: Map<string, string>
): PunctualityTrain | null {
  if (!isRecord(value)) return null;
  const number = shortText(value.n);
  const origin = shortText(value.o);
  const category = typeof value.c === "string" ? value.c.trim().toUpperCase() : "";
  const days = intIn(value.d, 0, limits.maxCount);
  const cancelled = value.x === undefined ? 0 : intIn(value.x, 0, limits.maxCount);
  // Optional (files of the pipeline from its extension on): an absent, null or invalid value only
  // leaves the train undated, it never costs the train.
  const daysSinceSeen = intIn(value.l, 0, MAX_WINDOW_DAYS) ?? NOT_DATED;
  if (!number || !origin || category.length > MAX_TEXT || days === undefined || cancelled === undefined || cancelled > days) return null;
  if (!Array.isArray(value.s) || value.s.length > limits.maxStopsPerTrain) return null;
  // A train cancelled on every day observed has no stops; any other train needs at least one.
  if (!value.s.length && !(days > 0 && cancelled === days)) return null;
  const limit = onTimeLimitMinutes(category);
  const stops: CompactStop[] = [];
  for (const raw of value.s) {
    const stop = compactStop(raw, buckets, limit, limits, codes);
    if (!stop) return null;
    stops.push(stop);
  }
  return new PunctualityTrain(
    number,
    codes.get(origin) ?? origin,
    category,
    days,
    cancelled,
    stops.map(stop => stop.code),
    Uint16Array.from(stops, stop => stop.samples),
    Uint16Array.from(stops, stop => stop.onTime),
    Int16Array.from(stops, stop => stop.meanTenths),
    Int16Array.from(stops, stop => stop.scheduledMinute),
    Uint8Array.from(stops, stop => stop.weekdays),
    daysSinceSeen
  );
}

/**
 * Reads the JSON document of `stats_v1.json.gz`. The top-level fields are decoded one by one and
 * the trains one train at a time, so the whole document is never held as objects. Null for a
 * syntax error, another schema, missing window or buckets, more than `maxTrains` entries or no
 * usable train.
 */
export function parsePunctualityStats(
  text: string,
  limits: PunctualityLimits = DEFAULT_PUNCTUALITY_LIMITS
): PunctualityStats | null {
  try {
    return parseOrThrow(text, limits);
  } catch {
    return null;
  }
}

function parseOrThrow(text: string, limits: PunctualityLimits): PunctualityStats | null {
  const fields = new Map<string, unknown>();
  let trainsAt: number | undefined;
  let position = skipWhitespace(text, expectAt(text, skipWhitespace(text, 0), OPEN_BRACE));
  if (text.charCodeAt(position) === CLOSE_BRACE) position++;
  else
    for (;;) {
      position = skipWhitespace(text, position);
      const keyEnd = endOfString(text, position);
      const key = JSON.parse(text.slice(position, keyEnd)) as string;
      position = skipWhitespace(text, expectAt(text, skipWhitespace(text, keyEnd), COLON));
      const end = endOfValue(text, position);
      if (fields.has(key)) throw new SyntaxError(`duplicate key ${key}`);
      if (key === "trains") {
        trainsAt = position;
        fields.set(key, undefined);
      } else fields.set(key, JSON.parse(text.slice(position, end)));
      position = skipWhitespace(text, end);
      const code = text.charCodeAt(position++);
      if (code === CLOSE_BRACE) break;
      if (code !== COMMA) throw new SyntaxError(`unexpected character at ${position - 1}`);
    }
  if (skipWhitespace(text, position) !== text.length) throw new SyntaxError("text after the document");

  if (fields.get("schema") !== PUNCTUALITY_SCHEMA) return null;
  const buckets = validBuckets(fields.get("buckets"), limits.maxBuckets);
  const windowEnd = isoDate(fields.get("windowEnd")) ?? railDate(fields.get("generatedAt"));
  if (!buckets || !windowEnd || trainsAt === undefined) return null;
  const windowStart = isoDate(fields.get("windowStart"));
  const windowDays = intIn(fields.get("windowDays"), 1, MAX_WINDOW_DAYS) ?? (windowStart ? daysBetween(windowStart, windowEnd) : undefined);
  if (windowDays === undefined || windowDays < 1 || windowDays > MAX_WINDOW_DAYS) return null;

  const trains: PunctualityTrain[] = [];
  const codes = new Map<string, string>();
  let entries = 0;
  forEachElement(text, trainsAt, source => {
    if (++entries > limits.maxTrains) throw new RangeError(`more than ${limits.maxTrains} trains`);
    const train = compactTrain(JSON.parse(source), buckets, limits, codes);
    if (train) trains.push(train);
  });
  return trains.length ? new PunctualityStats(windowEnd, windowDays, trains) : null;
}
