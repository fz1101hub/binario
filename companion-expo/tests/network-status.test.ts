import { describe, expect, it } from "vitest";
import { createLocalScheduledSnapshot, createUnavailableSnapshot } from "@/lib/companion-snapshot";
import { clampTimerDelay, maxTimerDelayMs, msUntilSnapshotExpiry } from "@/lib/network-status";

const now = new Date("2030-01-02T08:00:00").getTime();

describe("scadenza dello snapshot e timer", () => {
  it("misura i millisecondi alla scadenza senza scendere sotto zero", () => {
    const snapshot = createLocalScheduledSnapshot({ id: 4, name: "Roma → Napoli", travelDate: "2030-01-02", travelTime: "09:30" }, now);
    expect(msUntilSnapshotExpiry(snapshot.provenance, now)).toBe(90 * 60_000);
    expect(msUntilSnapshotExpiry(snapshot.provenance, now + 2 * 60 * 60_000)).toBe(0);
    expect(msUntilSnapshotExpiry(createUnavailableSnapshot({ kind: "route", id: "none", label: "Nessuna tratta attiva" }).provenance, now)).toBe(0);
  });

  it("riporta il ritardo di setTimeout entro il limite a 32 bit invece di farlo traboccare", () => {
    expect(maxTimerDelayMs).toBe(2 ** 31 - 1);
    expect(clampTimerDelay(1_000)).toBe(1_000);
    expect(clampTimerDelay(maxTimerDelayMs)).toBe(maxTimerDelayMs);
    expect(clampTimerDelay(2 ** 31)).toBe(maxTimerDelayMs);
    expect(clampTimerDelay(40 * 24 * 60 * 60_000)).toBe(maxTimerDelayMs);
    expect(clampTimerDelay(Number.POSITIVE_INFINITY)).toBe(maxTimerDelayMs);
    expect(clampTimerDelay(-1)).toBe(0);
    expect(clampTimerDelay(Number.NaN)).toBe(0);
  });

  it("una tratta oltre 24 giorni resta entro il limite anche con il margine di sicurezza della Home", () => {
    const snapshot = createLocalScheduledSnapshot({ id: 4, name: "Roma → Napoli", travelDate: "2030-03-02", travelTime: "09:30" }, now);
    expect(msUntilSnapshotExpiry(snapshot.provenance, now) + 50).toBeGreaterThan(maxTimerDelayMs);
    expect(clampTimerDelay(msUntilSnapshotExpiry(snapshot.provenance, now) + 50)).toBe(maxTimerDelayMs);
  });
});
