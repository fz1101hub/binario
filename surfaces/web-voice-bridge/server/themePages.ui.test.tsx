/** @vitest-environment jsdom */
import React from "react";
import axe from "axe-core";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "../client/src/contexts/ThemeContext";

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}

Object.defineProperty(globalThis, "ResizeObserver", { value: ResizeObserverMock, configurable: true });

vi.mock("@/components/DashboardLayout", () => ({
  default: ({ children }: { children: React.ReactNode }) => <div data-testid="dashboard-shell">{children}</div>,
}));

vi.mock("@/_core/hooks/useAuth", () => ({
  useAuth: () => ({ loading: false, user: null }),
}));

vi.mock("@/components/StationAutocompleteInput", () => ({
  StationAutocompleteInput: ({ label }: { label: string }) => <input aria-label={label} />,
}));

vi.mock("@/lib/trpc", () => {
  const mutation = { mutate: vi.fn(), isPending: false, error: null };
  return {
    trpc: {
      useUtils: () => ({ routes: { list: { invalidate: vi.fn() } }, stations: { listPreferences: { invalidate: vi.fn() } }, companion: { listDevices: { invalidate: vi.fn() } } }),
      routes: {
        list: { useQuery: () => ({ data: [], isLoading: false, error: null }) },
        create: { useMutation: () => mutation },
        setPinned: { useMutation: () => mutation },
        remove: { useMutation: () => mutation },
        archive: { useMutation: () => mutation },
        restore: { useMutation: () => mutation },
        import: { useMutation: () => mutation },
        reorder: { useMutation: () => mutation },
      },
      stations: {
        listPreferences: { useQuery: () => ({ data: [], isLoading: false, error: null, refetch: vi.fn() }) },
        markOpened: { useMutation: () => mutation },
        setFavorite: { useMutation: () => mutation },
        clearRecent: { useMutation: () => mutation },
      },
      voice: {
        status: { useQuery: () => ({ data: { configured: false, linked: false }, refetch: vi.fn() }) },
        unlink: { useMutation: () => mutation },
      },
      companion: {
        listDevices: { useQuery: () => ({ data: [], isLoading: false, error: null }) },
        issuePairingCode: { useMutation: () => mutation },
        revokeDevice: { useMutation: () => mutation },
        revokeAllDevices: { useMutation: () => mutation },
      },
      privacy: {
        exportData: { useQuery: () => ({ data: null, isFetching: false, refetch: vi.fn() }) },
        deleteData: { useMutation: () => mutation },
      },
      dictation: {
        transcribe: { useMutation: () => mutation },
      },
    },
  };
});

vi.mock("wouter", () => ({
  useLocation: () => ["/", vi.fn()],
}));

import Home from "../client/src/pages/Home";
import { StationBoardDetails } from "../client/src/pages/StationBoard";

afterEach(() => {
  cleanup();
  document.documentElement.className = "";
});

function renderWithTheme(node: React.ReactNode, theme: "light" | "dark") {
  localStorage.clear();
  document.documentElement.className = "";
  return render(<ThemeProvider defaultTheme={theme} switchable>{node}</ThemeProvider>);
}

describe("superfici ferroviarie e temi", () => {
  it.each(["light", "dark"] as const)("renderizza la Home ospite in tema %s senza richiedere un account", theme => {
    renderWithTheme(<Home />, theme);

    expect(screen.getByRole("heading", { name: "Binario" })).toBeTruthy();
    expect(screen.getByText(/organizza i tuoi spostamenti subito, senza creare un account/i)).toBeTruthy();
    expect(screen.getByRole("button", { name: /accedi facoltativamente/i })).toBeTruthy();
    expect(screen.getByText(/dati realtime non disponibili/i)).toBeTruthy();
    expect(document.querySelector(".binario-page")).toBeTruthy();
    expect(document.documentElement.classList.contains("dark")).toBe(theme === "dark");
  });

  it("non rileva violazioni WCAG automatiche critiche o gravi nella Home ospite", async () => {
    const { container } = renderWithTheme(<Home />, "light");

    const result = await axe.run(container, {
      runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22a", "wcag22aa"] },
      rules: { "color-contrast": { enabled: false } },
    });
    const blockingViolations = result.violations.filter(violation => violation.impact === "critical" || violation.impact === "serious");

    expect(blockingViolations).toEqual([]);
  });

  it.each(["light", "dark"] as const)("renderizza il Tabellone in tema %s con stato dati esplicito", theme => {
    renderWithTheme(<StationBoardDetails stationName="Roma Termini" stationCode="8300000" onBack={vi.fn()} />, theme);

    expect(screen.getByRole("heading", { name: "Roma Termini" })).toBeTruthy();
    expect(screen.getByText(/non vengono mostrati orari, ritardi, binari o disservizi/i)).toBeTruthy();
    expect(document.querySelector(".binario-page")).toBeTruthy();
    expect(document.documentElement.classList.contains("dark")).toBe(theme === "dark");
  });
});
