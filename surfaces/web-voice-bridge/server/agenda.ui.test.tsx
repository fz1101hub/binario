/** @vitest-environment jsdom */
import React from "react";
import axe from "axe-core";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { LOCAL_GUEST_ROUTES_STORAGE_KEY } from "../shared/localGuestRoutes";
import { LOCAL_JOURNEY_ITINERARIES_STORAGE_KEY } from "../shared/localJourneyItineraries";
import { journeyToIcs } from "../shared/localJourneyToolkit";
import { ThemeProvider } from "../client/src/contexts/ThemeContext";

const setLocation = vi.fn();

vi.mock("@/components/DashboardLayout", () => ({
  default: ({ children }: { children: React.ReactNode }) => <div data-testid="dashboard-shell">{children}</div>,
}));

vi.mock("wouter", () => ({
  useLocation: () => ["/agenda", setLocation],
}));

import Agenda from "../client/src/pages/Agenda";

function renderAgenda() {
  return render(<ThemeProvider defaultTheme="light" switchable><Agenda /></ThemeProvider>);
}

function storedRoute() {
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  const travelDate = `${tomorrow.getFullYear()}-${String(tomorrow.getMonth() + 1).padStart(2, "0")}-${String(tomorrow.getDate()).padStart(2, "0")}`;
  return {
    id: "route-1",
    origin: "Roma Termini",
    destination: "Firenze S. M. Novella",
    originStationCode: "S001",
    destinationStationCode: "S002",
    travelDate,
    travelTime: "08:30",
    archivedAt: null,
    completedAt: null,
    completedOccurrenceDates: [],
    reminderMinutes: 20,
    recurrence: null,
    checklistTemplateIds: [],
    checklist: [],
    createdAt: "2026-09-01T00:00:00.000Z",
  };
}

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem(LOCAL_GUEST_ROUTES_STORAGE_KEY, JSON.stringify([storedRoute()]));
  setLocation.mockClear();
});

afterEach(cleanup);

describe("Agenda locale", () => {
  it("rende il viaggio locale con stato programmato e filtro promemoria", () => {
    renderAgenda();

    expect(screen.getByRole("heading", { name: /agenda viaggi/i })).toBeTruthy();
    expect(screen.getByText(/pronto per partire/i)).toBeTruthy();
    expect(screen.getByText(/checklist viaggio/i)).toBeTruthy();
    expect(screen.getAllByRole("button", { name: /scarica .ics/i }).length).toBeGreaterThan(0);
    expect(screen.getByRole("heading", { name: /roma termini.*firenze/i })).toBeTruthy();
    expect(screen.getAllByText(/programmato, non realtime/i).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole("button", { name: "Promemoria" }));
    expect(screen.getByText(/promemoria 20 min/i)).toBeTruthy();
  });

  it("archivia una tratta solo dopo un’azione esplicita e aggiorna l’archivio browser", () => {
    renderAgenda();

    fireEvent.click(screen.getByRole("button", { name: "Archivia" }));
    const persisted = JSON.parse(localStorage.getItem(LOCAL_GUEST_ROUTES_STORAGE_KEY) ?? "[]");
    expect(persisted[0].archivedAt).toEqual(expect.any(String));
  });

  it("mostra più eventi ICS in anteprima e li importa solo dopo la conferma", async () => {
    const existing = storedRoute();
    const additional = { ...storedRoute(), id: "route-2", origin: "Firenze S. M. Novella", destination: "Bologna Centrale", originStationCode: "S002", destinationStationCode: "S003", travelTime: "10:15" };
    const firstCalendar = journeyToIcs({ ...existing, reminderMinutes: 20 as const });
    const secondCalendar = journeyToIcs({ ...additional, reminderMinutes: 20 as const });
    expect(firstCalendar).not.toBeNull();
    expect(secondCalendar).not.toBeNull();
    const firstEvent = firstCalendar!.match(/BEGIN:VEVENT[\s\S]*?END:VEVENT/)?.[0] ?? "";
    const secondEvent = secondCalendar!.match(/BEGIN:VEVENT[\s\S]*?END:VEVENT/)?.[0] ?? "";
    const calendar = new File([`BEGIN:VCALENDAR\n${firstEvent}\n${secondEvent}\nEND:VCALENDAR`], "viaggi.ics", { type: "text/calendar" });
    renderAgenda();

    fireEvent.change(screen.getByLabelText(/importa file .ics/i), { target: { files: [calendar] } });
    expect(await screen.findByText(/anteprima importazione calendario/i)).toBeTruthy();
    expect(JSON.parse(localStorage.getItem(LOCAL_GUEST_ROUTES_STORAGE_KEY) ?? "[]")).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: /importa selezionate/i }));
    expect(JSON.parse(localStorage.getItem(LOCAL_GUEST_ROUTES_STORAGE_KEY) ?? "[]")).toHaveLength(2);
  });

  it("crea un itinerario a tappe e importa testo solo dopo la revisione", () => {
    const first = storedRoute();
    const second = { ...storedRoute(), id: "route-2", origin: "Firenze S. M. Novella", destination: "Bologna Centrale", originStationCode: "S002", destinationStationCode: "S003", travelTime: "10:30" };
    localStorage.setItem(LOCAL_GUEST_ROUTES_STORAGE_KEY, JSON.stringify([first, second]));
    renderAgenda();

    expect(screen.getByText(/itinerari a tappe/i)).toBeTruthy();
    fireEvent.click(screen.getByRole("checkbox", { name: /roma termini.*firenze/i }));
    fireEvent.click(screen.getByRole("checkbox", { name: /firenze.*bologna/i }));
    fireEvent.click(screen.getByRole("button", { name: /crea itinerario locale/i }));
    expect(JSON.parse(localStorage.getItem(LOCAL_JOURNEY_ITINERARIES_STORAGE_KEY) ?? "[]")).toHaveLength(1);
    expect(screen.getByRole("heading", { name: /modalità cambio/i })).toBeTruthy();
    expect(screen.getByText(/prossima tappa:/i)).toBeTruthy();

    fireEvent.change(screen.getByLabelText(/una riga per tratta/i), { target: { value: "Bologna Centrale → Milano Centrale · 14/09/2026 alle 12:00" } });
    fireEvent.click(screen.getByRole("button", { name: /crea anteprima/i }));
    expect(screen.getByText(/rivedi le tratte prima di aggiungerle/i)).toBeTruthy();
    expect(JSON.parse(localStorage.getItem(LOCAL_GUEST_ROUTES_STORAGE_KEY) ?? "[]")).toHaveLength(2);
    fireEvent.click(screen.getByRole("checkbox", { name: /ho verificato stazioni, data e ora/i }));
    fireEvent.click(screen.getByRole("button", { name: /importa selezionate/i }));
    expect(JSON.parse(localStorage.getItem(LOCAL_GUEST_ROUTES_STORAGE_KEY) ?? "[]")).toHaveLength(3);
  });

  it("non rileva violazioni WCAG automatiche critiche o gravi nell’agenda", async () => {
    const { container } = renderAgenda();
    const result = await axe.run(container, {
      runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22a", "wcag22aa"] },
      rules: { "color-contrast": { enabled: false } },
    });

    expect(result.violations.filter(violation => violation.impact === "critical" || violation.impact === "serious")).toEqual([]);
  });
});
