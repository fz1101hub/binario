import Foundation
import XCTest
@testable import BinarioKit

/// What the widgets load from the bundled timetable (WidgetFeeds) and keep in their App Group (WidgetSharedStore).
final class WidgetFeedsTests: XCTestCase {
    private let origin = Station(id: "S08409", name: "Roma Termini", railwayCode: "S08409")
    private let destination = Station(id: "S06421", name: "Firenze Santa Maria Novella", railwayCode: "S06421")

    /// 2026-11-02 (a Monday, Central European Time) at `hour`:`minute` in Italy.
    private func at(_ hour: Int, _ minute: Int) throws -> Date {
        let day = try XCTUnwrap(PlannedLocalDate(iso: "2026-11-02"))
        return try XCTUnwrap(ItalianWallClock.instant(date: day, time: try XCTUnwrap(PlannedLocalTime(hour: hour, minute: minute))))
    }

    private func service(_ trip: String, _ hour: Int, _ minute: Int, category: String = "FR") -> TrainService {
        let time = LiveText.padded(hour, width: 2) + ":" + LiveText.padded(minute, width: 2)
        return TrainService(
            id: "trenitalia-gtfs:\(trip):20261102:\(hour * 3_600 + minute * 60)", number: trip.replacingOccurrences(of: "T", with: "95"),
            operatorName: "Trenitalia", category: category, destination: "Milano Centrale", scheduledDeparture: time,
            expectedDeparture: time, status: OperationalState.programmed.rawValue, platform: nil, delayMinutes: 0
        )
    }

    func testTheRouteWidgetPagesTheTimetableToItsRangeWithTheArrivals() async throws {
        let services = [service("T1", 8, 0), service("T2", 8, 30, category: "RV"), service("T3", 9, 0), service("T4", 9, 30), service("T5", 13, 0)]
        let repository = PagedRepository(services: services, pageSize: 2)
        let trips = FixedTrips(stops: [
            TimetableTripKey(sourceID: "trenitalia-gtfs", tripID: "T1"): TimetableTripStops(stops: [
                .init(stopID: "a", sequence: 1, seconds: 8 * 3_600, catalogStationID: "S08409"),
                .init(stopID: "b", sequence: 2, seconds: 8 * 3_600 + 2_400, catalogStationID: "S00001"),
                .init(stopID: "c", sequence: 3, seconds: 9 * 3_600 + 35 * 60, catalogStationID: "S06421"),
            ], agencyTimeZones: ["Europe/Rome"]),
        ])
        let now = try at(7, 50)
        let snapshot = await WidgetFeeds.route(
            origin: origin, destination: destination, direction: .outbound, timeRange: .next3Hours, now: now,
            repository: repository, trips: trips, live: nil
        )
        // The first page and one more (three hours ask for one extra page): T5 is never asked.
        XCTAssertEqual(["T1", "T2", "T3", "T4"].map { "trenitalia-gtfs:\($0)" }, snapshot.rows.map { String($0.id.prefix(18)) })
        XCTAssertEqual(try at(8, 0), snapshot.rows[0].departure)
        XCTAssertEqual(try at(9, 35), snapshot.rows[0].arrival)
        XCTAssertNil(snapshot.rows[1].arrival)
        XCTAssertEqual(.highSpeed, snapshot.rows[0].group)
        XCTAssertEqual(.regionalSuburban, snapshot.rows[1].group)
        XCTAssertNil(snapshot.failure)
        XCTAssertEqual([], snapshot.liveSources)

        // The return direction asks the other way round.
        let back = await WidgetFeeds.route(
            origin: origin, destination: destination, direction: .return, timeRange: .next, now: now,
            repository: repository, trips: nil, live: nil
        )
        XCTAssertEqual(.return, back.direction)
        XCTAssertEqual("S06421", repository.askedOrigins.last)

        // «In viaggio» until the arrival, with «fino all'arrivo».
        let listed = WidgetRouteRules.visible(
            snapshot.rows, run: { $0.run }, now: try at(9, 0), excluded: [], untilArrival: true, timeRange: .next
        )
        XCTAssertEqual(["T1", "T3", "T4"], listed.map { String($0.row.id.dropFirst(16).prefix(2)) })
        XCTAssertEqual([true, false, false], listed.map(\.inTransit))
    }

    func testAnEndedTimetableIsTheWidgetsFailure() async throws {
        let snapshot = await WidgetFeeds.route(
            origin: origin, destination: destination, direction: .outbound, timeRange: .next, now: try at(8, 0),
            repository: EndedRepository(), trips: nil, live: nil
        )
        XCTAssertEqual(.scheduleExpired, snapshot.failure)
        XCTAssertTrue(snapshot.rows.isEmpty)
    }

    func testTheFavouritesAskedAreNearTheirRunUnlessTheUserAsks() throws {
        let train = try FavouriteTrain(
            number: "9541", operatorName: "Trenitalia", category: "FR", terminus: "Milano Centrale", scheduledDeparture: "08:30",
            bikesAllowed: nil, origin: StationIdentity(origin), destination: StationIdentity(destination), savedAt: try at(6, 0)
        )
        let late = try FavouriteTrain(
            number: "9599", operatorName: "Trenitalia", category: "FR", terminus: "Milano Centrale", scheduledDeparture: "20:30",
            bikesAllowed: nil, origin: StationIdentity(origin), destination: StationIdentity(destination), savedAt: try at(6, 0)
        )
        let automatic = WidgetFeeds.trainsToAsk([train, late], statuses: [:], now: try at(8, 0), automatic: true)
        XCTAssertEqual(["9541"], automatic.map { $0.train.number })
        XCTAssertEqual("2026-11-02", automatic.first?.day.iso)
        let tapped = WidgetFeeds.trainsToAsk([train, late], statuses: [:], now: try at(8, 0), automatic: false)
        XCTAssertEqual(["9541", "9599"], tapped.map { $0.train.number })

        let timetable = WidgetFeeds.timetableStatus(late, now: try at(8, 0))
        XCTAssertFalse(timetable.operationalVerified)
        XCTAssertEqual(try at(20, 30), timetable.departure)
        XCTAssertEqual("Roma Termini", timetable.origin)
    }

    func testTheSharedStoreKeepsWhatTheWidgetsLoaded() throws {
        let suite = "it.binario.tests.widgets.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = WidgetSharedStore(defaults: defaults)
        XCTAssertTrue(store.liveDataEnabled, "on unless the app wrote false")
        store.setLiveDataEnabled(false)
        XCTAssertFalse(store.liveDataEnabled)
        XCTAssertNil(store.scheduleFileName)
        store.setScheduleFileName("../escape.sqlite")
        XCTAssertNil(store.scheduleFileName, "a file name, never a path")
        store.setScheduleFileName("binario-gtfs-0123456789abcdef.sqlite")
        XCTAssertEqual("binario-gtfs-0123456789abcdef.sqlite", store.scheduleFileName)

        let now = try at(8, 0)
        let snapshot = WidgetRouteSnapshot(
            originID: "S08409", destinationID: "S06421", direction: .return, timeRange: .wholeDay, rows: [],
            retrievedAt: now, liveSources: ["ViaggiaTreno"], liveUnavailable: false, failure: nil
        )
        store.saveRouteSnapshot(snapshot, key: "k")
        XCTAssertEqual(snapshot, store.routeSnapshot(key: "k"))
        store.recordAttempt(key: "k", at: now)
        XCTAssertEqual(now, store.lastAttempt(key: "k"))
        store.setRowOffset(5, key: "k")
        XCTAssertEqual(5, store.rowOffset(key: "k"))
        XCTAssertEqual(.outbound, store.direction(routeKey: "k").shown)
        store.saveDirection(WidgetDirectionState(shown: .return, manual: .return, manualUntil: now), routeKey: "k")
        XCTAssertEqual(.return, store.direction(routeKey: "k").manual)
        store.saveNearest(WidgetNearestState(station: nil, note: .permissionDenied, at: now), configuredStationID: "S08409")
        XCTAssertEqual(.permissionDenied, store.nearest(configuredStationID: "S08409")?.note)
        store.saveNearest(nil, configuredStationID: "S08409")
        XCTAssertNil(store.nearest(configuredStationID: "S08409"))
    }
}

/// The trains of `services` from the query's time on, `pageSize` at a time; records the origins asked.
private final class PagedRepository: JourneyRepository, @unchecked Sendable {
    let services: [TrainService]
    let pageSize: Int
    private let lock = NSLock()
    private var origins: [String] = []

    init(services: [TrainService], pageSize: Int) {
        self.services = services
        self.pageSize = pageSize
    }

    var askedOrigins: [String] {
        lock.lock()
        defer { lock.unlock() }
        return origins
    }

    /// Synchronous: a lock is never taken across a suspension point.
    private func record(_ origin: String) {
        lock.lock()
        origins.append(origin)
        lock.unlock()
    }

    func journeys(for query: JourneyQuery) async -> JourneyResult {
        record(query.origin.id)
        let page = services.filter { service in
            guard let departure = WidgetFeeds.departure(of: service, reference: query.departure) else { return false }
            return departure >= query.departure
        }.prefix(pageSize)
        guard !page.isEmpty else { return .unavailable(.noJourney) }
        return .available(JourneySnapshot(
            query: query, generatedAt: query.departure, expiresAt: query.departure.addingTimeInterval(3_600),
            operationalState: .programmed,
            provenance: DataProvenance(
                producer: .iosApp, nature: .programmed, sourceLabel: "Test", observedAt: query.departure,
                freshness: .unknown, confidence: .staticOnly
            ),
            services: Array(page)
        ))
    }
}

private struct EndedRepository: JourneyRepository {
    func journeys(for query: JourneyQuery) async -> JourneyResult { .unavailable(.scheduleExpired) }
}

private struct FixedTrips: TimetableTripReading {
    let stops: [TimetableTripKey: TimetableTripStops]

    func tripStops(_ trips: [TimetableTripKey]) -> [TimetableTripKey: TimetableTripStops] {
        stops.filter { trips.contains($0.key) }
    }
}
