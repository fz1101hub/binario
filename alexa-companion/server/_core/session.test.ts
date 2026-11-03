import { describe, expect, it, vi } from "vitest";
import { SignJWT } from "jose";
import type { Request } from "express";
import { COOKIE_NAME, SESSION_TTL_MS } from "../../shared/const";

vi.hoisted(() => {
  Object.assign(process.env, {
    JWT_SECRET: "session-secret-with-at-least-32-characters!!",
    VITE_APP_ID: "binario-app",
    OAUTH_SERVER_URL: "https://oauth.example",
  });
});

import { sdk } from "./sdk";
import * as db from "../db";
import { environmentProblems } from "./envCheck";
import { appRouter } from "../routers";
import type { TrpcContext } from "./context";

describe("sessioni dashboard", () => {
  it("accetta solo sessioni emesse per questa app", async () => {
    const own = await sdk.createSessionToken("user-1", { name: "Utente" });
    expect(await sdk.verifySession(own)).toMatchObject({
      openId: "user-1",
      appId: "binario-app",
    });

    const secret = new TextEncoder().encode(process.env.JWT_SECRET as string);
    const foreign = await new SignJWT({
      openId: "user-1",
      appId: "other-app",
      name: "Utente",
    })
      .setProtectedHeader({ alg: "HS256", typ: "JWT" })
      .setExpirationTime("1h")
      .sign(secret);
    expect(await sdk.verifySession(foreign)).toBeNull();
  });

  it("accetta sessioni con display name vuoto o assente", async () => {
    const emptyName = await sdk.createSessionToken("empty-name-user", { name: "" });
    expect(await sdk.verifySession(emptyName)).toEqual({
      openId: "empty-name-user",
      appId: "binario-app",
      name: "",
    });

    const secret = new TextEncoder().encode(process.env.JWT_SECRET as string);
    const legacyWithoutName = await new SignJWT({
      openId: "legacy-empty-name-user",
      appId: "binario-app",
    })
      .setProtectedHeader({ alg: "HS256", typ: "JWT" })
      .setExpirationTime("1h")
      .sign(secret);
    expect(await sdk.verifySession(legacyWithoutName)).toEqual({
      openId: "legacy-empty-name-user",
      appId: "binario-app",
      name: "",
    });
  });

  it("continua a richiedere openId e appId", async () => {
    const secret = new TextEncoder().encode(process.env.JWT_SECRET as string);
    const missingOpenId = await new SignJWT({ appId: "binario-app" })
      .setProtectedHeader({ alg: "HS256", typ: "JWT" })
      .setExpirationTime("1h")
      .sign(secret);
    const missingAppId = await new SignJWT({ openId: "user-without-app" })
      .setProtectedHeader({ alg: "HS256", typ: "JWT" })
      .setExpirationTime("1h")
      .sign(secret);

    expect(await sdk.verifySession(missingOpenId)).toBeNull();
    expect(await sdk.verifySession(missingAppId)).toBeNull();
  });

  it("non trasforma una sessione con display name vuoto in UNAUTHORIZED", async () => {
    const token = await sdk.createSessionToken("empty-name-user", { name: "" });
    const now = new Date();
    const user = {
      id: 7,
      openId: "empty-name-user",
      email: null,
      name: null,
      loginMethod: "manus",
      role: "user" as const,
      createdAt: now,
      updatedAt: now,
      lastSignedIn: now,
    };
    const userLookup = vi.spyOn(db, "getUserByOpenId").mockResolvedValueOnce(user);
    const touch = vi.spyOn(db, "touchExistingUserSession").mockResolvedValueOnce(true);
    const request = {
      headers: { cookie: `${COOKIE_NAME}=${token}` },
    } as Request;

    await expect(sdk.authenticateRequest(request)).resolves.toEqual(user);
    expect(userLookup).toHaveBeenCalledWith("empty-name-user");
    expect(touch).toHaveBeenCalledWith(7);
    userLookup.mockRestore();
    touch.mockRestore();
  });

  it("una sessione precedente all'erasure non ricrea automaticamente l'utente", async () => {
    const token = await sdk.createSessionToken("deleted-user", { name: "Utente" });
    const userLookup = vi.spyOn(db, "getUserByOpenId").mockResolvedValueOnce(undefined);
    const upstreamLookup = vi.spyOn(sdk, "getUserInfoWithJwt");
    const request = {
      headers: { cookie: `${COOKIE_NAME}=${token}` },
    } as Request;

    await expect(sdk.authenticateRequest(request)).rejects.toThrow("User not found");
    expect(userLookup).toHaveBeenCalledWith("deleted-user");
    expect(upstreamLookup).not.toHaveBeenCalled();
    userLookup.mockRestore();
    upstreamLookup.mockRestore();
  });

  it("fallisce chiuso se l'account viene eliminato fra lookup e touch sessione", async () => {
    const token = await sdk.createSessionToken("racing-user", { name: "Utente" });
    const now = new Date();
    const userLookup = vi.spyOn(db, "getUserByOpenId").mockResolvedValueOnce({
      id: 9,
      openId: "racing-user",
      email: null,
      name: "Utente",
      loginMethod: "manus",
      role: "user",
      createdAt: now,
      updatedAt: now,
      lastSignedIn: now,
    });
    const touch = vi.spyOn(db, "touchExistingUserSession").mockResolvedValueOnce(false);
    const request = {
      headers: { cookie: `${COOKIE_NAME}=${token}` },
    } as Request;

    await expect(sdk.authenticateRequest(request)).rejects.toThrow(
      "User deleted during authentication"
    );
    expect(touch).toHaveBeenCalledWith(9);
    userLookup.mockRestore();
    touch.mockRestore();
  });

  it("segnala segreti troppo corti e variabili mancanti", () => {
    const enabledAlexa = {
      alexaEnabled: "true",
      alexaSkillId: "skill",
      alexaOAuthClientId: "client",
      alexaOAuthClientSecret: "secret",
      voiceTokenPepper: "pepper",
      alexaOAuthRedirectUris: ["https://layla.amazon.com/api/skill/link/test"],
    } as const;
    const dashboard = { oAuthPortalUrl: "https://portal.example" } as const;
    expect(
      environmentProblems({
        cookieSecret: "short",
        appId: "",
        oAuthServerUrl: "",
        databaseUrl: "",
        ...dashboard,
        ...enabledAlexa,
      })
    ).toHaveLength(4);
    expect(
      environmentProblems({
        cookieSecret: "x".repeat(32),
        appId: "app",
        oAuthServerUrl: "https://oauth.example",
        databaseUrl: "mysql://db",
        ...dashboard,
        alexaEnabled: "false",
        alexaSkillId: "",
        alexaOAuthClientId: "",
        alexaOAuthClientSecret: "",
        voiceTokenPepper: "",
        alexaOAuthRedirectUris: [],
      })
    ).toEqual([]);
    expect(
      environmentProblems({
        cookieSecret: "x".repeat(32),
        appId: "app",
        oAuthServerUrl: "https://oauth.example",
        databaseUrl: "mysql://db",
        ...dashboard,
        alexaEnabled: "true",
        alexaSkillId: "",
        alexaOAuthClientId: "",
        alexaOAuthClientSecret: "",
        voiceTokenPepper: "",
        alexaOAuthRedirectUris: [],
      })
    ).toContain("ALEXA_SKILL_ID non configurato");
    expect(
      environmentProblems({
        cookieSecret: "x".repeat(32),
        appId: "app",
        oAuthServerUrl: "https://oauth.example",
        databaseUrl: "mysql://db",
        ...dashboard,
        alexaEnabled: "false",
        alexaSkillId: "skill",
        alexaOAuthClientId: "",
        alexaOAuthClientSecret: "",
        voiceTokenPepper: "",
        alexaOAuthRedirectUris: [],
      })
    ).toContain("ALEXA_ENABLED=false non ammette configurazione Alexa residua");
  });

  it("rifiuta endpoint OAuth non HTTPS e URL database non mysql", () => {
    const disabledAlexa = {
      alexaEnabled: "false",
      alexaSkillId: "",
      alexaOAuthClientId: "",
      alexaOAuthClientSecret: "",
      voiceTokenPepper: "",
      alexaOAuthRedirectUris: [],
    } as const;
    expect(
      environmentProblems({
        cookieSecret: "x".repeat(32),
        appId: "app",
        oAuthServerUrl: "http://oauth.example",
        oAuthPortalUrl: "http://portal.example",
        databaseUrl: "postgres://db",
        ...disabledAlexa,
      })
    ).toEqual(
      expect.arrayContaining([
        "OAUTH_SERVER_URL deve essere un URL HTTPS valido",
        "VITE_OAUTH_PORTAL_URL deve essere un URL HTTPS valido",
        "DATABASE_URL deve essere un URL mysql valido",
      ])
    );
  });
});

describe("durata delle sessioni dashboard", () => {
  const HOUR = 60 * 60 * 1000;
  const DAY = 24 * HOUR;
  const secret = () => new TextEncoder().encode(process.env.JWT_SECRET as string);
  const seconds = (ms: number) => Math.floor(ms / 1000);
  async function token(claims: { iat?: number; exp: number; auth_time?: number }) {
    const jwt = new SignJWT({
      openId: "user-1",
      appId: "binario-app",
      name: "Utente",
      ...(claims.auth_time !== undefined ? { auth_time: seconds(claims.auth_time) } : {}),
    })
      .setProtectedHeader({ alg: "HS256", typ: "JWT" })
      .setExpirationTime(seconds(claims.exp));
    if (claims.iat !== undefined) jwt.setIssuedAt(seconds(claims.iat));
    return jwt.sign(secret());
  }
  const payload = (jwt: string) => JSON.parse(Buffer.from(jwt.split(".")[1], "base64url").toString()) as Record<string, number>;

  it("emette sessioni di 24 ore con l'ora del login", async () => {
    const now = Date.now();
    const claims = payload(await sdk.createSessionToken("user-1", { name: "Utente", expiresInMs: SESSION_TTL_MS }));
    expect(SESSION_TTL_MS).toBe(DAY);
    expect(claims.exp * 1000 - now).toBeGreaterThan(DAY - 5_000);
    expect(claims.exp * 1000 - now).toBeLessThanOrEqual(DAY);
    expect(Math.abs(claims.auth_time * 1000 - now)).toBeLessThan(5_000);
  });

  it("rifiuta i cookie emessi con la vecchia durata di 30 giorni e le sessioni oltre la vita massima", async () => {
    const now = Date.now();
    expect(await sdk.verifySession(await token({ exp: now + 29 * DAY }))).toBeNull();
    expect(await sdk.verifySession(await token({ iat: now - 2 * HOUR, exp: now + HOUR, auth_time: now - 8 * DAY }))).toBeNull();
    expect(await sdk.verifySession(await token({ iat: now - 2 * HOUR, exp: now + HOUR, auth_time: now - 6 * DAY }))).toMatchObject({ openId: "user-1" });
  });

  it("fa scorrere una sessione usata, al massimo una volta l'ora e mai oltre i sette giorni dal login", async () => {
    const now = Date.now();
    expect(await sdk.renewSession(await sdk.createSessionToken("user-1", { name: "Utente" }))).toBeNull();
    // Sessions from before this policy carry no login time and simply expire.
    expect(await sdk.renewSession(await token({ iat: now - 2 * HOUR, exp: now + HOUR }))).toBeNull();

    const renewed = await sdk.renewSession(await token({ iat: now - 2 * HOUR, exp: now + HOUR, auth_time: now - 2 * DAY }));
    expect(renewed?.maxAgeMs).toBe(SESSION_TTL_MS);
    const claims = payload(renewed!.token);
    expect(claims.auth_time).toBe(seconds(now - 2 * DAY));
    expect(claims.exp * 1000 - now).toBeGreaterThan(DAY - 5_000);

    const nearCap = await sdk.renewSession(await token({ iat: now - 2 * HOUR, exp: now + HOUR, auth_time: now - (7 * DAY - 3 * HOUR) }));
    expect(nearCap!.maxAgeMs).toBeLessThanOrEqual(3 * HOUR);
    expect(payload(nearCap!.token).exp * 1000).toBeLessThanOrEqual(now - (7 * DAY - 3 * HOUR) + 7 * DAY + 1_000);
  });

  it("auth.me rinnova il cookie __Host- di un utente autenticato", async () => {
    const now = Date.now();
    const cookie = vi.fn();
    const ctx = {
      user: { id: 7, openId: "user-1", name: "Utente" },
      req: { protocol: "https", headers: { cookie: `${COOKIE_NAME}=${await token({ iat: now - 2 * HOUR, exp: now + HOUR, auth_time: now - DAY })}` } },
      res: { cookie },
    } as unknown as TrpcContext;
    await expect(appRouter.createCaller(ctx).auth.me()).resolves.toMatchObject({ id: 7 });
    expect(cookie).toHaveBeenCalledWith(
      COOKIE_NAME,
      expect.any(String),
      expect.objectContaining({ httpOnly: true, secure: true, path: "/", maxAge: SESSION_TTL_MS })
    );
    cookie.mockClear();
    await appRouter.createCaller({ ...ctx, user: null }).auth.me();
    expect(cookie).not.toHaveBeenCalled();
  });
});
