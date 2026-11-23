import Foundation

/// contracts/shared-data/v1/notices-sources-v1.json: the notice feeds and the tables that read them,
/// Android's values (a Kotlin test keeps the file equal to its constants until a generator writes
/// both). The app bundles the file; a missing or invalid one turns every notice source off.
public struct NoticeSourcesTable: Decodable, Sendable {
    public static let resourceName = "notices-sources-v1"
    public static let resourceExtension = "json"
    static let schemaName = "binario-shared-data-notices-sources-v1"
    /// A bigger file is not a table of this app.
    static let maximumFileBytes = 262_144

    let schema: String
    let manners: Manners
    let sources: [Source]
    let links: Links
    let text: Text
    let rss: Rss
    let strikes: Strikes
    let months: [String]
    let trenitalia: Trenitalia
    let trenord: Trenord
    let routeWorks: RouteWorks
    public let display: Display

    /// Android's `PublicSourceRefusalGate` and `SourceCircuitBreaker`, and the notices service's limits.
    struct Manners: Decodable, Sendable {
        let refusalStatuses: [Int]
        let refusalPauseSeconds: TimeInterval
        let maximumRetryAfterSeconds: TimeInterval
        let failureThreshold: Int
        let baseOpenSeconds: TimeInterval
        let maximumOpenSeconds: TimeInterval
        let sourceTimeoutSeconds: TimeInterval
        let reloadAfterSeconds: TimeInterval
    }

    /// Which builds ask a source: every one, or only those with the personal live sources (on the
    /// iPhone, «Dati in tempo reale»).
    enum Gate: String, Decodable, Sendable {
        case allBuilds = "all-builds"
        case personal
    }

    enum Redirects: String, Decodable, Sendable {
        /// The same path and query over HTTPS on the same host (`HttpPublicSourceFetcher`).
        case sameResource = "same-resource"
        /// Any path over HTTPS on the same host (`HttpMitStrikeFetcher`).
        case sameHTTPSHost = "same-https-host"
    }

    struct Source: Decodable, Sendable {
        let id: String
        let publisher: String?
        let gate: Gate
        let url: String
        let accept: String
        let connectTimeoutSeconds: TimeInterval
        let readTimeoutSeconds: TimeInterval
        let totalTimeoutSeconds: TimeInterval
        let maximumBytes: Int
        let maximumRequests: Int
        let redirects: Redirects
        let viaggiaTrenoCleartextHop: Bool
        let minimumIntervalSeconds: TimeInterval
        let timeToLiveSeconds: TimeInterval
        let maximumStaleSeconds: TimeInterval
        let retryDelayMillis: Int?
        let pauseLabel: String?
    }

    struct Links: Decodable, Sendable {
        let mitStrikeCalendar: String
        let fsNews: String
        let guaranteedTrains: [String: String]
    }

    struct Text: Decodable, Sendable {
        let maximumInput: Int
        let noticeTitleLength: Int
        let noticeBodyLength: Int
        let strikeWordPattern: String
        let urlPattern: String
        let urlTrailing: String
        let minimumUrlLength: Int
        let cleartextOfficialDomains: [String]
    }

    struct Rss: Decodable, Sendable {
        let maximumItems: Int
        let maximumText: Int
    }

    struct Strikes: Decodable, Sendable {
        let railWordsPattern: String
        let titleLength: Int
        let descriptionLength: Int
        let upcomingShown: Int
        let undatedRecentDays: Int
        let startLabelPattern: String
        let endLabelPattern: String
        let labelWindow: Int
        let inferNextYearMonthsBack: Int
    }

    struct Trenitalia: Decodable, Sendable {
        let maximumItems: Int
        let titleLength: Int
        let descriptionLength: Int
        let linkLength: Int
        let maximumTrainTags: Int
        let trainTagPattern: String
    }

    struct Trenord: Decodable, Sendable {
        struct Area: Decodable, Sendable {
            let minimumLatitude: Double
            let maximumLatitude: Double
            let minimumLongitude: Double
            let maximumLongitude: Double
        }

        let maximumLines: Int
        let maximumNewsPerLine: Int
        let textLength: Int
        let titleLength: Int
        let lineCodeLength: Int
        let lineCodePattern: String
        let lineDescriptionLength: Int
        let operationalPattern: String
        let stationTokenSeparators: [String]
        let minimumTokenLength: Int
        let hubLineCount: Int
        let networkWideMinimumLines: Int
        let area: Area
        let gtfsStationPrefix: String
    }

    struct RouteWorks: Decodable, Sendable {
        let maximumRoutes: Int
        let minimumNameLength: Int
        let worksPattern: String
        let linePattern: String
        let commonWords: [String]
    }

    /// How much the screens show before «Mostra tutti» and «Mostra tutto».
    public struct Display: Decodable, Sendable {
        public let networkCollapsed: Int
        public let bodyCollapsedLines: Int
        public let bodyCollapsedCharacters: Int
        public let maximumLineCodes: Int
        public let worksCollapsed: Int
    }

    /// The source `id`, nil when the table has none.
    func source(_ id: String) -> Source? {
        sources.first { $0.id == id }
    }

    /// The table in `data`, nil when it is not a valid one (never a partial table).
    public static func load(data: Data) -> NoticeSourcesTable? {
        guard data.count <= maximumFileBytes,
              let table = try? JSONDecoder().decode(NoticeSourcesTable.self, from: data),
              table.isValid else { return nil }
        return table
    }

    /// The bundled table, nil without one.
    public static func load(url: URL?) -> NoticeSourcesTable? {
        guard let url, let data = try? Data(contentsOf: url) else { return nil }
        return load(data: data)
    }

    private var isValid: Bool {
        let ids = Set(sources.map(\.id))
        guard schema == Self.schemaName, ids.count == sources.count,
              ids.isSuperset(of: NoticeSourceID.all), months.count == 12,
              !text.cleartextOfficialDomains.isEmpty, manners.refusalStatuses.contains(403),
              manners.refusalPauseSeconds > 0, manners.maximumRetryAfterSeconds > 0, manners.failureThreshold > 0,
              manners.baseOpenSeconds > 0, manners.maximumOpenSeconds > 0, manners.sourceTimeoutSeconds > 0,
              ([links.mitStrikeCalendar, links.fsNews] + Array(links.guaranteedTrains.values)).allSatisfy(Self.isHTTPS)
        else { return false }
        return sources.allSatisfy { source in
            Self.isHTTPS(source.url) && !source.accept.isEmpty && source.readTimeoutSeconds > 0
                && source.totalTimeoutSeconds > 0 && (1...16 * 1_048_576).contains(source.maximumBytes)
                && (1...5).contains(source.maximumRequests) && source.minimumIntervalSeconds > 0
                && source.timeToLiveSeconds > 0 && source.maximumStaleSeconds >= 0
        }
    }

    /// An HTTPS address with a host and without credentials.
    static func isHTTPS(_ value: String) -> Bool {
        guard let components = URLComponents(string: value) else { return false }
        return components.scheme == "https" && components.host?.isEmpty == false && components.user == nil && components.password == nil
    }
}

/// The identifiers of the notice sources, Android's `SourceId`s.
enum NoticeSourceID {
    static let mitStrikes = "mit-strikes-rss"
    static let rfiInfomobility = "rfi-infomobility-rss"
    static let trenitaliaNews = "trenitalia-infomobility-news"
    static let trenordLines = "trenord-line-notices"
    static let all: Set<String> = [mitStrikes, rfiInfomobility, trenitaliaNews, trenordLines]
}

/// A compiled pattern of the notice rules. `NSRegularExpression` is immutable and thread safe.
struct NoticeRegex: @unchecked Sendable {
    /// One match: its range and its groups (nil for a group that did not take part), in UTF-16 offsets.
    struct Match {
        let range: NSRange
        let groups: [NSRange?]

        var start: Int { range.location }

        /// The text of group `index` in `value`, "" when it did not take part (Kotlin's `groupValues`).
        func group(_ index: Int, in value: NSString) -> String {
            guard index < groups.count, let range = groups[index] else { return "" }
            return value.substring(with: range)
        }
    }

    private let expression: NSRegularExpression

    init?(_ pattern: String, caseInsensitive: Bool = false) {
        guard let expression = try? NSRegularExpression(pattern: pattern, options: caseInsensitive ? [.caseInsensitive] : []) else {
            return nil
        }
        self.expression = expression
    }

    func matches(in value: String) -> [Match] {
        let range = NSRange(location: 0, length: (value as NSString).length)
        return expression.matches(in: value, options: [], range: range).map { result in
            Match(range: result.range, groups: (0..<result.numberOfRanges).map { index in
                let group = result.range(at: index)
                return group.location == NSNotFound ? nil : group
            })
        }
    }

    /// Kotlin's `containsMatchIn`.
    func contains(_ value: String) -> Bool {
        expression.firstMatch(in: value, options: [], range: NSRange(location: 0, length: (value as NSString).length)) != nil
    }

    /// Kotlin's `matches`: the whole of `value`.
    func matchesWhole(_ value: String) -> Bool {
        let length = (value as NSString).length
        guard let match = expression.firstMatch(in: value, options: [.anchored], range: NSRange(location: 0, length: length)) else {
            return false
        }
        return match.range.length == length
    }

    /// Every match replaced by `template` (literal text).
    func replacing(in value: String, with template: String) -> String {
        expression.stringByReplacingMatches(
            in: value, options: [], range: NSRange(location: 0, length: (value as NSString).length),
            withTemplate: NSRegularExpression.escapedTemplate(for: template)
        )
    }
}

/// The table with its patterns compiled: every notice rule reads it. Nil when a pattern of the
/// table does not compile, so the sources stay off.
public struct NoticeRules: Sendable {
    let table: NoticeSourcesTable
    let strikeWord: NoticeRegex
    let url: NoticeRegex
    let railWords: NoticeRegex
    let startLabel: NoticeRegex
    let endLabel: NoticeRegex
    let trainTag: NoticeRegex
    let lineCode: NoticeRegex
    let operational: NoticeRegex
    let works: NoticeRegex
    let line: NoticeRegex
    let strikeDatePatterns: StrikeDatePatterns
    let noticeDatePatterns: NoticeDatePatterns

    public init?(table: NoticeSourcesTable) {
        guard let strikeWord = NoticeRegex(table.text.strikeWordPattern, caseInsensitive: true),
              let url = NoticeRegex(table.text.urlPattern, caseInsensitive: true),
              let railWords = NoticeRegex(table.strikes.railWordsPattern),
              let startLabel = NoticeRegex(table.strikes.startLabelPattern),
              let endLabel = NoticeRegex(table.strikes.endLabelPattern),
              let trainTag = NoticeRegex(table.trenitalia.trainTagPattern),
              let lineCode = NoticeRegex(table.trenord.lineCodePattern),
              let operational = NoticeRegex(table.trenord.operationalPattern, caseInsensitive: true),
              let works = NoticeRegex(table.routeWorks.worksPattern),
              let line = NoticeRegex(table.routeWorks.linePattern),
              let strikeDatePatterns = StrikeDatePatterns(months: table.months),
              let noticeDatePatterns = NoticeDatePatterns(months: table.months)
        else { return nil }
        self.table = table
        self.strikeWord = strikeWord
        self.url = url
        self.railWords = railWords
        self.startLabel = startLabel
        self.endLabel = endLabel
        self.trainTag = trainTag
        self.lineCode = lineCode
        self.operational = operational
        self.works = works
        self.line = line
        self.strikeDatePatterns = strikeDatePatterns
        self.noticeDatePatterns = noticeDatePatterns
    }

    /// The bundled table's rules, nil when the table is missing or invalid.
    public static func load(url: URL?) -> NoticeRules? {
        NoticeSourcesTable.load(url: url).flatMap { NoticeRules(table: $0) }
    }

    public var display: NoticeSourcesTable.Display { table.display }

    /// «Ricarica»: a section shown again after this asks its sources again (their caches decide).
    public var reloadAfter: TimeInterval { table.manners.reloadAfterSeconds }

    /// The saved routes a works section reads at most.
    public var maximumWorksRoutes: Int { table.routeWorks.maximumRoutes }

    /// The MIT strike calendar page, opened in the browser on a tap only.
    public var mitStrikeCalendar: URL? { URL(string: table.links.mitStrikeCalendar) }

    /// The FS News site with its radio bulletins, opened in the browser on a tap only.
    public var fsNews: URL? { URL(string: table.links.fsNews) }

    /// The operator's page of the trains guaranteed during a strike (none is known for RFI).
    public func guaranteedTrains(of publisher: NoticePublisher) -> URL? {
        table.links.guaranteedTrains[publisher.tableName].flatMap { URL(string: $0) }
    }

    /// Roughly the Trenord service area (Lombardy and its border stations), used only to avoid
    /// useless requests.
    public func inTrenordArea(latitude: Double, longitude: Double) -> Bool {
        let area = table.trenord.area
        return (area.minimumLatitude...area.maximumLatitude).contains(latitude)
            && (area.minimumLongitude...area.maximumLongitude).contains(longitude)
    }

    /// A station of the Trenord timetable, or one whose catalogue coordinates lie in its area.
    public func inTrenordArea(_ station: Station) -> Bool {
        if station.id.hasPrefix(table.trenord.gtfsStationPrefix) { return true }
        guard let latitude = station.latitude, let longitude = station.longitude else { return false }
        return inTrenordArea(latitude: latitude, longitude: longitude)
    }
}
