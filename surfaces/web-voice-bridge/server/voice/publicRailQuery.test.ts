import { describe, expect, it } from "vitest";
import { publicRouteQueryFallback, publicTrainQueryFallback } from "./publicRailQuery";

describe("risposte Alexa pubbliche e fail-closed", () => {
  it("risponde a una tratta nominata senza richiedere un account Binario", () => {
    expect(publicRouteQueryFallback("Roma Ostiense — Roma San Pietro")).toBe("Per la tratta Roma Ostiense — Roma San Pietro: dati non disponibili in tempo reale");
  });

  it("non costruisce soggetti malformati o troppo lunghi", () => {
    expect(publicTrainQueryFallback("  1234  ")).toBe("Per il treno 1234: dati non disponibili in tempo reale");
    expect(publicRouteQueryFallback("x".repeat(161))).toBe("dati non disponibili in tempo reale");
  });
});
