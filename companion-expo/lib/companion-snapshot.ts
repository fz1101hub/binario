import type { LocalPlannedRoute } from "@/lib/local-route-contract";
import { createTranslator, type CompanionLocale } from "@/lib/i18n";

export type CompanionSnapshotSubject = { kind: "route"; id: string; label: string };
export type CompanionSnapshot = { subject: CompanionSnapshotSubject; title: string; summary: string; provenance: { dataNature: "scheduled" | "unavailable"; sourceLabel: string; capturedAtUtcMs: number; expiresAtUtcMs: number | null; notice: string } };
export type SnapshotDecision = { action: "show" | "suppress"; notice: string };

export function createUnavailableSnapshot(subject: CompanionSnapshotSubject, locale?: CompanionLocale): CompanionSnapshot {
  const t = createTranslator(locale);
  return { subject, title: subject.label, summary: t("snapshot.unavailableSummary"), provenance: { dataNature: "unavailable", sourceLabel: t("snapshot.unavailableSource"), capturedAtUtcMs: Date.now(), expiresAtUtcMs: null, notice: t("snapshot.unavailableNotice") } };
}

export function createLocalScheduledSnapshot(route: Pick<LocalPlannedRoute, "id" | "name" | "travelDate" | "travelTime">, now = Date.now(), locale?: CompanionLocale): CompanionSnapshot {
  const t = createTranslator(locale);
  const scheduledAt = new Date(`${route.travelDate}T${route.travelTime}:00`).getTime();
  return { subject: { kind: "route", id: String(route.id), label: route.name }, title: route.name, summary: `${route.travelDate} · ${route.travelTime}`, provenance: { dataNature: "scheduled", sourceLabel: t("snapshot.localSource"), capturedAtUtcMs: now, expiresAtUtcMs: Number.isFinite(scheduledAt) ? scheduledAt : null, notice: t("snapshot.localNotice") } };
}

export function evaluateSnapshot(snapshot: CompanionSnapshot, now = Date.now(), locale?: CompanionLocale): SnapshotDecision {
  const t = createTranslator(locale);
  return snapshot.provenance.dataNature === "scheduled" && snapshot.provenance.expiresAtUtcMs !== null && snapshot.provenance.expiresAtUtcMs < now
    ? { action: "suppress", notice: t("snapshot.expired") }
    : { action: "show", notice: snapshot.provenance.notice };
}
