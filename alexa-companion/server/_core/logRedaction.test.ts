import { TRPCError } from "@trpc/server";
import { DrizzleQueryError } from "drizzle-orm";
import { afterEach, describe, expect, it, vi } from "vitest";

// An address nothing listens on: every query fails with a real DrizzleQueryError.
vi.hoisted(() => {
  process.env.DATABASE_URL = "mysql://binario:secret-password@127.0.0.1:9/binario";
});

import { upsertUser } from "../db";
import { appRouter } from "../routers";
import { describeErrorForLog } from "./logRedaction";
import { safeErrorDetails } from "./safeError";

const PERSONAL_VALUES = [
  "Mario Rossi",
  "mario.rossi@example.com",
  "open-id-of-mario",
  "Roma Termini",
  "Casa di Mario",
  "secret-password",
];

function driverError() {
  // The shape mysql2 gives a failed statement: the interpolated SQL and a message quoting a value.
  return Object.assign(new Error("Duplicate entry 'mario.rossi@example.com' for key 'users_email'"), {
    code: "ER_DUP_ENTRY",
    errno: 1062,
    sqlState: "23000",
    sqlMessage: "Duplicate entry 'mario.rossi@example.com' for key 'users_email'",
    sql: "insert into `users` (`openId`, `name`, `email`) values ('open-id-of-mario', 'Mario Rossi', 'mario.rossi@example.com')",
  });
}

function queryError() {
  return new DrizzleQueryError(
    "insert into `railway_routes` (`userId`, `origin`, `destination`, `name`) values (?, ?, ?, ?)",
    [7, "Roma Termini", "Tivoli", "Casa di Mario"],
    driverError()
  );
}

function logged(spy: { mock: { calls: unknown[][] } }) {
  return JSON.stringify(spy.mock.calls);
}

afterEach(() => vi.restoreAllMocks());

describe("log degli errori senza dati personali", () => {
  it("conserva classe, codice driver e SQL parametrizzato, mai i valori", () => {
    const description = describeErrorForLog(queryError());
    expect(description).toEqual({
      name: "DrizzleQueryError",
      query: "insert into `railway_routes` (`userId`, `origin`, `destination`, `name`) values (?, ?, ?, ?)",
      cause: { name: "Error", code: "ER_DUP_ENTRY", errno: 1062, sqlState: "23000" },
    });
    const serialized = JSON.stringify(description);
    for (const value of PERSONAL_VALUES) expect(serialized).not.toContain(value);
  });

  it("descrive la causa di un TRPCError e i messaggi fissi del codice", () => {
    expect(describeErrorForLog(new TRPCError({ code: "INTERNAL_SERVER_ERROR", cause: queryError() }))).toMatchObject({
      name: "DrizzleQueryError",
    });
    expect(describeErrorForLog(new Error("Archivio personale non disponibile"))).toEqual({
      name: "Error",
      message: "Archivio personale non disponibile",
    });
    // JSON.parse quotes its input in the message: only the class is kept.
    let parseError: unknown;
    try {
      JSON.parse("Casa di Mario");
    } catch (error) {
      parseError = error;
    }
    expect(describeErrorForLog(parseError)).toEqual({ name: "SyntaxError" });
  });

  it("il formatter tRPC registra un errore interno senza parametri SQL", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const error = new TRPCError({ code: "INTERNAL_SERVER_ERROR", cause: queryError() });
    const shape = appRouter._def._config.errorFormatter({
      error,
      type: "mutation",
      path: "routes.create",
      input: undefined,
      ctx: undefined,
      shape: { message: error.message, code: -32603, data: { code: "INTERNAL_SERVER_ERROR", httpStatus: 500 } },
    } as never) as { message: string };
    expect(shape.message).not.toContain("Roma Termini");
    expect(spy).toHaveBeenCalledTimes(1);
    const output = logged(spy);
    expect(output).toContain("DrizzleQueryError");
    expect(output).toContain("ER_DUP_ENTRY");
    for (const value of PERSONAL_VALUES) expect(output).not.toContain(value);
  });

  it("un upsert utente fallito su un database reale non scrive nome, email né openId", async () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => undefined);
    await expect(
      upsertUser({ openId: "open-id-of-mario", name: "Mario Rossi", email: "mario.rossi@example.com" })
    ).rejects.toThrow();
    const output = logged(spy);
    expect(output).toContain("DrizzleQueryError");
    expect(output).toContain("ECONNREFUSED");
    for (const value of PERSONAL_VALUES) expect(output).not.toContain(value);
    // The OAuth callback logs the same failure through safeErrorDetails.
    const callback = JSON.stringify(safeErrorDetails(queryError()));
    for (const value of PERSONAL_VALUES) expect(callback).not.toContain(value);
  });
});
