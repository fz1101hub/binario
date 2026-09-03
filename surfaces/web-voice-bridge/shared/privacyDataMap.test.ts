import { describe, expect, it } from "vitest";
import { privacyDataMap } from "./privacyDataMap";

describe("mappa dati privacy", () => {
  it("distingue dati locali, area personale, companion, voce e assenza realtime senza promettere retention non attestata", () => {
    expect(privacyDataMap.map(item => item.id)).toEqual(["browser", "account", "companion", "voice", "realtime"]);
    expect(privacyDataMap.find(item => item.id === "voice")?.description).toContain("non è attestata");
    expect(privacyDataMap.find(item => item.id === "realtime")?.location).toBe("Nessuna fonte attiva");
  });
});
