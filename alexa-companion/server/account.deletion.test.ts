import { describe, expect, it, vi } from "vitest";
import { COOKIE_NAME } from "../shared/const";
import type { TrpcContext } from "./_core/context";

const dbMocks = vi.hoisted(() => ({
  deleteUserAccount: vi.fn(),
}));

vi.mock("./db", async importOriginal => ({
  ...(await importOriginal<typeof import("./db")>()),
  deleteUserAccount: dbMocks.deleteUserAccount,
}));

import { appRouter } from "./routers";

function authenticatedContext() {
  const clearCookie = vi.fn();
  const ctx: TrpcContext = {
    user: {
      id: 7,
      openId: "delete-me",
      email: "delete@example.com",
      name: "Utente",
      loginMethod: "manus",
      role: "user",
      createdAt: new Date(),
      updatedAt: new Date(),
      lastSignedIn: new Date(),
    },
    req: { protocol: "https", headers: {} } as TrpcContext["req"],
    res: { clearCookie } as unknown as TrpcContext["res"],
  };
  return { ctx, clearCookie };
}

describe("account.delete", () => {
  it("richiede la conferma esatta prima di cancellare", async () => {
    const { ctx } = authenticatedContext();

    await expect(
      appRouter.createCaller(ctx).account.delete({ confirmation: "NO" as "ELIMINA" })
    ).rejects.toMatchObject({ code: "BAD_REQUEST" });
    expect(dbMocks.deleteUserAccount).not.toHaveBeenCalled();
  });

  it("cancella l'account e invalida la sessione", async () => {
    dbMocks.deleteUserAccount.mockResolvedValueOnce(true);
    const { ctx, clearCookie } = authenticatedContext();

    await expect(
      appRouter.createCaller(ctx).account.delete({ confirmation: "ELIMINA" })
    ).resolves.toEqual({ success: true });

    expect(dbMocks.deleteUserAccount).toHaveBeenCalledWith(7);
    expect(clearCookie).toHaveBeenCalledWith(
      COOKIE_NAME,
      expect.objectContaining({
        secure: true,
        sameSite: "lax",
        httpOnly: true,
        path: "/",
      })
    );
  });
});
