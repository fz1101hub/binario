import { describe, expect, it } from "vitest";
import { LIVE_DATA_UNAVAILABLE_SPEECH } from "./config";
import { liveDataUnavailableSpeech } from "./speech";

describe("risposte vocali Binario senza feed live", () => {
  it("usa esattamente la frase obbligatoria per assenza dato live", () => {
    expect(liveDataUnavailableSpeech()).toBe("dati non disponibili in tempo reale");
    expect(LIVE_DATA_UNAVAILABLE_SPEECH).toBe("dati non disponibili in tempo reale");
  });

  it("può contestualizzare la frase senza alterarnee il testo", () => {
    expect(liveDataUnavailableSpeech("Per il treno 1234")).toContain("dati non disponibili in tempo reale");
  });
});
