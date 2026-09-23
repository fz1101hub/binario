import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { afterEach, describe, expect, it } from "vitest";
import { createApp, OAUTH_REQUESTS_PER_MINUTE, trustProxySetting } from "./app";

let server: Server | undefined;

async function start() {
  const app = await createApp({ serveClient: false });
  server = app.listen(0);
  await new Promise<void>(resolve => server!.once("listening", () => resolve()));
  return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}

afterEach(async () => {
  if (server) await new Promise<void>(resolve => server!.close(() => resolve()));
  server = undefined;
});

describe("applicazione HTTP", () => {
  it("espone /healthz con gli header di sicurezza", async () => {
    const baseUrl = await start();
    const response = await fetch(`${baseUrl}/healthz`);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: "ok" });
    expect(response.headers.get("content-security-policy")).toBe("frame-ancestors 'none'");
    expect(response.headers.get("x-frame-options")).toBe("DENY");
    expect(response.headers.get("x-powered-by")).toBeNull();
  });

  it("limita la frequenza delle richieste OAuth per client", async () => {
    const baseUrl = await start();
    let lastStatus = 0;
    for (let attempt = 0; attempt <= OAUTH_REQUESTS_PER_MINUTE; attempt++) {
      const response = await fetch(`${baseUrl}/api/alexa/oauth/token`, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: "grant_type=authorization_code" });
      lastStatus = response.status;
    }
    expect(lastStatus).toBe(429);
  });

  it("interpreta TRUST_PROXY con un default prudente", () => {
    expect(trustProxySetting("", true)).toBe(1);
    expect(trustProxySetting("", false)).toBe(false);
    expect(trustProxySetting("2", true)).toBe(2);
    expect(trustProxySetting("false", true)).toBe(false);
    expect(trustProxySetting("loopback", true)).toBe("loopback");
  });
});
