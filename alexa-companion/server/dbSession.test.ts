import { EventEmitter } from "node:events";
import { describe, expect, it, vi } from "vitest";

const pools = vi.hoisted(() => [] as Array<{ options: unknown; pool: EventEmitter }>);
vi.mock("mysql2", async importOriginal => ({
  ...(await importOriginal<typeof import("mysql2")>()),
  createPool: (options: unknown) => {
    const pool = new EventEmitter();
    pools.push({ options, pool });
    return pool;
  },
}));

import { createDatabasePool, OAUTH_RETENTION_DAYS, oauthRetentionCutoff, UTC_SESSION_STATEMENT } from "./db";

describe("database session time zone", () => {
  it("sets every new pool connection to UTC before its first query", () => {
    createDatabasePool("mysql://user:secret@db.invalid:3306/binario");
    const [{ options, pool }] = pools;
    expect(options).toEqual({ uri: "mysql://user:secret@db.invalid:3306/binario" });
    const statements: string[] = [];
    pool.emit("connection", { query: (sql: string) => void statements.push(sql) });
    expect(UTC_SESSION_STATEMENT).toBe("SET time_zone = '+00:00'");
    expect(statements).toEqual([UTC_SESSION_STATEMENT]);
  });

  it("logs a failure without the connection string", () => {
    const warnings = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    createDatabasePool("mysql://user:secret@db.invalid:3306/binario");
    const { pool } = pools[pools.length - 1];
    pool.emit("connection", { query: (_sql: string, done: (error: Error) => void) => done(new Error("Unknown or incorrect time zone")) });
    expect(warnings).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(warnings.mock.calls)).not.toContain("secret");
    warnings.mockRestore();
  });
});

describe("OAuth retention", () => {
  it("keeps finished codes and refresh tokens for 30 days", () => {
    expect(OAUTH_RETENTION_DAYS).toBe(30);
    expect(oauthRetentionCutoff(new Date("2026-11-12T10:00:00Z")).toISOString()).toBe("2026-10-13T10:00:00.000Z");
  });
});
