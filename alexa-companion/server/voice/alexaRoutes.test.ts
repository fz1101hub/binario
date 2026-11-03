import express from "express";
import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { afterEach, describe, expect, it } from "vitest";
import { registerAlexaRoutes } from "./alexa";

let server: Server | undefined;

async function request(path: string, options?: RequestInit) {
  const app = express();
  registerAlexaRoutes(app);
  server = app.listen(0);
  await new Promise<void>(resolve => server!.once("listening", () => resolve()));
  const port = (server.address() as AddressInfo).port;
  return fetch(`http://127.0.0.1:${port}${path}`, options);
}

afterEach(async () => {
  if (server) await new Promise<void>(resolve => server!.close(() => resolve()));
  server = undefined;
});

describe("endpoint Alexa in assenza credenziali", () => {
  it("rifiuta webhook, authorize e token prima di elaborare dati o firme", async () => {
    const webhook = await request("/api/alexa/webhook", { method: "POST", headers: { "content-type": "application/json" }, body: "{}" });
    expect(webhook.status).toBe(503);
    expect(await webhook.json()).toEqual({ error: "alexa_not_configured" });

    const authorize = await request("/api/alexa/oauth/authorize?response_type=code&client_id=x&redirect_uri=https%3A%2F%2Fexample.test&state=s", {
      headers: { "accept-language": "es-ES" },
    });
    expect(authorize.status).toBe(503);
    expect(authorize.headers.get("content-type")).toContain("text/html");
    expect(await authorize.text()).toContain(
      "La integración de Alexa no está configurada"
    );

    const token = await request("/api/alexa/oauth/token", { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: "grant_type=authorization_code&code=x" });
    expect(token.status).toBe(503);
    expect(await token.json()).toEqual({ error: "alexa_not_configured" });
  });
});
