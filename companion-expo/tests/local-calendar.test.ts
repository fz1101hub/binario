import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("expo-document-picker", () => ({ getDocumentAsync: vi.fn() }));
vi.mock("expo-file-system/legacy", () => ({ cacheDirectory: "file:///cache/", writeAsStringAsync: vi.fn(), readAsStringAsync: vi.fn() }));
vi.mock("expo-sharing", () => ({ isAvailableAsync: vi.fn(), shareAsync: vi.fn() }));

import { buildLocalJourneyIcs, calendarDraftKey, foldIcsLine, parseLocalJourneyIcs } from "@/lib/local-calendar";

const deviceTimeZone = process.env.TZ;
afterEach(() => { process.env.TZ = deviceTimeZone; });

function calendar(...events: string[][]) {
  return ["BEGIN:VCALENDAR", "VERSION:2.0", ...events.flatMap(lines => ["BEGIN:VEVENT", ...lines, "END:VEVENT"]), "END:VCALENDAR", ""].join("\r\n");
}

describe("importazione ICS", () => {
  it("converte in ora italiana gli orari UTC e quelli con TZID di un altro fuso", () => {
    for (const zone of ["UTC", "Europe/Rome", "America/New_York"]) {
      process.env.TZ = zone;
      const drafts = parseLocalJourneyIcs(calendar(
        ["SUMMARY:Roma Termini → Milano Centrale", "DTSTART:20261112T070000Z"],
        ["SUMMARY:Torino Porta Nuova → Genova Piazza Principe", "DTSTART:20260712T153000Z"],
        ["SUMMARY:Bari Centrale → Lecce", "DTSTART:20261111T233000Z"],
        ["SUMMARY:Napoli Centrale → Salerno", "DTSTART;TZID=Europe/Rome:20260712T090000"],
        ["SUMMARY:Verona Porta Nuova → Venezia Santa Lucia", "DTSTART;TZID=\"Europe/London\":20260712T090000"],
        ["SUMMARY:Pisa Centrale → Firenze Santa Maria Novella", "DTSTART:20260712T090000"],
        ["SUMMARY:Bologna Centrale → Rimini", "DTSTART;TZID=W. Europe Standard Time:20260712T090000"],
      ));
      expect(drafts.map(draft => `${draft.travelDate} ${draft.travelTime}`), zone).toEqual([
        "2026-11-12 08:00", // 07:00 UTC in November is 08:00 in Italy
        "2026-07-12 17:30", // 15:30 UTC in July is 17:30 in Italy
        "2026-11-12 00:30", // across midnight
        "2026-07-12 09:00",
        "2026-07-12 10:00", // 09:00 in London is 10:00 in Italy
        "2026-07-12 09:00", // floating: read as Italian time
        "2026-07-12 09:00", // a zone name Intl does not know stays as written
      ]);
    }
  });

  it("ricompone le righe piegate e i caratteri di escape del SUMMARY", () => {
    const [draft] = parseLocalJourneyIcs(calendar([
      "SUMMARY:Roma Termini → Reggio di Calabria Centrale via Ro",
      " ma\\, binari regionali",
      "DTSTART;VALUE=DATE-TIME:20261112T081500",
    ]));
    expect(draft).toMatchObject({ origin: "Roma Termini", destination: "Reggio di Calabria Centrale via Roma, binari regionali", travelDate: "2026-11-12", travelTime: "08:15" });
  });

  it("tiene separati due eventi alla stessa ora e unisce soltanto i duplicati identici", () => {
    const drafts = parseLocalJourneyIcs(calendar(
      ["SUMMARY:Roma Termini → Milano Centrale", "DTSTART:20261112T080000"],
      ["SUMMARY:Roma Termini → Napoli Centrale", "DTSTART:20261112T080000"],
      ["SUMMARY:Roma Termini → Napoli Centrale", "DTSTART:20261112T080000"],
    ));
    expect(drafts).toHaveLength(2);
    expect(new Set(drafts.map(calendarDraftKey)).size).toBe(2);
  });

  it("scarta eventi senza ora, senza tratta o in un’ora inesistente in Italia", () => {
    expect(parseLocalJourneyIcs(calendar(
      ["SUMMARY:Roma Termini → Milano Centrale", "DTSTART;VALUE=DATE:20261112"],
      ["SUMMARY:Riunione", "DTSTART:20261112T080000"],
      ["SUMMARY:Roma Termini → Milano Centrale", "DTSTART:20270328T023000"],
    ))).toEqual([]);
  });
});

describe("esportazione ICS", () => {
  const route = { id: 42, origin: "Reggio di Calabria Centrale", destination: "Milano Centrale; binario, da verificare", travelDate: "2026-07-12", travelTime: "23:30" };

  it("scrive ora italiana con TZID, VTIMEZONE, DTSTAMP e righe piegate entro 75 ottetti", () => {
    for (const zone of ["UTC", "America/New_York"]) {
      process.env.TZ = zone;
      const ics = buildLocalJourneyIcs(route, "ja", Date.UTC(2026, 10, 10, 12, 0, 5))!;
      const lines = ics.split("\r\n");
      expect(ics.endsWith("\r\n"), zone).toBe(true);
      expect(lines, zone).toContain("DTSTAMP:20261110T120005Z");
      expect(lines, zone).toContain("DTSTART;TZID=Europe/Rome:20260712T233000");
      // One hour later is the next Italian day.
      expect(lines, zone).toContain("DTEND;TZID=Europe/Rome:20260713T003000");
      expect(lines, zone).toContain("BEGIN:VTIMEZONE");
      expect(lines, zone).toContain("TZID:Europe/Rome");
      for (const line of lines) expect(new TextEncoder().encode(line).byteLength, line).toBeLessThanOrEqual(75);
      // No floating event time: only the VTIMEZONE rules carry a bare DTSTART.
      expect(ics, zone).not.toMatch(/^DTSTART:2026/m);
    }
  });

  it("si reimporta nella stessa tratta, data e ora", () => {
    const ics = buildLocalJourneyIcs(route, "it", Date.UTC(2026, 10, 10))!;
    expect(parseLocalJourneyIcs(ics)).toEqual([{ origin: route.origin, originStationCode: null, destination: "Milano Centrale; binario, da verificare", destinationStationCode: null, travelDate: "2026-07-12", travelTime: "23:30", plannedReminderMinutes: null }]);
  });

  it("non esporta una data impossibile", () => {
    expect(buildLocalJourneyIcs({ ...route, travelDate: "2026-02-30" })).toBeNull();
  });

  it("piega senza spezzare un carattere UTF-8", () => {
    const folded = foldIcsLine(`SUMMARY:${"→".repeat(40)}`);
    for (const line of folded.split("\r\n")) expect(new TextEncoder().encode(line).byteLength).toBeLessThanOrEqual(75);
    expect(folded.replace(/\r\n /g, "")).toBe(`SUMMARY:${"→".repeat(40)}`);
  });
});
