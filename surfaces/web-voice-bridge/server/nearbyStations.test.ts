import { describe, expect, it } from "vitest";
import { distanceBetweenCoordinatesKm, findNearbyCanonicalStations } from "../shared/nearbyStations";
import type { CanonicalStation } from "../shared/stationCatalog";

const stations: CanonicalStation[] = [
  { stationId: "roma", name: "Roma Termini", code: "8300000", latitude: 41.901, longitude: 12.501, aliases: [], confidence: "Alta", trenitaliaLocationId: null },
  { stationId: "tivoli", name: "Tivoli", code: "8300181", latitude: 41.960, longitude: 12.798, aliases: [], confidence: "Alta", trenitaliaLocationId: null },
  { stationId: "unknown", name: "Senza coordinate", code: "000", latitude: null, longitude: null, aliases: [], confidence: "Alta", trenitaliaLocationId: null },
];

describe("stazioni vicine", () => {
  it("calcola una distanza simmetrica e non negativa", () => {
    const there = distanceBetweenCoordinatesKm(41.901, 12.501, 41.960, 12.798);
    const back = distanceBetweenCoordinatesKm(41.960, 12.798, 41.901, 12.501);
    expect(there).toBeGreaterThan(0);
    expect(there).toBeCloseTo(back, 8);
  });

  it("ordina le sole stazioni con coordinate senza inventare risultati", () => {
    expect(findNearbyCanonicalStations(stations, 41.9, 12.5, 3).map(result => result.station.name)).toEqual(["Roma Termini", "Tivoli"]);
  });

  it("restituisce un elenco vuoto quando il catalogo non contiene coordinate utilizzabili", () => {
    const noCoordinates = stations.map(station => ({ ...station, latitude: null, longitude: null }));
    expect(findNearbyCanonicalStations(noCoordinates, 41.9, 12.5)).toEqual([]);
  });
});
