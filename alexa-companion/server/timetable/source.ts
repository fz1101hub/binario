import { createHash } from "node:crypto";
import { rmSync } from "node:fs";
import { mkdtemp, open, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { inflate } from "node:zlib";
import { APPLICATION_ID, TimetableIndex, TimetableIndexError, USER_VERSION } from "./repository";

/**
 * Where the skill reads the programmed timetable from: the index the iPhone bundles
 * (`ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib` and its metadata), which
 * tools/build_ios_gtfs_schedule.py builds from Android's packs, so the skill reads the same trains
 * as the apps. `pnpm run build` copies both to `dist/data/timetable/` (scripts/rail-data.mjs) and
 * checks the copies; the production server reads `dist/data`, a development server the repository
 * files. The index is checked as the iPhone installs it (`BundledGTFSScheduleInstaller`): metadata
 * bounds, size and SHA-256 of the compressed file and of the database, then the schema, the
 * integrity check and the input fingerprint (`SQLiteGTFSJourneyRepository.validate`). The
 * database is written to a private temporary directory, opened read-only and removed at exit.
 * Nothing is downloaded: a new timetable reaches the skill with a new build and deploy.
 */
export const TIMETABLE_FILE = "timetable/binario_gtfs_schedule_v1.sqlite.zlib";
export const TIMETABLE_METADATA_FILE = "timetable/binario_gtfs_schedule_v1.metadata.json";

/** The iPhone installer's bounds. */
export const MAX_METADATA_BYTES = 4 * 1024 * 1024;
export const MAX_COMPRESSED_BYTES = 32 * 1024 * 1024;
export const MAX_DATABASE_BYTES = 256 * 1024 * 1024;

/** A failed read (file not there yet during a deploy, EMFILE…) is tried again after this long. */
export const TIMETABLE_RETRY_MS = 60_000;

const inflateAsync = promisify(inflate);

type Files = { index: string; metadata: string };

/** dist/data in production; the repository's iPhone resources outside it. */
export function defaultTimetableFiles(): Files {
  if (process.env.NODE_ENV === "production")
    return { index: path.resolve(import.meta.dirname, "data", TIMETABLE_FILE), metadata: path.resolve(import.meta.dirname, "data", TIMETABLE_METADATA_FILE) };
  const resources = path.resolve(import.meta.dirname, "..", "..", "..", "ios", "Resources");
  return {
    index: path.join(resources, path.basename(TIMETABLE_FILE)),
    metadata: path.join(resources, path.basename(TIMETABLE_METADATA_FILE)),
  };
}

type Unavailable = "absent" | "tooLarge" | "unreadable" | "invalid";

class UnavailableError extends Error {
  constructor(readonly reason: Unavailable) {
    super(reason);
  }
}

async function readBounded(file: string, maxBytes: number, expectedBytes?: number): Promise<Buffer> {
  let handle: Awaited<ReturnType<typeof open>> | undefined;
  try {
    handle = await open(file, "r");
    const { size } = await handle.stat();
    if (size > maxBytes) throw new UnavailableError("tooLarge");
    if (expectedBytes !== undefined && size !== expectedBytes) throw new UnavailableError("invalid");
    const bytes = await handle.readFile();
    if (bytes.length !== size) throw new UnavailableError("unreadable");
    return bytes;
  } catch (error) {
    if (error instanceof UnavailableError) throw error;
    throw new UnavailableError((error as { code?: unknown }).code === "ENOENT" ? "absent" : "unreadable");
  } finally {
    await handle?.close();
  }
}

const isSha256 = (value: unknown): value is string => typeof value === "string" && /^[0-9a-f]{64}$/.test(value);
const isSize = (value: unknown, maximum: number): value is number => Number.isInteger(value) && (value as number) >= 1 && (value as number) <= maximum;
const sha256 = (bytes: Uint8Array) => createHash("sha256").update(bytes).digest("hex");

type Metadata = {
  database: { sha256: string; sizeBytes: number };
  compression: { sha256: string; sizeBytes: number };
  fingerprint: string;
};

/** The metadata as the iPhone installer accepts it, or an error. */
export function parseTimetableMetadata(text: string): Metadata | null {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    return null;
  }
  const document = value as {
    schemaVersion?: unknown;
    maximumCompressedSizeBytes?: unknown;
    database?: { applicationId?: unknown; userVersion?: unknown; sha256?: unknown; sizeBytes?: unknown };
    compression?: { format?: unknown; level?: unknown; sha256?: unknown; sizeBytes?: unknown };
    inputs?: { fingerprintSha256?: unknown };
  } | null;
  const { database, compression, inputs } = document ?? {};
  if (
    document?.schemaVersion !== 1 ||
    database?.applicationId !== APPLICATION_ID ||
    database.userVersion !== USER_VERSION ||
    !isSize(database.sizeBytes, MAX_DATABASE_BYTES) ||
    !isSize(compression?.sizeBytes, MAX_COMPRESSED_BYTES) ||
    compression?.format !== "zlib" ||
    compression.level !== 9 ||
    document.maximumCompressedSizeBytes !== MAX_COMPRESSED_BYTES ||
    !isSha256(database.sha256) ||
    !isSha256(compression.sha256) ||
    !isSha256(inputs?.fingerprintSha256)
  )
    return null;
  return {
    database: { sha256: database.sha256, sizeBytes: database.sizeBytes },
    compression: { sha256: compression.sha256, sizeBytes: compression.sizeBytes as number },
    fingerprint: inputs.fingerprintSha256,
  };
}

/** An index opened from a private copy of its database (`file`), which `close` removes. */
export type LoadedTimetable = { index: TimetableIndex; file: string; close: () => void };

const openDirectories = new Set<string>();
let exitHookInstalled = false;

function removeOnExit(directory: string) {
  openDirectories.add(directory);
  if (exitHookInstalled) return;
  exitHookInstalled = true;
  process.once("exit", () => {
    for (const open of openDirectories) rmSync(open, { recursive: true, force: true });
  });
}

/** The index described by `files`, checked end to end, or null (logged by file state only). */
export async function loadTimetable(files: Files): Promise<LoadedTimetable | null> {
  let directory: string | undefined;
  try {
    const metadata = parseTimetableMetadata((await readBounded(files.metadata, MAX_METADATA_BYTES)).toString("utf8"));
    if (!metadata) throw new UnavailableError("invalid");
    const compressed = await readBounded(files.index, MAX_COMPRESSED_BYTES, metadata.compression.sizeBytes);
    if (sha256(compressed) !== metadata.compression.sha256) throw new UnavailableError("invalid");
    let database: Buffer;
    try {
      database = await inflateAsync(compressed, { maxOutputLength: metadata.database.sizeBytes });
    } catch {
      throw new UnavailableError("invalid");
    }
    if (database.length !== metadata.database.sizeBytes || sha256(database) !== metadata.database.sha256) throw new UnavailableError("invalid");
    directory = await mkdtemp(path.join(tmpdir(), "binario-timetable-"));
    const file = path.join(directory, "binario_gtfs_schedule_v1.sqlite");
    await writeFile(file, database, { mode: 0o400, flag: "wx" });
    let index: TimetableIndex;
    try {
      index = new TimetableIndex(file, metadata.fingerprint);
    } catch (error) {
      if (error instanceof TimetableIndexError) throw new UnavailableError("invalid");
      throw error;
    }
    const opened = directory;
    removeOnExit(opened);
    console.info("[Timetable] index loaded", { sourceGeneratedAt: index.sourceGeneratedAt });
    return {
      index,
      file,
      close: () => {
        index.close();
        openDirectories.delete(opened);
        rmSync(opened, { recursive: true, force: true });
      },
    };
  } catch (error) {
    if (directory) await rm(directory, { recursive: true, force: true });
    // File state only: the timetable is not personal and no request is logged.
    console.warn("[Timetable] index not available", { reason: error instanceof UnavailableError ? error.reason : "unreadable" });
    return null;
  }
}

let files = defaultTimetableFiles();
let value: Promise<LoadedTimetable | null> | undefined;
let retryAt: number | undefined;

/**
 * The timetable index, read on first use (or at boot) and kept for the life of the process (it
 * changes only with a deploy); concurrent first uses share one read. A read that gave null is
 * kept for {@link TIMETABLE_RETRY_MS} only.
 */
export async function timetableIndex(now: number = Date.now()): Promise<TimetableIndex | null> {
  if (!value || (retryAt !== undefined && now >= retryAt)) {
    retryAt = undefined;
    const reading = loadTimetable(files);
    value = reading;
    void reading.then(result => {
      if (result === null && value === reading) retryAt = now + TIMETABLE_RETRY_MS;
    });
  }
  return (await value)?.index ?? null;
}

/** Starts the read at server start, so the first question does not pay it and a missing file shows in the log at once. */
export function preloadTimetable(): Promise<unknown> {
  return timetableIndex();
}

/** Points the reader at another data directory laid out as dist/data (tests), closing what was read. */
export async function useTimetableDir(directory?: string) {
  const previous = value;
  value = undefined;
  retryAt = undefined;
  files = directory
    ? { index: path.join(directory, TIMETABLE_FILE), metadata: path.join(directory, TIMETABLE_METADATA_FILE) }
    : defaultTimetableFiles();
  (await previous)?.close();
}
