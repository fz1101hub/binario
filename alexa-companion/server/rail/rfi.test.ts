import { readFileSync } from "node:fs";
import { describe, expect, it, vi } from "vitest";
import { parseRfiBoard, RfiLiveDepartures } from "./rfi";

const now = Date.parse("2026-10-27T00:46:42Z");
const fixture = (name = "rfi-milano-20261027.html") => readFileSync(new URL(`../../../contracts/rail-live-fixtures/${name}`, import.meta.url), "utf8");

describe("public RFI live data", () => {
  it("preserves source timestamp and real monitor fields", () => {
    const board = parseRfiBoard(fixture(), now);
    expect(board.observedAt).toBe(Date.parse("2026-10-27T00:46:38Z"));
    expect(board.departures.find(train => train.number === "2451")).toMatchObject({
      delayMinutes: 0, cancelled: false, scheduledAt: Date.parse("2026-10-27T05:15:00+01:00"),
    });
    expect(board.departures.find(train => train.number === "2451")?.stopNames.has("LODI")).toBe(true);
  });

  it("uses a published delay to compute the expected departure", () => {
    const delayed = fixture().replaceAll("&quot;Nessuno&quot;", "&quot;15 minuti&quot;");
    const train = parseRfiBoard(delayed, now).departures.find(train => train.number === "2451");
    expect(train?.delayMinutes).toBe(15);
    expect(train?.expectedAt).toBe(Date.parse("2026-10-27T05:30:00+01:00"));
  });

  it("does not relabel an old monitor as current after a successful fetch", () => {
    expect(() => parseRfiBoard(fixture().replaceAll("27/10/2026", "26/10/2026"), now)).toThrow("stale");
  });

  it("matches an intermediate commuter stop and shares one public-station request", async () => {
    const fetch = vi.fn(async () => fixture());
    const source = new RfiLiveDepartures(fetch, () => now);
    const [first, second] = await Promise.all([source.get("Milano Centrale", "Lodi"), source.get("Milano Centrale", "Bologna Centrale")]);
    expect(first?.departures.some(train => train.number === "2451")).toBe(true);
    expect(second?.departures.some(train => train.number === "2451")).toBe(true);
    expect(fetch).toHaveBeenCalledOnce();
    const url = fetch.mock.calls[0][0] as URL;
    expect(url.protocol).toBe("https:");
    expect(url.searchParams.get("placeId")).toBe("1728");
    expect(url.searchParams.has("destination")).toBe(false);
  });

  it("rejects a response for another station and backs off a failing source", async () => {
    const fetch = vi.fn(async () => fixture("rfi-roma-20261027.html"));
    const source = new RfiLiveDepartures(fetch, () => now);
    expect(await source.get("Milano Centrale", "Roma Tiburtina")).toBeUndefined();
    expect(await source.get("Milano Centrale", "Roma Tiburtina")).toBeUndefined();
    expect(fetch).toHaveBeenCalledOnce();
  });

  it("never invents an ID for an unknown station", async () => {
    const fetch = vi.fn();
    expect(await new RfiLiveDepartures(fetch).get("Unknown station", "Lodi")).toBeUndefined();
    expect(fetch).not.toHaveBeenCalled();
  });

  it.runIf(process.env.BINARIO_VERIFY_LIVE === "true")("reaches current departures with the production HTTPS fetcher", async () => {
    const failures: string[] = [];
    const result = await new RfiLiveDepartures(undefined, Date.now, kind => failures.push(kind)).get("Roma Termini", "Roma Tiburtina");
    expect(result, `Current RFI data missing: ${failures.join(", ")}`).toBeDefined();
    expect(result?.departures.length).toBeGreaterThan(0);
    expect(Date.now() - result!.observedAt).toBeLessThan(120_000);
    console.info("LIVE VERIFIED Alexa: Roma Termini → Roma Tiburtina", result!.departures.map(train => train.number), new Date(result!.observedAt).toISOString());
  }, 10_000);
});
