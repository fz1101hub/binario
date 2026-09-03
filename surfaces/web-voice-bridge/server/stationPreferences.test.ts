import { describe, expect, it } from "vitest";
import { MAX_RECENT_STATIONS, recentStationIdsToPrune } from "./db";

describe("registro stazioni recenti", () => {
  it("conserva solo il numero massimo di recenti e identifica le aperture più vecchie da rimuovere", () => {
    const recent = Array.from({ length: MAX_RECENT_STATIONS + 3 }, (_, index) => ({ id: index + 1 }));
    expect(recentStationIdsToPrune(recent)).toEqual([MAX_RECENT_STATIONS + 1, MAX_RECENT_STATIONS + 2, MAX_RECENT_STATIONS + 3]);
  });

  it("non rimuove nulla quando i recenti sono entro la soglia", () => {
    expect(recentStationIdsToPrune([{ id: 1 }, { id: 2 }])).toEqual([]);
  });
});
