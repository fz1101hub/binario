import { describe, expect, it } from "vitest";
import { defaultJourneyExtension } from "./localJourneyToolkit";
import { parseLocalGuestRoutes, prependLocalGuestRoute, type LocalGuestRoute } from "./localGuestRoutes";

const route = (id: string): LocalGuestRoute => ({ id, origin: "Roma Termini", destination: "Firenze S. M. Novella", originStationCode: "S00001", destinationStationCode: "S00002", travelDate: "2026-09-02", travelTime: "09:00", ...defaultJourneyExtension("2026-09-01T00:00:00.000Z") });

describe("tratte ospite locali", () => {
  it("accetta solo il formato minimizzato e rifiuta dati corrotti", () => {
    expect(parseLocalGuestRoutes(JSON.stringify([route("a"), { id: "bad", origin: "Roma" }, null]))).toEqual([route("a")]);
    expect(parseLocalGuestRoutes("non json")).toEqual([]);
  });

  it("arricchisce le tratte del browser precedenti senza perdere il percorso", () => {
    const legacy = { id: "legacy", origin: "Roma", destination: "Tivoli", originStationCode: null, destinationStationCode: null, travelDate: "2026-09-03", travelTime: "08:10" };
    const parsed = parseLocalGuestRoutes(JSON.stringify([legacy]));
    expect(parsed[0]).toMatchObject({ ...legacy, archivedAt: null, completedAt: null, completedOccurrenceDates: [], reminderMinutes: null, recurrence: null, checklist: [] });
  });

  it("mantiene al massimo quaranta tratte e sostituisce il duplicato semantico", () => {
    const forty = Array.from({ length: 40 }, (_, index) => ({ ...route(String(index)), origin: `Origine ${index}`, destination: `Destinazione ${index}` }));
    expect(prependLocalGuestRoute(forty, route("new"))).toHaveLength(40);
    expect(prependLocalGuestRoute([route("old")], route("new"))).toEqual([route("new")]);
  });
});
