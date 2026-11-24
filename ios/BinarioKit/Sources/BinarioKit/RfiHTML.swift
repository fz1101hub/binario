import Foundation

// A small HTML reader for RFI's «Quadro orario» pages, written here with no third-party code: enough of an HTML tree
// to answer the questions Android's parser asks Jsoup (`RfiProgrammedTimetableAdapter`): the tables and their rows,
// the header and data cells of a row, the first element whose id, class or headers attribute contains a word, an
// element's text with its images' alternative texts, the whole page's text. Comments, scripts and styles are skipped;
// table cells and rows close the ones still open, as browsers do; entities are decoded.

final class RfiHTMLNode {
    let name: String
    let attributes: [String: String]
    let text: String
    private(set) var children: [RfiHTMLNode] = []
    fileprivate(set) weak var parent: RfiHTMLNode?

    init(name: String, attributes: [String: String] = [:], text: String = "") {
        self.name = name
        self.attributes = attributes
        self.text = text
    }

    var isText: Bool { name == "#text" }

    fileprivate func append(_ child: RfiHTMLNode) {
        child.parent = self
        children.append(child)
    }

    /// Every element below this one, in document order (this one not included).
    func descendants(named names: Set<String>) -> [RfiHTMLNode] {
        var found: [RfiHTMLNode] = []
        func visit(_ node: RfiHTMLNode) {
            for child in node.children where !child.isText {
                if names.contains(child.name) { found.append(child) }
                visit(child)
            }
        }
        visit(self)
        return found
    }

    /// Jsoup's `selectFirst("[id*=a],[class*=b],[headers*=c]")`: this element or the first below it whose attribute
    /// contains the value, ignoring case.
    func firstElement(attributeContains selectors: [(attribute: String, value: String)]) -> RfiHTMLNode? {
        let wanted = selectors.map { ($0.attribute, $0.value.lowercased()) }
        func matches(_ node: RfiHTMLNode) -> Bool {
            wanted.contains { attribute, value in node.attributes[attribute]?.lowercased().contains(value) == true }
        }
        if matches(self) { return self }
        func visit(_ node: RfiHTMLNode) -> RfiHTMLNode? {
            for child in node.children where !child.isText {
                if matches(child) { return child }
                if let found = visit(child) { return found }
            }
            return nil
        }
        return visit(self)
    }

    /// Jsoup's `text()`: the text below, with a space where a block element or a line break begins or ends, the
    /// whitespace collapsed and trimmed.
    var textContent: String {
        var output = ""
        func visit(_ node: RfiHTMLNode) {
            if node.isText {
                output += node.text
                return
            }
            let separates = RfiHTML.blockElements.contains(node.name) || node.name == "br"
            if separates { output += " " }
            for child in node.children { visit(child) }
            if separates { output += " " }
        }
        for child in children { visit(child) }
        return RfiHTML.collapsingWhitespace(output)
    }

    /// The alternative texts of the images below.
    var imageAlternatives: [String] {
        descendants(named: ["img"]).compactMap { $0.attributes["alt"] }
    }
}

enum RfiHTML {
    static let voidElements: Set<String> = [
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
    ]
    static let skippedElements: Set<String> = ["script", "style", "noscript", "template"]
    static let rawTextElements: Set<String> = ["title", "textarea"]
    /// Jsoup's block tags: their text is set apart from the text around them.
    static let blockElements: Set<String> = [
        "html", "head", "body", "frameset", "script", "noscript", "style", "meta", "link", "title", "frame", "noframes",
        "section", "nav", "aside", "hgroup", "header", "footer", "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol",
        "pre", "div", "blockquote", "hr", "address", "figure", "figcaption", "form", "fieldset", "ins", "del", "dl", "dt",
        "dd", "li", "table", "caption", "thead", "tfoot", "tbody", "colgroup", "col", "tr", "th", "td", "video", "audio",
        "canvas", "details", "menu", "plaintext", "template", "article", "main", "center", "dir", "applet", "marquee",
        "listing",
    ]
    private static let entities: [String: String] = [
        "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": "\u{00A0}",
        "agrave": "à", "aacute": "á", "egrave": "è", "eacute": "é", "igrave": "ì", "iacute": "í", "ograve": "ò",
        "oacute": "ó", "ugrave": "ù", "uacute": "ú", "Agrave": "À", "Aacute": "Á", "Egrave": "È", "Eacute": "É",
        "Igrave": "Ì", "Iacute": "Í", "Ograve": "Ò", "Oacute": "Ó", "Ugrave": "Ù", "Uacute": "Ú", "ccedil": "ç",
        "rsquo": "’", "lsquo": "‘", "rdquo": "”", "ldquo": "“", "ndash": "–", "mdash": "—", "hellip": "…",
        "deg": "°", "middot": "·", "laquo": "«", "raquo": "»", "euro": "€", "copy": "©", "reg": "®", "bull": "•",
    ]

    /// The tree of `html`: a document node whose children are the page's top elements and text.
    static func parse(_ html: String) -> RfiHTMLNode {
        let document = RfiHTMLNode(name: "#document")
        var stack: [RfiHTMLNode] = [document]
        let scalars = Array(html.unicodeScalars)
        var index = 0
        var text = String.UnicodeScalarView()

        func flushText() {
            guard !text.isEmpty else { return }
            stack[stack.count - 1].append(RfiHTMLNode(name: "#text", text: decode(String(text))))
            text = String.UnicodeScalarView()
        }
        func startsWith(_ word: String, at position: Int) -> Bool {
            let wanted = Array(word.unicodeScalars)
            guard position + wanted.count <= scalars.count else { return false }
            for offset in 0..<wanted.count where lower(scalars[position + offset]) != wanted[offset] { return false }
            return true
        }
        func skip(past marker: String, from position: Int) -> Int {
            var cursor = position
            while cursor < scalars.count {
                if startsWith(marker, at: cursor) { return cursor + marker.unicodeScalars.count }
                cursor += 1
            }
            return scalars.count
        }
        /// Pops the open elements down to the nearest `name`, when it is open below the nearest `boundary`.
        func close(_ names: Set<String>, within boundary: Set<String>) {
            var position = stack.count - 1
            while position > 0 {
                let open = stack[position].name
                if names.contains(open) {
                    stack.removeSubrange(position...)
                    return
                }
                if boundary.contains(open) { return }
                position -= 1
            }
        }

        while index < scalars.count {
            let scalar = scalars[index]
            guard scalar == "<", index + 1 < scalars.count else {
                text.append(scalar)
                index += 1
                continue
            }
            let next = scalars[index + 1]
            if startsWith("<!--", at: index) {
                flushText()
                index = skip(past: "-->", from: index + 4)
            } else if next == "!" || next == "?" {
                flushText()
                index = skip(past: ">", from: index + 2)
            } else if next == "/" {
                flushText()
                var cursor = index + 2
                var name = String.UnicodeScalarView()
                while cursor < scalars.count, isNameCharacter(scalars[cursor]) {
                    name.append(lower(scalars[cursor]))
                    cursor += 1
                }
                index = skip(past: ">", from: cursor)
                let closing = String(name)
                if let position = stack.lastIndex(where: { $0.name == closing }), position > 0 {
                    stack.removeSubrange(position...)
                }
            } else if isLetter(next) {
                flushText()
                var cursor = index + 1
                var name = String.UnicodeScalarView()
                while cursor < scalars.count, isNameCharacter(scalars[cursor]) {
                    name.append(lower(scalars[cursor]))
                    cursor += 1
                }
                var attributes: [String: String] = [:]
                var selfClosing = false
                while cursor < scalars.count, scalars[cursor] != ">" {
                    let current = scalars[cursor]
                    if current == "/" {
                        selfClosing = true
                        cursor += 1
                        continue
                    }
                    if current.properties.isWhitespace {
                        cursor += 1
                        continue
                    }
                    selfClosing = false
                    var attribute = String.UnicodeScalarView()
                    while cursor < scalars.count, !scalars[cursor].properties.isWhitespace,
                          scalars[cursor] != "=", scalars[cursor] != ">", scalars[cursor] != "/" {
                        attribute.append(lower(scalars[cursor]))
                        cursor += 1
                    }
                    while cursor < scalars.count, scalars[cursor].properties.isWhitespace { cursor += 1 }
                    var value = String.UnicodeScalarView()
                    if cursor < scalars.count, scalars[cursor] == "=" {
                        cursor += 1
                        while cursor < scalars.count, scalars[cursor].properties.isWhitespace { cursor += 1 }
                        if cursor < scalars.count, scalars[cursor] == "\"" || scalars[cursor] == "'" {
                            let quote = scalars[cursor]
                            cursor += 1
                            while cursor < scalars.count, scalars[cursor] != quote {
                                value.append(scalars[cursor])
                                cursor += 1
                            }
                            cursor += 1
                        } else {
                            while cursor < scalars.count, !scalars[cursor].properties.isWhitespace, scalars[cursor] != ">" {
                                value.append(scalars[cursor])
                                cursor += 1
                            }
                        }
                    }
                    let key = String(attribute)
                    if !key.isEmpty, attributes[key] == nil { attributes[key] = decode(String(value)) }
                }
                index = min(cursor + 1, scalars.count)
                let tag = String(name)
                if skippedElements.contains(tag) || rawTextElements.contains(tag) {
                    // The element's raw text runs to its end tag.
                    var end = index
                    while end < scalars.count, !(scalars[end] == "<" && startsWith("</" + tag, at: end)) { end += 1 }
                    if rawTextElements.contains(tag) {
                        let element = RfiHTMLNode(name: tag, attributes: attributes)
                        let raw = String(String.UnicodeScalarView(scalars[index..<end]))
                        element.append(RfiHTMLNode(name: "#text", text: decode(raw)))
                        stack[stack.count - 1].append(element)
                    }
                    index = skip(past: ">", from: end)
                    continue
                }
                switch tag {
                case "td", "th":
                    close(["td", "th"], within: ["tr", "table"])
                case "tr":
                    close(["tr"], within: ["table"])
                case "p":
                    close(["p"], within: ["div", "td", "th", "table", "body"])
                default:
                    break
                }
                let element = RfiHTMLNode(name: tag, attributes: attributes)
                stack[stack.count - 1].append(element)
                if !selfClosing, !voidElements.contains(tag) { stack.append(element) }
            } else {
                text.append(scalar)
                index += 1
            }
        }
        flushText()
        return document
    }

    /// Jsoup's whitespace normalisation: runs of whitespace (no-break spaces included) become one space, trimmed.
    static func collapsingWhitespace(_ value: String) -> String {
        var output = String.UnicodeScalarView()
        var pendingSpace = false
        for scalar in value.unicodeScalars {
            if scalar == " " || scalar == "\t" || scalar == "\n" || scalar == "\r" || scalar == "\u{0C}" || scalar == "\u{A0}" {
                pendingSpace = !output.isEmpty
                continue
            }
            if pendingSpace { output.append(" ") }
            pendingSpace = false
            output.append(scalar)
        }
        return String(output)
    }

    /// Named and numeric character references.
    static func decode(_ value: String) -> String {
        guard value.contains("&") else { return value }
        var output = ""
        var rest = Substring(value)
        while let ampersand = rest.firstIndex(of: "&") {
            output += rest[rest.startIndex..<ampersand]
            let after = rest[rest.index(after: ampersand)...]
            guard let semicolon = after.prefix(12).firstIndex(of: ";") else {
                output += "&"
                rest = after
                continue
            }
            let name = String(after[after.startIndex..<semicolon])
            var replacement: String?
            if name.hasPrefix("#x") || name.hasPrefix("#X") {
                replacement = UInt32(name.dropFirst(2), radix: 16).flatMap { Unicode.Scalar($0) }.map { String($0) }
            } else if name.hasPrefix("#") {
                replacement = UInt32(name.dropFirst(), radix: 10).flatMap { Unicode.Scalar($0) }.map { String($0) }
            } else {
                replacement = entities[name]
            }
            if let replacement {
                output += replacement
                rest = after[after.index(after: semicolon)...]
            } else {
                output += "&"
                rest = after
            }
        }
        output += rest
        return output
    }

    private static func lower(_ scalar: Unicode.Scalar) -> Unicode.Scalar {
        ("A"..."Z").contains(scalar) ? Unicode.Scalar(scalar.value + 32) ?? scalar : scalar
    }

    private static func isLetter(_ scalar: Unicode.Scalar) -> Bool {
        ("A"..."Z").contains(scalar) || ("a"..."z").contains(scalar)
    }

    private static func isNameCharacter(_ scalar: Unicode.Scalar) -> Bool {
        isLetter(scalar) || ("0"..."9").contains(scalar) || scalar == "-" || scalar == ":"
    }
}
