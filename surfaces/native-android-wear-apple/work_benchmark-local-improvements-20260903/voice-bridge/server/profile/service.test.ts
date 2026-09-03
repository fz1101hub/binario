import { describe, expect, it } from "vitest";
import { type RemoteProfileDraft, type RemoteProfileStoredValues, type StoredRemoteProfile } from "./contract";
import { synchronizeRemoteProfile, type RemoteProfileStore } from "./service";

const input: RemoteProfileDraft = {
  schemaVersion: 1,
  activeRouteId: "roma-torino",
  direction: "OUTBOUND",
  monitoredTrainNumbers: ["9512"],
  wearSnapshotSharingEnabled: true,
  voiceProfileSyncRequested: true,
  appleWatchSetupRequested: false,
  expectedRevision: 0,
  clientMutationId: "2b899092-512e-4a73-8c18-5e623d978c71",
};

function memoryStore(): RemoteProfileStore {
  let profile: StoredRemoteProfile | null = null;
  return {
    load: async () => profile,
    insert: async (_userId: number, revision: number, values: RemoteProfileStoredValues) => {
      profile = { ...values, revision, updatedAt: new Date("2026-08-13T19:00:00Z") };
    },
    compareAndSet: async (_userId: number, expectedRevision: number, nextRevision: number, values: RemoteProfileStoredValues) => {
      if (!profile || profile.revision !== expectedRevision) return false;
      profile = { ...values, revision: nextRevision, updatedAt: new Date("2026-08-13T19:00:00Z") };
      return true;
    },
  };
}

describe("servizio di sincronizzazione profilo remoto", () => {
  it("non incrementa la revisione quando la stessa mutazione viene ripetuta", async () => {
    const store = memoryStore();
    const first = await synchronizeRemoteProfile(store, 7, input);
    const replay = await synchronizeRemoteProfile(store, 7, input);

    expect(first).toMatchObject({ idempotent: false, profile: { revision: 1, monitoredTrainNumbers: ["9512"] } });
    expect(replay).toMatchObject({ idempotent: true, profile: { revision: 1, monitoredTrainNumbers: ["9512"] } });
    expect(replay.profile).not.toHaveProperty("monitoredTrainNumbersJson");
    expect(replay.profile).not.toHaveProperty("lastMutationId");
  });
});
