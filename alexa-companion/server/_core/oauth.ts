import {
  COOKIE_NAME,
  SESSION_TTL_MS,
  OAUTH_STATE_COOKIE,
  decodeOAuthState,
} from "@shared/const";
import { parse as parseCookieHeader } from "cookie";
import type { Express, Request, Response } from "express";
import * as db from "../db";
import { getSessionCookieOptions } from "./cookies";
import { ENV } from "./env";
import { safeErrorDetails } from "./safeError";
import { sdk } from "./sdk";
import {
  AUTHORIZATION_REQUEST_COOKIE,
  AUTHORIZATION_REQUEST_COOKIE_PATH,
  readAuthorizationRequest,
} from "../voice/consent";
import { isAlexaConfigured } from "../voice/config";
import { parseOAuthAuthorizationParameters } from "../voice/oauthContract";

function getQueryParam(req: Request, key: string): string | undefined {
  const value = req.query[key];
  return typeof value === "string" ? value : undefined;
}

function authorizationRequestCookieOptions() {
  // AUTHORIZATION_REQUEST_COOKIE is __Host-prefixed, so Secure and Path=/ are
  // mandatory. Refusing the browser's HTTP cookie is safer than resuming an
  // Amazon authorization request over an unencrypted callback.
  return {
    httpOnly: true,
    path: AUTHORIZATION_REQUEST_COOKIE_PATH,
    sameSite: "lax" as const,
    secure: true,
  };
}

function resumedAlexaAuthorization(req: Request) {
  if (!isAlexaConfigured()) return null;
  const stored = readAuthorizationRequest(
    parseCookieHeader(req.headers.cookie ?? "")[AUTHORIZATION_REQUEST_COOKIE]
  );
  if (!stored) return null;
  // Re-run exact client, redirect URI and state validation after identity is
  // established; a valid signature by itself must never resume an old policy.
  return parseOAuthAuthorizationParameters(
    {
      response_type: "code",
      client_id: stored.clientId,
      redirect_uri: stored.redirectUri,
      state: stored.state,
      scope: stored.scope,
    },
    ENV.alexaOAuthClientId,
    ENV.alexaOAuthRedirectUris
  );
}

function resumeAlexaAuthorizationUrl(
  request: NonNullable<ReturnType<typeof resumedAlexaAuthorization>>
) {
  const parameters = new URLSearchParams({
    response_type: request.responseType,
    client_id: request.clientId,
    redirect_uri: request.redirectUri,
    state: request.state,
    scope: request.scope,
  });
  return `/api/alexa/oauth/authorize?${parameters.toString()}`;
}

export function registerOAuthRoutes(app: Express) {
  app.get("/api/oauth/callback", async (req: Request, res: Response) => {
    const code = getQueryParam(req, "code");
    const state = getQueryParam(req, "state");

    if (!code || !state) {
      // A failed or interrupted dashboard login must never leave a request
      // available for a later, unrelated callback.
      res.clearCookie(
        AUTHORIZATION_REQUEST_COOKIE,
        authorizationRequestCookieOptions()
      );
      res.status(400).json({ error: "code and state are required" });
      return;
    }

    // CSRF guard: the nonce in `state` must match the one-time cookie that
    // startLogin set in the browser that began this login. An attacker can
    // forge `state`, but cannot plant this cookie in the victim's browser.
    const { nonce } = decodeOAuthState(state);
    const expectedNonce = parseCookieHeader(req.headers.cookie ?? "")[
      OAUTH_STATE_COOKIE
    ];
    if (!nonce || nonce !== expectedNonce) {
      res.clearCookie(
        AUTHORIZATION_REQUEST_COOKIE,
        authorizationRequestCookieOptions()
      );
      res.status(403).json({ error: "invalid oauth state" });
      return;
    }
    res.clearCookie(OAUTH_STATE_COOKIE, {
      path: "/",
      secure: true,
      sameSite: "lax",
    });
    const alexaAuthorization = resumedAlexaAuthorization(req);
    // Consume the preserved request even if upstream OAuth fails afterwards.
    res.clearCookie(
      AUTHORIZATION_REQUEST_COOKIE,
      authorizationRequestCookieOptions()
    );

    try {
      const tokenResponse = await sdk.exchangeCodeForToken(code, state);
      const userInfo = await sdk.getUserInfo(tokenResponse.accessToken);

      if (!userInfo.openId) {
        res.status(400).json({ error: "openId missing from user info" });
        return;
      }

      await db.upsertUser({
        openId: userInfo.openId,
        name: userInfo.name || null,
        email: userInfo.email ?? null,
        loginMethod: userInfo.loginMethod ?? userInfo.platform ?? null,
        lastSignedIn: new Date(),
      });

      const sessionToken = await sdk.createSessionToken(userInfo.openId, {
        name: userInfo.name || "",
        expiresInMs: SESSION_TTL_MS,
      });

      const cookieOptions = getSessionCookieOptions(req);
      res.cookie(COOKIE_NAME, sessionToken, {
        ...cookieOptions,
        maxAge: SESSION_TTL_MS,
      });

      if (alexaAuthorization) {
        return res.redirect(
          302,
          resumeAlexaAuthorizationUrl(alexaAuthorization)
        );
      }
      res.redirect(302, "/");
    } catch (error) {
      console.error("[OAuth] Callback failed", safeErrorDetails(error));
      res.status(500).json({ error: "OAuth callback failed" });
    }
  });
}
