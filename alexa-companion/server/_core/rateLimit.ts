import type { Request, Response } from "express";
import { rateLimit } from "express-rate-limit";

const WINDOW_MS = 60_000;

/** Per-IP limiter for browser-facing routes: every request counts. */
export function perIpLimiter(limit: number) {
  return rateLimit({
    windowMs: WINDOW_MS,
    limit,
    standardHeaders: "draft-8",
    legacyHeaders: false,
    message: { error: "too_many_requests" },
  });
}

/**
 * Per-IP limiter that counts only the responses `isFailure` recognises. Amazon calls the
 * webhook and the token endpoint from a small shared pool of addresses, so counting its
 * successful traffic per IP would throttle every user at once; failed verification or
 * client authentication is what an attacker produces, and is what this bounds.
 */
export function perIpFailureLimiter(
  limit: number,
  isFailure: (req: Request, res: Response) => boolean
) {
  return rateLimit({
    windowMs: WINDOW_MS,
    limit,
    standardHeaders: "draft-8",
    legacyHeaders: false,
    message: { error: "too_many_requests" },
    skipSuccessfulRequests: true,
    requestWasSuccessful: (req, res) => !isFailure(req, res),
  });
}

/**
 * Marks a rejected request that Amazon relayed for another skill or another OAuth client: a
 * correctly signed webhook request with a foreign skill ID, or token credentials naming a client
 * that is not ours. Anyone can point their own skill at these URLs, and Amazon then calls from
 * the same few addresses as our users, so such rejections must not use up the per-IP failure
 * budget ({@link failedWith}).
 */
export function markRelayedForAnotherSkill(res: Response) {
  res.locals.relayedForAnotherSkill = true;
}

/** `isFailure` for {@link perIpFailureLimiter}: `status`, unless {@link markRelayedForAnotherSkill} marked it. */
export function failedWith(status: number) {
  return (_req: Request, res: Response) => res.statusCode === status && res.locals.relayedForAnotherSkill !== true;
}

/**
 * Fixed one-minute window counter keyed on an identity the caller has already verified
 * (an authenticated OAuth client, a signed Alexa user). Memory is bounded: expired keys are
 * pruned before a new one is stored, and when `maxKeys` live keys exist new keys are let
 * through uncounted rather than evicting someone else's window.
 */
export function createWindowCounter(options: {
  limit: number;
  windowMs?: number;
  maxKeys?: number;
  now?: () => number;
}) {
  const windowMs = options.windowMs ?? WINDOW_MS;
  const maxKeys = options.maxKeys ?? 100_000;
  const clock = options.now ?? Date.now;
  const windows = new Map<string, { count: number; resetAt: number }>();

  function prune(now: number) {
    windows.forEach((window, key) => {
      if (window.resetAt <= now) windows.delete(key);
    });
  }

  return {
    /** Counts one request for `key`; false once the key is over its limit in this window. */
    hit(key: string): boolean {
      const now = clock();
      const current = windows.get(key);
      if (current && current.resetAt > now) {
        current.count += 1;
        return current.count <= options.limit;
      }
      if (!current && windows.size >= maxKeys) prune(now);
      if (!current && windows.size >= maxKeys) return true;
      windows.set(key, { count: 1, resetAt: now + windowMs });
      return 1 <= options.limit;
    },
    /** Seconds until the key's window resets, for Retry-After. */
    retryAfterSeconds(key: string): number {
      const window = windows.get(key);
      return window ? Math.max(1, Math.ceil((window.resetAt - clock()) / 1000)) : 1;
    },
  };
}
