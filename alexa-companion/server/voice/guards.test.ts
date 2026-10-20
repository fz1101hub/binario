import { describe, expect, it } from "vitest";
import { needsAccountLink } from "./guards";

describe("guardia di personalizzazione Alexa", () => {
  it("blocca tutte le richieste personali senza token di account linking", () => {
    expect(needsAccountLink(undefined)).toBe(true);
    expect(needsAccountLink("  ")).toBe(true);
  });

  it("lascia proseguire solo una richiesta che presenta un token", () => {
    expect(needsAccountLink("token-verificabile-a-valle")).toBe(false);
  });
});
