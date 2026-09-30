import { createHmac, hkdfSync } from "node:crypto";
import {
  formatMessage,
  messagesForLocale,
  type SupportedLocale,
} from "@shared/i18n";
import { ENV } from "../_core/env";
import { createOpaqueSecret, secureStringEquals } from "./crypto";

export const CONSENT_COOKIE = "alexa_consent";
export const CONSENT_COOKIE_PATH = "/api/alexa/oauth";
export const CONSENT_TTL_MS = 10 * 60 * 1000;
// This is deliberately separate from the CSRF nonce. It keeps a *validated*
// Amazon authorization request across an interactive dashboard login. The
// __Host- prefix prevents a sibling subdomain from tossing a cookie with the
// same name; callers must therefore use Secure, Path=/ and no Domain.
export const AUTHORIZATION_REQUEST_COOKIE =
  "__Host-alexa_authorization_request";
export const AUTHORIZATION_REQUEST_COOKIE_PATH = "/";
export const AUTHORIZATION_REQUEST_TTL_MS = 10 * 60 * 1000;

function consentKey() {
  if (!ENV.voiceTokenPepper)
    throw new Error("Integrazione Alexa non configurata");
  return Buffer.from(
    hkdfSync("sha256", ENV.voiceTokenPepper, "", "binario-alexa-consent", 32)
  );
}

export function createConsentNonce() {
  return createOpaqueSecret();
}

// The form token binds the cookie nonce to the authenticated user, so a nonce
// planted by another origin cannot be replayed on behalf of someone else.
export function consentToken(nonce: string, userId: number) {
  return createHmac("sha256", consentKey())
    .update(`${userId}:${nonce}`)
    .digest("hex");
}

export function verifyConsentToken(
  nonce: string | undefined,
  userId: number,
  token: unknown
) {
  if (!nonce || typeof token !== "string" || !token) return false;
  return secureStringEquals(consentToken(nonce, userId), token);
}

export type StoredAuthorizationRequest = {
  clientId: string;
  redirectUri: string;
  state: string;
  scope: string;
};

type SignedAuthorizationRequest = StoredAuthorizationRequest & {
  expiresAt: number;
  version: 1;
};

function authorizationRequestSignature(encodedPayload: string) {
  return createHmac("sha256", consentKey())
    .update(`binario-alexa-authorization:${encodedPayload}`)
    .digest("base64url");
}

/**
 * The request is public OAuth metadata, but it must be tamper-evident: the
 * callback resumes only the exact client, redirect URI and state that passed
 * the allowlist before dashboard login began.
 */
export function signAuthorizationRequest(
  request: StoredAuthorizationRequest,
  now = Date.now()
) {
  const payload: SignedAuthorizationRequest = {
    ...request,
    version: 1,
    expiresAt: now + AUTHORIZATION_REQUEST_TTL_MS,
  };
  const encodedPayload = Buffer.from(JSON.stringify(payload)).toString(
    "base64url"
  );
  return `${encodedPayload}.${authorizationRequestSignature(encodedPayload)}`;
}

/** Returns only a signed, unexpired request. Semantic OAuth validation happens again on resume. */
export function readAuthorizationRequest(
  value: string | undefined,
  now = Date.now()
): StoredAuthorizationRequest | null {
  if (!value) return null;
  const [encodedPayload, signature, ...rest] = value.split(".");
  if (
    !encodedPayload ||
    !signature ||
    rest.length ||
    !secureStringEquals(
      authorizationRequestSignature(encodedPayload),
      signature
    )
  )
    return null;

  try {
    const payload = JSON.parse(
      Buffer.from(encodedPayload, "base64url").toString("utf8")
    ) as Partial<SignedAuthorizationRequest>;
    if (
      payload.version !== 1 ||
      typeof payload.expiresAt !== "number" ||
      !Number.isSafeInteger(payload.expiresAt) ||
      payload.expiresAt <= now ||
      typeof payload.clientId !== "string" ||
      typeof payload.redirectUri !== "string" ||
      typeof payload.state !== "string" ||
      typeof payload.scope !== "string"
    )
      return null;
    return {
      clientId: payload.clientId,
      redirectUri: payload.redirectUri,
      state: payload.state,
      scope: payload.scope,
    };
  } catch {
    return null;
  }
}

export function escapeHtml(value: string) {
  return value.replace(/[&<>"']/g, character => {
    switch (character) {
      case "&":
        return "&amp;";
      case "<":
        return "&lt;";
      case ">":
        return "&gt;";
      case '"':
        return "&quot;";
      default:
        return "&#39;";
    }
  });
}

export type ConsentPageInput = {
  locale: SupportedLocale;
  userName: string;
  clientId: string;
  redirectUri: string;
  state: string;
  scope: string;
  token: string;
};

export type ConsentErrorKind =
  | "notConfigured"
  | "invalidRequest"
  | "invalidCsrf"
  | "loginRequired";

function pageStyles() {
  return `<style>
body{font-family:system-ui,sans-serif;max-width:32rem;margin:3rem auto;padding:0 1rem;line-height:1.5;color:#1b1b1b}
button,.button{font:inherit;display:inline-block;padding:.75rem 1.25rem;border-radius:.5rem;border:1px solid #1b1b1b;background:#fff;color:#1b1b1b;cursor:pointer;text-decoration:none}
button.primary{background:#1b1b1b;color:#fff}
form{display:flex;gap:1rem;margin-top:1.5rem}
</style>`;
}

export function renderConsentErrorPage(
  locale: SupportedLocale,
  kind: ConsentErrorKind
) {
  const messages = messagesForLocale(locale).consent;
  return `<!doctype html>
<html lang="${locale}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${messages.errorTitle}</title>
${pageStyles()}
</head>
<body>
<h1>${messages.errorTitle}</h1>
<p>${messages[kind]}</p>
<a class="button" href="/">${messages.returnDashboard}</a>
</body>
</html>
`;
}

export function renderConsentPage(input: ConsentPageInput) {
  const messages = messagesForLocale(input.locale).consent;
  const hidden = (name: string, value: string) =>
    `<input type="hidden" name="${name}" value="${escapeHtml(value)}">`;
  const who = input.userName
    ? formatMessage(messages.signedInAs, {
        userName: escapeHtml(input.userName),
      })
    : "";
  const description = formatMessage(messages.description, { signedInAs: who });
  return `<!doctype html>
<html lang="${input.locale}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${messages.title}</title>
${pageStyles()}
</head>
<body>
<h1>${messages.title}</h1>
<p>${description}</p>
<form method="post" action="/api/alexa/oauth/authorize">
${hidden("response_type", "code")}
${hidden("client_id", input.clientId)}
${hidden("redirect_uri", input.redirectUri)}
${hidden("state", input.state)}
${hidden("scope", input.scope)}
${hidden("csrf_token", input.token)}
<button class="primary" type="submit" name="decision" value="allow">${messages.allow}</button>
<button type="submit" name="decision" value="deny">${messages.deny}</button>
</form>
</body>
</html>
`;
}
