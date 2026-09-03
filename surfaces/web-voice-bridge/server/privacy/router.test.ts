import { describe, expect, it, vi } from "vitest";
import type { TrpcContext } from "../_core/context";

const dbMocks = vi.hoisted(() => ({
  deletePersonalBinarioData: vi.fn(),
  exportPersonalBinarioData: vi.fn(),
}));

vi.mock("../db", () => dbMocks);

import { createPrivacyRouter } from "./router";

function context(userId: number | null): TrpcContext {
  return {
    user: userId === null ? null : { id: userId, openId: `user-${userId}`, email: null, name: null, loginMethod: "manus", role: "user", createdAt: new Date(), updatedAt: new Date(), lastSignedIn: new Date() },
    req: { protocol: "https", headers: {} } as TrpcContext["req"],
    res: { clearCookie: vi.fn() } as unknown as TrpcContext["res"],
  };
}

describe("centro privacy Binario", () => {
  it("esporta dati minimizzati solo per l’utente autenticato", async () => {
    const exported = { schemaVersion: 1, routes: [], stationPreferences: [], companionDevices: [], alexaLinks: [], profile: null };
    dbMocks.exportPersonalBinarioData.mockResolvedValue(exported);
    const caller = createPrivacyRouter().createCaller(context(17));

    await expect(caller.exportData()).resolves.toEqual(exported);
    expect(dbMocks.exportPersonalBinarioData).toHaveBeenCalledWith(17);
  });

  it("cancella solo i dati Binario del profilo autenticato", async () => {
    dbMocks.deletePersonalBinarioData.mockResolvedValue(undefined);
    const caller = createPrivacyRouter().createCaller(context(17));

    await expect(caller.deleteData()).resolves.toEqual({ success: true });
    expect(dbMocks.deletePersonalBinarioData).toHaveBeenCalledWith(17);
  });

  it("nega export e cancellazione senza sessione", async () => {
    const caller = createPrivacyRouter().createCaller(context(null));
    await expect(caller.exportData()).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.deleteData()).rejects.toMatchObject({ code: "UNAUTHORIZED" });
  });
});
