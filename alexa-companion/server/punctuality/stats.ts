/**
 * Binario's unofficial network punctuality statistics (`stats_v1.json.gz`, format in
 * docs/data-sources/PUNCTUALITY_STATS.md): how every observed train ran over the last 30 days,
 * aggregated from ViaggiaTreno observations by the repository pipeline. The same file the Android
 * app embeds; the skill answers from it and never asks ViaggiaTreno anything.
 *
 * The reader keeps only what the voice answers need (per stop: station code, samples, mean delay
 * and the samples within the train's on-time limit), so about nine thousand trains stay a few
 * megabytes. It is tolerant like the app's reader: a file that is malformed, of another schema or
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

/** "9558" for "9558", "FR 9558" and "09558"; a number without digits keeps its letters. */
export function trainNumberKey(number: string): string {
  const digits = number.replace(/[^0-9]/g, "").replace(/^0+/, "");
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
    readonly meanTenths: Int16Array
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
}

export class PunctualityStats {
  private readonly byNumber = new Map<string, PunctualityTrain[]>();

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
    let observed = 0;
    for (const train of this.trains) observed = Math.max(observed, train.daysObserved);
    return Math.max(1, Math.min(this.windowDays, observed || this.windowDays));
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
 * How the train numbered `number` arrives at its terminus (the last stop of the file). Among
 * trains with the same number from different origins the one with the most samples at its
 * terminus is taken; a train cancelled on every one of at least {@link MIN_SAMPLES} days says so.
 */
export function trainPunctuality(stats: PunctualityStats, number: string): TrainPunctuality {
  const candidates = stats.trainsNumbered(number);
  if (!candidates.length) return { kind: "unknown" };
  const terminusSamples = (train: PunctualityTrain) => train.samples[train.stopCount - 1] ?? 0;
  const running = candidates
    .filter(train => train.stopCount > 0)
    .sort((left, right) => terminusSamples(right) - terminusSamples(left) || left.originCode.localeCompare(right.originCode));
  const best = running[0];
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
  const origins = new Set([...originCodes].filter(isStationCode));
  const destinations = new Set([...destinationCodes].filter(isStationCode));
  if (!origins.size || !destinations.size) return null;
  for (const code of origins) if (destinations.has(code)) return null;
  let trains = 0;
  let samples = 0;
  let onTime = 0;
  let delaySum = 0;
  const limits = new Set<number>();
  for (const train of stats.trains) {
    const boarding = train.codes.findIndex(code => origins.has(code));
    if (boarding < 0) continue;
    let alighting = -1;
    for (let index = boarding + 1; index < train.stopCount; index++)
      if (destinations.has(train.codes[index])) {
        alighting = index;
        break;
      }
    if (alighting < 0) continue;
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

type CompactStop = { code: string; samples: number; meanTenths: number; onTime: number };

function compactStop(
  value: unknown,
  buckets: readonly number[],
  limit: number,
  limits: PunctualityLimits,
  codes: Map<string, string>
): CompactStop | null {
  if (!Array.isArray(value) || value.length < 4) return null;
  // Later elements (suppressions, max delay, scheduled minute, platform, weekdays) are not spoken.
  const [rawCode, rawSamples, mean, cumulative, suppressed] = value as unknown[];
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
  return { code: interned, samples, meanTenths: Math.round(mean * 10), onTime };
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
    Int16Array.from(stops, stop => stop.meanTenths)
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
