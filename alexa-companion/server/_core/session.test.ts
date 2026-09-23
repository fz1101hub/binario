import { describe, expect, it, vi } from "vitest";
import { SignJWT } from "jose";

vi.hoisted(() => {
  Object.assign(process.env, {
    JWT_SECRET: "session-secret-with-at-least-32-characters!!",
    VITE_APP_ID: "binario-app",
    OAUTH_SERVER_URL: "https://oauth.example",
  });
});

import { sdk } from "./sdk";
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
