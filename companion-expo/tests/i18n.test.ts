import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import {
  createTranslator,
  dictionaryKeys,
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
  it("dichiara il modulo locale nativo e le cinque lingue nella config Expo", () => {
    const config = readFileSync(
      path.resolve(import.meta.dirname, "..", "app.config.ts"),
      "utf8",
    );
    const packageJson = JSON.parse(
      readFileSync(path.resolve(import.meta.dirname, "..", "package.json"), "utf8"),
    ) as { dependencies?: Record<string, string> };
    expect(packageJson.dependencies?.["expo-localization"]).toBe("~17.0.9");
    expect(config).toContain('"expo-localization"');
    expect(config).toContain('supportedLocales: ["it", "en", "de", "fr", "es"]');
    expect(config).toContain("allowDynamicLocaleChangesAndroid: true");
  });

  it("mantiene lo stesso insieme di chiavi in italiano, inglese, tedesco, francese e spagnolo", () => {
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

  it("mantiene le stesse chiavi native phone e Wear nelle cinque lingue generate", () => {
    for (const module of ["app", "wear"] as const) {
      const canonical = nativeResourceKeys(module, "values");
      expect(canonical.length, module).toBeGreaterThan(40);
      for (const locale of ["en", "de", "fr", "es"] as const) {
        expect(nativeResourceKeys(module, `values-${locale}`), `${module}:${locale}`).toEqual(canonical);
      }
    }
  });

  it("risolve preferenze BCP-47 in ordine e usa italiano per lingue non supportate", () => {
    expect(resolveCompanionLocale("en-US")).toBe("en");
    expect(resolveCompanionLocale("de_DE")).toBe("de");
    expect(resolveCompanionLocale(["pt-BR", "fr-CA", "es-ES"])).toBe("fr");
    expect(resolveCompanionLocale(["invalid_locale_%%%", "es-MX"])).toBe("es");
    expect(resolveCompanionLocale(["pt-BR", "ja-JP"])).toBe("it");
    expect(resolveCompanionLocale(null)).toBe("it");
  });

  it("mantiene italiano come fallback deterministico delle API di libreria", () => {
    expect(createTranslator()("hub.planJourney")).toBe(
      translate("it", "hub.planJourney"),
    );
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
