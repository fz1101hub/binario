import { describe, expect, it } from "vitest";
import { parseLocalJourneyTemplates, prependLocalJourneyTemplate, type LocalJourneyTemplate } from "../shared/localJourneyTemplates";

const template: LocalJourneyTemplate = { schemaVersion: 1, id: "t-1", label: "Ufficio", origin: "Roma Termini", destination: "Milano Centrale", originStationCode: "RMTE", destinationStationCode: "MICE", travelTime: "08:15", reminderMinutes: 20, checklistTemplateIds: ["commuter"], createdAt: "2026-09-02T10:00:00.000Z" };

describe("modelli di viaggio locali", () => {
  it("normalizza modelli validi e rifiuta dati corrotti o percorsi non validi", () => {
    expect(parseLocalJourneyTemplates(JSON.stringify([template]))).toEqual([template]);
    expect(parseLocalJourneyTemplates("not-json")).toEqual([]);
    expect(parseLocalJourneyTemplates(JSON.stringify([{ ...template, origin: "Roma", destination: "Roma" }]))).toEqual([]);
  });

  it("deduplica semanticamente il modello senza superare il limite locale", () => {
    const newer = { ...template, id: "t-2", label: "Pendolare", createdAt: "2026-09-02T11:00:00.000Z" };
    expect(prependLocalJourneyTemplate([template], newer)).toEqual([newer]);
  });
});
