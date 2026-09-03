import { describe, expect, it } from "vitest";
import { normalizePersonalRouteDraft, personalRouteSyncFingerprint } from "./contract";

describe("contratto tratte personali", () => {
  it("normalizza etichette senza memorizzare spazi superflui", () => {
    expect(normalizePersonalRouteDraft({ origin: "  Roma   Termini ", originStationCode: " s00001 ", destination: "Tivoli", destinationStationCode: "s00002", name: " Casa  lavoro ", isPinned: true })).toEqual({
      origin: "Roma Termini", originStationCode: "S00001", destination: "Tivoli", destinationStationCode: "S00002", name: "Casa lavoro", isPinned: true, travelDate: null, travelTime: null, travelProfile: "occasional", plannedReminderMinutes: null, personalNote: null, accessibilityNote: null, personalTagsJson: "[]", preDepartureChecklistJson: "[]",
    });
  });

  it("rifiuta una tratta senza origine-destinazione effettiva", () => {
    expect(() => normalizePersonalRouteDraft({ origin: "Roma", destination: "  roma ", name: "Test" })).toThrow("Origine e destinazione devono essere diverse");
  });

  it("non inventa codici stazione quando l’utente non seleziona un risultato canonico", () => {
    expect(normalizePersonalRouteDraft({ origin: "Roma Termini", destination: "Tivoli", name: "Casa" })).toMatchObject({
      originStationCode: null,
      destinationStationCode: null,
    });
    expect(() => normalizePersonalRouteDraft({ origin: "Roma Termini", originStationCode: "codice con spazi", destination: "Tivoli", name: "Casa" })).toThrow("Codice stazione di partenza non valido");
  });

  it("accetta solo data e ora di pianificazione valide e lascia assenti i valori non scelti", () => {
    expect(normalizePersonalRouteDraft({ origin: "Roma Termini", destination: "Tivoli", name: "Casa", travelDate: "2026-09-01", travelTime: "08:30" })).toMatchObject({ travelDate: "2026-09-01", travelTime: "08:30" });
    expect(() => normalizePersonalRouteDraft({ origin: "Roma Termini", destination: "Tivoli", name: "Casa", travelDate: "2026-02-30" })).toThrow("Data di riferimento non valida");
    expect(() => normalizePersonalRouteDraft({ origin: "Roma Termini", destination: "Tivoli", name: "Casa", travelTime: "25:00" })).toThrow("Ora di riferimento non valida");
  });

  it("normalizza preferenze personali senza inferire dati di viaggio", () => {
    expect(normalizePersonalRouteDraft({ origin: "Roma Termini", destination: "Tivoli", name: "Casa", travelProfile: "accessibility", plannedReminderMinutes: 20, personalNote: "  Incontro lato nord\r\ncon calma ", accessibilityNote: " Ascensore da verificare in autonomia " })).toMatchObject({
      travelProfile: "accessibility",
      plannedReminderMinutes: 20,
      personalNote: "Incontro lato nord\ncon calma",
      accessibilityNote: "Ascensore da verificare in autonomia",
    });
    expect(() => normalizePersonalRouteDraft({ origin: "Roma Termini", destination: "Tivoli", name: "Casa", plannedReminderMinutes: 1_441 })).toThrow("Anticipo promemoria non valido");
    expect(() => normalizePersonalRouteDraft({ origin: "Roma Termini", destination: "Tivoli", name: "Casa", accessibilityNote: "x".repeat(501) })).toThrow("Nota accessibilità troppo lunga");
  });

  it("normalizza, deduplica e limita i tag personali", () => {
    expect(normalizePersonalRouteDraft({ origin: "Roma Termini", destination: "Tivoli", name: "Casa", personalTags: [" Lavoro ", "lavoro", "Mattina"] }).personalTagsJson).toBe('["Lavoro","Mattina"]');
    expect(() => normalizePersonalRouteDraft({ origin: "Roma Termini", destination: "Tivoli", name: "Casa", personalTags: ["uno", "due", "tre", "quattro", "cinque", "sei"] })).toThrow("Tag personali non validi");
  });

  it("normalizza checklist pre-partenza e rifiuta identificativi duplicati", () => {
    expect(normalizePersonalRouteDraft({ origin: "Roma Termini", destination: "Tivoli", name: "Casa", preDepartureChecklist: [{ id: "documenti", label: "  Porta i documenti ", done: false }] }).preDepartureChecklistJson).toBe('[{"id":"documenti","label":"Porta i documenti","done":false}]');
    expect(() => normalizePersonalRouteDraft({ origin: "Roma Termini", destination: "Tivoli", name: "Casa", preDepartureChecklist: [{ id: "uno", label: "Primo", done: false }, { id: "uno", label: "Secondo", done: false }] })).toThrow("Checklist personale non valida");
  });

  it("genera una fingerprint idempotente per sincronizzare soltanto la stessa tratta programmata", () => {
    const original = { origin: " Roma Termini ", originStationCode: "rmt", destination: "Milano Centrale", destinationStationCode: "mic", travelDate: "2026-10-02", travelTime: "08:15" };
    expect(personalRouteSyncFingerprint(original)).toBe("RMT|MIC|2026-10-02|08:15");
    expect(personalRouteSyncFingerprint({ ...original, destinationStationCode: "" })).toBe("RMT|milano centrale|2026-10-02|08:15");
  });
});
