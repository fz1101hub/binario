import CSQLite
import Foundation
import XCTest
@testable import BinarioKit

/// «Partenze» from the bundled timetable (`SQLiteGTFSJourneyRepository` as `StationBoardProvider`),
/// on a small index built like the one of `SQLiteGTFSJourneyRepositoryTests`.
final class StationBoardTests: XCTestCase {
    private let rome = TimeZone(identifier: "Europe/Rome")!
    private let station = StationIdentity(id: "catalog-board", name: "Milano Centrale", railwayCode: "S01700")

    func testTheBoardListsTodaysNextTrainsInDepartureOrderNamedAsThePlannerNamesThem() async throws {
        let fixture = try BoardFixture()
        try fixture.insertTrip(id: 1, externalID: "late", number: "2155", calls: [
            BoardCall(stop: 1, seconds: 11 * 3_600), BoardCall(stop: 2, seconds: 12 * 3_600),
        ])
        try fixture.insertTrip(id: 2, externalID: "early", number: "2153", calls: [
            BoardCall(stop: 1, seconds: 9 * 3_600 + 30 * 60), BoardCall(stop: 5, seconds: 10 * 3_600),
            BoardCall(stop: 3, seconds: 11 * 3_600),
        ], bikesAllowed: 1)
        try fixture.insertTrip(id: 3, externalID: "gone", number: "2151", calls: [
            BoardCall(stop: 1, seconds: 8 * 3_600), BoardCall(stop: 2, seconds: 9 * 3_600),
        ])
        // Boarded at the station's other stop, after a call elsewhere.
        try fixture.insertTrip(id: 4, externalID: "through", number: "2157", calls: [
            BoardCall(stop: 5, seconds: 9 * 3_600), BoardCall(stop: 4, seconds: 10 * 3_600),
            BoardCall(stop: 2, seconds: 11 * 3_600),
        ], bikesAllowed: 2)

        let result = await fixture.repository.departures(for: try boardQuery("2024-04-01 09:00"))
        let page = try availablePage(result)

        XCTAssertEqual(page.departures.map(\.service.scheduledDeparture), ["09:30", "10:00", "11:00"])
        XCTAssertEqual(page.departures.map(\.service.number), ["2153", "2157", "2155"])
        XCTAssertEqual(page.departures.map { ReliableAlternatives.trainLabel($0.service) }, ["REG 2153", "REG 2157", "REG 2155"])
        // The destination is the trip's last stop, with its catalogue identity when it has one.
        XCTAssertEqual(page.departures.map(\.service.destination), ["Lugano", "Bergamo", "Bergamo"])
        XCTAssertEqual(page.departures[0].terminus, StationIdentity(id: "gtfs:source:abroad", name: "Lugano"))
        XCTAssertEqual(page.departures[1].terminus, StationIdentity(id: "catalog-terminus", name: "Bergamo", railwayCode: "S01529"))
        XCTAssertEqual(page.departures.map(\.service.bikesAllowed), [true, false, nil])
        for departure in page.departures {
            XCTAssertEqual(departure.service.status, "PROGRAMMED")
            XCTAssertEqual(departure.service.category, "REG")
            XCTAssertEqual(departure.service.operatorName, "Synthetic Rail")
            XCTAssertNil(departure.service.platform)
            XCTAssertEqual(departure.service.delayMinutes, 0)
            XCTAssertNil(departure.live)
            XCTAssertTrue(departure.isScheduled)
            // The train detail of a row is a ride from the board's station to the train's last stop.
            XCTAssertNoThrow(try JourneyQuery(origin: station, destination: departure.terminus, departure: departure.departure))
        }
        XCTAssertFalse(page.hasMore)
        XCTAssertNil(page.firstDepartureNextDay)
        XCTAssertEqual(page.attributions, ["Synthetic official feed"])
    }

    func testATripWithoutItsOwnNumberIsListedByItsRouteAndMarkedAsNoTrainNumber() async throws {
        let fixture = try BoardFixture()
        try fixture.insertTrip(id: 1, externalID: "numbered", number: "2153", calls: [
            BoardCall(stop: 1, seconds: 9 * 3_600 + 30 * 60), BoardCall(stop: 2, seconds: 10 * 3_600),
        ])
        // SNCF and Trentino trips publish no trip_short_name: the row shows the route's «C3».
        try fixture.execute("""
            INSERT INTO route VALUES (2, 1, 1, 'route-c3', 'C3', NULL, 2);
            INSERT INTO trip VALUES (2, 1, 2, 1, 'line', NULL, NULL, NULL, NULL);
            INSERT INTO stop_time VALUES (2, 1, 1, \(10 * 3_600), \(10 * 3_600), 0, 0);
            INSERT INTO stop_time VALUES (2, 2, 2, \(11 * 3_600), \(11 * 3_600), 0, 0);
            """)
        let result = await fixture.repository.departures(for: try boardQuery("2024-04-01 09:00"))
        let page = try availablePage(result)
        XCTAssertEqual(page.departures.map(\.service.number), ["2153", "C3"])
        XCTAssertEqual(page.departures.map(\.service.hasTrainNumber), [true, false])
    }

    func testTrainsGoneNotBoardingHereOrEndingHereAreNoDeparturesAndATrainCallingTwiceIsListedOnce() async throws {
        let fixture = try BoardFixture()
        try fixture.insertTrip(id: 1, externalID: "left", number: "2301", calls: [
            BoardCall(stop: 1, seconds: 8 * 3_600 + 59 * 60), BoardCall(stop: 2, seconds: 10 * 3_600),
        ])
        try fixture.insertTrip(id: 2, externalID: "arriving", number: "2302", calls: [
            BoardCall(stop: 5, seconds: 10 * 3_600), BoardCall(stop: 1, seconds: 10 * 3_600 + 30 * 60),
        ])
        try fixture.insertTrip(id: 3, externalID: "loop", number: "2303", calls: [
            BoardCall(stop: 1, seconds: 10 * 3_600), BoardCall(stop: 5, seconds: 10 * 3_600 + 20 * 60),
            BoardCall(stop: 4, seconds: 11 * 3_600),
        ])
        try fixture.insertTrip(id: 4, externalID: "no-pickup", number: "2304", calls: [
            BoardCall(stop: 1, seconds: 10 * 3_600 + 15 * 60, pickup: 1), BoardCall(stop: 2, seconds: 11 * 3_600),
        ])
        try fixture.insertTrip(id: 5, externalID: "direct", number: "2305", calls: [
            BoardCall(stop: 1, seconds: 10 * 3_600 + 20 * 60), BoardCall(stop: 2, seconds: 11 * 3_600),
        ])
        try fixture.insertTrip(id: 6, externalID: "twice", number: "2306", calls: [
            BoardCall(stop: 1, seconds: 10 * 3_600 + 40 * 60), BoardCall(stop: 5, seconds: 10 * 3_600 + 55 * 60),
            BoardCall(stop: 4, seconds: 11 * 3_600 + 10 * 60), BoardCall(stop: 2, seconds: 12 * 3_600),
        ])

        let morning = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-04-01 09:00")))
        let later = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-04-01 10:45")))

        XCTAssertEqual(morning.departures.map(\.service.number), ["2305", "2306"])
        XCTAssertEqual(morning.departures.map(\.service.scheduledDeparture), ["10:20", "10:40"])
        // After its first call the train is listed at its second one.
        XCTAssertEqual(later.departures.map(\.service.scheduledDeparture), ["11:10"])
        XCTAssertEqual(later.departures.map(\.service.number), ["2306"])
    }

    func testPagesFollowTheLastListedTrainWithoutGapsOrRepeats() async throws {
        let fixture = try BoardFixture()
        let trains: [(Int64, String, Int)] = [
            (1, "2201", 0), (2, "2204", 5), (3, "2202", 5), (4, "2203", 5), (5, "2205", 10), (6, "2206", 15), (7, "2207", 20),
        ]
        for (id, number, minutes) in trains {
            try fixture.insertTrip(id: id, externalID: "trip-\(id)", number: number, calls: [
                BoardCall(stop: 1, seconds: 10 * 3_600 + minutes * 60), BoardCall(stop: 2, seconds: 11 * 3_600 + minutes * 60),
            ])
        }

        let whole = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-04-01 09:00")))
        let first = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-04-01 09:00", limit: 3)))
        let firstLast = try XCTUnwrap(first.departures.last)
        let second = try availablePage(await fixture.repository.departures(for: try StationBoardQuery(
            station: station, from: firstLast.departure, limit: 3, after: firstLast.cursor
        )))
        let secondLast = try XCTUnwrap(second.departures.last)
        let third = try availablePage(await fixture.repository.departures(for: try StationBoardQuery(
            station: station, from: secondLast.departure, limit: 3, after: secondLast.cursor
        )))

        XCTAssertEqual(whole.departures.map(\.service.number), ["2201", "2202", "2203", "2204", "2205", "2206", "2207"])
        XCTAssertEqual(first.departures.map(\.service.number), ["2201", "2202", "2203"])
        XCTAssertTrue(first.hasMore)
        XCTAssertEqual(second.departures.map(\.service.number), ["2204", "2205", "2206"])
        XCTAssertTrue(second.hasMore)
        XCTAssertEqual(third.departures.map(\.service.number), ["2207"])
        XCTAssertFalse(third.hasMore)
        XCTAssertEqual((first.departures + second.departures + third.departures).map(\.id), whole.departures.map(\.id))
        // A later page never gives «primo domani»: only a first page without trains does.
        XCTAssertNil(third.firstDepartureNextDay)
    }

    func testYesterdaysRunsStillToLeaveAfterMidnightJoinTodaysServiceDay() async throws {
        let fixture = try BoardFixture()
        // Monday's service day (1 April): one run left on Monday evening, one leaves at 24:30.
        try fixture.insertTrip(id: 1, externalID: "evening", number: "2401", service: 1, calls: [
            BoardCall(stop: 1, seconds: 23 * 3_600), BoardCall(stop: 2, seconds: 24 * 3_600),
        ])
        try fixture.insertTrip(id: 2, externalID: "night", number: "2402", service: 1, calls: [
            BoardCall(stop: 1, seconds: 24 * 3_600 + 30 * 60), BoardCall(stop: 2, seconds: 25 * 3_600),
        ])
        // Tuesday's service day (2 April), including a run past its own midnight.
        try fixture.insertTrip(id: 3, externalID: "morning", number: "2403", service: 3, calls: [
            BoardCall(stop: 1, seconds: 5 * 3_600), BoardCall(stop: 2, seconds: 6 * 3_600),
        ])
        try fixture.insertTrip(id: 4, externalID: "late-night", number: "2404", service: 3, calls: [
            BoardCall(stop: 1, seconds: 24 * 3_600 + 15 * 60), BoardCall(stop: 2, seconds: 25 * 3_600),
        ])

        let afterMidnight = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-04-02 00:10")))
        let afterTheNightRun = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-04-02 00:40")))

        XCTAssertEqual(afterMidnight.departures.map(\.service.number), ["2402", "2403", "2404"])
        XCTAssertEqual(afterMidnight.departures.map(\.service.scheduledDeparture), ["00:30", "05:00", "00:15"])
        XCTAssertTrue(afterMidnight.departures[0].id.contains(":20240401:"), "yesterday's service day")
        XCTAssertTrue(afterMidnight.departures[2].id.contains(":20240402:"), "today's service day past midnight")
        let tuesdayNight = try XCTUnwrap(romeCalendar.date(from: DateComponents(year: 2024, month: 4, day: 3, hour: 0, minute: 15)))
        XCTAssertEqual(afterMidnight.departures[2].departure, tuesdayNight)
        XCTAssertEqual(afterTheNightRun.departures.map(\.service.number), ["2403", "2404"])
    }

    func testTheFirstHoursOfTomorrowsServiceDayFollowTonightsLastTrains() async throws {
        let fixture = try BoardFixture()
        // Monday's service day (1 April) ends at 23:53; Tuesday's (2 April) starts at 00:02, as a
        // feed whose service day starts at midnight (Trenord, the Swiss pack) publishes its night trains.
        try fixture.insertTrip(id: 1, externalID: "last-tonight", number: "797", service: 1, calls: [
            BoardCall(stop: 1, seconds: 23 * 3_600 + 53 * 60), BoardCall(stop: 2, seconds: 24 * 3_600 + 20 * 60),
        ])
        try fixture.insertTrip(id: 2, externalID: "after-midnight", number: "801", service: 3, calls: [
            BoardCall(stop: 1, seconds: 2 * 60), BoardCall(stop: 2, seconds: 30 * 60),
        ])
        try fixture.insertTrip(id: 3, externalID: "night", number: "701", service: 3, calls: [
            BoardCall(stop: 1, seconds: 23 * 60), BoardCall(stop: 2, seconds: 50 * 60),
        ])
        // Too far into Tuesday for Monday night's board.
        try fixture.insertTrip(id: 4, externalID: "dawn", number: "2503", service: 3, calls: [
            BoardCall(stop: 1, seconds: 5 * 3_600), BoardCall(stop: 2, seconds: 6 * 3_600),
        ])

        let evening = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-04-01 23:40")))
        XCTAssertEqual(evening.departures.map(\.service.number), ["797", "801", "701"])
        XCTAssertEqual(evening.departures.map(\.service.scheduledDeparture), ["23:53", "00:02", "00:23"])
        XCTAssertTrue(evening.departures[1].id.contains(":20240402:"), "tomorrow's service day")
        let tuesday = try XCTUnwrap(romeCalendar.date(from: DateComponents(year: 2024, month: 4, day: 2, hour: 0, minute: 2)))
        XCTAssertEqual(evening.departures[1].departure, tuesday)
        XCTAssertFalse(evening.hasMore, "05:00 is beyond the next service day's first hours")
        XCTAssertNil(evening.firstDepartureNextDay)

        // «Più tardi» goes on after midnight without a gap or a repeat.
        let first = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-04-01 23:40", limit: 2)))
        XCTAssertEqual(first.departures.map(\.service.number), ["797", "801"])
        XCTAssertTrue(first.hasMore)
        let last = try XCTUnwrap(first.departures.last)
        let second = try availablePage(await fixture.repository.departures(for: try StationBoardQuery(
            station: station, from: last.departure, limit: 2, after: last.cursor
        )))
        XCTAssertEqual(second.departures.map(\.service.number), ["701", "2503"])
        XCTAssertFalse(second.hasMore)

        // Earlier in the evening the night trains are too far ahead: tonight's train alone.
        let early = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-04-01 19:00")))
        XCTAssertEqual(early.departures.map(\.service.number), ["797"])
        XCTAssertFalse(early.hasMore)
        XCTAssertEqual(SQLiteGTFSJourneyRepository.boardNextServiceDayHorizon, 4 * 60 * 60)
    }

    func testTheServiceDayFollowsTheTimetableCalendarAcrossRomeDST() async throws {
        let fixture = try BoardFixture()
        // Saturday 30 March 26:30 is 03:30 on the Sunday clocks go forward.
        try fixture.insertTrip(id: 1, externalID: "night", number: "N 42", service: 4, calls: [
            BoardCall(stop: 1, seconds: 26 * 3_600 + 30 * 60), BoardCall(stop: 2, seconds: 27 * 3_600 + 30 * 60),
        ])

        let page = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-03-31 03:00")))

        XCTAssertEqual(page.departures.map(\.service.number), ["N 42"])
        XCTAssertEqual(page.departures.map(\.service.scheduledDeparture), ["03:30"])
    }

    func testTomorrowsTrainsAfterTheNightAreNotListedAndTheFirstOneIsGivenWhenNoneIsLeft() async throws {
        let fixture = try BoardFixture()
        try fixture.insertTrip(id: 1, externalID: "today", number: "2501", service: 1, calls: [
            BoardCall(stop: 1, seconds: 8 * 3_600), BoardCall(stop: 2, seconds: 9 * 3_600),
        ])
        try fixture.insertTrip(id: 2, externalID: "tomorrow-later", number: "2503", service: 3, calls: [
            BoardCall(stop: 1, seconds: 7 * 3_600), BoardCall(stop: 2, seconds: 8 * 3_600),
        ])
        try fixture.insertTrip(id: 3, externalID: "tomorrow-first", number: "2502", service: 3, calls: [
            BoardCall(stop: 1, seconds: 6 * 3_600 + 15 * 60), BoardCall(stop: 2, seconds: 7 * 3_600),
        ])

        let morning = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-04-01 07:00")))
        let evening = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-04-01 21:00")))

        XCTAssertEqual(morning.departures.map(\.service.number), ["2501"])
        XCTAssertNil(morning.firstDepartureNextDay)
        XCTAssertEqual(evening.departures, [])
        XCTAssertFalse(evening.hasMore)
        XCTAssertEqual(evening.firstDepartureNextDay?.service.number, "2502")
        XCTAssertEqual(evening.firstDepartureNextDay?.service.scheduledDeparture, "06:15")
        XCTAssertEqual(evening.attributions, ["Synthetic official feed"])
    }

    func testAFeedEndedWhileAnotherGoesOnListsTheRestWithTheExpiredNotice() async throws {
        let fixture = try BoardFixture()
        try fixture.insertTrip(id: 1, externalID: "still-running", number: "2701", calls: [
            BoardCall(stop: 1, seconds: 10 * 3_600), BoardCall(stop: 2, seconds: 11 * 3_600),
        ])
        let alone = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-04-01 09:00")))
        XCTAssertFalse(alone.someTimetablesEnded, "every feed of the station goes on")

        // The pack that ended on 31 March also served the board's station.
        try fixture.execute("INSERT INTO stop VALUES (7, 2, 'board-old', 'Milano Centrale', NULL, NULL, 'catalog-board', 'S01700', NULL);")
        let page = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-04-01 09:00")))
        XCTAssertEqual(page.departures.map(\.service.number), ["2701"], "the trains still covered are listed")
        XCTAssertTrue(page.someTimetablesEnded, "and «Orari scaduti» says why others are missing")
        let before = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-03-31 09:00")))
        XCTAssertFalse(before.someTimetablesEnded, "on its last day the pack still runs")
    }

    func testUnservedStationsEndedTimetablesAndInvalidIndexesFailClosed() async throws {
        let fixture = try BoardFixture()
        try fixture.insertTrip(id: 1, externalID: "direct", number: "2601", calls: [
            BoardCall(stop: 1, seconds: 10 * 3_600), BoardCall(stop: 2, seconds: 11 * 3_600),
        ])
        let unserved = StationIdentity(id: "never-indexed", name: "Napoli Centrale")
        let ended = StationIdentity(id: "catalog-old", name: "Chiusa")
        let impostor = StationIdentity(id: "catalog-board", name: "Milano Centrale", railwayCode: "S09999")

        let unservedResult = await fixture.repository.departures(for: try boardQuery("2024-04-01 09:00", station: unserved))
        let impostorResult = await fixture.repository.departures(for: try boardQuery("2024-04-01 09:00", station: impostor))
        let endedResult = await fixture.repository.departures(for: try boardQuery("2024-04-01 09:00", station: ended))
        let afterTheIndex = await fixture.repository.departures(for: try boardQuery("2025-01-15 09:00"))
        XCTAssertEqual(unservedResult, .unavailable(.noJourney))
        XCTAssertEqual(impostorResult, .unavailable(.noJourney))
        XCTAssertEqual(endedResult, .unavailable(.scheduleExpired))
        XCTAssertEqual(afterTheIndex, .unavailable(.scheduleExpired))

        let malformedURL = FileManager.default.temporaryDirectory.appendingPathComponent("BinarioBoard-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: malformedURL) }
        try Data("not sqlite".utf8).write(to: malformedURL)
        let malformed = await SQLiteGTFSJourneyRepository(databaseURL: malformedURL).departures(for: try boardQuery("2024-04-01 09:00"))
        XCTAssertEqual(malformed, .unavailable(.providerInvalid))
        let failClosed = await FailClosedStationBoardProvider().departures(for: try boardQuery("2024-04-01 09:00"))
        XCTAssertEqual(failClosed, .unavailable(.noAuthorizedProvider))
    }

    func testQueriesAreBoundedAndInvalidStationsAreRefused() async throws {
        let from = try date("2024-04-01 09:00")
        XCTAssertEqual(try StationBoardQuery(station: station, from: from, limit: 0).limit, 1)
        XCTAssertEqual(try StationBoardQuery(station: station, from: from, limit: 500).limit, StationBoardQuery.maximumPageSize)
        XCTAssertThrowsError(try StationBoardQuery(station: StationIdentity(id: "", name: "Milano Centrale"), from: from, limit: 20))
        XCTAssertThrowsError(try StationBoardQuery(station: StationIdentity(id: "catalog-board", name: "Milano  Centrale"), from: from, limit: 20))
        XCTAssertEqual(StationBoardQuery.minuteStart(of: from.addingTimeInterval(42)), from)

        let fixture = try BoardFixture()
        for index in 0..<55 {
            let seconds = 10 * 3_600 + index * 60
            try fixture.insertTrip(id: Int64(index + 1), externalID: "trip-\(index)", number: "\(3_000 + index)", calls: [
                BoardCall(stop: 1, seconds: seconds), BoardCall(stop: 2, seconds: seconds + 3_600),
            ])
        }
        let page = try availablePage(await fixture.repository.departures(for: try boardQuery("2024-04-01 09:00", limit: 500)))
        XCTAssertEqual(page.departures.count, StationBoardQuery.maximumPageSize)
        XCTAssertEqual(page.departures.first?.service.scheduledDeparture, "10:00")
        XCTAssertEqual(page.departures.last?.service.scheduledDeparture, "10:49")
        XCTAssertTrue(page.hasMore)
    }

    func testARowIsScheduledUnlessALiveSourceObservedItAndLeavesAMinuteAfterItsDeparture() throws {
        let departure = try date("2024-04-01 10:00")
        let service = TrainService(
            id: "source:trip:20240401:36000", number: "2153", operatorName: "Trenord", category: "REG",
            destination: "Bergamo", scheduledDeparture: "10:00", expectedDeparture: "10:00",
            status: "PROGRAMMED", platform: nil, delayMinutes: 0
        )
        let terminus = StationIdentity(id: "catalog-terminus", name: "Bergamo")
        let scheduled = StationBoardDeparture(departure: departure, service: service, terminus: terminus)
        let observed = StationBoardDeparture(departure: departure, service: service, terminus: terminus, live: StationBoardLiveStatus(
            expectedDeparture: departure.addingTimeInterval(4 * 60), delayMinutes: 4, cancelled: false,
            platform: "5", sourceLabel: "Live source", observedAt: departure.addingTimeInterval(-120)
        ))

        XCTAssertTrue(scheduled.isScheduled)
        XCTAssertFalse(observed.isScheduled)
        XCTAssertTrue(scheduled.stillToLeave(at: departure.addingTimeInterval(59)))
        XCTAssertFalse(scheduled.stillToLeave(at: departure.addingTimeInterval(60)))
        XCTAssertEqual(scheduled.cursor, StationBoardCursor(departure: departure, number: "2153", id: service.id))
        XCTAssertLessThan(
            StationBoardCursor(departure: departure, number: "2153", id: "b"),
            StationBoardCursor(departure: departure, number: "2154", id: "a")
        )
    }

    func testTheLastStationIsRememberedOnThisIPhoneOnlyWhenItIsValid() throws {
        let suite = "it.binario.ios.tests.board.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = StationBoardStationStore(defaults: defaults)
        XCTAssertNil(store.load())

        store.save(station)
        XCTAssertEqual(store.load(), station)
        XCTAssertNotNil(defaults.data(forKey: StationBoardStationStore.storageKey))
        XCTAssertEqual(StationBoardStationStore(defaults: defaults).load(), station)

        // An invalid identity is never written over the remembered one.
        store.save(StationIdentity(id: "", name: "Nowhere"))
        XCTAssertEqual(store.load(), station)

        for payload in [
            Data("not-json".utf8),
            Data(#"{"id":"","name":"Milano Centrale"}"#.utf8),
            Data(#"{"id":"catalog-board","name":" Milano Centrale"}"#.utf8),
            Data(repeating: 0x20, count: StationBoardStationStore.maximumStoredBytes + 1),
        ] {
            defaults.set(payload, forKey: StationBoardStationStore.storageKey)
            XCTAssertNil(store.load())
        }
    }

    private var romeCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = rome
        return calendar
    }

    private func boardQuery(_ value: String, limit: Int = 20, station: StationIdentity? = nil) throws -> StationBoardQuery {
        try StationBoardQuery(station: station ?? self.station, from: date(value), limit: limit)
    }

    private func availablePage(_ result: StationBoardResult, file: StaticString = #filePath, line: UInt = #line) throws -> StationBoardPage {
        guard case let .available(page) = result else {
            XCTFail("Expected a board page, got \(result)", file: file, line: line)
            throw BoardFixtureError.noPage
        }
        return page
    }

    private func date(_ value: String) throws -> Date {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = rome
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return try XCTUnwrap(formatter.date(from: value))
    }
}

/// One call of a fixture trip: its stop, its GTFS second (arrival and departure) and boarding rules.
private struct BoardCall {
    let stop: Int64
    let seconds: Int
    var pickup = 0
    var dropOff = 0
}

/// A small valid index: pack 1 (until 31 December 2024) with four one-day services from Saturday
/// 30 March 2024 (1 Monday 1 April, 2 Sunday 31 March, 3 Tuesday 2 April, 4 Saturday 30 March),
/// the board station's two stops (1 and 4), Bergamo (2), an uncatalogued Lugano (3) and Monza (5);
/// pack 2, ended on 31 March, with one stop of another station (6).
private final class BoardFixture {
    let url: URL
    let repository: SQLiteGTFSJourneyRepository
    private var database: OpaquePointer?

    init() throws {
        url = FileManager.default.temporaryDirectory.appendingPathComponent("BinarioBoard-\(UUID().uuidString).sqlite")
        repository = SQLiteGTFSJourneyRepository(databaseURL: url)
        guard sqlite3_open(url.path, &database) == SQLITE_OK else { throw BoardFixtureError.sqlite }
        try execute(Self.schema)
        try execute("PRAGMA application_id = \(SQLiteGTFSJourneyRepository.applicationID); PRAGMA user_version = \(SQLiteGTFSJourneyRepository.userVersion);")
        try execute("""
            INSERT INTO metadata VALUES ('schema_version', '\(SQLiteGTFSJourneyRepository.userVersion)');
            INSERT INTO metadata VALUES ('input_fingerprint_sha256', '\(String(repeating: "a", count: 64))');
            INSERT INTO metadata VALUES ('source_generated_at', '2024-03-30');
            INSERT INTO metadata VALUES ('row_counts_json', '{}');
            INSERT INTO pack VALUES (1, 'source', 'Synthetic official feed', 20241231);
            INSERT INTO pack VALUES (2, 'ended', 'Ended feed', 20240331);
            INSERT INTO agency VALUES (1, 1, 'agency', 'Synthetic Rail', 'Europe/Rome');
            INSERT INTO route VALUES (1, 1, 1, 'route', 'REG', NULL, 2);
            INSERT INTO service VALUES (1, 1, 'monday', 20240330, 4, X'04');
            INSERT INTO service VALUES (2, 1, 'sunday', 20240330, 4, X'02');
            INSERT INTO service VALUES (3, 1, 'tuesday', 20240330, 4, X'08');
            INSERT INTO service VALUES (4, 1, 'saturday', 20240330, 4, X'01');
            INSERT INTO stop VALUES (1, 1, 'board', 'Milano Centrale', NULL, NULL, 'catalog-board', 'S01700', NULL);
            INSERT INTO stop VALUES (2, 1, 'terminus', 'Bergamo', NULL, NULL, 'catalog-terminus', 'S01529', NULL);
            INSERT INTO stop VALUES (3, 1, 'abroad', 'Lugano', NULL, NULL, NULL, NULL, NULL);
            INSERT INTO stop VALUES (4, 1, 'board-low', 'Milano Centrale Bassa', NULL, NULL, 'catalog-board', 'S01700', NULL);
            INSERT INTO stop VALUES (5, 1, 'middle', 'Monza', NULL, NULL, 'catalog-middle', 'S01322', NULL);
            INSERT INTO stop VALUES (6, 2, 'old', 'Chiusa', NULL, NULL, 'catalog-old', 'S02040', NULL);
            """)
    }

    deinit {
        if let database { sqlite3_close(database) }
        try? FileManager.default.removeItem(at: url)
    }

    func insertTrip(id: Int64, externalID: String, number: String, service: Int64 = 1, calls: [BoardCall], bikesAllowed: Int? = nil) throws {
        let bikes = bikesAllowed.map { String($0) } ?? "NULL"
        var sql = "INSERT INTO trip VALUES (\(id), 1, 1, \(service), '\(externalID)', NULL, '\(number)', NULL, \(bikes));"
        for (index, call) in calls.enumerated() {
            sql += "INSERT INTO stop_time VALUES (\(id), \(index + 1), \(call.stop), \(call.seconds), \(call.seconds), \(call.pickup), \(call.dropOff));"
        }
        try execute(sql)
    }

    func execute(_ sql: String) throws {
        guard let database, sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else { throw BoardFixtureError.sqlite }
    }

    private static let schema = """
        CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL) WITHOUT ROWID;
        CREATE TABLE pack (id INTEGER PRIMARY KEY, source_id TEXT NOT NULL UNIQUE, attribution TEXT NOT NULL, last_service_date INTEGER NOT NULL);
        CREATE TABLE agency (id INTEGER PRIMARY KEY, pack_id INTEGER NOT NULL REFERENCES pack(id), external_id TEXT NOT NULL, name TEXT NOT NULL, timezone TEXT NOT NULL, UNIQUE(pack_id, external_id));
        CREATE TABLE route (id INTEGER PRIMARY KEY, pack_id INTEGER NOT NULL REFERENCES pack(id), agency_id INTEGER NOT NULL REFERENCES agency(id), external_id TEXT NOT NULL, short_name TEXT, long_name TEXT, route_type INTEGER NOT NULL, UNIQUE(pack_id, external_id));
        CREATE TABLE service (id INTEGER PRIMARY KEY, pack_id INTEGER NOT NULL REFERENCES pack(id), external_id TEXT NOT NULL, start_date INTEGER NOT NULL, day_count INTEGER NOT NULL CHECK(day_count > 0), active_days BLOB NOT NULL, UNIQUE(pack_id, external_id), CHECK(length(active_days) = (day_count + 7) / 8));
        CREATE TABLE trip (id INTEGER PRIMARY KEY, pack_id INTEGER NOT NULL REFERENCES pack(id), route_id INTEGER NOT NULL REFERENCES route(id), service_id INTEGER NOT NULL REFERENCES service(id), external_id TEXT NOT NULL, headsign TEXT, short_name TEXT, direction_id INTEGER, bikes_allowed INTEGER CHECK(bikes_allowed IN (1, 2)), UNIQUE(pack_id, external_id));
        CREATE TABLE stop (id INTEGER PRIMARY KEY, pack_id INTEGER NOT NULL REFERENCES pack(id), external_id TEXT NOT NULL, name TEXT NOT NULL, latitude REAL, longitude REAL, catalog_station_id TEXT, railway_code TEXT, trenitalia_location_id TEXT, UNIQUE(pack_id, external_id));
        CREATE TABLE stop_time (trip_id INTEGER NOT NULL REFERENCES trip(id), stop_sequence INTEGER NOT NULL, stop_id INTEGER NOT NULL REFERENCES stop(id), arrival_seconds INTEGER, departure_seconds INTEGER, pickup_type INTEGER NOT NULL, drop_off_type INTEGER NOT NULL, PRIMARY KEY(trip_id, stop_sequence)) WITHOUT ROWID;
        CREATE UNIQUE INDEX idx_route_pack_external ON route(pack_id, external_id);
        CREATE UNIQUE INDEX idx_service_pack_external ON service(pack_id, external_id);
        CREATE UNIQUE INDEX idx_trip_pack_external ON trip(pack_id, external_id);
        CREATE INDEX idx_trip_route_service ON trip(route_id, service_id);
        CREATE UNIQUE INDEX idx_stop_pack_external ON stop(pack_id, external_id);
        CREATE INDEX idx_stop_catalog_station ON stop(catalog_station_id) WHERE catalog_station_id IS NOT NULL;
        CREATE INDEX idx_stop_time_stop_departure ON stop_time(stop_id, departure_seconds, trip_id);
        """
}

private enum BoardFixtureError: Error { case sqlite, noPage }
