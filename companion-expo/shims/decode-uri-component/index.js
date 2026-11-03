'use strict';

/*
 * Replacement for decode-uri-component@0.2.2, which query-string@7 (used by React Navigation's linking)
 * loads with require(). The fixed upstream releases (>= 0.5.1, GHSA-qr28-fpjm-wq7f) are ES modules only.
 *
 * Same contract as 0.2.2: decode like decodeURIComponent, but never throw on malformed escapes; keep a
 * sequence that is not valid UTF-8 as written. Every run of %XX escapes is decoded in a single pass,
 * so the work is linear in the input length instead of quadratic.
 *
 * Two deliberate differences from 0.2.2, neither observable through query-string@7:
 * 1. "+" is not turned into a space. query-string.parse replaces "+" before it decodes a key or a
 *    value, and its only other decode, of the fragment, needs parseFragmentIdentifier, which React
 *    Navigation's queryString.parse(query) never sets.
 * 2. A lone %C2 that is not part of a valid sequence stays "%C2" instead of becoming U+FFFD, like
 *    every other invalid byte.
 */

const ESCAPE_RUN = /(?:%[a-f0-9]{2})+/gi;
const BYTE_ORDER_MARKS = /%(?:FE%FF|FF%FE)/gi;

function continuation(byte) {
  return byte >= 0x80 && byte <= 0xbf;
}

/* Length of the valid UTF-8 sequence starting at bytes[index], or 0 when it is not valid. */
function sequenceLength(bytes, index) {
  const first = bytes[index];
  if (first < 0x80) return 1;
  const length = first >= 0xc2 && first <= 0xdf ? 2 : first >= 0xe0 && first <= 0xef ? 3 : first >= 0xf0 && first <= 0xf4 ? 4 : 0;
  if (length === 0 || index + length > bytes.length) return 0;
  const second = bytes[index + 1];
  if (first === 0xe0 && (second < 0xa0 || second > 0xbf)) return 0;
  if (first === 0xed && (second < 0x80 || second > 0x9f)) return 0;
  if (first === 0xf0 && (second < 0x90 || second > 0xbf)) return 0;
  if (first === 0xf4 && (second < 0x80 || second > 0x8f)) return 0;
  for (let offset = 1; offset < length; offset += 1) {
    if (!continuation(bytes[index + offset])) return 0;
  }
  return length;
}

function codePoint(bytes, index, length) {
  if (length === 1) return bytes[index];
  let value = bytes[index] & (0xff >> (length + 1));
  for (let offset = 1; offset < length; offset += 1) value = (value << 6) | (bytes[index + offset] & 0x3f);
  return value;
}

/* One run of %XX escapes: valid UTF-8 sequences are decoded, every other byte keeps its escape. */
function decodeRun(run) {
  const bytes = [];
  for (let index = 0; index < run.length; index += 3) bytes.push(parseInt(run.slice(index + 1, index + 3), 16));
  let output = '';
  let index = 0;
  while (index < bytes.length) {
    const length = sequenceLength(bytes, index);
    if (length === 0) {
      output += run.slice(index * 3, index * 3 + 3);
      index += 1;
    } else {
      output += String.fromCodePoint(codePoint(bytes, index, length));
      index += length;
    }
  }
  return output;
}

function decodeUriComponent(encodedURI) {
  if (typeof encodedURI !== 'string') {
    throw new TypeError('Expected `encodedURI` to be of type `string`, got `' + typeof encodedURI + '`');
  }
  try {
    return decodeURIComponent(encodedURI);
  } catch (error) {
    return encodedURI
      .replace(BYTE_ORDER_MARKS, '��')
      .replace(ESCAPE_RUN, decodeRun);
  }
}

module.exports = decodeUriComponent;
