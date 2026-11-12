import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TRPC_MAX_BATCH_SIZE } from "@shared/const";

vi.hoisted(() => {
  Object.assign(process.env, {
    ALEXA_ENABLED: "true",
    ALEXA_SKILL_ID: "amzn1.ask.skill.test",
    ALEXA_OAUTH_CLIENT_ID: "alexa-client",
    ALEXA_OAUTH_CLIENT_SECRET: "alexa-secret",
    ALEXA_OAUTH_REDIRECT_URIS: "https://layla.amazon.com/api/skill/link/M1TEST",
    VOICE_TOKEN_PEPPER: "test-pepper-with-plenty-of-entropy-0123456789",
  });
});

import {
  ALEXA_TOKEN_FAILED_REQUESTS_PER_MINUTE,
  ALEXA_WEBHOOK_FAILED_REQUESTS_PER_MINUTE,
  createApp,
  OAUTH_REQUESTS_PER_MINUTE,
  trustProxySetting,
} from "./app";

let server: Server | undefined;

async function start() {
  const app = await createApp({ serveClient: false });
  server = app.listen(0);
  await new Promise<void>(resolve =>
    server!.once("listening", () => resolve())
  );
  return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}

afterEach(async () => {
  if (server)
    await new Promise<void>(resolve => server!.close(() => resolve()));
  server = undefined;
});

describe("applicazione HTTP", () => {
  it("espone /healthz con gli header di sicurezza", async () => {
    const baseUrl = await start();
    const response = await fetch(`${baseUrl}/healthz`);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: "ok" });
    expect(response.headers.get("content-security-policy")).toBe(
      "frame-ancestors 'none'"
    );
    expect(response.headers.get("x-frame-options")).toBe("DENY");
    expect(response.headers.get("x-powered-by")).toBeNull();
  });

  it("espone /readyz: 200 se il servizio può rispondere, 503 altrimenti, senza cache", async () => {
    const app = await createApp({
      serveClient: false,
      readiness: async () => ({ ready: false, database: "unreachable", railData: "loaded" }),
    });
    server = app.listen(0);
    await new Promise<void>(resolve => server!.once("listening", () => resolve()));
    const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    const response = await fetch(`${baseUrl}/readyz`);
    expect(response.status).toBe(503);
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(await response.json()).toEqual({ ready: false, database: "unreachable", railData: "loaded" });
  });

  it("limita per IP la pagina di consenso Alexa aperta dal browser", async () => {
    const baseUrl = await start();
    let lastStatus = 0;
    for (let attempt = 0; attempt <= OAUTH_REQUESTS_PER_MINUTE; attempt++)
      lastStatus = (await fetch(`${baseUrl}/api/alexa/oauth/authorize`, { redirect: "manual" })).status;
    expect(lastStatus).toBe(429);
  });

  it("limita per IP solo l'autenticazione client fallita sul token endpoint", async () => {
    const baseUrl = await start();
    let lastStatus = 0;
    for (let attempt = 0; attempt <= ALEXA_TOKEN_FAILED_REQUESTS_PER_MINUTE; attempt++) {
      const response = await fetch(`${baseUrl}/api/alexa/oauth/token`, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: "grant_type=authorization_code&client_id=alexa-client&client_secret=wrong",
      });
      lastStatus = response.status;
    }
    expect(lastStatus).toBe(429);
  });

  it("non conta per IP le credenziali di un altro client, che Amazon inoltra dagli stessi indirizzi", async () => {
    const baseUrl = await start();
    const statuses = new Set<number>();
    for (let attempt = 0; attempt <= ALEXA_TOKEN_FAILED_REQUESTS_PER_MINUTE + 5; attempt++) {
      const response = await fetch(`${baseUrl}/api/alexa/oauth/token`, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: "grant_type=authorization_code&client_id=someone-elses-skill&client_secret=theirs",
      });
      statuses.add(response.status);
    }
    expect([...statuses]).toEqual([401]);
  });

  it("non limita per IP il traffico token del client autenticato, che arriva da pochi indirizzi Amazon", async () => {
    const baseUrl = await start();
    const statuses = new Set<number>();
    for (let attempt = 0; attempt <= ALEXA_TOKEN_FAILED_REQUESTS_PER_MINUTE + 10; attempt++) {
      const response = await fetch(`${baseUrl}/api/alexa/oauth/token`, {
        method: "POST",
        headers: {
          "content-type": "application/x-www-form-urlencoded",
          authorization: `Basic ${Buffer.from("alexa-client:alexa-secret").toString("base64")}`,
        },
        body: "grant_type=device_code",
      });
      statuses.add(response.status);
    }
    expect([...statuses]).toEqual([400]);
  });

  it("limita per IP le richieste webhook che non superano la verifica della firma", async () => {
    const baseUrl = await start();
    let lastStatus = 0;
    for (let attempt = 0; attempt <= ALEXA_WEBHOOK_FAILED_REQUESTS_PER_MINUTE; attempt++) {
      const response = await fetch(`${baseUrl}/api/alexa/webhook`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: "{}",
      });
      lastStatus = response.status;
    }
    expect(lastStatus).toBe(429);
  });

  it("non espone proxy storage o notifiche di scaffolding", async () => {
    const baseUrl = await start();
    const storage = await fetch(`${baseUrl}/manus-storage/private-key`);
    const notification = await fetch(`${baseUrl}/api/trpc/system.notifyOwner`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ json: { title: "x", content: "y" } }),
    });

    expect(storage.status).toBe(404);
    expect(notification.status).toBe(404);
  });

  it.each(["auth.logout", "voice.unlink"])(
    "rifiuta form cross-site prima della mutation %s",
    async procedure => {
      const baseUrl = await start();
      const response = await fetch(`${baseUrl}/api/trpc/${procedure}`, {
        method: "POST",
        headers: {
          "content-type": "multipart/form-data; boundary=csrf",
          origin: "https://attacker.invalid",
          "sec-fetch-site": "cross-site",
        },
        body: "--csrf--\r\n",
      });

      expect(response.status).toBe(415);
      expect(await response.json()).toEqual({
        error: "json_same_origin_required",
      });
    }
  );

  it("lascia raggiungere tRPC alle richieste JSON non formabili", async () => {
    const baseUrl = await start();
    const response = await fetch(`${baseUrl}/api/trpc/auth.logout`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "{}",
    });

    expect(response.status).not.toBe(415);
  });

  it("rifiuta un batch tRPC oltre il limite condiviso con il client", async () => {
    const baseUrl = await start();
    const procedures = (count: number) => Array.from({ length: count }, () => "auth.me").join(",");
    const input = (count: number) =>
      encodeURIComponent(JSON.stringify(Object.fromEntries(Array.from({ length: count }, (_, i) => [i, {}]))));
    const within = await fetch(
      `${baseUrl}/api/trpc/${procedures(TRPC_MAX_BATCH_SIZE)}?batch=1&input=${input(TRPC_MAX_BATCH_SIZE)}`
    );
    const over = await fetch(
      `${baseUrl}/api/trpc/${procedures(TRPC_MAX_BATCH_SIZE + 1)}?batch=1&input=${input(TRPC_MAX_BATCH_SIZE + 1)}`
    );

    expect(within.status).toBe(200);
    expect(over.status).toBe(400);
  });

  it("interpreta TRUST_PROXY con un default prudente", () => {
    expect(trustProxySetting("", true)).toBe(1);
    expect(trustProxySetting("", false)).toBe(false);
    expect(trustProxySetting("2", true)).toBe(2);
    expect(trustProxySetting("false", true)).toBe(false);
    expect(trustProxySetting("loopback", true)).toBe("loopback");
    expect(() => trustProxySetting("true", true)).toThrow(/unsafe/);
  });
});
