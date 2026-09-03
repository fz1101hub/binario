/** @vitest-environment jsdom */
import React from "react";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NearbyStationsPanel } from "../client/src/components/NearbyStationsPanel";
import { loadCanonicalStationCatalog } from "../client/src/lib/stationCatalogClient";

vi.mock("../client/src/lib/stationCatalogClient", () => ({ loadCanonicalStationCatalog: vi.fn() }));

const catalog = [{ stationId: "roma", name: "Roma Termini", code: "8300000", latitude: 41.901, longitude: 12.501, aliases: [], confidence: "Alta", trenitaliaLocationId: null }];

function setGeolocation(getCurrentPosition: Geolocation["getCurrentPosition"]) {
  Object.defineProperty(navigator, "geolocation", { configurable: true, value: { getCurrentPosition } });
}

describe("NearbyStationsPanel", () => {
  afterEach(() => cleanup());

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(loadCanonicalStationCatalog).mockResolvedValue(catalog);
  });

  it("spiega il fallback quando il permesso posizione è negato", async () => {
    setGeolocation((_success, error) => error?.({ code: 1, message: "denied", PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 } as GeolocationPositionError));
    render(<NearbyStationsPanel onOpenStation={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Trova" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/permesso posizione non disponibile/i);
  });

  it("calcola localmente, non persiste la posizione e apre una stazione canonica", async () => {
    const onOpenStation = vi.fn();
    setGeolocation(success => success({ coords: { latitude: 41.901, longitude: 12.501 } } as GeolocationPosition));
    render(<NearbyStationsPanel onOpenStation={onOpenStation} />);
    fireEvent.click(screen.getByRole("button", { name: "Trova" }));
    const station = await screen.findByRole("button", { name: /roma termini/i });
    fireEvent.click(station);
    await waitFor(() => expect(onOpenStation).toHaveBeenCalledWith(catalog[0]));
  });

  it("rende indisponibilità esplicita quando il catalogo non ha coordinate", async () => {
    vi.mocked(loadCanonicalStationCatalog).mockResolvedValue(catalog.map(station => ({ ...station, latitude: null, longitude: null })));
    setGeolocation(success => success({ coords: { latitude: 41.901, longitude: 12.501 } } as GeolocationPosition));
    render(<NearbyStationsPanel onOpenStation={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Trova" }));
    expect((await screen.findByRole("status")).textContent).toMatch(/nessuna stazione con coordinate disponibili/i);
  });
});
