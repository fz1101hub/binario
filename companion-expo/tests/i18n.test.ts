import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import {
  createTranslator,
  dictionaryKeys,
  isRightToLeft,
  pluralCategory,
  resolveCompanionLocale,
  supportedLocales,
  translate,
  translationDictionaries,
  type CompanionLocale,
  type TranslationKey,
  useTranslation,
} from "@/lib/i18n";
import { parseGuidedTextLocalRouteImports } from "@/lib/local-journey-itineraries";

function placeholders(value: string): string[] {
  return [...value.matchAll(/\{([A-Za-z][A-Za-z0-9_]*)(?:,|\})/g)]
    .map((match) => match[1]!)
    .sort();
}

function nativeResourceKeys(module: "app" | "wear", directory: string): string[] {
  const xml = readFileSync(
    path.resolve(import.meta.dirname, "..", "android", module, "src", "main", "res", directory, "strings.xml"),
    "utf8",
  );
  const keys = [...xml.matchAll(/<(?:string|plurals)\s+name="([^"]+)"/g)]
    .map((match) => match[1]!)
    .filter((key) => module === "wear" || key.startsWith("binario_"));
  return [...new Set(keys)].sort();
}

describe("cataloghi localizzati Companion", () => {
  it("dichiara il modulo locale nativo e le diciassette lingue nella config Expo", () => {
    const config = readFileSync(
      path.resolve(import.meta.dirname, "..", "app.config.ts"),
      "utf8",
    );
    const packageJson = JSON.parse(
      readFileSync(path.resolve(import.meta.dirname, "..", "package.json"), "utf8"),
    ) as { dependencies?: Record<string, string> };
    expect(packageJson.dependencies?.["expo-localization"]).toBe("~17.0.9");
    expect(config).toContain('"expo-localization"');
    // iOS takes Apple's localization IDs; Android's list also feeds resourceConfigurations,
    // so it uses resource qualifiers: legacy "in" for Indonesian, "zh" for b+zh+Hans resources.
    expect(config).toContain('ios: ["it", "en", "de", "fr", "es", "ar", "hi", "id", "ja", "ko", "my", "pt-BR", "ru", "th", "tr", "vi", "zh-Hans"]');
    expect(config).toContain('android: ["it", "en", "de", "fr", "es", "ar", "hi", "in", "ja", "ko", "my", "pt", "ru", "th", "tr", "vi", "zh"]');
    expect(config).toContain("supportsRTL: true");
    expect(config).toContain("allowDynamicLocaleChangesAndroid: true");
  });

  it("mantiene lo stesso insieme di chiavi in tutte le lingue", () => {
    const canonical = [...dictionaryKeys("it")].sort();
    expect(canonical.length).toBeGreaterThan(400);
    for (const locale of supportedLocales) {
      expect([...dictionaryKeys(locale)].sort(), locale).toEqual(canonical);
    }
  });

  it("mantiene placeholder e plurali compatibili con il catalogo canonico", () => {
    const canonical = translationDictionaries.it;
    for (const locale of supportedLocales) {
      for (const key of Object.keys(canonical) as TranslationKey[]) {
        const localized = translationDictionaries[locale][key];
        expect(localized.trim(), `${locale}:${key}`).not.toBe("");
        expect(placeholders(localized), `${locale}:${key}`).toEqual(
          placeholders(canonical[key]),
        );
      }
    }
  });

  it("mantiene gli esempi import realmente accettati dal parser in ogni lingua", () => {
    for (const locale of supportedLocales) {
      const example = createTranslator(locale)("import.example");
      expect(parseGuidedTextLocalRouteImports(example), locale).toHaveLength(2);
    }
  });

  it("mantiene le stesse chiavi native phone e Wear in tutte le lingue generate", () => {
    for (const module of ["app", "wear"] as const) {
      const canonical = nativeResourceKeys(module, "values");
      expect(canonical.length, module).toBeGreaterThan(40);
      for (const locale of ["en", "de", "fr", "es", "ar", "hi", "in", "ja", "ko", "my", "pt", "ru", "th", "tr", "vi", "b+zh+Hans"] as const) {
        expect(nativeResourceKeys(module, `values-${locale}`), `${module}:${locale}`).toEqual(canonical);
      }
    }
  });

  it("risolve preferenze BCP-47 in ordine e usa italiano per lingue non supportate", () => {
    expect(resolveCompanionLocale("en-US")).toBe("en");
    expect(resolveCompanionLocale("de_DE")).toBe("de");
    expect(resolveCompanionLocale(["nl-NL", "fr-CA", "es-ES"])).toBe("fr");
    expect(resolveCompanionLocale(["invalid_locale_%%%", "es-MX"])).toBe("es");
    expect(resolveCompanionLocale(["nl-NL", "sv-SE"])).toBe("it");
    expect(resolveCompanionLocale(null)).toBe("it");
    expect(resolveCompanionLocale("pt-PT")).toBe("pt");
    expect(resolveCompanionLocale("ja-JP")).toBe("ja");
    expect(resolveCompanionLocale("in-ID")).toBe("id");
    expect(resolveCompanionLocale("zh-Hans-CN")).toBe("zh");
    expect(resolveCompanionLocale("zh-CN")).toBe("zh");
    // Traditional Chinese is not served Simplified Chinese: the next preference wins.
    expect(resolveCompanionLocale(["zh-TW", "en-US"])).toBe("en");
    expect(resolveCompanionLocale(["zh-Hant-HK"])).toBe("it");
  });

  it("seleziona tutte le lingue anche su Hermes senza Intl.Locale", () => {
    const descriptor = Object.getOwnPropertyDescriptor(Intl, "Locale");
    try {
      Object.defineProperty(Intl, "Locale", { configurable: true, value: undefined });
      for (const locale of supportedLocales) {
        expect(resolveCompanionLocale(`${locale}-IT`), locale).toBe(locale);
      }
      expect(resolveCompanionLocale(["en--US", "de-", "nl-NL", "fr-CA"])).toBe("fr");
      expect(resolveCompanionLocale(["es-%%", "es_MX"])).toBe("es");
      expect(resolveCompanionLocale(["en--US", "nl-NL"])).toBe("it");
    } finally {
      if (descriptor) Object.defineProperty(Intl, "Locale", descriptor);
      else Reflect.deleteProperty(Intl, "Locale");
    }
  });

  it("mantiene italiano come fallback deterministico delle API di libreria", () => {
    expect(createTranslator()("hub.planJourney")).toBe(
      translate("it", "hub.planJourney"),
    );
  });

  it("seleziona le categorie plurali CLDR delle lingue aggiunte senza Intl.PluralRules", () => {
    expect([0, 1, 2, 3, 11, 100].map(count => pluralCategory("ar", count))).toEqual(["zero", "one", "two", "few", "many", "other"]);
    expect([1, 2, 5, 11, 21, 22, 25].map(count => pluralCategory("ru", count))).toEqual(["one", "few", "many", "many", "one", "few", "many"]);
    expect([0, 1, 2].map(count => pluralCategory("pt", count))).toEqual(["one", "one", "other"]);
    expect([0, 1, 2].map(count => pluralCategory("hi", count))).toEqual(["one", "one", "other"]);
    expect([1, 2].map(count => pluralCategory("ja", count))).toEqual(["other", "other"]);
    // CLDR French: "one" for an integer part of 0 or 1, unlike Italian, English, German and Spanish.
    expect([0, 1, 1.5, 2].map(count => pluralCategory("fr", count))).toEqual(["one", "one", "one", "other"]);
    expect([0, 1, 2].map(count => pluralCategory("it", count))).toEqual(["other", "one", "other"]);
    expect(translate("fr", "settings.cleared", { count: 0 })).toContain("0 rappel annulé");
    expect(translate("fr", "settings.cleared", { count: 2 })).toContain("2 rappels annulés");
    expect(translate("ru", "settings.cleared", { count: 3 })).toContain("3 напоминания");
    expect(translate("ru", "settings.cleared", { count: 5 })).toContain("5 напоминаний");
    expect(translate("ar", "alerts.futureCopies", { count: 4 })).toContain("4 نسخ");
    expect(isRightToLeft("ar")).toBe(true);
    expect(supportedLocales.filter(isRightToLeft)).toEqual(["ar"]);
  });

  it("interpola i valori e seleziona il plurale per ogni lingua", () => {
    for (const locale of supportedLocales as readonly CompanionLocale[]) {
      const singular = translate(locale, "alerts.futureCopies", { count: 1 });
      const plural = translate(locale, "alerts.futureCopies", { count: 2 });
      expect(singular, locale).toContain("1");
      expect(plural, locale).toContain("2");
      expect(singular, locale).not.toContain("{");
      expect(plural, locale).not.toContain("{");
      expect(singular, locale).not.toBe(plural);
    }
  });
});
