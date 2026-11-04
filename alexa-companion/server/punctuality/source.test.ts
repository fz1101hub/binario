import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { gzipSync } from "node:zlib";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  defaultRailDataDir,
  loadPunctualityStats,
  MAX_STATS_JSON_BYTES,
  punctualityStats,
  railStationIndex,
  STATIONS_FILE,
  STATS_FILE,
  useRailDataDir,
} from "./source";
import { catalogDocument, networkDocument, ROMA_TERMINI } from "./testFixtures";

const SCRIPT = path.resolve(import.meta.dirname, "..", "..", "scripts", "rail-data.mjs");

let scratch: string;
beforeEach(() => {
  scratch = mkdtempSync(path.join(tmpdir(), "binario-rail-data-"));
});
afterEach(() => {
  useRailDataDir();
  vi.restoreAllMocks();
  rmSync(scratch, { recursive: true, force: true });
});

function write(root: string, file: string, contents: string | Buffer) {
  mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
  writeFileSync(path.join(root, file), contents);
}

describe("rail data files", () => {
  it("reads the gzip statistics and the catalogue once, then reuses them", async () => {
    const info = vi.spyOn(console, "info").mockImplementation(() => undefined);
    write(scratch, STATS_FILE, gzipSync(networkDocument()));
    write(scratch, STATIONS_FILE, catalogDocument());
    useRailDataDir(scratch);
    const [first, second] = await Promise.all([punctualityStats(), punctualityStats()]);
    expect(first).toBe(second);
    expect(first?.trains.length).toBe(8);
    expect((await railStationIndex())?.codesFor("Roma Termini")).toEqual([ROMA_TERMINI]);
    expect(info).toHaveBeenCalledTimes(1);
    expect(info.mock.calls[0]).toEqual(["[Punctuality] statistics loaded", { trains: 8, windowEnd: "2026-11-09" }]);
  });

  it("says the statistics are absent, unreadable or invalid by file state only", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    useRailDataDir(scratch);
    expect(await punctualityStats()).toBeNull();
    expect(await railStationIndex()).toBeNull();
    write(scratch, "broken.json.gz", Buffer.from("not gzip"));
    expect(await loadPunctualityStats(path.join(scratch, "broken.json.gz"))).toBeNull();
    write(scratch, "schema.json.gz", gzipSync(networkDocument({ schema: "other" })));
    expect(await loadPunctualityStats(path.join(scratch, "schema.json.gz"))).toBeNull();
    expect(warn.mock.calls).toEqual([
      ["[Punctuality] statistics not available", { reason: "absent" }],
      ["[Punctuality] station catalogue not available", { reason: "absent" }],
      ["[Punctuality] statistics not available", { reason: "invalid" }],
      ["[Punctuality] statistics not available", { reason: "invalid" }],
    ]);
  });

  it("stops decompressing past the JSON bound", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    write(scratch, "bomb.json.gz", gzipSync(Buffer.alloc(MAX_STATS_JSON_BYTES + 1, 0x20)));
    expect(await loadPunctualityStats(path.join(scratch, "bomb.json.gz"))).toBeNull();
    expect(warn).toHaveBeenCalledWith("[Punctuality] statistics not available", { reason: "invalid" });
  });

  it("reads the repository assets outside production", () => {
    expect(defaultRailDataDir()).toBe(path.resolve(import.meta.dirname, "..", "..", "..", "app", "src", "main", "assets"));
  });
});

describe("scripts/rail-data.mjs", () => {
  function run(command: "copy" | "verify", repo: string, dist: string) {
    return spawnSync(process.execPath, [SCRIPT, command, "--repo", repo, "--dist", dist], { encoding: "utf8" });
  }

  it("copies the repository files where the production server reads them, and checks the copies", async () => {
    const repo = path.join(scratch, "repo");
    const dist = path.join(scratch, "dist");
    const assets = path.join(repo, "app", "src", "main", "assets");
    write(assets, STATS_FILE, gzipSync(networkDocument()));
    write(assets, STATIONS_FILE, catalogDocument());

    expect(run("verify", repo, dist).status).toBe(1);
    const copy = run("copy", repo, dist);
    expect(copy.status, copy.stderr).toBe(0);
    expect(run("verify", repo, dist).status).toBe(0);

    // The production server's data directory is dist/data: the same relative names load.
    vi.spyOn(console, "info").mockImplementation(() => undefined);
    useRailDataDir(path.join(dist, "data"));
    expect((await punctualityStats())?.windowEnd).toBe("2026-11-09");
    expect((await railStationIndex())?.size).toBeGreaterThan(0);

    // A copy that differs from the repository fails the build.
    write(path.join(dist, "data"), STATS_FILE, gzipSync(networkDocument({ windowEnd: "2026-11-02" })));
    const stale = run("verify", repo, dist);
    expect(stale.status).toBe(1);
    expect(stale.stderr).toContain("dist/data/punctuality/stats_v1.json.gz differs from the repository file");

    // Without the weekly file the build drops the old copy: the skill says the statistics are not available.
    rmSync(path.join(assets, STATS_FILE));
    expect(run("verify", repo, dist).stderr).toContain("exists but the repository has no");
    expect(run("copy", repo, dist).stdout).toContain("absent in the repository: punctuality/stats_v1.json.gz");
    expect(existsSync(path.join(dist, "data", STATS_FILE))).toBe(false);
    expect(run("verify", repo, dist).status).toBe(0);
    expect(readFileSync(path.join(dist, "data", STATIONS_FILE), "utf8")).toBe(catalogDocument());
  });
});
