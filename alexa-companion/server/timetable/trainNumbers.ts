import { TrainNumberPrefixes } from "@shared/generated/sharedData";

/**
 * Exact public-number matching, as Android's `TrainNumberQuery` and the iPhone's
 * `LiveTrainNumberQuery` read a number: category and line labels never turn it into a substring
 * search. A published «line - number» label («RE_11 - 2153») is asked by its run number. The cases
 * are the `query`, `queryNumeric` and `queryValid` fields of contracts/shared-rules/v1/rules-v1.json;
 * the categories that may prefix a number are contracts/shared-data/v1/train-number-prefixes-v1.json.
 */

const NUMBER = new RegExp(`^(?:(?:${TrainNumberPrefixes.CATEGORIES.join("|")}))?([0-9]+)$`);

function normalize(value: string): string {
  return value.trim().toUpperCase().replace(/[\s-]+/g, "");
}

/** The digits after a known category, without leading zeros («FR09558» is 9558, «VE545» none). */
function numeric(value: string): string | null {
  const digits = NUMBER.exec(value)?.[1];
  if (digits === undefined) return null;
  return digits.replace(/^0+/, "") || "0";
}

export class TrainNumberQuery {
  readonly normalized: string;
  readonly numeric: string | null;

  constructor(number: string) {
    const trimmed = number.trim();
    const separator = trimmed.lastIndexOf(" - ");
    this.normalized = normalize(separator >= 0 ? trimmed.slice(separator + 3) : trimmed);
    this.numeric = numeric(this.normalized);
  }

  /** 1–32 letters or digits, with at least one digit. */
  get valid(): boolean {
    const characters = Array.from(this.normalized);
    return characters.length >= 1 && characters.length <= 32 && characters.every(character => /[\p{L}\p{Nd}]/u.test(character)) && /\p{Nd}/u.test(this.normalized);
  }

  /** True when `published` is this number: the same text, its run number, or the same digits after a category. */
  matches(published: string): boolean {
    const separator = published.lastIndexOf(" - ");
    const candidate = (separator >= 0 ? published.slice(separator + 3) : published).trim();
    return (
      this.normalized === normalize(published) ||
      this.normalized === normalize(candidate) ||
      (this.numeric !== null && this.numeric === numeric(normalize(candidate)))
    );
  }
}
