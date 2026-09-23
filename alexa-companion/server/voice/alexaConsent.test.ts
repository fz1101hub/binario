import express from "express";
import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const REDIRECT = "https://layla.amazon.com/api/skill/link/M1TEST";

vi.hoisted(() => {
  Object.assign(process.env, {
    ALEXA_ENABLED: "true",
    ALEXA_SKILL_ID: "amzn1.ask.skill.test",
    ALEXA_OAUTH_CLIENT_ID: "alexa-client",
    ALEXA_OAUTH_CLIENT_SECRET: "alexa-secret",
    ALEXA_OAUTH_REDIRECT_URIS:
      "https://pitangui.amazon.com/api/skill/link/M1TEST, https://layla.amazon.com/api/skill/link/M1TEST",
    VOICE_TOKEN_PEPPER: "test-pepper-with-plenty-of-entropy-0123456789",
  });
});

const dbMocks = vi.hoisted(() => ({
  createAuthorizationCode: vi.fn(),
  createRefreshToken: vi.fn(),
  getActiveRefreshToken: vi.fn(),
  getAlexaLinkStatus: vi.fn(),
  listPersonalRoutes: vi.fn(),
  recordAlexaVerification: vi.fn(),
  restoreAlexaLink: vi.fn(),
  revokeRefreshToken: vi.fn(),
  consumeAuthorizationCode: vi.fn(),
}));
const sdkMocks = vi.hoisted(() => ({ authenticateRequest: vi.fn() }));

vi.mock("../db", () => dbMocks);
vi.mock("../_core/sdk", () => ({ sdk: sdkMocks }));

import { registerAlexaRoutes, resolveLinkedUser } from "./alexa";
import {
  AUTHORIZATION_REQUEST_COOKIE,
  CONSENT_COOKIE,
  readAuthorizationRequest,
  signAuthorizationRequest,
} from "./consent";
import { issueVoiceAccessToken } from "./crypto";

let server: Server | undefined;
let baseUrl = "";

beforeEach(async () => {
  vi.clearAllMocks();
  sdkMocks.authenticateRequest.mockResolvedValue({ id: 7, name: "Test" });
  dbMocks.createAuthorizationCode.mockResolvedValue(undefined);
  dbMocks.restoreAlexaLink.mockResolvedValue(undefined);
  dbMocks.createRefreshToken.mockResolvedValue(undefined);
  dbMocks.recordAlexaVerification.mockResolvedValue(undefined);
  const app = express();
  registerAlexaRoutes(app);
  server = app.listen(0);
  await new Promise<void>(resolve =>
    server!.once("listening", () => resolve())
  );
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterEach(async () => {
  if (server)
    await new Promise<void>(resolve => server!.close(() => resolve()));
  server = undefined;
});

const authorizeQuery = `response_type=code&client_id=alexa-client&redirect_uri=${encodeURIComponent(REDIRECT)}&state=opaque-state&scope=routes.read`;

async function openConsentPage() {
  const response = await fetch(
    `${baseUrl}/api/alexa/oauth/authorize?${authorizeQuery}`,
    { redirect: "manual" }
  );
  const html = await response.text();
  const nonce =
    response.headers
      .get("set-cookie")
      ?.match(new RegExp(`${CONSENT_COOKIE}=([^;]+)`))?.[1] ?? "";
  const token = html.match(/name="csrf_token" value="([0-9a-f]+)"/)?.[1] ?? "";
  return { response, html, nonce, token };
}

function submitConsent(fields: Record<string, string>, cookie?: string) {
  return fetch(`${baseUrl}/api/alexa/oauth/authorize`, {
    method: "POST",
    redirect: "manual",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      ...(cookie ? { cookie } : {}),
    },
    body: new URLSearchParams({
      response_type: "code",
      client_id: "alexa-client",
      redirect_uri: REDIRECT,
      state: "opaque-state",
      scope: "routes.read",
      ...fields,
    }).toString(),
  });
}

describe("authorize Alexa con consenso esplicito", () => {
  it("non accetta richieste preservate manomesse o scadute", () => {
    const signed = signAuthorizationRequest(
      {
        clientId: "alexa-client",
        redirectUri: REDIRECT,
        state: "opaque-state",
        scope: "routes.read",
      },
      1_000
    );
    expect(readAuthorizationRequest(signed, 1_001)).toMatchObject({
      clientId: "alexa-client",
    });
    expect(readAuthorizationRequest(`${signed}x`, 1_001)).toBeNull();
    expect(readAuthorizationRequest(signed, 1_000 + 10 * 60 * 1000)).toBeNull();
  });

  it("il GET autenticato mostra la pagina di consenso senza emettere alcun code", async () => {
    const { response, html, nonce, token } = await openConsentPage();
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("text/html");
    expect(html).toContain("Collega Alexa a Binario");
    expect(nonce).not.toBe("");
    expect(token).toMatch(/^[0-9a-f]{64}$/);
    expect(dbMocks.createAuthorizationCode).not.toHaveBeenCalled();
  });

  it("rifiuta una callback fuori dall'allowlist e un utente non autenticato", async () => {
    const foreign = await fetch(
      `${baseUrl}/api/alexa/oauth/authorize?response_type=code&client_id=alexa-client&redirect_uri=${encodeURIComponent("https://attacker.example/cb")}&state=s`,
      { redirect: "manual" }
    );
    expect(foreign.status).toBe(400);
    sdkMocks.authenticateRequest.mockRejectedValueOnce(new Error("no session"));
    const anonymous = await fetch(
      `${baseUrl}/api/alexa/oauth/authorize?${authorizeQuery}`,
      { redirect: "manual" }
    );
    expect(anonymous.status).toBe(302);
    expect(anonymous.headers.get("location")).toBe("/");
    const setCookie = anonymous.headers.get("set-cookie") ?? "";
    const stored = setCookie.match(
      new RegExp(`${AUTHORIZATION_REQUEST_COOKIE}=([^;]+)`)
    )?.[1];
    expect(setCookie).toContain("HttpOnly");
    expect(setCookie).toContain("SameSite=Lax");
    expect(setCookie).toContain("Path=/");
    expect(setCookie).toContain("Secure");
    expect(readAuthorizationRequest(stored)).toMatchObject({
      clientId: "alexa-client",
      redirectUri: REDIRECT,
      state: "opaque-state",
    });
    expect(dbMocks.createAuthorizationCode).not.toHaveBeenCalled();
  });

  it("emette il code solo da un POST con cookie di consenso e token CSRF coerenti", async () => {
    const { nonce, token } = await openConsentPage();

    const withoutCookie = await submitConsent({
      csrf_token: token,
      decision: "allow",
    });
    expect(withoutCookie.status).toBe(403);
    const wrongToken = await submitConsent(
      { csrf_token: "0".repeat(64), decision: "allow" },
      `${CONSENT_COOKIE}=${nonce}`
    );
    expect(wrongToken.status).toBe(403);
    expect(dbMocks.createAuthorizationCode).not.toHaveBeenCalled();

    const allowed = await submitConsent(
      { csrf_token: token, decision: "allow" },
      `${CONSENT_COOKIE}=${nonce}`
    );
    expect(allowed.status).toBe(302);
    const location = new URL(allowed.headers.get("location") ?? "");
    expect(location.origin + location.pathname).toBe(REDIRECT);
    expect(location.searchParams.get("state")).toBe("opaque-state");
    expect(location.searchParams.get("code")).toMatch(/^[A-Za-z0-9_-]{40,}$/);
    expect(dbMocks.createAuthorizationCode).toHaveBeenCalledWith(
      expect.objectContaining({
        userId: 7,
        clientId: "alexa-client",
        redirectUri: REDIRECT,
      })
    );
  });

  it("un token di consenso emesso per un altro utente non è riutilizzabile", async () => {
    const { nonce, token } = await openConsentPage();
    sdkMocks.authenticateRequest.mockResolvedValue({ id: 8, name: "Altro" });
    const response = await submitConsent(
      { csrf_token: token, decision: "allow" },
      `${CONSENT_COOKIE}=${nonce}`
    );
    expect(response.status).toBe(403);
    expect(dbMocks.createAuthorizationCode).not.toHaveBeenCalled();
  });

  it("il rifiuto del consenso torna ad Amazon con access_denied senza creare code", async () => {
    const { nonce, token } = await openConsentPage();
    const denied = await submitConsent(
      { csrf_token: token, decision: "deny" },
      `${CONSENT_COOKIE}=${nonce}`
    );
    expect(denied.status).toBe(302);
    const location = new URL(denied.headers.get("location") ?? "");
    expect(location.searchParams.get("error")).toBe("access_denied");
    expect(location.searchParams.get("code")).toBeNull();
    expect(dbMocks.createAuthorizationCode).not.toHaveBeenCalled();
  });
});

describe("scambio del code e revoca del collegamento", () => {
  it("lo scambio di un code valido ripristina il collegamento e rilascia i token", async () => {
    dbMocks.consumeAuthorizationCode.mockResolvedValue({ userId: 7 });
    const response = await fetch(`${baseUrl}/api/alexa/oauth/token`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        client_id: "alexa-client",
        client_secret: "alexa-secret",
        code: "raw-code",
        redirect_uri: REDIRECT,
      }).toString(),
    });
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body.token_type).toBe("Bearer");
    expect(dbMocks.restoreAlexaLink).toHaveBeenCalledWith(7);
    expect(
      await resolveLinkedUser(body.access_token, "amzn1.ask.account.TEST")
    ).toBe(7);
    expect(dbMocks.recordAlexaVerification).toHaveBeenCalledWith(
      7,
      expect.stringMatching(/^[0-9a-f]{64}$/)
    );
  });

  it("dopo l'unlink un access token ancora valido non legge dati né ripristina il collegamento", async () => {
    const accessToken = await issueVoiceAccessToken(7);
    dbMocks.getAlexaLinkStatus.mockResolvedValue({
      userId: 7,
      revokedAt: new Date(),
    });
    expect(
      await resolveLinkedUser(accessToken, "amzn1.ask.account.TEST")
    ).toBeNull();
    expect(dbMocks.recordAlexaVerification).not.toHaveBeenCalled();
  });

  it("un token assente, manomesso o di un altro client non identifica alcun utente", async () => {
    dbMocks.getAlexaLinkStatus.mockResolvedValue(null);
    expect(
      await resolveLinkedUser(undefined, "amzn1.ask.account.TEST")
    ).toBeNull();
    const accessToken = await issueVoiceAccessToken(7);
    expect(
      await resolveLinkedUser(
        `${accessToken.slice(0, -2)}xx`,
        "amzn1.ask.account.TEST"
      )
    ).toBeNull();
    expect(dbMocks.recordAlexaVerification).not.toHaveBeenCalled();
  });
});
