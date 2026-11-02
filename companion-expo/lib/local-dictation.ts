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
  // The added grammars follow the example in planner.dictationDescription for each language.
  // Station names stay as the catalogue spells them; unknown phrasings only leave fields empty.
  ar: {
    route: /(?:^|\s)من\s+(.+?)\s+(?:إلى|الى)\s+(.+?)(?=\s+(?:غدًا|غدا|اليوم|الساعة\s+\d{1,2}(?::\d{2})?)|$)/u,
    time: /الساعة\s+([01]?\d|2[0-3])(?::([0-5]\d))?/u,
    date: /(?:^|\s)(غدًا|غدا|اليوم)(?=\s|$)/u,
    tomorrow: new Set(["غدًا", "غدا"]),
  },
  hi: {
    route: /^(.+?)\s+से\s+(.+?)(?:\s+(?:तक|के लिए))?(?=\s+(?:कल|आज|\d{1,2}(?::\d{2})?\s*बजे)|$)/u,
    time: /([01]?\d|2[0-3])(?::([0-5]\d))?\s*बजे/u,
    date: /(?:^|\s)(कल|आज)(?=\s|$)/u,
    tomorrow: new Set(["कल"]),
  },
  id: {
    route: /(?:^|\s)dari\s+(.+?)\s+(?:ke|menuju)\s+(.+?)(?=\s+(?:besok|hari\s+ini|tanggal\s+\d{1,2}[/.\-]\d{1,2}(?:[/.\-]\d{2,4})?|(?:pukul|jam)\s+\d{1,2}(?:[:.]\d{2})?)|$)/iu,
    time: /(?:pukul|jam)\s+([01]?\d|2[0-3])(?:[:.]([0-5]\d))?/iu,
    date: /(?:^|\s)(besok|hari ini)(?=\s|$)/iu,
    tomorrow: new Set(["besok"]),
  },
  ja: {
    route: /^(.+?)\s*から\s*(.+?)\s*(?:まで|行き)?(?=\s*(?:明日|今日|あした|きょう|\d{1,2}\s*(?:[:：]|時))|$)/u,
    time: /([01]?\d|2[0-3])\s*(?:[:：]|時)\s*([0-5]\d)?/u,
    date: /(明日|今日|あした|きょう)/u,
    tomorrow: new Set(["明日", "あした"]),
  },
  ko: {
    route: /^(.+?)\s*에서\s*(.+?)\s*(?:까지|으로|로)?(?=\s*(?:내일|오늘|\d{1,2}\s*(?:[:：]|시))|$)/u,
    time: /([01]?\d|2[0-3])\s*(?:[:：]|시)\s*([0-5]\d)?/u,
    date: /(내일|오늘)/u,
    tomorrow: new Set(["내일"]),
  },
  my: {
    route: /^(.+?)\s*မှ\s+(.+?)\s*(?:သို့|ကို)?(?=\s*(?:မနက်ဖြန်|ယနေ့|ဒီနေ့|\d{1,2}[:：]\d{2})|$)/u,
    time: /([01]?\d|2[0-3])[:：]([0-5]\d)/u,
    date: /(မနက်ဖြန်|ယနေ့|ဒီနေ့)/u,
    tomorrow: new Set(["မနက်ဖြန်"]),
  },
  pt: {
    route: /(?:^|\s)de\s+(.+?)\s+(?:para|até|a)\s+(.+?)(?=\s+(?:amanhã|hoje|(?:no\s+)?dia\s+\d{1,2}[/.\-]\d{1,2}(?:[/.\-]\d{2,4})?|à?s\s+\d{1,2}(?::\d{2})?)|$)/iu,
    time: /(?:^|\s)à?s\s+([01]?\d|2[0-3])(?::([0-5]\d))?/iu,
    date: /(?:^|\s)(amanhã|hoje)(?=\s|$)/iu,
    tomorrow: new Set(["amanhã"]),
  },
  ru: {
    route: /(?:^|\s)(?:от|из)\s+(.+?)\s+(?:до|в)\s+(.+?)(?=\s+(?:завтра|сегодня|в\s+\d{1,2}(?::\d{2})?)|$)/iu,
    time: /(?:^|\s)в\s+([01]?\d|2[0-3])(?::([0-5]\d))?/iu,
    date: /(?:^|\s)(завтра|сегодня)(?=\s|$)/iu,
    tomorrow: new Set(["завтра"]),
  },
  th: {
    route: /จาก\s*(.+?)\s*(?:ไป|ถึง)\s*(.+?)(?=\s*(?:พรุ่งนี้|วันนี้|เวลา|\d{1,2}[:.]\d{2})|$)/u,
    time: /([01]?\d|2[0-3])[:.]([0-5]\d)/u,
    date: /(พรุ่งนี้|วันนี้)/u,
    tomorrow: new Set(["พรุ่งนี้"]),
  },
  tr: {
    route: /^(.+?)['’]?(?:dan|den|tan|ten)\s+(.+?)['’]?(?:ya|ye|a|e|na|ne)(?=\s+(?:yarın|bugün|saat\s+\d)|$)/iu,
    time: /(?:^|\s)saat\s+([01]?\d|2[0-3])(?:[:.]([0-5]\d))?/iu,
    date: /(?:^|\s)(yarın|bugün)(?=\s|$)/iu,
    tomorrow: new Set(["yarın"]),
  },
  vi: {
    route: /(?:^|\s)từ\s+(.+?)\s+(?:đến|tới)\s+(.+?)(?=\s+(?:ngày\s+mai|hôm\s+nay|lúc\s+\d{1,2}(?:[:h]\d{2})?)|$)/iu,
    time: /(?:^|\s)lúc\s+([01]?\d|2[0-3])(?:[:h]([0-5]\d))?/iu,
    date: /(?:^|\s)(ngày mai|hôm nay)(?=\s|$)/iu,
    tomorrow: new Set(["ngày mai"]),
  },
  zh: {
    route: /从\s*(.+?)\s*(?:到|去|至)\s*(.+?)(?=\s*(?:明天|今天|\d{1,2}\s*(?:[:：]|点))|$)/u,
    time: /([01]?\d|2[0-3])\s*(?:[:：]|点)\s*([0-5]\d)?/u,
    date: /(明天|今天)/u,
    tomorrow: new Set(["明天"]),
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
