// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("@/components/VoiceDictationButton", () => ({
  VoiceDictationButton: ({ label, onTranscript }: { label: string; onTranscript: (text: string) => void }) => (
    <button type="button" onClick={() => onTranscript("  Roma Termini  ")}>{label}</button>
  ),
}));

vi.mock("@/lib/stationCatalogClient", () => ({ loadCanonicalStationCatalog: vi.fn(() => Promise.resolve([{ stationId: "8300000", name: "Roma Termini", aliases: ["Roma T."], code: "8300000", confidence: "verified" }])) }));

import { StationAutocompleteInput } from "../client/src/components/StationAutocompleteInput";

afterEach(cleanup);

describe("StationAutocompleteInput con dettatura", () => {
  it("inserisce la trascrizione nel campo in forma modificabile e richiede una nuova selezione canonica", () => {
    const onChange = vi.fn();
    const onSelect = vi.fn();
    render(<StationAutocompleteInput id="origin" label="Da" value="" placeholder="Partenza" selectedStation={null} onChange={onChange} onSelect={onSelect} />);

    fireEvent.click(screen.getByRole("button", { name: "Detta da" }));

    expect(onChange).toHaveBeenCalledWith("Roma Termini");
    expect(onSelect).toHaveBeenCalledWith(null);
  });

  it("espone un combobox navigabile da tastiera con opzione attiva e classi dark coerenti", async () => {
    render(<StationAutocompleteInput id="station" label="Stazione" value="Roma" placeholder="Stazione" selectedStation={null} onChange={vi.fn()} onSelect={vi.fn()} />);

    const input = screen.getByRole("combobox", { name: "Stazione" });
    fireEvent.focus(input);
    const listbox = await screen.findByRole("listbox");
    const option = await screen.findByRole("option", { name: /Roma Termini/i });

    expect(listbox.className).toContain("dark:bg-card");
    expect(option.className).toContain("focus-visible:ring-ring");
    expect(option.className).toContain("dark:hover:bg-blue-950/40");
    fireEvent.keyDown(input, { key: "ArrowDown" });
    expect(input.getAttribute("aria-activedescendant")).toBe("station-option-8300000");
    fireEvent.keyDown(input, { key: "Enter" });
    expect(screen.getByRole("combobox", { name: "Stazione" }).getAttribute("aria-expanded")).toBe("false");
  });
});
