import { describe, expect, it } from "vitest";
import { decideRemoteProfileWrite, isIdempotentReplay, normalizeRemoteProfileDraft, REMOTE_PROFILE_SCHEMA_VERSION, toRemoteProfileDto } from "./contract";

const validDraft = {
  schemaVersion: REMOTE_PROFILE_SCHEMA_VERSION,
  activeRouteId: " roma-torino ",
  direction: "OUTBOUND" as const,
  monitoredTrainNumbers: [" 9512 ", "9512", "RV-4132"],
  wearSnapshotSharingEnabled: true,
  voiceProfileSyncRequested: false,
  appleWatchSetupRequested: true,
  expectedRevision: 0,
  clientMutationId: "2b899092-512e-4a73-8c18-5e623d978c71",
};

describe("contratto profilo remoto", () => {
  it("normalizza identificativi e rimuove monitoraggi duplicati", () => {
    expect(normalizeRemoteProfileDraft(validDraft)).toMatchObject({
      activeRouteId: "roma-torino",
      monitoredTrainNumbers: ["9512", "RV-4132"],
      expectedRevision: 0,
    });
  });

  it("rifiuta versioni non supportate e numeri treno non validi", () => {
    expect(() => normalizeRemoteProfileDraft({ ...validDraft, schemaVersion: 2 })).toThrow("Versione profilo non supportata");
    expect(() => normalizeRemoteProfileDraft({ ...validDraft, monitoredTrainNumbers: ["95@12"] })).toThrow("Numero treno non valido");
  });

  it("riconosce una replica idempotente della stessa mutazione", () => {
    expect(isIdempotentReplay(validDraft.clientMutationId, validDraft.clientMutationId)).toBe(true);
    expect(isIdempotentReplay(validDraft.clientMutationId, "ad1f65da-2a40-4edf-b6f8-9f69e1d84792")).toBe(false);
    expect(decideRemoteProfileWrite({ revision: 4, lastMutationId: validDraft.clientMutationId }, validDraft)).toEqual({ idempotent: true, nextRevision: 4 });
    expect(decideRemoteProfileWrite({ revision: 4, lastMutationId: "ad1f65da-2a40-4edf-b6f8-9f69e1d84792" }, { ...validDraft, expectedRevision: 4 })).toEqual({ idempotent: false, nextRevision: 5 });
  });

  it("mappa il record di archivio in un DTO senza JSON interno né identificativo mutazione", () => {
    const dto = toRemoteProfileDto({
      ...validDraft,
      monitoredTrainNumbersJson: JSON.stringify(["9512"]),
      revision: 4,
      lastMutationId: validDraft.clientMutationId,
      updatedAt: new Date("2026-08-13T19:00:00Z"),
    });
    expect(dto).toMatchObject({ monitoredTrainNumbers: ["9512"], revision: 4 });
    expect(dto).not.toHaveProperty("monitoredTrainNumbersJson");
    expect(dto).not.toHaveProperty("lastMutationId");
  });
});
