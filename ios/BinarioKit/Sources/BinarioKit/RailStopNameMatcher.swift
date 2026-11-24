import Foundation

/// Android's `RailStopNameMatcher`: whether a stop name an operator publishes (RFI's «Firenze S.M.N.», «MILANO C.LE»)
/// names a catalogue station.
///
/// The whole name must match, token by token: «Cisterna di Latina» never names «Latina» and «Roma Tiburtina» never
/// names «Roma». Only the abbreviations railway timetables use are expanded: one- or two-letter prefixes («S.», «M.»,
/// «P.»), runs of initials («SMN») and a few two-part forms («C.LE», «M.MA», «P.ZA», «V.LE»). Italian linking words
/// and the «(Tutte le stazioni)» suffix are ignored, and junk aliases (codes, one-letter tokens) never take part. The
/// cases both platforms read are in contracts/shared-rules/v1/rfi-programmed-v1.json (`names`).
public enum RailStopNameMatcher {
    private static let fillers: Set<String> = [
        "DI", "DEL", "DELLA", "DELLE", "DEI", "DEGLI", "DE", "D", "IN", "SU", "SUL", "SULLA", "AL", "ALLA", "E",
    ]
    private static let twoPartAbbreviations: [String: String] = [
        "C LE": "CENTRALE", "M MA": "MARITTIMA", "P ZA": "PIAZZA", "V LE": "VIALE",
    ]

    /// True when `published` names the station called `name`, or one of its `aliases`.
    public static func matches(name: String, aliases: [String] = [], published: String) -> Bool {
        let publishedTokens = tokens(published)
        guard !publishedTokens.isEmpty else { return false }
        let candidates = [name] + aliases.filter { alias in
            !alias.contains("_") && !alias.hasPrefix("@") && tokens(alias).reduce(0) { $0 + $1.count } >= 3
        }
        return candidates.contains { sequenceMatches(publishedTokens, tokens($0)) }
    }

    /// The words of a name: no accents, upper case, «(Tutte le stazioni)» dropped, split at anything but A–Z and 0–9,
    /// the two-part abbreviations joined, the linking words left out.
    public static func tokens(_ value: String) -> [String] {
        var folded = String.UnicodeScalarView()
        for scalar in value.decomposedStringWithCanonicalMapping.unicodeScalars where !isMark(scalar) {
            folded.append(scalar)
        }
        let upper = removingAllStationsSuffix(String(folded).uppercased())
        var raw: [String] = []
        var current = String.UnicodeScalarView()
        for scalar in upper.unicodeScalars {
            if ("A"..."Z").contains(scalar) || ("0"..."9").contains(scalar) {
                current.append(scalar)
            } else if !current.isEmpty {
                raw.append(String(current))
                current = String.UnicodeScalarView()
            }
        }
        if !current.isEmpty { raw.append(String(current)) }
        var joined: [String] = []
        var index = 0
        while index < raw.count {
            if index + 1 < raw.count, let pair = twoPartAbbreviations["\(raw[index]) \(raw[index + 1])"] {
                joined.append(pair)
                index += 2
            } else {
                joined.append(raw[index])
                index += 1
            }
        }
        return joined.filter { !fillers.contains($0) }
    }

    /// `\p{M}`: a combining mark.
    private static func isMark(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .nonspacingMark, .spacingMark, .enclosingMark: return true
        default: return false
        }
    }

    /// Android's `\(\s*TUTTE\s+LE\s+STAZIONI\s*\)`, replaced by a space.
    private static func removingAllStationsSuffix(_ value: String) -> String {
        guard value.contains("TUTTE"),
              let expression = try? NSRegularExpression(pattern: "\\(\\s*TUTTE\\s+LE\\s+STAZIONI\\s*\\)") else { return value }
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return expression.stringByReplacingMatches(in: value, options: [], range: range, withTemplate: " ")
    }

    private static func sequenceMatches(_ published: [String], _ station: [String]) -> Bool {
        guard !station.isEmpty else { return false }
        if published == station { return true }
        // Abbreviations only bridge the rest of a name: at least one real word must agree, so «P» alone never names
        // «Piacenza».
        guard published.contains(where: { $0.count >= 3 && station.contains($0) }) else { return false }
        var memo: [Int: Bool] = [:]
        func match(_ i: Int, _ j: Int) -> Bool {
            let key = i * 10_000 + j
            if let known = memo[key] { return known }
            var result = false
            if i == published.count && j == station.count {
                result = true
            } else if i < published.count && j < station.count {
                let p = published[i]
                let s = station[j]
                if p == s && match(i + 1, j + 1) {
                    result = true
                } else if isAbbreviation(p, of: s) && match(i + 1, j + 1) {
                    result = true
                } else if isAbbreviation(s, of: p) && match(i + 1, j + 1) {
                    result = true
                } else if let span = initialsSpan(p, station, from: j), match(i + 1, j + span) {
                    result = true
                } else if let span = initialsSpan(s, published, from: i), match(i + span, j + 1) {
                    result = true
                }
            }
            memo[key] = result
            return result
        }
        return match(0, 0)
    }

    /// «S» for «SANTA», «PT» never: only one- or two-letter alphabetic prefixes count.
    private static func isAbbreviation(_ short: String, of full: String) -> Bool {
        (1...2).contains(short.count) && short.unicodeScalars.allSatisfy { ("A"..."Z").contains($0) }
            && full.count > short.count && full.hasPrefix(short)
    }

    /// «SMN» spanning «SANTA MARIA NOVELLA» (or «S M NOVELLA»): how many tokens it covers.
    private static func initialsSpan(_ initials: String, _ tokens: [String], from: Int) -> Int? {
        let letters = Array(initials.unicodeScalars)
        guard (2...4).contains(letters.count), letters.allSatisfy({ ("A"..."Z").contains($0) }),
              from + letters.count <= tokens.count else { return nil }
        let covered = Array(tokens[from..<(from + letters.count)])
        for (offset, token) in covered.enumerated() where token.unicodeScalars.first != letters[offset] { return nil }
        return covered.contains { $0.count > 1 } ? letters.count : nil
    }
}
