import { beforeEach, describe, expect, it, vi } from "vitest";

const storage = new Map<string, string>();
vi.mock("@react-native-async-storage/async-storage", () => ({
  default: {
    getItem: vi.fn(async (key: string) => storage.get(key) ?? null),
    setItem: vi.fn(async (key: string, value: string) => { storage.set(key, value); }),
    removeItem: vi.fn(async (key: string) => { storage.delete(key); }),
  },
}));

import { createLocalScheduledSnapshot, createUnavailableSnapshot, evaluateSnapshot } from "@/lib/companion-snapshot";
import { supportedLocales, translationDictionaries, type CompanionLocale } from "@/lib/i18n";
import { parseLocalDictation } from "@/lib/local-dictation";
import { createLocalJourneyTemplate, loadLocalJourneyTemplates, saveLocalJourneyTemplate } from "@/lib/local-journey-templates";
import { loadLocalJourneyTimingPreferences, saveLocalJourneyTimingPreferences } from "@/lib/local-journey-timing";
import { parseManualRouteTransfer, previewManualRouteTransfer } from "@/lib/manual-route-transfer-contract";
import { travelEpochMs } from "@/lib/travel-time-zone";

describe("servizi locali recuperati", () => {
  beforeEach(() => storage.clear());

  it("sopprime una pianificazione locale scaduta e non converte mai indisponibilità in realtime", () => {
    const snapshot = createLocalScheduledSnapshot({ id: 4, name: "Roma → Napoli", travelDate: "2030-01-02", travelTime: "09:30" }, 1);
    expect(snapshot.provenance.dataNature).toBe("scheduled");
    expect(evaluateSnapshot(snapshot, travelEpochMs("2030-01-02", "10:00")!).action).toBe("suppress");
    expect(createUnavailableSnapshot({ kind: "route", id: "none", label: "Nessuna" }).provenance.dataNature).toBe("unavailable");
  });

  it("estrae soltanto una bozza dalla dettatura, sempre revisionabile", () => {
    const now = new Date("2030-01-02T12:00:00");
    const expected = { origin: "Roma Ostiense", destination: "Roma San Pietro", travelDate: "2030-01-03", travelTime: "09:35" };
    expect(parseLocalDictation("Da Roma Ostiense a Roma San Pietro domani alle 9:35", now, "it")).toEqual(expected);
    expect(parseLocalDictation("From Roma Ostiense to Roma San Pietro tomorrow at 9:35", now, "en")).toEqual(expected);
    expect(parseLocalDictation("Von Roma Ostiense nach Roma San Pietro morgen um 9:35", now, "de")).toEqual(expected);
    expect(parseLocalDictation("De Roma Ostiense à Roma San Pietro demain à 9:35", now, "fr")).toEqual(expected);
    expect(parseLocalDictation("De Roma Ostiense a Roma San Pietro mañana a las 9:35", now, "es")).toEqual(expected);
    expect(parseLocalDictation("ritardo del treno", now)).toEqual({ origin: "", destination: "", travelDate: "", travelTime: "" });
  });

  it("riconosce la dettatura delle lingue aggiunte con la frase d'esempio dell'interfaccia", () => {
    const now = new Date("2030-01-02T12:00:00");
    const expected = { origin: "Roma Ostiense", destination: "Roma San Pietro", travelDate: "2030-01-03", travelTime: "09:35" };
    const phrases = {
      ar: "من Roma Ostiense إلى Roma San Pietro غدًا الساعة 9:35",
      hi: "Roma Ostiense से Roma San Pietro कल 9:35 बजे",
      id: "Dari Roma Ostiense ke Roma San Pietro besok pukul 9.35",
      ja: "Roma Ostiense から Roma San Pietro まで 明日 9時35分",
      ko: "Roma Ostiense에서 Roma San Pietro까지 내일 9시 35분",
      my: "Roma Ostiense မှ Roma San Pietro သို့ မနက်ဖြန် 9:35",
      pt: "De Roma Ostiense para Roma San Pietro amanhã às 9:35",
      ru: "От Roma Ostiense до Roma San Pietro завтра в 9:35",
      th: "จาก Roma Ostiense ไป Roma San Pietro พรุ่งนี้ เวลา 9:35",
      tr: "Roma Ostiense'den Roma San Pietro'ya yarın saat 9:35",
      vi: "Từ Roma Ostiense đến Roma San Pietro ngày mai lúc 9:35",
      zh: "从 Roma Ostiense 到 Roma San Pietro 明天 9点35分",
    } as const;
    for (const [locale, phrase] of Object.entries(phrases))
      expect(parseLocalDictation(phrase, now, locale as keyof typeof phrases), locale).toEqual(expected);
    // Each interface example (planner.dictationDescription) is itself understood.
    const examples = {
      ar: "من Roma Termini إلى Firenze Santa Maria Novella غدًا الساعة 09:35",
      hi: "Roma Termini से Firenze Santa Maria Novella कल 09:35 बजे",
      id: "Dari Roma Termini ke Firenze Santa Maria Novella besok pukul 09:35",
      ja: "Roma Termini から Firenze Santa Maria Novella まで 明日 09:35",
      ko: "Roma Termini에서 Firenze Santa Maria Novella까지 내일 09:35",
      my: "Roma Termini မှ Firenze Santa Maria Novella သို့ မနက်ဖြန် 09:35",
      pt: "De Roma Termini para Firenze Santa Maria Novella amanhã às 09:35",
      ru: "От Roma Termini до Firenze Santa Maria Novella завтра в 09:35",
      th: "จาก Roma Termini ไป Firenze Santa Maria Novella พรุ่งนี้ เวลา 09:35",
      tr: "Roma Termini'den Firenze Santa Maria Novella'ya yarın saat 09:35",
      vi: "Từ Roma Termini đến Firenze Santa Maria Novella ngày mai lúc 09:35",
      zh: "从 Roma Termini 到 Firenze Santa Maria Novella 明天 09:35",
    } as const;
    for (const [locale, phrase] of Object.entries(examples)) {
      expect(translationDictionaries[locale as keyof typeof examples]["planner.dictationDescription"], locale).toContain(phrase);
      expect(parseLocalDictation(phrase, now, locale as keyof typeof examples), locale).toEqual({
        origin: "Roma Termini", destination: "Firenze Santa Maria Novella", travelDate: "2030-01-03", travelTime: "09:35",
      });
    }
  });

  it("legge le ore serali 20–23 in ogni lingua senza ridurle alle 02", () => {
    const now = new Date("2030-01-02T12:00:00");
    const templates: Record<CompanionLocale, string> = {
      it: "Da Roma Ostiense a Roma San Pietro domani alle {h}:{m}",
      en: "From Roma Ostiense to Roma San Pietro tomorrow at {h}:{m}",
      de: "Von Roma Ostiense nach Roma San Pietro morgen um {h}:{m}",
      fr: "De Roma Ostiense à Roma San Pietro demain à {h}:{m}",
      es: "De Roma Ostiense a Roma San Pietro mañana a las {h}:{m}",
      ar: "من Roma Ostiense إلى Roma San Pietro غدًا الساعة {h}:{m}",
      hi: "Roma Ostiense से Roma San Pietro कल {h}:{m} बजे",
      id: "Dari Roma Ostiense ke Roma San Pietro besok pukul {h}.{m}",
      ja: "Roma Ostiense から Roma San Pietro まで 明日 {h}時{m}分",
      ko: "Roma Ostiense에서 Roma San Pietro까지 내일 {h}시 {m}분",
      my: "Roma Ostiense မှ Roma San Pietro သို့ မနက်ဖြန် {h}:{m}",
      pt: "De Roma Ostiense para Roma San Pietro amanhã às {h}:{m}",
      ru: "От Roma Ostiense до Roma San Pietro завтра в {h}:{m}",
      th: "จาก Roma Ostiense ไป Roma San Pietro พรุ่งนี้ เวลา {h}:{m}",
      tr: "Roma Ostiense'den Roma San Pietro'ya yarın saat {h}:{m}",
      vi: "Từ Roma Ostiense đến Roma San Pietro ngày mai lúc {h}:{m}",
      zh: "从 Roma Ostiense 到 Roma San Pietro 明天 {h}点{m}分",
    };
    for (const locale of supportedLocales) {
      for (const [hour, minute] of [["20", "15"], ["21", "40"], ["22", "05"], ["23", "59"], ["20", "00"]] as const) {
        const phrase = templates[locale].replace("{h}", hour).replace("{m}", minute);
        expect(parseLocalDictation(phrase, now, locale), `${locale} ${phrase}`).toEqual({
          origin: "Roma Ostiense", destination: "Roma San Pietro", travelDate: "2030-01-03", travelTime: `${hour}:${minute}`,
        });
      }
    }
    // An hour without minutes, and an impossible hour that must not be cut to its first digit.
    expect(parseLocalDictation("Da Roma Termini a Milano Centrale domani alle 21", now, "it").travelTime).toBe("21:00");
    expect(parseLocalDictation("Da Roma Termini a Milano Centrale domani alle 24:00", now, "it").travelTime).toBe("");
    expect(parseLocalDictation("Von Roma Termini nach Milano Centrale morgen um 25", now, "de").travelTime).toBe("");
    // French and Portuguese also write the hour with «h».
    expect(parseLocalDictation("De Roma Termini à Milano Centrale demain à 20h15", now, "fr").travelTime).toBe("20:15");
    expect(parseLocalDictation("De Roma Termini para Milano Centrale amanhã às 21h30", now, "pt").travelTime).toBe("21:30");
  });

  it("converte am/pm della dettatura inglese", () => {
    const now = new Date("2030-01-02T12:00:00");
    const time = (spoken: string) => parseLocalDictation(`From Roma Termini to Milano Centrale tomorrow at ${spoken}`, now, "en").travelTime;
    expect(time("8pm")).toBe("20:00");
    expect(time("8 pm")).toBe("20:00");
    expect(time("8:30 p.m.")).toBe("20:30");
    expect(time("11:59 PM")).toBe("23:59");
    expect(time("12pm")).toBe("12:00");
    expect(time("12am")).toBe("00:00");
    expect(time("8am")).toBe("08:00");
    expect(time("8")).toBe("08:00");
    expect(time("13pm")).toBe("");
    expect(parseLocalDictation("From Roma Termini to Milano Centrale at 8pm", now, "en")).toEqual({ origin: "Roma Termini", destination: "Milano Centrale", travelDate: "", travelTime: "20:00" });
  });

  it("salva modelli limitati al dispositivo e rifiuta stazioni non complete", async () => {
    const valid = createLocalJourneyTemplate({ id: "casa-lavoro", label: "Casa lavoro", origin: "Roma Ostiense", originStationCode: "S08406", destination: "Roma San Pietro", destinationStationCode: "S08409", travelTime: "08:15", plannedReminderMinutes: 15, checklist: [] });
    expect(valid).not.toBeNull();
    await saveLocalJourneyTemplate(valid!);
    await expect(loadLocalJourneyTemplates()).resolves.toHaveLength(1);
    expect(createLocalJourneyTemplate({ id: "x", label: "x", origin: "Roma", originStationCode: "", destination: "Milano", destinationStationCode: "S1", travelTime: "08:15", plannedReminderMinutes: 15, checklist: [] })).toBeNull();
  });

  it("mantiene il margine come preferenza locale e rifiuta valori non previsti", async () => {
    await expect(loadLocalJourneyTimingPreferences()).resolves.toEqual({ preferredReminderMinutes: 15 });
    await expect(saveLocalJourneyTimingPreferences(30)).resolves.toBe(true);
    await expect(loadLocalJourneyTimingPreferences()).resolves.toEqual({ preferredReminderMinutes: 30 });
    await expect(saveLocalJourneyTimingPreferences(999 as never)).resolves.toBe(false);
  });

  it("valida un archivio manuale e rende espliciti i duplicati nell’anteprima", () => {
    const archive = parseManualRouteTransfer(JSON.stringify({ schemaVersion: 1, createdAtUtc: "2030-01-01T00:00:00.000Z", routes: [{ origin: "Roma", originStationCode: "S1", destination: "Napoli", destinationStationCode: "S2", travelDate: "2030-01-02", travelTime: "09:30", plannedReminderMinutes: null, checklist: [] }] }));
    expect(archive).not.toBeNull();
    expect(previewManualRouteTransfer(archive!.routes, archive!.routes)[0]?.state).toBe("already_present");
    expect(parseManualRouteTransfer("not json")).toBeNull();
  });
});
