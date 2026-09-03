import { describe, expect, it } from "vitest";
import { getDictationMessage, normalizeDictationMimeType, normalizeDictationText } from "../shared/dictationPresentation";

describe("presentazione della dettatura", () => {
  it("espone un fallback comprensibile per browser e permessi non disponibili", () => {
    expect(getDictationMessage("unsupported")).toContain("continuare a digitare");
    expect(getDictationMessage("denied")).toContain("Microfono non autorizzato");
  });

  it("normalizza solo formati audio ammessi prima dell’invio", () => {
    expect(normalizeDictationMimeType("audio/webm;codecs=opus")).toBe("audio/webm");
    expect(normalizeDictationMimeType("audio/mp4")).toBe("audio/mp4");
    expect(normalizeDictationMimeType("video/webm")).toBeNull();
  });

  it("lascia un testo di dettatura rivedibile e privo di spazi superflui", () => {
    expect(normalizeDictationText("  Roma Termini  ")).toBe("Roma Termini");
  });
});
