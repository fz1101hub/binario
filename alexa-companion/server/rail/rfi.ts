import stations from "../../../contracts/rail-sources/rfi_stations.v1.json";

export type LiveDeparture = {
  number: string;
  destination: string;
  scheduledAt: number;
  expectedAt: number;
  delayMinutes: number;
  cancelled: boolean;
  platform?: string;
  stopNames: Set<string>;
};
export type LiveDepartures = { observedAt: number; departures: LiveDeparture[] };
type Board = LiveDepartures & { station: string; expiresAt: number };
type Fetch = (url: URL) => Promise<string>;

export function stationKey(value: string) {
  return value.normalize("NFD").replace(/\p{M}+/gu, "").toUpperCase()
    .replace(/\bS\.\s*/g, "SAN ").replace(/[^A-Z0-9]/g, "");
}
const placeIDs = new Map(stations.stations.map(station => [stationKey(station.name), station.id]));

const entities: Record<string, string> = { "&nbsp;": " ", "&amp;": "&", "&quot;": '"', "&#39;": "'", "&apos;": "'", "&lt;": "<", "&gt;": ">" };
function text(value: string) {
  return value.replace(/<[^>]*>/g, " ").replace(/&(?:nbsp|amp|quot|apos|lt|gt);|&#39;/g, entity => entities[entity])
    .replace(/\s+/g, " ").trim();
}
function cell(id: string, row: string) {
  return row.match(new RegExp(`<td\\b[^>]*\\bid=["']${id}["'][^>]*>([\\s\\S]*?)</td>`, "i"))?.[1] ?? "";
}

const romeParts = new Intl.DateTimeFormat("en-GB", {
  timeZone: "Europe/Rome", year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
});
function parts(at: number) {
  return Object.fromEntries(romeParts.formatToParts(at).filter(part => part.type !== "literal").map(part => [part.type, Number(part.value)]));
}
function romeTime(year: number, month: number, day: number, hour: number, minute: number, second = 0) {
  const utc = Date.UTC(year, month - 1, day, hour, minute, second);
  const local = parts(utc);
  return utc - (Date.UTC(local.year, local.month - 1, local.day, local.hour, local.minute, local.second) - utc);
}

export function parseRfiBoard(html: string, fetchedAt: number): Board {
  if (html.length > 2_000_000) throw new Error("RFI response size limit");
  const timestamp = text(html).match(/aggiornato il\s+(\d{2})\/(\d{2})\/(\d{4})\s+alle ore\s+(\d{2}):(\d{2}):(\d{2})/i);
  const heading = html.match(/<h1\b[^>]*>([\s\S]*?)<\/h1>/i)?.[1];
  if (!timestamp || !heading) throw new Error("Invalid RFI monitor");
  const observedAt = romeTime(+timestamp[3], +timestamp[2], +timestamp[1], +timestamp[4], +timestamp[5], +timestamp[6]);
  if (observedAt > fetchedAt + 60_000 || observedAt + 120_000 <= fetchedAt) throw new Error("RFI monitor is stale");
  const departures: LiveDeparture[] = [];
  for (const match of html.matchAll(/<tr\b[^>]*\bname=["']treno["'][^>]*>([\s\S]*?)<\/tr>/gi)) {
    const row = match[1];
    const number = text(cell("RTreno", row));
    const destination = text(cell("RStazione", row));
    const time = text(cell("ROrario", row)).match(/^(\d{1,2}):(\d{2})$/);
    if (!/^\d{1,8}$/.test(number) || !destination || !time || +time[1] > 23 || +time[2] > 59) continue;
    const delayTag = row.match(/<td\b[^>]*\bid=["']RRitardo["'][^>]*>/i)?.[0] ?? "";
    const aria = delayTag.match(/aria-label\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/i);
    const delayText = text(cell("RRitardo", row));
    const label = text(aria?.[1] ?? aria?.[2] ?? aria?.[3] ?? delayText);
    const cancelled = /\b(?:CANCELLATO|SOPPRESSO)\b/i.test(delayText);
    const onTime = /\b(?:NESSUNO|IN ORARIO)\b/i.test(label);
    const delayValue = label.match(/\b(\d{1,4})\b/)?.[1];
    if (!cancelled && !onTime && delayValue === undefined) continue;
    const delayMinutes = cancelled ? 0 : Number(delayValue ?? 0);
    if (delayMinutes > 1_440) continue;
    const explicitDate = row.match(/\bbtn_(\d{4})(\d{2})(\d{2})/);
    const date = parts(observedAt);
    let scheduledAt = romeTime(
      explicitDate ? +explicitDate[1] : date.year, explicitDate ? +explicitDate[2] : date.month,
      explicitDate ? +explicitDate[3] : date.day, +time[1], +time[2]
    );
    if (!explicitDate && scheduledAt < observedAt - 12 * 3_600_000) scheduledAt += 86_400_000;
    const stopNames = new Set(Array.from(text(cell("RDettagli", row)).matchAll(/(?:FERMA A:|\s+-\s+)(.*?)\s*\(\d{1,2}:\d{2}\)/g), stop => stationKey(stop[1])));
    const platform = text(cell("RBinario", row));
    departures.push({
      number, destination, scheduledAt, expectedAt: scheduledAt + delayMinutes * 60_000,
      delayMinutes, cancelled, platform: ["", "PF", "N/D", "-"].includes(platform.toUpperCase()) ? undefined : platform.slice(0, 32), stopNames,
    });
  }
  return { station: text(heading), observedAt, expiresAt: Math.min(fetchedAt + 45_000, observedAt + 120_000), departures };
}

async function fetchBoard(url: URL): Promise<string> {
  const response = await fetch(url, {
    signal: AbortSignal.timeout(6_000), redirect: "error", credentials: "omit",
    headers: { "User-Agent": "Binario/1.0 (rail information client)", Accept: "text/html", "Cache-Control": "no-cache" },
  });
  if (!response.ok || !response.body) throw new Error("RFI unavailable");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let bytes = 0;
  let html = "";
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      bytes += chunk.value.length;
      if (bytes > 2_000_000) throw new Error("RFI response size limit");
      html += decoder.decode(chunk.value, { stream: true });
    }
    return html + decoder.decode();
  } finally { await reader.cancel(); }
}

/** Process-local, bounded cache keyed only by public station ID, shared across linked accounts. */
export class RfiLiveDepartures {
  private readonly cache = new Map<number, Board>();
  private readonly pending = new Map<number, Promise<Board>>();
  private readonly attempted = new Map<number, number>();
  constructor(
    private readonly fetch: Fetch = fetchBoard, private readonly now = Date.now,
    private readonly onFailure?: (kind: string) => void
  ) {}

  async get(origin: string, destination: string): Promise<LiveDepartures | undefined> {
    const placeID = placeIDs.get(stationKey(origin));
    if (!placeID) return undefined;
    try {
      const now = this.now();
      let board = this.cache.get(placeID);
      if (!board || board.expiresAt <= now) {
        let pending = this.pending.get(placeID);
        if (!pending) {
          if (now - (this.attempted.get(placeID) ?? 0) < 20_000) return undefined;
          for (const [id, time] of this.attempted) if (now - time >= 120_000) this.attempted.delete(id);
          this.attempted.set(placeID, now);
          const url = new URL("https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor");
          url.searchParams.set("placeId", String(placeID)); url.searchParams.set("arrivals", "False");
          pending = this.fetch(url).then(html => {
            const value = parseRfiBoard(html, this.now());
            if (placeIDs.get(stationKey(value.station)) !== placeID) throw new Error("RFI station mismatch");
            for (const [id, cached] of this.cache) if (cached.expiresAt <= this.now()) this.cache.delete(id);
            if (this.cache.size >= 16) this.cache.delete(this.cache.keys().next().value!);
            this.cache.set(placeID, value);
            return value;
          }).finally(() => { this.pending.delete(placeID); });
          this.pending.set(placeID, pending);
        }
        board = await pending;
      }
      const target = stationKey(destination);
      const date = parts(now);
      const departures = board.departures.filter(train => {
        const serviceDate = parts(train.scheduledAt);
        return train.expectedAt >= now - 300_000 && serviceDate.year === date.year && serviceDate.month === date.month && serviceDate.day === date.day
          && (stationKey(train.destination) === target || train.stopNames.has(target));
      });
      return departures.length ? { observedAt: board.observedAt, departures } : undefined;
    } catch (error) {
      this.onFailure?.(error instanceof Error ? error.name : "UnknownError");
      return undefined;
    }
  }
}

const live = new RfiLiveDepartures();
export const getLiveDepartures = (origin: string, destination: string) => live.get(origin, destination);
