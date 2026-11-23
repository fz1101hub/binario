import Foundation

enum NoticeFeedError: Error {
    case format
}

/// Android's tolerant `JSONObject.text`: trimmed, nil when missing, JSON null, blank or «null».
private func noticeText(_ object: LiveJSONObject, _ key: String) -> String? {
    guard let text = object.text(key), text != "null" else { return nil }
    return text
}

/// Android's `TrenitaliaInfomobilityNewsAdapter.parse`: the Trenitalia infomobility news of
/// ViaggiaTreno (`news/infomobility`), national and regional, with the train numbers they concern.
/// The descriptions arrive entity-escaped twice; the news reaches the app over the cleartext hop,
/// so only links to the publisher's own HTTPS domains stay tappable.
enum TrenitaliaNewsParser {
    static func alerts(_ body: String, rules: NoticeRules) throws -> [NoticeAlert] {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == "null" { return [] }
        guard trimmed.hasPrefix("["), let array = LiveJSON.parse(trimmed) as? [Any] else { throw NoticeFeedError.format }
        let limits = rules.table.trenitalia
        return array.prefix(limits.maximumItems).compactMap { value -> NoticeAlert? in
            guard let item = LiveJSONObject(value) else { return nil }
            return alert(item, rules: rules)
        }
    }

    private static func alert(_ item: LiveJSONObject, rules: NoticeRules) -> NoticeAlert? {
        let limits = rules.table.trenitalia
        guard let title = CleartextNoticeLinks.neutralize(
            NoticeText.decodeEntities(noticeText(item, "title"), maximumLength: limits.titleLength, rules: rules), rules: rules
        ) else { return nil }
        let description = CleartextNoticeLinks.neutralize(
            NoticeText.fromDoubleEscapedHtml(noticeText(item, "description"), maximumLength: limits.descriptionLength, rules: rules),
            rules: rules
        )
        let published = item.millis("pubDate").map(LiveRailTime.date(millis:))
        var tags: Set<String> = []
        for tag in (item.array("trainTags") ?? []).prefix(limits.maximumTrainTags) {
            guard let text = LiveJSON.text(tag) else { continue }
            let number = String(text.drop { $0 == "0" })
            if rules.trainTag.matchesWhole(number) { tags.insert(number) }
        }
        let millis = published.map { String(LiveRailTime.millis($0)) } ?? "0"
        let hash = String(BundledGTFSScheduleInstaller.sha256Hex(Data("\(title)|\(millis)".utf8)).prefix(24))
        return NoticeAlert(
            id: "\(NoticeSourceID.trenitaliaNews):\(hash)", title: title, description: description, publishedAt: published,
            link: CleartextNoticeLinks.link(noticeText(item, "link").map { String($0.prefix(limits.linkLength)) }, rules: rules),
            trainNumbers: tags
        )
    }
}

/// A Trenord line («direttrice») as published: its code (`D034`) and the stations of its name.
public struct TrenordLine: Equatable, Sendable {
    public let code: String
    public let description: String
    /// The normalised station names of the description, in order (`LAVENO-VARESE-SARONNO-MILANO`).
    let stationTokens: [String]

    init(code: String, description: String, rules: NoticeRules) {
        self.code = code
        self.description = description
        let limits = rules.table.trenord
        var tokens: [String] = []
        var pieces = [description]
        for separator in limits.stationTokenSeparators {
            pieces = pieces.flatMap { $0.components(separatedBy: separator) }
        }
        for piece in pieces {
            let token = TrenordLineMatcher.normalizeStationName(piece)
            if token.count >= limits.minimumTokenLength, !tokens.contains(token) { tokens.append(token) }
        }
        stationTokens = tokens
    }
}

/// Every Trenord line with the notices published on it, each notice once with all its lines.
struct TrenordLineNotices: Equatable, Sendable {
    let lines: [TrenordLine]
    let alerts: [NoticeAlert]
}

/// Android's `TrenordLineNoticesAdapter.parse`: the line list (`mia/direttrici`) with its news. The
/// same news repeated under many lines (a strike notice is on all of them) becomes one notice that
/// lists every line; promotional items are dropped.
enum TrenordLinesParser {
    static func notices(_ body: String, rules: NoticeRules) throws -> TrenordLineNotices {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("["), let array = LiveJSON.parse(trimmed) as? [Any] else { throw NoticeFeedError.format }
        let limits = rules.table.trenord
        var lines: [TrenordLine] = []
        var news: [String: (text: String, date: Date?, lines: [String])] = [:]
        var order: [String] = []
        let dates = ISODates()
        for value in array.prefix(limits.maximumLines) {
            guard let item = LiveJSONObject(value),
                  let code = noticeText(item, "nome").map({ String($0.prefix(limits.lineCodeLength)) }),
                  rules.lineCode.matchesWhole(code) else { continue }
            let description = noticeText(item, "descrizione").map { String($0.prefix(limits.lineDescriptionLength)) } ?? ""
            lines.append(TrenordLine(code: code, description: description, rules: rules))
            guard let entries = item.array("news") else { continue }
            for newsItem in entries.prefix(limits.maximumNewsPerLine) {
                guard let entry = LiveJSONObject(newsItem),
                      let text = NoticeText.fromHtml(noticeText(entry, "description"), maximumLength: limits.textLength, rules: rules),
                      isOperational(text, rules: rules) else { continue }
                let date = noticeText(entry, "date").flatMap(dates.parse)
                let key = "\(NoticeText.dedupKey(text))|\(date.map { Int64($0.timeIntervalSince1970.rounded(.down)) } ?? 0)"
                if var existing = news[key] {
                    if !existing.lines.contains(code) { existing.lines.append(code) }
                    news[key] = existing
                } else {
                    news[key] = (text, date, [code])
                    order.append(key)
                }
            }
        }
        var seenCodes: Set<String> = []
        let distinctLines = lines.filter { seenCodes.insert($0.code).inserted }
        let alerts: [NoticeAlert] = order.compactMap { key -> NoticeAlert? in
            guard let item = news[key] else { return nil }
            let seconds = item.date.map { String(Int64($0.timeIntervalSince1970.rounded(.down))) } ?? "0"
            let hash = String(BundledGTFSScheduleInstaller.sha256Hex(Data("\(item.text)|\(seconds)".utf8)).prefix(24))
            return NoticeAlert(
                id: "\(NoticeSourceID.trenordLines):\(hash)", title: summary(item.text, rules: rules), description: item.text,
                publishedAt: item.date, link: nil, lines: item.lines
            )
        }
        // Newest first; an undated notice last (Kotlin's stable `sortedByDescending`).
        let sorted = alerts.enumerated().sorted { first, second in
            let a = first.element.publishedAt ?? Date(timeIntervalSince1970: 0)
            let b = second.element.publishedAt ?? Date(timeIntervalSince1970: 0)
            return a == b ? first.offset < second.offset : a > b
        }.map(\.element)
        return TrenordLineNotices(lines: distinctLines, alerts: sorted)
    }

    /// The promotional filter: a notice is kept only when its text, ignoring case and accents, has an
    /// operational stem of the table (a bare «bus» is not one: the «Ricaricami» promotion says
    /// «treni, metro, tram e bus»).
    static func isOperational(_ text: String, rules: NoticeRules) -> Bool {
        rules.operational.contains(NoticeText.withoutMarks(text))
    }

    /// The first sentence (or line) of the notice, bounded: its title.
    static func summary(_ text: String, rules: NoticeRules) -> String {
        let firstLine = (text.components(separatedBy: "\n").first ?? text).trimmingCharacters(in: .whitespaces)
        let characters = Array(firstLine)
        var sentence = firstLine
        for index in characters.indices where ".:!?".contains(characters[index]) {
            if index + 1 == characters.count || characters[index + 1].isWhitespace {
                sentence = String(characters[...index])
                break
            }
        }
        let limit = rules.table.trenord.titleLength
        guard sentence.count > limit else { return sentence }
        return NoticeText.trimmingTrailingWhitespace(String(sentence.prefix(limit - 1))) + "…"
    }
}

/// ISO 8601 instants with or without fractional seconds (Java's `Instant.parse`).
private struct ISODates {
    private let fractional: ISO8601DateFormatter
    private let whole: ISO8601DateFormatter

    init() {
        fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        whole = ISO8601DateFormatter()
        whole.formatOptions = [.withInternetDateTime]
    }

    func parse(_ value: String) -> Date? {
        fractional.date(from: value) ?? whole.date(from: value)
    }
}

/// Android's `TrenordLineMatcher`: Trenord line notices related to stations by name. A station
/// matches a station of a line's description when the normalised names are equal or one is the other
/// followed by more words («VARESE NORD» matches «VARESE», «MILANO CENTRALE» matches «MILANO»).
enum TrenordLineMatcher {
    private static let sanPrefix = NoticeRegex(#"\bS\.\s*"#)

    /// Accents removed, upper case, «S.» as «SAN», letters and digits only (Android's `normalizeStationName`).
    static func normalizeStationName(_ value: String) -> String {
        let upper = NoticeText.withoutMarks(value).uppercased()
        let named = sanPrefix?.replacing(in: upper, with: "SAN ") ?? upper
        var scalars = String.UnicodeScalarView()
        var pendingSpace = false
        for scalar in named.unicodeScalars {
            if ("A"..."Z").contains(scalar) || ("0"..."9").contains(scalar) {
                if pendingSpace, !scalars.isEmpty { scalars.append(" ") }
                scalars.append(scalar)
                pendingSpace = false
            } else {
                pendingSpace = true
            }
        }
        return String(scalars)
    }

    static func matchedTokens(_ line: TrenordLine, stationNames: [String]) -> [String] {
        let stations = stationNames.map(normalizeStationName).filter { $0.count >= 2 }
        return line.stationTokens.filter { token in stations.contains { matches($0, token) } }
    }

    private static func matches(_ station: String, _ token: String) -> Bool {
        station == token || station.hasPrefix(token + " ") || token.hasPrefix(station + " ")
    }

    /// The lines whose description names at least `minimum` distinct stations among `stationNames`.
    static func linesNaming(_ lines: [TrenordLine], stationNames: [String], minimum: Int) -> [TrenordLine] {
        lines.filter { matchedTokens($0, stationNames: stationNames).count >= minimum }
    }

    /// The notices of a station board: its lines' notices, or only the network-wide ones at a hub
    /// such as Milano.
    static func alertsForStation(_ notices: TrenordLineNotices, stationName: String, rules: NoticeRules) -> [NoticeAlert] {
        let matched = Set(linesNaming(notices.lines, stationNames: [stationName], minimum: 1).map(\.code))
        if matched.isEmpty { return [] }
        if matched.count > rules.table.trenord.hubLineCount {
            return notices.alerts.filter { isNetworkWide($0, lines: notices.lines, rules: rules) }
        }
        return notices.alerts.filter { alert in alert.lines.contains(where: matched.contains) }
    }

    /// A notice published on (almost) every line.
    static func isNetworkWide(_ alert: NoticeAlert, lines: [TrenordLine], rules: NoticeRules) -> Bool {
        lines.count > 1 && alert.lines.count >= max(rules.table.trenord.networkWideMinimumLines, (lines.count + 2) / 3)
    }
}
