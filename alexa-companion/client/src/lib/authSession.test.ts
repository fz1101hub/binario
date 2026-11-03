import { UNAUTHED_ERR_MSG } from "@shared/const";
import { QueryClient } from "@tanstack/react-query";
import { TRPCClientError } from "@trpc/client";
import { getQueryKey } from "@trpc/react-query";
import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { forgetPersonalData, handleApiError, isUnauthorizedError } from "./authSession";
import { trpc } from "./trpc";

const meKey = getQueryKey(trpc.auth.me, undefined, "query");
const routesKey = getQueryKey(trpc.routes.list, undefined, "query");
const voiceKey = getQueryKey(trpc.voice.status, undefined, "query");

function signedInClient() {
  const client = new QueryClient();
  client.setQueryData(meKey, { id: 7, name: "Mario Rossi" });
  client.setQueryData(routesKey, [{ id: 1, name: "Casa di Mario" }]);
  client.setQueryData(voiceKey, { linked: true });
  return client;
}

describe("sessione della dashboard", () => {
  it("riconosce soltanto l'errore UNAUTHORIZED del server", () => {
    expect(isUnauthorizedError(new TRPCClientError(UNAUTHED_ERR_MSG))).toBe(true);
    expect(isUnauthorizedError(new TRPCClientError("Errore interno"))).toBe(false);
    expect(isUnauthorizedError(new Error(UNAUTHED_ERR_MSG))).toBe(false);
  });

  it("dopo logout o cancellazione non resta in cache nulla di personale e l'utente è disconnesso", () => {
    const client = signedInClient();
    forgetPersonalData(client);
    expect(client.getQueryData(meKey)).toBeNull();
    expect(client.getQueryData(routesKey)).toBeUndefined();
    expect(client.getQueryData(voiceKey)).toBeUndefined();
    expect(client.getQueryCache().findAll()).toHaveLength(1);
  });

  it("un UNAUTHORIZED mostra la schermata di accesso; altri errori non toccano la sessione", () => {
    const client = signedInClient();
    handleApiError(client, new TRPCClientError("Errore interno"));
    expect(client.getQueryData(meKey)).toMatchObject({ id: 7 });
    handleApiError(client, new TRPCClientError(UNAUTHED_ERR_MSG));
    expect(client.getQueryData(meKey)).toBeNull();
    expect(client.getQueryData(routesKey)).toBeUndefined();
  });

  it("nessun gestore globale e nessuna pagina avvia il login da sola", () => {
    const source = (file: string) => readFileSync(path.resolve(import.meta.dirname, "..", file), "utf8");
    for (const file of ["main.tsx", "lib/authSession.ts", "pages/Home.tsx"])
      expect(source(file), file).not.toMatch(/startLogin\(/);
    // Home's protected queries wait for a signed-in user.
    expect(source("pages/Home.tsx")).toMatch(/routes\.list\.useQuery\(undefined, \{ enabled: signedIn \}\)/);
    expect(source("pages/Home.tsx")).toMatch(/voice\.status\.useQuery\(undefined, \{ enabled: signedIn \}\)/);
  });
});
