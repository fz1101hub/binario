import { describe, expect, it } from "vitest";
import {
  countAtMost,
  DEFAULT_PUNCTUALITY_LIMITS,
  onTimeLimitMinutes,
  parsePunctualityStats,
  routePunctuality,
  trainNumberKey,
  trainPunctuality,
} from "./stats";
import {
  BUCKETS,
  FIRENZE_SMN,
  type FixtureStop,
  fixtureTrain,
  GUIDONIA,
  MILANO_CENTRALE,
  networkDocument,
  ROMA_TERMINI,
  statsDocument,
  stopFromDelays,
  TIVOLI,
} from "./testFixtures";

function network() {
  const stats = parsePunctualityStats(networkDocument());
  if (!stats) throw new Error("fixture did not parse");
  return stats;
}

describe("statistics reader", () => {
  it("reads the window and every valid train, with 5-, 7- and 9-element stops", () => {
    const stats = network();
    expect(stats.windowEnd).toBe("2026-11-09");
    expect(stats.windowDays).toBe(30);
    expect(stats.trains).toHaveLength(8);
    const seven = parsePunctualityStats(
      statsDocument([fixtureTrain("100", ROMA_TERMINI, "REG", 6, 0, [stopFromDelays(ROMA_TERMINI, [0, 1, 2, 3, 4, 20], [0, 20, 1450])])])
    );
    expect(seven?.trains[0].samples[0]).toBe(6);
  });

  it("counts on time against the train's own limit: 5 minutes for high speed, 15 for the others", () => {
    expect(onTimeLimitMinutes("FR")).toBe(5);
    expect(onTimeLimitMinutes(" italo ")).toBe(5);
    expect(onTimeLimitMinutes("ESC")).toBe(5);
    expect(onTimeLimitMinutes("REG")).toBe(15);
    expect(onTimeLimitMinutes("IC")).toBe(15);
    expect(onTimeLimitMinutes("")).toBe(15);
    const stats = network();
    const fr = stats.trainsNumbered("9558").find(train => train.originCode === ROMA_TERMINI)!;
    expect(fr.onTime[fr.stopCount - 1]).toBe(22);
    const regional = stats.trainsNumbered("2301")[0];
    expect(regional.onTime[regional.stopCount - 1]).toBe(9);
  });

  it("interpolates between bucket limits and clamps outside them", () => {
    const cumulative = [2, 4, 6, 8, 10, 12, 14, 16];
    expect(countAtMost(cumulative, BUCKETS, 5)).toBe(6);
    expect(countAtMost(cumulative, BUCKETS, 7.5)).toBe(7);
    expect(countAtMost(cumulative, BUCKETS, 90)).toBe(16);
    expect(countAtMost(cumulative, BUCKETS, -1)).toBe(0);
    // A file whose buckets do not include 15: the limit is interpolated, then rounded down.
    const odd = parsePunctualityStats(
      statsDocument([fixtureTrain("200", ROMA_TERMINI, "REG", 10, 0, [[TIVOLI, 10, 9, [2, 4, 8, 10], 0]])], { buckets: [0, 10, 20, 30] })
    );
    expect(odd?.trains[0].onTime[0]).toBe(6);
  });

  it("keys train numbers by their digits, as published or spoken", () => {
    expect(trainNumberKey("9558")).toBe("9558");
    expect(trainNumberKey("FR 9558")).toBe("9558");
    expect(trainNumberKey("09558")).toBe("9558");
    expect(trainNumberKey(" ab c ")).toBe("ABC");
    expect(network().trainsNumbered("FR 9558")).toHaveLength(2);
  });

  it("leaves out a train whose fields do not add up and keeps the others", () => {
    const valid = fixtureTrain("1", ROMA_TERMINI, "REG", 5, 0, [stopFromDelays(TIVOLI, [0, 0, 0, 0, 0])]);
    const broken = [
      fixtureTrain("2", ROMA_TERMINI, "REG", 5, 6, [stopFromDelays(TIVOLI, [0])]),
      fixtureTrain("3", ROMA_TERMINI, "REG", 5, 0, [[TIVOLI, 2, 0, [2, 1, 2, 2, 2, 2, 2, 2], 0]]),
      fixtureTrain("4", ROMA_TERMINI, "REG", 5, 0, [[TIVOLI, 2, 0, [0, 0, 1], 0]]),
      fixtureTrain("5", ROMA_TERMINI, "REG", 5, 0, [[TIVOLI, 2, "NaN", [0, 0, 1, 1, 2, 2, 2, 2], 0] as unknown as FixtureStop]),
      fixtureTrain("6", ROMA_TERMINI, "REG", 5, 0, []),
      fixtureTrain("7", "", "REG", 5, 0, [stopFromDelays(TIVOLI, [0])]),
      fixtureTrain("8", ROMA_TERMINI, "REG", 5, 0, [[TIVOLI, 2, 0, [0, 0, 1, 1, 2, 2, 2, 2], null]]),
      { n: "9", o: ROMA_TERMINI, c: "REG", d: 5 },
      "not a train",
    ];
    const stats = parsePunctualityStats(statsDocument([valid, ...broken]));
    expect(stats?.trains.map(train => train.number)).toEqual(["1"]);
  });

  it.each([
    ["another schema", statsDocument([], { schema: "binario-punctuality-v2" })],
    ["no trains", statsDocument([])],
    ["invalid buckets", statsDocument([fixtureTrain("1", ROMA_TERMINI, "REG", 5, 0, [stopFromDelays(TIVOLI, [0])])], { buckets: [5, 2] })],
    ["no window", statsDocument([fixtureTrain("1", ROMA_TERMINI, "REG", 5, 0, [stopFromDelays(TIVOLI, [0])])], { windowEnd: null, generatedAt: null })],
    ["truncated JSON", networkDocument().slice(0, -40)],
    ["text after the document", `${networkDocument()} {}`],
    ["a duplicate key", networkDocument().replace('"schema":', '"schema":"x","schema":')],
    ["a syntax error inside a train", networkDocument().replace('"n":"2301"', '"n":2301x')],
    ["not JSON at all", "\u001f\u008b binary"],
  ])("gives null for %s", (_label, text) => {
    expect(parsePunctualityStats(text)).toBeNull();
  });

  it("derives the window from generatedAt and windowStart when the explicit fields are missing", () => {
    const train = fixtureTrain("1", ROMA_TERMINI, "REG", 5, 0, [stopFromDelays(TIVOLI, [0])]);
    const stats = parsePunctualityStats(statsDocument([train], { windowEnd: undefined, windowDays: undefined, generatedAt: "2026-11-09T23:30:00Z" }));
    // 23:30 UTC is already 10 November in Rome.
    expect(stats?.windowEnd).toBe("2026-11-10");
    expect(stats?.windowDays).toBe(31);
  });

  it("finds the extent of values containing brackets and escaped quotes inside strings", () => {
    const train = fixtureTrain("1", ROMA_TERMINI, "REG", 5, 0, [stopFromDelays(TIVOLI, [0, 0, 0, 0, 0], [0, 0, 600, ["3 \"]}[", 5, 5], null])]);
    const stats = parsePunctualityStats(statsDocument([train], { note: "a } ] \\\" [ {" }));
    expect(stats?.trains[0].samples[0]).toBe(5);
  });

  it("refuses a document with more trains than the bound, without reading them all", () => {
    const train = fixtureTrain("1", ROMA_TERMINI, "REG", 5, 0, [stopFromDelays(TIVOLI, [0])]);
    const document = statsDocument([train, train, train]);
    expect(parsePunctualityStats(document, { ...DEFAULT_PUNCTUALITY_LIMITS, maxTrains: 3 })?.trains).toHaveLength(3);
    expect(parsePunctualityStats(document, { ...DEFAULT_PUNCTUALITY_LIMITS, maxTrains: 2 })).toBeNull();
  });

  it("reads nine thousand trains of fourteen stops quickly and keeps them compact", () => {
    const stops = Array.from({ length: 14 }, (_, index) =>
      stopFromDelays(`S${String(10000 + index).padStart(5, "0")}`, Array.from({ length: 28 }, (_, day) => (day * index) % 23), [0, 22, 600 + index, ["1", 20, 28], [[4, 3, 4], [4, 3, 4], [4, 3, 4], [4, 3, 4], [4, 3, 4], [4, 3, 4], [4, 3, 4]]])
    );
    const trains = Array.from({ length: 9_000 }, (_, index) => fixtureTrain(String(10_000 + index), "S10000", index % 3 ? "REG" : "FR", 28, 0, stops));
    const document = statsDocument(trains);
    const started = performance.now();
    const stats = parsePunctualityStats(document);
    const elapsed = performance.now() - started;
    expect(stats?.trains).toHaveLength(9_000);
    // Station codes are shared between trains, not copied per stop.
    expect(stats!.trains[0].codes[3]).toBe(stats!.trains[8_999].codes[3]);
    expect(elapsed).toBeLessThan(10_000);
  });
});

describe("train punctuality", () => {
  it("measures the train at its terminus, taking the origin with the most samples there", () => {
    expect(trainPunctuality(network(), "9558")).toEqual({
      kind: "stats",
      samples: 27,
      onTime: 22,
      percent: 81,
      limitMinutes: 5,
      meanDelayMinutes: 6,
      days: 28,
      cancelledDays: 1,
    });
  });

  it("says when the train is unknown, cancelled every day, or has too few samples", () => {
    const stats = network();
    expect(trainPunctuality(stats, "4242")).toEqual({ kind: "unknown" });
    expect(trainPunctuality(stats, "7777")).toEqual({ kind: "cancelledEveryDay", days: 7 });
    expect(trainPunctuality(stats, "1234")).toEqual({ kind: "fewData" });
  });

  it("shows an early average as no delay", () => {
    const early = parsePunctualityStats(
      statsDocument([fixtureTrain("300", ROMA_TERMINI, "REG", 5, 0, [[TIVOLI, 5, -1.4, [5, 5, 5, 5, 5, 5, 5, 5], 0]])])
    )!;
    expect(trainPunctuality(early, "300")).toMatchObject({ kind: "stats", percent: 100, meanDelayMinutes: 0 });
  });
});

describe("route punctuality", () => {
  it("aggregates the trains from the origin to the destination, on arrival, with their own limit", () => {
    expect(routePunctuality(network(), [ROMA_TERMINI], [TIVOLI])).toEqual({
      trains: 3,
      samples: 30,
      onTime: 28,
      percent: 93,
      meanDelayMinutes: 5,
      days: 28,
      limits: [15],
    });
  });

  it("ignores the other direction, trains that do not reach the destination and the rare train below 20 samples", () => {
    const stats = network();
    expect(routePunctuality(stats, [TIVOLI], [ROMA_TERMINI])).toBeNull();
    expect(routePunctuality(stats, [GUIDONIA], [TIVOLI])).toBeNull();
    expect(routePunctuality(stats, [FIRENZE_SMN], [MILANO_CENTRALE])).toMatchObject({ trains: 2, samples: 33, onTime: 22, limits: [5] });
    expect(routePunctuality(stats, [ROMA_TERMINI], [ROMA_TERMINI])).toBeNull();
    expect(routePunctuality(stats, [], [TIVOLI])).toBeNull();
    expect(routePunctuality(stats, ["not a code"], [TIVOLI])).toBeNull();
  });

  it("reports mixed limits when high-speed and other trains share the route", () => {
    const stats = parsePunctualityStats(
      statsDocument([
        fixtureTrain("1", ROMA_TERMINI, "FR", 12, 0, [stopFromDelays(ROMA_TERMINI, Array(12).fill(0)), stopFromDelays(TIVOLI, Array(12).fill(6))]),
        fixtureTrain("2", ROMA_TERMINI, "REG", 12, 0, [stopFromDelays(ROMA_TERMINI, Array(12).fill(0)), stopFromDelays(TIVOLI, Array(12).fill(6))]),
      ])
    )!;
    // 6 minutes is late for the FR, on time for the regional train.
    expect(routePunctuality(stats, [ROMA_TERMINI], [TIVOLI])).toMatchObject({ trains: 2, samples: 24, onTime: 12, percent: 50, limits: [5, 15] });
  });

  it("accepts several codes for one end, as a station name shared by more than one code", () => {
    expect(routePunctuality(network(), [ROMA_TERMINI, "S99999"], [TIVOLI, "S99998"])).toMatchObject({ trains: 3 });
  });

  it("covers only the days the window really has", () => {
    const stats = network();
    expect(stats.coveredDays).toBe(28);
  });
});
