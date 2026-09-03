import { describe, expect, it } from "vitest";
import { findCanonicalOfflineStationByCode, findNearbyOfflineStations, offlineStationCatalog, searchCanonicalOfflineStations, searchOfflineStations } from "../lib/offline-station-index";

describe("indice stazioni offline", () => {
  it("espone il catalogo canonico versionato e con checksum", () => {
    expect(offlineStationCatalog.version).toBe("stazioni-canoniche-v2");
    expect(offlineStationCatalog.checksumSha256).toHaveLength(64);
    expect(offlineStationCatalog.stationCount).toBe(offlineStationCatalog.stations.length);
    expect(offlineStationCatalog.stationCount).toBeGreaterThan(3_000);
  });

  it("trova nome, alias e codice senza risultati inventati", () => {
    const stations = [{ id: "A", name: "Roma Ostiense", code: "S00001", aliases: ["OSTIENSE"] }, { id: "B", name: "Roma San Pietro", code: "S00002", aliases: ["SAN PIETRO"] }];
    expect(searchOfflineStations(stations, "osti")[0]?.code).toBe("S00001");
    expect(searchOfflineStations(stations, "S00002")[0]?.name).toBe("Roma San Pietro");
    expect(searchOfflineStations(stations, "inesistente")).toEqual([]);
    expect(searchCanonicalOfflineStations("Abano")[0]?.code).toBe("S05700");
    expect(findCanonicalOfflineStationByCode("S05700")?.name).toBe("Abano");
    expect(findCanonicalOfflineStationByCode("inesistente")).toBeNull();
  });

  it("ordina stazioni vicine usando esclusivamente coordinate presenti nel catalogo", () => {
    const nearby = findNearbyOfflineStations(41.8, 12.5, 1);
    expect(nearby).toHaveLength(1);
    expect(nearby[0]?.station.latitude).not.toBeNull();
    expect(nearby[0]?.distanceKm).toBeGreaterThanOrEqual(0);
  });
});
