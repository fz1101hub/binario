import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { ItalianHolidays, Punctuality } from "@shared/generated/sharedData";
import {
  HIGH_SPEED_ON_TIME_MINUTES,
  isItalianHoliday,
  MIN_AGGREGATE_SAMPLES,
  MIN_SAMPLES,
  onTimeLimitMinutes,
  OTHER_ON_TIME_MINUTES,
} from "./stats";

/**
 * contracts/shared-data/v1: the tables the skill reads, from their JSON files as the Android and
 * iPhone tests read them, against the generated module and the code that uses it.
 */
const tables = path.resolve(import.meta.dirname, "..", "..", "..", "contracts", "shared-data", "v1");
const table = <T>(name: string): T => JSON.parse(readFileSync(path.join(tables, name), "utf8")) as T;

describe("shared data", () => {
  it("uses the shared punctuality thresholds", () => {
    const json = table<{
      highSpeedCategories: string[];
      highSpeedOnTimeMinutes: number;
      otherOnTimeMinutes: number;
      minimumSamples: number;
      minimumAggregateSamples: number;
    }>("punctuality-v1.json");
    expect([...Punctuality.HIGH_SPEED_CATEGORIES]).toEqual(json.highSpeedCategories);
    for (const category of json.highSpeedCategories) expect(onTimeLimitMinutes(category), category).toBe(json.highSpeedOnTimeMinutes);
    expect(onTimeLimitMinutes("REG")).toBe(json.otherOnTimeMinutes);
    expect(HIGH_SPEED_ON_TIME_MINUTES).toBe(json.highSpeedOnTimeMinutes);
    expect(OTHER_ON_TIME_MINUTES).toBe(json.otherOnTimeMinutes);
    expect(MIN_SAMPLES).toBe(json.minimumSamples);
    expect(MIN_AGGREGATE_SAMPLES).toBe(json.minimumAggregateSamples);
  });

  it("uses the shared holiday calendar", () => {
    const json = table<{ holidays: { month: number; day: number; fromYear?: number }[]; easterOffsetsDays: number[] }>(
      "italian-holidays-v1.json"
    );
    expect(ItalianHolidays.HOLIDAYS.map(({ month, day, fromYear }) => ({ month, day, fromYear }))).toEqual(
      json.holidays.map(({ month, day, fromYear }) => ({ month, day, fromYear: fromYear ?? null }))
    );
    expect(ItalianHolidays.EASTER_OFFSETS_DAYS).toEqual(json.easterOffsetsDays);
    for (const { month, day, fromYear } of json.holidays) {
      for (const year of [2024, 2025, 2026, 2027, 2028]) {
        const date = `${year}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
        expect(isItalianHoliday(date), date).toBe(year >= (fromYear ?? year));
      }
    }
    // Easter Sunday 2026 is 5 April.
    for (const [date, offset] of [["2026-04-04", -1], ["2026-04-05", 0], ["2026-04-06", 1], ["2026-04-07", 2]] as const) {
      expect(isItalianHoliday(date), date).toBe(json.easterOffsetsDays.includes(offset));
    }
  });
});
