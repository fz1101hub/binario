import { describe, expect, it } from "vitest";
import { ACCOUNT_DELETION_CONFIRMATION } from "./const";
import {
  LOCALE_MESSAGES,
  SUPPORTED_LOCALES,
  formatMessage,
  formatPlural,
  messagesForLocale,
  resolveLocale,
} from "./i18n";

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
    ["pt-BR", "it-IT"],
    [undefined, "it-IT"],
  ] as const)("resolves %s to %s", (header, expected) => {
    expect(resolveLocale(header)).toBe(expected);
  });

  it("uses browser language order when qualities are implicit", () => {
    expect(resolveLocale(["pt-BR", "fr-CA", "de-DE"])).toBe("fr-FR");
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
    const italianKeys = leafPaths(LOCALE_MESSAGES.it).sort();
    for (const language of ["en", "de", "fr", "es"] as const)
      expect(leafPaths(LOCALE_MESSAGES[language]).sort()).toEqual(italianKeys);
  });

  it("has identical interpolation-placeholder parity for every translated leaf", () => {
    const italianLeaves = new Map(stringLeaves(LOCALE_MESSAGES.it));
    for (const language of ["en", "de", "fr", "es"] as const) {
      for (const [path, translation] of stringLeaves(LOCALE_MESSAGES[language]))
        expect(placeholders(translation), `${language}:${path}`).toEqual(
          placeholders(italianLeaves.get(path) ?? "")
        );
    }
  });

  it("contains no blank translations or unresolved replacement markers", () => {
    for (const [language, dictionary] of Object.entries(LOCALE_MESSAGES)) {
      for (const [path, translation] of stringLeaves(dictionary)) {
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
      "de-DE",
      "fr-FR",
      "es-ES",
    ]);
  });
});
