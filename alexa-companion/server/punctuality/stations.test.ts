import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { defaultRailDataDir, STATIONS_FILE } from "./source";
import { normalizeStationName, parseStationCatalog } from "./stations";
import { catalogDocument, FIRENZE_SMN, MILANO_CENTRALE, ROMA_TERMINI, TIVOLI } from "./testFixtures";

describe("station names", () => {
  it("normalises case, accents and punctuation the way users type names", () => {
    expect(normalizeStationName("  Firenze S.M. Novella ")).toBe("firenze s m novella");
    expect(normalizeStationName("Città Sant’Angelo")).toBe("citta sant angelo");
    expect(normalizeStationName("ROMA—TERMINI")).toBe("roma termini");
  });

  it("matches a catalogue name or alias exactly, never a part of one", () => {
    const index = parseStationCatalog(catalogDocument())!;
    expect(index.codesFor("Roma Termini")).toEqual([ROMA_TERMINI]);
    expect(index.codesFor("roma termini")).toEqual([ROMA_TERMINI]);
    expect(index.codesFor("Rome Termini")).toEqual([ROMA_TERMINI]);
    expect(index.codesFor("Firenze Santa Maria Novella")).toEqual([FIRENZE_SMN]);
    expect(index.codesFor("firenze s.m. novella")).toEqual([FIRENZE_SMN]);
    expect(index.codesFor("Mailand")).toEqual([MILANO_CENTRALE]);
    expect(index.codesFor("Tivoli")).toEqual([TIVOLI]);
    expect(index.codesFor("Roma")).toEqual([]);
    expect(index.codesFor("Milano")).toEqual([]);
    // Stations without a ViaggiaTreno code are not in the statistics.
    expect(index.codesFor("Abbiate Guazzone")).toEqual([]);
  });

  it("gives every code of a name shared by several stations", () => {
    const index = parseStationCatalog(
      JSON.stringify({ stations: [
        { name: "Bologna Centrale", railwayCode: "S05043", aliases: ["Bologne"] },
        { name: "Bologna San Ruffillo", railwayCode: "S05100", aliases: ["Bologne"] },
      ] })
    )!;
    expect(index.codesFor("bologne")).toEqual(["S05043", "S05100"]);
  });

  it.each([["not JSON", "{"], ["no stations", "{}"], ["no coded station", JSON.stringify({ stations: [{ name: "X", railwayCode: null }] })]])(
    "gives null for %s",
    (_label, text) => expect(parseStationCatalog(text)).toBeNull()
  );

  const catalogue = path.join(defaultRailDataDir(), STATIONS_FILE);
  it.skipIf(!existsSync(catalogue))("resolves the stations of the dashboard examples in the repository catalogue", () => {
    const index = parseStationCatalog(readFileSync(catalogue, "utf8"))!;
    expect(index.codesFor("Roma Termini")).toEqual(["S08409"]);
    expect(index.codesFor("Tivoli")).toEqual(["S08508"]);
    expect(index.codesFor("Roma Ostiense")).toEqual(["S08406"]);
    expect(index.codesFor("Milano Centrale")).toEqual(["S01700"]);
  });
});
