import { describe, expect, it } from "vitest";
import { prepareOptInRouteSyncPreview, selectedOptInRouteSyncDrafts } from "../shared/optInRouteSync";

const localRoute = { id: "local-1", origin: "Roma Termini", originStationCode: "RMT", destination: "Milano Centrale", destinationStationCode: "MIC", travelDate: "2026-10-02", travelTime: "08:15", createdAt: "2026-09-02T10:00:00.000Z", archivedAt: null, completedAt: null, reminderMinutes: null, recurrence: null, completedOccurrenceDates: [], checklist: [] } as const;

describe("sincronizzazione Binario opt-in", () => {
  it("mostra nuove tratte e non invia nulla nella preview", () => {
    const preview = prepareOptInRouteSyncPreview([localRoute], []);
    expect(preview).toMatchObject([{ localId: "local-1", state: "new", route: { name: "Roma Termini → Milano Centrale" } }]);
    expect(selectedOptInRouteSyncDrafts(preview, new Set())).toEqual([]);
  });

  it("distingue una copia già nell’account da un conflitto di codice", () => {
    const existing = { id: 4, origin: "Roma Termini", originStationCode: "RMT", destination: "Milano Centrale", destinationStationCode: "MIC", travelDate: "2026-10-02", travelTime: "08:15" };
    expect(prepareOptInRouteSyncPreview([localRoute], [existing])[0]).toMatchObject({ state: "already_in_account", conflictingAccountRouteId: 4 });
    const differingCodes = { ...existing, originStationCode: "ALT" };
    expect(prepareOptInRouteSyncPreview([localRoute], [differingCodes])[0]).toMatchObject({ state: "conflict", conflictingAccountRouteId: 4 });
  });

  it("prepara all’invio soltanto le nuove tratte selezionate", () => {
    const other = { ...localRoute, id: "local-2", destination: "Bologna Centrale", destinationStationCode: "BOC" };
    const preview = prepareOptInRouteSyncPreview([localRoute, other], []);
    expect(selectedOptInRouteSyncDrafts(preview, new Set(["local-2"]))).toEqual([expect.objectContaining({ destination: "Bologna Centrale" })]);
  });
});
