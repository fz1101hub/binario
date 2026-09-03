import { searchCanonicalStations, type CanonicalStation } from "./stationCatalog";

export type ParsedVoiceJourney = {
  transcript: string;
  originPhrase: string | null;
  destinationPhrase: string | null;
  date: string | null;
  time: string | null;
  notices: string[];
};

export type VoiceStationResolution = {
  phrase: string | null;
  status: "confirmed" | "needs-review" | "unresolved";
  station: CanonicalStation | null;
  candidates: CanonicalStation[];
};

export type VoiceJourneyReview = ParsedVoiceJourney & {
  origin: VoiceStationResolution;
  destination: VoiceStationResolution;
};

const months: Record<string, number> = {
  gennaio: 0, febbraio: 1, marzo: 2, aprile: 3, maggio: 4, giugno: 5,
  luglio: 6, agosto: 7, settembre: 8, ottobre: 9, novembre: 10, dicembre: 11,
};

const numbers: Record<string, number> = {
  zero: 0, uno: 1, una: 1, due: 2, tre: 3, quattro: 4, cinque: 5, sei: 6, sette: 7, otto: 8, nove: 9,
  dieci: 10, undici: 11, dodici: 12, tredici: 13, quattordici: 14, quindici: 15, sedici: 16, diciassette: 17,
  diciotto: 18, diciannove: 19, venti: 20, ventuno: 21, ventidue: 22, ventitre: 23, ventitré: 23,
  venticinque: 25, trenta: 30, trentacinque: 35, quaranta: 40, quarantacinque: 45, cinquanta: 50, cinquantacinque: 55,
};

function formatDate(date: Date) {
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 10);
}

function addDays(date: Date, days: number) {
  const result = new Date(date);
  result.setDate(result.getDate() + days);
  return formatDate(result);
}

function normalize(value: string) {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLocaleLowerCase("it")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function cleanStationPhrase(value: string) {
  return value
    .replace(/\b(?:per favore|grazie|oggi|domani|dopodomani)\b/gi, " ")
    .replace(/\b(?:il\s+)?\d{1,2}\s+(?:gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre)(?:\s+\d{4})?\b/gi, " ")
    .replace(/\b\d{1,2}[\/.\-]\d{1,2}(?:[\/.\-]\d{2,4})?\b/g, " ")
    .replace(/\b(?:alle|ore|per le)\s+(?:\d{1,2}(?:[:.]\d{2})?|[a-zà-ÿ]+(?:\s+e\s+[a-zà-ÿ]+)?)\b/gi, " ")
    .replace(/\s+/g, " ")
    .replace(/^[,.;:\-\s]+|[,.;:\-\s]+$/g, "")
    .trim();
}

function parseTime(text: string) {
  const explicit = /\b(?:alle|ore|per le)\s+(\d{1,2})(?:[:.](\d{2})|\s+e\s+(\d{1,2}))?\b/i.exec(text);
  if (explicit) {
    const hour = Number(explicit[1]);
    const minute = Number(explicit[2] ?? explicit[3] ?? 0);
    if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) return `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
  }

  const spoken = /\b(?:alle|ore|per le)\s+([a-zà-ÿ]+)(?:\s+e\s+([a-zà-ÿ]+))?\b/i.exec(text);
  if (!spoken) return null;
  const hour = numbers[normalize(spoken[1] ?? "")];
  const minute = numbers[normalize(spoken[2] ?? "")] ?? 0;
  if (hour === undefined || hour > 23 || minute > 59) return null;
  return `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
}

function parseDate(text: string, reference: Date) {
  const lowered = normalize(text);
  if (/\boggi\b/.test(lowered)) return addDays(reference, 0);
  if (/\bdomani\b/.test(lowered)) return addDays(reference, 1);
  if (/\bdopodomani\b/.test(lowered)) return addDays(reference, 2);

  const numeric = /\b(\d{1,2})[\/.\-](\d{1,2})(?:[\/.\-](\d{2,4}))?\b/.exec(text);
  if (numeric) {
    const day = Number(numeric[1]);
    const month = Number(numeric[2]) - 1;
    const explicitYear = numeric[3] ? Number(numeric[3]) : reference.getFullYear();
    const year = explicitYear < 100 ? 2000 + explicitYear : explicitYear;
    const value = new Date(year, month, day);
    if (value.getFullYear() === year && value.getMonth() === month && value.getDate() === day) return formatDate(value);
  }

  const spoken = /\b(?:il\s+)?(\d{1,2})\s+(gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre)(?:\s+(\d{4}))?\b/i.exec(text);
  if (!spoken) return null;
  const day = Number(spoken[1]);
  const month = months[normalize(spoken[2] ?? "")];
  const year = spoken[3] ? Number(spoken[3]) : reference.getFullYear();
  const value = new Date(year, month, day);
  return value.getFullYear() === year && value.getMonth() === month && value.getDate() === day ? formatDate(value) : null;
}

function parseRoute(text: string) {
  const explicit = /\b(?:da|parto\s+da|partenza\s+da)\s+(.+?)\s+\b(?:a|verso|fino\s+a|destinazione)\s+(.+)/i.exec(text);
  if (!explicit) return { originPhrase: null, destinationPhrase: null };
  return {
    originPhrase: cleanStationPhrase(explicit[1] ?? "") || null,
    destinationPhrase: cleanStationPhrase(explicit[2] ?? "") || null,
  };
}

export function parseItalianJourneyDictation(transcript: string, referenceDate = new Date()): ParsedVoiceJourney {
  const compactTranscript = transcript.replace(/\s+/g, " ").trim();
  const { originPhrase, destinationPhrase } = parseRoute(compactTranscript);
  const date = parseDate(compactTranscript, referenceDate);
  const time = parseTime(compactTranscript);
  const notices: string[] = [];
  if (!originPhrase || !destinationPhrase) notices.push("Indica entrambe le stazioni, ad esempio: da Roma Termini a Milano Centrale.");
  if (!date) notices.push("Nessuna data pronunciata: il valore già presente non verrà modificato.");
  if (!time) notices.push("Nessun orario pronunciato: il valore già presente non verrà modificato.");
  return { transcript: compactTranscript, originPhrase, destinationPhrase, date, time, notices };
}

function resolveVoiceStation(phrase: string | null, catalog: CanonicalStation[]): VoiceStationResolution {
  if (!phrase) return { phrase, status: "unresolved", station: null, candidates: [] };
  const query = normalize(phrase);
  const exact = catalog.filter(station => [station.name, station.code, ...station.aliases].some(value => normalize(value) === query));
  if (exact.length === 1) return { phrase, status: "confirmed", station: exact[0]!, candidates: [] };
  const candidates = searchCanonicalStations(catalog, phrase, 3);
  if (candidates.length > 0) return { phrase, status: "needs-review", station: null, candidates };
  return { phrase, status: "unresolved", station: null, candidates };
}

export function createVoiceJourneyReview(transcript: string, catalog: CanonicalStation[], referenceDate = new Date()): VoiceJourneyReview {
  const parsed = parseItalianJourneyDictation(transcript, referenceDate);
  return { ...parsed, origin: resolveVoiceStation(parsed.originPhrase, catalog), destination: resolveVoiceStation(parsed.destinationPhrase, catalog) };
}

export function confirmVoiceStation(resolution: VoiceStationResolution, station: CanonicalStation): VoiceStationResolution {
  return { ...resolution, status: "confirmed", station, candidates: [] };
}
