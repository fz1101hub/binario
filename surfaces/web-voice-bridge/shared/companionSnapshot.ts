export type RailDataNature = "realtime" | "scheduled" | "estimated" | "unavailable";
export type RailDataConfidence = "verified" | "partial" | "conflict" | "unavailable";
export type CompanionSurface = "app" | "widget" | "smartwatch" | "alexa" | "notification";
export type CompanionNotificationKind = "departure-reminder" | "delay" | "cancellation" | "platform" | "service-alert";

export type RailProvenance = {
  providerId: string;
  providerLabel: string;
  licenseUrl?: string;
  observedAtUtcMs?: number;
  expiresAtUtcMs?: number;
  dataNature: RailDataNature;
  confidence: RailDataConfidence;
  notice: string;
};

export type CompanionSnapshot = {
  schemaVersion: 1;
  subject: { kind: "station" | "route" | "train"; id: string; label: string };
  provenance: RailProvenance;
  generatedAtUtcMs: number;
};

export type CompanionDelivery = {
  action: "deliver" | "deliver_with_notice" | "suppress";
  notice: string;
};

const UNAVAILABLE_NOTICE = "Dati non disponibili in tempo reale.";
const STALE_NOTICE = "Dati non aggiornati: avviso non inviato.";

function hasFreshObservation(provenance: RailProvenance, nowUtcMs: number) {
  return typeof provenance.observedAtUtcMs === "number"
    && typeof provenance.expiresAtUtcMs === "number"
    && provenance.observedAtUtcMs <= nowUtcMs
    && provenance.expiresAtUtcMs > nowUtcMs;
}

export function evaluateCompanionSnapshot(snapshot: CompanionSnapshot, nowUtcMs = Date.now()): CompanionDelivery {
  const { provenance } = snapshot;
  if (provenance.dataNature === "unavailable" || provenance.confidence === "unavailable") {
    return { action: "suppress", notice: UNAVAILABLE_NOTICE };
  }

  if (provenance.dataNature === "realtime" && !hasFreshObservation(provenance, nowUtcMs)) {
    return { action: "suppress", notice: STALE_NOTICE };
  }

  if (provenance.confidence === "conflict") {
    return { action: "suppress", notice: "Dati in conflitto: avviso non inviato." };
  }

  if (provenance.dataNature === "estimated" || provenance.confidence === "partial") {
    return { action: "deliver_with_notice", notice: `${provenance.notice} Dato stimato o parziale.` };
  }

  return { action: "deliver", notice: provenance.notice };
}

export function evaluateCompanionNotification(
  snapshot: CompanionSnapshot,
  kind: CompanionNotificationKind,
  nowUtcMs = Date.now(),
): CompanionDelivery {
  const delivery = evaluateCompanionSnapshot(snapshot, nowUtcMs);
  if (delivery.action === "suppress") return delivery;

  const realtimeEvent = kind === "delay" || kind === "cancellation" || kind === "platform" || kind === "service-alert";
  if (realtimeEvent && snapshot.provenance.dataNature !== "realtime") {
    return { action: "suppress", notice: "Avviso realtime non inviato: manca una fonte realtime verificata." };
  }

  if (kind === "departure-reminder" && snapshot.provenance.dataNature === "scheduled") {
    return { action: "deliver_with_notice", notice: `${snapshot.provenance.notice} Orario programmato, non realtime.` };
  }

  return delivery;
}

export function getCompanionFallback(surface: CompanionSurface) {
  return surface === "notification"
    ? "Notifica non inviata: dati non disponibili in tempo reale."
    : UNAVAILABLE_NOTICE;
}

export function createUnavailableCompanionSnapshot(
  subject: CompanionSnapshot["subject"],
  nowUtcMs = Date.now(),
): CompanionSnapshot {
  return {
    schemaVersion: 1,
    subject,
    generatedAtUtcMs: nowUtcMs,
    provenance: {
      providerId: "binario-no-live-provider",
      providerLabel: "Binario",
      dataNature: "unavailable",
      confidence: "unavailable",
      notice: UNAVAILABLE_NOTICE,
    },
  };
}
