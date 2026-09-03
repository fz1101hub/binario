import { describe, expect, it, vi } from "vitest";

const dbMocks = vi.hoisted(() => ({
  consumeCompanionPairingCode: vi.fn(),
  createCompanionDevice: vi.fn(),
  createCompanionPairingCode: vi.fn(),
  getActiveCompanionDevice: vi.fn(),
  getRemoteProfile: vi.fn(),
  importPersonalRoutes: vi.fn(),
  listCompanionDevices: vi.fn(),
  listPersonalRoutes: vi.fn(),
  revokeAllCompanionDevices: vi.fn(),
  revokeCompanionDevice: vi.fn(),
  setCompanionDeviceSync: vi.fn(),
  touchCompanionDevice: vi.fn(),
  upsertRemoteProfile: vi.fn(),
}));

vi.mock("./db", () => dbMocks);

import { appRouter } from "./routers";
import type { TrpcContext } from "./_core/context";
import { RemoteProfileConflictError } from "./profile/contract";

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

const profileInput = {
  schemaVersion: 1 as const,
  activeRouteId: "roma-torino",
  direction: "OUTBOUND" as const,
  monitoredTrainNumbers: ["9512"],
  wearSnapshotSharingEnabled: true,
  voiceProfileSyncRequested: true,
  appleWatchSetupRequested: false,
  expectedRevision: 2,
  clientMutationId: "2b899092-512e-4a73-8c18-5e623d978c71",
};

describe("profilo remoto Binario", () => {
  it("nega lettura e salvataggio senza sessione", async () => {
    const caller = appRouter.createCaller({ ...authenticatedContext(), user: null });
    await expect(caller.profile.get()).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.profile.upsert(profileInput)).rejects.toMatchObject({ code: "UNAUTHORIZED" });
  });

  it("restituisce e salva solo il profilo dell’utente autenticato", async () => {
    dbMocks.getRemoteProfile.mockResolvedValue({ activeRouteId: "roma-torino", monitoredTrainNumbers: ["9512"], revision: 2 });
    dbMocks.upsertRemoteProfile.mockResolvedValue({ profile: { activeRouteId: "roma-torino", monitoredTrainNumbers: ["9512"], revision: 3 }, idempotent: false });
    const caller = appRouter.createCaller(authenticatedContext());

    await expect(caller.profile.get()).resolves.toMatchObject({ activeRouteId: "roma-torino", monitoredTrainNumbers: ["9512"], revision: 2 });
    await expect(caller.profile.upsert(profileInput)).resolves.toMatchObject({ idempotent: false, profile: { revision: 3 } });
    expect(dbMocks.getRemoteProfile).toHaveBeenCalledWith(7);
    expect(dbMocks.upsertRemoteProfile).toHaveBeenCalledWith(7, profileInput);
  });

  it("valida UUID e revisione prima di contattare l’archivio", async () => {
    const caller = appRouter.createCaller(authenticatedContext());
    await expect(caller.profile.upsert({ ...profileInput, clientMutationId: "non-uuid" })).rejects.toMatchObject({ code: "BAD_REQUEST" });
    await expect(caller.profile.upsert({ ...profileInput, expectedRevision: -1 })).rejects.toMatchObject({ code: "BAD_REQUEST" });
  });

  it("espone un conflitto invece di sovrascrivere una revisione più recente", async () => {
    dbMocks.upsertRemoteProfile.mockRejectedValueOnce(new RemoteProfileConflictError());
    const caller = appRouter.createCaller(authenticatedContext());
    await expect(caller.profile.upsert(profileInput)).rejects.toMatchObject({ code: "CONFLICT" });
  });

  it("restituisce una replica idempotente senza esporre i campi di archivio", async () => {
    dbMocks.upsertRemoteProfile.mockResolvedValue({
      profile: { activeRouteId: "roma-torino", monitoredTrainNumbers: ["9512"], revision: 3 },
      idempotent: true,
    });
    const caller = appRouter.createCaller(authenticatedContext());
    const result = await caller.profile.upsert(profileInput);
    expect(result.idempotent).toBe(true);
    expect(result.profile).toMatchObject({ monitoredTrainNumbers: ["9512"], revision: 3 });
    expect(result.profile).not.toHaveProperty("monitoredTrainNumbersJson");
    expect(result.profile).not.toHaveProperty("lastMutationId");
  });
});
