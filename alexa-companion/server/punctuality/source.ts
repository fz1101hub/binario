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

let dataDir = defaultRailDataDir();
let stats: Promise<PunctualityStats | null> | undefined;
let stations: Promise<StationIndex | null> | undefined;

/**
 * The statistics, read once on first use and kept for the life of the process (the file changes
 * only with a deploy). Concurrent first requests share one read.
 */
export function punctualityStats(): Promise<PunctualityStats | null> {
  stats ??= loadPunctualityStats(path.join(dataDir, STATS_FILE));
  return stats;
}

/** The station catalogue, read once on the first route question. */
export function railStationIndex(): Promise<StationIndex | null> {
  stations ??= loadStationIndex(path.join(dataDir, STATIONS_FILE));
  return stations;
}

/** Points the readers at another data directory (tests), forgetting what was read. */
export function useRailDataDir(directory: string = defaultRailDataDir()) {
  dataDir = directory;
  stats = undefined;
  stations = undefined;
}
