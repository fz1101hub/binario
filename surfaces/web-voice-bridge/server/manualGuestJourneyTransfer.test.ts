import { describe, expect, it } from "vitest";
import { buildManualGuestTransferArchive, parseManualGuestTransferArchive, previewManualGuestTransfer } from "../shared/manualGuestJourneyTransfer";
import { defaultJourneyExtension } from "../shared/localJourneyToolkit";

const route = { id: "route-1", origin: "Roma Termini", destination: "Firenze S. M. Novella", originStationCode: "8300000", destinationStationCode: "8300017", travelDate: "2026-10-01", travelTime: "08:30", ...defaultJourneyExtension("2026-09-01T00:00:00.000Z"), reminderMinutes: 20 as const, checklistTemplateIds: ["commuter"] as const, checklist: [{ id: "commuter-0", label: "Margine", completed: false }] };

describe("trasferimento manuale browser", () => {
  it("crea un archivio ferroviario minimizzato e compatibile", () => {
    const archive = buildManualGuestTransferArchive([route], 1);
    expect(archive.routes[0]).toMatchObject({ origin: "Roma Termini", plannedReminderMinutes: 20 });
    expect(archive.routes[0]?.checklist).toEqual(expect.arrayContaining([{ id: "time-margin", completed: false }]));
    expect(parseManualGuestTransferArchive(JSON.stringify(archive))).toEqual(archive);
  });
  it("rifiuta input non valido e presenta duplicati senza importarli", () => {
    expect(parseManualGuestTransferArchive("no")).toBeNull();
    expect(previewManualGuestTransfer([route], buildManualGuestTransferArchive([route]).routes)).toEqual([expect.objectContaining({ state: "already_present" })]);
  });
});
