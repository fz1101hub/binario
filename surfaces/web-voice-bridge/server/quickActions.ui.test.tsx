/** @vitest-environment jsdom */
import React from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QuickActionsCard } from "../client/src/components/QuickActionsCard";

afterEach(cleanup);
beforeEach(() => localStorage.clear());

describe("azioni rapide", () => {
  it("sposta il focus all’azione scelta dall’utente", () => {
    render(<><input id="route-origin" aria-label="Partenza" /><QuickActionsCard actions={[{ id: "plan", label: "Nuova tratta", description: "Vai alla partenza", targetId: "route-origin" }]} /></>);
    fireEvent.click(screen.getByRole("button", { name: /nuova tratta/i }));
    expect(document.activeElement).toBe(document.getElementById("route-origin"));
  });

  it("attiva la scorciatoia solo dopo consenso e non la usa mentre si scrive", () => {
    const onAgenda = vi.fn();
    render(<><input aria-label="Scrivi qui" /><QuickActionsCard actions={[{ id: "agenda", label: "Apri agenda", description: "Agenda locale", onInvoke: onAgenda }]} /></>);
    fireEvent.click(screen.getByRole("checkbox", { name: /attiva scorciatoie/i }));
    fireEvent.keyDown(window, { key: "a", altKey: true, shiftKey: true });
    expect(onAgenda).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(screen.getByRole("textbox", { name: "Scrivi qui" }), { key: "a", altKey: true, shiftKey: true });
    expect(onAgenda).toHaveBeenCalledTimes(1);
  });
});
