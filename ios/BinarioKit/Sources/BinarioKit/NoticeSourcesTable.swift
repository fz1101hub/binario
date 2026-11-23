import Foundation

/// contracts/shared-data/v1/notices-sources-v1.json, generated as `SharedData.NoticesSources`: the
/// notice feeds and the tables that read them, the same values as Android's (`SharedDataTests`).
/// How every source is asked (User-Agent, refusals, breaker) is `SharedData.SourceManners`.
public typealias NoticeTable = SharedData.NoticesSources

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

/// The table's patterns compiled: every notice rule reads them.
public struct NoticeRules: Sendable {
    /// The rules of the shared table; nil only if one of its patterns does not compile here
    /// (NSRegularExpression), and then the notice sources stay off. Kotlin compiles the same
    /// texts; `NoticesContractTests` proves that NSRegularExpression takes every one.
    public static let standard: NoticeRules? = NoticeRules()

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

    init?() {
        guard let strikeWord = NoticeRegex(NoticeTable.text.strikeWordPattern, caseInsensitive: true),
              let url = NoticeRegex(NoticeTable.text.urlPattern, caseInsensitive: true),
              let railWords = NoticeRegex(NoticeTable.strikes.railWordsPattern),
              let startLabel = NoticeRegex(NoticeTable.strikes.startLabelPattern),
              let endLabel = NoticeRegex(NoticeTable.strikes.endLabelPattern),
              let trainTag = NoticeRegex(NoticeTable.trenitalia.trainTagPattern),
              let lineCode = NoticeRegex(NoticeTable.trenord.lineCodePattern),
              let operational = NoticeRegex(NoticeTable.trenord.operationalPattern, caseInsensitive: true),
              let works = NoticeRegex(NoticeTable.routeWorks.worksPattern),
              let line = NoticeRegex(NoticeTable.routeWorks.linePattern),
              let strikeDatePatterns = StrikeDatePatterns(months: NoticeTable.months),
              let noticeDatePatterns = NoticeDatePatterns(months: NoticeTable.months)
        else { return nil }
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

    public var display: NoticeTable.Display { NoticeTable.display }

    /// «Ricarica»: a section shown again after this asks its sources again (their caches decide).
    public var reloadAfter: TimeInterval { NoticeTable.service.reloadAfterSeconds }

    /// The saved routes a works section reads at most.
    public var maximumWorksRoutes: Int { NoticeTable.routeWorks.maximumRoutes }

    /// The MIT strike calendar page, opened in the browser on a tap only.
    public var mitStrikeCalendar: URL? { URL(string: NoticeTable.links.mitStrikeCalendar) }

    /// The FS News site with its radio bulletins, opened in the browser on a tap only.
    public var fsNews: URL? { URL(string: NoticeTable.links.fsNews) }

    /// The operator's page of the trains guaranteed during a strike (none is known for RFI).
    public func guaranteedTrains(of publisher: NoticePublisher) -> URL? {
        NoticeTable.links.guaranteedTrains[publisher.tableName].flatMap { URL(string: $0) }
    }

    /// Roughly the Trenord service area (Lombardy and its border stations), used only to avoid
    /// useless requests.
    public func inTrenordArea(latitude: Double, longitude: Double) -> Bool {
        let area = NoticeTable.trenord.area
        return (area.minimumLatitude...area.maximumLatitude).contains(latitude)
            && (area.minimumLongitude...area.maximumLongitude).contains(longitude)
    }

    /// A station of the Trenord timetable, or one whose catalogue coordinates lie in its area.
    public func inTrenordArea(_ station: Station) -> Bool {
        if station.id.hasPrefix(NoticeTable.trenord.gtfsStationPrefix) { return true }
        guard let latitude = station.latitude, let longitude = station.longitude else { return false }
        return inTrenordArea(latitude: latitude, longitude: longitude)
    }
}
