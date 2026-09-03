import { describe, expect, it, vi } from "vitest";

const dbMocks = vi.hoisted(() => ({
  archivePersonalRoute: vi.fn(),
  clearRecentStations: vi.fn(),
  consumeCompanionPairingCode: vi.fn(),
  createCompanionDevice: vi.fn(),
  createCompanionPairingCode: vi.fn(),
  createPersonalRoute: vi.fn(),
  duplicatePersonalRoute: vi.fn(),
  getActiveCompanionDevice: vi.fn(),
  getAlexaLinkStatus: vi.fn(),
  getPersonalRoute: vi.fn(),
  getRemoteProfile: vi.fn(),
  importPersonalRoutes: vi.fn(),
  listCompanionDevices: vi.fn(),
  listPersonalRoutes: vi.fn(),
  listStationPreferences: vi.fn(),
  invertPersonalRoute: vi.fn(),
  recordStationOpened: vi.fn(),
  removePersonalRoute: vi.fn(),
  revokeAllCompanionDevices: vi.fn(),
  restorePersonalRoute: vi.fn(),
  revokeCompanionDevice: vi.fn(),
  revokeAllVoiceCredentialsForUser: vi.fn(),
  setCompanionDeviceSync: vi.fn(),
  setPersonalRoutePinned: vi.fn(),
  setStationFavorite: vi.fn(),
  touchCompanionDevice: vi.fn(),
  updatePersonalRoute: vi.fn(),
  upsertRemoteProfile: vi.fn(),
}));

const dictationMocks = vi.hoisted(() => ({
  storageGetSignedUrl: vi.fn(),
  storagePut: vi.fn(),
  transcribeAudio: vi.fn(),
}));

vi.mock("./db", () => dbMocks);
vi.mock("./storage", () => ({ storagePut: dictationMocks.storagePut, storageGetSignedUrl: dictationMocks.storageGetSignedUrl }));
vi.mock("./_core/voiceTranscription", () => ({ transcribeAudio: dictationMocks.transcribeAudio }));

import { appRouter } from "./routers";
import type { TrpcContext } from "./_core/context";

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

function anonymousContext(): TrpcContext {
  return { ...authenticatedContext(), user: null };
}

describe("procedure protette Binario", () => {
  it("nega l’elenco tratte senza sessione autenticata", async () => {
    const caller = appRouter.createCaller(anonymousContext());
    await expect(caller.routes.list()).rejects.toMatchObject({ code: "UNAUTHORIZED" });
  });

  it("salva una tratta solo per l’utente autenticato", async () => {
    dbMocks.createPersonalRoute.mockResolvedValue({ id: 41, userId: 7, origin: "Roma Termini", destination: "Tivoli", name: "Casa — lavoro", isPinned: true, travelDate: "2026-09-01", travelTime: "08:30" });
    const caller = appRouter.createCaller(authenticatedContext());
    await expect(caller.routes.create({ origin: "Roma Termini", destination: "Tivoli", name: "Casa — lavoro", isPinned: true, travelDate: "2026-09-01", travelTime: "08:30" })).resolves.toMatchObject({ id: 41, userId: 7, travelDate: "2026-09-01", travelTime: "08:30" });
    expect(dbMocks.createPersonalRoute).toHaveBeenCalledWith(7, expect.objectContaining({ name: "Casa — lavoro", travelDate: "2026-09-01", travelTime: "08:30" }));
  });

  it("inoltra i codici del catalogo solo quando una stazione è stata selezionata", async () => {
    dbMocks.createPersonalRoute.mockResolvedValue({ id: 42, userId: 7, origin: "Roma Termini", originStationCode: "S00001", destination: "Tivoli", destinationStationCode: "S00002", name: "Casa — lavoro", isPinned: false });
    const caller = appRouter.createCaller(authenticatedContext());

    await expect(caller.routes.create({
      origin: "Roma Termini",
      originStationCode: "S00001",
      destination: "Tivoli",
      destinationStationCode: "S00002",
      name: "Casa — lavoro",
    })).resolves.toMatchObject({ id: 42, userId: 7 });

    expect(dbMocks.createPersonalRoute).toHaveBeenLastCalledWith(7, expect.objectContaining({
      originStationCode: "S00001",
      destinationStationCode: "S00002",
    }));
  });

  it("elenca, fissa e rimuove tratte per il solo profilo autenticato", async () => {
    dbMocks.listPersonalRoutes.mockResolvedValue([{ id: 41, userId: 7, name: "Casa — lavoro", travelDate: "2026-09-01", travelTime: "08:30" }]);
    dbMocks.setPersonalRoutePinned.mockResolvedValue(undefined);
    dbMocks.removePersonalRoute.mockResolvedValue(undefined);
    const caller = appRouter.createCaller(authenticatedContext());
    await expect(caller.routes.list()).resolves.toEqual([{ id: 41, userId: 7, name: "Casa — lavoro", travelDate: "2026-09-01", travelTime: "08:30" }]);
    await expect(caller.routes.setPinned({ id: 41, isPinned: true })).resolves.toEqual({ success: true });
    await expect(caller.routes.remove({ id: 41 })).resolves.toEqual({ success: true });
    expect(dbMocks.listPersonalRoutes).toHaveBeenCalledWith(7);
    expect(dbMocks.setPersonalRoutePinned).toHaveBeenCalledWith(7, 41, true);
    expect(dbMocks.removePersonalRoute).toHaveBeenCalledWith(7, 41);
  });

  it("archivia e ripristina tratte soltanto per l’utente autenticato", async () => {
    dbMocks.listPersonalRoutes.mockResolvedValue([{ id: 41, userId: 7, name: "Casa — lavoro", archivedAt: new Date() }]);
    dbMocks.archivePersonalRoute.mockResolvedValue(true);
    dbMocks.restorePersonalRoute.mockResolvedValue(true);
    const caller = appRouter.createCaller(authenticatedContext());

    await expect(caller.routes.list({ includeArchived: true })).resolves.toHaveLength(1);
    await expect(caller.routes.archive({ id: 41 })).resolves.toEqual({ success: true });
    await expect(caller.routes.restore({ id: 41 })).resolves.toEqual({ success: true });

    expect(dbMocks.listPersonalRoutes).toHaveBeenLastCalledWith(7, { includeArchived: true });
    expect(dbMocks.archivePersonalRoute).toHaveBeenCalledWith(7, 41);
    expect(dbMocks.restorePersonalRoute).toHaveBeenCalledWith(7, 41);
  });

  it("legge e modifica il dettaglio tratta soltanto per il profilo autenticato", async () => {
    const route = { id: 41, userId: 7, origin: "Roma Termini", destination: "Tivoli", name: "Casa", isPinned: false, travelProfile: "commuter", plannedReminderMinutes: 20, personalNote: "Nota", accessibilityNote: null };
    dbMocks.getPersonalRoute.mockResolvedValue(route);
    dbMocks.updatePersonalRoute.mockResolvedValue(route);
    dbMocks.duplicatePersonalRoute.mockResolvedValue({ ...route, id: 42 });
    dbMocks.invertPersonalRoute.mockResolvedValue({ ...route, origin: "Tivoli", destination: "Roma Termini" });
    const caller = appRouter.createCaller(authenticatedContext());

    await expect(caller.routes.get({ id: 41 })).resolves.toEqual(route);
    await expect(caller.routes.update({ ...route, id: 41 })).resolves.toEqual(route);
    await expect(caller.routes.duplicate({ id: 41 })).resolves.toMatchObject({ id: 42, userId: 7 });
    await expect(caller.routes.invert({ id: 41 })).resolves.toMatchObject({ origin: "Tivoli", destination: "Roma Termini" });
    expect(dbMocks.getPersonalRoute).toHaveBeenCalledWith(7, 41);
    expect(dbMocks.updatePersonalRoute).toHaveBeenCalledWith(7, 41, expect.objectContaining({ travelProfile: "commuter", plannedReminderMinutes: 20, personalNote: "Nota" }));
    expect(dbMocks.duplicatePersonalRoute).toHaveBeenCalledWith(7, 41);
    expect(dbMocks.invertPersonalRoute).toHaveBeenCalledWith(7, 41);
  });

  it("importa al massimo cinquanta tratte portabili per il solo profilo autenticato", async () => {
    dbMocks.importPersonalRoutes.mockResolvedValue(1);
    const caller = appRouter.createCaller(authenticatedContext());
    const payload = { schemaVersion: 1 as const, routes: [{ origin: "Roma Termini", destination: "Tivoli", name: "Casa", travelProfile: "occasional" as const, plannedReminderMinutes: 15, personalNote: "Solo mia" }] };
    await expect(caller.routes.import(payload)).resolves.toEqual({ imported: 1 });
    expect(dbMocks.importPersonalRoutes).toHaveBeenCalledWith(7, payload.routes);
    await expect(caller.routes.import({ ...payload, routes: Array.from({ length: 51 }, () => payload.routes[0]) })).rejects.toMatchObject({ code: "BAD_REQUEST" });
  });

  it("gestisce preferiti e recenti solo per il profilo autenticato", async () => {
    dbMocks.listStationPreferences.mockResolvedValue([{ id: 1, userId: 7, stationName: "Roma Termini", stationCode: "8300000", isFavorite: true }]);
    dbMocks.recordStationOpened.mockResolvedValue(undefined);
    dbMocks.setStationFavorite.mockResolvedValue(undefined);
    dbMocks.clearRecentStations.mockResolvedValue(undefined);
    const caller = appRouter.createCaller(authenticatedContext());

    await expect(caller.stations.listPreferences()).resolves.toHaveLength(1);
    await expect(caller.stations.markOpened({ stationName: "Roma Termini", stationCode: "8300000" })).resolves.toEqual({ success: true });
    await expect(caller.stations.setFavorite({ stationName: "Roma Termini", stationCode: "8300000", isFavorite: true })).resolves.toEqual({ success: true });
    await expect(caller.stations.clearRecent()).resolves.toEqual({ success: true });
    expect(dbMocks.recordStationOpened).toHaveBeenCalledWith(7, expect.objectContaining({ stationCode: "8300000" }));
    expect(dbMocks.setStationFavorite).toHaveBeenCalledWith(7, expect.objectContaining({ isFavorite: true }));
    expect(dbMocks.clearRecentStations).toHaveBeenCalledWith(7);
  });

  it("propaga un errore dell’archivio senza attribuire la tratta ad altri utenti", async () => {
    dbMocks.createPersonalRoute.mockRejectedValueOnce(new Error("Archivio personale non disponibile"));
    const caller = appRouter.createCaller(authenticatedContext());
    await expect(caller.routes.create({ origin: "Roma Termini", destination: "Tivoli", name: "Casa — lavoro" })).rejects.toThrow("Archivio personale non disponibile");
  });

  it("nega le mutazioni tratte e la revoca vocale senza sessione", async () => {
    const caller = appRouter.createCaller(anonymousContext());
    await expect(caller.routes.create({ origin: "Roma Termini", destination: "Tivoli", name: "Casa — lavoro" })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.routes.setPinned({ id: 41, isPinned: true })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.routes.remove({ id: 41 })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.routes.archive({ id: 41 })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.routes.restore({ id: 41 })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.routes.get({ id: 41 })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.routes.update({ id: 41, origin: "Roma Termini", destination: "Tivoli", name: "Casa" })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.routes.duplicate({ id: 41 })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.routes.invert({ id: 41 })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.routes.import({ schemaVersion: 1, routes: [{ origin: "Roma Termini", destination: "Tivoli", name: "Casa" }] })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.stations.listPreferences()).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.stations.markOpened({ stationName: "Roma Termini", stationCode: "8300000" })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.stations.setFavorite({ stationName: "Roma Termini", stationCode: "8300000", isFavorite: true })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.stations.clearRecent()).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.voice.unlink()).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await expect(caller.companion.snapshot({ subject: { kind: "station", id: "8300000", label: "Roma Termini" } })).rejects.toMatchObject({ code: "UNAUTHORIZED" });
  });

  it("espone un solo snapshot companion fail-closed in assenza di fonte autorizzata", async () => {
    const caller = appRouter.createCaller(authenticatedContext());
    await expect(caller.companion.snapshot({ subject: { kind: "station", id: "8300000", label: "Roma Termini" } })).resolves.toMatchObject({
      schemaVersion: 1,
      subject: { kind: "station", id: "8300000", label: "Roma Termini" },
      provenance: { dataNature: "unavailable", confidence: "unavailable" },
    });
  });

  it("trascrive la dettatura dell’app senza account né registrazione nel profilo", async () => {
    dictationMocks.storagePut.mockResolvedValue({ key: "dictation/ephemeral/clip.webm", url: "/manus-storage/dictation/ephemeral/clip.webm" });
    dictationMocks.storageGetSignedUrl.mockResolvedValue("https://storage.example/dictation/ephemeral/clip.webm");
    dictationMocks.transcribeAudio.mockResolvedValue({ task: "transcribe", text: " Roma Termini ", language: "it", duration: 1.2, segments: [] });
    const caller = appRouter.createCaller(anonymousContext());

    await expect(caller.dictation.transcribe({ audioBase64: Buffer.from("voce").toString("base64"), mimeType: "audio/webm" })).resolves.toEqual({ text: "Roma Termini", language: "it", duration: 1.2 });
    expect(dictationMocks.storagePut).toHaveBeenCalledWith(expect.stringMatching(/^dictation\/ephemeral\//), expect.any(Buffer), "audio/webm");
    expect(dictationMocks.transcribeAudio).toHaveBeenCalledWith(expect.objectContaining({ language: "it" }));
  });

  it("azzera il buffer audio in memoria dopo la trascrizione", async () => {
    let uploadedAudio: Buffer | undefined;
    dictationMocks.storagePut.mockImplementation(async (_key: string, data: Buffer) => {
      uploadedAudio = data;
      return { key: "dictation/ephemeral/temporary.webm", url: "/manus-storage/dictation/ephemeral/temporary.webm" };
    });
    dictationMocks.storageGetSignedUrl.mockResolvedValue("https://storage.example/dictation/ephemeral/temporary.webm");
    dictationMocks.transcribeAudio.mockResolvedValue({ task: "transcribe", text: "Tivoli", language: "it", duration: 0.8, segments: [] });
    const caller = appRouter.createCaller(anonymousContext());

    await expect(caller.dictation.transcribe({ audioBase64: Buffer.from("voce privata").toString("base64"), mimeType: "audio/webm" })).resolves.toMatchObject({ text: "Tivoli" });

    expect(uploadedAudio).toBeDefined();
    expect(uploadedAudio?.every(byte => byte === 0)).toBe(true);
  });

  it("propaga gli errori delle mutazioni protette senza considerarle riuscite", async () => {
    dbMocks.setPersonalRoutePinned.mockRejectedValueOnce(new Error("Archivio personale non disponibile"));
    dbMocks.removePersonalRoute.mockRejectedValueOnce(new Error("Archivio personale non disponibile"));
    dbMocks.revokeAllVoiceCredentialsForUser.mockRejectedValueOnce(new Error("Archivio collegamenti vocali non disponibile"));
    const caller = appRouter.createCaller(authenticatedContext());
    await expect(caller.routes.setPinned({ id: 41, isPinned: true })).rejects.toThrow("Archivio personale non disponibile");
    await expect(caller.routes.remove({ id: 41 })).rejects.toThrow("Archivio personale non disponibile");
    await expect(caller.voice.unlink()).rejects.toThrow("Archivio collegamenti vocali non disponibile");
  });

  it("revoca il collegamento vocale soltanto per il profilo autenticato", async () => {
    dbMocks.revokeAllVoiceCredentialsForUser.mockResolvedValue(undefined);
    const caller = appRouter.createCaller(authenticatedContext());
    await expect(caller.voice.unlink()).resolves.toEqual({ success: true });
    expect(dbMocks.revokeAllVoiceCredentialsForUser).toHaveBeenCalledWith(7);
  });
});
