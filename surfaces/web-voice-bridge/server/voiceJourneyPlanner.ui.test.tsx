/** @vitest-environment jsdom */
import React from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const catalog = [
  { stationId: "8300000", name: "Roma Termini", aliases: ["Roma T."], code: "8300000", confidence: "verified" },
  { stationId: "8300017", name: "Milano Centrale", aliases: ["Milano C."], code: "8300017", confidence: "verified" },
];

const { loadCatalog } = vi.hoisted(() => ({ loadCatalog: vi.fn() }));

vi.mock("@/components/VoiceDictationButton", () => ({
  VoiceDictationButton: ({ onTranscript }: { onTranscript: (text: string) => void }) => <button type="button" onClick={() => onTranscript("Da Roma Termini a Milano Centrale domani alle otto e trenta")}>Detta l’intera tratta</button>,
}));

vi.mock("@/lib/stationCatalogClient", () => ({ loadCanonicalStationCatalog: loadCatalog }));

import { VoiceJourneyPlanner } from "../client/src/components/VoiceJourneyPlanner";

afterEach(cleanup);
beforeEach(() => {
  loadCatalog.mockReset();
  loadCatalog.mockResolvedValue(catalog);
});

describe("VoiceJourneyPlanner", () => {
  it("richiede revisione e applica soltanto stazioni canoniche e valori pronunciati", async () => {
    const onApply = vi.fn();
    render(<VoiceJourneyPlanner onApply={onApply} />);

    expect(screen.getByText(/la trascrizione è automatica, ma non prende decisioni sul viaggio/i)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Detta l’intera tratta" }));

    expect(await screen.findByText(/Partenza: Roma Termini/i)).toBeTruthy();
    expect(screen.getByText(/Arrivo: Milano Centrale/i)).toBeTruthy();
    expect(screen.getByText(/Data: \d{4}-\d{2}-\d{2}/i)).toBeTruthy();
    expect(screen.getByText(/Ora: 08:30/i)).toBeTruthy();
    expect(screen.getByText(/nessuna decisione sul viaggio è automatizzata/i)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /applica alla pianificazione/i }));

    expect(onApply).toHaveBeenCalledWith(expect.objectContaining({ origin: catalog[0], destination: catalog[1], date: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/), time: "08:30" }));
  });

  it("non applica la tratta quando il catalogo non è disponibile", async () => {
    loadCatalog.mockRejectedValueOnce(new Error("catalogo non disponibile"));
    const onApply = vi.fn();
    render(<VoiceJourneyPlanner onApply={onApply} />);

    fireEvent.click(screen.getByRole("button", { name: "Detta l’intera tratta" }));

    expect((await screen.findByRole("alert")).textContent).toMatch(/catalogo non è disponibile/i);
    expect(onApply).not.toHaveBeenCalled();
  });

  it("blocca l’applicazione quando una stazione non è risolta dal catalogo", async () => {
    render(<VoiceJourneyPlanner onApply={vi.fn()} />);
    const input = screen.getByRole("textbox", { name: "Testo della dettatura viaggio" });
    fireEvent.change(input, { target: { value: "Da Roma Termini a Stazione Inesistente" } });
    fireEvent.click(screen.getByRole("button", { name: "Interpreta" }));

    expect(await screen.findByText(/Arrivo: Stazione Inesistente/i)).toBeTruthy();
    expect(screen.getByRole("button", { name: /applica alla pianificazione/i }).hasAttribute("disabled")).toBe(true);
  });

  it("richiede e applica una scelta esplicita quando l’arrivo è ambiguo", async () => {
    loadCatalog.mockResolvedValueOnce([...catalog, { stationId: "8300020", name: "Milano Porta Garibaldi", aliases: ["Milano Garibaldi"], code: "8300020", confidence: "verified" }]);
    const onApply = vi.fn();
    render(<VoiceJourneyPlanner onApply={onApply} />);
    const input = screen.getByRole("textbox", { name: "Testo della dettatura viaggio" });
    fireEvent.change(input, { target: { value: "Da Roma Termini a Milano" } });
    fireEvent.click(screen.getByRole("button", { name: "Interpreta" }));

    const candidate = await screen.findByRole("button", { name: "Milano Centrale" });
    expect(screen.getByRole("button", { name: /applica alla pianificazione/i }).hasAttribute("disabled")).toBe(true);
    fireEvent.click(candidate);
    fireEvent.click(screen.getByRole("button", { name: /applica alla pianificazione/i }));

    expect(onApply).toHaveBeenCalledWith(expect.objectContaining({ destination: catalog[1] }));
  });
});
