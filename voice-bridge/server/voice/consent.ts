import { createHmac, hkdfSync } from "node:crypto";
import { ENV } from "../_core/env";
import { createOpaqueSecret, secureStringEquals } from "./crypto";

export const CONSENT_COOKIE = "alexa_consent";
export const CONSENT_COOKIE_PATH = "/api/alexa/oauth";
export const CONSENT_TTL_MS = 10 * 60 * 1000;

function consentKey() {
  if (!ENV.voiceTokenPepper) throw new Error("Integrazione Alexa non configurata");
  return Buffer.from(hkdfSync("sha256", ENV.voiceTokenPepper, "", "binario-alexa-consent", 32));
}

export function createConsentNonce() {
  return createOpaqueSecret();
}

// The form token binds the cookie nonce to the authenticated user, so a nonce
// planted by another origin cannot be replayed on behalf of someone else.
export function consentToken(nonce: string, userId: number) {
  return createHmac("sha256", consentKey()).update(`${userId}:${nonce}`).digest("hex");
}

export function verifyConsentToken(nonce: string | undefined, userId: number, token: unknown) {
  if (!nonce || typeof token !== "string" || !token) return false;
  return secureStringEquals(consentToken(nonce, userId), token);
}

export function escapeHtml(value: string) {
  return value.replace(/[&<>"']/g, character => {
    switch (character) {
      case "&": return "&amp;";
      case "<": return "&lt;";
      case ">": return "&gt;";
      case '"': return "&quot;";
      default: return "&#39;";
    }
  });
}

export type ConsentPageInput = {
  userName: string;
  clientId: string;
  redirectUri: string;
  state: string;
  scope: string;
  token: string;
};

export function renderConsentPage(input: ConsentPageInput) {
  const hidden = (name: string, value: string) => `<input type="hidden" name="${name}" value="${escapeHtml(value)}">`;
  const who = input.userName ? ` come <strong>${escapeHtml(input.userName)}</strong>` : "";
  return `<!doctype html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Collega Alexa a Binario</title>
<style>
body{font-family:system-ui,sans-serif;max-width:32rem;margin:3rem auto;padding:0 1rem;line-height:1.5;color:#1b1b1b}
button{font:inherit;padding:.75rem 1.25rem;border-radius:.5rem;border:1px solid #1b1b1b;background:#fff;cursor:pointer}
button.primary{background:#1b1b1b;color:#fff}
form{display:flex;gap:1rem;margin-top:1.5rem}
</style>
</head>
<body>
<h1>Collega Alexa a Binario</h1>
<p>Stai per collegare Amazon Alexa al tuo account Binario${who}. Alexa potrà leggere i nomi delle tratte che hai salvato. Nessun dato in tempo reale viene condiviso.</p>
<form method="post" action="/api/alexa/oauth/authorize">
${hidden("response_type", "code")}
${hidden("client_id", input.clientId)}
${hidden("redirect_uri", input.redirectUri)}
${hidden("state", input.state)}
${hidden("scope", input.scope)}
${hidden("csrf_token", input.token)}
<button class="primary" type="submit" name="decision" value="allow">Collega</button>
<button type="submit" name="decision" value="deny">Annulla</button>
</form>
</body>
</html>
`;
}
