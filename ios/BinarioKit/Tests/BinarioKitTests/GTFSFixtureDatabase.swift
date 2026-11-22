import CSQLite
import CZlib
import Foundation
import XCTest
@testable import BinarioKit

/// A synthetic timetable index in the schema of the bundled one (`userVersion` 2), with any packs:
/// the base index with border subsets, or a per-country index of one pack. Every service runs daily
/// from 2024-03-30 to 2024-04-02; every agency keeps Rome's time zone.
final class GTFSFixtureDatabase {
    struct Call {
        let stop: Int64
        let seconds: Int
        var pickupType = 0
        var dropOffType = 0
    }

    let url: URL
    private var database: OpaquePointer?

    /// `packs`: id, source and last service day (yyyymmdd) of each pack, in ingestion order.
    init(
        packs: [(id: Int64, sourceID: String, lastServiceDate: Int)],
        fingerprint: String = String(repeating: "a", count: 64)
    ) throws {
        url = FileManager.default.temporaryDirectory.appendingPathComponent("BinarioForeign-\(UUID().uuidString).sqlite")
        guard sqlite3_open(url.path, &database) == SQLITE_OK else { throw GTFSFixtureError.sqlite }
        try execute(Self.schema)
        try execute("PRAGMA application_id = \(SQLiteGTFSJourneyRepository.applicationID); PRAGMA user_version = \(SQLiteGTFSJourneyRepository.userVersion);")
        try execute("""
            INSERT INTO metadata VALUES ('schema_version', '\(SQLiteGTFSJourneyRepository.userVersion)');
            INSERT INTO metadata VALUES ('input_fingerprint_sha256', '\(fingerprint)');
            INSERT INTO metadata VALUES ('source_generated_at', '2024-03-30');
            INSERT INTO metadata VALUES ('row_counts_json', '{}');
            """)
        for pack in packs {
            let id = pack.id
            try execute("""
                INSERT INTO pack VALUES (\(id), '\(pack.sourceID)', 'Feed \(pack.sourceID)', \(pack.lastServiceDate));
                INSERT INTO agency VALUES (\(id), \(id), 'agency-\(id)', 'Rail \(pack.sourceID)', 'Europe/Rome');
                INSERT INTO route VALUES (\(id), \(id), \(id), 'route-\(id)', 'R', NULL, 2);
                INSERT INTO service VALUES (\(id), \(id), 'daily-\(id)', 20240330, 4, X'0F');
                """)
        }
    }

    deinit {
        close()
        try? FileManager.default.removeItem(at: url)
    }

    /// Closes the connection: the file is then complete on disk, for hashing or compressing.
    func close() {
        if let database { sqlite3_close(database) }
        database = nil
    }

    func insertStop(id: Int64, pack: Int64, externalID: String, name: String, catalogStationID: String?) throws {
        let catalog = catalogStationID.map { "'\($0)'" } ?? "NULL"
        try execute("INSERT INTO stop VALUES (\(id), \(pack), '\(externalID)', '\(name)', NULL, NULL, \(catalog), NULL, NULL);")
    }

    /// A trip of `pack` (whose route and service share its id) calling at `calls` in order.
    func insertTrip(id: Int64, pack: Int64, externalID: String, number: String, calls: [Call]) throws {
        var sql = "INSERT INTO trip VALUES (\(id), \(pack), \(pack), \(pack), '\(externalID)', NULL, '\(number)', NULL, NULL);"
        var sequence = 1
        for call in calls {
            sql += "INSERT INTO stop_time VALUES (\(id), \(sequence), \(call.stop), \(call.seconds), \(call.seconds), \(call.pickupType), \(call.dropOffType));"
            sequence += 1
        }
        try execute(sql)
    }

    func execute(_ sql: String) throws {
        guard let database, sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else { throw GTFSFixtureError.sqlite }
    }

    /// The closed file's bytes.
    func bytes() throws -> Data {
        close()
        return try Data(contentsOf: url)
    }

    static func zlibCompress(_ data: Data) throws -> Data {
        var output = Data(count: Int(compressBound(uLong(data.count))))
        var outputLength = uLongf(output.count)
        let status = output.withUnsafeMutableBytes { destination in
            data.withUnsafeBytes { source in
                compress2(
                    destination.bindMemory(to: Bytef.self).baseAddress,
                    &outputLength,
                    source.bindMemory(to: Bytef.self).baseAddress,
                    uLong(data.count),
                    Z_BEST_COMPRESSION
                )
            }
        }
        guard status == Z_OK else { throw GTFSFixtureError.zlib }
        output.count = Int(outputLength)
        return output
    }

    /// Pins in the shared schema with one downloadable pack whose artifact is `compressed` of
    /// `database`, served at `served` and complete in the border zone at `zone`.
    static func pins(
        sourceID: String = "oebb-gtfs",
        country: String = "AT",
        lastServiceDate: String = "2024-12-31",
        served: [String],
        zone: [String],
        compressed: Data,
        database: Data,
        fingerprint: String = String(repeating: "a", count: 64)
    ) throws -> ForeignSchedulePins {
        let sha = SHA256Digest.hex(compressed)
        let tag = "gtfs-ios-\(sourceID)-\(sha.prefix(12))"
        let asset = "\(sourceID).sqlite.zlib"
        let object: [String: Any] = [
            "schemaVersion": 1,
            "generatedAt": "2024-03-30",
            "repository": "fz1101hub/binario",
            "borderZone": ["radiusKm": 35, "tripRule": "whole-trips-touching-zone"] as [String: Any],
            "packs": [[
                "sourceId": sourceID,
                "country": country,
                "lastServiceDate": lastServiceDate,
                "fullServiceUntil": lastServiceDate,
                "servedStationIds": served,
                "zoneStationIds": zone,
                "borderOnly": true,
                "android": NSNull(),
                "ios": [
                    "tag": tag,
                    "asset": asset,
                    "url": ForeignSchedulePins.releaseDownloadPrefix + tag + "/" + asset,
                    "sizeBytes": compressed.count,
                    "sha256": sha,
                    "database": [
                        "sha256": SHA256Digest.hex(database),
                        "sizeBytes": database.count,
                        "userVersion": Int(SQLiteGTFSJourneyRepository.userVersion),
                        "applicationId": Int(SQLiteGTFSJourneyRepository.applicationID),
                    ] as [String: Any],
                    "inputFingerprintSha256": fingerprint,
                ] as [String: Any],
            ] as [String: Any]],
        ]
        return try ForeignSchedulePins(data: JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]))
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

enum GTFSFixtureError: Error { case sqlite, zlib }
