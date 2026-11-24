import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/remote-gtfs-v1.json: the archives the iPhone reads and refuses (`GTFSZipArchive`), the
/// catalogue station a stop names (`RemoteGTFSStationMatch`), and the trains of the operator a route finds through
/// the downloaded index (`RemoteGTFSSchedule`, `TimetableFallbacks`), the cases Android's RemoteGtfsContractTest and
/// tests/test_ios_gtfs_schedule.py read too.
final class RemoteGTFSContractTests: XCTestCase {
    func testEveryArchiveIsReadOrRefusedAsTheCaseSays() throws {
        let rules = try contract()
        XCTAssertGreaterThanOrEqual(rules.archives.count, 10)
        for item in rules.archives {
            let data = try XCTUnwrap(Data(base64Encoded: item.zipBase64), item.name)
            var limits = GTFSArchiveLimits.remote
            if let override = item.limits {
                if let value = override.maximumArchiveBytes { limits.maximumArchiveBytes = value }
                if let value = override.maximumEntryBytes { limits.maximumEntryBytes = value }
                if let value = override.maximumTotalUncompressedBytes { limits.maximumTotalUncompressedBytes = value }
            }
            var outcome = "ok"
            var names: [String] = []
            do {
                let archive = try GTFSZipArchive(data, limits: limits)
                try archive.requireGTFSFiles()
                for name in archive.names { _ = try archive.data(of: name) }
                names = archive.names.sorted()
            } catch let failure as GTFSArchiveFailure {
                outcome = failure.rawValue
            }
            XCTAssertEqual(outcome, item.expected, item.name)
            if let files = item.files { XCTAssertEqual(names, files, item.name) }
        }
    }

    func testTheEntriesInflateToTheirText() throws {
        let rules = try contract()
        let data = try XCTUnwrap(Data(base64Encoded: rules.archives[0].zipBase64))
        let archive = try GTFSZipArchive(data)
        let agency = try XCTUnwrap(try archive.data(of: "agency.txt"))
        XCTAssertEqual(String(decoding: agency, as: UTF8.self), "agency_id,agency_name\nes,European Sleeper\n")
        XCTAssertNil(try archive.data(of: "shapes.txt"))
    }

    func testAStopNamesTheStationsOfItsCandidateNamesAndIsBoundToOne() throws {
        let rules = try contract()
        for item in rules.stationMatches {
            let stations = item.stations.map(\.station)
            let match = RemoteGTFSStationMatch(stations: stations)
            let key = RemoteGTFSStationMatch.normalize(item.stop.name)
            let named = stations.filter { station in
                RemoteGTFSStationMatch.candidateNames(station).contains { RemoteGTFSStationMatch.normalize($0) == key }
            }.map(\.id).sorted()
            XCTAssertEqual(named, item.named.sorted(), item.name)
            let bound = match.station(stopName: item.stop.name, latitude: item.stop.latitude, longitude: item.stop.longitude)
            XCTAssertEqual(bound?.id, item.bound, item.name)
        }
    }

    func testTheOperatorsTrainsOfTheArchiveAnswerARouteTheTimetablesDoNotServe() async throws {
        let rules = try contract()
        let parser = DateFormatter()
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.timeZone = TimeZone(identifier: "Europe/Rome")
        parser.dateFormat = "yyyy-MM-dd'T'HH:mm"
        for item in rules.timetables {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("BinarioRemote-\(UUID().uuidString)")
            defer { try? FileManager.default.removeItem(at: directory) }
            let archive = try XCTUnwrap(Data(base64Encoded: item.zipBase64), item.name)
            let departure = try XCTUnwrap(parser.date(from: item.departure), item.name)
            let transport = FakeLiveTransport { _ in .response(status: 200, headers: [:], body: archive) }
            let feed = try XCTUnwrap(SharedData.RemoteGtfs.feeds.first)
            let schedule = RemoteGTFSSchedule(feed: feed, directory: directory, transport: transport, now: { departure })
            let fallbacks = TimetableFallbacks(rfi: nil, archives: [schedule])
            let stations = item.stations.map(\.station)
            fallbacks.useCatalogue(stations)
            let origin = try XCTUnwrap(stations.first { $0.id == item.origin })
            let destination = try XCTUnwrap(stations.first { $0.id == item.destination })
            let query = try JourneyQuery(origin: StationIdentity(origin), destination: StationIdentity(destination), departure: departure)
            let result = await fallbacks.journeys(for: query)
            var trains: [Train] = []
            if case let .available(snapshot)? = result {
                XCTAssertTrue(snapshot.usesCoverageTimetable, item.name)
                XCTAssertEqual(snapshot.provenance.sourceLabel, feed.attribution, item.name)
                for service in snapshot.services {
                    // "european-sleeper:<trip>:<yyyymmdd>:<seconds>": the service day, here the day of departure.
                    let parts = service.id.split(separator: ":")
                    let day = String(parts[parts.count - 2])
                    let date = "\(day.prefix(4))-\(day.dropFirst(4).prefix(2))-\(day.suffix(2))"
                    trains.append(Train(number: service.number, departure: "\(date)T\(service.scheduledDeparture)", operator: service.operatorName))
                }
            } else {
                XCTAssertNil(result, item.name)
            }
            XCTAssertEqual(trains, item.expected, item.name)
            // Before its first day the archive is not even asked for.
            if item.expected.isEmpty, item.departure.prefix(10) < Substring(feed.notBefore) {
                XCTAssertEqual(transport.requests.count, 0, item.name)
            }
        }
    }

    func testTheIndexOfAnArchiveHasTheSchemaOfTheBundledOne() throws {
        let rules = try contract()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("BinarioRemote-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let archive = try XCTUnwrap(Data(base64Encoded: rules.timetables[0].zipBase64))
        let destination = directory.appendingPathComponent("european-sleeper.sqlite")
        let feed = try XCTUnwrap(SharedData.RemoteGtfs.feeds.first)
        try RemoteGTFSIndex.build(
            archive: archive, feed: feed, stations: rules.timetables[0].stations.map(\.station), generatedOn: Date(), into: destination
        )
        XCTAssertTrue(SQLiteGTFSJourneyRepository.validatesDatabase(
            at: destination, inputFingerprint: SHA256Digest.hex(archive), onlySourceID: feed.pack
        ))
        let packs = try SQLiteGTFSJourneyRepository(databaseURL: destination).sourcePacks()
        XCTAssertEqual(packs.map(\.sourceID), ["european-sleeper"])
        XCTAssertEqual(packs.first?.tripCount, 1)
    }

    func testARefusalPausesTheArchiveAndNothingIsAskedAgain() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("BinarioRemote-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let feed = try XCTUnwrap(SharedData.RemoteGtfs.feeds.first)
        let transport = FakeLiveTransport { _ in FakeLiveTransport.status(429) }
        let day = try XCTUnwrap(RailDay(iso: "2026-11-24"))
        let schedule = RemoteGTFSSchedule(feed: feed, directory: directory, transport: transport)
        let station = Station(id: "S01700", name: "Milano Centrale")
        let first = await schedule.index(forDay: day, stations: [station])
        let second = await schedule.index(forDay: day, stations: [station])
        XCTAssertNil(first)
        XCTAssertNil(second)
        XCTAssertEqual(transport.requests.count, 1)
    }

    /// Android's `enrichWithSameFeedRealtime`: the Trip Updates of the archive's own feed time its train by trip_id
    /// and stop of the same archive. 2026-09-10, ES123 leaves Bruxelles-Midi at 09:00 and is 10 minutes late.
    func testTheArchivesTripUpdatesTimeItsTrains() async throws {
        let rules = try contract()
        let item = rules.timetables[0]
        let archive = try XCTUnwrap(Data(base64Encoded: item.zipBase64))
        let now = Date(timeIntervalSince1970: 1_789_023_900) // 2026-09-10T07:05:00Z, 09:05 in Brussels and Rome
        let realtimeFeed = try XCTUnwrap(SharedData.RealtimeFeeds.tripUpdates.first { $0.pack == "european-sleeper" })
        let payload = Self.tripUpdates(timestamp: 1_789_023_900, tripID: "trip-1", startDate: "20260910", stopID: "bru", sequence: 1, delay: 600)
        let transport = FakeLiveTransport { request in
            .response(status: 200, headers: [:], body: request.url.absoluteString == realtimeFeed.url ? payload : archive)
        }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("BinarioRemote-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let feed = try XCTUnwrap(SharedData.RemoteGtfs.feeds.first)
        let fallbacks = TimetableFallbacks(
            rfi: nil, archives: [RemoteGTFSSchedule(feed: feed, directory: directory, transport: transport, now: { now })]
        )
        let stations = item.stations.map(\.station)
        fallbacks.useCatalogue(stations)
        let origin = try XCTUnwrap(stations.first { $0.id == item.origin })
        let destination = try XCTUnwrap(stations.first { $0.id == item.destination })
        let query = try JourneyQuery(
            origin: StationIdentity(origin), destination: StationIdentity(destination),
            departure: Date(timeIntervalSince1970: 1_789_020_000)
        )
        guard case let .available(snapshot)? = await fallbacks.journeys(for: query) else { return XCTFail("no train") }
        let service = try XCTUnwrap(snapshot.services.first)
        // The bundled index is never opened for the archive's trips: they are read from the archive's own index.
        let repository = SQLiteGTFSJourneyRepository(
            databaseURL: directory.appendingPathComponent("absent.sqlite"), fallbacks: fallbacks
        )
        let realtime = TimetableRealtime.standard(transport: transport, pauses: LiveSourcePauses(), now: { now })
        let answer = await realtime.statuses(serviceIDs: [service.id], stationID: origin.id, day: now, timetable: repository)
        let status = try XCTUnwrap(answer.statuses[service.id])
        XCTAssertEqual(status.delayMinutes, 10)
        XCTAssertEqual(status.expectedDeparture, Date(timeIntervalSince1970: 1_789_024_200)) // 09:10
        XCTAssertEqual(status.source, .europeanSleeperRealtime)
        XCTAssertEqual(answer.sources, [.europeanSleeperRealtime])
    }

    /// A GTFS-Realtime FeedMessage with one Trip Update of one stop, as protobuf.
    private static func tripUpdates(
        timestamp: UInt64, tripID: String, startDate: String, stopID: String, sequence: UInt64, delay: UInt64
    ) -> Data {
        func varint(_ value: UInt64) -> [UInt8] {
            var bytes: [UInt8] = []
            var rest = value
            repeat {
                var byte = UInt8(rest & 0x7F)
                rest >>= 7
                if rest != 0 { byte |= 0x80 }
                bytes.append(byte)
            } while rest != 0
            return bytes
        }
        func message(_ number: UInt64, _ body: [UInt8]) -> [UInt8] { varint(number << 3 | 2) + varint(UInt64(body.count)) + body }
        func text(_ number: UInt64, _ value: String) -> [UInt8] { message(number, Array(value.utf8)) }
        func number(_ field: UInt64, _ value: UInt64) -> [UInt8] { varint(field << 3) + varint(value) }
        let header = text(1, "2.0") + number(3, timestamp)
        let trip = text(1, tripID) + text(3, startDate)
        let stop = number(1, sequence) + message(3, number(1, delay)) + text(4, stopID)
        let update = message(1, trip) + message(2, stop)
        let entity = text(1, "entity-1") + message(3, update)
        return Data(message(1, header) + message(2, entity))
    }

    func testOnlyTheOperatorsAgencyIsKept() {
        XCTAssertEqual(TimetableNaming.operatorName("European Sleeper", sourceID: "european-sleeper"), "European Sleeper")
        XCTAssertTrue(RemoteGTFSSchedule.isCoverageService("european-sleeper:trip-1:20260910:32400"))
        XCTAssertFalse(RemoteGTFSSchedule.isCoverageService("trenitalia-gtfs:1:20260910:32400"))
    }

    // MARK: - Contract

    private struct Train: Equatable, Decodable {
        let number: String
        let departure: String
        let `operator`: String
    }

    private struct CaseStation: Decodable {
        let id: String
        let name: String
        let aliases: [String]
        let railwayCode: String?
        let trenitaliaLocationId: String?
        let latitude: Double?
        let longitude: Double?

        var station: Station {
            Station(
                id: id, name: name, railwayCode: railwayCode, trenitaliaLocationId: trenitaliaLocationId, aliases: aliases,
                latitude: latitude, longitude: longitude
            )
        }
    }

    private struct Limits: Decodable {
        let maximumArchiveBytes: Int?
        let maximumEntryBytes: Int?
        let maximumTotalUncompressedBytes: Int?
    }

    private struct Archive: Decodable {
        let name: String
        let zipBase64: String
        let expected: String
        let files: [String]?
        let limits: Limits?
    }

    private struct Stop: Decodable {
        let name: String
        let latitude: Double?
        let longitude: Double?
    }

    private struct StationMatch: Decodable {
        let name: String
        let stations: [CaseStation]
        let stop: Stop
        let named: [String]
        let bound: String?
    }

    private struct Timetable: Decodable {
        let name: String
        let zipBase64: String
        let stations: [CaseStation]
        let origin: String
        let destination: String
        let departure: String
        let expected: [Train]
    }

    private struct Contract: Decodable {
        let archives: [Archive]
        let stationMatches: [StationMatch]
        let timetables: [Timetable]
    }

    private func contract() throws -> Contract {
        try JSONDecoder().decode(
            Contract.self,
            from: Data(contentsOf: repositoryRoot.appendingPathComponent("contracts/shared-rules/v1/remote-gtfs-v1.json"))
        )
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
