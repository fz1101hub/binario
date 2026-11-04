import Foundation
import XCTest
@testable import BinarioKit

final class NetworkReliabilityTests: XCTestCase {
    /// FR 9558 from Roma Termini with the 5-element stops of the first files.
    private let frecciarossa = """
        {"n": "9558", "o": "S08409", "c": "FR", "d": 28, "x": 1, "s": [
            ["S08409", 27, 0.8, [20, 24, 26, 27, 27, 27, 27, 27], 0],
            ["S00219", 26, 4.6, [9, 14, 19, 23, 25, 26, 26, 26], 1]
        ]}
        """
    /// A regional train with the 7-element stops of the first extension; one max delay is null.
    private let regional = """
        {"n": "2153", "o": "S01700", "c": "reg", "d": 10, "x": 0, "s": [
            ["S01700", 10, 1.0, [6, 7, 8, 9, 10, 10, 10, 10], 0, 9, 425],
            ["S01529", 10, 6.2, [2, 4, 5, 6, 8, 9, 9, 10], 0, null, 480]
        ]}
        """
    /// A train with the 9-element stops of the second extension (platform, weekdays, nulls).
    private let southTyrol = """
        {"n": "16691", "o": "S02026", "c": "REG", "d": 30, "x": 0, "s": [
            ["S02026", 30, 0.5, [25, 27, 28, 29, 30, 30, 30, 30], 0, 12, 500, ["3", 9, 10], null],
            ["S02216", 30, 3.0, [15, 20, 24, 27, 28, 29, 30, 30], 0, 40, 540, null,
             [[5, 4, 5], [5, 4, 5], [4, 4, 4], [4, 3, 4], [4, 1, 2], [4, 4, 4], [4, 4, 4]], "future"]
        ]}
        """
    /// Cancelled on every day observed: listed without stops.
    private let cancelled = #"{"n": "2155", "o": "S01700", "c": "REG", "d": 12, "x": 12, "s": []}"#

    func testFiveSevenAndNineElementStopsAreReadAndNullsIgnored() throws {
        let malformedMean = #"{"n": "999", "o": "S00001", "c": "REG", "d": 5, "x": 0, "s": [["S00001", 5, "late", [1, 2, 3, 4, 5, 5, 5, 5], 0]]}"#
        let wrongBuckets = #"{"n": "998", "o": "S00001", "c": "REG", "d": 5, "x": 0, "s": [["S00001", 5, 1.0, [1, 2, 3, 4, 5, 5, 5], 0]]}"#
        let decreasing = #"{"n": "997", "o": "S00001", "c": "REG", "d": 5, "x": 0, "s": [["S00001", 5, 1.0, [3, 2, 3, 4, 5, 5, 5, 5], 0]]}"#
        let stats = try XCTUnwrap(NetworkReliabilityParser.parse(json: document([
            frecciarossa, regional, southTyrol, cancelled, malformedMean, wrongBuckets, decreasing,
        ])))

        XCTAssertEqual(stats.trains.map(\.number), ["9558", "2153", "16691", "2155"])
        XCTAssertEqual(stats.windowEnd, NetworkReliabilityDay(year: 2026, month: 11, day: 9))
        XCTAssertEqual(stats.windowDays, 30)
        XCTAssertEqual(stats.coveredDays, 30)
        XCTAssertEqual(stats.buckets, [0, 2, 5, 10, 15, 20, 30, 60])

        let fr = stats.trains[0]
        XCTAssertEqual(fr.category, "FR")
        XCTAssertEqual(fr.samples(at: 1), 26)
        XCTAssertEqual(fr.meanDelayMinutes(at: 1), 4.6, accuracy: 1e-9)
        XCTAssertEqual(fr.suppressedDays(at: 1), 1)
        XCTAssertNil(fr.maxDelayMinutes(at: 1))
        XCTAssertNil(fr.scheduledMinuteOfDay(at: 0))
        XCTAssertNil(fr.platform(at: 0))
        XCTAssertNil(fr.weekdays(at: 1))

        let reg = stats.trains[1]
        XCTAssertEqual(reg.category, "REG")
        XCTAssertEqual(reg.maxDelayMinutes(at: 0), 9)
        XCTAssertNil(reg.maxDelayMinutes(at: 1), "a null max delay is ignored, not the stop")
        XCTAssertEqual(reg.scheduledMinuteOfDay(at: 1), 480)

        let sta = stats.trains[2]
        XCTAssertEqual(sta.platform(at: 0)?.name, "3")
        XCTAssertEqual(sta.platform(at: 0)?.count, 9)
        XCTAssertEqual(sta.platform(at: 0)?.observed, 10)
        XCTAssertNil(sta.platform(at: 1))
        XCTAssertNil(sta.weekdays(at: 0))
        XCTAssertEqual(sta.weekdays(at: 1)?.count, 7)
        XCTAssertEqual(sta.weekdays(at: 1)?[4], WeekdayCounts(weekday: 5, samples: 4, withinFive: 1, withinFifteen: 2))
        XCTAssertEqual(sta.maxDelayMinutes(at: 1), 40)

        XCTAssertEqual(stats.trains[3].stopCount, 0)
    }

    func testAbsentCorruptOversizedAndOtherSchemaFilesHideTheFeature() async throws {
        let missing = FileManager.default.temporaryDirectory.appendingPathComponent("absent-\(UUID().uuidString).json.gz")
        XCTAssertNil(NetworkReliabilityParser.parseGzipFile(at: missing))
        XCTAssertNil(NetworkReliabilityParser.parseGzip(Data("not gzip".utf8)))
        XCTAssertNil(NetworkReliabilityParser.parse(json: document([frecciarossa], schema: "binario-punctuality-v2")))
        XCTAssertNil(NetworkReliabilityParser.parse(json: Array("{\"schema\": \"binario-punctuality-v1\"".utf8)))
        XCTAssertNil(NetworkReliabilityParser.parse(json: document([frecciarossa]) + Array(" x".utf8)), "data after the document")
        XCTAssertNil(NetworkReliabilityParser.parse(json: document([frecciarossa], buckets: "[0, 5, 5]")))
        XCTAssertNil(NetworkReliabilityParser.parse(json: document([frecciarossa], windowDays: 0)))
        XCTAssertNil(NetworkReliabilityParser.parse(json: document([frecciarossa]), limits: .init(maximumJSONBytes: 64)))
        XCTAssertNil(NetworkReliabilityParser.parse(json: document([frecciarossa, regional]), limits: .init(maximumTrains: 1)))
        XCTAssertNil(NetworkReliabilityParser.parseGzip(try gzipFixture(), limits: .init(maximumCompressedBytes: 16)))
        let deep = String(repeating: "[", count: 40) + String(repeating: "]", count: 40)
        XCTAssertNil(NetworkReliabilityParser.parse(json: Array("{\"schema\": \"binario-punctuality-v1\", \"future\": \(deep)}".utf8)))
        // The file is read from the bundle; without the resource there is nothing to read.
        let none = await NetworkReliabilityLoader.load(url: nil)
        XCTAssertNil(none)
        let absent = await NetworkReliabilityLoader.load(url: missing)
        XCTAssertNil(absent)
    }

    func testGzipWrapperIsDecompressedWithinItsBound() throws {
        let gzip = try gzipFixture()
        let stats = try XCTUnwrap(NetworkReliabilityParser.parseGzip(gzip))
        XCTAssertEqual(stats.trains.map(\.number), ["9558"])
        XCTAssertNil(NetworkReliabilityParser.parseGzip(gzip, limits: .init(maximumJSONBytes: 100)), "decompressed size bound")
        XCTAssertNil(NetworkReliabilityParser.parseGzip(gzip.prefix(gzip.count - 12)), "truncated stream")
        XCTAssertNil(NetworkReliabilityParser.parseGzip(gzip + Data([0x00, 0x01])), "data after the gzip member")
        // The same document with a zlib wrapper is not the bundled format.
        let zlib = try XCTUnwrap(Data(base64Encoded: "eJxNTctuwjAQ/Jc9T9DuErtJzoUPgGPkgwlItVqcChIeQvx71xxQpTnM7Dz2Qefh63CM1NEu5XhKY/U752Ga40+a7tVFCHRNeT9eV3lvIWX1lUjF7dv4jPczdUsG7ebh+zCZ6BkKB2GIgzLM9BxA0ymmXPwHZRtrnWtsZjS65aZ+bQ4m1hsj9k0b0I06AZVO/w7pB3jRoLdlraG+HP4hwH6VNKuUtEe98OhbSA1poUuoe7UKAiSEZ3j+AVL3Q4U="))
        XCTAssertNil(NetworkReliabilityParser.parseGzip(zlib))

        let file = FileManager.default.temporaryDirectory.appendingPathComponent("stats-\(UUID().uuidString).json.gz")
        try gzip.write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        XCTAssertEqual(NetworkReliabilityParser.parseGzipFile(at: file)?.trains.count, 1)
    }

    func testOnTimeLimitsMinimumSamplesAndInterpolation() throws {
        let stats = try XCTUnwrap(NetworkReliabilityParser.parse(json: document([frecciarossa, regional, southTyrol, cancelled])))

        XCTAssertEqual(NetworkReliability.onTimeLimitMinutes(category: "FR"), 5)
        XCTAssertEqual(NetworkReliability.onTimeLimitMinutes(category: "italo"), 5)
        XCTAssertEqual(NetworkReliability.onTimeLimitMinutes(category: "REG"), 15)
        XCTAssertEqual(NetworkReliability.onTimeLimitMinutes(category: "IC"), 15)
        XCTAssertEqual(NetworkReliability.numberKey("FR 9558"), "9558")
        XCTAssertEqual(NetworkReliability.numberKey("09558"), "9558")
        // The Trenord timetables publish the line before the train number.
        XCTAssertEqual(NetworkReliability.numberKey("RE_11 - 2153"), "2153")
        XCTAssertEqual(NetworkReliability.numberKey("TER"), "TER")
        XCTAssertTrue(NetworkReliability.isStationCode("S01700"))
        XCTAssertFalse(NetworkReliability.isStationCode("S1700"))
        XCTAssertFalse(NetworkReliability.isStationCode("NC000634"))

        // FR at 5 minutes: 19 of 26 within 5; the mean of 4.6 shows as 5 minutes.
        let fr = try XCTUnwrap(NetworkReliability.match(stats, number: "FR 9558", alightingCode: "S00219", boardingCode: "S08409"))
        XCTAssertEqual(fr.boardingIndex, 0)
        let frStop = try XCTUnwrap(NetworkReliability.at(fr.train, index: fr.alightingIndex))
        XCTAssertEqual(frStop.onTime, 19)
        XCTAssertEqual(frStop.samples, 26)
        XCTAssertEqual(frStop.onTimeLimitMinutes, 5)
        XCTAssertEqual(frStop.shownMeanDelayMinutes, 5)
        XCTAssertEqual(frStop.daysCancelled, 1)
        // REG at 15 minutes: 8 of 10.
        let reg = try XCTUnwrap(NetworkReliability.match(stats, number: "RE_11 - 2153", alightingCode: "S01529", boardingCode: "S01700"))
        XCTAssertEqual(NetworkReliability.at(reg.train, index: reg.alightingIndex)?.onTime, 8)
        // Between the 5 and 10 minute limits the count is interpolated: 19 + (23 − 19) × 2/5.
        XCTAssertEqual(fr.train.count(at: 1, atMost: 7), 20.6, accuracy: 1e-9)
        XCTAssertEqual(fr.train.count(at: 1, atMost: 90), 26)
        XCTAssertEqual(fr.train.count(at: 1, atMost: -1), 0)

        // Another origin is another train; a stop before the boarding one does not match.
        XCTAssertNil(NetworkReliability.match(stats, number: "9558", alightingCode: "S08409", boardingCode: "S00219"))
        XCTAssertNil(NetworkReliability.match(stats, number: "9559", alightingCode: "S00219"))
        XCTAssertNil(NetworkReliability.match(stats, number: "9558", alightingCode: "MI"))

        // Below five samples nothing is shown.
        let few = #"{"n": "1", "o": "S00001", "c": "REG", "d": 4, "x": 0, "s": [["S00001", 4, 0, [4, 4, 4, 4, 4, 4, 4, 4], 0], ["S00002", 4, 0, [4, 4, 4, 4, 4, 4, 4, 4], 0]]}"#
        let sparse = try XCTUnwrap(NetworkReliabilityParser.parse(json: document([few])))
        XCTAssertNil(NetworkReliability.at(sparse.trains[0], index: 1))

        let cancelledStop = try XCTUnwrap(NetworkReliability.cancelledEveryDay(stats, number: "2155", originCode: "S01700"))
        XCTAssertTrue(cancelledStop.cancelledEveryDay)
        XCTAssertEqual(cancelledStop.daysCancelled, 12)
        XCTAssertEqual(cancelledStop.score, 0)
    }

    func testUsualPlatformAndWeekdayRemarksFollowTheSpecificationThresholds() throws {
        func platformTrain(_ platform: String) throws -> NetworkReliabilityTrain {
            let train = """
                {"n": "5", "o": "S00001", "c": "REG", "d": 10, "x": 0, "s": [
                    ["S00001", 10, 0, [10, 10, 10, 10, 10, 10, 10, 10], 0, 0, 600, \(platform), null]
                ]}
                """
            return try XCTUnwrap(NetworkReliabilityParser.parse(json: document([train]))?.trains.first)
        }
        XCTAssertNil(NetworkReliability.usualPlatform(try platformTrain(#"["1", 4, 4]"#), index: 0), "fewer than five days")
        XCTAssertNil(NetworkReliability.usualPlatform(try platformTrain(#"["1", 5, 10]"#), index: 0), "50% is below 60%")
        XCTAssertNil(NetworkReliability.usualPlatform(try platformTrain(#"["1", 6, 5]"#), index: 0), "more days than observed")
        XCTAssertNil(NetworkReliability.usualPlatform(try platformTrain("null"), index: 0))
        XCTAssertEqual(
            NetworkReliability.usualPlatform(try platformTrain(#"[" 3 Ovest ", 6, 10]"#), index: 0),
            UsualPlatform(stationCode: "S00001", name: "3 Ovest", count: 6, observed: 10)
        )

        let stats = try XCTUnwrap(NetworkReliabilityParser.parse(json: document([southTyrol])))
        let train = stats.trains[0]
        // 28 of 30 within 15 minutes (93%); only Friday, 2 of 4, is 15 points or more away.
        XCTAssertEqual(
            NetworkReliability.weekdayRemarks(train, index: 1),
            [WeekdayRemark(weekday: 5, samples: 4, onTime: 2, overallOnTimePercent: 93)]
        )
        XCTAssertEqual(NetworkReliability.weekdayRemarks(train, index: 0), [], "no weekdays published at the origin")

        // Fewer than four samples are never compared; at most two remarks, largest gap first.
        let weekdays = """
            {"n": "6", "o": "S00001", "c": "FR", "d": 20, "x": 0, "s": [
                ["S00001", 20, 2, [10, 12, 16, 18, 20, 20, 20, 20], 0, 3, 600, null,
                 [[3, 0, 0], [4, 0, 4], [4, 4, 4], [4, 1, 4], [5, 4, 5], [0, 0, 0], [0, 0, 0]]]
            ]}
            """
        let mixed = try XCTUnwrap(NetworkReliabilityParser.parse(json: document([weekdays]))?.trains.first)
        // FR: 16 of 20 within 5 minutes (80%). Tuesday 0% (80 points), Thursday 25% (55), Wednesday 100% (20).
        XCTAssertEqual(NetworkReliability.weekdayRemarks(mixed, index: 0).map(\.weekday), [2, 4])
        XCTAssertEqual(NetworkReliability.weekdayRemarks(mixed, index: 0).first?.overallOnTimePercent, 80)
    }

    func testDistributionRouteAndHourBands() throws {
        let stats = try XCTUnwrap(NetworkReliabilityParser.parse(json: document([southTyrol])))
        let distribution = try XCTUnwrap(NetworkReliabilityAggregates.distribution(stats.trains[0], index: 1))
        XCTAssertEqual(distribution.counts, [15, 9, 4, 2, 0])
        XCTAssertEqual(distribution.percentages, [50, 30, 13, 7, 0])
        XCTAssertEqual(distribution.percentages.reduce(0, +), 100)

        let early = """
            {"n": "100", "o": "S00001", "c": "REG", "d": 20, "x": 0, "s": [
                ["S00001", 20, 0, [20, 20, 20, 20, 20, 20, 20, 20], 0, 0, 420],
                ["S00002", 20, 2.0, [10, 15, 18, 19, 20, 20, 20, 20], 0, 9, 450]
            ]}
            """
        let evening = """
            {"n": "102", "o": "S00001", "c": "REG", "d": 20, "x": 0, "s": [
                ["S00001", 20, 3, [10, 12, 14, 16, 18, 20, 20, 20], 0, 5, 1110],
                ["S00002", 20, 12.0, [2, 4, 8, 12, 14, 16, 18, 20], 0, 45, 1140]
            ]}
            """
        let fast = """
            {"n": "9500", "o": "S00001", "c": "FR", "d": 5, "x": 0, "s": [
                ["S00001", 5, 0, [5, 5, 5, 5, 5, 5, 5, 5], 0, 0, 600],
                ["S00002", 5, 4.0, [1, 2, 3, 4, 5, 5, 5, 5], 0, 8, 620]
            ]}
            """
        let route = try XCTUnwrap(NetworkReliabilityAggregates.route(
            try XCTUnwrap(NetworkReliabilityParser.parse(json: document([early, evening, fast]))),
            originCode: "S00001",
            destinationCode: "S00002"
        ))
        // Each train against its own limit: 20 + 14 at 15 minutes, 3 of 5 at 5 minutes.
        XCTAssertEqual(route.trains, 3)
        XCTAssertEqual(route.samples, 45)
        XCTAssertEqual(route.onTime, 37)
        XCTAssertEqual(route.onTimePercent, 82)
        XCTAssertEqual(route.shownMeanDelayMinutes, 7)
        XCTAssertEqual(route.bestBand, .earlyMorning)
        XCTAssertEqual(route.worstBand, .evening)
        XCTAssertEqual(HourBand.of(minuteOfDay: 1_450), .night)
        XCTAssertNil(NetworkReliabilityAggregates.route(stats, originCode: "S02026", destinationCode: "S02026"))
        let close = [
            HourBandPunctuality(band: .morning, samples: 25, onTime: 22),
            HourBandPunctuality(band: .evening, samples: 25, onTime: 21),
        ]
        let (best, worst) = NetworkReliabilityAggregates.bestAndWorst(close)
        XCTAssertNil(best, "5 points are needed to tell the hours apart")
        XCTAssertNil(worst)
    }

    func testResultsAreMeasuredAtTheDestinationAndMostReliableComesFirst() throws {
        let stats = try XCTUnwrap(NetworkReliabilityParser.parse(json: document([frecciarossa, regional, southTyrol, cancelled])))
        let origin = StationIdentity(id: "S01700", name: "Milano Centrale", railwayCode: "S01700")
        let destination = StationIdentity(id: "S01529", name: "Bergamo", railwayCode: "S01529")
        let query = try JourneyQuery(origin: origin, destination: destination, departure: Date(timeIntervalSince1970: 1_790_000_000))
        func service(_ id: String, _ number: String) -> TrainService {
            TrainService(id: id, number: number, operatorName: "Trenord", category: "RE11", destination: "Bergamo",
                         scheduledDeparture: "08:00", expectedDeparture: "08:00", status: "PROGRAMMED", platform: nil, delayMinutes: 0)
        }
        let services = [service("a", "R 1"), service("b", "2155"), service("c", "RE_11 - 2153")]
        let snapshot = JourneySnapshot(
            query: query,
            generatedAt: Date(timeIntervalSince1970: 1_790_000_000),
            expiresAt: Date(timeIntervalSince1970: 1_790_043_200),
            operationalState: .programmed,
            provenance: DataProvenance(producer: .iosApp, nature: .programmed, sourceLabel: "Trenord", observedAt: Date(timeIntervalSince1970: 1_789_900_000), freshness: .unknown, confidence: .staticOnly),
            services: services
        )

        let reliability = JourneyReliability.assess(snapshot, stats: stats)
        XCTAssertNil(reliability.byServiceID["a"])
        XCTAssertEqual(reliability.byServiceID["b"]?.cancelledEveryDay, true)
        XCTAssertEqual(reliability.byServiceID["c"]?.onTime, 8)
        XCTAssertNil(reliability.route, "fewer than 20 samples on the route")
        // The measured trains by score (the cancelled one last among them), then the others.
        XCTAssertEqual(reliability.mostReliableFirst(services).map(\.id), ["c", "b", "a"])

        let detail = try XCTUnwrap(NetworkReliabilityDetail.of(stats, number: "RE_11 - 2153", boardingCode: "S01700", destinationCode: "S01529"))
        XCTAssertEqual(detail.arrival.stationCode, "S01529")
        XCTAssertEqual(detail.distribution?.counts, [2, 3, 3, 1, 1])
        XCTAssertNil(detail.maxDelayMinutes)
        XCTAssertEqual(detail.worstStop, WorstStop(stationCode: "S01529", increaseMinutes: 5, samples: 10))

        let platform = try XCTUnwrap(NetworkReliabilityDetail.of(stats, number: "16691", boardingCode: "S02026", destinationCode: "S02216"))
        XCTAssertEqual(platform.usualPlatform?.name, "3")
        XCTAssertEqual(platform.maxDelayMinutes, 40)
        XCTAssertEqual(platform.weekdayRemarks.map(\.weekday), [5])
        XCTAssertEqual(NetworkReliabilityDetail.of(stats, number: "2155", boardingCode: "S01700", destinationCode: nil)?.arrival.cancelledEveryDay, true)
    }

    func testAirportLinksNeedAPackWithTripsAndCoveredStations() throws {
        let catalog = try CuratedStationCatalog(data: Data(contentsOf: repositoryRoot
            .appendingPathComponent("app/src/main/assets/stations/curated_it_rail_stations.json")))
        let trenord = AirportRoutes.links(catalog: catalog, coveredStationIDs: ["S01700", "S01066", "S01139"], packsWithTrips: ["lombardia-trenord-gtfs"])
        XCTAssertEqual(trenord.map(\.id), ["S01700--S01139", "S01066--S01139"])
        XCTAssertEqual(trenord.first?.airport.name, "Malpensa Aeroporto")
        XCTAssertEqual(AirportRoutes.links(catalog: catalog, coveredStationIDs: ["S01700", "S01139"], packsWithTrips: []), [])
        XCTAssertEqual(AirportRoutes.links(catalog: catalog, coveredStationIDs: ["S01700"], packsWithTrips: ["lombardia-trenord-gtfs"]), [])
        XCTAssertEqual(AirportRoutes.all.count, 8)
    }

    // MARK: - Fixtures

    private func document(
        _ trains: [String],
        schema: String = "binario-punctuality-v1",
        buckets: String = "[0, 2, 5, 10, 15, 20, 30, 60]",
        windowDays: Int = 30
    ) -> [UInt8] {
        let text = """
            {"schema": "\(schema)", "generatedAt": "2026-11-10T04:00:00Z", "windowStart": "2026-10-11",
             "windowEnd": "2026-11-09", "windowDays": \(windowDays), "source": "ViaggiaTreno, elaborazione Binario (non ufficiale)",
             "buckets": \(buckets), "future": {"field": [1, 2, {"nested": true}]}, "trains": [\(trains.joined(separator: ",\n"))]}
            """
        return Array(text.utf8)
    }

    /// The FR 9558 document compressed with Python's gzip (mtime 0).
    private func gzipFixture() throws -> Data {
        try XCTUnwrap(Data(base64Encoded: "H4sIAAAAAAACA01Ny27CMBD8lz1P0O4Su0nOhQ+AY+SDCUi1WpwKEh5C/HvXHFClOczsPPZB5+HrcIzU0S7leEpj9TvnYZrjT5ru1UUIdE15P15XeW8hZfWVSMXt2/iM9zN1Swbt5uH7MJnoGQoHYYiDMsz0HEDTKaZc/AdlG2uda2xmNLrlpn5tDibWGyP2TRvQjToBlU7/DukHeNGgt2Wtob4c/iHAfpU0q5S0R73w6FtIDWmhS6h7tQoCJIRneP4BGzSHYQkBAAA="))
    }

    private var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}
