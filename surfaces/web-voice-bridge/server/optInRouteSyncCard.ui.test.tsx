// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import React, { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { OptInRouteSyncCard } from "../client/src/components/OptInRouteSyncCard";

const candidate = { localId: "local-1", state: "new" as const, route: { origin: "Roma", originStationCode: "ROM", destination: "Milano", destinationStationCode: "MIL", name: "Roma → Milano", travelDate: "2026-10-02", travelTime: "08:15" } };

function Harness() {
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  return <OptInRouteSyncCard candidates={[candidate]} selectedLocalIds={selected} isPreviewOpen={open} isDisabled={false} isPending={false} message="" error={false} onOpenPreview={() => setOpen(true)} onClosePreview={() => setOpen(false)} onToggleCandidate={id => setSelected(current => current.includes(id) ? [] : [id])} onConfirm={vi.fn()} onDisable={vi.fn()} onEnable={vi.fn()} />;
}

describe("card sincronizzazione opt-in", () => {
  it("non seleziona né copia tratte prima della scelta esplicita", () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: /esamina tratte/i }));
    expect((screen.getByRole("checkbox") as HTMLInputElement).checked).toBe(false);
    expect((screen.getByRole("button", { name: /conferma copia/i }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("checkbox"));
    expect((screen.getByRole("button", { name: /conferma copia di 1 tratta/i }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("rende non selezionabile un conflitto", () => {
    render(<OptInRouteSyncCard candidates={[{ ...candidate, state: "conflict", conflictingAccountRouteId: 1 }]} selectedLocalIds={[]} isPreviewOpen isDisabled={false} isPending={false} message="" error={false} onOpenPreview={vi.fn()} onClosePreview={vi.fn()} onToggleCandidate={vi.fn()} onConfirm={vi.fn()} onDisable={vi.fn()} onEnable={vi.fn()} />);
    expect((screen.getByRole("checkbox") as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText(/conflitto/i)).toBeTruthy();
  });
});

afterEach(cleanup);
