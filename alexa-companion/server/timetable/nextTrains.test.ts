import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { TimetableIndex } from "./repository";
import { nextTrainsContract, writeIndex } from "./testIndex";

/**
 * contracts/shared-rules/v1/next-trains-v1.json: the next trains between two stations in the
 * programmed timetable index, as the iPhone planner reads them (NextTrainsContractTests.swift).
 */
const contract = nextTrainsContract();
let scratch: string;
let index: TimetableIndex;

beforeAll(() => {
  scratch = mkdtempSync(path.join(tmpdir(), "binario-next-trains-"));
  const file = path.join(scratch, "index.sqlite");
  writeIndex(file, contract.index);
  index = new TimetableIndex(file);
});

afterAll(() => {
  index.close();
  rmSync(scratch, { recursive: true, force: true });
});

describe("next trains, shared with the iPhone planner", () => {
  it.each(contract.cases.map(item => [item.why, item] as const))("%s", (_why, item) => {
    const result = index.nextTrains([item.origin], [item.destination], Date.parse(item.at));
    if ("unavailable" in item.expected) {
      expect(result.kind).toBe(item.expected.unavailable);
      return;
    }
    expect(result.kind).toBe("trains");
    if (result.kind !== "trains") return;
    expect(
      result.services.map(service => ({
        time: service.time,
        number: service.number,
        category: service.category,
        operator: service.operatorName,
        destination: service.destination,
        vehicle: service.vehicle,
      }))
    ).toEqual(item.expected.trains);
  });
});
