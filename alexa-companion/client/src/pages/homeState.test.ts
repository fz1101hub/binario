import { describe, expect, it } from "vitest";
import { queryView } from "./homeState";

describe("dashboard query states", () => {
  const status = { configured: true, linked: true };

  it("shows loading, not «not linked», while the status has not arrived", () => {
    expect(queryView({ data: undefined, isError: false })).toEqual({ state: "loading" });
  });

  it("shows an error, not «not linked», when the status request failed", () => {
    expect(queryView({ data: undefined, isError: true })).toEqual({ state: "error" });
  });

  it("shows the data once it arrived, also when a later refresh failed", () => {
    expect(queryView({ data: status, isError: false })).toEqual({ state: "ready", data: status });
    expect(queryView({ data: status, isError: true })).toEqual({ state: "ready", data: status });
    expect(queryView({ data: [], isError: false })).toEqual({ state: "ready", data: [] });
  });
});
