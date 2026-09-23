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
    expect(await sdk.verifySession(own)).toMatchObject({ openId: "user-1", appId: "binario-app" });

    const secret = new TextEncoder().encode(process.env.JWT_SECRET as string);
    const foreign = await new SignJWT({ openId: "user-1", appId: "other-app", name: "Utente" }).setProtectedHeader({ alg: "HS256", typ: "JWT" }).setExpirationTime("1h").sign(secret);
    expect(await sdk.verifySession(foreign)).toBeNull();
  });

  it("segnala segreti troppo corti e variabili mancanti", () => {
    expect(environmentProblems({ cookieSecret: "short", appId: "", oAuthServerUrl: "" })).toHaveLength(3);
    expect(environmentProblems({ cookieSecret: "x".repeat(32), appId: "app", oAuthServerUrl: "https://oauth.example" })).toEqual([]);
  });
});
