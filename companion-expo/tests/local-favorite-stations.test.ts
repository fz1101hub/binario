import { describe, expect, it } from "vitest";
import { parseLocalFavoriteStations, toggleLocalFavoriteStation } from "../lib/local-favorite-stations";

describe("stazioni preferite locali", () => {
  it("deduplica per codice e recupera da dati non validi", () => {
    const first = toggleLocalFavoriteStation([], { code: "S08406", name: "Roma Ostiense" }, 20);
    expect(first).toHaveLength(1);
    expect(toggleLocalFavoriteStation(first, { code: "S08406", name: "Roma Ostiense" }, 21)).toEqual([]);
    expect(parseLocalFavoriteStations("{bad json")).toEqual([]);
  });
});
