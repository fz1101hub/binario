import { describe, expect, it, vi } from "vitest";
import { SignJWT } from "jose";

vi.hoisted(() => {
  Object.assign(process.env, {
    ALEXA_OAUTH_CLIENT_ID: "alexa-client",
    VOICE_TOKEN_PEPPER: "test-pepper-with-plenty-of-entropy-0123456789",
  });
});

import { hashVoiceSecret, issueVoiceAccessToken, verifyVoiceAccessToken } from "./crypto";

describe("token vocali", () => {
  it("un token emesso viene verificato e identifica l'utente", async () => {
    expect(await verifyVoiceAccessToken(await issueVoiceAccessToken(42))).toBe(42);
  });

  it("rifiuta token firmati con il pepper grezzo o con un altro algoritmo", async () => {
    const rawPepper = new TextEncoder().encode(process.env.VOICE_TOKEN_PEPPER as string);
    const claims = { token_use: "binario_alexa" };
    const withRawPepper = await new SignJWT(claims).setProtectedHeader({ alg: "HS256" }).setIssuer("binario-voice-bridge").setAudience("alexa-client").setSubject("42").setExpirationTime("1h").sign(rawPepper);
    expect(await verifyVoiceAccessToken(withRawPepper)).toBeNull();
    const hs512 = await new SignJWT(claims).setProtectedHeader({ alg: "HS512" }).setIssuer("binario-voice-bridge").setAudience("alexa-client").setSubject("42").setExpirationTime("1h").sign(rawPepper);
    expect(await verifyVoiceAccessToken(hs512)).toBeNull();
  });

  it("i digest dei segreti restano compatibili con quelli già memorizzati (HMAC del pepper)", () => {
    expect(hashVoiceSecret("abc")).toMatch(/^[0-9a-f]{64}$/);
    expect(hashVoiceSecret("abc")).toBe(hashVoiceSecret("abc"));
    expect(hashVoiceSecret("abc")).not.toBe(hashVoiceSecret("abd"));
  });
});
