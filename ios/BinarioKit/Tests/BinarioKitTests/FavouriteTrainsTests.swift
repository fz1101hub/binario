import Foundation
import XCTest
@testable import BinarioKit

/// «I miei treni» on the iPhone: the favourite trains, their store and «Affidabilità dei miei treni»
/// (the rules of the Android FavouriteTrains, BinarioDeepLink.FavouriteTrain and
/// FavouriteTrainPunctuality with ReliabilityMarks.favouriteStop).
final class FavouriteTrainsTests: XCTestCase {
    private let milano = StationIdentity(id: "S01700", name: "Milano Centrale", railwayCode: "S01700")
    private let pavia = StationIdentity(id: "S01860", name: "Pavia", railwayCode: "S01860")
    private let roma = StationIdentity(id: "S08409", name: "Roma Termini", railwayCode: "S08409")
    private let firenze = StationIdentity(id: "S06421", name: "Firenze Santa Maria Novella", railwayCode: "S06421")
    /// A station of a foreign pack: no ViaggiaTreno code.
    private let zurich = StationIdentity(id: "ch:8503000", name: "Zürich HB")
    private let savedAt = Date(timeIntervalSince1970: 1_762_000_000)

    // MARK: The train and its identity

    func testANumberIsAcceptedWhenAndroidAcceptsItsTrainNumberAndThePublishedFormIsKept() throws {
        // Android: ^[A-Za-z0-9]{1,12}$ on the train number; the iPhone timetables publish Trenord's
        // «RE_11 - 2153», ÖBB's «S 2590» and SNCF's «K1+», whose train numbers are 2153, 2590, 1.
        for number in ["2153", "9558", "RE_11 - 2153", "S 2590", "K1+", "REG", "123456789012", "RE_1100 - 00001234567890"] {
            XCTAssertTrue(FavouriteTrain.isValidNumber(number), number)
        }
        XCTAssertEqual("RE_1100 - 00001234567890".count, FavouriteTrain.maximumNumberLength)
        for number in [
            "", " 2153", "2153 ", "21  53", "21\n53", "2153…", "21.53", "２１５３",
            // 13 digits, and 25 characters.
            "1234567890123", "RE_11000 - 00001234567890",
            // A line name, not a train number.
            "Linea SASSARI-SORSO",
        ] {
            XCTAssertFalse(FavouriteTrain.isValidNumber(number), number)
        }

        let trenord = try favourite("RE_11 - 2153")
        XCTAssertEqual(trenord.number, "RE_11 - 2153")
        XCTAssertThrowsError(try favourite("Linea SASSARI-SORSO")) { error in
            XCTAssertEqual(error as? FavouriteTrainValidationError, .invalidNumber)
        }
    }

    func testAFavouriteIsItsNumberBoardedAtItsOriginAndOpensAsItWasSaved() throws {
        let service = TrainService(
            id: "lombardia-trenord-gtfs:trip:20261109:29520",
            number: "RE_11 - 2153",
            operatorName: "Trenord",
            category: "RE",
            destination: "Pavia",
            scheduledDeparture: "08:12",
            expectedDeparture: "08:12",
            status: "PROGRAMMED",
            platform: nil,
            delayMinutes: 0,
            bikesAllowed: true
        )
        let query = try JourneyQuery(origin: milano, destination: pavia, departure: savedAt)
        let train = try FavouriteTrain(service: service, query: query, savedAt: savedAt)

        XCTAssertEqual(train.id, "RE_11 - 2153@S01700")
        XCTAssertEqual(train.id, FavouriteTrain.identifier(number: "re_11 - 2153", originID: "S01700"), "case is ignored, as on Android")
        XCTAssertNotEqual(train.id, FavouriteTrain.identifier(number: "RE_11 - 2153", originID: "S01529"), "another origin is another train")

        // The detail opens the train as the timetable published it, boarded where it was saved.
        let shown = train.service
        XCTAssertEqual(shown.number, "RE_11 - 2153")
        XCTAssertEqual(shown.operatorName, "Trenord")
        XCTAssertEqual(shown.category, "RE")
        XCTAssertEqual(shown.destination, "Pavia")
        XCTAssertEqual(shown.scheduledDeparture, "08:12")
        XCTAssertEqual(shown.expectedDeparture, "08:12")
        XCTAssertEqual(shown.status, OperationalState.programmed.rawValue)
        XCTAssertNil(shown.platform)
        XCTAssertEqual(shown.delayMinutes, 0)
        XCTAssertEqual(shown.bikesAllowed, true)
        let reopened = try XCTUnwrap(train.query)
        XCTAssertEqual(reopened.origin, milano)
        XCTAssertEqual(reopened.destination, pavia)
    }

    func testTextsDeparturesAndStationsAreValidated() throws {
        for departure in ["00:00", "08:12", "23:59"] {
            XCTAssertNoThrow(try favourite("2153", departure: departure), departure)
        }
        for departure in ["", "8:12", "08:1", "24:00", "08:60", "08-12", "08:12:00", "o8:12"] {
            XCTAssertThrowsError(try favourite("2153", departure: departure), departure) { error in
                XCTAssertEqual(error as? FavouriteTrainValidationError, .invalidDeparture)
            }
        }
        for name in ["", " Trenord", "Tren  ord", "Tren\nord", "Tren\u{0007}ord", String(repeating: "x", count: 65)] {
            XCTAssertThrowsError(try favourite("2153", operatorName: name), name) { error in
                XCTAssertEqual(error as? FavouriteTrainValidationError, .invalidText)
            }
        }
        XCTAssertNoThrow(try favourite("2153", operatorName: String(repeating: "x", count: 64)))
        XCTAssertThrowsError(try favourite("2153", from: milano, to: milano)) { error in
            XCTAssertEqual(error as? FavouriteTrainValidationError, .invalidStations)
        }
        XCTAssertThrowsError(try favourite("2153", from: StationIdentity(id: "", name: "Milano"), to: pavia)) { error in
            XCTAssertEqual(error as? FavouriteTrainValidationError, .invalidStations)
        }
    }

    // MARK: The list

    func testTheListIsNewestFirstHoldsTwelveAndRemovingIsAlwaysPossible() throws {
        XCTAssertEqual(FavouriteTrainCollection.maximumTrains, 12)
        var list = FavouriteTrainCollection.normalized([])
        for number in 1...12 {
            list = try list.adding(favourite("\(2_100 + number)"))
        }
        XCTAssertEqual(list.trains.count, 12)
        XCTAssertEqual(list.trains.first?.number, "2112", "the newest first")
        XCTAssertEqual(list.trains.last?.number, "2101")

        // The thirteenth is refused, never swapped for the oldest; a train already saved is no change.
        XCTAssertThrowsError(try list.adding(favourite("2199"))) { error in
            XCTAssertEqual(error as? FavouriteTrainValidationError, .tooManyTrains)
        }
        let saved = try favourite("2105")
        XCTAssertEqual(try list.adding(saved), list)
        // The same number boarded elsewhere is another train.
        let elsewhere = try favourite("2105", from: roma, to: firenze)
        XCTAssertNotEqual(elsewhere.id, saved.id)
        XCTAssertFalse(list.contains(id: elsewhere.id))

        let shorter = list.removing(ids: [saved.id, "not-saved"])
        XCTAssertEqual(shorter.trains.count, 11)
        XCTAssertFalse(shorter.contains(id: saved.id))
        let newest = try favourite("2199")
        XCTAssertEqual(try shorter.adding(newest).trains.first, newest)
        XCTAssertEqual(list.removing(ids: Set(list.trains.map(\.id))).trains, [])

        // Validation refuses a thirteenth train and a duplicate; normalising keeps the first of each.
        XCTAssertThrowsError(try FavouriteTrainCollection(validating: list.trains + [newest])) { error in
            XCTAssertEqual(error as? FavouriteTrainValidationError, .tooManyTrains)
        }
        let first = try favourite("2101")
        let duplicate = try favourite("2101", departure: "09:00")
        XCTAssertEqual(first.id, duplicate.id)
        XCTAssertThrowsError(try FavouriteTrainCollection(validating: [first, duplicate])) { error in
            XCTAssertEqual(error as? FavouriteTrainValidationError, .duplicateTrain)
        }
        let normalized = FavouriteTrainCollection.normalized([first, duplicate] + list.trains + [newest])
        XCTAssertEqual(normalized.trains.count, 12)
        XCTAssertEqual(normalized.trains.first, first)
        XCTAssertFalse(normalized.contains(id: newest.id))
    }

    // MARK: The store

    func testTheStoreKeepsTheTrainsInItsOwnKeyAndReadsThemBack() throws {
        let suite = "it.binario.ios.tests.favourites.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertEqual(FavouriteTrainStore.storageKey, "it.binario.ios.favourite-trains.v1")
        let store = FavouriteTrainStore(defaults: defaults)
        XCTAssertEqual(store.load().trains, [])

        let list = try FavouriteTrainCollection(validating: [
            favourite("RE_11 - 2153"),
            favourite("9558", from: roma, to: firenze, bikesAllowed: false),
            favourite("2157", from: zurich, to: milano),
        ])
        try store.save(list)
        XCTAssertNotNil(defaults.data(forKey: FavouriteTrainStore.storageKey))
        XCTAssertEqual(FavouriteTrainStore(defaults: defaults).load(), list)

        try store.save(list.removing(ids: Set(list.trains.map(\.id))))
        XCTAssertEqual(store.load().trains, [])
    }

    func testAnUnreadableStoreReadsAsNoTrainAndAnInvalidRecordIsSkippedOnItsOwn() throws {
        let suite = "it.binario.ios.tests.favourites.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = FavouriteTrainStore(defaults: defaults)

        for payload in [Data("not-json".utf8), Data("{\"number\": \"2153\"}".utf8), Data("[1, 2]".utf8)] {
            defaults.set(payload, forKey: FavouriteTrainStore.storageKey)
            XCTAssertEqual(store.load().trains, [])
        }

        // One record edited into a line name, one without its departure, one duplicate: each is
        // dropped on its own and the valid ones stay, in order.
        let first = try favourite("2153")
        let second = try favourite("9558", from: roma, to: firenze)
        let encoded = try FavouriteTrainStore.encode([first, first, second, second])
        let records = try XCTUnwrap(try JSONSerialization.jsonObject(with: encoded) as? [[String: Any]])
        var edited = records
        edited[1]["number"] = "Linea SASSARI-SORSO"
        edited[3].removeValue(forKey: "scheduledDeparture")
        edited.append(records[0])
        edited.append(records[2])
        defaults.set(try JSONSerialization.data(withJSONObject: edited), forKey: FavouriteTrainStore.storageKey)
        XCTAssertEqual(store.load().trains, [first, second])

        // More than twelve stored: the first twelve.
        let many = try (1...14).map { try favourite("\(2_100 + $0)") }
        defaults.set(try FavouriteTrainStore.encode(many), forKey: FavouriteTrainStore.storageKey)
        XCTAssertEqual(store.load().trains, Array(many.prefix(12)))

        // A value far larger than twelve trains was not written by the app.
        defaults.set(Data(repeating: 0x20, count: FavouriteTrainStore.maximumStoredBytes + 1), forKey: FavouriteTrainStore.storageKey)
        XCTAssertEqual(store.load().trains, [])
    }

    // MARK: «Affidabilità dei miei treni»

    func testEachFavouriteIsMeasuredAtItsLastStopWithItsOwnLimitFromFiveSamples() throws {
        let stats = try statistics()
        // A Frecciarossa boarded at its origin or on the way: its last stop, at 5 minutes.
        let frecciarossa = StopReliability(stationCode: "S01700", samples: 26, onTime: 19, onTimeLimitMinutes: 5, meanDelayMinutes: 4.6, daysObserved: 28, daysCancelled: 1)
        XCTAssertEqual(FavouriteTrainsReliability.lastStop(stats, number: "9558", originCode: "S08409"), frecciarossa)
        XCTAssertEqual(FavouriteTrainsReliability.lastStop(stats, number: "FR 9558", originCode: "S06421"), frecciarossa)
        // A Trenord regional as the timetable numbers it: at 15 minutes.
        XCTAssertEqual(
            FavouriteTrainsReliability.lastStop(stats, number: "RE_11 - 2153", originCode: "S01700"),
            StopReliability(stationCode: "S01860", samples: 28, onTime: 24, onTimeLimitMinutes: 15, meanDelayMinutes: 6.4, daysObserved: 30, daysCancelled: 2)
        )
        // Nothing below 5 samples at the last stop; 5 are enough.
        XCTAssertNil(FavouriteTrainsReliability.lastStop(stats, number: "2155", originCode: "S01700"))
        XCTAssertEqual(FavouriteTrainsReliability.lastStop(stats, number: "2157", originCode: "S01700")?.samples, 5)
        // Cancelled on every day observed: no delays to show.
        XCTAssertNil(FavouriteTrainsReliability.lastStop(stats, number: "2159", originCode: "S01700"))
        // Boarded at its last stop, or at a station it does not serve: none rather than a guess.
        XCTAssertNil(FavouriteTrainsReliability.lastStop(stats, number: "9558", originCode: "S01700"))
        XCTAssertNil(FavouriteTrainsReliability.lastStop(stats, number: "9558", originCode: "S01860"))
        // Without a code: the only train with the number that runs, else none.
        XCTAssertEqual(FavouriteTrainsReliability.lastStop(stats, number: "9558", originCode: nil), frecciarossa)
        XCTAssertNil(FavouriteTrainsReliability.lastStop(stats, number: "2153", originCode: nil), "two trains 2153 with other origins")
        XCTAssertNil(FavouriteTrainsReliability.lastStop(stats, number: "4711", originCode: "S01700"))

        let favourites = try [
            favourite("9558", from: firenze, to: milano),
            favourite("2155"),
            favourite("RE_11 - 2153"),
            favourite("9558", from: zurich, to: milano),
        ]
        let reliability = try XCTUnwrap(FavouriteTrainsReliability.assess(favourites, stats: stats))
        XCTAssertEqual(reliability.rows.map(\.train.id), ["9558@S06421", "RE_11 - 2153@S01700", "9558@ch:8503000"])
        XCTAssertEqual(reliability.rows.first?.network, frecciarossa)
        XCTAssertEqual(reliability.windowEnd, NetworkReliabilityDay(year: 2026, month: 11, day: 9))
        XCTAssertEqual(reliability.days, 30)
    }

    func testOnlyTheFirstEightFavouritesAreConsideredInTheirOrder() throws {
        XCTAssertEqual(FavouriteTrainsReliability.maximumRows, 8)
        let stats = try statistics()
        // Ten Frecciarossa favourites boarded at ten stations with Roma Termini's code.
        var favourites = try (0..<10).map { index in
            try favourite("9558", from: StationIdentity(id: "roma-\(index)", name: "Roma \(index)", railwayCode: "S08409"), to: milano)
        }
        var rows = try XCTUnwrap(FavouriteTrainsReliability.assess(favourites, stats: stats)).rows
        XCTAssertEqual(rows.map(\.train.origin.id), (0..<8).map { "roma-\($0)" })

        // The second is not measured: seven rows, and the ninth is still not considered.
        favourites[1] = try favourite("2155")
        rows = try XCTUnwrap(FavouriteTrainsReliability.assess(favourites, stats: stats)).rows
        XCTAssertEqual(rows.map(\.train.origin.id), ["roma-0"] + (2..<8).map { "roma-\($0)" })
    }

    func testAbsentStatisticsOrNoTrainMeasuredShowNothing() throws {
        let favourites = try [favourite("9558", from: roma, to: milano)]
        let stats = try statistics()
        // A build without the file (BINARIO_IOS_PUNCTUALITY_STATS=false), or while it is read.
        XCTAssertNil(FavouriteTrainsReliability.assess(favourites, stats: nil))
        XCTAssertNil(FavouriteTrainsReliability.assess([], stats: stats))
        let unmeasured = try [favourite("2155"), favourite("4711")]
        XCTAssertNil(FavouriteTrainsReliability.assess(unmeasured, stats: stats))
        XCTAssertNotNil(FavouriteTrainsReliability.assess(favourites, stats: stats))
        // An empty file: no train to measure.
        let empty = try XCTUnwrap(NetworkReliabilityParser.parse(json: document([])))
        XCTAssertNil(FavouriteTrainsReliability.assess(favourites, stats: empty))
    }

    // MARK: - Fixtures

    private func favourite(
        _ number: String,
        from origin: StationIdentity? = nil,
        to destination: StationIdentity? = nil,
        departure: String = "08:12",
        operatorName: String = "Trenord",
        bikesAllowed: Bool? = nil
    ) throws -> FavouriteTrain {
        try FavouriteTrain(
            number: number,
            operatorName: operatorName,
            category: "RE",
            terminus: "Pavia",
            scheduledDeparture: departure,
            bikesAllowed: bikesAllowed,
            origin: origin ?? milano,
            destination: destination ?? pavia,
            savedAt: savedAt
        )
    }

    /// FR 9558 Roma Termini → Firenze → Milano Centrale; Trenord 2153 Milano Centrale → Pavia and
    /// another 2153 elsewhere; 2155 with 4 samples at Pavia, 2157 with 5; 2159 cancelled every day.
    private func statistics() throws -> NetworkReliabilityStats {
        try XCTUnwrap(NetworkReliabilityParser.parse(json: document([
            """
            {"n": "9558", "o": "S08409", "c": "FR", "d": 28, "x": 1, "s": [
                ["S08409", 27, 0.8, [20, 24, 26, 27, 27, 27, 27, 27], 0],
                ["S06421", 27, 2.0, [15, 20, 24, 26, 27, 27, 27, 27], 0],
                ["S01700", 26, 4.6, [9, 14, 19, 23, 25, 26, 26, 26], 1]
            ]}
            """,
            """
            {"n": "2153", "o": "S01700", "c": "RE", "d": 30, "x": 2, "s": [
                ["S01700", 28, 0.5, [25, 27, 28, 28, 28, 28, 28, 28], 0],
                ["S01860", 28, 6.4, [10, 14, 18, 21, 24, 26, 28, 28], 0]
            ]}
            """,
            """
            {"n": "2153", "o": "S02430", "c": "REG", "d": 20, "x": 0, "s": [
                ["S02430", 20, 1.0, [15, 18, 20, 20, 20, 20, 20, 20], 0],
                ["S02593", 20, 3.0, [8, 12, 16, 18, 20, 20, 20, 20], 0]
            ]}
            """,
            """
            {"n": "2155", "o": "S01700", "c": "RE", "d": 4, "x": 0, "s": [
                ["S01700", 4, 0.5, [4, 4, 4, 4, 4, 4, 4, 4], 0],
                ["S01860", 4, 2.0, [2, 3, 4, 4, 4, 4, 4, 4], 0]
            ]}
            """,
            """
            {"n": "2157", "o": "S01700", "c": "RE", "d": 5, "x": 0, "s": [
                ["S01700", 5, 0.5, [5, 5, 5, 5, 5, 5, 5, 5], 0],
                ["S01860", 5, 2.0, [2, 3, 4, 5, 5, 5, 5, 5], 0]
            ]}
            """,
            #"{"n": "2159", "o": "S01700", "c": "RE", "d": 12, "x": 12, "s": []}"#,
        ])))
    }

    private func document(_ trains: [String]) -> [UInt8] {
        let text = """
            {"schema": "binario-punctuality-v1", "generatedAt": "2026-11-10T04:00:00Z", "windowStart": "2026-10-11",
             "windowEnd": "2026-11-09", "windowDays": 30, "source": "ViaggiaTreno, elaborazione Binario (non ufficiale)",
             "buckets": [0, 2, 5, 10, 15, 20, 30, 60], "trains": [\(trains.joined(separator: ",\n"))]}
            """
        return Array(text.utf8)
    }
}
