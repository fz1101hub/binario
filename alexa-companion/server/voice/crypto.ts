import { createHmac, hkdfSync, randomBytes, timingSafeEqual } from "node:crypto";
import { jwtVerify, SignJWT } from "jose";
import { ENV } from "../_core/env";

const encoder = new TextEncoder();
// Protocol identifier kept stable across the directory/product rename so an
// in-flight access token is not invalidated solely by an architectural cleanup.
const issuer = "binario-voice-bridge";

// The pepper keeps hashing the stored code/refresh digests directly (the
// digests already persisted depend on it); the JWT key is derived so that the
// signing key and the hashing key are never the same bytes.
function signingKey() {
  if (!ENV.voiceTokenPepper) throw new Error("Integrazione Alexa non configurata");
  return new Uint8Array(hkdfSync("sha256", encoder.encode(ENV.voiceTokenPepper), new Uint8Array(0), "binario-voice-access-token", 32));
}

export function createOpaqueSecret() {
  return randomBytes(32).toString("base64url");
}

export function hashVoiceSecret(value: string) {
  if (!ENV.voiceTokenPepper) throw new Error("Integrazione Alexa non configurata");
  return createHmac("sha256", ENV.voiceTokenPepper).update(value).digest("hex");
}

export function secureStringEquals(left: string, right: string) {
  const leftBuffer = Buffer.from(left);
  const rightBuffer = Buffer.from(right);
  return leftBuffer.length === rightBuffer.length && timingSafeEqual(leftBuffer, rightBuffer);
}

export async function issueVoiceAccessToken(userId: number) {
  return new SignJWT({ token_use: "binario_alexa" })
    .setProtectedHeader({ alg: "HS256" })
    .setIssuer(issuer)
    .setAudience(ENV.alexaOAuthClientId)
    .setSubject(String(userId))
    .setIssuedAt()
    .setExpirationTime("1h")
    .sign(signingKey());
}

export async function verifyVoiceAccessToken(token: string): Promise<number | null> {
  try {
    const { payload } = await jwtVerify(token, signingKey(), {
      algorithms: ["HS256"],
      issuer,
      audience: ENV.alexaOAuthClientId,
    });
    const userId = Number(payload.sub);
    return payload.token_use === "binario_alexa" && Number.isSafeInteger(userId) && userId > 0 ? userId : null;
  } catch {
    return null;
  }
}
