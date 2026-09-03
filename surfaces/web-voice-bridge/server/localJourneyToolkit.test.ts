import { describe, expect, it } from "vitest";
import {
  checklistFromTemplates,
  defaultJourneyAccessibilityPreferences,
  defaultJourneyExtension,
  deriveAgenda,
  deriveLocalJourneyStats,
  generateJourneyOccurrences,
  journeyShareText,
  journeyToIcs,
  mergeChecklistTemplates,
  normalizeJourneyAccessibilityPreferences,
  parseJourneyIcs,
  parseJourneyIcsEntries,
  parseJourneyAccessibilityPreferences,
  createReplannedJourneyDraft,
  createReturnJourneyDraft,
  suggestedJourneyReminderMinutes,
  normalizeJourneyExtension,
  type JourneyRecord,
} from "../shared/localJourneyToolkit";

function route(overrides: Partial<JourneyRecord> = {}): JourneyRecord {
  return {
    id: "route-1",
    origin: "Roma Termini",
    destination: "Firenze S. M. Novella",
    originStationCode: "S001",
    destinationStationCode: "S002",
    travelDate: "2026-09-07",
    travelTime: "08:30",
    ...defaultJourneyExtension("2026-09-01T09:00:00.000Z"),
    ...overrides,
  };
}

describe("local journey toolkit", () => {
  it("normalizza dati locali corrotti senza bloccare le tratte precedenti", () => {
    expect(normalizeJourneyExtension({ reminderMinutes: 999, recurrence: { weekdays: [] }, checklist: [{ label: "incompleta" }] }, "2026-09-01T00:00:00.000Z")).toEqual(defaultJourneyExtension("2026-09-01T00:00:00.000Z"));
  });

  it("applica modelli checklist senza deduplicare lo stato privato con la rete", () => {
    const items = checklistFromTemplates(["commuter", "accessibility"]);
    expect(items.map(item => item.label)).toEqual(expect.arrayContaining(["Verifica il margine di anticipo", "Imposta un margine di anticipo adeguato"]));
    expect(items.every(item => !item.completed)).toBe(true);
  });

  it("mantiene lo stato delle checklist quando si riapplicano modelli personali", () => {
    const items = mergeChecklistTemplates([{ id: "mine", label: "Controlla gli effetti personali", completed: true }, { id: "custom", label: "Porta la borraccia", completed: false }], ["commuter"]);
    expect(items).toEqual(expect.arrayContaining([{ id: "mine", label: "Controlla gli effetti personali", completed: true }, { id: "custom", label: "Porta la borraccia", completed: false }]));
  });

  it("normalizza preferenze viaggio accessibili e locali", () => {
    expect(normalizeJourneyAccessibilityPreferences({ schemaVersion: 1, minimumTransferMinutes: 30, defaultReminderMinutes: 45, accessibilityChecklistByDefault: true })).toEqual({ schemaVersion: 1, minimumTransferMinutes: 30, defaultReminderMinutes: 45, accessibilityChecklistByDefault: true });
    expect(normalizeJourneyAccessibilityPreferences({ schemaVersion: 7, minimumTransferMinutes: 1 })).toEqual(defaultJourneyAccessibilityPreferences());
    expect(parseJourneyAccessibilityPreferences("{non-json")).toEqual(defaultJourneyAccessibilityPreferences());
    expect(suggestedJourneyReminderMinutes({ schemaVersion: 1, minimumTransferMinutes: 30, defaultReminderMinutes: null, accessibilityChecklistByDefault: false })).toBe(30);
    expect(suggestedJourneyReminderMinutes({ schemaVersion: 1, minimumTransferMinutes: 30, defaultReminderMinutes: 45, accessibilityChecklistByDefault: false })).toBe(45);
  });

  it("genera bozze ritorno e ripianifica senza riusare data, ora o stati completati", () => {
    const journey = route({ checklistTemplateIds: ["commuter"], checklist: [{ id: "done", label: "Controlla", completed: true }], completedAt: "2026-09-01T00:00:00.000Z" });
    const replanned = createReplannedJourneyDraft(journey);
    expect(replanned).toMatchObject({ origin: "Roma Termini", destination: "Firenze S. M. Novella", travelDate: "", travelTime: "" });
    expect(replanned.checklist.every(item => !item.completed)).toBe(true);
    expect(createReturnJourneyDraft(journey)).toMatchObject({ origin: "Firenze S. M. Novella", destination: "Roma Termini", originStationCode: "S002", destinationStationCode: "S001", travelDate: "", travelTime: "" });
  });

  it("genera occorrenze solo nel numero e nei giorni scelti dall’utente", () => {
    const recurrent = route({
      recurrence: { schemaVersion: 1, weekdays: [1, 3, 5], time: "08:30", startsOn: "2026-09-07", endsOn: "2026-09-12", occurrences: 3 },
    });
    expect(generateJourneyOccurrences(recurrent, new Date("2026-09-07T00:00:00"))).toMatchObject([
      { travelDate: "2026-09-07", travelTime: "08:30" },
      { travelDate: "2026-09-09", travelTime: "08:30" },
      { travelDate: "2026-09-11", travelTime: "08:30" },
    ]);
  });

  it("filtra l’agenda imminente ed esclude tratte archiviate o completate", () => {
    const entries = deriveAgenda([
      route({ id: "next", travelDate: "2026-09-08" }),
      route({ id: "archived", travelDate: "2026-09-08", archivedAt: "2026-09-01T00:00:00.000Z" }),
      route({ id: "completed", travelDate: "2026-09-08", completedAt: "2026-09-01T00:00:00.000Z" }),
    ], "upcoming", new Date("2026-09-07T00:00:00"));
    expect(entries.map(entry => entry.id)).toEqual(["next"]);
  });

  it("calcola statistiche dal solo archivio locale e non espone note o token", () => {
    const stats = deriveLocalJourneyStats([
      route({ reminderMinutes: 20 }),
      route({ id: "done", destination: "Bologna Centrale", archivedAt: "2026-09-01T00:00:00.000Z", completedAt: "2026-09-01T00:00:00.000Z" }),
    ]);
    expect(stats).toMatchObject({ active: 1, archived: 1, completed: 1, remindersConfigured: 1, remindersCompleted: 0 });
    expect(stats.topDestinations).toEqual(expect.arrayContaining([{ destination: "Firenze S. M. Novella", count: 1 }]));
  });

  it("genera riepilogo e ICS senza note private né claim realtime", () => {
    const journey = route({ reminderMinutes: 20 });
    expect(journeyShareText(journey)).toContain("Stato ferroviario: non realtime.");
    const ics = journeyToIcs(journey, new Date("2026-09-01T00:00:00.000Z"));
    expect(ics).toContain("BEGIN:VCALENDAR");
    expect(ics).toContain("SUMMARY:Roma Termini → Firenze S. M. Novella");
    expect(ics).toContain("TRIGGER:-PT20M");
    expect(ics).not.toContain("nota privata");
    expect(parseJourneyIcs(ics ?? "")).toEqual({ origin: "Roma Termini", destination: "Firenze S. M. Novella", travelDate: "2026-09-07", travelTime: "08:30" });
    expect(parseJourneyIcs("BEGIN:VCALENDAR\nSUMMARY:Una sola stazione\nEND:VCALENDAR")).toBeNull();
  });

  it("estrae più eventi ICS completi, eliminando duplicati e ignorando componenti non validi", () => {
    const first = journeyToIcs(route(), new Date("2026-09-01T00:00:00.000Z"))!;
    const second = journeyToIcs(route({ id: "route-2", origin: "Firenze S. M. Novella", destination: "Bologna Centrale", travelDate: "2026-09-08", travelTime: "10:15" }), new Date("2026-09-01T00:00:00.000Z"))!;
    const events = `${first.match(/BEGIN:VEVENT[\s\S]*?END:VEVENT/)?.[0]}\n${second.match(/BEGIN:VEVENT[\s\S]*?END:VEVENT/)?.[0]}\n${first.match(/BEGIN:VEVENT[\s\S]*?END:VEVENT/)?.[0]}`;
    expect(parseJourneyIcsEntries(`BEGIN:VCALENDAR\n${events}\nBEGIN:VEVENT\nSUMMARY:Non valida\nEND:VEVENT\nEND:VCALENDAR`)).toEqual([
      { origin: "Roma Termini", destination: "Firenze S. M. Novella", travelDate: "2026-09-07", travelTime: "08:30" },
      { origin: "Firenze S. M. Novella", destination: "Bologna Centrale", travelDate: "2026-09-08", travelTime: "10:15" },
    ]);
  });
});
