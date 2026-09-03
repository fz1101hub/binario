import type { LocalPlannedRoute } from "@/lib/local-route-contract";

export type CompanionSnapshotSubject = { kind: "route"; id: string; label: string };
export type CompanionSnapshot = { subject: CompanionSnapshotSubject; title: string; summary: string; provenance: { dataNature: "scheduled" | "unavailable"; sourceLabel: string; capturedAtUtcMs: number; expiresAtUtcMs: number | null; notice: string } };
export type SnapshotDecision = { action: "show" | "suppress"; notice: string };

export function createUnavailableSnapshot(subject: CompanionSnapshotSubject): CompanionSnapshot {
  return { subject, title: subject.label, summary: "Dati ferroviari non disponibili", provenance: { dataNature: "unavailable", sourceLabel: "Nessun provider ferroviario autorizzato", capturedAtUtcMs: Date.now(), expiresAtUtcMs: null, notice: "Binario non mostra ritardi, binari, cancellazioni o avvisi senza una fonte autorizzata." } };
}

export function createLocalScheduledSnapshot(route: Pick<LocalPlannedRoute, "id" | "name" | "travelDate" | "travelTime">, now = Date.now()): CompanionSnapshot {
  const scheduledAt = new Date(`${route.travelDate}T${route.travelTime}:00`).getTime();
  return { subject: { kind: "route", id: String(route.id), label: route.name }, title: route.name, summary: `${route.travelDate} · ${route.travelTime}`, provenance: { dataNature: "scheduled", sourceLabel: "Pianificazione locale del dispositivo", capturedAtUtcMs: now, expiresAtUtcMs: Number.isFinite(scheduledAt) ? scheduledAt : null, notice: "Orario programmato locale: non è un aggiornamento realtime ferroviario." } };
}

export function evaluateSnapshot(snapshot: CompanionSnapshot, now = Date.now()): SnapshotDecision {
  return snapshot.provenance.dataNature === "scheduled" && snapshot.provenance.expiresAtUtcMs !== null && snapshot.provenance.expiresAtUtcMs < now
    ? { action: "suppress", notice: "Pianificazione scaduta: dati non più mostrati." }
    : { action: "show", notice: snapshot.provenance.notice };
}
