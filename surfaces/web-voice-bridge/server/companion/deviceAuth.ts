import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { ENV } from "../_core/env";

const PAIRING_PREFIX = "binario-companion-pairing-v1:";
const DEVICE_PREFIX = "binario-companion-device-v1:";
const ATTEMPT_PREFIX = "binario-companion-attempt-v1:";

function companionKey() {
  if (!ENV.cookieSecret) throw new Error("Autenticazione companion non configurata");
  return ENV.cookieSecret;
}

export function createCompanionSecret() {
  return randomBytes(32).toString("base64url");
}

export function hashPairingCode(value: string) {
  return createHmac("sha256", companionKey()).update(PAIRING_PREFIX).update(value).digest("hex");
}

export function hashDeviceToken(value: string) {
  return createHmac("sha256", companionKey()).update(DEVICE_PREFIX).update(value).digest("hex");
}

export function hashPairingAttemptClient(value: string) {
  return createHmac("sha256", companionKey()).update(ATTEMPT_PREFIX).update(value).digest("hex");
}

export function tokenPrefix(value: string) {
  return value.slice(0, 10);
}

export function getCompanionBearer(value: string | string[] | undefined) {
  const header = Array.isArray(value) ? value[0] : value;
  const match = typeof header === "string" ? /^Bearer ([A-Za-z0-9_-]{40,128})$/.exec(header) : null;
  return match?.[1] ?? null;
}

export function constantTimeEquals(left: string, right: string) {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.length === b.length && timingSafeEqual(a, b);
}
