import { open } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";
import { gunzip } from "node:zlib";
import { parseStationCatalog, type StationIndex } from "./stations";
import { parsePunctualityStats, type PunctualityStats } from "./stats";

/**
 * Where the skill reads the rail data from. `pnpm run build` copies the repository files
 * `app/src/main/assets/<file>` to `dist/data/<file>` (scripts/rail-data.mjs) and checks that the
 * copies match; the production server reads `dist/data`, a development server the repository
 * files directly. Nothing is downloaded: a new weekly file reaches the skill with a new build and
 * deploy (docs/ALEXA_SETUP.md).
 */
export const STATS_FILE = "punctuality/stats_v1.json.gz";
export const STATIONS_FILE = "stations/curated_it_rail_stations.json";

/** Bounds of what is read (the pipeline writes at most 3 MB gzip and 32 MB JSON). */
export const MAX_STATS_GZIP_BYTES = 8 * 1024 * 1024;
export const MAX_STATS_JSON_BYTES = 48 * 1024 * 1024;
export const MAX_STATIONS_BYTES = 8 * 1024 * 1024;

const gunzipAsync = promisify(gunzip);

export function defaultRailDataDir(): string {
  return process.env.NODE_ENV === "production"
    ? path.resolve(import.meta.dirname, "data")
    : path.resolve(import.meta.dirname, "..", "..", "..", "app", "src", "main", "assets");
}

type Unavailable = "absent" | "tooLarge" | "unreadable" | "invalid";

function logUnavailable(what: string, reason: Unavailable) {
  // File state only: the statistics and the catalogue are not personal and no request is logged.
  console.warn(`[Punctuality] ${what} not available`, { reason });
}

async function readBounded(file: string, maxBytes: number, what: string): Promise<Buffer | null> {
  let handle: Awaited<ReturnType<typeof open>> | undefined;
  try {
    handle = await open(file, "r");
    const { size } = await handle.stat();
    if (size > maxBytes) {
      logUnavailable(what, "tooLarge");
      return null;
    }
    return await handle.readFile();
  } catch (error) {
    logUnavailable(what, (error as { code?: unknown }).code === "ENOENT" ? "absent" : "unreadable");
    return null;
  } finally {
    await handle?.close();
  }
}

/** The statistics in `file`, or null when the file is absent, over the bounds or not valid. */
export async function loadPunctualityStats(file: string): Promise<PunctualityStats | null> {
  const compressed = await readBounded(file, MAX_STATS_GZIP_BYTES, "statistics");
  if (!compressed) return null;
  let text: string;
  try {
    const json = await gunzipAsync(compressed, { maxOutputLength: MAX_STATS_JSON_BYTES });
    text = new TextDecoder("utf-8", { fatal: true }).decode(json);
  } catch {
    logUnavailable("statistics", "invalid");
    return null;
  }
  const stats = parsePunctualityStats(text);
  if (!stats) logUnavailable("statistics", "invalid");
  else console.info("[Punctuality] statistics loaded", { trains: stats.trains.length, windowEnd: stats.windowEnd });
  return stats;
}

/** The station names of the catalogue in `file`, or null. */
export async function loadStationIndex(file: string): Promise<StationIndex | null> {
  const bytes = await readBounded(file, MAX_STATIONS_BYTES, "station catalogue");
  if (!bytes) return null;
  let index: StationIndex | null = null;
  try {
    index = parseStationCatalog(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  } catch {
    index = null;
  }
  if (!index) logUnavailable("station catalogue", "invalid");
  return index;
}

/** A failed read (file not there yet during a deploy, EMFILE…) is tried again after this long. */
export const RAIL_DATA_RETRY_MS = 60_000;

/**
 * A file read once and kept for the life of the process (it changes only with a deploy);
 * concurrent first uses share one read. A read that gave null is kept for
 * {@link RAIL_DATA_RETRY_MS} only, so one bad moment does not disable the answers until a restart.
 */
function cachedRead<T>(read: () => Promise<T | null>) {
  let value: Promise<T | null> | undefined;
  let retryAt: number | undefined;
  return {
    get(now: number): Promise<T | null> {
      if (!value || (retryAt !== undefined && now >= retryAt)) {
        retryAt = undefined;
        const reading = read();
        value = reading;
        void reading.then(result => {
          if (result === null && value === reading) retryAt = now + RAIL_DATA_RETRY_MS;
        });
      }
      return value;
    },
    forget() {
      value = undefined;
      retryAt = undefined;
    },
  };
}

let dataDir = defaultRailDataDir();
const stats = cachedRead(() => loadPunctualityStats(path.join(dataDir, STATS_FILE)));
const stations = cachedRead(() => loadStationIndex(path.join(dataDir, STATIONS_FILE)));

/** The statistics, read on first use (or at boot, {@link preloadRailData}) and kept. */
export function punctualityStats(now: number = Date.now()): Promise<PunctualityStats | null> {
  return stats.get(now);
}

/** The station catalogue, read on the first route question (or at boot) and kept. */
export function railStationIndex(now: number = Date.now()): Promise<StationIndex | null> {
  return stations.get(now);
}

/**
 * Starts both reads at server start, so the first question does not pay them (a few hundred
 * milliseconds for the statistics) and a missing file shows in the log at once.
 */
export function preloadRailData(): Promise<unknown> {
  return Promise.all([punctualityStats(), railStationIndex()]);
}

/** Points the readers at another data directory (tests), forgetting what was read. */
export function useRailDataDir(directory: string = defaultRailDataDir()) {
  dataDir = directory;
  stats.forget();
  stations.forget();
}
