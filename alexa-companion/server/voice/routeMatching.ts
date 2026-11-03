import type { SupportedLocale } from "@shared/i18n";

/**
 * Spoken route lookup. AMAZON.SearchQuery returns lower-case ASR text without punctuation or,
 * often, accents ("casa lavoro" for "Casa–Lavoro", "citta alta" for "Città Alta"), so names are
 * compared after normalisation, then by whole compact form, then token by token.
 */

// Latin/Greek/Cyrillic accents left by NFKD, and Arabic short vowels and tatweel. Devanagari
// vowel signs and Japanese dakuten are letters of their scripts and are kept.
const DIACRITICS = /[̀-ًͯ-ٰٟـ]/g;
const PUNCTUATION = /[\p{P}\p{S}]+/gu;
// Scripts written without spaces between words: compare by containment, not tokens.
const UNSPACED_SCRIPT = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Thai}]/u;

export function normalizeRouteName(value: string, locale: SupportedLocale): string {
  return value
    .normalize("NFKD")
    .replace(DIACRITICS, "")
    .normalize("NFC")
    .toLocaleLowerCase(locale)
    .replace(PUNCTUATION, " ")
    .replace(/\s+/g, " ")
    .trim();
}

export type RouteMatch<T> =
  | { kind: "match"; route: T }
  | { kind: "ambiguous"; routes: T[] }
  | { kind: "none" };

function tokensCover(words: string[], against: string[]) {
  return words.every(word => against.some(candidate => candidate.startsWith(word)));
}

/**
 * Finds the route the user named. Exact (normalised or space-insensitive) matches win; the
 * first in list order is taken when several routes normalise identically, since they sound
 * the same. Otherwise a route matches when every spoken word starts one of its words, or every
 * one of its words appears in what was said ("la tratta mare" for "Mare"). Several such
 * routes are ambiguous and the caller asks which one.
 */
export function matchRouteByName<T extends { name: string }>(
  routes: readonly T[],
  spoken: string,
  locale: SupportedLocale
): RouteMatch<T> {
  const query = normalizeRouteName(spoken, locale);
  if (!query) return { kind: "none" };
  const compactQuery = query.replace(/ /g, "");
  const named = routes.map(route => {
    const name = normalizeRouteName(route.name, locale);
    return { route, name, compact: name.replace(/ /g, ""), words: name.split(" ").filter(Boolean) };
  });

  const exact = named.find(item => item.name === query) ?? named.find(item => item.compact === compactQuery);
  if (exact) return { kind: "match", route: exact.route };

  const queryWords = query.split(" ").filter(Boolean);
  const unspaced = UNSPACED_SCRIPT.test(query);
  const partial = named.filter(item => {
    if (!item.compact) return false;
    if (unspaced || UNSPACED_SCRIPT.test(item.name))
      return (
        (compactQuery.length >= 2 && item.compact.includes(compactQuery)) ||
        (item.compact.length >= 2 && compactQuery.includes(item.compact))
      );
    return tokensCover(queryWords, item.words) || item.words.every(word => queryWords.includes(word));
  });
  if (partial.length === 1) return { kind: "match", route: partial[0].route };
  if (partial.length > 1) return { kind: "ambiguous", routes: partial.map(item => item.route) };
  return { kind: "none" };
}
