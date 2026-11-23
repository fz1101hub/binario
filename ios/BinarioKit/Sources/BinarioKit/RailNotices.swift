import Foundation

/// The publisher of a network notice, in Android's order (RFI first).
public enum NoticePublisher: Int, CaseIterable, Comparable, Hashable, Sendable {
    case rfi
    case trenitalia
    case trenord

    /// The brand, a proper name: never translated.
    public var brand: String {
        switch self {
        case .rfi: return "RFI"
        case .trenitalia: return "Trenitalia"
        case .trenord: return "Trenord"
        }
    }

    /// The name of the publisher in the shared table.
    var tableName: String {
        switch self {
        case .rfi: return "RFI"
        case .trenitalia: return "TRENITALIA"
        case .trenord: return "TRENORD"
        }
    }

    public static func < (lhs: NoticePublisher, rhs: NoticePublisher) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// A publisher's line: code and published name (Trenord `D034`, `LAVENO-VARESE-SARONNO-MILANO`).
public struct NoticeLine: Equatable, Hashable, Sendable {
    public let code: String
    public let name: String
}

/// A network notice ready for display: plain text only, with the publisher's own link when it has one.
public struct RailNotice: Equatable, Identifiable, Sendable {
    public let id: String
    public let publisher: NoticePublisher
    public let title: String
    public let body: String?
    public let publishedAt: Date?
    public let link: String?
    public let lines: [NoticeLine]
    /// Whether the notice reached the app over the cleartext ViaggiaTreno hop.
    public let cleartextHop: Bool
    /// The strike badge: the text mentions «sciopero» or «scioperi».
    public let mentionsStrike: Bool
    /// Trenord titles are the first sentence of the text: when the body repeats it, the body alone is shown.
    public var titleInBody: Bool {
        let opening = NoticeText.trimmingTrailingWhitespace(title.hasSuffix("…") ? String(title.dropLast()) : title)
        return body?.hasPrefix(opening) == true
    }
}

extension RailNotice {
    /// Whether `url`, found in the text or as the link of this notice, may be tappable. A notice that
    /// travelled over the cleartext hop could have been rewritten on the way: only HTTPS links to the
    /// publisher's own domains are.
    public func allowsLink(_ url: String, rules: NoticeRules) -> Bool {
        !cleartextHop || CleartextNoticeLinks.allowed(url, rules: rules)
    }
}

/// Trenord notices of one set of lines; `allLines` marks notices published on (almost) every line.
public struct NoticeGroup: Equatable, Sendable {
    public let lines: [NoticeLine]
    public let allLines: Bool
    public let notices: [RailNotice]
}

/// The notices area: RFI and Trenitalia notices without duplicates, Trenord notices by line, the
/// publishers that did not answer and whether a list is an earlier copy.
public struct GeneralNotices: Equatable, Sendable {
    public let network: [RailNotice]
    public let trenordGroups: [NoticeGroup]
    public let unavailable: Set<NoticePublisher>
    public let stale: Bool
}

/// The Trenord notices of one station board.
public struct StationNotices: Equatable, Sendable {
    public let notices: [RailNotice]
    public let unavailable: Bool
    public let stale: Bool
}

/// What one read of a feed gave: a list within its time to live, an earlier list kept after a
/// failed refresh, or the reason there is none (Android's `SourceRead`).
enum NoticeFeedRead<Value: Sendable>: Sendable {
    case fresh(Value)
    case stale(Value)
    case failed(LiveUnavailableReason)

    var value: Value? {
        switch self {
        case let .fresh(value), let .stale(value): return value
        case .failed: return nil
        }
    }

    var isFailed: Bool {
        if case .failed = self { return true }
        return false
    }

    var isStale: Bool {
        if case .stale = self { return true }
        return false
    }
}

enum NoticeFeedLoad<Value: Sendable>: Sendable {
    case loaded(Value, fetchedAt: Date)
    case failed(LiveUnavailableReason)
}

/// The last list of one feed, Android's `ResilientSourceCache`: kept for its time to live, then
/// served as an earlier copy for at most `maximumStale` while a refresh fails. In memory only.
actor NoticeFeedCache<Value: Sendable> {
    private let timeToLive: TimeInterval
    private let maximumStale: TimeInterval
    private var kept: (value: Value, fetchedAt: Date)?

    init(timeToLive: TimeInterval, maximumStale: TimeInterval) {
        self.timeToLive = timeToLive
        self.maximumStale = maximumStale
    }

    func read(at now: Date, load: @escaping @Sendable () async -> NoticeFeedLoad<Value>) async -> NoticeFeedRead<Value> {
        if let kept, now < kept.fetchedAt.addingTimeInterval(timeToLive) { return .fresh(kept.value) }
        switch await load() {
        case let .loaded(value, fetchedAt):
            if kept.map({ fetchedAt >= $0.fetchedAt }) ?? true { kept = (value, fetchedAt) }
            return .fresh(value)
        case let .failed(reason):
            if let kept, now <= kept.fetchedAt.addingTimeInterval(timeToLive + maximumStale) { return .stale(kept.value) }
            return .failed(reason)
        }
    }
}

/// One notice feed: its address, limits and rate from the shared table, through `LiveSourceGate`
/// (one request per interval, a pause at the first refusal, the breaker of every source:
/// `SharedData.SourceManners`) and `LiveSourceClient` (HTTPS, the honest User-Agent, bounded body,
/// Android's redirect rule), with the list kept by `NoticeFeedCache`.
struct NoticeFeed<Value: Sendable>: Sendable {
    let source: NoticeTable.Source
    let gate: LiveSourceGate
    let cache: NoticeFeedCache<Value>
    let fetch: @Sendable () async -> LiveFetchResult
    let parse: @Sendable (LiveBody) -> Value?
    let now: @Sendable () -> Date

    init(
        _ source: NoticeTable.Source, client: LiveSourceClient,
        pauses: LiveSourcePauses, now: @escaping @Sendable () -> Date, bytes: Bool = false,
        parse: @escaping @Sendable (LiveBody) -> Value?
    ) {
        self.source = source
        // No status line names these pauses: Android's breakers of the notice feeds carry no label.
        gate = LiveSourceGate(
            source: nil,
            configuration: .init(minimumInterval: source.minimumIntervalSeconds, maximumEntries: 1),
            pauses: pauses, now: now
        )
        cache = NoticeFeedCache(timeToLive: source.timeToLiveSeconds, maximumStale: source.maximumStaleSeconds)
        let policy = LiveRequestPolicy(
            accept: source.accept, requestTimeout: source.readTimeoutSeconds, totalTimeout: source.totalTimeoutSeconds,
            maximumBytes: source.maximumBytes, maximumRequests: source.maximumRequests,
            allowsViaggiaTrenoCleartextHop: source.viaggiaTrenoCleartextHop,
            allowsSameHostRedirects: source.redirects == "same-https-host"
        )
        let url = URL(string: source.url)
        if bytes {
            // The XML names its own encoding: the bytes are read in it, not as UTF-8.
            fetch = {
                guard let url else { return .failure(.invalidRequest) }
                switch await client.getData(url, policy: policy) {
                case let .failure(failure): return .failure(failure)
                case let .body(data): return .body(LiveBody(text: RssDocument.utf8Text(data) ?? "", fetchedAt: now()))
                }
            }
        } else {
            fetch = {
                guard let url else { return .failure(.invalidRequest) }
                return await client.get(url, policy: policy)
            }
        }
        self.parse = parse
        self.now = now
    }

    func read() async -> NoticeFeedRead<Value> {
        let gate = self.gate
        let key = source.url
        let fetch = self.fetch
        let parse = self.parse
        return await cache.read(at: now()) {
            switch await gate.fetch(key, load: fetch) {
            case let .failure(failure):
                return .failed(failure.unavailableReason)
            case let .body(body):
                guard let value = parse(body) else { return .failed(.unreadable) }
                return .loaded(value, fetchedAt: body.fetchedAt)
            }
        }
    }
}

/// «Lavori e interruzioni sulle tue tratte»: the notices that name the routes, and whether a source
/// failed or answered from an earlier copy.
public struct RouteWorks: Equatable, Sendable {
    public let notices: [RouteWorksNotice]
    public let unavailable: Bool
    public let stale: Bool
}

/// Android's `RailNoticesService` for the iPhone: the notices of the enabled public sources for the
/// screens. It never throws: a failed source is reported as unavailable and its notices are absent;
/// an earlier copy is flagged. `personal` is «Dati in tempo reale»: without it the Trenitalia and
/// Trenord sources (personal sources on Android) are never asked; MIT and RFI are always asked.
public struct RailNoticeService: Sendable {
    public let rules: NoticeRules
    let strikeFeed: NoticeFeed<[StrikeNotice]>
    let rfi: NoticeFeed<[NoticeAlert]>
    let trenitalia: NoticeFeed<[NoticeAlert]>
    let trenord: NoticeFeed<TrenordLineNotices>

    init(rules: NoticeRules, transport: any LiveHTTPTransport, pauses: LiveSourcePauses, now: @escaping @Sendable () -> Date) {
        let client = LiveSourceClient(transport: transport, now: now)
        func source(_ id: String) -> NoticeTable.Source {
            // The generated table has every source of `NoticeSourceID` (`SharedDataTests`).
            NoticeTable.sources.first { $0.id == id } ?? NoticeTable.sources[0]
        }
        let mit = source(NoticeSourceID.mitStrikes)
        strikeFeed = NoticeFeed(mit, client: client, pauses: pauses, now: now, bytes: true) { body in
            guard let items = try? Rss2Parser.parse(Data(body.text.utf8), rules: rules),
                  let day = ItalianWallClock.date(of: body.fetchedAt) else { return nil }
            return rules.strikes(items, fetchedOn: day)
        }
        rfi = NoticeFeed(source(NoticeSourceID.rfiInfomobility), client: client, pauses: pauses, now: now) { body in
            try? RfiInfomobilityParser.alerts(Data(body.text.utf8))
        }
        trenitalia = NoticeFeed(source(NoticeSourceID.trenitaliaNews), client: client, pauses: pauses, now: now) { body in
            try? TrenitaliaNewsParser.alerts(body.text, rules: rules)
        }
        trenord = NoticeFeed(source(NoticeSourceID.trenordLines), client: client, pauses: pauses, now: now) { body in
            try? TrenordLinesParser.notices(body.text, rules: rules)
        }
        self.rules = rules
    }

    /// The iPhone's notice sources over `transport` (ephemeral `URLSession`s); nil without rules.
    public static func standard(
        rules: NoticeRules?, transport: any LiveHTTPTransport = URLSessionLiveTransport(), pauses: LiveSourcePauses = .shared,
        now: @escaping @Sendable () -> Date = { Date() }
    ) -> RailNoticeService? {
        guard let rules else { return nil }
        return RailNoticeService(rules: rules, transport: transport, pauses: pauses, now: now)
    }

    private func allowed(_ feed: NoticeTable.Source, personal: Bool) -> Bool {
        feed.gate == "all-builds" || personal
    }

    /// Whether the Trenord notices of a station board would be asked (the personal sources on).
    public func hasTrenordSource(personal: Bool) -> Bool {
        allowed(trenord.source, personal: personal)
    }

    /// A read bounded by Android's per-source time limit; nil when the source is not asked.
    private func read<Value: Sendable>(_ feed: NoticeFeed<Value>, personal: Bool, asked: Bool = true) async -> NoticeFeedRead<Value>? {
        guard asked, allowed(feed.source, personal: personal) else { return nil }
        let timeout = NoticeTable.service.sourceTimeoutSeconds
        let read = await LiveTasks.withDeadline(seconds: timeout) { await feed.read() }
        return read ?? NoticeFeedRead<Value>.failed(.sourceUnavailable)
    }

    /// «Scioperi in programma»: the rail strikes of the MIT calendar; empty when the feed fails, and
    /// then the area keeps only the link to the calendar.
    public func strikeCalendar() async -> [StrikeNotice] {
        await read(strikeFeed, personal: false)?.value ?? []
    }

    /// The notices area; without `includeTrenord` (the works sections) Trenord's lines are not asked.
    public func general(personal: Bool, includeTrenord: Bool = true) async -> GeneralNotices {
        async let rfiRead = read(rfi, personal: personal)
        async let trenitaliaRead = read(trenitalia, personal: personal)
        async let trenordRead = read(trenord, personal: personal, asked: includeTrenord)
        var reads: [(publisher: NoticePublisher, read: NoticeFeedRead<[NoticeAlert]>)] = []
        if let read = await rfiRead { reads.append((publisher: .rfi, read: read)) }
        if let read = await trenitaliaRead { reads.append((publisher: .trenitalia, read: read)) }
        let lines: NoticeFeedRead<TrenordLineNotices>? = await trenordRead
        var network: [RailNotice] = []
        var unavailable: Set<NoticePublisher> = []
        for entry in reads {
            network += (entry.read.value ?? []).compactMap { notice($0, publisher: entry.publisher) }
            if entry.read.isFailed { unavailable.insert(entry.publisher) }
        }
        if lines?.isFailed == true { unavailable.insert(.trenord) }
        let groups: [NoticeGroup] = lines?.value.map { groupByLines($0) } ?? []
        return GeneralNotices(
            network: newestFirstWithoutDuplicates(network),
            trenordGroups: groups,
            unavailable: unavailable,
            stale: reads.contains { $0.read.isStale } || lines?.isStale == true
        )
    }

    /// The Trenord notices of the board of `stationName`, asked only with the personal sources on.
    public func stationNotices(_ stationName: String, personal: Bool) async -> StationNotices {
        guard let lines = await read(trenord, personal: personal) else {
            return StationNotices(notices: [], unavailable: false, stale: false)
        }
        guard let value = lines.value else { return StationNotices(notices: [], unavailable: lines.isFailed, stale: false) }
        let alerts = TrenordLineMatcher.alertsForStation(value, stationName: stationName, rules: rules)
        return StationNotices(
            notices: alerts.compactMap { trenordNotice($0, lines: value.lines) }, unavailable: lines.isFailed, stale: lines.isStale
        )
    }

    /// The works sections: the network notices (RFI, and Trenitalia with the personal sources) that
    /// name `routes`, without Trenord's lines.
    public func routeWorks(_ routes: [WorksRoute], matcher: RouteWorksMatcher, today: PlannedLocalDate, personal: Bool) async -> RouteWorks {
        let area = await general(personal: personal, includeTrenord: false)
        return RouteWorks(
            notices: matcher.match(area.network, routes: routes, today: today),
            unavailable: !area.unavailable.isEmpty, stale: area.stale
        )
    }

    // MARK: Notices for display

    /// The first notice of each title wins in publisher order (RFI before Trenitalia); then newest first.
    func newestFirstWithoutDuplicates(_ notices: [RailNotice]) -> [RailNotice] {
        let byPublisher = notices.enumerated().sorted { first, second in
            first.element.publisher == second.element.publisher ? first.offset < second.offset : first.element.publisher < second.element.publisher
        }.map(\.element)
        var seen: Set<String> = []
        let unique = byPublisher.filter { seen.insert(NoticeText.dedupKey($0.title)).inserted }
        return unique.enumerated().sorted { first, second in
            let a = first.element, b = second.element
            let aDate = a.publishedAt ?? Date(timeIntervalSince1970: 0), bDate = b.publishedAt ?? Date(timeIntervalSince1970: 0)
            if aDate != bDate { return aDate > bDate }
            if a.publisher != b.publisher { return a.publisher < b.publisher }
            return first.offset < second.offset
        }.map(\.element)
    }

    /// A notice for the screens. The last change of the text happens here (decoding an entity can
    /// complete a URL), so the links of the cleartext source are checked again after it; the screen
    /// checks once more where it makes them tappable.
    func notice(_ alert: NoticeAlert, publisher: NoticePublisher) -> RailNotice? {
        let cleartextHop = publisher == .trenitalia
        let rules = self.rules
        func checked(_ text: String?) -> String? { cleartextHop ? CleartextNoticeLinks.neutralize(text, rules: rules) : text }
        guard let title = checked(NoticeText.normalize(alert.title, maximumLength: NoticeTable.text.noticeTitleLength)) else { return nil }
        // RFI descriptions can carry markup; the others are plain text by now.
        let body = checked(alert.description.flatMap { NoticeText.fromHtml($0, maximumLength: NoticeTable.text.noticeBodyLength, rules: rules) })
            .flatMap { NoticeText.dedupKey($0) == NoticeText.dedupKey(title) ? nil : $0 }
        return RailNotice(
            id: alert.id, publisher: publisher, title: title, body: body, publishedAt: alert.publishedAt,
            link: cleartextHop ? CleartextNoticeLinks.link(alert.link, rules: rules) : alert.link,
            lines: [], cleartextHop: cleartextHop,
            mentionsStrike: NoticeText.mentionsStrike([title, body], rules: rules)
        )
    }

    private func trenordNotice(_ alert: NoticeAlert, lines: [TrenordLine]) -> RailNotice? {
        guard let base = notice(alert, publisher: .trenord) else { return nil }
        let names = Dictionary(lines.map { ($0.code, $0.description) }, uniquingKeysWith: { first, _ in first })
        return RailNotice(
            id: base.id, publisher: base.publisher, title: base.title, body: base.body, publishedAt: base.publishedAt,
            link: base.link, lines: alert.lines.sorted().map { NoticeLine(code: $0, name: names[$0] ?? "") },
            cleartextHop: base.cleartextHop, mentionsStrike: base.mentionsStrike
        )
    }

    /// Trenord's notices by set of lines: network-wide groups first, then by number of lines and code.
    func groupByLines(_ value: TrenordLineNotices) -> [NoticeGroup] {
        let names = Dictionary(value.lines.map { ($0.code, $0.description) }, uniquingKeysWith: { first, _ in first })
        var order: [[String]] = []
        var groups: [[String]: [NoticeAlert]] = [:]
        for alert in value.alerts {
            let codes = alert.lines.sorted()
            if groups[codes] == nil { order.append(codes) }
            groups[codes, default: []].append(alert)
        }
        let made: [NoticeGroup] = order.compactMap { codes in
            guard let alerts = groups[codes], let first = alerts.first else { return nil }
            let notices = alerts.compactMap { trenordNotice($0, lines: value.lines) }
            guard !notices.isEmpty else { return nil }
            return NoticeGroup(
                lines: codes.map { NoticeLine(code: $0, name: names[$0] ?? "") },
                allLines: TrenordLineMatcher.isNetworkWide(first, lines: value.lines, rules: rules),
                notices: notices
            )
        }
        return made.enumerated().sorted { first, second in
            let a = first.element, b = second.element
            if a.allLines != b.allLines { return a.allLines }
            if a.lines.count != b.lines.count { return a.lines.count > b.lines.count }
            let aCode = a.lines.first?.code ?? "", bCode = b.lines.first?.code ?? ""
            return aCode == bCode ? first.offset < second.offset : aCode < bCode
        }.map(\.element)
    }
}
