import { sql } from "drizzle-orm";
import { getDb } from "../db";
import { punctualityStats, railStationIndex } from "../punctuality/source";
import { isAlexaConfigured } from "../voice/config";
import { ENV } from "./env";

/**
 * `GET /readyz`: whether this process can serve, for a proxy or a monitor to see a bad deploy that
 * `/healthz` (the process answers) cannot: a database that does not answer, or the rail data of
 * the skill missing from `dist/data`. States only, nothing about users or the data itself.
 */
export type Readiness = {
  ready: boolean;
  database: "ok" | "unreachable" | "notConfigured";
  /** The punctuality statistics and the station catalogue the skill reads; not needed without the skill. */
  railData: "loaded" | "unavailable" | "notNeeded";
};

export type ReadinessProbes = {
  database: () => Promise<Readiness["database"]>;
  railData: () => Promise<Readiness["railData"]>;
  production: boolean;
};

/** A database that does not answer within this long counts as unreachable. */
export const READINESS_TIMEOUT_MS = 3_000;

/** `work`, or a rejection once `timeoutMs` have passed without it settling. */
export function withinTimeout<T>(work: Promise<T>, timeoutMs: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => reject(new Error("timeout")), timeoutMs);
    timer.unref?.();
  });
  return Promise.race([work, timeout]).finally(() => clearTimeout(timer));
}

export const DEFAULT_READINESS_PROBES: ReadinessProbes = {
  database: async () => {
    const db = await getDb();
    if (!db) return "notConfigured";
    try {
      await withinTimeout(db.execute(sql`SELECT 1`), READINESS_TIMEOUT_MS);
      return "ok";
    } catch {
      return "unreachable";
    }
  },
  railData: async () => {
    if (!isAlexaConfigured()) return "notNeeded";
    const [stats, stations] = await Promise.all([punctualityStats(), railStationIndex()]);
    return stats && stations ? "loaded" : "unavailable";
  },
  get production() {
    return ENV.isProduction;
  },
};

/** Ready with a database that answers (or none configured, outside production) and the skill's rail data when the skill is on. */
export async function checkReadiness(probes: ReadinessProbes = DEFAULT_READINESS_PROBES): Promise<Readiness> {
  const [database, railData] = await Promise.all([probes.database(), probes.railData()]);
  const databaseReady = database === "ok" || (database === "notConfigured" && !probes.production);
  return { ready: databaseReady && railData !== "unavailable", database, railData };
}
