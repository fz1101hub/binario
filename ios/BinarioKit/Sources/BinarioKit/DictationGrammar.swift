import Foundation

/// Dictated text as `RouteDictation` compares it, in any of the scripts of the app: Android's
/// `VoiceText` (`PlannerVoiceGrammar.kt`), ported line by line. Positions and lengths count UTF-16
/// units, as Android's strings do, so that both phones read a sentence alike
/// (contracts/voice-input/v1).
enum DictationText {
    private static let tatweel: UInt32 = 0x0640
    private static let latinLetterFolds: [UInt32: String] = [
        0x0131: "i", 0x0111: "d", 0x00F0: "d", 0x00F8: "o", 0x0142: "l", 0x00DF: "ss",
        0x00E6: "ae", 0x0153: "oe", 0x00FE: "th", 0x0127: "h",
    ]

    /// Lower case; digits of every script in ASCII («٢٠:١٥», «२०:१५», «၂၀:၁၅», «๒๐:๑๕», «２０：１５» are
    /// 20:15); compatibility forms folded (full-width letters, «：»); punctuation as a space; a space
    /// between two scripts («9時35分» is «9 時 35 分», «Pietro까지» is «pietro 까지»). Latin letters lose
    /// their accents and tone marks («Mañana», «yarın», «Từ», «đến»), unless `foldLatinMarks` is false:
    /// Vietnamese words differ by them. The marks of the other scripts are part of their letters and
    /// stay, except the optional vowel signs of Arabic («غدًا» is «غدا», «إلى» is «الى»).
    static func normalize(_ value: String, foldLatinMarks: Bool = true) -> String {
        let decomposed = raw(value).decomposedStringWithCanonicalMapping
        var folded = String.UnicodeScalarView()
        var script = DictationScript.common
        for scalar in decomposed.unicodeScalars {
            if isMark(scalar) {
                let optional = (script == .latin && foldLatinMarks)
                    || (script == .arabic && ((0x064B...0x065F).contains(scalar.value) || scalar.value == 0x0670))
                if !optional { folded.append(scalar) }
                continue
            }
            if scalar.value == tatweel { continue }
            script = DictationScript.of(scalar)
            if foldLatinMarks, let replacement = latinLetterFolds[scalar.value] {
                folded.append(contentsOf: replacement.unicodeScalars)
            } else {
                folded.append(scalar)
            }
        }
        let words = wordsOnly(String(folded).precomposedStringWithCanonicalMapping)
        return singleSpaced(spacedScripts(words))
    }

    /// Chinese, Japanese, Thai and Burmese do not separate their words: a space inside their text means nothing.
    static func joinNativeWords(_ text: String) -> String {
        let scalars = Array(text.unicodeScalars)
        var joined = String.UnicodeScalarView()
        for index in scalars.indices {
            let scalar = scalars[index]
            let joins = scalar == " " && index > 0 && index < scalars.count - 1
                && isNativeLetter(scalars[index - 1]) && isNativeLetter(scalars[index + 1])
            if !joins { joined.append(scalar) }
        }
        return String(joined)
    }

    /// Digits of any script in ASCII and compatibility forms folded, in lower case; punctuation kept
    /// («9:35», «12/11»).
    static func raw(_ value: String) -> String {
        asciiDigits(value.precomposedStringWithCompatibilityMapping).lowercased()
    }

    /// «٩٥١٢», «९५१२» and «９５１２» are 9512: recognizers and keyboards write digits in the script of their language.
    static func asciiDigits(_ value: String) -> String {
        var result = String.UnicodeScalarView()
        for scalar in value.unicodeScalars {
            if scalar.value < 0x30 || scalar.value > 0x39, scalar.properties.numericType == .decimal,
               let digit = scalar.properties.numericValue, digit >= 0, digit <= 9 {
                result.append(Unicode.Scalar(UInt8(48 + Int(digit))))
            } else {
                result.append(scalar)
            }
        }
        return String(result)
    }

    static func isMark(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .nonspacingMark, .spacingMark, .enclosingMark: return true
        default: return false
        }
    }

    static func isLetter(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
        default: return false
        }
    }

    static func isDigit(_ scalar: Unicode.Scalar) -> Bool {
        scalar.properties.numericType == .decimal
    }

    private static func isNumber(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .decimalNumber, .letterNumber, .otherNumber: return true
        default: return false
        }
    }

    private static func isNativeLetter(_ scalar: Unicode.Scalar) -> Bool {
        (isLetter(scalar) || isMark(scalar)) && DictationScript.of(scalar) != .latin
    }

    /// Android's `[^\p{L}\p{M}\p{N}]+` replaced by a space.
    private static func wordsOnly(_ value: String) -> String {
        var result = String.UnicodeScalarView()
        var inGap = false
        for scalar in value.unicodeScalars {
            if isLetter(scalar) || isMark(scalar) || isNumber(scalar) {
                result.append(scalar)
                inGap = false
            } else if !inGap {
                result.append(" ")
                inGap = true
            }
        }
        return String(result)
    }

    /// Runs of spaces as one, none at either end.
    private static func singleSpaced(_ value: String) -> String {
        var result = String.UnicodeScalarView()
        var pendingSpace = false
        for scalar in value.unicodeScalars {
            if scalar == " " {
                pendingSpace = !result.isEmpty
                continue
            }
            if pendingSpace {
                result.append(" ")
                pendingSpace = false
            }
            result.append(scalar)
        }
        return String(result)
    }

    private enum Kind: Equatable {
        case digit
        case script(DictationScript)
    }

    private static func spacedScripts(_ text: String) -> String {
        var spaced = String.UnicodeScalarView()
        var previous: Kind?
        for scalar in text.unicodeScalars {
            let kind: Kind?
            if scalar == " " {
                kind = nil
            } else if isDigit(scalar) {
                kind = .digit
            } else if isMark(scalar) {
                kind = previous
            } else {
                let script = DictationScript.of(scalar)
                kind = script == .common || script == .inherited ? previous : Kind.script(script)
            }
            if let kind, let previous, kind != previous { spaced.append(" ") }
            spaced.append(scalar)
            previous = kind
        }
        return String(spaced)
    }

    // MARK: UTF-16 comparisons, as Android's `String` makes them

    static func isSpace(_ unit: UInt16) -> Bool {
        // Java's `\s`: space, tab, line feed, vertical tab, form feed, carriage return.
        unit == 0x20 || (0x09...0x0D).contains(unit)
    }

    static func hasPrefix(_ text: [UInt16], _ prefix: [UInt16], at start: Int = 0) -> Bool {
        guard start >= 0, start + prefix.count <= text.count else { return false }
        for offset in prefix.indices where text[start + offset] != prefix[offset] { return false }
        return true
    }

    static func hasSuffix(_ text: [UInt16], _ suffix: [UInt16]) -> Bool {
        hasPrefix(text, suffix, at: text.count - suffix.count)
    }

    /// The text before UTF-16 offset `end`.
    static func prefix(_ text: String, upTo end: Int) -> String {
        let units = Array(text.utf16)
        return String(decoding: units[0..<min(max(end, 0), units.count)], as: UTF16.self)
    }

    /// The text from UTF-16 offset `start`.
    static func suffix(_ text: String, from start: Int) -> String {
        let units = Array(text.utf16)
        return String(decoding: units[min(max(start, 0), units.count)...], as: UTF16.self)
    }

    static func trimmingLeadingSpaces(_ units: [UInt16]) -> [UInt16] {
        Array(units.drop(while: isSpace))
    }

    static func trimmingTrailingSpaces(_ units: [UInt16]) -> [UInt16] {
        var end = units.count
        while end > 0, isSpace(units[end - 1]) { end -= 1 }
        return Array(units[0..<end])
    }

    /// Whether `word` stands in `text` with a space or an end on both sides (Android's
    /// `(?:^|\s)word(?=\s|$)`).
    static func containsWord(_ text: [UInt16], _ word: [UInt16]) -> Bool {
        guard !word.isEmpty, word.count <= text.count else { return false }
        for start in 0...(text.count - word.count) where hasPrefix(text, word, at: start) {
            let end = start + word.count
            if (start == 0 || isSpace(text[start - 1])) && (end == text.count || isSpace(text[end])) { return true }
        }
        return false
    }

    static func contains(_ text: [UInt16], _ word: [UInt16]) -> Bool {
        guard word.count <= text.count else { return false }
        if word.isEmpty { return true }
        for start in 0...(text.count - word.count) where hasPrefix(text, word, at: start) { return true }
        return false
    }

    /// Android's `replace(Regex("(?:^|\\s)word(?=\\s|$)"), " ")`: every match, the space before it
    /// included, becomes one space; matches do not overlap.
    static func replacingWord(_ text: [UInt16], _ word: [UInt16]) -> [UInt16] {
        guard !word.isEmpty else { return text }
        var result: [UInt16] = []
        var position = 0
        while position <= text.count {
            var found: (start: Int, end: Int)?
            var candidate = position
            while candidate <= text.count, found == nil {
                if candidate == 0, hasPrefix(text, word, at: 0), endsWord(text, word.count) {
                    found = (0, word.count)
                } else if candidate < text.count, isSpace(text[candidate]),
                          hasPrefix(text, word, at: candidate + 1), endsWord(text, candidate + 1 + word.count) {
                    found = (candidate, candidate + 1 + word.count)
                }
                candidate += 1
            }
            guard let match = found else {
                result.append(contentsOf: text[position...])
                break
            }
            result.append(contentsOf: text[position..<match.start])
            result.append(0x20)
            position = match.end
            if position == text.count { break }
        }
        return result
    }

    /// Android's `String.replace(word, " ")`: every occurrence, left to right.
    static func replacing(_ text: [UInt16], _ word: [UInt16]) -> [UInt16] {
        guard !word.isEmpty else { return text }
        var result: [UInt16] = []
        var position = 0
        while position < text.count {
            if hasPrefix(text, word, at: position) {
                result.append(0x20)
                position += word.count
            } else {
                result.append(text[position])
                position += 1
            }
        }
        return result
    }

    private static func endsWord(_ text: [UInt16], _ end: Int) -> Bool {
        end == text.count || (end < text.count && isSpace(text[end]))
    }

    /// The UTF-16 ranges of `term` in `text` where it stands as whole words, a case ending of
    /// `endings` allowed after it (Android's `(?:^|\s)(term)(?:endings)?(?=\s|$)`, found one after
    /// the other without overlapping). `endings` are tried longest first, as the alternation is.
    static func wordMatches(of term: [UInt16], in text: [UInt16], endings: [[UInt16]]) -> [Range<Int>] {
        guard !term.isEmpty else { return [] }
        var ranges: [Range<Int>] = []
        var position = 0
        while position <= text.count {
            var found: (term: Range<Int>, end: Int)?
            var candidate = position
            while candidate <= text.count, found == nil {
                if candidate == 0, let end = tail(of: term, at: 0, in: text, endings: endings) {
                    found = (0..<term.count, end)
                } else if candidate < text.count, isSpace(text[candidate]),
                          let end = tail(of: term, at: candidate + 1, in: text, endings: endings) {
                    found = ((candidate + 1)..<(candidate + 1 + term.count), end)
                }
                candidate += 1
            }
            guard let match = found else { break }
            ranges.append(match.term)
            position = match.end
        }
        return ranges
    }

    /// Where a match of `term` at `start` ends, its ending included, when a space or the end follows.
    private static func tail(of term: [UInt16], at start: Int, in text: [UInt16], endings: [[UInt16]]) -> Int? {
        guard hasPrefix(text, term, at: start) else { return nil }
        let end = start + term.count
        for ending in endings where hasPrefix(text, ending, at: end) && endsWord(text, end + ending.count) {
            return end + ending.count
        }
        return endsWord(text, end) ? end : nil
    }
}

/// The script of a letter, as far as dictation needs it: Android's `Character.UnicodeScript` for the
/// scripts of the app's languages and their neighbours. Hiragana and Katakana count as Han, as on
/// Android; a letter of another script is `.other`.
enum DictationScript: Equatable, Sendable {
    case latin, greek, cyrillic, armenian, hebrew, arabic, devanagari, thai, lao, myanmar, georgian, hangul, han
    case common, inherited, other

    static func of(_ scalar: Unicode.Scalar) -> DictationScript {
        switch scalar.value {
        case 0x41...0x5A, 0x61...0x7A, 0xAA, 0xBA, 0xC0...0xD6, 0xD8...0xF6, 0xF8...0x2B8, 0x2E0...0x2E4,
             0x1D00...0x1D25, 0x1D2C...0x1D5C, 0x1D62...0x1D65, 0x1D6B...0x1D77, 0x1D79...0x1DBE,
             0x1E00...0x1EFF, 0x2071, 0x207F, 0x2090...0x209C, 0x212A, 0x212B, 0x2132, 0x214E, 0x2160...0x2188,
             0x2C60...0x2C7F, 0xA722...0xA787, 0xA78B...0xA7FF, 0xAB30...0xAB5A, 0xAB5C...0xAB64,
             0xFB00...0xFB06, 0xFF21...0xFF3A, 0xFF41...0xFF5A:
            return .latin
        case 0x0300...0x036F, 0x0485, 0x0486, 0x064B...0x0655, 0x0670, 0x1AB0...0x1AFF, 0x1DC0...0x1DFF,
             0x200C, 0x200D, 0x20D0...0x20FF, 0x3099, 0x309A, 0xFE00...0xFE0F, 0xFE20...0xFE2D:
            return .inherited
        case 0x02B9...0x02DF, 0x02E5...0x02FF, 0x0640, 0x3006, 0x30FC, 0xA700...0xA721, 0xA788...0xA78A, 0xFF70:
            return .common
        case 0x0370...0x03FF, 0x1F00...0x1FFF:
            return .greek
        case 0x0400...0x052F, 0x1C80...0x1C8F, 0x2DE0...0x2DFF, 0xA640...0xA69F:
            return .cyrillic
        case 0x0531...0x058F, 0xFB13...0xFB17:
            return .armenian
        case 0x0591...0x05FF, 0xFB1D...0xFB4F:
            return .hebrew
        case 0x0600...0x06FF, 0x0750...0x077F, 0x0870...0x08FF, 0xFB50...0xFDFF, 0xFE70...0xFEFF:
            return .arabic
        case 0x0900...0x097F, 0xA8E0...0xA8FF:
            return .devanagari
        case 0x0E01...0x0E5B:
            return .thai
        case 0x0E80...0x0EFF:
            return .lao
        case 0x1000...0x109F, 0xA9E0...0xA9FE, 0xAA60...0xAA7F:
            return .myanmar
        case 0x10A0...0x10FF, 0x1C90...0x1CBF, 0x2D00...0x2D2F:
            return .georgian
        case 0x1100...0x11FF, 0x3131...0x318E, 0xA960...0xA97F, 0xAC00...0xD7AF, 0xD7B0...0xD7FF, 0xFFA0...0xFFDC:
            return .hangul
        case 0x2E80...0x2FDF, 0x3005, 0x3007, 0x3021...0x3029, 0x3038...0x303B, 0x3041...0x3096, 0x309D...0x309F,
             0x30A1...0x30FA, 0x30FD...0x30FF, 0x31F0...0x31FF, 0x32D0...0x32FE, 0x3400...0x4DBF, 0x4E00...0x9FFF,
             0xF900...0xFAFF, 0xFF66...0xFF6F, 0xFF71...0xFF9D, 0x20000...0x3134F:
            return .han
        default:
            return DictationText.isLetter(scalar) || DictationText.isMark(scalar) ? .other : .common
        }
    }
}

/// A compiled pattern of the dictation grammars. `NSRegularExpression` is immutable and thread
/// safe, and it is ICU's engine, as Android's `Regex` is on the phone.
struct DictationPattern: @unchecked Sendable {
    private let expression: NSRegularExpression?

    /// `source` in its NFKC form, as Android compiles the grammars' patterns (Thai «ำ» is compared as «ํา»).
    init(_ source: String) {
        expression = try? NSRegularExpression(pattern: source.precomposedStringWithCompatibilityMapping)
    }

    /// False when ICU refuses the pattern (a test keeps every grammar's patterns valid).
    var isValid: Bool { expression != nil }

    func firstMatch(in text: String) -> DictationMatch? {
        guard let expression else { return nil }
        let length = text.utf16.count
        guard let result = expression.firstMatch(in: text, options: [], range: NSRange(location: 0, length: length)) else {
            return nil
        }
        let groups: [Range<Int>?] = (0..<result.numberOfRanges).map { index in
            let range = result.range(at: index)
            return range.location == NSNotFound ? nil : range.location..<(range.location + range.length)
        }
        return DictationMatch(text: Array(text.utf16), groups: groups)
    }
}

/// A match of a `DictationPattern`, in UTF-16 offsets.
struct DictationMatch {
    let text: [UInt16]
    /// Index 0 is the whole match; nil for a group that did not take part.
    let groups: [Range<Int>?]

    var range: Range<Int> { groups.first.flatMap { $0 } ?? 0..<0 }

    func group(_ index: Int) -> Range<Int>? {
        index < groups.count ? groups[index] : nil
    }

    /// The text of a group, empty when it is absent or did not take part (Android's
    /// `groupValues.getOrElse(index) { "" }`).
    func value(_ index: Int) -> String {
        guard let range = group(index) else { return "" }
        return String(decoding: text[range], as: UTF16.self)
    }
}

/// A day and a month; `monthFirst` when the month comes first («October 9», «11月12日»); group 3 is the year.
struct DictationDatePattern: Sendable {
    let pattern: String
    let monthFirst: Bool

    init(_ pattern: String, monthFirst: Bool = false) {
        self.pattern = pattern
        self.monthFirst = monthFirst
    }
}

enum DictationStationRole: Sendable {
    case from, to
}

/// The words of one language for a day, a time, a return and the role of a station (Android's
/// `VoiceGrammar`). Words are written as people write them and compared after `text(_:)`; the `times`
/// and `dates` patterns are written in that compared form.
struct DictationGrammar: Sendable {
    struct DateRule: Sendable {
        let pattern: DictationPattern
        let monthFirst: Bool
    }

    /// Words are separated by spaces; otherwise (Chinese, Japanese, Korean particles, Thai, Burmese) a
    /// word is found inside the text.
    let spaced: Bool
    /// Vietnamese tells «lúc» (at) from «lục» (six) by the tone mark: its words keep them. Station names never do.
    private let keepsLatinMarks: Bool
    let today: [String]
    let tomorrow: [String]
    let dayAfterTomorrow: [String]
    private let notDays: [String]
    private let afternoon: [String]
    private let notAfternoon: [String]
    let returnTrip: [String]
    let oneWay: [String]
    private let months: [String: Int]
    let times: [DictationPattern]
    let dateRules: [DateRule]
    let minuteWords: [String: Int]
    private let continuations: [String]
    private let hourModifiers: [(word: String, minutes: Int)]
    /// «12/11» is the 12th of November; not read where the month comes first (Japanese, Korean, Chinese).
    let readsSlashDates: Bool
    /// Years of the Thai calendar are 543 ahead.
    private let eraOffset: Int
    private let fromBefore: [String]
    private let toBefore: [String]
    private let fromAfter: [String]
    private let toAfter: [String]
    /// Case endings written onto a station name (Turkish «Romadan»), longest first.
    let stationEndings: [[UInt16]]

    init(
        spaced: Bool = true,
        keepsLatinMarks: Bool = false,
        today: [String],
        tomorrow: [String],
        dayAfterTomorrow: [String] = [],
        notDays: [String] = [],
        afternoon: [String] = [],
        notAfternoon: [String] = [],
        returnTrip: [String] = [],
        oneWay: [String] = [],
        months: [(String, Int)] = [],
        times: [String],
        dates: [DictationDatePattern] = [],
        minuteWords: [(String, Int)] = [],
        continuations: [String] = [],
        hourModifiers: [(String, Int)] = [],
        readsSlashDates: Bool = true,
        eraOffset: Int = 0,
        fromBefore: [String] = [],
        toBefore: [String] = [],
        fromAfter: [String] = [],
        toAfter: [String] = [],
        stationEndings: [String] = []
    ) {
        func text(_ value: String) -> String {
            Self.text(value, spaced: spaced, keepsLatinMarks: keepsLatinMarks)
        }
        func words(_ list: [String]) -> [String] {
            var seen = Set<String>()
            return list.map(text).filter { !$0.isEmpty && seen.insert($0).inserted }
        }
        func stationWords(_ list: [String]) -> [String] {
            var seen = Set<String>()
            return list.map { DictationText.normalize($0) }.filter { !$0.isEmpty && seen.insert($0).inserted }
        }
        self.spaced = spaced
        self.keepsLatinMarks = keepsLatinMarks
        self.today = words(today)
        self.tomorrow = words(tomorrow)
        self.dayAfterTomorrow = words(dayAfterTomorrow)
        self.notDays = words(notDays)
        self.afternoon = words(afternoon)
        self.notAfternoon = words(notAfternoon)
        self.returnTrip = words(returnTrip)
        self.oneWay = words(oneWay)
        var monthTable: [String: Int] = [:]
        var monthNames: [String] = []
        for (name, number) in months {
            let key = text(name)
            if monthTable[key] == nil { monthNames.append(key) }
            monthTable[key] = number
        }
        self.months = monthTable
        let alternation = monthNames
            .sorted { $0.utf16.count > $1.utf16.count }
            .map { NSRegularExpression.escapedPattern(for: $0) }
            .joined(separator: "|")
        self.times = times.map { DictationPattern($0) }
        self.dateRules = dates.map {
            DateRule(pattern: DictationPattern($0.pattern.replacingOccurrences(of: "{M}", with: alternation)), monthFirst: $0.monthFirst)
        }
        var minuteTable: [String: Int] = [:]
        for (word, minutes) in minuteWords { minuteTable[text(word)] = minutes }
        self.minuteWords = minuteTable
        self.continuations = words(continuations)
        self.hourModifiers = hourModifiers.map { (word: text($0.0), minutes: $0.1) }
        self.readsSlashDates = readsSlashDates
        self.eraOffset = eraOffset
        self.fromBefore = stationWords(fromBefore)
        self.toBefore = stationWords(toBefore)
        self.fromAfter = stationWords(fromAfter)
        self.toAfter = stationWords(toAfter)
        self.stationEndings = stationWords(stationEndings)
            .sorted { $0.utf16.count > $1.utf16.count }
            .map { Array($0.utf16) }
    }

    private static func text(_ value: String, spaced: Bool, keepsLatinMarks: Bool) -> String {
        let normalized = DictationText.normalize(value, foldLatinMarks: !keepsLatinMarks)
        return spaced ? normalized : DictationText.joinNativeWords(normalized)
    }

    /// `value` as this grammar compares its words.
    func text(_ value: String) -> String {
        Self.text(value, spaced: spaced, keepsLatinMarks: keepsLatinMarks)
    }

    /// Every pattern compiles (a test checks it: ICU refusing one would silently read nothing).
    var patternsAreValid: Bool {
        times.allSatisfy(\.isValid) && dateRules.allSatisfy(\.pattern.isValid)
    }

    func has(_ text: String, _ word: String) -> Bool {
        spaced
            ? DictationText.containsWord(Array(text.utf16), Array(word.utf16))
            : DictationText.contains(Array(text.utf16), Array(word.utf16))
    }

    func hasAny(_ text: String, _ words: [String]) -> Bool {
        words.contains { has(text, $0) }
    }

    private func without(_ text: String, _ phrases: [String]) -> String {
        let rest = phrases.reduce(Array(text.utf16)) { rest, phrase in
            spaced ? DictationText.replacingWord(rest, Array(phrase.utf16)) : DictationText.replacing(rest, Array(phrase.utf16))
        }
        return String(decoding: rest, as: UTF16.self)
    }

    func withoutNotDays(_ text: String) -> String {
        without(text, notDays)
    }

    func saysAfternoon(_ text: String) -> Bool {
        hasAny(without(text, notAfternoon), afternoon)
    }

    /// Whether what follows a time changes it: a word of `continuations`, or a number where no minutes were said.
    func continues(_ rest: String, minutesSaid: Bool) -> Bool {
        let next = DictationText.trimmingLeadingSpaces(Array(rest.utf16))
        if !minutesSaid, let first = String(decoding: next, as: UTF16.self).unicodeScalars.first, DictationText.isDigit(first) {
            return true
        }
        return continuations.contains { startsWithWord(next, Array($0.utf16)) }
    }

    /// The minutes a word right before the hour moves it by (Hindi «साढ़े 9 बजे» is 9:30).
    func modifierBefore(_ text: String, hourStart: Int) -> Int? {
        let before = DictationText.trimmingTrailingSpaces(Array(DictationText.prefix(text, upTo: hourStart).utf16))
        return hourModifiers.first { endsWithWord(before, Array($0.word.utf16)) }?.minutes
    }

    func month(_ text: String) -> Int? {
        months[text] ?? Int(text)
    }

    func year(_ text: String) -> Int? {
        Int(text).map { eraOffset > 0 && $0 > 2_400 ? $0 - eraOffset : $0 }
    }

    /// What the words next to a station name in `stationText` (its UTF-16 `range`) say about it.
    func role(_ stationText: String, range: Range<Int>) -> DictationStationRole? {
        let units = Array(stationText.utf16)
        let before = DictationText.trimmingTrailingSpaces(Array(units[0..<range.lowerBound]))
        let after = DictationText.trimmingLeadingSpaces(Array(units[range.upperBound...]))
        let from = fromBefore.contains { endsWithWord(before, Array($0.utf16)) }
            || fromAfter.contains { startsWithWord(after, Array($0.utf16)) }
        let to = toBefore.contains { endsWithWord(before, Array($0.utf16)) }
            || toAfter.contains { startsWithWord(after, Array($0.utf16)) }
        if from && !to { return .from }
        if to && !from { return .to }
        return nil
    }

    private func startsWithWord(_ text: [UInt16], _ word: [UInt16]) -> Bool {
        guard spaced else { return DictationText.hasPrefix(text, word) }
        return text == word || DictationText.hasPrefix(text, word + [0x20])
    }

    private func endsWithWord(_ text: [UInt16], _ word: [UInt16]) -> Bool {
        guard spaced else { return DictationText.hasSuffix(text, word) }
        return text == word || DictationText.hasSuffix(text, [0x20] + word)
    }
}

/// One grammar per language of the app (Android's `VoiceGrammars`): the language of the app chooses
/// it, so the words never mix.
enum DictationGrammars {
    private static let westernMinutes = "(\\d{2}|mezza|mezzo|media|demie|un quarto|quarto|cuarto|quart)"
    private static let meridiem = "(?:\\s*(am|a m|pm|p m))?"

    /// Italian, English, German, French and Spanish share one table, as on Android: their words were
    /// built and tested together and do not collide.
    static let western = DictationGrammar(
        today: ["oggi", "stasera", "stamattina", "today", "tonight", "heute", "aujourd'hui", "ce soir", "hoy"],
        tomorrow: ["domani", "tomorrow", "morgen", "demain", "mañana"],
        dayAfterTomorrow: ["dopodomani", "day after tomorrow", "übermorgen", "après-demain", "pasado mañana"],
        // "heute morgen" is this morning, "por la mañana" in the morning.
        notDays: ["heute morgen", "por la mañana", "de la mañana"],
        afternoon: [
            "di sera", "della sera", "la sera", "stasera", "di pomeriggio", "del pomeriggio", "nel pomeriggio",
            "il pomeriggio", "di notte", "stanotte", "in the evening", "this evening", "tonight", "in the afternoon",
            "this afternoon", "at night", "abends", "am abend", "heute abend", "nachmittags", "am nachmittag",
            "heute nachmittag", "nachts", "du soir", "le soir", "ce soir", "de l'après-midi", "l'après-midi",
            "cet après-midi", "de la tarde", "por la tarde", "esta tarde", "de la noche", "por la noche", "esta noche",
        ],
        returnTrip: [
            "andata e ritorno", "andata ritorno", "return trip", "round trip", "return journey", "and back",
            "hin und zurück", "hin und rückfahrt", "hin und retour", "aller-retour", "ida y vuelta",
        ],
        oneWay: [
            "sola andata", "solo andata", "one way", "single journey", "einfache fahrt", "nur hinfahrt",
            "aller simple", "solo ida",
        ],
        months: [
            ("gennaio", 1), ("febbraio", 2), ("marzo", 3), ("aprile", 4), ("maggio", 5), ("giugno", 6),
            ("luglio", 7), ("agosto", 8), ("settembre", 9), ("ottobre", 10), ("novembre", 11), ("dicembre", 12),
            ("january", 1), ("february", 2), ("march", 3), ("april", 4), ("may", 5), ("june", 6),
            ("july", 7), ("august", 8), ("september", 9), ("october", 10), ("november", 11), ("december", 12),
            ("januar", 1), ("februar", 2), ("märz", 3), ("mai", 5), ("juni", 6), ("juli", 7),
            ("oktober", 10), ("dezember", 12),
            ("janvier", 1), ("février", 2), ("mars", 3), ("avril", 4), ("juin", 6), ("juillet", 7),
            ("août", 8), ("septembre", 9), ("octobre", 10), ("décembre", 12),
            ("enero", 1), ("febrero", 2), ("abril", 4), ("mayo", 5), ("junio", 6), ("julio", 7),
            ("septiembre", 9), ("setiembre", 9), ("octubre", 10), ("noviembre", 11), ("diciembre", 12),
        ],
        times: [
            "(?:^|\\s)(?:alle|ore|per le|at|around|um|gegen|vers|a las|a la|sobre las|hacia las|a)\\s+(\\d{1,2})" +
                "(?:\\s*(?:uhr|heures|heure|horas|hours|h|o clock))?(?:\\s*(?:e|y|and|et)?\\s*\(westernMinutes))?\(meridiem)(?=\\s|$)",
            "(?:^|\\s)(\\d{1,2})\\s*(?:uhr|heures|heure|h)(?:\\s*\(westernMinutes))?\(meridiem)(?=\\s|$)",
        ],
        dates: [
            DictationDatePattern("(?:^|\\s)(\\d{1,2})(?:\\s?(?:st|nd|rd|th|er|o))?\\s+(?:de\\s+|of\\s+)?({M})(?:\\s+(?:de\\s+)?(\\d{4}))?(?=\\s|$)"),
            DictationDatePattern("(?:^|\\s)({M})\\s+(?:the\\s+)?(\\d{1,2})(?:\\s?(?:st|nd|rd|th))?(?:\\s+(\\d{4}))?(?=\\s|$)", monthFirst: true),
        ],
        minuteWords: [
            ("mezza", 30), ("mezzo", 30), ("media", 30), ("demie", 30),
            ("un quarto", 15), ("quarto", 15), ("cuarto", 15), ("quart", 15),
        ],
        // «alle 8 e tre quarti», «alle 9 meno 10», «a las 8 menos cuarto», «à 9 h moins le quart», «um 10 vor 8».
        continuations: ["e", "y", "and", "et", "und", "meno", "menos", "moins", "minus", "past", "vor"],
        fromBefore: ["da", "dal", "dalla", "dallo", "from", "von", "vom", "ab", "de", "du", "des", "desde"],
        toBefore: [
            "a", "al", "alla", "allo", "per", "verso", "to", "nach", "zum", "zur", "bis", "à", "au", "vers", "pour",
            "hacia", "para", "hasta",
        ]
    )

    // The languages below follow the example sentences of the companion app and the hints of the app, with
    // the forms its audit found misread (Android's VoiceGrammars, word for word).

    static let portuguese = DictationGrammar(
        today: ["hoje", "esta noite", "esta tarde"],
        tomorrow: ["amanhã"],
        dayAfterTomorrow: ["depois de amanhã"],
        afternoon: ["da tarde", "da noite", "à tarde", "à noite", "de tarde", "de noite", "esta tarde", "esta noite"],
        returnTrip: ["ida e volta"],
        oneWay: ["só ida", "somente ida", "apenas ida"],
        months: [
            ("janeiro", 1), ("fevereiro", 2), ("março", 3), ("abril", 4), ("maio", 5), ("junho", 6),
            ("julho", 7), ("agosto", 8), ("setembro", 9), ("outubro", 10), ("novembro", 11), ("dezembro", 12),
        ],
        // «às 9:35», «às 21h30», «às 18h», «às 8 e meia».
        times: [
            "(?:^|\\s)(?:as|a|pelas|por volta das)\\s(\\d{1,2})(?:\\s?(?:horas|hora|hs|h))?" +
                "(?:\\s(?:e\\s)?(\\d{2}|meia)(?:\\s(?:minutos|min))?)?(?=\\s|$)",
            "(?:^|\\s)(\\d{1,2})\\s?(?:horas|h)(?:\\s(?:e\\s)?(\\d{2}|meia))?(?=\\s|$)",
        ],
        dates: [DictationDatePattern("(?:^|\\s)(?:dia\\s)?(\\d{1,2})\\s(?:de\\s)?({M})(?:\\s(?:de\\s)?(\\d{4}))?(?=\\s|$)")],
        minuteWords: [("meia", 30)],
        continuations: ["e", "menos"],
        fromBefore: ["de", "do", "da", "desde"],
        toBefore: ["para", "pra", "até", "ao", "a"]
    )

    static let turkish = DictationGrammar(
        today: ["bugün", "bu akşam"],
        tomorrow: ["yarın"],
        dayAfterTomorrow: ["yarından sonra", "öbür gün"],
        afternoon: ["akşam", "öğleden sonra"],
        returnTrip: ["gidiş dönüş"],
        oneWay: ["tek yön", "tek gidiş"],
        months: [
            ("ocak", 1), ("şubat", 2), ("mart", 3), ("nisan", 4), ("mayıs", 5), ("haziran", 6),
            ("temmuz", 7), ("ağustos", 8), ("eylül", 9), ("ekim", 10), ("kasım", 11), ("aralık", 12),
        ],
        // «saat 9:35», «saat 18'de», «saat 8 buçukta», «akşam 8'de»; the case ending after the hour is read too.
        times: [
            "(?:^|\\s)(?:saat|aksam|sabah|ogleden sonra)\\s(\\d{1,2})(?:\\s(\\d{2}|bucuk))?(?:\\s?(?:de|da|te|ta))?(?=\\s|$)",
        ],
        dates: [DictationDatePattern("(?:^|\\s)(\\d{1,2})\\s({M})(?:\\s(\\d{4}))?(?=\\s|$)")],
        minuteWords: [("buçuk", 30)],
        // «saat 9'u çeyrek geçe», «saat 9'a 10 kala»: the quarter-past and to-the-hour forms are not read.
        continuations: ["çeyrek", "geçe", "kala", "u", "ü", "i", "ı", "yi", "a", "e", "ya", "ye"],
        fromAfter: ["dan", "den", "tan", "ten", "ndan", "nden"],
        toAfter: ["ya", "ye", "a", "e", "na", "ne"],
        stationEndings: ["dan", "den", "tan", "ten", "ndan", "nden", "ya", "ye", "na", "ne", "a", "e", "da", "de", "ta", "te"]
    )

    static let vietnamese = DictationGrammar(
        keepsLatinMarks: true,
        today: ["hôm nay", "tối nay", "chiều nay", "sáng nay"],
        tomorrow: ["ngày mai", "sáng mai", "chiều mai", "tối mai"],
        dayAfterTomorrow: ["ngày kia", "ngày mốt"],
        afternoon: ["chiều", "tối"],
        notAfternoon: ["một chiều", "chiều dài"],
        returnTrip: ["khứ hồi"],
        oneWay: ["một chiều"],
        // «lúc 9:35», «lúc 18 giờ», «lúc 9 giờ 35 phút», «lúc 8 giờ rưỡi», «9h35».
        times: [
            "(?:^|\\s)(?:lúc|vào)\\s(\\d{1,2})(?:\\s?(?:giờ|h))?(?:\\s(\\d{2}|\\d(?=\\s?phút)|rưỡi)(?:\\s?phút)?)?(?=\\s|$)",
            "(?:^|\\s)(\\d{1,2})\\s?(?:giờ|h)(?:\\s(\\d{2}|\\d(?=\\s?phút)|rưỡi)(?:\\s?phút)?)?(?=\\s|$)",
        ],
        dates: [DictationDatePattern("(?:^|\\s)(?:ngày\\s)?(\\d{1,2})\\stháng\\s(\\d{1,2})(?:\\snăm\\s(\\d{4}))?(?=\\s|$)")],
        minuteWords: [("rưỡi", 30)],
        // «9 giờ kém 15» is 8:45, «9 giờ lẻ 5» 9:05.
        continuations: ["kém", "lẻ"],
        fromBefore: ["từ"],
        toBefore: ["đến", "tới", "về"]
    )

    static let indonesian = DictationGrammar(
        today: ["hari ini", "malam ini", "sore ini", "nanti malam"],
        tomorrow: ["besok", "esok"],
        dayAfterTomorrow: ["lusa", "besok lusa"],
        afternoon: ["sore", "malam"],
        returnTrip: ["pulang pergi"],
        oneWay: ["sekali jalan", "satu arah"],
        months: [
            ("januari", 1), ("februari", 2), ("maret", 3), ("april", 4), ("mei", 5), ("juni", 6),
            ("juli", 7), ("agustus", 8), ("september", 9), ("oktober", 10), ("november", 11), ("desember", 12),
        ],
        // «pukul 9.35», «pukul 18», «jam 18:00».
        times: ["(?:^|\\s)(?:pukul|jam)\\s(\\d{1,2})(?:\\s(\\d{2}))?(?=\\s|$)"],
        dates: [DictationDatePattern("(?:^|\\s)(?:tanggal\\s)?(\\d{1,2})\\s({M})(?:\\s(\\d{4}))?(?=\\s|$)")],
        // «jam 9 lewat 15», «jam 9 kurang 15».
        continuations: ["lewat", "kurang", "lebih"],
        fromBefore: ["dari"],
        toBefore: ["ke", "menuju", "sampai"]
    )

    static let russian = DictationGrammar(
        today: ["сегодня"],
        tomorrow: ["завтра"],
        dayAfterTomorrow: ["послезавтра"],
        afternoon: ["вечера", "вечером", "дня"],
        returnTrip: ["туда и обратно", "туда обратно"],
        oneWay: ["в одну сторону", "только туда"],
        months: [
            ("января", 1), ("февраля", 2), ("марта", 3), ("апреля", 4), ("мая", 5), ("июня", 6),
            ("июля", 7), ("августа", 8), ("сентября", 9), ("октября", 10), ("ноября", 11), ("декабря", 12),
        ],
        // «в 9:35», «в 18», «в 8 вечера», «в 9 часов 35 минут».
        times: [
            "(?:^|\\s)(?:в|к|около)\\s(\\d{1,2})(?:\\s?(?:часов|часа|час|ч))?" +
                "(?:\\s(\\d{2}|\\d(?=\\s?мин))(?:\\s?(?:минут|минуты|минута|мин))?)?(?=\\s|$)",
            "(?:^|\\s)(\\d{1,2})\\s?(?:часов|часа|час|ч)(?:\\s(\\d{2}|\\d(?=\\s?мин))(?:\\s?(?:минут|минуты|минута|мин))?)?(?=\\s|$)",
        ],
        dates: [DictationDatePattern("(?:^|\\s)(\\d{1,2})\\s({M})(?:\\s(\\d{4}))?(?=\\s|$)")],
        continuations: ["без"],
        fromBefore: ["из", "от", "с"],
        toBefore: ["в", "до", "на"]
    )

    static let arabic = DictationGrammar(
        today: ["اليوم", "الليلة", "هذا المساء"],
        tomorrow: ["غدًا", "غدا", "بكرة", "بكرا"],
        dayAfterTomorrow: ["بعد غد", "بعد غدا", "بعد بكرة"],
        afternoon: ["مساءً", "مساءا", "مساء", "بعد الظهر", "الليلة", "هذا المساء"],
        returnTrip: ["ذهاب وعودة", "ذهاب وإياب", "ذهابا وإيابا"],
        oneWay: ["ذهاب فقط", "اتجاه واحد"],
        months: [
            ("يناير", 1), ("فبراير", 2), ("مارس", 3), ("أبريل", 4), ("مايو", 5), ("يونيو", 6),
            ("يوليو", 7), ("أغسطس", 8), ("سبتمبر", 9), ("أكتوبر", 10), ("نوفمبر", 11), ("ديسمبر", 12),
        ],
        // «الساعة 9:35», «الساعة 18», «الساعة 9 والنصف», «الساعة 9 و35 دقيقة».
        times: [
            "(?:^|\\s)الساعة\\s(\\d{1,2})(?:\\s(?:و\\s?)?(\\d{2}|\\d(?=\\s?دقيق)|النصف|نصف|الربع|ربع)" +
                "(?:\\s?(?:دقيقة|دقائق))?)?(?=\\s|$)",
        ],
        dates: [DictationDatePattern("(?:^|\\s)(\\d{1,2})\\s({M})(?:\\s(\\d{4}))?(?=\\s|$)")],
        minuteWords: [("النصف", 30), ("نصف", 30), ("الربع", 15), ("ربع", 15)],
        // «التاسعة إلا ربع» is 8:45.
        continuations: ["إلا"],
        fromBefore: ["من"],
        toBefore: ["إلى", "حتى"]
    )

    static let hindi = DictationGrammar(
        today: ["आज"],
        tomorrow: ["कल"],
        dayAfterTomorrow: ["परसों"],
        afternoon: ["शाम", "दोपहर"],
        returnTrip: ["आना जाना", "राउंड ट्रिप", "वापसी सहित"],
        oneWay: ["एक तरफ़ा", "एक तरफा", "वन वे"],
        months: [
            ("जनवरी", 1), ("फ़रवरी", 2), ("फरवरी", 2), ("मार्च", 3), ("अप्रैल", 4), ("मई", 5), ("जून", 6),
            ("जुलाई", 7), ("अगस्त", 8), ("सितंबर", 9), ("सितम्बर", 9), ("अक्टूबर", 10), ("नवंबर", 11),
            ("नवम्बर", 11), ("दिसंबर", 12), ("दिसम्बर", 12),
        ],
        // «9:35 बजे», «18 बजे», «9 बजकर 35 मिनट».
        times: [
            "(?:^|\\s)(\\d{1,2})(?:\\s(\\d{2}))?\\s?बजे(?=\\s|$)",
            "(?:^|\\s)(\\d{1,2})\\s?बजकर\\s(\\d{1,2})\\s?मिनट(?=\\s|$)",
        ],
        dates: [DictationDatePattern("(?:^|\\s)(\\d{1,2})\\s({M})(?:\\s(\\d{4}))?(?=\\s|$)")],
        // «साढ़े 9 बजे» 9:30, «सवा 9 बजे» 9:15, «पौने 9 बजे» 8:45.
        hourModifiers: [("साढ़े", 30), ("सवा", 15), ("पौने", -15)],
        fromAfter: ["से"],
        toAfter: ["तक", "के लिए", "को"]
    )

    static let japanese = DictationGrammar(
        spaced: false,
        today: ["今日", "きょう", "本日", "今夜", "今晩"],
        tomorrow: ["明日", "あした"],
        dayAfterTomorrow: ["明後日", "あさって"],
        notDays: ["明日香"],
        afternoon: ["午後", "夕方", "今夜", "今晩"],
        returnTrip: ["往復"],
        oneWay: ["片道"],
        // «9時35分», «18 時», «8時半»; «2時間» is two hours.
        times: ["(?<!\\d)(\\d{1,2}) ?時(?!間)(?: ?(\\d{1,2}(?= ?分)|半)(?: ?分)?)?"],
        dates: [DictationDatePattern("(?<!\\d)(\\d{1,2}) ?月 ?(\\d{1,2}) ?日", monthFirst: true)],
        minuteWords: [("半", 30)],
        // «9時5分前» is 8:55.
        continuations: ["前"],
        readsSlashDates: false,
        fromAfter: ["から", "発"],
        toAfter: ["まで", "行き", "へ", "着"]
    )

    static let korean = DictationGrammar(
        // Words carry their particles («내일은», «9시에»): a word is found inside the text.
        spaced: false,
        today: ["오늘"],
        tomorrow: ["내일"],
        dayAfterTomorrow: ["모레", "내일모레"],
        afternoon: ["오후", "저녁"],
        returnTrip: ["왕복"],
        oneWay: ["편도"],
        // «9시 35분», «18시», «8시 반»; «2시간» is two hours.
        times: ["(?<!\\d)(\\d{1,2}) ?시(?!간)(?: ?(\\d{1,2}(?= ?분)|반)(?: ?분)?)?"],
        dates: [DictationDatePattern("(?<!\\d)(\\d{1,2}) ?월 ?(\\d{1,2}) ?일", monthFirst: true)],
        minuteWords: [("반", 30)],
        // «9시 5분 전» is 8:55.
        continuations: ["전"],
        readsSlashDates: false,
        fromAfter: ["에서", "출발"],
        toAfter: ["까지", "으로", "로", "행"]
    )

    static let chinese = DictationGrammar(
        spaced: false,
        today: ["今天", "今晚", "今日"],
        tomorrow: ["明天", "明日", "明早", "明晚"],
        dayAfterTomorrow: ["后天", "後天"],
        notDays: ["大后天", "大後天"],
        afternoon: ["下午", "晚上", "今晚", "傍晚", "明晚"],
        returnTrip: ["往返", "来回"],
        oneWay: ["单程"],
        // «9点35分», «18 点», «8点半», «8点一刻»; «2小时» is two hours.
        times: ["(?<!\\d)(\\d{1,2}) ?(?:点|點|时)(?: ?(\\d{2}|\\d(?= ?分)|半|一刻|三刻)(?: ?分)?)?"],
        dates: [DictationDatePattern("(?<!\\d)(\\d{1,2}) ?月 ?(\\d{1,2}) ?(?:日|号|號)", monthFirst: true)],
        minuteWords: [("半", 30), ("一刻", 15), ("三刻", 45)],
        // «9点差5分» is 8:55.
        continuations: ["差", "前"],
        readsSlashDates: false,
        fromBefore: ["从", "從", "由"],
        toBefore: ["到", "去", "至", "往"]
    )

    static let thai = DictationGrammar(
        spaced: false,
        today: ["วันนี้", "คืนนี้"],
        tomorrow: ["พรุ่งนี้"],
        dayAfterTomorrow: ["มะรืนนี้", "มะรืน"],
        afternoon: ["บ่าย", "เย็น", "ค่ำ", "คืนนี้"],
        returnTrip: ["ไปกลับ", "ไป-กลับ"],
        oneWay: ["เที่ยวเดียว"],
        months: [
            ("มกราคม", 1), ("กุมภาพันธ์", 2), ("มีนาคม", 3), ("เมษายน", 4), ("พฤษภาคม", 5), ("มิถุนายน", 6),
            ("กรกฎาคม", 7), ("สิงหาคม", 8), ("กันยายน", 9), ("ตุลาคม", 10), ("พฤศจิกายน", 11), ("ธันวาคม", 12),
        ],
        // «18 นาฬิกา», «6 โมงเย็น», «บ่าย 2 โมง», «เวลา 9:35», «18.00 น.».
        times: [
            "(?<!\\d)(\\d{1,2}) ?นาฬิกา(?: ?(\\d{2}|\\d(?= ?นาที)|ครึ่ง)(?: ?นาที)?)?",
            "(?<!\\d)(\\d{1,2}) ?โมง(?: ?(\\d{2}|\\d(?= ?นาที)|ครึ่ง)(?: ?นาที)?)?",
            "เวลา ?(\\d{1,2})(?: (\\d{2}))?(?!\\d)",
            "(?<!\\d)(\\d{1,2}) (\\d{2}) ?น(?!า)",
        ],
        dates: [DictationDatePattern("(?<!\\d)(\\d{1,2}) ?({M})(?: ?(\\d{4}))?")],
        minuteWords: [("ครึ่ง", 30)],
        // «ใช้เวลา 2 ชั่วโมง» takes two hours.
        continuations: ["ชั่วโมง", "นาที"],
        eraOffset: 543,
        fromBefore: ["จาก"],
        toBefore: ["ไป", "ถึง", "ไปยัง", "ไปที่"]
    )

    static let burmese = DictationGrammar(
        spaced: false,
        today: ["ယနေ့", "ဒီနေ့"],
        tomorrow: ["မနက်ဖြန်", "နက်ဖြန်"],
        dayAfterTomorrow: ["သန်ဘက်ခါ"],
        afternoon: ["ညနေ"],
        returnTrip: ["အသွားအပြန်"],
        oneWay: ["အသွားသာ"],
        // «9:35», «18 နာရီ», «9 နာရီ 35 မိနစ်», «9 နာရီခွဲ».
        times: ["(?<!\\d)(\\d{1,2}) ?နာရီ(?: ?(\\d{2}|\\d(?= ?မိနစ်)|ခွဲ)(?: ?မိနစ်)?)?"],
        minuteWords: [("ခွဲ", 30)],
        // «2 နာရီကြာ» takes two hours.
        continuations: ["ကြာ", "ထိုး"],
        fromAfter: ["မှ", "ကနေ"],
        toAfter: ["သို့", "ကို", "အထိ"]
    )

    /// Every grammar, for the test that compiles their patterns.
    static let all: [DictationGrammar] = [
        western, portuguese, turkish, vietnamese, indonesian, russian, arabic, hindi, japanese, korean, chinese, thai, burmese,
    ]

    /// The grammar of a language code ("in" is Indonesian on Android, "id" on the iPhone), or nil where
    /// the app knows no words.
    static func forLanguage(_ language: String) -> DictationGrammar? {
        switch language.lowercased() {
        case "it", "en", "de", "fr", "es": return western
        case "pt": return portuguese
        case "tr": return turkish
        case "vi": return vietnamese
        case "in", "id": return indonesian
        case "ru": return russian
        case "ar": return arabic
        case "hi": return hindi
        case "ja": return japanese
        case "ko": return korean
        case "my": return burmese
        case "th": return thai
        case "zh": return chinese
        default: return nil
        }
    }

    /// The grammar of the five first languages.
    static var standard: DictationGrammar { western }
}
