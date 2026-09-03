import { describe, expect, it } from "vitest";
import { MAX_LOCAL_FAVORITE_STATIONS, parseLocalFavoriteStations, toggleLocalFavoriteStation } from "../shared/localFavoriteStations";

const roma = { stationId: "roma-termini", name: "Roma Termini", code: "8300000", latitude: 41.9, longitude: 12.5, aliases: [], confidence: "Alta" as const, trenitaliaLocationId: null };

describe("preferiti stazioni locali", () => {
  it("recupera da dati corrotti e deduplica per codice", () => {
    expect(parseLocalFavoriteStations("non json")).toEqual([]);
    expect(parseLocalFavoriteStations(JSON.stringify([{ stationId: "roma", name: "Roma Termini", code: "8300000" }, { stationId: "roma-2", name: "Roma T.", code: "8300000" }, { invalid: true }]))).toEqual([{ stationId: "roma", name: "Roma Termini", code: "8300000" }]);
  });

  it("aggiunge, rimuove e limita le stazioni senza ranking", () => {
    const added = toggleLocalFavoriteStation([], roma);
    expect(added).toHaveLength(1);
    expect(toggleLocalFavoriteStation(added, roma)).toEqual([]);
    const many = Array.from({ length: MAX_LOCAL_FAVORITE_STATIONS }, (_, index) => ({ stationId: `stazione-${index}`, name: `Stazione ${index}`, code: `S${index}` }));
    expect(toggleLocalFavoriteStation(many, roma)).toHaveLength(MAX_LOCAL_FAVORITE_STATIONS);
  });
});
