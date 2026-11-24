import { PackRoles } from "@shared/generated/sharedData";
import { normalizeName } from "./naming";

/**
 * One train that two packs of the timetable both publish (the national Trenitalia pack and the
 * Swiss pack for a EuroCity), kept once, as the iPhone (`TimetableCopies`) and Android
 * (`EmbeddedGtfsRouteAdapter.withoutDuplicates` with `PlannedPackOrder`) keep it: the national
 * packs' copy first, the foreign packs' copy first at a station no national pack serves, the
 * Italian regional packs in between. The pack roles are contracts/shared-data/v1/pack-roles-v1.json.
 */

/** A service as the copies are compared: pack, number, operator, minutes it leaves and arrives, names of its two stops. */
export type TimetableRun = {
  sourceId: string;
  number: string;
  operatorName: string;
  /** Epoch milliseconds. */
  departure: number;
  arrival: number | null;
  originName: string;
  destinationName: string;
  /** A run of a city network: one pack's own line, never a copy of another pack's train. */
  urban?: boolean;
};

/** National, Italian regional, foreign; with `border` (no national pack serves an end) foreign, Italian regional, national. */
export function packRank(sourceId: string, border: boolean): number {
  const national = PackRoles.NATIONAL_PACKS.has(sourceId);
  const foreign = PackRoles.FOREIGN_PACKS.has(sourceId);
  if (border) return foreign ? 0 : national ? 2 : 1;
  return national ? 0 : foreign ? 2 : 1;
}

/** The timetable's minute of an instant. */
function minute(instant: number): number {
  return Math.floor(instant / 60_000);
}

/** Upper case, letters and digits only. */
function compactNumber(number: string): string {
  return number.toUpperCase().replace(/[^A-Z0-9]/g, "");
}

/** The last run of digits after the last « - », without leading zeros («S11 - 25017» is 25017); null without one. */
export function coreNumber(number: string): string | null {
  const separator = number.lastIndexOf(" - ");
  const tail = separator >= 0 ? number.slice(separator + 3) : number;
  const runs = tail.match(/[0-9]+/g);
  if (!runs) return null;
  const trimmed = runs[runs.length - 1].replace(/^0+/, "");
  return trimmed || null;
}

/**
 * Two services of different packs at the same minutes are one run when their numbers match, when
 * the operator is the same, or when one pack republishes partner operators' trains. A city run (its
 * line is no train number) is never the same run as another pack's service.
 */
export function mayBeSameRun(first: TimetableRun, second: TimetableRun): boolean {
  if (first.urban || second.urban) return false;
  const number = coreNumber(first.number);
  if (number !== null && number === coreNumber(second.number)) return true;
  const firstOperator = first.operatorName.trim().toLowerCase();
  if (firstOperator && firstOperator === second.operatorName.trim().toLowerCase()) return true;
  return PackRoles.CROSS_PUBLISHING_PACKS.has(first.sourceId) || PackRoles.CROSS_PUBLISHING_PACKS.has(second.sourceId);
}

/** Departure minute, then the preferred pack, then the number; equal services keep their order. */
function preferredOrder(runs: readonly TimetableRun[], border: boolean): number[] {
  return runs
    .map((_, index) => index)
    .sort((first, second) => {
      const one = runs[first];
      const other = runs[second];
      if (minute(one.departure) !== minute(other.departure)) return minute(one.departure) - minute(other.departure);
      const rankDifference = packRank(one.sourceId, border) - packRank(other.sourceId, border);
      if (rankDifference !== 0) return rankDifference;
      if (one.number !== other.number) return one.number < other.number ? -1 : 1;
      return first - second;
    });
}

/**
 * The planner's copies: in departure order, the preferred pack first, one service per number,
 * minute and pair of stops; then, between two packs, one service per departure and arrival minute
 * when `mayBeSameRun`. The indices of `runs` kept, in that order.
 */
export function keptRuns(runs: readonly TimetableRun[], border: boolean): number[] {
  const identities = new Set<string>();
  const keptByTimes = new Map<string, number[]>();
  const kept: number[] = [];
  for (const index of preferredOrder(runs, border)) {
    const run = runs[index];
    const identity = [
      compactNumber(run.number),
      String(minute(run.departure)),
      normalizeName(run.originName),
      normalizeName(run.destinationName),
    ].join("|");
    if (identities.has(identity)) continue;
    identities.add(identity);
    if (run.arrival !== null) {
      const times = `${minute(run.departure)}|${minute(run.arrival)}`;
      const others = keptByTimes.get(times) ?? [];
      if (others.some(other => runs[other].sourceId !== run.sourceId && mayBeSameRun(runs[other], run))) continue;
      keptByTimes.set(times, [...others, index]);
    }
    kept.push(index);
  }
  return kept;
}
