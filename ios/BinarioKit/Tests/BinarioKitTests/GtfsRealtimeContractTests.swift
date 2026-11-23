import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/gtfs-realtime-v1.json: the GTFS-Realtime rules Android and the iPhone share (the protobuf
/// reading, the SNCF, Trenitalia France and STA feeds, the join to the timetable, the city feeds), read from the same
/// file by both test suites, with Android's protobuf fixtures as inputs.
final class GtfsRealtimeContractTests: XCTestCase {
    func testTheProtobufReadingOfTheBindings() throws {
        for item in try RealtimeContract.cases("decoding") {
            let name = item.string("name")
            let feed = GTFSRealtimeDecoder.decode(try RealtimeContract.hex(item.string("hex")))
            if item.bool("unreadable") {
                XCTAssertNil(feed, name)
                continue
            }
            let decoded = try XCTUnwrap(feed, name)
            XCTAssertEqual(decoded.version, item.string("version"), name)
            XCTAssertEqual(decoded.timestamp, item.int64("timestamp"), name)
            let entities = item.objects("entities")
            XCTAssertEqual(decoded.entities.count, entities.count, name)
            for (expected, entity) in zip(entities, decoded.entities) {
                XCTAssertEqual(entity.id, expected.string("id"), name)
                XCTAssertEqual(entity.isDeleted, expected.bool("isDeleted"), name)
                guard let trip = expected.object("tripUpdate") else {
                    XCTAssertNil(entity.tripUpdate, name)
                    continue
                }
                let update = try XCTUnwrap(entity.tripUpdate, name)
                XCTAssertEqual(update.trip.tripID, trip.string("tripId"), name)
                XCTAssertEqual(update.trip.routeID, trip.string("routeId"), name)
                XCTAssertEqual(update.trip.startDate, trip.string("startDate"), name)
                XCTAssertEqual(RealtimeContract.name(update.trip.scheduleRelationship), trip.string("scheduleRelationship"), name)
                XCTAssertEqual(update.timestamp, trip.int64("timestamp"), name)
                let stops = trip.objects("stops")
                XCTAssertEqual(update.stopTimeUpdates.count, stops.count, name)
                for (stop, actual) in zip(stops, update.stopTimeUpdates) {
                    XCTAssertEqual(actual.stopSequence.map { Int64($0) }, stop.int64("stopSequence"), name)
                    XCTAssertEqual(actual.stopID, stop.string("stopId"), name)
                    XCTAssertEqual(RealtimeContract.name(actual.scheduleRelationship), stop.string("scheduleRelationship"), name)
                    RealtimeContract.assertEvent(actual.arrival, stop.object("arrival"), name)
                    RealtimeContract.assertEvent(actual.departure, stop.object("departure"), name)
                }
            }
        }
    }

    func testTheStopUpdatesOfTheSncfTrenitaliaFranceAndStaFeeds() throws {
        let cases = try RealtimeContract.cases("protobufUpdates") + RealtimeContract.cases("jsonUpdates")
        for item in cases {
            let name = item.string("name")
            let feed = try XCTUnwrap(SharedData.RealtimeFeeds.tripUpdates.first { $0.id == item.string("feed") }, name)
            let fetchedAt = Date(timeIntervalSince1970: TimeInterval(item.int64("fetchedAt") ?? 0))
            let payload: Data
            if let json = item.optionalString("json") {
                payload = Data(json.utf8)
            } else {
                payload = try RealtimeContract.hex(item.string("hex"))
            }
            let updates = GTFSRealtimeFeedSource.stopUpdates(payload, format: feed.format, feedID: feed.id, fetchedAt: fetchedAt, trips: nil)
            if item.bool("unreadable") {
                XCTAssertNil(updates, name)
                XCTAssertFalse(GTFSRealtimeFeedSource.readable(payload, format: feed.format, feedID: feed.id, fetchedAt: fetchedAt), name)
                continue
            }
            let actual = try XCTUnwrap(updates, name)
            XCTAssertTrue(GTFSRealtimeFeedSource.readable(payload, format: feed.format, feedID: feed.id, fetchedAt: fetchedAt), name)
            let expected = item.objects("updates")
            XCTAssertEqual(actual.count, expected.count, name)
            for (wanted, update) in zip(expected, actual) {
                XCTAssertEqual(update.tripID, wanted.string("tripId"), name)
                XCTAssertEqual(update.stopID, wanted.optionalString("stopId"), name)
                XCTAssertEqual(update.stopSequence.map { Int64($0) }, wanted.int64("stopSequence"), name)
                XCTAssertEqual(update.serviceDate.map { Int64($0) }, wanted.int64("serviceDate"), name)
                XCTAssertEqual(update.predicted.map { Int64($0.timeIntervalSince1970) }, wanted.int64("predicted"), name)
                XCTAssertEqual(update.delaySeconds.map { Int64($0) }, wanted.int64("delaySeconds"), name)
                XCTAssertEqual(Int64(update.observedAt.timeIntervalSince1970), wanted.int64("observedAt"), name)
                XCTAssertEqual(Int64(update.expiresAt.timeIntervalSince1970), wanted.int64("expiresAt"), name)
                XCTAssertEqual(update.entityID, wanted.string("entityId"), name)
                XCTAssertEqual(update.feedID, feed.id, name)
            }
            // Only the trips asked about are made, the others still checked.
            if let first = expected.first?.string("tripId") {
                let one = GTFSRealtimeFeedSource.stopUpdates(payload, format: feed.format, feedID: feed.id, fetchedAt: fetchedAt, trips: [first])
                XCTAssertEqual(one?.map(\.tripID), expected.map { $0.string("tripId") }.filter { $0 == first }, name)
            }
        }
    }

    func testTheJoinOfATripUpdateToTheListedStopOfATimetableTrain() throws {
        for item in try RealtimeContract.cases("join") {
            let name = item.string("name")
            let now = Date(timeIntervalSince1970: TimeInterval(item.int64("now") ?? 0))
            let stops = item.objects("stops").map { stop in
                TimetableTripStops.Stop(
                    stopID: stop.string("stopId"), sequence: Int(stop.int64("sequence") ?? 0),
                    seconds: RealtimeContract.seconds(stop.string("time")), catalogStationID: nil
                )
            }
            let zones = item.optionalString("agencyTimeZone").map { [$0] } ?? []
            let trip = TimetableTripStops(stops: stops, agencyTimeZones: zones)
            let listed = item.objects("stops")[Int(item.int64("listed") ?? 0)]
            let serviceDate = Int(item.int64("serviceDate") ?? 0)
            let id = "\(item.string("pack")):\(item.string("tripId")):\(serviceDate):\(RealtimeContract.seconds(listed.string("time")) ?? -1)"
            let reference = try XCTUnwrap(TimetableServiceReference(serviceID: id), name)
            let updates = item.objects("updates").map { update -> GTFSRealtimeStopUpdate in
                let observed = Date(timeIntervalSince1970: TimeInterval(update.int64("observedAt") ?? 0))
                return GTFSRealtimeStopUpdate(
                    feedID: update.string("feed"), entityID: "contract", tripID: update.string("tripId"),
                    stopID: update.optionalString("stopId"), stopSequence: update.int64("stopSequence").map { Int($0) },
                    serviceDate: update.int64("serviceDate").map { Int($0) },
                    predicted: update.int64("predicted").map { Date(timeIntervalSince1970: TimeInterval($0)) },
                    delaySeconds: update.int64("delaySeconds").map { Int($0) }, observedAt: observed,
                    expiresAt: Date(timeIntervalSince1970: TimeInterval(update.int64("expiresAt") ?? 0))
                )
            }
            let timing = GTFSRealtimeJoin.timing(reference, stationID: "contract", trip: trip, updates: updates, searchDay: now, now: now)
            guard let expected = item.object("expected") else {
                XCTAssertNil(timing, name)
                continue
            }
            let found = try XCTUnwrap(timing, name)
            XCTAssertEqual(Int64(found.delayMinutes), expected.int64("delayMinutes"), name)
            XCTAssertEqual(LiveRailTime.hhmm(found.expected), expected.string("expected"), name)
        }
    }

    func testTheCityFeedsOfRomeAndMestre() throws {
        for item in try RealtimeContract.cases("urban") {
            let name = item.string("name")
            let feed = try XCTUnwrap(UrbanRealtimeFeeds.all.first { $0.id == item.string("feed") }, name)
            let routes = Set(item.strings("routes"))
            let fetchedAt = Date(timeIntervalSince1970: TimeInterval(item.int64("fetchedAt") ?? 0))
            let snapshot = try XCTUnwrap(
                UrbanRealtimeDecoder.decode(try RealtimeContract.data(item.string("file")), feed: feed, fetchedAt: fetchedAt) { routes.contains($0) },
                name
            )
            XCTAssertEqual(Int64(snapshot.observedAt.timeIntervalSince1970), item.int64("observedAt"), name)
            if let trips = item.object("trips") {
                XCTAssertEqual(Set(snapshot.trips.keys), Set(trips.keys), name)
                for key in trips.keys {
                    let expected = try XCTUnwrap(trips.object(key), name)
                    let actual = try XCTUnwrap(snapshot.trips[key]?.first, name)
                    XCTAssertEqual(snapshot.trips[key]?.count, 1, name)
                    XCTAssertEqual(actual.startDate.map { Int64($0) }, expected.int64("startDate"), name)
                    XCTAssertEqual(actual.cancelled, expected.bool("cancelled"), name)
                    XCTAssertEqual(actual.stops.map(\.stopID), expected.strings("stops"), name)
                }
            } else {
                XCTAssertEqual(Set(snapshot.trips.keys), Set(item.strings("tripIds")), name)
            }
            for entry in item.objects("expected") {
                let scheduled = Date(timeIntervalSince1970: TimeInterval(entry.int64("scheduled") ?? 0))
                let actual = snapshot.trip(entry.string("trip"), serviceDate: Int(entry.int64("serviceDate") ?? 0))?
                    .expected(at: entry.string("stop"), scheduled: scheduled, departure: entry.bool("departure"))
                guard let result = entry.object("result") else {
                    XCTAssertNil(actual, name)
                    continue
                }
                XCTAssertEqual(actual?.expected.map { Int64($0.timeIntervalSince1970) }, result.int64("time"), name)
                XCTAssertEqual(actual?.cancelled, result.bool("cancelled"), name)
            }
        }
        XCTAssertEqual(UrbanRealtimeFeeds.forBuild(personalSources: false, packs: ["roma-mobilita-gtfs"]).map(\.id), [])
        XCTAssertEqual(UrbanRealtimeFeeds.forBuild(personalSources: true, packs: ["roma-mobilita-gtfs"]).map(\.id), ["roma-mobilita-gtfs-rt"])
    }

    func testAServiceIdentifierKeepsTheColonsOfItsTrip() throws {
        let reference = try XCTUnwrap(TimetableServiceReference(serviceID: "sncf-gtfs:OCESN:TER:FR::87756882:16:1503:20261123:36000"))
        XCTAssertEqual(reference.trip, TimetableTripKey(sourceID: "sncf-gtfs", tripID: "OCESN:TER:FR::87756882:16:1503"))
        XCTAssertEqual(reference.serviceDate, 20_261_123)
        XCTAssertEqual(reference.seconds, 36_000)
        XCTAssertNil(TimetableServiceReference(serviceID: "live:9558:2026"))
        XCTAssertNil(TimetableServiceReference(serviceID: "sncf-gtfs::20261123:36000"))
    }

    func testLimitsAndMalformedLengthsAreNoData() throws {
        // A feed past the byte limit, an entity past the entity limit, a string past the text limit.
        let tight = GTFSRealtimeDecoder.Limits(maximumBytes: 8, maximumEntities: 1, maximumStopTimeUpdatesPerTrip: 1, maximumTextBytes: 3)
        let header = try RealtimeContract.hex("0a050a03322e30")
        XCTAssertNotNil(GTFSRealtimeDecoder.decode(header, limits: tight))
        XCTAssertNil(GTFSRealtimeDecoder.decode(header + Data([0x12, 0x03, 0x0a, 0x01, 0x61]), limits: tight), "past the bytes")
        var wide = tight
        wide.maximumBytes = 1_000
        XCTAssertNotNil(GTFSRealtimeDecoder.decode(header + Data([0x12, 0x03, 0x0a, 0x01, 0x61]), limits: wide))
        XCTAssertNil(GTFSRealtimeDecoder.decode(header + Data([0x12, 0x03, 0x0a, 0x01, 0x61, 0x12, 0x03, 0x0a, 0x01, 0x62]), limits: wide), "entities")
        XCTAssertNil(GTFSRealtimeDecoder.decode(header + Data([0x12, 0x06, 0x0a, 0x04, 0x61, 0x62, 0x63, 0x64]), limits: wide), "text")
    }
}

/// The cases of gtfs-realtime-v1.json and the readers they need.
enum RealtimeContract {
    static var repositoryRoot: URL { NoticeContract.repositoryRoot }

    static func data(_ path: String) throws -> Data {
        try Data(contentsOf: repositoryRoot.appendingPathComponent(path))
    }

    static func cases(_ section: String) throws -> [ContractObject] {
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data("contracts/shared-rules/v1/gtfs-realtime-v1.json")) as? [String: Any])
        return try XCTUnwrap(root[section] as? [[String: Any]], section).map(ContractObject.init)
    }

    static func hex(_ text: String) throws -> Data {
        var bytes: [UInt8] = []
        var index = text.startIndex
        while index < text.endIndex {
            let next = try XCTUnwrap(text.index(index, offsetBy: 2, limitedBy: text.endIndex))
            bytes.append(try XCTUnwrap(UInt8(text[index..<next], radix: 16)))
            index = next
        }
        return Data(bytes)
    }

    /// "HH:MM:SS" (hours past 23 allowed) as GTFS seconds.
    static func seconds(_ time: String) -> Int? {
        let parts = time.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return parts[0] * 3_600 + parts[1] * 60 + parts[2]
    }

    /// The bindings' enum names.
    static func name(_ value: GTFSRealtimeTripRelationship) -> String {
        switch value {
        case .scheduled: return "SCHEDULED"
        case .added: return "ADDED"
        case .unscheduled: return "UNSCHEDULED"
        case .canceled: return "CANCELED"
        case .replacement: return "REPLACEMENT"
        case .duplicated: return "DUPLICATED"
        case .deleted: return "DELETED"
        case .new: return "NEW"
        }
    }

    static func name(_ value: GTFSRealtimeStopRelationship) -> String {
        switch value {
        case .scheduled: return "SCHEDULED"
        case .skipped: return "SKIPPED"
        case .noData: return "NO_DATA"
        case .unscheduled: return "UNSCHEDULED"
        }
    }

    static func assertEvent(_ actual: GTFSRealtimeStopTimeEvent?, _ expected: ContractObject?, _ name: String) {
        XCTAssertEqual(actual == nil, expected == nil, name)
        guard let actual, let expected else { return }
        XCTAssertEqual(actual.delay.map { Int64($0) }, expected.int64("delay"), name)
        XCTAssertEqual(actual.time, expected.int64("time"), name)
    }
}

/// A JSON object of a contract file with typed readers; JSON null and missing keys read as nil.
struct ContractObject {
    let values: [String: Any]

    var keys: [String] { Array(values.keys) }

    func string(_ key: String) -> String { values[key] as? String ?? "" }
    func optionalString(_ key: String) -> String? { values[key] as? String }
    func bool(_ key: String) -> Bool { (values[key] as? Bool) ?? false }
    func int64(_ key: String) -> Int64? { (values[key] as? NSNumber)?.int64Value }
    func strings(_ key: String) -> [String] { values[key] as? [String] ?? [] }
    func object(_ key: String) -> ContractObject? { (values[key] as? [String: Any]).map(ContractObject.init) }
    func objects(_ key: String) -> [ContractObject] { (values[key] as? [[String: Any]] ?? []).map(ContractObject.init) }
}
