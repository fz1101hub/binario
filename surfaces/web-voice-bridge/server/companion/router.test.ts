import { describe, expect, it, vi } from "vitest";
import { createCompanionRouter } from "./router";

const subject = { kind: "station" as const, id: "S100", label: "Roma Termini" };
const user = { id: 7, openId: "user-7", role: "user" as const, name: null, email: null, loginMethod: null, createdAt: new Date(), updatedAt: new Date(), lastSignedIn: new Date() };

function context(authorization?: string, authenticated = false) {
  return { user: authenticated ? user : null, req: { headers: { authorization } }, res: {} } as never;
}

function repository() {
  return {
    createPairingCode: vi.fn(),
    consumePairingCode: vi.fn(),
    createDevice: vi.fn(),
    getActiveDevice: vi.fn(),
    touchDevice: vi.fn(),
    listDevices: vi.fn(),
    listPersonalRoutes: vi.fn(),
    importPersonalRoutes: vi.fn(),
    setDeviceSync: vi.fn(),
    revokeAllDevices: vi.fn(),
    revokeDevice: vi.fn(),
  } as never;
}

describe("pairing e sincronizzazione del companion", () => {
  it("richiede una sessione web prima di emettere un codice monouso", async () => {
    const caller = createCompanionRouter(repository()).createCaller(context(undefined, false));
    await expect(caller.issuePairingCode({ deviceName: "Telefono", platform: "android" })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
  });

  it("emette un codice breve a scadenza e ne persiste soltanto il digest", async () => {
    const repo = repository();
    const caller = createCompanionRouter(repo).createCaller(context(undefined, true));
    const result = await caller.issuePairingCode({ deviceName: "Telefono", platform: "android" });
    expect(result.code).toMatch(/^[A-Za-z0-9_-]{40,128}$/);
    expect(result.expiresAtUtcMs).toBeGreaterThan(Date.now());
    expect(repo.createPairingCode).toHaveBeenCalledWith(expect.objectContaining({ userId: 7, codeHash: expect.not.stringMatching(result.code) }));
  });

  it("rifiuta snapshot senza bearer e non consulta alcun provider alternativo", async () => {
    const repo = repository();
    const caller = createCompanionRouter(repo).createCaller(context());
    await expect(caller.deviceSnapshot({ subject })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    expect(repo.getActiveDevice).not.toHaveBeenCalled();
  });

  it("restituisce lo stesso fallback fail-closed solo a un dispositivo attivo", async () => {
    const repo = repository();
    repo.getActiveDevice.mockResolvedValue({ id: 9, userId: 7, syncEnabled: false });
    const caller = createCompanionRouter(repo).createCaller(context(`Bearer ${"a".repeat(43)}`));
    const result = await caller.deviceSnapshot({ subject });
    expect(result.provenance.dataNature).toBe("unavailable");
    expect(repo.touchDevice).toHaveBeenCalledWith(9);
  });

  it("proietta soltanto tratte pianificate e campi minimizzati dopo consenso", async () => {
    const repo = repository();
    repo.getActiveDevice.mockResolvedValue({ id: 9, userId: 7, syncEnabled: true });
    repo.listPersonalRoutes.mockResolvedValue([
      { id: 10, name: "Casa", origin: "Roma", originStationCode: "ROM", destination: "Tivoli", destinationStationCode: "TIV", travelDate: "2026-09-03", travelTime: "08:30", plannedReminderMinutes: 20, personalNote: "Privata", accessibilityNote: "Privata" },
      { id: 11, name: "Senza data", origin: "Roma", destination: "Tivoli", travelDate: null, travelTime: null, plannedReminderMinutes: 20 },
    ]);
    const caller = createCompanionRouter(repo).createCaller(context(`Bearer ${"a".repeat(43)}`));
    await expect(caller.devicePlannedRoutes()).resolves.toEqual([{ id: 10, name: "Casa", origin: "Roma", originStationCode: "ROM", destination: "Tivoli", destinationStationCode: "TIV", travelDate: "2026-09-03", travelTime: "08:30", plannedReminderMinutes: 20 }]);
    expect(repo.listPersonalRoutes).toHaveBeenCalledWith(7);
  });

  it("nega pull prima del consenso e permette di attivarlo solo al device bearer", async () => {
    const repo = repository();
    repo.getActiveDevice.mockResolvedValue({ id: 9, userId: 7, syncEnabled: false });
    repo.setDeviceSync.mockResolvedValue(true);
    const caller = createCompanionRouter(repo).createCaller(context(`Bearer ${"a".repeat(43)}`));
    await expect(caller.devicePlannedRoutes()).rejects.toMatchObject({ code: "FORBIDDEN" });
    await expect(caller.deviceSyncStatus()).resolves.toEqual({ linked: true, syncEnabled: false });
    await expect(caller.setDeviceSync({ enabled: true })).resolves.toEqual({ syncEnabled: true });
    expect(repo.setDeviceSync).toHaveBeenCalledWith(7, 9, true);
  });

  it("importa tratte soltanto quando syncEnabled è attivo", async () => {
    const repo = repository();
    repo.getActiveDevice.mockResolvedValue({ id: 9, userId: 7, syncEnabled: true });
    repo.importPersonalRoutes.mockResolvedValue(1);
    const caller = createCompanionRouter(repo).createCaller(context(`Bearer ${"a".repeat(43)}`));
    const input = { schemaVersion: 1 as const, routes: [{ origin: "Roma", originStationCode: "ROM", destination: "Milano", destinationStationCode: "MIL", name: "Roma → Milano", travelDate: "2026-10-02", travelTime: "08:15", plannedReminderMinutes: 15 }] };
    await expect(caller.deviceImportPlannedRoutes(input)).resolves.toEqual({ imported: 1 });
    expect(repo.importPersonalRoutes).toHaveBeenCalledWith(7, input.routes);
  });

  it("permette al titolare di bloccare la sola sincronizzazione di un device senza revocarlo", async () => {
    const repo = repository();
    repo.setDeviceSync.mockResolvedValue(true);
    const caller = createCompanionRouter(repo).createCaller(context(undefined, true));
    await expect(caller.setManagedDeviceSync({ id: 9, enabled: false })).resolves.toEqual({ syncEnabled: false });
    expect(repo.setDeviceSync).toHaveBeenCalledWith(7, 9, false);
  });

  it("non modifica dispositivi revocati o appartenenti a un altro account", async () => {
    const repo = repository();
    repo.setDeviceSync.mockResolvedValue(false);
    const caller = createCompanionRouter(repo).createCaller(context(undefined, true));
    await expect(caller.setManagedDeviceSync({ id: 9, enabled: true })).rejects.toMatchObject({ code: "NOT_FOUND" });
  });

  it("non emette token quando il codice è scaduto, consumato o sconosciuto", async () => {
    const repo = repository();
    repo.consumePairingCode.mockResolvedValue(null);
    const caller = createCompanionRouter(repo).createCaller(context());
    await expect(caller.redeemPairingCode({ code: "a".repeat(43) })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    expect(repo.createDevice).not.toHaveBeenCalled();
  });

  it("elenca per il solo utente autenticato metadati privi di token e consente la revoca", async () => {
    const repo = repository();
    repo.listDevices.mockResolvedValue([{ id: 9, deviceName: "Telefono", platform: "android", tokenPrefix: "abcdefghij", expiresAt: new Date(), syncEnabled: true, revokedAt: null, lastUsedAt: null, createdAt: new Date() }]);
    repo.revokeDevice.mockResolvedValue(true);
    const caller = createCompanionRouter(repo).createCaller(context(undefined, true));
    const devices = await caller.listDevices();
    expect(devices[0]).toMatchObject({ id: 9, deviceName: "Telefono", platform: "android", syncEnabled: true });
    expect(devices[0]).not.toHaveProperty("tokenHash");
    await expect(caller.revokeDevice({ id: 9 })).resolves.toEqual({ revoked: true });
    expect(repo.revokeDevice).toHaveBeenCalledWith(7, 9);
  });

  it("consente al device bearer di revocare soltanto se stesso", async () => {
    const repo = repository();
    repo.getActiveDevice.mockResolvedValue({ id: 9, userId: 7, syncEnabled: true });
    repo.revokeDevice.mockResolvedValue(true);
    const caller = createCompanionRouter(repo).createCaller(context(`Bearer ${"a".repeat(43)}`));
    await expect(caller.revokeCurrentDevice()).resolves.toEqual({ revoked: true });
    expect(repo.revokeDevice).toHaveBeenCalledWith(7, 9);
  });
});
