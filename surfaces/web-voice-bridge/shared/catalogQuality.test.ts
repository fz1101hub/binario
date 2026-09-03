import { describe, expect, it } from "vitest";
import { validateCanonicalCatalog } from "./catalogQuality";

const station = { stationId: "1", name: "Roma Termini", code: "S00001", latitude: 41.9, longitude: 12.5, aliases: ["Termini"], confidence: "Alta", trenitaliaLocationId: null };

describe("qualità catalogo canonico", () => {
  it("produce revisione stabile e accetta record coerenti", () => {
    const report = validateCanonicalCatalog([station]);
    expect(report).toMatchObject({ schemaVersion: 1, recordCount: 1, uniqueCodes: 1, valid: true });
    expect(report.revision).toMatch(/^fnv1a-/);
  });
  it("segnala codici duplicati e coordinate fuori intervallo", () => {
    const report = validateCanonicalCatalog([station, { ...station, stationId: "2", latitude: 91 }]);
    expect(report.valid).toBe(false);
    expect(report.duplicateCodes).toEqual(["S00001"]);
    expect(report.invalidCoordinates).toEqual(["S00001"]);
  });
});
