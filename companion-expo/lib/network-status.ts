import type { CompanionSnapshot } from "@/lib/companion-snapshot";

/** setTimeout accetta al massimo 2^31−1 ms: un ritardo maggiore trabocca e il timer scatterebbe subito. */
export const maxTimerDelayMs = 2_147_483_647;

export function msUntilSnapshotExpiry(provenance: CompanionSnapshot["provenance"], now = Date.now()) {
  return provenance.expiresAtUtcMs === null ? 0 : Math.max(0, provenance.expiresAtUtcMs - now);
}

/** Riporta un ritardo entro [0, maxTimerDelayMs]. Un ritardo tagliato scatta prima della scadenza: chi programma il timer deve ricontrollarla allo scatto. */
export function clampTimerDelay(ms: number) {
  return Number.isNaN(ms) ? 0 : Math.min(Math.max(0, ms), maxTimerDelayMs);
}
