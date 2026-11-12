import { describe, expect, it } from "vitest";
import {
  boundedShare,
  countAtMost,
  DEFAULT_PUNCTUALITY_LIMITS,
  mostPunctualDepartures,
  NO_SCHEDULED_MINUTE,
  onTimeLimitMinutes,
  parsePunctualityStats,
  publicTrainNumber,
  isItalianHoliday,
  railClock,
  routePunctuality,
  trainNumberKey,
  trainPunctuality,
  type RailClock,
} from "./stats";
import {
  arrivals,
  BUCKETS,
  departure,
  departuresDocument,
  FIRENZE_SMN,
  type FixtureStop,
  fixtureTrain,
  GUIDONIA,
  MILANO_CENTRALE,
  MONDAY_MORNING,
  networkDocument,
  ROMA_TERMINI,
  statsDocument,
  stopFromDelays,
  TIVOLI,
  weekdaysOnTime,
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
    expect(network().trainsNumbered("FR 9558").map(train => train.originCode)).toEqual([ROMA_TERMINI]);
  });

  it("knows a Trenord train by the run after its line, as the app does", () => {
    expect(publicTrainNumber("RE_11 - 2153")).toBe("2153");
    expect(publicTrainNumber("S9 - 25012")).toBe("25012");
    expect(publicTrainNumber(" 2153 ")).toBe("2153");
    expect(publicTrainNumber("RE_11 - ")).toBe("RE_11 -");
    expect(trainNumberKey("RE_11 - 2153")).toBe("2153");
    expect(trainNumberKey("2153")).toBe("2153");
  });

  it("keeps the scheduled minute (element 6) and the weekdays (element 8) of each stop, tolerating their absence", () => {
    const stats = parsePunctualityStats(
      statsDocument([
        departure("1", "REG", 1450, arrivals(14, 14), weekdaysOnTime([2, 2, 2, 2, 2, 2, 2])),
        departure("2", "REG", null, arrivals(5, 5)),
        fixtureTrain("3", ROMA_TERMINI, "REG", 5, 0, [stopFromDelays(ROMA_TERMINI, [0, 0, 0, 0, 0]), stopFromDelays(TIVOLI, [0, 0, 0, 0, 0], [0, 0, 600])]),
        // Not a minute (out of the three days, a fraction, a string) or weekdays that do not add up: unknown, the train stays.
        departure("4", "REG", 4320, arrivals(14, 14), weekdaysOnTime([2, 2, 2, 2, 2, 2, 1])),
        departure("5", "REG", 61.5, arrivals(14, 14), [[2, 3, 2], [2, 2, 2], [2, 2, 2], [2, 2, 2], [2, 2, 2], [2, 2, 2], [2, 2, 2]]),
        departure("6", "REG", "08:12" as unknown as number, arrivals(14, 14), "every day"),
      ])
    )!;
    const [one, two, three, four, five, six] = stats.trains;
    expect(Array.from(one.scheduledMinute)).toEqual([1450, 1490]);
    expect(one.weekdays[0]).toBe(0xff);
    expect(one.weekdays[1]).toBe(0);
    expect(Array.from(two.scheduledMinute)).toEqual([NO_SCHEDULED_MINUTE, NO_SCHEDULED_MINUTE]);
    expect(Array.from(three.scheduledMinute)).toEqual([NO_SCHEDULED_MINUTE, 600]);
    for (const train of [four, five, six]) {
      expect(train.scheduledMinute[0], train.number).toBe(NO_SCHEDULED_MINUTE);
      expect(train.weekdays[0], train.number).toBe(0);
      expect(train.mayRunOn(0, 7), train.number).toBe(true);
    }
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
  it("measures the train at its terminus, the only one with its number", () => {
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

  it("gives no figure when several trains of the number, from different origins, run, whatever their samples", () => {
    const sameNumber = (origin: string, delays: number[]) =>
      fixtureTrain("9558", origin, "FR", delays.length, 0, [stopFromDelays(origin, Array(delays.length).fill(0)), stopFromDelays(MILANO_CENTRALE, delays)]);
    const shared = parsePunctualityStats(statsDocument([sameNumber(ROMA_TERMINI, Array(20).fill(0)), sameNumber(FIRENZE_SMN, Array(5).fill(30))]))!;
    expect(trainPunctuality(shared, "9558")).toEqual({ kind: "sharedNumber" });
    expect(trainPunctuality(shared, "FR 9558")).toEqual({ kind: "sharedNumber" });
    // Four samples from Firenze are still another train: no guess that the one from Roma is meant, as in the app.
    const fewer = parsePunctualityStats(statsDocument([sameNumber(ROMA_TERMINI, Array(20).fill(0)), sameNumber(FIRENZE_SMN, Array(4).fill(30))]))!;
    expect(trainPunctuality(fewer, "9558")).toEqual({ kind: "sharedNumber" });
    // Two trains cancelled every day from different origins: neither is chosen.
    const twoCancelled = parsePunctualityStats(
      statsDocument([fixtureTrain("9558", ROMA_TERMINI, "FR", 9, 9, []), fixtureTrain("9558", FIRENZE_SMN, "FR", 7, 7, [])])
    )!;
    expect(trainPunctuality(twoCancelled, "9558")).toEqual({ kind: "sharedNumber" });
    // A train cancelled every day does not run: beside one with figures, those figures are given.
    const cancelled = parsePunctualityStats(statsDocument([sameNumber(ROMA_TERMINI, Array(20).fill(0)), fixtureTrain("9558", FIRENZE_SMN, "FR", 9, 9, [])]))!;
    expect(trainPunctuality(cancelled, "9558")).toMatchObject({ kind: "stats", samples: 20 });
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
    expect(routePunctuality(stats, [FIRENZE_SMN], [MILANO_CENTRALE])).toMatchObject({ trains: 2, samples: 31, onTime: 22, limits: [5] });
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

describe("most punctual departures", () => {
  // A day of the week of Monday 9 – Sunday 15 November 2026, which has no holiday.
  const clockAt = (hours: number, minutes: number, isoWeekday = 1): RailClock => ({
    minuteOfDay: hours * 60 + minutes,
    isoWeekday,
    date: `2026-11-${String(8 + isoWeekday).padStart(2, "0")}`,
  });
  const numbers = (result: ReturnType<typeof mostPunctualDepartures>) => result?.map(train => train.number);
  const departures = () => parsePunctualityStats(departuresDocument())!;

  it("reads the Italian clock, winter and summer time, with the weekday", () => {
    expect(railClock(new Date(MONDAY_MORNING))).toEqual({ minuteOfDay: 7 * 60 + 30, isoWeekday: 1, date: "2026-11-09" });
    expect(railClock(new Date("2026-11-08T22:30:00Z"))).toEqual({ minuteOfDay: 23 * 60 + 30, isoWeekday: 7, date: "2026-11-08" });
    expect(railClock(new Date("2026-07-05T21:45:00Z"))).toEqual({ minuteOfDay: 23 * 60 + 45, isoWeekday: 7, date: "2026-07-05" });
    // The night the clocks go forward: 01:30 CET, then 03:30 CEST an hour later.
    expect(railClock(new Date("2026-03-29T00:30:00Z")).minuteOfDay).toBe(90);
    expect(railClock(new Date("2026-03-29T01:30:00Z")).minuteOfDay).toBe(210);
    expect(railClock(new Date("2026-11-09T23:00:00Z"))).toEqual({ minuteOfDay: 0, isoWeekday: 2, date: "2026-11-10" });
  });

  it("knows the Italian national holidays, Easter Monday included", () => {
    const holidays2026 = ["01-01", "01-06", "04-05", "04-06", "04-25", "05-01", "06-02", "08-15", "10-04", "11-01", "12-08", "12-25", "12-26"];
    for (const day of holidays2026) expect(isItalianHoliday(`2026-${day}`), day).toBe(true);
    for (const day of ["2026-04-07", "2026-12-07", "2026-12-24", "2026-11-02", "2027-04-05"]) expect(isItalianHoliday(day), day).toBe(false);
    // Easter Monday 2027 and 2030; 4 October is a holiday again only from 2026.
    expect(isItalianHoliday("2027-03-29")).toBe(true);
    expect(isItalianHoliday("2030-04-22")).toBe(true);
    expect(isItalianHoliday("2025-10-04")).toBe(false);
  });

  it("keeps the trains leaving the origin in the next three hours with five arrivals or more, best first, three at most", () => {
    const result = mostPunctualDepartures(departures(), [ROMA_TERMINI], [TIVOLI], clockAt(7, 30));
    expect(result).toEqual([
      { number: "2157", minuteOfDay: 492, minutesUntil: 42, samples: 30, onTime: 27, tenths: 9, percent: 90, limitMinutes: 15, meanDelayMinutes: 5 },
      { number: "2161", minuteOfDay: 552, minutesUntil: 102, samples: 30, onTime: 24, tenths: 8, percent: 80, limitMinutes: 15, meanDelayMinutes: 7 },
      { number: "2165", minuteOfDay: 612, minutesUntil: 162, samples: 30, onTime: 21, tenths: 7, percent: 70, limitMinutes: 15, meanDelayMinutes: 10 },
    ]);
    expect(numbers(mostPunctualDepartures(departures(), [ROMA_TERMINI], [TIVOLI], clockAt(7, 30), 10))).toEqual(["2157", "2161", "2165", "2169"]);
  });

  it("includes a train leaving now and leaves out one leaving exactly three hours later", () => {
    const stats = parsePunctualityStats(statsDocument([departure("1", "REG", 600, arrivals(5, 5)), departure("2", "REG", 780, arrivals(5, 5))]))!;
    expect(numbers(mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(10, 0)))).toEqual(["1"]);
    expect(numbers(mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(10, 1)))).toEqual(["2"]);
    expect(numbers(mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(13, 1)))).toEqual([]);
  });

  it("wraps past midnight, reading scheduled minutes beyond 1439 as the next day's", () => {
    const stats = parsePunctualityStats(
      statsDocument([
        departure("late", "REG", 1430, arrivals(5, 5)),
        departure("after-midnight", "REG", 1450, arrivals(5, 4)),
        departure("early", "REG", 60, arrivals(5, 3)),
        departure("too-early", "REG", 140, arrivals(5, 5)),
        departure("gone", "REG", 1300, arrivals(5, 5)),
      ])
    )!;
    // Sunday at 23:00: the five covered days (Thursday 5 – Monday 9 November) include Sunday and Monday.
    const result = mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(23, 0, 7), 10);
    expect(result?.map(train => [train.number, train.minuteOfDay, train.minutesUntil])).toEqual([
      ["late", 1430, 50],
      ["after-midnight", 10, 70],
      ["early", 60, 120],
    ]);
  });

  it("leaves out trains without a scheduled minute at the origin and below five arrivals at the destination", () => {
    const stats = parsePunctualityStats(
      statsDocument([
        departure("none", "REG", null, arrivals(5, 5)),
        departure("four", "REG", 500, arrivals(4, 4)),
        departure("five", "REG", 500, arrivals(5, 1)),
        // Scheduled minutes only at the destination: the departure is unknown.
        fixtureTrain("arrival-only", ROMA_TERMINI, "REG", 5, 0, [stopFromDelays(ROMA_TERMINI, [0, 0, 0, 0, 0]), stopFromDelays(TIVOLI, [0, 0, 0, 0, 0], [0, 0, 520])]),
      ])
    )!;
    expect(numbers(mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(8, 0)))).toEqual(["five"]);
  });

  it("ranks by on-time share in exact integers, then mean delay, then the earlier departure", () => {
    const stats = parsePunctualityStats(
      statsDocument([
        // All observed on the same 20 days, with arrivals at Tivoli measured on some of them.
        // 7 of 10 and 14 of 20: the same share; the mean delay decides, then the departure.
        departure("later-same", "REG", 560, [...Array(14).fill(2), ...Array(6).fill(40)], null, 20),
        departure("earlier-same", "REG", 520, [...Array(7).fill(2), ...Array(3).fill(40)], null, 20),
        departure("lower-mean", "REG", 590, [...Array(7).fill(0), ...Array(3).fill(30)], null, 20),
        // 2 of 3 rounds like 7 of 10 but is less: it comes after them.
        departure("two-thirds", "REG", 500, [...Array(4).fill(0), ...Array(2).fill(20)], null, 20),
        departure("best", "REG", 600, arrivals(6, 6), null, 20),
      ])
    )!;
    expect(numbers(mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(8, 0), 10))).toEqual([
      "best",
      "lower-mean",
      "earlier-same",
      "later-same",
      "two-thirds",
    ]);
  });

  it("counts a cancelled day as a day not on time, so a train often cancelled is not first", () => {
    // Every arrival on time, but cancelled on `cancelled` of `days` days, leaving Roma Termini at `scheduled`.
    const cancelledOn = (number: string, scheduled: number, days: number, cancelled: number) =>
      fixtureTrain(number, ROMA_TERMINI, "REG", days, cancelled, [
        stopFromDelays(ROMA_TERMINI, Array(days - cancelled).fill(0), [0, 0, scheduled, null, null]),
        stopFromDelays(TIVOLI, Array(days - cancelled).fill(3), [0, 3, scheduled + 40, null, null]),
      ]);
    const stats = parsePunctualityStats(
      statsDocument([
        // Every train observed on the same 20 days.
        // On time on the 10 days it ran, cancelled on 10 more: on time on half the days.
        cancelledOn("often-cancelled", 500, 20, 10),
        // 8 of the 10 arrivals measured, never cancelled.
        departure("steady", "REG", 540, arrivals(10, 8), null, 20),
        // On time on the 19 days it ran, cancelled once: 19 of 20 days.
        cancelledOn("once-cancelled", 580, 20, 1),
      ])
    )!;
    const result = mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(8, 0), 10);
    expect(numbers(result)).toEqual(["once-cancelled", "steady", "often-cancelled"]);
    // The arrivals stay those of the days it ran; the share said is of the days observed.
    expect(result?.[2]).toMatchObject({ samples: 10, onTime: 10, tenths: 5, percent: 50 });
    expect(result?.[0]).toMatchObject({ samples: 19, onTime: 19, tenths: 9, percent: 95 });
    expect(result?.[1]).toMatchObject({ samples: 10, onTime: 8, tenths: 8, percent: 80 });
  });

  it("measures each train against its own limit: 5 minutes for high speed, 15 for the others and Trenord", () => {
    const sixMinutes = Array(10).fill(6);
    const stats = parsePunctualityStats(
      statsDocument([
        departure("9530", "FR", 480, sixMinutes),
        departure("8901", "ITALO", 490, sixMinutes),
        departure("2301", "REG", 500, sixMinutes),
        departure("RE_11 - 2153", "RE", 510, sixMinutes),
        departure("25012", "S", 520, sixMinutes),
      ])
    )!;
    const result = mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(8, 0), 10)!;
    expect(result.map(train => [train.number, train.limitMinutes, train.tenths])).toEqual([
      ["2301", 15, 10],
      ["2153", 15, 10],
      ["25012", 15, 10],
      ["9530", 5, 0],
      ["8901", 5, 0],
    ]);
  });

  it("leaves out a train whose weekdays show it does not run on the day of the departure", () => {
    const weekend = weekdaysOnTime([0, 0, 0, 0, 0, 8, 8]);
    const stats = parsePunctualityStats(
      statsDocument([
        departure("weekend", "REG", 510, arrivals(16, 16), weekend),
        // Reached after midnight: Saturday's run reaches the origin on Sunday at 00:20.
        departure("night", "REG", 1460, arrivals(16, 16), weekdaysOnTime([0, 0, 0, 0, 0, 16, 0])),
        departure("daily", "REG", 520, arrivals(16, 8)),
      ])
    )!;
    const at = (hours: number, minutes: number, weekday: number) =>
      numbers(mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(hours, minutes, weekday), 10));
    expect(at(8, 0, 1)).toEqual(["daily"]);
    expect(at(8, 0, 6)).toEqual(["weekend", "daily"]);
    expect(at(8, 0, 7)).toEqual(["weekend", "daily"]);
    // Saturday 23:30: the 00:20 departure is on Sunday, of Saturday's run.
    expect(at(23, 30, 6)).toEqual(["night"]);
    // Sunday 00:10: still Saturday's run.
    expect(at(0, 10, 7)).toEqual(["night"]);
    expect(at(0, 10, 1)).toEqual([]);
  });

  it("treats a national holiday as a Sunday, so a train of the working days is not proposed", () => {
    const stats = parsePunctualityStats(
      statsDocument([
        departure("working-days", "REG", 510, arrivals(20, 20), weekdaysOnTime([4, 4, 4, 4, 4, 0, 0])),
        departure("sundays", "REG", 520, arrivals(16, 8), weekdaysOnTime([0, 0, 0, 0, 0, 0, 16])),
        // Reached after midnight: the run of the day before the holiday, a working day.
        departure("night", "REG", 1460, arrivals(16, 16), weekdaysOnTime([4, 4, 4, 4, 0, 0, 0])),
      ])
    )!;
    const at = (clock: RailClock) => numbers(mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clock, 10));
    // Tuesday 8 December 2026 (Immacolata) at 07:30, and Easter Monday 6 April 2026.
    expect(at({ minuteOfDay: 450, isoWeekday: 2, date: "2026-12-08" })).toEqual(["sundays"]);
    expect(at({ minuteOfDay: 450, isoWeekday: 1, date: "2026-04-06" })).toEqual(["sundays"]);
    // The working day before: the working-days train.
    expect(at({ minuteOfDay: 450, isoWeekday: 1, date: "2026-12-07" })).toEqual(["working-days"]);
    // Tuesday 8 December at 00:10 is still Monday's run; Monday 7 at 23:30 leads to it as well.
    expect(at({ minuteOfDay: 10, isoWeekday: 2, date: "2026-12-08" })).toEqual(["night"]);
    expect(at({ minuteOfDay: 23 * 60 + 30, isoWeekday: 1, date: "2026-12-07" })).toEqual(["night"]);
    // Tuesday 8 December at 23:30, and Wednesday 9 at 00:10: the 00:20 belongs to the holiday's run.
    expect(at({ minuteOfDay: 23 * 60 + 30, isoWeekday: 2, date: "2026-12-08" })).toEqual([]);
    expect(at({ minuteOfDay: 10, isoWeekday: 3, date: "2026-12-09" })).toEqual([]);
    expect(at({ minuteOfDay: 10, isoWeekday: 4, date: "2026-12-10" })).toEqual(["night"]);
  });

  it("proposes a train whose weekdays the file leaves out only on a weekday its observed days show it runs", () => {
    // 30 days covered, 11 October – 9 November 2026 (a Monday): five Sundays and Mondays, four of every other weekday.
    const stats = parsePunctualityStats(
      statsDocument([
        // Saturdays and Sundays only: 8 days of 30, every one on time, so no weekdays in the file (below 14 samples).
        departure("weekend", "REG", 510, arrivals(8, 8)),
        // Every day but three not observed: fewer missing days than the four or five of any weekday.
        departure("daily-gaps", "REG", 515, arrivals(27, 24)),
        departure("daily", "REG", 520, arrivals(30, 24)),
      ])
    )!;
    expect(stats.coveredDays).toBe(30);
    expect([1, 2, 3, 4, 5, 6, 7].map(weekday => stats.coveredDaysOn(weekday))).toEqual([5, 4, 4, 4, 4, 4, 5]);
    const at = (weekday: number) => numbers(mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(8, 0, weekday), 10));
    // On a Tuesday the weekend train does not run, however punctual it is.
    expect(at(2)).toEqual(["daily-gaps", "daily"]);
    // Nor is it proposed at the weekend: without its weekdays nothing tells which days it runs.
    expect(at(6)).toEqual(["daily-gaps", "daily"]);
    expect(at(7)).toEqual(["daily-gaps", "daily"]);
  });

  it("early in the collection proposes, without weekdays, only the trains observed on every covered day", () => {
    // Six days covered, Tuesday 3 – Sunday 8 November 2026: one of each weekday but Monday.
    const stats = parsePunctualityStats(
      statsDocument([departure("every-day", "REG", 520, arrivals(6, 5)), departure("five-days", "REG", 510, arrivals(5, 5))], {
        windowEnd: "2026-11-08",
      })
    )!;
    expect(stats.coveredDays).toBe(6);
    // The day «five-days» was not observed may be the only Sunday, Wednesday… of the window: it may not run then.
    for (const weekday of [3, 7])
      expect(numbers(mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(8, 0, weekday), 10)), String(weekday)).toEqual(["every-day"]);
    // No Monday is covered yet: nothing shows that even «every-day» runs on Mondays.
    expect(stats.coveredDaysOn(1)).toBe(0);
    expect(numbers(mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(8, 0, 1), 10))).toEqual([]);
  });

  it("leaves out a train the file says was last seen more than seven days before the window's end", () => {
    const dated = (number: string, scheduled: number, onTime: number, l: unknown) => ({ ...departure(number, "REG", scheduled, arrivals(20, onTime)), l });
    const stats = parsePunctualityStats(
      statsDocument([
        // Withdrawn at the timetable change: on time every day it ran, last seen 10 days ago.
        dated("withdrawn", 500, 20, 10),
        dated("week-ago", 510, 19, 7),
        dated("today", 520, 18, 0),
        // Files without the field, or with a value that is not a day count: undated, kept.
        departure("undated", "REG", 530, arrivals(20, 17)),
        dated("bad-l", 540, 16, -3),
        dated("text-l", 550, 15, "10"),
      ])
    )!;
    expect(Object.fromEntries(stats.trains.map(train => [train.number, train.daysSinceSeen]))).toEqual({
      withdrawn: 10,
      "week-ago": 7,
      today: 0,
      undated: -1,
      "bad-l": -1,
      "text-l": -1,
    });
    expect(numbers(mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(8, 0), 10))).toEqual([
      "week-ago",
      "today",
      "undated",
      "bad-l",
      "text-l",
    ]);
    // The train's own figures are still answered when asked by number.
    expect(trainPunctuality(stats, "withdrawn")).toMatchObject({ kind: "stats", samples: 20 });
  });

  it("after a gap in the collection measures the days against those really collected", () => {
    // 30-day window to Monday 9 November 2026 with five days not collected: the most observed train has 25 days.
    const stats = parsePunctualityStats(
      statsDocument([
        departure("daily", "REG", 520, arrivals(25, 20)),
        // Two of the 25 collected days without a sample: fewer than the three or four of any weekday.
        departure("two-missing", "REG", 515, arrivals(23, 20)),
        // Saturdays and Sundays of the collected days only.
        departure("weekend", "REG", 510, arrivals(7, 7)),
      ])
    )!;
    expect(stats.coveredDays).toBe(25);
    for (const weekday of [1, 2, 3, 4, 5, 6, 7]) {
      expect(stats.coveredDaysOn(weekday), String(weekday)).toBeGreaterThanOrEqual(3);
      expect(numbers(mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(8, 0, weekday), 10)), String(weekday)).toEqual([
        "two-missing",
        "daily",
      ]);
    }
  });

  it("proposes on no weekday the window has not covered, as after a collection restarted on a Monday", () => {
    // Monday 2 – Saturday 7 November 2026: a Monday-to-Saturday train is seen on all six days.
    const stats = parsePunctualityStats(statsDocument([departure("mon-sat", "REG", 520, arrivals(6, 6))], { windowEnd: "2026-11-07" }))!;
    expect(stats.coveredDays).toBe(6);
    expect([1, 2, 3, 4, 5, 6, 7].map(weekday => stats.coveredDaysOn(weekday))).toEqual([1, 1, 1, 1, 1, 1, 0]);
    expect(numbers(mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(8, 0, 6), 10))).toEqual(["mon-sat"]);
    // Sunday, and a holiday, which runs the Sunday service: not proposed.
    expect(numbers(mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], clockAt(8, 0, 7), 10))).toEqual([]);
    expect(numbers(mostPunctualDepartures(stats, [ROMA_TERMINI], [TIVOLI], { minuteOfDay: 480, isoWeekday: 2, date: "2026-12-08" }, 10))).toEqual([]);
  });

  it("matches the route as the route's punctuality does, and says nothing without codes", () => {
    const stats = departures();
    expect(mostPunctualDepartures(stats, [TIVOLI], [ROMA_TERMINI], clockAt(7, 30))).toEqual([]);
    expect(mostPunctualDepartures(stats, [ROMA_TERMINI, "S99999"], [TIVOLI], clockAt(7, 30))).toHaveLength(3);
    expect(mostPunctualDepartures(stats, [ROMA_TERMINI], [ROMA_TERMINI], clockAt(7, 30))).toBeNull();
    expect(mostPunctualDepartures(stats, [], [TIVOLI], clockAt(7, 30))).toBeNull();
    expect(mostPunctualDepartures(stats, ["Roma Termini"], [TIVOLI], clockAt(7, 30))).toBeNull();
    // A file of before the 4 November extension has no scheduled minutes: no train to propose.
    expect(mostPunctualDepartures(network(), [ROMA_TERMINI], [TIVOLI], clockAt(7, 30))).toEqual([]);
  });

  it("never says always or never unless every or no arrival was on time", () => {
    expect(boundedShare(29, 30, 10)).toBe(9);
    expect(boundedShare(30, 30, 10)).toBe(10);
    expect(boundedShare(1, 30, 10)).toBe(1);
    expect(boundedShare(0, 30, 10)).toBe(0);
    expect(boundedShare(8, 10, 10)).toBe(8);
    expect(boundedShare(17, 20, 10)).toBe(9);
    expect(boundedShare(199, 200, 100)).toBe(99);
    expect(boundedShare(0, 0, 10)).toBe(0);
  });
});
