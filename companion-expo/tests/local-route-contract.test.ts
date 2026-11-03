import { describe, expect, it } from "vitest";
import { applyLocalJourneyChecklistPreset, createLocalPlannedRoute, createReplannedLocalRouteDraft, createReturnLocalRouteDraft, displayLocalPlannedRouteName, filterLocalJourneyAgenda, getLocalJourneyPreparationPreset, isLocalPlannedRoute, localCountdownLabel, remainingLocalJourneyChecklistItems, selectNextLocalPlannedRoute, setLocalJourneyChecklistItem, setLocalPlannedRouteLabel, sortLocalPlannedRoutes } from "../lib/local-route-contract";
import { createLocalScheduledSnapshot, evaluateSnapshot } from "../lib/companion-snapshot";

describe("pianificazione locale Binario", () => {
  it("applica soltanto preset pertinenti alla preparazione del viaggio", () => {
    const route = createLocalPlannedRoute({ origin: "Roma Ostiense", originStationCode: "S08406", destination: "Roma San Pietro", destinationStationCode: "S08409", travelDate: "2030-01-02", travelTime: "09:30", plannedReminderMinutes: 15 }, 1, 1)!;
    expect(applyLocalJourneyChecklistPreset(route, "bici").map(item => item.id)).toEqual(["time-margin", "documents", "belongings", "bike"]);
  });
  it("ricava il filtro preparazione dalle sole voci checklist ferroviarie", () => {
    expect(getLocalJourneyPreparationPreset({ checklist: [{ id: "time-margin", completed: false }, { id: "assistance", completed: false }] })).toBe("assistenza");
    expect(getLocalJourneyPreparationPreset({ checklist: [{ id: "time-margin", completed: false }, { id: "bike", completed: false }] })).toBe("bici");
    expect(getLocalJourneyPreparationPreset({ checklist: [{ id: "time-margin", completed: false }, { id: "documents", completed: false }] })).toBe("pendolare");
  });
  it("accetta una tratta esplicita e normalizza il nome senza dati ferroviari", () => {
    expect(createLocalPlannedRoute({ origin: " Roma   Termini ", originStationCode: "S00001", destination: " Milano Centrale ", destinationStationCode: "S00002", travelDate: "2026-10-02", travelTime: "08:15", plannedReminderMinutes: null }, 9, 1)).toMatchObject({ id: 9, name: "Roma Termini → Milano Centrale", originStationCode: "S00001", destinationStationCode: "S00002", source: "device", plannedReminderMinutes: null });
  });
  it("rifiuta stazioni uguali, date e orari non validi", () => {
    expect(createLocalPlannedRoute({ origin: "Roma", destination: "Roma", travelDate: "2026-10-02", travelTime: "08:15", plannedReminderMinutes: 15 }, 1)).toBeNull();
    expect(createLocalPlannedRoute({ origin: "Roma", destination: "Milano", travelDate: "2026-14-02", travelTime: "25:15", plannedReminderMinutes: 15 }, 1)).toBeNull();
  });
  it("ordina le tratte per data e non presenta la pianificazione scaduta", () => {
    const routes = sortLocalPlannedRoutes([createLocalPlannedRoute({ origin: "Roma", destination: "Milano", travelDate: "2026-10-04", travelTime: "08:15", plannedReminderMinutes: 15 }, 2, 1)!, createLocalPlannedRoute({ origin: "Roma", destination: "Bologna", travelDate: "2026-10-02", travelTime: "08:15", plannedReminderMinutes: 15 }, 1, 1)!]);
    expect(routes[0]?.id).toBe(1);
    const snapshot = createLocalScheduledSnapshot(routes[0]!, new Date("2026-10-01T10:00:00").getTime());
    expect(evaluateSnapshot(snapshot, new Date("2026-10-03T20:00:00").getTime()).action).toBe("suppress");
  });
  it("seleziona solo la prossima tratta futura e calcola un countdown programmato", () => {
    const oldRoute = createLocalPlannedRoute({ origin: "Roma", destination: "Milano", travelDate: "2026-10-02", travelTime: "08:15", plannedReminderMinutes: 15 }, 1, 1)!;
    const nextRoute = createLocalPlannedRoute({ origin: "Roma", destination: "Bologna", travelDate: "2026-10-02", travelTime: "09:30", plannedReminderMinutes: 15 }, 2, 1)!;
    const now = new Date("2026-10-02T08:20:00").getTime();
    expect(selectNextLocalPlannedRoute([nextRoute, oldRoute], now)?.id).toBe(2);
    expect(localCountdownLabel(nextRoute, now)).toBe("Tra 1 h 10 min");
    expect(selectNextLocalPlannedRoute([oldRoute], now)).toBeNull();
  });
  it("filtra imminenti, settimana e archivio con il solo orario programmato locale", () => {
    const now = new Date("2026-10-02T08:00:00").getTime();
    const oldRoute = createLocalPlannedRoute({ origin: "Roma", destination: "Pisa", travelDate: "2026-10-01", travelTime: "08:00", plannedReminderMinutes: null }, 1, 1)!;
    const todayRoute = createLocalPlannedRoute({ origin: "Roma", destination: "Milano", travelDate: "2026-10-02", travelTime: "08:15", plannedReminderMinutes: null }, 2, 1)!;
    const nextWeekRoute = createLocalPlannedRoute({ origin: "Roma", destination: "Napoli", travelDate: "2026-10-09", travelTime: "08:15", plannedReminderMinutes: null }, 3, 1)!;
    expect(filterLocalJourneyAgenda([nextWeekRoute, oldRoute, todayRoute], "upcoming", now).map(route => route.id)).toEqual([2, 3]);
    expect(filterLocalJourneyAgenda([nextWeekRoute, oldRoute, todayRoute], "week", now).map(route => route.id)).toEqual([2]);
    expect(filterLocalJourneyAgenda([nextWeekRoute, oldRoute, todayRoute], "archive", now).map(route => route.id)).toEqual([1]);
  });
  it("inizializza una checklist locale non sensibile e ne calcola lo stato residuo", () => {
    const route = createLocalPlannedRoute({ origin: "Roma", destination: "Milano", travelDate: "2026-10-02", travelTime: "08:15", plannedReminderMinutes: 15 }, 5, 1)!;
    expect(remainingLocalJourneyChecklistItems(route)).toBe(3);
    expect(remainingLocalJourneyChecklistItems(setLocalJourneyChecklistItem(route, "documents", true))).toBe(2);
  });

  it("prepara ritorno e ripianifica senza conservare data, ora o checklist completata", () => {
    const route = createLocalPlannedRoute({ origin: "Roma", originStationCode: "S001", destination: "Milano", destinationStationCode: "S002", travelDate: "2030-10-02", travelTime: "08:15", plannedReminderMinutes: 30 }, 5, 1)!;
    const replanned = createReplannedLocalRouteDraft(setLocalJourneyChecklistItem(route, "documents", true));
    expect(replanned).toMatchObject({ origin: "Roma", destination: "Milano", travelDate: "", travelTime: "", plannedReminderMinutes: 30 });
    expect(replanned.checklist.every(item => !item.completed)).toBe(true);
    expect(createReturnLocalRouteDraft(route)).toMatchObject({ origin: "Milano", destination: "Roma", originStationCode: "S002", destinationStationCode: "S001", travelDate: "", travelTime: "" });
  });

  it("mantiene le tratte preesistenti valide e ordina prima priorità ed ordine manuale", () => {
    const first = createLocalPlannedRoute({ origin: "Roma", destination: "Milano", travelDate: "2030-06-01", travelTime: "10:00", plannedReminderMinutes: 15 }, 1, 1)!;
    const second = { ...createLocalPlannedRoute({ origin: "Roma", destination: "Napoli", travelDate: "2030-05-01", travelTime: "08:00", plannedReminderMinutes: 15 }, 2, 1)!, pinned: true, manualOrder: 4 };
    const third = { ...createLocalPlannedRoute({ origin: "Roma", destination: "Bologna", travelDate: "2030-04-01", travelTime: "09:00", plannedReminderMinutes: 15 }, 3, 1)!, manualOrder: 1 };
    expect(isLocalPlannedRoute({ ...first, customLabel: undefined, pinned: undefined, manualOrder: undefined })).toBe(true);
    expect(sortLocalPlannedRoutes([first, third, second]).map(route => route.id)).toEqual([2, 3, 1]);
    expect(displayLocalPlannedRouteName(setLocalPlannedRouteLabel(first, "Pendolare Ostiense"))).toBe("Pendolare Ostiense");
  });
});
