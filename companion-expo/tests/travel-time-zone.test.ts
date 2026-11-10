import { afterEach, describe, expect, it } from "vitest";
import { createLocalScheduledSnapshot } from "@/lib/companion-snapshot";
import { createLocalPlannedRoute, filterLocalJourneyAgenda, localCountdownLabel, localRouteScheduledAtUtcMs, selectNextLocalPlannedRoute } from "@/lib/local-route-contract";
import { deriveLocalItineraryChangeSummary } from "@/lib/local-journey-itineraries";
import { italianOffsetMinutesByEuRule, italianWallClock, italianWallClockToEpoch, travelEpochMs, zonedWallClockToEpoch } from "@/lib/travel-time-zone";

const deviceTimeZone = process.env.TZ;
function inDeviceTimeZone(timeZone: string, run: () => void) {
  process.env.TZ = timeZone;
  try { run(); } finally { process.env.TZ = deviceTimeZone; }
}
afterEach(() => { process.env.TZ = deviceTimeZone; });

const deviceZones = ["UTC", "Europe/Rome", "Europe/London", "America/New_York", "Asia/Tokyo", "Pacific/Auckland"];

describe("orari dei treni letti come ora italiana", () => {
  it("dà lo stesso istante qualunque sia il fuso del telefono", () => {
    for (const zone of deviceZones) inDeviceTimeZone(zone, () => {
      // Summer (CEST, UTC+2) and winter (CET, UTC+1).
      expect(travelEpochMs("2026-07-12", "09:00"), zone).toBe(Date.UTC(2026, 6, 12, 7, 0));
      expect(travelEpochMs("2026-11-12", "08:00"), zone).toBe(Date.UTC(2026, 10, 12, 7, 0));
      expect(travelEpochMs("2026-12-31", "23:59"), zone).toBe(Date.UTC(2026, 11, 31, 22, 59));
      expect(travelEpochMs("2027-01-01", "00:00"), zone).toBe(Date.UTC(2026, 11, 31, 23, 0));
    });
  });

  it("rifiuta date impossibili e gestisce i cambi d’ora italiani", () => {
    for (const zone of deviceZones) inDeviceTimeZone(zone, () => {
      expect(travelEpochMs("2026-02-30", "09:00"), zone).toBeNull();
      expect(travelEpochMs("2026-07-12", "24:00"), zone).toBeNull();
      expect(travelEpochMs("2026-07-12", "9:00"), zone).toBeNull();
      // 28 March 2027: 02:00–02:59 does not exist in Italy.
      expect(travelEpochMs("2027-03-28", "02:30", { strict: true }), zone).toBeNull();
      expect(travelEpochMs("2027-03-28", "02:30"), zone).toBe(Date.UTC(2027, 2, 28, 1, 30));
      expect(travelEpochMs("2027-03-28", "03:00", { strict: true }), zone).toBe(Date.UTC(2027, 2, 28, 1, 0));
      // 25 October 2026: 02:30 happens twice; the first (summer time) is the departure.
      expect(travelEpochMs("2026-10-25", "02:30", { strict: true }), zone).toBe(Date.UTC(2026, 9, 25, 0, 30));
      expect(travelEpochMs("2026-10-25", "03:00", { strict: true }), zone).toBe(Date.UTC(2026, 9, 25, 2, 0));
    });
  });

  it("usa la regola europea quando Intl non conosce i fusi, con gli stessi risultati", () => {
    const noIntl = () => null;
    for (let year = 2024; year <= 2032; year += 1) {
      for (let t = Date.UTC(year, 0, 1); t < Date.UTC(year + 1, 0, 1); t += 7 * 3_600_000 + 13 * 60_000) {
        expect(italianWallClock(t, noIntl), new Date(t).toISOString()).toEqual(italianWallClock(t));
      }
      for (const [month, day] of [[2, 31], [9, 31]] as const) {
        const lastSunday = new Date(Date.UTC(year, month, day));
        lastSunday.setUTCDate(day - lastSunday.getUTCDay());
        for (let t = lastSunday.getTime() - 3 * 3_600_000; t <= lastSunday.getTime() + 6 * 3_600_000; t += 15 * 60_000) {
          expect(italianWallClock(t, noIntl), new Date(t).toISOString()).toEqual(italianWallClock(t));
          const wall = italianWallClock(t);
          expect(italianWallClockToEpoch(wall, { intl: noIntl }), new Date(t).toISOString()).toBe(italianWallClockToEpoch(wall));
        }
      }
    }
    expect(italianOffsetMinutesByEuRule(Date.UTC(2026, 6, 1))).toBe(120);
    expect(italianOffsetMinutesByEuRule(Date.UTC(2026, 0, 1))).toBe(60);
  });

  it("riporta ogni istante alla stessa ora italiana", () => {
    for (let t = Date.UTC(2026, 2, 28); t < Date.UTC(2026, 2, 30); t += 5 * 60_000) {
      expect(italianWallClockToEpoch(italianWallClock(t)), new Date(t).toISOString()).toBe(t);
    }
  });

  it("converte un orario di un altro fuso noto a Intl", () => {
    expect(zonedWallClockToEpoch({ year: 2026, month: 7, day: 12, hour: 9, minute: 0 }, "Europe/London")).toBe(Date.UTC(2026, 6, 12, 8, 0));
    expect(zonedWallClockToEpoch({ year: 2026, month: 1, day: 12, hour: 9, minute: 0 }, "America/New_York")).toBe(Date.UTC(2026, 0, 12, 14, 0));
    expect(zonedWallClockToEpoch({ year: 2026, month: 1, day: 12, hour: 9, minute: 0 }, "Not/A_Zone")).toBeNull();
  });

  it("calcola scadenza, conto alla rovescia e prossima tratta sull’ora italiana con il telefono a Londra o New York", () => {
    const route = createLocalPlannedRoute({ origin: "Roma Termini", destination: "Firenze Santa Maria Novella", travelDate: "2026-07-12", travelTime: "09:00", plannedReminderMinutes: 15 }, 7, 1)!;
    const departure = Date.UTC(2026, 6, 12, 7, 0);
    for (const zone of ["Europe/London", "America/New_York"]) inDeviceTimeZone(zone, () => {
      expect(localRouteScheduledAtUtcMs(route), zone).toBe(departure);
      expect(createLocalScheduledSnapshot(route, departure - 3_600_000).provenance.expiresAtUtcMs, zone).toBe(departure);
      expect(localCountdownLabel(route, departure - 30 * 60_000, "en"), zone).toBe("In 30 min");
      // At 08:30 London time (07:30 UTC) the 09:00 Italian train has already left.
      expect(selectNextLocalPlannedRoute([route], Date.UTC(2026, 6, 12, 7, 30)), zone).toBeNull();
      expect(deriveLocalItineraryChangeSummary([7], [route], Date.UTC(2026, 6, 12, 7, 30)).nextSegment, zone).toBeNull();
      // "This week" counts Italian days: from 23:30 on 5 July in Italy the 12 July train is outside, from 00:30 on 6 July inside.
      expect(filterLocalJourneyAgenda([route], "week", Date.UTC(2026, 6, 5, 21, 30)), zone).toEqual([]);
      expect(filterLocalJourneyAgenda([route], "week", Date.UTC(2026, 6, 5, 22, 30)).map(item => item.id), zone).toEqual([7]);
    });
  });
});
