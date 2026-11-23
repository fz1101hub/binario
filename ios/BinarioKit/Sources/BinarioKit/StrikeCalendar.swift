import Foundation

/// A rail-sector strike of the MIT calendar (Android's `StrikeNotice`). `startDate` and `endDate`
/// are read from the item's own text (title, then description); nil when the text gives no date, and
/// then the strike is never «tomorrow».
public struct StrikeNotice: Equatable, Identifiable, Sendable {
    public let id: String
    public let title: String
    public let description: String?
    /// Only an HTTPS page of the announcement.
    public let link: String?
    public let publishedAt: Date?
    public let startDate: PlannedLocalDate?
    public let endDate: PlannedLocalDate?
}

/// The date patterns of the strike calendar, Android's `StrikeCalendar`, with the months of the table.
struct StrikeDatePatterns: Sendable {
    let months: [String]
    let numeric: NoticeRegex
    let iso: NoticeRegex
    let spanned: NoticeRegex
    let named: NoticeRegex

    init?(months: [String]) {
        let month = months.joined(separator: "|")
        guard let numeric = NoticeRegex(#"\b(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})\b"#),
              let iso = NoticeRegex(#"\b(\d{4})-(\d{2})-(\d{2})\b"#),
              let spanned = NoticeRegex(#"\b(\d{1,2})\s*(?:-|al|e)\s*(\d{1,2})\s+("# + month + #")(?:\s+(\d{4}))?\b"#),
              let named = NoticeRegex(#"\b(\d{1,2})(?:°|º)?\s+("# + month + #")(?:\s+(\d{4}))?\b"#)
        else { return nil }
        self.months = months
        self.numeric = numeric
        self.iso = iso
        self.spanned = spanned
        self.named = named
    }
}

extension PlannedLocalDate {
    /// Java's `plusMonths`: the same day, or the month's last day when it has fewer.
    func addingMonths(_ months: Int) -> PlannedLocalDate? {
        let total = year * 12 + (month - 1) + months
        let newYear = ItalianWallClock.floorDivision(total, 12)
        let newMonth = total - newYear * 12 + 1
        return PlannedLocalDate(year: newYear, month: newMonth, day: min(day, Self.daysInMonth(year: newYear, month: newMonth)))
    }
}

extension NoticeRules {
    /// Lower case without accents, as Android's `StrikeCalendar.normalize`.
    static func folded(_ value: String) -> String {
        NoticeText.withoutMarks(value).lowercased()
    }

    /// An item about rail: a sector word in its title or description, case and accents ignored.
    func isRail(_ item: RssItem) -> Bool {
        railWords.contains(Self.folded(item.title + " " + (item.description ?? "")))
    }

    /// The first start and end date written in `text`; a date without year takes the one of `reference`.
    func strikeDates(_ text: String, reference: PlannedLocalDate) -> (start: PlannedLocalDate?, end: PlannedLocalDate?) {
        let folded = Self.folded(text)
        let value = folded as NSString
        let patterns = strikeDatePatterns
        struct Found {
            let position: Int
            let start: PlannedLocalDate
            let end: PlannedLocalDate?
        }
        var found: [Found] = []
        func number(_ match: NoticeRegex.Match, _ group: Int) -> Int? { Int(match.group(group, in: value)) }
        for match in patterns.numeric.matches(in: folded) {
            guard let day = number(match, 1), let month = number(match, 2), let year = number(match, 3),
                  let date = PlannedLocalDate(year: year, month: month, day: day) else { continue }
            found.append(Found(position: match.start, start: date, end: nil))
        }
        for match in patterns.iso.matches(in: folded) {
            guard let year = number(match, 1), let month = number(match, 2), let day = number(match, 3),
                  let date = PlannedLocalDate(year: year, month: month, day: day) else { continue }
            found.append(Found(position: match.start, start: date, end: nil))
        }
        let spans = patterns.spanned.matches(in: folded)
        for match in spans {
            guard let index = patterns.months.firstIndex(of: match.group(3, in: value)) else { continue }
            let month = index + 1
            let year = number(match, 4) ?? inferredYear(month: month, reference: reference)
            guard let first = number(match, 1).flatMap({ PlannedLocalDate(year: year, month: month, day: $0) }) else { continue }
            let second = number(match, 2).flatMap { PlannedLocalDate(year: year, month: month, day: $0) }
            found.append(Found(position: match.start, start: first, end: second.flatMap { $0 < first ? nil : $0 }))
        }
        for match in patterns.named.matches(in: folded) {
            if spans.contains(where: { NSLocationInRange(match.start, $0.range) }) { continue }
            guard let index = patterns.months.firstIndex(of: match.group(2, in: value)) else { continue }
            let month = index + 1
            let year = number(match, 3) ?? inferredYear(month: month, reference: reference)
            guard let date = number(match, 1).flatMap({ PlannedLocalDate(year: year, month: month, day: $0) }) else { continue }
            found.append(Found(position: match.start, start: date, end: nil))
        }
        guard !found.isEmpty else { return (nil, nil) }
        let ordered = found.enumerated().sorted { ($0.element.position, $0.offset) < ($1.element.position, $1.offset) }.map(\.element)
        let window = NoticeTable.strikes.labelWindow
        func prefix(_ item: Found) -> String {
            let from = max(0, item.position - window)
            return value.substring(with: NSRange(location: from, length: item.position - from))
        }
        let startIndex = ordered.firstIndex { startLabel.contains(prefix($0)) } ?? 0
        let start = ordered[startIndex]
        if let end = start.end { return (start.start, end) }
        let labelled = ordered.indices.first { index in
            index != startIndex && endLabel.contains(prefix(ordered[index])) && !(ordered[index].start < start.start)
        }
        if let labelled { return (start.start, ordered[labelled].start) }
        let later = ordered.first { $0.position > start.position && !($0.start < start.start) }
        return (start.start, later?.start ?? start.start)
    }

    private func inferredYear(month: Int, reference: PlannedLocalDate) -> Int {
        month < reference.month - NoticeTable.strikes.inferNextYearMonthsBack ? reference.year + 1 : reference.year
    }

    /// The rail items with their dates; markup in titles and descriptions is reduced to plain text.
    func strikes(_ items: [RssItem], fetchedOn: PlannedLocalDate) -> [StrikeNotice] {
        var seen: Set<String> = []
        return items.filter { isRail($0) }.compactMap { item -> StrikeNotice? in
            let title = NoticeText.fromHtml(item.title, maximumLength: NoticeTable.strikes.titleLength, rules: self) ?? item.title
            let description = NoticeText.fromHtml(item.description, maximumLength: NoticeTable.strikes.descriptionLength, rules: self)
            let reference = item.pubDate.flatMap { ItalianWallClock.date(of: $0) } ?? fetchedOn
            var dates = strikeDates(title, reference: reference)
            if dates.start == nil { dates = strikeDates(description ?? "", reference: reference) }
            let published = item.pubDate.map { String(Int64(($0.timeIntervalSince1970 * 1_000).rounded())) } ?? "null"
            let id = item.guid ?? item.link
                ?? String(BundledGTFSScheduleInstaller.sha256Hex(Data("\(title)|\(published)".utf8)).prefix(32))
            guard seen.insert(id).inserted else { return nil }
            return StrikeNotice(
                id: id, title: title, description: description,
                link: item.link.flatMap { $0.hasPrefix("https://") ? $0 : nil },
                publishedAt: item.pubDate, startDate: dates.start, endDate: dates.end
            )
        }
    }

    /// «Scioperi in programma»: strikes not over yet, by start date, then recent items without a
    /// readable date (shown rather than lost), at most the table's number.
    public func upcoming(_ strikes: [StrikeNotice], today: PlannedLocalDate, now: Date) -> [StrikeNotice] {
        let dated = strikes.enumerated().filter { _, strike in
            guard let last = strike.endDate ?? strike.startDate else { return false }
            return !(last < today)
        }.sorted { first, second in
            let a = first.element, b = second.element
            if a.startDate != b.startDate { return (a.startDate?.daysSinceEpoch ?? 0) < (b.startDate?.daysSinceEpoch ?? 0) }
            if a.title != b.title { return a.title < b.title }
            return first.offset < second.offset
        }.map(\.element)
        let recent = now.addingTimeInterval(-TimeInterval(NoticeTable.strikes.undatedRecentDays) * 86_400)
        let undated = strikes.enumerated().filter { _, strike in
            strike.startDate == nil && (strike.publishedAt.map { $0 > recent } ?? false)
        }.sorted { first, second in
            let a = first.element.publishedAt ?? .distantPast, b = second.element.publishedAt ?? .distantPast
            return a == b ? first.offset < second.offset : a > b
        }.map(\.element)
        return Array((dated + undated).prefix(NoticeTable.strikes.upcomingShown))
    }

    /// The strikes that start on the day after `today` (the subject of Android's 18:00 reminder).
    func startingTomorrow(_ strikes: [StrikeNotice], today: PlannedLocalDate) -> [StrikeNotice] {
        let tomorrow = today.adding(days: 1)
        return strikes.filter { $0.startDate != nil && $0.startDate == tomorrow }
    }
}
