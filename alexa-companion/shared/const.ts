// The `__Host-` prefix makes the browser accept the dashboard session only as
// a host-only, Secure, Path=/ cookie: a sibling site under the same registrable
// domain (for example another *.manus.space app) cannot toss or fixate it.
export const COOKIE_NAME = "__Host-app_session_id";
// Pre-prefix name. It is never read again; logout and login clear it so a stale
// or tossed copy cannot linger in the browser.
export const LEGACY_COOKIE_NAME = "app_session_id";
// Upper bound for procedures in one batched tRPC HTTP request (client and server).
export const TRPC_MAX_BATCH_SIZE = 10;
export const ONE_YEAR_MS = 1000 * 60 * 60 * 24 * 365;
// Dashboard sessions are not revocable server-side, so they must stay short.
export const SESSION_TTL_MS = 1000 * 60 * 60 * 24 * 30;
export const AXIOS_TIMEOUT_MS = 30_000;
export const UNAUTHED_ERR_MSG = 'Please login (10001)';
export const NOT_ADMIN_ERR_MSG = 'You do not have required permission (10002)';

// This transport-level confirmation is intentionally locale-independent. The
// dashboard localizes the visible action but always sends this exact value, so
// translating UI text can never weaken the destructive-action guard.
export const ACCOUNT_DELETION_CONFIRMATION = "ELIMINA" as const;

// One-time nonce cookie that binds an OAuth login to the browser that started
// it. The `__Host-` prefix forces the cookie host-only (Secure, Path=/, no
// Domain), so a sibling *.manus.space site cannot plant a matching value in a
// victim's browser.
export const OAUTH_STATE_COOKIE = "__Host-oauth_state";

// `state` carries the callback redirect URI (used at token exchange) plus the
// CSRF nonce. Defined here so the client encoder and server decoder never drift.
export type OAuthState = { redirectUri: string; nonce?: string };

export const encodeOAuthState = (state: OAuthState): string =>
  btoa(JSON.stringify(state));

export const decodeOAuthState = (state: string): OAuthState => {
  let decoded: string;
  try {
    decoded = atob(state);
  } catch {
    // Malformed base64 (e.g. attacker-supplied garbage). Return no nonce so the
    // callback's CSRF guard rejects it with 403 — never throw, since the caller
    // runs outside the request handler's try/catch.
    return { redirectUri: "" };
  }
  try {
    const parsed = JSON.parse(decoded);
    if (parsed && typeof parsed.redirectUri === "string") return parsed;
  } catch {
    // Legacy links: `state` was a bare base64(redirectUri) with no nonce.
  }
  return { redirectUri: decoded };
};
