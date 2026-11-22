import Foundation
import XCTest
@testable import BinarioKit

/// The base index with a full foreign timetable installed beside it: two synthetic files read through
/// separate read-only connections, merged by time, the border subset of the installed pack skipped.
///
/// Base: «sta-gtfs» (Bolzano, Brennero, Innsbruck) and the ÖBB border subset «oebb-gtfs» (Brennero,
/// Innsbruck in the zone, Wien outside it without a catalogue identity). Foreign: the full ÖBB index.
final class SQLiteGTFSForeignFilesTests: XCTestCase {
    private let rome = TimeZone(identifier: "Europe/Rome")!
    private let bolzano = StationIdentity(id: "TEST-BOLZANO", name: "Bolzano")
    private let brennero = StationIdentity(id: "TEST-BRENNERO", name: "Brennero")
    private let innsbruck = StationIdentity(id: "TEST-INNSBRUCK", name: "Innsbruck Hbf")
    private let wien = StationIdentity(id: "TEST-WIEN", name: "Wien Hbf")

    func testWithoutForeignFilesTheBaseIndexAnswersAsBefore() async throws {
        let files = try Files()
        let repository = SQLiteGTFSJourneyRepository(databaseURL: files.base.url)

        let result = await repository.journeys(for: try query(brennero, innsbruck))
        guard case let .available(snapshot) = result else { return XCTFail("expected trains, got \(result)") }
        XCTAssertEqual(snapshot.services.map(\.number), ["REG 1", "EC 80 subset", "REG 2"])
        let wienResult = await repository.journeys(for: try query(brennero, wien))
        XCTAssertEqual(wienResult, .unavailable(.noJourney), "Wien lies outside the border zone")
        XCTAssertFalse(try repository.coveredCatalogStationIDs().contains("TEST-WIEN"))
        XCTAssertEqual(try repository.sourcePacks().map(\.sourceID), ["sta-gtfs", "oebb-gtfs"])
        XCTAssertEqual(try repository.sourcePacks().map(\.tripCount), [3, 1])
        let board = await repository.departures(for: try boardQuery(wien))
        XCTAssertEqual(board, .unavailable(.noJourney))
    }

    func testAnInstalledFullTimetableIsMergedByTimeAndReplacesItsSubset() async throws {
        let files = try Files()
        let repository = SQLiteGTFSJourneyRepository(
            databaseURL: files.base.url,
            foreignDatabaseURLs: ["oebb-gtfs": files.foreign.url]
        )

        let result = await repository.journeys(for: try query(brennero, innsbruck))
        guard case let .available(snapshot) = result else { return XCTFail("expected trains, got \(result)") }
        // The subset's copy of EC 80 is never read; the full one is, between the base's trains.
        XCTAssertEqual(snapshot.services.map(\.number), ["REG 1", "RJ 4", "EC 80", "REG 2"])
        XCTAssertEqual(snapshot.services.map(\.scheduledDeparture), ["10:00", "10:15", "10:30", "10:45"])
        XCTAssertEqual(snapshot.provenance.sourceLabel, "Feed sta-gtfs + Feed oebb-gtfs")

        let toWien = await repository.journeys(for: try query(brennero, wien))
        guard case let .available(wienSnapshot) = toWien else { return XCTFail("expected EC 80 to Wien, got \(toWien)") }
        XCTAssertEqual(wienSnapshot.services.map(\.number), ["EC 80"])
        XCTAssertEqual(wienSnapshot.provenance.sourceLabel, "Feed oebb-gtfs")

        XCTAssertTrue(try repository.coveredCatalogStationIDs().contains("TEST-WIEN"))
        let packs = try repository.sourcePacks()
        XCTAssertEqual(packs.map(\.sourceID), ["sta-gtfs", "oebb-gtfs"], "the full pack takes the subset's place")
        XCTAssertEqual(packs.map(\.tripCount), [3, 3])
    }

    func testTheMergedResultsAreBoundedAtEight() async throws {
        let files = try Files(extraTrips: 6)
        let repository = SQLiteGTFSJourneyRepository(
            databaseURL: files.base.url,
            foreignDatabaseURLs: ["oebb-gtfs": files.foreign.url]
        )

        let result = await repository.journeys(for: try query(brennero, innsbruck))
        guard case let .available(snapshot) = result else { return XCTFail("expected trains, got \(result)") }
        XCTAssertEqual(snapshot.services.count, SQLiteGTFSJourneyRepository.maximumResults)
        XCTAssertEqual(
            snapshot.services.map(\.scheduledDeparture),
            ["10:00", "10:15", "10:30", "10:45", "11:05", "11:10", "11:15", "11:20"]
        )
    }

    func testTheBoardMergesBothFilesWithoutTheSubsetsCopy() async throws {
        let files = try Files()
        let repository = SQLiteGTFSJourneyRepository(
            databaseURL: files.base.url,
            foreignDatabaseURLs: ["oebb-gtfs": files.foreign.url]
        )

        let result = await repository.departures(for: try boardQuery(brennero))
        guard case let .available(page) = result else { return XCTFail("expected a board, got \(result)") }
        XCTAssertEqual(page.departures.map(\.service.number), ["REG 1", "RJ 4", "EC 80", "REG 2"])
        XCTAssertFalse(page.hasMore)
        XCTAssertEqual(page.attributions, ["Feed sta-gtfs", "Feed oebb-gtfs"])
        let ec80 = try XCTUnwrap(page.departures.first { $0.service.number == "EC 80" })
        XCTAssertEqual(ec80.terminus.id, "TEST-WIEN", "the full timetable names the real terminus")

        let small = await repository.departures(for: try boardQuery(brennero, limit: 2))
        guard case let .available(first) = small else { return XCTFail("expected a page, got \(small)") }
        XCTAssertEqual(first.departures.map(\.service.number), ["REG 1", "RJ 4"])
        XCTAssertTrue(first.hasMore)
        let last = try XCTUnwrap(first.departures.last)
        let later = await repository.departures(for: try StationBoardQuery(station: brennero, from: last.departure, limit: 2, after: last.cursor))
        guard case let .available(second) = later else { return XCTFail("expected «Più tardi», got \(later)") }
        XCTAssertEqual(second.departures.map(\.service.number), ["EC 80", "REG 2"])

        // The base index lists no train leaving Innsbruck; the full timetable does.
        let innsbruckBoard = await repository.departures(for: try boardQuery(innsbruck))
        guard case let .available(innsbruckPage) = innsbruckBoard else { return XCTFail("expected a board, got \(innsbruckBoard)") }
        XCTAssertEqual(innsbruckPage.departures.map(\.service.number), ["EC 80", "RJ 2"])
    }

    func testAFileThatCannotAnswerIsLeftOutWhileAnotherAnswers() async throws {
        let files = try Files()
        let damaged = FileManager.default.temporaryDirectory.appendingPathComponent("BinarioForeignDamaged-\(UUID().uuidString).sqlite")
        try Data("not sqlite".utf8).write(to: damaged)
        defer { try? FileManager.default.removeItem(at: damaged) }
        let repository = SQLiteGTFSJourneyRepository(databaseURL: files.base.url, foreignDatabaseURLs: ["oebb-gtfs": damaged])

        let base = await repository.journeys(for: try query(bolzano, brennero))
        guard case let .available(snapshot) = base else { return XCTFail("the base index still answers, got \(base)") }
        XCTAssertEqual(snapshot.services.map(\.number), ["REG 0"])
        let nothing = await repository.journeys(for: try query(brennero, wien))
        XCTAssertEqual(nothing, .unavailable(.providerInvalid), "with no train anywhere, the damaged file is reported")
        XCTAssertThrowsError(try repository.sourcePacks())
    }

    func testAnEndedForeignTimetableReportsTheExpiry() async throws {
        let files = try Files(foreignLastServiceDate: 20240331)
        let repository = SQLiteGTFSJourneyRepository(
            databaseURL: files.base.url,
            foreignDatabaseURLs: ["oebb-gtfs": files.foreign.url]
        )

        let result = await repository.journeys(for: try query(brennero, wien))
        XCTAssertEqual(result, .unavailable(.scheduleExpired))
        let board = await repository.departures(for: try boardQuery(brennero))
        guard case let .available(page) = board else { return XCTFail("the base index still answers, got \(board)") }
        XCTAssertEqual(page.departures.map(\.service.number), ["REG 1", "REG 2"])
        XCTAssertTrue(page.someTimetablesEnded)
    }

    // MARK: - Fixture

    private func query(_ origin: StationIdentity, _ destination: StationIdentity) throws -> JourneyQuery {
        try JourneyQuery(origin: origin, destination: destination, departure: date("2024-04-01 09:00"))
    }

    private func boardQuery(_ station: StationIdentity, limit: Int = 20) throws -> StationBoardQuery {
        try StationBoardQuery(station: station, from: date("2024-04-01 09:00"), limit: limit)
    }

    private func date(_ value: String) throws -> Date {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = rome
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return try XCTUnwrap(formatter.date(from: value))
    }

    /// The two files. Times are seconds after midnight on 2024-04-01, Rome.
    private final class Files {
        let base: GTFSFixtureDatabase
        let foreign: GTFSFixtureDatabase

        init(extraTrips: Int = 0, foreignLastServiceDate: Int = 20241231) throws {
            typealias Call = GTFSFixtureDatabase.Call
            let base = try GTFSFixtureDatabase(packs: [
                (id: 1, sourceID: "sta-gtfs", lastServiceDate: 20241231),
                (id: 2, sourceID: "oebb-gtfs", lastServiceDate: 20241231),
            ])
            try base.insertStop(id: 1, pack: 1, externalID: "bolzano", name: "Bolzano", catalogStationID: "TEST-BOLZANO")
            try base.insertStop(id: 2, pack: 1, externalID: "brennero", name: "Brennero", catalogStationID: "TEST-BRENNERO")
            try base.insertStop(id: 3, pack: 1, externalID: "innsbruck", name: "Innsbruck Hbf", catalogStationID: "TEST-INNSBRUCK")
            try base.insertStop(id: 4, pack: 2, externalID: "brennero", name: "Brennero", catalogStationID: "TEST-BRENNERO")
            try base.insertStop(id: 5, pack: 2, externalID: "innsbruck", name: "Innsbruck Hbf", catalogStationID: "TEST-INNSBRUCK")
            try base.insertStop(id: 6, pack: 2, externalID: "wien", name: "Wien Hbf", catalogStationID: nil)
            try base.insertTrip(id: 1, pack: 1, externalID: "reg-0", number: "REG 0", calls: [
                Call(stop: 1, seconds: 9 * 3_600 + 30 * 60), Call(stop: 2, seconds: 9 * 3_600 + 55 * 60),
            ])
            try base.insertTrip(id: 2, pack: 1, externalID: "reg-1", number: "REG 1", calls: [
                Call(stop: 2, seconds: 10 * 3_600), Call(stop: 3, seconds: 10 * 3_600 + 40 * 60),
            ])
            try base.insertTrip(id: 3, pack: 1, externalID: "reg-2", number: "REG 2", calls: [
                Call(stop: 2, seconds: 10 * 3_600 + 45 * 60), Call(stop: 3, seconds: 11 * 3_600 + 25 * 60),
            ])
            // The border subset's copy of EC 80: never read once the full timetable is installed.
            try base.insertTrip(id: 4, pack: 2, externalID: "ec-80-subset", number: "EC 80 subset", calls: [
                Call(stop: 4, seconds: 10 * 3_600 + 30 * 60), Call(stop: 5, seconds: 11 * 3_600 + 10 * 60),
                Call(stop: 6, seconds: 15 * 3_600),
            ])
            for index in 0..<extraTrips {
                let id = Int64(10 + index)
                try base.insertTrip(id: id, pack: 1, externalID: "reg-extra-\(index)", number: "REG X\(index)", calls: [
                    Call(stop: 2, seconds: 11 * 3_600 + 5 * 60 + index * 600), Call(stop: 3, seconds: 12 * 3_600 + index * 600),
                ])
            }

            let foreign = try GTFSFixtureDatabase(packs: [(id: 1, sourceID: "oebb-gtfs", lastServiceDate: foreignLastServiceDate)])
            try foreign.insertStop(id: 1, pack: 1, externalID: "brennero", name: "Brennero", catalogStationID: "TEST-BRENNERO")
            try foreign.insertStop(id: 2, pack: 1, externalID: "innsbruck", name: "Innsbruck Hbf", catalogStationID: "TEST-INNSBRUCK")
            try foreign.insertStop(id: 3, pack: 1, externalID: "wien", name: "Wien Hbf", catalogStationID: "TEST-WIEN")
            try foreign.insertTrip(id: 1, pack: 1, externalID: "ec-80", number: "EC 80", calls: [
                Call(stop: 1, seconds: 10 * 3_600 + 30 * 60), Call(stop: 2, seconds: 11 * 3_600 + 10 * 60),
                Call(stop: 3, seconds: 15 * 3_600),
            ])
            try foreign.insertTrip(id: 2, pack: 1, externalID: "rj-2", number: "RJ 2", calls: [
                Call(stop: 2, seconds: 12 * 3_600), Call(stop: 3, seconds: 16 * 3_600),
            ])
            try foreign.insertTrip(id: 3, pack: 1, externalID: "rj-4", number: "RJ 4", calls: [
                Call(stop: 1, seconds: 10 * 3_600 + 15 * 60), Call(stop: 2, seconds: 10 * 3_600 + 50 * 60),
            ])
            for index in 0..<extraTrips {
                let id = Int64(10 + index)
                try foreign.insertTrip(id: id, pack: 1, externalID: "rj-extra-\(index)", number: "RJ X\(index)", calls: [
                    Call(stop: 1, seconds: 11 * 3_600 + 10 * 60 + index * 600), Call(stop: 2, seconds: 12 * 3_600 + index * 600),
                ])
            }
            base.close()
            foreign.close()
            self.base = base
            self.foreign = foreign
        }
    }
}
