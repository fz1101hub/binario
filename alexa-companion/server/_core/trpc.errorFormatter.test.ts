import { describe, expect, it } from "vitest";
import { INTERNAL_ERROR_MESSAGE, maskInternalError } from "./trpc";

describe("formattazione degli errori tRPC", () => {
  it("maschera i messaggi interni che potrebbero contenere dettagli del database", () => {
    const shape = { message: "connect ECONNREFUSED db.internal:3306", code: -32603, data: { code: "INTERNAL_SERVER_ERROR" } };
    expect(maskInternalError(shape, "INTERNAL_SERVER_ERROR").message).toBe(INTERNAL_ERROR_MESSAGE);
  });

  it("lascia intatti gli errori applicativi", () => {
    const shape = { message: "Please login (10001)", code: -32001, data: { code: "UNAUTHORIZED" } };
    expect(maskInternalError(shape, "UNAUTHORIZED")).toBe(shape);
  });
});
