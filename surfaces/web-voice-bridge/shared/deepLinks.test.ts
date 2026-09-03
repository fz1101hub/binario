import { describe, expect, it } from "vitest";
import { getRouteDetailPath, parseBinarioInternalPath } from "./deepLinks";

describe("deep link Binario", () => {
  it("costruisce e interpreta soltanto URL interne consentite", () => {
    expect(getRouteDetailPath(7)).toBe("/viaggi/7");
    expect(parseBinarioInternalPath("/viaggi/7")).toEqual({ kind: "route", routeId: 7 });
    expect(parseBinarioInternalPath("/stazioni?name=Roma%20Termini&code=S00001")).toEqual({ kind: "station", name: "Roma Termini", code: "S00001" });
  });

  it("rifiuta schemi, parametri e identificativi non consentiti", () => {
    expect(() => getRouteDetailPath(0)).toThrow("Identificativo tratta non valido");
    expect(parseBinarioInternalPath("https://example.test/viaggi/7")).toBeNull();
    expect(parseBinarioInternalPath("/viaggi/7?token=segreto")).toBeNull();
    expect(parseBinarioInternalPath("/stazioni?name=Roma&code=S1&token=segreto")).toBeNull();
  });
});
