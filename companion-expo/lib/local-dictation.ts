import type { CompanionLocale } from "@/lib/i18n";

export type LocalDictationDraft = { origin: string; destination: string; travelDate: string; travelTime: string };

type DictationGrammar = {
  route: RegExp;
  time: RegExp;
  date: RegExp;
  tomorrow: ReadonlySet<string>;
};

const grammars: Record<CompanionLocale, DictationGrammar> = {
  it: {
    route: /(?:da\s+)(.+?)(?:\s+(?:a|verso)\s+)(.+?)(?=(?:\s+(?:domani|oggi|il\s+\d{1,2}[/.\-]\d{1,2}(?:[/.\-]\d{2,4})?|alle\s+\d{1,2}(?::\d{2})?))|$)/iu,
    time: /(?:alle\s+)([01]?\d|2[0-3])(?::([0-5]\d))?/iu,
    date: /\b(domani|oggi)\b/iu,
    tomorrow: new Set(["domani"]),
  },
  en: {
    route: /(?:from\s+)(.+?)(?:\s+to\s+)(.+?)(?=(?:\s+(?:tomorrow|today|on\s+\d{1,2}[/.\-]\d{1,2}(?:[/.\-]\d{2,4})?|at\s+\d{1,2}(?::\d{2})?))|$)/iu,
    time: /(?:at\s+)([01]?\d|2[0-3])(?::([0-5]\d))?/iu,
    date: /\b(tomorrow|today)\b/iu,
    tomorrow: new Set(["tomorrow"]),
  },
  de: {
    route: /(?:von\s+)(.+?)(?:\s+(?:nach|bis)\s+)(.+?)(?=(?:\s+(?:morgen|heute|am\s+\d{1,2}[/.\-]\d{1,2}(?:[/.\-]\d{2,4})?|um\s+\d{1,2}(?::\d{2})?))|$)/iu,
    time: /(?:um\s+)([01]?\d|2[0-3])(?::([0-5]\d))?/iu,
    date: /\b(morgen|heute)\b/iu,
    tomorrow: new Set(["morgen"]),
  },
  fr: {
    route: /(?:de\s+)(.+?)(?:\s+(?:à|vers)\s+)(.+?)(?=(?:\s+(?:demain|aujourd['’]hui|le\s+\d{1,2}[/.\-]\d{1,2}(?:[/.\-]\d{2,4})?|à\s+\d{1,2}(?::\d{2})?))|$)/iu,
    time: /(?:à\s+)([01]?\d|2[0-3])(?::([0-5]\d))?/iu,
    date: /\b(demain|aujourd['’]hui)\b/iu,
    tomorrow: new Set(["demain"]),
  },
  es: {
    route: /(?:de\s+)(.+?)(?:\s+(?:a|hacia)\s+)(.+?)(?=(?:\s+(?:mañana|hoy|el\s+\d{1,2}[/.\-]\d{1,2}(?:[/.\-]\d{2,4})?|a\s+las\s+\d{1,2}(?::\d{2})?))|$)/iu,
    time: /(?:a\s+las\s+)([01]?\d|2[0-3])(?::([0-5]\d))?/iu,
    date: /\b(mañana|hoy)\b/iu,
    tomorrow: new Set(["mañana"]),
  },
};

export function parseLocalDictation(input: string, now = new Date(), locale: CompanionLocale = "it"): LocalDictationDraft {
  const text = input.trim().replace(/\s+/g, " ");
  const grammar = grammars[locale];
  const route = grammar.route.exec(text);
  const time = grammar.time.exec(text);
  const date = grammar.date.exec(text);
  const day = new Date(now);
  if (date && grammar.tomorrow.has(date[1]!.toLocaleLowerCase(locale))) day.setDate(day.getDate() + 1);
  const travelDate = date ? `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, "0")}-${String(day.getDate()).padStart(2, "0")}` : "";
  return {
    origin: route?.[1]?.trim() ?? "",
    destination: route?.[2]?.trim() ?? "",
    travelDate,
    travelTime: time ? `${time[1]!.padStart(2, "0")}:${time[2] ?? "00"}` : "",
  };
}
