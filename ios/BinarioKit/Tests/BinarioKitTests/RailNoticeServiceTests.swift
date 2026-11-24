import Foundation
import XCTest
@testable import BinarioKit

/// The manners of the notice feeds on the iPhone: Android's addresses, limits, caches and pauses
/// from the shared tables, the personal sources behind «Dati in tempo reale», and no source at all
/// without rules. No test reaches the network.
final class RailNoticeServiceTests: XCTestCase {
    private func service(
        _ clock: LiveTestClock, pauses: LiveSourcePauses = LiveSourcePauses(), _ handler: @escaping FakeLiveTransport.Handler
    ) throws -> (RailNoticeService, FakeLiveTransport) {
        let transport = FakeLiveTransport(handler)
        return (RailNoticeService(rules: try NoticeContract.rules(), transport: transport, pauses: pauses, now: clock.closure), transport)
    }

    func testEveryPatternOfTheTableCompilesAndWithoutRulesNoSourceIsAsked() throws {
        // NSRegularExpression takes every pattern Kotlin compiles: the shared rules exist.
        XCTAssertNotNil(NoticeRules.standard)
        XCTAssertNil(NoticeRegex("(unclosed"))
        XCTAssertNil(RailNoticeService.standard(rules: nil))
        XCTAssertNotNil(RailNoticeService.standard(rules: NoticeRules.standard))
    }

    func testEveryRequestIsAndroidsHTTPSGetWithTheHonestUserAgentAndTheFeedsLimits() async throws {
        let clock = LiveTestClock(LiveTestDates.viaggiaTrenoNow)
        let rules = try NoticeContract.rules()
        let (notices, transport) = try service(clock) { _ in FakeLiveTransport.ok("[]") }
        _ = await notices.general(personal: true)
        _ = await notices.strikeCalendar()
        // Every feed of the table but the per-train notices, which ViaggiaTrenoLiveSource asks.
        XCTAssertEqual(Set(transport.paths), Set(NoticeTable.sources.filter { $0.id != NoticeSourceID.trainNotices }.map(\.url)))
        for request in transport.requests {
            let source = try XCTUnwrap(NoticeTable.sources.first { $0.url == request.url.absoluteString })
            XCTAssertEqual(request.url.scheme, "https")
            XCTAssertEqual(request.headers["User-Agent"], LiveSourceUserAgent.general)
            XCTAssertTrue(request.headers["User-Agent"]?.hasSuffix("(iOS; rail timetable app)") == true)
            XCTAssertEqual(request.headers["Accept"], source.accept)
            XCTAssertEqual(request.maximumBytes, source.maximumBytes)
            XCTAssertEqual(request.timeout, source.readTimeoutSeconds)
        }
    }

    func testTrenitaliaAndTrenordAreAskedOnlyWithLiveDataOn() async throws {
        let clock = LiveTestClock(LiveTestDates.viaggiaTrenoNow)
        let rules = try NoticeContract.rules()
        let (notices, transport) = try service(clock) { _ in FakeLiveTransport.ok("[]") }
        let rfi = try rules.feedURL(NoticeSourceID.rfiInfomobility)
        let mit = try rules.feedURL(NoticeSourceID.mitStrikes)
        _ = await notices.general(personal: false)
        let board = await notices.stationNotices("Varese Nord", personal: false)
        XCTAssertTrue(board.notices.isEmpty)
        XCTAssertFalse(board.unavailable)
        XCTAssertEqual(transport.paths, [rfi])
        XCTAssertFalse(notices.hasTrenordSource(personal: false))
        XCTAssertTrue(notices.hasTrenordSource(personal: true))
        _ = await notices.strikeCalendar()
        XCTAssertEqual(transport.paths, [rfi, mit])
    }

    func testAListIsKeptForItsTimeToLiveThenServedAsAnEarlierCopyWhileTheFeedFails() async throws {
        let clock = LiveTestClock(LiveTestDates.viaggiaTrenoNow)
        let rules = try NoticeContract.rules()
        let rfi = try XCTUnwrap(NoticeTable.sources.first { $0.id == NoticeSourceID.rfiInfomobility })
        let failing = LiveCounter()
        let xml = #"<?xml version="1.0"?><rss><channel><item><title>Lavori tra Pavia e Voghera</title><link>https://www.rfi.it/a.html</link></item></channel></rss>"#
        let (notices, transport) = try service(clock) { _ in failing.marked("fail") ? .failure(.network) : FakeLiveTransport.ok(xml) }
        let first = await notices.general(personal: false)
        XCTAssertEqual(first.network.map(\.title), ["Lavori tra Pavia e Voghera"])
        clock.advance(rfi.timeToLiveSeconds - 1)
        _ = await notices.general(personal: false)
        XCTAssertEqual(transport.requests.count, 1, "within the time to live the feed is not asked again")

        failing.mark("fail")
        clock.advance(2)
        let stale = await notices.general(personal: false)
        XCTAssertTrue(stale.stale)
        XCTAssertEqual(stale.network.count, 1)
        XCTAssertTrue(stale.unavailable.isEmpty)
        XCTAssertEqual(transport.requests.count, 2)
        // A failure is remembered for the rate interval: no new request before it.
        _ = await notices.general(personal: false)
        XCTAssertEqual(transport.requests.count, 2)

        clock.advance(rfi.maximumStaleSeconds + rfi.minimumIntervalSeconds)
        let gone = await notices.general(personal: false)
        XCTAssertTrue(gone.network.isEmpty)
        XCTAssertEqual(gone.unavailable, [.rfi])
    }

    func testTheFirstRefusalPausesTheFeedAndNoStatusLineNamesIt() async throws {
        let clock = LiveTestClock(LiveTestDates.viaggiaTrenoNow)
        let rules = try NoticeContract.rules()
        let pauses = LiveSourcePauses()
        let (notices, transport) = try service(clock, pauses: pauses) { _ in FakeLiveTransport.status(429) }
        let rfi = try XCTUnwrap(NoticeTable.sources.first { $0.id == NoticeSourceID.rfiInfomobility })
        let refused = await notices.general(personal: false)
        XCTAssertEqual(refused.unavailable, [.rfi])
        // Past the rate interval the feed is still paused (15 minutes): nothing is sent.
        clock.advance(rfi.minimumIntervalSeconds + 1)
        let paused = await notices.general(personal: false)
        XCTAssertEqual(paused.unavailable, [.rfi])
        XCTAssertEqual(transport.requests.count, 1)
        XCTAssertTrue(pauses.active(at: clock.now()).isEmpty)
        clock.advance(SharedData.SourceManners.refusalPauseSeconds)
        _ = await notices.general(personal: false)
        XCTAssertEqual(transport.requests.count, 2)
    }

    func testTheStrikeCalendarIsReadInTheEncodingItsDeclarationNames() async throws {
        let clock = LiveTestClock(LiveTestDates.viaggiaTrenoNow)
        let xml = #"<?xml version="1.0" encoding="ISO-8859-1"?><rss version="2.0"><channel><item><title>Sciopero ferroviario: è il 3 dicembre 2026</title><guid>x</guid></item></channel></rss>"#
        let latin1 = try XCTUnwrap(xml.data(using: .isoLatin1))
        let (notices, _) = try service(clock) { _ in .response(status: 200, headers: [:], body: latin1) }
        let strikes = await notices.strikeCalendar()
        XCTAssertEqual(strikes.map(\.title), ["Sciopero ferroviario: è il 3 dicembre 2026"])
        XCTAssertEqual(strikes.first?.startDate, PlannedLocalDate(year: 2026, month: 12, day: 3))
    }

    func testOnlyTheMITCalendarFollowsARedirectToAnotherPathOfItsHost() async throws {
        let clock = LiveTestClock(LiveTestDates.viaggiaTrenoNow)
        let rules = try NoticeContract.rules()
        let mit = try rules.feedURL(NoticeSourceID.mitStrikes)
        let rfi = try rules.feedURL(NoticeSourceID.rfiInfomobility)
        let rss = #"<rss><channel><item><title>Sciopero ferroviario</title><link>https://scioperi.mit.gov.it/a</link></item></channel></rss>"#
        let (notices, transport) = try service(clock) { request in
            switch request.url.absoluteString {
            case mit: return FakeLiveTransport.redirect("/mit2/public/scioperi/rss/")
            case rfi: return FakeLiveTransport.redirect("/content/rfi/it/altro.xml")
            default: return FakeLiveTransport.ok(rss)
            }
        }
        let strikes = await notices.strikeCalendar()
        XCTAssertEqual(strikes.count, 1)
        let area = await notices.general(personal: false)
        XCTAssertEqual(area.unavailable, [.rfi], "the RFI feed follows only the same resource")
        XCTAssertFalse(transport.paths.contains { $0.hasSuffix("altro.xml") })
    }

    func testTrenitaliasNewsFollowsOnlyViaggiaTrenosOwnCleartextHop() async throws {
        let clock = LiveTestClock(LiveTestDates.viaggiaTrenoNow)
        let rules = try NoticeContract.rules()
        let news = try rules.feedURL(NoticeSourceID.trenitaliaNews)
        let hop = "http://www.viaggiatreno.it/infomobilitamobile/resteasy/news/infomobility"
        let item = #"[{"title":"Avviso","pubDate":1792879200000}]"#
        let (notices, transport) = try service(clock) { request in
            switch request.url.absoluteString {
            case news: return FakeLiveTransport.redirect(hop)
            case hop: return FakeLiveTransport.ok(item)
            default: return FakeLiveTransport.ok("[]")
            }
        }
        let area = await notices.general(personal: true, includeTrenord: false)
        XCTAssertEqual(area.network.map(\.title), ["Avviso"])
        XCTAssertEqual(area.network.first?.cleartextHop, true)
        XCTAssertEqual(transport.paths.filter { $0.contains("news/infomobility") }, [news, hop])
    }
}
