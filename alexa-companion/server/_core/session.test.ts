import { describe, expect, it, vi } from "vitest";
import { SignJWT } from "jose";
import type { Request } from "express";
import { COOKIE_NAME } from "../../shared/const";

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
