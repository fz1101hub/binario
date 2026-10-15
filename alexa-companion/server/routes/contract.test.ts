import { describe, expect, it } from "vitest";
import { normalizePersonalRouteDraft } from "./contract";

describe("contratto tratte personali", () => {
  it("normalizza etichette senza memorizzare spazi superflui", () => {
    expect(normalizePersonalRouteDraft({ origin: "  Roma   Termini ", destination: "Tivoli", name: " Casa  lavoro ", isPinned: true })).toEqual({
      origin: "Roma Termini", destination: "Tivoli", name: "Casa lavoro", isPinned: true,
    });
  });

  it("rifiuta una tratta senza origine-destinazione effettiva", () => {
    expect(() => normalizePersonalRouteDraft({ origin: "Roma", destination: "  roma ", name: "Test" })).toThrow("Origine e destinazione devono essere diverse");
  });
});
