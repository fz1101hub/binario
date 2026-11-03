import {
  AXIOS_TIMEOUT_MS,
  COOKIE_NAME,
  SESSION_MAX_LIFETIME_MS,
  SESSION_RENEW_AFTER_MS,
  SESSION_TTL_MS,
  decodeOAuthState,
} from "@shared/const";
import { ForbiddenError } from "@shared/_core/errors";
import axios, { type AxiosInstance } from "axios";
import { parse as parseCookieHeader } from "cookie";
import type { Request, Response } from "express";
import { SignJWT, jwtVerify } from "jose";
import type { User } from "../../drizzle/schema";
import * as db from "../db";
import { getSessionCookieOptions } from "./cookies";
import { ENV } from "./env";
import type {
  ExchangeTokenRequest,
  ExchangeTokenResponse,
  GetUserInfoResponse,
  GetUserInfoWithJwtRequest,
  GetUserInfoWithJwtResponse,
} from "./types/manusTypes";
// Utility function
const isNonEmptyString = (value: unknown): value is string =>
  typeof value === "string" && value.length > 0;

export type SessionPayload = {
  openId: string;
  appId: string;
  name?: string;
};

type SessionClaims = {
  openId: string;
  appId: string;
  name: string;
  issuedAtMs?: number;
  /** When the login that started this chain of renewals happened. */
  authTimeMs?: number;
};

// Tolerance for clocks when comparing a token's expiry with the session policy.
const SESSION_CLOCK_SKEW_MS = 5 * 60 * 1000;

const EXCHANGE_TOKEN_PATH = `/webdev.v1.WebDevAuthPublicService/ExchangeToken`;
const GET_USER_INFO_PATH = `/webdev.v1.WebDevAuthPublicService/GetUserInfo`;
const GET_USER_INFO_WITH_JWT_PATH = `/webdev.v1.WebDevAuthPublicService/GetUserInfoWithJwt`;

class OAuthService {
  constructor(private client: ReturnType<typeof axios.create>) {
    console.log("[OAuth] Initialized with baseURL:", ENV.oAuthServerUrl);
    if (!ENV.oAuthServerUrl) {
      console.error(
        "[OAuth] ERROR: OAUTH_SERVER_URL is not configured! Set OAUTH_SERVER_URL environment variable."
      );
    }
  }

  private decodeState(state: string): string {
    return decodeOAuthState(state).redirectUri;
  }

  async getTokenByCode(
    code: string,
    state: string
  ): Promise<ExchangeTokenResponse> {
    const payload: ExchangeTokenRequest = {
      clientId: ENV.appId,
      grantType: "authorization_code",
      code,
      redirectUri: this.decodeState(state),
    };

    const { data } = await this.client.post<ExchangeTokenResponse>(
      EXCHANGE_TOKEN_PATH,
      payload
    );

    return data;
  }

  async getUserInfoByToken(
    token: ExchangeTokenResponse
  ): Promise<GetUserInfoResponse> {
    const { data } = await this.client.post<GetUserInfoResponse>(
      GET_USER_INFO_PATH,
      {
        accessToken: token.accessToken,
      }
    );

    return data;
  }
}

const createOAuthHttpClient = (): AxiosInstance =>
  axios.create({
    baseURL: ENV.oAuthServerUrl,
    timeout: AXIOS_TIMEOUT_MS,
  });

class SDKServer {
  private readonly client: AxiosInstance;
  private readonly oauthService: OAuthService;

  constructor(client: AxiosInstance = createOAuthHttpClient()) {
    this.client = client;
    this.oauthService = new OAuthService(this.client);
  }

  private deriveLoginMethod(
    platforms: unknown,
    fallback: string | null | undefined
  ): string | null {
    if (fallback && fallback.length > 0) return fallback;
    if (!Array.isArray(platforms) || platforms.length === 0) return null;
    const set = new Set<string>(
      platforms.filter((p): p is string => typeof p === "string")
    );
    if (set.has("REGISTERED_PLATFORM_EMAIL")) return "email";
    if (set.has("REGISTERED_PLATFORM_GOOGLE")) return "google";
    if (set.has("REGISTERED_PLATFORM_APPLE")) return "apple";
    if (
      set.has("REGISTERED_PLATFORM_MICROSOFT") ||
      set.has("REGISTERED_PLATFORM_AZURE")
    )
      return "microsoft";
    if (set.has("REGISTERED_PLATFORM_GITHUB")) return "github";
    const first = Array.from(set)[0];
    return first ? first.toLowerCase() : null;
  }

  /**
   * Exchange OAuth authorization code for access token
   * @example
   * const tokenResponse = await sdk.exchangeCodeForToken(code, state);
   */
  async exchangeCodeForToken(
    code: string,
    state: string
  ): Promise<ExchangeTokenResponse> {
    return this.oauthService.getTokenByCode(code, state);
  }

  /**
   * Get user information using access token
   * @example
   * const userInfo = await sdk.getUserInfo(tokenResponse.accessToken);
   */
  async getUserInfo(accessToken: string): Promise<GetUserInfoResponse> {
    const data = await this.oauthService.getUserInfoByToken({
      accessToken,
    } as ExchangeTokenResponse);
    const loginMethod = this.deriveLoginMethod(
      (data as any)?.platforms,
      (data as any)?.platform ?? data.platform ?? null
    );
    return {
      ...(data as any),
      platform: loginMethod,
      loginMethod,
    } as GetUserInfoResponse;
  }

  private parseCookies(cookieHeader: string | undefined) {
    if (!cookieHeader) {
      return new Map<string, string>();
    }

    const parsed = parseCookieHeader(cookieHeader);
    return new Map(Object.entries(parsed));
  }

  private getSessionSecret() {
    const secret = ENV.cookieSecret;
    return new TextEncoder().encode(secret);
  }

  /**
   * Create a session token for a Manus user openId
   * @example
   * const sessionToken = await sdk.createSessionToken(userInfo.openId);
   */
  async createSessionToken(
    openId: string,
    options: { expiresInMs?: number; name?: string } = {}
  ): Promise<string> {
    return this.signSession(
      {
        openId,
        appId: ENV.appId,
        name: options.name || "",
      },
      options
    );
  }

  async signSession(
    payload: SessionPayload,
    options: { expiresInMs?: number; authTimeMs?: number } = {}
  ): Promise<string> {
    const issuedAt = Date.now();
    const authTime = options.authTimeMs ?? issuedAt;
    const expiresInMs = options.expiresInMs ?? SESSION_TTL_MS;
    // A renewed session never outlives the absolute lifetime of its login.
    const expiresAt = Math.min(issuedAt + expiresInMs, authTime + SESSION_MAX_LIFETIME_MS);
    const secretKey = this.getSessionSecret();

    return new SignJWT({
      openId: payload.openId,
      appId: payload.appId,
      name: payload.name,
      auth_time: Math.floor(authTime / 1000),
    })
      .setProtectedHeader({ alg: "HS256", typ: "JWT" })
      .setIssuedAt(Math.floor(issuedAt / 1000))
      .setExpirationTime(Math.floor(expiresAt / 1000))
      .sign(secretKey);
  }

  async verifySession(
    cookieValue: string | undefined | null
  ): Promise<{ openId: string; appId: string; name: string } | null> {
    const claims = await this.verifySessionClaims(cookieValue);
    return claims ? { openId: claims.openId, appId: claims.appId, name: claims.name } : null;
  }

  /**
   * A fresh token for a session last renewed more than SESSION_RENEW_AFTER_MS ago, keeping the
   * original login time; null when no renewal is due or allowed (cron or pre-policy sessions).
   */
  async renewSession(
    cookieValue: string | undefined | null
  ): Promise<{ token: string; maxAgeMs: number } | null> {
    const claims = await this.verifySessionClaims(cookieValue);
    if (!claims || claims.issuedAtMs === undefined || claims.authTimeMs === undefined) return null;
    if (claims.openId.startsWith(CRON_OPEN_ID_PREFIX)) return null;
    const now = Date.now();
    if (now - claims.issuedAtMs < SESSION_RENEW_AFTER_MS) return null;
    const remaining = claims.authTimeMs + SESSION_MAX_LIFETIME_MS - now;
    if (remaining <= 0) return null;
    const token = await this.signSession(
      { openId: claims.openId, appId: claims.appId, name: claims.name },
      { expiresInMs: SESSION_TTL_MS, authTimeMs: claims.authTimeMs }
    );
    return { token, maxAgeMs: Math.min(SESSION_TTL_MS, remaining) };
  }

  /** Slides the dashboard session cookie forward (called by auth.me for a signed-in user). */
  async renewSessionCookie(req: Request, res: Pick<Response, "cookie">) {
    const renewed = await this.renewSession(this.parseCookies(req.headers.cookie).get(COOKIE_NAME));
    if (renewed)
      res.cookie(COOKIE_NAME, renewed.token, {
        ...getSessionCookieOptions(req),
        maxAge: renewed.maxAgeMs,
      });
  }

  private async verifySessionClaims(
    cookieValue: string | undefined | null
  ): Promise<SessionClaims | null> {
    if (!cookieValue) {
      console.warn("[Auth] Missing session cookie");
      return null;
    }

    try {
      const secretKey = this.getSessionSecret();
      const { payload } = await jwtVerify(cookieValue, secretKey, {
        algorithms: ["HS256"],
      });
      const { openId, appId, name, exp, iat, auth_time: authTime } = payload as Record<string, unknown>;

      if (
        !isNonEmptyString(openId) ||
        !isNonEmptyString(appId) ||
        (name !== undefined && typeof name !== "string")
      ) {
        console.warn("[Auth] Session payload missing required fields");
        return null;
      }

      if (!openId.startsWith(CRON_OPEN_ID_PREFIX)) {
        // Cookies minted under a longer lifetime (the former 30 days) end at this policy.
        if (typeof exp !== "number" || exp * 1000 - Date.now() > SESSION_TTL_MS + SESSION_CLOCK_SKEW_MS) {
          console.warn("[Auth] Session lifetime exceeds the session policy");
          return null;
        }
        if (
          authTime !== undefined &&
          (typeof authTime !== "number" || Date.now() - authTime * 1000 > SESSION_MAX_LIFETIME_MS)
        ) {
          console.warn("[Auth] Session older than its absolute lifetime");
          return null;
        }
      }

      // Several apps may share one signing secret: a session minted for another
      // app is not a session for this one.
      if (appId !== ENV.appId) {
        console.warn("[Auth] Session issued for another app");
        return null;
      }

      return {
        openId,
        appId,
        // Legacy sessions may not carry a display name. Normalize that optional
        // claim so downstream authentication never mistakes it for a bad token.
        name: name ?? "",
        issuedAtMs: typeof iat === "number" ? iat * 1000 : undefined,
        authTimeMs: typeof authTime === "number" ? authTime * 1000 : undefined,
      };
    } catch (error) {
      console.warn("[Auth] Session verification failed", String(error));
      return null;
    }
  }

  async getUserInfoWithJwt(
    jwtToken: string
  ): Promise<GetUserInfoWithJwtResponse> {
    const payload: GetUserInfoWithJwtRequest = {
      jwtToken,
      projectId: ENV.appId,
    };

    const { data } = await this.client.post<GetUserInfoWithJwtResponse>(
      GET_USER_INFO_WITH_JWT_PATH,
      payload
    );

    const loginMethod = this.deriveLoginMethod(
      (data as any)?.platforms,
      (data as any)?.platform ?? data.platform ?? null
    );
    return {
      ...(data as any),
      platform: loginMethod,
      loginMethod,
    } as GetUserInfoWithJwtResponse;
  }

  async authenticateRequest(req: Request): Promise<AuthenticatedUser> {
    // The session lives only in the HttpOnly cookie: a token readable by
    // scripts (headers, storage) would widen what an XSS or a framing page
    // can do with it.
    const cookies = this.parseCookies(req.headers.cookie);
    const sessionToken = cookies.get(COOKIE_NAME);

    const session = await this.verifySession(sessionToken);

    if (!session) {
      throw ForbiddenError("Invalid session cookie");
    }

    if (session.openId.startsWith(CRON_OPEN_ID_PREFIX)) {
      const userInfo = await this.getUserInfoWithJwt(sessionToken ?? "");
      const taskUid = userInfo.taskUid ?? null;
      if (!taskUid) {
        throw ForbiddenError("Cron session missing task_uid");
      }
      return buildCronUser(userInfo);
    }

    const sessionUserId = session.openId;
    const user = await db.getUserByOpenId(sessionUserId);

    if (!user) {
      // Only the OAuth callback may provision an account. Otherwise a session
      // issued before erasure could silently recreate the deleted user.
      throw ForbiddenError("User not found");
    }

    if (!(await db.touchExistingUserSession(user.id))) {
      throw ForbiddenError("User deleted during authentication");
    }

    return user;
  }
}

const CRON_OPEN_ID_PREFIX = "cron_";

/** Result of `sdk.authenticateRequest`. Cron callbacks set `isCron=true` and `taskUid`; see `/home/ubuntu/skills/webdev-periodic-updates/SKILL.md`. */
export type AuthenticatedUser = User & {
  taskUid?: string;
  isCron?: boolean;
};

function buildCronUser(
  userInfo: GetUserInfoWithJwtResponse
): AuthenticatedUser {
  const now = new Date();
  return {
    id: -1,
    openId: userInfo.openId,
    name: userInfo.name || "Manus Scheduled Task",
    email: null,
    loginMethod: null,
    role: "user",
    createdAt: now,
    updatedAt: now,
    lastSignedIn: now,
    taskUid: userInfo.taskUid ?? undefined,
    isCron: true,
  } as AuthenticatedUser;
}

export const sdk = new SDKServer();
