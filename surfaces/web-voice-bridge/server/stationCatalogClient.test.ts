import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { loadCanonicalStationCatalog, resetCanonicalStationCatalogForTests } from "../client/src/lib/stationCatalogClient";

const validCatalog = "station_id,station_name,station_code\n1,Roma Termini,RMTE\n";
const response = (ok: boolean, body: string, status = ok ? 200 : 503) => ({ ok, status, text: async () => body }) as Response;

describe("catalogo stazioni client", () => {
  beforeEach(() => { resetCanonicalStationCatalogForTests(); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("ripete una sola volta un errore transitorio e conserva il catalogo valido", async () => {
    const request = vi.fn().mockResolvedValueOnce(response(false, "", 503)).mockResolvedValueOnce(response(true, validCatalog));
    vi.stubGlobal("fetch", request);
    await expect(loadCanonicalStationCatalog()).resolves.toMatchObject([{ name: "Roma Termini", code: "RMTE" }]);
    expect(request).toHaveBeenCalledTimes(2);
  });

  it("non memoizza un catalogo non valido e consente un recupero in una richiesta successiva", async () => {
    const request = vi.fn().mockResolvedValueOnce(response(true, "station_id,station_name,station_code\n")).mockResolvedValueOnce(response(true, "station_id,station_name,station_code\n")).mockResolvedValueOnce(response(true, validCatalog));
    vi.stubGlobal("fetch", request);
    await expect(loadCanonicalStationCatalog()).rejects.toThrow(/record validi/i);
    await expect(loadCanonicalStationCatalog()).resolves.toMatchObject([{ name: "Roma Termini" }]);
    expect(request).toHaveBeenCalledTimes(3);
  });

  it("condivide una singola richiesta tra componenti concorrenti", async () => {
    const request = vi.fn().mockResolvedValue(response(true, validCatalog));
    vi.stubGlobal("fetch", request);
    const [first, second] = await Promise.all([loadCanonicalStationCatalog(), loadCanonicalStationCatalog()]);
    expect(first).toEqual(second);
    expect(request).toHaveBeenCalledTimes(1);
  });
});
