/** @vitest-environment jsdom */
import React from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  invalidateDevices: vi.fn(),
  setManagedSync: vi.fn(),
}));

vi.mock("@/lib/trpc", () => ({
  trpc: {
    useUtils: () => ({ companion: { listDevices: { invalidate: mocks.invalidateDevices } } }),
    companion: {
      listDevices: { useQuery: () => ({ data: [{ id: 9, deviceName: "Telefono personale", platform: "android", expiresAt: new Date(Date.now() + 86_400_000), syncEnabled: true, revokedAt: null, lastUsedAt: null }], isLoading: false, error: null }) },
      issuePairingCode: { useMutation: () => ({ mutate: vi.fn(), isPending: false, error: null }) },
      revokeDevice: { useMutation: () => ({ mutate: vi.fn(), isPending: false }) },
      revokeAllDevices: { useMutation: () => ({ mutate: vi.fn(), isPending: false }) },
      setManagedDeviceSync: { useMutation: () => ({ mutate: mocks.setManagedSync, isPending: false }) },
    },
  },
}));

import { CompanionDeviceCard } from "../client/src/components/CompanionDeviceCard";

afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("card dispositivi companion", () => {
  it("mostra il consenso syncEnabled e consente di bloccarlo senza revocare il device", () => {
    render(<CompanionDeviceCard />);
    expect(screen.getByText(/autorizzata manualmente/i)).toBeTruthy();
    const control = screen.getByRole("button", { name: /blocca sincronizzazione/i });
    expect(control.getAttribute("aria-pressed")).toBe("true");
    fireEvent.click(control);
    expect(mocks.setManagedSync).toHaveBeenCalledWith({ id: 9, enabled: false });
    expect(screen.getByRole("button", { name: /^revoca$/i })).toBeTruthy();
  });
});
