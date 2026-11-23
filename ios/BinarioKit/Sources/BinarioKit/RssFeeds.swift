import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

/// One item of an RSS 2.0 channel, as published: text fields trimmed, markup untouched.
public struct RssItem: Equatable, Sendable {
    public let title: String
    public let link: String?
    public let description: String?
    public let pubDate: Date?
    public let guid: String?
}

/// A network notice as its feed published it, before `RailNoticeService` makes it a `RailNotice`
/// (Android's `ServiceAlert` of the notice adapters).
struct NoticeAlert: Equatable, Sendable {
    let id: String
    let title: String
    let description: String?
    let publishedAt: Date?
    let link: String?
    /// Trenitalia: the train numbers the news concerns.
    var trainNumbers: Set<String> = []
    /// Trenord: the lines it was published on, in the order first seen.
    var lines: [String] = []
}

enum RssFeedError: Error {
    case doctype
    case malformed
    case notRss
}

/// The XML documents of the feeds. DTDs are refused before parsing (Android's `disallow-doctype-decl`),
/// so no entity is declared and none is resolved; malformed XML is an error, never a partial list.
enum RssDocument {
    /// One element of an item: its name, its depth below the item (1 for a child) and its whole text.
    struct Element {
        let name: String
        let depth: Int
        var text: String
    }

    struct Parsed {
        let root: String
        let items: [[Element]]
    }

    static func parse(_ data: Data) throws -> Parsed {
        guard !declaresDoctype(data) else { throw RssFeedError.doctype }
        let reader = RssDocumentReader()
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = false
        parser.shouldReportNamespacePrefixes = false
        parser.shouldResolveExternalEntities = false
        parser.delegate = reader
        guard parser.parse(), !reader.failed, let root = reader.root else { throw RssFeedError.malformed }
        return Parsed(root: root, items: reader.items)
    }

    /// Whether the prolog (what comes before the root element) holds a `<!DOCTYPE`.
    static func declaresDoctype(_ data: Data) -> Bool {
        let bytes = [UInt8](data.prefix(65_536))
        var index = bytes.starts(with: [0xEF, 0xBB, 0xBF]) ? 3 : 0
        while index < bytes.count {
            let byte = bytes[index]
            if byte == 0x20 || byte == 0x09 || byte == 0x0A || byte == 0x0D {
                index += 1
                continue
            }
            guard byte == 0x3C, index + 1 < bytes.count else { return false }
            if bytes[index + 1] == 0x3F, let end = find(bytes, [0x3F, 0x3E], from: index + 2) {
                index = end + 2
            } else if bytes[index...].starts(with: Array("<!--".utf8)), let end = find(bytes, Array("-->".utf8), from: index + 4) {
                index = end + 3
            } else {
                return bytes[index + 1] == 0x21
            }
        }
        return false
    }

    private static func find(_ bytes: [UInt8], _ needle: [UInt8], from start: Int) -> Int? {
        var index = start
        while index + needle.count <= bytes.count {
            if Array(bytes[index..<(index + needle.count)]) == needle { return index }
            index += 1
        }
        return nil
    }

    /// The text of a document in the encoding its declaration names (UTF-8 without one), with the
    /// declaration removed so that it reads back as UTF-8; nil for an encoding not read here.
    static func utf8Text(_ data: Data) -> String? {
        var bytes = data
        if bytes.starts(with: [0xEF, 0xBB, 0xBF]) { bytes = bytes.dropFirst(3) }
        if bytes.starts(with: [0xFE, 0xFF]) || bytes.starts(with: [0xFF, 0xFE]) {
            return String(data: Data(bytes), encoding: .utf16).map(withoutDeclaration)
        }
        let head = String(decoding: bytes.prefix(256), as: UTF8.self).lowercased()
        var encoding: String.Encoding = .utf8
        if head.hasPrefix("<?xml"), let declaration = head.range(of: "?>").map({ head[..<$0.lowerBound] }),
           let attribute = declaration.range(of: "encoding") {
            let value = declaration[attribute.upperBound...].drop { $0 == " " || $0 == "=" || $0 == "\"" || $0 == "'" }
                .prefix { $0 != "\"" && $0 != "'" && $0 != " " }
            switch String(value) {
            case "utf-8", "utf8", "us-ascii", "ascii": encoding = .utf8
            case "iso-8859-1", "iso_8859-1", "latin1", "latin-1": encoding = .isoLatin1
            case "windows-1252", "cp1252": encoding = .windowsCP1252
            default: return nil
            }
        }
        return String(data: Data(bytes), encoding: encoding).map(withoutDeclaration)
    }

    /// Android's `startsWith("https://")` or its cleartext twin: a web address, secure or not. Only
    /// HTTPS ones are ever opened or asked.
    static func isWebAddress(_ value: String) -> Bool {
        guard let separator = value.range(of: "://") else { return false }
        let scheme = value[..<separator.lowerBound]
        return scheme == "https" || scheme == "http"
    }

    private static func withoutDeclaration(_ text: String) -> String {
        guard text.hasPrefix("<?xml"), let end = text.range(of: "?>") else { return text }
        return String(text[end.upperBound...])
    }
}

/// Collects the elements of every `item` (not nested in another), each with its whole text (DOM's
/// `textContent`, CDATA included). Used on one thread, by one parse.
private final class RssDocumentReader: NSObject, XMLParserDelegate {
    /// The longest text kept for one element: the feeds' bodies are bounded anyway.
    private static let maximumElementText = 262_144

    private(set) var root: String?
    private(set) var items: [[RssDocument.Element]] = []
    private(set) var failed = false
    private var depth = 0
    private var itemDepth: Int?
    private var current: [RssDocument.Element] = []
    /// Indices in `current` of the elements still open.
    private var open: [Int] = []

    func parser(
        _ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
        qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]
    ) {
        depth += 1
        let name = qName ?? elementName
        if root == nil { root = name }
        if let itemDepth {
            current.append(RssDocument.Element(name: name, depth: depth - itemDepth, text: ""))
            open.append(current.count - 1)
        } else if name == "item" {
            itemDepth = depth
            current = []
            open = []
        }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        if let itemDepth {
            if depth == itemDepth {
                items.append(current)
                self.itemDepth = nil
            } else if !open.isEmpty {
                open.removeLast()
            }
        }
        depth -= 1
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        append(string)
    }

    func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
        append(String(decoding: CDATABlock, as: UTF8.self))
    }

    func parser(_ parser: XMLParser, parseErrorOccurred parseError: any Error) {
        failed = true
    }

    private func append(_ text: String) {
        for index in open where current[index].text.utf16.count < Self.maximumElementText {
            current[index].text += text
        }
    }
}

/// Android's `Rss2Parser`: a standard RSS 2.0 reader (`channel/item` with title, link, description,
/// pubDate and guid), the root must be `rss` or `rdf:RDF`.
enum Rss2Parser {
    static func parse(_ data: Data, rules: NoticeRules) throws -> [RssItem] {
        let document = try RssDocument.parse(data)
        guard document.root.lowercased() == "rss" || document.root.lowercased() == "rdf:rdf" else { throw RssFeedError.notRss }
        let limit = NoticeTable.rss.maximumText
        return document.items.prefix(NoticeTable.rss.maximumItems).compactMap { elements -> RssItem? in
            /// The first child named so (any case), trimmed and bounded; nil when empty.
            func child(_ name: String) -> String? {
                guard let element = elements.first(where: { $0.depth == 1 && $0.name.lowercased() == name }) else { return nil }
                let text = String(element.text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(limit))
                return text.isEmpty ? nil : text
            }
            guard let title = child("title") else { return nil }
            return RssItem(
                title: title,
                link: child("link").flatMap { RssDocument.isWebAddress($0) ? $0 : nil },
                description: child("description"),
                pubDate: child("pubDate").flatMap(RssDate.parse),
                guid: child("guid")
            )
        }
    }
}

/// RFC 1123 dates of the feeds' `pubDate` («Mon, 02 Nov 2026 10:00:00 +0100», also «GMT»).
enum RssDate {
    static func parse(_ value: String) -> Date? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        for format in ["EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z", "d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm Z"] {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.timeZone = TimeZone(identifier: "GMT")
            formatter.dateFormat = format
            if let date = formatter.date(from: trimmed) { return date }
            if trimmed.hasSuffix(" GMT") {
                formatter.dateFormat = format.replacingOccurrences(of: " Z", with: " 'GMT'")
                if let date = formatter.date(from: trimmed) { return date }
            }
        }
        return nil
    }
}

/// Android's `RfiInfomobilityRssAdapter.parseUpdates`: every `item` with a title and a link; the
/// description keeps its markup (`RailNoticeService` reduces it to text).
enum RfiInfomobilityParser {
    static func alerts(_ data: Data) throws -> [NoticeAlert] {
        try RssDocument.parse(data).items.compactMap { elements -> NoticeAlert? in
            /// The first element named so below the item, trimmed (DOM's `getElementsByTagName`).
            func text(_ name: String) -> String {
                (elements.first { $0.name == name }?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            }
            let title = text("title")
            let link = text("link")
            guard !title.isEmpty, !link.isEmpty else { return nil }
            let description = text("description")
            let published = text("pubDate")
            return NoticeAlert(
                id: "rfi-rss:" + BundledGTFSScheduleInstaller.sha256Hex(Data(link.utf8)),
                title: title,
                description: description.isEmpty ? nil : description,
                publishedAt: published.isEmpty ? nil : RssDate.parse(published),
                link: RssDocument.isWebAddress(link) ? link : nil
            )
        }
    }
}
