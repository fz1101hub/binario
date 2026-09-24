import type { CompanionSnapshot } from "@/lib/companion-snapshot";
import type { LocalPlannedRoute } from "@/lib/local-route-contract";

export const companionWearDataPathV1 = "/binario/companion/watch-payload/v1";
export const companionWearSchemaVersion = 1;
export const companionWearMaxSerializedBytes = 8_192;
export const companionWearMaxTtlMillis = 365 * 24 * 60 * 60 * 1_000;
export const companionWearFutureSkewMillis = 60_000;

const producerValues = ["ANDROID_APP", "COMPANION", "IOS_APP", "UNKNOWN"] as const;
const dataNatureValues = ["PROGRAMMED", "OPERATIONAL", "UNAVAILABLE", "UNKNOWN"] as const;
const operationalStateValues = ["PROGRAMMED", "OPERATIONAL", "UNAVAILABLE", "UNKNOWN"] as const;
const freshnessValues = ["FRESH", "AGING", "STALE", "UNAVAILABLE", "UNKNOWN"] as const;
const confidenceValues = ["OPERATOR_PRIMARY", "INFRASTRUCTURE_PRIMARY", "OPEN_DATA", "LICENSED_AGGREGATE", "STATIC_ONLY", "CACHED", "LOCAL_PLANNED", "UNKNOWN"] as const;
const directionValues = ["OUTBOUND", "RETURN", "UNKNOWN"] as const;

export type CompanionWearPayloadV1 = {
  schemaVersion: 1;
  generatedAtEpochMillis: number;
  expiresAtEpochMillis: number;
  ttlMillis: number;
  provenanceProducer: (typeof producerValues)[number];
  provenanceDataNature: (typeof dataNatureValues)[number];
  provenanceSourceLabel: string;
  provenanceObservedAtEpochMillis: number;
  operationalState: (typeof operationalStateValues)[number];
  freshness: (typeof freshnessValues)[number];
  confidence: (typeof confidenceValues)[number];
  routeId: string;
  routeLabel: string;
  direction: (typeof directionValues)[number];
  notice: string;
  serviceCount: 0 | 1 | 2;
  [serviceField: `service${number}${string}`]: string | number;
};

export const companionWearBasePayloadKeys = [
  "schemaVersion",
  "generatedAtEpochMillis",
  "expiresAtEpochMillis",
  "ttlMillis",
  "provenanceProducer",
  "provenanceDataNature",
  "provenanceSourceLabel",
  "provenanceObservedAtEpochMillis",
  "operationalState",
  "freshness",
  "confidence",
  "routeId",
  "routeLabel",
  "direction",
  "notice",
  "serviceCount",
] as const;

const serviceFields = ["Number", "Operator", "Category", "Destination", "Scheduled", "Expected", "Status", "Platform", "DelayMinutes"] as const;
const localNotice = "Orario programmato locale: non è un aggiornamento realtime ferroviario.";
const unavailableNotice = "Dati non disponibili nel Companion: apri l’app principale per aggiornare la pianificazione locale.";

function normalize(value: string, maxCharacters: number): string {
  return value.trim().replace(/\s+/g, " ").slice(0, maxCharacters).trim();
}

function isNormalizedBounded(value: unknown, maxCharacters: number, allowEmpty = false): value is string {
  return typeof value === "string" && value === normalize(value, maxCharacters) && (allowEmpty || value.length > 0);
}

function isOneOf<T extends readonly string[]>(value: unknown, allowed: T): value is T[number] {
  return typeof value === "string" && (allowed as readonly string[]).includes(value);
}

function isInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value);
}

function expectedKeys(serviceCount: number) {
  return [
    ...companionWearBasePayloadKeys,
    ...Array.from({ length: serviceCount }, (_, index) => serviceFields.map(field => `service${index}${field}`)),
  ].flat();
}

function serializedBytes(payload: Record<string, unknown>) {
  return new TextEncoder().encode(JSON.stringify(payload)).byteLength;
}

/**
 * Validates the flat, transport-ready DataMap shape before the native bridge sees it.
 * This intentionally rejects unknown keys: v1 has no extension envelope.
 */
export function isValidCompanionWearPayloadV1(value: unknown, now = Date.now()): value is CompanionWearPayloadV1 {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const payload = value as Record<string, unknown>;
  const serviceCount = payload.serviceCount;
  if (!isInteger(serviceCount) || serviceCount < 0 || serviceCount > 2) return false;
  const keys = Object.keys(payload).sort();
  if (keys.length !== expectedKeys(serviceCount).length || keys.some((key, index) => key !== expectedKeys(serviceCount).sort()[index])) return false;
  if (payload.schemaVersion !== companionWearSchemaVersion || !isInteger(payload.generatedAtEpochMillis) || !isInteger(payload.expiresAtEpochMillis) || !isInteger(payload.ttlMillis) || !isInteger(payload.provenanceObservedAtEpochMillis)) return false;
  if (payload.ttlMillis <= 0 || payload.ttlMillis > companionWearMaxTtlMillis || payload.expiresAtEpochMillis - payload.generatedAtEpochMillis !== payload.ttlMillis || payload.expiresAtEpochMillis <= now || payload.generatedAtEpochMillis > now + companionWearFutureSkewMillis) return false;
  if (!isOneOf(payload.provenanceProducer, producerValues) || !isOneOf(payload.provenanceDataNature, dataNatureValues) || !isOneOf(payload.operationalState, operationalStateValues) || !isOneOf(payload.freshness, freshnessValues) || !isOneOf(payload.confidence, confidenceValues) || !isOneOf(payload.direction, directionValues)) return false;
  if (!isNormalizedBounded(payload.provenanceSourceLabel, 96) || !isNormalizedBounded(payload.routeId, 64) || !isNormalizedBounded(payload.routeLabel, 96) || !isNormalizedBounded(payload.notice, 180, true)) return false;
  if ((payload.operationalState === "UNAVAILABLE" || payload.provenanceDataNature === "UNAVAILABLE") && (serviceCount !== 0 || payload.provenanceDataNature !== "UNAVAILABLE")) return false;
  for (let index = 0; index < serviceCount; index += 1) {
    if (!isNormalizedBounded(payload[`service${index}Number`], 24) || !isNormalizedBounded(payload[`service${index}Operator`], 64) || !isNormalizedBounded(payload[`service${index}Category`], 64) || !isNormalizedBounded(payload[`service${index}Destination`], 96) || !isNormalizedBounded(payload[`service${index}Scheduled`], 16) || !isNormalizedBounded(payload[`service${index}Expected`], 16) || !isNormalizedBounded(payload[`service${index}Status`], 64) || !isNormalizedBounded(payload[`service${index}Platform`], 16, true) || !isInteger(payload[`service${index}DelayMinutes`]) || (payload[`service${index}DelayMinutes`] as number) < -720 || (payload[`service${index}DelayMinutes`] as number) > 1_440) return false;
  }
  return serializedBytes(payload) <= companionWearMaxSerializedBytes;
}

function unavailablePayload(now: number): CompanionWearPayloadV1 {
  return {
    schemaVersion: companionWearSchemaVersion,
    generatedAtEpochMillis: now,
    expiresAtEpochMillis: now + 15 * 60_000,
    ttlMillis: 15 * 60_000,
    provenanceProducer: "COMPANION",
    provenanceDataNature: "UNAVAILABLE",
    provenanceSourceLabel: "Companion locale",
    provenanceObservedAtEpochMillis: now,
    operationalState: "UNAVAILABLE",
    freshness: "UNAVAILABLE",
    confidence: "LOCAL_PLANNED",
    routeId: "unavailable",
    routeLabel: "Nessuna tratta attiva",
    direction: "UNKNOWN",
    notice: unavailableNotice,
    serviceCount: 0,
  };
}

/**
 * Builds the only Companion-originated Wear snapshot. It contains local planning only,
 * never inferred live train status, and becomes an explicit bounded UNAVAILABLE snapshot
 * when no valid local plan can be represented under the v1 contract.
 */
export function buildCompanionWearPayloadV1(snapshot: CompanionSnapshot, route: LocalPlannedRoute | null, now = Date.now()): CompanionWearPayloadV1 {
  const generatedAtEpochMillis = snapshot.provenance.capturedAtUtcMs;
  const expiresAtEpochMillis = snapshot.provenance.expiresAtUtcMs;
  const ttlMillis = expiresAtEpochMillis === null ? 0 : expiresAtEpochMillis - generatedAtEpochMillis;
  if (!route || snapshot.provenance.dataNature !== "scheduled" || expiresAtEpochMillis === null || !Number.isSafeInteger(generatedAtEpochMillis) || !Number.isSafeInteger(expiresAtEpochMillis) || generatedAtEpochMillis > now + companionWearFutureSkewMillis || expiresAtEpochMillis <= now || ttlMillis <= 0 || ttlMillis > companionWearMaxTtlMillis) return unavailablePayload(now);
  const payload: CompanionWearPayloadV1 = {
    schemaVersion: companionWearSchemaVersion,
    generatedAtEpochMillis,
    expiresAtEpochMillis,
    ttlMillis,
    provenanceProducer: "COMPANION",
    provenanceDataNature: "PROGRAMMED",
    provenanceSourceLabel: "Pianificazione locale del dispositivo",
    provenanceObservedAtEpochMillis: generatedAtEpochMillis,
    operationalState: "PROGRAMMED",
    freshness: "FRESH",
    confidence: "LOCAL_PLANNED",
    routeId: normalize(`local-${route.id}`, 64),
    routeLabel: normalize(route.name, 96),
    direction: "OUTBOUND",
    notice: normalize(snapshot.provenance.notice || localNotice, 180),
    serviceCount: 0,
  };
  return isValidCompanionWearPayloadV1(payload, now) ? payload : unavailablePayload(now);
}
