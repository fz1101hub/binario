import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { createLocalScheduledSnapshot } from "@/lib/companion-snapshot";
import { buildCompanionWearPayloadV1, companionWearBasePayloadKeys, companionWearMaxSerializedBytes, isValidCompanionWearPayloadV1 } from "@/lib/companion-wear-contract";
import { createLocalPlannedRoute } from "@/lib/local-route-contract";

const now = new Date("2030-01-01T00:00:00Z").getTime();
const fixture = JSON.parse(readFileSync(path.resolve(process.cwd(), "../contracts/wear-snapshot/v1/companion-to-wear-v1.json"), "utf8")) as Record<string, unknown>;
const route = createLocalPlannedRoute({ origin: "Roma Termini", destination: "Napoli Centrale", travelDate: "2030-01-01", travelTime: "01:30", plannedReminderMinutes: 15 }, 42, now)!;

function fixtureAtNow() {
  const ttlMillis = fixture.ttlMillis as number;
  return {
    ...fixture,
    generatedAtEpochMillis: now,
    expiresAtEpochMillis: now + ttlMillis,
    provenanceObservedAtEpochMillis: now,
  };
}

describe("contratto Companion Expo → Wear v1", () => {
  it("trasporta il golden canonico con sole chiavi chiuse e nel budget", () => {
    const golden = fixtureAtNow();
    expect(isValidCompanionWearPayloadV1(golden, now)).toBe(true);
    expect(Object.keys(golden).sort()).toEqual([...companionWearBasePayloadKeys].sort());
    expect(new TextEncoder().encode(JSON.stringify(golden)).byteLength).toBeLessThanOrEqual(companionWearMaxSerializedBytes);
  });

  it("produce un payload Companion pianificato che è direttamente inseribile in DataMap", () => {
    const payload = buildCompanionWearPayloadV1(createLocalScheduledSnapshot(route, now), route, now);
    expect(payload).toEqual(fixtureAtNow());
    expect(isValidCompanionWearPayloadV1(payload, now)).toBe(true);
  });

  it("rifiuta alla scadenza un payload altrimenti valido", () => {
    const payload = fixtureAtNow();
    expect(isValidCompanionWearPayloadV1(payload, payload.expiresAtEpochMillis as number)).toBe(false);
  });

  it("rifiuta manomissione di una chiave, TTL o campo extra", () => {
    const payload = fixtureAtNow();
    expect(isValidCompanionWearPayloadV1({ ...payload, ttlMillis: (fixture.ttlMillis as number) + 1 }, now)).toBe(false);
    expect(isValidCompanionWearPayloadV1({ ...payload, secret: "non-ammesso" }, now)).toBe(false);
    expect(isValidCompanionWearPayloadV1({ ...payload, provenanceProducer: 1 }, now)).toBe(false);
  });

  it("consente solo i campi che il contratto dichiara esplicitamente vuoti", () => {
    const payload = fixtureAtNow();
    expect(isValidCompanionWearPayloadV1({ ...payload, routeId: "", routeLabel: "", notice: "" }, now)).toBe(true);
    expect(isValidCompanionWearPayloadV1({ ...payload, provenanceSourceLabel: "" }, now)).toBe(false);
  });
});
