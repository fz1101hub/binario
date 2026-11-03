import { describe, expect, it, vi } from "vitest";
import { router } from "../_core/trpc";
import type { TrpcContext } from "../_core/context";
import { toRemoteProfileDto, type RemoteProfileStoredValues, type StoredRemoteProfile } from "./contract";
import { createProfileRouter, type ProfileRepository } from "./router";
import { synchronizeRemoteProfile, type RemoteProfileStore } from "./service";

const input = {
  schemaVersion: 1 as const,
  activeRouteId: "roma-torino",
  direction: "OUTBOUND" as const,
  monitoredTrainNumbers: ["9512"],
  expectedRevision: 0,
  clientMutationId: "2b899092-512e-4a73-8c18-5e623d978c71",
};

function authenticatedContext(): TrpcContext {
  return {
    user: {
      id: 7, openId: "binario-test-user", email: "test@example.com", name: "Test", loginMethod: "manus", role: "user",
      createdAt: new Date(), updatedAt: new Date(), lastSignedIn: new Date(),
    },
    req: { protocol: "https", headers: {} } as TrpcContext["req"],
    res: { clearCookie: vi.fn() } as unknown as TrpcContext["res"],
  };
}

function realProfileRepository(): ProfileRepository {
  let record: StoredRemoteProfile | null = null;
  const store: RemoteProfileStore = {
    load: async () => record,
    insert: async (_userId: number, revision: number, values: RemoteProfileStoredValues) => {
      record = { ...values, revision, updatedAt: new Date("2026-08-13T19:00:00Z") };
    },
    compareAndSet: async (_userId: number, expectedRevision: number, nextRevision: number, values: RemoteProfileStoredValues) => {
      if (!record || record.revision !== expectedRevision) return false;
      record = { ...values, revision: nextRevision, updatedAt: new Date("2026-08-13T19:00:00Z") };
      return true;
    },
  };
  return {
    get: async () => record ? toRemoteProfileDto(record) : null,
    upsert: (userId, draft) => synchronizeRemoteProfile(store, userId, draft),
  };
}

describe("integrazione tRPC profilo remoto", () => {
  it("ripete la stessa mutazione senza nuova revisione e restituisce soltanto il DTO pubblico", async () => {
    const app = router({ profile: createProfileRouter(realProfileRepository()) });
    const caller = app.createCaller(authenticatedContext());

    const first = await caller.profile.upsert(input);
    const replay = await caller.profile.upsert(input);
    const fetched = await caller.profile.get();

    expect(first).toMatchObject({ idempotent: false, profile: { revision: 1, monitoredTrainNumbers: ["9512"] } });
    expect(replay).toMatchObject({ idempotent: true, profile: { revision: 1, monitoredTrainNumbers: ["9512"] } });
    expect(fetched).toMatchObject({ revision: 1, monitoredTrainNumbers: ["9512"] });
    for (const value of [first.profile, replay.profile, fetched]) {
      expect(value).not.toHaveProperty("monitoredTrainNumbersJson");
      expect(value).not.toHaveProperty("lastMutationId");
      expect(value).not.toHaveProperty("wearSnapshotSharingEnabled");
      expect(value).not.toHaveProperty("appleWatchSetupRequested");
    }
  });
});
