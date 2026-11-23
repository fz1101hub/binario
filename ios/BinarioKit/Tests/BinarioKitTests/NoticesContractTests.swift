import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/notices-v1.json: the notice rules Android and the iPhone must share
/// (strike days, notice text and links, Trenitalia and Trenord lists, route works), read from the
/// same file by both test suites, with Android's fixtures as inputs and the shared table of
/// contracts/shared-data/v1 as the rules.
final class NoticesContractTests: XCTestCase {
    func testStrikeDaysOfTheMITCalendar() throws {
        let rules = try NoticeContract.rules()
        let strikes = try NoticeContract.section("strikes")
        let items = try Rss2Parser.parse(NoticeContract.data(strikes.string("feed")), rules: rules)
        XCTAssertEqual(items.count, strikes.int("items"))
        let first = strikes.object("firstItem")
        XCTAssertEqual(items.first?.title, first.string("title"))
        XCTAssertEqual(items.first?.link, first.string("link"))
        XCTAssertEqual(items.first?.guid, first.string("guid"))
        XCTAssertEqual(items.first?.pubDate, NoticeContract.instant(first.string("pubDate")))
        XCTAssertTrue(items.first?.description?.contains(first.string("descriptionContains")) == true)

        let today = try XCTUnwrap(PlannedLocalDate(iso: strikes.string("fetchedOn")))
        let rail = rules.strikes(items, fetchedOn: today)
        let expected = strikes.objects("rail")
        XCTAssertEqual(rail.map(\.id), expected.map { $0.string("id") })
        for item in expected {
            let strike = try XCTUnwrap(rail.first { $0.id == item.string("id") })
            XCTAssertEqual(strike.startDate, item.date("start"), strike.id)
            XCTAssertEqual(strike.endDate, item.date("end"), strike.id)
        }
        let description = strikes.object("descriptionContains")
        XCTAssertTrue(rail.first { $0.id == description.string("id") }?.description?.contains(description.string("text")) == true)
        let now = try XCTUnwrap(NoticeContract.instant(strikes.string("now")))
        XCTAssertEqual(rules.upcoming(rail, today: today, now: now).map(\.id), strikes.strings("upcoming"))
        XCTAssertEqual(rules.startingTomorrow(rail, today: today).map(\.id), strikes.strings("startingTomorrow"))
        let gone = strikes.object("undatedGoneAfter")
        let later = try XCTUnwrap(NoticeContract.instant(gone.string("now")))
        XCTAssertFalse(rules.upcoming(rail, today: today, now: later).contains { $0.id == gone.string("id") })

        for item in strikes.objects("isRail") {
            let rss = RssItem(title: item.string("title"), link: nil, description: item.optionalString("description"), pubDate: nil, guid: nil)
            XCTAssertEqual(rules.isRail(rss), item.bool("rail"), item.string("title"))
        }
        for item in strikes.objects("dates") {
            let reference = try XCTUnwrap(PlannedLocalDate(iso: item.string("reference")))
            let dates = rules.strikeDates(item.string("text"), reference: reference)
            XCTAssertEqual(dates.start, item.date("start"), item.string("text"))
            XCTAssertEqual(dates.end, item.date("end"), item.string("text"))
        }
        for body in strikes.strings("unreadable") {
            XCTAssertNil(try? Rss2Parser.parse(Data(body.utf8), rules: rules), body)
        }
    }

    func testNoticeTextStrikeWordsAndLinks() throws {
        let rules = try NoticeContract.rules()
        let text = try NoticeContract.section("text")
        for item in text.objects("mentionsStrike") {
            let texts = item.optionalStrings("texts")
            XCTAssertEqual(NoticeText.mentionsStrike(texts, rules: rules), item.bool("strike"), "\(texts)")
        }
        for item in text.objects("normalize") {
            XCTAssertEqual(NoticeText.normalize(item.string("text"), maximumLength: item.int("maximumLength")), item.optionalString("expected"))
        }
        for item in text.objects("fromHtml") {
            XCTAssertEqual(
                NoticeText.fromHtml(item.string("html"), maximumLength: item.int("maximumLength"), rules: rules), item.optionalString("expected")
            )
        }
        for pair in text.arrays("sameDedupKey") {
            XCTAssertEqual(NoticeText.dedupKey(pair[0]), NoticeText.dedupKey(pair[1]))
        }
        for item in text.objects("links") {
            let segments = NoticeLinks.segments(item.string("text"), rules: rules)
            XCTAssertEqual(NoticeContract.links(segments), item.strings("links"))
            XCTAssertEqual(segments.map(\.text).joined(), item.string("text"))
        }
        for item in text.objects("cleartextAllowed") {
            XCTAssertEqual(CleartextNoticeLinks.allowed(item.string("url"), rules: rules), item.bool("allowed"), item.string("url"))
        }
        for item in text.objects("neutralize") {
            let neutral = try XCTUnwrap(CleartextNoticeLinks.neutralize(item.string("text"), rules: rules))
            XCTAssertEqual(NoticeContract.links(NoticeLinks.segments(neutral, rules: rules)), item.strings("links"), item.string("text"))
            if let contained = item.optionalString("contains") { XCTAssertTrue(neutral.contains(contained), neutral) }
            if let expected = item.optionalString("expected") { XCTAssertEqual(neutral, expected) }
        }
    }

    func testTrenitaliaInfomobilityNews() async throws {
        let rules = try NoticeContract.rules()
        let news = try NoticeContract.section("trenitalia")
        let body = try NoticeContract.text(news.string("feed"))
        let alerts = try TrenitaliaNewsParser.alerts(body, rules: rules)
        let expected = news.objects("notices")
        XCTAssertEqual(alerts.count, expected.count)
        for (item, alert) in zip(expected, alerts) {
            if let title = item.optionalString("title") { XCTAssertEqual(alert.title, title) }
            if let prefix = item.optionalString("titleStartsWith") { XCTAssertTrue(alert.title.hasPrefix(prefix)) }
            let description = try XCTUnwrap(alert.description)
            if let prefix = item.optionalString("descriptionStartsWith") { XCTAssertTrue(description.hasPrefix(prefix), description) }
            for fragment in item.strings("descriptionContains") { XCTAssertTrue(description.contains(fragment), fragment) }
            for markup in news.strings("neverInDescriptions") { XCTAssertFalse(description.contains(markup), markup) }
            XCTAssertEqual(alert.trainNumbers, Set(item.strings("trainNumbers")))
            XCTAssertEqual(alert.publishedAt, NoticeContract.instant(item.string("publishedAt")))
            if item.has("link") { XCTAssertEqual(alert.link, item.optionalString("link")) }
        }

        let hostile = news.object("hostile")
        let parsed = try XCTUnwrap(TrenitaliaNewsParser.alerts(NoticeContract.json([hostile.raw("item")]), rules: rules).first)
        XCTAssertEqual(parsed.description, hostile.string("description"))
        XCTAssertEqual(parsed.link, hostile.optionalString("link"))

        let foreign = news.object("foreignLinks")
        let both = try TrenitaliaNewsParser.alerts(NoticeContract.json(foreign.raw("items")), rules: rules)
        XCTAssertEqual(both.count, 2)
        XCTAssertEqual(both.first?.link, foreign.optionalString("firstLink"))
        let firstText = (both.first?.title ?? "") + " " + (both.first?.description ?? "")
        XCTAssertEqual(NoticeContract.links(NoticeLinks.segments(firstText, rules: rules)), foreign.strings("firstLinks"))
        XCTAssertTrue(both.first?.title.contains(foreign.string("firstTitleContains")) == true)
        XCTAssertTrue(both.first?.description?.contains(foreign.string("firstDescriptionContains")) == true)
        XCTAssertEqual(both.last?.link, foreign.string("secondLink"))

        let twice = news.object("encodedTwice")
        let service = try NoticeContract.service(rules, answers: [NoticeSourceID.trenitaliaNews: NoticeContract.json([twice.raw("item")])])
        let area = await service.general(personal: true)
        let notice = try XCTUnwrap(area.network.first)
        XCTAssertTrue(notice.cleartextHop)
        XCTAssertEqual(NoticeContract.links(NoticeLinks.segments(notice.title + " " + (notice.body ?? ""), rules: rules)), twice.strings("links"))
        XCTAssertTrue(notice.body?.contains(twice.string("bodyContains")) == true)
        XCTAssertEqual(notice.link, twice.string("link"))
        // Where a link is made the rule holds on its own, whatever the text went through before.
        XCTAssertFalse(notice.allowsLink("https://evil.example/", rules: rules))

        for bad in news.strings("unreadable") + [String(body.prefix(news.int("truncatedUnreadable")))] {
            XCTAssertNil(try? TrenitaliaNewsParser.alerts(bad, rules: rules), bad)
        }
    }

    func testTheNoticesAreaWithoutDuplicatesAndWithTheStrikeBadge() async throws {
        let rules = try NoticeContract.rules()
        let general = try NoticeContract.section("general")
        let news = try NoticeContract.text(NoticeContract.section("trenitalia").string("feed"))
        let lines = try NoticeContract.text(NoticeContract.section("trenord").string("feed"))
        let service = try NoticeContract.service(rules, answers: [
            NoticeSourceID.rfiInfomobility: general.string("rfi"), NoticeSourceID.trenitaliaNews: news,
        ])
        let area = await service.general(personal: true, includeTrenord: false)
        XCTAssertEqual(area.network.count, general.int("network"))
        let rfiOnly = area.network.filter { $0.title.hasPrefix(general.string("rfiOnlyTitleStartsWith")) }
        XCTAssertEqual(rfiOnly.map(\.publisher), [.rfi])
        XCTAssertEqual(rfiOnly.first?.body, general.string("rfiOnlyBody"))
        XCTAssertEqual(rfiOnly.first?.publishedAt, NoticeContract.instant(general.string("rfiOnlyPublishedAt")))
        XCTAssertTrue(area.unavailable.isEmpty)

        let broken = try NoticeContract.service(rules, answers: [NoticeSourceID.rfiInfomobility: general.string("rfi")])
        let failed = await broken.general(personal: true, includeTrenord: false)
        XCTAssertEqual(failed.unavailable, [.trenitalia])
        XCTAssertEqual(failed.network.count, general.int("trenitaliaFailingNetwork"))

        let badges = try NoticeContract.service(rules, answers: [
            NoticeSourceID.rfiInfomobility: general.string("strikeRfi"), NoticeSourceID.trenitaliaNews: news,
            NoticeSourceID.trenordLines: lines,
        ])
        let withBadges = await badges.general(personal: true)
        XCTAssertEqual(withBadges.network.filter(\.mentionsStrike).map(\.publisher.tableName), general.strings("strikeBadgePublishers"))
        let trenordStrikes = withBadges.trenordGroups.flatMap(\.notices).filter(\.mentionsStrike)
        XCTAssertEqual(trenordStrikes.count, 1)
        XCTAssertTrue(trenordStrikes.first?.body?.contains(general.string("trenordStrikeBodyContains")) == true)
    }

    func testTrenordLineNoticesGroupsAndStationBoards() async throws {
        let rules = try NoticeContract.rules()
        let trenord = try NoticeContract.section("trenord")
        let body = try NoticeContract.text(trenord.string("feed"))
        let notices = try TrenordLinesParser.notices(body, rules: rules)
        XCTAssertEqual(notices.lines.map(\.code), trenord.strings("lines"))
        XCTAssertEqual(notices.lines.first?.stationTokens, trenord.strings("firstLineTokens"))
        XCTAssertEqual(notices.alerts.count, trenord.int("notices"))
        let strikeCase = trenord.object("strike")
        let strike = try XCTUnwrap(notices.alerts.first { $0.description?.hasPrefix(strikeCase.string("startsWith")) == true })
        XCTAssertEqual(Set(strike.lines), Set(strikeCase.strings("lines")))
        XCTAssertEqual(strike.publishedAt, NoticeContract.instant(strikeCase.string("date")))
        XCTAssertTrue(strike.title.hasPrefix(strikeCase.string("titleStartsWith")))
        XCTAssertTrue(strike.title.count <= strikeCase.int("titleMaximumLength") && strike.title.hasSuffix("…"))
        XCTAssertTrue(strike.description?.hasSuffix(strikeCase.string("descriptionEndsWith")) == true)
        XCTAssertFalse(notices.alerts.contains { $0.description?.contains(trenord.string("neverContains")) == true })
        let dates = notices.alerts.map { $0.publishedAt ?? .distantPast }
        XCTAssertEqual(dates, dates.sorted(by: >))
        for item in trenord.objects("operational") {
            XCTAssertEqual(TrenordLinesParser.isOperational(item.string("text"), rules: rules), item.bool("operational"), item.string("text"))
        }

        let service = try NoticeContract.service(rules, answers: [NoticeSourceID.trenordLines: body])
        let groups = await service.general(personal: true).trenordGroups
        let expectedGroups = trenord.objects("groups")
        XCTAssertEqual(groups.map { $0.lines.map(\.code) }, expectedGroups.map { $0.strings("lines") })
        XCTAssertEqual(groups.map(\.allLines), expectedGroups.map { $0.bool("allLines") })
        for (item, group) in zip(expectedGroups, groups) {
            if let name = item.optionalString("name") { XCTAssertEqual(group.lines.first?.name, name) }
        }
        XCTAssertEqual(groups.reduce(0) { $0 + $1.notices.count }, trenord.int("groupedNotices"))
        for item in trenord.objects("stations") {
            let station = await service.stationNotices(item.string("name"), personal: true).notices
            XCTAssertEqual(station.count, item.int("notices"), item.string("name"))
            if let code = item.optionalString("everyNoticeOnLine") {
                XCTAssertTrue(station.allSatisfy { notice in notice.lines.contains { $0.code == code } })
            }
        }
        for item in trenord.objects("matchedTokens") {
            let line = TrenordLine(code: item.string("code"), description: item.string("description"), rules: rules)
            XCTAssertEqual(Set(TrenordLineMatcher.matchedTokens(line, stationNames: item.strings("stations"))), Set(item.strings("tokens")))
        }
        for item in trenord.objects("area") {
            XCTAssertEqual(rules.inTrenordArea(latitude: item.double("latitude"), longitude: item.double("longitude")), item.bool("inArea"))
        }
        for bad in trenord.strings("unreadable") + [String(body.prefix(trenord.int("truncatedUnreadable")))] {
            XCTAssertNil(try? TrenordLinesParser.notices(bad, rules: rules), bad)
        }
    }

    func testRouteWorksOnTheStationsAndLinesOfTheRoutes() async throws {
        let rules = try NoticeContract.rules()
        let works = try NoticeContract.section("routeWorks")
        let catalog = try CuratedStationCatalog(data: NoticeContract.data(works.string("catalogue")))
        func station(_ name: String) throws -> Station { try XCTUnwrap(catalog.stations.first { $0.name == name }, name) }
        var routes: [String: WorksRoute] = [:]
        var order: [String] = []
        for route in works.objects("routes") {
            routes[route.string("id")] = try WorksRoute(id: route.string("id"), name: route.string("id"), stations: route.strings("stations").map(station))
            order.append(route.string("id"))
        }
        let service = try NoticeContract.service(
            rules, answers: [NoticeSourceID.rfiInfomobility: NoticeContract.text(works.string("feed"))],
            now: XCTUnwrap(NoticeContract.instant(works.string("now")))
        )
        let notices = await service.general(personal: false, includeTrenord: false).network
        XCTAssertEqual(notices.count, works.int("network"))
        let matcher = RouteWorksMatcher(catalogue: catalog.stations, rules: rules)
        let today = try XCTUnwrap(PlannedLocalDate(iso: works.string("today")))
        let found = matcher.match(notices, routes: order.compactMap { routes[$0] }, today: today)
        let expected = works.objects("works")
        XCTAssertEqual(found.map(\.notice.title), expected.map { $0.string("title") })
        for (item, notice) in zip(expected, found) {
            let reasons = item.objects("reasons")
            XCTAssertEqual(notice.reasons.map(\.routeID), reasons.map { $0.string("routeId") })
            XCTAssertEqual(notice.reasons.map(\.stations), reasons.map { $0.strings("stations") })
            XCTAssertEqual(notice.reasons.map(\.line), reasons.map { $0.optionalString("line") })
            let dates = item.object("dates")
            XCTAssertEqual(notice.dates?.first, dates.date("first"))
            XCTAssertEqual(notice.dates?.last, dates.date("last"))
        }
        // The works sections never ask Trenord's line list, nor Trenitalia's without the personal sources.
        let rfiURL = try rules.feedURL(NoticeSourceID.rfiInfomobility)
        XCTAssertEqual(service.transportURLs, [rfiURL])
        for ids in works.arrays("noMatchRoutes") {
            XCTAssertTrue(matcher.match(notices, routes: ids.compactMap { routes[$0] }, today: today).isEmpty, "\(ids)")
        }
        for item in works.objects("singleNotices") {
            let title = item.string("title")
            let route = try WorksRoute(id: "r", name: "r", stations: item.strings("route").map(station))
            let notice = RailNotice(
                id: title, publisher: .rfi, title: title, body: nil, publishedAt: nil, link: nil, lines: [], cleartextHop: false,
                mentionsStrike: false
            )
            let result = matcher.match([notice], routes: [route], today: today)
            if item.isNull("stations") {
                XCTAssertTrue(result.isEmpty, title)
            } else {
                XCTAssertEqual(result.first?.reasons.first?.stations, item.strings("stations"), title)
            }
        }
        for item in works.objects("dates") {
            let reference = try XCTUnwrap(PlannedLocalDate(iso: item.string("reference")))
            let dates = rules.noticeDates(item.string("text"), reference: reference)
            XCTAssertEqual(dates?.first, item.date("first"), item.string("text"))
            XCTAssertEqual(dates?.last, item.date("last"), item.string("text"))
        }
        for item in works.objects("isWorks") {
            XCTAssertEqual(RouteWorksMatcher.isWorks(item.string("text"), rules: rules), item.bool("works"), item.string("text"))
        }
        for item in works.objects("fold") {
            XCTAssertEqual(RouteWorksMatcher.fold(item.string("text")), item.string("expected"))
        }
    }
}

// MARK: - The contract files

/// One object of the cases file, read leniently: a missing key reads as empty, and a test that needs
/// it then fails on its value.
struct NoticeCase {
    let values: [String: Any]

    func has(_ key: String) -> Bool { values[key] != nil }
    func isNull(_ key: String) -> Bool { values[key] == nil || values[key] is NSNull }
    func raw(_ key: String) -> Any { values[key] ?? NSNull() }
    func string(_ key: String) -> String { values[key] as? String ?? "" }
    func optionalString(_ key: String) -> String? { values[key] as? String }
    func int(_ key: String) -> Int { (values[key] as? NSNumber)?.intValue ?? 0 }
    func double(_ key: String) -> Double { (values[key] as? NSNumber)?.doubleValue ?? 0 }
    func bool(_ key: String) -> Bool { (values[key] as? NSNumber)?.boolValue ?? false }
    func object(_ key: String) -> NoticeCase { NoticeCase(values: values[key] as? [String: Any] ?? [:]) }
    func objects(_ key: String) -> [NoticeCase] { (values[key] as? [Any] ?? []).map { NoticeCase(values: $0 as? [String: Any] ?? [:]) } }
    func strings(_ key: String) -> [String] { (values[key] as? [Any] ?? []).compactMap { $0 as? String } }
    func optionalStrings(_ key: String) -> [String?] { (values[key] as? [Any] ?? []).map { $0 as? String } }
    func arrays(_ key: String) -> [[String]] { (values[key] as? [Any] ?? []).map { ($0 as? [Any] ?? []).compactMap { $0 as? String } } }
    func date(_ key: String) -> PlannedLocalDate? { optionalString(key).flatMap { PlannedLocalDate(iso: $0) } }
}

/// A notice service whose transport answers each feed from `answers` (by source id) and fails the
/// others; it records what was asked.
final class NoticeContractService: @unchecked Sendable {
    let service: RailNoticeService
    private let transport: FakeLiveTransport

    init(rules: NoticeRules, answers: [String: String], now: Date) {
        let urls = Dictionary(uniqueKeysWithValues: NoticeTable.sources.map { ($0.url, $0.id) })
        let transport = FakeLiveTransport { request in
            guard let id = urls[request.url.absoluteString], let body = answers[id] else { return .failure(.network) }
            return FakeLiveTransport.ok(body)
        }
        self.transport = transport
        service = RailNoticeService(rules: rules, transport: transport, pauses: LiveSourcePauses(), now: { now })
    }

    var transportURLs: [String] { transport.paths }

    func general(personal: Bool, includeTrenord: Bool = true) async -> GeneralNotices {
        await service.general(personal: personal, includeTrenord: includeTrenord)
    }

    func stationNotices(_ name: String, personal: Bool) async -> StationNotices {
        await service.stationNotices(name, personal: personal)
    }
}

extension NoticeRules {
    /// The address of the source `id` in the table.
    func feedURL(_ id: String) throws -> String {
        try XCTUnwrap(NoticeTable.sources.first { $0.id == id }?.url, id)
    }
}

enum NoticeContract {
    static var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }

    static func data(_ path: String) throws -> Data {
        try Data(contentsOf: repositoryRoot.appendingPathComponent(path))
    }

    static func text(_ path: String) throws -> String {
        try String(contentsOf: repositoryRoot.appendingPathComponent(path), encoding: .utf8)
    }

    /// The rules of the generated table (contracts/shared-data/v1/notices-sources-v1.json).
    static func rules() throws -> NoticeRules {
        try XCTUnwrap(NoticeRules.standard)
    }

    static func section(_ name: String) throws -> NoticeCase {
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data("contracts/shared-rules/v1/notices-v1.json")) as? [String: Any])
        return NoticeCase(values: try XCTUnwrap(root[name] as? [String: Any], name))
    }

    static func instant(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)
    }

    static func json(_ value: Any) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: value), as: UTF8.self)
    }

    static func links(_ segments: [NoticeLinks.Segment]) -> [String] {
        segments.compactMap { segment in
            if case let .link(url) = segment { return url }
            return nil
        }
    }

    static func service(_ rules: NoticeRules, answers: [String: String], now: Date = LiveTestDates.viaggiaTrenoNow) throws -> NoticeContractService {
        NoticeContractService(rules: rules, answers: answers, now: now)
    }
}
