import Foundation

// Tolerant readers of what the public sources send. A payload is data, never trusted: lengths,
// ranges and dates are checked by the parsers; malformed input gives fewer values, never a crash.

enum LiveText {
    static func isDigits(_ value: String, count: ClosedRange<Int>) -> Bool {
        let bytes = value.utf8
        return count.contains(bytes.count) && bytes.allSatisfy { (48...57).contains($0) }
    }

    /// `value` in decimal with leading zeros up to `width` digits.
    static func padded(_ value: Int, width: Int) -> String {
        let digits = String(value.magnitude)
        let zeros = String(repeating: "0", count: max(0, width - digits.count))
        return (value < 0 ? "-" : "") + zeros + digits
    }

    /// Whitespace runs as one space, trimmed (Kotlin `replace(Regex("\\s+"), " ").trim()`, Jsoup `text()`).
    static func compact(_ value: String) -> String {
        value.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    /// `value` compacted, or nil when empty or longer than `maximumLength` characters.
    static func bounded(_ value: String?, maximumLength: Int) -> String? {
        guard let value else { return nil }
        let compacted = compact(value)
        guard !compacted.isEmpty, compacted.count <= maximumLength else { return nil }
        return compacted
    }

    /// Kotlin `removeSurrounding`: only when `value` both starts and ends with `delimiter`.
    static func removingSurrounding(_ value: String, _ delimiter: Character) -> String {
        guard value.count >= 2, value.first == delimiter, value.last == delimiter else { return value }
        return String(value.dropFirst().dropLast())
    }

    /// Only the ASCII letters A–Z and digits.
    static func asciiAlphanumerics(_ value: String) -> String {
        String(String.UnicodeScalarView(value.unicodeScalars.filter {
            ("A"..."Z").contains($0) || ("0"..."9").contains($0)
        }))
    }

    /// Android's key of the hand-made RFI and Italo station maps: upper case, «S. » as «SAN »,
    /// letters and digits only (accented letters are dropped, as `[^A-Z0-9]` does).
    static func legacyStationKey(_ value: String) -> String {
        asciiAlphanumerics(value.uppercased().replacingOccurrences(of: "S. ", with: "SAN "))
    }

    /// Android's key of the published catalogues: accents removed (NFD without marks), upper
    /// case, letters and digits only; the RFI catalogue also reads «S. » as «SAN ».
    static func catalogueStationKey(_ value: String, sanPrefix: Bool) -> String {
        let folded = value.folding(options: [.diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX")).uppercased()
        return asciiAlphanumerics(sanPrefix ? folded.replacingOccurrences(of: "S. ", with: "SAN ") : folded)
    }

    /// `StationBoardAggregateService.normalize`: lower case, ö ä ü as oe ae ue, a–z and digits only.
    static func boardKey(_ value: String) -> String {
        let lower = value.lowercased()
            .replacingOccurrences(of: "ö", with: "oe")
            .replacingOccurrences(of: "ä", with: "ae")
            .replacingOccurrences(of: "ü", with: "ue")
        return String(String.UnicodeScalarView(lower.unicodeScalars.filter {
            ("a"..."z").contains($0) || ("0"..."9").contains($0)
        }))
    }

    /// Android's `hasExplicitCancellationStatus` (`OperationalStatusTerms.kt`): «soppress…» or
    /// «cancellat…» as a word; station names are not running states.
    static func hasExplicitCancellationStatus(_ value: String) -> Bool {
        LiveRegex.explicitCancellation.contains(value)
    }
}

/// A compiled pattern shared across tasks. `NSRegularExpression` is immutable and thread safe.
struct LiveRegex: @unchecked Sendable {
    private let expression: NSRegularExpression?

    init(_ pattern: String, caseInsensitive: Bool = false) {
        expression = try? NSRegularExpression(pattern: pattern, options: caseInsensitive ? [.caseInsensitive] : [])
    }

    /// The groups of the first match (index 0 is the whole match; nil for a group that did not take part).
    func firstMatch(in value: String) -> [String?]? {
        guard let expression else { return nil }
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        guard let match = expression.firstMatch(in: value, options: [], range: range) else { return nil }
        return Self.groups(of: match, in: value)
    }

    func allMatches(in value: String, limit: Int) -> [[String?]] {
        guard let expression, limit > 0 else { return [] }
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        var result: [[String?]] = []
        for match in expression.matches(in: value, options: [], range: range) {
            result.append(Self.groups(of: match, in: value))
            if result.count >= limit { break }
        }
        return result
    }

    /// The end of each match, as an offset in `value`'s UTF-16 view.
    func matchEnds(in value: String, limit: Int) -> [Int] {
        guard let expression, limit > 0 else { return [] }
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return expression.matches(in: value, options: [], range: range).prefix(limit).map { $0.range.location + $0.range.length }
    }

    func contains(_ value: String) -> Bool {
        guard let expression else { return false }
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return expression.firstMatch(in: value, options: [], range: range) != nil
    }

    private static func groups(of match: NSTextCheckingResult, in value: String) -> [String?] {
        (0..<match.numberOfRanges).map { index -> String? in
            let range = match.range(at: index)
            guard range.location != NSNotFound, let bounds = Range(range, in: value) else { return nil }
            return String(value[bounds])
        }
    }

    static let explicitCancellation = LiveRegex(#"\b(?:soppress\w*|cancellat\w*)\b"#, caseInsensitive: true)
}

/// A JSON object read with Android's tolerant accessors (`JSONObject.text`, `millis`, `intOrNull`).
struct LiveJSONObject {
    let values: [String: Any]

    init?(_ value: Any?) {
        guard let values = value as? [String: Any] else { return nil }
        self.values = values
    }

    /// The value as text, trimmed; nil when missing, JSON `null`, a collection or blank.
    func text(_ key: String) -> String? {
        LiveJSON.text(values[key])
    }

    /// A positive whole number (epoch milliseconds), nil otherwise.
    func millis(_ key: String) -> Int64? {
        guard let raw = text(key), let value = Int64(raw), value > 0 else { return nil }
        return value
    }

    func int(_ key: String) -> Int? {
        guard let raw = text(key) else { return nil }
        return Int(raw)
    }

    /// Android's `optBoolean(key, false)`: `true`, or the text «true» in any case.
    func bool(_ key: String) -> Bool {
        switch values[key] {
        case let flag as Bool: return flag
        case let text as String: return text.trimmingCharacters(in: .whitespaces).lowercased() == "true"
        default: return false
        }
    }

    func object(_ key: String) -> LiveJSONObject? {
        LiveJSONObject(values[key])
    }

    func array(_ key: String) -> [Any]? {
        values[key] as? [Any]
    }
}

enum LiveJSON {
    /// The JSON document in `text` (an object or an array), nil when malformed.
    static func parse(_ text: String) -> Any? {
        guard let data = text.data(using: .utf8), !data.isEmpty else { return nil }
        return try? JSONSerialization.jsonObject(with: data, options: [])
    }

    /// A scalar as text: strings trimmed, numbers in decimal; nil for null, collections and blanks.
    static func text(_ value: Any?) -> String? {
        guard let value else { return nil }
        let text: String
        switch value {
        case is NSNull: return nil
        case let string as String: text = string
        case let number as NSNumber: text = number.stringValue
        case let integer as Int: text = String(integer)
        case let double as Double: text = String(double)
        default: return nil
        }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// The JSON value (object or array) starting at `start` in `text`, read up to its matching
    /// bracket: what Android's `JSONTokener.nextValue()` reads after `stationList =`.
    static func value(in text: String, startingAtUTF16Offset start: Int, maximumLength: Int = 524_288) -> Any? {
        let utf16 = text.utf16
        guard start >= 0, start < utf16.count else { return nil }
        let startIndex = utf16.index(utf16.startIndex, offsetBy: start)
        var depth = 0
        var quote: UInt16?
        var escaped = false
        var length = 0
        var index = startIndex
        while index < utf16.endIndex, length < maximumLength {
            let unit = utf16[index]
            length += 1
            if let open = quote {
                if escaped { escaped = false } else if unit == 0x5C { escaped = true } else if unit == open { quote = nil }
            } else {
                switch unit {
                case 0x22, 0x27: quote = unit
                case 0x5B, 0x7B: depth += 1
                case 0x5D, 0x7D:
                    depth -= 1
                    if depth == 0 {
                        let end = utf16.index(after: index)
                        guard let slice = String(utf16[startIndex..<end]) else { return nil }
                        return parse(slice)
                    }
                    if depth < 0 { return nil }
                default: break
                }
            }
            index = utf16.index(after: index)
        }
        return nil
    }
}

/// A tolerant reader of the public HTML pages: tags, attributes and text; nothing is executed,
/// fetched or rendered. Malformed markup gives fewer elements, never a crash.
struct LiveHTML {
    struct Tag {
        /// Lower case.
        let name: String
        /// Lower-case names, values with entities decoded; the first of a repeated name wins.
        let attributes: [String: String]
        let isEnd: Bool
        let isSelfClosing: Bool
        /// Byte offsets of "<" and just after ">".
        let start: Int
        let end: Int
    }

    struct Element {
        let tagIndex: Int
        /// The tags of the content are `tagIndex + 1 ..< endTagIndex`.
        let endTagIndex: Int
        let contentStart: Int
        let contentEnd: Int
    }

    private let bytes: [UInt8]
    let tags: [Tag]

    init(_ html: String, maximumTags: Int = 250_000) {
        let bytes = Array(html.utf8)
        self.bytes = bytes
        tags = Self.tokenize(bytes, maximumTags: maximumTags)
    }

    func tag(_ element: Element) -> Tag { tags[element.tagIndex] }

    /// The start tags of `scope` (the whole page without one) for which `predicate` holds, as elements.
    func elements(in scope: Element? = nil, limit: Int = 10_000, where predicate: (Tag) -> Bool) -> [Element] {
        let lower = scope.map { $0.tagIndex + 1 } ?? 0
        let upper = scope.map(\.endTagIndex) ?? tags.count
        guard lower < upper else { return [] }
        var result: [Element] = []
        for index in lower..<upper where !tags[index].isEnd && predicate(tags[index]) {
            result.append(element(at: index))
            if result.count >= limit { break }
        }
        return result
    }

    func first(in scope: Element? = nil, where predicate: (Tag) -> Bool) -> Element? {
        elements(in: scope, limit: 1, where: predicate).first
    }

    /// The first element of `scope` whose `id` is `id` (Jsoup `#id`, case sensitive).
    func element(id: String, in scope: Element? = nil) -> Element? {
        first(in: scope) { $0.attributes["id"] == id }
    }

    /// The text of `element` as Jsoup's `text()` gives it: entities decoded, whitespace compacted,
    /// a space between blocks, scripts left out.
    func text(_ element: Element, maximumBytes: Int = 65_536) -> String {
        LiveText.compact(Self.decodeEntities(raw(element, blockSpaces: true, maximumBytes: maximumBytes)))
    }

    /// The text nodes of `element` as they are, entities decoded (Jsoup `wholeText()`).
    func wholeText(_ element: Element, maximumBytes: Int = 524_288) -> String {
        Self.decodeEntities(raw(element, blockSpaces: false, maximumBytes: maximumBytes))
    }

    private func raw(_ element: Element, blockSpaces: Bool, maximumBytes: Int) -> String {
        var collected: [UInt8] = []
        var cursor = element.contentStart
        var index = element.tagIndex + 1
        func take(_ end: Int) {
            guard end > cursor, collected.count < maximumBytes else { return }
            let upper = min(end, cursor + (maximumBytes - collected.count))
            collected.append(contentsOf: bytes[cursor..<upper])
        }
        while index < element.endTagIndex {
            let inner = tags[index]
            take(inner.start)
            if blockSpaces, Self.blockNames.contains(inner.name) { collected.append(0x20) }
            cursor = max(cursor, inner.end)
            if !inner.isEnd, Self.rawTextNames.contains(inner.name) {
                cursor = index + 1 < tags.count ? max(cursor, tags[index + 1].start) : element.contentEnd
            }
            index += 1
        }
        take(element.contentEnd)
        return String(decoding: collected, as: UTF8.self)
    }

    private func element(at index: Int) -> Element {
        let open = tags[index]
        if open.isSelfClosing || Self.voidNames.contains(open.name) {
            return Element(tagIndex: index, endTagIndex: index + 1, contentStart: open.end, contentEnd: open.end)
        }
        var depth = 0
        var cursor = index + 1
        while cursor < tags.count {
            let other = tags[cursor]
            if Self.implicitlyClosed.contains(open.name) {
                if Self.closes(open.name, other) {
                    return Element(tagIndex: index, endTagIndex: cursor, contentStart: open.end, contentEnd: other.start)
                }
            } else if other.name == open.name {
                if other.isEnd {
                    if depth == 0 {
                        return Element(tagIndex: index, endTagIndex: cursor, contentStart: open.end, contentEnd: other.start)
                    }
                    depth -= 1
                } else if !other.isSelfClosing {
                    depth += 1
                }
            }
            cursor += 1
        }
        return Element(tagIndex: index, endTagIndex: tags.count, contentStart: open.end, contentEnd: bytes.count)
    }

    private static let voidNames: Set<String> = [
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
    ]
    private static let rawTextNames: Set<String> = ["script", "style"]
    private static let implicitlyClosed: Set<String> = ["tr", "td", "th", "option", "li"]
    private static let tableEnds: Set<String> = ["table", "tbody", "thead", "tfoot"]
    private static let blockNames: Set<String> = [
        "br", "div", "p", "td", "th", "tr", "li", "ul", "ol", "table", "tbody", "thead", "h1", "h2", "h3", "h4", "h5", "h6",
        "section", "article", "header", "footer", "option", "select",
    ]

    /// Rows and cells end at the next row or cell, as browsers end them.
    private static func closes(_ name: String, _ tag: Tag) -> Bool {
        switch name {
        case "tr": return tag.name == "tr" || (tag.isEnd && tableEnds.contains(tag.name))
        case "td", "th":
            return tag.name == "td" || tag.name == "th" || tag.name == "tr" || (tag.isEnd && tableEnds.contains(tag.name))
        case "option": return tag.name == "option" || tag.name == "optgroup" || (tag.isEnd && tag.name == "select")
        case "li": return tag.name == "li" || (tag.isEnd && (tag.name == "ul" || tag.name == "ol"))
        default: return false
        }
    }

    private static func tokenize(_ bytes: [UInt8], maximumTags: Int) -> [Tag] {
        var tags: [Tag] = []
        let count = bytes.count
        var index = 0
        while index < count, tags.count < maximumTags {
            guard bytes[index] == 0x3C else {
                index = bytes[index...].firstIndex(of: 0x3C) ?? count
                continue
            }
            if hasPrefix(bytes, at: index, "<!--") {
                index = find(bytes, "-->", from: index + 4).map { $0 + 3 } ?? count
                continue
            }
            var cursor = index + 1
            guard cursor < count else { break }
            if bytes[cursor] == 0x21 || bytes[cursor] == 0x3F {
                index = find(bytes, ">", from: cursor).map { $0 + 1 } ?? count
                continue
            }
            let isEnd = bytes[cursor] == 0x2F
            if isEnd { cursor += 1 }
            let nameStart = cursor
            while cursor < count, isNameByte(bytes[cursor]) { cursor += 1 }
            guard cursor > nameStart, isLetter(bytes[nameStart]) else {
                index += 1
                continue
            }
            let name = String(decoding: bytes[nameStart..<cursor], as: UTF8.self).lowercased()
            var attributes: [String: String] = [:]
            var selfClosing = false
            var closed = false
            while cursor < count {
                let byte = bytes[cursor]
                if byte == 0x3E {
                    closed = true
                    cursor += 1
                    break
                }
                if isSpace(byte) || byte == 0x2F {
                    selfClosing = byte == 0x2F
                    cursor += 1
                    continue
                }
                selfClosing = false
                let attributeStart = cursor
                while cursor < count, !isSpace(bytes[cursor]), bytes[cursor] != 0x3D, bytes[cursor] != 0x3E, bytes[cursor] != 0x2F {
                    cursor += 1
                }
                if cursor == attributeStart {
                    cursor += 1
                    continue
                }
                let attributeName = String(decoding: bytes[attributeStart..<cursor], as: UTF8.self).lowercased()
                while cursor < count, isSpace(bytes[cursor]) { cursor += 1 }
                var value = ""
                if cursor < count, bytes[cursor] == 0x3D {
                    cursor += 1
                    while cursor < count, isSpace(bytes[cursor]) { cursor += 1 }
                    if cursor < count, bytes[cursor] == 0x22 || bytes[cursor] == 0x27 {
                        let quote = bytes[cursor]
                        let valueStart = cursor + 1
                        let valueEnd = bytes[valueStart...].firstIndex(of: quote) ?? count
                        value = String(decoding: bytes[valueStart..<valueEnd], as: UTF8.self)
                        cursor = min(count, valueEnd + 1)
                    } else {
                        let valueStart = cursor
                        while cursor < count, !isSpace(bytes[cursor]), bytes[cursor] != 0x3E { cursor += 1 }
                        value = String(decoding: bytes[valueStart..<cursor], as: UTF8.self)
                    }
                }
                if attributes[attributeName] == nil { attributes[attributeName] = decodeEntities(value) }
            }
            guard closed else { break }
            tags.append(Tag(name: name, attributes: attributes, isEnd: isEnd, isSelfClosing: selfClosing, start: index, end: cursor))
            index = cursor
            if !isEnd, rawTextNames.contains(name) {
                index = findCaseInsensitive(bytes, "</" + name, from: cursor) ?? count
            }
        }
        return tags
    }

    private static func isSpace(_ byte: UInt8) -> Bool { byte == 0x20 || byte == 0x09 || byte == 0x0A || byte == 0x0D || byte == 0x0C }
    private static func isLetter(_ byte: UInt8) -> Bool { (0x41...0x5A).contains(byte) || (0x61...0x7A).contains(byte) }
    private static func isNameByte(_ byte: UInt8) -> Bool { isLetter(byte) || (0x30...0x39).contains(byte) || byte == 0x2D || byte == 0x3A }

    private static func hasPrefix(_ bytes: [UInt8], at index: Int, _ prefix: String) -> Bool {
        let needle = Array(prefix.utf8)
        guard index + needle.count <= bytes.count else { return false }
        return Array(bytes[index..<(index + needle.count)]) == needle
    }

    private static func find(_ bytes: [UInt8], _ needle: String, from start: Int) -> Int? {
        let pattern = Array(needle.utf8)
        guard let first = pattern.first, start < bytes.count else { return nil }
        var index = start
        while index + pattern.count <= bytes.count {
            guard let candidate = bytes[index...].firstIndex(of: first) else { return nil }
            if candidate + pattern.count > bytes.count { return nil }
            if Array(bytes[candidate..<(candidate + pattern.count)]) == pattern { return candidate }
            index = candidate + 1
        }
        return nil
    }

    private static func findCaseInsensitive(_ bytes: [UInt8], _ needle: String, from start: Int) -> Int? {
        let pattern = Array(needle.lowercased().utf8)
        guard !pattern.isEmpty, start < bytes.count else { return nil }
        var index = start
        while index + pattern.count <= bytes.count {
            var matches = true
            for offset in 0..<pattern.count {
                let byte = bytes[index + offset]
                let lower = (0x41...0x5A).contains(byte) ? byte + 0x20 : byte
                if lower != pattern[offset] {
                    matches = false
                    break
                }
            }
            if matches { return index }
            index += 1
        }
        return nil
    }

    /// Named, decimal and hexadecimal character references; unknown ones are kept as written.
    static func decodeEntities(_ value: String) -> String {
        guard value.contains("&") else { return value }
        var result = ""
        result.reserveCapacity(value.count)
        var index = value.startIndex
        while index < value.endIndex {
            let character = value[index]
            if character == "&", let semicolon = value[index...].prefix(12).firstIndex(of: ";"),
               let decoded = entity(String(value[value.index(after: index)..<semicolon])) {
                result.append(decoded)
                index = value.index(after: semicolon)
                continue
            }
            result.append(character)
            index = value.index(after: index)
        }
        return result
    }

    private static func entity(_ name: String) -> Character? {
        if name.hasPrefix("#") {
            let digits = name.dropFirst()
            let code: UInt32?
            if digits.first == "x" || digits.first == "X" {
                code = UInt32(digits.dropFirst(), radix: 16)
            } else {
                code = UInt32(digits, radix: 10)
            }
            guard let code, code > 0, let scalar = Unicode.Scalar(code) else { return nil }
            return Character(scalar)
        }
        return namedEntities[name]
    }

    private static let namedEntities: [String: Character] = [
        "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": "\u{00A0}",
        "agrave": "à", "egrave": "è", "eacute": "é", "igrave": "ì", "ograve": "ò", "ugrave": "ù",
        "Agrave": "À", "Egrave": "È", "Eacute": "É", "Igrave": "Ì", "Ograve": "Ò", "Ugrave": "Ù",
        "rsquo": "’", "lsquo": "‘", "laquo": "«", "raquo": "»", "deg": "°", "middot": "·", "ndash": "–", "mdash": "—",
    ]
}
