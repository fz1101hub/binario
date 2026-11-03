import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import express from "express";
import { afterEach, describe, expect, it } from "vitest";
import { createApp } from "./_core/app";
import { clientFallbackStatus, serveStatic } from "./_core/static";

const ROOT = path.resolve(import.meta.dirname, "..");
const manifest = readFileSync(path.join(ROOT, "skill-package", "skill.json"), "utf8");
const HOST = "https://REPLACE_WITH_HTTPS_HOST";

let server: Server | undefined;
afterEach(async () => {
  if (server) await new Promise<void>(resolve => server!.close(() => resolve()));
  server = undefined;
});

async function listen(app: express.Express) {
  server = app.listen(0);
  await new Promise<void>(resolve => server!.once("listening", () => resolve()));
  return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}

function pngSize(file: string) {
  const bytes = readFileSync(file);
  expect(bytes.subarray(0, 8).toString("hex"), file).toBe("89504e470d0a1a0a");
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
}

describe("URL del manifest della skill", () => {
  const urls = [...new Set(manifest.match(/https:\/\/REPLACE_WITH_HTTPS_HOST\/[^"\s]*/g) ?? [])];

  it("rimanda solo a percorsi che il servizio serve davvero", async () => {
    const paths = urls.map(url => url.slice(HOST.length)).sort();
    expect(paths).toEqual(["/alexa/icon-108.png", "/alexa/icon-512.png", "/api/alexa/webhook", "/privacy", "/terms"]);
    const baseUrl = await listen(await createApp({ serveClient: false }));
    for (const page of ["/privacy", "/terms"]) {
      const response = await fetch(`${baseUrl}${page}`);
      expect(response.status, page).toBe(200);
      expect(response.headers.get("content-type"), page).toContain("text/html");
    }
    // Without Alexa configuration the webhook exists and refuses, it is not a missing route.
    expect((await fetch(`${baseUrl}/api/alexa/webhook`, { method: "POST" })).status).toBe(503);
  });

  it("include icone PNG 108×108 e 512×512 pubblicate da Vite in /alexa", () => {
    expect(manifest).toContain(`"smallIconUri": "${HOST}/alexa/icon-108.png"`);
    expect(manifest).toContain(`"largeIconUri": "${HOST}/alexa/icon-512.png"`);
    expect(pngSize(path.join(ROOT, "client", "public", "alexa", "icon-108.png"))).toEqual({ width: 108, height: 108 });
    expect(pngSize(path.join(ROOT, "client", "public", "alexa", "icon-512.png"))).toEqual({ width: 512, height: 512 });
  });

  it("lascia le credenziali del revisore fuori dal repository", () => {
    const instructions = (JSON.parse(manifest) as { manifest: { publishingInformation: { testingInstructions: string } } })
      .manifest.publishingInformation.testingInstructions;
    expect(instructions).toContain("REPLACE_WITH_REVIEWER_ACCOUNT_AT_SUBMISSION");
    expect(instructions).not.toMatch(/password|@[a-z0-9-]+\./i);
  });
});

describe("pagine legali", () => {
  const privacyPolicy = readFileSync(path.join(ROOT, "..", "PRIVACY.md"), "utf8");
  const alexaSection = privacyPolicy.slice(privacyPolicy.indexOf("## Alexa Companion"), privacyPolicy.indexOf("## Scelte dell'utente"));

  it("riprende le sezioni Alexa Companion di PRIVACY.md senza aggiungere promesse", async () => {
    const baseUrl = await listen(await createApp({ serveClient: false }));
    const html = await (await fetch(`${baseUrl}/privacy`)).text();
    const headings = [...alexaSection.matchAll(/^### (.+)$/gm)].map(match => match[1]);
    expect(headings).toEqual(["Quando viene usato", "Voce, dati inviati da Alexa e risposte", "Revoca, cancellazione e conservazione"]);
    for (const heading of headings) expect(html).toContain(`<h2>${heading}</h2>`);
    for (const fact of ["digest HMAC", "non salva il contenuto audio", "ON DELETE CASCADE", "<code>ELIMINA</code>", "Da completare dal titolare"])
      expect(html).toContain(fact);
    expect(html).toContain('<html lang="it">');
  });

  it("marca i termini come bozza da rivedere", async () => {
    const baseUrl = await listen(await createApp({ serveClient: false }));
    const html = await (await fetch(`${baseUrl}/terms`)).text();
    expect(html).toContain("Bozza da far rivedere al titolare");
    expect(html).toContain('href="/privacy"');
  });
});

describe("percorsi sconosciuti", () => {
  it("rispondono 404, anche quando la dashboard mostra la sua pagina «non trovata»", async () => {
    const dist = mkdtempSync(path.join(tmpdir(), "binario-dist-"));
    try {
      writeFileSync(path.join(dist, "index.html"), "<!doctype html><title>Binario</title>");
      writeFileSync(path.join(dist, "asset.txt"), "ok");
      const app = express();
      serveStatic(app, dist);
      const baseUrl = await listen(app);
      expect((await fetch(`${baseUrl}/`)).status).toBe(200);
      expect((await fetch(`${baseUrl}/?alexa=1`)).status).toBe(200);
      expect((await fetch(`${baseUrl}/asset.txt`)).status).toBe(200);
      const missing = await fetch(`${baseUrl}/privacy-policy`);
      expect(missing.status).toBe(404);
      expect(await missing.text()).toContain("<title>Binario</title>");
      const api = await fetch(`${baseUrl}/api/unknown`);
      expect(api.status).toBe(404);
      expect(await api.json()).toEqual({ error: "not_found" });
    } finally {
      rmSync(dist, { recursive: true, force: true });
    }
    expect(clientFallbackStatus("/")).toBe(200);
    expect(clientFallbackStatus("/404")).toBe(404);
  });
});
