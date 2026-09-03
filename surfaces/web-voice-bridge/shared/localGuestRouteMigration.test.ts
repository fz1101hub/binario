import { describe, expect, it } from "vitest";
import { prepareGuestRouteImport } from "./localGuestRouteMigration";

const local = [
  { id: "a", origin: "Roma Ostiense", originStationCode: "S1", destination: "Roma San Pietro", destinationStationCode: "S2", travelDate: "2026-09-03", travelTime: "08:15" },
  { id: "b", origin: "Roma Ostiense", originStationCode: "S1", destination: "Roma San Pietro", destinationStationCode: "S2", travelDate: "2026-09-03", travelTime: "08:15" },
  { id: "c", origin: "Roma Termini", originStationCode: null, destination: "Firenze S. M. Novella", destinationStationCode: null, travelDate: "2026-09-04", travelTime: "09:30" },
];

describe("migrazione esplicita tratte locali", () => {
  it("prepara solo le tratte non presenti e deduplica la stessa copia locale", () => {
    expect(prepareGuestRouteImport(local, [{ origin: "Roma Ostiense", originStationCode: "S1", destination: "Roma San Pietro", destinationStationCode: "S2", travelDate: "2026-09-03", travelTime: "08:15" }])).toEqual([{ origin: "Roma Termini", originStationCode: null, destination: "Firenze S. M. Novella", destinationStationCode: null, name: "Roma Termini → Firenze S. M. Novella", travelDate: "2026-09-04", travelTime: "09:30" }]);
  });
});
