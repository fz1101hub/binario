import { describe, expect, it } from "vitest";
import { decodeDictationAudio, DICTATION_MAX_BYTES, DICTATION_REQUEST_MAX_BYTES, isDictationBase64TooLarge, isDictationRequestTooLarge } from "./dictation";

describe("validazione audio della dettatura", () => {
  it("accetta un payload base64 canonico non vuoto", () => {
    expect(decodeDictationAudio(Buffer.from("voce").toString("base64")).toString()).toBe("voce");
  });

  it("rifiuta payload base64 vuoti, modificati o non canonici prima dello storage", () => {
    expect(() => decodeDictationAudio("")).toThrow(/formato valido/i);
    expect(() => decodeDictationAudio("not-base64!")).toThrow(/formato valido/i);
    expect(() => decodeDictationAudio(" dm9jZQ==")).toThrow(/formato valido/i);
  });

  it("rifiuta un payload base64 oltre la soglia prima della validazione costosa", () => {
    const canonicalLengthAtLimit = Math.ceil(DICTATION_MAX_BYTES / 3) * 4;
    expect(isDictationBase64TooLarge(canonicalLengthAtLimit)).toBe(false);
    expect(isDictationBase64TooLarge(canonicalLengthAtLimit + 4)).toBe(true);
  });

  it("rifiuta Content-Length audio oltre l’envelope dedicato e non penalizza header assenti o malformati", () => {
    expect(isDictationRequestTooLarge(String(DICTATION_REQUEST_MAX_BYTES))).toBe(false);
    expect(isDictationRequestTooLarge(String(DICTATION_REQUEST_MAX_BYTES + 1))).toBe(true);
    expect(isDictationRequestTooLarge(undefined)).toBe(false);
    expect(isDictationRequestTooLarge("22mb")).toBe(false);
  });
});
