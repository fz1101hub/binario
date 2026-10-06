import express from "express";
import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const REDIRECT = "https://layla.amazon.com/api/skill/link/M1TEST";

vi.hoisted(() => {
  Object.assign(process.env, {
    JWT_SECRET: "session-secret-with-at-least-32-characters!!",
    VITE_APP_ID: "binario-app",
    OAUTH_SERVER_URL: "https://oauth.example",
    DATABASE_URL: "mysql://test",
    ALEXA_ENABLED: "true",
    ALEXA_SKILL_ID: "amzn1.ask.skill.test",
    ALEXA_OAUTH_CLIENT_ID: "alexa-client",
    ALEXA_OAUTH_CLIENT_SECRET: "alexa-secret",
    ALEXA_OAUTH_REDIRECT_URIS: "https://layla.amazon.com/api/skill/link/M1TEST",
    VOICE_TOKEN_PEPPER: "test-pepper-with-plenty-of-entropy-0123456789",
  });
});

const dbMocks = vi.hoisted(() => ({ upsertUser: vi.fn() }));
const sdkMocks = vi.hoisted(() => ({
  exchangeCodeForToken: vi.fn(),
  getUserInfo: vi.fn(),
  createSessionToken: vi.fn(),
}));

vi.mock("../db", () => dbMocks);
vi.mock("./sdk", () => ({ sdk: sdkMocks }));

import { encodeOAuthState, OAUTH_STATE_COOKIE } from "@shared/const";
import { registerOAuthRoutes } from "./oauth";
import {
  AUTHORIZATION_REQUEST_COOKIE,
  signAuthorizationRequest,
} from "../voice/consent";

let server: Server | undefined;
let baseUrl = "";

beforeEach(async () => {
  vi.clearAllMocks();
  sdkMocks.exchangeCodeForToken.mockResolvedValue({
    accessToken: "dashboard-token",
  });
  sdkMocks.getUserInfo.mockResolvedValue({
    openId: "dashboard-user",
    name: "Dashboard User",
  });
  sdkMocks.createSessionToken.mockResolvedValue("dashboard-session");
  const app = express();
  registerOAuthRoutes(app);
  server = app.listen(0);
  await new Promise<void>(resolve => server!.once("listening", resolve));
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterEach(async () => {
  if (server)
    await new Promise<void>(resolve => server!.close(() => resolve()));
  server = undefined;
});

function callbackUrl() {
  const state = encodeOAuthState({
    redirectUri: `${baseUrl}/api/oauth/callback`,
    nonce: "dashboard-nonce",
  });
  return `${baseUrl}/api/oauth/callback?code=dashboard-code&state=${encodeURIComponent(state)}`;
}

describe("callback dashboard OAuth", () => {
  it("emette una sessione quando il display name e vuoto", async () => {
    sdkMocks.getUserInfo.mockResolvedValueOnce({
      openId: "empty-name-user",
      name: "",
    });

    const response = await fetch(callbackUrl(), {
      redirect: "manual",
      headers: { cookie: `${OAUTH_STATE_COOKIE}=dashboard-nonce` },
    });

    expect(response.status).toBe(302);
    expect(response.headers.get("location")).toBe("/");
    expect(dbMocks.upsertUser).toHaveBeenCalledWith(
      expect.objectContaining({ openId: "empty-name-user", name: null })
    );
    expect(sdkMocks.createSessionToken).toHaveBeenCalledWith("empty-name-user", {
      name: "",
      expiresInMs: 1000 * 60 * 60 * 24 * 30,
    });
    const setCookie = response.headers.get("set-cookie") ?? "";
    expect(setCookie).toContain("__Host-app_session_id=dashboard-session");
    expect(setCookie).toMatch(
      /__Host-app_session_id=dashboard-session; Max-Age=\d+; Path=\/; Expires=[^;]+; HttpOnly; Secure; SameSite=Lax/
    );
    expect(setCookie).not.toContain("Domain=");
    // The pre-prefix cookie is expired so a stale or tossed copy cannot linger.
    expect(setCookie).toMatch(/(^|,\s*)app_session_id=;/);
  });

  it("riprende una richiesta Alexa firmata una sola volta e la rivalida", async () => {
    const request = signAuthorizationRequest({
      clientId: "alexa-client",
      redirectUri: REDIRECT,
      state: "amazon-state",
      scope: "routes.read",
    });
    const response = await fetch(callbackUrl(), {
      redirect: "manual",
      headers: {
        cookie: `${OAUTH_STATE_COOKIE}=dashboard-nonce; ${AUTHORIZATION_REQUEST_COOKIE}=${request}`,
      },
    });

    expect(response.status).toBe(302);
    const resumed = new URL(response.headers.get("location") ?? "", baseUrl);
    expect(resumed.pathname).toBe("/api/alexa/oauth/authorize");
    expect(resumed.searchParams.get("client_id")).toBe("alexa-client");
    expect(resumed.searchParams.get("redirect_uri")).toBe(REDIRECT);
    expect(resumed.searchParams.get("state")).toBe("amazon-state");
    expect(response.headers.get("set-cookie")).toContain(
      `${AUTHORIZATION_REQUEST_COOKIE}=;`
    );
    expect(response.headers.get("set-cookie")).toContain("Path=/");
    expect(response.headers.get("set-cookie")).toContain("Secure");
    expect(dbMocks.upsertUser).toHaveBeenCalledWith(
      expect.objectContaining({ openId: "dashboard-user" })
    );
  });

  it("non riprende un cookie firmato non valido", async () => {
    const response = await fetch(callbackUrl(), {
      redirect: "manual",
      headers: {
        cookie: `${OAUTH_STATE_COOKIE}=dashboard-nonce; ${AUTHORIZATION_REQUEST_COOKIE}=tampered`,
      },
    });

    expect(response.status).toBe(302);
    expect(response.headers.get("location")).toBe("/");
  });

  it("rivalida client e callback del cookie firmato e lo consuma anche con state dashboard non valido", async () => {
    const foreignRequest = signAuthorizationRequest({
      clientId: "other-client",
      redirectUri: REDIRECT,
      state: "amazon-state",
      scope: "routes.read",
    });
    const foreign = await fetch(callbackUrl(), {
      redirect: "manual",
      headers: {
        cookie: `${OAUTH_STATE_COOKIE}=dashboard-nonce; ${AUTHORIZATION_REQUEST_COOKIE}=${foreignRequest}`,
      },
    });
    expect(foreign.status).toBe(302);
    expect(foreign.headers.get("location")).toBe("/");
    expect(foreign.headers.get("set-cookie")).toContain(
      `${AUTHORIZATION_REQUEST_COOKIE}=;`
    );

    const invalidState = await fetch(
      `${baseUrl}/api/oauth/callback?code=dashboard-code&state=not-base64`,
      {
        redirect: "manual",
        headers: {
          cookie: `${AUTHORIZATION_REQUEST_COOKIE}=${foreignRequest}`,
        },
      }
    );
    expect(invalidState.status).toBe(403);
    expect(invalidState.headers.get("set-cookie")).toContain(
      `${AUTHORIZATION_REQUEST_COOKIE}=;`
    );
  });
});
