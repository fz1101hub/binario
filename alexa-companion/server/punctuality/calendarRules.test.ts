import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { isItalianHoliday } from "./stats";

/**
 * contracts/shared-rules/v1/calendar-v1.json: the Italian holidays (the shared table and Easter),
 * read from the same file by the Android tests.
 */
const contract = path.resolve(import.meta.dirname, "..", "..", "..", "contracts", "shared-rules", "v1");
const rules = JSON.parse(readFileSync(path.join(contract, "calendar-v1.json"), "utf8")) as {
  holidays: { date: string; holiday: boolean }[];
};

describe("shared calendar rules", () => {
  it("knows the shared holidays", () => {
    expect(rules.holidays.length).toBeGreaterThan(10);
    for (const { date, holiday } of rules.holidays) expect(isItalianHoliday(date), date).toBe(holiday);
  });
});
