import Foundation

/// Plain text of the publishers' HTML, Android's `NoticeText`. The markup is only read to reduce it
/// to text with paragraph breaks; it is never rendered, so scripts, styles and attributes cannot
/// reach the screen.
enum NoticeText {
    /// HTML whose markup is entity-escaped once more (`&lt;p&gt;Velocit&amp;agrave;…`), as Trenitalia publishes it.
    static func fromDoubleEscapedHtml(_ value: String?, maximumLength: Int, rules: NoticeRules) -> String? {
        guard let value else { return nil }
        let once = LiveHTML.decodeEntities(String(value.prefix(rules.table.text.maximumInput)))
        return fromHtml(once, maximumLength: maximumLength, rules: rules)
    }

    /// A plain-text field (a title) that may still carry HTML entities: entities decoded, nothing read as markup.
    static func decodeEntities(_ value: String?, maximumLength: Int, rules: NoticeRules) -> String? {
        guard let value else { return nil }
        return normalize(LiveHTML.decodeEntities(String(value.prefix(rules.table.text.maximumInput))), maximumLength: maximumLength)
    }

    static func fromHtml(_ value: String?, maximumLength: Int, rules: NoticeRules) -> String? {
        guard let value else { return nil }
        let html = String(value.prefix(rules.table.text.maximumInput))
        guard html.contains(where: { !$0.isWhitespace }) else { return nil }
        return normalize(NoticeMarkup.wholeText(html), maximumLength: maximumLength)
    }

    /// Collapses spaces inside lines, keeps at most one empty line between paragraphs and bounds the length.
    static func normalize(_ value: String, maximumLength: Int) -> String? {
        let lines = value.replacingOccurrences(of: "\u{00A0}", with: " ")
            .replacingOccurrences(of: "\r", with: "")
            .components(separatedBy: "\n")
            .map(collapsedLine)
        var out = ""
        var blank = 0
        for line in lines {
            if line.isEmpty {
                blank += 1
                continue
            }
            if !out.isEmpty { out += blank > 0 ? "\n\n" : "\n" }
            out += line
            blank = 0
        }
        let text = out.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        guard text.count > maximumLength else { return text }
        return trimmingTrailingWhitespace(String(text.prefix(max(0, maximumLength - 1)))) + "…"
    }

    /// Runs of spaces, tabs, vertical tabs and form feeds as one space, the line trimmed.
    private static func collapsedLine(_ line: String) -> String {
        var scalars = String.UnicodeScalarView()
        var inRun = false
        for scalar in line.unicodeScalars {
            if scalar == " " || scalar == "\t" || scalar == "\u{000B}" || scalar == "\u{000C}" {
                if !inRun { scalars.append(" ") }
                inRun = true
            } else {
                scalars.append(scalar)
                inRun = false
            }
        }
        return String(scalars).trimmingCharacters(in: .whitespaces)
    }

    static func trimmingTrailingWhitespace(_ value: String) -> String {
        var result = value
        while let last = result.last, last.isWhitespace { result.removeLast() }
        return result
    }

    /// True when a text mentions a strike («sciopero», «scioperi»): the trigger of the strike badge.
    static func mentionsStrike(_ texts: [String?], rules: NoticeRules) -> Bool {
        texts.contains { text in text.map { rules.strikeWord.contains($0) } ?? false }
    }

    /// Accents removed: the text decomposed (NFD) without its combining marks.
    static func withoutMarks(_ value: String) -> String {
        var scalars = String.UnicodeScalarView()
        for scalar in value.decomposedStringWithCanonicalMapping.unicodeScalars {
            switch scalar.properties.generalCategory {
            case .nonspacingMark, .spacingMark, .enclosingMark: continue
            default: scalars.append(scalar)
            }
        }
        return String(scalars)
    }

    /// The key that recognises the same notice published twice (case, spacing and punctuation ignored).
    static func dedupKey(_ value: String) -> String {
        var scalars = String.UnicodeScalarView()
        var pendingSpace = false
        for scalar in withoutMarks(value).lowercased().unicodeScalars {
            if ("a"..."z").contains(scalar) || ("0"..."9").contains(scalar) {
                if pendingSpace, !scalars.isEmpty { scalars.append(" ") }
                scalars.append(scalar)
                pendingSpace = false
            } else {
                pendingSpace = true
            }
        }
        return String(scalars)
    }
}

/// jsoup's `wholeText()` of a body fragment after Android's edits in `NoticeText.fromHtml`: scripts,
/// styles, `noscript` and `template` removed; a line break before and after each paragraph, `div`,
/// list item, heading and table row; «• » at the start of a list item; two line breaks for a `<br>`
/// (its own and the one Android adds). Entities are decoded in text only. Paragraphs, list items and
/// rows end where the HTML parser ends them (the next paragraph or block, the next item or row).
enum NoticeMarkup {
    private static let removed: Set<String> = ["script", "style", "noscript", "template"]
    /// Start tags, and end tags but `p`, that close an open paragraph (HTML's "close a p element").
    private static let closesParagraph: Set<String> = [
        "address", "article", "aside", "blockquote", "center", "details", "dir", "div", "dl", "fieldset",
        "figcaption", "figure", "footer", "header", "hgroup", "menu", "nav", "ol", "section", "summary", "ul",
        "h1", "h2", "h3", "h4", "h5", "h6", "pre", "listing", "form", "li", "dd", "dt", "table", "hr",
        "td", "th", "tr", "tbody", "thead", "tfoot", "caption",
    ]
    private static let blocks: Set<String> = ["div", "h1", "h2", "h3", "h4"]

    private enum Token {
        case skip(end: Int)
        case tag(name: String, isEnd: Bool, end: Int)
    }

    static func wholeText(_ html: String) -> String {
        // jsoup reads «\r\n» and a lone «\r» as «\n».
        let bytes = Array(html.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n").utf8)
        var out = ""
        var text: [UInt8] = []
        var paragraphOpen = false
        var rowOpen = false
        // Whether an item is open in each list, the outermost first; one level outside any list.
        var itemsOpen: [Bool] = [false]
        func flush() {
            guard !text.isEmpty else { return }
            out += LiveHTML.decodeEntities(String(decoding: text, as: UTF8.self))
            text.removeAll(keepingCapacity: true)
        }
        var index = 0
        while index < bytes.count {
            guard bytes[index] == 0x3C, let found = token(bytes, at: index) else {
                text.append(bytes[index])
                index += 1
                continue
            }
            flush()
            switch found {
            case let .skip(end):
                index = end
            case let .tag(name, isEnd, end):
                index = end
                if !isEnd, removed.contains(name) {
                    index = endOfRemoved(bytes, name, from: end)
                    continue
                }
                if paragraphOpen, name != "p", closesParagraph.contains(name) {
                    out += "\n"
                    paragraphOpen = false
                }
                switch name {
                case "br":
                    out += "\n\n"
                case "p":
                    if isEnd {
                        // A stray end tag is an empty paragraph.
                        out += paragraphOpen ? "\n" : "\n\n"
                        paragraphOpen = false
                    } else {
                        if paragraphOpen { out += "\n" }
                        out += "\n"
                        paragraphOpen = true
                    }
                case "ul", "ol":
                    if isEnd {
                        if itemsOpen.last == true { out += "\n" }
                        if itemsOpen.count > 1 { itemsOpen.removeLast() } else { itemsOpen[0] = false }
                    } else {
                        itemsOpen.append(false)
                    }
                case "li":
                    let last = itemsOpen.count - 1
                    if isEnd {
                        if itemsOpen[last] { out += "\n" }
                        itemsOpen[last] = false
                    } else {
                        if itemsOpen[last] { out += "\n" }
                        out += "\n• "
                        itemsOpen[last] = true
                    }
                case "tr":
                    if isEnd {
                        if rowOpen { out += "\n" }
                        rowOpen = false
                    } else {
                        if rowOpen { out += "\n" }
                        out += "\n"
                        rowOpen = true
                    }
                case "table", "tbody", "thead", "tfoot":
                    if isEnd, rowOpen {
                        out += "\n"
                        rowOpen = false
                    }
                default:
                    if blocks.contains(name) { out += "\n" }
                }
            }
        }
        flush()
        return out
    }

    /// A comment, a declaration or a tag starting at `start`; nil when "<" starts no tag (it is text).
    private static func token(_ bytes: [UInt8], at start: Int) -> Token? {
        let count = bytes.count
        var cursor = start + 1
        guard cursor < count else { return nil }
        if hasPrefix(bytes, at: start, "<!--") {
            return .skip(end: find(bytes, "-->", from: start + 4).map { $0 + 3 } ?? count)
        }
        if bytes[cursor] == 0x21 || bytes[cursor] == 0x3F {
            return .skip(end: find(bytes, ">", from: cursor).map { $0 + 1 } ?? count)
        }
        let isEnd = bytes[cursor] == 0x2F
        if isEnd { cursor += 1 }
        guard cursor < count, isLetter(bytes[cursor]) else { return nil }
        let nameStart = cursor
        while cursor < count, isNameByte(bytes[cursor]) { cursor += 1 }
        let name = String(decoding: bytes[nameStart..<cursor], as: UTF8.self).lowercased()
        // The attributes are skipped; a quote opens a value only right after «=».
        var quote: UInt8?
        var afterEquals = false
        while cursor < count {
            let byte = bytes[cursor]
            if let open = quote {
                if byte == open { quote = nil }
            } else if byte == 0x3E {
                return .tag(name: name, isEnd: isEnd, end: cursor + 1)
            } else if byte == 0x3D {
                afterEquals = true
            } else if (byte == 0x22 || byte == 0x27) && afterEquals {
                quote = byte
                afterEquals = false
            } else if !isSpace(byte) {
                afterEquals = false
            }
            cursor += 1
        }
        return nil
    }

    /// Just after the end tag of a removed element, or the end of the input without one.
    private static func endOfRemoved(_ bytes: [UInt8], _ name: String, from start: Int) -> Int {
        let needle = Array(("</" + name).utf8)
        var index = start
        while index + needle.count <= bytes.count {
            var matches = true
            for offset in 0..<needle.count {
                let byte = bytes[index + offset]
                let lower = (0x41...0x5A).contains(byte) ? byte + 0x20 : byte
                if lower != needle[offset] {
                    matches = false
                    break
                }
            }
            if matches { return find(bytes, ">", from: index).map { $0 + 1 } ?? bytes.count }
            index += 1
        }
        return bytes.count
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
        guard !pattern.isEmpty, start < bytes.count else { return nil }
        var index = start
        while index + pattern.count <= bytes.count {
            if Array(bytes[index..<(index + pattern.count)]) == pattern { return index }
            index += 1
        }
        return nil
    }
}

/// Notice text split into plain runs and http(s) URLs, so a screen makes only the URLs tappable
/// (Android's `NoticeLinks`).
public enum NoticeLinks {
    public enum Segment: Equatable, Sendable {
        case plain(String)
        case link(String)

        public var text: String {
            switch self {
            case let .plain(text), let .link(text): return text
            }
        }
    }

    /// A URL that `allow` refuses stays plain text: the rule is enforced here, where the link is made.
    public static func segments(_ text: String, rules: NoticeRules, allow: (String) -> Bool = { _ in true }) -> [Segment] {
        let value = text as NSString
        let trailing = Set(rules.table.text.urlTrailing.utf16)
        var out: [Segment] = []
        var cursor = 0
        for match in rules.url.matches(in: text) {
            var end = match.range.location + match.range.length
            while end > match.range.location, trailing.contains(value.character(at: end - 1)) { end -= 1 }
            let url = value.substring(with: NSRange(location: match.range.location, length: end - match.range.location))
            // Shorter than a scheme and one host character: not a usable link.
            if url.utf16.count < rules.table.text.minimumUrlLength || !allow(url) { continue }
            if match.range.location > cursor {
                out.append(.plain(value.substring(with: NSRange(location: cursor, length: match.range.location - cursor))))
            }
            out.append(.link(url))
            cursor = end
        }
        if cursor < value.length { out.append(.plain(value.substring(from: cursor))) }
        return out
    }
}

/// Links in notices that reach the app over the single cleartext hop (ViaggiaTreno redirects its
/// HTTPS JSON to its own domain over HTTP): anyone on the network path could rewrite them. Only HTTPS
/// links to the publisher's own domains stay tappable; any other URL in the text loses its scheme, so
/// the notice still shows it as plain text. The rule is applied again where the link is made
/// (`RailNotice.allowsLink`), so no later transformation of the text can bring a link back.
enum CleartextNoticeLinks {
    static func allowed(_ url: String, rules: NoticeRules) -> Bool {
        guard let components = URLComponents(string: url.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.scheme?.lowercased() == "https", components.user == nil, components.password == nil,
              let host = components.host?.lowercased(), !host.isEmpty,
              components.port == nil || components.port == 443 else { return false }
        return rules.table.text.cleartextOfficialDomains.contains { host == $0 || host.hasSuffix("." + $0) }
    }

    /// The notice's own link, kept only when `allowed`.
    static func link(_ url: String?, rules: NoticeRules) -> String? {
        guard let trimmed = url?.trimmingCharacters(in: .whitespacesAndNewlines), allowed(trimmed, rules: rules) else { return nil }
        return trimmed
    }

    /// `text` with every URL that is not `allowed` reduced to plain text (scheme removed), again until
    /// nothing changes: removing a scheme can uncover another one. Each changing pass shortens the text.
    static func neutralize(_ text: String?, rules: NoticeRules) -> String? {
        guard var current = text else { return nil }
        while true {
            let next = NoticeLinks.segments(current, rules: rules).map { segment -> String in
                switch segment {
                case let .plain(text): return text
                case let .link(url):
                    if allowed(url, rules: rules) { return url }
                    guard let scheme = url.range(of: "://") else { return url }
                    return String(url[scheme.upperBound...])
                }
            }.joined()
            if next == current { return current }
            current = next
        }
    }
}
