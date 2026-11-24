import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { deflateSync } from "node:zlib";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { parseStationCatalog } from "../punctuality/stations";
import { APPLICATION_ID, USER_VERSION } from "./repository";
import {
  defaultTimetableFiles,
  loadTimetable,
  MAX_COMPRESSED_BYTES,
  TIMETABLE_FILE,
  TIMETABLE_METADATA_FILE,
  timetableIndex,
  TIMETABLE_RETRY_MS,
  useTimetableDir,
} from "./source";
import { skillTimetableRows, writeIndex } from "./testIndex";

const SCRIPT = path.resolve(import.meta.dirname, "..", "..", "scripts", "rail-data.mjs");
const REPOSITORY = path.resolve(import.meta.dirname, "..", "..", "..");
const sha256 = (bytes: Uint8Array) => createHash("sha256").update(bytes).digest("hex");

let scratch: string;
beforeEach(() => {
  scratch = mkdtempSync(path.join(tmpdir(), "binario-timetable-"));
});
afterEach(async () => {
  await useTimetableDir();
  vi.restoreAllMocks();
  rmSync(scratch, { recursive: true, force: true });
});

/** The compressed index and its metadata, as tools/build_ios_gtfs_schedule.py writes them. */
function artifact(fingerprint = "b".repeat(64), indexFingerprint = fingerprint) {
  const file = path.join(scratch, `source-${Math.random().toString(16).slice(2)}.sqlite`);
  writeIndex(file, skillTimetableRows(), indexFingerprint);
  const database = readFileSync(file);
  const compressed = deflateSync(database, { level: 9 });
  const metadata = {
    schemaVersion: 1,
    maximumCompressedSizeBytes: MAX_COMPRESSED_BYTES,
    database: { applicationId: APPLICATION_ID, userVersion: USER_VERSION, sha256: sha256(database), sizeBytes: database.length },
    compression: { format: "zlib", level: 9, sha256: sha256(compressed), sizeBytes: compressed.length },
    inputs: { fingerprintSha256: fingerprint },
  };
  return { compressed, metadata };
}

function write(root: string, file: string, contents: string | Buffer) {
  mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
  writeFileSync(path.join(root, file), contents);
}

function install(root: string, { compressed, metadata }: ReturnType<typeof artifact>) {
  write(root, TIMETABLE_FILE, compressed);
  write(root, TIMETABLE_METADATA_FILE, JSON.stringify(metadata));
}

describe("timetable index files", () => {
  it("reads the compressed index once, checked as the iPhone installs it, and answers from it", async () => {
    const info = vi.spyOn(console, "info").mockImplementation(() => undefined);
    install(scratch, artifact());
    await useTimetableDir(scratch);
    const [first, second] = await Promise.all([timetableIndex(), timetableIndex()]);
    expect(first).toBe(second);
    const result = first?.nextTrains([{ id: "S08409", railwayCode: "S08409" }], [{ id: "S08508", railwayCode: "S08508" }], Date.parse("2026-11-10T09:00:00+01:00"));
    // The next 24 hours: today's last three, then tomorrow's first.
    expect(result?.kind === "trains" && result.services.map(service => `${service.time} ${service.category} ${service.number}`)).toEqual([
      "09:12 REG 2161",
      "09:30 BUS 7001",
      "10:12 REG 2165",
      "08:12 REG 2157",
    ]);
    expect(info).toHaveBeenCalledTimes(1);
    expect(info.mock.calls[0]).toEqual(["[Timetable] index loaded", { sourceGeneratedAt: "2024-03-30" }]);
  });

  it("refuses an index that is not the one its metadata describes, by file state only", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const files = (root: string) => ({ index: path.join(root, TIMETABLE_FILE), metadata: path.join(root, TIMETABLE_METADATA_FILE) });
    expect(await loadTimetable(files(scratch))).toBeNull();
    const good = artifact();
    // Another compressed file than the metadata's.
    const other = path.join(scratch, "other");
    install(other, { ...good, compressed: Buffer.concat([good.compressed, Buffer.from([0])]) });
    expect(await loadTimetable(files(other))).toBeNull();
    // A database whose fingerprint is not the metadata's input fingerprint.
    const stale = path.join(scratch, "stale");
    install(stale, artifact("c".repeat(64), "d".repeat(64)));
    expect(await loadTimetable(files(stale))).toBeNull();
    // Metadata of another schema.
    const schema = path.join(scratch, "schema");
    install(schema, { ...good, metadata: { ...good.metadata, database: { ...good.metadata.database, userVersion: 1 } } });
    expect(await loadTimetable(files(schema))).toBeNull();
    expect(warn.mock.calls).toEqual([
      ["[Timetable] index not available", { reason: "absent" }],
      ["[Timetable] index not available", { reason: "invalid" }],
      ["[Timetable] index not available", { reason: "invalid" }],
      ["[Timetable] index not available", { reason: "invalid" }],
    ]);
  });

  it("tries a failed read again a minute later, and removes its private copy when done", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    vi.spyOn(console, "info").mockImplementation(() => undefined);
    await useTimetableDir(scratch);
    const start = 1_000_000;
    expect(await timetableIndex(start)).toBeNull();
    install(scratch, artifact());
    expect(await timetableIndex(start + TIMETABLE_RETRY_MS - 1)).toBeNull();
    expect(await timetableIndex(start + TIMETABLE_RETRY_MS)).not.toBeNull();
    const loaded = await loadTimetable({ index: path.join(scratch, TIMETABLE_FILE), metadata: path.join(scratch, TIMETABLE_METADATA_FILE) });
    expect(loaded && existsSync(loaded.file)).toBe(true);
    loaded?.close();
    expect(loaded && existsSync(path.dirname(loaded.file))).toBe(false);
  });

  it("reads the repository's iPhone resources outside production, and the bundled index there is valid", async () => {
    vi.spyOn(console, "info").mockImplementation(() => undefined);
    expect(defaultTimetableFiles()).toEqual({
      index: path.join(REPOSITORY, "ios", "Resources", "binario_gtfs_schedule_v1.sqlite.zlib"),
      metadata: path.join(REPOSITORY, "ios", "Resources", "binario_gtfs_schedule_v1.metadata.json"),
    });
    const loaded = await loadTimetable(defaultTimetableFiles());
    expect(loaded).not.toBeNull();
    // The catalogue's stations resolve in the index: a national route has trains on a weekday.
    const catalogue = parseStationCatalog(readFileSync(path.join(REPOSITORY, "app", "src", "main", "assets", "stations", "curated_it_rail_stations.json"), "utf8"));
    const lastDay = JSON.parse(readFileSync(defaultTimetableFiles().metadata, "utf8")).inputs.sourceGeneratedAt as string;
    const at = Date.parse(`${lastDay}T07:00:00+01:00`) + 86_400_000;
    const result = loaded?.index.nextTrains(catalogue?.stationsFor("Milano Centrale") ?? [], catalogue?.stationsFor("Roma Termini") ?? [], at);
    expect(result?.kind).toBe("trains");
    loaded?.close();
  }, 30_000);
});

describe("scripts/rail-data.mjs and the timetable", () => {
  function run(command: "copy" | "verify", repo: string, dist: string) {
    return spawnSync(process.execPath, [SCRIPT, command, "--repo", repo, "--dist", dist], { encoding: "utf8" });
  }

  it("copies the iPhone index and its metadata where the production server reads them, and checks them together", async () => {
    vi.spyOn(console, "info").mockImplementation(() => undefined);
    const repo = path.join(scratch, "repo");
    const dist = path.join(scratch, "dist");
    const resources = path.join(repo, "ios", "Resources");
    const good = artifact();
    write(resources, "binario_gtfs_schedule_v1.sqlite.zlib", good.compressed);
    write(resources, "binario_gtfs_schedule_v1.metadata.json", JSON.stringify(good.metadata));

    expect(run("verify", repo, dist).status).toBe(1);
    const copy = run("copy", repo, dist);
    expect(copy.status, copy.stderr).toBe(0);
    expect(run("verify", repo, dist).status).toBe(0);
    await useTimetableDir(path.join(dist, "data"));
    expect(await timetableIndex()).not.toBeNull();

    // An index the metadata does not describe fails the build, even when both match the repository.
    write(resources, "binario_gtfs_schedule_v1.sqlite.zlib", Buffer.concat([good.compressed, Buffer.from([0])]));
    expect(run("copy", repo, dist).status).toBe(0);
    const mismatched = run("verify", repo, dist);
    expect(mismatched.status).toBe(1);
    expect(mismatched.stderr).toContain(`dist/data/${TIMETABLE_FILE} is not the index dist/data/${TIMETABLE_METADATA_FILE} describes`);

    // Without the index in the repository the build drops both copies.
    rmSync(resources, { recursive: true });
    run("copy", repo, dist);
    expect(existsSync(path.join(dist, "data", TIMETABLE_FILE))).toBe(false);
    expect(existsSync(path.join(dist, "data", TIMETABLE_METADATA_FILE))).toBe(false);
    expect(run("verify", repo, dist).status).toBe(0);
  });
});
