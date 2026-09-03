import { describe, expect, it } from "vitest";
import { evaluateCompanionNotification, evaluateCompanionSnapshot, getCompanionFallback, type CompanionSnapshot } from "../shared/companionSnapshot";

const now = 1_800_000_000_000;
const baseSnapshot: CompanionSnapshot = {
  schemaVersion: 1,
  subject: { kind: "train", id: "FR-9500", label: "Frecciarossa 9500" },
  generatedAtUtcMs: now,
  provenance: {
    providerId: "provider-autorizzato",
    providerLabel: "Provider autorizzato",
    observedAtUtcMs: now - 10_000,
    expiresAtUtcMs: now + 20_000,
    dataNature: "realtime",
    confidence: "verified",
    notice: "Aggiornato ora.",
  },
};

describe("contratto snapshot companion", () => {
  it("consegna un dato realtime verificato e fresco a ogni companion", () => {
    expect(evaluateCompanionSnapshot(baseSnapshot, now)).toEqual({ action: "deliver", notice: "Aggiornato ora." });
    expect(evaluateCompanionNotification(baseSnapshot, "delay", now).action).toBe("deliver");
  });

  it("sopprime eventi realtime quando la fonte è scaduta, indisponibile o in conflitto", () => {
    expect(evaluateCompanionNotification({ ...baseSnapshot, provenance: { ...baseSnapshot.provenance, expiresAtUtcMs: now - 1 } }, "delay", now).action).toBe("suppress");
    expect(evaluateCompanionNotification({ ...baseSnapshot, provenance: { ...baseSnapshot.provenance, expiresAtUtcMs: now } }, "delay", now).action).toBe("suppress");
    expect(evaluateCompanionNotification({ ...baseSnapshot, provenance: { ...baseSnapshot.provenance, dataNature: "unavailable", confidence: "unavailable" } }, "service-alert", now).action).toBe("suppress");
    expect(evaluateCompanionNotification({ ...baseSnapshot, provenance: { ...baseSnapshot.provenance, confidence: "conflict" } }, "platform", now).action).toBe("suppress");
  });

  it("permette un promemoria programmato solo con un’etichetta non realtime", () => {
    const scheduled = { ...baseSnapshot, provenance: { ...baseSnapshot.provenance, dataNature: "scheduled" as const, observedAtUtcMs: undefined, expiresAtUtcMs: undefined } };
    expect(evaluateCompanionNotification(scheduled, "departure-reminder", now)).toMatchObject({ action: "deliver_with_notice" });
    expect(evaluateCompanionNotification(scheduled, "delay", now).action).toBe("suppress");
  });

  it("distingue i fallback parlati e le notifiche non inviate", () => {
    expect(getCompanionFallback("alexa")).toMatch(/non disponibili in tempo reale/i);
    expect(getCompanionFallback("notification")).toMatch(/non inviata/i);
  });
});
