import { beforeEach, describe, expect, it, vi } from "vitest";

const dbMocks = vi.hoisted(() => ({
  createPersonalRoute: vi.fn(),
  getAlexaLinkStatus: vi.fn(),
  getRemoteProfile: vi.fn(),
  listPersonalRoutes: vi.fn(),
  removePersonalRoute: vi.fn(),
  revokeAllVoiceCredentialsForUser: vi.fn(),
  setPersonalRoutePinned: vi.fn(),
  upsertRemoteProfile: vi.fn(),
}));

vi.mock("./db", () => dbMocks);

const railData = vi.hoisted(() => ({ railStationIndex: vi.fn() }));
vi.mock("./punctuality/source", () => railData);

import { appRouter } from "./routers";
import { RouteLimitError, RouteValidationError } from "./routes/contract";
import { parseStationCatalog } from "./punctuality/stations";
import { catalogDocument } from "./punctuality/testFixtures";
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

beforeEach(() => {
  railData.railStationIndex.mockResolvedValue(parseStationCatalog(catalogDocument()));
});

describe("procedure protette Binario Alexa Companion", () => {
  it("rifiuta una tratta le cui stazioni il catalogo non riconosce, senza salvarla", async () => {
    dbMocks.createPersonalRoute.mockClear();
    const caller = appRouter.createCaller(authenticatedContext());
    for (const input of [
      { origin: "Roma", destination: "Tivoli", name: "Casa" },
      { origin: "Roma Termini", destination: "Milano", name: "Lavoro" },
    ])
      await expect(caller.routes.create(input)).rejects.toMatchObject({ code: "UNPROCESSABLE_CONTENT" });
    expect(dbMocks.createPersonalRoute).not.toHaveBeenCalled();
    // Names as the catalogue writes them, aliases, accents and case aside, are accepted.
    dbMocks.createPersonalRoute.mockResolvedValueOnce({ id: 43 });
    await expect(caller.routes.create({ origin: "rome termini", destination: "Firenze Santa Maria Novella", name: "Gita" })).resolves.toEqual({ id: 43 });
  });

  it("senza un catalogo leggibile salva comunque, invece di rifiutare ogni tratta", async () => {
    railData.railStationIndex.mockResolvedValueOnce(null);
    dbMocks.createPersonalRoute.mockResolvedValueOnce({ id: 44 });
    const caller = appRouter.createCaller(authenticatedContext());
    await expect(caller.routes.create({ origin: "Roma", destination: "Tivoli", name: "Casa" })).resolves.toEqual({ id: 44 });
  });

  it("nega l’elenco tratte senza sessione autenticata", async () => {
    const caller = appRouter.createCaller(anonymousContext());
    await expect(caller.routes.list()).rejects.toMatchObject({ code: "UNAUTHORIZED" });
  });

  it("salva una tratta solo per l’utente autenticato", async () => {
    dbMocks.createPersonalRoute.mockResolvedValue({ id: 41, userId: 7, origin: "Roma Termini", destination: "Tivoli", name: "Casa — lavoro", isPinned: true });
    const caller = appRouter.createCaller(authenticatedContext());
    await expect(caller.routes.create({ origin: "Roma Termini", destination: "Tivoli", name: "Casa — lavoro", isPinned: true })).resolves.toMatchObject({ id: 41, userId: 7 });
    expect(dbMocks.createPersonalRoute).toHaveBeenCalledWith(7, expect.objectContaining({ name: "Casa — lavoro" }));
  });

  it("elenca, fissa e rimuove tratte per il solo profilo autenticato", async () => {
    dbMocks.listPersonalRoutes.mockResolvedValue([{ id: 41, userId: 7, name: "Casa — lavoro" }]);
    dbMocks.setPersonalRoutePinned.mockResolvedValue(undefined);
    dbMocks.removePersonalRoute.mockResolvedValue(undefined);
    const caller = appRouter.createCaller(authenticatedContext());
    await expect(caller.routes.list()).resolves.toEqual([{ id: 41, userId: 7, name: "Casa — lavoro" }]);
    await expect(caller.routes.setPinned({ id: 41, isPinned: true })).resolves.toEqual({ success: true });
    await expect(caller.routes.remove({ id: 41 })).resolves.toEqual({ success: true });
    expect(dbMocks.listPersonalRoutes).toHaveBeenCalledWith(7);
    expect(dbMocks.setPersonalRoutePinned).toHaveBeenCalledWith(7, 41, true);
    expect(dbMocks.removePersonalRoute).toHaveBeenCalledWith(7, 41);
  });

  it.each([
    ["origine e destinazione uguali a meno di maiuscole e spazi", { origin: "Roma", destination: "  roma ", name: "Casa" }],
    ["un nome di un solo carattere dopo il trim", { origin: "Roma", destination: "Tivoli", name: "  a  " }],
    ["un'origine fatta di soli spazi", { origin: "     ", destination: "Tivoli", name: "Casa" }],
  ])("rifiuta come BAD_REQUEST, non come errore interno, %s", async (_label, input) => {
    const caller = appRouter.createCaller(authenticatedContext());
    await expect(caller.routes.create(input)).rejects.toMatchObject({ code: "BAD_REQUEST" });
    expect(dbMocks.createPersonalRoute).not.toHaveBeenCalledWith(7, expect.objectContaining(input));
  });

  it("rifiuta la tratta oltre il limite dell’account con un codice distinto dall’input non valido", async () => {
    dbMocks.createPersonalRoute.mockRejectedValueOnce(new RouteLimitError("Al massimo 32 tratte"));
    const caller = appRouter.createCaller(authenticatedContext());
    await expect(caller.routes.create({ origin: "Roma Termini", destination: "Tivoli", name: "Casa" })).rejects.toMatchObject({
      code: "PRECONDITION_FAILED",
    });
  });

  it("salva le etichette ripulite dagli spazi e mappa una validazione dell’archivio su BAD_REQUEST", async () => {
    dbMocks.createPersonalRoute.mockResolvedValueOnce({ id: 42 });
    const caller = appRouter.createCaller(authenticatedContext());
    await caller.routes.create({ origin: "  Roma Termini ", destination: "Tivoli", name: " Casa " });
    expect(dbMocks.createPersonalRoute).toHaveBeenLastCalledWith(7, { origin: "Roma Termini", destination: "Tivoli", name: "Casa" });
    dbMocks.createPersonalRoute.mockRejectedValueOnce(new RouteValidationError("Nome tratta non valido"));
    await expect(caller.routes.create({ origin: "Roma Termini", destination: "Tivoli", name: "Casa" })).rejects.toMatchObject({
      code: "BAD_REQUEST",
      message: "Nome tratta non valido",
    });
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
    await expect(caller.voice.unlink()).rejects.toMatchObject({ code: "UNAUTHORIZED" });
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
