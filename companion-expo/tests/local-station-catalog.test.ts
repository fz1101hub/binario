import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { buildLocalStationCatalog, computeLocalStationCatalogChecksum, localStationCatalogSourceVersion } from "../scripts/build-local-station-catalog.mjs";

const source = {
  schemaVersion: 1,
  stations: [
    { id: "S05700", name: " Abano ", railwayCode: "", aliases: ["ABANO TERME", " Abano Terme ", 7], latitude: 45.355322, longitude: 11.811495 },
    { id: "S01700", name: "Milano Centrale", railwayCode: "S01700", aliases: null, latitude: "n/a", longitude: 9.204 },
    { id: "", name: "Senza id" },
  ],
};

describe("catalogo stazioni locale", () => {
  it("calcola il checksum SHA-256 dalle stazioni normalizzate invece di scriverlo a mano", () => {
    const catalog = buildLocalStationCatalog(source);
    expect(catalog).toMatchObject({ schemaVersion: 1, sourceVersion: localStationCatalogSourceVersion, stationCount: 2 });
    expect(catalog.stations).toEqual([
      { id: "S05700", name: "Abano", code: "S05700", aliases: ["ABANO TERME", "Abano Terme"], latitude: 45.355322, longitude: 11.811495 },
      { id: "S01700", name: "Milano Centrale", code: "S01700", aliases: [], latitude: null, longitude: 9.204 },
    ]);
    expect(catalog.checksumSha256).toMatch(/^[a-f0-9]{64}$/);
    expect(catalog.checksumSha256).toBe(createHash("sha256").update(JSON.stringify(catalog.stations)).digest("hex"));
    expect(catalog.checksumSha256).toBe(computeLocalStationCatalogChecksum(catalog.stations));
  });

  it("è una funzione dei dati: stesso input stesso checksum, dato diverso checksum diverso", () => {
    const first = buildLocalStationCatalog(source);
    expect(buildLocalStationCatalog(structuredClone(source)).checksumSha256).toBe(first.checksumSha256);
    const renamed = structuredClone(source);
    renamed.stations[0]!.name = "Abano Terme";
    expect(buildLocalStationCatalog(renamed).checksumSha256).not.toBe(first.checksumSha256);
    expect(first.checksumSha256).not.toBe("58b441886c5fe8b731f69000c010c860b28424158fcab4ef1124434874b5712a");
  });

  it("rifiuta un catalogo sorgente non valido", () => {
    expect(() => buildLocalStationCatalog({ schemaVersion: 2, stations: [] })).toThrow("Catalogo stazioni sorgente non valido");
    expect(() => buildLocalStationCatalog(null)).toThrow("Catalogo stazioni sorgente non valido");
  });
});
