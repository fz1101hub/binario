import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { daysOnTime, MIN_SAMPLES, onTimeLimitMinutes, parsePunctualityStats, trainNumberKey, trainPunctuality } from "./stats";

/**
 * contracts/shared-rules/v1: the train-number keys and the statistics rules Android, iPhone and
 * Alexa must share, read from the same file by the three test suites.
 */
const contract = path.resolve(import.meta.dirname, "..", "..", "..", "contracts", "shared-rules", "v1");

type Figures = {
  samples: number;
  onTime: number;
  limitMinutes: number;
  daysObserved: number;
  daysCancelled: number;
  daysOnTime: { part: number; whole: number };
};

type Rules = {
  trainNumbers: { published: string; key: string }[];
  statistics: {
    document: string;
    minimumSamples: number;
    onTimeLimitMinutes: { category: string; minutes: number }[];
    lastStopWithoutOrigin: { number: string; divergent?: string; expected?: Figures | null; alexa?: Figures | null }[];
  };
};

const rules = JSON.parse(readFileSync(path.join(contract, "rules-v1.json"), "utf8")) as Rules;

describe("shared rules contract", () => {
  it("gives every train number the shared statistics key", () => {
    for (const { published, key } of rules.trainNumbers) expect(trainNumberKey(published), published).toBe(key);
  });

  it("uses the shared on-time limits and minimum samples", () => {
    expect(MIN_SAMPLES).toBe(rules.statistics.minimumSamples);
    for (const { category, minutes } of rules.statistics.onTimeLimitMinutes)
      expect(onTimeLimitMinutes(category), `«${category}»`).toBe(minutes);
  });

  it("measures a train without origin at its last stop as the other readers do", () => {
    const stats = parsePunctualityStats(readFileSync(path.join(contract, rules.statistics.document), "utf8"));
    if (!stats) throw new Error("the shared statistics fixture did not parse");
    for (const item of rules.statistics.lastStopWithoutOrigin) {
      // A case where Alexa differs from the app carries both («divergent»).
      const figures = (item.divergent === undefined ? item.expected : item.alexa) ?? null;
      const answer = trainPunctuality(stats, item.number);
      if (!figures) {
        expect(answer.kind, item.number).not.toBe("stats");
        continue;
      }
      expect(answer, item.number).toMatchObject({
        kind: "stats",
        samples: figures.samples,
        onTime: figures.onTime,
        limitMinutes: figures.limitMinutes,
        days: figures.daysObserved,
        cancelledDays: figures.daysCancelled,
      });
      // A cancelled day counts as a day not on time: the train answered, at its last stop.
      const train = stats
        .trainsNumbered(item.number)
        .filter(candidate => candidate.stopCount > 0 && candidate.samples[candidate.stopCount - 1] === figures.samples)[0];
      expect(train, item.number).toBeDefined();
      expect(daysOnTime(train, train.stopCount - 1), item.number).toEqual(figures.daysOnTime);
    }
  });
});
