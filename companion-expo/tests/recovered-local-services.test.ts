import { beforeEach, describe, expect, it, vi } from "vitest";

const storage = new Map<string, string>();
vi.mock("@react-native-async-storage/async-storage", () => ({
  default: {
    getItem: vi.fn(async (key: string) => storage.get(key) ?? null),
    setItem: vi.fn(async (key: string, value: string) => { storage.set(key, value); }),
    removeItem: vi.fn(async (key: string) => { storage.delete(key); }),
  },
}));

import { createLocalScheduledSnapshot, createUnavailableSnapshot, evaluateSnapshot } from "@/lib/companion-snapshot";
import { parseLocalDictation } from "@/lib/local-dictation";
import { createLocalJourneyTemplate, loadLocalJourneyTemplates, saveLocalJourneyTemplate } from "@/lib/local-journey-templates";
import { loadLocalJourneyTimingPreferences, saveLocalJourneyTimingPreferences } from "@/lib/local-journey-timing";
import { parseManualRouteTransfer, previewManualRouteTransfer } from "@/lib/manual-route-transfer-contract";

describe("servizi locali recuperati", () => {
  beforeEach(() => storage.clear());

  it("sopprime una pianificazione locale scaduta e non converte mai indisponibilità in realtime", () => {
    const snapshot = createLocalScheduledSnapshot({ id: 4, name: "Roma → Napoli", travelDate: "2030-01-02", travelTime: "09:30" }, 1);
    expect(snapshot.provenance.dataNature).toBe("scheduled");
    expect(evaluateSnapshot(snapshot, new Date("2030-01-02T10:00:00").getTime()).action).toBe("suppress");
    expect(createUnavailableSnapshot({ kind: "route", id: "none", label: "Nessuna" }).provenance.dataNature).toBe("unavailable");
  });

  it("estrae soltanto una bozza dalla dettatura, sempre revisionabile", () => {
    expect(parseLocalDictation("Da Roma Ostiense a Roma San Pietro domani alle 9:35", new Date("2030-01-02T12:00:00"))).toEqual({ origin: "Roma Ostiense", destination: "Roma San Pietro", travelDate: "2030-01-03", travelTime: "09:35" });
    expect(parseLocalDictation("ritardo del treno", new Date("2030-01-02T12:00:00"))).toEqual({ origin: "", destination: "", travelDate: "", travelTime: "" });
  });

  it("salva modelli limitati al dispositivo e rifiuta stazioni non complete", async () => {
    const valid = createLocalJourneyTemplate({ id: "casa-lavoro", label: "Casa lavoro", origin: "Roma Ostiense", originStationCode: "S08406", destination: "Roma San Pietro", destinationStationCode: "S08409", travelTime: "08:15", plannedReminderMinutes: 15, checklist: [] });
    expect(valid).not.toBeNull();
    await saveLocalJourneyTemplate(valid!);
    await expect(loadLocalJourneyTemplates()).resolves.toHaveLength(1);
    expect(createLocalJourneyTemplate({ id: "x", label: "x", origin: "Roma", originStationCode: "", destination: "Milano", destinationStationCode: "S1", travelTime: "08:15", plannedReminderMinutes: 15, checklist: [] })).toBeNull();
  });

  it("mantiene il margine come preferenza locale e rifiuta valori non previsti", async () => {
    await expect(loadLocalJourneyTimingPreferences()).resolves.toEqual({ preferredReminderMinutes: 15 });
    await expect(saveLocalJourneyTimingPreferences(30)).resolves.toBe(true);
    await expect(loadLocalJourneyTimingPreferences()).resolves.toEqual({ preferredReminderMinutes: 30 });
    await expect(saveLocalJourneyTimingPreferences(999 as never)).resolves.toBe(false);
  });

  it("valida un archivio manuale e rende espliciti i duplicati nell’anteprima", () => {
    const archive = parseManualRouteTransfer(JSON.stringify({ schemaVersion: 1, createdAtUtc: "2030-01-01T00:00:00.000Z", routes: [{ origin: "Roma", originStationCode: "S1", destination: "Napoli", destinationStationCode: "S2", travelDate: "2030-01-02", travelTime: "09:30", plannedReminderMinutes: null, checklist: [] }] }));
    expect(archive).not.toBeNull();
    expect(previewManualRouteTransfer(archive!.routes, archive!.routes)[0]?.state).toBe("already_present");
    expect(parseManualRouteTransfer("not json")).toBeNull();
  });
});
