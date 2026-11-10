import { describe, expect, it } from "vitest";
import { ACCOUNT_DELETION_CONFIRMATION } from "./const";
import {
  LOCALE_MESSAGES,
  SUPPORTED_LOCALES,
  WEB_LOCALES,
  formatMessage,
  formatPlural,
  localizeHtmlLang,
  messagesForLocale,
  resolveLocale,
  resolveWebLocale,
  textDirection,
} from "./i18n";

const LANGUAGES = Object.keys(LOCALE_MESSAGES) as Array<keyof typeof LOCALE_MESSAGES>;
/** Plural forms beyond one/other that a language may add, per its CLDR rules. */
const EXTRA_PLURAL_FORM = /\.(zero|two|few|many)$/;

function leafPaths(value: unknown, prefix = ""): string[] {
  if (!value || typeof value !== "object") return [prefix];
  return Object.entries(value).flatMap(([key, nested]) =>
    leafPaths(nested, prefix ? `${prefix}.${key}` : key)
  );
}

function placeholders(value: string): string[] {
  return [...value.matchAll(/\{([A-Za-z][A-Za-z0-9_]*)\}/g)]
    .map(match => match[1])
    .sort();
}

function stringLeaves(value: unknown, prefix = ""): Array<[string, string]> {
  if (typeof value === "string") return [[prefix, value]];
  if (!value || typeof value !== "object") return [];
  return Object.entries(value).flatMap(([key, nested]) =>
    stringLeaves(nested, prefix ? `${prefix}.${key}` : key)
  );
}

describe("locale resolution", () => {
  it.each([
    ["de-AT,de;q=0.9", "de-DE"],
    ["fr-CA,fr;q=0.8", "fr-FR"],
    ["es-MX", "es-ES"],
    ["en-GB", "en-GB"],
    ["en-AU", "en-US"],
    ["en-US;q=0.2, de-DE;q=0.9", "de-DE"],
    ["fr-FR;q=0, en-GB;q=0.8", "en-GB"],
    ["de-DE;q=bogus, es-ES;q=0.7", "es-ES"],
    ["*;q=1, fr;q=0.5", "fr-FR"],
    ["EN_gb", "en-GB"],
    ["pt-BR", "pt-BR"],
    ["pt-PT", "pt-BR"],
    ["ar-EG", "ar-SA"],
    ["hi", "hi-IN"],
    ["ja-JP", "ja-JP"],
    ["ko-KR", "it-IT"],
    ["nl-NL", "it-IT"],
    [undefined, "it-IT"],
  ] as const)("resolves %s to %s", (header, expected) => {
    expect(resolveLocale(header)).toBe(expected);
  });

  it("uses browser language order when qualities are implicit", () => {
    expect(resolveLocale(["nl-NL", "fr-CA", "de-DE"])).toBe("fr-FR");
  });

  it.each([
    ["ko-KR", "ko-KR"],
    ["ru", "ru-RU"],
    ["tr-TR,en;q=0.5", "tr-TR"],
    ["th-TH", "th-TH"],
    ["vi", "vi-VN"],
    ["id-ID", "id-ID"],
    ["in-ID", "it-IT"],
    ["my-MM", "my-MM"],
    ["zh-CN", "zh-Hans"],
    ["zh-Hans-SG", "zh-Hans"],
    ["zh", "zh-Hans"],
    ["zh-TW, en-GB;q=0.5", "en-GB"],
    ["zh-Hant-HK", "it-IT"],
    ["ja-JP", "ja-JP"],
    ["nl-NL", "it-IT"],
  ] as const)("resolves the web preference %s to %s", (header, expected) => {
    expect(resolveWebLocale(header)).toBe(expected);
  });

  it("keeps the web locales a superset of the Alexa locales", () => {
    expect(WEB_LOCALES.slice(0, SUPPORTED_LOCALES.length)).toEqual([...SUPPORTED_LOCALES]);
    for (const locale of WEB_LOCALES) expect(messagesForLocale(locale)).toBeDefined();
    expect(new Set(WEB_LOCALES.map(locale => messagesForLocale(locale))).size).toBe(LANGUAGES.length);
  });

  it("marks only Arabic as right-to-left, in the document and in HTML", () => {
    expect(WEB_LOCALES.filter(locale => textDirection(locale) === "rtl")).toEqual(["ar-SA"]);
    expect(localizeHtmlLang('<html lang="it">', "ar-SA")).toBe('<html lang="ar-SA" dir="rtl">');
    expect(localizeHtmlLang('<html lang="ar-SA" dir="rtl">', "ja-JP")).toBe('<html lang="ja-JP">');
  });

  it.each(["constructor", "constructor-foo", "__proto__", "toString", "hasOwnProperty"])(
    "rejects inherited Object key %s as a locale",
    candidate => {
      const locale = resolveLocale(candidate);
      expect(locale).toBe("it-IT");
      expect(typeof locale).toBe("string");
      expect(messagesForLocale(locale).consent.errorTitle).toBeTruthy();
    }
  );
});

describe("typed locale dictionaries", () => {
  it("has identical leaf-key parity for every language dictionary", () => {
    // Italian has CLDR "many" forms too: the parity is on the mandatory keys.
    const italianKeys = leafPaths(LOCALE_MESSAGES.it).filter(path => !EXTRA_PLURAL_FORM.test(path)).sort();
    for (const language of LANGUAGES)
      expect(
        leafPaths(LOCALE_MESSAGES[language]).filter(path => !EXTRA_PLURAL_FORM.test(path)).sort(),
        language
      ).toEqual(italianKeys);
  });

  it("adds plural forms only where the language's CLDR rules use them", () => {
    for (const locale of WEB_LOCALES) {
      const categories = new Intl.PluralRules(locale).resolvedOptions().pluralCategories;
      for (const path of leafPaths(messagesForLocale(locale))) {
        const extra = EXTRA_PLURAL_FORM.exec(path)?.[1];
        if (extra) expect(categories, `${locale}:${path}`).toContain(extra);
      }
    }
  });

  it("says an Arabic dual with the dual noun alone, never «2 مرتين» (two twice)", () => {
    const p = messagesForLocale("ar-SA").punctuality;
    const duals = [p.cancelled, p.meanDelay, p.minutes, p.observedDays, p.trainCount, p.outOfTen, p.train, p.route];
    for (const messages of duals) expect(formatPlural("ar-SA", 2, messages).replace("{count}", "2")).not.toMatch(/2 (?:مرتين|دقيقتين|يومين|قطارين)/);
  });

  it("has identical interpolation-placeholder parity for every translated leaf", () => {
    const italianLeaves = new Map(stringLeaves(LOCALE_MESSAGES.it));
    for (const language of LANGUAGES) {
      for (const [path, translation] of stringLeaves(LOCALE_MESSAGES[language])) {
        // An extra plural form matches either the Italian singular or the Italian plural.
        const references = EXTRA_PLURAL_FORM.test(path)
          ? [".one", ".other"].map(form => path.replace(EXTRA_PLURAL_FORM, form))
          : [path];
        const expected = references.map(reference => placeholders(italianLeaves.get(reference) ?? ""));
        // An Arabic dual says the number with the noun itself («مرتين», twice): no {count}.
        if (language === "ar" && path.endsWith(".two")) expected.push(...expected.map(names => names.filter(name => name !== "count")));
        expect(expected, `${language}:${path}`).toContainEqual(placeholders(translation));
      }
    }
  });

  it("contains no blank translations or unresolved replacement markers", () => {
    // Thai closes a sentence without punctuation: its list terminator is deliberately empty.
    const deliberatelyEmpty = new Set(["th:voice.listTerminator"]);
    for (const [language, dictionary] of Object.entries(LOCALE_MESSAGES)) {
      for (const [path, translation] of stringLeaves(dictionary)) {
        if (!deliberatelyEmpty.has(`${language}:${path}`))
          expect(translation.trim(), `${language}:${path}`).not.toBe("");
        expect(translation, `${language}:${path}`).not.toContain("REPLACE_WITH");
      }
    }
  });

  it("supports named interpolation and locale plural rules", () => {
    expect(formatMessage("Route {routeName}", { routeName: "Home" })).toBe("Route Home");
    expect(formatMessage("Route {routeName} {unknown}", { routeName: "Home" })).toBe(
      "Route Home {unknown}"
    );
    expect(
      formatPlural("en-US", 1, messagesForLocale("en-US").voice.moreRoutes)
    ).toContain("one more");
    expect(
      formatPlural("en-US", 2, messagesForLocale("en-US").voice.moreRoutes)
    ).toContain("2 more routes");
    expect(
      formatPlural("de-DE", 3, messagesForLocale("de-DE").voice.moreRoutes)
    ).toContain("3 weitere Strecken");
    const russian = messagesForLocale("ru-RU").voice.moreRoutes;
    expect(formatPlural("ru-RU", 1, russian)).toContain("ещё один маршрут");
    expect(formatPlural("ru-RU", 3, russian)).toContain("ещё 3 маршрута");
    expect(formatPlural("ru-RU", 5, russian)).toContain("ещё 5 маршрутов");
    const arabic = messagesForLocale("ar-SA").voice.moreRoutes;
    expect(formatPlural("ar-SA", 2, arabic)).toContain("مساران");
    expect(formatPlural("ar-SA", 4, arabic)).toContain("4 مسارات");
    expect(formatPlural("ar-SA", 12, arabic)).toContain("12 مسارًا");
    expect(formatPlural("ja-JP", 1, messagesForLocale("ja-JP").voice.moreRoutes)).toContain("ほかに 1 件");
  });

  it("keeps the destructive confirmation contract locale-independent and explicit", () => {
    expect(ACCOUNT_DELETION_CONFIRMATION).toBe("ELIMINA");
    for (const dictionary of Object.values(LOCALE_MESSAGES))
      expect(dictionary.dashboard.deleteAccountConfirmationContract).toContain(
        ACCOUNT_DELETION_CONFIRMATION
      );
  });

  it("covers every supported Alexa publishing locale", () => {
    expect(SUPPORTED_LOCALES).toEqual([
      "it-IT",
      "en-US",
      "en-GB",
      "en-IN",
      "de-DE",
      "fr-FR",
      "es-ES",
      "es-US",
      "ar-SA",
      "hi-IN",
      "ja-JP",
      "pt-BR",
    ]);
  });
});
