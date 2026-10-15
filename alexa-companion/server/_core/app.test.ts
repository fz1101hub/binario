import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { afterEach, describe, expect, it } from "vitest";
import { TRPC_MAX_BATCH_SIZE } from "@shared/const";
import {
  ALEXA_WEBHOOK_REQUESTS_PER_MINUTE,
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

  it("limita la frequenza delle richieste OAuth per client", async () => {
    const baseUrl = await start();
    let lastStatus = 0;
    for (let attempt = 0; attempt <= OAUTH_REQUESTS_PER_MINUTE; attempt++) {
      const response = await fetch(`${baseUrl}/api/alexa/oauth/token`, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: "grant_type=authorization_code",
      });
      lastStatus = response.status;
    }
    expect(lastStatus).toBe(429);
  });

  it("limita il webhook Alexa prima che il verificatore elabori il body", async () => {
    const baseUrl = await start();
    let lastStatus = 0;
    for (
      let attempt = 0;
      attempt <= ALEXA_WEBHOOK_REQUESTS_PER_MINUTE;
      attempt++
    ) {
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
