import Foundation
import XCTest
@testable import BinarioKit

/// The open Trip Updates feeds on the listed timetable trains: only the feed of the listed trains' pack, HTTPS with
/// Binario's User-Agent, the payload kept for the feed's time to live, a failure remembered, a refusal pausing every
/// feed of the host, nothing for another day.
final class TimetableRealtimeTests: XCTestCase {
    /// Trenitalia France's train 70458 of 23 November 2026, leaving Paris (10009) at 17:08 Italian time.
    private struct FrenchTimetable: TimetableTripReading {
        func tripStops(_ trips: [TimetableTripKey]) -> [TimetableTripKey: TimetableTripStops] {
            var found: [TimetableTripKey: TimetableTripStops] = [:]
            for trip in trips where trip.tripID == "70458" {
                found[trip] = TimetableTripStops(stops: [
                    .init(stopID: "10009", sequence: 0, seconds: 17 * 3_600 + 8 * 60, catalogStationID: nil),
                    .init(stopID: "10007", sequence: 62, seconds: 19 * 3_600 + 18 * 60, catalogStationID: "S01700"),
                ], agencyTimeZones: ["Europe/Rome"])
            }
            return found
        }
    }

    private let fetchedAt = Date(timeIntervalSince1970: 1_795_446_000) // 2026-11-23T15:00:00Z
    private let france = "trenitalia-france-gtfs:70458:20261123:61680"
    private let sncf = "sncf-gtfs:trip-42:20261123:57600"

    private func feed() throws -> Data {
        let item = try XCTUnwrap(try RealtimeContract.cases("protobufUpdates").first { $0.string("feed") == "trenitalia-france-gtfs-rt" })
        return try RealtimeContract.hex(item.string("hex"))
    }

    func testTheFeedOfTheListedTrainsPackTimesItAtItsStation() async throws {
        let payload = try feed()
        let clock = LiveTestClock(fetchedAt)
        let transport = FakeLiveTransport { _ in .response(status: 200, headers: [:], body: payload) }
        let realtime = TimetableRealtime.standard(transport: transport, pauses: LiveSourcePauses(), now: clock.closure)
        let answer = await realtime.statuses(serviceIDs: [france, "trenitalia-gtfs:1:20261123:600"], stationID: "x", day: fetchedAt, timetable: FrenchTimetable())
        XCTAssertEqual(transport.paths, ["https://proxy.transport.data.gouv.fr/resource/trenitalia-gtfs-rt"], "the national pack has no feed")
        let request = try XCTUnwrap(transport.requests.first)
        XCTAssertTrue(request.headers["User-Agent"]?.hasPrefix("Binario/") == true)
        XCTAssertFalse(request.headers["User-Agent"]?.contains("Mozilla") == true)
        let status = try XCTUnwrap(answer.statuses[france])
        XCTAssertEqual(status.delayMinutes, 4)
        XCTAssertEqual(status.expectedDeparture, Date(timeIntervalSince1970: 1_795_450_320)) // 16:12Z
        XCTAssertEqual(status.source, .trenitaliaFranceRealtime)
        XCTAssertFalse(status.cancelled)
        XCTAssertEqual(answer.sources, [.trenitaliaFranceRealtime])

        // The payload serves two minutes from the request, then the feed is asked again.
        clock.advance(119)
        _ = await realtime.statuses(serviceIDs: [france], stationID: "x", day: clock.now(), timetable: FrenchTimetable())
        XCTAssertEqual(transport.paths.count, 1)
        clock.advance(2)
        _ = await realtime.statuses(serviceIDs: [france], stationID: "x", day: clock.now(), timetable: FrenchTimetable())
        XCTAssertEqual(transport.paths.count, 2)
    }

    func testNothingIsAskedForAnotherDayOrATrainOfNoFeed() async throws {
        let transport = FakeLiveTransport { _ in .failure(.network) }
        let start = fetchedAt
        let realtime = TimetableRealtime.standard(transport: transport, pauses: LiveSourcePauses(), now: { start })
        let tomorrow = await realtime.statuses(serviceIDs: [france], stationID: "x", day: start.addingTimeInterval(86_400), timetable: FrenchTimetable())
        XCTAssertEqual(tomorrow, TimetableRealtimeAnswer.none)
        let uncovered = await realtime.statuses(serviceIDs: ["oebb-gtfs:1:20261123:600", "live:9558"], stationID: "x", day: start, timetable: FrenchTimetable())
        XCTAssertEqual(uncovered, TimetableRealtimeAnswer.none)
        XCTAssertTrue(transport.paths.isEmpty)
    }

    func testAFailureIsRememberedAndARefusalPausesEveryFeedOfTheHost() async throws {
        let clock = LiveTestClock(fetchedAt)
        let pauses = LiveSourcePauses()
        let transport = FakeLiveTransport { request in
            request.url.absoluteString.hasSuffix("sncf-gtfs-rt-trip-updates") ? FakeLiveTransport.status(403) : FakeLiveTransport.status(500)
        }
        let realtime = TimetableRealtime.standard(transport: transport, pauses: pauses, now: clock.closure)
        let refused = await realtime.statuses(serviceIDs: [sncf], stationID: "x", day: fetchedAt, timetable: FrenchTimetable())
        XCTAssertEqual(refused.unavailableSources[.sncfRealtime], .refused)
        // transport.data.gouv.fr refused: Trenitalia France, on the same host, is not asked either.
        let paused = await realtime.statuses(serviceIDs: [france], stationID: "x", day: fetchedAt, timetable: FrenchTimetable())
        guard case .paused = paused.unavailableSources[.trenitaliaFranceRealtime] else { return XCTFail("\(paused)") }
        XCTAssertEqual(transport.paths.count, 1)
        XCTAssertFalse(pauses.active(at: clock.now()).isEmpty)

        // Another feed's failure is the answer for 30 seconds, without a request.
        let staTransport = FakeLiveTransport { _ in FakeLiveTransport.status(500) }
        let sta = TimetableRealtime.standard(transport: staTransport, pauses: LiveSourcePauses(), now: clock.closure)
        let service = "alto-adige-sta-gtfs:T1:20261123:57600"
        _ = await sta.statuses(serviceIDs: [service], stationID: "x", day: fetchedAt, timetable: FrenchTimetable())
        clock.advance(20)
        _ = await sta.statuses(serviceIDs: [service], stationID: "x", day: clock.now(), timetable: FrenchTimetable())
        XCTAssertEqual(staTransport.paths.count, 1)
        clock.advance(11)
        _ = await sta.statuses(serviceIDs: [service], stationID: "x", day: clock.now(), timetable: FrenchTimetable())
        XCTAssertEqual(staTransport.paths, Array(repeating: "https://gtfs.api.opendatahub.com/v1/realtime/sta-time-tables/trip-updates", count: 2))
    }

    func testAnUnreadablePayloadIsAFailureOfTheSource() async throws {
        let transport = FakeLiveTransport { _ in FakeLiveTransport.ok("<html>maintenance</html>") }
        let start = fetchedAt
        let realtime = TimetableRealtime.standard(transport: transport, pauses: LiveSourcePauses(), now: { start })
        let answer = await realtime.statuses(serviceIDs: [france], stationID: "x", day: fetchedAt, timetable: FrenchTimetable())
        XCTAssertTrue(answer.statuses.isEmpty)
        XCTAssertEqual(answer.unavailableSources[.trenitaliaFranceRealtime], .sourceUnavailable)
    }
}
