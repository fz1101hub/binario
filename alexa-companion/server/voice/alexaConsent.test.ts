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
  exchangeAuthorizationCode: vi.fn(),
  getAlexaLinkStatus: vi.fn(),
  listPersonalRoutesForAlexa: vi.fn(),
  recordAlexaVerification: vi.fn(),
  rotateRefreshToken: vi.fn(),
}));
const sdkMocks = vi.hoisted(() => ({ authenticateRequest: vi.fn() }));

vi.mock("../db", () => dbMocks);
vi.mock("../_core/sdk", () => ({ sdk: sdkMocks }));

import { readClientCredentials, registerAlexaRoutes, resolveLinkedUser } from "./alexa";
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
  dbMocks.getAlexaLinkStatus.mockResolvedValue({
    userId: 7,
    credentialVersion: 3,
    revokedAt: null,
  });
  dbMocks.recordAlexaVerification.mockResolvedValue(undefined);
  const app = express();
  registerAlexaRoutes(app, { tokenRequestsPerClientPerMinute: 25 });
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

async function openConsentPage(acceptLanguage?: string) {
  const response = await fetch(
    `${baseUrl}/api/alexa/oauth/authorize?${authorizeQuery}`,
    {
      redirect: "manual",
      headers: acceptLanguage ? { "accept-language": acceptLanguage } : {},
    }
  );
  const html = await response.text();
  const nonce =
    response.headers
      .get("set-cookie")
      ?.match(new RegExp(`${CONSENT_COOKIE}=([^;]+)`))?.[1] ?? "";
  const token = html.match(/name="csrf_token" value="([0-9a-f]+)"/)?.[1] ?? "";
  return { response, html, nonce, token };
}

function submitConsent(
  fields: Record<string, string>,
  cookie?: string,
  acceptLanguage?: string
) {
  return fetch(`${baseUrl}/api/alexa/oauth/authorize`, {
    method: "POST",
    redirect: "manual",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      ...(cookie ? { cookie } : {}),
      ...(acceptLanguage ? { "accept-language": acceptLanguage } : {}),
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

  it("un Accept-Language ereditato non può terminare il processo OAuth", async () => {
    const invalid = await fetch(`${baseUrl}/api/alexa/oauth/authorize`, {
      headers: { "accept-language": "constructor" },
    });
    expect(invalid.status).toBe(400);
    expect(await invalid.text()).toContain('<html lang="it-IT">');
    const valid = await openConsentPage("constructor-foo");
    expect(valid.response.status).toBe(200);
    expect(valid.html).toContain('<html lang="it-IT">');
  });

  it.each([
    ["it-IT", "it-IT", "Collega Alexa a Binario"],
    ["en-US", "en-US", "Link Alexa to Binario"],
    ["en-GB", "en-GB", "Link Alexa to Binario"],
    ["de-DE", "de-DE", "Alexa mit Binario verknüpfen"],
    ["fr-FR", "fr-FR", "Associer Alexa à Binario"],
    ["es-ES", "es-ES", "Vincular Alexa con Binario"],
    ["pt-BR", "pt-BR", "Conectar a Alexa ao Binario"],
    ["ja-JP", "ja-JP", "Alexa を Binario にリンク"],
    // Web-only locales: the consent page is a web page, not an Alexa response.
    ["ko-KR", "ko-KR", "Alexa를 Binario에 연결"],
    ["zh-CN", "zh-Hans", "将 Alexa 关联到 Binario"],
    ["nl-NL, de-DE;q=0.8", "de-DE", "Alexa mit Binario verknüpfen"],
    ["zh-TW, nl-NL", "it-IT", "Collega Alexa a Binario"],
  ] as const)(
    "localizza l'HTML di consenso per %s come %s",
    async (header, expectedLocale, expectedTitle) => {
      const { response, html } = await openConsentPage(header);
      expect(response.status).toBe(200);
      expect(response.headers.get("content-type")).toContain("text/html");
      expect(html).toContain(`<html lang="${expectedLocale}">`);
      expect(html).toContain(`<title>${expectedTitle}</title>`);
      expect(html).toContain(`>${expectedTitle}</h1>`);
      expect(html).toContain('name="csrf_token"');
      expect(html).toContain('name="decision" value="allow"');
      expect(html).toContain('name="decision" value="deny"');
    }
  );

  it("imposta la direzione da destra a sinistra solo per l'arabo", async () => {
    const { response, html } = await openConsentPage("ar-SA");
    expect(response.status).toBe(200);
    expect(html).toContain('<html lang="ar-SA" dir="rtl">');
    expect(html).toContain("<title>ربط Alexa بـ Binario</title>");
    expect((await openConsentPage("en-US")).html).not.toContain("dir=");
  });

  it("escapa il nome utente senza alterare il markup localizzato", async () => {
    sdkMocks.authenticateRequest.mockResolvedValueOnce({
      id: 7,
      name: '<script>alert("x")</script>',
    });
    const { html } = await openConsentPage("en-US");
    expect(html).toContain("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;");
    expect(html).not.toContain('<script>alert("x")</script>');
  });

  it("localizza l'HTML di login richiesto senza emettere code", async () => {
    sdkMocks.authenticateRequest.mockRejectedValueOnce(new Error("no session"));
    const response = await submitConsent(
      { csrf_token: "0".repeat(64), decision: "allow" },
      undefined,
      "fr-FR"
    );
    const html = await response.text();
    expect(response.status).toBe(401);
    expect(response.headers.get("content-type")).toContain("text/html");
    expect(html).toContain('<html lang="fr-FR">');
    expect(html).toContain("Connectez-vous d’abord au tableau de bord Binario");
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
    }, undefined, "de-DE");
    expect(withoutCookie.status).toBe(403);
    expect(await withoutCookie.text()).toContain(
      "Die Sicherheitsprüfung ist abgelaufen oder ungültig"
    );
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

  it("un guasto DB nel consenso produce server_error senza redirect né code", async () => {
    const { nonce, token } = await openConsentPage();
    dbMocks.createAuthorizationCode.mockRejectedValueOnce(new Error("secret database details"));
    const failed = await submitConsent(
      { csrf_token: token, decision: "allow" },
      `${CONSENT_COOKIE}=${nonce}`
    );
    expect(failed.status).toBe(500);
    expect(failed.headers.get("cache-control")).toBe("no-store");
    const body = await failed.text();
    expect(body).toContain("server_error");
    expect(body).not.toContain("secret database details");
    expect((await openConsentPage()).response.status).toBe(200);
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

function basic(id: string, secret: string) {
  const encode = (value: string) => encodeURIComponent(value).replace(/%20/g, "+");
  return `Basic ${Buffer.from(`${encode(id)}:${encode(secret)}`).toString("base64")}`;
}

function tokenRequest(fields: Record<string, string>, authorization?: string) {
  return fetch(`${baseUrl}/api/alexa/oauth/token`, {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      ...(authorization ? { authorization } : {}),
    },
    body: new URLSearchParams(fields).toString(),
  });
}

describe("autenticazione del client sul token endpoint", () => {
  it("decodifica HTTP Basic con id e secret form-URL-encoded (RFC 6749 §2.3.1)", () => {
    expect(readClientCredentials(basic("alexa client:1", "s3cr+t:é"), {})).toEqual({
      clientId: "alexa client:1",
      clientSecret: "s3cr+t:é",
    });
    expect(readClientCredentials(`basic ${Buffer.from("id:").toString("base64")}`, {})).toEqual({ clientId: "id", clientSecret: "" });
    expect(readClientCredentials(undefined, { client_id: "id", client_secret: "secret" })).toEqual({ clientId: "id", clientSecret: "secret" });
    // Two methods at once, a different body client_id, malformed or undecodable Basic.
    expect(readClientCredentials(basic("id", "secret"), { client_secret: "secret" })).toBeNull();
    expect(readClientCredentials(basic("id", "secret"), { client_id: "other" })).toBeNull();
    expect(readClientCredentials("Basic", {})).toBeNull();
    expect(readClientCredentials("Basic bm8tc2VwYXJhdG9y", {})).toBeNull();
    expect(readClientCredentials(`Basic ${Buffer.from("%E0%A4%A:x").toString("base64")}`, {})).toBeNull();
    expect(readClientCredentials(undefined, { client_id: "id" })).toBeNull();
  });

  it("accetta HTTP Basic, lo schema raccomandato dalla console Alexa, e non rende cacheabile la risposta", async () => {
    dbMocks.rotateRefreshToken.mockResolvedValue({ userId: 7, credentialVersion: 3 });
    const response = await tokenRequest(
      { grant_type: "refresh_token", refresh_token: "old-refresh-token", client_id: "alexa-client" },
      basic("alexa-client", "alexa-secret")
    );
    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(response.headers.get("pragma")).toBe("no-cache");
    const body = await response.json();
    expect(body).toMatchObject({ token_type: "Bearer", expires_in: 3600 });
    expect(dbMocks.rotateRefreshToken).toHaveBeenCalledWith(expect.objectContaining({ clientId: "alexa-client" }));
  });

  it("anche la risposta con le credenziali nel body non è cacheabile", async () => {
    dbMocks.exchangeAuthorizationCode.mockResolvedValue({ userId: 7, credentialVersion: 3 });
    const response = await tokenRequest({
      grant_type: "authorization_code",
      client_id: "alexa-client",
      client_secret: "alexa-secret",
      code: "raw-code",
      redirect_uri: REDIRECT,
    });
    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(response.headers.get("pragma")).toBe("no-cache");
  });

  it.each([
    ["Basic con secret errato", { grant_type: "refresh_token" }, basic("alexa-client", "wrong")],
    ["Basic e client_secret insieme", { grant_type: "refresh_token", client_secret: "alexa-secret" }, basic("alexa-client", "alexa-secret")],
    ["client_id del body diverso da Basic", { grant_type: "refresh_token", client_id: "other" }, basic("alexa-client", "alexa-secret")],
    ["nessuna credenziale", { grant_type: "refresh_token" }, undefined],
  ] as const)("rifiuta %s con 401 invalid_client e WWW-Authenticate", async (_label, fields, authorization) => {
    const response = await tokenRequest({ ...fields, refresh_token: "old-refresh-token" }, authorization);
    expect(response.status).toBe(401);
    expect(await response.json()).toEqual({ error: "invalid_client" });
    expect(response.headers.get("www-authenticate")).toMatch(/^Basic realm="binario-alexa"/);
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(dbMocks.rotateRefreshToken).not.toHaveBeenCalled();
  });

  it("limita le richieste del client autenticato in un proprio bucket, non per IP", async () => {
    const statuses: number[] = [];
    for (let attempt = 0; attempt < 25; attempt++)
      statuses.push((await tokenRequest({ grant_type: "device_code" }, basic("alexa-client", "alexa-secret"))).status);
    expect(new Set(statuses)).toEqual(new Set([400]));
    const limited = await tokenRequest({ grant_type: "device_code" }, basic("alexa-client", "alexa-secret"));
    expect(limited.status).toBe(429);
    expect(Number(limited.headers.get("retry-after"))).toBeGreaterThan(0);
    // A failed authentication is not spent from the authenticated client's budget.
    expect((await tokenRequest({ grant_type: "device_code" }, basic("alexa-client", "wrong"))).status).toBe(401);
  });
});

describe("scambio del code e revoca del collegamento", () => {
  it.each([
    ["authorization_code", "exchangeAuthorizationCode"],
    ["refresh_token", "rotateRefreshToken"],
  ] as const)("un guasto %s del DB risponde server_error senza crash", async (grantType, operation) => {
    dbMocks[operation].mockRejectedValueOnce(new Error("secret database details"));
    const response = await fetch(`${baseUrl}/api/alexa/oauth/token`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: grantType,
        client_id: "alexa-client",
        client_secret: "alexa-secret",
        code: "raw-code",
        redirect_uri: REDIRECT,
        refresh_token: "old-refresh-token",
      }).toString(),
    });
    expect(response.status).toBe(500);
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(await response.json()).toEqual({ error: "server_error" });
    expect((await openConsentPage()).response.status).toBe(200);
  });

  it("lo scambio di un code valido ripristina il collegamento e rilascia i token", async () => {
    dbMocks.exchangeAuthorizationCode.mockResolvedValue({
      userId: 7,
      credentialVersion: 3,
    });
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
    expect(dbMocks.exchangeAuthorizationCode).toHaveBeenCalledWith(
      expect.objectContaining({
        clientId: "alexa-client",
        redirectUri: REDIRECT,
        codeHash: expect.stringMatching(/^[0-9a-f]{64}$/),
        refreshTokenHash: expect.stringMatching(/^[0-9a-f]{64}$/),
      })
    );
    // Long-lived refresh tokens: an idle month must not unlink the user.
    const { refreshTokenExpiresAt } = dbMocks.exchangeAuthorizationCode.mock.calls[0][0] as { refreshTokenExpiresAt: Date };
    expect(refreshTokenExpiresAt.getTime() - Date.now()).toBeGreaterThan(364 * 24 * 60 * 60 * 1000);
    expect(
      await resolveLinkedUser(body.access_token, "amzn1.ask.account.TEST")
    ).toEqual({ userId: 7, credentialVersion: 3 });
    expect(dbMocks.recordAlexaVerification).toHaveBeenCalledWith(
      7,
      expect.stringMatching(/^[0-9a-f]{64}$/)
    );
  });

  it("un authorization code revocato durante unlink resta invalid_grant", async () => {
    dbMocks.exchangeAuthorizationCode.mockResolvedValue(null);
    const response = await fetch(`${baseUrl}/api/alexa/oauth/token`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        client_id: "alexa-client",
        client_secret: "alexa-secret",
        code: "revoked-code",
        redirect_uri: REDIRECT,
      }).toString(),
    });

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toMatchObject({
      error: "invalid_grant",
    });
  });

  it("la rotazione refresh usa una singola operazione DB atomica", async () => {
    dbMocks.rotateRefreshToken.mockResolvedValue({
      userId: 7,
      credentialVersion: 3,
    });
    const response = await fetch(`${baseUrl}/api/alexa/oauth/token`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "refresh_token",
        client_id: "alexa-client",
        client_secret: "alexa-secret",
        refresh_token: "old-refresh-token",
      }).toString(),
    });

    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body.refresh_token).not.toBe("old-refresh-token");
    expect(dbMocks.rotateRefreshToken).toHaveBeenCalledWith(
      expect.objectContaining({
        clientId: "alexa-client",
        currentTokenHash: expect.stringMatching(/^[0-9a-f]{64}$/),
        nextTokenHash: expect.stringMatching(/^[0-9a-f]{64}$/),
      })
    );
    const { nextTokenExpiresAt } = dbMocks.rotateRefreshToken.mock.calls[0][0] as { nextTokenExpiresAt: Date };
    expect(nextTokenExpiresAt.getTime() - Date.now()).toBeGreaterThan(364 * 24 * 60 * 60 * 1000);
  });

  it("dopo l'unlink un access token ancora valido non legge dati né ripristina il collegamento", async () => {
    const accessToken = await issueVoiceAccessToken(7, 3);
    dbMocks.getAlexaLinkStatus.mockResolvedValue({
      userId: 7,
      credentialVersion: 4,
      revokedAt: new Date(),
    });
    expect(
      await resolveLinkedUser(accessToken, "amzn1.ask.account.TEST")
    ).toBeNull();
    expect(dbMocks.recordAlexaVerification).not.toHaveBeenCalled();
  });

  it("un access token valido senza link persistito fallisce chiuso", async () => {
    const accessToken = await issueVoiceAccessToken(7, 3);
    dbMocks.getAlexaLinkStatus.mockResolvedValue(null);
    expect(
      await resolveLinkedUser(accessToken, "amzn1.ask.account.TEST")
    ).toBeNull();
    expect(dbMocks.recordAlexaVerification).not.toHaveBeenCalled();
  });

  it("un access token di una generazione precedente resta revocato dopo relink", async () => {
    const accessToken = await issueVoiceAccessToken(7, 3);
    dbMocks.getAlexaLinkStatus.mockResolvedValue({
      userId: 7,
      credentialVersion: 5,
      revokedAt: null,
    });

    await expect(
      resolveLinkedUser(accessToken, "amzn1.ask.account.TEST")
    ).resolves.toBeNull();
    expect(dbMocks.recordAlexaVerification).not.toHaveBeenCalled();
  });

  it("un token assente, manomesso o di un altro client non identifica alcun utente", async () => {
    dbMocks.getAlexaLinkStatus.mockResolvedValue(null);
    expect(
      await resolveLinkedUser(undefined, "amzn1.ask.account.TEST")
    ).toBeNull();
    const accessToken = await issueVoiceAccessToken(7, 3);
    expect(
      await resolveLinkedUser(
        `${accessToken.slice(0, -2)}xx`,
        "amzn1.ask.account.TEST"
      )
    ).toBeNull();
    expect(dbMocks.recordAlexaVerification).not.toHaveBeenCalled();
  });
});
