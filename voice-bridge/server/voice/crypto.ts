import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { jwtVerify, SignJWT } from "jose";
import { ENV } from "../_core/env";

const encoder = new TextEncoder();
const issuer = "binario-voice-bridge";

function signingKey() {
  if (!ENV.voiceTokenPepper) throw new Error("Integrazione Alexa non configurata");
  return encoder.encode(ENV.voiceTokenPepper);
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
      issuer,
      audience: ENV.alexaOAuthClientId,
    });
    const userId = Number(payload.sub);
    return payload.token_use === "binario_alexa" && Number.isSafeInteger(userId) && userId > 0 ? userId : null;
  } catch {
    return null;
  }
}
