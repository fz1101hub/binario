/** @vitest-environment jsdom */
import React from "react";
import axe from "axe-core";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "../client/src/contexts/ThemeContext";

vi.mock("wouter", () => ({ useLocation: () => ["/", vi.fn()] }));

import { PublicDashboardLayout } from "../client/src/components/DashboardLayout";

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe("bypass della navigazione", () => {
  it("espone un link al contenuto principale e landmark nominati", () => {
    render(<ThemeProvider defaultTheme="light" switchable><PublicDashboardLayout><h1>Viaggi</h1></PublicDashboardLayout></ThemeProvider>);

    const skipLink = screen.getByRole("link", { name: "Vai al contenuto principale" });
    expect(skipLink.getAttribute("href")).toBe("#contenuto-principale");
    expect(screen.getByRole("navigation", { name: "Navigazione principale" })).toBeTruthy();
    const main = screen.getByRole("main");
    expect(main.id).toBe("contenuto-principale");
    expect(main.tabIndex).toBe(-1);
  });

  it("non rileva violazioni automatiche critiche o gravi nella shell pubblica", async () => {
    const { container } = render(<ThemeProvider defaultTheme="light" switchable><PublicDashboardLayout><h1>Viaggi</h1><p>Prepara una tratta locale.</p></PublicDashboardLayout></ThemeProvider>);

    const result = await axe.run(container, {
      rules: { "color-contrast": { enabled: false } },
    });
    const blockingViolations = result.violations.filter(violation => violation.impact === "critical" || violation.impact === "serious");

    expect(blockingViolations).toEqual([]);
  });
});
