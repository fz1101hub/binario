import { describe, expect, it } from "vitest";
import { matchRouteByName, normalizeRouteName } from "./routeMatching";

const route = (name: string) => ({ name });

describe("ricerca vocale delle tratte", () => {
  it("normalizza accenti, punteggiatura, maiuscole e spazi", () => {
    expect(normalizeRouteName("  Casa–Lavoro  ", "it-IT")).toBe("casa lavoro");
    expect(normalizeRouteName("Città Alta (sera)", "it-IT")).toBe("citta alta sera");
    expect(normalizeRouteName("Gare de l’Est", "fr-FR")).toBe("gare de l est");
    expect(normalizeRouteName("Zürich HB", "de-DE")).toBe("zurich hb");
    // Full-width forms fold to ASCII; Japanese kana keep their voicing marks.
    expect(normalizeRouteName("通勤　ルート９", "ja-JP")).toBe("通勤 ルート9");
    expect(normalizeRouteName("ばす", "ja-JP")).not.toBe(normalizeRouteName("はす", "ja-JP"));
    // Arabic short vowels are optional in writing; Devanagari vowel signs are not.
    expect(normalizeRouteName("المَنزِل", "ar-SA")).toBe("المنزل");
    expect(normalizeRouteName("घर", "hi-IN")).not.toBe(normalizeRouteName("घेर", "hi-IN"));
  });

  it.each([
    ["it-IT", ["Casa-Lavoro", "Mare"], "casa lavoro", "Casa-Lavoro"],
    ["it-IT", ["Città Alta", "Mare"], "citta alta", "Città Alta"],
    ["it-IT", ["Casa-Lavoro", "Mare"], "la tratta mare", "Mare"],
    ["it-IT", ["Casa-Lavoro", "Mare"], "casalavoro", "Casa-Lavoro"],
    ["en-US", ["Home to Work", "Beach"], "home", "Home to Work"],
    ["de-DE", ["Zürich – Basel", "Arbeit"], "zurich basel", "Zürich – Basel"],
    ["fr-FR", ["Gare de l’Est", "Travail"], "gare de l'est", "Gare de l’Est"],
    ["es-ES", ["Casa · Trabajo", "Playa"], "trabajo", "Casa · Trabajo"],
    ["ja-JP", ["通勤ルート", "週末"], "通勤", "通勤ルート"],
    ["ar-SA", ["المَنزِل", "العمل"], "المنزل", "المَنزِل"],
    ["hi-IN", ["घर से ऑफिस", "बाज़ार"], "ऑफिस", "घर से ऑफिस"],
  ] as const)("%s: trova la tratta salvata da un nome trascritto (%s → %s)", (locale, names, spoken, expected) => {
    expect(matchRouteByName(names.map(route), spoken, locale)).toEqual({ kind: "match", route: route(expected) });
  });

  it("chiede quale tratta quando il nome detto ne indica più d'una", () => {
    const routes = ["Casa-Lavoro", "Casa-Mare", "Palestra"].map(route);
    expect(matchRouteByName(routes, "casa", "it-IT")).toEqual({
      kind: "ambiguous",
      routes: [route("Casa-Lavoro"), route("Casa-Mare")],
    });
    // An exact name wins over partial matches.
    expect(matchRouteByName(["Casa", "Casa-Mare"].map(route), "casa", "it-IT")).toEqual({ kind: "match", route: route("Casa") });
  });

  it("non inventa corrispondenze", () => {
    expect(matchRouteByName(["Casa-Lavoro", "Mare"].map(route), "milano", "it-IT")).toEqual({ kind: "none" });
    expect(matchRouteByName(["Home"].map(route), "beach", "en-US")).toEqual({ kind: "none" });
    expect(matchRouteByName(["Home"].map(route), " – ", "en-US")).toEqual({ kind: "none" });
  });
});
