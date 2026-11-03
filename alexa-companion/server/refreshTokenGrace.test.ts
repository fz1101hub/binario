import { describe, expect, it } from "vitest";
import { isRefreshRetry, REFRESH_TOKEN_REUSE_GRACE_MS } from "./db";

// The end-to-end lost-response retry and concurrent refresh run against MariaDB in
// db.migrations.integration.test.ts; this pins the decision itself.
describe("riuso di un refresh token già ruotato", () => {
  const revokedAt = new Date("2026-11-03T10:00:00.000Z");
  const after = (ms: number) => new Date(revokedAt.getTime() + ms);

  it("è un retry entro la finestra se il successore è ancora attivo", () => {
    expect(REFRESH_TOKEN_REUSE_GRACE_MS).toBe(60_000);
    expect(isRefreshRetry({ revokedAt, now: after(0), successorIsLive: true })).toBe(true);
    expect(isRefreshRetry({ revokedAt, now: after(60_000), successorIsLive: true })).toBe(true);
    // Second-precision timestamps may round the stored revocation slightly into the future.
    expect(isRefreshRetry({ revokedAt, now: after(-500), successorIsLive: true })).toBe(true);
  });

  it("è un replay fuori finestra o se il successore è revocato, scaduto o già ruotato", () => {
    expect(isRefreshRetry({ revokedAt, now: after(60_001), successorIsLive: true })).toBe(false);
    expect(isRefreshRetry({ revokedAt, now: after(1_000), successorIsLive: false })).toBe(false);
  });
});
